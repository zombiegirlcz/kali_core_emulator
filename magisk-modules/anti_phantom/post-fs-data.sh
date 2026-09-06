#!/system/bin/sh
# Execute early just in case
/system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
/system/bin/settings put global max_phantom_processes 2147483647 2>/dev/null || true
