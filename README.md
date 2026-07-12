# Linux Kali NetHunter🐉 and ParrotOS security🦜 proot-distro emulator
                       _ROOT && UNROOT_
A state-of-the-art, highly optimized Android application designed to run full guest Linux distributions (**Kali NetHunter** & **ParrotOS Security**) on unrooted devices using PRoot and low-latency Termux terminal emulation, powered by a built-in premium **AdGuard VPN & DNS proxy firewall service**.

---

## ⚡ Key Highlights & Architecture

### 🚀 Up to 50x Faster Package & File Operations
- **Automated Optimizations:** During the initial bootstrap process, the app automatically configures both `dpkg` and `apt` package managers with disk write optimizations (`force-unsafe-io`).
- **Performance Boost:** File operations, package installations (`apt install`), and overall system upgrades run **up to 50x faster** than standard Android emulators, avoiding severe Android storage bottlenecking.

### 🔌 Premium SMART JNI VPN & DNS Proxy Sniffer
- **Native C++ Engine:** Powered by a direct native integration of AdGuard’s C++ stack (`libadguard-core.so` & `libadguard-dns.so`). Handles high-throughput userspace packet translation and local DNS resolution.
- **Interactive Telemetry Dashboard:** Includes a futuristic graphic visualizer under the "VPN Center" tab:
  - **Segmented Timeframes:** Instantly toggle between 24 Hours (Hourly), 7 Days, and 30 Days (Daily) traffic curves.
  - **Glowing Canvas Chart:** Draws distinct glowing curves for Download (green) and Upload (cyan) streams.
  - **Touch-Drag Cursor Overlay:** Tapping or dragging on the Canvas draws a vertical cyber-yellow highlight cursor, updating a floating detailed badge with exact data points in real time.
  - **Aggregate Metrics:** Calculates Peak Speeds, Average Rates, and Total Bandwidth transferred.
  - **History Breakdown:** Scrollable chronological log of all traffic logs with double-colored ratio progress bars.

### ⛓️ Session-Isolated VPN Bypass
- **BFS Socket Resolution:** Integrates a high-efficiency background socket-to-process crawler. When a TCP connection is established, the VPN service traverses `/proc` using a custom Breadth-First Search (BFS) process-tree resolver to determine if the local socket is owned by a bypassed chroot terminal session.
- **Dynamic Bypass Controls:** Execute `nh vpn ignore on` or `nh vpn ignore off` directly inside a terminal tab to instantly bypass VPN routing (e.g., for local network scans) without stopping global VPN protection.
  - *Legacy:* `ignore-vpn on` / `ignore-vpn off` still works via compatibility symlink.
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

## 🌐 Integrated Android API Bridge & Unified CLI (`nh`)

The application starts a loopback API server listening at `127.0.0.1:1337` on the Android host, exposing native device sensors and hardware features directly to the guest Linux terminal via the unified **`nh`** CLI tool (also accessible as `nethunter`).

> **Note:** The old `nethunter-*` and `vpn-*` standalone commands are **deprecated**. All functionality is now consolidated under `nh <category> <action>`. Legacy names still work via compatibility symlinks, but the unified CLI is the recommended interface.

| Command | Category | Action | Example |
| :--- | :--- | :--- | :--- |
| `nh system battery` | system | Retrieve detailed battery status in JSON | `nh system battery` |
| `nh system volume [N]` | system | Get or set media volume (0-15) | `nh system volume 10` |
| `nh system torch on\|off` | system | Turn the device flashlight on or off | `nh system torch on` |
| `nh system vibrate [ms]` | system | Vibrate the device (default 500ms) | `nh system vibrate 1000` |
| `nh system toast <msg>` | system | Display an Android Toast popup | `nh system toast "Done!"` |
| `nh system clipboard get\|set [text]` | system | Read/write host clipboard | `nh system clipboard set "pass123"` |
| `nh system notification -t T -c C` | system | Post a system notification | `nh system notification -t "Alert" -c "Scan done"` |
| `nh system speech` | system | Start voice recognition input | `nh system speech` |
| `nh network wifi [on\|off\|scan\|connect]` | network | Manage Wi-Fi interface | `nh network wifi scan` |
| `nh network cell` | network | Retrieve mobile network info | `nh network cell` |
| `nh network location` | network | Retrieve GPS coordinates in JSON | `nh network location` |
| `nh network map` | network | TerminalMap with current location | `nh network map` |
| `nh network ifconfig [iface]` | network | Show network interfaces | `nh network ifconfig wlan0` |
| `nh vpn start\|stop\|status` | vpn | Start/stop/check VPN service | `nh vpn status` |
| `nh vpn on [cmd]` | vpn | Enable VPN (optionally run a command) | `nh vpn on` |
| `nh vpn off [cmd]` | vpn | Disable VPN (optionally run a command) | `nh vpn off` |
| `nh vpn logs [-n N] [-g P] [json]` | vpn | Fetch MITM traffic logs | `nh vpn logs -n 50 -g mitm` |
| `nh vpn mitm on\|off\|status\|ca` | vpn | Control TLS MITM engine | `nh vpn mitm status` |
| `nh vpn bypass <cmd>` | vpn | Run a command outside VPN tunnel | `nh vpn bypass curl ipinfo.io` |
| `nh vpn ignore on\|off\|status` | vpn | Toggle VPN bypass for current shell | `nh vpn ignore on` |
| `nh vpn sni-fallback get\|set\|clear` | vpn | Manage SNI fallback hostname | `nh vpn sni-fallback set example.com` |
| `nh agent config\|start\|stop\|status` | agent | Manage the AI agent daemon | `nh agent status` |
| `nh agent ask <question>` | agent | Ask the AI a security question | `nh agent ask "Analyze this pcap"` |
| `nh agent chat` | agent | Open interactive AI expert console | `nh agent chat` |
| `nh log [-n N] [-g P]` | log | Colorized logcat viewer (V/D/I/W/E/F) | `nh log -n 50 -g LocalApiServer` |
| `nh log set <level>` | log | Set log level (1-5) | `nh log set 3` |
| `nh device admin status\|request\|lock` | device | Manage Device Admin | `nh device admin lock` |
| `nh device battery-optimize status\|request` | device | Battery optimization settings | `nh device battery-optimize request` |
| `nh device tap <x> <y>` | device | Tap at screen coordinates | `nh device tap 500 300` |
| `nh device click <text>` | device | Click by visible text | `nh device click "Submit"` |
| `nh device longclick <text>` | device | Long-click by visible text | `nh device longclick "App"` |
| `nh device swipe <x1> <y1> <x2> <y2>` | device | Swipe gesture | `nh device swipe 100 200 300 400` |
| `nh device text <text>` | device | Insert text at cursor | `nh device text "hello"` |
| `nh device scroll forward\|backward` | device | Scroll the active view | `nh device scroll forward` |
| `nh device global back\|home\|recents` | device | Global device action | `nh device global home` |
| `nh api share on\|off\|status` | api | Expose API externally (0.0.0.0 vs 127.0.0.1) | `nh api share on` |
| `nh desktop start\|stop\|status` | desktop | Control noVNC XFCE4 desktop | `nh desktop start` |
| `nh fix pkg <name>` | fix | Fix a stuck post-install script | `nh fix pkg libc6` |
| `nh fix auto` | fix | Auto-fix all broken packages | `nh fix auto` |
| `nh apps usage` | apps | App usage statistics (24h) | `nh apps usage` |
| `nh usb list` | usb | List USB devices | `nh usb list` |
| `nh usb permission <device>` | usb | Request USB device permission | `nh usb permission /dev/bus/usb/001/002` |
| `nh usb claim <device> [iface]` | usb | Claim USB interface | `nh usb claim /dev/bus/usb/001/002 0` |
| `nh usb release <device>` | usb | Release USB interface | `nh usb release /dev/bus/usb/001/002` |
| `nh usb send <device> <file>` | usb | Send raw bulk data to USB device | `nh usb send /dev/bus/usb/001/002 exploit.bin` |

---

## 🧠 AI Brain Integration (The Brain of the VPN)

NetHunter AI Operator features an embedded **AI Inference Engine** (`AIBrain.kt`) that sits directly in the packet pathway:
- **Packet Classification:** Every intercepted TCP/UDP session metadata is analyzed in real-time by a locally running lightweight neural network.
- **Features Tracked:** Classifies flows based on packet size, protocol number, delta-time intervals, source/destination ports, and payload entropy (to detect hidden encrypted tunnels).
- **Audit Logging:** Categorizes packets into `ALLOWED`, `VERBOSE`, `SUSPICIOUS`, or `CRITICAL` network anomalies.
- **Hacker Console Interaction:** Users can execute `nh agent chat` to open a local AI Expert console or run `nh agent start` to spawn a background daemon that monitors connection streams and triggers Android toasts/alerts if high-risk intrusions or security anomalies are detected.

---

## 🛡️ Chroot Environment Protection & Process Control

### 🧟 Zombie Process Resolution
To prevent ghost background processes and memory leakages, the terminal session manager has been hardened to trace process hierarchies. When a terminal session is closed or restarted, the app automatically traverses `/proc/$pid/fd` and `/proc/$pid/stat` to discover all child/descendant processes spawned by the session. It sends termination signals to clean up the entire descendant tree, ensuring no zombie processes are left running on the Android host.

### 📦 APT Installation Protection
Under unrooted PRoot environments, packages using systemd or low-level capabilities (`setcap`, `sysctl`, `resolvconf`, etc.) often fail to install, causing packages to remain half-configured. NetHunter AI Operator intercepts these operations using wrapper scripts (`apt`, `apt-get`):
- **Mock Helpers:** Redirects systemd controls to `/bin/true` to bypass failing service configurations.
- **Post-Install Repair:** `nh fix pkg <name>` mocks corrupted debian configuration scripts to `/bin/true`, unblocking stuck `dpkg --configure -a`.
- **Auto-Fix All:** `nh fix auto` scans and repairs all broken packages automatically.

### 🚀 PRoot Container Startup & Setup Lifecycle

Every time a terminal session is launched, `ProotManager.kt` ensures the virtualized environment is fully prepared, configured, and bridged to the Android host services.

#### 1. Directory Structure Creation
Before booting PRoot, the manager verifies and creates the following critical directories inside the guest `rootfs`:
- System mounts: `system`, `dev`, `proc`, `sys`
- User environments: `root`, `home/kali` (or `home/parrot`)
- Temporary and shared files: `tmp`, `sdcard` (if storage mounting is enabled)
- Unix standard paths: `bin`, `usr/bin`, `usr/sbin`, `sbin`, `lib`, `lib64`, `usr/lib`, `etc`

#### 2. Startup Sentinel Files
To track the state of the guest container, the following sentinel files are managed in `/root/`:
- `.hushlogin`: Automatically created to silence default login shell banners.
- `.bootstrap_required`: Created on fresh installations to flag that the system needs to run `bootstrap.sh`. Removed once the first initialization completes.
- `.setup_done`: Touched upon completion of `bootstrap.sh` to prevent re-running setup operations.

#### 3. Execution Entrypoints & Scripts
- **`launcher.sh`** (Android Host): A shell script generated dynamically in the app's files directory. It sets environment variables (`HOME=/root`, `USER=root`, `PATH`, `TERM=xterm-256color`, `LANG=C.UTF-8`), performs diagnostics, checks for dynamic loader combinations (`proot` + `loader` + `libtalloc.so.2` vs. standalone), and launches the guest shell with appropriate flag mounts (`-v 0 --kill-on-exit --link2symlink -0`).
- **`/root/bootstrap.sh`** (Guest Guest OS): Runs when `.bootstrap_required` is present. It configures trusted apt sources, temporarily replaces the `debconf` perl module with mock shell handlers (to bypass unconfigured Perl dependencies), diverts virtualization-incompatible system commands (e.g. `systemctl`, `service`, `udevadm`) to `/bin/true`, installs core packages (`usrmerge`, `perl`, `zsh`, `sudo`, `curl`, `python3`), installs required python libraries (`requests`, `scapy`), creates the default user (`kali` or `parrot`) with passwordless sudo rights, and sets Zsh/Bash as default.
- **`/root/entrypoint.sh`** (Guest Guest OS): Cleans up `dpkg` locks, restores `passwd` if it was incorrectly diverted, sets up user-specific `.zshrc` profiles, fixes `sudo` permissions (`chmod 4755`), and invokes the interactive login shell (`zsh` or fallback `/bin/bash`).

#### 4. Shared Library & Dynamic Linker Fixes
To prevent core dump or execution crashes in the sandboxed chroot:
- The system loader is copied into the guest `lib/ld-linux-aarch64.so.1` and `lib64/ld-linux-aarch64.so.1`.
- The helper library `libtalloc.so.2` is deployed into guest `lib/libtalloc.so.2`.
- Any broken symbolic links for `bin/sh` and `bin/bash` in the guest OS are automatically dereferenced and replaced with solid binaries to prevent loader failures.

#### 5. Deployed Helper Scripts — Unified `nh` CLI (Guest `/usr/local/bin/`)
At startup, `ProotManager` deploys a single unified **`nh`** CLI tool (symlinked as `nethunter`) into `/usr/local/bin/`. This replaces all legacy `nethunter-*`, `vpn-*`, and `ignore-vpn` scripts. Legacy names are kept as compatibility symlinks but all functionality is consolidated under `nh <category> <action>`:

| Category | Available Actions |
| :--- | :--- |
| `nh system` | `battery`, `volume`, `torch`, `vibrate`, `toast`, `clipboard`, `notification`, `speech` |
| `nh network` | `wifi`, `cell`, `location`, `map`, `ifconfig` |
| `nh vpn` | `start`, `stop`, `status`, `on`, `off`, `logs`, `mitm`, `bypass`, `ignore`, `sni-fallback` |
| `nh agent` | `config`, `start`, `stop`, `status`, `ask`, `chat` |
| `nh log` | `[-n N] [-g P]`, `set <level>` |
| `nh device` | `admin`, `battery-optimize`, `accessibility`, `tap`, `click`, `longclick`, `swipe`, `text`, `scroll`, `global` |
| `nh api` | `share on/off/status` |
| `nh desktop` | `start`, `stop`, `status` |
| `nh fix` | `pkg <name>`, `auto` |
| `nh apps` | `usage` |
| `nh usb` | `list`, `permission`, `claim`, `release`, `send`, `bulk`, `control` |

> Legacy scripts (`nethunter-*`, `vpn-on`, `vpn-off`, `vpn-cli`, `vpn-bypass`, `ignore-vpn`, `nethunter-agent-cli`, `nethunter-desktop`) are still present as **compatibility symlinks** pointing to `nh`. All new development and documentation should use the unified `nh` syntax.

---


## ⌨️ Premium Hacker Keyboard

The application integrates an advanced, fully customizable, and responsive overlay keypad split into 5 tabs for maximum efficiency:
1. **🎛️ Control**: Essential terminal controllers (`ESC`, `TAB`, `ENTER`, `DELETE`, `⌫`, `Shift+Tab`, and a system clipboard `PASTE` action).
2. **🔣 Symbols**: Fast access to 30 of the most frequently used special terminal characters (`\`, `|`, `~`, `$`, `*`, etc.).
3. **🧭 Navigation**: Navigational arrows, `Home`, `End`, `Page Up`, and `Page Down` (directly mapped to match guest zsh bindings).
4. **⚡ Combos**: 19 prepackaged Ctrl combinations (`^C`, `^Z`, `^X`, `^S`, etc.) sent directly as ASCII control bytes.
5. **🛠️ F-Keys**: Function keys F1 through F20 (including standard xterm mappings for higher-order keys F13–F20).

---

## 📈 Major Version 4.1 Changelog

This release introduces UI modularity improvements and advanced resource monitoring metrics for both virtualization and networking components:

### 1. Draggable & Minimized Session Drawer
- **Clean CLI View:** Automatically hides the top navigation bar in CLI mode to maximize vertical terminal space. Added a floating corner hamburger button (`☰`) to open the session panel.
- **Peek & Expand Gesture:** Launches the drawer in a minimized `70dp` view containing only distro emojis (`🐉`/`🦜`) and a fast VNC GUI launcher. Resizing is handled by dragging a right-edge handle, expanding the drawer to a full `280dp` layout with auto-snapping on release.

### 2. Live Resource & RAM Telemetry
- **Total System RAM:** Displays real-time total and used system memory in the expanded drawer header (e.g. `[RAM: 3.4 GB / 8.0 GB]`).
- **Chroot Session RSS:** Loops through session descendant processes inside `/proc` and aggregates their resident set size (`VmRSS` from `/proc/$pid/status`) to show per-session RAM footprints (e.g. `Session 1 (12.4 MB)`). Updates every 3 seconds when the drawer is open.
- **VPN Core & AI footprint:** Shows separate real-time memory stats in the VPN Gateway card: native heap allocation size for the C++ AdGuard engine, and the ONNX session memory footprint for the AI Brain.

---

## 📈 Major Version 4.0 Changelog (NetHunter App Store v4)

This release introduces the fully integrated **AI Brain Telemetry & Neural Classifier**, transforming the VPN into an intelligent, autonomous firewall capable of detecting and blocking advanced persistent threats (APTs) in real-time.

### 1. Embedded AI Inference Engine (LightGBM ONNX)
- **Live Packet Classification:** Connected the `AIBrain.kt` ONNX runtime to the live AdGuard JNI network flow. Extracted 14-dimensional features (size, delta-time, protocol, entropy, etc.) are fed into the neural network for every TCP/UDP session.
- **Evasion-Hardened Detection:** The AI model is trained on a balanced synthetic dataset, specifically hardened against stealth evasion techniques (Low-and-Slow Scans, Stealthy HTTPS C2 Beacons, and DNS Tunneling Data Exfiltration).
- **Zero Memory Leaks:** Implemented strict native JNI pointer garbage collection (`use` blocks for `OnnxTensor` and `OrtSession.Result`) to ensure the VPN runs endlessly without memory overflow during high-frequency packet interception.

### 2. Conversational AI Network Agent
- **ReAct Log Analysis:** Added `analyze_network` tool to the local `ai-agent.py`. The AI agent can now fetch, filter, and break down network telemetry directly from the host.
- **Hacker Console Integration:** Execute `nh agent chat` to open a local AI Expert console or run `nh agent start` for background monitoring and automated system toasts upon critical anomaly detection.

### 3. App-level Attribution & Premium Dashboard
- **Process Traversal:** Socket-to-process tracker using BFS `/proc` traversal to attribute network flows to specific chroot binaries.
- **Real-time Flow Visualizer:** Interactive scatter diagrams for entropy metrics and real-time active socket connection cards with immediate block actions.
- **Threat Intelligence:** Resolved GeoIP lookups and integrated country flag emojis directly into the traffic logs.

---

## 📈 Version 4.2 Changelog

This release fixes critical TLS MITM proxy issues, adds Root CA management, and introduces diagnostic CLI tools:

### 1. TLS MITM Engine Fixes
- **Fixed Client→Server Encryption:** Client plaintext is now correctly encrypted via `serverEngine.wrap()` before forwarding to the remote server. Previously, raw decrypted data was sent directly, breaking all MITM-proxied connections.
- **Removed Redundant Unwrap Block:** Eliminated a duplicate `clientEngine.unwrap` + `writeToServer` block that contained a double-flip ByteBuffer bug, causing zero bytes to be written.
- **Adaptive CPU Backoff:** `proxyLoop` now sleeps 1ms during active data transfer and 15ms when idle, reducing CPU usage dramatically (previously a constant 1ms busy-loop).

### 2. Root CA Certificate Management
- **New API Endpoint:** `GET /vpn/mitm/ca` returns the bundled MITM Root CA certificate in PEM format.
- **New CLI Command:** `nh vpn mitm ca` fetches and displays the Root CA for easy export and installation.
- **Installation Guide:** Documentation for installing the CA into Kali/PRoot trust store and Android system trust store.

### 3. Diagnostic CLI Tools
- **`nh log`:** Unified logcat viewer with level-based coloring (V=gray, D=blue, I=green, W=yellow, E/F=red bold), automatic keyword highlighting, and grep filtering.
- **`/app/logs` API Endpoint:** New LocalApiServer endpoint serving raw logcat output for `nh log`.

---

## 📈 Major Version 3.1 Changelog

This release stabilizes the native AdGuard JNI bridge layer, addressing security vulnerabilities, JNI contract mismatches, and memory management invariants:

### 1. SSL/TLS Certificate Verification & Parity Restored
- **Trust Store Integration:** Restored full X.509 chain verification inside `EventsAdapter` utilizing `CertificateFactory` and `TrustManagerFactory` backed by the default Android KeyStore / CA trust store. This closes a critical MITM vulnerability where invalid/self-signed certificates were accepted by default.
- **JNI Contract Parity:** Refactored `CertificateVerificationEvent` to use raw DER byte arrays (`ByteArray?`) and added the missing `@JvmField var chain: List<ByteArray>?` field, along with getter methods expected by the native library to prevent class-loading/method lookup exceptions.
- **Search Domain Bootstrap:** Restored local search domain suffix discovery via `ConnectivityManager` and `LinkProperties` parsing, registering wildcard suffixes (e.g. `*.local`) to prevent local network hostname leaks to public resolvers.

### 2. Native Stack Lifecycle & Memory Safety
- **Kernel File Descriptor Leak Guard:** Added validation checks in `NativeTcpIpStackImpl` constructor to prevent detaching the `ParcelFileDescriptor` using `pfd.detachFd()` when native `init()` fails, avoiding permanent file descriptor leaks.
- **JNI Callback Exception Safety:** Wrapped all TCP and UDP callback dispatches in `NativeTcpIpStackImpl.Callbacks` inside `try-catch (e: Throwable)` blocks, catching `RejectedExecutionException` during shutdowns and completing requests with `REJECT` to prevent JNI process-level crashes.
- **Teardown Thread-Safety:** Synchronized `DnsProxy.close()` to prevent double-free SEGFAULTs and guaranteed `nativePtr` resets to `0L` after deallocation.
- **ABI Filters Guard:** Implemented `ndk.abiFilters` for `arm64-v8a` inside `defaultConfig` in Gradle configurations to prevent runtime loading crashes on non-arm64 devices.

---

## 📈 Major Version 3.0 Changelog (NetHunter App Store v3)

This release implements secure peer-to-peer overlay capabilities, geo-proxy loop enhancements, and visual dashboard upgrades:

### 1. Peer-to-Peer Mesh VPN
- **Overlay Networking:** Added a virtual subnet (`10.9.0.0/24`) mapped to custom P2P interfaces.
- **STUN Hole Punching:** Queries public STUN servers (`stun.l.google.com:19302`) dynamically to resolve WAN sockets and punches holes through CGNAT routers.
- **ECDH Cryptography:** Secures communications between peers using AES-128-GCM, with keys derived on-the-fly via native Elliptic Curve Diffie-Hellman (ECDH) key agreements.
- **Serverless Pairing:** Allows direct peer pairing by pasting simple connection strings containing Node IDs, names, public keys, and resolved WAN addresses.

### 2. High-Performance SOCKS5 Proxy Loop (original)
- **Interval Control:** Replaced unstable sleep-modulo logic with a volatile timestamp-tracking loop to guarantee exact rotation intervals.
- **Interactive Geolocation Nodes:** Upgraded the "Worldwide Rotating Proxy" card to display country flags, resolved IP details, and segmented seg-selectors.
- **Concurrent Ping Latency Checker:** Added a "PING ALL" diagnostics button, resolving all proxy nodes' latencies concurrently and displaying color-coded speed tags.

### 3. Custom IP Proxy (v4.2+)
- **Removed SOCKS5 Pool & Rotation:** Replaced 6 hardcoded SOCKS5 proxy nodes with rotation modes (Static/Random/Time-loop) with a single **custom IP:Port** field.
- **Simplified Tunnel:** All TCP traffic can be forwarded directly to a user-specified endpoint (e.g. a personal VPS) via raw TCP tunnel — no SOCKS5 handshake, no rotation logic.
- **Fallback to Direct:** Proxy is completely optional. When no custom IP is set, traffic goes direct. When proxy fails, it falls back to direct connection automatically.
- **UI Cleanup:** Settings tab now shows a simple text input (`IP:Port`) instead of dropdowns for rotation mode, node selection, and interval slider.

**Motivation:** The SOCKS5 rotation was overengineered for most use cases. Users who need a static proxy (their own VPS, a VPN gateway, etc.) just enter the IP:Port and all traffic tunnels through it. Everyone else gets direct connection — zero configuration needed.

---

## 📈 Version 4.3 Changelog

This release replaces the complex SOCKS5 proxy rotation with a simple custom IP:Port tunnel, and adds various MITM stability fixes:

### 1. Custom IP Proxy (replaces SOCKS5 rotation)
- **Removed:** 6 hardcoded SOCKS5 proxies, 3 rotation modes (Static/Random/Time-loop), interval timer, latency checker
- **Added:** Single text field for custom `IP:Port` — all TCP traffic tunnels directly to that endpoint
- **Simplified Engine:** Removed SOCKS5 greeting/connect/response handshake from VpnNatEngine — replaced with raw TCP forward
- **Graceful Fallback:** If custom proxy connection fails, traffic automatically falls back to direct
- **Cleaner UI:** Proxy settings reduced from 3 dropdowns + slider to a single text input

### 2. MITM & Stability Fixes
- (existing fixes from 4.2 — see below)

---

## 📈 Major Version 2.0 Changelog
- **AdGuard7 JNI C++ VPN Engine Integration:** Built native AdGuard TCP/IP C++ stack (`libadguard-core.so` & `libadguard-dns.so`) with precise JNI structural mapping.
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
