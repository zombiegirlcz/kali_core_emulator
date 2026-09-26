#!/system/bin/sh
# post-fs-data.sh — Magisk early-boot hook.
#
# Cíl: připravit configfs gadget g2 (HID + mass_storage + rndis) JEŠTĚ PŘED
# tím, než se uživatel přihlásí. Aktivace (bind na UDC) se tady NEDĚLÁ —
# g1 (systémový MTP/ADB gadget) drží UDC a přepíná se až na explicitní
# povel z aplikace / guesta (`usbtool g2`), aby se neztratil ADB při bootu.
#
# configfs je namountovaný kernelem už v této fázi, takže mkdir v /config
# funguje. Pokud ne, setup se tiše přeskočí a zkusí se později z service.sh.

MODDIR="/data/adb/modules/custom_usb_g2_setup"
LOG_DIR="/data/local/tmp"
LOG="$LOG_DIR/usb_g2_setup.log"

mkdir -p "$LOG_DIR" 2>/dev/null || true

# configfs musí být vidět, jinak nemá smysl pokračovat
if [ ! -d /config/usb_gadget ] && [ ! -d /sys/kernel/config/usb_gadget ]; then
    echo "[$(date)] post-fs-data: configfs usb_gadget nenalezen, odkládám" >> "$LOG"
    exit 0
fi

# Počkat, až Magisk dokončí mount modulu (system/bin z modulu v /system)
# — v post-fs-data už je hotovo, ale pro jistotu.
if [ ! -f "$MODDIR/usb_g2_setup.sh" ]; then
    echo "[$(date)] post-fs-data: usb_g2_setup.sh chybí" >> "$LOG"
    exit 0
fi

echo "[$(date)] post-fs-data: spouštím usb_g2_setup.sh" >> "$LOG"
sh "$MODDIR/usb_g2_setup.sh" >> "$LOG" 2>&1 || \
    echo "[$(date)] post-fs-data: usb_g2_setup.sh selhal (rc=$?)" >> "$LOG"