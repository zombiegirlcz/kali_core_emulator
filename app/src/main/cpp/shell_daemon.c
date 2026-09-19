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

static char g_token[MAX_TOKEN] = {0};
static int g_port = DEFAULT_PORT;

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
        fprintf(stderr, "[shell_daemon] attach (cmd=%s)\n", cmd[0] ? cmd : "<interaktivni>");
        handle_attach(client_fd, cmd);
        free(cmd);
        return;
    }

    handle_exec(client_fd);
}

/* ── Attach klient (--attach) ───────────────────────────────────────────── */

static int run_attach_client(const char *host, int port, const char *cmd) {
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
        write_blob(fd, cmd ? cmd : "", strlen(cmd ? cmd : "")) < 0) {
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
        } else if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            printf("shell_daemon [--port=N] [--token=HEX] [--token-file=PATH] [--no-fork]\n"
                   "shell_daemon --attach [--attach-host=127.0.0.1] [--port=N] [--token=HEX] [--attach-cmd=CMD]\n");
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

    /* Klientsky rezim: pripoj se k bezicimu daemonu a predej mu PTY most. */
    if (attach_mode) {
        const char *h = attach_host ? attach_host : "127.0.0.1";
        return run_attach_client(h, g_port, attach_cmd);
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
        FILE *pf = fopen(pid_path, "w");
        if (pf) {
            fprintf(pf, "%d\n", (int)getpid());
            fclose(pf);
            chmod(pid_path, 0644);
        }
    }

    printf("[shell_daemon] listening on 127.0.0.1:%d (uid=%d, pid=%d)\n",
           g_port, (int)getuid(), (int)getpid());
    fflush(stdout);
    fflush(stderr);

    signal(SIGPIPE, SIG_IGN);

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
