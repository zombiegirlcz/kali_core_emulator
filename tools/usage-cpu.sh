#!/bin/sh
# usage-cpu.sh — test funkčnosti "CPU aplikací" (nh cpu apps/appmon/app + cpuctl).
#
# Spouštěj V GUESTU (Kali/Parrot pod prootem), kde jsou k dispozici `nh`,
# `sudo` (su_daemon) a přes bind `/system/bin/cpuctl`. Funguje i bez rootu —
# měřicí část pak jede přes uid 2000 (`ashell adb`, dumpsys cpuinfo).
#
# Testuje se na aplikaci, která je v telefonu VŽDY: launcher (domovská
# obrazovka, HOME intent). Když se nepodaří zjistit, vezme se první běžící
# aplikace, kterou monitor nahlásí.
#
#   sh tools/usage-cpu.sh            # autodetekce root/non-root
#   sh tools/usage-cpu.sh <balíček>  # test na konkrétní aplikaci
#
# Exit 0 = vše prošlo, 1 = něco selhalo, 2 = žádný zdroj dat (root ani uid 2000).

set -u

RST='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'
RED='\033[1;31m'; GRN='\033[1;32m'; YEL='\033[1;33m'; CYA='\033[1;36m'

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  ${GRN}✓${RST} %s\n" "$1"; }
bad()  { FAIL=$((FAIL+1)); printf "  ${RED}✗${RST} %s\n" "$1"; }
info() { printf "  ${DIM}%s${RST}\n" "$1"; }
head_() { printf "\n${BOLD}${CYA}%s${RST}\n" "$1"; }

CPUCTL=/system/bin/cpuctl
CPU_STATE_DIR="${FILES_DIR:-/data/user/0/com.linux_core/files}/nh/cpu"
TAB=$(printf '\t')

# ── Privilegovaná cesta: sudo (su_daemon) nebo su -c ────────────────────────
PRIV=""
if command -v sudo >/dev/null 2>&1 && sudo true >/dev/null 2>&1; then
    PRIV="sudo sh -c"
elif command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
    PRIV="su -c"
fi
priv() { [ -n "$PRIV" ] && $PRIV "$1" 2>/dev/null; }

# ── Root daemon (nh_cpuctl) běží? ───────────────────────────────────────────
root_daemon_up() {
    [ -f "$CPU_STATE_DIR/cpuctld" ] || return 1
    _p=""; _t=""
    read -r _p _t < "$CPU_STATE_DIR/cpuctld" 2>/dev/null || return 1
    [ -n "$_t" ] || return 1
    [ $(( $(date +%s) - _t )) -lt 15 ]
}

# ── Non-root měření (uid 2000, dumpsys) dostupné? ───────────────────────────
nonroot_up() {
    command -v ashell >/dev/null 2>&1 || return 1
    ashell adb dumpsys cpuinfo 2>/dev/null | grep -q '/'
}

# ── Detekce režimu ──────────────────────────────────────────────────────────
# Root režim má 4 podmínky; každou hlásíme zvlášť, ať je vidět, KTERÁ chybí
# (dřív jedna souhrnná podmínka tiše spadla do non-root / „žádný zdroj").
head_ "1) Prostředí a zdroj dat"
MODE="none"
ROOT_OK=1
if [ -n "$PRIV" ]; then
    ok "privilegovaná cesta: $PRIV"
else
    ROOT_OK=0; info "sudo/su nedostupné (root režim vypnutý)"
fi
if [ "$ROOT_OK" = 1 ]; then
    if priv "test -x $CPUCTL"; then
        ok "$CPUCTL existuje (Magisk modul nh_cpuctl nainstalovaný)"
    else
        ROOT_OK=0; bad "$CPUCTL chybí — nainstaluj Magisk modul nh_cpuctl (+ restart)"
    fi
fi
if [ "$ROOT_OK" = 1 ]; then
    CTL_VER=$(priv "$CPUCTL version" | sed -n 's/^cpuctl //p' | head -n1)
    # build před příkazem `version`, který už apps umí → podle nápovědy
    [ -z "$CTL_VER" ] && priv "$CPUCTL 2>&1" | grep -q 'app-pin' && CTL_VER="1.0+apps"
    if [ -n "$CTL_VER" ]; then
        ok "cpuctl $CTL_VER (umí apps/app-pin)"
    else
        ROOT_OK=0
        bad "cpuctl je stará binárka v1.0 (bez apps/app-pin/version)"
        info "přeflashuj nh_cpuctl-v1.1.zip (python3 magisk-modules/magiskb.py nh_cpuctl) a restartuj"
    fi
fi
if [ "$ROOT_OK" = 1 ]; then
    if root_daemon_up; then
        ok "nh_cpuctl daemon běží (heartbeat $CPU_STATE_DIR/cpuctld)"
    else
        ROOT_OK=0; bad "nh_cpuctl daemon neběží (chybí/starý heartbeat $CPU_STATE_DIR/cpuctld)"
        [ -d "$CPU_STATE_DIR" ] || info "$CPU_STATE_DIR v guestu není vidět — zapni Root Bridge → App Data (bind_app)"
    fi
fi

if [ "$ROOT_OK" = 1 ]; then
    MODE="root"
    ok "root režim: měření i pinování"
elif nonroot_up; then
    MODE="nonroot"
    ok "non-root režim: uid 2000 (ashell adb dumpsys cpuinfo) — jen měření"
else
    bad "žádný zdroj dat o CPU cizích aplikací"
    info "root:     nainstaluj Magisk modul nh_cpuctl v1.1"
    info "non-root: spusť 'ashell adb start' (jednou spáruj wireless debugging)"
    exit 2
fi

# ── Jednotné čtení app CPU listu (pkg<TAB>cpu[<TAB>uid<TAB>mask]) ────────────
apps_list() {
    if [ "$MODE" = root ]; then
        priv "$CPUCTL apps 0" | awk 'NR>1'
    else
        ashell adb dumpsys cpuinfo 2>/dev/null | awk '
            { p=$1; if (p !~ /%/) next; sub(/%.*/,"",p); sub(/^\+/,"",p);
              if (p+0<=0) next; t=$2; if (t !~ /\//) next;
              n=t; sub(/^[0-9]+\//,"",n); sub(/:.*/,"",n);
              if (n !~ /\./) next; printf "%s\t%s\t-\t-\n", n, p }'
    fi
}

# ── 2) Měření ───────────────────────────────────────────────────────────────
head_ "2) Měření CPU aplikací (nh cpu apps)"
LIST=$(apps_list)
CNT=$(printf '%s\n' "$LIST" | grep -c . || true)
if [ "${CNT:-0}" -ge 1 ]; then
    ok "monitor vrátil $CNT aplikací"
    printf '%s\n' "$LIST" | sort -t"$TAB" -k2 -nr | head -3 \
        | awk -F"$TAB" '{printf "      %-38s %5.1f%%\n",$1,$2}'
else
    bad "monitor nevrátil žádná data"
fi

# ── 3) Výběr vždy přítomné aplikace ─────────────────────────────────────────
head_ "3) Cílová aplikace"
TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    # launcher = domovská obrazovka, vždy přítomná a běžící
    if [ -n "$PRIV" ]; then
        TARGET=$(priv "cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME" \
            | sed -n 's/.*packageName=\([a-zA-Z0-9._]*\).*/\1/p' | head -1)
    fi
    if [ -z "$TARGET" ]; then
        TARGET=$(printf '%s\n' "$LIST" | sort -t"$TAB" -k2 -nr | awk -F"$TAB" 'NR==1{print $1}')
        [ -n "$TARGET" ] && info "launcher nezjištěn — beru nejvytíženější běžící aplikaci"
    else
        info "launcher (HOME) = vždy přítomná aplikace"
    fi
fi
if [ -n "$TARGET" ]; then
    ok "cíl: $TARGET"
else
    bad "nepodařilo se vybrat cílovou aplikaci"
    TARGET=""
fi

# ── 4) Přidělení jader (jen root) ───────────────────────────────────────────
head_ "4) Přidělení jader (nh cpu app / cpuctl app-pin)"
mask_of() {  # <pkg> -> hex maska z cpuctl apps
    priv "$CPUCTL apps 0" | awk -F"$TAB" -v p="$1" '$1==p{print $4; exit}'
}
if [ "$MODE" != root ]; then
    info "přeskočeno — přidělení jader je root-only (kernelový CAP_SYS_NICE)"
    info "non-root umí jen měřit; k pinování je potřeba Magisk nh_cpuctl"
elif [ -z "$TARGET" ]; then
    bad "není cílová aplikace, pin nelze otestovat"
else
    # úklid: ať cíl vždy skončí odepnutý, i při přerušení
    trap 'priv "$CPUCTL app-pin $TARGET off" >/dev/null 2>&1' EXIT INT TERM
    OUT=$(priv "$CPUCTL app-pin $TARGET 1")   # maska 1 = jen jádro 0
    if printf '%s' "$OUT" | grep -q 'mask=1'; then
        ok "app-pin $TARGET → jádro 0 (mask=1): $OUT"
    else
        bad "app-pin selhal: ${OUT:-<žádný výstup>}"
    fi
    sleep 1
    M=$(mask_of "$TARGET")
    if [ "$M" = 1 ]; then
        ok "cpuctl apps potvrzuje masku aplikace = 1 (pin drží)"
    else
        info "maska po pinu = ${M:-?} (aplikace možná zrovna neběží nebo se restartovala)"
    fi
    OFF=$(priv "$CPUCTL app-pin $TARGET off")
    if printf '%s' "$OFF" | grep -qi 'restored'; then
        ok "app-pin $TARGET off → vráceno na všechna jádra: $OFF"
    else
        bad "odepnutí selhalo: ${OFF:-<žádný výstup>}"
    fi
    M2=$(mask_of "$TARGET")
    info "maska po off = ${M2:-?} (mělo by být všechna jádra)"
    trap - EXIT INT TERM
fi

# ── Souhrn ──────────────────────────────────────────────────────────────────
head_ "Souhrn"
printf "  režim: ${BOLD}%s${RST}   ${GRN}PASS=%d${RST}  " "$MODE" "$PASS"
if [ "$FAIL" -gt 0 ]; then
    printf "${RED}FAIL=%d${RST}\n" "$FAIL"
    exit 1
fi
printf "${DIM}FAIL=0${RST}\n"
printf "  ${DIM}Tip: živý graf → nh cpu appmon${RST}\n"
exit 0
