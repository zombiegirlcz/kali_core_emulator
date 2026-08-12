# custom_usb_g2_setup — Magisk module

Připraví **předdefinovaný USB gadget `g2`** (HID klávesnice + RNDIS +
mass_storage) v configfs po bootu zařízení — **bez jakéhokoli zásahu do
aktivního systémového gadgetu `g1`** (MTP/ADB, vázán na UDC `a600000.dwc3`).

## Co modul dělá (a nedělá)

| Krok | Popis |
|------|-------|
| `post-fs-data.d/99_usb_g2_setup.sh` | Rychlý best-effort průchod hned po mountu /data |
| `service.sh` | Autoritativní pozdní start: opakovaně zkouší kompletní setup, dokud vendor gadget service (GSID) nevytvoří `gsi.rndis.rndis` / `mass_storage.0` (max 30 × 2 s) |
| Vytvoří | `g2` strukturu: `strings/0x409` (manufacturer/product/serial), `idVendor/idProduct/bcdDevice`, `configs/c.1` + `configs/c.1/strings/0x409` |
| Zlinkuje | `gsi.rndis.rndis` (fallback `rndis.usb0`) a `mass_storage.0` **symlinkem** do `configs/c.1/` z existujících vendor funkcí |
| Vytvoří | `functions/hid.usb0` (boot-keyboard report descriptor, report_length 8, subclass/protocol 1) a zlinkuje ho do `configs/c.1/` |
| Loguje | do `logcat -s usb_g2_setup` + `$MODDIR/usb_g2_setup.log` |

**Nikdy nedělá:**
- píše do `g1/*` (jen čte stav `g1/UDC` pro log),
- nezapisuje `g2/UDC` — **aktivaci/deaktivaci dělá výhradně aplikace**
  (`UsbGadgetManager.kt` / `POST /usbg2/start|stop`),
- nepokračuje/neconfiguruje, když je `g2/UDC` již obsazený (app ho používá),
- nepoužívá `rm -rf` (explicitní unlink/rmdir smyčky).

## Bezpečnostní kontrakty

1. **g1 ochrana:** všechny operace jsou omezeny na `$CFG/usb_gadget/g2/`. Jediná
   čtení mimo g2 jsou status g1 a resoluce vendor funkcí (read-only).
2. **SDÍLENÝ UDC:** dvě gadgety nemohou být vázány na stejný UDC zároveň
   (kernel vrací EBUSY). Pokud chce app aktivovat g2 na jediném UDC zařízení,
   musí nejdřív uvolnit g1 (`echo "" > g1/UDC` — viz `UsbGadgetManager.kt`,
   který to dělá jen výslovně a bezpečně). Modul se UDC bindingů vůbec
   nedotýká.
3. **Idempotence:** skripty běží každý boot; existující (nevázaný) g2 se
   přestaví z čistého stavu.

## Sestavení

```bash
# 1) modul bez binárek (jen configfs skript):
cd magisk-modules/custom_usb_g2_setup
bash build.sh

# 2) modul S host-side USB nástroji (po `zsh mbuild usrtools`,
#    aby app/src/main/assets/usr/bin obsahoval lsusb/usbrelay/...):
bash build.sh --with-tools
```

Výstup: `custom_usb_g2_setup-v1.0.zip` vedle repa. Flash → Magisk app →
*Modules → Install from storage*.

## Ověření po bootu

```bash
# status
cat /data/adb/modules/custom_usb_g2_setup/usb_g2_setup.log
logcat -s usb_g2_setup

# struktura (host, root):
ls -l /config/usb_gadget/g2/configs/c.1/
cat /config/usb_gadget/g2/UDC          # prázdné = app ho ještě neaktivoval

# aktivace / deaktivace z aplikace:
curl -s -X POST -H "Authorization: Bearer <token>" 127.0.0.1:1337/usbg2/start
curl -s -X POST -H "Authorization: Bearer <token>" 127.0.0.1:1337/usbg2/stop
curl -s -H "Authorization: Bearer <token>" 127.0.0.1:1337/usbg2/status
```

## Embedded nástroje (volitelné)

Při `--with-tools` modul do `/system/bin` přibalí host-side (Bionic/ARM64)
nástroje, které produkuje `modal_build.py::build_native`:

- `lsusb`, `usbhid-dump` (usbutils) — potřeba `libusb-1.0.so.0`
- `usbrelay`, `usbrelayd` — potřeba `libhidapi-libusb.so` + `libusbrelay.so`
- `show-gadgets`, `show-udcs`, `gadget-{hid,ms,acm-ecm,ffs,uvc,...}` (libusbgx)
  — potřeba `libusbgx.so.3`

Knihovny jdou do `/system/lib` (Magisk overlay, odinstalování modulu =
kompletní odstranění). `libc/libdl/libm` jsou ze systému. Tyto nástroje
fungují na hostu i uvnitř PRoot guestu (interpreter `/system/bin/linker64`).

> Pozn.: `lsusb` kompilovaný s `datadir=$PREFIX/share` hledá `usb.ids` v app
> prefixu (`/data/user/0/com.linux_core/files/usr/share/usb.ids`); modul ho
> navíc dává do `/system/usr/share/hwdata/usb.ids` — pokud app prefix chybí,
> lsusb běží dál bez jmen vendorů (jen ID).