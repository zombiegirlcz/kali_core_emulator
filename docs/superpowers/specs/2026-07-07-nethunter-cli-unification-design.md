# NetHunter CLI Unification Design

**Date:** 2026-07-07
**Status:** Approved design
**Project:** kali_core_emulator (NetHunter AI Operator)

## 1. Motivation

The current codebase ships ~25+ separate `nethunter-*` shell scripts plus `vpn-cli`,
`vpn-on`/`vpn-off`, `vpn-bypass`, `ignore-vpn`, and `nethunter-agent-cli` — all deployed
to `/usr/local/bin/` inside the PRoot container. This clutters the namespace, creates
redundancy (e.g. `nethunter-wifi-connectioninfo` overlaps with `nethunter-wifi-control status`),
and makes discovery harder for users.

Goal: a single unified `nethunter` command (alias `nh`) with consistent subcommand structure:

```
nh <category> <action> [args...]
```

## 2. Command Tree

Every old command maps to exactly one `nh <category> <action>` slot. Old names remain as
symlinks to `nh` for a grace period.

### 2.1 system

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh system battery` | `nethunter-battery-status` | JSON→formatted output: %, bar, temp, voltage, health, plugged |
| `nh system volume [N]` | `nethunter-volume` | Get/set media volume (0-15/100); bar display |
| `nh system torch on|off` | `nethunter-torch` | Toggle camera flash LED |
| `nh system vibrate [ms]` | `nethunter-vibrate` | Vibrate for N ms (default 500) |
| `nh system toast <msg>` | `nethunter-toast` | Android Toast notification |
| `nh system clipboard get` | `nethunter-clipboard-get` | Read host clipboard, display first 200 chars |
| `nh system clipboard set [text]` | `nethunter-clipboard-set` | Write to host clipboard (stdin or arg) |
| `nh system notification -t T -c C` | `nethunter-notification` | Post system notification |
| `nh system speech` | `nethunter-speech-input` | Voice recognition input from device |

### 2.2 network

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh network wifi` | `nethunter-wifi-connectioninfo` | Show current Wi-Fi connection info (SSID, BSSID, RSSI, link speed) |
| `nh network wifi on` | `nethunter-wifi-control on` | Enable Wi-Fi radio |
| `nh network wifi off` | `nethunter-wifi-control off` | Disable Wi-Fi radio |
| `nh network wifi scan` | — **new** | List nearby Wi-Fi networks in range |
| `nh network wifi connect <ssid>` | — **new** | Connect to specified Wi-Fi network |
| `nh network cell` | `nethunter-cellinfo` | Mobile network: operator, signal dBm+bars, type, towers |
| `nh network location` | `nethunter-location` | GPS coordinates + Google Maps URI |
| `nh network map` | `nethunter-map` + `nethunter-terminalmap` | Launch TerminalMap OSM viewer with current location |
| `nh network ifconfig [interface]` | `bin/nh-ifconfig` | Network interface info via `/shell` endpoint |

### 2.3 vpn

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh vpn status` | `vpn-cli status` | VPN running/stopped, packets, traffic |
| `nh vpn start` | `vpn-cli start` / `vpn-on` | Start VPN capture |
| `nh vpn stop` | `vpn-cli stop` / `vpn-off` | Stop VPN capture |
| `nh vpn on [cmd]` | `vpn-on` | Enable VPN (optionally run cmd with VPN on) |
| `nh vpn off [cmd]` | `vpn-off` | Disable VPN (optionally run cmd with VPN off, then restore) |
| `nh vpn logs [-f] [-n N] [-g P] [json\|legacy]` | `vpn-cli logs` | MITM decrypted traffic viewer |
| `nh vpn mitm on|off` | `vpn-cli mitm on/off` | Toggle TLS MITM inspection |
| `nh vpn mitm status` | `vpn-cli mitm status` | MITM on/off + active sessions |
| `nh vpn mitm ca` | `vpn-cli mitm ca` | Export Root CA certificate |
| `nh vpn bypass <cmd>` | `vpn-bypass` | Run command outside VPN tunnel |
| `nh vpn ignore on|off|status` | `ignore-vpn` | Session-level VPN bypass toggle |
| `nh vpn sni-fallback get|set FQDN|clear` | `vpn-cli sni-fallback` | MITM SNI fallback management |

### 2.4 agent

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh agent config --provider X --key Y [--model Z]` | `nethunter-agent-cli config` | Set AI provider credentials |
| `nh agent start` | `nethunter-agent-cli start` | Start background agent daemon |
| `nh agent stop` | `nethunter-agent-cli stop` | Stop agent daemon |
| `nh agent status` | `nethunter-agent-cli status` | Daemon running/stopped |
| `nh agent ask <prompt...>` | `nethunter-agent-cli ask` | Send query to agent (sync) |
| `nh agent chat` | `nethunter-agent-cli chat` | Interactive chat console |

### 2.5 log

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh log [-n N] [-g P] [lines]` | `nethunter-log` | Colorized logcat viewer (V=gray, D=blue, I=green, W=yellow, E/F=red bold) |
| `nh log set lvl 1-5` | — **new** | Set logging level synced with UI settings. 1=error only, 5=verbose/debug |

### 2.6 device

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh device admin status|request` | `nethunter-device-admin` | Device admin status/request |
| `nh device admin lock` | `nethunter-device-admin lock` | Lock device screen |
| `nh device battery-optimize status|request` | `nethunter-battery-optimize` | Battery optimization status/request exemption |
| `nh device accessibility` | `nethunter-accessibility-hierarchy` | Dump accessibility hierarchy |

### 2.7 api

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh api share on|off|status` | `nethunter-api share` | Toggle API binding (0.0.0.0 vs 127.0.0.1) |

### 2.8 desktop

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh desktop start|stop|status` | `nethunter-desktop` | XFCE4 VNC server + noVNC websockify management |

### 2.9 fix

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh fix pkg <balíček>` | `nethunter-fix-postinst` | Mock broken postinst for specific package |
| `nh fix auto` | — **new** | Auto-scan dpkg status, detect half-configured/failed packages, fix all |

### 2.10 apps

| `nh` invocation | Old command | Behaviour |
|---|---|---|
| `nh apps usage` | `nethunter-apps-usage` | App usage statistics (top 10 foreground apps) |

### 2.11 help

| `nh` invocation | Behaviour |
|---|---|
| `nh help` | Show command tree with brief descriptions |
| `nh help network` | Show all network subcommands with details |
| `nh list` | Flat list of all available commands |

## 3. Implementation

### 3.1 Single Shell Script

The entire `nh` command is a single POSIX shell script (`/usr/local/bin/nh`) that does
argument dispatch. It is deployed from assets just like the current `vpn-cli`.

Structure:
```
#!/bin/sh
# NetHunter Unified CLI — nh
# Symlinked as nethunter and nh

API_HOST="${API_HOST:-127.0.0.1}"
API_PORT="${API_PORT:-1337}"
API_URL="http://${API_HOST}:${API_PORT}"

get_token() { ... }   # unchanged from vpn-cli

banner() { ... }
separator() { ... }

usage() { ... }
list_cmds() { ... }

case "$1" in
    system)    shift; system_dispatch "$@" ;;
    network)   shift; net_dispatch "$@" ;;
    vpn)       shift; vpn_dispatch "$@" ;;
    agent)     shift; agent_dispatch "$@" ;;
    log)       shift; log_dispatch "$@" ;;
    device)    shift; device_dispatch "$@" ;;
    api)       shift; api_dispatch "$@" ;;
    desktop)   shift; desktop_dispatch "$@" ;;
    fix)       shift; fix_dispatch "$@" ;;
    apps)      shift; apps_dispatch "$@" ;;
    help|--help|-h) help_dispatch "$@" ;;
    list)      list_cmds ;;
    *)
        # If unknown, try as old nethunter-* compat by prefixing
        # e.g. "nh battery-status" → redirect to old symlink
        if [ -e "/usr/local/bin/nethunter-$1" ]; then
            shift
            exec "nethunter-$1" "$@"
        fi
        usage ;;
esac
```

### 3.2 Token Reading

All HTTP calls to `LocalApiServer` use the same `get_token()` function already
implemented in `vpn-cli` — reading from `api_security.xml`. This stays unchanged.

### 3.3 Color & Formatting

Reuse the ANSI color constants and formatting functions from `vpn-cli` (already
well-designed). Each dispatch function uses the same pattern:

```sh
system_battery() {
    DATA=$(curl -s "$API_URL/battery")
    echo "$DATA" | python3 -c "...formatted JSON display..."
}
```

### 3.4 Backward Compatibility

Old command names become symlinks:

```sh
ln -sf /usr/local/bin/nh /usr/local/bin/nethunter-battery-status
ln -sf /usr/local/bin/nh /usr/local/bin/nethunter-toast
...
ln -sf /usr/local/bin/nh /usr/local/bin/vpn-cli
ln -sf /usr/local/bin/nh /usr/local/bin/vpn-on
...
```

When invoked via symlink, `nh` detects `argv[0]` and dispatches accordingly.

### 3.5 Log Level Syncing

`nh log set lvl 1-5` writes to `$CONFIG_DIR/log_level` file AND POSTs to
`/app/logs/level` endpoint on `LocalApiServer` so the Android side persists it to
SharedPreferences. The `nethunter-log` Python logic reads this file.

### 3.6 MOTD Update

The `deployWelcomeProfile()` function in ProotManager.kt generates two things:
1. A `nethunter-welcome.sh` profile script (shown once per session)
2. An `/etc/motd` file (shown on login)

Both currently reference old command names. They must be updated to show the new
`nh` syntax instead:

**Old MOTD lines (examples):**
```
nethunter-location              GPS + Google Maps
nethunter-cellinfo               mobilní síť (5G/4G/3G)
nethunter-map                   OSM terminálová mapa
vpn-on / vpn-off                 VPN zapnout/vypnout
vpn-cli mitm on|off             TLS MITM zapnout/vypnout
```

**New MOTD lines:**
```
nh network location              GPS + Google Maps
nh network cell                  mobilní síť (5G/4G/3G)
nh network map                   OSM terminálová mapa (TerminalMap)
nh vpn on|off                    VPN zapnout/vypnout
nh vpn mitm on|off               TLS MITM zapnout/vypnout
nh vpn logs                      MITM formátované logy
nh vpn status                    stav VPN
nh vpn bypass <cmd>              obejít VPN pro příkaz
nh vpn ignore on|off             VPN bypass pro session
nh system battery                stav baterie
nh network wifi                  WiFi info (SSID, signál)
nh system volume 0-15            hlasitost
nh system torch on|off           svítilna
nh system toast "text"           Android toast
nh system vibrate [ms]           vibrace
nh system tts-speak "text"       přečíst text nahlas
nh system notification           systémová notifikace
nh system clipboard get|set      schránka (čtení/zápis)
nh network ifconfig [rozhraní]   síťová rozhraní (přes Android API)
nh desktop start                 XFCE4 GUI (noVNC :6080)
nh log [-n N] [-g P]             logcat viewer
nh help                          nápověda
nh list                          seznam všech příkazů
```

The bottom line `📖 cat nethunter_docs.md → plná dokumentace` stays.

### 3.7 nethunter_docs.md Update

The `deployVpnHelpDocument()` function generates a full `nethunter_docs.md` reference.
Every command reference in this document must be rewritten from old `nethunter-*` names
to `nh <category> <action>` syntax. Structure of the document stays the same (sections
for hardware/system, VPN, desktop, etc.) but command tables get new columns.

Since this is a large inline string in Kotlin, the change is mechanical: replace each
old command name with the corresponding `nh` invocation per section 2 mapping.

## 4. Files to Modify

| File | Change |
|---|---|
| `app/src/main/assets/nh` | **New** — the unified CLI script (~25KB, replaces `vpn-cli` + all 25+ scripts) |
| `app/src/main/java/.../ProotManager.kt` | 3 sections to rewrite: |
| | — `deployApiScripts()`: replace inline script generation with `nh` script deployment + symlinks. Remove all individual `nethunter-*` script builders. Keep `apt`, `apt-get`, `vpn-bypass`, `ai-agent.py`, `vpn-log-viewer.py` wrappers (they're not user-facing CLI). |
| | — `deployWelcomeProfile()`: rewrite MOTD lines to use `nh <category> <action>` syntax |
| | — `deployVpnHelpDocument()`: rewrite all command references from `nethunter-*` to `nh <category> <action>` |
| `app/src/main/java/.../LocalApiServer.kt` | Add `/app/logs/level` endpoint (POST to set log level, GET to read) |
| `app/src/main/java/.../VpnSettings.kt` (or equivalent) | Read/write log level setting |
| `app/src/main/assets/vpn-cli` | **Remove** — replaced by `nh vpn` subcommands |
| `app/src/main/assets/nethunter-agent-cli` | **Remove** — replaced by `nh agent` subcommands |
| `app/src/main/assets/bin/nh-ifconfig` | **Remove** — replaced by `nh network ifconfig` (inline in `nh` script) |

## 5. Dependencies & Risks

- The script depends on `python3` inside PRoot for JSON parsing (already present,
  installed by bootstrap). No new dependencies.
- `nh log set lvl` needs a new API endpoint on LocalApiServer — small addition.
- `nh network wifi scan` and `nh network wifi connect` need new API endpoints on
  LocalApiServer (currently not implemented) — requires Java/Kotlin work.
- `nh fix auto` needs to parse dpkg status — pure shell/Python, no new endpoints.
- Backward compat symlinks must be created before old scripts are removed to avoid
  breaking running sessions.

## 6. Out of Scope (Future)

- Interactive TUI command browser
- AI-powered command suggestions
- Tab completion scripts for zsh/bash
