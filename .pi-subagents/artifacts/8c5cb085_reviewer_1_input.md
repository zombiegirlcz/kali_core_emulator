# Task for reviewer

[Read from: /root/kali_core_emulator/plan.md, /root/kali_core_emulator/progress.md]

ANDROID LIFECYCLE + RUNTIME CORRECTNESS REVIEW of the new background auto-start / cron feature in the repo at /root/kali_core_emulator (project com.linux_core). Read-only; return concise evidence-backed findings with file:line + suggested fixes.

Scope (inspect files directly):
- AndroidManifest.xml (new RECEIVE_BOOT_COMPLETED permission, .core.BootReceiver with BOOT_COMPLETED + MY_PACKAGE_REPLACED, exported=true)
- app/src/main/java/com/linux_core/core/BootReceiver.kt (new)
- app/src/main/java/com/linux_core/core/BackgroundBoot.kt (new: detects rootfs dir, writes guest /root/.nh_boot.sh, calls ProotManager.setupProotEnvironment + TerminalService.createSession with view=null, guest boot script runs cron/crond and keeps alive)
- app/src/main/java/com/linux_core/core/TerminalService.kt (new onStartCommand logic: when intent==null [START_STICKY restart] && boot_autostart && sessions.empty -> BackgroundBoot.start)
- app/src/main/java/com/linux_core/MainActivity.kt (new 'Auto-start po restartu' toggle card, pref boot_autostart stored in 'vpn_settings')
- Relevant support: ProotManager.setupProotEnvironment, ProotManager.prootConfig command assembly (customCommand appended as a single argv element), TerminalService.createSession, terminal session lifecycle

Review questions:
1. WORKFLOW PERMISSION/SCHEME: Are both the manifest receiver and each O+ FGS start path legal on Android 12/13/14+? The boot path starts a foreground service from a BOOT_COMPLETED BroadcastReceiver via createSession->startService. Is directBootAware needed? Any FOREGROUND_SERVICE_TYPE quirk on API 34?
2. LIFECYCLE CORRECTNESS: TerminalService removes the session and calls stopForeground+stopSelf when sessions.isEmpty(). Trace whether the headless cron session can be torn down by an unrelated code path (e.g. user-opened interactive session closes, or a race between START_STICKY restart and createSession). Could cron die because the companion 'sessions' list is dropped on process death and only one session lives?
3. customCommand/PROOT ASSEMBLY: BackgroundBoot passes customCommand='bash /root/.nh_boot.sh' as a single string. Verify against setupProotEnvironment how it lands in the proot argv and in launcher.sh's handling — does it actually reach the guest shell correctly? Flag any quoting/argv mismatch.
4. EDGE CASES: no rootfs present yet (fresh install) -> BackgroundBoot skips, ok? boot/kill before credential-unlock; duplicate/late BootReceiver firing (device restarts while app already running)? FIRST-run bootstrap running headless (blocks, takes minutes) — acceptable? Multiple auto-start triggers causing duplicate sessions?
5. PREFS CONSISTENCY: toggle writes 'boot_autostart' to 'vpn_settings' prefs; BootReceiver and TerminalService read the same key/name. Verify consistent across all three.
6. Cron reliability: does START_STICKY restart truly restore cron, or does the session die with the process (PTY/proot child killed) making the restart logic ineffective? Is the keep-alive loop sound? What actually keeps the app/process alive vs the system culling it?

Report findings ranked by severity, evidence from code not summary.

---
Update progress at: /root/kali_core_emulator/.pi-subagents/artifacts/progress/8c5cb085/progress.md

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