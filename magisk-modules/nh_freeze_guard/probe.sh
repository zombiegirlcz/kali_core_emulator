#!/system/bin/sh
# nh_freeze_probe - telemetrie pro diagnostiku fatalnich zamrznuti.
#
# Proc po siti: pri zamrznuti zarizeni ztraci pristup k ulozisti, takze
# logcat, /data/anr, tombstony ani dmesg se uz nikam neulozi a hard reboot
# je smaze. Jedina cesta, jak data dostat ven, je poslat je prubezne pryc.
#
# Proc UDP: fire-and-forget, neblokuje. TCP by se pri umirajicim I/O nebo
# siti zaseklo na write() a vzorky bychom ztratili prave v okamziku, ktery
# nas zajima nejvic. Ztracene datagramy nevadi - kazdy vzorek je samostatny.
#
# Proc bezi na hostu (ne v prootu): kazdy start prootu sam generuje stovky
# SELinux audit zaznamu (viz AGENTS.md, audit bourre) - monitor by zhorsoval
# presne to, co ma merit. Cte se jen /proc, forkuje se jen jedno `nc`.
#
# Konfigurace: /data/adb/nh_probe.conf
#   HOST=192.168.1.10     # kam posilat (povinne)
#   PORT=9999             # default 9999
#   INTERVAL=1            # sekundy mezi vzorky, default 1
#
# Rucni spusteni bez instalace modulu (z guesta):
#   ashell -c '/data/adb/nh_freeze_probe/probe.sh &'

CONF=/data/adb/nh_probe.conf
[ -f "$CONF" ] && . "$CONF"
: "${PORT:=9999}"
: "${INTERVAL:=1}"
: "${TAG:=nh}"

if [ -z "$HOST" ]; then
    echo "nh_probe: chybi HOST v $CONF" >&2
    exit 1
fi

# Jeden vzorek = jeden radek key=value. Ctou se jen /proc soubory,
# zadny fork (krome `nc`, ktery bezi jen jednou pro celou smycku).
sample() {
    now=$(date +%s)

    # PSI - tlak na CPU a I/O. Pri stallu uloziste jde io_full k 100.
    cpu10=""; ios10=""; iof10=""
    if [ -r /proc/pressure/cpu ]; then
        read -r _ a _ _ _ < /proc/pressure/cpu
        cpu10=${a#avg10=}
    fi
    if [ -r /proc/pressure/io ]; then
        { read -r _ a _ _ _; read -r _ b _ _ _; } < /proc/pressure/io
        ios10=${a#avg10=}
        iof10=${b#avg10=}
    fi

    read -r l1 _ _ procs _ < /proc/loadavg

    # MemAvailable je spolehlivejsi nez MemFree (zapocita reclaimovatelne).
    memav=""
    while read -r k v _; do
        case "$k" in MemAvailable:) memav=$v; break;; esac
    done < /proc/meminfo

    # diskstats pole 12 = I/O prave probihajici, 13 = io_ticks.
    # Rostouci inflight, ktery neklesa, = zaseknute uloziste.
    infl=""; iot=""
    while read -r _ _ name _ _ _ _ _ _ _ _ f12 f13 _; do
        if [ "$name" = "sda" ]; then infl=$f12; iot=$f13; break; fi
    done < /proc/diskstats

    printf '%s t=%s psi_cpu=%s psi_io_some=%s psi_io_full=%s load=%s procs=%s memav=%s dsk_inflight=%s dsk_ioticks=%s\n' \
        "$TAG" "$now" "$cpu10" "$ios10" "$iof10" "$l1" "$procs" "$memav" "$infl" "$iot"
}

# Kazdy 10. vzorek pribali i stav audit fronty - hlavni podezreli
# z drivejsich pádů (audit_backlog_limit=64 preteka, audit_log_start()
# je synchronni kernelova cesta sdilena vsemi procesy).
audit_sample() {
    d=$(dmesg 2>/dev/null | tail -400)
    bl=$(echo "$d" | grep -c 'audit_backlog=')
    lost=$(echo "$d" | grep -o 'audit_lost=[0-9]*' | tail -1)
    avc=$(echo "$d" | grep -c 'avc:')
    scmp=$(echo "$d" | grep -c 'type=1326')
    printf '%s t=%s audit_backlog_hits=%s %s avc_tail400=%s seccomp_tail400=%s\n' \
        "$TAG" "$(date +%s)" "$bl" "${lost:-audit_lost=0}" "$avc" "$scmp"
}

# Vnejsi smycka: kdyby `nc` umrelo (sit vypadne), po chvili to zkusi znovu.
while :; do
    {
        i=0
        while :; do
            sample
            i=$((i + 1))
            if [ "$i" -ge 10 ]; then audit_sample; i=0; fi
            sleep "$INTERVAL"
        done
    } | nc -u "$HOST" "$PORT" 2>/dev/null
    sleep 5
done
