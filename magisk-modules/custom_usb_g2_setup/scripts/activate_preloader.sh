#!/system/bin/sh
# activate_preloader.sh — nastaví "preloader" režim g2.
#
# Označí g2 pro MediaTek Preloader (VCOM) scénář. Preloader se hlásí jako
# VID:PID 0e8d:2000/2001. Stejně jako brom jde jen o poznámku — funkce g2
# se nemění.

LOG_DIR="/data/adb/usb_g2"  # root-only (dříve /data/local/tmp = zapisovatelné uid 2000 → symlink útok na root zápisy)
mkdir -p "$LOG_DIR" && chmod 700 "$LOG_DIR" 2>/dev/null || true

echo "preloader" > "$LOG_DIR/usb_g2_mode"
echo "[$(date)] g2 režim = preloader" >> "$LOG_DIR/usb_g2_setup.log"
echo "g2 režim nastaven na: preloader"
exit 0