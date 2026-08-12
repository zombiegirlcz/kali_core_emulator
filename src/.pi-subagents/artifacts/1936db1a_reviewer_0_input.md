# Task for reviewer

Review the Android integration aspects of these changes. Focus on:

1. **ashell PATH change** (`TerminalActivity.kt:startAshellSession`):
   - The PATH now includes distro directories (e.g., `/data/data/com.linux_core/files/kali-arm64/usr/bin/`). But binaries there are compiled for glibc, not Android's bionic libc. Will they work when run directly? Or will they fail with linker errors? Is the user experience okay (some work, some don't)?
   - `filesDir.listFiles()` — is this potentially running on UI thread? Could it cause ANR if there are many files?
   - The `knownDirs` list is hardcoded `["kali-arm64", "parrot-arm64"]` — are there any other possible rootfs directory names that should be included?

2. **launcher.sh template** — is `#!/system/bin/sh` the right shebang for Android? Check other scripts in the project for precedent.

3. **ProotManager.kt changes**:
   - `deployLauncherScript()` now writes TWO files (distro-specific + compat wrapper). Any disk space or performance concerns?
   - The compat wrapper is written by `renderCompatLauncher()` which includes `filesDir.absolutePath` in the shell script. Is there any risk of path injection?
   - The template is in `assets/` and was previously gitignored by `*.sh` — check if the `.gitignore` fix is correct.

4. **`.gitignore` change**: Is `!app/src/main/assets/launcher.sh` correctly placed after the `*.sh` rule? Will git-track the file now?

5. **zshrc changes**: The PATH/LD_LIBRARY_PATH are set unconditionally. Is there any risk of breaking Android environment variables?

6. **Entrypoint interaction**: Read `/root/kali_core_emulator/app/src/main/java/com/linux_core/core/ProotManager.kt` around line 347 (`createEntrypointScript`) — does the entrypoint.sh set PATH too? Any conflicts with the zshrc changes?

Report: list each finding with file:line, classify as BLOCKER, FIX, or OPTIONAL, and explain why.

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