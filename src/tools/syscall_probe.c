// syscall_probe.c — static probe: which syscalls does the app seccomp policy kill?
// aarch64 syscall numbers (from asm-generic/unistd.h).
// Run on the Android HOST (via /shell API). If a syscall is BLOCKED by the
// app seccomp filter the process dies with SIGSYS; the last printed line
// names the culprit. Invalid args (-1) -> EINVAL/EFAULT if allowed.
#include <stdio.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

#define SC_quotactl          60
#define SC_vmsplice          75
#define SC_ptrace           117
#define SC_sched_getaffinity 123
#define SC_mlock            228
#define SC_perf_event_open  241
#define SC_process_vm_readv 270
#define SC_process_vm_writev 271
#define SC_kcmp             272
#define SC_getrandom        278
#define SC_bpf              280
#define SC_userfaultfd      282
#define SC_membarrier       283
#define SC_statx            291
#define SC_rseq             293
#define SC_clone3           435
#define SC_openat2          437
#define SC_faccessat2       439

int main(void) {
    long tests[] = {
        SC_statx, SC_rseq, SC_clone3, SC_faccessat2, SC_openat2,
        SC_membarrier, SC_vmsplice, SC_process_vm_readv, SC_process_vm_writev,
        SC_userfaultfd, SC_bpf, SC_perf_event_open, SC_kcmp, SC_quotactl,
        SC_ptrace, SC_getrandom, SC_sched_getaffinity, SC_mlock
    };
    const char *names[] = {
        "statx", "rseq", "clone3", "faccessat2", "openat2",
        "membarrier", "vmsplice", "process_vm_readv", "process_vm_writev",
        "userfaultfd", "bpf", "perf_event_open", "kcmp", "quotactl",
        "ptrace", "getrandom", "sched_getaffinity", "mlock"
    };
    int n = sizeof(tests) / sizeof(tests[0]);
    printf("PROBE pid=%d start\n", (int)getpid());
    fflush(stdout);
    for (int i = 0; i < n; i++) {
        errno = 0;
        long r = syscall(tests[i], -1L, -1L, -1L, -1L, -1L, -1L);
        printf("TEST %-18s nr=%-3ld -> rc=%-3ld errno=%d (%s)\n",
               names[i], tests[i], r, errno, strerror(errno));
        fflush(stdout);
    }
    printf("PROBE ALL-DONE\n");
    return 0;
}
