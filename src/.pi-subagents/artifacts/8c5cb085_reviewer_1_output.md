## Review

### Verified correct (with evidence)

**1. Workflow permission/scheme — legal on Android 12/13/14 (with one manifest inconsistency, see MED-1)**
- `RECEIVE_BOOT_COMPLETED` + `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` all declared (AndroidManifest.xml:8-10). `BootReceiver` is `exported="true"` with `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` (AndroidManifest.xml:237-241).
- The boot path starts the FGS via `context.startForegroundService(intent)` on O+ (TerminalService.kt:224), which is the *correct* API for starting a foreground service from a background context. `startForeground()` is called synchronously at the top of `onStartCommand` (TerminalService.kt:305), so there is no `ForegroundServiceDidNotStartInTimeException` (5s ANR) risk.
- Android 12+ background-FGS-launch restrictions explicitly exempt apps that receive `ACTION_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED` — so `createSession → startService` from the receiver is legal.
- `directBootAware` is correctly **not** needed: only `BOOT_COMPLETED` (post-unlock, credential-encrypted storage available) is used; `LOCKED_BOOT_COMPLETED` is not registered (AndroidManifest.xml:240-241). Matches AGENTS.md note.

**2. customCommand/PROOT assembly — correct, no quoting/argv mismatch**
`BackgroundBoot` passes `"bash /root/.nh_boot.sh"` as a single argv element (BackgroundBoot.kt:41). Trace:
- `setupProotEnvironment` → `fullCommand = ["/system/bin/sh", launcherFile, customCommand]` (ProotManager.kt:110-113) — one element, no splitting.
- launcher.sh receives `$1="bash /root/.nh_boot.sh"` and forwards it verbatim: `set -- "$PR" ... /bin/sh /root/entrypoint.sh "$@"` → `exec "$@"` (assets/launcher.sh:96-118).
- entrypoint.sh runs `exec "$ENTRY_SHELL" -c "$*"` when args exist (ProotManager.kt:507-511) → `bash -c "bash /root/.nh_boot.sh"` inside the guest → boot script (cron + `while true; sleep 60`) executes in the guest rootfs. Confirmed correct end-to-end.

**3. Prefs consistency — consistent in all three places**
- BootReceiver.kt:24-25: `"vpn_settings"` / `"boot_autostart"` / default `true`.
- TerminalService.kt:309-310: same file, key, default.
- MainActivity.kt:320, 1000, 1053: same file, key, default. No drift.

**4. START_STICKY restart logic — sound in the process-kill case**
The static `sessions`/`sessionClients` lists die with the process; on START_STICKY restart `onStartCommand(intent=null)` sees `sessions.isEmpty()` (fresh process, TerminalService.kt:310-315) and re-runs `BackgroundBoot.start`, re-deploying proot + cron. `PARTIAL_WAKE_LOCK` + FGS keep the process alive between restarts; the `while true; sleep 60` loop (BackgroundBoot.kt:70) keeps the PTY child from exiting so `onSessionFinished` doesn't tear down the session.

---

### Findings ranked by severity

**MED-1 — `foregroundServiceType="specialUse"` declared without `FOREGROUND_SERVICE_SPECIAL_USE` permission** (AndroidManifest.xml:108; permission list at lines 5-10)
The service declares `android:foregroundServiceType="specialUse"` but the manifest never declares `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, and the code uses the 2-arg `startForeground(int, Notification)` (TerminalService.kt:305) — not `ServiceCompat.startForeground(..., TYPE_SPECIAL_USE)`. At targetSdk 28 this currently does not crash (API-34 FGS-type enforcement is targetSdk-gated), but the combination is inconsistent and will throw `SecurityException`/`MissingForegroundServiceTypeException` if targetSdk is ever raised to 34+. **Fix:** either drop `foregroundServiceType="specialUse"` from the three services that declare it, or declare the permission + use `ServiceCompat` with the type.

**MED-2 — No dedup guard on `BackgroundBoot.start` → duplicate cron sessions** (BackgroundBoot.kt:23-52, TerminalService.kt:310-315)
`BackgroundBoot.start` unconditionally creates a new session. Two realistic duplicate triggers:
- `ACTION_MY_PACKAGE_REPLACED` fires while the app is already running with a live cron session (BootReceiver → BackgroundBoot → *second* session with a second `crond` and a second `while sleep 60` loop — doubled CPU/PTY).
- Race between a `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` receiver invocation and a concurrent `onStartCommand(intent=null)` restart.
**Fix:** track a "headless cron session already active" flag (e.g. a `@Volatile` `cronSessionId` set inside `createSession`, or check `sessions` for a session whose env contains `NETHUNTER_SESSION_ID`/a marker) and skip if present.

**MED-3 — Clean (non-kill) death of the sole cron session permanently stops cron** (TerminalService.kt:163-171, ViewHostSessionClient.onSessionFinished → removeSession)
If the cron session's PTY child exits cleanly (guest `bash` crash, proot error, `sleep` churn), `onSessionFinished` → `removeSession` → `sessions.isEmpty()` → `stopForeground + stopSelf()` (TerminalService.kt:171-174). `START_STICKY` only rescues on *process* kill; after `stopSelf` nothing re-spawns cron until the next boot / app launch / package replace. `boot_autostart=true` remains set, but the feature silently dies. **Fix:** in `removeSession`, when `sessions.isEmpty() && boot_autostart && instance was started for cron`, schedule a bounded re-launch instead of unconditional `stopSelf` (with a backoff to avoid loops).

**MED-4 — Headless bootstrap runs synchronously and silently on first boot** (assets/launcher.sh:73-87 + BackgroundBoot.kt:28-36)
When `.setup_done` is absent, launcher.sh runs the full bootstrap (apt, packages, users — minutes) inside the `BackgroundBoot` thread with no UI and no indication. On a fresh install the device will spend minutes bootstrapping headlessly after every reboot until it succeeds; a process cull mid-bootstrap leaves `.bootstrap_required` and restarts from scratch. Acceptable per requirement, but should be gated (e.g. skip background boot until the user has run an interactive session once) or at minimum logged clearly.

**LOW-5 — `detectActiveRootfsDir` selects newest rootfs non-deterministically** (BackgroundBoot.kt:56-63)
With multiple rootfs dirs (e.g. kali-arm64 + parrot-arm64), the "active" distro is chosen by `lastModified`, which may not match the distro the user actually uses — cron jobs run in a possibly unexpected distro. **Fix:** read the last-selected distro from prefs (`selectedDistro`/`RootfsManager.DISTROS`) instead of mtime.

**LOW-6 — Toggling auto-start off doesn't stop a running cron session** (MainActivity.kt:998-1000, 1053)
The toggle only gates *future* starts; an already-running headless cron session keeps running until its session is closed or "Exit All". Expected-but-documentable behavior; consider stopping the headless session on toggle-off.

---

### Answers to the remaining review questions

- **Q2 lifecycle:** The user-interactive-session-close path cannot tear down cron *while cron still runs* (sessions list is shared; removing the user session leaves the cron session → non-empty → no stop). The teardown risk is the MED-3 clean-exit case, not the interactive-close case. The "sessions list dropped on process death" concern is handled correctly by `onStartCommand(intent=null)` re-creating a session.
- **Q4 edge cases:** No-rootfs → `detectActiveRootfsDir` returns null → clean skip (BackgroundBoot.kt:28-31) ✓. Boot-before-unlock is impossible for `BOOT_COMPLETED` (post-unlock only); `MY_PACKAGE_REPLACED` can fire pre-unlock but `filesDir` is credential-encrypted → wrapped in try/catch, just logs (acceptable). Duplicate/late firing → MED-2. Headless bootstrap blocking → MED-4.
- **Q6 cron reliability:** START_STICKY *does* restore cron on process kill because the fresh process has an empty `sessions` list and re-runs the full proot boot; the keep-alive loop is sound for keeping the PTY child alive; actual process liveness comes from FGS + `PARTIAL_WAKE_LOCK` + START_STICKY, not from the loop. Residual risk: each restore re-runs full proot init (potentially bootstrap), and Doze/battery-optimization can still delay cron despite the wake lock (`nh device battery-optimize request` is the documented mitigation).

---