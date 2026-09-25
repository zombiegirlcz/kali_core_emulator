/*
 * cpuctl — root CPU control daemon for NetHunter AI Operator (Magisk module)
 *
 * Commands:
 *   cpuctl daemon            — netlink proc connector + /proc fallback loop
 *   cpuctl boost on|off [N]  — cpufreq min=max for policy of core N
 *   cpuctl status            — daemon, sessions, boost, frequencies
 *   cpuctl pin <hexmask> <pid> — one-shot: set affinity for pid + descendants
 *
 * Build: aarch64-linux-android24-clang -static -o cpuctl cpuctl.c
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/cn_proc.h>
#include <linux/connector.h>
#include <linux/netlink.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

/* ── Constants ─────────────────────────────────────────────────────── */
#define FILES_DIR       "/data/user/0/com.linux_core/files"
#define CPU_DIR         FILES_DIR "/nh/cpu"
#define LOG_DIR         "/data/adb/cpuctl"
#define LOG_FILE        LOG_DIR "/cpuctl.log"
#define LOG_MAX         (256 * 1024)
#define HEARTBEAT_FILE  CPU_DIR "/cpuctld"
#define HB_INTERVAL     5
#define REPIN_INTERVAL  5
#define MAX_SESSIONS    32
#define MAX_TREE        2048
#define MAX_WORDS       128
#define WORD_LEN        64

/* ── Globals ───────────────────────────────────────────────────────── */
static volatile sig_atomic_t g_running = 1;
static int  g_app_uid = -1;
static FILE *g_logfp;
static char  g_logpath[PATH_MAX];

typedef struct {
    pid_t pid;
    unsigned long mask;
    char rootfs[PATH_MAX];
} session_t;

static session_t g_sess[MAX_SESSIONS];
static int g_nsess;

typedef struct { pid_t pid; int tries; } pending_t;
static pending_t g_pend[512];
static int g_npend;

/* ── File helpers ──────────────────────────────────────────────────── */

static int read_file(const char *path, char *buf, int sz) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) { buf[0] = '\0'; return -1; }
    int n = read(fd, buf, sz - 1);
    close(fd);
    if (n <= 0) { buf[0] = '\0'; return -1; }
    buf[n] = '\0';
    while (n > 0 && (buf[n-1] == '\n' || buf[n-1] == '\r')) buf[--n] = '\0';
    return n;
}

static int write_file(const char *path, const char *str) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return -1;
    int len = (int)strlen(str);
    int r = (int)write(fd, str, len);
    close(fd);
    return r == len ? 0 : -1;
}

static int append_file(const char *path, const char *str) {
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return -1;
    int len = (int)strlen(str);
    int r = (int)write(fd, str, len);
    close(fd);
    return r == len ? 0 : -1;
}

static void chown_app(const char *path) {
    if (g_app_uid > 0) chown(path, g_app_uid, g_app_uid);
}

static int pid_alive(pid_t p) {
    char tmp[32];
    snprintf(tmp, sizeof tmp, "/proc/%d", p);
    return access(tmp, F_OK) == 0;
}

/* ── Logging ───────────────────────────────────────────────────────── */

static void log_rotate(void) {
    if (!g_logfp) return;
    if (ftell(g_logfp) < LOG_MAX) return;
    fclose(g_logfp);
    char old[PATH_MAX];
    snprintf(old, sizeof old, "%s.1", g_logpath);
    rename(g_logpath, old);
    g_logfp = fopen(g_logpath, "a");
}

static void logmsg(const char *fmt, ...) {
    if (!g_logfp) return;
    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    char ts[24];
    strftime(ts, sizeof ts, "%m-%d %H:%M:%S", &tm);
    fprintf(g_logfp, "[%s] ", ts);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_logfp, fmt, ap);
    va_end(ap);
    fputc('\n', g_logfp);
    fflush(g_logfp);
    log_rotate();
}

static int log_open(void) {
    mkdir(LOG_DIR, 0755);
    snprintf(g_logpath, sizeof g_logpath, "%s", LOG_FILE);
    g_logfp = fopen(g_logpath, "a");
    return g_logfp ? 0 : -1;
}

/* ── CPU helpers ───────────────────────────────────────────────────── */

static unsigned long all_mask(void) {
    unsigned long m = 0;
    char p[128], b[4];
    for (int i = 0; i < 64; i++) {
        snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d", i);
        if (access(p, F_OK) != 0) break;
        snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/online", i);
        if (read_file(p, b, sizeof b) < 0 || b[0] == '1')
            m |= 1UL << i;
    }
    return m ? m : 0xff;
}

static int set_aff(pid_t pid, unsigned long mask) {
    cpu_set_t cs;
    CPU_ZERO(&cs);
    for (int i = 0; i < 64; i++)
        if (mask & (1UL << i)) CPU_SET(i, &cs);
    return sched_setaffinity(pid, sizeof cs, &cs);
}

static unsigned long get_aff(pid_t pid) {
    cpu_set_t cs;
    CPU_ZERO(&cs);
    if (sched_getaffinity(pid, sizeof cs, &cs) != 0) return 0;
    unsigned long m = 0;
    for (int i = 0; i < 64; i++)
        if (CPU_ISSET(i, &cs)) m |= 1UL << i;
    return m;
}

static pid_t get_ppid_of(pid_t pid) {
    char path[64], buf[512];
    snprintf(path, sizeof path, "/proc/%d/stat", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    char *rp = strrchr(buf, ')');
    if (!rp) return 0;
    pid_t ppid;
    if (sscanf(rp + 1, " %*c %d", &ppid) != 1) return 0;
    return ppid;
}

static int get_comm(pid_t pid, char *out, int sz) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/comm", pid);
    return read_file(path, out, sz);
}

static int get_argv0_base(pid_t pid, char *out, int sz) {
    char path[64], buf[1024];
    snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    char *base = strrchr(buf, '/');
    base = base ? base + 1 : buf;
    strncpy(out, base, sz - 1);
    out[sz - 1] = '\0';
    return 0;
}

static int get_rootfs(pid_t pid, char *out, int sz) {
    char path[64], buf[4096];
    snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (int i = 0; i < n;) {
        if (strcmp(&buf[i], "-r") == 0) {
            i += (int)strlen(&buf[i]) + 1;
            if (i < n) { strncpy(out, &buf[i], sz - 1); out[sz - 1] = '\0'; return 0; }
        }
        i += (int)strlen(&buf[i]) + 1;
    }
    return -1;
}

/* ── Ancestry and free-list ────────────────────────────────────────── */

static int is_descendant(pid_t pid, pid_t anc) {
    pid_t cur = pid;
    for (int d = 0; d < 128 && cur > 1; d++) {
        if (cur == anc) return 1;
        cur = get_ppid_of(cur);
    }
    return 0;
}

static int is_freed(pid_t pid, pid_t sess_pid) {
    char path[PATH_MAX], buf[4096];
    snprintf(path, sizeof path, "%s/free.%d", CPU_DIR, sess_pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    pid_t cur = pid;
    for (int d = 0; d < 128 && cur > 1 && cur != sess_pid; d++) {
        char ps[16];
        snprintf(ps, sizeof ps, "%d", cur);
        int plen = (int)strlen(ps);
        char *line = buf;
        while (*line) {
            while (*line == ' ' || *line == '\n') line++;
            if (!*line) break;
            char *nl = strchr(line, '\n');
            int ll = nl ? (int)(nl - line) : (int)strlen(line);
            if (ll == plen && strncmp(line, ps, plen) == 0) return 1;
            line = nl ? nl + 1 : line + ll;
        }
        cur = get_ppid_of(cur);
    }
    return 0;
}

static session_t *find_session(pid_t pid) {
    for (int i = 0; i < g_nsess; i++)
        if (is_descendant(pid, g_sess[i].pid))
            return &g_sess[i];
    return NULL;
}

/* Collect tree of pid excluding freed subtrees */
static int collect_tree(pid_t root, pid_t sess_pid, pid_t *out, int max) {
    pid_t q[MAX_TREE];
    int h = 0, t = 0, cnt = 0;
    q[t++] = root;
    while (h < t && cnt < max) {
        pid_t p = q[h++];
        out[cnt++] = p;
        DIR *d = opendir("/proc");
        if (!d) break;
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (!isdigit((unsigned char)de->d_name[0])) continue;
            pid_t ch = (pid_t)atoi(de->d_name);
            if (ch <= 0) continue;
            if (get_ppid_of(ch) == p && !is_freed(ch, sess_pid) && t < MAX_TREE)
                q[t++] = ch;
        }
        closedir(d);
    }
    return cnt;
}

/* ── CPU_ALL parsing ───────────────────────────────────────────────── */

static int get_cpu_all(pid_t pid, char *out, int sz) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/environ", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char buf[8192];
    int total = 0, n;
    while ((n = (int)read(fd, buf + total, sizeof(buf) - total - 1)) > 0)
        total += n;
    close(fd);
    if (total <= 0) return -1;
    buf[total] = '\0';
    for (int i = 0; i < total;) {
        if (strncmp(&buf[i], "CPU_ALL=", 8) == 0) {
            strncpy(out, &buf[i] + 8, sz - 1);
            out[sz - 1] = '\0';
            return 0;
        }
        i += (int)strlen(&buf[i]) + 1;
    }
    return -1;
}

static int parse_words(const char *val, const char *rootfs,
                       char words[][WORD_LEN], int maxw) {
    int nw = 0;
    char copy[2048];
    strncpy(copy, val, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = '\0';
    for (char *p = copy; *p; p++)
        if (*p == ',' || *p == ';') *p = ' ';

    char expanded[4096]; expanded[0] = '\0';
    char *src = copy;
    while (*src) {
        while (*src == ' ') src++;
        if (!*src) break;
        /* $(cat FILE) */
        if (strncmp(src, "$(cat", 5) == 0) {
            char *s = src + 5;
            while (*s == ' ') s++;
            char *e = strchr(s, ')');
            if (e) {
                char fname[PATH_MAX];
                int fl = (int)(e - s);
                if (fl >= (int)sizeof(fname)) fl = (int)sizeof(fname) - 1;
                strncpy(fname, s, fl); fname[fl] = '\0';
                while (fl > 0 && fname[fl-1] == ' ') fname[--fl] = '\0';
                char full[PATH_MAX];
                if (fname[0] == '~' && fname[1] == '/')
                    snprintf(full, sizeof full, "%s/root/%s", rootfs, fname + 2);
                else if (fname[0] == '/')
                    snprintf(full, sizeof full, "%s%s", rootfs, fname);
                else
                    snprintf(full, sizeof full, "%s", fname);
                char fb[1024];
                if (read_file(full, fb, sizeof fb) > 0) {
                    for (char *fp = fb; *fp; fp++)
                        if (*fp == ',' || *fp == ';' || *fp == '\n') *fp = ' ';
                    strncat(expanded, fb, sizeof(expanded) - strlen(expanded) - 2);
                    strcat(expanded, " ");
                }
                src = e + 1;
                continue;
            }
        }
        /* word boundary */
        char *end = src;
        while (*end && *end != ' ') end++;
        char word[PATH_MAX];
        int wl = (int)(end - src);
        if (wl >= (int)sizeof(word)) wl = (int)sizeof(word) - 1;
        strncpy(word, src, wl); word[wl] = '\0';

        if ((word[0] == '~' && word[1] == '/') || word[0] == '/') {
            char full[PATH_MAX];
            if (word[0] == '~' && word[1] == '/')
                snprintf(full, sizeof full, "%s/root/%s", rootfs, word + 2);
            else
                snprintf(full, sizeof full, "%s%s", rootfs, word);
            char fb[1024];
            if (read_file(full, fb, sizeof fb) > 0) {
                for (char *fp = fb; *fp; fp++)
                    if (*fp == ',' || *fp == ';' || *fp == '\n') *fp = ' ';
                strncat(expanded, fb, sizeof(expanded) - strlen(expanded) - 2);
                strcat(expanded, " ");
            }
        } else {
            strncat(expanded, word, sizeof(expanded) - strlen(expanded) - 2);
            strcat(expanded, " ");
        }
        src = end;
    }

    char *tok = strtok(expanded, " \t\n");
    while (tok && nw < maxw) {
        strncpy(words[nw], tok, WORD_LEN - 1);
        words[nw][WORD_LEN - 1] = '\0';
        nw++;
        tok = strtok(NULL, " \t\n");
    }
    return nw;
}

static int check_cpu_all(pid_t pid, session_t *s) {
    char val[2048];
    if (get_cpu_all(pid, val, sizeof val) != 0 || val[0] == '\0') return 0;
    char words[MAX_WORDS][WORD_LEN];
    int nw = parse_words(val, s->rootfs, words, MAX_WORDS);
    if (nw <= 0) return 0;
    char comm[64], argv0[256];
    if (get_comm(pid, comm, sizeof comm) != 0) return 0;
    get_argv0_base(pid, argv0, sizeof argv0);
    for (int i = 0; i < nw; i++)
        if (strcmp(words[i], comm) == 0 || strcmp(words[i], argv0) == 0)
            return 1;
    return 0;
}

/* ── Free a process (CPU_ALL match) ────────────────────────────────── */

static void free_pid(pid_t pid, session_t *s) {
    set_aff(pid, all_mask());
    char path[PATH_MAX], line[32];
    snprintf(path, sizeof path, "%s/free.%d", CPU_DIR, s->pid);
    snprintf(line, sizeof line, "%d\n", pid);
    append_file(path, line);
    chown_app(path);
}

/* ── Session scanning ──────────────────────────────────────────────── */

static void scan_sessions(void) {
    DIR *d = opendir(CPU_DIR);
    if (!d) return;
    g_nsess = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL && g_nsess < MAX_SESSIONS) {
        if (strncmp(de->d_name, "pin.", 4) != 0) continue;
        pid_t pid = (pid_t)atoi(de->d_name + 4);
        if (pid <= 0 || !pid_alive(pid)) {
            char p1[PATH_MAX], p2[PATH_MAX];
            snprintf(p1, sizeof p1, "%s/%s", CPU_DIR, de->d_name);
            snprintf(p2, sizeof p2, "%s/free.%d", CPU_DIR, pid);
            unlink(p1); unlink(p2);
            logmsg("cleaned dead session pid=%d", pid);
            continue;
        }
        char path[PATH_MAX], buf[32];
        snprintf(path, sizeof path, "%s/%s", CPU_DIR, de->d_name);
        if (read_file(path, buf, sizeof buf) <= 0) continue;
        unsigned long mask = strtoul(buf, NULL, 16);
        if (!mask) continue;
        session_t *s = &g_sess[g_nsess++];
        s->pid = pid; s->mask = mask; s->rootfs[0] = '\0';
        get_rootfs(pid, s->rootfs, sizeof s->rootfs);
    }
    closedir(d);
}

/* ── Re-pin sessions (cpuset override recovery) ───────────────────── */

static void repin_sessions(void) {
    for (int i = 0; i < g_nsess; i++) {
        session_t *s = &g_sess[i];
        if (!pid_alive(s->pid)) continue;
        unsigned long cur = get_aff(s->pid);
        if (cur == s->mask) continue;
        pid_t tree[MAX_TREE];
        int n = collect_tree(s->pid, s->pid, tree, MAX_TREE);
        int changed = 0;
        for (int j = 0; j < n; j++) {
            if (!is_freed(tree[j], s->pid)) {
                set_aff(tree[j], s->mask);
                changed++;
            }
        }
        logmsg("repin session %d mask=%lx was=%lx procs=%d", s->pid, s->mask, cur, changed);
    }
}

/* ── Handle new process ────────────────────────────────────────────── */

static void handle_new(pid_t pid) {
    session_t *s = find_session(pid);
    if (!s) return;
    if (is_freed(pid, s->pid)) return;
    set_aff(pid, s->mask);
    if (check_cpu_all(pid, s)) {
        free_pid(pid, s);
        logmsg("cpu_all freed pid=%d sess=%d", pid, s->pid);
    }
}

static void schedule_recheck(pid_t pid) {
    if (g_npend >= (int)(sizeof g_pend / sizeof g_pend[0])) return;
    g_pend[g_npend].pid = pid;
    g_pend[g_npend].tries = 3;
    g_npend++;
}

static void process_pending(void) {
    int i = 0;
    while (i < g_npend) {
        pending_t *p = &g_pend[i];
        if (!pid_alive(p->pid) || p->tries <= 0) {
            g_pend[i] = g_pend[--g_npend]; continue;
        }
        session_t *s = find_session(p->pid);
        if (s && !is_freed(p->pid, s->pid) && check_cpu_all(p->pid, s)) {
            free_pid(p->pid, s);
            logmsg("cpu_all freed (delayed) pid=%d sess=%d", p->pid, s->pid);
            g_pend[i] = g_pend[--g_npend]; continue;
        }
        p->tries--;
        i++;
    }
}

/* ── Heartbeat ─────────────────────────────────────────────────────── */

static void write_heartbeat(void) {
    char buf[64];
    snprintf(buf, sizeof buf, "%d %ld", getpid(), (long)time(NULL));
    mkdir(CPU_DIR, 0755);
    write_file(HEARTBEAT_FILE, buf);
    chown_app(HEARTBEAT_FILE);
}

/* ── Netlink proc connector ────────────────────────────────────────── */

static int nl_connect(void) {
    int sock = socket(PF_NETLINK, SOCK_DGRAM, NETLINK_CONNECTOR);
    if (sock < 0) return -1;
    struct sockaddr_nl addr;
    memset(&addr, 0, sizeof addr);
    addr.nl_family = AF_NETLINK;
    addr.nl_pid = (unsigned)getpid();
    addr.nl_groups = CN_IDX_PROC;
    if (bind(sock, (struct sockaddr *)&addr, sizeof addr) < 0) {
        close(sock); return -1;
    }
    struct {
        struct nlmsghdr nl;
        struct cn_msg cn;
        enum proc_cn_mcast_op op;
    } __attribute__((packed)) msg;
    memset(&msg, 0, sizeof msg);
    msg.nl.nlmsg_len = sizeof msg;
    msg.nl.nlmsg_type = NLMSG_DONE;
    msg.nl.nlmsg_pid = (unsigned)getpid();
    msg.cn.id.idx = CN_IDX_PROC;
    msg.cn.id.val = CN_VAL_PROC;
    msg.cn.len = sizeof(enum proc_cn_mcast_op);
    msg.op = PROC_CN_MCAST_LISTEN;
    if (send(sock, &msg, sizeof msg, 0) < 0) {
        close(sock); return -1;
    }
    return sock;
}

/* ── Daemon ────────────────────────────────────────────────────────── */

static void sig_handler(int sig) { (void)sig; g_running = 0; }

static int daemon_main(void) {
    /* daemonize: double fork */
    pid_t p = fork();
    if (p < 0) { perror("fork"); return 1; }
    if (p > 0) return 0;           /* parent exits */
    setsid();
    p = fork();
    if (p < 0) _exit(1);
    if (p > 0) _exit(0);           /* first child exits */
    /* second child is the daemon */
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) { dup2(devnull, 0); dup2(devnull, 1); dup2(devnull, 2); close(devnull); }

    signal(SIGTERM, sig_handler);
    signal(SIGINT, sig_handler);
    signal(SIGPIPE, SIG_IGN);

    if (log_open() != 0) return 1;

    struct stat st;
    g_app_uid = (stat(FILES_DIR, &st) == 0) ? (int)st.st_uid : -1;
    logmsg("daemon started pid=%d app_uid=%d", getpid(), g_app_uid);

    int nl = nl_connect();
    int use_nl = (nl >= 0);
    logmsg(use_nl ? "proc connector active" : "proc connector unavailable, /proc fallback");

    time_t last_hb = 0, last_rp = 0, last_pend = 0;

    while (g_running) {
        time_t now = time(NULL);

        if (now - last_hb >= HB_INTERVAL) {
            scan_sessions();
            write_heartbeat();
            last_hb = now;
        }
        if (now - last_rp >= REPIN_INTERVAL) {
            repin_sessions();
            last_rp = now;
        }
        if (now - last_pend >= 1) {
            process_pending();
            last_pend = now;
        }

        if (use_nl) {
            struct pollfd pfd = { .fd = nl, .events = POLLIN };
            int r = poll(&pfd, 1, 1000);
            if (r > 0 && (pfd.revents & POLLIN)) {
                char buf[4096];
                int len = (int)recv(nl, buf, sizeof buf, 0);
                if (len > 0) {
                    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
                    for (; NLMSG_OK(nlh, (unsigned)len); nlh = NLMSG_NEXT(nlh, len)) {
                        struct cn_msg *cn = (struct cn_msg *)NLMSG_DATA(nlh);
                        struct proc_event *ev = (struct proc_event *)cn->data;
                        switch (ev->what) {
                        case PROC_EVENT_EXEC:
                            handle_new(ev->event_data.exec.process_pid);
                            schedule_recheck(ev->event_data.exec.process_pid);
                            break;
                        case PROC_EVENT_FORK:
                            handle_new(ev->event_data.fork.child_pid);
                            break;
                        default: break;
                        }
                    }
                }
            }
        } else {
            usleep(2000000);
            for (int i = 0; i < g_nsess; i++) {
                session_t *s = &g_sess[i];
                if (!pid_alive(s->pid)) continue;
                pid_t tree[MAX_TREE];
                int n = collect_tree(s->pid, s->pid, tree, MAX_TREE);
                for (int j = 0; j < n; j++) {
                    if (!is_freed(tree[j], s->pid))
                        set_aff(tree[j], s->mask);
                }
            }
        }
    }

    logmsg("daemon stopped");
    unlink(HEARTBEAT_FILE);
    if (nl >= 0) close(nl);
    if (g_logfp) fclose(g_logfp);
    return 0;
}

/* ── Boost ─────────────────────────────────────────────────────────── */

static int fastest_core(void) {
    int best = 0, core = 0;
    char path[PATH_MAX], buf[32];
    for (int i = 0; i < 64; i++) {
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        if (read_file(path, buf, sizeof buf) <= 0) continue;
        int f = atoi(buf);
        if (f > best) { best = f; core = i; }
    }
    return core;
}

static int find_policy_num(int core) {
    char path[PATH_MAX], buf[PATH_MAX];
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq", core);
    ssize_t n = readlink(path, buf, sizeof(buf) - 1);
    if (n > 0) {
        buf[n] = '\0';
        char *p = strstr(buf, "policy");
        if (p) return atoi(p + 6);
    }
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpufreq/policy%d", core);
    if (access(path, F_OK) == 0) return core;
    return core;
}

static int cmd_boost(int argc, char **argv) {
    if (argc < 1) { fprintf(stderr, "Usage: cpuctl boost on|off|status [core]\n"); return 1; }

    if (strcmp(argv[0], "status") == 0) {
        DIR *d = opendir("/sys/devices/system/cpu/cpufreq");
        if (!d) { fprintf(stderr, "cpuctl: cannot read cpufreq\n"); return 1; }
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (strncmp(de->d_name, "policy", 6) != 0) continue;
            int pn = atoi(de->d_name + 6);
            char p[PATH_MAX], mn[32], mx[32], cu[32], gv[32];
            snprintf(p, sizeof p, "/sys/devices/system/cpu/cpufreq/%s/scaling_min_freq", de->d_name);
            read_file(p, mn, sizeof mn);
            snprintf(p, sizeof p, "/sys/devices/system/cpu/cpufreq/%s/scaling_max_freq", de->d_name);
            read_file(p, mx, sizeof mx);
            snprintf(p, sizeof p, "/sys/devices/system/cpu/cpufreq/%s/scaling_cur_freq", de->d_name);
            read_file(p, cu, sizeof cu);
            snprintf(p, sizeof p, "/sys/devices/system/cpu/cpufreq/%s/scaling_governor", de->d_name);
            read_file(p, gv, sizeof gv);
            char orig[PATH_MAX];
            snprintf(orig, sizeof orig, "%s/boost_orig.%d", LOG_DIR, pn);
            int boosted = (access(orig, F_OK) == 0);
            printf("  policy%d: min=%d max=%d cur=%d MHz gov=%s%s\n",
                   pn, atoi(mn)/1000, atoi(mx)/1000, atoi(cu)/1000, gv,
                   boosted ? "  [BOOSTED]" : "");
        }
        closedir(d);
        return 0;
    }

    if (strcmp(argv[0], "on") == 0) {
        int core = (argc >= 2) ? atoi(argv[1]) : fastest_core();
        int pn = find_policy_num(core);
        char mn_path[PATH_MAX], mx_path[PATH_MAX], mn_buf[32], mx_buf[32];
        snprintf(mn_path, sizeof mn_path,
                 "/sys/devices/system/cpu/cpufreq/policy%d/scaling_min_freq", pn);
        snprintf(mx_path, sizeof mx_path,
                 "/sys/devices/system/cpu/cpufreq/policy%d/scaling_max_freq", pn);
        if (read_file(mx_path, mx_buf, sizeof mx_buf) <= 0) {
            fprintf(stderr, "cpuctl: cannot read max freq for policy%d\n", pn); return 1;
        }
        if (read_file(mn_path, mn_buf, sizeof mn_buf) > 0) {
            char orig[PATH_MAX];
            snprintf(orig, sizeof orig, "%s/boost_orig.%d", LOG_DIR, pn);
            mkdir(LOG_DIR, 0755);
            write_file(orig, mn_buf);
        }
        if (write_file(mn_path, mx_buf) != 0) {
            fprintf(stderr, "cpuctl: cannot write scaling_min_freq (need root)\n"); return 1;
        }
        printf("boost ON: policy%d min=%d MHz (was %d)\n",
               pn, atoi(mx_buf)/1000, atoi(mn_buf)/1000);
        return 0;
    }

    if (strcmp(argv[0], "off") == 0) {
        DIR *d = opendir(LOG_DIR);
        if (!d) { printf("boost: no saved values\n"); return 0; }
        struct dirent *de;
        int restored = 0;
        while ((de = readdir(d)) != NULL) {
            if (strncmp(de->d_name, "boost_orig.", 11) != 0) continue;
            int pn = atoi(de->d_name + 11);
            char opath[PATH_MAX], obuf[32];
            snprintf(opath, sizeof opath, "%s/%s", LOG_DIR, de->d_name);
            if (read_file(opath, obuf, sizeof obuf) <= 0) continue;
            char mn_path[PATH_MAX];
            snprintf(mn_path, sizeof mn_path,
                     "/sys/devices/system/cpu/cpufreq/policy%d/scaling_min_freq", pn);
            if (write_file(mn_path, obuf) == 0) {
                printf("boost OFF: policy%d min restored to %d MHz\n", pn, atoi(obuf)/1000);
                unlink(opath);
                restored++;
            }
        }
        closedir(d);
        if (!restored) printf("boost: nothing to restore\n");
        return 0;
    }

    fprintf(stderr, "Usage: cpuctl boost on|off|status [core]\n");
    return 1;
}

/* ── Status ────────────────────────────────────────────────────────── */

static int cmd_status(void) {
    printf("=== cpuctl status ===\n\n");

    /* daemon */
    char hb[64];
    if (read_file(HEARTBEAT_FILE, hb, sizeof hb) > 0) {
        pid_t dp = 0; long ts = 0;
        sscanf(hb, "%d %ld", &dp, &ts);
        long age = (long)time(NULL) - ts;
        if (dp > 0 && pid_alive(dp) && age < 15)
            printf("Daemon: RUNNING (pid %d, heartbeat %lds ago)\n", dp, age);
        else
            printf("Daemon: STALE (pid %d, %lds ago)\n", dp, age);
    } else {
        printf("Daemon: NOT RUNNING\n");
    }

    /* sessions */
    g_app_uid = -1;
    scan_sessions();
    printf("\nPinned sessions (%d):\n", g_nsess);
    if (g_nsess == 0) printf("  (none)\n");
    for (int i = 0; i < g_nsess; i++) {
        session_t *s = &g_sess[i];
        unsigned long cur = get_aff(s->pid);
        printf("  pid=%-6d mask=%-4lx current=%-4lx rootfs=%s\n",
               s->pid, s->mask, cur, s->rootfs[0] ? s->rootfs : "?");
    }

    /* CPUs */
    printf("\nCPUs:\n");
    char path[PATH_MAX], buf[32];
    for (int i = 0; i < 64; i++) {
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d", i);
        if (access(path, F_OK) != 0) break;
        char on[4] = "1", cu[16] = "?", mx[16] = "?", gv[24] = "?";
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/online", i);
        read_file(path, on, sizeof on);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", i);
        read_file(path, cu, sizeof cu);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        read_file(path, mx, sizeof mx);
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor", i);
        read_file(path, gv, sizeof gv);
        printf("  cpu%d: %5d/%-5d MHz  %-12s %s\n",
               i, atoi(cu)/1000, atoi(mx)/1000, gv,
               on[0] == '1' ? "online" : "offline");
    }

    /* boost */
    printf("\nBoost:\n");
    char *bargs[] = {"status"};
    cmd_boost(1, bargs);

    return 0;
}

/* ── Pin (one-shot) ────────────────────────────────────────────────── */

static int cmd_pin(const char *hex, const char *spid) {
    unsigned long mask = strtoul(hex, NULL, 16);
    pid_t pid = (pid_t)atoi(spid);
    if (!mask || pid <= 0) {
        fprintf(stderr, "Usage: cpuctl pin <hexmask> <pid>\n"); return 1;
    }
    pid_t tree[MAX_TREE];
    int n = collect_tree(pid, pid, tree, MAX_TREE);
    int ok = 0;
    for (int i = 0; i < n; i++)
        if (set_aff(tree[i], mask) == 0) ok++;
    printf("pinned %d/%d processes (mask %lx)\n", ok, n, mask);
    return 0;
}

/* ── Main ──────────────────────────────────────────────────────────── */

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr,
            "Usage: cpuctl <command> [args]\n"
            "  daemon               start background daemon\n"
            "  boost on|off [core]  cpufreq boost\n"
            "  boost status         show boost state\n"
            "  status               show everything\n"
            "  pin <hexmask> <pid>  one-shot affinity\n");
        return 1;
    }
    if (strcmp(argv[1], "daemon") == 0) return daemon_main();
    if (strcmp(argv[1], "boost") == 0)  return cmd_boost(argc - 2, argv + 2);
    if (strcmp(argv[1], "status") == 0) return cmd_status();
    if (strcmp(argv[1], "pin") == 0) {
        if (argc < 4) { fprintf(stderr, "Usage: cpuctl pin <hexmask> <pid>\n"); return 1; }
        return cmd_pin(argv[2], argv[3]);
    }
    fprintf(stderr, "cpuctl: unknown command '%s'\n", argv[1]);
    return 1;
}
