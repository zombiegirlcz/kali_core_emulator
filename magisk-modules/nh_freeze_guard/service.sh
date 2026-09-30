#!/system/bin/sh
# nh_freeze_guard - post-boot service
#
# Poradi kroku:
#   1) Cekat na sys.boot_completed (auditctl a magiskpolicy pred bootem
#      nejsou dostupne, nebo by nefungovaly korektne)
#   2) Vrstva 1: auditctl -r 1000 (audit_rate_limit, PRIMARNI ucinek)
#   3) Vrstva 2: dontaudit fallback pres magiskpolicy --live (zaloha
#      pro sepolicy.rule, kdyby modul byl aktivovan bez rebootu)
#   4) Vrstva 3 (volitelna): probe.sh - UDP telemetrie, jen pokud
#      existuje /data/adb/nh_probe.conf s HOST=

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 3

MODDIR=${0%/*}

# --- Vrstva 1: audit_rate_limit (overeno 2026-09-27) ---
# Default audit_rate_limit=5 msg/s je pro PRoot workload hluboko pod potrebou.
# Po `auditctl -r 1000` klesl audit_lost z 21095 na 403 za boot.
# Android auditctl na tomto zarizeni podporuje JEN `-r rate` (ne `-b backlog`,
# ne `-s`) - overeno 2026-09-30: "Usage: /system/bin/auditctl [-r rate]".
/system/bin/auditctl -r 1000 2>/dev/null || true

# --- Vrstva 2: dontaudit na setattr DENIALS (2026-09-30) ---
# Zaloha pro sepolicy.rule (ktere Magisk aplikuje uz behem bootu pred
# service skripty) - kdyby modul byl aktivovan/prinstalovan za behu bez
# rebootu, dorovnej live policii rucne. proot pri startu chmodne Android
# property soubory v /dev/__properties__ a SELinux to vzdy zamitne.
# Merenim 30. 9. to bylo 3778 z 4514 denials (84 %) = nejvetsi jediny zdroj.
/data/adb/magisk/magiskpolicy --live \
  "dontaudit untrusted_app_27 property_type file { setattr }" \
  "dontaudit untrusted_app_27 property_info file { setattr }" \
  "dontaudit untrusted_app_27 properties_serial file { setattr }" \
  2>/dev/null || true

# --- Vrstva 3: UDP telemetrie (volitelna) ---
# Bez /data/adb/nh_probe.conf se sonda nespousti (aby modul nikomu neposilal
# data omylem). Format konfigurace viz README.md.
if [ -f /data/adb/nh_probe.conf ] && [ -x "$MODDIR/probe.sh" ]; then
    "$MODDIR/probe.sh" >/dev/null 2>&1 &
fi
