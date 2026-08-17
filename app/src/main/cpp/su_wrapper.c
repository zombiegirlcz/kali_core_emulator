/*
 * su_wrapper.c — Guest PRoot su/sudo Wrapper for NetHunter Host Root Escalation
 *
 * Deployed in PRoot guest as /usr/local/bin/su and /usr/local/bin/sudo
 * (shadowing the original binaries, which get renamed to .orig for fallback).
 * Connects to /run/host_ipc/magisk_daemon.sock, passes STDIN/STDOUT/STDERR
 * via SCM_RIGHTS, and forwards execution to the host daemon.
 * Reads exit status code from daemon and returns it to the caller.
 * Falls back to su.orig / sudo.orig if host daemon socket is unreachable.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <stdint.h>
#include <pty.h>
#include <sys/select.h>
#include <termios.h>
#include <signal.h>

#define PRIMARY_SOCKET "/run/host_ipc/magisk_daemon.sock"
#define SECONDARY_SOCKET "/data/data/com.linux_core/files/ipc/magisk_daemon.sock"
#define TERTIARY_SOCKET "/data/user/0/com.linux_core/files/ipc/magisk_daemon.sock"
#define BUFFER_SIZE 8192
#define MAX_ARGS 128

/* Returns basename of argv[0] (e.g. "su", "sudo", "su_wrapper"). */
static const char *invoked_name(const char *arg0) {
    const char *slash = strrchr(arg0, '/');
    return slash ? slash + 1 : arg0;
}

/* Detect bare su/sudo without -c: should run as interactive session. */
static int is_interactive_su(const char *name, int argc, char **argv) {
    if (strcmp(name, "su") != 0 && strcmp(name, "sudo") != 0) return 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-c") == 0) return 0;
        if (strcmp(argv[i], "--") == 0) break;
    }
    return 1;
}

static int send_fds_and_payload(int socket_fd, int *fds, int fd_count,
                                uint32_t target_uid, uint32_t target_gid,
                                const char *cwd, int argc, char **argv,
                                int interactive, int pty_master) {
    struct msghdr msg = {0};
    char control_buf[CMSG_SPACE(sizeof(int) * 5)];
    memset(control_buf, 0, sizeof(control_buf));

    char payload_buf[BUFFER_SIZE];
    memset(payload_buf, 0, sizeof(payload_buf));

    // Build payload protocol
    unsigned char *ptr = (unsigned char *)payload_buf;
    memcpy(ptr, &target_uid, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(ptr, &target_gid, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    uint32_t u_argc = (uint32_t)argc;
    memcpy(ptr, &u_argc, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    uint8_t flags = (interactive ? 0x01 : 0x00);
    memcpy(ptr, &flags, sizeof(uint8_t)); ptr += sizeof(uint8_t);

    // CWD
    size_t cwd_len = strlen(cwd);
    memcpy(ptr, cwd, cwd_len + 1); ptr += cwd_len + 1;

    // ARGV (the real command — wrapper name already stripped by caller)
    for (int i = 0; i < argc; i++) {
        size_t arg_len = strlen(argv[i]);
        if ((ptr + arg_len + 1) - (unsigned char *)payload_buf >= BUFFER_SIZE) break;
        memcpy(ptr, argv[i], arg_len + 1);
        ptr += arg_len + 1;
    }

    size_t payload_size = ptr - (unsigned char *)payload_buf;

    struct iovec io = { .iov_base = payload_buf, .iov_len = payload_size };
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control_buf;
    msg.msg_controllen = CMSG_LEN(sizeof(int) * (fd_count + (pty_master >= 0 ? 1 : 0)));

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int) * (fd_count + (pty_master >= 0 ? 1 : 0)));
    int idx = 0;
    for (int i = 0; i < fd_count; i++) {
        ((int *)CMSG_DATA(cmsg))[idx++] = fds[i];
    }
    if (pty_master >= 0) {
        ((int *)CMSG_DATA(cmsg))[idx++] = pty_master;
    }

    return sendmsg(socket_fd, &msg, 0) >= 0 ? 0 : -1;
}

/* ── SECURITY-CRITICAL fallback policy ──────────────────────────────────────
 *
 * When the host daemon is unreachable we must NEVER:
 *   - exec an interactive shell (that is how `nh fix permission` "switched to
 *     ashell" — the wrapper exec'd /bin/sh and the user got a stray shell),
 *   - exec an arbitrary argv[1] as a binary, nor
 *   - fall back to a HOST su/sudo (on a magisk/rooted device the .orig could
 *     be the real system su → REAL ROOT OUTSIDE PRoot confinement).
 *
 * The ONLY safe fallback is the in-rootfs .orig (guest-limited) when it is
 * actually present; everything else fails loudly with a message + exit 1.
 */
static int try_fallback(int argc, char **argv) {
    const char *name = invoked_name(argv[0]);
    int is_sudo = (strcmp(name, "sudo") == 0);
    int is_su = (strcmp(name, "su") == 0);

    /* In-guest .orig (if present) runs INSIDE PRoot → still confined.
     * Exec it directly as the invoke binary (su/sudo only). */
    if (is_sudo || is_su) {
        const char *orig = is_sudo ? "/usr/bin/sudo.orig" : "/usr/bin/su.orig";
        if (access(orig, X_OK) != 0 && is_su) orig = "/bin/su.orig";
        if (access(orig, X_OK) == 0) {
            argv[0] = (char *)orig;
            execvp(orig, argv);
            _exit(127);
        }
    }

    /* Daemon down and nothing safe to exec → fail loudly, exit 1. */
    fprintf(stderr,
        "[su_wrapper] CHYBA: magisk daemon nedostupný (socket %s, %s, %s)\n"
        "            Pro `nh fix permission` / `su` je potřeba spuštěný Root Bridge:\n"
        "            v aplikaci otevřete záložku Root Bridge a zapněte Start.\n"
        "            FALLBACK BLOKOVÁN (bezpečnost): žádný lokální su/shell.",
        PRIMARY_SOCKET, SECONDARY_SOCKET, TERTIARY_SOCKET);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 1) return 127;

    const char *name = invoked_name(argv[0]);
    int is_su = (strcmp(name, "su") == 0);
    int interactive = is_interactive_su(name, argc, argv);

    // ── Build the command to forward to the host daemon (strip wrapper name) ──
    char *cmd_argv[MAX_ARGS];
    memset(cmd_argv, 0, sizeof(cmd_argv));
    int cmd_argc = 0;

    /* nh fix permission <path> → su_wrapper --fix <path>
     * Sends a @FIX marker + path to the daemon, which rewrites ownership of
     * that guest path back to the app uid (host-side, confined to rootfs). */
    if (argc >= 3 && strcmp(argv[1], "--fix") == 0) {
        cmd_argv[0] = "@FIX";
        cmd_argv[1] = argv[2];
        cmd_argc = 2;
    } else if (is_su && argc > 1 && strcmp(argv[1], "-c") == 0 && argc > 2) {
        // su -c 'command' → run via host shell
        cmd_argv[0] = "/system/bin/sh";
        cmd_argv[1] = "-c";
        cmd_argv[2] = argv[2];
        cmd_argc = 3;
        interactive = 0;
    } else if (argc > 1) {
        // sudo <cmd> [args...] or su <cmd> [args...] or su_wrapper <cmd> [args...]
        for (int i = 1; i < argc && cmd_argc < MAX_ARGS - 1; i++) {
            cmd_argv[cmd_argc++] = argv[i];
        }
    } else {
        // Bare su/sudo → root shell on the host
        cmd_argv[0] = "/system/bin/sh";
        cmd_argc = 1;
    }
    cmd_argv[cmd_argc] = NULL;

    // ── Connect to host daemon (try each known socket path) ──
    const char *sock_candidates[] = {
        PRIMARY_SOCKET,
        SECONDARY_SOCKET,
        TERTIARY_SOCKET,
    };
    int sock_fd = -1;
    for (unsigned int i = 0; i < sizeof(sock_candidates) / sizeof(sock_candidates[0]); i++) {
        const char *cand = sock_candidates[i];
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) continue;
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, cand, sizeof(addr.sun_path) - 1);
        if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            sock_fd = fd;
            break;
        }
        close(fd);
    }

    if (sock_fd < 0) {
        return try_fallback(argc, argv);
    }

    // Get current working directory
    char cwd[1024] = {0};
    if (getcwd(cwd, sizeof(cwd)) == NULL) {
        strncpy(cwd, "/", sizeof(cwd) - 1);
    }

    int fds[3] = {STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO};
    uint32_t target_uid = 0;
    uint32_t target_gid = 0;

    int pty_master = -1;
    if (interactive) {
        int pty_slave;
        if (openpty(&pty_master, &pty_slave, NULL, NULL, NULL) == 0) {
            fds[0] = pty_slave;
            fds[1] = pty_slave;
            fds[2] = pty_slave;
        }
    }

    if (send_fds_and_payload(sock_fd, fds, 3, target_uid, target_gid, cwd, cmd_argc, cmd_argv, interactive, pty_master) < 0) {
        if (pty_master >= 0) close(pty_master);
        close(sock_fd);
        return try_fallback(argc, argv);
    }
    if (pty_master >= 0) close(fds[0]); /* slave no longer needed */

    if (interactive && pty_master >= 0) {
        unsigned char buf[BUFFER_SIZE];
        fd_set rfds;
        int maxfd = (sock_fd > pty_master ? sock_fd : pty_master) + 1;
        int client_closed = 0;
        while (1) {
            FD_ZERO(&rfds);
            FD_SET(sock_fd, &rfds);
            FD_SET(pty_master, &rfds);
            int r = select(maxfd, &rfds, NULL, NULL, NULL);
            if (r <= 0) break;
            if (FD_ISSET(sock_fd, &rfds)) {
                ssize_t n = read(sock_fd, buf, sizeof(buf));
                if (n <= 0) { client_closed = 1; break; }
                if (write(STDOUT_FILENO, buf, n) != n) break;
            }
            if (FD_ISSET(pty_master, &rfds)) {
                ssize_t n = read(pty_master, buf, sizeof(buf));
                if (n <= 0) break;
                if (write(STDIN_FILENO, buf, n) != n) break;
            }
        }
        close(pty_master);
        close(sock_fd);
        /* Daemon sends exit code only for non-interactive; interactive
         * session exit is reflected by EOF on pty_master/sock_fd. */
        return client_closed ? 0 : 130;
    }

    // Read exit status code from host daemon
    int exit_code = 0;
    ssize_t res = read(sock_fd, &exit_code, sizeof(exit_code));
    close(sock_fd);

    if (res == sizeof(exit_code)) {
        return exit_code;
    }

    return 0;
}
