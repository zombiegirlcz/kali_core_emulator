#!/bin/sh
# nh_test.sh — NetHunter nh-vs-logcat correlation test (one pass, full CLI coverage).
#
# Spusti davku `nh` prikazu NAPRIC vsemi kategoriemi a paralelne zachyti logcat
# klicovany na app UID. Primarne `adb logcat --uid=<appUID>`; kdyz adb nema
# zarizeni, fallback na host-endpoint GET http://127.0.0.1:1337/app/logs?limit=N
# (app musi bezet, root neni potreba). Vsechno se zapise do ~/kali_core_emulator/test.log.
#
# Bezpecnost: pouzivaji se READ-ONLY / stavove prikazy. Destruktivni se vynechavaji:
#   distro kill/remove/backup/restore, fix auto/pkg/permission/git, usb claim/release/
#   permission/send/bulk/control/bridge/gadget, cpu on/off/pin/app, vpn on/off/start/stop,
#   zkill, float*, ashell (bez arg otevira okno), agent start/stop, api share on/off,
#   log set lvl (meni nastaveni), torch off (nechava se na uzivateli).
#
# Pouziti:  cd /root/kali_core_emulator && zsh tools/nh_test.sh
# Exit: 0 = batch probehl a log byl zapsan; 1 = host endpoint nedostupny a zaroven adb bez zarizeni.

set -u

# ── cesty ─────────────────────────────────────────────────
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$HOME/kali_core_emulator/test.log"
RAW="${TMPDIR:-/tmp}/nh_test_logcat.$$".raw
API="http://127.0.0.1:1337"
PKG="com.linux_core"

mkdir -p "$(dirname "$LOG")" 2>/dev/null
: > "$RAW"

# ── app UID ────────────────────────────────────────────────
APP_UID=""
if command -v dumpsys >/dev/null 2>&1; then
    APP_UID=$(dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n1)
fi
if [ -z "$APP_UID" ] && command -v cmd >/dev/null 2>&1; then
    APP_UID=$(cmd package list packages -U "$PKG" 2>/dev/null | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -n1)
fi
if [ -z "$APP_UID" ]; then
    APP_UID=$(id 2>/dev/null | sed -n 's/.*u0_a\([0-9][0-9]*\).*/\1/p' | head -n1)
    [ -n "$APP_UID" ] && APP_UID=$((10000 + APP_UID))
fi
[ -z "$APP_UID" ] && APP_UID=10323

# ── adb dostupnost ────────────────────────────────────────
ADB_OK=0
if command -v adb >/dev/null 2>&1; then
    if adb devices 2>/dev/null | grep -q 'device$'; then
        ADB_OK=1
    fi
fi

# ── host endpoint dostupnost ──────────────────────────────
HOST_OK=0
if command -v curl >/dev/null 2>&1; then
    code=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$API/app/logs?limit=1" 2>/dev/null)
    [ "$code" = "200" ] && HOST_OK=1
fi

# ── hlavicka logu ─────────────────────────────────────────
{
    echo "=========================================================="
    echo " nh_test.sh  $(date '+%Y-%m-%d %H:%M:%S')"
    echo " repo:     $REPO_DIR"
    echo " app pkg:  $PKG  uid=$APP_UID"
    echo " adb:      $([ $ADB_OK -eq 1 ] && echo 'dostupne' || echo 'nedostupne (fallback na host-endpoint)')"
    echo " host:     $API  $([ $HOST_OK -eq 1 ] && echo OK || echo NEDOSTUPNY)"
    echo "=========================================================="
} > "$LOG"

if [ "$HOST_OK" -eq 0 ] && [ "$ADB_OK" -eq 0 ]; then
    echo "LocalApiServer na 127.0.0.1:1337 nedostupny a adb nema zarizeni." | tee -a "$LOG"
    exit 1
fi

# ── background logcat ─────────────────────────────────────
LOGCAT_PID=""
if [ "$ADB_OK" -eq 1 ]; then
    adb logcat --uid="$APP_UID" -v time > "$RAW" 2>/dev/null &
    LOGCAT_PID=$!
fi

# ── davka nh prikazu (plain, dle REFERENCE.md) ────────────
run() {
    echo "--- nh $*" >> "$LOG"
    nh "$@" >> "$LOG" 2>&1
    echo >> "$LOG"
}

# ── device (automatizace UI) ──────────────────────────────
section() { echo >> "$LOG"; echo "########## $* ##########" >> "$LOG"; }

section "device"
run device admin status
run device battery-optimize status
run device accessibility
run device tap 500 1000
run device click 'CTRL'
run device longclick 'CTRL'
run device longclick 452 1390
run device swipe 500 1500 500 500 300
run device text 'nhtest'
run device scroll fwd
run device scroll back --gesture
run device global recents

section "system"
run system battery
run system volume
run system torch on
run system vibrate 100
run system toast 'nhtest'
run system clipboard get
run system notification 'nhtest'
run system speech 'nhtest'

section "network"
run network wifi
run network cell
run network location
run network map
run network ifconfig

section "vpn"
run vpn status
run vpn mitm status
run vpn mitm ca
run vpn logs
run vpn ai
run vpn sni-fallback

section "log"
run log -n 5

section "api"
run api share status

section "desktop"
run desktop status

section "apps"
run apps usage

section "usb"
run usb list

section "distro"
run distro list
run distro ps

section "cpu"
run cpu status

section "docs"
run docs

# ── zastavit/vybrat logcat ────────────────────────────────
sleep 1
if [ -n "$LOGCAT_PID" ]; then
    kill "$LOGCAT_PID" 2>/dev/null
    wait "$LOGCAT_PID" 2>/dev/null
else
    curl -s -m 5 "$API/app/logs?limit=1000" > "$RAW" 2>/dev/null
fi

# ── korelace ──────────────────────────────────────────────
# Pocitej jen nase endpointy (vynech /app/logs harness).
REQS=$(grep -E 'Request: (GET|POST) ' "$RAW" 2>/dev/null | grep -vc '/app/logs' || true)
# Vsechny varianty uspechu: "EXECUTED ...", "EXECUTED: ...", "POSTED", "READ EXECUTED".
EXEC_OK=$(grep -cE 'EXECUTED|POSTED|READ EXECUTED' "$RAW" 2>/dev/null || true)
# Chyby: ok=false z accessibility + speech permission + E/ radky z naseho serveru.
ERRS=$(grep -cE 'EXECUTED ok=false|Speech error|E/LocalApiServer' "$RAW" 2>/dev/null || true)
# grep -c při 0 shodách tiskne "0" A vrací 1 → "|| echo 0" by dal "0\n0"
REQS=${REQS:-0}; EXEC_OK=${EXEC_OK:-0}; ERRS=${ERRS:-0}

{
    echo "=========================================================="
    echo " LOGCAT CORRELATION  (raw: $RAW)"
    echo "=========================================================="
    echo "Request zachycen: $REQS"
    echo "EXECUTED (ok):    $EXEC_OK"
    echo "chyby/failed:     $ERRS"
    echo "----------------------------------------------------------"
    grep -E 'Request: |EXECUTED|POSTED|Speech error|DeviceAdmin|Battery' "$RAW" 2>/dev/null
    echo "=========================================================="
} >> "$LOG"

rm -f "$RAW"
echo "nh_test.sh: hotovo -> $LOG"
exit 0
