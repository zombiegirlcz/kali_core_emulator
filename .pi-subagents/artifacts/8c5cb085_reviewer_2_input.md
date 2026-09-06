# Task for reviewer

[Read from: /root/kali_core_emulator/plan.md, /root/kali_core_emulator/progress.md]

DOCUMENTATION ACCURACY + CONSISTENCY REVIEW in the repo at /root/kali_core_emulator. Read-only; return concise evidence-backed findings with file:line + suggested fixes. Aim: catch stale/incorrect claims and verify new claims against the real code.

Scope (inspect files directly):
- README.md
- app/src/main/assets/nethunter_docs.md (source-of-truth user doc, deployed to guest) 
- AGENTS.md (maintenance notes)
- docs/ (docs/nethunter_docs.md was DELETED as a stale duplicate of the assets one)
- Reference the actual implementation: app/src/main/cpp/su_daemon.c, app/src/main/cpp/su_wrapper.c, app/src/main/assets/launcher.sh (-- raw-exec mode), app/src/main/assets/nh (fix_permission), AndroidManifest.xml, app/src/main/java/com/linux_core/core/{BootReceiver,BackgroundBoot}.kt, TerminalService.kt

Review questions:
1. STALE CLAIMS: find any remaining text that still says su_daemon runs a command directly on the Android host, or old host-root semantics, that contradict the 2026-08-14 re-entry model (should say: re-enters PRoot as real root; never on host). Search README.md, assets/nethunter_docs.md, AGENTS.md, and any docs/*.md.
2. NEW-CLAIM ACCURACY: verify each new doc claim matches code exactly:
   - auto-fix skip list is 'dev proc sys run sdcard mnt system vendor product apex storage data'
   - fail-closed exit 126 when no launcher
   - 'nh fix permission' command syntax and bind-path rejection list
   - background boot: RECEIVE_BOOT_COMPLETED, BootReceiver actions, cron/crond, START_STICKY restore, rootfs on credential-encrypted storage (no LOCKED_BOOT_COMPLETED)
   - 'nh_boot.sh' filename and keep-alive, /var/log/nethunter-boot.log path
3. CONSISTENCY: do README.md and assets/nethunter_docs.md (and AGENTS.md) describe the same commands, same pref names (boot_autostart vs auto_fix_permissions), same paths? Any contradictions between the three docs?
4. DELETION IMPACT: any lingering git/doc references to docs/nethunter_docs.md that should now point to the assets path? (e.g. README says 'nethunter_docs.md se generuje z assets/nethunter_docs.md').
5. READER VALUE: flag anything incomplete, wrong example output, self-contradictory, or overly robotic that would confuse a user.

Report findings ranked by severity (incorrect claims are critical; minor style nits optional). Evidence from files, not the summary.

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