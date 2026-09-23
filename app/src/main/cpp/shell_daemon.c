/*
 * shell_daemon.c — persistentní shell UID (2000) daemon pro NetHunter
 *
 * Obdoba su_daemon.c, ale běží jako **shell (uid 2000)** místo root.
 * Spustí se JEDNOU přes `adb shell` (wireless debugging), pak drží
 * do rebootu (jako adb démon) a přijímá příkazy přes TCP 127.0.0.1:<port>.
 *
 * Aplikace (app UID) se připojí, pošle token + příkaz a dostane zpět
 * stdout/stderr/exit_code. Příkazy běží pod uid 2000 → stejná práva jako
 * Shizuku/adb (`pm`, `settings`, `dumpsys`, `cmd package install`, …).
 *
 * PROČ TCP A NE UNIX SOCKET:
 *   su_daemon používá UNIX socket ve filesDir — může tam, protože běží jako
 *   root. Shell UID 2000 do app filesDir (`rwx------` u0_aXXX) psát NESMÍ.
 *   TCP loopback (127.0.0.1) obchází filesystem permissions: bind/connect
 *   na high port na localhostu smí jakákoli appka i shell.
 *
 * BEZPEČNOST:
 *   - Bind jen na 127.0.0.1 (ne 0.0.0.0) — nedostupné z LAN.
 *   - Token (128 hex znaků) — bez něj daemon příkaz neprovede.
 *     Token vygeneruje aplikace při prvním startu a předá ho:
 *       * daemonu jako `--token=<hex>` (nebo `--token-file=<path>`)
 *       * sobě má v SharedPreferences (viz ShellDaemonManager.kt)
 *     Guest ho čte z `/mnt/app/shell_daemon.token` (bind filesDir → /mnt/app).
 *   - Fork-per-connection (parent hned accept), POLLHUP na client socketu
 *     → SIGKILL command childa (nedrží se zbytečně visící spojení).
 *
 * PROTOKOL (binární, NUL-separated + length-prefix):
 *   Request:
 *     [uint32 token_len][token bytes]
 *     [uint32 cmd_len][cmd bytes]           (spouští se přes /system/bin/sh -c)
 *     [uint32 cwd_len][cwd bytes]           (0 = zdědit /)
 *   Response:
 *     [int32  exit_code]
 *     [uint32 out_len][stdout bytes]
 *     [uint32 err_len][stderr bytes]
 *   Chyba protokolu: exit_code = -1, stderr = popis.
 *
 * USAGE:
 *   shell_daemon [--port=N] [--token=HEX] [--token-file=PATH] [--no-fork]
 *
 * EXIT KÓDY:
 *   0   normální ukončení (jen na signál)
 *   1   bind/listen selhal
 *   2   žádný token (ani --token, ani --token-file)
 */

#define _GNU_SOURCE

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <fcntl.h>
#include <stdint.h>
#include <time.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <termios.h>

#define DEFAULT_PORT 13341
#define DEFAULT_TOKEN_FILE "/data/local/tmp/shell_daemon.token"
#define MAX_TOKEN 256
#define CMD_BUF 65536
#define OUT_BUF 262144
#define MAX_ARGS 64

/* Protokol: prvni uint32 je magic, pak uint8 mode. */
#define SH_MAGIC 0x53484C4Cu   /* "SHLL" */
#define SH_MODE_EXEC   0u      /* jednorazovy prikaz (stdout/stderr/exit) */
#define SH_MODE_ATTACH 1u      /* interaktivni PTY shell pod uid 2000 */
#define SH_MODE_INSTALL 2u     /* streamovany install: cmd package install -S */
#define SH_MODE_STOP   3u      /* zastav daemona (bez adb; pouziva ashell adb stop) */
#define SH_MODE_KILL_SESSION 4u /* zabij pojmenovanou perzistentni session */

static char g_token[MAX_TOKEN] = {0};
static int g_port = DEFAULT_PORT;
/* Cesta k PID file — potrebuje ji SIGTERM handler pro uklid. */
static char g_pid_path[512] = "/data/local/tmp/shelldaemon.pid";

/* ── PTY helpers (pro attach rezim) ─────────────────────────────────────── */

static int open_pty(int *master_fd, int *slave_fd) {
    int m = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (m < 0) return -1;
    if (grantpt(m) < 0) { close(m); return -1; }
    if (unlockpt(m) < 0) { close(m); return -1; }
    char *name = ptsname(m);
    if (name == NULL) { close(m); return -1; }
    int s = open(name, O_RDWR | O_NOCTTY);
    if (s < 0) { close(m); return -1; }
    *master_fd = m;
    *slave_fd = s;
    return 0;
}

static void set_nonblock(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

/* Cte jen blokujici cteni pres nonblocking fd s poll fallbackem. */
static int poll_read(int fd, void *buf, size_t len, int timeout_ms) {
    struct pollfd p = { .fd = fd, .events = POLLIN };
    int pr = poll(&p, 1, timeout_ms);
    if (pr <= 0) return pr;
    if (p.revents & (POLLIN | POLLHUP | POLLERR)) {
        ssize_t n = read(fd, buf, len);
        return (int)n;
    }
    return 0;
}

/* ── I/O helpers ────────────────────────────────────────────────────────── */

static int write_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_all(int fd, void *buf, size_t len) {
    char *p = (char *)buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

/* Přečti [uint32 len][bytes] do bufferu; max_len včetně NUL. Vrací 0/-1. */
static int read_blob(int fd, char *out, size_t max_len) {
    uint32_t len = 0;
    if (read_all(fd, &len, sizeof(len)) < 0) return -1;
    if (len >= max_len) {
        /* přečti a zahoď (nedovol buffer overflow) */
        char tmp[4096];
        size_t remain = len;
        while (remain > 0) {
            size_t chunk = remain < sizeof(tmp) ? remain : sizeof(tmp);
            if (read_all(fd, tmp, chunk) < 0) return -1;
            remain -= chunk;
        }
        return -1;
    }
    if (len > 0) {
        if (read_all(fd, out, len) < 0) return -1;
    }
    out[len] = '\0';
    return 0;
}

static int write_blob(int fd, const char *data, size_t len) {
    uint32_t u = (uint32_t)len;
    if (write_all(fd, &u, sizeof(u)) < 0) return -1;
    if (len > 0 && write_all(fd, data, len) < 0) return -1;
    return 0;
}

/* ── Command execution ──────────────────────────────────────────────────── */

struct cmd_result {
    int exit_code;
    char *out;
    size_t out_len;
    char *err;
    size_t err_len;
};

/* Spustí `/system/bin/sh -c <cmd>` pod aktuálním (shell) UID, zachytí
 * stdout/stderr odděleně. Volající uvolní res->out/res->err. */
static void run_command(const char *cmd, const char *cwd, struct cmd_result *res) {
    memset(res, 0, sizeof(*res));
    res->exit_code = -1;

    int out_pipe[2], err_pipe[2];
    if (pipe(out_pipe) < 0 || pipe(err_pipe) < 0) {
        res->err = strdup("pipe() selhal");
        res->err_len = strlen(res->err);
        return;
    }

    pid_t pid = fork();
    if (pid < 0) {
        res->err = strdup("fork() selhal");
        res->err_len = strlen(res->err);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        return;
    }

    if (pid == 0) {
        /* child */
        close(out_pipe[0]);
        close(err_pipe[0]);
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[1]);
        close(err_pipe[1]);
        /* stdin → /dev/null (žádné interaktivní blokování) */
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) { dup2(devnull, STDIN_FILENO); close(devnull); }

        if (cwd && cwd[0] != '\0') chdir(cwd);

        /* Reset signálů, ať sh dědí čistý stav. */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        /* fallback: některá zařízení mají /bin/sh (symlink) */
        execl("/bin/sh", "sh", "-c", cmd, (char *)NULL);
        fprintf(stderr, "shell_daemon: nelze spustit sh: %s\n", strerror(errno));
        _exit(127);
    }

    /* parent */
    close(out_pipe[1]);
    close(err_pipe[1]);

    char *out = malloc(OUT_BUF);
    char *err = malloc(OUT_BUF);
    size_t out_len = 0, err_len = 0;
    if (!out || !err) {
        if (out) free(out);
        if (err) free(err);
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        res->err = strdup("malloc() selhal");
        res->err_len = strlen(res->err);
        return;
    }

    /* Non-blocking čtení z obou pip. */
    int ofd = out_pipe[0], efd = err_pipe[0];
    fcntl(ofd, F_SETFL, O_NONBLOCK);
    fcntl(efd, F_SETFL, O_NONBLOCK);
    int open_fds = 2;
    char buf[8192];

    while (open_fds > 0) {
        struct pollfd pfds[2];
        int nfds = 0;
        if (ofd >= 0) { pfds[nfds].fd = ofd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0; nfds++; }
        if (efd >= 0) { pfds[nfds].fd = efd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0; nfds++; }
        if (nfds == 0) break;
        int pr = poll(pfds, nfds, 200);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pr == 0) {
            /* timeout — zkontroluj, jestli dítě neběží zbytečně bez outputu */
            int st;
            if (waitpid(pid, &st, WNOHANG) == pid) {
                /* dočti zbytek */
                /* (řeší se v dalším průchodu, fds se zavřou až po EOF) */
                /* Pozn: po dokončení procesu je EOF na pipech → read = 0 */
                continue;
            }
            continue;
        }
        for (int i = 0; i < nfds; i++) {
            if (!(pfds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            int fd = pfds[i].fd;
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n > 0) {
                if (fd == ofd && out_len + (size_t)n < OUT_BUF) {
                    memcpy(out + out_len, buf, (size_t)n);
                    out_len += (size_t)n;
                } else if (fd == efd && err_len + (size_t)n < OUT_BUF) {
                    memcpy(err + err_len, buf, (size_t)n);
                    err_len += (size_t)n;
                }
            } else if (n == 0) {
                /* EOF */
                close(fd);
                if (fd == ofd) { ofd = -1; open_fds--; }
                if (fd == efd) { efd = -1; open_fds--; }
            } else {
                if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                close(fd);
                if (fd == ofd) { ofd = -1; open_fds--; }
                if (fd == efd) { efd = -1; open_fds--; }
            }
        }
    }

    int status = 0;
    waitpid(pid, &status, 0);
    res->exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : (128 + WTERMSIG(status));
    res->out = out; res->out_len = out_len;
    res->err = err; res->err_len = err_len;
}

/* ── Per-connection workers ─────────────────────────────────────────────── */

/* Jednorazovy prikaz: precti cmd+cwd, spust, vrat exit/out/err. */
static void handle_exec(int client_fd) {
    char *cmd = malloc(CMD_BUF);
    char *cwd = malloc(4096);
    if (!cmd || !cwd) {
        if (cmd) free(cmd);
        if (cwd) free(cwd);
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "alokace selhala", 14);
        write_blob(client_fd, "", 0);
        return;
    }

    if (read_blob(client_fd, cmd, CMD_BUF) < 0 ||
        read_blob(client_fd, cwd, 4096) < 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "", 0);
        write_blob(client_fd, "protokol: neocekavany konec", 26);
        free(cmd); free(cwd);
        return;
    }

    fprintf(stderr, "[shell_daemon] exec: %s (cwd=%s)\n", cmd, cwd[0] ? cwd : "/");

    struct cmd_result res;
    run_command(cmd, cwd, &res);

    int32_t rc = (int32_t)res.exit_code;
    if (write_all(client_fd, &rc, sizeof(rc)) < 0) goto done;
    if (write_blob(client_fd, res.out ? res.out : "", res.out_len) < 0) goto done;
    if (write_blob(client_fd, res.err ? res.err : "", res.err_len) < 0) goto done;

done:
    if (res.out) free(res.out);
    if (res.err) free(res.err);
    free(cmd);
    free(cwd);
}

/* Interaktivni PTY shell pod uid 2000.
 *
 * Po ack (int32 0) je tok: klient→daemon FRAMOVANY ([u8 type][u32 len][payload]),
 * daemon→klient RAW vystup PTY.
 *
 *   type 1 = stdin data
 *   type 2 = resize (payload [u32 rows][u32 cols])
 *   type 3 = EOF/close (klient uz nebude nic posilat)
 */
static void handle_attach(int client_fd, const char *cmd) {
    int master_fd = -1, slave_fd = -1;
    if (open_pty(&master_fd, &slave_fd) < 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "nelze otevrit PTY", 17);
        return;
    }

    /* Vychozi velikost; klient posle resize hned po pripojeni. */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = 24; ws.ws_col = 80;
    ioctl(slave_fd, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "fork selhal", 11);
        close(master_fd); close(slave_fd);
        return;
    }
    if (pid == 0) {
        /* child: nove session + PTY jako kontrolni terminal */
        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > STDERR_FILENO) close(slave_fd);
        close(master_fd);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        if (cmd != NULL && cmd[0] != '\0') {
            execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
            execl("/bin/sh", "sh", "-c", cmd, (char *)NULL);
        } else {
            /* interaktivni login shell */
            execl("/system/bin/sh", "sh", "-i", (char *)NULL);
            execl("/bin/sh", "sh", "-i", (char *)NULL);
        }
        fprintf(stderr, "shell_daemon attach: nelze spustit sh: %s\n", strerror(errno));
        _exit(127);
    }

    /* parent = most */
    close(slave_fd);
    set_nonblock(master_fd);

    int32_t ack = 0;
    if (write_all(client_fd, &ack, sizeof(ack)) < 0) {
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        close(master_fd);
        return;
    }

    int client_open = 1;
    char buf[16384];
    while (1) {
        if (waitpid(pid, NULL, WNOHANG) == pid) break;

        struct pollfd pfds[2];
        int nfds = 0;
        pfds[nfds].fd = master_fd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0; nfds++;
        if (client_open) {
            pfds[nfds].fd = client_fd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0; nfds++;
        }
        int pr = poll(pfds, nfds, 500);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pr == 0) continue;

        /* PTY → klient (raw) */
        if (pfds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(master_fd, buf, sizeof(buf));
            if (n > 0) {
                if (write_all(client_fd, buf, (size_t)n) < 0) break;
            } else if (n == 0) {
                break;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                break;
            }
        }

        /* klient → PTY (framovane) */
        if (client_open && nfds > 1 && (pfds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            uint8_t type = 0;
            ssize_t n = read(client_fd, &type, 1);
            if (n <= 0) {
                client_open = 0;
                kill(pid, SIGKILL);
                break;
            }
            uint32_t len = 0;
            if (read_all(client_fd, &len, sizeof(len)) < 0) { client_open = 0; break; }
            if (len > sizeof(buf)) { client_open = 0; break; }
            if (len > 0 && read_all(client_fd, buf, len) < 0) { client_open = 0; break; }
            if (type == 1) {
                size_t off = 0;
                while (off < len) {
                    ssize_t w = write(master_fd, buf + off, len - off);
                    if (w < 0) {
                        if (errno == EINTR) continue;
                        break;
                    }
                    off += (size_t)w;
                }
            } else if (type == 2 && len >= 8) {
                uint32_t rows = 0, cols = 0;
                memcpy(&rows, buf, 4);
                memcpy(&cols, buf + 4, 4);
                struct winsize nws;
                memset(&nws, 0, sizeof(nws));
                nws.ws_row = (unsigned short)rows;
                nws.ws_col = (unsigned short)cols;
                ioctl(master_fd, TIOCSWINSZ, &nws);
                kill(pid, SIGWINCH);
            } else if (type == 3) {
                client_open = 0;
                kill(pid, SIGKILL);
                break;
            }
        }
    }

    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
    close(master_fd);
}

/* ── Perzistentní pojmenované session (přežijí detach/odpojení) ───────────
 * Model: každé pojmenované session má vlastní supervisor proces, který drží
 * PTY + shell child NEZÁVISLE na jednotlivých klientských spojeních. Klienti
 * se k němu připojují/odpojují přes control UNIX socket
 * (/data/local/tmp/.shd_sess_<name>.sock) a předávají si svůj síťový
 * client_fd přes SCM_RIGHTS — to je tady bezpečné, protože OBA konce jsou
 * hostitelské procesy (uid 2000, mimo PRoot ptrace), na rozdíl od tmux
 * client/server UVNITŘ PRootu, kde přesně tohle (SCM_RIGHTS mezi dvěma
 * ptrace-sledovanými procesy) spolehlivě nefunguje — viz AGENTS.md sekce 11
 * "PRoot verze — tmux/multiplexery". Tenhle daemon už běží mimo PRoot,
 * takže stejný mechanismus tady funguje správně (ověřeno: su_daemon už
 * SCM_RIGHTS host-only dělá roky pro guest_fd handoff bez problémů).
 *
 * Omezení v1: jeden aktivní klient na session (žádné tmux-style zrcadlení
 * více připojených klientů najednou) — druhé připojení čeká ve frontě
 * backlogu, dokud se první neodpojí. */

#define SESSION_SOCK_DIR "/data/local/tmp"
#define SESSION_NAME_MAX 64
#define SESSION_CTL_ATTACH 1u
#define SESSION_CTL_KILL   2u

/* Sanitizuje jméno session do bezpečné cesty (jen [a-zA-Z0-9_-]). */
static int session_sock_path(char *out, size_t outlen, const char *name) {
    if (name == NULL || name[0] == '\0') return -1;
    char safe[SESSION_NAME_MAX];
    size_t j = 0;
    for (size_t i = 0; name[i] != '\0' && j < sizeof(safe) - 1; i++) {
        char c = name[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-') {
            safe[j++] = c;
        }
    }
    safe[j] = '\0';
    if (j == 0) return -1;
    int n = snprintf(out, outlen, "%s/.shd_sess_%s.sock", SESSION_SOCK_DIR, safe);
    return (n > 0 && (size_t)n < outlen) ? 0 : -1;
}

/* Pošle 1 řídicí byte + volitelný fd (SCM_RIGHTS) přes UNIX socket. */
static int send_ctl_fd(int sock, uint8_t ctl_type, int fd_to_send) {
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    struct iovec io = { .iov_base = &ctl_type, .iov_len = 1 };
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    char control_buf[CMSG_SPACE(sizeof(int))];
    if (fd_to_send >= 0) {
        memset(control_buf, 0, sizeof(control_buf));
        msg.msg_control = control_buf;
        msg.msg_controllen = sizeof(control_buf);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &fd_to_send, sizeof(int));
        msg.msg_controllen = cmsg->cmsg_len;
    }
    return sendmsg(sock, &msg, 0) >= 0 ? 0 : -1;
}

/* Přijme 1 řídicí byte + volitelný fd (SCM_RIGHTS). *fd_out = -1, pokud
 * žádný fd nepřišel. Vrací -1 na chybu/EOF. */
static int recv_ctl_fd(int sock, uint8_t *ctl_type, int *fd_out) {
    *fd_out = -1;
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    struct iovec io = { .iov_base = ctl_type, .iov_len = 1 };
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    char control_buf[CMSG_SPACE(sizeof(int))];
    memset(control_buf, 0, sizeof(control_buf));
    msg.msg_control = control_buf;
    msg.msg_controllen = sizeof(control_buf);

    ssize_t n = recvmsg(sock, &msg, 0);
    if (n <= 0) return -1;

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg != NULL && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
        memcpy(fd_out, CMSG_DATA(cmsg), sizeof(int));
    }
    return 0;
}

/* Jeden cyklus relay smyčky mezi PTY master_fd a client_fd — stejná logika
 * jako v handle_attach, ale na rozdíl od ní VRACÍ místo zabití childa, když
 * klient odpojí/detachne. Volající (supervisor) rozhodne, jestli childa
 * zabít. Vrací: 0 = klient odešel/detachnul (child ŽIJE dál), 1 = child se
 * sám ukončil (waitpid trefil). */
static int relay_loop(int master_fd, int client_fd, pid_t pid) {
    char buf[16384];
    while (1) {
        if (waitpid(pid, NULL, WNOHANG) == pid) return 1;

        struct pollfd pfds[2];
        pfds[0].fd = master_fd; pfds[0].events = POLLIN; pfds[0].revents = 0;
        pfds[1].fd = client_fd; pfds[1].events = POLLIN; pfds[1].revents = 0;
        int pr = poll(pfds, 2, 500);
        if (pr < 0) { if (errno == EINTR) continue; return 0; }
        if (pr == 0) continue;

        if (pfds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(master_fd, buf, sizeof(buf));
            if (n > 0) {
                if (write_all(client_fd, buf, (size_t)n) < 0) return 0;
            } else if (n == 0) {
                return 0;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                return 0;
            }
        }

        if (pfds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            uint8_t type = 0;
            ssize_t n = read(client_fd, &type, 1);
            if (n <= 0) return 0; /* odpojení = detach, ne kill */
            uint32_t len = 0;
            if (read_all(client_fd, &len, sizeof(len)) < 0) return 0;
            if (len > sizeof(buf)) return 0;
            if (len > 0 && read_all(client_fd, buf, len) < 0) return 0;
            if (type == 1) {
                size_t off = 0;
                while (off < len) {
                    ssize_t w = write(master_fd, buf + off, len - off);
                    if (w < 0) {
                        if (errno == EINTR) continue;
                        break;
                    }
                    off += (size_t)w;
                }
            } else if (type == 2 && len >= 8) {
                uint32_t rows = 0, cols = 0;
                memcpy(&rows, buf, 4);
                memcpy(&cols, buf + 4, 4);
                struct winsize nws;
                memset(&nws, 0, sizeof(nws));
                nws.ws_row = (unsigned short)rows;
                nws.ws_col = (unsigned short)cols;
                ioctl(master_fd, TIOCSWINSZ, &nws);
                kill(pid, SIGWINCH);
            } else if (type == 3) {
                return 0; /* explicitní detach (drž session naživu) */
            }
        }
    }
}

/* Supervisor: drží PTY+shell child naživu napříč detach/reattach cykly.
 * Běží jako samostatný proces (fork z handle_attach_persistent), dokud
 * child sám neskončí nebo nepřijde SESSION_CTL_KILL. Volá se hned po
 * forku — nikdy se nevrací (_exit na konci). */
static void session_supervisor(const char *sock_path, const char *cmd, int ready_fd) {
    int master_fd = -1, slave_fd = -1;
    if (open_pty(&master_fd, &slave_fd) < 0) {
        close(ready_fd);
        _exit(1);
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = 24; ws.ws_col = 80;
    ioctl(slave_fd, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        close(master_fd); close(slave_fd);
        close(ready_fd);
        _exit(1);
    }
    if (pid == 0) {
        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > STDERR_FILENO) close(slave_fd);
        close(master_fd);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        if (cmd != NULL && cmd[0] != '\0') {
            execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
            execl("/bin/sh", "sh", "-c", cmd, (char *)NULL);
        } else {
            execl("/system/bin/sh", "sh", "-i", (char *)NULL);
            execl("/bin/sh", "sh", "-i", (char *)NULL);
        }
        fprintf(stderr, "shell_daemon session: nelze spustit sh: %s\n", strerror(errno));
        _exit(127);
    }
    close(slave_fd);
    set_nonblock(master_fd);

    int listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listen_fd < 0) { kill(pid, SIGKILL); waitpid(pid, NULL, 0); close(master_fd); close(ready_fd); _exit(1); }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);
    unlink(sock_path); /* stará/mrtvá session se stejným jménem */
    if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0 ||
        listen(listen_fd, 4) < 0) {
        kill(pid, SIGKILL); waitpid(pid, NULL, 0);
        close(master_fd); close(listen_fd); close(ready_fd);
        _exit(1);
    }
    chmod(sock_path, 0600);

    /* Signalizuj parentovi (handle_attach_persistent), že socket je hotový. */
    {
        uint8_t one = 1;
        write(ready_fd, &one, 1);
    }
    close(ready_fd);

    for (;;) {
        struct pollfd lp = { listen_fd, POLLIN, 0 };
        int pr = poll(&lp, 1, 1000);
        if (pr < 0) { if (errno == EINTR) continue; break; }

        if (waitpid(pid, NULL, WNOHANG) == pid) break; /* shell sám skončil */

        if (pr == 0) continue;

        int client_fd = accept(listen_fd, NULL, NULL);
        if (client_fd < 0) continue;

        uint8_t ctl = 0;
        int passed_fd = -1;
        if (recv_ctl_fd(client_fd, &ctl, &passed_fd) < 0) { close(client_fd); continue; }
        close(client_fd); /* control socket dál nepoužíváme */

        if (ctl == SESSION_CTL_KILL) {
            if (passed_fd >= 0) close(passed_fd);
            break;
        }
        if (ctl != SESSION_CTL_ATTACH || passed_fd < 0) {
            if (passed_fd >= 0) close(passed_fd);
            continue;
        }

        int child_exited = relay_loop(master_fd, passed_fd, pid);
        close(passed_fd);
        if (child_exited) break;
        /* jinak: klient detachnul, child žije dál — čekej na další accept() */
    }

    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
    close(master_fd);
    close(listen_fd);
    unlink(sock_path);
    _exit(0);
}

/* Handoff: připoj se na (existující nebo čerstvou) supervisor session
 * <name> a předej jí client_fd. Volá se z handle_client pro pojmenovaný
 * SH_MODE_ATTACH. Worker proces (volající) se po handoffu vrátí a je
 * normálně reapnut jako po jakémkoliv jiném spojení — supervisor běží
 * dál nezávisle (setsid, adoptovaný initem po skončení tohoto workera). */
static void handle_attach_persistent(int client_fd, const char *cmd, const char *name) {
    char sock_path[512];
    if (session_sock_path(sock_path, sizeof(sock_path), name) < 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "neplatne jmeno session", 23);
        return;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "socket() selhal", 16);
        return;
    }

    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        /* Session neexistuje (nebo mrtvý socket) — založ novou. */
        close(sock);
        int readyp[2];
        if (pipe(readyp) < 0) {
            int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "pipe() selhal", 13);
            return;
        }
        pid_t sup = fork();
        if (sup < 0) {
            close(readyp[0]); close(readyp[1]);
            int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "fork() selhal", 13);
            return;
        }
        if (sup == 0) {
            close(readyp[0]);
            close(client_fd);
            if (setsid() < 0) setpgid(0, 0);
            session_supervisor(sock_path, cmd, readyp[1]);
            _exit(0); /* nedosažitelné, session_supervisor sám _exit()uje */
        }
        close(readyp[1]);
        uint8_t ready = 0;
        struct pollfd rp = { readyp[0], POLLIN, 0 };
        int pr = poll(&rp, 1, 5000);
        int got_ready = (pr > 0) && (read(readyp[0], &ready, 1) == 1) && ready;
        close(readyp[0]);
        if (!got_ready) {
            int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "session supervisor se nespustil", 32);
            return;
        }

        sock = socket(AF_UNIX, SOCK_STREAM, 0);
        if (sock < 0 || connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            if (sock >= 0) close(sock);
            int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "pripojeni k nove session selhalo", 33);
            return;
        }
    }

    int32_t ack = 0;
    if (write_all(client_fd, &ack, sizeof(ack)) < 0) { close(sock); return; }

    send_ctl_fd(sock, SESSION_CTL_ATTACH, client_fd);
    close(sock);
}

/* Zabije pojmenovanou session (SH_MODE_KILL_SESSION). Fire-and-forget vůči
 * supervisoru — pokud session neexistuje, jen tiše ohlásíme OK (idempotentní). */
static void handle_kill_session(int client_fd, const char *name) {
    char sock_path[512];
    if (session_sock_path(sock_path, sizeof(sock_path), name) < 0) {
        int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "neplatne jmeno session", 23);
        return;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock >= 0 && connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
        send_ctl_fd(sock, SESSION_CTL_KILL, -1);
    }
    if (sock >= 0) close(sock);

    int32_t rc = 0;
    write_all(client_fd, &rc, sizeof(rc));
    write_blob(client_fd, "ok", 2);
}


/* Reaper pro worker child processes. Bez tohoto by každý fork-per-connection
 * worker po skončení zůstal jako zombie (parent ho nikdy nečeká) — přesně
 * tenhle problém měl su_daemon.c, opraveno identicky (viz su_daemon.c
 * sigchld_reaper). Worker si hned po forku přepne SIGCHLD na SIG_DFL, aby
 * mohl sám waitpid() svého command/PTY childa a získat exit kód. */
static void sigchld_reaper(int sig) {
    (void)sig;
    int saved_errno = errno;
    while (waitpid(-1, NULL, WNOHANG) > 0) { }
    errno = saved_errno;
}

/* Uklid PID file pri ukonceni daemona (SIGTERM z SH_MODE_STOP nebo rucni kill). */
static void sigterm_cleanup(int sig) {
    (void)sig;
    unlink(g_pid_path);
    _exit(0);
}

/* ── Streamovany install (jako adb install / INSTALL_STREAM) ──────────────
 *
 * adb otevre sluzbu `exec:cmd package install -S <size>` a posle APK po
 * socketu (raw bytes). My delame to same, jen bez adb: pres `cmd` binarku
 * (bezi pod uid 2000). Klient posle blob(cmd_args) + uint64(size) + APK.
 *
 * Odpoved: int32 exit + blob(stdout) + blob(stderr).
 */
static void handle_install(int client_fd) {
    char *args = malloc(CMD_BUF);
    if (!args) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "", 0);
        write_blob(client_fd, "alokace selhala", 14);
        return;
    }
    if (read_blob(client_fd, args, CMD_BUF) < 0) {
        free(args);
        return;
    }
    uint64_t size = 0;
    if (read_all(client_fd, &size, sizeof(size)) < 0) {
        free(args);
        return;
    }
    fprintf(stderr, "[shell_daemon] install: args='%s' size=%llu\n",
            args, (unsigned long long)size);

    /* Sestav prikaz: `cmd package install -S <size> [args]`. */
    char cmd[CMD_BUF];
    snprintf(cmd, sizeof(cmd), "cmd package install -S %llu %s",
             (unsigned long long)size, args);

    /* Dve pipe: stdin (APK) a stdout+stderr (vysledek). */
    int in_pipe[2];
    int out_pipe[2];
    if (pipe(in_pipe) < 0 || pipe(out_pipe) < 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "", 0);
        write_blob(client_fd, "pipe selhal", 11);
        free(args);
        return;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(in_pipe[0]); close(in_pipe[1]);
        close(out_pipe[0]); close(out_pipe[1]);
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "", 0);
        write_blob(client_fd, "fork selhal", 10);
        free(args);
        return;
    }
    if (pid == 0) {
        /* child: stdin = APK, stdout+stderr = out_pipe */
        dup2(in_pipe[0], STDIN_FILENO);
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(out_pipe[1], STDERR_FILENO);
        close(in_pipe[0]); close(in_pipe[1]);
        close(out_pipe[0]); close(out_pipe[1]);
        signal(SIGPIPE, SIG_DFL);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        execl("/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }

    /* parent: posli APK do childova stdin. */
    close(in_pipe[0]);
    close(out_pipe[1]);
    uint64_t sent = 0;
    char buf[65536];
    int write_err = 0;
    while (sent < size) {
        size_t want = sizeof(buf);
        if (size - sent < want) want = (size_t)(size - sent);
        ssize_t n = read(client_fd, buf, want);
        if (n <= 0) { write_err = 1; break; }
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(in_pipe[1], buf + off, (size_t)(n - off));
            if (w <= 0) { write_err = 1; break; }
            off += w;
        }
        if (write_err) break;
        sent += (uint64_t)n;
    }
    close(in_pipe[1]);

    /* cti stdout+stderr childa do bufferu */
    char *out = malloc(OUT_BUF);
    size_t out_len = 0;
    if (out) {
        ssize_t n;
        while ((n = read(out_pipe[0], buf, sizeof(buf))) > 0) {
            if (out_len + (size_t)n < OUT_BUF - 1) {
                memcpy(out + out_len, buf, (size_t)n);
                out_len += (size_t)n;
            }
        }
        out[out_len] = '\0';
    }
    close(out_pipe[0]);

    int status = 0;
    waitpid(pid, &status, 0);
    int exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;

    int32_t rc = (int32_t)exit_code;
    write_all(client_fd, &rc, sizeof(rc));
    if (write_err) {
        write_blob(client_fd, out ? out : "", out ? out_len : 0);
        write_blob(client_fd, "stream prerusen (klient zavrel?)", 32);
    } else {
        write_blob(client_fd, out ? out : "", out ? out_len : 0);
        write_blob(client_fd, "", 0);
    }
    if (out) free(out);
    free(args);
}

/* Rozhodne podle magic+mode; overi token; dispatchne. */
static void handle_client(int client_fd) {
    uint32_t magic = 0;
    if (read_all(client_fd, &magic, sizeof(magic)) < 0) return;
    if (magic != SH_MAGIC) {
        fprintf(stderr, "[shell_daemon] spatny magic 0x%08x\n", magic);
        return;
    }
    uint8_t mode = 0;
    if (read_all(client_fd, &mode, 1) < 0) return;

    char token[MAX_TOKEN] = {0};
    if (read_blob(client_fd, token, sizeof(token)) < 0) return;

    if (strcmp(token, g_token) != 0) {
        fprintf(stderr, "[shell_daemon] zamitnuto: neplatny token\n");
        if (mode == SH_MODE_EXEC) {
            int32_t rc = -1;
            write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "", 0);
            write_blob(client_fd, "neplatny token", 13);
        } else {
            int32_t rc = -1;
            write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "neplatny token", 13);
        }
        return;
    }

    if (mode == SH_MODE_ATTACH) {
        char *cmd = malloc(CMD_BUF);
        if (!cmd) { int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc)); write_blob(client_fd, "alokace selhala", 14); return; }
        if (read_blob(client_fd, cmd, CMD_BUF) < 0) { free(cmd); return; }
        char session_name[SESSION_NAME_MAX] = {0};
        /* Prazdny blob (delka 0) = efemerni session (puvodni chovani: kill
         * na detach). Klient (run_attach_client) posila vzdy — viz nize. */
        if (read_blob(client_fd, session_name, sizeof(session_name)) < 0) { free(cmd); return; }
        if (session_name[0] != '\0') {
            fprintf(stderr, "[shell_daemon] attach session=%s (cmd=%s)\n",
                    session_name, cmd[0] ? cmd : "<interaktivni>");
            handle_attach_persistent(client_fd, cmd, session_name);
        } else {
            fprintf(stderr, "[shell_daemon] attach (cmd=%s)\n", cmd[0] ? cmd : "<interaktivni>");
            handle_attach(client_fd, cmd);
        }
        free(cmd);
        return;
    }

    if (mode == SH_MODE_KILL_SESSION) {
        char session_name[SESSION_NAME_MAX] = {0};
        if (read_blob(client_fd, session_name, sizeof(session_name)) < 0 || session_name[0] == '\0') {
            int32_t rc = -1; write_all(client_fd, &rc, sizeof(rc));
            write_blob(client_fd, "chybi jmeno session", 20);
            return;
        }
        fprintf(stderr, "[shell_daemon] kill-session=%s\n", session_name);
        handle_kill_session(client_fd, session_name);
        return;
    }

    if (mode == SH_MODE_INSTALL) {
        handle_install(client_fd);
        return;
    }

    if (mode == SH_MODE_STOP) {
        /* Klient (ashell adb stop) chce daemona ukoncit — bez adb.
         * Worker nemuze jen _exit (to by zabilo jen sebe), musi ukoncit
         * parenta (skutecneho daemona). Posle mu SIGTERM; parentuv handler
         * uklidi PID file a skonci. Odpovime jeste predtim, aby klient
         * dostal potvrzeni. */
        fprintf(stderr, "[shell_daemon] STOP pozadavek — ukoncuji daemona\n");
        int32_t rc = 0;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "stopping", 8);
        write_blob(client_fd, "", 0);
        pid_t parent = getppid();
        if (parent > 1) kill(parent, SIGTERM);
        return;
    }

    handle_exec(client_fd);
}

/* ── Attach klient (--attach) ───────────────────────────────────────────── */

static int run_kill_session_client(const char *host, int port, const char *session_name) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        fprintf(stderr, "shell_daemon: spatna adresa %s\n", host);
        close(fd); return 1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "shell_daemon: nelze se pripojit na %s:%d: %s\n", host, port, strerror(errno));
        close(fd); return 1;
    }

    uint32_t magic = SH_MAGIC;
    uint8_t mode = SH_MODE_KILL_SESSION;
    if (write_all(fd, &magic, sizeof(magic)) < 0 ||
        write_all(fd, &mode, 1) < 0 ||
        write_blob(fd, g_token, strlen(g_token)) < 0 ||
        write_blob(fd, session_name, strlen(session_name)) < 0) {
        fprintf(stderr, "shell_daemon: zapis hlavicky selhal\n");
        close(fd); return 1;
    }

    int32_t rc = -1;
    read_all(fd, &rc, sizeof(rc));
    char msg[256] = {0};
    uint32_t mlen = 0;
    if (read_all(fd, &mlen, sizeof(mlen)) == 0 && mlen < sizeof(msg)) {
        read_all(fd, msg, mlen);
    }
    close(fd);
    printf("[shell_daemon] kill-session %s: %s\n", session_name, msg[0] ? msg : (rc == 0 ? "ok" : "chyba"));
    return rc == 0 ? 0 : 1;
}

static int run_attach_client(const char *host, int port, const char *cmd, const char *session_name) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        fprintf(stderr, "shell_daemon: spatna adresa %s\n", host);
        close(fd); return 1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "shell_daemon: nelze se pripojit na %s:%d: %s\n", host, port, strerror(errno));
        close(fd); return 1;
    }

    uint32_t magic = SH_MAGIC;
    uint8_t mode = SH_MODE_ATTACH;
    if (write_all(fd, &magic, sizeof(magic)) < 0 ||
        write_all(fd, &mode, 1) < 0 ||
        write_blob(fd, g_token, strlen(g_token)) < 0 ||
        write_blob(fd, cmd ? cmd : "", strlen(cmd ? cmd : "")) < 0 ||
        write_blob(fd, session_name ? session_name : "", strlen(session_name ? session_name : "")) < 0) {
        fprintf(stderr, "shell_daemon: zapis hlavicky selhal\n");
        close(fd); return 1;
    }

    int32_t ack = -1;
    if (read_all(fd, &ack, sizeof(ack)) < 0) {
        fprintf(stderr, "shell_daemon: zadna odpoved\n");
        close(fd); return 1;
    }
    if (ack != 0) {
        char eb[512] = {0};
        uint32_t elen = 0;
        if (read_all(fd, &elen, sizeof(elen)) == 0 && elen < sizeof(eb)) {
            read_all(fd, eb, elen);
        }
        fprintf(stderr, "shell_daemon: attach odmitnut: %s\n", eb[0] ? eb : "(neznama chyba)");
        close(fd); return 1;
    }

    /* Posli pocatecni velikost terminalu. */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) < 0) {
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = 24; ws.ws_col = 80;
    }
    {
        uint8_t type = 2;
        uint32_t len = 8;
        uint32_t rows = ws.ws_row, cols = ws.ws_col;
        write_all(fd, &type, 1);
        write_all(fd, &len, sizeof(len));
        write_all(fd, &rows, 4);
        write_all(fd, &cols, 4);
    }

    set_nonblock(STDIN_FILENO);

    /* Signal pro zmenu velikosti okna. */
    signal(SIGWINCH, SIG_IGN);

    char buf[16384];
    int stdin_open = 1;
    while (1) {
        struct pollfd pfds[2];
        int nfds = 0;
        pfds[nfds].fd = fd; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0; nfds++;
        if (stdin_open) {
            pfds[nfds].fd = STDIN_FILENO; pfds[nfds].events = POLLIN; pfds[nfds].revents = 0; nfds++;
        }
        int pr = poll(pfds, nfds, 500);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (pr == 0) continue;

        /* daemon → nas stdout (raw) */
        if (pfds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n > 0) {
                if (write_all(STDOUT_FILENO, buf, (size_t)n) < 0) break;
            } else {
                break;
            }
        }

        /* stdin → daemon (framovane) */
        if (stdin_open && nfds > 1 && (pfds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n > 0) {
                uint8_t type = 1;
                uint32_t len = (uint32_t)n;
                if (write_all(fd, &type, 1) < 0 ||
                    write_all(fd, &len, sizeof(len)) < 0 ||
                    write_all(fd, buf, (size_t)n) < 0) break;
            } else if (n == 0) {
                uint8_t type = 3;
                uint32_t len = 0;
                write_all(fd, &type, 1);
                write_all(fd, &len, sizeof(len));
                stdin_open = 0;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                stdin_open = 0;
            }
        }
    }

    close(fd);
    return 0;
}

/* ── Token ──────────────────────────────────────────────────────────────── */

static int load_token(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char buf[MAX_TOKEN];
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); return -1; }
    fclose(f);
    /* ořízni whitespace */
    size_t n = strlen(buf);
    while (n > 0 && (buf[n-1] == '\n' || buf[n-1] == '\r' || buf[n-1] == ' ')) buf[--n] = '\0';
    if (n == 0 || n >= MAX_TOKEN) return -1;
    strncpy(g_token, buf, sizeof(g_token) - 1);
    return 0;
}

/* ── Main ───────────────────────────────────────────────────────────────── */

int main(int argc, char **argv) {
    const char *token_file = NULL;
    int no_fork = 0;
    int attach_mode = 0;
    const char *attach_host = NULL;
    const char *attach_cmd = NULL;
    const char *attach_session = NULL;
    const char *kill_session = NULL;

    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "--port=", 7) == 0) {
            g_port = atoi(argv[i] + 7);
            if (g_port <= 0 || g_port > 65535) g_port = DEFAULT_PORT;
        } else if (strncmp(argv[i], "--token=", 8) == 0) {
            strncpy(g_token, argv[i] + 8, sizeof(g_token) - 1);
        } else if (strncmp(argv[i], "--token-file=", 13) == 0) {
            token_file = argv[i] + 13;
        } else if (strcmp(argv[i], "--no-fork") == 0) {
            no_fork = 1;
        } else if (strcmp(argv[i], "--attach") == 0) {
            attach_mode = 1;
        } else if (strncmp(argv[i], "--attach-host=", 14) == 0) {
            attach_host = argv[i] + 14;
        } else if (strncmp(argv[i], "--attach-cmd=", 13) == 0) {
            attach_cmd = argv[i] + 13;
        } else if (strncmp(argv[i], "--attach-session=", 17) == 0) {
            attach_session = argv[i] + 17;
        } else if (strncmp(argv[i], "--kill-session=", 15) == 0) {
            kill_session = argv[i] + 15;
        } else if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            printf("shell_daemon [--port=N] [--token=HEX] [--token-file=PATH] [--no-fork]\n"
                   "shell_daemon --attach [--attach-host=127.0.0.1] [--port=N] [--token=HEX] [--attach-cmd=CMD] [--attach-session=NAME]\n"
                   "shell_daemon --kill-session=NAME [--attach-host=127.0.0.1] [--port=N] [--token=HEX]\n"
                   "  --attach-session=NAME  pojmenovana session prezije detach/odpojeni (reattach = stejne jmeno)\n"
                   "  --kill-session=NAME    tvrde ukonci pojmenovanou session (kill shellu + uklid)\n");
            return 0;
        }
    }

    if (g_token[0] == '\0') {
        const char *tf = token_file ? token_file : DEFAULT_TOKEN_FILE;
        if (load_token(tf) != 0) {
            fprintf(stderr, "[shell_daemon] FATAL: chybi token (--token= nebo --token-file=%s)\n", tf);
            return 2;
        }
    }

    if (kill_session != NULL) {
        const char *h = attach_host ? attach_host : "127.0.0.1";
        return run_kill_session_client(h, g_port, kill_session);
    }

    /* Klientsky rezim: pripoj se k bezicimu daemonu a predej mu PTY most. */
    if (attach_mode) {
        const char *h = attach_host ? attach_host : "127.0.0.1";
        return run_attach_client(h, g_port, attach_cmd, attach_session);
    }

    uid_t uid = getuid();
    if (uid == 0) {
        fprintf(stderr, "[shell_daemon] VAROVANI: bezim jako root (uid 0); shell_daemon je urcen pro uid 2000\n");
    }

    /* ── Socket ── */
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        perror("[shell_daemon] socket");
        return 1;
    }
    int opt = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)g_port);
    /* 127.0.0.1 — jen localhost, nikdy 0.0.0.0 */
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("[shell_daemon] bind 127.0.0.1");
        close(listen_fd);
        return 1;
    }
    if (listen(listen_fd, 16) < 0) {
        perror("[shell_daemon] listen");
        close(listen_fd);
        return 1;
    }

    /* Daemonizace (pokud není --no-fork): odpoj od terminálu, ať přežije
     * zavření `adb shell` okna. Debug: --no-fork běží v popředí. */
    if (!no_fork) {
        /* PID file zapisujeme az v diteti (po fork), aby obsahoval PID
         * skutecneho daemona, ne parenta, ktery hned zmizi. */
        pid_t dfork = fork();
        if (dfork < 0) {
            perror("[shell_daemon] fork (daemonize)");
            return 1;
        }
        if (dfork > 0) {
            /* parent: vytiskni pid a skonči (adbd zavře session) */
            printf("[shell_daemon] PID=%d port=%d (daemonized)\n", (int)dfork, g_port);
            fflush(stdout);
            _exit(0);
        }
        setsid();
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            if (devnull > STDERR_FILENO) close(devnull);
        }
    }

    /* PID file pro appku (LocalApiServer status) i pro `ashell adb stop`.
     * /data/local/tmp je world-writable, takze ho uid 2000 muze zapsat. */
    {
        const char *pid_path = getenv("SHELLDAEMON_PID_FILE");
        if (pid_path == NULL || pid_path[0] == '\0') pid_path = "/data/local/tmp/shelldaemon.pid";
        strncpy(g_pid_path, pid_path, sizeof(g_pid_path) - 1);
        g_pid_path[sizeof(g_pid_path) - 1] = '\0';
        FILE *pf = fopen(g_pid_path, "w");
        if (pf) {
            fprintf(pf, "%d\n", (int)getpid());
            fclose(pf);
            chmod(g_pid_path, 0644);
        }
    }

    printf("[shell_daemon] listening on 127.0.0.1:%d (uid=%d, pid=%d)\n",
           g_port, (int)getuid(), (int)getpid());
    fflush(stdout);
    fflush(stderr);

    signal(SIGPIPE, SIG_IGN);

    /* SIGTERM/SIGINT → uklid PID file a skonci. Potrebuje to SH_MODE_STOP
     * (worker posle parentovi SIGTERM) i rucni `kill` z ashellu. Bez toho
     * by po sobe daemon nechal osirely PID file a status by lhal. */
    {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = sigterm_cleanup;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGTERM, &sa, NULL);
        sigaction(SIGINT, &sa, NULL);
    }

    /* Reaper pro worker child processes — zabraňuje hromadění zombie.
     * SA_NOCLDSTOP: nereaguj na stop/continue (jen na exit).
     * SA_RESTART: nezruš accept() při doručení signálu. */
    {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = sigchld_reaper;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = SA_RESTART | SA_NOCLDSTOP;
        sigaction(SIGCHLD, &sa, NULL);
    }

    while (1) {
        int client_fd = accept(listen_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            perror("[shell_daemon] accept");
            break;
        }

        pid_t worker = fork();
        if (worker < 0) {
            perror("[shell_daemon] fork worker");
            close(client_fd);
            continue;
        }
        if (worker == 0) {
            signal(SIGCHLD, SIG_DFL);
            close(listen_fd);
            handle_client(client_fd);
            close(client_fd);
            _exit(0);
        }
        close(client_fd);
    }

    close(listen_fd);
    return 0;
}
