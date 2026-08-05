# Review — su/sudo Host-Root Bridge: Integration, Wiring & Architecture

## Review

**Headline:** The feature is dead scaffolding end-to-end. Three independent blockers: (1) nothing ever starts `su_daemon`, and the app no longer has any root bootstrap left; (2) `deploySuBridge` installs the wrapper as `su_wrapper` only — never as `su`/`sudo`, never renames originals; (3) even if both were fixed, the daemon `execvp`s the wrapper's own `argv[0]` **on the host**, never the guest command, never inside PRoot. The mount wiring (bind path) is the one piece that actually lines up.

### BLOCKER 1 — STARTUP: nothing launches `su_daemon`; no root bootstrap exists
- `deploySuBridge` (ProotManager.kt:759-810) only copies the binary to `context.filesDir/su_daemon` + `setExecutable`. Full-repo grep for `su_daemon|magisk_daemon` in Kotlin shows only the deploy code; no `ProcessBuilder`/`Runtime.exec`/shizuku/nh path starts it. `su_daemon.c:201-204` calls `setresuid(0)` — impossible from an app-uid process, so the daemon *must* be spawned as root.
- Verified: ShizukuManager.kt was stripped of every su path — `suAvailable` hardcoded `false` (line 14, 175), `startWithSu()` deleted, `exec()` su fallback removed; only ADB-based Shizuku start remains (needs a PC). No Magisk `su -c` anywhere in the repo (TerminalActivity "su" hits are just "suggestion" substrings).
- **What breaks:** the socket is never created → wrapper's `access()` on both socket paths fails → `try_fallback()` every time. And the fallback is also unreachable (Blocker 2).
- **Fix:** wire daemon startup into `ShizukuManager.exec` (one-shot `startServer`/root bootstrap, PID file, restart on session prepare), or re-add a Magisk su bootstrap. Until a root launcher exists, the daemon is inert.

### BLOCKER 2 — INSTALLATION: wrapper never shadows `su`/`sudo`
- ProotManager.kt:765 installs only `usr/local/bin/su_wrapper`. No `su`/`sudo` targets, no `su.orig`/`sudo.orig` renames, no symlinks (the `compatNames` list at :636 has none).
- `su_wrapper.c:73` fallback probes `/usr/bin/su.orig`/`/usr/bin/sudo.orig` — those renames never happen. Header comment (su_wrapper.c:3) "Deployed in PRoot guest as /usr/local/bin/su and /usr/local/bin/sudo" is stale vs. actual install name.
- **What breaks:** guest `su`/`sudo` keep hitting Kali's real binaries; the wrapper is invocable only by manually typing `su_wrapper`. Nothing in the guest (zshrc, bootstrap, nh CLI) references it.
- **Fix:** deploy copies/symlinks `/usr/local/bin/su` + `/usr/local/bin/sudo` → wrapper (Kali root PATH puts /usr/local/bin ahead of /usr/bin and /bin, so no renames needed); optionally rename originals for the wrapper's `su.orig` fallback.

### BLOCKER 3 — EXEC: daemon runs the wrapper's `argv[0]` on the HOST, outside PRoot
- `su_wrapper.c` forwards full `argc, argv` including `argv[0]`; `su_daemon.c:207-208` does `execvp(cmd_argv[0], cmd_argv)` — for `sudo apt update` that's `execvp("sudo")` in the **host** namespace (host PATH, `/system/bin`-world). Android has no `sudo`/`apt` there → perror + `_exit(127)` printed to the guest's stderr. Even `su_wrapper ls` would `execvp("su_wrapper")`.
- The protocol carries no rootfs path / proot binary / PROOT_LOADER/LD_PRELOAD, and the passed cwd is a guest-virtualized path (`chdir` return ignored, su_daemon.c:196-198). No PRoot is ever invoked on the daemon side.
- **Fix:** wrapper must strip `argv[0]` and forward `argv[1..]` as the command, plus the rootfs path + proot loader env; daemon must exec via a one-shot `proot -r <rootfs> …` — otherwise guest commands can never run as intended.

### HIGH 4 — No wait-for-completion; exit code always 0
- Daemon parent closes `client_fd` + passed FDs immediately after `fork()` (su_daemon.c:180-186, 212-216) → the wrapper's `while (read(sock_fd, …) > 0)` (su_wrapper.c:135-138) hits EOF instantly. The dup2'd pty FDs keep output visible, but the shell prompt returns while the command still runs; `sudo x && sudo y`, `set -e`, and exit-status handling all break.
- **Fix:** keep the accepted socket open in the daemon parent until `waitpid()`, then send the exit status before closing; wrapper returns it as its own exit code.

### MEDIUM 5 — Secondary socket path is dead inside the guest
- `su_wrapper.c:21` `/data/data/com.linux_core/files/...` — the guest rootfs has no `/data` (launcher.sh binds only `FILES_DIR:/run/host_ipc`). The "fallback" path never resolves. Harmless while PRIMARY works, but it's a false safety net.

### LOW 6 — Asset size check via `InputStream.available()`
- ProotManager.kt:769/793: for `AssetInputStream`, `available()` = remaining **uncompressed** length — reliable enough for these ~100KB binaries; worst case a mismatch causes a redundant redeploy (benign). Equal-size content change would skip redeploy (rare for binaries). Not a blocker; prefer unconditional `copyTo`.
- Note: `su_daemon`/`su_wrapper` don't exist in the local assets dir (generated only on the Modal volume) — consistent with the no-local-build convention; `mbuild build` alone (without `all`) packages stale/missing native assets (pre-existing pattern shared with `usb_bridge`).

### LOW 7 — Mount wiring: paths DO line up (the one correct piece)
- launcher.sh:55 `-b $FILES_DIR:/run/host_ipc`, template filled with `context.filesDir` (ProotManager.kt:1518); daemon `DEFAULT_SOCKET_PATH` = `/data/data/com.linux_core/files/magisk_daemon.sock` (su_daemon.c:25). These are the same directory on devices where `/data/data` is a symlink to `/data/user/0` (standard on API 28+); the guest path `/run/host_ipc/magisk_daemon.sock` is the same file as the daemon's bind. Kali rootfs has `/run`, and PRoot creates missing bind destinations. So the socket plumbing would work — if the daemon existed.
- Integration-facing security note: the bind exposes the entire app filesDir into the guest, and `chmod(socket_path, 0777)` (su_daemon.c:136) with no peer/auth check means any local process can request root exec — relevant once a launcher is added.

### LOW 8 — AGENTS.md compliance
- Pipeline order verified: `mbuild all` = `upload_src` → `build_native` → `build` (modal_build.py:344-361, mbuild); `build_native` writes `su_daemon`/`su_wrapper` into volume assets (modal_build.py:204-224) before gradle packages them; asset names match `assets.open(...)`. No contradiction with the documented Modal-only build flow.
- Watch-out: AGENTS.md records that static binaries were removed 2026-08-01 ("nefungovaly"), yet `su_wrapper` is again built `-static` with bionic clang (modal_build.py:224). `usb_bridge` precedent exists, but static-bionic `execvp`/`getcwd` deserve a device smoke test.
- TerminalService.kt diff (clipboard NUL/whitespace cleanup) is unrelated to this feature — no conflict. ShizukuManager su-stripping is directly relevant (it removed the only root bootstrap).

### Redeploy cadence
`deploySuBridge` runs from `deployApiScripts` (ProotManager.kt:81 → 627) on every session prepare, gated by length/available-size checks — cheap when unchanged. `setExecutable` on filesDir works (`/data` is not noexec).

## Acceptance Report