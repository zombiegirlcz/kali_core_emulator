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

#define PRIMARY_SOCKET "/run/host_ipc/magisk_daemon.sock"
#define SECONDARY_SOCKET "/data/data/com.linux_core/files/ipc/magisk_daemon.sock"
#define BUFFER_SIZE 8192
#define MAX_ARGS 128

/* Returns basename of argv[0] (e.g. "su", "sudo", "su_wrapper"). */
static const char *invoked_name(const char *arg0) {
    const char *slash = strrchr(arg0, '/');
    return slash ? slash + 1 : arg0;
}

static int send_fds_and_payload(int socket_fd, int *fds, int fd_count,
                                uint32_t target_uid, uint32_t target_gid,
                                const char *cwd, int argc, char **argv) {
    struct msghdr msg = {0};
    char control_buf[CMSG_SPACE(sizeof(int) * 3)];
    memset(control_buf, 0, sizeof(control_buf));

    char payload_buf[BUFFER_SIZE];
    memset(payload_buf, 0, sizeof(payload_buf));

    // Build payload protocol
    unsigned char *ptr = (unsigned char *)payload_buf;
    memcpy(ptr, &target_uid, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(ptr, &target_gid, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    uint32_t u_argc = (uint32_t)argc;
    memcpy(ptr, &u_argc, sizeof(uint32_t)); ptr += sizeof(uint32_t);

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
    msg.msg_controllen = CMSG_LEN(sizeof(int) * fd_count);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int) * fd_count);
    memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * fd_count);

    return sendmsg(socket_fd, &msg, 0) >= 0 ? 0 : -1;
}

static void try_fallback(int argc, char **argv) {
    const char *name = invoked_name(argv[0]);
    int is_sudo = (strcmp(name, "sudo") == 0);
    int is_su = (strcmp(name, "su") == 0);

    if (is_sudo) {
        const char *orig = "/usr/bin/sudo.orig";
        if (access(orig, X_OK) == 0) {
            argv[0] = (char *)orig;
            execvp(orig, argv);
        }
    } else if (is_su) {
        const char *orig = "/usr/bin/su.orig";
        if (access(orig, X_OK) != 0) orig = "/bin/su.orig";
        if (access(orig, X_OK) == 0) {
            argv[0] = (char *)orig;
            execvp(orig, argv);
        }
    }

    // Generic wrapper invocation: su_wrapper <cmd> [args...] — run cmd directly
    if (argc > 1) {
        execvp(argv[1], &argv[1]);
    }

    // Last resort shell
    char *shell_args[] = {"/bin/sh", NULL};
    execvp("/bin/sh", shell_args);
    _exit(127);
}

int main(int argc, char **argv) {
    if (argc < 1) return 127;

    const char *name = invoked_name(argv[0]);
    int is_su = (strcmp(name, "su") == 0);
    int is_sudo = (strcmp(name, "sudo") == 0);

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

    // ── Connect to host daemon ──
    const char *sock_path = PRIMARY_SOCKET;
    if (access(sock_path, F_OK) != 0) {
        sock_path = SECONDARY_SOCKET;
    }

    int sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock_fd < 0) {
        try_fallback(argc, argv);
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (connect(sock_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(sock_fd);
        try_fallback(argc, argv);
        return 1;
    }

    // Get current working directory
    char cwd[1024] = {0};
    if (getcwd(cwd, sizeof(cwd)) == NULL) {
        strncpy(cwd, "/", sizeof(cwd) - 1);
    }

    int fds[3] = {STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO};
    uint32_t target_uid = 0; // Default root
    uint32_t target_gid = 0;

    if (send_fds_and_payload(sock_fd, fds, 3, target_uid, target_gid, cwd, cmd_argc, cmd_argv) < 0) {
        close(sock_fd);
        try_fallback(argc, argv);
        return 1;
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
