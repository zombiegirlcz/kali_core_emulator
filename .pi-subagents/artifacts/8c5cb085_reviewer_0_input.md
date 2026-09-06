# Task for reviewer

[Read from: /root/kali_core_emulator/plan.md, /root/kali_core_emulator/progress.md]

ADVERSARIAL SECURITY + C CORRECTNESS REVIEW of the real-root su_daemon PRoot re-entry bridge in the repo at /root/kali_core_emulator (project com.linux_core). This code runs as REAL ROOT on the Android host. Inspect files directly.

Scope (do not edit files, read-only; return concise evidence-backed findings with file:line and suggested fixes):
- app/src/main/cpp/su_daemon.c (new 2026-08-14 model: daemon re-enters PRoot sandbox as real root via launcher.sh '--' raw-exec instead of chroot+setresuid+execvp; new argv: socket, launcher, rootfs, app_uid, app_gid, auto_fix; 'handle_fix_request' @FIX protocol; auto-fix via nftw+lchown after waitpid; realpath containment check)
- app/src/main/cpp/su_wrapper.c (--fix mode)
- app/src/main/assets/launcher.sh (the '--' raw-exec re-entry mode, token handling, guest /bin/sh shim, unset LD_PRELOAD/PROOT_LOADER)
- app/src/main/assets/nh (fix_permission / fix_dispatch, guest-side bind path rejection)

Adversarial questions to answer:
1. FAIL-CLOSED: is it truly impossible for a guest-originated command to run directly on the Android host? Trace every code path when launcher path is missing/empty, when rootfs is missing. Any bypass?
2. TOKEN/QUOTING SAFETY: launcher.sh '--' forwards args verbatim (no shell re-quoting). Verify with execv/argv boundaries. Can a crafted arg escape into the guest shell shim ('/bin/sh -c "unset LD_PRELOAD PROOT_LOADER; exec "$@"" -- "$@"')? Consider both $@ handling and the shim single-quote/paren forms.
3. PATH CONTAINMENT: the realpath containment check in handle_fix_request — can a symlink escape to /sdcard or elsewhere outside rootfs? Is realpath applied on the pre-walk scope and per-file? Is there a TOCTOU? Does auto-fix walk with FTW_PHYS and lchown? Is the bind-dir skip list (dev proc sys run sdcard mnt system vendor product apex storage data) applied correctly and is it sufficient as defense-in-depth?
4. PROTOCOL INJECTION: how are commands delimited over the Unix socket? Can a guest inject extra argv entries (e.g. via newlines/NUL/return) to smuggle launcher --root-args or a second command? Is su_wrapper the only client confirmed (socket perms/peer cred)?
5. deny_command blocklist: can a shell-string form (e.g. 'sh -c "rm -rf /"') or glob/quoting evade it? Given the new model that commands run INSIDE the PRoot guest (not on host), is the blocklist still necessary, and is it consistent?
6. Process/lifecycle: waitpid + children cleanup, zombie handling, exit codes (126 semantics), stderr routing, double-exec safety.

Report findings ranked by severity. Evidence from the code, not the conversation summary.

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