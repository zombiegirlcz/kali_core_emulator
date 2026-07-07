# Task for reviewer

You are a Senior Code Reviewer with expertise in software architecture,
design patterns, and best practices. Your job is to review completed work
against its plan or requirements and identify issues before they cascade.

## What Was Implemented

Code-server (VS Code in browser) integration into the NetHunter AI Operator Android app:

1. **`code-server-ctl`** shell script (`app/src/main/assets/code-server-ctl`) — controls code-server lifecycle inside a PRoot Linux guest container. Commands: start, stop, status, password, install, info, log. Runs inside Kali/Parrot rootfs chroot.

2. **`ProotManager.kt`** — added `code-server-ctl` to the assets-to-deploy list so it gets placed in guest `/usr/local/bin/`. Added EDITOR section to MOTD and welcome banner.

3. **`LocalApiServer.kt`** — 5 new HTTP endpoints (`/editor/start|stop|status|password|info`) that call `code-server-ctl` inside the PRoot guest via `ProcessBuilder("sh", launcher.sh, "code-server-ctl", args)`. Auth: Bearer token for remote access, `/editor/password` on sensitiveEndpoints list.

4. **`EditorTab.kt`** (new Compose UI screen) — Android UI with: start/stop toggle, status pill (RUNNING/STOPPED/ERROR/STARTING), WebView showing code-server UI, password bottom sheet with copy-to-clipboard, first-time setup dialog.

5. **`MainActivity.kt`** — added "EDITOR" as a third top-level tab alongside "GUEST DISTROS" and "VPN GATEWAY". Uses `Icons.Default.Code`.

6. **`nethunter_docs.md`** — documentation section for code-server usage, API, security rules, and persistence.

## Requirements / Plan

Plan is in `/root/kali_core_emulator/PLAN.md` — the "VSCode editor" sections (Fáze 1-7 + Architektura, kapitoly 1-4). Key requirements:

- code-server runs inside PRoot guest on 127.0.0.1:8443 only
- Auth always on, password auto-generated, stored in config.yaml (chmod 600), never in CLI args
- `/editor/password` API is localhost-only even with Bearer token
- CLI tool `code-server-ctl` with start|stop|status|password|install commands
- Compose UI with WebView, start/stop button, password visibility, status indication
- Same security pattern as existing VPN/MITM endpoints
- Workspace at `/root/projects`

## Files to Review

Read and review these files:

1. `/root/kali_core_emulator/app/src/main/assets/code-server-ctl` — shell script
2. `/root/kali_core_emulator/app/src/main/java/com/linux_core/ui/editor/EditorTab.kt` — Compose UI
3. `/root/kali_core_emulator/app/src/main/java/com/linux_core/core/LocalApiServer.kt` — new handlers at the end of file
4. `/root/kali_core_emulator/app/src/main/java/com/linux_core/core/ProotManager.kt` — check the asset deployment around line 1440 and MOTD/welcome banners
5. `/root/kali_core_emulator/app/src/main/java/com/linux_core/MainActivity.kt` — new Editor tab integration
6. `/root/kali_core_emulator/nethunter_docs.md` — new Editor section

## Read-Only Review

Your review is read-only. Do not mutate the working tree, the index, HEAD, or branch state in any way. Use read tool to inspect files.

## Output Format

### Strengths
[What's well done? Be specific.]

### Issues

#### Critical (Must Fix)
[Bugs, security issues, data loss risks, broken functionality]

#### Important (Should Fix)
[Architecture problems, missing features, poor error handling, test gaps]

#### Minor (Nice to Have)
[Code style, optimization opportunities, documentation polish]

### Recommendations
[Improvements for code quality, architecture, or process]

### Assessment

**Ready to merge?** [Yes | No | With fixes]

**Reasoning:** [1-2 sentence technical assessment]

## Acceptance Contract
Acceptance level: reviewed
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Implement the requested change without widening scope
- criterion-2: Return evidence sufficient for an independent acceptance review

Required evidence: changed-files, tests-added, commands-run, validation-output, residual-risks, no-staged-files

Review gate: required by reviewer.

Finish with a fenced JSON block tagged `acceptance-report` in this shape:
Use empty arrays when no items apply; array fields contain strings unless object entries are shown.
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