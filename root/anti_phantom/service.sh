#!/system/bin/sh
# Wait until Android boot is complete
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# Extra wait for ActivityManager & DeviceConfig services to be fully up
sleep 5

# Set max phantom processes to maximum integer value
/system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
/system/bin/device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
/system/bin/settings put global max_phantom_processes 2147483647 2>/dev/null || true

# Continuous background check loop (ensures settings/device_config don't get reset by OS)
(
    while true; do
        sleep 60
        CURRENT_VAL=$(/system/bin/device_config get activity_manager max_phantom_processes 2>/dev/null)
        if [ "$CURRENT_VAL" != "2147483647" ]; then
            /system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
            /system/bin/device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
            /system/bin/settings put global max_phantom_processes 2147483647 2>/dev/null || true
        fi
    done
) &
