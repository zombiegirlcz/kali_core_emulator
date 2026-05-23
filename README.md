# Kali Core Emulator

An Android application to run a full Kali Linux environment (NetHunter) on unrooted devices using PRoot and Termux terminal components.

## Features
- **Unrooted Operation**: Runs entirely in user space without requiring root privileges.
- **SELinux Bypass**: Implements established workarounds (legacy targetSdk, standalone dynamic binaries) to function on Android 10-14.
- **Automatic Bootstrap**: Self-configuring environment on first launch (DNS, Apt Sources, ZSH, Sudo).
- **Interactive Terminal**: Full PTY support with soft keyboard integration.

## Installation & Setup
1. Build the APK using Gradle: `./gradlew assembleDebug`
2. Install and launch the app.
3. Click "Download" and "Extract" to acquire the Kali rootfs.
4. Click "Launch" to start the session.
5. On the first run, the system will automatically perform a bootstrap installation (approx. 5 minutes).

## Technical Details
For an in-depth look at the workarounds used to bypass Android's security restrictions, see [SUCCESSFUL_WORKAROUNDS.md](SUCCESSFUL_WORKAROUNDS.md).

## Development
This project uses:
- **Kotlin & Jetpack Compose** for UI.
- **PRoot** for user-space chroot.
- **Termux Libraries** for terminal emulation.
- **OkHttp & Apache Commons Compress** for rootfs management.
