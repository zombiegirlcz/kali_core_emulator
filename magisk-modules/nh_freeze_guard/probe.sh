#!/system/bin/sh
# nh_freeze_guard probe - vcasne zachyceni I/O stallu (UFS / f2fs checkpoint).
#
# Precursor (incidenty 2026-09-30 a 2026-10-01): desitky sekund pred tim, nez
# se cokoli projevi na displeji nebo v ANR, roste I/O tlak (ActivityManager:
# 70 % iowait v 28s okne, f2fs_ckpt zablokovany, kworker 56 % kernel).
# Sonda proto meri okamzity podil casu stallu z PSI `total=` (ne avg10, ten
# reaguje pomalu) a pri prekroceni prahu sama prepne do BURST rezimu:
# vzorky kazdych 200 ms + seznam D-state uloh s wchan + kernel stacky + dmesg.
#
# Kam se zapisuje (vse bez sahnuti na /data, ktere pri stallu visi):
#   $RUNDIR/probe.log  tmpfs v /dev = RAM; prezije stall, ktery se sam zotavi
#   /dev/pmsg0         pstore; prezije i watchdog reboot ->
#                      po bootu cist /sys/fs/pstore/pmsg-ramoops-0
#   UDP $HOST:$PORT    volitelne, jen kdyz je HOST nastaveny
#
# Interpret: STATICKY Magisk busybox (ash) zkopirovany spolu s timhle
# skriptem do tmpfs ($RUNDIR) a spusteny s ASH_STANDALONE=1 (service.sh).
# 10-01 09:16 sonda pod mksh vypadla na 29,5 s: mksh + bionic libc jsou
# file-backed stranky z /system na UFS -> page fault pri stallu = zaseknuti.
# Staticka binarka v RAM zadnou stranku z UFS nepotrebuje a applety
# (date, dmesg, mkfifo, nc ...) bezi bez exec z /system.
#
# Pravidla pro hot path (aby sonda bezela i behem stallu):
#   - zadny exec: cas z /proc/uptime, sleep = `read -t` na FIFO v tmpfs,
#     vystup pres builtin `printf`
#   - zadne here-docy (shell je muze zapisovat do docasneho souboru)
#   - ash ma 64bit aritmetiku, ale uvodni 0 = osmickova soustava ->
#     desetinne casti se prevadi trikem `1$f - 100`
#   - cela smycka je jeden slozeny prikaz -> shell po startu ze skriptu
#     nic necte
#
# Konfigurace (volitelna): /data/adb/nh_probe.conf
#   HOST=                 UDP cil (prazdne = jen lokalne)
#   PORT=9999
#   INTERVAL=1            baseline perioda [s]
#   BURST_INTERVAL=0.2    perioda v burstu [s]
#   BURST_SECS=30         jak dlouho burst trva po poslednim prekroceni prahu
#   TRIG_PCT=50           prah: % casu, kdy vsechny ulohy cekaly na I/O (PSI full)
#   TRIG_N=3              kolik vzorku po sobe musi byt nad prahem
#   TRIG_BLOCKED=8        nebo: tolik uloh v D-state (procs_blocked) najednou
#   RUNDIR=/dev/nh_probe
#   RING_LINES=20000      rotace probe.log -> probe.log.1
#   PMSG=1                0 = nezapisovat do pstore

CONF=/data/adb/nh_probe.conf
[ -f "$CONF" ] && . "$CONF"
: "${PORT:=9999}" "${INTERVAL:=1}" "${BURST_INTERVAL:=0.2}" "${BURST_SECS:=30}"
: "${TRIG_PCT:=50}" "${TRIG_N:=3}" "${TRIG_BLOCKED:=8}"
: "${RUNDIR:=/dev/nh_probe}" "${RING_LINES:=20000}"
: "${PMSG:=1}" "${TAG:=nh}"

trap '' PIPE
mkdir -p "$RUNDIR" || exit 1
LOG=$RUNDIR/probe.log
echo $$ > "$RUNDIR/pid"

rm -f "$RUNDIR/tick"; mkfifo "$RUNDIR/tick" || exit 1
exec 7<>"$RUNDIR/tick"
exec 4>>"$LOG"
LINES=0

# Pozn.: selhani presmerovani u `exec` (special builtin) ukonci shell,
# proto se zapisovatelnost overuje predem.
PMSG_ON=0
if [ "$PMSG" = 1 ] && [ -w /dev/pmsg0 ]; then exec 6>/dev/pmsg0; PMSG_ON=1; fi
PMSG_NOW=0

# Sit je volitelna a NIKDY se neobnovuje behem burstu: exec `nc` i otevreni
# FIFO pro zapis (ceka na ctenare) by mohly pri stallu zablokovat smycku.
NET_ON=0; NCPID=; NET_LAST=0
net_start() {
    [ -n "$NCPID" ] && kill "$NCPID" 2>/dev/null
    rm -f "$RUNDIR/net"; mkfifo "$RUNDIR/net" || return
    nc -u "$HOST" "$PORT" < "$RUNDIR/net" > /dev/null 2>&1 &
    NCPID=$!
    exec 5>"$RUNDIR/net"
    NET_ON=1
}
[ -n "$HOST" ] && net_start

emit() {
    printf '%s\n' "$1" >&4
    LINES=$((LINES + 1))
    [ "$PMSG_NOW" = 1 ] && [ "$PMSG_ON" = 1 ] && printf '%s\n' "$1" >&6
    if [ "$NET_ON" = 1 ] && ! printf '%s\n' "$1" >&5 2>/dev/null; then
        NET_ON=0
    fi
}

# "0.2" / "1" / "1.5" -> setiny sekundy
to_cs() {
    case "$1" in
        *.*) w=${1%.*}; f=${1#*.}00; f=${f%"${f#??}"} ;;
        *)   w=$1; f=00 ;;
    esac
    R=$(( ${w:-0} * 100 + 1$f - 100 ))
}

# Spanek bez exec: `read -t` na FIFO, do ktereho nikdo nepise. Signal
# (napr. SIGCHLD od podprocesu na pozadi) read prerusi predcasne, proto
# se dospava do deadlinu podle /proc/uptime.
nap() {
    read -r u_ _ < /proc/uptime
    dl_=$(( ${u_%.*} * 100 + 1${u_#*.} - 100 + $1 )); rem_=$1
    while [ $rem_ -gt 0 ]; do
        c_=$((rem_ % 100)); [ $c_ -lt 10 ] && c_=0$c_
        read -t "$((rem_ / 100)).$c_" -u7 _
        read -r u_ _ < /proc/uptime
        rem_=$(( dl_ - (${u_%.*} * 100 + 1${u_#*.} - 100) ))
    done
}

UP=0; UPCS=0
read_up() {
    read -r UP _ < /proc/uptime
    UPCS=$(( ${UP%.*} * 100 + 1${UP#*.} - 100 ))
}

clock() { emit "$TAG clock up=$UP wall=$(date +%Y-%m-%dT%H:%M:%S%z)"; }

# PSI total= (us) -> globalni vars <prefix>_some / <prefix>_full
psi() {
    { read -r _ _ _ _ a; read -r _ _ _ _ b; } < "/proc/pressure/$1"
    eval "${1}_some=\${a#total=}; ${1}_full=\${b#total=}"
}

# /proc/stat: iowait jiffies, pocet online CPU, procs_blocked.
# Celkove jiffies za interval = DCS * NCPU (USER_HZ=100 = setiny sekundy).
STAT_IOW=0; NCPU=1; BLOCKED=0
read_stat() {
    nc_=0
    while read -r k a _ _ _ w _; do
        case "$k" in
            cpu) STAT_IOW=$w ;;
            cpu[0-9]*) nc_=$((nc_ + 1)) ;;
            procs_blocked) BLOCKED=$a; break ;;
        esac
    done < /proc/stat
    [ $nc_ -gt 0 ] && NCPU=$nc_
}

F2FS_PIDS=""
find_f2fs() {
    for d in /proc/[0-9]*; do
        read -r c 2>/dev/null < "$d/comm" || continue
        case "$c" in f2fs_ckpt*|f2fs_gc*|f2fs_discard*) F2FS_PIDS="$F2FS_PIDS ${d#/proc/}";; esac
    done
}

# ulohy v D-state (cekaji neprerusitelne, typicky na I/O) + kde v kernelu stoji
DPIDS=""
# Pruchod /proc trva ~2 s, proto jen kdyz procs_blocked > 0 a konci, jakmile
# najde tolik uloh, kolik jich kernel hlasi.
dstate() {
    n=0; out=""; DPIDS=""
    if [ "$BLOCKED" -le 0 ]; then emit "$TAG dstate up=$UP n=0"; return; fi
    for d in /proc/[0-9]*; do
        read -r line 2>/dev/null < "$d/stat" || continue
        s=${line##*") "}; s=${s%% *}
        [ "$s" = D ] || continue
        c=${line#*"("}; c=${c%")"*}
        w=""; read -r w 2>/dev/null < "$d/wchan"
        out="$out ${d#/proc/}:$c:$w"
        DPIDS="$DPIDS ${d#/proc/}"
        n=$((n + 1)); { [ $n -ge 40 ] || [ $n -ge "$BLOCKED" ]; } && break
    done
    emit "$TAG dstate up=$UP n=$n$out"
}

stacks() {
    k=0
    for p in $F2FS_PIDS $DPIDS; do
        c=""; read -r c 2>/dev/null < "/proc/$p/comm" || continue
        st=""
        while read -r l; do st="$st<${l#*"] "}"; done 2>/dev/null < "/proc/$p/stack"
        emit "$TAG stack up=$UP pid=$p comm=$c $st"
        k=$((k + 1)); [ $k -ge 10 ] && break
    done
}

kmsg() {
    km=$(dmesg 2>/dev/null | tail -n "$1")
    oifs=$IFS; IFS='
'
    set -f
    for l in $km; do
        case "$l" in
            *Alarmtimer*|*QG-K*|*ibat_ua*|*USBPD*|*healthd*|*avc:*|*GTP-*|*aw8624*|*wlan:*) continue ;;
        esac
        emit "$TAG kmsg $l"
    done
    set +f; IFS=$oifs
}

DSK_INF=""; DSK_TICKS=""
read_disk() {
    while read -r _ _ name _ _ _ _ _ _ _ _ f12 f13 _; do
        if [ "$name" = sda ]; then DSK_INF=$f12; DSK_TICKS=$f13; break; fi
    done < /proc/diskstats
}

MEMAV=""
read_mem() {
    while read -r k v _; do
        case "$k" in MemAvailable:) MEMAV=$v; break ;; esac
    done < /proc/meminfo
}

# Nastaveni UFS linku (experiment hibern8/clkgate, viz AGENTS.md §13).
# JEN atributy, ktere ovladac vraci z promennych v RAM hosta. NIKDY
# nedavat sem ufstw_*/ufshpb_* ani nic, co posila dotaz (query) do
# zarizeni: 2026-10-02 cteni ufstw_lu0/tw_enable behem host resetu
# skoncilo `Synchronous External Abort` v ufshcd_exec_dev_cmd -> kernel
# panic. Proto se taky NEvola pri TRIGGER/END, jen pri startu a v baseline.
UFS=/sys/devices/platform/soc/1d84000.ufshc
ufs_state() {
    o=""
    for kv in h8_en:hibern8_on_idle_enable h8_ms:hibern8_on_idle_delay_ms \
              cg_pwr:clkgate_delay_ms_pwr_save cg_perf:clkgate_delay_ms_perf; do
        v=?; read -r v 2>/dev/null < "$UFS/${kv#*:}"
        o="$o ${kv%%:*}=$v"
    done
    emit "$TAG ufs up=$UP$o"
}

# ---------------------------------------------------------------- start

find_f2fs
read_up; clock
SHN=?; read -r SHN < /proc/$$/comm
emit "$TAG start pid=$$ sh=$SHN host=${HOST:-none} trig_pct=$TRIG_PCT trig_n=$TRIG_N f2fs=$F2FS_PIDS pmsg=$PMSG_ON"
ufs_state

read_up; psi io; psi memory; psi cpu; read_stat
P_UPCS=$UPCS; P_IOF=$io_full; P_IOS=$io_some; P_MEMF=$memory_full
P_CPUS=$cpu_some; P_IOW=$STAT_IOW

BURST=0; BURST_UNTIL=0; HI=0; I=0
to_cs "$INTERVAL"; INT_CS=$R; to_cs "$BURST_INTERVAL"; BINT_CS=$R
nap $INT_CS

while :; do
    read_up; psi io; psi memory; psi cpu; read_stat; read_disk; read_mem
    read -r L1 _ _ PROCS _ < /proc/loadavg

    # PSI total je v us, DCS v setinach s -> % = dus / (DCS * 100)
    DCS=$((UPCS - P_UPCS)); [ $DCS -le 0 ] && DCS=1
    R=$((io_full - P_IOF)); IOF=$((R / (DCS * 100)))
    R=$((io_some - P_IOS)); IOS=$((R / (DCS * 100)))
    R=$((memory_full - P_MEMF)); MEMF=$((R / (DCS * 100)))
    R=$((cpu_some - P_CPUS)); CPUS=$((R / (DCS * 100)))
    R=$((STAT_IOW - P_IOW)); IOW=$((R * 100 / (DCS * NCPU)))
    P_UPCS=$UPCS; P_IOF=$io_full; P_IOS=$io_some; P_MEMF=$memory_full
    P_CPUS=$cpu_some; P_IOW=$STAT_IOW
    # PSI agreguje se zpozdenim -> delta muze presahnout interval
    [ $IOF -gt 100 ] && IOF=100; [ $IOS -gt 100 ] && IOS=100
    [ $MEMF -gt 100 ] && MEMF=100; [ $CPUS -gt 100 ] && CPUS=100

    if [ $IOF -ge "$TRIG_PCT" ] || [ $MEMF -ge "$TRIG_PCT" ] || [ $BLOCKED -ge "$TRIG_BLOCKED" ]; then
        HI=$((HI + 1))
    else
        HI=0
    fi

    if [ $BURST = 0 ] && [ $HI -ge "$TRIG_N" ]; then
        BURST=1; PMSG_NOW=1; I=0
        BURST_UNTIL=$((UPCS + BURST_SECS * 100))
        emit "$TAG TRIGGER up=$UP io_full=$IOF mem_full=$MEMF iowait=$IOW blocked=$BLOCKED"
        dstate; stacks
        # dmesg/date jen v pozadi, aby pripadne zaseknuti nezastavilo sondu.
        # Sysfs UFS ovladace se behem stallu NECTE (viz ufs_state).
        ( clock; kmsg 300 ) &
    elif [ $BURST = 1 ] && [ $HI -ge 1 ]; then
        BURST_UNTIL=$((UPCS + BURST_SECS * 100))
    fi

    if [ $BURST = 1 ]; then M=B; else M=N; fi
    [ $BURST = 0 ] && { [ $((I % 10)) = 0 ] && PMSG_NOW=1 || PMSG_NOW=0; }

    emit "$TAG up=$UP m=$M io_full=$IOF io_some=$IOS mem_full=$MEMF cpu_some=$CPUS iowait=$IOW blocked=$BLOCKED load=$L1 procs=$PROCS memav=$MEMAV dsk_inflight=$DSK_INF dsk_ioticks=$DSK_TICKS"

    I=$((I + 1))
    if [ $BURST = 1 ]; then
        [ $((I % 5)) = 0 ] && dstate
        [ $((I % 25)) = 0 ] && stacks
        if [ $UPCS -ge $BURST_UNTIL ]; then
            emit "$TAG END up=$UP"
            dstate; stacks
            ( kmsg 300; clock ) &
            BURST=0; PMSG_NOW=0; I=0
        fi
        nap $BINT_CS
    else
        if [ $((I % 600)) = 0 ]; then ( clock; ufs_state ) & fi
        nap $INT_CS
    fi

    if [ $BURST = 0 ] && [ $LINES -ge "$RING_LINES" ]; then
        exec 4>&-; mv -f "$LOG" "$LOG.1"; exec 4>>"$LOG"; LINES=0
    fi

    if [ $BURST = 0 ] && [ -n "$HOST" ] && [ $NET_ON = 0 ] && [ $((UPCS - NET_LAST)) -ge 1000 ]; then
        NET_LAST=$UPCS; net_start
    fi
done
