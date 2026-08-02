# Task for reviewer

[Read from: /root/kali_core_emulator/plan.md, /root/kali_core_emulator/progress.md]

ADVERSARIAL REVIEW — ANGLE: INTEGRATION, WIRING & ARCHITECTURE

Context: The repo /root/kali_core_emulator (Android app 'NetHunter AI Operator', package com.linux_core) has an uncommitted feature: a su/sudo host-root bridge with new C files `app/src/main/cpp/su_daemon.c` + `su_wrapper.c`, build steps in `tools/modal_build.py`, bind mount in `app/src/main/assets/launcher.sh`, deploy logic in `app/src/main/java/com/linux_core/core/ProotManager.kt` (`deploySuBridge`).

Your angle: does this feature ACTUALLY WORK END-TO-END when wired into the app? Trace the full lifecycle by reading the code:

1. STARTUP: Search the whole Kotlin codebase for anything that STARTS su_daemon (ProcessBuilder, Runtime.exec, shizuku, nh CLI, etc.). deploySuBridge only copies the binary to filesDir — who launches it, and with what privileges? A normal app process cannot setresuid(0); is there any root bootstrap (Magisk su, Shizuku) left in the app? (Note: ShizukuManager.kt was recently stripped of all su invocations — verify.) If nothing starts the daemon as root, the entire feature is dead scaffolding.
2. INSTALLATION AS su/sudo: the design requires the guest wrapper to SHADOW /usr/bin/su and /bin/sudo (originals renamed su.orig / sudo.orig). Check what deploySuBridge actually installs: file name, path (/usr/local/bin/su_wrapper vs su/sudo), whether originals are renamed, whether PATH precedence makes the wrapper effective. Grep ProotManager.kt for su.orig/sudo.orig/symlinks.
3. BUILD PIPELINE: modal_build.py writes su_daemon + su_wrapper into app/src/main/assets during build_native. Verify the pipeline order (upload_src → build_native → build?) and that the gradle APK actually packages those assets. Check `deploySuBridge` asset-name match with build output paths (assets/su_daemon, assets/su_wrapper) and the asset-size comparison logic using InputStream.available() — is that reliable for Android assets?
4. MOUNT CONTRADICTIONS: launcher.sh binds `-b $FILES_DIR:/run/host_ipc`; wrapper PRIMARY_SOCKET is /run/host_ipc/magisk_daemon.sock; daemon binds /data/data/com.linux_core/files/magisk_daemon.sock. Do the paths line up? Is /run/host_ipc created automatically by PRoot? Is the socket path inside the guest actually the same file as the daemon's bind path? Also check the earlier ShizukuManager.kt / TerminalService.kt changes are unrelated to this feature (they were part of a previous session's fix) — flag if anything there conflicts.
5. DESIGN COMPLIANCE vs AGENTS.md: read AGENTS.md — note the project conventions (no local builds, Modal pipeline, assets layout). Does the implementation contradict documented architecture? Any stale doc claims (e.g. 'deployed as /usr/local/bin/su and /usr/local/bin/sudo' comment vs actual su_wrapper name)?
6. REDEPLOY LOGIC: run the deploy logic mentally on every app start — does it redeploy binaries each boot (waste), is the length check sound, does setExecutable on filesDir work for exec?

Return concise, evidence-backed findings: severity, file:line, what breaks in the real flow, concrete fix. Do NOT edit files. Do NOT restate the diff — only integration defects, missing wiring, and contradictions.

## Acceptance Contract
Acceptance level: attested
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Return concrete findings with file paths and severity when applicable

Required evidence: review-findings, residual-risks

Finish with a fenced JSON block tagged `acceptance-report` in this shape:
Use empty arrays when no items apply; array fields contain strings unless object entries are shown.
`criteriaSatisfied[].status` must be exactly one of: satisfied, not-satisfied, not-applicable.
`commandsRun[].result` must be exactly one of: passed, failed, not-run.
`manualNotes` and `notes` are optional strings; an empty string means no note and does not satisfy `manual-notes` evidence.
```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "specific proof"
    }
  ],
  "changedFiles": [
    "src/file.ts"
  ],
  "testsAddedOrUpdated": [
    "test/file.test.ts"
  ],
  "commandsRun": [
    {
      "command": "command",
      "result": "passed",
      "summary": "short result"
    }
  ],
  "validationOutput": [
    "validation output or concise summary"
  ],
  "residualRisks": [
    "none"
  ],
  "noStagedFiles": true,
  "diffSummary": "short description of the diff",
  "reviewFindings": [
    "blocker: file.ts:12 - issue found, or no blockers"
  ],
  "manualNotes": "anything else the parent should know"
}
```