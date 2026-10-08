/*
 * ashell_pty — streaming bridge between the PRoot guest and the Android host.
 *
 * Runs as the APP UID (spawned by LocalApiServer). Listens on 127.0.0.1:<port>
 * and, for each loopback connection, runs the requested host command and relays
 * its stdio over a small binary frame protocol. This gives `ashell -c '...'`
 * REAL shell semantics: stdin streaming, pipes, a true exit code, cwd from the
 * guest (translated into the host rootfs), and — for interactive commands — a
 * real pty (job control, resize, isatty).
 *
 *   argv[1] = TCP port (default "13340")
 *   argv[2] = app filesDir (used for PATH/HOME/PREFIX of the spawned command)
 *
 * Frame protocol (both directions), 5-byte header + payload:
 *   u8 type | u32 be payload_len | payload
 *
 *   Client -> Server:
 *     0x05 HELLO        payload = cmd \0 cwd \0 rootfs \0 term \0 interactive \0 token \0
 *     0x01 STDIN        payload = raw bytes for the command's stdin
 *     0x06 STDIN_EOF    no payload — close the command's stdin (pipe mode)
 *     0x03 WINCH        payload = 8 bytes: u32 cols, u32 rows
 *     0xFF CLOSE        abort the session
 *
 *   Server -> Client:
 *     0x02 STDOUT       payload = raw bytes from the command (stdout+stderr)
 *     0x04 EXIT         payload = 4 bytes: u32 exit code (big-endian)
 *     0xFF CLOSE        session finished (sent after EXIT)
 *
 * Interactive vs. pipe: if HELLO's `interactive` flag is "1", the command runs
 * in a fresh pty (echo on). Otherwise it runs on plain pipes (no echo, no
 * \n->\r\n translation) — correct for `ashell -c 'cmd | tail'` and piped stdin.
 *
 * Security: only accepts peers from 127.0.0.1 AND only from an allowed UID.
 * Loopback is shared by every app on Android, so 127.0.0.1 alone is not
 * enough (HTTP /shell has a Bearer token, this protocol has none). Without
 * changing the protocol, the worker looks the peer's socket up in
 * /proc/net/tcp{,6} (entry whose local port == client port and remote port ==
 * our listen port) and reads its owner uid. Allowed: getuid() (the app itself
 * / the PRoot guest), 0 (root, su_daemon/sudo) and 2000 (shell_daemon). If the
 * uid cannot be determined, the connection is refused (fail-closed).
 *
 * POZOR (ověřeno 2026-10-08 na zařízení): v kontextu untrusted_app_27 SELinux
 * zakazuje čtení /proc/net/tcp{,6} (EACCES) i NETLINK_SOCK_DIAG. Když /proc/net
 * při startu nejde číst vůbec, UID kontrola se vypne (g_peer_uid_check=0).
 * Skutečnou ochranu dává TOKEN: 6. pole HELLO musí odpovídat
 * <filesDir>/api.token (LocalApiServer.publishGuestToken, 0600 pod UID appky).
 */

#define _GNU_SOURCE

#include <stdio.h>
#include <stdint.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <termios.h>

#ifndef TIOCSCTTY
#define TIOCSCTTY 0x540E
#endif

#define DEFAULT_PORT 13340
#define BUFSZ 8192
#define FRAME_MAX 65536

static const unsigned char F_STDIN     = 0x01;
static const unsigned char F_STDOUT    = 0x02;
static const unsigned char F_WINCH     = 0x03;
static const unsigned char F_EXIT      = 0x04;
static const unsigned char F_HELLO     = 0x05;
static const unsigned char F_STDIN_EOF = 0x06;
static const unsigned char F_CLOSE     = 0xFF;

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

/* Max doba bez jakéhokoli pokroku zápisu (EAGAIN) — pak write_all vzdá (-1).
 * Jinak by dítě, které nečte stdin, zablokovalo relay navždy (nečetl by se
 * klient ani výstup, nedetekoval by se POLLHUP). */
#define WRITE_STALL_MS 10000

/* Write all bytes; handles EINTR and EAGAIN (non-blocking fd) via short poll.
 * Returns -1 on error or when no progress is made for WRITE_STALL_MS. */
static int write_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    int stalled_ms = 0;
    while (off < len) {
        ssize_t w = write(fd, buf + off, len - off);
        if (w > 0) { off += (size_t)w; stalled_ms = 0; continue; }
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && errno == EAGAIN) {
            if (stalled_ms >= WRITE_STALL_MS) return -1;
            struct pollfd pfd = { .fd = fd, .events = POLLOUT };
            if (poll(&pfd, 1, 500) < 0 && errno != EINTR) return -1;
            stalled_ms += 500;
            continue;
        }
        return -1;
    }
    return 0;
}

/* Read exactly len bytes (blocking, EINTR-safe). 0 on success, -1 on EOF/error. */
static int read_exact(int fd, char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t r = read(fd, buf + off, len - off);
        if (r > 0) { off += (size_t)r; continue; }
        if (r < 0 && errno == EINTR) continue;
        return -1;
    }
    return 0;
}

/* Send one frame. 0 on success, -1 on error. */
static int send_frame(int fd, unsigned char type, const char *payload, uint32_t len) {
    unsigned char hdr[5];
    hdr[0] = type;
    hdr[1] = (unsigned char)((len >> 24) & 0xFF);
    hdr[2] = (unsigned char)((len >> 16) & 0xFF);
    hdr[3] = (unsigned char)((len >> 8) & 0xFF);
    hdr[4] = (unsigned char)(len & 0xFF);
    if (write_all(fd, (const char *)hdr, 5) != 0) return -1;
    if (len > 0 && write_all(fd, payload, len) != 0) return -1;
    return 0;
}

/* Send an EXIT frame (exit code as big-endian u32). */
static int send_exit(int fd, uint32_t code) {
    unsigned char b[4];
    b[0] = (unsigned char)((code >> 24) & 0xFF);
    b[1] = (unsigned char)((code >> 16) & 0xFF);
    b[2] = (unsigned char)((code >> 8) & 0xFF);
    b[3] = (unsigned char)(code & 0xFF);
    return send_frame(fd, F_EXIT, (const char *)b, 4);
}

/* Receive one frame. Payload length (>=0), or -1 on EOF/error. */
static int recv_frame(int fd, unsigned char *type, char *payload, size_t cap) {
    unsigned char hdr[5];
    if (read_exact(fd, (char *)hdr, 5) != 0) return -1;
    uint32_t len = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16) |
                   ((uint32_t)hdr[3] << 8) | (uint32_t)hdr[4];
    if (len > cap) return -1;
    if (len > 0 && read_exact(fd, payload, len) != 0) return -1;
    *type = hdr[0];
    return (int)len;
}

/* Resolve guest cwd to a host path inside rootfs. */
static void resolve_cwd(const char *rootfs, const char *guest_cwd, char *out, size_t cap) {
    if (rootfs == NULL || rootfs[0] == '\0') {
        snprintf(out, cap, "/");
        return;
    }
    size_t rl = strlen(rootfs);
    while (rl > 1 && rootfs[rl - 1] == '/') rl--;

    if (guest_cwd == NULL || guest_cwd[0] == '\0' || strcmp(guest_cwd, "/") == 0) {
        snprintf(out, cap, "%.*s", (int)rl, rootfs);
    } else {
        snprintf(out, cap, "%.*s%s", (int)rl, rootfs, guest_cwd);
    }
    struct stat st;
    if (stat(out, &st) == 0 && S_ISDIR(st.st_mode)) return;
    if (rl > 0) {
        snprintf(out, cap, "%.*s", (int)rl, rootfs);
        if (stat(out, &st) == 0 && S_ISDIR(st.st_mode)) return;
    }
    snprintf(out, cap, "/");
}

/* Env for a host-side command (mirror ExecCore.hostShellEnv). */
static void set_child_env(const char *files_dir, const char *term) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/usr/bin:/system/bin:/system/xbin:/vendor/bin:%s",
             files_dir, files_dir);
    setenv("PATH", path, 1);
    setenv("HOME", files_dir, 1);
    char pref[4096];
    snprintf(pref, sizeof(pref), "%s/usr", files_dir);
    setenv("PREFIX", pref, 1);
    setenv("TERM", term, 1);
    setenv("ANDROID_DATA", "/data", 1);
    setenv("ANDROID_ROOT", "/system", 1);
    unsetenv("LD_LIBRARY_PATH");
}

/* ── Interactive path: real pty (echo on, job control, resize) ──────────── */
static void run_pty(int client_fd, const char *files_dir, const char *cmd,
                    const char *host_cwd, const char *term) {
    int master_fd = -1, slave_fd = -1;
    if (open_pty(&master_fd, &slave_fd) < 0) {
        send_exit(client_fd, 127);
        send_frame(client_fd, F_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    struct winsize ws;
    ws.ws_row = 24; ws.ws_col = 80; ws.ws_xpixel = 0; ws.ws_ypixel = 0;

    pid_t pid = fork();
    if (pid < 0) {
        close(master_fd); close(slave_fd);
        send_exit(client_fd, 127);
        send_frame(client_fd, F_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    if (pid == 0) {
        close(client_fd);
        close(master_fd);
        if (setsid() < 0) { setpgid(0, 0); setsid(); }
        ioctl(slave_fd, TIOCSCTTY, 0);
        ioctl(slave_fd, TIOCSWINSZ, &ws);
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        close(slave_fd);
        tcsetpgrp(STDIN_FILENO, getpgrp());
        chdir(host_cwd);
        set_child_env(files_dir, term);
        execlp("sh", "sh", "-c", cmd, (char *)NULL);
        dprintf(STDERR_FILENO, "[ashell_pty] exec sh failed: %s\n", strerror(errno));
        _exit(127);
    }

    close(slave_fd);
    set_nonblock(master_fd);

    int status = 0, exit_code = 0, client_dead = 0;

    for (;;) {
        struct pollfd pfds[2];
        pfds[0].fd = client_fd;  pfds[0].events = POLLIN | POLLHUP;
        pfds[1].fd = master_fd;  pfds[1].events = POLLIN;
        int pr = poll(pfds, 2, 100);
        if (pr < 0) { if (errno == EINTR) continue; break; }

        if (pfds[0].revents & (POLLHUP | POLLERR | POLLNVAL)) {
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            client_dead = 1;
            exit_code = 130;
            break;
        }

        if (pr > 0) {
            if (pfds[1].revents & POLLIN) {
                char buf[BUFSZ];
                ssize_t n = read(master_fd, buf, sizeof(buf));
                if (n > 0) send_frame(client_fd, F_STDOUT, buf, (uint32_t)n);
            }
            if (pfds[0].revents & POLLIN) {
                unsigned char t;
                char b[FRAME_MAX];
                int len = recv_frame(client_fd, &t, b, sizeof(b));
                if (len < 0) {
                    kill(pid, SIGKILL);
                    waitpid(pid, &status, 0);
                    client_dead = 1;
                    exit_code = 130;
                    break;
                }
                if (t == F_STDIN) {
                    if (write_all(master_fd, b, (size_t)len) != 0)
                        fprintf(stderr, "[ashell_pty] pty stdin zapis zasekly/selhal — vstup zahozen\n");
                } else if (t == F_STDIN_EOF) {
                    write_all(master_fd, "\x04", 1); /* VEOF = ^D */
                } else if (t == F_WINCH && len >= 8) {
                    uint32_t cols = ((uint32_t)(unsigned char)b[0] << 24) |
                                    ((uint32_t)(unsigned char)b[1] << 16) |
                                    ((uint32_t)(unsigned char)b[2] << 8) |
                                    (uint32_t)(unsigned char)b[3];
                    uint32_t rows = ((uint32_t)(unsigned char)b[4] << 24) |
                                    ((uint32_t)(unsigned char)b[5] << 16) |
                                    ((uint32_t)(unsigned char)b[6] << 8) |
                                    (uint32_t)(unsigned char)b[7];
                    ws.ws_col = (unsigned short)(cols & 0xFFFF);
                    ws.ws_row = (unsigned short)(rows & 0xFFFF);
                    ioctl(master_fd, TIOCSWINSZ, &ws);
                } else if (t == F_CLOSE) {
                    kill(pid, SIGHUP);
                }
            }
        }

        pid_t wr = waitpid(pid, &status, WNOHANG);
        if (wr == pid) {
            for (;;) {
                char buf[BUFSZ];
                ssize_t n = read(master_fd, buf, sizeof(buf));
                if (n > 0) { send_frame(client_fd, F_STDOUT, buf, (uint32_t)n); continue; }
                if (n == 0) break;
                if (n < 0 && errno == EAGAIN) {
                    struct pollfd p = { master_fd, POLLIN, 0 };
                    if (poll(&p, 1, 50) < 0) break;
                    if (!(p.revents & POLLIN)) break;
                    continue;
                }
                break;
            }
            exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            break;
        }
    }

    close(master_fd);
    if (!client_dead) {
        send_exit(client_fd, (uint32_t)(exit_code & 0xFF));
        send_frame(client_fd, F_CLOSE, NULL, 0);
    }
    close(client_fd);
}

/* ── Non-interactive path: plain pipes (no echo, no \r translation) ─────── */
static void run_pipe(int client_fd, const char *files_dir, const char *cmd,
                     const char *host_cwd, const char *term) {
    int in_pipe[2], out_pipe[2];
    if (pipe(in_pipe) < 0 || pipe(out_pipe) < 0) {
        send_exit(client_fd, 127);
        send_frame(client_fd, F_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(in_pipe[0]); close(in_pipe[1]);
        close(out_pipe[0]); close(out_pipe[1]);
        send_exit(client_fd, 127);
        send_frame(client_fd, F_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    if (pid == 0) {
        close(client_fd);
        close(in_pipe[1]);
        close(out_pipe[0]);
        dup2(in_pipe[0], STDIN_FILENO);
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(out_pipe[1], STDERR_FILENO);
        close(in_pipe[0]);
        close(out_pipe[1]);
        chdir(host_cwd);
        set_child_env(files_dir, term);
        execlp("sh", "sh", "-c", cmd, (char *)NULL);
        dprintf(STDERR_FILENO, "[ashell_pty] exec sh failed: %s\n", strerror(errno));
        _exit(127);
    }

    close(in_pipe[0]);
    close(out_pipe[1]);
    set_nonblock(in_pipe[1]);
    set_nonblock(out_pipe[0]);

    int stdin_open = 1;
    int status = 0, exit_code = 0, client_dead = 0;

    for (;;) {
        struct pollfd pfds[2];
        pfds[0].fd = client_fd;  pfds[0].events = POLLIN | POLLHUP;
        pfds[1].fd = out_pipe[0]; pfds[1].events = POLLIN;
        int pr = poll(pfds, 2, 100);
        if (pr < 0) { if (errno == EINTR) continue; break; }

        if (pfds[0].revents & (POLLHUP | POLLERR | POLLNVAL)) {
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            client_dead = 1;
            exit_code = 130;
            break;
        }

        if (pr > 0) {
            if (pfds[1].revents & POLLIN) {
                char buf[BUFSZ];
                ssize_t n = read(out_pipe[0], buf, sizeof(buf));
                if (n > 0) send_frame(client_fd, F_STDOUT, buf, (uint32_t)n);
            }
            if (pfds[0].revents & POLLIN) {
                unsigned char t;
                char b[FRAME_MAX];
                int len = recv_frame(client_fd, &t, b, sizeof(b));
                if (len < 0) {
                    kill(pid, SIGKILL);
                    waitpid(pid, &status, 0);
                    client_dead = 1;
                    exit_code = 130;
                    break;
                }
                if (t == F_STDIN) {
                    /* Zaseknutý zápis (dítě nečte stdin) → zavřít stdin, dítě dostane EOF/EPIPE. */
                    if (stdin_open && write_all(in_pipe[1], b, (size_t)len) != 0) {
                        fprintf(stderr, "[ashell_pty] pipe stdin zapis zasekly/selhal — zaviram stdin\n");
                        close(in_pipe[1]); in_pipe[1] = -1; stdin_open = 0;
                    }
                } else if (t == F_STDIN_EOF) {
                    if (stdin_open) { close(in_pipe[1]); in_pipe[1] = -1; stdin_open = 0; }
                } else if (t == F_CLOSE) {
                    kill(pid, SIGHUP);
                }
            }
        }

        pid_t wr = waitpid(pid, &status, WNOHANG);
        if (wr == pid) {
            for (;;) {
                char buf[BUFSZ];
                ssize_t n = read(out_pipe[0], buf, sizeof(buf));
                if (n > 0) { send_frame(client_fd, F_STDOUT, buf, (uint32_t)n); continue; }
                if (n == 0) break;
                if (n < 0 && errno == EAGAIN) {
                    struct pollfd p = { out_pipe[0], POLLIN, 0 };
                    if (poll(&p, 1, 50) < 0) break;
                    if (!(p.revents & POLLIN)) break;
                    continue;
                }
                break;
            }
            exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            break;
        }
    }

    if (in_pipe[1] >= 0) close(in_pipe[1]);
    close(out_pipe[0]);
    if (!client_dead) {
        send_exit(client_fd, (uint32_t)(exit_code & 0xFF));
        send_frame(client_fd, F_CLOSE, NULL, 0);
    }
    close(client_fd);
}

/* Najde v /proc/net/tcp{,6} socket klienta (local port == client_port,
 * remote port == listen_port) a vrátí počet shod; *uid_out = jeho uid.
 * TIME_WAIT (06) a LISTEN (0A) se přeskakují — timewait záznamy mají uid 0.
 * Víc shod s různým uid → -2 (nejednoznačné, odmítnout). Soubor nejde
 * otevřít → -1. */
static int lookup_peer_uid_in(const char *path, unsigned client_port,
                              unsigned listen_port, unsigned *uid_out) {
    FILE *f = fopen(path, "r");
    if (f == NULL) return -1;
    char line[512];
    int matches = 0;
    unsigned found = 0;
    if (fgets(line, sizeof(line), f) == NULL) { fclose(f); return 0; } /* hlavička */
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned lport = 0, rport = 0, st = 0, uid = 0;
        /* sl: local_addr:port rem_addr:port st tx:rx tr:when retrnsmt uid ... */
        if (sscanf(line, " %*d: %*[0-9A-Fa-f]:%x %*[0-9A-Fa-f]:%x %x %*x:%*x %*x:%*x %*x %u",
                   &lport, &rport, &st, &uid) != 4)
            continue;
        if (lport != client_port || rport != listen_port) continue;
        if (st == 0x06 || st == 0x0A) continue;
        if (matches > 0 && uid != found) { fclose(f); return -2; }
        found = uid;
        matches++;
    }
    fclose(f);
    if (matches > 0) *uid_out = found;
    return matches;
}

/* 1 = /proc/net/tcp{,6} je čitelný → UID peera se vynucuje; 0 = nejde (SELinux). */
static int g_peer_uid_check = 1;

/* Ověří UID peera TCP spojení (viz Security v hlavičce). 0 = povolit, -1 = odmítnout. */
static int check_peer_uid(int client_fd, unsigned listen_port) {
    if (!g_peer_uid_check) return 0;
    struct sockaddr_storage ss;
    socklen_t sl = sizeof(ss);
    if (getpeername(client_fd, (struct sockaddr *)&ss, &sl) < 0) {
        fprintf(stderr, "[ashell_pty] getpeername: %s — spojeni odmitnuto\n", strerror(errno));
        return -1;
    }
    unsigned client_port;
    if (ss.ss_family == AF_INET) {
        client_port = ntohs(((struct sockaddr_in *)&ss)->sin_port);
    } else if (ss.ss_family == AF_INET6) {
        client_port = ntohs(((struct sockaddr_in6 *)&ss)->sin6_port);
    } else {
        fprintf(stderr, "[ashell_pty] neznama rodina peera (%d) — spojeni odmitnuto\n", ss.ss_family);
        return -1;
    }

    unsigned uid = 0, uid6 = 0;
    int n4 = lookup_peer_uid_in("/proc/net/tcp", client_port, listen_port, &uid);
    int n6 = lookup_peer_uid_in("/proc/net/tcp6", client_port, listen_port, &uid6);
    if (n4 == -2 || n6 == -2 || (n4 > 0 && n6 > 0 && uid != uid6)) {
        fprintf(stderr, "[ashell_pty] nejednoznacny uid peera (port %u) — spojeni odmitnuto\n", client_port);
        return -1;
    }
    if (n4 <= 0 && n6 > 0) { uid = uid6; n4 = n6; }
    if (n4 <= 0) {
        fprintf(stderr, "[ashell_pty] uid peera (port %u) nelze zjistit z /proc/net/tcp{,6} — spojeni odmitnuto\n",
                client_port);
        return -1;
    }
    if (uid == (unsigned)getuid() || uid == 0 || uid == 2000) return 0;
    fprintf(stderr, "[ashell_pty] peer uid %u neni povolen (povoleno %u/0/2000) — spojeni odmitnuto\n",
            uid, (unsigned)getuid());
    return -1;
}

/* Porovná token z HELLO s filesDir/api.token v konstantním čase. */
static int token_ok(const char *files_dir, const char *given) {
    char path[1024], want[256];
    if (!given || !*given) return 0;
    snprintf(path, sizeof(path), "%s/api.token", files_dir);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, want, sizeof(want) - 1);
    close(fd);
    if (n <= 0) return 0;
    while (n > 0 && (want[n-1] == '\n' || want[n-1] == '\r' || want[n-1] == ' ')) n--;
    want[n] = '\0';
    size_t gl = strlen(given), wl = (size_t)n;
    if (wl == 0 || gl != wl) return 0;
    unsigned char diff = 0;
    for (size_t i = 0; i < wl; i++) diff |= (unsigned char)(given[i] ^ want[i]);
    return diff == 0;
}

/* Handle one client connection (runs in a forked worker). */
static void handle_client(int client_fd, const char *files_dir) {
    unsigned char type = 0;
    char hello[FRAME_MAX];
    int hlen = recv_frame(client_fd, &type, hello, sizeof(hello) - 1);
    if (hlen < 0 || type != F_HELLO) {
        close(client_fd);
        return;
    }
    hello[hlen] = '\0';

    /* Sekvenční parsování s explicitní mezí: pole[i] ukazuje jen dovnitř
     * hello[0..hlen]; chybějící pole = NULL → nahradí se defaultem. Žádná
     * aritmetika za koncem literálu ani srovnání ukazatelů mezi objekty. */
    const char *fields[6] = { NULL, NULL, NULL, NULL, NULL, NULL };
    {
        size_t pos = 0;
        for (int i = 0; i < 6 && pos < (size_t)hlen; i++) {
            fields[i] = hello + pos;
            pos += strlen(hello + pos) + 1;   /* hello[hlen] == '\0' → nepřeteče */
        }
    }
    const char *cmd    = fields[0] ? fields[0] : "";
    const char *cwd    = fields[1] ? fields[1] : "";
    const char *rootfs = fields[2] ? fields[2] : "";
    const char *term   = fields[3] ? fields[3] : "xterm-256color";
    int interactive = 1;
    if (fields[4]) interactive = (fields[4][0] == '1') ? 1 : 0;

    /* Autentizace: 6. pole HELLO = API token (filesDir/api.token, 0600 pod UID
     * appky — přečte ho jen appka/guest a root). Kontrola UID peera přes
     * /proc/net/tcp na Androidu 10+ pro untrusted_app nefunguje, token ano. */
    if (!token_ok(files_dir, fields[5])) {
        fprintf(stderr, "[ashell_pty] HELLO bez platneho tokenu — spojeni odmitnuto\n");
        send_exit(client_fd, 126);
        send_frame(client_fd, F_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    if (cmd[0] == '\0') {
        send_exit(client_fd, 0);
        send_frame(client_fd, F_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    char host_cwd[2048];
    resolve_cwd(rootfs, cwd, host_cwd, sizeof(host_cwd));

    if (interactive) {
        run_pty(client_fd, files_dir, cmd, host_cwd, term);
    } else {
        run_pipe(client_fd, files_dir, cmd, host_cwd, term);
    }
}

int main(int argc, char **argv) {
    int port = DEFAULT_PORT;
    if (argc > 1) port = atoi(argv[1]);
    const char *files_dir = "/data/user/0/com.linux_core/files";
    if (argc > 2 && argv[2][0] != '\0') files_dir = argv[2];

    signal(SIGPIPE, SIG_IGN);
    /* Auto-reap per-connection workers. The accept loop below forks a worker
     * for every client but never waitpid()s it, so without SIG_IGN each
     * handled connection would leave a zombie behind (process leak).
     * Workers reset this to SIG_DFL so they can wait for their own shell. */
    signal(SIGCHLD, SIG_IGN);

    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd < 0) { perror("[ashell_pty] socket"); return 1; }

    int one = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((unsigned short)port);

    if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("[ashell_pty] bind");
        close(listen_fd);
        return 1;
    }
    if (listen(listen_fd, 10) < 0) {
        perror("[ashell_pty] listen");
        close(listen_fd);
        return 1;
    }

    if (access("/proc/net/tcp", R_OK) != 0 && access("/proc/net/tcp6", R_OK) != 0) {
        g_peer_uid_check = 0;
        fprintf(stderr, "[ashell_pty] VAROVANI: /proc/net/tcp{,6} nelze cist (%s) — "
                        "kontrola UID peera vypnuta, plati jen loopback\n", strerror(errno));
    }

    printf("[ashell_pty] listening on 127.0.0.1:%d (filesDir=%s)\n", port, files_dir);
    fflush(stdout);

    for (;;) {
        struct sockaddr_in peer;
        socklen_t plen = sizeof(peer);
        int client_fd = accept(listen_fd, (struct sockaddr *)&peer, &plen);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            perror("[ashell_pty] accept");
            /* Přechodné chyby (ECONNABORTED, EMFILE, ENOBUFS, …) nesmí shodit
             * celý most — krátká pauza a znovu. Konec jen u EBADF/EINVAL/ENOTSOCK. */
            if (errno == EBADF || errno == EINVAL || errno == ENOTSOCK) break;
            struct timespec ts = { 0, 100 * 1000 * 1000 };
            nanosleep(&ts, NULL);
            continue;
        }
        if (ntohl(peer.sin_addr.s_addr) != INADDR_LOOPBACK) {
            close(client_fd);
            continue;
        }

        pid_t worker = fork();
        if (worker < 0) {
            perror("[ashell_pty] fork worker");
            close(client_fd);
            continue;
        }
        if (worker == 0) {
            signal(SIGCHLD, SIG_DFL);
            close(listen_fd);
            if (check_peer_uid(client_fd, (unsigned)port) != 0) {
                close(client_fd);
                _exit(0);
            }
            handle_client(client_fd, files_dir);
            _exit(0);
        }
        close(client_fd);
    }

    close(listen_fd);
    return 0;
}
