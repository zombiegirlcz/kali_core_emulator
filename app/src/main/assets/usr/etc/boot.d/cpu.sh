# boot.d/cpu.sh — CPU pin plugin (bundled by default, viz nh_cpuctl root plugin
# pro cpuctl část). Sourcováno z `boot` (boot.d loader, Fáze 3,
# docs/plans/2026-09-26-plugin-system-design.md) — PŘESUNUTO beze změny
# z `boot` samotného, žádná úprava chování. `log`/`err`/`$FILES_DIR` musí být
# už definované v `boot`, než se tento fragment sourcuje (loader to zajišťuje
# umístěním až po jejich definici, před voláním `cpu_pin_apply` v main()).

# ─── CPU pin (NH_CPU_PIN=1: ikona CPU na kartě distra, `nh cpu`) ──
# Každý syscall, který proot zachytí, je ptrace výměna guest ↔ proot. Když
# běží na různých jádrech, čeká se na probuzení druhého jádra (~320 µs na
# syscall); na jednom velkém jádru ~70–100 µs (shell/apt/git 2–5× rychleji).
# Daň: celý guest jede na jednom jádru (make -j, john, xz -T0 → `nh cpu all`).
# Pin se nastaví na vlastní PID před exec, proot i guest ho zdědí. Stav je v
# $FILES_DIR/nh/cpu/pin.<pid> (maska); hlídač ho obnoví, když Android při
# změně cpusetu (appka do pozadí/popředí) affinity přepíše.
CPU_DIR="$FILES_DIR/nh/cpu"
TASKSET_BIN=/system/bin/taskset

# Nejrychlejší jádra (max cpuinfo_max_freq), s více sessions se střídají.
cpu_pick_core() {
    local best=0 list="" c f n i
    for c in /sys/devices/system/cpu/cpu[0-9]*; do
        f=$(cat "$c/cpufreq/cpuinfo_max_freq" 2>/dev/null) || continue
        [ "$(cat "$c/online" 2>/dev/null || echo 1)" = "1" ] || continue
        c=${c##*cpu}
        if [ "$f" -gt "$best" ]; then best=$f; list=$c
        elif [ "$f" -eq "$best" ]; then list="$list $c"; fi
    done
    [ -n "$list" ] || { echo 0; return; }
    set -- $list
    n=$(ls "$CPU_DIR"/pin.* 2>/dev/null | wc -l)
    i=$(( n % $# + 1 ))
    eval echo "\${$i}"
}

# Potomci PID (včetně něj), bez podstromů uvedených v $CPU_DIR/free.<root>.
cpu_tree() {
    local root=$1 all="" p pp line todo out free
    for p in /proc/[0-9]*; do
        { read -r line < "$p/stat"; } 2>/dev/null || continue
        line=${line##*) }
        set -- $line
        all="$all ${p#/proc/}:$2"
    done
    free=" "
    [ -f "$CPU_DIR/free.$root" ] && while read -r p; do free="$free$p "; done < "$CPU_DIR/free.$root"
    todo=$root out=""
    while [ -n "$todo" ]; do
        set -- $todo; p=$1; shift; todo="$*"
        case "$free" in *" $p "*) continue ;; esac
        out="$out $p"
        for pp in $all; do
            [ "${pp#*:}" = "$p" ] && todo="$todo ${pp%%:*}"
        done
    done
    echo $out
}

# CPU_ALL v prostředí procesu v guestu = programy, které mají jet na všech
# jádrech (make, cargo, john, xz, …). Slova oddělená mezerou/čárkou/řádkem;
# slovo s cestou (/…, ~/…) je soubor se seznamem, doslovné '$(cat SOUBOR)'
# (hodnota v jednoduchých uvozovkách) se čte stejně. Cesty jsou v guestu.
cpu_all_words() {  # <pid> <rootfs>
    local v w
    v=$(tr '\0' '\n' < "/proc/$1/environ" 2>/dev/null | sed -n 's/^CPU_ALL=//p' | head -n 1)
    [ -n "$v" ] || return 0
    v=$(echo "$v" | sed 's/\$(cat[[:space:]]*\([^)]*\))/\1/g' | tr ',;' '  ')
    for w in $v; do
        case "$w" in "~/"*) w="/root/${w#??}" ;; esac
        case "$w" in
            /*) [ -f "$2$w" ] && tr ',;' '  ' < "$2$w" ;;
            *)  echo "$w" ;;
        esac
    done
}

cpu_pin_watch() {
    # Hlídač dědí `set -e` z boot; v mksh by ho zabil první neúspěšný test
    # na konci smyčky (a hlídač by tiše zmizel). Tady se chyby řeší ručně.
    set +e
    local pid=$1 file="$CPU_DIR/pin.$1" mask cur t n=0 seen=" " rootfs all_mask comm argv w
    all_mask=0
    for t in /sys/devices/system/cpu/cpu[0-9]*; do all_mask=$(( all_mask | (1 << ${t##*cpu}) )); done
    all_mask=$(printf '%x' "$all_mask")
    while kill -0 "$pid" 2>/dev/null && [ -f "$file" ]; do
        # rootfs z příkazové řádky proot (-r <cesta>) kvůli souborům v CPU_ALL;
        # hned po startu je $pid ještě boot (exec proot proběhne až potom)
        [ -n "$rootfs" ] || rootfs=$(tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | sed -n '/^-r$/{n;p;q}')
        # nové procesy s CPU_ALL, jejichž jméno je na seznamu → všechna jádra
        for t in $(cpu_tree "$pid"); do
            case "$seen" in *" $t "*) continue ;; esac
            seen="$seen$t "
            [ "$t" = "$pid" ] && continue
            w=$(cpu_all_words "$t" "$rootfs")
            [ -n "$w" ] || continue
            { read -r comm < "/proc/$t/comm"; } 2>/dev/null || continue
            argv=$(tr '\0' '\n' < "/proc/$t/cmdline" 2>/dev/null | head -n 1); argv=${argv##*/}
            case " $(echo $w) " in
                *" $comm "*|*" $argv "*)
                    $TASKSET_BIN -p "$all_mask" "$t" >/dev/null 2>&1 && echo "$t" >> "$CPU_DIR/free.$pid"
                    ;;
            esac
        done
        # každých ~10 s: Android při změně cpusetu přepsal affinity → obnovit
        n=$(( n + 1 ))
        if [ $(( n % 5 )) -eq 0 ]; then
            { read -r mask < "$file"; } 2>/dev/null || mask=""
            cur=$($TASKSET_BIN -p "$pid" 2>/dev/null); cur=${cur##*: }
            if [ -n "$mask" ] && [ "$cur" != "$mask" ]; then
                for t in $(cpu_tree "$pid"); do $TASKSET_BIN -p "$mask" "$t" >/dev/null 2>&1; done
            fi
            seen=" "        # PIDy se recyklují
        fi
        sleep 2
    done
    rm -f "$file" "$CPU_DIR/free.$pid"
}

cpu_pin_isolate_host() {
    # Přesunout host procesy z proot jádra na zbylá jádra.
    # $1 = hex maska proot jádra
    local proot_mask=$1 all_mask avoid p comm
    all_mask=0
    for p in /sys/devices/system/cpu/cpu[0-9]*; do
        all_mask=$(( all_mask | (1 << ${p##*cpu}) ))
    done
    avoid=$(printf '%x' $(( all_mask & ~0x$proot_mask )))
    [ "$avoid" != "0" ] || return 0
    for p in /proc/[0-9]*/comm; do
        comm=$(cat "$p" 2>/dev/null) || continue
        case "$comm" in
            com.linux_core*|linux_core*|nethunter*|dropbear|sshd)
                local pid=${p#/proc/}; pid=${pid%/comm}
                $TASKSET_BIN -p "$avoid" "$pid" >/dev/null 2>&1 && \
                    log "CPU isolate: $comm ($pid) → 0x$avoid"
                ;;
        esac
    done
}

cpu_pin_apply() {
    [ "${NH_CPU_PIN:-0}" = "1" ] && [ -x "$TASKSET_BIN" ] || return 0
    local core mask
    mkdir -p "$CPU_DIR" 2>/dev/null || true
    # úklid po mrtvých sessions (if, ne `&&`: boot běží se `set -e` a mksh
    # skončí i na smyčce, jejíž poslední `[ … ] && …` neprojde)
    for f in "$CPU_DIR"/pin.*; do
        if [ -f "$f" ] && ! kill -0 "${f##*.}" 2>/dev/null; then
            rm -f "$f" "$CPU_DIR/free.${f##*.}"
        fi
    done
    core=${NH_CPU_PIN_CORE:-$(cpu_pick_core)}
    mask=$(printf '%x' $((1 << core)))
    if $TASKSET_BIN -p "$mask" $$ >/dev/null 2>&1; then
        echo "$mask" > "$CPU_DIR/pin.$$"
        log "CPU pin: cpu$core (mask $mask)"
        # isolate: přesunout host procesy (appku, agenta) z proot jádra
        cpu_pin_isolate_host "$mask"
        # Root daemon (cpuctld) má netlink proc connector a obnoví pin
        # okamžitě. Pokud běží (<15 s heartbeat), mksh hlídač je zbytečný.
        local _cpuctld_fresh=0
        if [ -f "$CPU_DIR/cpuctld" ]; then
            local _hb_pid _hb_ts _now_s
            read -r _hb_pid _hb_ts < "$CPU_DIR/cpuctld" 2>/dev/null || true
            _now_s=$(date +%s)
            if [ -n "$_hb_pid" ] && [ -n "$_hb_ts" ] && [ $(( _now_s - _hb_ts )) -lt 15 ]; then
                _cpuctld_fresh=1
                log "CPU pin: root daemon active (pid $_hb_pid), skipping mksh watcher"
            fi
        fi
        if [ "$_cpuctld_fresh" = "0" ]; then
            # Hlídač odpojený od tohoto procesu (po exec je $$ proot, dítě by
            # proot sklízel jako neznámý tracee) → dvojitý fork, rodič je init.
            ( cpu_pin_watch $$ </dev/null >/dev/null 2>&1 & )
        fi
    else
        err "CPU pin: taskset selhal, běžím bez pinu"
    fi
    return 0
}
