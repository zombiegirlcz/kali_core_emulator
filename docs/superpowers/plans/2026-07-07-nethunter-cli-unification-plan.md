# NetHunter CLI Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Test all existing `nethunter-*` and VPN commands for correctness, then consolidate them into a single unified `nh` CLI tool with MOTD/docs updates.

**Architecture:** Phase 1 = verification + bugfix of ~25 existing scripts. Phase 2 = build single `nh` shell script dispatching by category/action. Phase 3 = deploy `nh`, create backward-compat symlinks, update MOTD + docs, remove old scripts from `ProotManager.kt`.

**Tech Stack:** POSIX shell, Python3 (inside PRoot for JSON formatting), Kotlin (ProotManager.kt deployment code), HTTP REST (LocalApiServer:1337)

## Global Constraints

- All commands communicate with `LocalApiServer` on `127.0.0.1:1337`
- Authentication via `Authorization: Bearer <token>` from `/data/data/com.linux_core/shared_prefs/api_security.xml`
- Python3 must be available inside PRoot for JSON formatting (installed by bootstrap)
- ANSI color constants and formatting functions reused from existing `vpn-cli`
- Old command names kept as symlinks to `nh` for backward compatibility
- `nethunter_docs.md` and `/etc/motd` must be updated to reflect new `nh` syntax
- `nh` script deployed from `app/src/main/assets/nh` asset

---
## Phase 0: Test Existing Commands

### Task 0.1: Verify nethunter-battery-status

**Files:**
- Test: inline via PRoot terminal or `adb shell` on running device

**Command to test:**
```bash
nethunter-battery-status
```

**Expected output:**
```
🔋  85%  [████████░░]
   Status:  discharging
   Health:  good
   Teplota: 32.5°C
   Napětí:  4123 mV
```

**Checks:**
- [ ] Does it return colored formatted output?
- [ ] Does it call `curl -s http://127.0.0.1:1337/battery`?
- [ ] Does the `/battery` endpoint on LocalApiServer exist and return valid JSON?
- [ ] Are percentage, temperature, status fields correct vs. actual device state?
- [ ] **If broken:** log the issue in `/root/kali_core_emulator/docs/superpowers/fixes/` as YAML bug report

---

### Task 0.2: Verify nethunter-toast

**Command:**
```bash
nethunter-toast "Test message"
```

**Expected:**
```
✔  Toast sent to host engine
```
AND a visible Android toast appears on the device screen.

**Checks:**
- [ ] Calls `POST http://127.0.0.1:1337/toast` with body?
- [ ] Returns formatted output (green checkmark)?
- [ ] Toast actually appears on device?
- [ ] **If broken:** log bug

---

### Task 0.3: Verify nethunter-vibrate

**Command:**
```bash
nethunter-vibrate 500
nethunter-vibrate      # test default 500ms
```

**Expected:**
```
📳  Vibration: 500 ms
```

**Checks:**
- [ ] Calls `POST http://127.0.0.1:1337/vibrate` with duration?
- [ ] Device vibrates?
- [ ] **If broken:** log bug

---

### Task 0.4: Verify nethunter-tts-speak

**Command:**
```bash
nethunter-tts-speak "Hello world"
echo "Hello from pipe" | nethunter-tts-speak
```

**Expected:**
```
🔊  TTS: Sent to host engine
```
AND device speaks the text.

**Checks:**
- [ ] Calls `POST http://127.0.0.1:1337/tts`?
- [ ] Works from both stdin pipe and arg?
- [ ] TTS actually plays audio?
- [ ] **If broken:** log bug

---

### Task 0.5: Verify nethunter-clipboard-get / nethunter-clipboard-set

**Commands:**
```bash
nethunter-clipboard-set "test123"
nethunter-clipboard-get
nethunter-clipboard-get --raw
```

**Expected (get):**
```
📋  Clipboard: test123
```

**Expected (--raw):**
```
test123
```

**Checks:**
- [ ] `set` calls `POST http://127.0.0.1:1337/clipboard`?
- [ ] `get` calls `GET http://127.0.0.1:1337/clipboard`?
- [ ] `--raw` returns plain text without formatting?
- [ ] Formatted output shows first 200 chars only?
- [ ] **If broken:** log bug

---

### Task 0.6: Verify nethunter-notification

**Command:**
```bash
nethunter-notification -t "Title" -c "Content body"
nethunter-notification "Just content"
```

**Expected:**
```
🔔  Notification posted: Title
```

**Checks:**
- [ ] Calls `POST http://127.0.0.1:1337/notification` with JSON `{"title":"...","content":"..."}`?
- [ ] Notification appears in Android notification drawer?
- [ ] **If broken:** log bug

---

### Task 0.7: Verify nethunter-wifi-connectioninfo

**Command:**
```bash
nethunter-wifi-connectioninfo
```

**Expected:**
```
📡  SSID: MyHomeWiFi
🔗  BSSID: aa:bb:cc:dd:ee:ff
📶  Signál: -65 dBm (Dobrý)
   [██████░░░░]
📶  Rychlost: 433 Mbps
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/wifi`?
- [ ] Returns SSID, BSSID, RSSI, link speed?
- [ ] Signal bar is proportional?
- [ ] **Issue:** this is same data as `nethunter-wifi-control status` — mark for dedup
- [ ] **If broken:** log bug

---

### Task 0.8: Verify nethunter-wifi-control

**Commands:**
```bash
nethunter-wifi-control         # should show status
nethunter-wifi-control on      # enable wifi
nethunter-wifi-control off     # disable wifi
```

**Expected (status):**
```
📶  Wi-Fi: ENABLED
```

**Checks:**
- [ ] Calls `POST http://127.0.0.1:1337/wifi` with body `status`/`on`/`off`?
- [ ] Does `on` actually enable Wi-Fi on the device?
- [ ] Does `off` actually disable Wi-Fi?
- [ ] Does `/wifi` endpoint on LocalApiServer handle all 3 modes?
- [ ] **Missing features:** `scan` (list nearby networks), `connect <ssid>` — not implemented in LocalApiServer
- [ ] **If broken:** log bug

---

### Task 0.9: Verify nethunter-cellinfo

**Command:**
```bash
nethunter-cellinfo
```

**Expected:**
```
📶  T-Mobile CZ  (LTE)
📋  SIM: T-Mobile CZ  |  Data: CONNECTED  |  Roaming: False

Vysílač #1 [REGISTROVÁN]
Typ: LTE | MCC: 230 | MNC: 01
TAC/LAC: 12345 | CID: 67890 | PCI/PSC: 321
Síla signálu: -85 dBm (Velmi dobrý)
Ukazatel: [████████░░] (Velmi dobrý)
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/cellinfo`?
- [ ] Returns carrier, network type, signal dBm, tower info?
- [ ] Signal bars are proportional to dBm?
- [ ] **If broken:** log bug

---

### Task 0.10: Verify nethunter-location

**Command:**
```bash
nethunter-location
```

**Expected:**
```
📍  50.123456, 14.987654  (±25m)  [gps]
🗺️  Google Maps: https://maps.google.com/?q=50.123456,14.987654
🔗 Geo URI:     geo:50.123456,14.987654
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/location`?
- [ ] Returns lat/lng, accuracy, provider, maps URL?
- [ ] **If broken:** log bug

---

### Task 0.11: Verify nethunter-map / nethunter-terminalmap

**Command:**
```bash
nethunter-map
```

**Expected:**
```
[*] Fetching current location...
📍 Location: 50.123456, 14.987654
🗺️  Starting TerminalMap...
[*] Controls: arrow keys/hjkl=pan, a/+/-=zoom, ...
```
(launches `terminalmap` binary)

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/map` first?
- [ ] Then launches `terminalmap` binary?
- [ ] `nethunter-terminalmap` is just a symlink/alias to `nethunter-map`?
- [ ] TerminalMap actually renders map?
- [ ] **If broken:** log bug

---

### Task 0.12: Verify nethunter-battery-optimize

**Commands:**
```bash
nethunter-battery-optimize          # status
nethunter-battery-optimize status
nethunter-battery-optimize request
```

**Expected (status):**
```
🔋  Battery Optimization: IGNORED (OK)
```
or
```
🔋  Battery Optimization: RESTRICTED
```

**Checks:**
- [ ] Calls `GET/POST http://127.0.0.1:1337/battery/optimize`?
- [ ] `request` opens system battery optimization settings?
- [ ] **If broken:** log bug

---

### Task 0.13: Verify nethunter-device-admin

**Commands:**
```bash
nethunter-device-admin           # status
nethunter-device-admin status
nethunter-device-admin request
nethunter-device-admin lock
```

**Expected (status):**
```
🔑  Device Admin: ACTIVE
```
or
```
🔑  Device Admin: INACTIVE
```

**Expected (lock):**
Device screen locks immediately.

**Checks:**
- [ ] Calls `GET/POST http://127.0.0.1:1337/device/admin`?
- [ ] `lock` calls `POST http://127.0.0.1:1337/device/lock`?
- [ ] Device actually locks?
- [ ] **If broken:** log bug

---

### Task 0.14: Verify nethunter-volume

**Commands:**
```bash
nethunter-volume           # get current
nethunter-volume 10        # set to 10
```

**Expected (get):**
```
🔊  Hlasitost: 10/15  [████████░░]
```

**Checks:**
- [ ] Get calls `GET http://127.0.0.1:1337/volume`?
- [ ] Set calls `POST http://127.0.0.1:1337/volume` with value?
- [ ] Volume actually changes on device?
- [ ] Bar renders properly?
- [ ] **If broken:** log bug

---

### Task 0.15: Verify nethunter-torch

**Commands:**
```bash
nethunter-torch on
nethunter-torch off
```

**Expected (on):**
```
🔦  Flashlight: ON
```

**Expected (off):**
```
🌙  Flashlight: OFF
```

**Checks:**
- [ ] Calls `POST http://127.0.0.1:1337/torch` with `on`/`off`?
- [ ] Flashlight physically turns on/off on device?
- [ ] **If broken:** log bug

---

### Task 0.16: Verify nethunter-log

**Commands:**
```bash
nethunter-log
nethunter-log 50
nethunter-log -n 200 -g "TlsMitm"
```

**Expected:**
```
07-07 14:23:45.123 D/TlsMitmEngine (12345): message here...
07-07 14:23:46.456 I/VpnNatEngine (12345): success message
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/app/logs?limit=N`?
- [ ] Color coding: V=gray, D=blue, I=green, W=yellow, E=red bold?
- [ ] Highlighting: `error`/`fail`=red, `success`/`established`=green?
- [ ] `-g` filter is case-insensitive and works?
- [ ] **Log level sync:** Does `nh log set lvl 1-5` endpoint exist on LocalApiServer? (will be added in Phase 2)
- [ ] **If broken:** log bug

---

### Task 0.17: Verify nethunter-speech-input

**Command:**
```bash
nethunter-speech-input
```

**Expected:**
```
🎙️  Voice: recognized text here
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/voice_input`?
- [ ] Android speech recognition dialog appears?
- [ ] Recognized text returned?
- [ ] **If broken:** log bug

---

### Task 0.18: Verify nethunter-notifications-active

**Command:**
```bash
nethunter-notifications-active
```

**Expected:**
```
🔔  Active Notifications:
   💬  New message [com.whatsapp]
   📧  Email received [com.google.android.gm]
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/notifications/active`?
- [ ] Lists active notifications with title and package?
- [ ] **If broken:** log bug

---

### Task 0.19: Verify nethunter-apps-usage

**Command:**
```bash
nethunter-apps-usage
```

**Expected:**
```
📊  App Usage Statistics:
   💠  com.google.chrome - 45 min
   💠  com.whatsapp - 23 min
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/apps/usage`?
- [ ] Lists top 10 apps with foreground time?
- [ ] **If broken:** log bug

---

### Task 0.20: Verify nethunter-accessibility-hierarchy

**Command:**
```bash
nethunter-accessibility-hierarchy
```

**Expected:**
(Returns raw XML/JSON accessibility tree from device)
```
<node ...>
  <node class="android.widget.TextView" text="Hello" />
</node>
```

**Checks:**
- [ ] Calls `GET http://127.0.0.1:1337/accessibility/hierarchy`?
- [ ] Returns structured accessibility data?
- [ ] **If broken:** log bug

---

### Task 0.21: Verify nethunter-fix-postinst

**Command:**
```bash
nethunter-fix-postinst broken-package-name
```

**Expected:**
```
[*] Mocking postinst for broken-package-name...
[*] Reconfiguring dpkg...
[+] Successfully fixed postinst for broken-package-name!
```

**Checks:**
- [ ] Creates `/var/lib/dpkg/info/broken-package-name.postinst` symlink to `/bin/true`?
- [ ] Runs `dpkg --configure -a`?
- [ ] **If broken:** log bug

---

### Task 0.22: Verify nethunter-desktop

**Commands:**
```bash
nethunter-desktop status
nethunter-desktop start
nethunter-desktop stop
```

**Expected (status):**
```
=== DESKTOP SESSION STATUS ===
[+] VNC Server: RUNNING
[+] noVNC Websockify: RUNNING
=== VNC LOGS (LAST 20 LINES) ===
...
```

**Checks:**
- [ ] `start` installs packages (xfce4, tigervnc, novnc) if missing?
- [ ] `start` launches VNC on :1 and websockify on :6080?
- [ ] `stop` kills both processes and cleans up?
- [ ] `status` reports correct state?
- [ ] **If broken:** log bug

---

### Task 0.23: Verify nethunter-api share

**Commands:**
```bash
nethunter-api share status
nethunter-api share on
nethunter-api share off
```

**Expected:**
```
[+] API sharing is currently DISABLED (127.0.0.1)
```

**Checks:**
- [ ] `on` calls POST to `/api/share` with body "on"?
- [ ] `off` calls POST to `/api/share` with body "off"?
- [ ] After `on`, API becomes accessible from network (0.0.0.0)?
- [ ] **If broken:** log bug

---

### Task 0.24: Verify vpn-cli commands

**Commands:**
```bash
vpn-cli status
vpn-cli start
vpn-cli stop
vpn-cli mitm status
vpn-cli mitm on
vpn-cli mitm off
vpn-cli mitm ca
vpn-cli logs
vpn-cli logs -n 10
vpn-cli logs -g "google"
vpn-cli logs json
vpn-cli logs legacy
vpn-cli sni-fallback get
vpn-cli sni-fallback set www.example.com
vpn-cli sni-fallback clear
```

**Expected (status):**
```
──────────────────────────────────────────────
  VPN Status:  🟢 RUNNING
  Packets:     12345
  Traffic:     12.3 MB
──────────────────────────────────────────────
```

**Checks:**
- [ ] All commands authenticate via Bearer token?
- [ ] `mitm on/off` actually toggles MITM state?
- [ ] `mitm status` shows active sessions?
- [ ] `logs` shows formatted HTTP traffic?
- [ ] `logs -f` follows live?
- [ ] **If broken:** log bug

---

### Task 0.25: Verify vpn-on / vpn-off / vpn-bypass / ignore-vpn

**Commands:**
```bash
vpn-on
vpn-off
vpn-bypass curl ipinfo.io
ignore-vpn on
ignore-vpn status
ignore-vpn off
```

**Checks:**
- [ ] `vpn-on` calls `POST /vpn/start`?
- [ ] `vpn-off` calls `POST /vpn/stop`?
- [ ] `vpn-on curl ipinfo.io` starts VPN, runs curl, leaves VPN running?
- [ ] `vpn-off curl ipinfo.io` stops VPN, runs curl, restarts VPN?
- [ ] `vpn-bypass` sets proxy env vars and runs command?
- [ ] `ignore-vpn on/off` calls `/vpn/ignore?session_id=N&ignored=true/false`?
- [ ] **If broken:** log bug

---

### Task 0.26: Compile Bug Reports

**Files:**
- Create: `docs/superpowers/fixes/2026-07-07-cli-test-report.yaml`

**Format:**
```yaml
test_date: 2026-07-07
tested_by: automated
commands:
  nethunter-battery-status:
    status: PASS
    notes: ""
  nethunter-toast:
    status: PASS
    notes: ""
  nethunter-wifi-control:
    status: PARTIAL
    notes: "scan and connect features missing from LocalApiServer"
  nethunter-wifi-connectioninfo:
    status: PASS
    notes: "Redundant with wifi-control status — mark for dedup"
  ...
summary:
  total: 25
  pass: 21
  partial: 2
  fail: 2
  missing_features:
    - "nh network wifi scan — not implemented in LocalApiServer"
    - "nh network wifi connect <ssid> — not implemented in LocalApiServer"
    - "nh log set lvl 1-5 — needs new endpoint"
```

- [ ] Create bug report YAML file with results from all tests
- [ ] List missing features for Phase 2 implementation
- [ ] Note commands that are purely redundant (e.g., `nethunter-wifi-connectioninfo` = `wifi-control status`)
- [ ] **Commit test report**

---

## Phase 1: Create Unified `nh` Script

### Task 1.1: Create `nh` CLI shell script

**Files:**
- Create: `app/src/main/assets/nh`

**Interface:**
- Consumes: All existing HTTP endpoints on LocalApiServer:1337
- Produces: `/usr/local/bin/nh` shell script (deployed as asset)

**Implementation:**
Build single POSIX shell script with the following dispatch structure. The script inlines all the formatting logic from the existing `vpn-cli` and `nethunter-*` scripts.

```sh
#!/bin/sh
# NetHunter Unified CLI — nh
# Deployed to /usr/local/bin/nh, symlinked as nethunter and all old compat names

API_HOST="${API_HOST:-127.0.0.1}"
API_PORT="${API_PORT:-1337}"
API_URL="http://${API_HOST}:${API_PORT}"

# ---- ANSI Colors ----
RST='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'
RED='\033[1;31m'; GREEN='\033[1;32m'; YELLOW='\033[1;33m'
BLUE='\033[1;34m'; MAGENTA='\033[1;35m'; CYAN='\033[1;36m'; WHITE='\033[1;37m'
BG_RED='\033[41m'; BG_GREEN='\033[42m'; BG_BLUE='\033[44m'
GRAY='\033[0;90m'

get_token() {
    # Read auth token from api_security.xml — same as vpn-cli
    TOKEN=$(cat /data/data/com.linux_core/shared_prefs/api_security.xml 2>/dev/null | \
        grep 'name="auth_token"' | sed 's/.*value="\([^"]*\)".*/\1/' | head -n 1)
    [ -z "$TOKEN" ] && TOKEN=$(cat /data/data/com.linux_core/shared_prefs/api_security.xml 2>/dev/null | \
        grep 'name="auth_token"' | sed "s/.*value='\([^']*\)'.*/\1/" | head -n 1)
    echo "$TOKEN"
}

api_get() { curl -s -H "Authorization: Bearer $(get_token)" "$API_URL$1"; }
api_post() { curl -s -X POST -H "Authorization: Bearer $(get_token)" -d "$2" "$API_URL$1"; }

# ---- Python JSON Formatter Helper ----
# Each dispatch function builds a python3 script and pipes JSON through it
format_json() {
    # Reads stdin JSON, applies python formatting script
    python3 -c "$1" 2>/dev/null
}
```

Then dispatch by category:

```sh
case "$(basename "$0")" in
    nethunter-*|vpn-*|ignore-vpn)
        # Symlink backward compat — map to nh invocation
        compat_dispatch "$0" "$@"
        exit $?
        ;;
esac

COMMAND="$1"
[ -z "$COMMAND" ] && { usage; exit 1; }
shift

case "$COMMAND" in
    system)   system_dispatch "$@" ;;
    network)  network_dispatch "$@" ;;
    vpn)      vpn_dispatch "$@" ;;
    agent)    agent_dispatch "$@" ;;
    log)      log_dispatch "$@" ;;
    device)   device_dispatch "$@" ;;
    api)      api_dispatch "$@" ;;
    desktop)  desktop_dispatch "$@" ;;
    fix)      fix_dispatch "$@" ;;
    apps)     apps_dispatch "$@" ;;
    help|--help|-h) help_dispatch "$@" ;;
    list)     list_cmds ;;
    *)        usage ;;
esac
```

Each dispatch function follows the pattern:

```sh
system_battery() {
    api_get "/battery" | format_json '
import sys, json
G,R,Y,N,Gy=chr(27)+"[92m",chr(27)+"[91m",chr(27)+"[93m",chr(27)+"[0m",chr(27)+"[90m"
try:
    d=json.load(sys.stdin)
    pct=d.get("percentage",-1); st=d.get("status","?")
    hl=d.get("health","?"); temp=d.get("temperature",0); volt=d.get("voltage",0)
    n=max(1,int(pct/10)) if pct>0 else 0; bar="█"*n+"░"*(10-n)
    c = G if st=="charging" else (G if pct>50 else (Y if pct>20 else R))
    ico = "🔌" if st=="charging" else "🔋"
    print(f"{c}{ico}  {pct}%  [{bar}]{N}")
    print(f"   Status:  {st}")
    print(f"   Health:  {hl}")
    print(f"   Teplota: {temp}°C")
    print(f"   Napětí:  {volt} mV")
except: print("Error")
'
}
```

Include all dispatch functions covering every command from section 2 of the spec.

- [ ] Write the full `nh` script with all category dispatchers
- [ ] Include `compat_dispatch()` for backward compat symlink detection
- [ ] Include `usage()` with full help tree
- [ ] Include `list_cmds()` for flat list
- [ ] Ensure all commands use the same `get_token()` / `api_get` / `api_post` infrastructure
- [ ] **Commit**

---

### Task 1.2: Add `/app/logs/level` endpoint to LocalApiServer

**Files:**
- Modify: `app/src/main/java/.../LocalApiServer.kt`

**Interface:**
- `GET /app/logs/level` → `{"level": 3}`
- `POST /app/logs/level` body `3` → sets log level

**Implementation:**
```kotlin
// In handleConnection(), around the existing /app/logs handler:
val LOG_LEVEL_PREFS = "log_settings"
val LOG_LEVEL_KEY = "log_level"

// Handler:
"/app/logs/level" -> {
    when (method) {
        "GET" -> {
            val level = context.getSharedPreferences(LOG_LEVEL_PREFS, Context.MODE_PRIVATE)
                .getInt(LOG_LEVEL_KEY, 3) // default 3 = INFO
            sendJson(out, """{"level":$level}""")
        }
        "POST" -> {
            val level = body.trim().toIntOrNull() ?: 3
            val validLevel = level.coerceIn(1, 5)
            context.getSharedPreferences(LOG_LEVEL_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(LOG_LEVEL_KEY, validLevel).apply()
            Log.i(TAG, "Log level changed to $validLevel")
            sendJson(out, """{"level":$validLevel}""")
        }
        else -> sendJson(out, 405, """{"error":"Method not allowed"}""")
    }
}
```

Modify `nethunter-log` / `nh log` Python to read the stored log level and adjust verbosity.

- [ ] Add log level endpoints to LocalApiServer.kt
- [ ] Wire up `nethunter-log` Python script to read log level and filter accordingly
- [ ] **Commit**

---

### Task 1.3: Add Wi-Fi scan + connect endpoints to LocalApiServer

**Files:**
- Modify: `app/src/main/java/.../LocalApiServer.kt`

**Interface:**
- `POST /wifi` body `scan` → returns JSON list of nearby networks
- `POST /wifi` body `connect:<ssid>` → connect to specified network

**Implementation (in the existing `/wifi` handler):**
```kotlin
val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

when (body.trim()) {
    "on" -> {
        wifiManager.setWifiEnabled(true)
        sendJson(out, """{"enabled":true}""")
    }
    "off" -> {
        wifiManager.setWifiEnabled(false)
        sendJson(out, """{"enabled":false}""")
    }
    "status" -> {
        // existing behavior — return connection info
        val info = wifiManager.connectionInfo
        sendJson(out, buildWifiJson(info))
    }
    "scan" -> {
        val success = wifiManager.startScan()
        val results = wifiManager.scanResults
        val networks = results.map { 
            """{"ssid":"${it.SSID}","bssid":"${it.BSSID}","level":${it.level},"capabilities":"${it.capabilities}"}"""
        }
        sendJson(out, """{"scan_complete":$success,"networks":[${networks.joinToString(",")}]}""")
    }
    else -> {
        // Check for "connect:<ssid>"
        if (body.startsWith("connect:")) {
            val ssid = body.removePrefix("connect:")
            // Configure Wi-Fi network
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            val netId = wifiManager.addNetwork(conf)
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
            sendJson(out, """{"connected":true,"ssid":"$ssid"}""")
        } else {
            sendJson(out, 400, """{"error":"Unknown wifi command"}""")
        }
    }
}
```

- [ ] Add scan and connect logic to `/wifi` handler
- [ ] Test with `nh network wifi scan` and `nh network wifi connect <ssid>`
- [ ] **Commit**

---

## Phase 2: Deploy and Remove Old Scripts

### Task 2.1: Rewrite ProotManager.kt — deployApiScripts()

**Files:**
- Modify: `app/src/main/java/.../ProotManager.kt`

**Changes:**
1. Remove all individual `nethunter-*` script templates from the `scripts` map in `deployApiScripts()`
2. Keep only: `apt`, `apt-get`, `vpn-bypass`, `dcheck`, `ai-agent.py`, `vpn-log-viewer.py` (these are internal wrappers, not user-facing CLI)
3. Deploy the `nh` script from `app/src/main/assets/nh` instead of inline generation
4. Create all backward-compat symlinks

**Implementation sketch:**
```kotlin
// In deployApiScripts(), replace the scripts map with:
// 1. Deploy nh from asset
val nhFile = File(binDir, "nh")
context.assets.open("nh").use { input ->
    nhFile.outputStream().use { output -> input.copyTo(output) }
}
nhFile.setExecutable(true, false)
nhFile.setReadable(true, false)

// 2. Symlink as nethunter (full name)
val nethunterLink = File(binDir, "nethunter")
if (!nethunterLink.exists()) {
    Files.createSymbolicLink(nethunterLink.toPath(), nhFile.toPath())
}

// 3. Create backward-compat symlinks
val compatNames = listOf(
    // nethunter-* compatibility
    "nethunter-battery-status", "nethunter-toast", "nethunter-vibrate",
    "nethunter-tts-speak", "nethunter-clipboard-get", "nethunter-clipboard-set",
    "nethunter-notification", "nethunter-wifi-connectioninfo",
    "nethunter-wifi-control", "nethunter-cellinfo", "nethunter-location",
    "nethunter-map", "nethunter-terminalmap", "nethunter-battery-optimize",
    "nethunter-device-admin", "nethunter-volume", "nethunter-torch",
    "nethunter-log", "nethunter-speech-input", "nethunter-notifications-active",
    "nethunter-apps-usage", "nethunter-accessibility-hierarchy",
    "nethunter-fix-postinst", "nethunter-desktop", "nethunter-api",
    // VPN compatibility
    "vpn-cli", "vpn-on", "vpn-off", "vpn-bypass", "ignore-vpn",
    // old standalone
    "nethunter-agent-cli", "nh-ifconfig"
)
for (name in compatNames) {
    val link = File(binDir, name)
    if (!link.exists()) {
        Files.createSymbolicLink(link.toPath(), nhFile.toPath())
    }
}
```

- [ ] Remove all 25+ `nethunter-*` script templates from `deployApiScripts()`
- [ ] Add `nh` asset deployment
- [ ] Add all backward-compat symlinks
- [ ] Remove `vpn-cli` inline content (no longer needed as inline string)
- [ ] **Commit**

---

### Task 2.2: Rewrite ProotManager.kt — deployWelcomeProfile() MOTD

**Files:**
- Modify: `app/src/main/java/.../ProotManager.kt`

**Changes:**
Replace all old command references in both:
1. The `nethunter-welcome.sh` profile script (the `echo` lines)
2. The `/etc/motd` file

Old patterns (search for these in code):
```
nethunter-location
nethunter-cellinfo
nethunter-map
nethunter-battery-status
nethunter-wifi-connectioninfo
nethunter-volume
nethunter-torch
nethunter-toast
nethunter-vibrate
nethunter-tts-speak
nethunter-notification
nethunter-clipboard-get/set
nh-ifconfig
vpn-on / vpn-off
vpn-cli mitm on|off
vpn-cli mitm status
vpn-cli logs
vpn-cli status
vpn-cli chat
vpn-bypass
ignore-vpn
nethunter-desktop
```

Replace with:
```
nh network location
nh network cell
nh network map
nh system battery
nh network wifi
nh system volume
nh system torch
nh system toast
nh system vibrate
nh system tts-speak
nh system notification
nh system clipboard get|set
nh network ifconfig
nh vpn on|off
nh vpn mitm on|off
nh vpn mitm status
nh vpn logs
nh vpn status
nh agent chat
nh vpn bypass
nh vpn ignore on|off
nh desktop
```

- [ ] Update all MOTD echo lines in profile script
- [ ] Update all MOTD text lines in `/etc/motd`
- [ ] Add `nh help` and `nh list` reference line
- [ ] **Commit**

---

### Task 2.3: Update nethunter_docs.md

**Files:**
- Modify: `app/src/main/java/.../ProotManager.kt`

**Changes:**
Replace the `deployVpnHelpDocument()` inline string content. Every command table must be updated from old names to `nh <category> <action>`. Keep the same structure (hardware, VPN, desktop sections) but update each row.

**Example table replacement:**

Old:
```
| nethunter-battery-status | Vypíše stav baterie | nethunter-battery-status |
| nethunter-toast <msg>    | Toast notifikace    | nethunter-toast "text"   |
```

New:
```
| nh system battery        | Vypíše stav baterie | nh system battery        |
| nh system toast <msg>    | Toast notifikace    | nh system toast "text"   |
```

- [ ] Rewrite all command reference tables in `deployVpnHelpDocument()` string
- [ ] Add new `nh` section showing the unified structure
- [ ] Add `nh help` and `nh list` to quick reference
- [ ] **Commit**

---

### Task 2.4: Remove old standalone asset files

**Files:**
- Delete: `app/src/main/assets/vpn-cli` (now inline in nh script)
- Delete: `app/src/main/assets/nethunter-agent-cli` (now inline in nh script)
- Delete: `app/src/main/assets/bin/nh-ifconfig` (now inline in nh script)

**Note:** Only remove the standalone asset files after confirming the new `nh` script covers all functionality. The symlinks in `/usr/local/bin/` will still point to `nh` for backward compat.

- [ ] Remove `vpn-cli` asset
- [ ] Remove `nethunter-agent-cli` asset
- [ ] Remove `bin/nh-ifconfig` asset
- [ ] **Commit**

---

### Task 2.5: Build and deploy APK for testing

**Command:**
```bash
cd /root/kali_core_emulator
modal run modal_build.py::upload_src && modal run modal_build.py::build
```

- [ ] Upload source to Modal
- [ ] Build APK
- [ ] Download and install APK on device
- [ ] Verify `nh` command is deployed to `/usr/local/bin/`
- [ ] Verify `nh help` shows the command tree
- [ ] Verify old names still work (e.g., `nethunter-battery-status` → redirects to `nh system battery`)
- [ ] Verify MOTD shows new `nh` syntax
- [ ] Verify `cat nethunter_docs.md` shows updated docs
- [ ] **Commit version bump**

---

## Phase 3: Full Integration Test

### Task 3.1: Run integration test suite

**Test all `nh` commands:**
```bash
# Help and discovery
nh help
nh help vpn
nh list

# System
nh system battery
nh system volume
nh system volume 10
nh system torch on && sleep 2 && nh system torch off
nh system vibrate 300
nh system toast "nh unified test"
nh system clipboard set "nh-test-42"
nh system clipboard get
nh system clipboard get --raw
nh system notification -t "NH Test" -c "Unified CLI test"
nh system speech

# Network
nh network wifi
nh network wifi on
nh network wifi off
nh network wifi scan
nh network cell
nh network location
nh network map
nh network ifconfig

# VPN
nh vpn status
nh vpn start
nh vpn stop
nh vpn mitm status
nh vpn mitm on
nh vpn mitm off
nh vpn logs
nh vpn logs -n 5 -g "google"
nh vpn logs json
nh vpn bypass curl ipinfo.io

# Agent
nh agent status
nh agent ask "Hello, are you there?"

# Log
nh log -n 10
nh log -g "VpnNat"
nh log set lvl 1
nh log set lvl 5

# Device
nh device admin status
nh device battery-optimize status
nh device accessibility | head -20

# API
nh api share status

# Desktop
nh desktop status

# Fix
nh fix pkg broken-package
nh fix auto

# Apps
nh apps usage
```

- [ ] Run all commands on device
- [ ] Verify each returns expected formatted output
- [ ] Verify each communicates with LocalApiServer
- [ ] Update bug report YAML with final results
- [ ] **Commit final test report**

---

### Task 3.2: Final cleanup and version bump

**Files:**
- Modify: `app/build.gradle.kts`

**Changes:**
- Increment `versionCode` by 1
- All old script assets removed
- `nethunter-agent-cli` deployment removed from `ProotManager.kt` asset deployment list

- [ ] Version bump
- [ ] Verify no dead code remains in ProotManager.kt
- [ ] **Commit with message: "feat: unified nh CLI — consolidated all nethunter-* and VPN commands"**
