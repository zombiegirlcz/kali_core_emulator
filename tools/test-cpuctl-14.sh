#!/bin/sh
# Test: `cpuctl cores` (v1.4) — clustery jader detekované z cpuinfo_max_freq.
#
# Proč: UI dřív měl natvrdo "little (0-3)" / "big (4-7)", což na cílovém
# zařízení neplatí (reálně 0-5 little @1804 MHz, 6-7 big @2304 MHz). Clustery
# se proto musí odvodit z /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq,
# nikdy hardcoded.
#
# Spouštět na zařízení jako root (cpuctl je root-only binárka):
#   ashell -c "su -c 'sh <repo>/tools/test-cpuctl-14.sh'"
# CPUCTL=… cesta k binárce (default: cpuctl z PATH).
set -u

CPUCTL=${CPUCTL:-cpuctl}
fails=0
pass() { printf '  [ok]   %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; fails=$((fails + 1)); }

command -v "$CPUCTL" >/dev/null 2>&1 || CPUCTL=/data/adb/modules/nh_cpuctl/system/bin/cpuctl
[ -x "$CPUCTL" ] || { echo "SKIP: cpuctl binárka nenalezena"; exit 0; }

id -u 2>/dev/null | grep -qx 0 || { echo "SKIP: potřeba root"; exit 0; }

nproc_all() {
    n=$(nproc --all 2>/dev/null) && [ -n "$n" ] && { printf '%s' "$n"; return; }
    n=0
    for c in /sys/devices/system/cpu/cpu[0-9]*; do
        [ -d "$c" ] && n=$((n + 1))
    done
    printf '%s' "$n"
}

echo "── cpuctl version (očekáváno 1.4) ──"
ver=$("$CPUCTL" version 2>/dev/null)
case "$ver" in
    *1.4*) pass "version: $ver" ;;
    *) fail "version: '$ver' (očekáváno 1.4)" ;;
esac

echo
echo "── cpuctl cores ──"
out=$("$CPUCTL" cores) || { fail "cpuctl cores selhal (stará binárka?)"; echo; echo "SELHALO: $fails"; exit "$fails"; }

ncpu=$(nproc_all)
n=$(printf '%s\n' "$out" | grep -c .)
if [ "$n" -eq "$ncpu" ]; then
    pass "cores: $n řádků (= $ncpu jader)"
else
    fail "cores: $n řádků, jader $ncpu"
fi

# každý řádek 5 polí, cluster je číslo, max_khz > 0
if printf '%s\n' "$out" | awk -F'\t' 'NF!=5 || $2<=0 || $5!~/^-?[0-9]+$/ {exit 1}'; then
    pass "cores: tvar řádku (5 polí, max_khz>0, cluster číslo)"
else
    fail "cores: tvar řádku"
fi

# clustery vzestupně podle max_khz: nižší cluster id nesmí mít vyšší max_khz
if printf '%s\n' "$out" | sort -t"$(printf '\t')" -k5,5n -k2,2n |
    awk -F'\t' '{if ($5==p5 && $2<p2) exit 1; if ($5>p5 && $2<p2) exit 1; p5=$5; p2=$2}'; then
    pass "cores: clustery jsou vzestupně podle frekvence"
else
    fail "cores: clustery nejsou vzestupně podle frekvence"
fi

# jeden cluster je legální stav (homogenní SoC/emulátor) — nesmí to být chyba
nclusters=$(printf '%s\n' "$out" | awk -F'\t' '{print $5}' | sort -un | grep -v '^-1$' | wc -l)
pass "clusterů nalezeno: $nclusters (1 je legální stav)"

echo
printf '%s\n' "$out"

echo
if [ "$fails" -eq 0 ]; then
    echo "VŠE OK"
else
    echo "SELHALO: $fails"
fi
exit "$fails"
