#!/system/bin/sh
# activate_brom.sh — nastaví "brom" režim g2.
#
# Označí g2 za připravený pro MediaTek BROM (BootROM) scénář — typicky
# mass_storage s exploit image + HID pro odeslání payloadu. Vlastní konfiguraci
# funkcí nemění (g2 je připravená z usb_g2_setup.sh); jen zapíše režim, který
# čte aplikace a usbtool status.
#
# Pro skutečné připojení BROM zařízení je potřeba:
#   1. usbtool host         (port do OTG režimu)
#   2. připojit cílové zařízení
#   3. usbtool detect       (ověřit VID:PID 0e8d:0003 = BROM)

LOG_DIR="/data/local/tmp"
mkdir -p "$LOG_DIR" 2>/dev/null || true

echo "brom" > "$LOG_DIR/usb_g2_mode"
echo "[$(date)] g2 režim = brom" >> "$LOG_DIR/usb_g2_setup.log"
echo "g2 režim nastaven na: brom"
exit 0