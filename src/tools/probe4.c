// probe4.c — NO LIBC. Raw aarch64 syscalls. Usage: probe4 <syscall-nr>
// Parses argv from the initial stack (sp -> argc, sp+8 -> argv[0], ...).
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

#ifndef NR
#define NR 0
#endif

void _start(void) {
    long nr = NR;
    char buf[96];
    char num[24];
    int j = 0;
    itoa(nr, num);
    { int k = 0; while (num[k]) buf[j++] = num[k++]; }
    buf[j++] = ':'; buf[j++] = ' ';
    long r = sc(nr, -1, -1, -1, -1, -1, -1);
    itoa(r, num);
    { int k = 0; while (num[k]) buf[j++] = num[k++]; }
    buf[j++] = '\n';
    wr(buf, j);
    sc(93, 0, 0, 0, 0, 0, 0); /* exit(0) */
    for (;;) {}
}
