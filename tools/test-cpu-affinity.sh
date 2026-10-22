#!/bin/sh
# Test: `nh cpu pin|unpin|core` musí nastavit afinitu VŠECH VLÁKEN každého
# procesu v proot tree, ne jen hlavního vlákna.
#
# Proč: `taskset -p <maska> <pid>` mění afinitu jediného tasku (TID == PID).
# Pracovní vlákna, která vznikla za staré masky, si ji drží → po `nh cpu unpin`
# zůstalo 61 z 85 vláken na jednom jádru a vytížení toho jádra nekleslo,
# přitom `nh cpu status` (čte taky jen hlavní vlákno) hlásil "všechna jádra".
# Správně je `taskset -a -p` (toybox i util-linux ho umí, host i guest).
#
# Spouštět v proot session: ashell -c "sh tools/test-cpu-affinity.sh"
#   NH=…  cesta k nh (default: nh z PATH)
set -u

NH=${NH:-nh}
fails=0
pass() { printf '  [ok]   %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; fails=$((fails + 1)); }

TP=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/$$/status 2>/dev/null)
[ -n "$TP" ] && [ "$TP" != 0 ] || { echo "SKIP: mimo proot (není tracer)"; exit 0; }
command -v taskset >/dev/null 2>&1 || { echo "SKIP: taskset není"; exit 0; }
command -v python3 >/dev/null 2>&1 || { echo "SKIP: python3 (generátor vláken) není"; exit 0; }

CPU_DIR="${FILES_DIR:-/data/user/0/com.linux_core/files}/nh/cpu"

all_mask() {
    m=0
    for c in /sys/devices/system/cpu/cpu[0-9]*; do
        [ "$(cat "$c/online" 2>/dev/null || echo 1)" = "1" ] && m=$((m | (1 << ${c##*cpu})))
    done
    printf '%x' "$m"
}

# Masky všech vláken daného PID, po řádcích.
thread_masks() {
    for t in /proc/"$1"/task/*; do
        [ -d "$t" ] || continue
        taskset -p "${t##*/}" 2>/dev/null | sed 's/.*: //'
    done
}

# Vlákna PIDu, která NEMAJÍ očekávanou masku.
threads_off_mask() { thread_masks "$1" | grep -v "^$2$" | grep -c .; }

ALL=$(all_mask)
ONE_CORE=7
ONE=$(printf '%x' $((1 << ONE_CORE)))

# ── Původní stav si uložíme a na konci vrátíme ────────────────────────
ORIG_PIN=""
[ -f "$CPU_DIR/pin.$TP" ] && ORIG_PIN=$(cat "$CPU_DIR/pin.$TP" 2>/dev/null)

# Vícevláknový cíl: hlavní vlákno + 8 workerů, všechny jen spí.
python3 -c '
import threading, time
for _ in range(8):
    threading.Thread(target=time.sleep, args=(600,), daemon=True).start()
time.sleep(600)
' &
TARGET=$!
sleep 2
kill -0 "$TARGET" 2>/dev/null || { echo "SKIP: cíl nenaběhl"; exit 0; }
# Session vrátíme tam, kde byla: pinnutá na původní masku, nebo uvolněná.
cleanup() {
    kill "$TARGET" 2>/dev/null || true
    if [ -n "$ORIG_PIN" ]; then
        echo "$ORIG_PIN" > "$CPU_DIR/pin.$TP" 2>/dev/null || true
        "$NH" cpu pin "$ORIG_CORE" >/dev/null 2>&1 || true
    else
        "$NH" cpu unpin >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM
# z hex masky zpět na číslo jádra (jen pro obnovu jedno-jádrového pinu)
ORIG_CORE=$ONE_CORE
if [ -n "$ORIG_PIN" ]; then
    i=0
    while [ "$i" -lt 64 ]; do
        [ $((0x$ORIG_PIN >> i & 1)) -eq 1 ] && ORIG_CORE=$i && break
        i=$((i + 1))
    done
fi

nthreads=$(thread_masks "$TARGET" | grep -c .)
[ "$nthreads" -ge 5 ] || { echo "SKIP: cíl má jen $nthreads vláken"; exit 0; }
echo "cíl pid=$TARGET vláken=$nthreads  (1 jádro=$ONE, všechna=$ALL)"

echo
echo "── 1) nh cpu pin $ONE_CORE → všechna vlákna na cpu$ONE_CORE ──"
"$NH" cpu pin "$ONE_CORE" >/dev/null 2>&1
sleep 1
off=$(threads_off_mask "$TARGET" "$ONE")
if [ "$off" -eq 0 ]; then
    pass "všech $nthreads vláken má masku $ONE"
else
    fail "$off z $nthreads vláken NEMÁ masku $ONE (masky: $(thread_masks "$TARGET" | sort -u | tr '\n' ' '))"
fi

echo
echo "── 2) nh cpu unpin → všechna vlákna na všechna jádra ──"
"$NH" cpu unpin >/dev/null 2>&1
sleep 1
off=$(threads_off_mask "$TARGET" "$ALL")
if [ "$off" -eq 0 ]; then
    pass "všech $nthreads vláken má masku $ALL"
else
    fail "$off z $nthreads vláken NEMÁ masku $ALL (masky: $(thread_masks "$TARGET" | sort -u | tr '\n' ' '))"
fi

echo
echo "── 3) nh cpu status nesmí hlásit 'všechna jádra', když vlákna pinnutá jsou ──"
# Pinneme POUZE hlavní vlákno cíle (simulace staré chyby) a necháme status mluvit.
"$NH" cpu pin "$ONE_CORE" >/dev/null 2>&1
sleep 1
taskset -p "$ALL" "$TARGET" >/dev/null 2>&1   # jen hlavní vlákno zpět na všechna
st=$("$NH" cpu status 2>/dev/null | sed -n '/Tato session/,/^$/p')
workers_pinned=$(thread_masks "$TARGET" | grep -c "^$ONE$")
if [ "$workers_pinned" -gt 0 ] && printf '%s' "$st" | grep -q "vlákn"; then
    pass "status vlákna zmiňuje ($workers_pinned vláken na $ONE)"
else
    fail "status o pinnutých vláknech mlčí ($workers_pinned vláken na $ONE) → uživatel věří, že je uvolněno"
fi

echo
if [ "$fails" -eq 0 ]; then
    echo "VŠE OK"
else
    echo "SELHALO: $fails"
fi
exit "$fails"
