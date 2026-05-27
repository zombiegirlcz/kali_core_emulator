# NetHunter AI Operator

A modern, highly optimized Android application to run full Linux environments (**Kali NetHunter** & **ParrotOS Security**) on unrooted devices using PRoot and Termux terminal components.

---

## ⚡ Key Highlights

### 🚀 Up to 50x Faster Package & File Operations
- The application automatically configures both `dpkg` and `apt` package managers with disk write optimizations (`force-unsafe-io`) during the bootstrap process.
- **Result:** Package installations, updates, and overall filesystem operations in the terminal execute **up to 50x faster** than standard emulators. Both Parrot OS and Kali are lightning fast and buttery smooth.

### 🌐 Integrated Android API Bridge (NetHunter API)
The application features a built-in loopback API server (listening on `127.0.0.1:1337` on the host side) exposing core Android capabilities directly to your Linux container. You can control your hardware and system features straight from the guest shell using these built-in commands:

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

---

## ⌨️ Premium Termius-style Hacker Keyboard
The application integrates an advanced, fully customizable, and responsive overlay keypad split into 5 tabs for maximum efficiency:
1. **🎛️ Control**: Essential terminal controllers (`ESC`, `TAB`, `ENTER`, `DELETE`, `⌫`, `Shift+Tab`, and a system clipboard `PASTE` action).
2. **🔣 Symbols**: Fast access to 30 of the most frequently used special terminal characters (`\`, `|`, `~`, `$`, `*`, etc.).
3. **🧭 Navigation**: Navigational arrows, `Home`, `End`, `Page Up`, and `Page Down` (directly mapped to match guest zsh bindings).
4. **⚡ Combos**: 19 prepackaged Ctrl combinations (`^C`, `^Z`, `^X`, `^S`, etc.) sent directly as ASCII control bytes.
5. **🛠️ F-Keys**: Function keys F1 through F20 (including standard xterm mappings for higher-order keys F13–F20).

---

## 🎨 Immersive Environment & UI
- **Smart Root Detection**: Before running the bootstrap script, the app prompts you to specify whether the host device has ROOT (Magisk / KernelSU). If you select **Yes**, the app skips generating system binary mocks (`systemctl`, `service`, `sysctl`, etc., diverted to `/bin/true`), ensuring a pristine native launch.
- **Classic Parrot Prompt**: The Parrot OS shell features the legendary dual-line prompt with cyan borders and a colorful spearhead indicator `╼` (green for regular users, red for root).
- **NetHunter Design**: Kali Linux comes pre-configured with custom NetHunter login banners, colored API help listings, and zsh syntax-highlighting.
- **3D Matrix Menu**: The main screen features a fully animated 3D Matrix raining code background.

---

## ⚙️ Technology Stack
- **Kotlin & Jetpack Compose** for modern, responsive UI.
- **PRoot** for user-space chroot virtualization without root privileges.
- **Termux Libraries** for low-latency terminal rendering.
- **OkHttp & Apache Commons Compress** for robust rootfs downloads and extraction.

---

## ☕ Support the Developer & Feedback

If you find this project useful or want to support ongoing development, consider buying the developer a coffee:

- **Buy me a coffee:** [https://revolut.me/tomaspetrpayme](https://revolut.me/tomaspetrpayme)
- **Feedback & Support:** [zombiegirlcz@gmail.com](mailto:zombiegirlcz@gmail.com)
- **GitHub Repository:** [https://github.com/zombiegirlcz/kali_core_emulator.git](https://github.com/zombiegirlcz/kali_core_emulator.git)