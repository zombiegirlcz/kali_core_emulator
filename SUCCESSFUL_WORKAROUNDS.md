# Successful PRoot on Android (Unrooted) Workarounds

This document details the specific configuration and workarounds that allowed running a functional Kali Linux environment on modern Android (10+) without root, bypassing SELinux and W^X restrictions.

## 1. SELinux & Execution Bypass (The "Termux" Strategy)

Modern Android enforces **W^X (Write XOR Execute)** policies on the application's data directory (`/data/data/...`). This blocks `execve()` calls on binaries extracted to internal storage.

### Key Workarounds:
- **targetSdkVersion 28**: Lowering the target SDK to 28 (Android 9) grants the application a legacy exemption from the strictest W^X enforcements, allowing execution from the data directory.
- **Standalone Dynamic Binary**: Using the dynamic `proot` binary from the Termux project, paired with its specific `loader`.
- **LD_LIBRARY_PATH Alignment**: Extracting `libtalloc.so.2` and the `loader` to the same internal directory as the `proot` binary and pointing `LD_LIBRARY_PATH` there to resolve linker dependencies without host-system pollution.

## 2. PRoot Configuration for Stability

- **Ptrace Mode (-v 0)**: Using the `-v 0` flag forces PRoot into ptrace-only mode. While slightly slower, it bypasses many SELinux-related seccomp issues that cause PRoot to crash with exit code 255 or 1 on newer kernels.
- **Explicit Path Binding**: Manages UsrMerge symlinks (`/bin` -> `/usr/bin`, etc.) by explicitly binding the target directories to ensure the guest linker can always find the guest binaries and their interpreters.

## 3. Terminal & PTY Interaction

- **Input Process Wiring**: Implementing `onKeyDown` and `onCodePoint` in `TerminalViewClient` to explicitly forward Android keyboard events to the PTY session.
- **Focus Fallback**: Adding a click listener to the `TerminalView` to ensure the keyboard can be summoned manually even if the automatic focus request is delayed or blocked by the OS.

## 4. Automatic Bootstrap System

- **Entrypoint Wrapper**: A guest-side `/root/entrypoint.sh` script coordinates the transition from raw PRoot start to a fully configured environment. This avoids complex quoting issues in the Android shell.
- **Bootstrap Script**: A `/root/bootstrap.sh` script that:
    - Fixes networking by forcing Google DNS.
    - Patches problematic package maintainer scripts (neutralizing `systemctl`, `update-rc.d`, etc.) to prevent `Permission denied` errors during package installation.
    - Configures the `kali` user with passwordless access and sudo privileges.
    - Switches the default shell to ZSH with syntax highlighting.
