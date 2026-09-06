#!/bin/sh
# Shizuku rish shell wrapper — nasazeno jako /usr/local/bin/shizuku v PRoot guestu
#
# Pokusí se najít vhodný interpreter. V PRootu je /system/bin/sh k dispozici
# přes bind mount, ale pro jistotu používáme /bin/sh který existuje vždy.
BASEDIR=$(dirname "$0")
DEX="$BASEDIR"/rish_shizuku.dex

if [ ! -f "$DEX" ]; then
  echo "Cannot find $DEX, please check the tutorial in Shizuku app" >&2
  exit 1
fi

# Detekce Android verze (getprop je v /system/bin/getprop, dostupný přes PRoot bind mount)
if command -v getprop >/dev/null 2>&1; then
  SDK=$(getprop ro.build.version.sdk 2>/dev/null)
  if [ -n "$SDK" ] && [ "$SDK" -ge 34 ] 2>/dev/null; then
    if [ -w "$DEX" ]; then
      echo "On Android 14+, app_process cannot load writable dex." >&2
      echo "Attempting to remove the write permission..." >&2
      chmod 400 "$DEX" 2>/dev/null
    fi
    if [ -w "$DEX" ]; then
      echo "Cannot remove the write permission of $DEX." >&2
      echo "Copy the dex to a private directory first." >&2
      exit 1
    fi
  fi
fi

# Replace "PKG" with the application id of your terminal app
[ -z "$RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="com.linux_core"

# Zkus app_process — v PRootu je /system bind mountovaný, takže by měl být dostupný
APP_PROCESS="/system/bin/app_process"
if [ ! -x "$APP_PROCESS" ]; then
  echo "Shizuku rish requires Android app_process ($APP_PROCESS)" >&2
  echo "Make sure /system is bind-mounted in PRoot or run outside PRoot." >&2
  exit 1
fi

exec "$APP_PROCESS" \
  -Djava.class.path="$DEX" \
  /system/bin \
  --nice-name=rish \
  rikka.shizuku.shell.ShizukuShellLoader \
  "$@"
