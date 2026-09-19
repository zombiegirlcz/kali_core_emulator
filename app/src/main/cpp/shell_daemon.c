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

#define DEFAULT_PORT 13341
#define DEFAULT_TOKEN_FILE "/data/local/tmp/shell_daemon.token"
#define MAX_TOKEN 256
#define CMD_BUF 65536
#define OUT_BUF 262144
#define MAX_ARGS 64

static char g_token[MAX_TOKEN] = {0};
static int g_port = DEFAULT_PORT;

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

/* ── Per-connection worker ──────────────────────────────────────────────── */

static void handle_client(int client_fd) {
    char token[MAX_TOKEN] = {0};
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

    if (read_blob(client_fd, token, sizeof(token)) < 0 ||
        read_blob(client_fd, cmd, CMD_BUF) < 0 ||
        read_blob(client_fd, cwd, 4096) < 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "", 0);
        write_blob(client_fd, "protokol: neocekavany konec", 26);
        free(cmd); free(cwd);
        return;
    }

    if (strcmp(token, g_token) != 0) {
        int32_t rc = -1;
        write_all(client_fd, &rc, sizeof(rc));
        write_blob(client_fd, "", 0);
        write_blob(client_fd, "neplatny token", 13);
        fprintf(stderr, "[shell_daemon] zamitnuto: neplatny token\n");
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
        } else if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            printf("shell_daemon [--port=N] [--token=HEX] [--token-file=PATH] [--no-fork]\n");
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
