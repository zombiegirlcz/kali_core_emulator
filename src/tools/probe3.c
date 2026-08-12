// probe3.c — NO LIBC. Raw aarch64 syscalls, marker printed BEFORE each call.
#include <stddef.h>

static long sc(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2),
                     "r"(x3), "r"(x4), "r"(x5) : "memory");
    return x0;
}
static void wr(const char *s, long len) { sc(64, 1, (long)s, len, 0, 0, 0); }

static void itoa(long v, char *out) {
    char tmp[24]; int i = 0; int neg = 0;
    if (v < 0) { neg = 1; v = -v; }
    do { tmp[i++] = '0' + (v % 10); v /= 10; } while (v > 0);
    if (neg) tmp[i++] = '-';
    int j = 0;
    while (i > 0) out[j++] = tmp[--i];
    out[j] = 0;
}

void _start(void) {
    /* nr, name */
    static const long tests[] = {
        291, 439, 437, 283, 75, 270, 271, 282, 280, 241, 272, 60,
        117, 278, 123, 228, 435, 293
    };
    static const char *names[] = {
        "statx", "faccessat2", "openat2", "membarrier", "vmsplice",
        "process_vm_readv", "process_vm_writev", "userfaultfd", "bpf",
        "perf_event_open", "kcmp", "quotactl", "ptrace", "getrandom",
        "sched_getaffinity", "mlock", "clone3", "rseq"
    };
    int n = sizeof(tests) / sizeof(tests[0]);
    char buf[128];
    char num[24];
    int i;
    wr("P3-START\n", 9);
    for (i = 0; i < n; i++) {
        long nr = tests[i];
        int j = 0;
        /* marker BEFORE the call */
        while (names[i][j]) { buf[j] = names[i][j]; j++; }
        buf[j++] = ' '; buf[j++] = '#';
        itoa(nr, num);
        { int k = 0; while (num[k]) buf[j++] = num[k++]; }
        buf[j++] = ' '; buf[j++] = '.';
        buf[j++] = '.';
        buf[j++] = '.';
        buf[j++] = '\n';
        wr(buf, j);
        long r = sc(nr, -1, -1, -1, -1, -1, -1);
        j = 0;
        while (names[i][j]) { buf[j] = names[i][j]; j++; }
        buf[j++] = ' '; buf[j++] = '=';
        itoa(r, num);
        { int k = 0; while (num[k]) buf[j++] = num[k++]; }
        buf[j++] = '\n';
        wr(buf, j);
    }
    sc(93, 0, 0, 0, 0, 0, 0); /* exit(0) */
    for (;;) {}
}
