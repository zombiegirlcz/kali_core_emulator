# Design: pluginový systém + vlastní balíčkový manažer (`nh plugin`)

Datum: 2026-09-26
Status: návrh k diskusi

## Cíl

Rozříznout monolit `com.linux_core` na **malé neměnné jádro** + **pluginy**,
které se instalují na vyžádání. Zdroj pravdy pro pluginy je **git**, resoluci
závislostí a install orchestruje **agent** (13338), ale samotný install musí být
deterministický i bez agenta (agent = pohodlí, ne podmínka).

## Zásadní rozhodnutí: dvě třídy pluginů

Ne všechno se hodí do APK. Rozdělení podle toho, co plugin doopravdy je:

### A) Asset plugin (bez APK)
Čistě `nh` rozšíření + nativní binárky + soubory do guestu / `filesDir`. Nasazuje
se rozbalením do stromu, žádná instalace přes PackageManager.

- **Kandidáti:** `cpu` (nh cpu + `cpuctl` Magisk modul + cpu funkce v `boot`),
  `usb` tools, `fix` tools, X11/desktop helpery, MITM CA/certy.
- **Proč ne APK:** `nh cpu` je jen skript + `cpuctl` binárka; APK by přidalo jen
  režii (podpis, PackageManager, IPC) bez užitku.
- **Runtime:** žádný nový proces navíc; jede v guestu / přes `su_daemon`.

### B) App plugin (samostatné APK, `sharedUserId`)
Samostatná Android appka se stejným keystorem a `sharedUserId="cz.nethunter.agent"`
→ sdílí `filesDir` skupinu a UID, komunikuje přes `LocalApiServer` (1337),
bound service nebo `ContentProvider`.

- **Kandidáti:** **VPN** (+ MITM + `AIBrain`), **AI agent démon** (13338),
  `kali_GUI` (už dnes samostatné APK — první existující „plugin").
- **Proč APK:** dlouho běžící služby s vlastním lifecyclem, notifikacemi,
  VpnService oprávněním; profitují z Android sandboxu a updatů přes store.

## Co zůstává v jádře (neplugin)

„Kernel" NetHunteru — bez něj se nic nespustí, tudíž se needinstaluje:

- `ProotManager`, `RootfsManager`, `boot`, PRoot statické binárky + loader
- `TerminalActivity/Service`, Termux emulátor
- `LocalApiServer` (most 1337) + `su_daemon` / Root Bridge
- `ShizukuManager`, `BootReceiver`/auto-start
- `nh` runtime + dispatch registr (viz níže)

## Manifest pluginu (`.nh/plugin`)

Plochý `KEY=VALUE` — stejný styl jako `.nh/manifest` u rootfs, parsovatelný `sed`em:

```
NH_PLUGIN_NAME=cpu
NH_PLUGIN_VERSION=1.2.0
NH_PLUGIN_KIND=asset            # asset | app
NH_PLUGIN_DEPS=                 # mezerami oddělené názvy pluginů
NH_PLUGIN_MIN_CORE=20           # min versionCode jádra
NH_PLUGIN_ABI=aarch64           # nebo "any"
# asset:
NH_PLUGIN_FILES=nh.d/cpu bin/cpuctl boot.d/cpu.sh
NH_PLUGIN_NH_DISPATCH=cpu       # název, který se zaregistruje do `nh`
NH_PLUGIN_HOOK_INSTALL=hooks/install.sh
NH_PLUGIN_HOOK_REMOVE=hooks/remove.sh
# app:
NH_PLUGIN_APK=vpn.apk
NH_PLUGIN_PKG=com.linux_core.vpn
NH_PLUGIN_API_PROBE=/vpn/status # endpoint na 1337 pro health-check
```

SHA256 payloadu je v indexu registru (níže), ne v manifestu.

## Registr (git)

Nový git repo `nethunter-plugins` (analogie k `nethunter-store-data`), Git LFS pro
`.apk`/binárky. Struktura:

```
index.tsv                       # jeden řádek na plugin+verzi
plugins/cpu/1.2.0/.nh/plugin
plugins/cpu/1.2.0/nh.d/cpu
plugins/cpu/1.2.0/bin/cpuctl
plugins/vpn/4.5.0/.nh/plugin
plugins/vpn/4.5.0/vpn.apk       # LFS
```

`index.tsv` (appka ho jen regexem čte, nespouští — jako u `RemoteRootfsCatalog`):

```
name	version	kind	abi	sha256	min_core	path
cpu	1.2.0	asset	aarch64	<sha>	20	plugins/cpu/1.2.0
vpn	4.5.0	app	aarch64	<sha>	20	plugins/vpn/4.5.0
```

## `nh` dispatch — dynamický registr

Dnes je dispatch hardcoded (`cpu) cpu_dispatch ;;`). Aby šel plugin přidat bez
úpravy `nh`:

- Jádro nese jen kmenové dispatche (system, network, distro, fix, …).
- Instalované asset pluginy skládají soubory do `$FILES_DIR/nh.d/<name>`.
- `nh` na startu projde `$FILES_DIR/nh.d/*` a zaregistruje `<name>) source .../<name> ;;`.
- Guest binárky pluginu → `/usr/local/bin` přes `ProotManager` deploy, `boot.d/*.sh`
  se nasourcuje v `boot` (nový hook adresář, gate-ovaný jako usrtools version gate).

## `nh plugin` — příkazy

```
nh plugin list                  # nainstalované + verze
nh plugin search [q]            # z index.tsv registru
nh plugin info <name>
nh plugin install <name>[@ver]  # resolve deps → fetch → verify sha → deploy
nh plugin remove <name>         # hook remove + smaž soubory / uninstall APK
nh plugin update [name]         # diff verzí proti registru
nh plugin enable|disable <name> # bez smazání
```

- **asset install:** `git`/HTTPS fetch payloadu → ověř SHA256 → rozbal do
  `$FILES_DIR/plugins/<name>` → symlinky do `nh.d`/`boot.d` → guest deploy → hook.
- **app install:** stáhni `.apk` → ověř SHA256 + **podpis stejným keystorem**
  (jinak `sharedUserId` odmítne) → `pm install` (přes Shizuku/su, jinak dialog).
- **remove:** app = `pm uninstall`; asset = hook + smazání stromu (nikdy nemazat
  přes symlink ven — použít `deleteRootfsTree` vzor).

## Role agenta vs. git

- **git = zdroj pravdy.** `nh plugin install` funguje čistě deterministicky:
  fetch z indexu → SHA verify → deploy. Žádný agent není nutný.
- **agent = pohodlná vrstva nad tím.** „Nainstaluj mi něco na cracking WPA" →
  agent přeloží na `nh plugin install aircrack-suite`, vyřeší závislosti,
  navrhne. Agent nikdy neobchází SHA/podpis verifikaci.

## Bezpečnost (nepřekročitelné)

- **App pluginy musí být podepsané `release.jks`** — jiný podpis rozbije shared UID.
  Registr smí obsahovat jen appkou-důvěryhodně podepsané APK; ověř podpis před `pm install`.
- SHA256 každého payloadu z indexu; HTTPS + host whitelist (rozšířit
  `RootfsManager` whitelist o registr host).
- `pm install`/`uninstall` jde přes `ShizukuManager` fallback řetěz, ne přímo.
- Asset deploy nikdy nemění perms systémových složek (bootloop pravidlo).
- Blocklist destruktivních příkazů se pluginem nesmí obejít — hooky běží pod
  stejným `ashell`/`su_daemon` režimem.

## Migrace — fáze

1. **Infra (žádná extrakce):** `nh plugin` dispatch + `nh.d`/`boot.d` loader +
   `index.tsv` parser (`RemotePluginCatalog.kt`) + prázdný registr. Jádro se
   chová identicky.
2. **První asset plugin = `cpu`** (nejmenší, dobře izolovaný: nh cpu funkce,
   `boot` cpu bloky, `cpuctl` Magisk, `cpu_all.conf`). Ověří celý řetěz na
   nízkém riziku.
3. **První app plugin = VPN** (už plánuješ oddělit). Vytáhni `vpn/`, `mitm/`,
   `ai/` (AIBrain) do `com.linux_core.vpn`, shared UID, most přes 1337.
4. **AI agent démon** jako app plugin (13338 už autentizovaný).
5. **Zbytek asset pluginů:** `usb`, `fix`, `desktop`.

## Otevřené otázky

1. Registr na GitLabu (jako store-data) nebo GitHubu (zdroj pravdy jádra)?
2. Verzování pluginů proti `versionCode` jádra — jen `MIN_CORE`, nebo i strop?
3. Sdílet `filesDir` mezi app pluginy přes shared UID, nebo každý svůj + IPC?
4. Offline režim: bundlovat „core" pluginy (cpu) rovnou v APK jako fallback?
