#!/bin/sh
# nh_test.sh — NetHunter nh-vs-logcat correlation test (one pass).
#
# Spusti davku `nh` prikazu a paralelne zachyti logcat kliceovany na app UID.
# Primarne pres `adb logcat --uid=<appUID>`; kdyz adb nema zarizeni, fallback
# na host-endpoint GET http://127.0.0.1:1337/app/logs?limit=N (app musi bezet).
# Vsechno se zapise do ~/kali_core_emulator/test.log.
#
# Pouziti:  cd /root/kali_core_emulator && zsh tools/nh_test.sh
# Exit: 0 = batch probehl a log byl zapsan; 1 = host endpoint nedostupny.

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
# fallback z id: u0_a323 -> 10323
if [ -z "$APP_UID" ]; then
    APP_UID=$(id 2>/dev/null | sed -n 's/.*u0_a\([0-9][0-9]*\).*/1\1/p' | head -n1)
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

run device accessibility
run device tap 500 1000
run device click 'NetHunter'
run device longclick 'NetHunter'
run device swipe 500 1500 500 500 300
run device text 'nhtest'
run device scroll fwd
run device global recents
run device admin status
run device battery-optimize status
run system battery
run system volume
run system torch on
run system vibrate 100
run system toast 'nhtest'
run system clipboard read
run system notification 'nhtest'
run system speech 'nhtest'

# ── zastavit/vybrat logcat ────────────────────────────────
sleep 1
if [ -n "$LOGCAT_PID" ]; then
    kill "$LOGCAT_PID" 2>/dev/null
    wait "$LOGCAT_PID" 2>/dev/null
else
    curl -s -m 5 "$API/app/logs?limit=500" > "$RAW" 2>/dev/null
fi

# ── korelace ──────────────────────────────────────────────
# Pocitej jen nase endpointy (vynech /app/logs harness).
REQS=$(grep -E 'Request: (GET|POST) ' "$RAW" 2>/dev/null | grep -vc '/app/logs' || echo 0)
# Vsechny varianty uspechu: "EXECUTED ...", "EXECUTED: ...", "POSTED", "READ EXECUTED".
EXEC_OK=$(grep -cE 'EXECUTED|POSTED|READ EXECUTED' "$RAW" 2>/dev/null || echo 0)
# Chyby: ok=false z accessibility + speech permission + E/ radky z naseho serveru.
ERRS=$(grep -cE 'EXECUTED ok=false|Speech error|E/LocalApiServer' "$RAW" 2>/dev/null || echo 0)

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
