#!/system/bin/sh
# usb_g2_setup.sh — idempotentní příprava configfs USB gadget g2
# (HID keyboard + Mass Storage + RNDIS), bez bindování UDC.

set -e

MODDIR="/data/adb/modules/custom_usb_g2_setup"
LOG_DIR="/data/local/tmp"
LOG="$LOG_DIR/usb_g2_setup.log"
GADGET_ROOT="/config/usb_gadget"
G2="$GADGET_ROOT/g2"

# Try both common configfs mount points
for c in /config /sys/kernel/config; do
    if [ -d "$c/usb_gadget" ]; then
        GADGET_ROOT="$c/usb_gadget"
        G2="$GADGET_ROOT/g2"
        break
    fi
done

if [ ! -d "$GADGET_ROOT" ]; then
    echo "CHYBA: configfs usb_gadget nenalezen" | tee -a "$LOG"
    exit 1
fi

echo "[$(date)] usb_g2_setup.sh start" >> "$LOG"

# ── Cleanup old g2 (step-by-step, configfs requires this order) ────────────
if [ -d "$G2" ]; then
    # 1. Unbind UDC
    echo "" > "$G2/UDC" 2>/dev/null || true

    # 2. Remove config symlinks
    for cfg in "$G2"/configs/*; do
        [ -d "$cfg" ] || continue
        for f in "$cfg"/*; do
            [ -L "$f" ] && rm -f "$f" 2>/dev/null || true
        done
        # Remove config strings
        rm -rf "$cfg/strings" 2>/dev/null || true
        rmdir "$cfg" 2>/dev/null || true
    done

    # 3. Remove functions
    for fn in "$G2"/functions/*; do
        [ -d "$fn" ] && rmdir "$fn" 2>/dev/null || true
    done

    # 4. Remove strings and os_desc
    rm -rf "$G2/strings" 2>/dev/null || true
    rm -rf "$G2/os_desc" 2>/dev/null || true

    # 5. Finally remove gadget directory
    rmdir "$G2" 2>/dev/null || true
fi

# ── Create fresh g2 ────────────────────────────────────────────────────────
mkdir -p "$G2"
cd "$G2"

# VID/PID — same VID as g1, different PID for g2 composite
echo 0x18d1 > idVendor
echo 0x4eed > idProduct

# Strings
mkdir -p strings/0x409
echo "0123456789" > strings/0x409/serialnumber
echo "Google" > strings/0x409/manufacturer
echo "NetHunter Gadget" > strings/0x409/product

# Config
mkdir -p configs/c.1
echo 100 > configs/c.1/MaxPower
echo 0x80 > configs/c.1/bmAttributes

# ── HID keyboard ──────────────────────────────────────────────────────────
mkdir -p functions/hid.usb0
echo 1 > functions/hid.usb0/protocol
echo 1 > functions/hid.usb0/subclass
echo 8 > functions/hid.usb0/report_length
printf '\x05\x01\x09\x06\xa1\x01\x05\x07\x19\xe0\x29\xe7\x15\x00\x25\x01\x75\x01\x95\x08\x81\x02\x95\x01\x75\x08\x81\x03\x95\x05\x75\x01\x05\x08\x19\x01\x29\x05\x91\x02\x95\x01\x75\x03\x91\x03\x95\x06\x75\x08\x15\x00\x26\xff\x00\x81\x00\xc0' \
    > functions/hid.usb0/report_desc

# ── Mass Storage (SCSI CD-ROM emulation) ─────────────────────────────────
mkdir -p functions/mass_storage.0/lun.0
echo 0 > functions/mass_storage.0/stall
echo 0 > functions/mass_storage.0/lun.0/cdrom
echo 0 > functions/mass_storage.0/lun.0/ro
echo 0 > functions/mass_storage.0/lun.0/nofua
echo 0 > functions/mass_storage.0/lun.0/removable
echo "-" > functions/mass_storage.0/lun.0/inquiry_string

# ── RNDIS (Ethernet over USB) ─────────────────────────────────────────────
mkdir -p functions/rndis.usb0
echo "0123456789" > functions/rndis.usb0/host_addr
echo "0123456789" > functions/rndis.usb0/dev_addr
echo 1 > functions/rndis.usb0/manufacturer
echo 1 > functions/rndis.usb0/wceis

# Bind functions to config
ln -sf functions/hid.usb0 configs/c.1/hid.usb0
ln -sf functions/mass_storage.0 configs/c.1/mass_storage.0
ln -sf functions/rndis.usb0 configs/c.1/rndis.usb0

# Do NOT bind UDC here — usbtool/switch-usb-gadget g2 handles that.
echo "" > "$G2/UDC" 2>/dev/null || true

echo "[$(date)] usb_g2_setup.sh done" >> "$LOG"
exit 0
