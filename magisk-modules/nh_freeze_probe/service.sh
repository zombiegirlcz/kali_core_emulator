#!/system/bin/sh
# Spusti telemetrii po bootu. Bezi jako real root na hostu, mimo proot.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 5

MODDIR=${0%/*}
[ -f /data/adb/nh_probe.conf ] || exit 0   # bez konfigurace se nespousti
exec "$MODDIR/probe.sh" >/dev/null 2>&1 &
