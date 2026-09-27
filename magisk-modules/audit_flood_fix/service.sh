#!/system/bin/sh
# Zaloha pro sepolicy.rule (ktere Magisk aplikuje uz behem bootu pred
# service skripty) - kdyby modul byl aktivovan/prinstalovan za behu bez
# rebootu, dorovnej live policii rucne.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 3

/data/adb/magisk/magiskpolicy --live \
  "dontaudit untrusted_app_27 app_data_file:file execute" \
  "dontaudit untrusted_app_27 app_data_file:file execute_no_trans" \
  2>/dev/null || true
