# NetHunter AI Operator - Project Overview

This project is an Android application designed to run a Kali Linux environment (NetHunter) on
unrooted Android devices using **PRoot**. It leverages the Termux terminal emulator components for
the user interface.

## Architecture

- **Core Logic (`cz.hackai.nethunter_ai_operator.core`)**:
    - `ProotManager.kt`: Manages CPU architecture detection, binary preparation (copying from assets
      to filesDir), and constructing the PRoot execution command. It includes workarounds for SELinux
      execution restrictions.
    - `RootfsManager.kt`: Handles downloading the Kali rootfs archive and extracting it with symlink
      support using `commons-compress`.
- **UI (`cz.hackai.nethunter_ai_operator.ui`)**:
    - `MainActivity.kt`: The entry point, handling rootfs download and extraction status.
    - `TerminalActivity.kt`: Integrates Termux's `TerminalView` and `TerminalSession` to provide a
      functional terminal running the PRoot session, with enhanced error handling and logging.

## Key Technologies

- **Kotlin & Jetpack Compose**: For the Android application UI and logic.
- **PRoot**: A user-space implementation of `chroot`, `mount --bind`, and `binfmt_misc`.
- **Termux Libraries**: `terminal-view` and `terminal-emulator` for the terminal UI.
- **OkHttp & Apache Commons Compress**: For rootfs acquisition and extraction.

## Development & Build

### Building the Project

Use Gradle to build the application:

```powershell
./gradlew assembleDebug
```

### Binary Extraction Scripts

The project includes Python scripts to fetch the necessary binaries from the Termux repositories:

- `extract_proot.py`: Downloads `proot` binaries for all supported architectures.
- `extract_libtalloc.py`: Downloads `libtalloc` libraries and a static loader.

Run these scripts if you need to update the binaries in `app/src/main/assets`.

## PRoot Execution & SELinux Workarounds

Android's `untrusted_app` SELinux domain restricts execution from `app_data_file` directories (e.g., `/data/data/.../files/`). To overcome this, the project employs several strategies:

1. **Native Library Directory**: Binaries in `nativeLibraryDir` (`/data/app/.../lib/`) are assigned the `apk_data_file` type, which allows execution. The app uses a static `proot` binary renamed to `libproot.so` to leverage this.
2. **PROOT_LOADER Workaround**: The PRoot loader is also placed in the native library directory (`libloader.so`). `PROOT_LOADER` environment variable is set to this path.
3. **Read-only TMP_DIR**: To prevent PRoot from attempting to copy the loader to a writable but non-executable `tmp` directory, `PROOT_TMP_DIR` is pointed to a read-only directory (`tmp_ro`). This forces PRoot to use the loader from its original, executable path.
4. **Shell Execution**: Instead of using `exec` to replace the shell process with PRoot (which causes issues in the PTY context), PRoot is run as a child process of the shell.
5. **Interpreter Path Fix**: The app replaces broken `ld-linux` symlinks in the rootfs with hard copies to ensure dynamically linked binaries can find their interpreter.

## Debugging

- **Logcat**: Check logs with tag `TerminalActivity`, `ProotManager`, and `TermSession`.
- **Manual Testing**: Use `test_proot.sh` via `adb shell` to test PRoot execution manually.
   ```bash
   adb push test_proot.sh /data/local/tmp/
   adb shell "sh /data/local/tmp/test_proot.sh"
   ```
- **Error Overlay**: The terminal UI includes an error overlay that captures and displays PRoot's stderr and stdout for easier diagnosis of startup failures.
