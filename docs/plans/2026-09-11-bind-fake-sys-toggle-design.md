# Design: přepínač „Fake /proc & /sys" + `/data` bind v RootBridge

Datum: 2026-09-11
Status: schváleno uživatelem (varianta A)

## Problém

1. **Nesoulad kódu a docs.** `docs/proot-cmd-mod.md` tvrdí, že `NH_ISOLATED=1`
   automaticky znamená `NH_MINIMAL=1`, ale `BootModePersistence.bootModeFlags()`
   dává `I -> ("1" to "0")`. Docs dál u ISOLATED nezmiňují fake `/sys/fs/selinux`,
   ale `boot:build_binds()` ho tam (díky gate na `NH_MINIMAL=0`) přidává.
   Docs taky tvrdí, že ISOLATED binduje celý `/proc/sys`, kód binduje jen
   4 jednotlivé soubory.

2. **Chybí režim s reálným `/proc` + `/sys`.** Všechny dnešní režimy, které
   nejsou `M`, přepisují `/proc/version`, `/proc/stat`, `/proc/uptime`,
   `/proc/vmstat`, `/proc/loadavg`, 4 soubory v `/proc/sys` a `/sys/fs/selinux`.
   Navíc `--kernel-release=` falšuje `uname -r`. Nástroje, které zkoumají
   skutečný kernel/process view (Frida), potřebují originál. `M` je bez fake,
   ale zároveň ztrácí `--sysvipc` i `/dev` fixes, takže se hodí jen omezeně.

3. **`/data` není vidět.** PRoot běží jako app UID (`u0_a315`), takže
   `/data/data/<jiná app>` nepřečte (DAC 0700 + SELinux). Pod `sudo`
   (su_daemon re-enter pod reálným rootem) by čitelné bylo, ale cesta tam
   není nabindovaná.

## Rozhodnutí

Nezasahovat do sémantiky D/I/M. Místo toho **nezávislý přepínač**, který je
ortogonální ke všem módům:

| pref (`root_settings`) | default | efekt |
|---|---|---|
| `bind_fake_sys` | `true` | `1` = dnešní chování; `0` = žádný fake overlay |
| `bind_data` | `false` | `-b /data:/mnt/data` |

## Změny

### 1. `boot` skript (`app/src/main/assets/usr/bin/boot`)

Nová env proměnná `NH_FAKE_SYS` (default `1`):

- `PROOT_FLAGS_BASE` se rozdělí na `PROOT_SYSVIPC` a `PROOT_KERNEL_RELEASE`.
  `--sysvipc` zůstává vždy (kromě minimal), `--kernel-release=` se přidá jen
  když `NH_FAKE_SYS=1`.
- `setup_sysdata_shm()` — po kontrole `NH_MINIMAL` přibude kontrola
  `NH_FAKE_SYS=0` → `return 0` (PROOT_L2S_DIR se nastavuje před tím, zůstává).
- `build_binds()` — blok se sysdata/shm/selinux se gate-uje
  `NH_MINIMAL=0 && NH_FAKE_SYS=1`. Blok `/dev` fixes zůstává jen na
  `NH_MINIMAL=0`.

### 2. `ProotManager.kt`

- `NH_FAKE_SYS=${if (rootPrefs.getBoolean("bind_fake_sys", true)) "1" else "0"}`
  do `envVars` (vedle `NH_ISOLATED`/`NH_MINIMAL`).
- `extraMounts`: `if (bind_data) append(" -b /data:/mnt/data")`.

### 3. `ExecCore.kt` (agentí exec + sudo cesta)

- wrapper: `export NH_FAKE_SYS='...'` vedle `NH_EXTRA_MOUNTS`.
- `extraMounts`: stejný `bind_data` bind (default off → beze změny chování).

### 4. `RootBridgeTab.kt` (UI)

- nové stavy `bindData`, `fakeSys` + položka v seznamu bindů:
  `Data (root)` → `/data → /mnt/data (vidí jen sudo)`.
- samostatný checkbox **„Fake /proc & /sys"** s popiskem vysvětlujícím dopad
  (vypnuto = reálný kernel/`/proc`/`/sys`, např. pro Frida).
- obojí se propisuje do `root_settings` (platí při příštím startu session).

### 5. Docs

- `docs/proot-cmd-mod.md` přepsat podle skutečnosti (I ≠ minimal, seznam
  fake bindů, `--kernel-release` gate) + sekce o `NH_FAKE_SYS` a `bind_data`.
- `AGENTS.md` — krátká zmínka v §11 (pitfalls).

## Chování po změně (matice)

| | D | I | M | D/I + fake off |
|---|---|---|---|---|
| `-b /dev -b /proc -b /sys` | ✅ | ✅ | ✅ | ✅ |
| fake `/proc/*` + `/sys/fs/selinux` | ✅ | ✅ | ❌ | ❌ |
| `--kernel-release` (fake `uname -r`) | ✅ | ✅ | ❌ | ❌ |
| `--sysvipc` | ✅ | ✅ | ❌ | ✅ |
| `/dev` fixes | ✅ | ✅ | ❌ | ✅ |
| host cesty (`/system`,…) | ✅ | ❌ | ❌ | dle módu |

## Bezpečnost / rizika

- **Žádný host `mount --bind`** — bindy dělá PRoot v userspace, takže nemůže
  dojít k leaknutí mountů do globálního namespace (incident s 342 leaked mounts).
- Nemění se ownership/perms žádné systémové složky.
- `/data → /mnt/data` je default **off**; v ne-root seanci je obsah stejně
  nedostupný, smysl má jen pod `sudo`.
- `bind_fake_sys` default **true** ⇒ zpětná kompatibilita zachována.

## Test

1. `sh -n boot` (syntaxe).
2. Live v guestu (přes `ashell`):
   - `NH_FAKE_SYS=0 boot parrot -- /bin/cat /proc/version` → reálný kernel
   - `NH_FAKE_SYS=0 boot parrot -- /bin/ls /sys/fs/selinux` → reálný (Permission denied)
   - `NH_FAKE_SYS=1 boot parrot -- /bin/cat /proc/version` → fake 6.17.0-nethunter
   - `uname -r` v obou režimech
3. Build přes Modal.
