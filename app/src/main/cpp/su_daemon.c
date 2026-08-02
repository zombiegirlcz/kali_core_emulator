/*
 * su_daemon.c — Host Root Daemon for NetHunter PRoot Privilege Escalation
 *
 * Listens on a UNIX domain socket. When a request is received:
 * 1. Receives standard FDs (stdin, stdout, stderr) via SCM_RIGHTS.
 * 2. Receives target UID/GID, CWD, and command arguments.
 * 3. Forks a child process.
 * 4. In child: switches UID/GID, duplicates FDs to 0,1,2, changes directory, and execs command.
 * 5. In parent: waits for child, receives exit code, and returns exit code to client wrapper.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdint.h>

#define DEFAULT_SOCKET_PATH "/data/data/com.linux_core/files/ipc/magisk_daemon.sock"
#define MAX_ARGS 128
#define BUFFER_SIZE 8192

static int recv_fds_and_payload(int socket_fd, int *fds, int max_fds,
                                uint32_t *target_uid, uint32_t *target_gid,
                                char *cwd, size_t cwd_size,
                                char **argv, int max_args) {
    struct msghdr msg = {0};
    char control_buf[CMSG_SPACE(sizeof(int) * max_fds)];
    memset(control_buf, 0, sizeof(control_buf));

    char payload_buf[BUFFER_SIZE];
    memset(payload_buf, 0, sizeof(payload_buf));

    struct iovec io = { .iov_base = payload_buf, .iov_len = sizeof(payload_buf) - 1 };
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = control_buf;
    msg.msg_controllen = sizeof(control_buf);

    ssize_t n = recvmsg(socket_fd, &msg, 0);
    if (n <= 0) {
        return -1;
    }

    // Extract file descriptors
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    int fd_count = 0;
    if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
        int *fd_ptr = (int *)CMSG_DATA(cmsg);
        int total_bytes = cmsg->cmsg_len - CMSG_LEN(0);
        fd_count = total_bytes / sizeof(int);
        for (int i = 0; i < fd_count && i < max_fds; i++) {
            fds[i] = fd_ptr[i];
        }
    }

    if (fd_count < 3) {
        return -1;
    }

    // Parse payload protocol:
    // [uint32_t target_uid][uint32_t target_gid][uint32_t argc][null-terminated cwd][null-terminated arg0]...
    unsigned char *ptr = (unsigned char *)payload_buf;
    unsigned char *end = (unsigned char *)payload_buf + n;

    if ((size_t)(end - ptr) < sizeof(uint32_t) * 3) {
        return -1;
    }

    memcpy(target_uid, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(target_gid, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    uint32_t argc = 0;
    memcpy(&argc, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    // Read CWD
    if (ptr >= end) return -1;
    size_t remaining = end - ptr;
    size_t cwd_len = strnlen((char *)ptr, remaining);
    if (cwd_len >= remaining) {
        return -1;
    }

    if (cwd_len < cwd_size) {
        strncpy(cwd, (char *)ptr, cwd_size - 1);
        cwd[cwd_size - 1] = '\0';
    } else {
        cwd[0] = '\0';
    }
    ptr += cwd_len + 1;

    // Read ARGV
    int arg_index = 0;
    while (arg_index < (int)argc && arg_index < max_args - 1 && ptr < end) {
        remaining = end - ptr;
        size_t arg_len = strnlen((char *)ptr, remaining);
        if (arg_len >= remaining) {
            break;
        }
        argv[arg_index] = strdup((char *)ptr);
        ptr += arg_len + 1;
        arg_index++;
    }
    argv[arg_index] = NULL;

    return 0;
}

int main(int argc, char **argv) {
    const char *socket_path = DEFAULT_SOCKET_PATH;
    if (argc > 1) {
        socket_path = argv[1];
    }

    // Ensure socket parent directory exists
    char dir_buf[1024];
    strncpy(dir_buf, socket_path, sizeof(dir_buf) - 1);
    dir_buf[sizeof(dir_buf) - 1] = '\0';
    char *last_slash = strrchr(dir_buf, '/');
    if (last_slash) {
        *last_slash = '\0';
        mkdir(dir_buf, 0755);
    }

    unlink(socket_path);

    int listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        perror("[su_daemon] socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    if (bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("[su_daemon] bind");
        close(listen_fd);
        return 1;
    }

    chmod(socket_path, 0777);

    if (listen(listen_fd, 10) < 0) {
        perror("[su_daemon] listen");
        close(listen_fd);
        return 1;
    }

    // Write PID file (<socket_dir>/su_daemon.pid) so the app can track us
    {
        char pid_path[1100];
        strncpy(pid_path, socket_path, sizeof(pid_path) - 1);
        pid_path[sizeof(pid_path) - 1] = '\0';
        char *ls = strrchr(pid_path, '/');
        if (ls) {
            strncpy(ls + 1, "su_daemon.pid", sizeof(pid_path) - (size_t)(ls + 1 - pid_path) - 1);
            pid_path[sizeof(pid_path) - 1] = '\0';
        } else {
            strncpy(pid_path, "su_daemon.pid", sizeof(pid_path) - 1);
        }
        int pidfd = open(pid_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (pidfd >= 0) {
            char pids[32];
            int plen = snprintf(pids, sizeof(pids), "%d\n", (int)getpid());
            (void)!write(pidfd, pids, (size_t)plen);
            close(pidfd);
            printf("[su_daemon] PID file: %s\n", pid_path);
            fflush(stdout);
        }
    }

    printf("[su_daemon] Listening on UNIX socket: %s (PID=%d)\n", socket_path, getpid());
    fflush(stdout);

    while (1) {
        int client_fd = accept(listen_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            perror("[su_daemon] accept");
            break;
        }

        int fds[3] = {-1, -1, -1};
        uint32_t target_uid = 0, target_gid = 0;
        char cwd[BUFFER_SIZE] = {0};
        char *cmd_argv[MAX_ARGS] = {NULL};

        if (recv_fds_and_payload(client_fd, fds, 3, &target_uid, &target_gid, cwd, sizeof(cwd), cmd_argv, MAX_ARGS) < 0) {
            close(client_fd);
            continue;
        }

        pid_t pid = fork();
        if (pid < 0) {
            perror("[su_daemon] fork");
            close(fds[0]); close(fds[1]); close(fds[2]);
            int err_code = 1;
            write(client_fd, &err_code, sizeof(err_code));
            close(client_fd);
            continue;
        }

        if (pid == 0) {
            // Child process
            close(listen_fd);

            // Redirect STDIN, STDOUT, STDERR
            dup2(fds[0], STDIN_FILENO);
            dup2(fds[1], STDOUT_FILENO);
            dup2(fds[2], STDERR_FILENO);

            close(fds[0]); close(fds[1]); close(fds[2]);

            if (cwd[0] != '\0') {
                chdir(cwd);
            }

            // Switch GID & UID
            if (setresgid(target_gid, target_gid, target_gid) < 0) {
                perror("[su_daemon] setresgid");
            }
            if (setresuid(target_uid, target_uid, target_uid) < 0) {
                perror("[su_daemon] setresuid");
            }

            if (cmd_argv[0] != NULL) {
                execvp(cmd_argv[0], cmd_argv);
                perror("[su_daemon] execvp failed");
            }
            _exit(127);
        }

        // Parent process: wait for child to finish so we can return exit code
        close(fds[0]); close(fds[1]); close(fds[2]);

        int status = 0;
        int exit_code = 0;
        if (waitpid(pid, &status, 0) > 0) {
            if (WIFEXITED(status)) {
                exit_code = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code = 128 + WTERMSIG(status);
            }
        }

        // Send exit code back to client wrapper
        write(client_fd, &exit_code, sizeof(exit_code));
        close(client_fd);

        // Free cmd_argv strings
        for (int i = 0; cmd_argv[i] != NULL; i++) {
            free(cmd_argv[i]);
        }
    }

    close(listen_fd);
    unlink(socket_path);
    {
        char pid_path[1100];
        strncpy(pid_path, socket_path, sizeof(pid_path) - 1);
        pid_path[sizeof(pid_path) - 1] = '\0';
        char *ls = strrchr(pid_path, '/');
        if (ls) {
            strncpy(ls + 1, "su_daemon.pid", sizeof(pid_path) - (size_t)(ls + 1 - pid_path) - 1);
            pid_path[sizeof(pid_path) - 1] = '\0';
        }
        unlink(pid_path);
    }
    return 0;
}
