# AGENTS.md — NetHunter AI Operator (komprimováno 2026-09-10)

> Provozní kontrakt pro agenty. **Neopakovat zde, co už je jinde:**
> funkce + celé `nh` CLI → `README.md`; MITM → `assets/nethunter_docs.md`;
> bezpečnost → `docs/SECURITY_AUDIT.md`; velké změny → `docs/plans/`.
> S uživatelem komunikuj česky.

## 0. Nepřekročitelná pravidla

- **ZÁKAZ LOKÁLNÍHO BUILDU** — nikdy `./gradlew …` lokálně. Build **jen** přes Modal (`zsh mbuild …`).
- **Nikdy neměnit ownership/perms systémových složek na zařízení** (rekurzivní `chmod`/`chown` na
  `/system`, `/data`, …) → bootloop incident 2026-08-23. Deploy jen do app `filesDir`, `/data/adb`
  nebo přes Magisk modul.
- **Zdroj pravdy je GitHub** (`zombiegirlcz/kali_core_emulator`, branch `dev`). Před buildem vždy
  `git commit && git push`; `mbuild sync` klonuje/pulluje **z GitHubu**, ne z telefonu.
- Neměnit: `--link2symlink` v proot (bez něj je apt/dpkg rozbitý), pořadí načítání `jniLibs`,
  `useLegacyPackaging = true`.
- Nebezpečné operace (`rm -rf /`, `mkfs`, `reboot`, …) jsou blokované dvakrát (`su_daemon` blocklist +
  `LocalApiServer` + ashell) — neobcházet.

## 1. Build & ověření (VŽDY Modal)

```zsh
# 1) commit + push na GitHub (jinak se změny neprojeví)
# 2) sync   = git clone/pull zdroje na Modal straně (nikdy upload z telefonu)
zsh mbuild sync

zsh mbuild all      # sync + SMART inkrementální build (proot/native/gradle) → APK
zsh mbuild build    # jen Gradle build + stažení APK
zsh mbuild native   # jen NDK/C compile + pull_full_assets()
zsh mbuild smart    # inkrementální build bez sync
zsh mbuild clean    # smaže src + gradle-cache na Volume
```

- APK se stahuje na `~/Download/kali_core.apk` (na Volume `kali-build-data`, `builds/app-debug.apk`).
- `pull_full_assets()` stahuje **celý** `app/src/main/assets/` rekurzivně z Volume → přepíše lokální verze.
- **Pravidlo: sync se dělá vždy zvlášť, build nikdy nevolá sync.**
- Logcat bez ADB (z hostujícího shellu/guestu): `nethunter-log [-n N] [-g VZOR]`
  (nasazuje `ProotManager` do guest `/usr/local/bin/`; HTTP `GET /app/logs?limit=N`).
- Diagnostická binárka `nethunter-log` je Python skript, ne Kotlin — nehledat v dexu.

## 2. Identita a podepisování

| Atribut | Hodnota |
|---|---|
| Balíček | `com.linux_core` (**ne** `cz.hackai.nethunter_ai_operator`) |
| Zdroj | `app/src/main/java/com/linux_core/` |
| Verze | `versionCode = 20`, `versionName = "4.5-MULTI-ROOTFS"` (`app/build.gradle.kts`) |
| minSdk / targetSdk | 28 / 28 (ne 33/36 — `copilot-instructions.md` je zastaralé) |
| Java/Kotlin | JVM 17 (`sourceCompatibility = JavaVersion.VERSION_17`), Kotlin `official` styl |
| BuildConfig | `ENABLE_MITM=false`, `ENABLE_ATTESTATION=true` (default v `app/build.gradle.kts`) |
| Podpis | `app/debug.jks` — debug i release **stejný** keystore (veřejný, jen pro vývoj — není to tajný release klíč) → `adb install -r` bez odinstalace |
| Alias/heslo | `debugKey` / `password123` (env: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) |

Před každou distribuovanou verzí navyš `versionCode`.

## 3. Architektura

Jednomodulová app (`:app`). Spouští Kali/ParrotOS
v nerootovaném PRoot kontejneru (Termux terminál) s AdGuard C++ VPN, TLS MITM a X11 serverem.
GUI není součást core — desktop renderuje **externí** NetHunter X11 Launcher (`kali_GUI` app).

### Runtime porty (host loopback)

| Port | Služba |
|---|---|
| 1337 | `LocalApiServer` — REST most (baterka, toast, wifi, GPS, schránka, VPN, USB, `/shell`, `/distro/*`) |
| 13339 | VPN bypass proxy (`http(s)_proxy` pro guest → obchází AdGuard) |
| 6000/tcp | X11 server **Xvfb :0** v guestu (`DISPLAY=:0`, viewer `127.0.0.1:6000`) |
| 13340 | `ashell_pty` (PTY/pipe relay, jen vlastní UID / root / 2000) |
| 13341 | `shell_daemon` (uid 2000, token) |

### Klíčové třídy

| Třída | Role |
|---|---|
| `MainActivity` | Compose UI: rootfs download/extrakce, terminál, VPN centrum, auto-start toggle |
| `ProotManager` | Arch detekce, deploy PRoot/loader + `boot` launcheru a helper skriptů do guestu |
| `RootfsManager` | OkHttp download + commons-compress tar.xz extrakce, `Flow<Int>` průběh |
| `TerminalActivity` / `TerminalService` | Termux `TerminalView`, session lifecycle, headless session (`view=null`) |
| `BackgroundBoot` / `BootReceiver` | Auto-start po bootu / `MY_PACKAGE_REPLACED` → headless session s cronem |
| `FloatingTerminalService` | Overlay plovoucí terminál (`nh float`) |
| `ShareReceiverActivity` | „Open with"/share → `filesDir/share` → guest `~/share` |
| `VpnCaptureService` / `VpnNatEngine` | VPN lifecycle, TCP/UDP NAT, QUIC gating, packet forwarding |
| `TlsMitmEngine` / `TlsClientHelloParser` | TLS MITM (`TlsMitmSession`), SNI extrakce, dešifrovaný provoz |
| `AIBrain` / `AIBrainWorker` / `VerdictEngine` | ONNX klasifikace toků (`vpn_brain_v7.onnx`, LightGBM) |
| `VpnLogManager` / `VpnFirewallManager` / `VpnProxyManager` / `VpnPeerManager` | logy, IP blocklist, custom `IP:Port` proxy, Mesh VPN |
| `ShellDaemonClient` / `shell_daemon` | Privilegované příkazy bez rootu (uid 2000, `ashell adb`) — nahradilo odstraněný ShizukuManager (2026-09-19) |
| `UsbHostManager` / `usb_bridge.c` / `usbfd_jni.c` | Raw USB pro mtkclient/EDL (`/usb/stream` binární frame protokol) |
| `core/` je ~66 souborů, `ui/` 8, `security/` 10 — nevyjmenovávat všechny, viz README |

### PRoot binární strategie

- **Jen STATICKÉ buildy** z kořene `assets/`: `proot-static-{aarch64,arm,i686,x86_64}` +
  `loader-static-*`. Dynamické (`proot-*`, `loader-*`, `libtalloc-*.so`) byly z repa odstraněny
  **2026-09-08** (`ProotManager.deployArchBinaries`).
- `talloc` je do `proot-static-*` slinkovan **staticky** (`libtalloc.a`, viz
  `tools/modal_build.py`); žádný `libtalloc.so.2` se za běhu nenasazuje.
- Launcher je univerzální `assets/usr/bin/boot` (re-entry mód `boot -- <args…>`, `-0 --kill-on-exit`,
  `--link2symlink`); nasazuje se do `$PREFIX/bin/boot`.
- Guest bindy zahrnují `$FILES_DIR/share → /root/share`, `$FILES_DIR/ipc → /run/host_ipc`.

### jniLibs struktura

`app/src/main/jniLibs/arm64-v8a/` = AdGuard knihovny. **Na pořadí načítání záleží:**
`liba` → `libio_utils` → `libcommon_native_jni` → `libadguard-core` → `libadguard-dns`.
Ostatní ABI (`x86`, `x86_64`, `armeabi-v7a`) obsahují jen PRoot.

## 4. Native moduly → assets (PRAVIDLO)

Nativní C moduly (`app/src/main/cpp/*.c`) se kompilují na Modalu a **musí být vždy v APK**:

1. **Přidej kompilační krok** do `build_native()` (resp. `_build_native_bin`/`_build_native_lib`/
   `_build_usrtools`) v `tools/modal_build.py` — NDK cross-compile → `assets/` (např.
   `su_wrapper`, `usb_bridge`), `assets/usr/bin/` (host binárky — `su_daemon`, `ashell_pty`; patří
   do host toolchainu, ne do rootu `filesDir`) nebo `jniLibs/arm64-v8a/` (`*.so`).
2. **Spusť `zsh mbuild native`** (nebo `all`) + `pull_full_assets()` → artefakty do lokálního repa.
3. **Commitni a pushni binárky** (`assets/su_wrapper`, `usb_bridge`, `usr/bin/*` — vč. `su_daemon`,
   `ashell_pty` — a `usr/lib/*`). **Host binárky `su_daemon`/`ashell_pty` jsou v `assets/usr/bin/`**
   (přesun 2026-09-29 z rootu): nasazují se do `filesDir/usr/bin` jako součást version-gated host
   toolchainu (`deployDir("usr/bin")` — pozor, ten při bumpu `USR_TOOLS_VERSION` **wipne celý usr/bin**,
   proto binárky MUSÍ být v `assets/usr/bin`, jinak po bumpu zmizí). Deploy je i self-healing z
   `LocalApiServer.startAshellPty` / `ProotManager` / `RootBridgeTab` (mkdirs + migrace: mažou starou
   binárku z rootu `filesDir`). `pkill -x su_daemon` matchuje comm, ne cestu — přesun ho neovlivní.
   **Nevracet zpět do rootu `filesDir`.**
   `.gitignore` má pro ně explicitní `!` výjimky a sync klonuje z GitHubu — necommitnutá binárka
   v APK nebude. (Přesně tak se 2026-08-23 ztratily bionic usrtools; obnova:
   `modal run tools/rebuild_usrtools_recovery.py::rebuild`.)
4. Nikdy nenechávej artefakt jen na Volume — `sync` je git-based (`git reset --hard
   origin/dev`), takže necommitnutý/nepushnutý artefakt na dalším `sync` zmizí ze
   stromu na Volume (ne rsync --delete, jak to tvrdila starší verze tohoto bodu).

## 5. Závislosti a konvence

- Version catalog `gradle/libs.versions.toml` už obsahuje i `commons-compress`, `xz`, Termux
  (`terminal-view`/`terminal-emulator`/`termux-shared` v0.118.0), `guava` 33.6.0, `bouncycastle`,
  `androidx.biometric` — už **není** potřeba deklarovat přímo v `app/build.gradle.kts`.
  Přímo jsou jen `androidx.viewpager2`, `recyclerview` a `onnxruntime-android:1.17.1`.
- **Guava exclude:** `com.google.guava:listenablefuture` vyloučit ze všech Termux závislostí.
- `packaging.jniLibs.useLegacyPackaging = true` (nutné pro Termux `.so`), `org.gradle.parallel=false`.
- Compose BOM z catalogu spravuje Compose verze; ONNX modely `vpn_brain*.onnx` v assets.
- Bionic usrtools (`sed`/`rsync`/`nano`/`rg`) — **ne glibc**: NDK r28, interpreter
  `/system/bin/linker64`, bez `usr/lib` závislostí; deploy je version-gated (`USR_TOOLS_VERSION`,
  aktuálně `layout-20260815-1` v `ProotManager.kt`) → staré rozbité binárky se smažou a nasadí znovu.

## 6. Repozitář

- **Git LFS** povinné (`.apk` přes LFS, viz `.gitattributes`).
- Submodul `nethunter-store-data` → `https://gitlab.com/zombiegirlcz/nethunter-store-data.git`.
- Klon: `git clone --recurse-submodules` + `git lfs pull`.

## 7. Bezpečnost

Původní audit: 25 nálezů (9 CRITICAL / 8 HIGH / 5 MEDIUM / 3 LOW) — všechny hlavní opravené:

- **build.gradle.kts:** hesla keystoru z env/gradle properties, `isJniDebuggable` odstraněno.
- **AndroidManifest.xml:** `allowBackup=false`, `usesCleartextTraffic=false`, `networkSecurityConfig`;
  citlivé komponenty (`TerminalActivity`, notification/accessibility service, `DistroDocumentsProvider`)
  `exported="false"`.
- **LocalApiServer.kt:** Bearer token (šifrovaně v `api_security`, plaintext pro guest v `filesDir/api.token`), povinný
  pro citlivé endpointy i z loopbacku, blocklist destruktivních příkazů, max délka commandu (`/shell` 1024, `/shelldaemon/exec` 8192).
- **RootfsManager.kt:** HTTPS + host whitelist (`kali.org`, `parrot.sh`, `raw.githubusercontent.com`),
  TLS 1.2+, OkHttp timeouty. **VpnFirewallManager.kt:** IPv4/IPv6 validace před blokací.
- **res/xml/network_security_config.xml:** cert piny (platnost do 2027-12-31).
- Hotovo: cert pinning, `OffensiveEngine` notification confirm (Allow/Deny, 30 s), odstraněný hex dump
  z CSV/JSON exportu. Starý python agent (13338) odstraněn 2026-10-08 → AI je v appce
  Kali AI Assistant (`com.kali.aiassistant`); `nh agent ask` i hlasový asistent jí dotaz jen předají.

### Audit 2026-10-08 — záměrná rozhodnutí (NEhlásit znovu jako chyby)

- **Uživatelské CA v `network_security_config.xml`** jsou záměrné — bez nich nefunguje TLS MITM
  (uživatel instaluje vygenerovanou CA z `GET /vpn/mitm/ca`).
- **Rootfs katalog / Docker image** se ověřují při přidání do seznamu v repu `zombiegirlcz/ROOTFS-for-proot`
  (`tools/validate.py`, SHA256 v `<distro>.sh`) — appka pak stahuje jen z katalogu.
- **`app/debug.jks`** (alias `debugKey`, heslo `password123`) je veřejný vývojový klíč, ne tajný release klíč.
- **Loopback ≠ důvěra (API token):** na 127.0.0.1 se dostane každá appka v zařízení a UID volajícího
  z `/proc/net/tcp{,6}` ani `sock_diag` **zjistit nejde** (SELinux `untrusted_app_27` → EACCES, ověřeno
  2026-10-08). Proto citlivé endpointy na 1337 chtějí Bearer token **i z loopbacku** a `ashell_pty`
  (13340) chce token v 6. poli HELLO. Token: `LocalApiServer.start()` ho vygeneruje/rozšifruje a zapíše
  plaintext do `filesDir/api.token` (0600, jen UID appky → appka, její PRoot guest a root). `api_security.xml`
  drží jen šifrované `enc:…` — guest ho **nepoužije**. Klienti: `nh` (`get_token`), `ashell`
  (`read_auth_token`, posílá token u každého HTTP requestu i v HELLO). Nový klient = musí číst `api.token`.
  (Dřív se `getAuthToken()` nikde nevolalo → `authToken==null` a prázdný `Bearer ` prošel.)
- **`su_daemon` socket** je `0600` pod app UID + `SO_PEERCRED` (jen uid 0 a app UID z argv[4]);
  perzistentní sudo sessions v root-only `/data/local/tmp/.nh_sud` (0700, peer musí být root).
- **`shell_daemon`**: `accept4(SOCK_CLOEXEC)` (děti nedědí `client_fd`), token porovnáván v konstantním
  čase a po parsování přepsán v argv; useknutý výstup (> 256 KB) hlásí do stderr.
- **Limity příkazů:** `/shell` 1024 znaků (`ExecCore.hostExec`), `/shelldaemon/exec` 8192
  (`MAX_SHELL_CMD_LEN`) + stejný `DESTRUCTIVE_PATTERNS`/blocklist jako `/shell`.
- **Manifest rootfs je nedůvěryhodný:** `boot` pustí `NH_BIND` jen do vlastního rootfs (bez symlinků ven)
  nebo na allowlist (`/dev`, `/proc`, `/sys`, `/system`, `/vendor`, `/product`, `/apex`, `$FILES_DIR/share`),
  `NH_PATH` jen se znaky `[A-Za-z0-9_./:+-]`; hodnoty se předávají jako argumenty, ne do `-c` řetězce.
- **Session evidence:** `boot` zapisuje `nh/sessions/<distro>.<PID>.pid` + `.info` (čte MainActivity),
  mrtvé záznamy uklízí při dalším startu.
- **`tools/mbuild`** odmítne `all|native|smart|pull|proot`, když jsou necommitnuté změny v `assets/`,
  `jniLibs/`, `magisk-modules/` (pull by je přepsal); přebití `MBUILD_FORCE_PULL=1`. Výstup jde i do
  `build.log` (`tee`).
- **`su_daemon` blocklist** přeskakuje obalové příkazy (`env`, `timeout`, `busybox`, …) a chrání
  `/`, `/system`, `/vendor`, `/data`, `/dev`, `/proc`, `/sys`, `/storage`, … (rekurzivní chmod/chown/rm,
  `find -delete`); `-c` payload se skenuje celý. Je to pojistka proti nehodě, ne bezpečnostní hranice.
- Root zápisy (cpuctl, Magisk moduly) nikdy nenásledují symlinky v app-writable/`/data/local/tmp`
  (`O_NOFOLLOW`, `lchown`, logy `custom_usb_g2_setup` v `/data/adb/usb_g2`).
- Dropbear se v `entrypoint.sh` **nespouští automaticky** (dřív 0.0.0.0:2222 + účty bez hesla).

### Cert pin SHA-256 (k 2026-06-27, potřebují obnovu po expiraci)

Kali → GTS: leaf kali.org `vxHMRAr73HgUyGzWLG8C4xtO/qsK9nkPG59jH3i/mqc=`,
leaf kali.download `xAu7m0o10HbvBkBpvcS7+PYtxxX1rdUN8FHEI2Kg0Fo=`,
GTS WE1 `kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=`,
GTS Root R4 `mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=`.

Parrot/GitHub → Let's Encrypt: leaf deb.parrot.sh `lkI2NEknt/oq8INt5aiW7TriA18Z1mMNvvT6tjZjghs=`,
leaf raw.githubusercontent.com `PaZDXCM44SEEkf5qy7PN/gi0Z1u+nhGbRcKHSZQxhmA=`,
LE YR2 `nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=`,
ISRG Root YR `fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=`.

### Certificate & Attestation modul (`app/src/main/java/com/linux_core/security/`)

- `CertificateManager` (fasáda, init z `MainActivity.onCreate`, záloha `LocalApiServer.start`),
  `RootCaInstaller` (MITM CA z `assets/certs/mitm-ca.crt`; produkce nikdy neinstaluje CA systémově),
  `SslContextFactory` (`assets/certs/internal.p12`, fallback heslo z `KEYSTORE_PASSWORD`).
- `AttestationKeyManager` (alias `attest_ec` StrongBox→TEE, `attest_secret` AES-GCM-256, 30 s biometric
  okno), `AttestationVerifier` (PKIX chain + nonce + signature), `BiometricGate`, `KeystoreManager`,
  `MitmCertSigner` (BouncyCastle).
- Debug `network_security_config_mitm.xml` je samostatný (jen dokumentace); produkční config beze změn.
- Testy: `app/src/test/java/com/linux_core/security/`.

## 8. TLS MITM Inspection

Kompletní proxy pro dešifrování HTTPS v VPN tunelu. Zapnuto/vypnuto přes `enable_mitm`
(SharedPreferences `vpn_settings`, default `BuildConfig.ENABLE_MITM=false`).

- **Tok:** `VpnNatEngine.handleTcpPacket` detekuje Client Hello → `TlsMitmEngine.onClientData` vytvoří
  `TlsMitmSession` → SNI parse → server-side handshake → `RootCaInstaller.signLeafForServer()` →
  client-side handshake s forged certem → `proxyLoop` dešifruje/přeposílá; snippety (max 200) v
  `decryptedSnippets`.
- **Soubory:** `TlsClientHelloParser.kt`, `TlsMitmEngine.kt`, `RootCaInstaller.kt`, `MitmCertSigner.kt`,
  `VpnNatEngine.kt` (detekce), `VpnSecurityTab.kt` (UI), `VpnSettingsTab.kt` (přepínač),
  `LocalApiServer.kt` (endpointy).
- **CLI:** `vpn-cli mitm on|off|status|ca`, `vpn-cli logs [json]` (token z
  `/data/data/com.linux_core/files/api.token`).
- **API (1337, Bearer):** `POST /vpn/mitm` (on|off), `GET /vpn/mitm`, `GET /vpn/mitm/ca`,
  `GET /vpn/mitm/logs[?format=json]`.
- **Známé bugy — nevracet zpět:** double-flip v `writeToServer` (volající flipují sami); passthrough
  vždy s **fresh socketem** (jinak otrávený kanál); `writeFully()` místo non-blocking dropu při `w=0`;
  `sendTcpAck` na MITM cestě; fail-fast server handshake (~50 iterací); `protect()` fail → `null`;
  QUIC blokovat jen když MITM aktivně dešifruje (`shouldBlockQuic()`); `extractSni`/`isTlsClientHello`
  čtou sessionIdLen ze stejného offsetu (43); `RootCaInstaller` debug fallback heslo `"nethunter-dev"`
  (P12 v assetech); forged cert musí mít **vlastní RSA keypair** (ne CA private key).
- **`runEngineHandshake` akumulace `netIn` (2026-09-30) — nevracet na `netIn.clear()`:** handshake
  buffer je **WRITE-mode akumulační** (`var acc`, flip→unwrap→compact); volající předává netIn ve
  write módu (ClientHello se `put`-ne, **neflipuje**). Původní `clear()+put()` na každé čtení přepsal
  předchozí data → fragmentovaný server flight (certifikát chain > 1 TCP segment) nikdy nesložen →
  handshake fail → passthrough. Vnitřní smyčka drénuje všechny kompletní recordy (guard na progres,
  netiká `iterations`), `appendCiphertext` roste buffer. Half-close TCP: klientský FIN dělá
  `shutdownOutput()` na WAN (ne teardown) + `FIN_WAIT` reaping v `cleanIdleSessions` (60 s) — jinak
  guest visí ve `FIN_WAIT_2`.
- Capture-only režim: `startCaptureOnly()`/`captureLoop()` (dešifruje lokálně, timeout 10 s),
  `VpnSettings.isMitmCaptureOnly()`.
- **Postup při selhání:** `vpn-cli mitm status` → `nethunter-log -g "TlsMitm"` (hledej `SNI=null`,
  `falling back to passthrough`) → `nethunter-log -g "RootCaInstaller"` → ověř `assets/certs/mitm-ca.crt`
  a nainstalovanou CA v trust store → nouzově `vpn-cli mitm off`.

## 9. ashell — host shell konfigurace (`ashell.conf`, v4.5)

`/shell` API (`ashell -c`) nezná hardcoded allowlist, ale **blocklist + env** z `<filesDir>/ashell.conf`.
`DESTRUCTIVE_PATTERNS` zůstávají druhá vrstva; `SHELL_ALLOWLIST` chrání jen `/proot/exec`.

```sh
block <cmd>        # zakáže <cmd> přes /shell (basename match)
export FOO=bar     # sh řádky aplikované před KAŽDÝM příkazem
unset LD_LIBRARY_PATH
```

- `${FILES_DIR}` se expanduje na filesDir (config přenositelný); config se vytvoří při prvním použití.
- Endpointy (Bearer; citlivé i remote): `GET/POST /ashell/config`, `GET/POST /ashell/blocklist`.
- CLI: `ashell -e` (editor; bez `$EDITOR` jen hláška), `ashell --list`, `--add <cmd>`, `--remove <cmd>`.
- Interaktivní host shell čte config z `.ashell_env` přes `ENV=` (unset přebije spawn `LD_LIBRARY_PATH`).
- Test parseru: `app/src/test/java/com/linux_core/core/AshellConfigParserTest.kt`.

### 9b. `ashell adb` — shell_daemon pod uid 2000 (non-root)

Persistentní démon, který appce dává **shell UID (2000)** — stejná práva jako `adb shell`
(`pm`, `settings`, `cmd`, `dumpsys`, `logcat`), bez roota a bez reálného adb.

- **Binárka:** `app/src/main/jniLibs/arm64-v8a/libshelldaemon.so` (ELF s `main`, ne JNI lib).
  Android ji extrahuje do `applicationInfo.nativeLibraryDir` (`useLegacyPackaging=true`).
  **Nespouští ji appka** (app UID 10323 nedokáže spawnout uid 2000) — spouští ji guest:
  `ashell adb start` → `adb shell "nohup <nativeLibraryDir>/arm64/libshelldaemon.so … &"`.
- **Protokol:** TCP `127.0.0.1:13341`, binární (magic `SHLL`, mode `EXEC`/`ATTACH`/`INSTALL`,
  length-prefixed bloby) — viz `app/src/main/cpp/shell_daemon.c`. TCP místo UNIX socketu proto,
  že uid 2000 nesmí zapisovat do `filesDir` appky.
- **Token:** `filesDir/shell_daemon.token` (appka zapisuje), uid 2000 ho dostane přes
  `GET /shelldaemon/info` na `127.0.0.1:1337`. `nativeLibraryDir` je read-only (system:system).
- **`ashell adb <cmd>`** = `daemon_exec()` v ashellu → `POST /shelldaemon/exec`. Sdílená funkce
  pro `<cmd>`, `shell <cmd>` i `-c <cmd>`; parsuje `{stdout,stderr,exit_code}`.
- **`ashell adb shell`** (bez args) otevře `TerminalActivity` → `startAdbShellSession()` →
  `libshelldaemon.so --attach` → PTY shell pod uid 2000.

**UI nikdy neběží pod uid 2000** — WindowManager přiděluje okno jen procesu s app identitou.
Rozdělení: *emulace + render* v app procesu (Termux `terminal-emulator`/`terminal-view`, uid 10323),
*spouštění příkazů + PTY* v daemonu (uid 2000). Attach klient (`--attach`) běží jako dítě appky
(uid 10323) a jen tuneluje bajty; shell za ním je uid 2000. Perzistence screen state by vyžadovala
emulátor v daemonu (tmux model) — dnes `handle_attach` po zavření socketu shell **zabíjí**.

**Co nevrátit zpět (pitfalls):**

- `ashell adb shell <cmd>` **nesmí** otevírat okno — s argumenty musí jít přes `daemon_exec`.
  Vnější `case "$SUBCMD"` matchne `shell)` dřív než passthrough `*)`.
- `LocalApiServer.handleAshell` **nesmí** posílat `ashellMode=true` pro `adb-shell` — patří jen
  `ashell-host`. Jinak `TerminalActivity` spustí `startAshellSession()` (uid 10323) místo
  `startAdbShellSession()` (uid 2000). Platí i pro `cmd activity start-activity` v ashellu
  (tam `--ez ashellMode true` u `ashell-adb` nepatří).
- V `TerminalActivity` (`onNewIntent` i `setupAndStartSession`) musí být `rootfsDirName == "ashell-adb"`
  vyhodnoceno **před** `ashellMode` (přidána i explicitní podmínka `!= "ashell-adb"`).
- **OPRAVA (2026-09-27, ověřeno na zařízení — tvrzení níže bylo NESPRÁVNÉ):** démon **přežívá**
  vypnutí wireless debugging bez roota. Stejný `pid` odpovídal na `/shelldaemon/status` i po
  `adb_wifi_enabled=0`. Důvod: `main()` v `shell_daemon.c` se démonizuje (`fork()` + `setsid()` v
  dítěti, `stdin`→`/dev/null`, viz sekce „Daemonizace" u `bind()`/`listen()`) — přesně stejný trik
  jako Shizuku (`nohup`/`setsid` v `start.sh`). `setsid()` odpojí proces od `adb shell`u ovládajícího
  terminálu/session → je imunní vůči SIGHUP při zavření adbd spojení. Cgroup teorie níže (kdyby
  platila) by `setsid()` nevyřešil (cgroup ≠ session), ale empiricky proces PŘEŽÍVÁ, takže buď cgroup
  vůbec neumírá při pouhém vypnutí wireless debugging (jen SIGHUP by bez `setsid()` zabil), nebo je
  teorie z 2026-09-25 mylná. Původní text (nechán jako historie/pro referenci, NEŘÍDIT se jím):
  ~~proces spuštěný přes `adb shell` žije v adbd session cgroup (`/sys/fs/cgroup/uid_0/pid_<adbd>`);
  vypnutí wireless debugging ukončí adbd → cgroup se zabije → daemon umře. Trvalé přežití =
  spustit/přesunout daemona do root cgroup (`su 2000 -c …` nebo zápis do `/sys/fs/cgroup/cgroup.procs`
  jako root) + `oom_score_adj=-1000`. Bez roota nelze.~~
- Diagnostika: `ashell -c 'curl -s 127.0.0.1:1337/shelldaemon/info'` (nezávislé na adb),
  PID file `/data/local/tmp/shelldaemon.pid`.

### 9c. Shizuku-kompatibilní server (uid 2000, `nh shizuku`)

Druhý privilegovaný server vedle `shell_daemon`, ale protokolově kompatibilní s reálným
**Shizuku** (`moe.shizuku.server.IShizukuService`) — appky používající `rikka.shizuku:api`
knihovnu (velmi rozšířené u root-less nástrojů) fungují **beze zásahu**, žádný vlastní SDK.

- **AIDL vendorováno 1:1** z `RikkaApps/Shizuku-API` do `app/src/main/aidl/moe/shizuku/server/`
  (`IShizukuService`, `IRemoteProcess`, `IShizukuApplication`, `IShizukuServiceConnection`) —
  **nepřejmenovávat package ani metody, transakční kódy (`= N`) jsou deterministické z textu
  a MUSÍ sedět s originálem.** `moe.shizuku.api.BinderContainer`
  (`app/src/main/java/moe/shizuku/api/BinderContainer.kt`) musí zůstat v přesně tomhle
  package/jméně třídy — klientská knihovna na něj dělá `Class.forName()` při čtení z Parcelu.
- **Doručení binderu** (`BinderDelivery.kt`) jde přes `IActivityManager.getContentProviderExternal`
  + `IContentProvider.call("sendBinder", ...)` na klientovu `<balíček>.shizuku` ContentProvider
  (ten appky s `rikka.shizuku:api` mají v manifestu automaticky z knihovny). Obě hidden-API třídy
  (`IActivityManager`, `IContentProvider`) jsou v SDK stub jaru **celé odstraněné** (ne jen `@hide`
  metody) → `HiddenApis.kt` je čistě reflexní (`Class.forName`+`Method.invoke`), **žádný
  compile-time typ na ně, i náhodný import rozbije build.**
- **Server běží jako `app_process` pod uid 2000** (`ShizukuServerMain.main()`), startovaný stejným
  `adb shell "nohup ... &"` mechanismem jako `shell_daemon` (§9b) — `nh shizuku start` získá
  `apkPath`+`token` z `GET /shizuku/info` (appka, uid app, čte vlastní `api.token`), pak
  `CLASSPATH='<apk>' app_process /system/bin --nice-name=shizuku_server
  com.linux_core.shizuku.ShizukuServerMain --token=<token>`.
- **Permission grant/revoke:** `nh shizuku grant/revoke/list` **nebo** `ShizukuAppsActivity`
  (terminál → panel služeb → 🔑 SHIZUKU → ⚙ APLIKACE; seznam appek s `<pkg>.shizuku` providerem,
  přepínač píše do stejných prefs; odinstalace appky grant maže — `ShizukuPackageRemovedReceiver`
  na `PACKAGE_FULLY_REMOVED` + promazání při načtení správce). Žádný dialog/notifikace při `requestPermission` — to zůstává. Stav žije v appce
  (`SharedPreferences "shizuku_permissions"`, `LocalApiServer`), server (uid 2000, nemá přístup
  k `filesDir`) se dotazuje přes loopback `GET /shizuku/permission?pkg=` (`ShizukuHttpClient.kt`,
  token z `/shizuku/info`, stejný vzor jako `shell_daemon` token delivery, jen v opačném směru).
- **`java.lang.Process` vs `android.os.Process` name shadowing** (2026-10-08): import
  `android.os.Process` (pro `myUid`/`myPid`/`killProcess`) stínil `java.lang.Process` vrácený
  z `ProcessBuilder.start()` — Kotlin bez explicitní kvalifikace vybral `android.os.Process` a
  `RemoteProcessImpl` ztratil `outputStream`/`inputStream`/`errorStream`/`waitFor`/`exitValue`/
  `destroy`/`isAlive`. Fix: `java.lang.Process` fully-qualified na typu pole i v `newProcess()`.
  **Platí obecně pro jakýkoli soubor co importuje `android.os.Process` a zároveň pracuje
  s `java.lang.Process`.**

**Opravy 2026-10-08 (nevracet zpět):**

- **Doručení binderu klientům** dělá `BinderDistributor` v `ShizukuServerMain.kt`: každé 2 s čte
  `/proc/*/cmdline` (uid 2000 má readproc) a hlavnímu procesu každého balíčku s grantem pošle binder
  (znovu při novém PID). Dřív šel binder jen do `com.linux_core` → klienti hlásili „Shizuku není
  nainstalováno“. Doručuje se jen běžícím procesům (`getContentProviderExternal` by appku spustil).
- **`IContentProvider.call` na API 31+** bere `AttributionSource` (balíček `com.android.shell`, uid 2000)
  — `HiddenApis.contentProviderCall` ho zkouší první.
- **`callerPackage()`**: výstup `cmd package list packages --uid` je `package:<pkg> uid:<N>` → jen první token.
- **Token serveru** smí v `LocalApiServer` jen GET `/shizuku/permission*` a `/shizuku/resolve`
  (`isShizukuServerToken`); grant/revoke dál jen `api.token`.
- `newProcess`/`setSystemProperty` vyžadují grant volajícího (`enforceGranted`).
- **Stav serveru v appce = binder, ne `/proc`** (2026-10-09): uid appky `/proc` procesů uid 2000
  nevidí (hidepid). `BinderDistributor` proto posílá binder serveru i vlastní appce
  (`UserServiceBinderProvider.sendServerBinder`, při každém novém PID `com.linux_core`) →
  `ShizukuServerState` (`pingBinder`/`getVersion`, STOP = `IShizukuService.exit()`); z něj čte
  kontrolka 🔑 SHIZUKU v panelu služeb i `running` v `/shizuku/info`. Dřív to bylo vždy false. `nh shizuku status/start/stop` se ptá pod uid 2000
  (`pidof shizuku_server` přes shell_daemon/adb). `comm` serveru je `main` → `pkill -x` nefunguje.
  Start přes `exec app_process`, jinak drží obalový `sh -c` token v cmdline.
  `nh shizuku start` jde **primárně přes shell_daemon** (`adb_daemon_exec "CLASSPATH=… setsid nohup
  app_process …"` — setsid kvůli odpojení od exec požadavku démona, ověřeno 2026-10-09), adb jen
  jako fallback; adb tedy nemusí být připojené, stačí běžící `nh adb start`.
- **`nh` nesmí definovat funkci `ashell()`** — zakrývá binárku `ashell` (všechna `ashell -c …` v `nh`
  pak otevírala host shell okno pod uid appky; tak vznikl bug „`nh adb shell` běží pod 10323“).
  `nh adb shell` v terminálu běží inline: `ashell -tc` + `libshelldaemon.so --attach --token-file=…`
  (+ `stty raw -echo`). Pod `ashell -t` (PTY) padá `pm path` na binder „Failed transaction“ — cestu
  k binárce brát z `/shelldaemon/info`. `am start`/`cmd activity` z uid appky padá na SecurityException
  → `nh agent ask` jde přes shell_daemon.

**UserService hosting (2026-10-09)** — `UserServiceManager.kt` je port `UserServiceManager` +
`UserServiceRecord` ze Shizuku-API `server-shared` (klíč `<pkg>:<tag ?: class>`, dedup, změna
`versionCode` = nový proces, peek vrací `versionCode`/-1, non-daemon končí smrtí posledního
spojení, odstranění = transakce `16777115` destroy + kill procesu po 2 s, start timeout 30 s).
aShell i MacroDroid bez něj hlásí „Shizuku není nainstalováno“.

- **Proces:** server spustí `app_process /system/bin --nice-name=<pkg>:<suffix>
  com.linux_core.shizuku.UserServiceStarter --token= --package= --class= --uid= --apk=` s
  `CLASSPATH=<naše APK>` (z `/shizuku/resolve?pkg=com.linux_core`, ne z env serveru — po update
  appky by stará cesta neexistovala). Starter = port `UserService.create()` reflexí:
  `ActivityThread.systemMain()` → `createPackageContextAsUser` → `LoadedApk.makeApplication(true)`
  → konstruktor `(Context)` nebo bez args; fallback `PathClassLoader(apk)`.
- **Binder zpět:** starter ani server nemají provider → starter `putUserService` do
  `UserServiceBinderProvider` (`com.linux_core.shizuku.userservice`, jen uid 0/2000, TTL 60 s),
  na stdout `READY:<token>`, server ho čte a `takeUserService`. **Neplést stdout starteru s logem**
  — logovat jen přes `Log`, stdout je kontrolní kanál. `attachUserService(101)` funguje i
  originálním tokenovým tokem.
- **Stdin starteru server nikdy nezavírá** — EOF = server umřel → starter `exitProcess(0)`
  (náhrada `linkToDeath` na binder serveru, který starter nemá).
- Ownership: balíček z `ComponentName` musí mít appId volajícího (přes `/shizuku/resolve`),
  `addUserService` navíc chce grant.

**Záměrně nedokončeno (dokumentovaný gap, ne bug):**

- Raw transact-relay (`BINDER_TRANSACTION_transact = 1`, proxy arbitrárních systémových binder
  volání přes server) — mechanismus nejde ověřit z dostupných zdrojů, **vědomě vynecháno**.
- `getContentProviderExternal`/`IContentProvider.call` signatury se liší SDK verzí —
  `HiddenApis.kt` zkouší víc arit podle běžící verze, ale **nebylo ověřeno na zařízení**,
  jen že se to zkompiluje a Modal build projde.

## 10. Diagnostika a CLI

- `nethunter-log [-n N] [-g VZOR]` — barevný logcat (V šedá, D modrá, I zelená, W žlutá, E/F červená;
  `error`/`fail` červeně, `success`/`established` zeleně). API: `GET /app/logs?limit=N`.
- `vpn-cli status|start|stop|logs [json]|mitm …|bypass …` → `127.0.0.1:1337`.
- Unified `nh <kategorie> <akce>` (symlinky `nethunter-*`/`vpn-*` ještě fungují): `system`, `network`,
  `vpn`, `agent`, `device`, `fix permission`, `distro`, `desktop`, `float`, … — celý seznam v `README.md`.
- `nh distro list|ps|kill|remove|backup|restore` vyžaduje u destruktivních `--force`.

## 11. Historie oprav — co nevrátit zpět (pitfalls)

**VPN / logy:** UDP/443 drop jen při MITM ON; první DNS UDP write v BLOCKING režimu (API>33 vrací 0);
SYN_RECEIVED timeout 15 s; `elapsedTime` z reálného času (žádný random); `ProcessResolver` čte celý
`/proc/net/tcp`, ne `/proc/self`; `bytesSent/Received` ze `TcpSession`; `getConnectionOwnerUid` z user
app nefunguje → `-1`/„Unknown App".

**Proxy:** SOCKS5 rotace (pool + režimy) odstraněna → jediný custom `IP:Port`
(`VpnProxyManager.setCustomProxy`), volitelný, fallback na direct.

**`su_wrapper` shadow musí být idempotentní při KAŽDÉM startu:** `ProotManager` (`deploySuWrapper`)
přejmenovává `/usr/bin/{su,sudo}` → `.orig`, aby `sudo`/`su` v guestu šly přes `su_wrapper`
(`/usr/local/bin/sudo`). Guard **nesmí** být `!origBackup.exists()` (jen "poprvé") — `apt` umí
`sudo`/`su` kdykoliv přeinstalovat jako závislost něčeho jiného a tiše obnovit `/usr/bin/sudo`,
čímž náš wrapper odstíní (PATH ho najde dřív). Výsledek: `sudo <cmd>` tiše běží jen pod fake-root
PRootem (žádná skutečná eskalace, žádná chybová hláška), a spadne až na hostitelsky vlastněných
cestách (`/data/app`, …) s `Permission denied`. Fix: `if (origBin.exists())` bez druhé podmínky,
přepsat `.orig` pokaždé znovu. Zdrojová diagnóza vyžadovala `strncpy`/`which sudo`/`PATH` pořadí
kontrolu — `/bin` (merged-usr symlink na `/usr/bin`) před `/usr/local/bin` v `PATH` má stejný efekt,
i kdyby byl shadow OK.

**PRoot/perf:** seccomp je aktivní (`proot -V` → `seccomp_filter = yes`) — ne „vypnutý"; `--link2symlink`
nutné (apt/dpkg zálohy přes `link()`); `-0` kvůli fake root UX; tracer cost ~100 µs/syscall je intrinsický
(ne degradace live vs. fresh); **`LD_LIBRARY_PATH` v `hostShellEnv()` způsoboval SIGBUS** — nikdy
nepřidávat; `/usr/sbin/find` musí být symlink na `find` (ne `rg`).

**PRoot verze — `tmux`/multiplexery vyžadují ≥ v5.1.107.91:** staré `v5.1.107.90` desynchronizuje
syscall tracer state machine na aarch64 zařízeních se starým 4.x kernelem (`arm64 before v5.3`, tj.
většina Android telefonů) při emulaci syscallů, které PRoot cancelluje/fejkuje (upstream fix
`61681c64`, „syscall: don't wait for a sysenter stop the kernel skips"). `PROOT_TAG` je teď
`v5.1.107.93` — nevracet zpět na `.90`.

**PRoot USERLAND mode (`fake_id0`) nelze použít — ničí PTY/tmux:** USERLAND mode (`#define USERLAND`
v `fake_id0/config.h`) zabraňuje SELinux audit bouři (`comm="proot" setattr proc:dir`), ale způsobuje
jinou regresi: `ioctl(fd, TCGETS)` selže pro **jakýkoli fd otevřený přes `open()` uvnitř proot** —
tj. `isatty()` = 0 pro `/dev/tty`, `/dev/pts/N`, vše otevřené nově. Zděděný fd 0 funguje,
nově otevřené PTY fdy ne → `tmux new` → "open terminal failed: not a terminal"; `tmux < /dev/tty`
→ "can't use /dev/tty". `setsid` ani `< /dev/tty` redirect nepomůže.

**`boot` kopíruje referenční příkazy proot-distro (`docs/proot-cmd-mod.md`, 2026-09-24):**
proot se spouští přes `env -i $(guest_env) "$PROOT" …`: guest nedědí prostředí appky, jen
explicitní seznam proměnných (navíc proti referenci `LANG`/`LC_CTYPE`, `NETHUNTER_SESSION_ID`,
`NH_DISTRO` pro guest `nh`). Flagy `--kill-on-exit --link2symlink -L --change-id=0:0` (+ `--sysvipc`,
`--kernel-release` mimo M). `-b /dev/urandom:/dev/random` vždy (mimo M). Storage = jeden zdroj
`/storage/self/primary` bindnutý do `/mnt/sdcard`, `/sdcard`, `/storage/emulated/0`,
`/storage/self/primary`, bez bindu celého `/storage` (gate `NH_MOUNT_STORAGE` = přepínač
„Shared Storage" v MainActivity). Navíc proti referenci jen `ipc`/`share` a uživatelské
`NH_EXTRA_MOUNTS`/`--bind`.
CLI flagy `boot` (kdekoli **před** `--`, za `--` se nesahá): `-d`/`-i`/`-m` (mód, přebije env),
`-b src:dest` / `--bind src:dest` / `--bind=src:dest`, `--shared-tmp` (`-b $FILES_DIR/tmp:/tmp`
ve všech módech; appka ho přidá z Root Bridge přepínače `shared_tmp`, pro sudo přes `root_env`).
Výpočet módu je ve funkci `apply_mode()`, kterou `main()` volá až po parsování argumentů.
**tmux „not a terminal" z terminálu appky:** mód M funguje, plný D ne. Tmux server dostane
pts fd přes `SCM_RIGHTS` a `isatty()` na něm selže. Příčina v D zatím **není potvrzená**. Samotné
odebrání samostatných storage bindů problém nevyřešilo. Proot binárka ani env (`env -i` ve stejné
session) to nejsou. Diagnostické helpery v guestu: `scmtest`, `ptsdiag`, `prootcmd`, `tmuxdiag`.

**SELinux fix (aktuální) — `--wrap=chmod` linker-level wrapper:** proot binary má 4 volání
`chmod@plt` z různých .c souborů. Per-file `#define` nestačí. Správné řešení: `selinux_android_fix.c`
s `__wrap_chmod` + `-Wl,--wrap=chmod` v LDFLAGS → linker přesměruje VŠECHNA `chmod` volání
v proot binary přes wrapper, který přeskočí `/proc` a `/sys`. NON-USERLAND mode zachován →
tmux a PTY fungují.
Viz `_SELINUX_FIX_C` + `selinux_fix_o` v `tools/modal_build.py::_build_proot_one_arch()`.
**Detekce binárky:** NON-USERLAND proot má `.l2s.` string (USERLAND měl `.proot.l2s.`).

**Druhý zdroj audit bouře — `execute`, ne `setattr` (2026-09-27, fatální reboot zařízení):**
`--wrap=chmod` řeší jen `setattr proc:dir`. Nezávisle na tom každý exec řetěz v PRootu
(`proot` → `ld-linux-aarch64.so.1` → `loader` → binárka) generuje vlastní
`avc: granted { execute }` na `untrusted_app_27:app_data_file:file` — to je kernelová
LSM hook na `execve()`, nejde to obejít v proot/loader kódu. Pod PRoot workloadem to
zahltí `audit_backlog_limit=64`/`audit_rate_limit=5` (`dmesg`: `audit_lost=21095`,
`rate limit exceeded`). `audit_log_start()` je synchronní kernelová cesta sdílená
VŠEMI procesy v systému → přeplněná fronta dokázala zaseknout i `system_server`
(pozorován MIUI `FW_SCOUT_HANG` na hwbinder volání hned po pádu), ne jen appku.
**OPRAVA (2026-09-27, ověřeno na zařízení — recidiva freezu, modul nikdy nebyl nainstalovaný):**
`dontaudit untrusted_app_27 app_data_file:file { execute execute_no_trans }`
(`magisk-modules/audit_flood_fix/sepolicy.rule`) je proti tomuhle **bezúčinný** —
`dontaudit` potlačuje logování ZAMÍTNUTÝCH přístupů, ale `execute` je tu ALLOW;
jeho logování řídí samostatný `auditallow untrusted_app_27 app_data_file:file
{ execute execute_no_trans }` v MIUI vendor policy (`magiskpolicy --print-rules`
ho ukázal souběžně s `dontaudit` — nesouvisí s žádným naším modulem, `dontaudit`
ho nepřebije). Skutečný fix: `auditctl -r 1000` (Android auditctl na tomto
zařízení podporuje jen `-r rate`, ne `-b backlog`) — zvedne `audit_rate_limit`
z defaultní `5` msg/s, což je pro PRoot execve-řetězec workload hluboko
nedostatečné. Ověřeno: po `auditctl -r 1000` **žádný** další `audit_lost` i pod
zátěží (40× exec v proot), zatímco `dontaudit` fix (aplikovaný živě přes
`magiskpolicy --live`) na běžící storm nic nezměnil. `audit_flood_fix/service.sh`
teď dělá obojí (`dontaudit` pro starý `setattr` mechanismus + `auditctl -r 1000`
jako hlavní fix), čeká na `sys.boot_completed`. META-INF zkopírováno ze
sesterského `anti_phantom` modulu. **Modul samotný ale nikdy nebyl nainstalovaný
do `/data/adb/modules/`** (jen v repu) — proto se freeze zopakoval; při
podobném incidentu nejdřív zkontrolovat `ls /data/adb/modules/audit_flood_fix`.

**NAVAZUJÍCÍ (2026-09-30, třetí freeze — modul UŽ nainstalovaný, `-r 1000` aktivní):**
`auditctl -r 1000` prokazatelně funguje (`audit: audit_lost=403 audit_rate_limit=1000`
vs. dřívějších 21095), **ale úzké hrdlo se tím jen přesunulo z rate limiteru na
backlog frontu**: `audit: audit_backlog=65 > audit_backlog_limit=64` (8× v jediném
1,1 ms burstu). To je horší stav než zahazování — při plném backlogu `audit_log_start()`
volající úlohu **blokuje**. `audit_backlog_limit` zdejším auditctl zvednout nelze
(`Usage: /system/bin/auditctl [-r rate]` — žádné `-b`, žádné `-s`); jde jen přes
kernel cmdline `audit_backlog_limit=N`. **Jediná zbylá páka je snížit počet záznamů.**

Měření (okno 5,4 min — `dmesg` ring buffer víc nepojme, tak je zahlcený): 6575 avc
záznamů ≈ **20/s**. Rozpad: **`setattr` denials od `comm="proot"` na
`/dev/__properties__` = 3778 ze 4514 denials (84 %)**, `granted execute` 2061.
Proot při startu chmodne `property_info`, `properties_serial`, `*_prop`; SELinux to
vždy zamítne → audit záznam. Každý `sudo`/guest příkaz = nová proot instance = další
várka stovek. **`--wrap=chmod` tohle nechytal — přeskakoval jen `/proc` a `/sys`.**
Fix: do skip listu přidáno `/dev` (`tools/modal_build.py::_SELINUX_FIX_C`) → nutný
`zsh mbuild native` + commit binárek (§4), jinak se do APK nedostane.
`sepolicy.rule` přepsán z (neúčinného) `execute` na
`dontaudit untrusted_app_27 {property_type,property_info,properties_serial}:file setattr`
— tohle účinné je, protože jde o DENIALS.

**Past při čtení ANR po freezu:** soubor v `/data/anr/` je typicky až z doby ~40 s
**po** rebootu (30. 9.: boot 06:08:01, ANR 06:08:42), tedy artefakt rozjezdu, ne
příčina; `libdebuggerd_client: timeout expired` + hlavní vlákno `wchan=0` = hladovění
po CPU, ne deadlock. Kernel časy z `dmesg` mapovat na reálný čas přes
`/proc/uptime` (boot = teď − uptime), jinak se burst snadno přiřadí ke špatné události.

**Boot módy D/I/M + fake sys:** `NH_ISOLATED` a `NH_MINIMAL` jsou **nezávislé** flagy
(`D=0/0`, `I=1/0`, `M=1/1`) — `I` **není** minimal, i když starší `docs/proot-cmd-mod.md`
tvrdil opak. Fake `/proc` + `/sys` overlay (sysdata, `sys_empty:/sys/fs/selinux`,
`--kernel-release`) je gate-ovaný na `NH_MINIMAL=0` **a** `NH_FAKE_SYS=1`; reálný `-b /sys`
je v základním řádku `build_binds()` ve **všech** módech. `NH_FAKE_SYS=0`
(RootBridge → „Fake /proc & /sys", pref `bind_fake_sys`) dá guestu skutečný
kernel/`/proc`/`/sys`, ale zachová `--sysvipc` + `/dev` fixes (Frida). Pref `bind_data`
přidá nativní bind `/data` — obsah je čitelný jen v sudo seanci (`su_daemon` re-entry),
nikdy se nedělá hostitelský `mount --bind` (leak mountů do globálního namespace).

**Root Bridge extra bindy jdou na nativní cesty** (2026-09-25): `-b /system`, `-b /vendor`,
`-b /data/local/tmp`, `-b /config/usb_gadget`, `-b /data/misc/bluetooth`,
`-b /data/user/0/com.linux_core`, `-b /data/user/0/com.kali.aiassistant`, `-b /data` (ne pro
docker/Termux image). Žádné `/mnt/*`. `/dev/bus/usb` a `/sys/class/bluetooth` samostatný bind
nemají (`/dev` a `/sys` jsou bindnuté vždy). `boot` `dedupe_binds()` vynechá bind se stejným
zdrojem i cílem, který už přidal mód (např. `/system` v D). Guest env: `PROOT_TMP_DIR` se v guest
wrapperu unsetuje (zbyl by vedle `TMPDIR`), `EXTERNAL_STORAGE=/storage/emulated/0` jen při
`NH_MOUNT_STORAGE=1`.

**Preset rootfs z `zombiegirlcz/ROOTFS-for-proot` (2026-09-25):** appka skripty **nespouští**,
jen z nich regexem čte `TARBALL_URL['<arch>']` (jen přesná arch, žádný fallback), SHA256 a heredocy
`bootstrap.sh`, `root/entrypoint.sh`, `.nh/manifest` (`RemoteRootfsCatalog.kt`, zápis v
`RootfsManager.pullRemoteDistroScript`). `boot_docker` čte `/.nh/manifest` (`NH_SHELL`,
`NH_ENTRYPOINT`, `NH_BOOTSTRAP` jednou se značkou `/.nh/bootstrap.done`, `NH_PATH`, `NH_WORKDIR`,
`NH_ENV`, `NH_BIND`); cesty ověřuje `rootfs_resolve` (symlinky uvnitř rootfs, ne na hostu). Login
shell vždy `-l` (busybox/dash `--login` neznají). `ProotManager` do docker image nenasazuje debianí
bootstrap/entrypoint/zshrc/profil (jen s `NH_INTEGRATION=full`). Extrakce docker image: hardlink =
kopie zdroje (linkName je od kořene archivu), procházení stromu jen přes nio bez symlinků;
`resolv.conf` symlink se nahradí souborem. Mazání rootfs jen `RootfsManager.deleteRootfsTree()`
(nenásleduje symlinky — `File.deleteRecursively()` přes absolutní symlink leze na host).
Spec + validátor + prompt denního agenta: `ROOTFS-for-proot/AGENTS.md`, `tools/validate.py`.
**Formát archivu se pozná z obsahu, ne z přípony (2026-10-09):** všechny cesty (katalog
`pullRemoteDistroScript`, URL, lokální import, Docker vrstvy) volají `extractArchive()` →
`ArchiveFormats.open()` (magic bajty přes commons-compress `ArchiveStreamFactory`/`CompressorStreamFactory`):
gzip (i vícečlenný), xz, bzip2, zstd (`zstd-jni` AAR), lzma, lz4, .Z + kontejner tar/zip; `docker save`
tar (`manifest.json` + `Layers`) se rozbalí po vrstvách. Dřív rozhodovala přípona URL a Docker vrstvy
šly natvrdo přes gzip → „not in the .gz format“ (Chimera). Nevracet na `detectTarFormat(url)`.

**Výkon PRoot = affinity, ne flagy (2026-09-25):** každý trasovaný syscall ~320 µs (i
`fstat`/`getcwd`; netrasovaný `getpid` 0,5 µs) = latence ptrace výměny mezi jádry. Flagy
(`-L`, `--sysvipc`, `--change-id`, počet bindů) jsou v šumu, `PROOT_NO_SECCOMP=1` 3× horší.
Pin proot + guestu na jedno velké jádro = 3–5× rychlejší → `NH_CPU_PIN` (`boot`
`cpu_pin_apply`, stav `$FILES_DIR/nh/cpu/pin.<pid>`, hlídač obnovuje pin po změně cpusetu;
`free.<pid>` = PIDy z `nh cpu all` a z `CPU_ALL` whitelistu — hlídač ho čte z
`/proc/<pid>/environ` guest procesů, cesty v něm relativně k rootfs z `-r` v cmdline proot), pref `boot_modes/cpu_pin_<kali|parrot|docker>`, ikona
`CpuPinToggle` na kartě distra, `nh cpu`. Hlídač se spouští dvojitým forkem — dítě procesu,
který pak `exec`-ne proot, by proot sklízel jako neznámý tracee.
Hlídač čte `/proc/<pid>/{cmdline,environ}` **jen** přes `proc_lines()` (`dd count=1` + `timeout`),
nikdy `tr … < /proc/…`: u zaniklého PID se toybox `tr` zacyklí na chybě `read()` (~50 % jádra)
a hlídač visí navždy (2026-09-27, osiřelé `tr`/`head` pod `boot`).

**`nh cpu core <N|auto> [distro]`** (2026-09-27): persistentní manuální override jádra,
odděleně od `nh cpu pin [N]` (ten je jen živý/session-only, nepřežije boot). Motivace: `nh cpu bench`
naměřil v jedné session „little 2× rychlejší", v jiné „big 1.18× rychlejší" — rozdíl byl kontaminace
běžící AI-agent session (Claude Code sám běží ve stejném guest cpuset jako proot, `nh cpu trace`
ho ukázal jako 47–51 % šumu na pinovaném jádru, `nh cpu isolate` ho nezachytí — `noise_patterns`
v `cpu_isolate()` je natvrdo daný seznam known daemonů, ne obecná detekce). Uživatel chtěl způsob,
jak automatiku (`cpu_pick_core()`/`cpu_fastest()`, heuristika „nejnižší cpuinfo_max_freq") natvrdo
přebít a mít jistotu, že se jádro nebude nikdy přepínat pod ním. Implementace: `BootModePersistence.kt`
(`loadCpuPinCore`/`saveCpuPinCore`, `null` = automatika, SharedPreferences `-1` sentinel), `ProotManager.kt`
(`NH_CPU_PIN_CORE` env — mechanismus v `boot`'s `cpu_pin_apply()` uz existoval,
`core=${NH_CPU_PIN_CORE:-$(cpu_pick_core)}`, jen nebyl nikde vystavený uživateli), `LocalApiServer.kt`
(`/distro/cpupin` GET/POST rozšířeno o `"core": int|null`, POST teď akceptuje `enabled` NEBO `core`
samostatně — nemusí se posílat obojí), `assets/nh` (`cpu_core()` + dispatch `core)` + `cpu_status()`
zobrazuje manuální/automatický stav). **Neplést s `nh cpu pin [N]`** — ten mění jen běžící session,
`nh cpu core` mění perzistentní volbu pro příští booty daného distra.

**Magisk modul `nh_cpuctl` (volitelný, root):** statická binárka `cpuctl`
(`app/src/main/cpp/cpuctl.c`, výstup `magisk-modules/nh_cpuctl/system/bin/cpuctl`).
`service.sh` po bootu spustí `cpuctl daemon` — netlink proc connector (EXEC/FORK eventy)
okamžitě zachytí nové procesy a aplikuje session masku / CPU_ALL, s fallbackem na `/proc`
scan. Heartbeat `$FILES_DIR/nh/cpu/cpuctld` (`<PID> <unix_ts>`, každých 5 s); `boot`
`cpu_pin_apply` ho čte — čerstvý heartbeat (<15 s) → mksh hlídač se nespouští.
`cpuctl boost on|off [N]` nastaví scaling_min_freq=max pro policy jádra N (přežije jen
do rebootu). `cpuctl pin <hexmask> <pid>` one-shot. `cpuctl status` vše.
`nh cpu boost on|off|status` → `sudo /system/bin/cpuctl boost …`.

**Řízení CPU cizích aplikací (`cpuctl apps`/`app-pin`, 2026-09-27):** `cpuctl apps [N]` čte
per-balíček CPU% (2 vzorky 1 s od sebe z `/proc/<pid>/stat` utime+stime), aktuální masku a
uid; **jen aplikace** (`uid >= 10000`, konstanta `APP_UID_MIN`) — systémové procesy se nikdy
nečtou ani nepinují. `cpuctl app-pin <balíček> <hexmask|off>` uloží pravidlo do
`$CPU_DIR/app.<balíček>` a hned pinuje všechny běžící PIDy balíčku (`pin_pkg`); daemon pravidla
načítá v HB smyčce (`scan_apprules`), aplikuje na nové procesy (`handle_new_app` v `handle_new`)
a obnovuje po změně cpusetu (`repin_apps`). Balíček = první token `/proc/<pid>/cmdline` bez
`:subprocess`. Vystaveno: `nh cpu apps [N]` / `nh cpu app <balíček> <jádra|off>` (guest, přes
`sudo`), API `GET /cpu/apps` + `POST /cpu/apps/pin` (`{package, cores:"0-3"|null}`, Bearer +
localhost gate — v `sensitiveEndpoints`), a UI `CpuAppsActivity` (spouští se z CPU řádku v
services panelu, volá cpuctl přes `su -c` jako `runCpuBoost`). **Neplést** `nh cpu app` (cizí
appka) s `nh cpu core/pin` (proot session).

**`cpuctl` binárka musí jít spolu se zdrojem (2026-09-27):** `apps`/`app-pin` byly v `cpuctl.c`
commitnuté bez přebuildu a modul na telefonu zůstal v1.0, která je nezná →
`nh cpu apps/app` i `tools/usage-cpu.sh` modul „neviděly" (přebuild až f8ab7e7). Od v1.1 má
`cpuctl version` (`CPUCTL_VERSION` = `module.prop` version); `nh` (`cpu_ctl_new`) a test podle
něj (fallback: `app-pin` v nápovědě) hlásí starou binárku. Po změně `cpuctl.c`: `zsh mbuild native` → commit binárky → navýšit `module.prop` →
`python3 magisk-modules/magiskb.py nh_cpuctl` → flash + reboot.

**Non-root měření (`cpu_apps_raw` v `assets/nh`, `nh cpu apps`/`appmon`):** tři úrovně —
app uid nevidí cizí `/proc` (`hidepid`), uid 2000 (`ashell adb`, `dumpsys cpuinfo`) **měří** ale
**nepinuje**, root (nh_cpuctl) obojí. `cpu_apps_raw` vrací TSV a kódem zdroj (0 root / 2 non-root
dumpsys / 1 nic); `cpu_apps` tiskne tabulku, `cpu_appmon` živé bary (čistý sh+awk, bez python).
**Přidělení jader cizí appce je fyzicky root-only** (`sched_setaffinity` na cizí uid = `CAP_SYS_NICE`);
non-root pinování nelze — neslibovat ho v UI ani CLI.

**`sudo` dědí nastavení přes soubor:** `su_daemon` `execv`-ne `boot -- cmd` pod rootem, ale
dítě dědí **jen prostředí daemonu** (žádné `NH_*`) → sudo session by měla prázdné
`/data` a fake `uname -r`. Proto `ProotManager` zapisuje `$FILES_DIR/nh/root_env`
(`NH_ISOLATED/MINIMAL/FAKE_SYS/MOUNT_STORAGE/EXTRA_MOUNTS`) a `boot` ho nasourceuje,
**jen když `NH_ENV_FROM_APP != 1`** (appka předává hodnoty přes env, ty mají přednost).

**X server / desktop:** bývalý `:linux-x11` modul s assetem `usr/lib/linux-x11` (`libXlorie.so` z Termux-X11) byl **odstraněn** (2026-09-11) — byla to JNI knihovna bez `main`, nespustitelná samostatně. Desktop jede na **Xvfb** (`apt install xvfb`, `nh desktop start`): display `:0` → TCP 6000, `-ac -listen tcp`; MIT-SHM funguje i přes loopback TCP (ověřeno). Renderuje **externí** app `kali_GUI` (`com.linux_core.xlauncher`, `X11Client` + XTEST). `ProotManager.removeLegacyLinuxX11()` uklidí staré ~20 MB artefakty z `files/usr/{bin,lib}`.

**git pod prootem (link2symlink):** PRoot `-L` mění git hardlinky (pack/idx/rev i loose objekty)
na symlinky do `$ROOTFS/.l2s`. Když se `.l2s` vyčistí (nová session / přepnutí módu), symlinky
osiří → `invalid object … Not a directory` a rozbitý repo (postihuje VŠECHNA repa v rootfs).
Pojistka: `nh fix git [path]` materializuje symlinky na reálné soubory (přes host bind App Data `/data/user/0/com.linux_core`, fallback `/mnt/app`,
kde jsou symlinky vidět; vyžaduje `bind_aiapp`). Pouštět po `git clone/gc/repack` pod prootem.

**`ShellDaemonClient.kt` ↔ `shell_daemon.c` byl endianness-broken od začátku (2026-09-27):**
Kotlinovo `DataOutputStream.writeInt/writeLong` a `DataInputStream.readInt` jsou podle Java
kontraktu VŽDY big-endian, ale `shell_daemon.c` čte/píše `uint32_t`/`uint64_t` čistě nativně
(`read_all(fd, &x, sizeof(x))`, žádné `ntohl`/`htonl`) — na aarch64 little-endian. Důsledek: KAŽDÉ
volání `exec()`/`install()`/`stopDaemon()`'s SH_MODE_STOP z appky posílalo magii `SHLL`
byte-prohozenou (`0x4c4c4853` místo `0x53484c4c`); démon to zalogoval jako `spatny magic` a **zavřel
socket bez odpovědi** (appka ještě psala zbytek požadavku → "Broken pipe"/"Connection reset").
Interaktivní `--attach` cesta (`ashell adb shell` bez args → `libshelldaemon.so --attach`, C-to-C)
tím postižena NEBYLA (nativní klient, stejná endianness na obou stranách) — proto bug přežil
nepovšimnutý, non-interaktivní `ashell adb <cmd>`/`/shelldaemon/exec` vždy padal na fallback.
Fix: `writeIntLE`/`writeLongLE`/`readIntLE` helpery v `ShellDaemonClient.kt` (manuální bajt-po-bajtu
LE write/read) namísto `writeInt`/`writeLong`/`readInt`. **Nevracet zpět na `DataOutputStream.writeInt`**
pro cokoliv, co jde na `shell_daemon.c` socket — ten protokol je a zůstává nativní (LE), ne network
byte order. Diagnostika: restartovat démon s `> logfile 2>&1` místo `> /dev/null 2>&1` (`ashell adb start`
default přesměrovává stderr do /dev/null, takže `spatny magic`/crash hlášky jsou jinak ztracené).

**su_daemon / Root Bridge:** fork-per-connection (parent hned `accept()`, žádné blokování nových `sudo`),
POLLHUP → SIGKILL command childa, config v `g_*` globálech, ignorovat SIGPIPE, `pkill -x` (ne `-f`),
fail-closed bez launcheru (`_exit(126)`), **re-entry do PRoot** místo host `chroot` (ochrana proti
host-globálním příkazům), ownership fix fd-based (`openat`+`fchownat` `AT_SYMLINK_NOFOLLOW`, odolné proti TOCTOU) s vynecháním bind dirů
(`dev proc sys run sdcard mnt system vendor product apex storage data`), `@FIX` režim + `nh fix permission`.

**Terminál:** paste přes `emulator.paste()` (bracketed paste, ESC/C1 sanitizace), ne `session.write()`;
spawn s `LANG=C.UTF-8`/`LC_CTYPE=C.UTF-8` (glibc ≥ 2.35); MIUI multi-input = debounce
`updateSuggestions()` (žádné synchronní `Button()` v IME `commitText`).

**Bionic usrtools:** všechny 4 nástroje Bionic (glibc v app kontextu padá na seccomp `rseq` → SIGSYS;
v guestu to maskuje PRootův seccomp filtr). Deploy musí mít exec bit + version gate.

**Auto-start:** `RECEIVE_BOOT_COMPLETED` + `.core.BootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED;
**ne** LOCKED_BOOT_COMPLETED — filesDir je credential-encrypted), `TerminalService` START_STICKY
restart s dedupem (jedna cron session) a backoffem; toggle `boot_autostart`.

**`ashell` je od 2026-09-27 nativní binárka, ne `/bin/sh` skript** (`app/src/main/cpp/ashell.c`,
build v `_build_native_bin` v `tools/modal_build.py`, `-static` stejným `aarch64-linux-android24-clang`
toolchainem jako `su_wrapper`/`ashell_pty` — bionic binárka běžně běží i v glibc guestu, PRoot je
ptrace-based a ABI trasovaného procesu nerozlišuje). Motivace: starý skript dělal `curl`+`python3` na
KAŽDÉ volání (`api_call()`/`daemon_exec()`) a psal si dočasný `.py` klient pro `ashell_pty` protokol —
každý ten fork+exec generoval vlastní `avc: granted { execute }` (viz „Druhý zdroj audit bouře" výše);
`-c` dělal 1 extra exec (python3 pro PTY bridge), `ashell adb <cmd>` dělal 2 (curl+python3). Nativní
klient mluví HTTP (127.0.0.1:1337) i binární `ashell_pty` protokol (127.0.0.1:13340, framing
`0x01 STDIN/0x02 STDOUT/0x03 WINCH/0x04 EXIT/0x05 HELLO/0x06 STDIN_EOF`; HELLO = `cmd\0cwd\0rootfs\0term\0interactive\0token\0`) přímo raw sockety — 0 extra
execů na hot paths. CLI grammar zachována 1:1 (`-c`, `adb start/stop/status/shell/<cmd>/install/
uninstall/push/pull/devices/help`, `--add/--remove/--list/-e`, bare = host shell) + vedoucí boolean vlajky
`-v`/`--verbose` (debug marker, nebo env `ASHELL_DEBUG=1`) a `-t`/`--tty` (vynuť serverový PTY). Oddělené
i slepené clustery v LIBOVOLNÉM pořadí (`-vtc`==`-vct`==`-tvc`==`-ct`…): kombinace `v`/`t` + nejvýš jeden
selektor `c`/`e` kdekoliv (v našem modelu `c`/`e` NEbere inline argument — příkaz je samostatný token —
takže na pozici selektoru nezáleží). `-v` → `ashell -c` napíše na stderr, kterou cestou šel:
`via PTY (…13340…)` vs. `via HTTP fallback (/shell)` (marker na stderr, stdout čistý).
**`ashell -c` default = PIPE režim (ssh model, `interactive=0` v HELLO)**, ne PTY — levnější (žádná
kernelová tty line-discipline, čistý výstup, žádný per-loop traced ioctl pod prootem). PTY se vyžádá jen
`-t` (`ashell -tc htop`) — interaktivní TUI bez `-t` spadnou na „not a terminal". **Nevracet zpět na
`interactive = isatty(stdin)`** (over-selektovalo PTY pro každý příkaz z terminálu).
**`ashell -t` (bez `-c`) = interaktivní host shell PŘÍMO v tomhle terminálu** (`cmd_host_shell_inline`,
`exec sh -i` přes ashell_pty), bez nového Android okna a bez `cmd activity` (ta na některých ROM padá na
binder „Failed transaction"). Fallback na okno (`cmd_open_host_shell`), když daemon neběží. Od 2026-09-29
běží **holé `ashell` i `--tmux`/`-tx` také inline** (`cmd_host_shell_inline(use_tmux)`, bez `-t`); nové okno
jen jako fallback. `--tmux` spouští `ltmux` (glibc tmux z rootfs přes bionic `elf_loader`, `assets/usr/bin/`),
pak host `tmux`, pak `sh -i`. Relay smyčka v
`run_via_ashell_pty` je **event-driven**: blokující `select` bez timeoutu + `SIGWINCH` handler (bez
`SA_RESTART` → EINTR přepošle velikost okna) — žádný 200ms polling ani per-loop `TIOCGWINSZ`, takže když
příkaz tiše běží, klient (pod prootem) nedělá žádné trasované syscally. Nízko-frekventní
větve (`adb start/stop`, `-e` editor, otevření PTY okna) klidně používají `system()`/`execvp` — nejsou
hot path. JSON parsing je ručně napsaný minimální extraktor (jen pro known ploché tvary odpovědí
tohoto projektu, ne obecný parser) — **nerozšiřovat na obecné vnořené struktury** bez rozmyslu.
Testováno lokální kompilací v guestu (glibc, jen pro validaci — oficiální artefakt musí přes Modal).

**Launcher:** flag `-E` pro proot **neexistuje** — LD_PRELOAD/PROOT_LOADER se v guestu řeší přes
`/bin/sh -c 'unset LD_PRELOAD PROOT_LOADER; exec "$@"'` před prvním exec.

## 12. Známé technické dluhy

1. MITM historicky padal do passthrough / korumpoval stream — **hlavní příčiny opraveny 2026-09-30:**
   (a) chybějící CA privátní klíč (nyní runtime-generovaný, viz bod 4), (b) neakumulovaný `netIn` v
   `runEngineHandshake` (fragmentované server certy → passthrough), (c) `handleAppData` v `proxyLoop`
   zahazoval partial recordy + `serverNetIn.clear()` každou iteraci (mid-stream korupce) — teď
   `serverNetIn` WRITE-mode akumulace + compact, `clientAppDataIn` zvětšen na 32 kB. Zbývá ověřit na
   zařízení; jediná neopravená cesta je renego `driveServerHandshake`/`driveClientHandshake`
   `netIn.clear()` (v TLS 1.3 se renego nepoužívá).
2. Widget zakomentován v manifestu („pro later").
3. DNS tab prakticky prázdný (moderní Android jede DoH/TCP, ne UDP/53).
4. ~~`app/src/main/assets/certs/mitm-ca.p12` chybí (je jen `.crt`)~~ **VYŘEŠENO (2026-09-29):**
   bundled dev p12 byl v `.gitignore` → v žádném buildu → MITM vždy passthrough. `RootCaInstaller`
   teď CA **generuje na zařízení** (`MitmCertSigner.createSelfSignedCa`, RSA-2048, CA:true) do
   `filesDir/certs/mitm-ca.p12` (heslo přes `resolvePassword()`), self-healing, klíč nikdy v gitu/APK.
   Cert k instalaci vydává `GET /vpn/mitm/ca`. Asset p12 (pokud existuje) má přednost (back-compat).
5. Cert piny expirují 2027-12-31 → pak obnovit SHA-256 v `network_security_config.xml`.

## 13. Fatal freeze root cause (2026-09-30) — UFS resume failure, **ne appka**

**Symptom.** Zařízení tvrdě zamrzlo (bez odezvy na tlačítka, bez charging indikace), následoval hard
watchdog reboot v `06:07:54 CEST`. `com.linux_core` běžel; předchozí session měla `uptime ≈ 42 h 55 m`.
Lokální `logcat`/`dmesg` byly ztraceny (RAM ringbuffer neuloží při I/O výpadku).

**Zdroj pravdy.** `/sys/fs/pstore/console-ramoops-0` — kernel ring buffer z předchozího bootu, přežije
přes reboot v rezervovaném regionu RAM (ramoops driver). Plus `/data/anr/anr_2026-09-30-06-08-42-736`
(App Scout Exception, com.linux_core, `libdebuggerd_client: timeout expired`).

**Časová osa (uptime = sekundy od předchozího bootu):**

```
[154498.98] ufshcd-qcom 1d84000.ufshc: ufshcd_eh_host_reset_handler: reset in progress - 2
[154499.18] ufshcd-qcom 1d84000.ufshc: ufshcd_resume: UFS resume error
[154499.68 → 154502.68] 7× "pwr ctrl cmd 0x18 with mode 0x0 completion timeout" (à 500 ms)
[154502.68] Host self-block=1, hibern8_exit_cnt=40796, outstanding_reqs=0 outstanding_tasks=1
[154502.69] ICE (Inline Crypto Engine) registers dump
[154508 → 154539] kernel žije, userspace visí — poslední řádek je fuel-gauge tik
~30 s po incidentu → hardware watchdog reset
```

**Diagnóza.** UFS host controller (`ufshcd-qcom @1d84000`) selhal při resume z power-managementu. PA
power control (`0x18`) neodpovídal → dm-crypt/fscrypt I/O visí → `/data` nedostupná. Vysoká frekvence
hibern8 tranzicí (`40796 / 43 h ≈ 15/min`) je známý bug source pro UFS resume failures na Qualcomm
(mainline commity `scsi: ufs-qcom: Fix …` z 2021+).

**ANR waiting channels potvrzují sekundární rolu appky:** 0 běžících threadů, všechny na
`binder_ioctl_write_read` / `futex_wait_queue_me` / `epoll_wait` / `pipe_read` / `do_sys_poll`. Appka
nedělala nic, jen čekala na filesystem, který nikdy neodpověděl. `libdebuggerd_client: timeout` = i
debuggerd neuměl otevřít `/data/anr/`.

**Co to NENÍ (vyvráceno v ramoops):**

- ❌ **PRoot SELinux audit flood** — v ramoops **nula** `audit`/`avc` řádků, `audit_lost=0`. Byla to
  hypotéza z pádů 09-26/09-27, ne z tohoto. `nh_freeze_guard` modul (vrstva 1+2: `setattr` dontaudit +
  `auditctl -r 1000`) zůstává správný jako **prevence** (menší I/O burst z proot spawnu = menší
  pravděpodobnost, že sami dotlačíme UFS přes hranu), ale **není fix na tento pád**.
- ❌ **`elf_loader` Go seccomp filter** (hypotéza z `MAX_OVERRIDES 64→256` změny + `gh auth login`).
  Ramoops nemá stopu po `SECCOMP_RET_TRAP` enforcement; `com.linux_core` waiting channels neobsahují
  žádný seccomp handler frame. Hypotéza definitivně **vyvrácena**.
- ❌ **Phantom Process Killer** — appka měla `settings_enable_monitor_phantom_procs=true` (vrácené
  po incidentu ze 09-27, viz `magisk-modules/anti_phantom`).

**Druhý incident 2026-10-01 07:23 — zotavený stall bez rebootu (stejná třída).** ANR bouře 8 s
(`system_server` 2×, `systemui`, `Input dispatching timed out`, pokemonunite), `system_server` ANR
trval 23,4 s, logcat úplně ztichl 07:23:49 → 07:24:10. Power tlačítko stiskl uživatel **jako reakci**
na zamrzlý displej, není to spouštěč. ActivityManager CPU dump za 28 s před ANR:
`70% iowait`, `kworker/u16:12` 56 % kernel, `f2fs_ckpt-253:4` zablokovaný (0 % CPU). `ramoops`
nic nemá (žádný reboot), `dmesg` okno mezitím přeteklo → jediný zdroj byl logcat + `/data/anr`.
**Precursor:** I/O tlak roste **desítky sekund** před prvním viditelným symptomem.

**`nh_freeze_guard` v1.2 — sonda s vlastním spouštěčem (běží vždy, bez konfigurace).** Měří
*okamžitý* stall z PSI `total=` (ne `avg10`, ten má ~10s zpoždění) + `procs_blocked`; po 3 s
`io_full ≥ 50 %` (nebo ≥ 8 D-úloh) přepne na burst: vzorky po 200 ms, D-state úlohy s `wchan`,
kernel stacky f2fs vláken, filtrovaný `dmesg`. Výstup: `/dev/nh_probe/probe.log` (tmpfs, přežije
zotavený stall) a `/dev/pmsg0` (**pstore, přežije i watchdog reboot** → po bootu
`/sys/fs/pstore/pmsg-ramoops-0`). UDP jen volitelně (`HOST=`). Po incidentu:
`sudo grep -E 'TRIGGER|END|dstate|stack' /dev/nh_probe/probe.log`. Detaily v README modulu.

**Třetí incident 2026-10-01 09:16 — PRVNÍ zachycený sondou, mechanismus potvrzen z jádra.**
Živý `dmesg` už byl přepsaný; přežil jen díky `kmsg` dumpu sondy v `/dev/nh_probe/probe.log`.

```
21424.449  5× Read(16) + 1× Write(10) odeslány na sda → UFS NEODPOVÍ
21425-437  sonda: io_full 30→60 %, io_some ~70 %, blocked 6→12, iowait ~50 %
21428.18   TRIGGER (uživatel ve stejné vteřině mačká power)
21454.90   SCSI timeout 30 s → ufshcd_abort všech tagů → failed with err -5
21455.44   LU reset tm cmd timed-out (-110) → ufshcd_eh_host_reset_handler: reset in progress - 2
21455.64   gear 1 → gear 3, ufshpb reset, ufstw (TurboWrite) reset → ZOTAVENO
21466.19   f2fs slow fsync 41 709 ms (charge_logger) — dočištění fronty
```

- **Stejná sekvence jako fatální pád 30. 9.** — tam host reset proběhl taky, ale po něm
  `ufshcd_resume` selhal (`pwr ctrl cmd 0x18 timeout`) → watchdog. Jde o **jeden mechanismus
  s dvěma konci**: UFS firmware přestane odpovídat → 30 s timeout → reset → (ne)zotaví se.
- **Nástup je skokový, ne pozvolný**: 25 s před tím úplně čisto, pak během 1 s 0 → 31 % full.
  Precursor „desítky sekund rostoucího tlaku“ (z incidentu 07:23) byl ve skutečnosti už běžící
  stall v 28s okně. Předpovědět předem to nejde; jde to zachytit do ~3 s od začátku.
- **Zaseknutá čtení byla HPB** (Host Performance Booster 2.0): `Read(16)` s nesmyslnou „LBA“
  (`00 03 8e 18 01 6b 00 67`) = HPB entry v CDB. Stav: `hpb_read_disable=0`,
  `hibern8_on_idle_delay_ms=1` (link usíná po 1 ms → ~15 probuzení/min), `rpm_lvl=3`
  (SLEEP+HIBERN8), TurboWrite zapnutý. Health: EOL 0x01, opotřebení 30–40 % / 10–20 % → **ne**
  konec životnosti. Ovládání: `/sys/devices/platform/soc/1d84000.ufshc/{ufshpb_lu0/hpb_read_disable,
  hibern8_on_idle_delay_ms,ufstw_lu0/tw_enable}` (runtime, reboot vrací).
- **Sonda sama vypadla na 29,5 s** (21436.9 → 21466.5): hlavní smyčka nedělá I/O, takže ji
  zablokoval page fault na file-backed stránce (text mksh/libc z UFS) nebo `pmsg_lock`. Oprava
  (**v1.3**): `service.sh` kopíruje statický `/data/adb/magisk/busybox` + `probe.sh` do
  `/dev/nh_probe/` a spouští `ASH_STANDALONE=1 busybox sh` (kopie se musí jmenovat `busybox`,
  jinak „applet not found“). Sonda navíc loguje `nh ufs …` (hibern8/clkgate/HPB/TW) při startu,
  à 10 min, při TRIGGER a END. Režie ~1,35 % jednoho jádra. Kdyby i busybox sonda vypadávala,
  zbývá jen `pmsg_lock` → zkusit `PMSG=0`.

**Čtvrtý incident 2026-10-02 23:44 — kernel panic ZPŮSOBENÝ SONDOU v1.3** (`ro.boot.bootreason=
kernel_panic,fatal_exception`, uptime 159931 s, varianta B celou dobu aktivní):

```
159900.02  Read(10) 128 bloků odeslán → UFS NEODPOVÍ
159904.63  sonda TRIGGER → podproces ( clock; kmsg; ufs_state ) čte ufstw_lu0/tw_enable
159931.07  SCSI timeout → ufshcd_abort failed err 15 → LU reset -110 → host reset
159931.60  Synchronous External Abort v ufshcd_exec_dev_cmd  (Comm: busybox = sonda)
           ← ufsf_query_flag ← ufstw_sysfs_show_tw_enable ← sysfs read → Kernel panic
```

- `tw_enable` (a obecně `ufstw_*`/`ufshpb_*` atributy) **není proměnná hosta, ale query do
  zařízení**. Čtení čekalo ve frontě dev_cmd a probudilo se uprostřed host resetu, kdy jsou
  registry řadiče bez hodin → přístup = external abort → panic. Bez sondy by stall
  pravděpodobně skončil zotavením jako 10-01 09:16.
- Stejná situace už 2026-10-02 ~20:07 (uptime 146885): sondin podproces visel v
  `ufshcd_exec_dev_cmd` (vidět v `dstate`), tehdy reset prošel bez paniky (`tw=` prázdné).
- **Fix v1.4:** `ufs_state` čte jen `hibern8_on_idle_*` a `clkgate_delay_ms_*` (hodnoty z RAM
  hosta), volá se jen při startu a v baseline, nikdy při TRIGGER/END. **Nikdy z host nástrojů
  nečíst ufstw/ufshpb/query sysfs atributy, zvlášť ne během stallu.**
- **Varianta B nepomohla:** během ní 2 stally s abortem (146912 zotaven, 159931) + TRIGGER
  v 141317 a 88721 → hibern8/clkgate delay není spouštěč. Po rebootu je zpět default (1/10/50).

**Pasti při psaní host shell skriptů (mksh) — ověřeno 2026-10-01:**

- **`printf` není v Android mksh builtin** (`type printf` → `/system/bin/printf` = exec toyboxu).
  Pro výstup bez exec použít `print -ru<fd> -- "$x"`. Stejně `sleep`, `date`, `cat` = exec.
- **mksh má 32bit aritmetiku** (`$((2147483647+1))` = záporné). Kumulativní čítače (PSI `total`
  v µs, jiffies) ořezat na posledních 9 číslic a delty počítat modulo 1e9.
- **`sudo sh -c '…'` testuje guest dash, ne hostový mksh** (chybový formát `/bin/sh: 1:`) a běží
  pod PRootem (každé čtení `/proc` ~10× dražší). Host root bez PRootu: `ashell -c 'su -c "…"'`
  (kontext `u:r:magisk:s0`); jen parse kontrola: `ashell -c '/system/bin/sh -n <soubor>'`.
- **`A && B &` pošle na pozadí celý seznam** → fork v každé iteraci; `SIGCHLD` pak přeruší
  `read -t` spánek → smyčka se roztočí (naměřeno 34 % CPU). Spánek dospávat do deadlinu.
- **PSI `full` na nečinném telefonu klame**: když nic jiného neběží, jediný `fsync` = 100 % full.
  Běžné zápisové špičky (i `magisk --install-module` 10 kB modulu → 59 %) trvají 1–2 s → práh
  musí být časový (≥ 3 s v řadě), ne jen výška.

**Nevracet zpět (pitfalls z této analýzy):**

1. **`__wrap_chmod` skip pro `/dev`** (v `tools/modal_build.py` `_SELINUX_FIX_C`) — nevracet zpět;
   84 % denials z proot spawnu bylo `setattr` na `/dev/__properties__/*`.
2. **`nh_freeze_guard` probe.sh běží mimo proot na hostu** — nedávat dovnitř. Každý proot start
   sám generuje stovky SELinux denials; monitor uvnitř by zhoršoval, co má měřit.
3. **Nekonstantně analyzovat časové okno logcatu bez ověření `uptime`** — pád 09-30 mě
   předtím zavedl na okno `06:44–06:52`, což bylo 36+ minut PO bootu, tj. zdravý běh, ne recovery
   z pádu. `uptime` v sekundách × převod na absolutní čas boot momentu je první krok každé freeze
   analýzy.
4. **`App Scout Exception`** v ANR = MIUI/Xiaomi FW_SCOUT_HANG watchdog, ne standardní AMS ANR.
   Jméno tě přímo směřuje k tomu, kdo tě zabil — nezaměňovat s běžným "Input dispatching timed out".

**Volitelný mitigation experiment** (paliativní, ne fix): `setprop persist.vendor.ufs.hibern8_on_idle_enable 0`
by mohl prodloužit interval mezi UFS resume failures za cenu baterie. Reverznout, pokud drain výrazně
vzroste. Skutečný fix je jen na úrovni kernel driveru — mimo dosah appky.

