# linux-distro

An Android application to run full Linux environments (Kali NetHunter & ParrotOS Security) on unrooted devices using PRoot and Termux terminal components.

## Features
- **Unrooted Operation**: Runs entirely in user space without requiring root privileges.
- **SELinux Bypass**: Implements established workarounds (legacy targetSdk, standalone dynamic binaries) to function on Android 10-14+.
- **Automatic Bootstrap**: Self-configuring environment on first launch (DNS, Apt Sources, ZSH, Sudo).
- **Interactive Terminal**: Full PTY support with soft keyboard integration, pinch-to-zoom text resizing, and clipboard copy/paste.
- **3D Matrix UI**: Fully animated 3D Matrix-style raining code background in the main launcher menu.
- **Storage Access**: Optional toggle to bind mount `/sdcard` for direct access to internal Android storage.
- **Dynamic ZSH**: Automatic installation and injection of `zsh-syntax-highlighting` into the shell profiles.
- **Setuid Workarounds**: Built-in support for `sudo` and `su` for standard users (`kali`) inside the PRoot container.

## Installation & Setup
1. Build the APK using Gradle: `./gradlew assembleDebug`
2. Install and launch the app.
3. Select your preferred distro and click "INSTALL" to acquire the rootfs.
4. (Optional) Toggle "Mount /sdcard Storage" to enable access to Android files.
5. Click "LAUNCH" to start the session.
6. On the first run, the system will automatically perform a bootstrap installation and fix broken dpkg states.

## Technical Details
For an in-depth look at the workarounds used to bypass Android's security restrictions, see [SUCCESSFUL_WORKAROUNDS.md](SUCCESSFUL_WORKAROUNDS.md).

## Development
This project uses:
- **Kotlin & Jetpack Compose** for UI.
- **PRoot** for user-space chroot.
- **Termux Libraries** for terminal emulation.
- **OkHttp & Apache Commons Compress** for rootfs management.