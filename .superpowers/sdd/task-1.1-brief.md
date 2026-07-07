# Task 1.1: Create nh CLI Shell Script

## Requirements

Create `/root/kali_core_emulator/app/src/main/assets/nh` — a single POSIX shell script
that replaces all 25+ separate `nethunter-*` and VPN scripts.

## Architecture

Single shell script with:
1. Shared infrastructure (colors, get_token, api_get/api_post, JSON formatter helper)
2. Dispatch by `case "$1"` for category (system/network/vpn/agent/log/device/api/desktop/fix/apps)
3. Each subcommand is a shell function
4. Python3 is used for JSON formatting (piped through `python3 -c '...'`)
5. Backward compat: when invoked via symlink (e.g. `nethunter-battery-status`), detect via `basename "$0"` and dispatch accordingly

## API base URL

```
API_URL="http://127.0.0.1:1337"
```

## Token Reading (same as existing vpn-cli)

```sh
get_token() {
    TOKEN=$(cat /data/data/com.linux_core/shared_prefs/api_security.xml 2>/dev/null | \
        grep 'name="auth_token"' | sed 's/.*value="\([^"]*\)".*/\1/' | head -n 1)
    [ -z "$TOKEN" ] && TOKEN=$(cat /data/data/com.linux_core/shared_prefs/api_security.xml 2>/dev/null | \
        grep 'name="auth_token"' | sed "s/.*value='\([^']*\)'.*/\1/" | head -n 1)
    echo "$TOKEN"
}

api_get() { curl -s -H "Authorization: Bearer $(get_token)" "$API_URL$1"; }
api_post() { curl -s -X POST -H "Authorization: Bearer $(get_token)" -d "$2" "$API_URL$1"; }
api_post_bin() { echo "$2" | curl -s -X POST --data-binary @- -H "Authorization: Bearer $(get_token)" "$API_URL$1"; }
```

## Color Constants (from existing vpn-cli)

```sh
RST='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'
RED='\033[1;31m'; GREEN='\033[1;32m'; YELLOW='\033[1;33m'
BLUE='\033[1;34m'; MAGENTA='\033[1;35m'; CYAN='\033[1;36m'; WHITE='\033[1;37m'
BG_RED='\033[41m'; BG_GREEN='\033[42m'; BG_BLUE='\033[44m'
GRAY='\033[0;90m'
```

## All Category Dispatch Functions

### system

| Subcommand | Endpoint | Python Formatter Logic |
|---|---|---|
| `battery` | `GET /battery` | Show % with bar, status, health, temp, voltage |
| `volume [N]` | `GET/POST /volume` | Show `cur/max [████░░]` or set volume |
| `torch on\|off` | `POST /torch` | Show `🔦 ON` or `🌙 OFF` |
| `vibrate [ms]` | `POST /vibrate` | Show `📳  Vibration: N ms` |
| `toast <msg>` | `POST /toast` (binary body) | Show `✔ Toast sent` |
| `clipboard get\|set [text]` | `GET/POST /clipboard` | Show first 200 chars, `--raw` for plain |
| `notification -t T -c C` | `POST /notification` (JSON `{title,content}`) | Show `🔔 Notification posted: T` |
| `speech` | `GET /voice_input` | Show `🎙️ Voice: recognized text` |

### network

| Subcommand | Endpoint | Python Formatter Logic |
|---|---|---|
| `wifi [on\|off\|scan\|connect <ssid>]` | `GET/POST /wifi` | Show connection info, or toggle, or scan list, or connect |
| `cell` | `GET /cellinfo` | Show carrier, network type, signal, towers |
| `location` | `GET /location` | Show lat/lng, accuracy, provider, maps URI |
| `map` | `GET /map` then exec terminalmap | Show location then launch TerminalMap |
| `ifconfig [interface]` | `POST /shell` with `ip addr show` | Show network interfaces via Android API |

### vpn

| Subcommand | Endpoint |
|---|---|
| `status\|start\|stop` | `GET/POST /vpn`, `/vpn/start`, `/vpn/stop` |
| `on [cmd]\|off [cmd]` | `POST /vpn/start\|stop` (optional cmd mode) |
| `logs [-f] [-n N] [-g P] [json\|legacy]` | `GET /vpn/mitm/logs` |
| `mitm on\|off\|status\|ca` | `POST/GET /vpn/mitm`, `GET /vpn/mitm/ca` |
| `bypass <cmd>` | Set proxy env vars to 127.0.0.1:13339, exec cmd |
| `ignore on\|off\|status` | `GET/POST /vpn/ignore?session_id=...` |
| `sni-fallback get\|set\|clear` | `GET/POST /vpn/mitm/sni-fallback` |

The VPN dispatch is the biggest section. Reuse formatting from the existing `vpn-cli` (format_pretty_logs, format_legacy_logs, fetch_mitm_logs, banner, separator functions).

### agent

| Subcommand | Action |
|---|---|
| `config --provider X --key Y [--model Z]` | Write JSON config file |
| `start\|stop\|status` | Manage nethunter_agent.py daemon |
| `ask <prompt...>` | POST /agent/query, show response |
| `chat` | Interactive chat loop via nethunter_agent.py |

### log

| Subcommand | Action |
|---|---|
| `[-n N] [-g P] [lines]` | `GET /app/logs?limit=N` — colorized Python formatter |
| `set lvl 1-5` | `POST /app/logs/level` |

### device

| Subcommand | Endpoint |
|---|---|
| `admin status\|request\|lock` | `GET/POST /device/admin`, `POST /device/lock` |
| `battery-optimize status\|request` | `GET/POST /battery/optimize` |
| `accessibility` | `GET /accessibility/hierarchy` |

### api

| Subcommand | Endpoint |
|---|---|
| `share on\|off\|status` | `GET/POST /api/share` |

### desktop

| Subcommand | Action |
|---|---|
| `start\|stop\|status` | Manage VNC + noVNC (same logic as nethunter-desktop) |

### fix

| Subcommand | Action |
|---|---|
| `pkg <name>` | Mock postinst (symlink to /bin/true), run dpkg --configure -a |
| `auto` | Parse dpkg status, detect + fix all half-configured packages |

### apps

| Subcommand | Endpoint |
|---|---|
| `usage` | `GET /apps/usage` |

### help + list

- `nh help [category]` — show command tree
- `nh list` — flat list of all commands

## Backward Compat Dispatch

When invoked as `nethunter-battery-status` (via symlink), map:

```
nethunter-battery-status     → nh system battery "$@"
nethunter-toast              → nh system toast "$@"
...
vpn-cli                      → nh vpn "$@"
vpn-on                       → nh vpn on "$@"
...
```

## Format

Write to: `/root/kali_core_emulator/app/src/main/assets/nh`

The file must:
- Be valid POSIX shell (/bin/sh)
- Have no external dependencies beyond: curl, python3, grep, sed
- Use `python3 -c '...'` for JSON formatting (keep it compact)
- Include all formatting from existing scripts (battery bars, signal bars, color coding)

## Output

Write the complete file and verify it's executable:

```bash
chmod +x /root/kali_core_emulator/app/src/main/assets/nh
file /root/kali_core_emulator/app/src/main/assets/nh
```

Report:
- File size
- Number of lines
- Number of shell functions defined
- All category dispatchers present (system, network, vpn, agent, log, device, api, desktop, fix, apps, help)
