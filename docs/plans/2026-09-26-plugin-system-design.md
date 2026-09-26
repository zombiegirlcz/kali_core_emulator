# Design: NetHunter monorepo — „F-Droid pro hackery"

Datum: 2026-09-26
Status: návrh k diskusi
Autor kontext: zombiegirlcz

## Vize

Jedno **monorepo na GitHubu**, které funguje jako **F-Droid repo pro hackery**:
katalog APK aplikací (app marketplace) + katalog script/root pluginů (plugin
marketplace). Monolit `com.linux_core` se rozřízne na **malé neměnné jádro**
(zároveň = klient marketplace) + **pluginy**, které se instalují na vyžádání.

Model přebíráme z **F-Droid** (formát metadat/indexu/podpisu), ale infrastruktura
je **naše vlastní, nová** — ne cizí store. Rozlišení:
- **Cizí (oficiální NetHunter store):** `com.linux_core.yml` (F-Droid `fdroiddata`
  metadata) + `fastlane/metadata/android/en-US/` + submodul `nethunter-store-data`
  (GitLab) slouží jen k **odeslání žádosti o zabalení APK do jejich storu**. Není
  to naše úložiště, jen packaging request. → formát metadat **znovupoužijeme jako
  vzor**, ale distribuci si stavíme sami.
- **Naše (nové):** vlastní `fdroid/` katalog v monorepo + payloady na GitHub
  Releases. Nezávislé na oficiálním storu.
- Znovupoužitelné střípky, které už máme: Git LFS na `.apk`, cert pinning,
  host whitelist, download+verify infra (`RootfsManager`).

Zdroj pravdy = **git**. Resoluci závislostí a instalaci orchestruje **agent**
(13338), ale install je deterministický i bez něj (git = pravda, agent = pohodlí).

---

## Mapa současného stavu (co / kde / jak)

### Repo top-level
```
app/                    jednomodulová Android app (:app)
assets -> app/src/main/assets
magisk-modules/         anti_phantom, custom_usb_g2_setup, linux_core_keepalive, nh_cpuctl
tools/                  modal_build.py, mbuild (build JEN přes Modal)
docs/plans/             design dokumenty
fastlane/metadata/      Triple-T metadata (popisy, screenshoty) — pro CIZÍ store
com.linux_core.yml      F-Droid fdroiddata metadata (Builds matrix) — pro CIZÍ store
nethunter-store-data/   submodul (GitLab) — packaging request do oficiálního storu (ne naše infra)
```
- **git origin:** `github.com/zombiegirlcz/kali_core_emulator` (GitHub = pravda).
- **submodul:** `nethunter-store-data` → GitLab.

### Kotlin zdroj (app/src/main/java/com/linux_core), ~36 k řádků
| Balíček | soubory | řádky | Role |
|---|---:|---:|---|
| `core/rootfs` | 6 | 4464 | `ProotManager`, `RootfsManager`, boot mode, `RemoteRootfsCatalog` — **JÁDRO** |
| `core/vpn` | 12 | 4900 | VpnCaptureService, NatEngine, Firewall, Proxy, Peer, Log — **app plugin** |
| `core/terminal` | 8 | 2282 | TerminalActivity/Service, ShellDaemon, Share, Boot — **JÁDRO** |
| `core/mitm` | 3 | 1910 | TlsMitmEngine, MitmHttpParser/Store — **app plugin (s VPN)** |
| `core/ai` | 8 | 1369 | AIBrain, VerdictEngine, Offensive/Defense — **app plugin (s VPN)** |
| `core/device` | 9 | 1271 | Battery/Wifi/GPS/backup/git notifier — **JÁDRO (API)** |
| `core/widget` | 4 | 1081 | WidgetProvider — **asset/app plugin** |
| `core/assistant` | 7 | 1007 | Accessibility, NotifListener, DeviceAdmin, Voice — **app plugin** |
| `core/usb` | 2 | 757 | UsbHostManager, usb_bridge — **app/asset plugin** |
| `core/docker` | 2 | 733 | docker image extrakce — **JÁDRO (rootfs)** |
| `core/` root | 2 | — | `LocalApiServer` (3444 ř., ~110 endpointů), `UsbFdExporter` — **JÁDRO** |
| `ui/vpn` | 7 | 4399 | VPN taby — jde s VPN pluginem |
| `ui/terminal` | 7 | 3791 | terminál UI — JÁDRO |
| `security` | 10 | 1322 | Attestation, Cert, Biometric — JÁDRO |
| `bridge` | 1 | 104 | **`CoreBridgeService` (exported) + `ICoreBridge.aidl`** — plugin IPC ✅ |

### Nativní (cpp → Modal → assets/jniLibs)
| Zdroj | Artefakt | Třída |
|---|---|---|
| `su_daemon.c` | `assets/su_daemon` | **root plugin** |
| `su_wrapper.c` | `assets/su_wrapper` | **root plugin** |
| `cpuctl.c` | `magisk-modules/nh_cpuctl/system/bin/cpuctl` | **root plugin** |
| `shell_daemon.c` | `jniLibs/libshelldaemon.so` | JÁDRO (ashell adb) |
| `ashell_pty.c` | `assets/ashell_pty` | JÁDRO |
| `usb_bridge.c` / `usbfd_jni.c` | `assets/usb_bridge`, `jniLibs/libusbfd_exporter.so` | usb plugin |
| — | `jniLibs/liba/libio_utils/libcommon_native_jni/libadguard-*` | **VPN plugin** (AdGuard) |
| — | proot-static-*, loader-static-* (usr/bin) | JÁDRO |

### `nh` CLI (assets/nh, 3390 ř.) — dispatch je **hardcoded**
Kategorie: `system network vpn agent log device api desktop fix apps usb distro
cpu zkill float compat`. Každá má `*_dispatch()` + `case` v `main()`. Přidání
pluginu dnes = editace `nh`.

### `LocalApiServer` — endpoint skupiny (~110)
vpn(21), usb(17), accessibility(11), distro(8), shelldaemon(7), ashell(7),
device(6), rootfs(4), battery(4), app(4), wifi/volume/clipboard(3)…
→ VPN a USB endpointy odejdou s pluginy; distro/rootfs/shell/device/battery zůstanou.

### Existující stavební kameny pro marketplace (de-riskuje projekt)
1. **`ICoreBridge.aidl` + `CoreBridgeService` (exported)** — plugin↔jádro IPC
   už definované: `prootExec`, `hostShell`, `elfExec`, `getStatus`
   (`bridge_version`+`core_version`), `listDistros`. → **verzované API pro pluginy**.
2. **`sharedUserId="cz.nethunter.agent"`** — shared UID cesta hotová.
3. **`RootfsManager` + `RemoteRootfsCatalog`** — HTTPS download, SHA256 verify,
   host whitelist, tar.xz extrakce, `Flow<Int>` progress. → **vzor pro fetch payloadu**.
4. **F-Droid metadata formát** (`com.linux_core.yml` + fastlane) — máme z něj
   vzor katalogu (i když dnes cílí na cizí store, ne na náš).
5. **Git LFS** na `.apk` — payload distribuce připravená.
6. **`magisk-modules/`** — seed root plugin repa.

---

## Cílový monorepo layout

```
kali_core_emulator/                (GitHub, monorepo)
├── core/                          přejmenované z app/ — jádro + marketplace klient
│   └── ... (ProotManager, terminal, LocalApiServer, bridge, security)
├── plugins/
│   ├── vpn/                       :plugins:vpn  → APK (app plugin)
│   ├── agent/                     :plugins:agent → APK (AI agent démon)
│   ├── assistant/                 :plugins:assistant → APK
│   ├── cpu/                       asset plugin (nh.d + boot.d, žádný gradle modul)
│   ├── usb/                       asset/app plugin
│   └── fix/                       asset plugin
├── root/                          root pluginy (bývalé magisk-modules/)
│   ├── su-bridge/                 su_daemon + su_wrapper + cpp/
│   ├── nh_cpuctl/
│   ├── anti_phantom/
│   └── ...
├── fdroid/                        „F-Droid repo" — generovaný katalog
│   ├── index.json                 podepsaný index (apps + pluginy + hash + sig)
│   ├── metadata/*.yml             per-balík (jako com.linux_core.yml)
│   └── (payloady přes GitHub Releases, ne v gitu)
├── tools/                         modal_build.py rozšířený o per-modul build
└── settings.gradle.kts            include(":core", ":plugins:vpn", ":plugins:agent", …)
```
Gradle: dnes jednomodul `:app`. Cíl = multi-modul. Sdílené API (`ICoreBridge`,
konstanty portů, plugin manifest parser) → nový modul `:sdk` konzumovaný jádrem
i app pluginy.

---

## Tři třídy pluginů

### A) Asset plugin (bez APK)
Skript + binárky + soubory do guestu/`filesDir`. Kandidáti: `cpu`, `usb`, `fix`,
desktop/X11, MITM certy. Runtime: žádný proces navíc, běží v guestu / přes bridge.

### B) App plugin (APK, shared UID)
Samostatné APK, stejný keystore, `sharedUserId="cz.nethunter.agent"`, komunikace
přes `CoreBridgeService` (AIDL) nebo 1337. Kandidáti: **VPN**(+MITM+AIBrain),
**AI agent**, **assistant** (accessibility/notif/voice — těžká oprávnění zvlášť),
`kali_GUI` (už existuje = první app plugin).

### C) Root plugin (Magisk modul / su-deploy)
Potřebuje skutečný root. Kandidáti: `su_daemon`+`su_wrapper` (Root Bridge),
`cpuctl` (`nh_cpuctl`), `anti_phantom`, `custom_usb_g2_setup`,
`linux_core_keepalive`, `parrot_elf_loader` (zmíněn v `elfExec`). Install = Magisk
flash / su-deploy; bez rootu `nh plugin install` odmítne s hláškou. Jádro drží jen
tenký deploy-hook v `ProotManager` (idempotentní `su_wrapper` shadow zůstává).

## Co zůstává v jádře (needinstaluje se)
PRoot binárky+`boot`, `ProotManager`, `RootfsManager`, docker extrakce, terminál
(+ShellDaemon), `LocalApiServer` (most 1337) + tenký Root Bridge deploy-hook,
`CoreBridgeService`, `ShizukuManager`, `security/`, auto-start, `nh` runtime +
dynamický dispatch registr.

---

## Manifest pluginu (`.nh/plugin`, plochý KEY=VALUE — jako `.nh/manifest`)
```
NH_PLUGIN_NAME=vpn
NH_PLUGIN_VERSION=4.5.0
NH_PLUGIN_KIND=app              # asset | app | root
NH_PLUGIN_NEEDS_ROOT=0
NH_PLUGIN_DEPS=                 # mezerami oddělené pluginy
NH_PLUGIN_MIN_CORE=20           # min versionCode jádra
NH_PLUGIN_MAX_CORE=             # volitelný strop
NH_PLUGIN_BRIDGE_API=1          # min bridge_version z ICoreBridge
NH_PLUGIN_ABI=aarch64           # nebo "any"
# asset:
NH_PLUGIN_FILES=nh.d/cpu boot.d/cpu.sh bin/cpuctl
NH_PLUGIN_NH_DISPATCH=cpu
NH_PLUGIN_HOOK_INSTALL=hooks/install.sh
NH_PLUGIN_HOOK_REMOVE=hooks/remove.sh
# app:
NH_PLUGIN_PKG=com.linux_core.vpn
NH_PLUGIN_API_PROBE=/vpn/status
# root:
NH_PLUGIN_MAGISK_ID=nh_cpuctl
```

## „F-Droid" katalog (`fdroid/index.json`)
Podepsaný index (jarsigner keystorem jako F-Droid), jeden záznam na balík+verzi:
```json
{
  "repo": {"name":"NetHunter Hacker Store","address":"github releases base url"},
  "packages": {
    "com.linux_core.vpn": {"kind":"app","versions":[
      {"versionCode":4500,"sha256":"…","sig":"…","url":"…/vpn-4.5.0.apk",
       "minCore":20,"bridgeApi":1,"deps":[]}]},
    "cpu": {"kind":"asset","versions":[
      {"version":"1.2.0","sha256":"…","url":"…/cpu-1.2.0.tar.xz","minCore":20}]}
  }
}
```
Metadata pro zobrazení (popis, ikona, screenshoty) = fastlane struktura per balík.

## Distribuce
- **Registr/index/metadata** → git (monorepo `fdroid/`), verzované a diffovatelné.
- **Payloady (APK, tar.xz binárky)** → **GitHub Releases assets** (nejdou proti
  LFS kvótě, přímé URL, tag = verze). Whitelist host = github.com/objects.githubusercontent.com.
- **Ne vlastní server** — ušetří cert pin údržbu (máš expiraci 2027-12-31 jako dluh).

## `nh plugin` příkazy
```
nh plugin list | search [q] | info <name>
nh plugin install <name>[@ver]   # resolve deps → fetch → verify sha+sig → deploy → hook
nh plugin remove <name>
nh plugin update [name]
nh plugin enable | disable <name>
```
- **asset install:** fetch → SHA verify → rozbal do `$FILES_DIR/plugins/<name>` →
  symlinky do `nh.d`/`boot.d` → guest deploy → hook.
- **app install:** APK → SHA + **podpis stejným keystorem** (jinak shared UID
  odmítne) → `pm install` přes Shizuku/su/dialog.
- **root install:** vyžaduje root → SHA → Magisk flash/su-deploy → aktivace hooku.
- **remove:** app=`pm uninstall`; asset=hook+smaž strom (nikdy přes symlink ven,
  vzor `deleteRootfsTree`); root=odflash Magisk + odstranit hook.

## Dynamický `nh` dispatch
Jádro nese kmenové dispatche; `nh` na startu projde `$FILES_DIR/nh.d/*` a
zaregistruje `<name>) source .../<name> ;;`. `boot.d/*.sh` se nasourcuje v `boot`
(nový hook adresář, version-gated jako usrtools).

---

## Migrace — fáze (robustně, low-risk → high)

1. **`:sdk` modul + plugin API kontrakt.** Vytáhni `ICoreBridge`, port konstanty,
   plugin manifest parser do `:sdk`. Jádro i pluginy ho konzumují. Verzuj
   `bridge_version`. **Žádná extrakce funkcí** — chování beze změny.
2. **`nh plugin` infra.** Dispatch + `nh.d`/`boot.d` loader + `index.json` parser
   (`RemotePluginCatalog.kt`, vzor `RemoteRootfsCatalog`) + prázdný `fdroid/` repo
   + `fdroid gen` skript v `tools/`. Jádro se chová identicky.
3. **První asset plugin = `cpu`.** Nejmenší, izolovaný (nh cpu, boot cpu bloky,
   `cpu_all.conf`). Ověří celý asset řetěz na nízkém riziku. `cpuctl` část →
   root plugin `nh_cpuctl`.
4. **Root repo reorg.** `magisk-modules/` + `su_daemon`/`su_wrapper` (+cpp+Modal
   kroky) → `root/`. `ProotManager` zúžit na tenký deploy-hook.
5. **První app plugin = VPN.** `core/vpn`+`core/mitm`+`core/ai`+`ui/vpn`+AdGuard
   jniLibs → `:plugins:vpn` (`com.linux_core.vpn`, shared UID, most přes bridge/1337).
   Odstranit VPN endpointy z jádra, přesměrovat přes bridge.
6. **AI agent** (`nethunter_agent.py`, 13338) jako app plugin.
7. **assistant** (accessibility/notif/voice/deviceadmin) jako app plugin — izoluje
   nejcitlivější oprávnění mimo jádro.
8. **Zbytek asset:** `usb`, `fix`, `desktop`, `widget`.
9. **Katalog live:** `fdroid gen` v CI (GitHub Actions) při release tagu →
   nahraje payloady na Releases, přegeneruje `index.json`, podepíše.

---

## Bezpečnost (nepřekročitelné)
- **App/root pluginy podepsané `release.jks`** — jiný podpis rozbije shared UID.
  Index smí nést jen důvěryhodně podepsané balíky; ověř podpis před instalací.
- **Index podepsaný** (jarsigner), klient ověří před parsováním (F-Droid model).
- SHA256 každého payloadu z indexu; HTTPS + rozšířit host whitelist o
  github.com/objects.githubusercontent.com.
- `pm install/uninstall` přes `ShizukuManager` fallback řetěz.
- Asset deploy nikdy nemění perms systémových složek (bootloop pravidlo).
- Blocklist destruktivních příkazů (su_daemon+LocalApiServer+ashell) se pluginem
  neobchází; hooky běží pod stejným režimem.
- Build **jen přes Modal**; per-modul APK build kroky do `modal_build.py`.

## Rozhodnutá (dřívější diskuse)
- **GitHub** (ne GitLab, ne vlastní server) — zdroj pravdy jádra, piny udržované.
- **Monorepo** — jádro + app marketplace + plugin marketplace pohromadě.
- **GitHub Releases** na payloady, git na index+metadata.

## Otevřené otázky
1. `nethunter-store-data` (GitLab submodul) je **packaging request do cizího
   oficiálního storu**, ne náš katalog — nechat beze změny jako publikační kanál
   pro hlavní APK, náš `fdroid/` katalog stavět nezávisle. (Potvrdit, že chceme
   hlavní APK dál nabízet i přes oficiální store, nebo jen přes náš.)
2. `:sdk` publikovat jako maven artefakt (pro externí autory pluginů), nebo jen
   interní modul?
3. Offline fallback: bundlovat „core" asset pluginy (cpu) rovnou v jádru APK?
4. Verze bridge API: jak řešit breaking change (plugin `MIN_CORE` + `BRIDGE_API`
   strop — stačí, nebo potřeba i deprecation okno?).
