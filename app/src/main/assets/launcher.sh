#!/system/bin/sh
# [__LOG_PREFIX__] launcher.sh — PRoot container startup for __DISTRO_ID__
#
# Deployed by ProotManager.deployLauncherScript() from template.
# USAGE: launcher.sh [command...]
#   Without arguments: start interactive login shell (entrypoint.sh)
#   With arguments:   execute command inside PRoot container

set -e

# ─── Paths (filled by template engine) ───────────────────
PROOT_BIN="__PROOT_BIN__"
LOADER_BIN="__LOADER_BIN__"
TALLOC_LIB="__TALLOC_LIB__"
STANDALONE_PROOT="__STANDALONE_PROOT__"
STANDALONE_LOADER="__STANDALONE_LOADER__"
ROOTFS_DIR="__ROOTFS_DIR__"
ROOTFS_NAME="__ROOTFS_NAME__"
TMP_DIR="__TMP_DIR__"
FILES_DIR="__FILES_DIR__"
SDCARD_MOUNT="__SDCARD_MOUNT__"
DOCKER_MODE="__DOCKER_MODE__"
LOG_PREFIX="__LOG_PREFIX__"
DISTRO_ID="__DISTRO_ID__"

# ─── Detect usable PRoot binary ──────────────────────────
PR=""
LOADER=""
if [ -x "$STANDALONE_PROOT" ] && [ -x "$STANDALONE_LOADER" ]; then
    PR="$STANDALONE_PROOT"
    LOADER="$STANDALONE_LOADER"
    echo "[$LOG_PREFIX] Using standalone PRoot: $STANDALONE_PROOT" >&2
elif [ -x "$PROOT_BIN" ] && [ -x "$LOADER_BIN" ] && [ -f "$TALLOC_LIB" ]; then
    PR="$PROOT_BIN"
    LOADER="$LOADER_BIN"
    export PROOT_LOADER="$LOADER"
    export LD_PRELOAD="$TALLOC_LIB"
    echo "[$LOG_PREFIX] Using dynamic PRoot: $PROOT_BIN (LD_PRELOAD=$TALLOC_LIB)" >&2
else
    echo "[$LOG_PREFIX] ERROR: No usable PRoot binary found" >&2
    echo "[$LOG_PREFIX]   standalone: exists=$([ -x "$STANDALONE_PROOT" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   standalone_loader: exists=$([ -x "$STANDALONE_LOADER" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   dynamic: exists=$([ -x "$PROOT_BIN" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   loader: exists=$([ -x "$LOADER_BIN" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   talloc: exists=$([ -f "$TALLOC_LIB" ] && echo yes || echo no)" >&2
    exit 1
fi

export PROOT_TMP_DIR="$TMP_DIR"
export TMPDIR="$TMP_DIR"

# ─── Rootfs validation ───────────────────────────────────
if [ ! -d "$ROOTFS_DIR" ]; then
    echo "[$LOG_PREFIX] ERROR: Rootfs dir not found: $ROOTFS_DIR" >&2
    exit 1
fi

echo "[$LOG_PREFIX] Starting $DISTRO_ID session ($ROOTFS_NAME, docker=$DOCKER_MODE)" >&2

# ─── PRoot bind mounts ──────────────────────────────────
BINDS="-b /dev -b /proc -b /sys"
BINDS="$BINDS -b $FILES_DIR/tmp:$TMP_DIR"
BINDS="$BINDS $SDCARD_MOUNT"

PROOT_FLAGS="-v 0 --kill-on-exit --link2symlink -0"
PROOT_CWD="/root"

# ─── Build and execute PRoot command ────────────────────
if [ "$DOCKER_MODE" = "1" ]; then
    set -- "$PR" $PROOT_FLAGS \
        -r "$ROOTFS_DIR" \
        -w "$PROOT_CWD" \
        $BINDS \
        /bin/sh -c 'cd /root && exec /bin/bash --login "$@"' -- "$@"
elif [ -f "$ROOTFS_DIR/root/entrypoint.sh" ]; then
    set -- "$PR" $PROOT_FLAGS \
        -r "$ROOTFS_DIR" \
        -w "$PROOT_CWD" \
        $BINDS \
        /bin/sh /root/entrypoint.sh "$@"
else
    set -- "$PR" $PROOT_FLAGS \
        -r "$ROOTFS_DIR" \
        -w "$PROOT_CWD" \
        $BINDS \
        /bin/sh -c 'cd /root && exec /bin/bash --login "$@"' -- "$@"
fi

echo "[$LOG_PREFIX] exec: $@" >&2
exec "$@"
