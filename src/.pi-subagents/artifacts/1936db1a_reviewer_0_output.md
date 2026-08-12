Now I have all the evidence. Here is my review.

---

## Review

### 1. ashell PATH change (TerminalActivity.kt:2600–2624)

**FIX — `filesDir.listFiles()` runs on UI thread**

`TerminalActivity.kt:2608` calls `filesDir.listFiles()` directly inside `startAshellSession()`, which is invoked from `onCreate` (line 1333) and from a setup path (line 2570). Both run on the main thread. `filesDir.listFiles()` is a blocking filesystem I/O call. While the app's private dir typically has few entries, scanning a directory is not supposed to be on the UI thread. Consider wrapping in `Dispatchers.IO` with `lifecycleScope.launch` or at least calling `filesDir.listFiles()` on a background thread.

**FIX — entrypoint.sh also writes resolv.conf with hardcoded 8.8.8.8/8.8.4.4**

Wait, this is unrelated to the review topic. Let me stick to the asked questions.

**OPTIONAL — Distro binaries won't work in ashell due to glibc/bionic mismatch**

`TerminalActivity.kt:2610–2622` adds paths like `kali-arm64/usr/bin/` to the PATH in a shell that runs `/system/bin/sh` (Android's bionic-based shell). Distro ELF binaries (python3, nmap, etc.) are linked against `/lib/ld-linux-aarch64.so.1` (glibc) which does not exist on Android. Executing them results in `ENOENT` ("No such file or directory") — a silently confusing UX since the file clearly exists. The comment says "aby byly dostupné" (to make them available) but most will not be. Shell scripts using `#!/bin/sh` may also fail if `/bin/sh` doesn't exist as a symlink on the host Android (common on API 33+). Only pure POSIX-shebang scripts that are executed by `/system/bin/sh` as the calling shell (using PATH) will work — e.g., the `nh` script (which uses `#!/bin/sh` though, a secondary risk). The feature provides marginal utility at best and misleading PATH entries at worst. **Recommendation**: Either document the limitation prominently in the ashell, or skip distro paths entirely and only add `filesDir` (where wrapper scripts like `nh` symlinks actually exist).

**OPTIONAL — Hardcoded rootfs directory names**

`TerminalActivity.kt:2608` hardcodes `["kali-arm64", "parrot-arm64"]` while dynamically detecting `docker-*` prefixes. If a user has a custom-named rootfs (e.g., `ubuntu-arm64`, `kali-armhf`), it won't be discovered. Consider scanning `filesDir` for directories that look like rootfs (contain `usr/bin`) rather than hardcoding names. The docker detection shows the pattern; apply it generically.

**FIX — Resolved in review**: The glibc/bionic mismatch means the feature is of limited practical value but is harmless. The UI thread I/O is a real (if low-risk) concern.

### 2. launcher.sh template shebang — CORRECT

`launcher.sh:1` uses `#!/system/bin/sh`. This is the correct shebang for Android host scripts. The `terminalmap` wrapper (`ProotManager.kt:apt wrapper section`) and `renderCompatLauncher()` also use `#!/system/bin/sh`. Other scripts (`nh`, `code-server-ctl`, `ashell`, `bin/ifconfig`) use `#!/bin/sh` which may or may not resolve (depends on device). The launcher template is explicitly correct and consistent with the project's Android-aware convention.

### 3. ProotManager.kt changes

**OPTIONAL — Two file writes, negligible overhead**

`ProotManager.kt:1489–1493` writes a compat `launcher.sh` (~25 lines) in addition to the distro-specific launcher (~90 lines). At ~115 lines total, disk/performance impact is negligible compared to everything else `setupProotEnvironment()` does. No concern.

**OPTIONAL — Path injection in `renderCompatLauncher()` (theoretical only)**

`ProotManager.kt:1510` embeds `filesDir.absolutePath` directly into a shell variable assignment: `FILES_DIR="${filesDir.absolutePath}"`. On Android, `filesDir` is always a system-controlled path (e.g., `/data/data/com.linux_core/files`) containing only safe characters. This is not exploitable. Could be made more defensive with single quotes but is unnecessary in practice.

### 4. `.gitignore` change — CORRECT

`.gitignore` (around lines 97–98, relative to file): `*.sh` appears first, then `!app/src/main/assets/launcher.sh` immediately after. In gitignore processing, later rules override earlier ones for the same path. The negated pattern correctly un-ignores the file. Git will now track `app/src/main/assets/launcher.sh`. Verified by looking at git diff HEAD~1 which shows it as new.

### 5. zshrc changes — CORRECT

`zshrc.kali` and `zshrc.parrot` add unconditional `export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"` plus a case statement that appends them only if missing, and `export LD_LIBRARY_PATH="/usr/local/lib:/usr/lib:/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu"`. These files are deployed **inside** the PRoot rootfs (`/root/.zshrc`, `/etc/skel/.zshrc`, etc.) by `deployZshrc()`. They are sourced only when `zsh --login` runs inside the PRoot container. There is zero risk of affecting Android host environment variables from within a PRoot namespace.

### 6. Entrypoint interaction — CORRECT

`ProotManager.kt:createEntrypointScript()` does NOT set `PATH` or `LD_LIBRARY_PATH`. It only does `unset LD_PRELOAD`, bootstraps the system, sets up user zshrc files, sets up dropbear, and then `exec "$ENTRY_SHELL" --login`. The shell then reads its rc files — including the newly-modified `.zshrc` with the PATH/LD_LIBRARY_PATH settings. There is no duplicate or conflicting PATH assignment between entrypoint.sh and zshrc.

---

## Acceptance Report