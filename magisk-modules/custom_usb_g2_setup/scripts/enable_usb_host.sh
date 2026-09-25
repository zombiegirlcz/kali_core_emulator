#!/system/bin/sh
# enable_usb_host.sh — přepne USB port do host (OTG) režimu.
#
# Na SM7150 (a600000.ssusb) se režim řídí zápisem do .../mode:
#   "host" = OTG host, gadgety (g1/g2) přestanou fungovat, nabíjení může vypadnout
#   "none" = auto/gadget (návrat zpět)
#
# POZOR: Po skončení práce je VŽDY potřeba `usbtool device` (nebo tento skript
# s argumentem "device"), jinak telefon nemusí nabíjet!

set -e

SSUSB_MODE="/sys/devices/platform/soc/a600000.ssusb/mode"
LOG_DIR="/data/local/tmp"
mkdir -p "$LOG_DIR" 2>/dev/null || true

if [ ! -f "$SSUSB_MODE" ]; then
    echo "CHYBA: $SSUSB_MODE neexistuje — neznámý SoC?" >&2
    exit 1
fi

case "${1:-host}" in
    host)
        echo host > "$SSUSB_MODE" || { echo "CHYBA: zápis 'host' selhal" >&2; exit 1; }
        echo "[$(date)] USB port -> host (OTG)" >> "$LOG_DIR/usb_g2_setup.log"
        echo "USB host režim aktivní (port = host)."
        ;;
    device)
        echo none > "$SSUSB_MODE" || { echo "CHYBA: zápis 'none' selhal" >&2; exit 1; }
        echo "[$(date)] USB port -> none (gadget/auto)" >> "$LOG_DIR/usb_g2_setup.log"
        echo "USB port zpět v gadget/auto režimu."
        ;;
    *)
        echo "Usage: $0 [host|device]" >&2
        exit 1
        ;;
esac
exit 0