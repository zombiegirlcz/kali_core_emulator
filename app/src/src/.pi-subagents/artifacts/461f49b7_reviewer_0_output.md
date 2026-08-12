All evidence gathered. I traced the full path: build (modal_build.py) → deploy (ProotManager.deploySuBridge) → socket wiring (launcher.sh bind) → wrapper (su_wrapper.c) → daemon (su_daemon.c) → exec. Here is the adversarial review.

## Review — CORRECTNESS & RUNTIME PROTOCOL (su/sudo privilege-escalation bridge)

### Blockers

**B1 — Daemon never spawns PRoot; commands exec directly on the HOST namespace.**
`su_daemon.c:195-209` — the child does `setresgid/setresuid` then `execvp(cmd_argv[0], cmd_argv)` with no reference to any rootfs, proot binary, `PROOT_LOADER`, or `LD_PRELOAD`. The design intent ("spawn a NEW one-shot PRoot instance `proot -r <rootfs> ... /bin/bash -c <cmd>`") is entirely absent. The daemon has no knowledge of the rootfs path at all.
Practical break: `sudo apt update` → wrapper sends argv `{"/usr/bin/sudo","apt","update"}` → host `execvp("/usr/bin/sudo")` → Android host has no `/usr/bin` → ENOENT → `perror` + `_exit(127)`. Even `su -c "apt update"` resolves `su` on the host (Magisk/toybox), not the guest. The command never runs in the guest, and `/usr/bin/apt` resolves in the host namespace (nonexistent). Fix: pass rootfs/proot/loader/talloc paths (wrapper already knows them via env — it runs inside the guest and could send them, or the daemon could derive them from the guest's `/proc/<wrapper-pid>/root`) and exec `proot -r <rootfs> -b ... /bin/bash -c "<cmd>"` with `PROOT_LOADER`/`LD_PRELOAD` set.

**B2 — Nothing ever starts the daemon.**
Grep for `su_daemon`/`magisk_daemon` in Kotlin shows only `deploySuBridge` (ProotManager.kt:787-808), which copies the binary to `filesDir` and sets exec bit — no `Runtime.exec`/`ProcessBuilder` anywhere launches it as root via Magisk su. The socket at `/data/data/com.linux_core/files/magisk_daemon.sock` is never bound → the wrapper's `connect()` always fails → permanent fallback path. Fix: add a daemon starter (e.g. `su -c <filesDir>/su_daemon` via Magisk, with keep-alive/restart) plus the SELinux caveat from R6.

**B3 — Wrapper is never installed as `/usr/bin/sudo` / `/bin/su`; originals never renamed.**
`deploySuBridge` (ProotManager.kt:763-784) only drops `su_wrapper` into `/usr/local/bin/su_wrapper`. Nothing creates `/usr/bin/sudo`→wrapper, `/bin/su`→wrapper, or renames to `su.orig`/`sudo.orig` (grep for `\.orig`, `ln -s` confirms). The bootstrap even keeps chmod'ing the real binaries (`ProotManager.kt:399`: `chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo`). So the user's `sudo` still invokes the distro's real sudo; the entire bridge is dead code. Fix: in `deploySuBridge` (or bootstrap), `mv` originals to `.orig` and symlink/copy the wrapper to `/usr/bin/sudo` + `/bin/su` (and `/usr/bin/su`), matching the `.orig` paths the fallback expects.

### High

**H1 — Wrapper returns before the command finishes; exit code is always 0.**
The daemon closes `client_fd` in BOTH parent and child immediately after `fork()` (`su_daemon.c:183, 216-218`). The child's stdio is the SCM_RIGHTS-duplicated PTY fds (which is why output still streams), but the socket connection dies at fork time → wrapper's `while (read(sock_fd, ...) > 0)` (`su_wrapper.c:123`) sees EOF immediately → returns 0. Consequences: shell prompt reappears while the elevated child still runs (output/prompt interleave); the child is orphaned into the daemon's session; `$?` is always 0 — `sudo false && echo ok` prints ok, `sudo apt update; echo $?` shows 0 on failure, so `&&`/`||` chains and scripts are silently wrong. Fix: keep the socket open until the child exits and write the exit status byte(s) before closing; wrapper should `exit(<status>)` and also `waitpid`-style block until EOF is genuinely caused by command completion.

**H2 — No TTY/job-control handling at all.**
No `setsid`/`setpgid`/`tcsetpgrp`/signal forwarding anywhere in su_daemon.c. The elevated child runs in the daemon's session, not the terminal's — Ctrl-C/Ctrl-Z on the user's PTY are delivered to the *foreground process group of the terminal session* (the wrapper/shell), never to the elevated child. Practical break: `sudo apt update` cannot be interrupted (a stuck `apt` holds dpkg locks); Ctrl-Z does nothing. Fix: pass the wrapper's pgid/sid in the payload and have the daemon child `setpgid(0, guest_pgid)`/`tcsetpgrp` (it has the PTY slave fd), or forward SIGINT/SIGTSTP over a control channel.

**H3 — No environment forwarding; exec uses the daemon's host PATH.**
Payload contains only uid/gid/argc/cwd/argv (`su_wrapper.c:40-55`, `su_daemon.c:52-59`). HOME, PATH, TERM, LD_PRELOAD, PROOT_LOADER are never transferred. `execvp` uses the root daemon's PATH (host), so even a hypothetical guest binary lookup is wrong. Fix: add an env block to the payload (at minimum TERM, HOME, PATH from the wrapper) and set it in the child before exec.

**H4 — SA_NOCLDWAIT leaks into the elevated child via exec.**
`setup_zombie_reaper` (`su_daemon.c:34-44`) sets SIGCHLD=SIG_IGN+SA_NOCLDWAIT; ignored dispositions survive `execve`, so the elevated command inherits them. For a single-shot `apt` it's mostly harmless, but for `sudo -i`/`sudo bash` the shell's `waitpid()` returns ECHILD → foreground/background job control inside the elevated shell breaks (children unreapable). Fix: reset `signal(SIGCHLD, SIG_DFL)` in the child before exec (the daemon parent keeps the reaper).

### Medium

**M1 — Payload can be silently truncated, and a 4096-byte payload makes the receiver overread.**
Sender caps at `BUFFER_SIZE` with `break` mid-argv (`su_wrapper.c:57-60`): args past the limit are dropped with no error → `sudo apt-get install a b c …` may execute a *different* command (truncated arg list) while `argc` still claims more. Receiver reads at most `BUFFER_SIZE-1` = 4095 (`su_daemon.c:47`) while the sender may send exactly 4096 → the final NUL is cut off → `strdup((char*)ptr)` (`su_daemon.c:98`) reads past `payload_buf` until a NUL in stack memory (UB, potential crash). Also there is no framing: a single `recvmsg` is assumed to deliver the whole stream payload (SCM_RIGHTS is tied to the first byte, but stream data can split; a split would silently parse garbage). Fix: length-prefix the payload, use a read loop (`MSG_WAITALL`), and abort loudly on overflow instead of truncating.

**M2 — FD leak on the daemon error path.**
If `recv_fds_and_payload` returns -1 after fds were already received (`fd_count < 3` or malformed payload, `su_daemon.c:74-82`, `172-174`), the received fds are never closed → fd leak in a long-lived root daemon → eventual fd exhaustion after repeated bad clients. Fix: close any received fds before `continue`; also check `msg.msg_flags & MSG_CTRUNC` (`su_daemon.c:56-68`) and reject.

**M3 — Wrapper fallback chain is broken in multiple ways.**
- `try_fallback` (`su_wrapper.c:70-89`) assumes `su.orig` lives at `/usr/bin/su.orig`, but design renames `/bin/su` (and B3 shows nothing renames anything) — access fails.
- Secondary fallback `/bin/su` is skipped when `argv[0] == "/bin/su"` (the exact case when the wrapper *is* `/bin/su`) → falls to direct-shell fallback, which for `su -c cmd` execs `argv[1]` = `"-c"` → failure.
- `strstr(argv[0], "sudo")` misclassifies any path containing "sudo" (e.g. `/usr/local/bin/sudo-test`).
Net effect: daemon down → confusing failures instead of graceful su.orig. Fix: derive `.orig` from the actual argv[0] path (`argv[0] + ".orig"`), restore `/bin/su` handling, and fix the shell fallback for the `-c` case.

**M4 — SIGPIPE can kill the wrapper instead of falling back.**
No `signal(SIGPIPE, SIG_IGN)` in the wrapper; if the daemon dies between `connect()` and `sendmsg()`, the wrapper dies with "Broken pipe" (141) instead of falling back to su.orig (`su_wrapper.c:113-119`). Fix: ignore SIGPIPE, check `sendmsg` return, then `try_fallback`.

**M5 — arm64-only ABI for both binaries, deployed to all guest archs.**
`modal_build.py:208,218` compiles both with `aarch64-linux-android24-clang` only, yet `deploySuBridge` copies the wrapper into every rootfs regardless of device arch (the rest of the app ships per-arch proot/loader/talloc for 4 ABIs). On x86/x86_64/armeabi-v7a devices the wrapper fails exec → broken/misleading fallback. Fix: build per-arch and deploy the matching one (or gate deployment on `Build.SUPPORTED_ABIS[0] == "arm64-v8a"`).

**M6 — Static-bionic build of the wrapper: OK for used calls, but the daemon's dynamic build and repo state deserve notes.**
- Good: the wrapper uses only socket/sendmsg/read/getcwd/execvp/str*/mem* — no `getpwnam`/`getaddrinfo`/`dlopen`/`iconv` (verified by grep), so `-static` bionic linking is safe here.
- The daemon is a dynamic arm64 binary placed in the app's `filesDir` and (hypothetically) run via Magisk su — SELinux: the socket is created in the root/su domain while the connecting guest runs in `untrusted_app`; `unix_stream_connect` between those domains is typically denied by default policy, and app-data traversal is 0700. Expect a runtime SELinux/permission failure on stock Magisk setups; needs a policy test on device. The daemon also runs with the caller's context when launched via Magisk su — verify it can even `bind` in `filesDir`.
- Repo hygiene: `su_daemon.c`/`su_wrapper.c` are untracked; the built binaries land in `assets/` untracked (not LFS-ignored), and on any build where `build_native` didn't run, `deploySuBridge` silently no-ops (`Log.w` at ProotManager.kt:784) → feature silently absent.

### Minor

**m1 — `chdir(cwd)` result unchecked; cwd is a guest path.** `su_daemon.c:193-195` — guest CWD like `/root` may not map to a meaningful host dir; failure silently ignored. Acceptable only because the PRoot redesign (B1) makes cwd semantics guest-side again.
**m2 — Wrapper's `SECONDARY_SOCKET` (`/data/data/...`) is unreachable from inside the guest** (`su_wrapper.c:21`): `/data` is not bind-mounted in `launcher.sh` BINDS, so it resolves into the rootfs's empty `/data`. Dead fallback path.
**m3 — `launcher.sh` binds the entire `FILES_DIR` into the guest at `/run/host_ipc`** — exposes app logs/daemon binary inside the guest; also requires `/run/host_ipc` to exist in the rootfs or the bind silently fails. Prefer a dedicated subdir (`filesDir/su_socket`).
**m4 — Unsigned underflow in receiver arg loop**: when `n` is small, `remaining = (size_t)n - (ptr - payload)` wraps huge (`su_daemon.c:95`); contained only because `payload_buf` is zeroed — sloppy, fix with a proper `ptr <= payload + n` guard.
**m5 — No auth/validation on the socket**: `chmod 0777`, no token, UID/GID/argv fully client-controlled; any code running in the guest (or app domain, if SELinux allows) gets arbitrary host-root exec + fd injection. This is the feature's intent, but combined with B1/B2 the *only* current effect of the C code is an unauthenticated root-exec socket that nothing binds. If/when wired up, add the same token auth the rest of the app uses.

### Commands run (read-only)
- `git status --short`, `git branch --show-current`, `git log --oneline -5` — passed
- `git diff --stat` + diff of launcher.sh, modal_build.py, ProotManager.kt, TerminalService.kt, ShizukuManager.kt — passed
- Reads of su_daemon.c / su_wrapper.c / launcher.sh / modal_build.py — passed
- Greps: `su_wrapper|su_daemon|su.orig|sudo.orig|magisk_daemon`, `sudo` in Kotlin, `exec|ProcessBuilder|Runtime.getRuntime`, `.orig|ln -s`, static-libc calls — passed (negative results are the evidence for B2/B3)