I now have a complete picture. Here is the review.

---

## Review

### File-by-file findings

#### 1. `/root/kali_core_emulator/app/src/main/assets/launcher.sh` (PRoot launcher template)

| # | Severity | Line(s) | Finding |
|---|----------|---------|---------|
| 1 | **FIX** | 31 | **`BINDS="$BINDS -b $FILES_DIR/tmp:$TMP_DIR"` — unquoted variable expansions** |
|   |          |         | `$FILES_DIR` and `$TMP_DIR` are not double-quoted. If either path contained spaces, the bind-mount argument would shatter into separate words. Although Android `filesDir` (`/data/data/com.linux_core/files`) never contains spaces, a future code path or custom rootfs path might. Quote the values but keep the `:` separator outside quotes: `BINDS="$BINDS -b \"$FILES_DIR/tmp:$TMP_DIR\""` or safer: join before assignment. |
| 2 | **NOTE** | 44 | **Diagnostic subshells are safe with `set -e`** |
|   |          |     | Lines like `exists=$([ -x "$STANDALONE_PROOT" ] && echo yes \|\| echo no)` use `$()` inside double-quoted echo. Because each `[ -x ... ]` is the first command in a `&&` chain, `set -e` is suppressed in the subshell — correct POSIX behavior. ✅ |
| 3 | **NOTE** | 10 | **`#!/system/bin/sh` is the correct Android shebang** |
|   |          |     | Android's `/system/bin/sh` is either `mksh` (MiraiOShell) or a `bash` link. This is the standard path. ✅ |
| 4 | **OPTIONAL** | 59-61 | **`$PROOT_FLAGS` and `$BINDS` intentionally unquoted in `set --`** |
|   |             |       | This is required for proper word-splitting into individual arguments. A comment explaining *why* they are unquoted would prevent a well-meaning maintainer from adding quotes and breaking the command line. |
| 5 | **FIX** | 80 | **`exec "$@"` — no fallback if exec fails** |
|   |         |     | `set -e` is active, so if `exec` fails (e.g. PRoot binary was deleted between validation and exec), the script exits with the error. The preceding `echo ... >&2` logs the full command line to stderr. **Low risk** because the validation checks on lines 22-33 would have already exited. But if someone removes the validation in the future, this is a trap. |
| 6 | **NOTE** | 27-36 | **Error messages correctly go to stderr (`>&2`)** |
|   |          |       | All diagnostic and error echos use `>&2`. ✅ |
| 7 | **OPTIONAL** | 76-78 | **`DOCKER_MODE` literal fallback** |
|   |             |       | If the Kotlin template engine leaves `__DOCKER_MODE__` unfilled, it is the literal string `__DOCKER_MODE__` (non-empty and not `"1"`), so the script takes the `elif` or `else` path. The Kotlin code (ProotManager.kt:1474) already checks for unfilled placeholders with a regex and logs a warning. No runtime failure, but the template engine guard is the right place. |
| 8 | **NOTE** | 68 | **`$SDCARD_MOUNT` handling is correct** |
|   |          |     | When `mountStorage=false`, `SDCARD_MOUNT` is empty and `BINDS` gains a harmless trailing space. When `mountStorage=true`, it becomes ` -b /sdcard` (leading space), which word-splits correctly. ✅ |

---

#### 2. `renderCompatLauncher()` wrapper (ProotManager.kt:1504-1535, generated as `launcher.sh`)

| # | Severity | Lines | Finding |
|---|----------|-------|---------|
| 9 | **FIX** | 1513-1516 | **Detection loop always picks Kali when multiple rootfs exist** |
|   |          |        | The `for d in kali parrot docker` loop iterates in fixed order and `break`s on the first match. If both `kali-arm64` AND `parrot-arm64` exist, the compat wrapper always launches Kali. The `currentDistro` fallback is irrelevant because the detection wins first. Multiple distro users cannot rely on the compat wrapper. |
| 10 | **FIX** | 1525-1528 | **No `exit` after `exec` — silent success on exec failure** |
|    |          |        | If `exec "$LAUNCHER" "$@"` fails (TOCTOU: file deleted between `-x` check and exec), the script falls through the `if` block and exits with code 0 (success). Shell scripts should follow the pattern `exec "$LAUNCHER" "$@" \|\| exit 1` to catch exec failure. |
| 11 | **FIX** | 1517 | **Docker rootfs dirs are never detected** |
|    |          |        | Docker image directories are named `docker-<name>-arm64` (e.g. `docker-ubuntu-arm64`). The detection only checks `"$FILES_DIR/\${d}-arm64"` (which would be `docker-arm64`, not `docker-ubuntu-arm64`) and `"$FILES_DIR/\${d}"` (which would be `docker`). Neither pattern matches `docker-ubuntu-arm64`. The detection loop always fails for Docker images, falling back to `currentDistro`. |
| 12 | **OPTIONAL** | 1514 | **Architecture detection limited to arm64** |
|    |             |       | Only checks `${d}-arm64` and `${d}` (no suffix). Does not handle `${d}-arm32`, `${d}-x86`, or `${d}-x86_64`. For non-arm64 devices the detection loop fails and falls back to `currentDistro`, which may be wrong. |

---

#### 3. `/root/kali_core_emulator/app/src/main/assets/zshrc.kali` / `zshrc.parrot`

| # | Severity | Lines | Finding |
|---|----------|-------|---------|
| 13 | **NOTE** | 9-16 | **`case ":$PATH:"` deduplication pattern is correct** |
|    |          |      | The colon-wrapping pattern `*:/usr/local/sbin:*` correctly checks each path element. The `export PATH="..."` on line 8 already sets all paths, making the `case` block effectively dead code on initial sourcing (it would match the first branch and skip). ✅ No deduplication happens because there's nothing to deduplicate — the export resets PATH entirely. |
| 14 | **FIX** | 14 | **Empty PATH edge case creates security concern** |
|    |          |      | The `*)` branch does `export PATH="$PATH:/usr/local/sbin:..."`. If PATH were somehow empty (e.g. the export on line 8 was removed or sourced out of order), this would produce `PATH=":/usr/local/sbin:..."`. A leading colon includes CWD in the search path — a minor privilege-escalation vector. Add a guard: `export PATH="/usr/local/sbin:/usr/local/bin:${PATH:+:$PATH}"` to handle empty PATH safely. |
| 15 | **FIX** | 16 | **`LD_LIBRARY_PATH` overrides rather than appends** |
|    |          |      | `export LD_LIBRARY_PATH="/usr/local/lib:..."` discards any inherited `LD_LIBRARY_PATH`. In the PRoot/chroot context this is usually correct (the host Android environment shouldn't leak into the guest). But if a parent script (e.g. `launcher.sh` or `entrypoint.sh`) exported additional library paths for the session, they would be lost. Safer default: `export LD_LIBRARY_PATH="/usr/local/lib:/usr/lib:/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"`. |
| 16 | **NOTE** | 16 | **aarch64 paths are correct for arm64, harmless on others** |
|    |          |      | `/usr/lib/aarch64-linux-gnu` and `/lib/aarch64-linux-gnu` are the standard Debian multiarch paths for arm64. On non-arm64 systems they simply don't exist, and the dynamic linker silently ignores missing directories. ✅ No bug. |
| 17 | **NOTE** | 310 | **`alias copi='bash ~/proxy-pilot/start.sh'` references non-standard script** |
|    |          |      | Both zshrc files contain this alias to `~/proxy-pilot/start.sh`, which is not part of the standard Kali/Parrot distribution. On a fresh install the file won't exist, producing a "No such file or directory" error if invoked. Not a script bug, but confusing for users. |
| 18 | **OPTIONAL** | 16 | **Same LD_LIBRARY_PATH in both files** |
|    |             |      | Both `zshrc.kali` and `zshrc.parrot` have identical PATH and LD_LIBRARY_PATH blocks. This is correct — both are Debian derivatives with the same multiarch path layout. ✅ |

---

### Summary of severities

| Severity | Count | IDs |
|----------|-------|-----|
| **BLOCKER** | 0 | *none* |
| **FIX** | 5 | #1 (unquoted path expansion), #9 (detection loop always picks Kali), #10 (no exit after exec), #11 (Docker detection broken), #15 (LD_LIBRARY_PATH override) |
| **NOTE** | 5 | #2, #3, #8, #13, #16 |
| **OPTIONAL** | 4 | #4, #7, #12, #18 |

- #14 (empty PATH security) is borderline between FIX and NOTE — the `export PATH=...` on line 8 prevents the empty case during normal sourcing, so the `case` block is dead code in practice. I classify it as NOTE but flag it as a latent issue.

---