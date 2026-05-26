# linux-distro

An Android application to run full Linux environments (Kali NetHunter & ParrotOS Security) on unrooted devices using PRoot and Termux terminal components.

## Features
- **Unrooted Operation**: Runs entirely in user space without requiring root privileges.
- **Android API Bridge (NetHunter API)**: Built-in local loopback API server (listening on `127.0.0.1:1337` on the host side) exposing core Android features (vibration, battery, clipboard, Text-to-Speech, notifications, GPS, Wi-Fi info, media volume, and flashlight) to the guest shell via prepackaged executable commands.
- **SELinux Bypass**: Implements established workarounds (legacy targetSdk, standalone dynamic binaries) to function on Android 10-14+.
- **Automatic Bootstrap**: Self-configuring environment on first launch (DNS, Apt Sources, ZSH, Sudo).
- **Interactive Terminal**: Full PTY support with soft keyboard integration, pinch-to-zoom text resizing, and clipboard copy/paste.
- **3D Matrix UI**: Fully animated 3D Matrix-style raining code background in the main launcher menu.
- **Storage Access**: Optional toggle to bind mount `/sdcard` for direct access to internal Android storage.
- **Dynamic ZSH**: Automatic installation and injection of `zsh-syntax-highlighting` into the shell profiles.
- **Setuid Workarounds**: Built-in support for `sudo` and `su` for standard users (`kali`) inside the PRoot container.

## Android API Bridge (NetHunter API)

To bridge the gap between Kali/Parrot guest containers and your Android device, the application automatically deploys several `nethunter-*` helper utilities inside `/usr/bin/` of the guest environment. These utilities communicate with our zero-dependency, local loopback API server bound to `127.0.0.1:1337` on the host:

| Command | Action | Example |
| :--- | :--- | :--- |
| `nethunter-battery-status` | Get detailed battery parameters in JSON | `nethunter-battery-status` |
| `nethunter-toast <msg>` | Display an Android Toast popup | `nethunter-toast "Task completed successfully!"` |
| `nethunter-vibrate [ms]` | Vibrate the device (defaults to 500ms) | `nethunter-vibrate 1000` |
| `nethunter-tts-speak <text>` | Speak text aloud via Text-to-Speech | `echo "Firewall breach detected" \| nethunter-tts-speak` |
| `nethunter-clipboard-get` | Output the current host clipboard content | `nethunter-clipboard-get` |
| `nethunter-clipboard-set <text>`| Write text to the host clipboard | `nethunter-clipboard-set "GeneratedPassword123"` |
| `nethunter-notification -t <t> -c <c>`| Post a standard system notification | `nethunter-notification -t "NetHunter Alert" -c "Scan completed"` |
| `nethunter-wifi-connectioninfo`| Retrieve SSID, BSSID, RSSI, and speed in JSON | `nethunter-wifi-connectioninfo` |
| `nethunter-location` | Retrieve current GPS coordinates in JSON | `nethunter-location` |
| `nethunter-volume [level]` | Retrieve or set the media stream volume | `nethunter-volume 10` |
| `nethunter-torch [on\|off]` | Turn the device flashlight on or off | `nethunter-torch on` |

## Installation & Setup
1. Build the APK using Gradle: `./gradlew assembleDebug`
2. Install and launch the app.
3. Select your preferred distro and click "INSTALL" to acquire the rootfs.
4. (Optional) Toggle "Mount /sdcard Storage" to enable access to Android files.
5. Click "LAUNCH" to start the session.
6. On the first run, the system will automatically perform a bootstrap installation, deploy the API scripts, and fix broken dpkg states.

## Technical Details
For an in-depth look at the workarounds used to bypass Android's security restrictions, see [SUCCESSFUL_WORKAROUNDS.md](SUCCESSFUL_WORKAROUNDS.md).

## Development
This project uses:
- **Kotlin & Jetpack Compose** for UI.
- **PRoot** for user-space chroot.
- **Termux Libraries** for terminal emulation.
- **OkHttp & Apache Commons Compress** for rootfs management.