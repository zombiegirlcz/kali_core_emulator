# Review — Documentation Accuracy + Consistency

Verified each new doc claim against the implementation files. Findings ranked by severity.

## Review

### Correct (verified against code)
- **Auto-fix skip list** `dev proc sys run sdcard mnt system vendor product apex storage data` matches `is_bind_dir()` in `app/src/main/cpp/su_daemon.c` and the `fix_cb` level-1 subtree skip. Claimed identically in README.md, nethunter_docs.md, AGENTS.md. ✓
- **Fail-closed exit 126:** `su_daemon.c` `_exit(126)` when `launcher_path` empty / not `X_OK` (and for `deny_command`). ✓
- **`nh fix permission`** syntax + alias `perms` + `@FIX` payload chain: `nh:1354` → `su_wrapper.c --fix` → `@FIX`. Bind rejection list in nethunter_docs (`/`, `/dev/*`, `/proc/*`, `/sys/*`, `/run/*`, `/sdcard/*`, `/mnt/*`, `/system/*`, `/vendor/*`, `/product/*`, `/apex/*`, `/storage/*`, `/data/*`) exactly equals `nh` case pattern and `is_bind_dir`. ✓
- **Background boot:** `RECEIVE_BOOT_COMPLETED` (AndroidManifest.xml:8), `.core.BootReceiver` exported, `BOOT_COMPLETED`+`MY_PACKAGE_REPLACED`, no `LOCKED_BOOT_COMPLETED` (manifest 237-243); `boot_autostart` default true in `vpn_settings` (BootReceiver.kt, MainActivity.kt:320); `START_STICKY` + null-intent restore (TerminalService.kt:309-316); rootfs on credential-encrypted `filesDir`. ✓
- **`nh_boot.sh`** filename, `cron`/`crond`, `while true; do sleep 60; done` keep-alive, `/var/log/nethunter-boot.log` — all in `BackgroundBoot.buildBootScript()`. ✓
- **`ld launcher.sh --` raw-exec:** `launcher.sh:60-80`, `NH_CWD`, PATH/HOME export, token-verbatim, bare `--` = zsh/bash login. ✓
- **Deletion impact:** `docs/nethunter_docs.md` deletion is clean. No doc references the repo path. README.md:521 refers to the *guest-deployed* doc generated from the asset, which ProotManager.kt:1000-1017 confirms. ✓

### Critical (incorrect claims — contradict current security model)
- `app/src/main/assets/nethunter_docs.md:98` — "Příkazy `sudo` a `su` v hostovaném OS jsou wrappery, které posílají příkazy na HOST a spouští je tam s root právy." **STALE/WRONG.** This is the deployed user doc. Directly contradicts the very next bullet (:104) "NIKDY nespouští přímo na hostiteli … znovu vstupuje do PRoot sandboxu". Fix: reword to "posílají příkazy hostinnému daemonu, který je **znovu vstupuje do PRoot sandboxu** jako skutečný root (nikdy přímo na hostu)."
- **`assets/nethunter_docs.md:109`** — "`sudo id`  # GID/UID root na hostiteli" **STALE.** The command runs in the guest rootfs under PRoot (uid 0), not on the host. Fix: "root uvnitř PRoot guestu".
- **`assets/nethunter_docs.md:111`** — "`su`  # hostitelský root shell" **STALE.** Fix: "# interaktivní root shell uvnitř PRoot guestu".
- **`README.md:503`** (Root Bridge Update Changelog) — "kerésí `su -c 'cmd'` … → host root shell". **STALE** (present-tense description inside the current user README of a behavior now replaced). Fix: mark as 2026-08-14-prehistorical or update to "guest root shell via re-entry".

### Notes (consistency / reader value)
- **`nh` in-guest self-help is stale & broken for the new command:** `nh help fix` fails because `help_fix` is undefined (help_dispatch at `nh:1454` invokes `help_$topic`; no `help_fix` exists), and both the category banner (`nh:1474` "fix pkg <name>, auto") and `nh list` (`nh:1643` "fix pkg" "fix auto") omit the newly-documented `nh fix permission <path>`. That's inconsistent with README.txt + nethunter_docs + AGENTS which all feature it. Add `permission <path>` to both help texts and a `help_fix` function.
- **Duplicate `detectGuestRootfs`:** `RootBridgeTab.kt:63` and `:78` define the identical function twice in the same `object` — a "Conflicting overloads" Kotlin compile risk / merge artifact. Code smell beyond doc scope; flag for parent.
- **AGENTS.md self-contradiction on the same fn:** the 2026-08-14 table row says "removed dead `detectGuestRootfs()`", but the Ownership-fix table says "vrácen `detectGuestRootfs()`" and the code has it (twice). Reconcile wording.
- **Typo:** `assets/nethunter_docs.md:98` — "Magisk/**roo** daemon" → "root".
- `AGENTS.md:274` — "Detailní dokumentace MITM feature je v `nethunter_docs.md`" is ambiguously pathless now that `docs/nethunter_docs.md` was deleted; point to `assets/nethunter_docs.md` explicitly.

Suggested action: fix the 3 stale lines + the "roo" typo inside `assets/nethunter_docs.md` (blocker — it is the deployed guest doc and is self-contradictory with its own safety paragraph), and update README.md:503. The lower-severity `nh` self-help+duplicate-fn items should be folded into the originating worker's follow-up.

```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "Returned concrete file:line findings with severity verified against code: assets/nethunter_docs.md:98,109,111 (stale host-exec claims), README.md:503 (changelog stale), plus verified-new-claim pass on su_daemon.c is_bind_dir/list, _exit(126), nh/@FIX/reject list, AndroidManifest.xml:8,237-241, BootReceiver/BackgroundBoot, TerminalService.kt:309-316."
    }
  ],
  "changedFiles": [
    ".pi-subagents/artifacts/progress/8c5cb085/progress.md"
  ],
  "testsAddedOrUpdated": [],
  "commandsRun": [
    {
      "command": "git status --short / git diff --stat",
      "result": "passed",
      "summary": "Confirmed docs/nethunter_docs.md deleted and all reviewed files are modified in the working tree."
    },
    {
      "command": "grep/sed across README.md, assets/nethunter_docs.md, AGENTS.md, docs/*.md, nh, *.c, *.kt",
      "result": "passed",
      "summary": "Located stale host-exec claims and verified new claims against code."
    }
  ],
  "validationOutput": [
    "New claims all match code (skip list, 126, fix permission, boot flow, nh_boot.sh/log). Stale claims confined to assets/nethunter_docs.md:98,109,111 and README.md:503. Minor: nh help_fix missing, duplicate detectGuestRootfs, AGENTS.md wording, 'roo' typo."
  ],
  "residualRisks": [
    "Cannot compile locally (AGENTS.md forbids local Gradle) — the duplicate detectGuestRootfs may cause a 'Conflicting overloads' build failure undetected in this review; needs a Modal build to confirm."
  ],
  "noStagedFiles": true,
  "diffSummary": "Review-only; no repo files modified. Only the scratch progress artifact was written."
  "reviewFindings": [
    "critical: assets/nethunter_docs.md:98 - intro describes su_daemon running commands 'na HOST', contradicts re-entry model line 104 of the same deployed user doc",
    "critical: assets/nethunter_docs.md:109 - 'sudo id # GID/UID root na hostiteli'", "stale, command runs in guest rootfs",
    "critical: assets/nethunter_docs.md:111 - 'su # hostitelský root shell'", "stale",
    "medium: README.md:503 - changelog says sudo/su → 'host root shell'", "stale post-2026-08-14",
    "note: nh:1454+ - nh help fix fails (help_fix undefined); nh:1474 and nh:1643 omit 'fix permission <path>'",
    "note: RootBridgeTab.kt:63,78 - duplicate identical detectGuestRootfs defs (compile risk)",
    "note: AGENTS.md:516vs829 - says detectGuestRootfs removed but also says returned; code has it",
    "note: assets/nethunter_docs.md:98 - typo 'roo' daemon"
  ],
  "manualNotes": "All core new claims verified correct; flag the 3 stale lines in assets/nethunter_docs.md as high priority since that file is the deployed/guest-facing user doc and is internally contradictory.",
  "