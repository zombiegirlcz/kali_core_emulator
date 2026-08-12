# Task for reviewer

Review the Kotlin changes in this Android project for correctness, regressions, and edge cases. Inspect the actual files:

1. `/root/kali_core_emulator/app/src/main/java/com/linux_core/core/ProotManager.kt` — look at `deployLauncherScript()` and `renderCompatLauncher()`. Check:
   - Is the distro-specific launcher file generation correct? (launcher-kali.sh, launcher-parrot.sh, launcher-docker.sh)
   - Is the backward-compat wrapper `renderCompatLauncher()` logic sound?
   - Does the template placeholder replacement cover all `__*__` placeholders?
   - Are there any concurrency issues with `synchronized(this)` when multiple sessions start?
   - Is `launcherDistroId` correctly computed for Docker vs non-Docker?

2. `/root/kali_core_emulator/app/src/main/java/com/linux_core/ui/terminal/TerminalActivity.kt` — look at `startAshellSession()`. Check:
   - Is the directory scanning logic correct and efficient?
   - Can `filesDir.listFiles()` return null? Is it handled?
   - Is the PATH string construction correct?
   - Any issues with the environment variable setting?

3. `/root/kali_core_emulator/app/src/main/assets/launcher.sh` — the new template. Check:
   - Are all 13 placeholders (`__PROOT_BIN__`, `__LOADER_BIN__`, etc.) correctly referenced?
   - Is the shell script logic sound (set -e, variable quoting, error handling)?

Report: list each finding with file:line, classify as BLOCKER, FIX, or OPTIONAL, and explain the issue.

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