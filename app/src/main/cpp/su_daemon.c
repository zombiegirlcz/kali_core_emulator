/*
 * su_daemon.c — Host Root Daemon for NetHunter PRoot Privilege Escalation
 *
 * Listens on a UNIX domain socket. When a request is received:
 * 1. Receives standard FDs (stdin, stdout, stderr) via SCM_RIGHTS.
 * 2. Receives target UID/GID, CWD, and command arguments.
 * 3. Forks a child process.
 * 4. In child: duplicates FDs to 0,1,2 and RE-ENTERS the PRoot guest sandbox
 *    (launcher.sh -- <command...>) under real root. The command therefore runs
 *    INSIDE the guest rootfs and can never touch the host filesystem directly.
 *    If no PRoot launcher is configured the request is refused (FAIL CLOSED).
 * 5. In parent: waits for child (proot --kill-on-exit reaps guest children), and
 *    returns the exit code to client wrapper.
 */

#define _GNU_SOURCE

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
#include <limits.h>
#include <dirent.h>
#include <sys/time.h>
#include <poll.h>
#include <termios.h>
#include <sys/ioctl.h>

#define DEFAULT_SOCKET_PATH "/data/user/0/com.linux_core/files/ipc/magisk_daemon.sock"
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
    // Optional extension (appended at end): [uint32_t term_len][term bytes]
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

    // Parse optional TERM field (new protocol extension, appended after argv)
    g_term[0] = '\0';
    size_t remaining = end - ptr;
    if (remaining >= sizeof(uint32_t)) {
        uint32_t term_len;
        memcpy(&term_len, ptr, sizeof(uint32_t));
        if (term_len > 0 && term_len <= remaining - sizeof(uint32_t) && term_len < sizeof(g_term) - 1) {
            ptr += sizeof(uint32_t);
            memcpy(g_term, ptr, term_len);
            g_term[term_len] = '\0';
            ptr += term_len;
        }
    }

    return 0;
}

/* ── Safety guard: deny destructive / host-global commands ─────────────────── */
static const char *cmd_basename(const char *p) {
    const char *slash = strrchr(p, '/');
    return slash ? slash + 1 : p;
}

static int cmd_has_recursive_flag(char **argv) {
    for (int i = 1; argv[i]; i++) {
        if (argv[i][0] != '-' || argv[i][1] == '\0') continue;
        if (strcmp(argv[i], "--") == 0) break;
        for (const char *p = argv[i] + 1; *p; p++)
            if (*p == 'r' || *p == 'R') return 1;
    }
    return 0;
}

/* Strip leading/trailing shell quote chars so 'rm -rf "/"' == 'rm -rf /'
 * (blocks quote-evasion of the whole-root wipe guard). Never follows content. */
static void strip_shell_quotes(char *t) {
    size_t n = strlen(t);
    while (n > 0 && (t[0] == '\'' || t[0] == '"')) { t++; n--; }
    while (n > 0 && (t[n-1] == '\'' || t[n-1] == '"')) { t[--n] = '\0'; }
}

/* Tokenize-scan a shell "-c" payload for classic whole-root destructive ops. */
static int deny_shell_payload(const char *s) {
    if (!s) return 0;
    char buf[BUFFER_SIZE];
    strncpy(buf, s, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    char *tokens[64];
    int nt = 0;
    char *save = NULL;
    for (char *tok = strtok_r(buf, " \t\n;|&", &save); tok && nt < 64; tok = strtok_r(NULL, " \t\n;|&", &save)) {
        strip_shell_quotes(tok);
        tokens[nt++] = tok;
    }
    int has_danger = 0, has_rec = 0, hits_root = 0;
    for (int i = 0; i < nt; i++) {
        const char *b = cmd_basename(tokens[i]);
        if (strcmp(b, "chmod") == 0 || strcmp(b, "chown") == 0 || strcmp(b, "chgrp") == 0 ||
            strcmp(b, "rm") == 0 || strcmp(b, "rmdir") == 0)
            has_danger = 1;
        if (tokens[i][0] == '-' && (strchr(tokens[i], 'r') || strchr(tokens[i], 'R')))
            has_rec = 1;
        if (strcmp(tokens[i], "/") == 0 || strcmp(tokens[i], "/*") == 0)
            hits_root = 1;
    }
    return (has_danger && has_rec && hits_root) ? 1 : 0;
}

/* Deny a command: returns 1 when it must be blocked (exit code 126 to caller). */
static int deny_command(char **argv) {
    if (argv[0] == NULL) return 0;
    const char *base = cmd_basename(argv[0]);

    /* 1) Raw host-global / block-device / power-control binaries — always deny. */
    static const char *banned[] = {
        "dd", "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.f2fs",
        "mkfs.vfat", "mkfs.xfs", "mkfs.btrfs", "mkfs.ntfs", "mkfs.minix",
        "mkfs.cramfs", "mkfs.erofs", "mke2fs", "fsck", "fsck.ext2", "fsck.ext4",
        "e2fsck", "tune2fs", "resize2fs", "fdisk", "parted", "sfdisk", "cfdisk",
        "gdisk", "sgdisk", "losetup", "mount", "umount", "swapon", "swapoff",
        "cryptsetup", "pvcreate", "vgcreate", "lvcreate", "vgremove", "lvremove",
        "raw", "blockdev", "reboot", "poweroff", "halt", "shutdown", "init", "telinit",
        NULL
    };
    for (int i = 0; banned[i]; i++)
        if (strcmp(base, banned[i]) == 0) return 1;
    if (strncmp(base, "mkfs.", 5) == 0) return 1;

    /* Shell "-c" payloads (su -c / sh -c forms) — scan for classic root-wipe. */
    for (int i = 1; argv[i]; i++) {
        if (strcmp(argv[i], "-c") == 0 && argv[i + 1] != NULL) {
            if (deny_shell_payload(argv[i + 1])) return 1;
        }
    }

    int recursive = cmd_has_recursive_flag(argv);

    /* 2) Recursive chmod/chown/chgrp touching the root — deny. */
    if (strcmp(base, "chmod") == 0 || strcmp(base, "chown") == 0 || strcmp(base, "chgrp") == 0) {
        const char *target = NULL;
        for (int i = 1; argv[i]; i++) {
            if (argv[i][0] == '-' && argv[i][1] != '\0' && argv[i][1] != '-') continue;
            if (argv[i][0] == '-') continue;
            target = argv[i]; /* last non-option = first file operand */
        }
        if (recursive && (target == NULL || strcmp(target, "/") == 0)) return 1;
        return 0;
    }

    /* 3) rm/rmdir on "/" or broad wildcard at root — deny. */
    if (strcmp(base, "rm") == 0 || strcmp(base, "rmdir") == 0) {
        for (int i = 1; argv[i]; i++) {
            if (argv[i][0] == '-') continue;
            if (strcmp(argv[i], "/") == 0) return 1;
            if (recursive && (strcmp(argv[i], "*") == 0 || strcmp(argv[i], "/*") == 0)) return 1;
        }
        return 0;
    }

    return 0;
}

/* ── Permission fix (host-side, OUTSIDE PRoot) ──────────────────────────────
 *
 * Commands run under real root create files owned by uid/gid 0 inside the
 * guest rootfs, which hides them from the app process (its own UID can no
 * longer read/modify them). This fix rewrites ownership back to the app UID.
 *
 * It MUST run on the HOST (not inside PRoot): inside PRoot, the rootfs shows
 * bind mounts (/sdcard, /dev, /proc, /sys, /run/host_ipc, ...) and a recursive
 * chown would wipe access to real user data -> catastrophe. On the host the
 * bind targets are just (empty) dirs under the rootfs, so we see the REAL
 * owner and can safely fix only the guest filesystem. */

/* Top-level bind / host-mapped dirs we never descend into, even on the host. */
static int is_bind_dir(const char *base) {
    static const char *skip[] = {
        "dev", "proc", "sys", "run", "sdcard", "mnt",
        "system", "vendor", "product", "apex", "storage", "data", NULL
    };
    for (int i = 0; skip[i]; i++)
        if (strcmp(base, skip[i]) == 0) return 1;
    return 0;
}

static uid_t fix_uid = (uid_t)-1;
static gid_t fix_gid = (gid_t)-1;
static int  fix_skip_top = 0;

/* File-scope copies of main()'s config — potřebné ve worker procesech
 * (fork-per-connection), které běží mimo stack main(). */
static char g_launcher_path[1024] = {0};
static char g_term[128] = {0};
static char g_rootfs_path[1024] = {0};
static uid_t g_app_uid = (uid_t)-1;
static gid_t g_app_gid = (gid_t)-1;
static int g_auto_fix = 1;

static int fix_walk(const char *path, int depth) {
    /* Top-level bind / host-mapped dirs: skip the whole subtree entirely
     * (don't even chown the dir — that would hit the host dir target). */
    if (fix_skip_top && depth == 1) {
        const char *base = strrchr(path, '/');
        base = base ? base + 1 : path;
        if (is_bind_dir(base)) return 0;
    }
    struct stat st;
    if (lstat(path, &st) != 0) return 0; /* missing/ENOENT/EACCES — keep going */
    if (st.st_uid != fix_uid || st.st_gid != fix_gid) {
        /* lchown: nikdy nenásleduje symlink mimo scope. */
        lchown(path, fix_uid, fix_gid);
    }
    /* Rekurze jen do skutečných adresářů. Symlinky nejsou S_ISDIR -> nikdy
     * se nenásledují => žádný escape ani symlink-loop. */
    if (!S_ISDIR(st.st_mode)) return 0;
    DIR *d = opendir(path);
    if (!d) return 0; /* nečitelný adresář — přeskoč tiše */
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) continue;
        char child[PATH_MAX];
        int n = snprintf(child, sizeof(child), "%s/%s", path, ent->d_name);
        if (n < 0 || (size_t)n >= sizeof(child)) continue; /* moc smugl — preskoc */
        fix_walk(child, depth + 1);
    }
    closedir(d);
    return 0;
}

/* Recursively rewrite ownership of a host path to the app uid/gid. */
static void fix_permissions(const char *scope, uid_t uid, gid_t gid, int skip_top) {
    fix_uid = uid;
    fix_gid = gid;
    fix_skip_top = skip_top;
    if (scope == NULL || scope[0] == '\0') return;
    if (strlen(scope) >= PATH_MAX) return;
    char root[PATH_MAX];
    snprintf(root, sizeof(root), "%s", scope);
    fix_walk(root, 0);
}

/* Handle `nh fix permission <path>`: @FIX payload from su_wrapper. */
static int handle_fix_request(int client_fd, int *fds, char **argv,
                              const char *rootfs, uid_t uid, gid_t gid) {

    if (rootfs == NULL || rootfs[0] == '\0') {
        dprintf(fds[2], "[su_daemon] FATAL: no rootfs configured for fix.\n");
        write(client_fd, &(int){126}, sizeof(int));
        return 126;
    }
    const char *path = argv[1];
    if (path == NULL || path[0] != '/') {
        dprintf(fds[2], "[su_daemon] fix: absolutní cesta vyžadována (např. /root/cil/dir).\n");
        write(client_fd, &(int){126}, sizeof(int));
        return 126;
    }
    if (strcmp(path, "/") == 0) {
        dprintf(fds[2], "[su_daemon] fix: příliš široký cíl '/'; použij konkrétní složku/soubor.\n");
        write(client_fd, &(int){126}, sizeof(int));
        return 126;
    }

    /* Reject any bind / host-mapped root (the user's core concern). */
    const char *first = path + 1;
    size_t flen = strcspn(first, "/");
    char comp[128];
    if (flen < sizeof(comp)) {
        memcpy(comp, first, flen);
        comp[flen] = '\0';
        if (is_bind_dir(comp)) {
            dprintf(fds[2], "[su_daemon] fix: odmítnuto — %s je bind/host mount (mimo guest rootfs).\n", path);
            write(client_fd, &(int){126}, sizeof(int));
            return 126;
        }
    }

    /* Host path = rootfs + guest path. Trim a trailing rootfs slash. */
    char host[PATH_MAX];
    size_t rl0 = strlen(rootfs);
    while (rl0 > 1 && rootfs[rl0 - 1] == '/') rl0--;
    snprintf(host, sizeof(host), "%.*s%s", (int)rl0, rootfs, path);

    /* Verify the resolved path stays within the rootfs (blocks symlink escape
     * to /sdcard or other host dirs). If it does not exist yet: nothing to fix. */
    char *rroot = realpath(rootfs, NULL);
    char *rtarget = realpath(host, NULL);
    if (!rroot) {
        free(rtarget);
        write(client_fd, &(int){126}, sizeof(int));
        return 126;
    }
    if (!rtarget) {
        free(rroot);
        write(client_fd, &(int){0}, sizeof(int)); /* nothing exists — OK */
        return 0;
    }
    size_t rrl = strlen(rroot);
    int contained = strncmp(rtarget, rroot, rrl) == 0 &&
                    (rtarget[rrl] == '\0' || rtarget[rrl] == '/');
    free(rroot);
    if (!contained) {
        free(rtarget);
        dprintf(fds[2], "[su_daemon] fix: cesta uniká z rootfs (symlink?) — odmítnuto.\n");
        write(client_fd, &(int){126}, sizeof(int));
        return 126;
    }

    fix_permissions(rtarget, uid, gid, 0);
    free(rtarget);
    write(client_fd, &(int){0}, sizeof(int));
    return 0;
}

/* forward decl — definice je za main() (fork-per-connection worker) */
static void handle_client(int client_fd);

/* Reaper pro worker child processes: bez tohohle by každý fork-per-connection
 * worker po skončení zůstal jako zombie (parent ho nikdy nečeká). SIGCHLD
 * handler s waitpid(-1, WNOHANG) posbírá všechny skončené workery.
 * POZN: worker si hned po forku resetuje SIGCHLD na SIG_DFL, aby mohl sám
 * waitpid() svého command childa a získat exit kód. */
static void sigchld_reaper(int sig) {
    (void)sig;
    int saved_errno = errno;
    while (waitpid(-1, NULL, WNOHANG) > 0) { }
    errno = saved_errno;
}

/* ── PTY bridge helpers (interactive binaries) ─────────────────────────────
 * Interactive programs (editors, pagers, shells, sudo/passwd via /dev/tty,
 * SIGINT from ctrl-c, job control) need a real controlling terminal. The
 * command runs as a separate process tree (host daemon, real root) so it can
 * never share the guest shell's controlling tty. We therefore give it its OWN
 * pty and relay bytes between that pty and the guest PTY. The guest PTY is
 * switched to raw only for the command's duration and fully restored after. */

#ifndef TIOCSCTTY
#define TIOCSCTTY 0x540E
#endif

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

static void set_block(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl & ~O_NONBLOCK);
}

/* Write all bytes; handles EAGAIN on non-blocking fds via short poll. */
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

int main(int argc, char **argv) {
    const char *socket_path = DEFAULT_SOCKET_PATH;
    char launcher_path[1024] = {0};
    char rootfs_path[1024] = {0};
    uid_t app_uid = (uid_t)-1;
    gid_t app_gid = (gid_t)-1;
    int auto_fix = 1; /* rewrite root-owned files back to the app uid after each command */

    if (argc > 1) {
        socket_path = argv[1];
    }
    /* argv[2] = path of the guest PRoot launcher script (e.g. launcher-kali.sh).
     * Commands are NEVER run on the bare host: we re-enter PRoot through this
     * launcher so real root stays confined to the guest rootfs. If it is not
     * configured the daemon refuses to run (FAIL CLOSED). */
    if (argc > 2 && argv[2][0] != '\0') {
        strncpy(launcher_path, argv[2], sizeof(launcher_path) - 1);
    }
    /* argv[3] = guest rootfs dir (host path) — needed for the ownership fix. */
    if (argc > 3 && argv[3][0] != '\0') {
        strncpy(rootfs_path, argv[3], sizeof(rootfs_path) - 1);
    }
    /* argv[4]/[5] = app uid/gid: after a real-root command, files the command
     * created are owned by root; the fix rewrites them back to this uid/gid. */
    if (argc > 4 && argv[4][0] != '\0') app_uid = (uid_t)strtoul(argv[4], NULL, 10);
    if (argc > 5 && argv[5][0] != '\0') app_gid = (gid_t)strtoul(argv[5], NULL, 10);
    /* argv[6] = auto-fix on/off ("1"/"0"). */
    if (argc > 6 && argv[6][0] != '\0') auto_fix = atoi(argv[6]);

    if (app_uid == (uid_t)-1) app_uid = 0;   /* safe fallback: no-op chown to root */
    if (app_gid == (gid_t)-1) app_gid = 0;

    /* Propis config do file-scope globálů — worker (fork-per-connection) běží
     * mimo stack main(), nemá přístup k lokálním proměnným. */
    strncpy(g_launcher_path, launcher_path, sizeof(g_launcher_path) - 1);
    g_launcher_path[sizeof(g_launcher_path) - 1] = '\0';
    strncpy(g_rootfs_path, rootfs_path, sizeof(g_rootfs_path) - 1);
    g_rootfs_path[sizeof(g_rootfs_path) - 1] = '\0';
    g_app_uid = app_uid;
    g_app_gid = app_gid;
    g_auto_fix = auto_fix;

    /* ── Self-daemonize ────────────────────────────────────────────────────
     * When started from a shell (`su -c '... '` with &) the daemon dies with
     * SIGHUP the moment the launching shell exits. Detach into our own
     * session so the process survives: fork → parent exits → child calls
     * setsid() and keeps stdin on /dev/null. stdout/stderr stay attached
     * (the caller redirects them to su_daemon.log).
     * Debug escape: `NH_DAEMON_FG=1` běží v popředí bez fork/daemonizace. */
    if (getenv("NH_DAEMON_FG") == NULL) {
        pid_t dfork = fork();
        if (dfork < 0) {
            perror("[su_daemon] fork (daemonize)");
            return 1;
        }
        if (dfork > 0) _exit(0);   /* rodič končí — shell se vrací ihned */
        /* child: nová session, osamostatněný */
        setsid();
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            /* stdout/stderr zůstávají na logu, který přesměroval volající shell */
            if (devnull > STDERR_FILENO) close(devnull);
        }
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

    fflush(stdout);

    /* ── Fork-per-connection ──────────────────────────────────────────────
     * Daemon je single-threaded. Kdyby celý request (recv → waitpid → 25 s
     * auto-fix → exit code) běžel v hlavní smyčce, každý pomalejší příkaz
     * (wifi scan, interactive shell, ...) by zablokoval accept() a VŠECHNA
     * nová `su`/`sudo` spojení by visela v backlogu — projev: „příkaz zůstal
     * stát“, nedá se provést ani pkill přes su (šel by přes zablokovaný daemon).
     * Řešení: každé spojení obslouží forknutý worker; parent se okamžitě vrací
     * k accept, takže daemon přijímá další požadavky i během běhu/návratu
     * libovolného příkazu. Worker navíc hlídá client socket (POLLHUP): když
     * su_wrapper zemře (ctrl+c), command child dostane SIGKILL a daemon se
     * uvolní okamžitě, ne až po waitpid nekonečného příkazu. */
    signal(SIGPIPE, SIG_IGN);

    /* Reaper pro worker child processes — zabraňuje hromadění zombie. */
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
            perror("[su_daemon] accept");
            break;
        }

        pid_t worker = fork();
        if (worker < 0) {
            perror("[su_daemon] fork worker");
            close(client_fd);
            continue;
        }
        if (worker == 0) {
            /* worker: obslouží právě toto spojení, pak končí.
             * Reset SIGCHLD na default, aby waitpid() na command childa
             * fungoval správně (dědičný reaper by ho sebral dřív). */
            signal(SIGCHLD, SIG_DFL);
            close(listen_fd);
            handle_client(client_fd);
            _exit(0);
        }
        /* parent: vlastní kopii client_fd zavře, jde zase na accept */
        close(client_fd);
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

/* ── Obsluha jednoho spojení (worker proces) ──────────────────────────────
 * Volá se z forknutého workera: čte payload + fds od su_wrapperu, vyřeší
 * deny/@FIX, spustí command jako child (launcher re-entry) a vrátí exit code.
 * POLLHUP na client_fd = klient zmizel → child dostane SIGKILL, worker skončí
 * (bez auto-fix a bez psaní exit kódu na mrtvý socket). */
static void handle_client(int client_fd) {
    int fds[3] = {-1, -1, -1};
    uint32_t target_uid = 0, target_gid = 0;
    char cwd[BUFFER_SIZE] = {0};
    char *cmd_argv[MAX_ARGS] = {NULL};

    if (recv_fds_and_payload(client_fd, fds, 3, &target_uid, &target_gid, cwd, sizeof(cwd), cmd_argv, MAX_ARGS) < 0) {
        close(fds[0]); close(fds[1]); close(fds[2]);
        close(client_fd);
        for (int i = 0; cmd_argv[i] != NULL; i++) free(cmd_argv[i]);
        return;
    }

    if (deny_command(cmd_argv)) {
        int deny_code = 126; /* 126 = command not permitted */
        write(client_fd, &deny_code, sizeof(deny_code));
        close(fds[0]); close(fds[1]); close(fds[2]);
        close(client_fd);
        for (int i = 0; cmd_argv[i] != NULL; i++) free(cmd_argv[i]);
        return;
    }

    /* Manual fix request from `nh fix permission <path>` (su_wrapper --fix). */
    if (cmd_argv[0] != NULL && strcmp(cmd_argv[0], "@FIX") == 0) {
        handle_fix_request(client_fd, fds, cmd_argv, g_rootfs_path, g_app_uid, g_app_gid);
        close(fds[0]); close(fds[1]); close(fds[2]);
        close(client_fd);
        for (int i = 0; cmd_argv[i] != NULL; i++) free(cmd_argv[i]);
        return;
    }

    int guest_fd = fds[0];
    int use_bridge = isatty(guest_fd);
    struct termios saved_term;
    int have_term = (use_bridge && tcgetattr(guest_fd, &saved_term) == 0);
    struct winsize gw;
    if (ioctl(guest_fd, TIOCGWINSZ, &gw) < 0) {
        gw.ws_row = 24; gw.ws_col = 80; gw.ws_xpixel = 0; gw.ws_ypixel = 0;
    }

    int master_fd = -1, slave_fd = -1;
    if (use_bridge && open_pty(&master_fd, &slave_fd) < 0) {
        use_bridge = 0; /* pty unavailable → degraded direct (pipe-like) mode */
    }

    if (use_bridge) {
        /* Guest PTY → raw so INTR/EOF reach the relay as plain bytes; the
         * command gets its OWN pty (its controlling terminal), so /dev/tty,
         * ctrl-c (SIGINT) and job control all work. Guest PTY termios is
         * fully restored when the command exits (see cleanup below). */
        if (have_term) {
            struct termios raw = saved_term;
            cfmakeraw(&raw);
            tcsetattr(guest_fd, TCSANOW, &raw);
        }
        set_nonblock(guest_fd);
        set_nonblock(master_fd);

        pid_t pid = fork();
        if (pid < 0) {
            /* bridge setup failed → restore guest tty and fall back to direct */
            if (have_term) tcsetattr(guest_fd, TCSANOW, &saved_term);
            set_block(guest_fd);
            close(master_fd); close(slave_fd);
            use_bridge = 0;
        } else if (pid == 0) {
            /* command child: own session + fresh controlling pty */
            close(client_fd);
            close(guest_fd);
            close(fds[1]);
            close(fds[2]);
            close(master_fd);

            if (setsid() < 0) {
                setpgid(0, 0);
                setsid();
            }
            ioctl(slave_fd, TIOCSCTTY, 0);
            if (have_term) tcsetattr(slave_fd, TCSANOW, &saved_term);
            ioctl(slave_fd, TIOCSWINSZ, &gw);

            dup2(slave_fd, STDIN_FILENO);
            dup2(slave_fd, STDOUT_FILENO);
            dup2(slave_fd, STDERR_FILENO);
            close(slave_fd);

            /* become foreground process group on our own pty → ctrl-c works */
            tcsetpgrp(STDIN_FILENO, getpgrp());

            /* SAFETY: never exec on the bare host — re-enter PRoot (fail closed). */
            if (g_launcher_path[0] == '\0' || access(g_launcher_path, X_OK) != 0) {
                dprintf(STDERR_FILENO,
                        "[su_daemon] FATAL: no PRoot launcher configured; "
                        "refusing to run command on host.\n");
                _exit(126);
            }

            char *launcher_argv[MAX_ARGS + 4];
            int li = 0;
            launcher_argv[li++] = (char *)g_launcher_path;
            launcher_argv[li++] = (char *)"--";

            if (cmd_argv[0] != NULL && strcmp(cmd_argv[0], "/system/bin/sh") == 0) {
                cmd_argv[0] = (char *)"/bin/sh";
            }
            int guest_args = 0;
            for (int i = 0; cmd_argv[i] != NULL; i++) guest_args = i + 1;
            int bare_su = (guest_args == 1) &&
                (strcmp(cmd_argv[0], "sh") == 0 || strcmp(cmd_argv[0], "/bin/sh") == 0 ||
                 strcmp(cmd_argv[0], "bash") == 0 || strcmp(cmd_argv[0], "/bin/bash") == 0);
            if (!bare_su) {
                for (int i = 0; cmd_argv[i] != NULL && li < MAX_ARGS + 3; i++) {
                    launcher_argv[li++] = cmd_argv[i];
                }
            }
            launcher_argv[li] = NULL;

            if (cwd[0] != '\0') setenv("NH_CWD", cwd, 1);
            if (g_term[0] != '\0') setenv("TERM", g_term, 1);

            execv(g_launcher_path, launcher_argv);
            dprintf(STDERR_FILENO, "[su_daemon] execv %s failed: %s\n",
                    g_launcher_path, strerror(errno));
            _exit(127);
        } else {
            /* worker: relay guest PTY <-> command PTY, watch for client death */
            close(slave_fd);

            int status = 0, exit_code = 0, aborted = 0;
            for (;;) {
                struct pollfd pfds[3];
                pfds[0].fd = guest_fd;  pfds[0].events = POLLIN;
                pfds[1].fd = master_fd; pfds[1].events = POLLIN;
                pfds[2].fd = client_fd; pfds[2].events = POLLIN | POLLHUP;
                int pr = poll(pfds, 3, 100);
                if (pr < 0) { if (errno == EINTR) continue; break; }

                if (pfds[2].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                    kill(pid, SIGKILL);
                    waitpid(pid, &status, 0);
                    exit_code = 130; aborted = 1; break;
                }

                char buf[4096];
                if (pr > 0) {
                    if (pfds[0].revents & POLLIN) {
                        ssize_t n = read(guest_fd, buf, sizeof(buf));
                        if (n > 0) write_all(master_fd, buf, (size_t)n);
                    }
                    if (pfds[1].revents & POLLIN) {
                        ssize_t n = read(master_fd, buf, sizeof(buf));
                        if (n > 0) write_all(guest_fd, buf, (size_t)n);
                    }
                }

                /* forward terminal resize (guest → command pty) */
                struct winsize cw;
                if (ioctl(guest_fd, TIOCGWINSZ, &cw) == 0 &&
                    (cw.ws_row != gw.ws_row || cw.ws_col != gw.ws_col)) {
                    gw = cw;
                    ioctl(master_fd, TIOCSWINSZ, &gw);
                }

                pid_t wr = waitpid(pid, &status, WNOHANG);
                if (wr == pid) {
                    /* drain any remaining command output */
                    for (;;) {
                        ssize_t n = read(master_fd, buf, sizeof(buf));
                        if (n > 0) { write_all(guest_fd, buf, (size_t)n); continue; }
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

            /* CRITICAL: restore guest shell terminal (raw was only temporary) */
            if (have_term) tcsetattr(guest_fd, TCSANOW, &saved_term);
            close(master_fd);
            close(fds[0]); close(fds[1]); close(fds[2]);

            if (!aborted) {
                if (g_auto_fix && exit_code != 126 && g_rootfs_path[0] != '\0') {
                    struct timeval tv0, tv1;
                    gettimeofday(&tv0, NULL);
                    fix_permissions(g_rootfs_path, g_app_uid, g_app_gid, 1);
                    gettimeofday(&tv1, NULL);
                    long ms = (tv1.tv_sec - tv0.tv_sec) * 1000L + (tv1.tv_usec - tv0.tv_usec) / 1000L;
                    dprintf(STDERR_FILENO, "[su_daemon] auto-fix: chowned root-owned files to %d:%d in %ld ms\n",
                            (int)g_app_uid, (int)g_app_gid, ms);
                }
                write(client_fd, &exit_code, sizeof(exit_code));
            }
            close(client_fd);
            for (int i = 0; cmd_argv[i] != NULL; i++) free(cmd_argv[i]);
            return;
        }
    }

    /* ── Direct (non-interactive / pipe) path ──
     * guest_fd is NOT a tty (piped input) → no PTY bridge, just dup2. */
    pid_t pid = fork();
    if (pid < 0) {
        perror("[su_daemon] fork");
        close(fds[0]); close(fds[1]); close(fds[2]);
        int err_code = 1;
        write(client_fd, &err_code, sizeof(err_code));
        close(client_fd);
        for (int i = 0; cmd_argv[i] != NULL; i++) free(cmd_argv[i]);
        return;
    }

    if (pid == 0) {
        // Command child
        close(client_fd);

        // Redirect STDIN, STDOUT, STDERR
        dup2(fds[0], STDIN_FILENO);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[2], STDERR_FILENO);

        close(fds[0]); close(fds[1]); close(fds[2]);

        /* SAFETY: never exec the requested command on the bare host. Convert
         * it into a RE-ENTRY of the PRoot guest so that even a host-global
         * command (e.g. `chmod -R / ...`) is confined to the guest rootfs by
         * PRoot. If no launcher is configured we REFUSE (fail closed). */
        if (g_launcher_path[0] == '\0' || access(g_launcher_path, X_OK) != 0) {
            dprintf(STDERR_FILENO,
                    "[su_daemon] FATAL: no PRoot launcher configured; "
                    "refusing to run command on host.\n");
            _exit(126);
        }

        /* Build: launcher_path -- <guest-program> [args...] */
        char *launcher_argv[MAX_ARGS + 4];
        int li = 0;
        launcher_argv[li++] = (char *)g_launcher_path;
        launcher_argv[li++] = (char *)"--";

        /* The wrapper sends "/system/bin/sh" for `su -c` and bare `su`;
         * inside the guest that must be the guest shell. */
        if (cmd_argv[0] != NULL && strcmp(cmd_argv[0], "/system/bin/sh") == 0) {
            cmd_argv[0] = (char *)"/bin/sh";
        }

        int guest_args = 0;
        for (int i = 0; cmd_argv[i] != NULL; i++) guest_args = i + 1;

        int bare_su = (guest_args == 1) &&
            (strcmp(cmd_argv[0], "sh") == 0 || strcmp(cmd_argv[0], "/bin/sh") == 0 ||
             strcmp(cmd_argv[0], "bash") == 0 || strcmp(cmd_argv[0], "/bin/bash") == 0);

        if (!bare_su) {
            for (int i = 0; cmd_argv[i] != NULL && li < MAX_ARGS + 3; i++) {
                launcher_argv[li++] = cmd_argv[i];
            }
        }
        launcher_argv[li] = NULL;

        /* Pass the guest working directory so proot can start there. */
        if (cwd[0] != '\0') {
            setenv("NH_CWD", cwd, 1);
        }
        if (g_term[0] != '\0') setenv("TERM", g_term, 1);

        /* Stay as real root (Magisk su). PRoot confines the filesystem. */
        execv(g_launcher_path, launcher_argv);
        dprintf(STDERR_FILENO, "[su_daemon] execv %s failed: %s\n",
                g_launcher_path, strerror(errno));
        _exit(127);
    }

    // Worker: wait for child; abort if client dies (POLLHUP on client_fd).
    close(fds[0]); close(fds[1]); close(fds[2]);

    int status = 0;
    int exit_code = 0;
    int command_done = 0;
    for (;;) {
        struct pollfd pfd;
        pfd.fd = client_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 500);
        if (pr > 0 && (pfd.revents & (POLLHUP | POLLERR | POLLNVAL))) {
            /* klient (su_wrapper) skončil — ctrl+c / crash: ukonči command */
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            command_done = 1;
            exit_code = 130;
            break;
        }
        pid_t wr = waitpid(pid, &status, WNOHANG);
        if (wr == pid) {
            if (WIFEXITED(status)) {
                exit_code = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code = 128 + WTERMSIG(status);
            }
            command_done = 1;
            break;
        }
        if (wr < 0 && errno != EINTR) {
            command_done = 1;
            exit_code = 1;
            break;
        }
    }

    if (command_done && exit_code != 130) {
        /* Layer 1 — automatic ownership fix (host side, OUTSIDE PRoot). */
        if (g_auto_fix && exit_code != 126 && g_rootfs_path[0] != '\0') {
            struct timeval tv0, tv1;
            gettimeofday(&tv0, NULL);
            fix_permissions(g_rootfs_path, g_app_uid, g_app_gid, 1);
            gettimeofday(&tv1, NULL);
            long ms = (tv1.tv_sec - tv0.tv_sec) * 1000L + (tv1.tv_usec - tv0.tv_usec) / 1000L;
            dprintf(STDERR_FILENO, "[su_daemon] auto-fix: chowned root-owned files to %d:%d in %ld ms\n",
                    (int)g_app_uid, (int)g_app_gid, ms);
        }

        // Send exit code back to client wrapper
        write(client_fd, &exit_code, sizeof(exit_code));
    } else if (exit_code == 130) {
        /* pošli 130 i tak — su_wrapper to už nečte, ale neškodí */
        (void)!write(client_fd, &exit_code, sizeof(exit_code));
    }
    close(client_fd);

    // Free cmd_argv strings
    for (int i = 0; cmd_argv[i] != NULL; i++) {
        free(cmd_argv[i]);
    }
}
