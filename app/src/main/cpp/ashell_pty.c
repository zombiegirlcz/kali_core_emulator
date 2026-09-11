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
 *     0x05 HELLO        payload = cmd \0 cwd \0 rootfs \0 term \0 interactive \0
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
 * Security: only accepts peers from 127.0.0.1 — same trust model as the
 * LocalApiServer /shell endpoint that the PRoot guest reaches over loopback.
 */

#define _GNU_SOURCE

#include <stdio.h>
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

/* Write all bytes; handles EINTR and EAGAIN (non-blocking fd) via short poll. */
static int write_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, buf + off, len - off);
        if (w > 0) { off += (size_t)w; continue; }
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && errno == EAGAIN) {
            struct pollfd pfd = { .fd = fd, .events = POLLOUT };
            if (poll(&pfd, 1, 2000) < 0) return -1;
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
                    write_all(master_fd, b, (size_t)len);
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
                    if (stdin_open) write_all(in_pipe[1], b, (size_t)len);
                } else if (t == F_STDIN_EOF) {
                    if (stdin_open) { close(in_pipe[1]); stdin_open = 0; }
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

    close(in_pipe[1]);
    close(out_pipe[0]);
    if (!client_dead) {
        send_exit(client_fd, (uint32_t)(exit_code & 0xFF));
        send_frame(client_fd, F_CLOSE, NULL, 0);
    }
    close(client_fd);
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

    char *cmd = hello;
    char *cwd = cmd + strlen(cmd) + 1;
    if (cwd >= hello + hlen) cwd = "";
    char *rootfs = cwd + strlen(cwd) + 1;
    if (rootfs >= hello + hlen) rootfs = "";
    char *term = rootfs + strlen(rootfs) + 1;
    if (term >= hello + hlen) term = "xterm-256color";
    char *itv = term + strlen(term) + 1;
    int interactive = 1;
    if (itv < hello + hlen) interactive = (itv[0] == '1') ? 1 : 0;

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

    printf("[ashell_pty] listening on 127.0.0.1:%d (filesDir=%s)\n", port, files_dir);
    fflush(stdout);

    for (;;) {
        struct sockaddr_in peer;
        socklen_t plen = sizeof(peer);
        int client_fd = accept(listen_fd, (struct sockaddr *)&peer, &plen);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            perror("[ashell_pty] accept");
            break;
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
            handle_client(client_fd, files_dir);
            _exit(0);
        }
        close(client_fd);
    }

    close(listen_fd);
    return 0;
}
