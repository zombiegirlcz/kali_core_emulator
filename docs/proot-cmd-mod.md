# NetHost proot command mods for com.linux_core
#
# Toto jsou reference bindy pro PRoot: ISOLATED, MINIMAL a DEFAULT.
# ZDE NEJSOU Termux cesty — vše je pro app `com.linux_core`,
# tedy `/data/user/0/com.linux_core/files/...` místo `/data/data/com.termux/files/...`.
#
# `boot` skript z `assets/usr/bin/boot` tyto bindy používá přímo:
# - setup_sysdata_shm()  → vytvoří fake /proc a /dev/shm
# - build_binds()        → sestaví -b flagy pro daný režim
# - is_termux_image()    → detekuje jen Termux rootfs UVNITR guestu,
#                          ne na hostiteli

#----ISOLATED----
# - env -i: čisté env bez Android proměnných
# - žádné host binds kromě /dev, /proc, /sys
# - fake sysdata: loadavg, stat, uptime, vmstat, sysctl
# - /dev/shm z $FILES_DIR/nh/shm/<distro>
# - PROOT_L2S_DIR uvnitř rootfs (.l2s)
#
# V boot skriptu: NH_ISOLATED=1 => NH_MINIMAL=1 automaticky

env \
  -i \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games:/data/user/0/com.linux_core/files/usr/bin:/system/bin:/system/xbin \
  MOZ_FAKE_NO_SANDBOX=1 \
  PULSE_SERVER=127.0.0.1 \
  HOME=/root \
  USER=root \
  TERM=xterm-256color \
  COLORTERM=truecolor \
  PROOT_L2S_DIR=/data/user/0/com.linux_core/files/nh/distro/<distro>/.l2s \
  /data/user/0/com.linux_core/files/usr/bin/proot \
  --kill-on-exit \
  --link2symlink \
  --sysvipc \
  "--kernel-release=\\Linux\\localhost\\6.17.0-proot-distro\\6.17.0-proot-distro\\aarch64\\localdomain\\-1\\" \
  -L \
  --change-id=0:0 \
  --rootfs=/data/user/0/com.linux_core/files/nh/distro/<distro> \
  --cwd=/root \
  --bind=/dev \
  --bind=/proc \
  --bind=/sys \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/loadavg:/proc/loadavg \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/stat:/proc/stat \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/uptime:/proc/uptime \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/vmstat:/proc/vmstat \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/sysctl:/proc/sys \
  --bind=/data/user/0/com.linux_core/files/nh/shm/<distro>:/dev/shm \
  /bin/bash \
  -l

#----MINIMAL----
# - env -i: jen TERM/COLORTERM
# - bez fake sysdata
# - jen základní binds /dev /proc /sys
#
# V boot skriptu: NH_MINIMAL=1 nebo NH_ISOLATED=1

env \
  -i \
  TERM=xterm-256color \
  COLORTERM=truecolor \
  PROOT_L2S_DIR=/data/user/0/com.linux_core/files/nh/distro/<distro>/.l2s \
  /data/user/0/com.linux_core/files/usr/bin/proot \
  --kill-on-exit \
  --link2symlink \
  -L \
  --change-id=0:0 \
  --rootfs=/data/user/0/com.linux_core/files/nh/distro/<distro> \
  --cwd=/root \
  --bind=/dev \
  --bind=/proc \
  --bind=/sys \
  /bin/bash \
  -l

#----DEFAULT----
# - plné env: Android ART/DALVIK/BOOTCLASSPATH + host /data/data paths
# - fake sysdata binds
# - /dev/shm z $FILES_DIR/nh/shm/<distro>
# - host /data/app, /storage, /apex, /system, /vendor, ...
# - host-side usr/bin a usr/lib z com.linux_core filesDir
#
# V boot skriptu: NH_ISOLATED=0 a NH_MINIMAL=0

env \
  -i \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games:/system/bin:/system/xbin \
  MOZ_FAKE_NO_SANDBOX=1 \
  PULSE_SERVER=127.0.0.1 \
  ANDROID_ART_ROOT=/apex/com.android.art \
  ANDROID_DATA=/data \
  ANDROID_I18N_ROOT=/apex/com.android.i18n \
  ANDROID_ROOT=/system \
  ANDROID_TZDATA_ROOT=/apex/com.android.tzdata \
  BOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/tcmiface.jar:/system/framework/telephony-ext.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/WfdCommon.jar:/system_ext/framework/miui-framework.jar:/system_ext/framework/miui-telephony-common.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.adservices/javalib/framework-adservices.jar:/apex/com.android.adservices/javalib/framework-sdksandbox.jar:/apex/com.android.appsearch/javalib/framework-appsearch.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar:/apex/com.android.ipsec/javalib/android.net.ipsec.ike.jar:/apex/com.android.media/javalib/updatable-media.jar:/apex/com.android.mediaprovider/javalib/framework-mediaprovider.jar:/apex/com.android.mediaprovider/javalib/framework-pdf.jar:/apex/com.android.mediaprovider/javalib/mediapicker.jar:/apex/com.android.ondevicepersonalization/javalib/framework-ondevicepersonalization.jar:/apex/com.android.os.statsd/javalib/framework-statsd.jar:/apex/com.android.permission/javalib/framework-permission.jar:/apex/com.android.permission-s/javalib/framework-permission-s.jar:/apex/com.android.scheduling/javalib/framework-scheduling.jar:/apex/com.android.sdkext/javalib/framework-sdkextensions.jar:/apex/com.android.tethering/javalib/framework-connectivity.jar:/apex/com.android.tethering/javalib/framework-connectivity-t.jar:/apex/com.android.wifi/javalib/framework-wifi.jar \
  DEX2OATBOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/tcmiface.jar:/system/framework/telephony-ext.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/WfdCommon.jar:/system_ext/framework/miui-framework.jar:/system_ext/framework/miui-telephony-common.jar:/apex/com.android.i18n/javalib/core-icu4j.jar \
  EXTERNAL_STORAGE=/sdcard \
  HOME=/root \
  USER=root \
  TERM=xterm-256color \
  COLORTERM=truecolor \
  PROOT_L2S_DIR=/data/user/0/com.linux_core/files/nh/distro/<distro>/.l2s \
  /data/user/0/com.linux_core/files/usr/bin/proot \
  --kill-on-exit \
  --link2symlink \
  --sysvipc \
  "--kernel-release=\\Linux\\localhost\\6.17.0-proot-distro\\6.17.0-proot-distro\\aarch64\\localdomain\\-1\\" \
  -L \
  --change-id=0:0 \
  --rootfs=/data/user/0/com.linux_core/files/nh/distro/<distro> \
  --cwd=/root \
  --bind=/dev \
  --bind=/proc \
  --bind=/sys \
  --bind=/dev/urandom:/dev/random \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/sys_empty:/sys/fs/selinux \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/loadavg:/proc/loadavg \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/stat:/proc/stat \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/uptime:/proc/uptime \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/version:/proc/version \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/vmstat:/proc/vmstat \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/sysctl_kernel_overflowuid:/proc/sys/kernel/overflowuid \
  --bind=/data/user/0/com.linux_core/files/nh/sysdata/<distro>/sysctl_kernel_overflowgid:/proc/sys/kernel/overflowgid \
  --bind=/data/user/0/com.linux_core/files/nh/shm/<distro>:/dev/shm \
  --bind=/data/app \
  --bind=/data/dalvik-cache \
  --bind=/data/misc/apexdata/com.android.art/dalvik-cache \
  --bind=/storage/self/primary:/mnt/sdcard \
  --bind=/storage/self/primary:/sdcard \
  --bind=/storage/self/primary:/storage/emulated/0 \
  --bind=/storage/self/primary:/storage/self/primary \
  --bind=/data/user/0/com.linux_core/files/tmp:/data/data/com.linux_core/files/tmp \
  --bind=/data/user/0/com.linux_core/files/usr \
  /bin/bash \
  -l
