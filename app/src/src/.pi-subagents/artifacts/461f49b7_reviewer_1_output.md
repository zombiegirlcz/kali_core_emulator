I have all the evidence I need. Here is my review.

## Review

**Scope note:** `/root/kali_core_emulator/plan.md` and `progress.md` do not exist (task said to read them; the repo has `docs/PLAN.md` instead — I read the actual changed/untracked files directly). I did not edit anything.

---

### BLOCKER-1 — Unauthenticated root RCE for the entire app-UID trust domain
**Files:** `app/src/main/cpp/su_daemon.c:146` (`chmod(socket_path, 0777)`), `su_daemon.c:47-118` (no auth anywhere), `su_daemon.c:207-208` (`execvp(cmd_argv[0], cmd_argv)` as root)

The daemon has **zero authentication**: no token in the payload, no `SO_PEERCRED` uid check, no per-request authorization, no confirmation prompt (contrast with the repo's own `OffensiveEngine` Allow/Deny gate), no logging, no rate limit. Reachability: DAC on `/data/data/com.linux_core/` (0700, app UID) plus SELinux excludes *other* apps, but **every process in the app's own UID domain can connect**: the app process itself, anything injected into it, and — decisively — the entire PRoot guest, which is an *untrusted* compute environment (user installs arbitrary packages, runs arbitrary downloaded tools, code-server with JS execution). Any compromise there (malicious apt/pip package, XSS in code-server, rogue binary) yields UID 0 on the host with no further step. Impact: read `/data` of every app, install/uninstall APKs, flip device security settings, exfiltrate credentials, persistent backdoor.

### BLOCKER-2 — Attacker-controlled argv[0] = arbitrary binary executed as root; payload uid/gid/args fully unvalidated
**Files:** `su_wrapper.c:123-134` (forwards raw `argc`/`argv` including argv[0]), `su_daemon.c:199-208` (client-supplied uid/gid, then `execvp(cmd_argv[0], cmd_argv)`)

`target_uid`/`target_gid` come straight from the client with no validation (any uid/gid accepted, including 0). Worse, `cmd_argv[0]` is the client's argv[0]. A guest process can do `execve("/usr/local/bin/su_wrapper", ["/run/host_ipc/evil", ...])` — argv[0] is an absolute path inside the bound filesDir, which the guest (app UID) can write to and which is exec-able (PRoot itself runs from filesDir). The daemon child does `setresuid(0)` then `execvp("/run/host_ipc/evil")` → **guest-written binary runs as root**. The wrapper is not even needed; any same-UID process can craft the wire protocol directly. The daemon has **no destructive-command blocklist** (the project's `LocalApiServer.kt:996-998` blocks `rm -rf /`, `mkfs`, etc., and `:1020-1021` caps command length; this daemon has neither), and a blocklist couldn't stop arbitrary binary exec anyway.

### HIGH-1 — Protocol mismatch: daemon execs the wrapper itself, never the requested command
**Files:** `su_wrapper.c:130-134` (sends full argv incl. argv[0]), `su_daemon.c:207-208`

`su -c id` → payload argv = `["/usr/local/bin/su", "-c", "id"]` → daemon child `execvp("/usr/local/bin/su")` **on the host filesystem** (host process; guest paths don't exist there) → ENOENT → `_exit(127)` → wrapper gets EOF and exits 0 with no output. Functionally broken. Edge case: if argv[0] is relative (`su`) it resolves against the *host* PATH and silently executes the host's Magisk `su` instead — the wrong su, wrong semantics. Any fix must decide who strips argv[0]; today nobody does.

### HIGH-2 — Bind mount exposes the entire app filesDir to the guest
**File:** `app/src/main/assets/launcher.sh:55` (`-b $FILES_DIR:/run/host_ipc`)

The guest previously had **no** access to host filesDir (paths outside the PRoot root). This bind grants read/write over everything in it:
- `su_daemon` binary (`ProotManager.kt:789-806` deploys it with `setExecutable(true,false)`, app-UID-writable) → guest replaces it via `/run/host_ipc/su_daemon`; next time the daemon is started as root, **root executes the guest's binary** — a persistent root backdoor independent of the socket.
- `launcher.sh`, the proot binaries, `nh` CLI, `shizuku.apk`, AI training data, other distro rootfs, and `tmp/nethunter_agent_token` (`LocalApiServer.kt:1367-1382`).
Only the socket file needs to be visible. Fix: bind a dedicated IPC subdir or the socket file itself (`-b $FILES_DIR/ipc:/run/host_ipc`, socket at `filesDir/ipc/magisk_daemon.sock`, daemon started before the guest) and integrity-check the deployed daemon binary.

### MED-1 — FD passing: root child's stdio = attacker-chosen fds; leaked extra fds
**Files:** `su_daemon.c:82-96` (only first 3 of received fds copied), `su_daemon.c:184-190` (`dup2` to 0/1/2)

Client-supplied fds become the root child's stdin/stdout/stderr. Concrete amplification: a guest holding a **read-only-granted** fd (SAF/URI permission) passes it as fd[1] → root child writes through it — a file the app UID could only read. Additionally, if the SCM_RIGHTS message carries more than 3 fds, the extras are **never closed** (`fd_count` up to unbounded vs `max_fds=3`) — they leak into the root daemon and are inherited by every root child across exec: fd exhaustion DoS and inherited attacker fds in root processes.

### MED-2 — Single-threaded accept loop with blocking recvmsg → socket DoS
**File:** `su_daemon.c:157-193`

`accept` → `recvmsg` with no `SO_RCVTIMEO`; one guest process that connects and stalls wedges the entire root bridge for all other clients. A crashed/infinite-looping app component or a malicious guest process can brick `su` globally.

### LOW-1 — OOB read in payload parser (memory safety)
**File:** `su_daemon.c:107-121`

If the cwd string has no NUL within the received bytes, `cwd_len == remaining`, then `ptr += cwd_len + 1` advances past the received region and the next `remaining = n - (ptr - base)` underflows to a huge `size_t`; `strnlen` then scans past `payload_buf[4095]` on the daemon's stack (read-only OOB, up to 127 iterations via client-controlled `argc`). Crash (DoS of the root bridge) or stack-data disclosure from the root process. Attacker is same-UID, but this is a root daemon parsing untrusted bytes — fix with explicit bounds checks.

### LOW-2 — Privilege-drop hygiene
**File:** `su_daemon.c:199-205`

`setresgid`/`setresuid` failures are only `perror`'d and execution continues — if a non-root target uid is requested and the drop fails, the command still runs (as root, inverted failure mode). Supplementary groups are never cleared, `umask` not set (root-created files inherit daemon umask), and `chdir` runs *before* the uid switch.

### NOTE-1 — Static wrapper asset + committed signing key
**Files:** `tools/modal_build.py:204-222` (`cc -static` into assets), `ProotManager.kt:759-810` (no integrity verification of deployed binaries)

On-device asset replacement isn't feasible (signed APK). But per AGENTS.md the signing keystore `app/release.jks` (password `password123`) is committed to the repo — anyone with repo access can rebuild with a malicious `su_daemon` baked into assets and the app accepts the update as genuine. Pre-existing repo issue, but with this feature the payoff becomes "guaranteed root RCE on install." Also, deployed binaries are only size-checked, never hash-verified, so guest-side overwrite (HIGH-2) is undetected.

### NOTE-2 — No lifecycle/auth integration; SELinux unknown
Nothing in the repo starts or supervises `su_daemon` (grep of all Kotlin/assets finds deploy code only) — exposure begins when a user starts it via Shizuku/ADB as root. On stock builds, SELinux (shizuku-domain socket vs `untrusted_app` client) may incidentally block guest connects — the only boundary that exists, and it protects nothing against the app's own UID.

### Posture comparison
The project's own bar (`LocalApiServer.kt`: bearer token via `api_security` prefs, localhost checks, command blocklist `:996-998`, 1024-char cap `:1020-1021`; `OffensiveEngine` Allow/Deny confirm; `SECURITY_AUDIT.md` 25 findings) is entirely absent in this new code. This bridge drops far below it: no auth, no allowlist, no confirmation, no logging, no limits — and it converts "unprivileged code inside the guest" into "UID 0 on the host" without any of the gates the rest of the app applies.

---

## Acceptance Report