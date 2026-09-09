#!/bin/bash
# Build Magisk module zips for all module directories in the current working directory.
#
# For every subdirectory containing a valid module tree (module.prop + META-INF/update-binary),
# produces a zip named <id>-<version>.zip next to this script.
#
# Requires: zip (or python3 as fallback)
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(pwd)"

# Find direct subdirectories in the current working directory
shopt -s nullglob
MOD_DIRS=("$BASE"/*/)
shopt -u nullglob

if [ ${#MOD_DIRS[@]} -eq 0 ]; then
    echo "ERROR: no subdirectories found in $BASE" >&2
    exit 1
fi

BUILT=0
FAILED=0

for MOD in "${MOD_DIRS[@]}"; do
    MOD="${MOD%/}"
    [ -d "$MOD" ] || continue

    [ -f "$MOD/module.prop" ] || { echo "SKIP: $MOD (missing module.prop)"; continue; }
    [ -f "$MOD/META-INF/com/google/android/update-binary" ] || { echo "SKIP: $MOD (missing update-binary)"; continue; }

    # Read id/version from module.prop
    MOD_ID="$(sed -n 's/^id=//p' "$MOD/module.prop" | head -1 | tr -d '[:space:]')"
    MOD_VER="$(sed -n 's/^version=//p' "$MOD/module.prop" | head -1 | tr -d '[:space:]')"
    [ -n "$MOD_ID" ] || MOD_ID="module"

    ZIP="$HERE/${MOD_ID}-${MOD_VER}.zip"
    rm -f "$ZIP"

    echo "[${MOD_ID}] zipping $MOD $MOD_VER ..."
    if command -v zip >/dev/null 2>&1; then
        ( cd "$MOD" && zip -qr "$ZIP" . -x '.*' )
    else
        # Fallback: python3 zipfile, preserving unix exec permissions
        python3 - "$MOD" "$ZIP" <<'EOF'
import os, stat, sys, zipfile
src, zpath = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        for f in sorted(files):
            p = os.path.join(root, f)
            rel = os.path.relpath(p, src)
            if rel.startswith('.'):
                continue
            zi = zipfile.ZipInfo.from_file(p, rel)
            zi.compress_type = zipfile.ZIP_DEFLATED
            mode = os.stat(p).st_mode
            zi.external_attr = (stat.S_IMODE(mode) << 16) | 0o100000  # unix perms + regular file
            with open(p, "rb") as fh:
                z.writestr(zi, fh.read())
EOF
    fi

    ls -la "$ZIP"

    # Sanity check: module.prop must be at zip root
    if command -v unzip >/dev/null 2>&1; then
        if unzip -l "$ZIP" | grep -q " module.prop$"; then
            echo "[${MOD_ID}] OK: module.prop at zip root"
        else
            echo "[${MOD_ID}] ERROR: module.prop not at zip root!" >&2
            FAILED=$((FAILED + 1))
            continue
        fi
    fi

    BUILT=$((BUILT + 1))
done

echo "[done] built=$BUILT failed=$FAILED"
[ "$FAILED" -eq 0 ] || exit 1
