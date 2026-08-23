#!/bin/bash
# Build the Magisk module zip for custom_usb_g2_setup.
#
# Zips the module tree as-is (module.prop at zip root + META-INF),
# naming the output from module.prop (id + version).
#
# Requires: zip (or python3 as fallback)
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$HERE/custom_usb_g2_setup"

[ -d "$MOD" ] || { echo "ERROR: module dir not found: $MOD" >&2; exit 1; }
[ -f "$MOD/module.prop" ] || { echo "ERROR: module.prop missing" >&2; exit 1; }
[ -f "$MOD/META-INF/com/google/android/update-binary" ] || { echo "ERROR: META-INF/update-binary missing" >&2; exit 1; }

# Read id/version from module.prop
MOD_ID="$(sed -n 's/^id=//p' "$MOD/module.prop" | head -1 | tr -d '[:space:]')"
MOD_VER="$(sed -n 's/^version=//p' "$MOD/module.prop" | head -1 | tr -d '[:space:]')"
[ -n "$MOD_ID" ] || MOD_ID="module"

ZIP="$HERE/${MOD_ID}-${MOD_VER}.zip"
rm -f "$ZIP"

echo "[1/2] zipping $MOD_ID $MOD_VER ..."
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

echo "[2/2] done: $ZIP"
ls -la "$ZIP"

# Sanity check: module.prop must be at zip root
if command -v unzip >/dev/null 2>&1; then
    unzip -l "$ZIP" | grep -q " module.prop$" \
        && echo "OK: module.prop at zip root" \
        || { echo "ERROR: module.prop not at zip root!" >&2; exit 1; }
fi
