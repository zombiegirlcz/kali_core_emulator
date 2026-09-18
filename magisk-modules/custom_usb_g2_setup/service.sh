#!/system/bin/sh
# service.sh — Magisk late-boot hook (po dokončení bootu).
#
# Fallback pro případ, že post-fs-data.sh setup g2 nestihl (configfs ještě
# nebyl připravený, nebo modul mount doběhl později). Je idempotentní:
# usb_g2_setup.sh sám pozná, že g2 už je hotové, a jen ho překonfiguruje.
#
# Záměrně NEBINDUJE g2 na UDC — to zůstává na explicitním `usbtool g2`.

MODDIR="/data/adb/modules/custom_usb_g2_setup"
LOG_DIR="/data/local/tmp"
LOG="$LOG_DIR/usb_g2_setup.log"

mkdir -p "$LOG_DIR" 2>/dev/null || true

# Pokud už g2 má nakonfigurované funkce z post-fs-data, nic nedělej.
if [ -L "/config/usb_gadget/g2/configs/c.1/hid.usb0" ]; then
    exit 0
fi

if [ ! -f "$MODDIR/usb_g2_setup.sh" ]; then
    echo "[$(date)] service: usb_g2_setup.sh chybí" >> "$LOG"
    exit 0
fi

echo "[$(date)] service: fallback setup g2" >> "$LOG"
sh "$MODDIR/usb_g2_setup.sh" >> "$LOG" 2>&1 || \
    echo "[$(date)] service: usb_g2_setup.sh selhal (rc=$?)" >> "$LOG"