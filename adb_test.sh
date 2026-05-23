#!/system/bin/sh
# ADB test script for NetHunter AI Operator PRoot
# Matches ProotManager.kt deployment: binaries named 'proot' and 'loader'
# Supports both static (no linker) and dynamic (linker64) modes

PKG_FILES=/data/data/cz.hackai.nethunter_ai_operator/files
PROOT_BIN="$PKG_FILES/proot"
LOADER_BIN="$PKG_FILES/loader"
ROOTFS_DIR="$PKG_FILES/nethunter/rootfs"
TMP_DIR="$PKG_FILES/tmp"

# Create tmp dir if needed
mkdir -p "$TMP_DIR" 2>/dev/null

# Environment (matching ProotManager.kt)
export PROOT_LOADER="$LOADER_BIN"
export PROOT_TMP_DIR="$TMP_DIR"
export LD_LIBRARY_PATH="$PKG_FILES"

# Detect architecture for linker selection
ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) LINKER="/system/bin/linker64" ;;
    arm*)         LINKER="/system/bin/linker"  ;;
    x86_64)       LINKER="/system/bin/linker64" ;;
    i686)         LINKER="/system/bin/linker"  ;;
    *)            LINKER="/system/bin/linker64" ;;
esac

echo "Arch: $ARCH | Proot: $PROOT_BIN | Loader: $LOADER_BIN | Rootfs: $ROOTFS_DIR"

# Build command arguments (static binary: run directly; dynamic: use linker)
if [ -x "$LINKER" ]; then
    echo "Using linker: $LINKER"
    set -- "$LINKER" "$PROOT_BIN"
else
    echo "No linker found, running proot directly (static binary assumed)"
    set -- "$PROOT_BIN"
fi

# Run PRoot
"$@" --kill-on-exit --link2symlink -0 \
    -r "$ROOTFS_DIR" -b /dev -b /proc -b /sys -w /root \
    /usr/bin/env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin:/usr/games:/usr/local/games \
    TERM=xterm-256color \
    LANG=C.UTF-8 \
    /bin/bash --login -c "echo HELLO_KALI; id"

echo "EXIT: $?"
