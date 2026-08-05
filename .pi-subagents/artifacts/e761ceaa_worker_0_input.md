# Task for worker

Apply these 6 fixes to the project at /root/kali_core_emulator. Read each file first, then make targeted edits. After each edit, verify braces are balanced.

### Fix 1: Docker detection in compat wrapper (ProotManager.kt — renderCompatLauncher)
The detection loop `for d in kali parrot docker; do if [ -d "$FILES_DIR/\${d}-arm64" ] ...` does NOT detect Docker images because docker dirs are named `docker-<name>-arm64`, not `docker-arm64`. Change the detection to also check for directories starting with "docker-":
```
for d in kali parrot; do
  if [ -d "$FILES_DIR/\${d}-arm64" ] || [ -d "$FILES_DIR/\${d}" ]; then
    DISTRO="\$d"
    break
  fi
done
# Docker images: match any docker-* directory
if [ -z "$DISTRO" ]; then
  for d in "$FILES_DIR"/docker-*; do
    if [ -d "$d" ]; then
      DISTRO="docker"
      break
    fi
  done
fi
```

### Fix 2: No exit after exec in compat wrapper (ProotManager.kt — renderCompatLauncher)
Change `exec "$LAUNCHER" "\$@"` to `exec "$LAUNCHER" "\$@" || exit 1` so exec failure exits non-zero.

### Fix 3: LD_LIBRARY_PATH append (zshrc.kali, zshrc.parrot)
Change `export LD_LIBRARY_PATH="/usr/local/lib:..."` to preserve existing:
```
export LD_LIBRARY_PATH="/usr/local/lib:/usr/lib:/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
```

### Fix 4: Empty PATH edge case (zshrc.kali, zshrc.parrot)
Change the `*)` branch in the case statement to handle empty PATH safely:
```
  if [ -n "$PATH" ]; then export PATH="$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"; else export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"; fi
```

### Fix 5: BINDS quoting in launcher.sh
In `launcher.sh` line 31, change:
```
BINDS="$BINDS -b $FILES_DIR/tmp:$TMP_DIR"
```
to:
```
BINDS="$BINDS -b \"$FILES_DIR\"/tmp:\"$TMP_DIR\""
```

### Fix 6: filesDir.listFiles() on UI thread (TerminalActivity.kt — startAshellSession)
Wrap the directory scanning into an `@Suppress("BlockingMethodInNonBlockingContext")` or add a comment that this is called once during session start and filesDir is typically small. But actually the simplest and safest fix is to move it to a background thread. However, since this is called from onCreate and the result is needed immediately, just add a clear comment about the minimal I/O. Alternative: call it within `withContext(Dispatchers.IO)` if possible. Since the function currently returns Unit, restructure to use lifecycleScope.launch(Dispatchers.IO) for the scanning part. But actually the cleanest approach given the code is to just keep it simple — add a comment noting it's a lightweight call on a small directory. Let's wrap in `runCatching` with a fallback to base PATH if scanning fails:
```
val distroPaths = mutableListOf<String>()
val listedDirs = try { filesDir.listFiles()?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
val knownDirs = listOf("kali-arm64", "parrot-arm64") +
    (listedDirs.filter { it.isDirectory && it.name.startsWith("docker-") }.map { it.name })
```

Report what was changed, with before/after snippets for each fix.

## Acceptance Contract
Acceptance level: checked
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Implement the requested change without widening scope

Required evidence: changed-files, tests-added, commands-run, residual-risks, no-staged-files

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