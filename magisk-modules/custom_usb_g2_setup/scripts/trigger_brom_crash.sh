#!/system/bin/sh
# trigger_brom_crash.sh — experimentální placeholder pro MediaTek BROM crash.
#
# Cíl: shodit MediaTek zařízení z Preloaderu do BROM tak, že se během úzkého
# okna pošle na jeho USB endpoint neplatný control transfer. Reálná
# implementace závisí na konkrétním SoC a je mimo rozsah tohoto modulu —
# skript jen zaloguje, co by se dělalo, a vrátí nenulový kód, aby volající
# poznal, že jde o stub.
#
# Použití: trigger_brom_crash.sh <cesta_k_usb_zarizeni>
#   výchozí: /sys/bus/usb/devices/1-1

DEV="${1:-/sys/bus/usb/devices/1-1}"
LOG_DIR="/data/local/tmp"
LOG="$LOG_DIR/usb_g2_brom_crash.log"
mkdir -p "$LOG_DIR" 2>/dev/null || true

{
    echo "[$(date)] trigger_brom_crash: cíl=$DEV"
    if [ ! -d "$DEV" ]; then
        echo "  CHYBA: $DEV není adresář (zařízení nepřipojeno?)"
    else
        echo "  vid=$(cat "$DEV/idVendor" 2>/dev/null):$(cat "$DEV/idProduct" 2>/dev/null)"
        echo "  product=$(cat "$DEV/product" 2>/dev/null)"
        echo "  STUB: reálný BROM crash není implementován (nutný SoC-specific payload)."
    fi
} >> "$LOG" 2>&1

echo "trigger_brom_crash: STUB — viz $LOG" >&2
exit 2