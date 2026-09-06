## Review

Reviewed (read-only): `app/src/main/cpp/su_daemon.c`, `app/src/main/cpp/su_wrapper.c`, `app/src/main/assets/launcher.sh`, `app/src/main/assets/nh` (fix_dispatch), plus `ProotManager.kt` (template rendering of launcher-*.sh), `ui/RootBridgeTab.kt` (daemon argv), `plan.md`/`progress.md` (absent from repo root — no progress files present).

---

### Q1 — FAIL-CLOSED (host execution of guest commands)

**Correct.** Every command path in `su_daemon.c` converts the request into a PRoot re-entry:
- `su_daemon.c:497-500` (child): if `launcher_path[0]=='\0' || access(launcher,X_OK)!=0` → `_exit(126)` — refused, never exec'd. Verified the guard is unconditional and before any exec.
- `su_daemon.c:514-536`: `execv(launcher_path, [launcher, "--", guest-args...])` — the guest argv goes after `--`, so it can never be interpreted as daemon/launcher flags.
- launcher.sh `--` mode (launcher.sh:71-92) always ends in `exec "$PR" ... proot ...`; if PR is unusable the launcher exits at launcher.sh:26-33 **before** the `--` block → child exit 1, still no host exec.
- **No bypass found for running the guest command on the bare host.** The `try_fallback()` in su_wrapper.c:55-84 only fires when the daemon socket is unreachable and executes `su.orig`/`sudo.orig`/argv[1] — but that runs *inside the already-PRooted guest shell*; it cannot reach the host. A stale/old `launcher-*.sh` without the `--` block fails safe (proot would try to exec a program literally named `--`).

**Caveat (model gap, not an exec bypass):** the "can never touch the host filesystem" claim is overstated because the launcher binds real host paths into the guest: launcher.sh:53-57 `-b /dev -b /proc -b /sys` + `-b $FILES_DIR/ipc:/run/host_ipc` + `-b /sdcard` (when mountStorage) + `-b /system:/mnt/system` + `-b /dev/bus/usb:/mnt/usb` (both default ON in ProotManager, see `bind_system=true`, `bind_usb=true` at ProotManager.kt:1270-1273). The daemon runs as **real uid 0** (it never drops privileges), so a guest command like `chmod -R 777 /dev` or `rm -rf /sdcard/*` or `rm -rf /dev/block` operates on the *real* host paths through the binds with full kernel privilege. PRoot only confines paths outside the binds. The near-brick (`chmod -R /`) is blocked by the blocklist, but the same damage class is reachable via bind-root targets (see Q5).

---

### Q2 — TOKEN/QUOTING SAFETY

**Correct.** `su_daemon.c:505-536` builds `launcher_argv` as a proper C argv array; every token is a separate `argv[i]` → `execv`. In launcher.sh:87 the shim is a **fixed literal** `-c 'unset LD_PRELOAD PROOT_LOADER; exec "$@"'` — guest tokens are never interpolated into the `-c` string, they only populate `"$@"` after the `--`. In the guest `/bin/sh`, `--` sets `$0`, remaining tokens become `$1..$n` (no re-parsing), and `exec "$@"` (double-quoted) executes argv faithfully. Crafted args containing spaces, `;`, `$(...)`, backticks, newlines, or single quotes remain single argv entries — no shell re-quoting, no shim escape. The single-quoted `-c` literal at launcher level prevents host-shell expansion. One cosmetic edge: if the first forwarded token starts with `-`, `exec`'s own options (`-c`/`-a` in dash) could be consumed, but that only clears env/sets argv0 — no privilege or containment change; the `su -c`/`sudo` paths always put `/bin/sh` (or `/bin/bash`) as token 0 so this is unreachable in practice. Also `$PROOT_FLAGS`/`$BINDS` are unquoted at launcher.sh:86, but they are template-controlled constants (launcher.sh:53-59), not attacker input.

---

### Q3 — PATH CONTAINMENT (handle_fix_request + auto-fix)

**Correct in structure, with a TOCTOU-free walk:**
- `su_daemon.c:315-329`: `realpath(rootfs)` + `realpath(host)` with a prefix-boundary check (`rtarget[rrl]=='\0'||'/'`) — blocks `rootfs-evil` and symlink escapes (e.g. `/root/link->/sdcard`). First-component bind check at su_daemon.c:293-304 rejects `/dev`, `/sdcard`, `/mnt`, etc. even with `..` games.
- The walk fixes `rtarget` (already canonical), so no symlink re-resolution is possible during the walk; `nftw(..., FTW_PHYS)` + `lchown` (su_daemon.c:256-258) never follows symlinks — a symlink swapped in mid-walk is reported as `FTW_SL` and not descended; `lchown` hits only the link itself. Residual TOCTOU is negligible (no follow → no escape).
- Auto-fix (su_daemon.c:566-576): walks whole rootfs with `FTW_PHYS` + `lchown`, skips bind dirs at level 1 via su_daemon.c:250-253. The skip list (su_daemon.c:234-242) covers all binds declared in launcher.sh:53-57 (`dev proc sys run sdcard mnt` + extra `system vendor product apex storage data`) and is sufficient as defense-in-depth *for the fix walk*.

**Two issues:**
1. **Performance/race (Medium):** auto-fix runs `nftw()` over the **entire rootfs** after *every* command (su_daemon.c:566-576) — on a multi-GB Kali rootfs this is a full-tree stat+lchown walk per `sudo`/`su` invocation; and it races with any still-running guest process (background `apt`, etc.) chowning files mid-flight. The `%ld ms` log line will show this. It also silently reverts *any* root-owned file, including ones the user intentionally made root-owned.
2. **Skip-list functional edge (Note):** `data` is in the skip list (su_daemon.c:237); a guest top-level `/data` directory would never be fixed → root-owned guest files under it stay inaccessible to the app (functional, not security).

---

### Q4 — PROTOCOL INJECTION

**No injection path found.** The payload is `[uid][gid][argc][cwd\0][arg0\0]...` parsed with `strnlen`/bounds (su_daemon.c:88-119); there is exactly one argv array and one `execv`. Newlines/NULs can't add argv entries (NUL terminates each arg; newlines are payload bytes inside one token, and `"$@"` keeps them as one token). `argc` is bounded by `max_args-1`. Nothing after `--` can reach proot/launcher options.

**Weaknesses (Low):**
- **No peer-credential check:** the daemon `accept`s any client (su_daemon.c:439) with socket mode 0777 (su_daemon.c:409). Today only the app and the guest can reach it (app-private dir; guest via the `/run/host_ipc` bind), but any process that can reach the path can drive arbitrary root commands and can forge `@FIX`. `SO_PEERCRED` (or requiring the connecting UID) would harden this.
- **DoS of the bridge (Low):** the guest can `rm /run/host_ipc/magisk_daemon.sock` (it's inside the bound `ipc` dir), orphaning the live listener and making new `su_wrapper` connections fail → silent fallback to `su.orig` (guest-only, no host impact, but the escalation feature dies silently).
- **`@FIX` is accepted from any client** (su_daemon.c:467) — harmless by itself (all its paths are validated), but it's an undocumented guest→daemon entry point worth gating with the same auth.

---

### Q5 — deny_command blocklist

**Necessity:** still necessary, because guest root can damage host via binds (see Q1 caveat). **Consistency:** poor. Concrete evasions, all reachable via `su -c '...'` or direct argv:

1. **Quote evasion of the shell-payload scanner (High).** `deny_shell_payload` (su_daemon.c:141-165) tokenizes with `strtok_r` and never strips quotes: `su -c 'rm -rf "/"'` → token `"/"` ≠ `"/"` → `hits_root=0` → **not denied**. Same for `'/*'`, `'chmod -R 777 "/"'`.
2. **Danger set too narrow (High).** The payload scanner only flags `chmod/chown/chgrp/rm/rmdir` (su_daemon.c:156-157). `su -c 'dd if=/dev/zero of=/dev/block/by-name/xxx count=1'` (host block device through the `/dev` bind — the daemon is real uid 0) is **not denied**, even though `dd` is in the direct-argv banned list (su_daemon.c:173-199). Same for `find / -delete`, `mkfs.*` via `sh -c`, `rm -rf /dev/block`, `chmod -R 777 /sdcard` — the argv rules for chmod/chown only guard target exactly `/` (su_daemon.c:216-221) and rm only guards `/`, `*`, `/*` (su_daemon.c:228-235); **bind-root targets (`/dev`, `/sdcard`, `/mnt/...`, `/system`) are not covered at all**, so recursive destructive ops on real host paths pass.
3. **Nested/indirect forms (Note):** `sh -c 'cd / && chmod -R 777 .'` → target `.` ≠ `/` → allowed; inside the guest that chmods everything under `/`, i.e. the rootfs **plus all binds** — host `/dev`, `/sdcard`, `/system`. The direct chmod-argv rule can't see this because the command is a `-c` string.

So: the flagship near-brick (`sudo chmod -R 777 /`) is blocked, but the blocklist is best-effort heuristics, not a real boundary. The real boundary is supposed to be PRoot, and the binds poke holes in it.

---

### Q6 — Process/lifecycle

- **waitpid + zombie handling: correct** — one `waitpid(pid,&status,0)` per child (su_daemon.c:543-549); `--kill-on-exit` makes proot reap guest children; exit codes mapped `128+sig`/`WEXITSTATUS`; daemon children reaped so no zombies accumulate in the daemon.
- **126 semantics: overloaded but consistent** — 126 = deny / fail-closed / fix-reject. Note `su_wrapper.c:99-104`: if the exit-code read returns `0` bytes (daemon crashed between connect and reply), the wrapper returns **0 (success)** — a silent success on daemon failure; should be a non-zero "daemon lost" code.
- **stderr routing: correct** — per-request `dprintf(fds[2])` → guest client's stderr; daemon diagnostics → the `> log 2>&1` redirect in RootBridgeTab.kt:218.
- **double-exec: safe** — single chain daemon→launcher→proot→guest-sh→command; no recursion.
- **fd leak (Medium/Low):** the deny path (su_daemon.c:457-463) and the `recv_fds_and_payload` failure path close `client_fd` but **not** the received `fds[0..2]` — 3 leaked fds per denied request until the daemon is killed.
- **Bare-su detection edge (Note):** `argc==0` or `argv[0]==""` from a client yields an interactive guest root shell (`launcher.sh --` with 0 args → login shell). That's the intended `su` behavior, but note the socket gives full guest-root shell to any client that reaches it — which is the point of the bridge, yet there is no auth to distinguish the app/`su_wrapper` from anything else that can connect.
- **uid-as-gid (Info):** RootBridgeTab.kt:214 passes `$appUid $appUid` — gid is set to the uid value; harmless in practice (Android app gid == uid) but should be `Process.myUid()` + actual gid (or just drop the gid param).

---

## Findings (severity-ranked)

| # | Sev | Location | Finding |
|---|-----|----------|---------|
| 1 | **High** | su_daemon.c:141-165 | `deny_shell_payload` doesn't strip quotes → `rm -rf "/"` / `rm -rf "/*"` evades the root-wipe guard |
| 2 | **High** | su_daemon.c:153-164, launcher.sh:53-57, ProotManager.kt:1270-1273 | Blocklist misses bind-root targets and `dd`/`find -delete`/`mkfs` in `sh -c` payloads; guest root (real uid 0) can chmod/rm host `/dev`, `/sdcard`, `/system`, `/mnt/usb` through binds — "can never touch host" is false for bound paths |
| 3 | **Medium** | su_daemon.c:566-576 | Auto-fix full-tree `nftw` after every command: O(rootfs) per sudo call + race with concurrent guest writers + silently reverts intentional root-owned files |
| 4 | **Medium** | su_daemon.c:457-463, 432-435 | FD leak (3 fds) per denied/malformed request (SCM_RIGHTS fds never closed) |
| 5 | **Low** | su_daemon.c:437-443, 409 | No `SO_PEERCRED`/auth; socket 0777 — any process that can reach the path drives root commands and can forge `@FIX` |
| 6 | **Low** | su_wrapper.c:99-104 | Returns 0 (success) when daemon dies before replying — silent success |
| 7 | **Low** | RootBridgeTab.kt:218, nh guest, launcher.sh:54-55 | Guest can unlink the IPC socket → silent DoS of the escalation bridge (no host impact); also `$appUid` passed as both uid and gid (RootBridgeTab.kt:214) |

**What's genuinely good (verified):** fail-closed host execution (Q1 — no bare-host exec path exists), token-faithful `--` forwarding with a fixed-literal shim (Q2 — no injection), solid realpath containment + FTW_PHYS/lchown fix walk (Q3 — no symlink escape, no follow, TOCTOU negligible), no protocol argv smuggling (Q4), correct waitpid/exit-code/zombie handling (Q6).

## Acceptance report