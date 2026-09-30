#!/system/bin/sh
# Zaloha pro sepolicy.rule (ktere Magisk aplikuje uz behem bootu pred
# service skripty) - kdyby modul byl aktivovan/prinstalovan za behu bez
# rebootu, dorovnej live policii rucne.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 3

# --- Vrstva 1: audit_rate_limit (overeno 2026-09-27) ---
# Default audit_rate_limit=5 msg/s je pro PRoot workload hluboko pod potrebou.
# Po `auditctl -r 1000` klesl audit_lost z 21095 na 403 za boot.
# Android auditctl na tomto zarizeni podporuje JEN `-r rate` (ne `-b backlog`,
# ne `-s`) - overeno 2026-09-30: "Usage: /system/bin/auditctl [-r rate]".
/system/bin/auditctl -r 1000 2>/dev/null || true

# --- Vrstva 2: dontaudit na setattr DENIALS (2026-09-30) ---
# POZOR: puvodni `dontaudit ... app_data_file:file { execute execute_no_trans }`
# je BEZUCINNY a byl odstranen - `dontaudit` plati jen na ZAMITNUTE pristupy,
# kdezto `execute` je ALLOW a jeho logovani rizi samostatny `auditallow`
# v MIUI vendor policy, ktery `dontaudit` neprebije (overeno print-rules
# 27. 9. i merenim 30. 9.: 2061 granted zaznamu s nainstalovanym modulem).
#
# Tohle naopak funguje, protoze jde o DENIALS: proot pri startu chmodne
# Android property soubory v /dev/__properties__ a SELinux to vzdy zamitne.
# Merenim 30. 9. to bylo 3778 z 4514 denials (84 %) = nejvetsi jediny zdroj.
/data/adb/magisk/magiskpolicy --live \
  "dontaudit untrusted_app_27 property_type:file setattr" \
  "dontaudit untrusted_app_27 property_info:file setattr" \
  "dontaudit untrusted_app_27 properties_serial:file setattr" \
  2>/dev/null || true
