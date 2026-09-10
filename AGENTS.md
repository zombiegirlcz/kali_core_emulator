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
| Podpis | `app/release.jks` — debug i release **stejný** keystore → `adb install -r` bez odinstalace |
| Alias/heslo | `releaseKey` / `password123` (env: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) |

Před každou distribuovanou verzí navyš `versionCode`.

## 3. Architektura

Jednomodulová app (`:app`) + in-tree nativní knihovna `:linux-x11`
(`app/src/main/linux-x11`, `include(":linux-x11")` v `settings.gradle.kts`). Spouští Kali/ParrotOS
v nerootovaném PRoot kontejneru (Termux terminál) s AdGuard C++ VPN, TLS MITM a X11 serverem.
GUI není součást core — desktop renderuje **externí** NetHunter X11 Launcher (`kali_GUI` app).

### Runtime porty (host loopback)

| Port | Služba |
|---|---|
| 1337 | `LocalApiServer` — REST most (baterka, toast, wifi, GPS, schránka, VPN, USB, `/shell`, `/distro/*`) |
| 13338 | AI agent démon (`nethunter_agent.py`, ReAct LLM, nástroj `analyze_network`) |
| 13339 | VPN bypass proxy (`http(s)_proxy` pro guest → obchází AdGuard) |
| 6000/tcp → `localabstract:/x11` | X11 server (`linux-x11`, `DISPLAY=:1`) přes `adb reverse` |

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
| `ShizukuManager` | Privilegované příkazy bez rootu (Shizuku → su -c → ADB → dialog) |
| `UsbHostManager` / `usb_bridge.c` / `usbfd_jni.c` | Raw USB pro mtkclient/EDL (`/usb/stream` binární frame protokol) |
| `core/` je ~66 souborů, `ui/` 8, `security/` 10 — nevyjmenovávat všechny, viz README |

### PRoot binární strategie

- **Jen STATICKÉ buildy** z kořene `assets/`: `proot-static-{aarch64,arm,i686,x86_64}` +
  `loader-static-*`. Dynamické (`proot-*`, `loader-*`, `libtalloc-*.so`) byly z repa odstraněny
  **2026-09-08** (`ProotManager.deployArchBinaries`).
- `talloc` se instaluje do rootfs jako `lib/libtalloc.so.2`.
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
   `_build_usrtools`) v `tools/modal_build.py` — NDK cross-compile → `assets/` (např. `su_daemon`,
   `su_wrapper`, `usb_bridge`) nebo `jniLibs/arm64-v8a/` (`*.so`).
2. **Spusť `zsh mbuild native`** (nebo `all`) + `pull_full_assets()` → artefakty do lokálního repa.
3. **Commitni a pushni binárky** (`assets/su_daemon`, `su_wrapper`, `usb_bridge`, `usr/bin/*`, `usr/lib/*`).
   `.gitignore` má pro ně explicitní `!` výjimky a sync klonuje z GitHubu — necommitnutá binárka
   v APK nebude. (Přesně tak se 2026-08-23 ztratily bionic usrtools; obnova:
   `modal run tools/rebuild_usrtools_recovery.py::rebuild`.)
4. Nikdy nenechávej artefakt jen na Volume — další `rsync --delete` ho smaže.

## 5. Závislosti a konvence

- Version catalog `gradle/libs.versions.toml` už obsahuje i `commons-compress`, `xz`, Termux
  (`terminal-view`/`terminal-emulator`/`termux-shared` v0.118.0), `guava` 33.6.0, `bouncycastle`,
  `androidx.biometric` — už **není** potřeba deklarovat přímo v `app/build.gradle.kts`.
  Přímo jsou jen `androidx.viewpager2`, `recyclerview`, `onnxruntime-android:1.17.1` a `:linux-x11`.
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
- **LocalApiServer.kt:** Bearer token (UUID v `api_security` SharedPreferences), localhost detekce pro
  citlivé endpointy, blocklist destruktivních příkazů, max délka commandu 1024 znaků.
- **RootfsManager.kt:** HTTPS + host whitelist (`kali.org`, `parrot.sh`, `raw.githubusercontent.com`),
  TLS 1.2+, OkHttp timeouty. **VpnFirewallManager.kt:** IPv4/IPv6 validace před blokací.
- **res/xml/network_security_config.xml:** cert piny (platnost do 2027-12-31).
- Hotovo: cert pinning, `OffensiveEngine` notification confirm (Allow/Deny, 30 s), odstraněný hex dump
  z CSV/JSON exportu, autentizace agenta na 13338.

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
  `/data/data/com.linux_core/shared_prefs/api_security.xml`).
- **API (1337, Bearer):** `POST /vpn/mitm` (on|off), `GET /vpn/mitm`, `GET /vpn/mitm/ca`,
  `GET /vpn/mitm/logs[?format=json]`.
- **Známé bugy — nevracet zpět:** double-flip v `writeToServer` (volající flipují sami); passthrough
  vždy s **fresh socketem** (jinak otrávený kanál); `writeFully()` místo non-blocking dropu při `w=0`;
  `sendTcpAck` na MITM cestě; fail-fast server handshake (~50 iterací); `protect()` fail → `null`;
  QUIC blokovat jen když MITM aktivně dešifruje (`shouldBlockQuic()`); `extractSni`/`isTlsClientHello`
  čtou sessionIdLen ze stejného offsetu (43); `RootCaInstaller` debug fallback heslo `"nethunter-dev"`
  (P12 v assetech); forged cert musí mít **vlastní RSA keypair** (ne CA private key).
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

**PRoot/perf:** seccomp je aktivní (`proot -V` → `seccomp_filter = yes`) — ne „vypnutý"; `--link2symlink`
nutné (apt/dpkg zálohy přes `link()`); `-0` kvůli fake root UX; tracer cost ~100 µs/syscall je intrinsický
(ne degradace live vs. fresh); **`LD_LIBRARY_PATH` v `hostShellEnv()` způsoboval SIGBUS** — nikdy
nepřidávat; `/usr/sbin/find` musí být symlink na `find` (ne `rg`).

**su_daemon / Root Bridge:** fork-per-connection (parent hned `accept()`, žádné blokování nových `sudo`),
POLLHUP → SIGKILL command childa, config v `g_*` globálech, ignorovat SIGPIPE, `pkill -x` (ne `-f`),
fail-closed bez launcheru (`_exit(126)`), **re-entry do PRoot** místo host `chroot` (ochrana proti
host-globálním příkazům), ownership fix `nftw`+`lchown` s vynecháním bind dirů
(`dev proc sys run sdcard mnt system vendor product apex storage data`), `@FIX` režim + `nh fix permission`.

**Terminál:** paste přes `emulator.paste()` (bracketed paste, ESC/C1 sanitizace), ne `session.write()`;
spawn s `LANG=C.UTF-8`/`LC_CTYPE=C.UTF-8` (glibc ≥ 2.35); MIUI multi-input = debounce
`updateSuggestions()` (žádné synchronní `Button()` v IME `commitText`).

**Bionic usrtools:** všechny 4 nástroje Bionic (glibc v app kontextu padá na seccomp `rseq` → SIGSYS;
v guestu to maskuje PRootův seccomp filtr). Deploy musí mít exec bit + version gate.

**Auto-start:** `RECEIVE_BOOT_COMPLETED` + `.core.BootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED;
**ne** LOCKED_BOOT_COMPLETED — filesDir je credential-encrypted), `TerminalService` START_STICKY
restart s dedupem (jedna cron session) a backoffem; toggle `boot_autostart`.

**Launcher:** flag `-E` pro proot **neexistuje** — LD_PRELOAD/PROOT_LOADER se v guestu řeší přes
`/bin/sh -c 'unset LD_PRELOAD PROOT_LOADER; exec "$@"'` před prvním exec.

## 12. Známé technické dluhy

1. MITM je historicky nestabilní (padá do passthrough na `SSLException` v unwrapu) — vždy ověř na zařízení.
2. Widget zakomentován v manifestu („pro later").
3. DNS tab prakticky prázdný (moderní Android jede DoH/TCP, ne UDP/53).
4. `app/src/main/assets/certs/mitm-ca.p12` chybí (je jen `.crt`) — build projde díky debug fallbacku.
5. Cert piny expirují 2027-12-31 → pak obnovit SHA-256 v `network_security_config.xml`.
