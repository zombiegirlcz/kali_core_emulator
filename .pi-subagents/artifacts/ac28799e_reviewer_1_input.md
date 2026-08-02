# Task for reviewer

[Read from: /root/kali_core_emulator/plan.md, /root/kali_core_emulator/progress.md]

ADVERSARIAL REVIEW — ANGLE: SECURITY & PRIVILEGE BOUNDARIES

Context: The repo /root/kali_core_emulator (Android app) just added a root-escalation bridge: untracked C files `app/src/main/cpp/su_daemon.c` (host daemon, intended to run as root) and `app/src/main/cpp/su_wrapper.c` (guest wrapper), plus launcher.sh bind mount and ProotManager.kt deploy logic. Read the actual files and the diff.

This daemon, if started as root, would execute arbitrary commands at UID 0/GID 0 from ANY client that can reach its UNIX socket. Review the privilege and trust boundaries as an attacker would:

- Socket exposure: su_daemon.c does `chmod(socket_path, 0777)` and binds /data/data/com.linux_core/files/magisk_daemon.sock. Who can connect? Is there any authentication (token, SO_PEERCRED uid check, secret in payload)? What can a malicious app or process on the device do if the daemon runs?
- Payload trust: target_uid/target_gid come from the client and are applied with setresuid/setresgid. Any validation? Can a client request ANY uid/gid, or only 0? Is there a blocklist for destructive commands (the project's LocalApiServer has a blocklist for rm -rf /, mkfs, reboot — see AGENTS.md / LocalApiServer.kt)? Does this daemon have one?
- FD passing (SCM_RIGHTS): risks of the daemon receiving and dup2-ing attacker-controlled fds; can a client pass an fd to a sensitive file and have the root child read/write it? (Note: fd is dup2'd to stdin/stdout/stderr — evaluate actual exposure.)
- Bind mount: launcher.sh adds `-b $FILES_DIR:/run/host_ipc` — the ENTIRE app-private filesDir is exposed inside the guest at /run/host_ipc. Check what lives in filesDir (search ProotManager.kt / LocalApiServer.kt for filesDir writes: api_security token, user profile, keys). Does the guest (running as the app UID) gain anything? Is binding the whole dir necessary vs binding just the socket file?
- The wrapper runs inside the guest as the app UID — check whether the wrapper itself can be abused (it passes fds 0,1,2 and forwards ALL args; who decides target uid — wrapper hardcodes uid=0 gid=0 — any privilege confusion?)
- Static su_wrapper binary in assets: any issues with being replaced/modified in the APK assets?
- Compare with existing security posture in the repo (AGENTS.md security section, LocalApiServer bearer token + blocklist) — does this new code drop below that bar?

Return concise, evidence-backed findings: severity, file:line, real attack/impact scenario, concrete fix. Do NOT edit files. Do NOT restate the design — only security defects and risks.

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