#!/system/bin/sh
# Zaloha pro sepolicy.rule (ktere Magisk aplikuje uz behem bootu pred
# service skripty) - kdyby modul byl aktivovan/prinstalovan za behu bez
# rebootu, dorovnej live policii rucne.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 3

# POZOR (overeno na zarizeni 2026-09-27): tohle NENI hlavni fix.
# `dontaudit` potlaci logovani ZAMITNUTYCH pristupu, ale execve() v PRootu
# je uz ALLOW - jeho logovani rizeny samostatnym `auditallow` v MIUI vendor
# policy (nesouvisi s zadnym nasim modulem), ktery `dontaudit` neprebiji.
# Zustava tu jen pro puvodni "setattr proc:dir" DENIED storm (viz
# fix-proot-selinux-storm), pro execute storm je bezucinny.
/data/adb/magisk/magiskpolicy --live \
  "dontaudit untrusted_app_27 app_data_file file { execute execute_no_trans }" \
  2>/dev/null || true

# Skutecny fix execute-storm: default audit_rate_limit=5 msg/s je pro PRoot
# workload (kazdy shell prikaz = retez execve() = spousta granted-audit
# zaznamu kvuli auditallow vyse) hluboko pod potrebou -> audit_lost stoupal
# o stovky/s, "rate limit exceeded" kazdou sekundu, backlog fronta
# (audit_log_start(), sync kernelova cesta sdilena VSEMI procesy) dokazala
# zaseknout system_server. Overeno: po `auditctl -r 1000` zadny dalsi
# audit_lost i pod zataezi (40x exec v proot). Android auditctl na tomto
# zarizeni podporuje jen `-r rate` (ne `-b backlog`).
/system/bin/auditctl -r 1000 2>/dev/null || true
