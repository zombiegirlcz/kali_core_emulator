# 🐉 NetHunter AI Operator

A state-of-the-art, highly optimized Android application designed to run full guest Linux distributions (**Kali NetHunter** & **ParrotOS Security**) on unrooted devices using PRoot and low-latency Termux terminal emulation, powered by a built-in premium **AdGuard VPN & DNS proxy firewall service**.

---

## ⚡ Key Highlights & Architecture

### 🚀 Up to 50x Faster Package & File Operations
- **Automated Optimizations:** During the initial bootstrap process, the app automatically configures both `dpkg` and `apt` package managers with disk write optimizations (`force-unsafe-io`).
- **Performance Boost:** File operations, package installations (`apt install`), and overall system upgrades run **up to 50x faster** than standard Android emulators, avoiding severe Android storage bottlenecking.

### 🔌 Premium AdGuard JNI VPN & DNS Proxy Sniffer
- **Native C++ Engine:** Powered by a direct native integration of AdGuard’s C++ stack (`libadguard-core.so` & `libadguard-dns.so`). Handles high-throughput userspace packet translation and local DNS resolution.
- **Interactive Telemetry Dashboard:** Includes a futuristic graphic visualizer under the "VPN Center" tab:
  - **Segmented Timeframes:** Instantly toggle between 24 Hours (Hourly), 7 Days, and 30 Days (Daily) traffic curves.
  - **Glowing Canvas Chart:** Draws distinct glowing curves for Download (green) and Upload (cyan) streams.
  - **Touch-Drag Cursor Overlay:** Tapping or dragging on the Canvas draws a vertical cyber-yellow highlight cursor, updating a floating detailed badge with exact data points in real time.
  - **Aggregate Metrics:** Calculates Peak Speeds, Average Rates, and Total Bandwidth transferred.
  - **History Breakdown:** Scrollable chronological log of all traffic logs with double-colored ratio progress bars.

### ⛓️ Session-Isolated VPN Bypass
- **BFS Socket Resolution:** Integrates a high-efficiency background socket-to-process crawler. When a TCP connection is established, the VPN service traverses `/proc` using a custom Breadth-First Search (BFS) process-tree resolver to determine if the local socket is owned by a bypassed chroot terminal session.
- **Dynamic Bypass Controls:** Execute `ignore-vpn on` or `ignore-vpn off` directly inside a terminal tab to instantly bypass VPN routing (e.g., for local network scans) without stopping global VPN protection.
- **Visual Status Badging:** Ignored sessions are dynamically color-coded inside the drawer menu using distinct Orange-Gold text and `[VPN IGNORED]` badges.

### 🐉 Multi-Distro Sessions Drawer
- **Distro Category Tabs:** The session manager drawer is equipped with a premium horizontal tab selector (**ALL**, **KALI**, **PARROT**) to filter sessions on the fly.
- **Aesthetic Emojis:** Each relacio card is prepended with visual badges representing the distro:
  - **🐉** for Kali NetHunter sessions.
  - **🦜** for ParrotOS Security sessions.
- **Persisted Custom Naming:** Long-pressing any card displays a custom native `AlertDialog`, allowing you to input a friendly name that persists perfectly across application restarts and switches.
- **Thicker Tap Targets:** Extended with a thick vertical `18dp` and horizontal `16dp` card layout, maximizing clicking accuracy on high-DPI screens.

### 📝 System-Wide Editor Integration (Nano)
- **File Intent Interceptor:** Registered as a system-wide document editor (`ACTION_VIEW` / `ACTION_EDIT`) in the Android Manifest.
- **Auto-Buffer chroot /tmp:** When you click "Open with NetHunter AI Operator" on any text or script file from external file managers, the app automatically:
  - Switches to CLI mode if currently in GUI desktop.
  - Copies the source stream safely to `/tmp/nethunter_edit_<filename>` inside the active guest rootfs.
  - Commands the active session shell (or boots a new one with a safe delay) to execute `nano /tmp/nethunter_edit_<filename>` using control sequences (`Ctrl+C` and `Ctrl+U`) to safely wipe the terminal line first.

### 🔍 Pinch-to-Zoom Precision & Memory
- **Dampened Font Scaling:** Pinch gestures are dampened by a custom factor of `0.15f` (making size adjustments ~6.6x slower and smoother) for micro-adjustments.
- **Size Persistence:** Saves your preferred font size to Android `SharedPreferences` instantly, restoring it automatically across fresh starts and new sessions.

---

## 🌐 Integrated Android API Bridge (NetHunter API)

The application starts a loopback API server listening at `127.0.0.1:1337` on the Android host, exposing native device sensors and hardware features directly to the guest Linux terminal via standard bash utilities:

| Command | Action | Example usage |
| :--- | :--- | :--- |
| `nethunter-battery-status` | Retrieve detailed battery status in JSON | `nethunter-battery-status` |
| `nethunter-toast <msg>` | Display an Android Toast popup on the screen | `nethunter-toast "Task completed successfully!"` |
| `nethunter-vibrate [ms]` | Vibrate the device (defaults to 500ms) | `nethunter-vibrate 1000` |
| `nethunter-tts-speak <text>` | Speak text aloud using Text-to-Speech | `echo "Firewall breach detected" \| nethunter-tts-speak` |
| `nethunter-clipboard-get` | Read the current host clipboard content | `nethunter-clipboard-get` |
| `nethunter-clipboard-set <text>`| Write text to the host clipboard | `nethunter-clipboard-set "GeneratedPassword123"` |
| `nethunter-notification -t <t> -c <c>`| Post a standard system notification | `nethunter-notification -t "NetHunter Alert" -c "Scan completed"` |
| `nethunter-wifi-connectioninfo`| Retrieve current Wi-Fi network details in JSON | `nethunter-wifi-connectioninfo` |
| `nethunter-location` | Retrieve current GPS coordinates in JSON | `nethunter-location` |
| `nethunter-volume [level]` | Retrieve or set the media stream volume | `nethunter-volume 10` |
| `nethunter-torch [on\|off]` | Turn the device flashlight on or off | `nethunter-torch on` |
| `vpn-on` | Enable global VPN AdGuard Sniffer | `vpn-on` |
| `vpn-off` | Disable global VPN AdGuard Sniffer | `vpn-off` |
| `ignore-vpn [on\|off\|status]` | Toggle VPN bypass for the current session | `ignore-vpn on` |

---

## ⌨️ Premium Termius-style Hacker Keyboard

The application integrates an advanced, fully customizable, and responsive overlay keypad split into 5 tabs for maximum efficiency:
1. **🎛️ Control**: Essential terminal controllers (`ESC`, `TAB`, `ENTER`, `DELETE`, `⌫`, `Shift+Tab`, and a system clipboard `PASTE` action).
2. **🔣 Symbols**: Fast access to 30 of the most frequently used special terminal characters (`\`, `|`, `~`, `$`, `*`, etc.).
3. **🧭 Navigation**: Navigational arrows, `Home`, `End`, `Page Up`, and `Page Down` (directly mapped to match guest zsh bindings).
4. **⚡ Combos**: 19 prepackaged Ctrl combinations (`^C`, `^Z`, `^X`, `^S`, etc.) sent directly as ASCII control bytes.
5. **🛠️ F-Keys**: Function keys F1 through F20 (including standard xterm mappings for higher-order keys F13–F20).

---

## 📈 Major Version 2.0 Changelog

This is the ultimate release of **NetHunter AI Operator**, introducing major core capabilities, UI upgrades, and bug fixes:

### 1. AdGuard JNI C++ VPN Engine Integration
- Replaced basic Android mock VPN with native AdGuard TCP/IP C++ stack (`libadguard-core.so` & `libadguard-dns.so`).
- Implemented precise JNI structural mapping callback hooks, matching Smali deconstructed signatures.
- Enforced Kotlin `@JvmField` properties on bridge models (`DnsRequestProcessedEvent`, `DnsProxySettings`, etc.) to prevent native JNI `NoSuchFieldError` crashes.

### 2. High-Fidelity Interactive Telemetry
- Upgraded the VPN Center traffic tracker into a fully populated, responsive diagnostic panel.
- Added Hourly (24h), Weekly (12w), and Daily (30d) dataset arrays prefilled with cyber-themed distribution workloads.
- Built pointer-coordinate tracking scopes on Canvas to render highlight cursor bars and float detailed tooltip overlay cards.
- Added scrollable history breakdown logs showing upload-vs-download ratios.

### 3. Session-Specific SOCKS5 Proxy Bypass
- Developed high-efficiency userspace process-tree mapping. Traverses `/proc` using Breadth-First Search (BFS) to map socket inodes to their matching active `TerminalSession`.
- Added `POST /vpn/ignore` endpoints on loopback loop, allowing users to execute `ignore-vpn [on|off]` to exclude active terminals from global VPN capture dynamically.
- Integrated Orange-Gold ignored visual badges inside the UI.

### 4. Categorized Distro-Session Tabs Drawer
- Redesigned the left session manager drawer with a horizontal segmented tab bar (**ALL**, **KALI**, **PARROT**) to filter active terminal streams.
- Added custom names database (`ConcurrentHashMap`) persisting session titles.
- Long-pressing session items opens a styled `AlertDialog` + `EditText` input window.
- Prepend cards with visual emojis (**🐉** for Kali, **Parrot** getting **🦜**).
- Made tap targets thicker (`16dp` horizontal, `18dp` vertical padding) to avoid tap misses.

### 5. System File Editor (Nano Integration)
- Configured `TerminalActivity` as an exported, singleTask-based Document Viewer.
- Intercepts VIEW and EDIT intent actions, copies files to guest `/tmp/` and automatically spawns `nano /tmp/nethunter_edit_<filename>` inside active shells.
- Injects line-wiping sequences (`Ctrl+C` and `Ctrl+U`) to safely open files without command conflicts.

### 6. Shell Script Portability Fix
- Resolved an execution failure where API scripts inside `/usr/bin/` would fail with `/usr/bin/sh not found`.
- Updated shebangs on all 18 guest scripts in `ProotManager.kt` from the Bionic host path `#!/system/bin/sh` to glibc-native chroot path `#!/bin/sh`.

### 7. Resizing Zoom Precision & Persistence
- Dampened pinch-to-zoom scaling by `0.15f` to increase layout sizing accuracy by ~6.6x.
- Saved active sizes to `SharedPreferences`, restoring them on subsequent application restarts and new session creations.

### 8. Native Libs Packing & Git-Tracking Fix
- Resolved an issue where compiled `.so` files were omitted from release APKs, shrinking size to 2.21MB and causing load failures.
- Added explicit sourceSets configuration block inside `build.gradle.kts`.
- Overrode ignore lists via custom `jniLibs/.gitignore` filter blocks to force tracking of shared libraries.
- Final packaged APK sizes: Debug (**55.5 MB**), Release (**43.5 MB**) containing all 10 compiled native architectures.

---

## ⚙️ Technology Stack
- **Kotlin & Jetpack Compose** for a modern, responsive UI.
- **PRoot** for user-space chroot virtualization without root privileges.
- **Termux Libraries** for low-latency terminal rendering.
- **AdGuard NatLibs** for secure network diagnostic intercepting.
- **OkHttp & Apache Commons Compress** for robust rootfs downloads and extraction.

---

## ☕ Support the Developer & Feedback

If you find this project useful or want to support ongoing development, consider buying the developer a coffee:

- **Buy me a coffee:** [https://revolut.me/tomaspetrpayme](https://revolut.me/tomaspetrpayme)
- **Feedback & Support:** [zombiegirlcz@gmail.com](mailto:zombiegirlcz@gmail.com)
- **GitHub Repository:** [https://github.com/zombiegirlcz/kali_core_emulator.git](https://github.com/zombiegirlcz/kali_core_emulator.git)