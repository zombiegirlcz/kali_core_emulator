#!/system/bin/sh
# NetHunter Keepalive - late_start service script
# Applies AOSP-level background-kill exemptions for com.linux_core.
# Re-applied on every boot since some of these can reset on OTA/app reinstall.

PKG="com.linux_core"
LOG_TAG="nethunter_keepalive"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
sleep 10

log() { /system/bin/log -t "$LOG_TAG" "$1"; }

# Doze / App Standby whitelist - exempts from deep-sleep network/CPU restriction
dumpsys deviceidle whitelist +$PKG 2>&1 | while read -r l; do log "$l"; done
am set-standby-bucket $PKG active 2>&1 | while read -r l; do log "$l"; done

# Background execution + foreground-start + wakelock permissions
cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>&1 | while read -r l; do log "$l"; done
cmd appops set $PKG RUN_IN_BACKGROUND allow 2>&1 | while read -r l; do log "$l"; done
cmd appops set $PKG START_FOREGROUND allow 2>&1 | while read -r l; do log "$l"; done
cmd appops set $PKG WAKE_LOCK allow 2>&1 | while read -r l; do log "$l"; done

log "Applied AOSP background-kill exemptions for $PKG"

# MIUI PowerKeeper (autostart / battery saver) sits ABOVE this layer and is
# NOT reliably scriptable across MIUI 14 builds (DB/provider schema differs
# per ROM revision). Deliberately not touched here - see NOTES.md.
