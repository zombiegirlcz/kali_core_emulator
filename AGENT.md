# Chat Conversation (Compressed History)

<details>
<summary><b>1. Initial Native Integration Phase</b></summary>

- Integrated premium AdGuard C++ native JNI libraries into `jniLibs/arm64-v8a`.
- Reconstructed Kotlin JNI bridge bindings: `NativeTcpIpStackImpl`, `DnsProxy`, and helper config wrappers.
- Modernized `VpnCaptureService` to boot the native engines and receive network traffic.
- Resolved compilation clashes and lock issues.
</details>

<details>
<summary><b>2. JNI Crash & Linker Debugging Phase</b></summary>

- Fixed JNI crash caused by missing `onTcpConnectRequest`/`onUdpConnectRequest` callbacks and missing static `REJECT` field in `ConnectionRequestResult`.
- Resolved `UnsatisfiedLinkError` linkage bugs by adding explicit bottom-up loading order for library dependencies (`liba`, `libio_utils`, `libcommon_native_jni`, `libadguard-core`/`libadguard-dns`).
- Excluded wireless ADB port `5555` and local package network routing loops to prevent ADB disconnection when starting the VPN.
- Added `@JvmField` annotations on Kotlin event models to expose backing fields as public members visible to JNI `GetFieldID` lookups.
- Added chroot rootfs backup/restore commands to the `LocalApiServer` API endpoints.
</details>

<details>
<summary><b>3. App-Level Attribution & AdGuard Premium UI (June 2026) - COMPLETED</b></summary>

- **Attribution:** Implemented `ProcessResolver` tracing guest PRoot sessions (`zsh`, `curl`, `apt`, etc.) via `/proc/self/net/` and walking process trees. Added reflection-based `checkConnectionOwner` API bridge.
- **Premium UI Dashboard:** Added Statistics card (ads/trackers ratio indicator) and Data Usage tracker card. Added active top apps list.
- **Recent Activity Log:** Restyled log lists with dynamically loaded package icons and red blocked query indicators with `REFUSED` badge.
- **Detailed Request Dialog:** Designed detailed bottom sheet showing granular metrics (connection ID, upload/download size, elapsed time) with copy-to-clipboard actions and block/allow rule triggers.
- **AI Brain Telemetry Integration:** Interactive scatter diagram of flow metrics (entropy vs size) and neural classifier statistics in the UI.
</details>

<details>
<summary><b>4. AI Isolation & Security Attack Testing (June 2026) - COMPLETED</b></summary>

- Isolated the `vpn_brain.onnx` classifier and built `AIBrainTest.kt` using desktop JVM ONNX runtime.
- Simulated and evaluated various network security flows (HTTPS, DNS request, Port scan, Buffer overflow exploit with NOP sleds, Reverse Shell backdoor payload, high-entropy Exfil).
- Discovered high prediction bias: all simulated attacks classified as `NORMAL (0)` due to heavy class imbalance.
<details>
<summary><b>5. AI Agent Network Analysis & Chat Training (June 2026) - COMPLETED</b></summary>

- **New Tool `analyze_network`:** Added to `nethunter_agent.py` (the ReAct agent). Fetches live VPN logs from `LocalApiServer`, filters by time window (default: 60 min) and optional IP address, computes stats (total connections, anomaly count, top destinations, top ports, protocol breakdown, average entropy, bytes sent/received, top apps, anomaly details).
- **System Prompt Extended:** LLM is instructed to use `analyze_network` whenever the user asks about network traffic, IP addresses, threats, anomalies, or security analysis. The agent presents results clearly, highlights CRITICAL/SUSPICIOUS connections, and suggests defensive actions (e.g., iptables block).
- **CLI Chat v2.0 (`vpn-cli chat`):** Updated the in-chroot `ai-agent.py` with commands: `stav`, `analýza`, `ip X.X.X.X`, `!příkaz`.
- **GUI Overlay:** `NetHunterAssistantSession` sends queries via `/agent/query` → agent daemon on port 13338 uses the new tool automatically.

### Training Datasets

| File | Rows | Purpose |
|------|------|---------|
| `vpn_training_dataset.csv` | 20,000 | Balanced ONNX classifier training (50% Normal, 20% DNS, 30% Critical Anomalies). 14-dim feature vector. |
| `ai_chat_training_dataset.jsonl` | 30 | Instruction fine-tuning for conversational AI. JSONL format with system/user/assistant messages. Covers: network analysis queries, tool invocations, interpreting observations, shell commands, VPN control. |

### Retraining Pipeline

- **Script:** `scratch/train_modal.py` — Serverless LightGBM training on Modal.com with `class_weight='balanced'`, exports to ONNX.
- **Status:** Dataset ready, script ready. Pending user execution on Modal.com.

</details>

<details>
<summary><b>6. Draggable & Minimized Drawer & RAM Usage Dashboard (June 2026) - COMPLETED</b></summary>

- **Minimized Draggable Drawer:** Replaced the full width `drawerView` in CLI mode with a floating hamburger button and a 70dp minimized drawer showing only emojis and a VNC GUI switch. Added a vertical drag handle on the right edge of the drawer, enabling manual sliding to expand it to 280dp with tabs, titles, and full detail controls (snaps on release).
- **Live RAM Telemetry:** Added total system memory status under the drawer header, and per-session RAM tracking (RSS read from `/proc/$pid/status` for all session descendants) next to the session labels in the drawer. Refreshes every 3 seconds while the drawer is open.
- **VPN & AI RAM Dashboard Stats:** Added dynamic native heap RAM usage for the C++ VPN Gateway engine and calculated ONNX session memory usage for the AI Classifier inside the Sniffer VPN Gateway card.
</details>

<details>
<summary><b>7. Parrot OS Zshrc Bootstrap Fix & Self-Healing Backups (June 2026) - COMPLETED</b></summary>

- **Bootstrap Race Condition Fix:** Unconditionally deployed optimized `.zshrc` templates and created `.zshrc.nethunter` backup templates in `/etc/skel` and `/root` during initial preparation, preventing distro package manager installations from overwriting or corrupting them during bootstrap.
- **Auto-Restoration:** Modified `bootstrap.sh` to copy the backup configurations to the appropriate homes (including user `/home/parrot`) at the end of the initial installation.
- **Entrypoint Protection:** Updated `setup_user_zsh` in `entrypoint.sh` to copy from the backup templates only when `.zshrc` is completely missing, ensuring the Parrot OS environment starts with a working terminal logo, API commands, and feedback links, while preserving custom user modifications.
</details>

---

## Proposed Network Traffic Monitoring Improvements & Next Goals (June 2026)

1. **[COMPLETED] App-level Attribution & Process Identification / AdGuard Premium UI:** Socket-to-process tracker and full visual dashboard.
2. **[COMPLETED] Threat Intelligence & IP Geolocation (IP Info):** Resolved country flag emojis, city/country GeoIP lookups, and threat warning details cards.
3. **[COMPLETED] DNS Query Inspector:** Dedicated view for DNS queries including Top 10 domains, queries log, and custom blocklists.
4. **[COMPLETED] Real-time Flow Visualizer (Active Sockets):** Display currently open TCP/UDP sockets with upload/download speeds, states, app attributions, and instant firewall action buttons.
5. **[COMPLETED] AI Brain Telemetry Integration:** Interactive scatter diagram of flow metrics (entropy vs size) and neural classifier statistics in the UI.
6. **[COMPLETED] AI Agent Network Analysis Chat:** The AI agent can now answer user questions about network traffic using the `analyze_network` tool. Supports time-based and IP-based filtering with full statistical breakdown.
7. **[COMPLETED] Serverless AI Retraining on Modal.com:** Synthesizing/loading balanced network traffic datasets (including synthetic stealth Evasion attacks), training a balanced LightGBM model, exporting/compiling to ONNX, and fixing decimal CSV parsing.
8. **[IN PROGRESS] Live AI Android Integration:** Connecting the `AIBrain.kt` ONNX runtime to the live AdGuard JNI network flow in `VpnCaptureService` / `ProcessResolver` to calculate features and classify traffic in real-time.
9. **[COMPLETED] Minimized & Draggable Session Drawer / System & Session RAM Telemetry:** Compact drawer layout (width 70dp) with swipe/expand gestures and real-time total system, per-session, and VPN/AI dashboard RAM usage trackers.
10. **[COMPLETED] Refactoring Keyboard / Key Bindings:** Designing a premium custom keyboard bar and key layout inside the CLI terminal to simplify shortcut input (Ctrl, Alt, Tab, arrow keys) and improve accessibility.
