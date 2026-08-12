# Task for reviewer

[Read from: /root/kali_core_emulator/plan.md, /root/kali_core_emulator/progress.md]

ADVERSARIAL REVIEW — ANGLE: CORRECTNESS & RUNTIME PROTOCOL

Context: The repo /root/kali_core_emulator (Android app, Kotlin + C JNI + PRoot guest) just gained a su/sudo privilege-escalation bridge. Review the UNCOMMITTED diff: `git diff` + untracked files `app/src/main/cpp/su_daemon.c` and `app/src/main/cpp/su_wrapper.c`, plus `app/src/main/assets/launcher.sh`, `tools/modal_build.py`, `app/src/main/java/com/linux_core/core/ProotManager.kt`.

DESIGN INTENT (from the product spec the implementation must satisfy):
1. Guest wrapper replaces /usr/bin/sudo and /bin/su; originals renamed su.orig/sudo.orig; wrapper is a smart client — internal Kali user switches are handled locally via su.orig; only real-host-privilege requests go to the host daemon.
2. Host daemon (run as root via Magisk) listens on a UNIX socket bind-mounted into the guest; on request it must spawn a NEW one-shot PRoot instance (e.g. `proot -r <rootfs> ... /bin/bash -c "<cmd>"`) running under the requested host UID, with stdin/stdout/stderr forwarded via SCM_RIGHTS so the user's terminal keeps working (TTY, colors, arrows, signals).
3. Zombie prevention via SA_NOCLDWAIT.

Your job — verify the C protocol and runtime behavior END TO END by reading the actual code:
- Protocol symmetry: compare the payload layout and FD-passing in su_wrapper.c (sender) vs su_daemon.c (receiver). Any mismatch in field order, sizes, endianness, or buffer math? Trace the byte offsets carefully.
- The wrapper waits for EOF on the socket (`while (read(...) > 0)`). The daemon closes client_fd in BOTH parent and child right after fork. Trace what that means: does the wrapper return BEFORE the command finishes? Is the command's exit code ever propagated to the shell? Does the shell prompt appear early while output still streams?
- CRITICAL: the daemon executes the command directly on the HOST via execvp (no PRoot spawning). Design requires a one-shot PRoot instance (rootfs path + proot binary + PROOT_LOADER/LD_PRELOAD env). Verify by reading su_daemon.c: does it ever invoke PRoot? What happens when the guest asks for `sudo apt update` — where does /usr/bin/apt resolve?
- TTY/job-control: is there any process-group/session handling (setsid, tcsetpgrp) so Ctrl-C / Ctrl-Z reach the elevated child? Any env forwarding (HOME, PATH, TERM, LD_PRELOAD)?
- Memory/FD safety: leaks, double-free, missing close, strnlen boundary math, CMSG truncation, fd_count > 3 handling, missing MSG_CTRUNC check.
- Build correctness: modal_build.py compiles su_wrapper with `-static` and NDK bionic clang — static bionic caveats (getpwnam stubs etc.) — do the used libc calls work statically? su_daemon is non-static arm64-only; any ABI assumption?

Return concise, evidence-backed findings: each with severity, file:line reference, what breaks in practice, and a concrete suggested fix. Do NOT edit files. Do NOT summarize the diff — report defects and risks only.

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