#!/system/bin/sh
# activate_normal.sh — nastaví "normal" režim g2.
#
# "Režim" je jen poznámka do /data/adb/usb_g2/usb_g2_mode, kterou čte aplikace
# (RootBridge → USB sekce) a usbtool status. Vlastní chování gadgetu se nemění;
# jde o to, aby si uživatel mohl označit, v jakém stavu má g2 být (např. před
# připojením cílového zařízení do BROM režimu).
#
# Normalizovaný režim je vhodný pro HID útoky a mass storage.

LOG_DIR="/data/adb/usb_g2"  # root-only (dříve /data/local/tmp = zapisovatelné uid 2000 → symlink útok na root zápisy)
mkdir -p "$LOG_DIR" && chmod 700 "$LOG_DIR" 2>/dev/null || true

echo "normal" > "$LOG_DIR/usb_g2_mode"
echo "[$(date)] g2 režim = normal" >> "$LOG_DIR/usb_g2_setup.log"
echo "g2 režim nastaven na: normal"
exit 0