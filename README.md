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

## 🌐 Integrated Android API Bridge & CLI VPN Control

The application starts a loopback API server listening at `127.0.0.1:1337` on the Android host, exposing native device sensors and hardware features directly to the guest Linux terminal via standard bash utilities and specialized VPN management scripts:

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
| `vpn-on` | Enable global VPN AdGuard Sniffer / NAT Engine | `vpn-on` |
| `vpn-off` | Disable global VPN AdGuard Sniffer / NAT Engine | `vpn-off` |
| `vpn-cli <action>` | Advanced VPN CLI to query logs, control service, or start monitor | `vpn-cli status` |
| `vpn-bypass <cmd>` | Forces a command to bypass VPN and connect directly | `vpn-bypass curl ipinfo.io` |
| `ignore-vpn [on\|off\|status]` | Toggle VPN bypass for the current shell session dynamically | `ignore-vpn on` |

---

## 🧠 AI Brain Integration (The Brain of the VPN)

NetHunter AI Operator features an embedded **AI Inference Engine** (`AIBrain.kt`) that sits directly in the packet pathway:
- **Packet Classification:** Every intercepted TCP/UDP session metadata is analyzed in real-time by a locally running lightweight neural network.
- **Features Tracked:** Classifies flows based on packet size, protocol number, delta-time intervals, source/destination ports, and payload entropy (to detect hidden encrypted tunnels).
- **Audit Logging:** Categorizes packets into `ALLOWED`, `VERBOSE`, `SUSPICIOUS`, or `CRITICAL` network anomalies.
- **Hacker Console Interaction:** Users can execute `vpn-cli chat` to open a local AI Expert console or run `vpn-cli ai start` to spawn a background daemon that monitors connection streams and triggers Android toasts/alerts if high-risk intrusions or security anomalies are detected.

---

## 🛡️ Chroot Environment Protection & Process Control

### 🧟 Zombie Process Resolution
To prevent ghost background processes and memory leakages, the terminal session manager has been hardened to trace process hierarchies. When a terminal session is closed or restarted, the app automatically traverses `/proc/$pid/fd` and `/proc/$pid/stat` to discover all child/descendant processes spawned by the session. It sends termination signals to clean up the entire descendant tree, ensuring no zombie processes are left running on the Android host.

### 📦 APT Installation Protection
Under unrooted PRoot environments, packages using systemd or low-level capabilities (`setcap`, `sysctl`, `resolvconf`, etc.) often fail to install, causing packages to remain half-configured. NetHunter AI Operator intercepts these operations using wrapper scripts (`apt`, `apt-get`):
- **Mock Helpers:** Redirects systemd controls to `/bin/true` to bypass failing service configurations.
- **Post-Install Repair (`nethunter-fix-postinst`):** Exposes a utility to mock corrupted debian configuration scripts dynamically when `dpkg --configure -a` gets stuck, protecting the environment's integrity during complex tool installations.

---

## ⌨️ Premium Termius-style Hacker Keyboard

The application integrates an advanced, fully customizable, and responsive overlay keypad split into 5 tabs for maximum efficiency:
1. **🎛️ Control**: Essential terminal controllers (`ESC`, `TAB`, `ENTER`, `DELETE`, `⌫`, `Shift+Tab`, and a system clipboard `PASTE` action).
2. **🔣 Symbols**: Fast access to 30 of the most frequently used special terminal characters (`\`, `|`, `~`, `$`, `*`, etc.).
3. **🧭 Navigation**: Navigational arrows, `Home`, `End`, `Page Up`, and `Page Down` (directly mapped to match guest zsh bindings).
4. **⚡ Combos**: 19 prepackaged Ctrl combinations (`^C`, `^Z`, `^X`, `^S`, etc.) sent directly as ASCII control bytes.
5. **🛠️ F-Keys**: Function keys F1 through F20 (including standard xterm mappings for higher-order keys F13–F20).

---

## 📈 Major Version 3.0 Changelog (NetHunter App Store v3)

This release implements secure peer-to-peer overlay capabilities, geo-proxy loop enhancements, and visual dashboard upgrades:

### 1. Peer-to-Peer Mesh VPN (Tailscale-style)
- **Overlay Networking:** Added a virtual subnet (`10.9.0.0/24`) mapped to custom P2P interfaces.
- **STUN Hole Punching:** Queries public STUN servers (`stun.l.google.com:19302`) dynamically to resolve WAN sockets and punches holes through CGNAT routers.
- **ECDH Cryptography:** Secures communications between peers using AES-128-GCM, with keys derived on-the-fly via native Elliptic Curve Diffie-Hellman (ECDH) key agreements.
- **Serverless Pairing:** Allows direct peer pairing by pasting simple connection strings containing Node IDs, names, public keys, and resolved WAN addresses.

### 2. High-Performance Proxy Loop Refactoring
- **Interval Control:** Replaced unstable sleep-modulo logic with a volatile timestamp-tracking loop to guarantee exact rotation intervals.
- **Interactive Geolocation Nodes:** Upgraded the "Worldwide Rotating Proxy" card to display country flags, resolved IP details, and segmented seg-selectors.
- **Concurrent Ping Latency Checker:** Added a "PING ALL" diagnostics button, resolving all proxy nodes' latencies concurrently and displaying color-coded speed tags.

---

## 📈 Major Version 2.0 Changelog
- **AdGuard JNI C++ VPN Engine Integration:** Built native AdGuard TCP/IP C++ stack (`libadguard-core.so` & `libadguard-dns.so`) with precise JNI structural mapping.
- **High-Fidelity Telemetry:** Upgraded the VPN traffic tracker to draw Download/Upload charts on Canvas.
- **Session-Specific SOCKS5 Proxy Bypass:** Traversing `/proc` via BFS to map socket inodes to sessions and toggle VPN ignore modes.
- **Nano Document Editor:** Spawns `nano` safely via view/edit intents from outer storage managers.

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