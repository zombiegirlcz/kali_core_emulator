# USB gadget tools toolchain (assets/usr, Bionic/ARM64)

Cross-compiled by `tools/modal_build.py::_build_usb_tools()` (invoked from
`build_native`, step 6, after the nano/rsync/sed/rg usr tools). All outputs
are **Bionic** (aarch64-linux-android, interpreter `/system/bin/linker64`)
so they run both on the Android host (ashell/su, where configfs `/config`
and `/dev/bus/usb` live) and inside the PRoot guest. glibc binaries would
die on the host with `Bad system call` (rseq blocked by app seccomp) —
see AGENTS.md session 2026-08-11.

## Components (pinned)

| Component | Version | Source | Output |
|-----------|---------|--------|--------|
| argp-standalone | 1.5.0 | github argp-standalone/argp-standalone (static) | `libargp.a` (link-time only, not shipped) |
| libusb | 1.0.27 | github libusb/libusb | `libusb-1.0.so.0` → `assets/usr/lib` |
| hidapi | hidapi-0.14.0 | github libusb/hidapi (git clone --recursive, CMake, libusb backend) | `libhidapi-libusb.so` → `assets/usr/lib` |
| libusbgx | libusbgx-v0.3.0 | github libusbgx/libusbgx (`--without-libconfig --disable-gadget-schemes --disable-tests`) | `libusbgx.so.3` + `show-gadgets`, `show-udcs`, `gadget-*` → `assets/usr` |
| usbrelay | v0.8 | github darrylb123/usbrelay (HIDAPI=libusb, `-largp`) | `libusbrelay.so` + `usbrelay` → `assets/usr` |
| usbrelayd | ours | `tools/usbtools/usbrelayd.c` | `usbrelayd` → `assets/usr/bin` |
| usbutils | v007 | github gregkh/usbutils (git clone --recurse-submodules; usbhid-dump submodule) | `lsusb`, `usbhid-dump` → `assets/usr/bin` |
| usb.ids | master | github usbids/usbids | `assets/usr/share/usb.ids` |

## Why these choices

- **argp:** usbrelay uses glibc `argp`; bionic has none. argp-standalone is
  the same approach Termux uses (`packages/argp`). Static lib, linked only
  into `usbrelay`.
- **hidapi libusb backend:** hidraw backend needs libudev (absent on
  bionic). Hidapi 0.14.0 is CMake-only. Built STANDALONE (NDK
  android.toolchain.cmake): its bundled `libusb/` submodule CMakeLists
  prefers an EXTERNAL libusb via pkg-config (`if(TARGET usb-1.0) ... else
  pkg_check_modules(libusb-1.0)`), so `libhidapi-libusb.so` links against
  OUR staged libusb 1.0.27 — one single `libusb-1.0.so.0` in usr/lib for
  everyone. (clone --recurse-submodules is still required: the wrapper dir
  must exist.)
- **usbrelayd:** upstream `usbrelayd` is a Python3+MQTT script (needs a
  CPython extension built against the guest interpreter). We ship a compiled
  C TCP daemon (text protocol, see `usbrelayd.c` header) that links the
  same `libusbrelay` + hidapi — no Python ABI anywhere.
- **usbutils v007 (not v015+):** usbutils switched name resolution to udev
  hwdb (`names.c` does `#include <libudev.h>`) already in v008-v018 —
  libudev does not exist on bionic. v007 is the last version that reads
  `usb.ids` directly (`names_init(DATADIR "/usb.ids")`, DATADIR comes from
  `--datadir`). v007's `usbhid-dump` is a git submodule (missing from GitHub
  tarballs) → cloned with `--recurse-submodules`. Configured with
  `--disable-zlib --disable-usbids` (we ship our own fetched usb.ids).
- **libconfig:** skipped. libusbgx 0.3.0 dropped the config-file `gadget`
  CLI; examples use the plain library API, which needs no libconfig.

## Runtime layout (assets/usr)

```
bin/  lsusb usbhid-dump usbrelay usbrelayd show-gadgets show-udcs
      gadget-acm-ecm gadget-hid gadget-ms gadget-export gadget-import
      gadget-ffs gadget-midi gadget-printer gadget-uvc
      gadget-rndis-os-desc gadget-vid-pid-remove
lib/  libusb-1.0.so -> libusb-1.0.so.0
      libhidapi-libusb.so  libusbgx.so -> libusbgx.so.3  libusbrelay.so
share/usb.ids
```

Binaries link libs via `-Wl,-rpath=$ORIGIN/../lib` → `/data/user/0/
com.linux_core/files/usr/lib` on the host, also reachable inside PRoot.

## Verify in a fresh container

```bash
zsh mbuild usrtools          # also builds usb tools + pulls assets
file app/src/main/assets/usr/bin/lsusb   # ELF ... interpreter /system/bin/linker64
# on device (host shell):
lsusb
usbrelay                    # dumps relay states
usbrelayd -p 8787 &         # TCP daemon; echo "LIST" | nc 127.0.0.1 8787
show-gadgets                # configfs gadget overview (needs root)
gadget-hid --help
```