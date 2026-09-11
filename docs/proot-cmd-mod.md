# NetHost proot command mods for com.linux_core

Reference PRoot bindů a přepínačů, které reálně sestavuje `boot` skript
(`assets/usr/bin/boot`). Vše je pro app `com.linux_core`, tedy
`/data/user/0/com.linux_core/files/...` — **žádné** Termux cesty.

Zdroj pravdy je kód, ne tenhle soubor:

| kde | co dělá |
|---|---|
| `boot:setup_sysdata_shm()` | generuje fake `/proc` soubory, `sys_empty`, `shm/<distro>` |
| `boot:build_binds()` | skládá `-b` flagy podle módu a přepínačů |
| `boot:build_path()` | sestaví guest `PATH` z existujících dirů |
| `ProotManager.extraMounts` | `root_settings` přepínače → `NH_EXTRA_MOUNTS` |
| `ProotManager` env | `NH_ISOLATED`, `NH_MINIMAL`, `NH_FAKE_SYS`, `NH_BOOT_MODE` |

## Env proměnné (vstup do `boot`)

| proměnná | default | význam |
|---|---|---|
| `NH_ISOLATED` | `0` | `1` = žádné host bindy, jen `/dev /proc /sys` |
| `NH_MINIMAL` | `0` | `1` = holý proot (bez `--sysvipc`, `--kernel-release`, fake sysdata, `/dev` fixes) |
| `NH_FAKE_SYS` | `1` | `0` = **žádný fake overlay** `/proc` + `/sys` (viz níže) |
| `NH_MOUNT_STORAGE` | `0` | `1` + `NH_ISOLATED=0` → `-b /sdcard` |
| `NH_EXTRA_MOUNTS` | — | dodatečné `-b` flagy z `root_settings` |
| `NH_EXTRA_BINDS` | — | dodatečné `-b` flagy z `nh distro login --bind` |
| `NH_BOOT_MODE` | — | `D`/`I`/`M`, jen informativní (do container JSONu) |

Mapování z UI (`BootModePersistence.bootModeFlags()`):

```
D  ->  NH_ISOLATED=0  NH_MINIMAL=0
I  ->  NH_ISOLATED=1  NH_MINIMAL=0     ← pozor: izolovaný NENÍ minimal
M  ->  NH_ISOLATED=1  NH_MINIMAL=1
```

> Starší verze tohohle dokumentu tvrdila, že `NH_ISOLATED=1` automaticky
> znamená `NH_MINIMAL=1`. **To není pravda** — flagy jsou nezávislé (viz kód
> výše). Stejně tak izolovaný mód **má** fake `/sys/fs/selinux`, protože ten
> blok je gate-ovaný jen na `NH_MINIMAL`.

## PROOT flagy

```sh
PROOT_FLAGS_BASE="-v 0 --kill-on-exit -0 --link2symlink -L"
PROOT_SYSVIPC="--sysvipc"
PROOT_KERNEL_RELEASE="--kernel-release=\\Linux\\localhost\\6.17.0-nethunter\\..."

# minimal = jen BASE
# non-minimal = BASE + SYSVIPC (+ KERNEL_RELEASE jen když NH_FAKE_SYS=1)
```

`--kernel-release` mění `uname -r`. Je součástí fake view, takže s
`NH_FAKE_SYS=0` se vynechá a `uname -r` vrátí skutečný kernel Androidu.

## Režimy — co se binduje

| | **D** default | **I** isolated | **M** minimal |
|---|---|---|---|
| `-b /dev -b /proc -b /sys` | ✅ | ✅ | ✅ |
| fake `/proc/{loadavg,stat,uptime,version,vmstat}` | ✅ | ✅ | ❌ |
| fake `/proc/sys/kernel/{cap_last_cap,overflowuid,overflowgid}` | ✅ | ✅ | ❌ |
| fake `/proc/sys/fs/inotify/max_user_watches` | ✅ | ✅ | ❌ |
| fake `sys_empty:/sys/fs/selinux` | ✅ | ✅ | ❌ |
| `shm/<distro>:/dev/shm` | ✅ | ✅ | ❌ |
| `/dev` fixes (`random`, `fd`, `stdin`, `stdout`, `stderr`) | ✅ | ✅ | ❌ |
| `--sysvipc` | ✅ | ✅ | ❌ |
| `--kernel-release` (fake `uname -r`) | ✅ | ✅ | ❌ |
| host cesty `/apex /odm /product /system /system_ext /vendor` | ✅ | ❌ | ❌ |
| `/linkerconfig/*.txt`, `/plat_property_contexts`, `/property_contexts` | ✅ | ❌ | ❌ |
| `/storage`, `/storage/emulated/0`, `/sdcard`, `/mnt/sdcard`, `/data/app`, `/data/dalvik-cache` | ✅ | ❌ | ❌ |
| app diry `files/tmp`, `ipc → /run/host_ipc`, `share → /root/share` | ✅ | ❌ | ❌ |

Reálný Android `/sys` je bindovaný **ve všech** módech (`-b /sys` je
v základním řádku `build_binds()`). Fake je nad ním jen `/sys/fs/selinux`
(prázdný adresář).

## `NH_FAKE_SYS` — originál místo fake dat

Přepínač v RootBridge UI (**Fake /proc & /sys**, pref `bind_fake_sys`,
default **zapnuto**). Když je vypnutý (`NH_FAKE_SYS=0`):

- `setup_sysdata_shm()` přeskočí generování fake souborů
- `build_binds()` nepřidá žádný sysdata bind ani `sys_empty:/sys/fs/selinux`
- `--kernel-release` se vynechá → `uname -r` hlásí skutečný kernel
- **zachová se** `--sysvipc` i `/dev` fixes (v ne-minimal módech)

Guest tak vidí reálný `/proc/version`, `/proc/stat`, `/proc/sys` a
`/sys/fs/selinux` (a tudíž i reálná oprávnění — typicky `Permission denied`
pro untrusted_app). Používá se pro nástroje zkoumající skutečný
kernel/process view (Frida, ptrace, kernel moduly).

## Přepínače z RootBridge (`root_settings` → `NH_EXTRA_MOUNTS`)

Platí při **příštím** startu session.

| pref | default | bind |
|---|---|---|
| `bind_system` | `true` | `/system:/mnt/system` |
| `bind_vendor` | `false` | `/vendor:/mnt/vendor` |
| `bind_tmp` | `false` | `/data/local/tmp:/mnt/tmp` |
| `bind_usb` | `true` | `/dev/bus/usb:/mnt/usb` (jen když zařízení existuje) |
| `bind_bluetooth` | `false` | `/sys/class/bluetooth` + `/data/misc/bluetooth` |
| `bind_app` | `false` | `/data/user/0/com.linux_core:/mnt/app` |
| `bind_aiapp` | `false` | `/data/user/0/com.kali.aiassistant:/mnt/aiapp` |
| `bind_data` | `false` | `/data:/mnt/data` |
| `bind_fake_sys` | `true` | není bind → `NH_FAKE_SYS` |

### `bind_data` — `/data` pod rootem

PRoot běžně jede jako app UID (`u0_a315`), takže `/data/data/<jiná app>`
nepřečte (DAC `0700` + SELinux). Bind `-b /data:/mnt/data` cestu zpřístupní,
ale **obsah uvidíš jen v sudo seanci** — `su_daemon` re-entruje `boot` pod
reálným rootem (viz `ExecCore`, kde `bind_data` zároveň vynutí `findSu()`).
V ne-root seanci je `/mnt/data` prázdný / nepřístupný.

Záměrně se **nedělá** hostitelský `mount --bind` — PRoot bindy jsou jen
userspace překlad cest, takže nemůže dojít k leaknutí mountů do globálního
namespace (incident s 342 leaked mounts, 2026-08-21).

## Poznámky

- `/usr/sbin/find` musí být symlink na `find` (ne `rg`).
- `LD_LIBRARY_PATH` se v `hostShellEnv()` nikdy nepřidává (SIGBUS).
- `--link2symlink` je povinný (apt/dpkg zálohy přes `link()`); pozor, mění
  git hardlinky na `.l2s` symlinky → `nh fix git`.
