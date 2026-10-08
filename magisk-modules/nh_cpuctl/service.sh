#!/system/bin/sh
# nh_cpuctl — late_start service script
# Waits for boot_completed, then starts cpuctl daemon (root, netlink proc connector).

LOG_TAG="nh_cpuctl"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
sleep 5

log() { /system/bin/log -t "$LOG_TAG" "$1"; }

if [ ! -x /system/bin/cpuctl ]; then
    log "cpuctl binary not found at /system/bin/cpuctl"
    exit 1
fi

# Kill any existing daemon
HEARTBEAT="/data/user/0/com.linux_core/files/nh/cpu/cpuctld"
if [ -f "$HEARTBEAT" ]; then
    OLD_PID=$(awk '{print $1}' "$HEARTBEAT" 2>/dev/null)
    # Heartbeat vlastní app UID → PID je nedůvěryhodný; zabít jen proces s comm=cpuctl
    # (comm cizího procesu útočník změnit nemůže; exe cesta je přes magic mount nespolehlivá).
    case "$OLD_PID" in ''|*[!0-9]*) OLD_PID= ;; esac
    if [ -n "$OLD_PID" ] && [ "$(cat "/proc/$OLD_PID/comm" 2>/dev/null)" = "cpuctl" ]; then
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
fi

/system/bin/cpuctl daemon
log "cpuctl daemon started"
