# AGENTS.md — NetHunter AI Operator

## Sestavení a ověření

**ZÁKAZ LOKÁLNÍHO BUILDU!**
Nikdy nespouštěj `./gradlew assembleDebug` nebo jiné buildovací příkazy lokálně. Lokální prostředí pro to není uzpůsobené a build spadne nebo se zablokuje. Pro sestavení APK VŽDY používej Modal:

_jsi spusten uvnitr samotne aplilace takze pro zobrazeni logcat napis_

```bash
nethunter-log [-n line] [-g grep]
```


```zsh
cd kali_core_emulator && zsh mbuild #zpousti build na modalu 
```


## Balíček a SDK (copilot-instructions.md je zastaralé)

- **Balíček:** `com.linux_core` — NE `cz.hackai.nethunter_ai_operator`
- **Zdrojová cesta:** `app/src/main/java/com/linux_core/`
- **minSdk 28 / targetSdk 28** (ne 33/36 jak tvrdí copilot-instructions)
- **Java kompilátor:** JVM 17 (`sourceCompatibility = JavaVersion.VERSION_17` v `app/build.gradle.kts`)

## Podepisování

- **Debug** i **release** používají **stejný keystore**: `app/release.jks`
- Alias `releaseKey`, heslo `password123`
- To zajišťuje, že `adb install -r` funguje napříč debug sestaveními bez nutnosti odinstalace
- Před každou distribuovanou verzí navyš `versionCode` v `app/build.gradle.kts`

## Architektura

Jednomodulová Android aplikace (`:app`), která spouští Kali/ParrotOS v nerootovaném PRoot kontejneru s Termux terminálovou emulací a vestavěnou AdGuard C++ VPN/firewall službou.

### Klíčové runtime porty (Android host loopback)

| Port | Služba |
|------|--------|
| 1337 | `LocalApiServer` — API most pro příkazy hostujících terminálů (baterka, toast, wifi, GPS, schránka, VPN ovládání) |
| 13338 | AI agent démon (`nethunter_agent.py`) — ReAct LLM agent s nástrojem `analyze_network` |
| 13339 | VPN bypass proxy — směruje příkazy mimo AdGuard zachytávání |

> **Note (v4.3):** `VpnProxyManager` byl refaktorován — odstraněna SOCKS5 rotace (pool 6 proxy + 3 režimy), nahrazena jedním custom `IP:Port` fieldem pro TCP tunnel. Proxy je čistě volitelná; není-li nastavena, traffic jde direct.

### Hlavní třídy

| Třída | Role |
|-------|------|
| `MainActivity` | Compose UI: stažení/extrakce rootfs, spuštění terminálu, VPN centrum dashboard |
| `ProotManager` | Detekce CPU architektury, nasazení PRoot binárek z assets, generuje `launcher.sh` shell skript, nasazuje pomocné skripty do guest `/usr/local/bin/` |
| `RootfsManager` | OkHttp stahování + commons-compress tar.xz extrakce, emituje `Flow<Int>` průběh |
| `TerminalActivity` | Termux `TerminalView`, správa relace, předletové kontroly |
| `VpnCaptureService` | Android VPN služba, životní cyklus JNI AdGuard enginu |
| `LocalApiServer` | Loopback HTTP server na 1337 vystavující Android senzory/funkce pro hostující OS |
| `ProcessResolver` | BFS `/proc` procházení mapující sockety na chroot procesy |
| `AIBrain` | ONNX runtime klasifikátor na živých síťových tocích |

### PRoot binární strategie

Assety v `app/src/main/assets/`:
- **Dynamické (arm64):** `proot-aarch64` + `loader-aarch64` + `libtalloc-aarch64.so` (používá `PROOT_LOADER` + `LD_PRELOAD`)
- **Ostatní architektury:** `proot-arm`/`proot-i686`/`proot-x86_64` + odpovídající `loader-*` + `libtalloc-*.so` (dynamické)
- **Standalone/statické binárky (`proot_static`, `proot-static-*`, `loader_static`, `loader-static-*`) byly odstraněny 2026-08-01** — nefungovaly, launcher používá výhradně dynamický PRoot
- Python skripty `extract_proot.py` / `extract_libtalloc.py` / `update_static_binaries.py` aktualizují tyto binárky

### Native build pipeline (C moduly → assets) ⚠️ PRAVIDLO

Nativní C moduly (`app/src/main/cpp/*.c`) se kompilují na Modal cloudu do `app/src/main/assets/` a `jniLibs/` a **musí být vždy v APK**. Postup při jakékoli změně/addici native modulu:

1. **Přidej kompilační krok do `build_native()` v `tools/modal_build.py`** — NDK cross-compile výstupu do `assets/` (např. `su_daemon`, `su_wrapper`, `usb_bridge`) nebo `jniLibs/arm64-v8a/` (`.so`).
2. **Přidej název výstupu do `NATIVE_BINARIES` v `mbuild`** — skript pak binárku vždy stáhne z Volume do lokálního `app/src/main/assets/` před uploadem+buildem.
3. **Přidej výstup do `_NATIVE_ASSET_EXCLUDES` v `tools/modal_build.py`** — `upload_src` používá `rsync -a --delete` a bez exclude by binárku smazal z Volume (není v baked image) → APK by byl bez ní.
4. **Stáhni binárky do assets** (z Volume): `modal volume get --force kali-build-data src/app/src/main/assets/<bin> app/src/main/assets/<bin>` nebo spusť `zsh mbuild native` (dělá upload → build_native → pull do lokálních assets).

**Pořadí buildu se nesmí měnit:** nejdřív `pull_binaries()` (binárky → lokální assets), pak `upload_src`, pak `build`. `zsh mbuild build` to dělá automaticky.

**Známý bug (opraven 2026-08-02):** `mbuild build` spouštěl upload → build BEZ `build_native`; rsync `--delete` smazal binárky z Volume → APK 128 MB bez `su_daemon`/`su_wrapper` (detekováno přes chybějící `assets/` v zipfile a `execvp failed` v su_wrapper). Fix: pull_binaries + rsync excludes + `NATIVE_BINARIES` list.

### jniLibs struktura

`app/src/main/jniLibs/arm64-v8a/` obsahuje AdGuard nativní knihovny. **Na pořadí načítání záleží:**
`liba` → `libio_utils` → `libcommon_native_jni` → `libadguard-core` → `libadguard-dns`

Ostatní architektury (x86, x86_64, armeabi-v7a) obsahují pouze proot/loader/talloc.

## Version Catalog a závislosti

- Version catalog v `gradle/libs.versions.toml`
- **NEJSOU ve version catalogu** (deklarovány přímo v `app/build.gradle.kts`):
  - `commons-compress` (1.28.0), `xz` (1.12)
  - Všechny Termux knihovny (`terminal-view`, `terminal-emulator`, `termux-shared` ve v0.118.0)
  - `guava` (33.6.0-android)
  - `com.microsoft.onnxruntime:onnxruntime-android:1.17.1`
  - `androidx.viewpager2:viewpager2`, `androidx.recyclerview:recyclerview`
- **Nepřesouvat** tyto závislosti do `libs.versions.toml` bez výslovného pokynu
- Guava exclude: `com.google.guava:listenablefuture` musí být vyloučeno ze všech Termux závislostí

## Nastavení repozitáře

- **Git LFS:** vyžadováno (`.apk` soubory sledovány přes LFS, viz `.gitattributes`)
- **Git submodul:** `nethunter-store-data` z `https://gitlab.com/zombiegirlcz/nethunter-store-data.git`
- Klonování: `git clone --recurse-submodules` plus `git lfs pull`

## Konvence

- `packaging.jniLibs.useLegacyPackaging = true` — vyžadováno pro Termux nativní `.so` soubory
- `org.gradle.parallel=false` v `gradle.properties` (paralelní sestavení vypnuta)
- Kotlin styl kódu: `official`
- Compose BOM `2026.05.01` spravuje všechny verze Compose závislostí
- ONNX modely: `vpn_brain.onnx` a `vpn_brain_v7.onnx` v assets
- Modal.com sestavení: `modal_build.py` pro bezserverové Android sestavení na Modal cloudu
- `.github/copilot-instructions.md` existuje, ale má špatný název balíčku a zastaralé SDK úrovně — nespoléhat se na něj bez ověření proti `app/build.gradle.kts`

## Bezpečnost (Security Audit — SECURITY_AUDIT.md)

Nalezeno 25 zranitelností (9 CRITICAL, 8 HIGH, 5 MEDIUM, 3 LOW). Hlavní opravené oblasti:

### Aplikované patche

- **build.gradle.kts:** Keystore hesla přesunuta do `System.getenv()` / gradle properties; `isJniDebuggable` odstraněno
- **AndroidManifest.xml:** `allowBackup=false`, `usesCleartextTraffic=false`, `networkSecurityConfig` přidán; `TerminalActivity`, `NetHunterNotificationListenerService`, `NetHunterAccessibilityService`, `DistroDocumentsProvider` změněny na `exported="false"`; duplicitní service declaration odstraněn
- **LocalApiServer.kt:** Bearer token autentizace (auto-generovaný UUID token uložený v `api_security` SharedPreferences), localhost detection pro citlivé endpointy, blocklist destruktivních shell příkazů (`rm -rf /`, `mkfs`, `reboot` atd.), limit délky commandu na 1024 znaků
- **RootfsManager.kt:** HTTPS + host whitelist enforcement (`kali.org`, `parrot.sh`, `raw.githubusercontent.com`), TLS 1.2+ sslSocketFactory, OkHttp connect/read timeout
- **VpnFirewallManager.kt:** IPv4/IPv6 validace před blokováním IP adres
- **res/xml/network_security_config.xml:** Vytvořen s certifikátovými piny pro Kali (GTS WE1 + GTS Root R4) a Parrot/GitHub (LE YR2 + ISRG Root YR) domény — platnost do 2027-12-31

### Zbývající body k dořešení

1. ~~Certificate pinning — dosadit správné SHA-256 otisky~~ ✅ **HOTOVO**
2. ~~OffensiveEngine — notification-based confirm (Allow/Deny), 30s timeout~~ ✅ **HOTOVO**
3. ~~VpnLogManager payload hex dump — odstraněno z CSV/JSON log exportu i data class~~ ✅ **HOTOVO**
4. ~~AI agent na portu 13338 — přidat autentizaci do agent daemona~~ ✅ **HOTOVO**

### Podepisování (po security patchi)

- Debug i release používají stejný keystore: `app/release.jks`
- Hesla se načítají z env vars: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Fallback: gradle properties `keystore.password`, `key.alias`, `key.password`
- Při běžném vývoji bez env vars padá fallback na `password123` (release.jks)

### Certifikátové piny (aktuální k 2026-06-27)

Kali domény (kali.org, kali.download, images.kali.org) → GTS:
- Leaf kali.org: `vxHMRAr73HgUyGzWLG8C4xtO/qsK9nkPG59jH3i/mqc=`
- Leaf kali.download: `xAu7m0o10HbvBkBpvcS7+PYtxxX1rdUN8FHEI2Kg0Fo=`
- Intermediate GTS WE1: `kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=`
- Root GTS Root R4: `mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=`

Parrot + GitHub (parrot.sh, deb.parrot.sh, raw.githubusercontent.com) → LE:
- Leaf deb.parrot.sh: `lkI2NEknt/oq8INt5aiW7TriA18Z1mMNvvT6tjZjghs=`
- Leaf raw.githubusercontent.com: `PaZDXCM44SEEkf5qy7PN/gi0Z1u+nhGbRcKHSZQxhmA=`
- Intermediate LE YR2: `nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=`
- Root ISRG Root YR: `fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=`

## Certificate & Attestation module

Plán implementace viz `.kilo/plans/1782753944642-certificate-attestation-plan.md`. Implementace sídlí v `app/src/main/java/com/linux_core/security/`:

- `CertificateManager` — fasáda, inicializuje se z `MainActivity.onCreate` (i `LocalApiServer.start` jako záloha).
- `RootCaInstaller` — MITM CA v `assets/certs/mitm-ca.crt`; produkční build nikdy neinstaluje CA systémově.
- `SslContextFactory` — `assets/certs/internal.p12`, fallback pass z `KEYSTORE_PASSWORD` env.
- `AttestationKeyManager` — alias `attest_ec` (StrongBox→TEE) a `attest_secret` (AES-GCM-256), oba 30s biometric window.
- `AttestationVerifier` — lokální PKIX chain + nonce + signature.
- `BiometricGate` — wrapper nad `androidx.biometric`.
- `KeystoreManager` — encrypt/decrypt Base64 blobů (používá `LocalApiServer` pro `api_security` token a `UserProfileStore` pro profil).
- `MitmCertSigner` — BouncyCastle re-sign listu zachycených TLS serverů.

BuildConfig příznaky: `ENABLE_MITM`, `ENABLE_ATTESTATION` (oba default `true` v `app/build.gradle.kts`).
Debug `network_security_config_mitm.xml` je samostatný soubor (pouze dokumentace + zakomentovaný blok), produkční `network_security_config.xml` zůstává beze změn.
Unit testy: `app/src/test/java/com/linux_core/security/`.

## TLS MITM Inspection

Kompletní TLS MITM proxy pro dešifrování HTTPS provozu v VPN tunelu.

### Architektura MITM

| Soubor | Účel |
|--------|-------|
| `TlsClientHelloParser.kt` | Parser TLS Client Hello, detekce TLS a extrakce SNI |
| `TlsMitmEngine.kt` | Singleton spravující MITM session (`TlsMitmSession`) |
| `TlsMitmSession` | Jedna relace: provádí server-side handshake, podepisuje cert, client-side handshake, proxy plaintext |
| `RootCaInstaller.kt` | Načte MITM CA, podepíše server cert, vytvoří `SSLContext` s forged certem |
| `MitmCertSigner.kt` | BouncyCastle podepisování listových certifikátů |
| `VpnNatEngine.kt` | Detekuje TLS Client Hello v `handleTcpPacket` a předává do `TlsMitmEngine` |
| `VpnSecurityTab.kt` | UI: žlutý indikátor `TLS MITM INTERCEPT`, karta `LIVE DECRYPTED TLS TRAFFIC` |
| `VpnSettingsTab.kt` | Přepínač `TLS MITM Inspection` v Nastavení |
| `LocalApiServer.kt` | Endpointy `/vpn/mitm`, `/vpn/mitm/ca` a `/vpn/mitm/logs` pro vzdálené ovládání |

### Tok MITM relace

1. `VpnNatEngine.handleTcpPacket` detekuje TLS Client Hello
2. `TlsMitmEngine.onClientData` vytvoří `TlsMitmSession`
3. Session parsuje SNI, naváže spojení k cíli, provede server-side TLS handshake
4. Extrahuje server certifikát, podepíše ho `RootCaInstaller.signLeafForServer()`
5. Vytvoří client-side `SSLEngine` s forged certem, proběhne handshake s klientem
6. Proxy čte zašifrovaná data, dešifruje je, přepošle plaintext mezi klientem a serverem
7. Části plaintextu se ukládají do `decryptedSnippets` bufferu (max 200 řádků)

### Nastavení a XY

Hodnota `enable_mitm` se ukládá do `SharedPreferences` (`vpn_settings`). Výchozí: `BuildConfig.ENABLE_MITM` (`true`).

#### CLI příkazy (`vpn-cli`)

Skript: `app/src/main/assets/vpn-cli`

```bash
# Zapnutí MITM
vpn-cli mitm on

# Vypnutí MITM
vpn-cli mitm off

# Stav MITM
vpn-cli mitm status

# Zobrazit/exportovat Root CA certifikát
vpn-cli mitm ca

# Uložit Root CA certifikát do souboru
vpn-cli mitm ca > /tmp/nethunter-ca.crt

# Formátovaný dešifrovaný provoz
vpn-cli logs

# JSON výstup
vpn-cli logs json
```

Token se čte z `/data/data/com.linux_core/shared_prefs/api_security.xml`.

#### HTTP API (port 1337)

```http
POST /vpn/mitm
Body: on|off

GET /vpn/mitm
{"mitm":"on","active_sessions":2,"sessions":[{"port":54321,"snippet":"..."}]}

GET /vpn/mitm/ca
(vrátí PEM certifikát s Content-Type: application/x-x509-ca-cert)

GET /vpn/mitm/logs
GET /vpn/mitm/logs?format=json
```

Ověření: `Authorization: Bearer <token>`.

## CLI a ovládání VPN

Vedlejší nástroj `vpn-cli` (asset `vpn-cli`) poskytuje terminálové rozhraní pro ovládání VPN a MITM z hostujícího OS.

```bash
vpn-cli status        # VPN stav
vpn-cli start         # Spustit VPN
vpn-cli stop          # Zastavit VPN
vpn-cli logs          # MITM dešifrovaný provoz (text)
vpn-cli logs json     # MITM provoz (JSON)
vpn-cli mitm on       # Zapnout TLS MITM
vpn-cli mitm off      # Vypnout TLS MITM
vpn-cli mitm status   # Stav MITM
vpn-cli mitm ca       # Zobrazit/exportovat Root CA certifikát
```

CLI komunikuje s `LocalApiServer` na `127.0.0.1:1337`.

## Dokumentace

Detailní dokumentace MITM feature je v `nethunter_docs.md`.

## Diagnostické CLI nástroje

### nethunter-log

Python skript pro barevné formátované zobrazení logcat záznamů aplikace bez nutnosti ADB. Nasazuje se z `ProotManager.kt` do guest `/usr/local/bin/`.

```bash
nethunter-log              # posledních 100 řádků
nethunter-log 50           # posledních 50 řádků
nethunter-log -n 200       # posledních 200 řádků
nethunter-log -g "Vpn"     # filtrovat podle vzoru
nethunter-log -n 100 -g "TlsMitm"  # kombinace
```

Barevné schéma: V=šedá, D=modrá, I=zelená, W=žlutá, E/F=červená tučná. Automatické zvýraznění klíčových slov (`error`/`fail`=červeně, `success`/`established`=zeleně).

HTTP API endpoint: `GET /app/logs?limit=N`

### TlsMitmEngine opravy (v4.2)

- **Oprava šifrování klient→server:** Klientská data se nyní správně zašifrují pomocí `serverEngine.wrap()` před odesláním na vzdálený server (dříve se posílal raw plaintext)
- **Odstranění duplicitního unwrap bloku:** Redundantní druhý `clientEngine.unwrap` + `writeToServer` blok s double-flip chybou byl odstraněn
- **Adaptivní CPU backoff:** `proxyLoop` nyní spí 1ms při aktivním provozu a 15ms při nečinnosti (dříve konstantně 1ms = burn CPU)

## Session 2026-07-05 — VPN/MITM debugging

### Problém: VPN zabíjí internet na novém zařízení

- GitHub načetl úvodní stránku (HTML), ale CSS/JS z CDN ne — QUIC (HTTP/3) dělal problémy
- `curl` z terminálu fungoval, protože app je v `addDisallowedApplication` → obchází VPN
- Chrome na telefonu jel přes NAT engine → pomalejší a timeouty

### Opravy provedené v této session

| Fix | Soubor | Popis |
|-----|--------|-------|
| QUIC blokování | `VpnNatEngine.kt:251` | UDP/443 drop (nyní jen když MITM ON) |
| DNS UDP zápis | `VpnNatEngine.kt:312-316` | První UDP write v BLOCKING režimu (API>33 non-blocking write vrací 0) |
| TCP SYN_RECEIVED timeout | `VpnNatEngine.kt:1068` | Session stuck v SYN_RECEIVED >15s se ukončí |
| Diagnostika spojení | `VpnNatEngine.kt:705` | Loguje čas navázání WAN spojení |
| `ENABLE_MITM` default `false` | `app/build.gradle.kts:28` | MITM defaultně vypnutý |
| `cleartextTrafficPermitted` | `network_security_config.xml:3` | Povolen HTTP pro testování |
| Protect() fail → IOException | `VpnNatEngine.kt:297` | Místo tichého pokračování do smyčky |
| IPv6 passthrough | `VpnNatEngine.kt` | IPv6 pakety se propouští do TUN |
| Zdravotní check | `VpnCaptureService.kt` | 3 faily/10s místo 1 fail/5s |
| DNS na API 33+ | `VpnCaptureService.kt` | `activeNetwork` místo deprecated `allNetworks` |
| Deprecation warnings | celý projekt | API 33+ deprecations, compose, `Divider`→`HorizontalDivider`, `ClipboardManager`→`LocalClipboard`, `startActivityForResult`→`rememberLauncherForActivityResult`, `Icons.Default.ShowChart`→`Icons.AutoMirrored.Filled.ShowChart`, `LinearProgressIndicator` progress lambda |

### MITM engine — zásadní bugy nalezené a opravené

1. **`RootCaInstaller.resolvePassword()` vracel `null`** — security audit smazal hardcoded `"nethunter-dev"` fallback (to je heslo k `assets/certs/mitm-ca.p12`, ne shell prikaz). P12 soubor v assetch ale porad pouziva heslo `"nethunter-dev"`. Debug build bez nej vracel `null` → CA private key se nenacte. Fix: obnoven debug fallback `"nethunter-dev"`.

2. **`createServerSslContext()` ukládal CA key jako private key pro forged cert** — `ks.setKeyEntry(ALIAS, caKey, ...)` uložil CA private key, ale forged cert měl `template.publicKey` (serverův RSA klíč). Privátní klíč nesedí k certu → TLS 1.3 podpis (ECDHE signature) vždy selže → Chrome zahodí spojení. Fix: generuje se vlastní RSA keypair, cert má `keyPair.public`, keystore ukládá `keyPair.private`.

3. **`onClientData()` zavřel `socketChannel` a MITM engine neposlal `initialClientHello` na server** — při pádu do passthrough se initial ClientHello (TLS handshake start) nikdy nedostal k reálnému serveru → server zahodil spojení. Fix: v `passthroughLoop()` se nejdřív pošle `initialClientHello` do `serverChannel`.

4. **MITM selhání → `close()` místo passthrough** — když `connectServer()` nebo `isAvailable()` selhalo, volalo se `close()` (RST) místo `fallingBackToPassthrough()`. Fix: oba případy volají `fallingBackToPassthrough()` který reconnectne server.

5. **Stav k 2026-07-05:** MITM engine stále nefunguje — všechny TLS spojení padají do passthrough s `SNI=null`. Pravděpodobná příčina: `TlsClientHelloParser.extractSni()` vrací null nebo `isTlsClientHello()` nedetekuje TLS. Po pádu do passthrough internet jede (data se proxují), ale při MITM ON je QUIC blokovaný → pomalejší.

### Nové funkce přidané v této session

- **`MitmCertSigner.signWithPublicKey()`** — varianta `sign()` která bere přímo `PublicKey` místo template certu
- **`RootCaInstaller.createCaptureOnlySslContext()`** — generuje RSA keypair, vytvoří cert podepsaný CA, SSLContext bez spojení k serveru
- **`VpnSettings.isMitmCaptureOnly()`** — přepínač pro capture-only režim (default `false`)
- **Capture-only MITM** (`TlsMitmEngine.kt`): `startCaptureOnly()` + `captureLoop()` — dešifruje lokálně, neposílá nic ven, timeout 10s

### Doporučený postup při selhání MITM

1. Zkontrolovat `vpn-cli mitm status` jestli je MITM ON
2. Zkontrolovat `nethunter-log -g "TlsMitm"` — hledat `SNI=null` nebo `falling back to passthrough`
3. Zkontrolovat `nethunter-log -g "RootCaInstaller"` — hledat chyby načítání CA
4. Ověřit že `assets/certs/mitm-ca.crt` a `mitm-ca.p12` existují
5. Ověřit že CA je nainstalovaná v trust store zařízení
6. Pokud vše selže: `vpn-cli mitm off` pro návrat k normálnímu provozu

## Session 2026-07-06 — MITM stále mrtvý, nalezen double-flip bug

### Diagnóza

V logu:
```
MITM SNI=null for port 45406
Server handshake failed: status=NEED_UNWRAP, cipher=SSL_NULL_WITH_NULL_NULL
Falling back to passthrough for port 45406
```

- `SNI=null` i po opravě `TlsClientHelloParser.extractSni()` — parsování funguje (falešná stopa)
- **Skutečný bug:** `writeToServer()` v `TlsMitmEngine.kt:804` volá `buf.flip()` — ALE všichni volající už flip volají před voláním:
  - `runEngineHandshake.writeToTransport` → `buf.flip(); writeToServer(buf)`
  - `driveServerHandshake` → `netOut.flip(); writeToServer(netOut)`
  - `handleAppData` → `serverNetOut.flip(); writeToServer(serverNetOut)`
- Výsledek: **double-flip** — po prvním flippu je position=0, limit=oldPos; po druhém flippu je position=0, limit=0 → `buf.hasRemaining()` vrací false → **ClientHello se nikdy nepošle na server** → server handshake zůstává viset v NEED_UNWRAP navždy (až do timeoutu 800 iterací)
- `TlsClientHelloParser.extractSni()` oprava (offset+6 místo offset+5 + `pos+=4` místo `pos+=3`) je správná, ale nebyla nutná — `isTlsClientHello` a `extractSni` teď čtou sessionIdLen ze stejného offsetu 43

### Opravy v této session

| Fix | Soubor | Popis |
|-----|--------|-------|
| Double-flip `writeToServer` | `TlsMitmEngine.kt:807` | Odebráno `buf.flip()` — všichni volající už flip provedli |
| `initialClientHello` forwarding | `TlsMitmEngine.kt:973-986` | Při pádu do passthrough se ClientHello pošle do serverChannel před vstupem do smyčky |
| `extractSni` off-by-one | `TlsClientHelloParser.kt:32-35` | Session ID length čten z offset+43 místo offset+42; handshake length čten z [6],[7],[8] místo [5],[6],[7] |

### Stav k 2026-07-06

- ✅ `writeToServer` double-flip opraven — ClientHello se pošle na server
- ✅ `initialClientHello` forwarding v passthrough funguje (log: `Forwarded initial TLS ClientHello (536B) for port 45406`)
- ✅ `extractSni` off-by-one opraven
- ❓ APK s opravou existuje, ale zatím netestováno na zařízení
- Stále nevyřešeno: pokud MITM handshake selže, QUIC/443 je blokovaný → Chrome pomalejší

### Relevantní log (2026-07-06, build #2, PID 24759)

```
TLS MITM started for port 45406 (captureOnly=false)
MITM SNI=null for port 45406 (captureOnly=false)
Connected to 142.251.141.174:443 for MITM
serverEngine configured with SNI=www.google.com, originalSNI=null, fallbackPref=www.google.com
Server handshake failed: status=NEED_UNWRAP, cipher=SSL_NULL_WITH_NULL_NULL
Falling back to passthrough for port 45406 SNI=null
TLS MITM passthrough established for port 45406 (SNI=null)
Forwarded initial TLS ClientHello (536B) for port 45406  ← tuhle řádku starý build neměl
passthrough active port=45406 totalForwarded=8804
```

⚠️ Důležité: `build` bez předchozího `upload_src` použije starou verzi zdrojáků na Volume.
Vždy spouštět: `modal run modal_build.py::upload_src && modal run modal_build.py::build`

## Session 2026-07-06 (b) — VPN outage při MITM ON

### Root cause (po double-flip fixu)

1. **Poisoned socket v passthrough** — `fallingBackToPassthrough()` reuse kanálu s MITM ClientHello na drátu → druhý browser Hello = neplatné TLS
2. **Non-blocking write drop** — `writeToServer()` ukončil smyčku při `w=0`, zahodil neodeslaná data → `NEED_UNWRAP` navždy
3. **Chybějící TCP ACK** při MITM intercept → ClientHello retransmit duplicity
4. **QUIC blokovaný při jakémkoli `enable_mitm=true`** i když MITM selže → total outage v Chrome

### Opravy (build #6)

| Fix | Soubor | Popis |
|-----|--------|-------|
| Fresh socket passthrough | `TlsMitmEngine.kt` | Vždy close + reconnect před passthrough |
| `writeFully()` | `TlsMitmEngine.kt` | Spolehlivý zápis; blocking kanál během handshake |
| `sendTcpAck` na MITM cestách | `VpnNatEngine.kt` | ACK před return při hijacku |
| Fail-fast handshake | `TlsMitmEngine.kt` | Server-side max 50 iterací (~250 ms) |
| Protect abort | `TlsMitmEngine.kt` | `protect()` failure → null channel |
| QUIC gating | `VpnNatEngine.kt` | Blok jen když `TlsMitmEngine.shouldBlockQuic()` (proxyLoop aktivní) |
| MITM log default | `VpnCaptureService.kt` | Sjednoceno s `BuildConfig.ENABLE_MITM` |

### Očekávané chování po opravě

- MITM selže → passthrough s čistým socketem → Chrome funguje (TCP)
- MITM úspěch → `isActivelyDecrypting=true` → QUIC blokován pro force TCP
- Log: `passthrough active totalForwarded=N` roste, nebo `Server cert subject:` při plném MITM

---

## Session 2026-07-07 — Proxy refactoring (SOCKS5 → Custom IP)

### Problém
- 6 hardcoded SOCKS5 proxy nodes s rotací (Static/Random/Time-loop) byly overengineered
- SOCKS5 handshake (greeting, auth, connect request) přidával zbytečnou složitost a latency
- Většina uživatelů potřebuje buď direct, nebo vlastní VPS endpoint

### Změny

| Soubor | Co se změnilo |
|--------|---------------|
| `VpnProxyManager.kt` | Smazán `proxyPool`, `rotationMode`, `rotationInterval`, `lastRotationTime`, `rotationThread`, `measureProxyLatencies()`, `triggerRandomRotation()`. Přidáno `setCustomProxy(ipPort)` + `getCustomProxy()` |
| `VpnNatEngine.kt` | Odstraněn SOCKS5 handshake blok (~40 řádků). Nahrazen přímým `connect()` na custom IP:Port. Fallback při selhání zůstává |
| `VpnSettingsTab.kt` | Místo 3 dropdownů (režim, node, interval) + slideru → jedno `OutlinedTextField` pro `IP:Port` s validací |
| `VpnDashboardTab.kt` | Místo vlajky+zamě+režimu rotace → `🌐 Custom Proxy IP:Port` |
| `VpnCaptureService.kt` | Opravena reference `.country` → `getCustomProxy()` |
| `OffensiveEngine.kt` | Odstraněno `VpnProxyManager.triggerRandomRotation()` |

### Chování
- Proxy je **čistě bonusová** — není-li nastavena custom IP, traffic jde direct
- Pokud proxy selže, automatický fallback na direct
- Build: **BUILD SUCCESSFUL** (Modal, 1m 32s, 39 tasks)


## Session 2026-07-09 — MITM handshake fix + log accuracy

### Nalezené problémy

1. **`runEngineHandshake` needMore logika** (TlsMitmEngine.kt:721): smyčka končila po prvním `result.status == OK` v NEED_WRAP/NEED_UNWRAP, takže handshake nikdy nepostoupil za první wrap. Engine zůstal v `NEED_UNWRAP` a `serverOk` se vrátil false. Toto bylo v kódu **od `1757e08` MITM** (žádná regrese z posledních commitů).

2. **runEngineHandshake unwrap výjimka**: Když `engine.unwrap()` vyhodí `SSLException: Unable to parse TLS packet header`, výjimka probublala do `start()` try-catch (ř. 329) a rovnou `close()` — **žádný passthrough fallback**. Internet zůstal viset na TCP.

3. **`elapsedTime` fake random**: VpnLogManager.logConnection generoval `(2..180).random()` ms jako elapsed time. Nyní používá skutečný `System.currentTimeMillis() - session.connectionStartTime`.

4. **`getConnectionOwnerUid` reflection**: `checkConnectionOwnerUid` je `@SystemApi` a nelze volat z user app. Vždy vracel -1 → `External Android App`. Nahrazeno čtením `/proc/net/tcp` (celý systém, ne `/proc/self/net/tcp`).

5. **`bytesSent/Received` v exportu**: Byly nastaveny na základě `srcIp` a `size` packetu. Nyní se předávají skutečné `session.bytesSent/bytesReceived` z `TcpSession`.

### Opravy (v4.2-MITM-LOG-FIX, versionCode 8)

| Fix | Soubor | Popis |
|-----|--------|-------|
| `needMore` smyčka | TlsMitmEngine.kt:721 | while běží dokud `handshakeStatus != FINISHED`, neukončuje po `result.status == OK` |
| Underflow streak | TlsMitmEngine.kt:786 | Pokud NEED_UNWRAP vrací null 200× (1s), handshake abort → false |
| Try-catch kolem handshake | TlsMitmEngine.kt:222 | SSLException neprobublá, vrací false → `fallingBackToPassthrough()` |
| Fake elapsed | VpnLogManager.kt:282 | Odstraněn random, používá se `elapsedTimeMs` parametr |
| `/proc/net/tcp` | ProcessResolver.kt:46 | Čte celý systém, ne `/proc/self/`; PackageManager resolvuje UID |
| getConnectionOwnerUid | VpnCaptureService.kt:84 | Zjednodušeno na `return -1` (nefunguje z user app) |
| `formatAppName` | VpnSecurityTab.kt:82 | "External Android App" → "Unknown App", "" → "System" |
| `getTopApps` filtr | VpnLogManager.kt:218 | Filtruje "External/Unknown/UID:" z výsledků |

Kompletní mapa projektu — k 2026-07-23

 ### Identita

 ┌──────────────────┬──────────────────────────────────────────┐
 │ Atribut          │ Hodnota                                  │
 ├──────────────────┼──────────────────────────────────────────┤
 │ Balíček          │ com.linux_core                           │
 ├──────────────────┼──────────────────────────────────────────┤
 │ Název            │ NetHunter AI Operator                    │
 ├──────────────────┼──────────────────────────────────────────┤
 │ Verze            │ 4.2-MITM-LOG-FIX (versionCode 8)         │
 ├──────────────────┼──────────────────────────────────────────┤
 │ minSdk/targetSdk │ 28 / 28                                  │
 ├──────────────────┼──────────────────────────────────────────┤
 │ Java/Kotlin      │ JVM 17, Kotlin 2.2.10                    │
 ├──────────────────┼──────────────────────────────────────────┤
 │ Gradle           │ AGP 9.2.1, Compose BOM 2026.05.01        │
 ├──────────────────┼──────────────────────────────────────────┤
 │ Keystore         │ app/release.jks (debug i release stejný) │
 └──────────────────┴──────────────────────────────────────────┘

 ────────────────────────────────────────────────────────────────────────────────

 ### 🏗️ Architektonická mapa (vrstvy)

 ```
   ┌─────────────────────────────────────────────────────────────────┐
   │                     MAIN ACTIVITY (MainActivity.kt)              │
   │  Compose UI: stahování rootfs, spuštění terminálu, VPN centrum   │
   ├─────────────────────────────────────────────────────────────────┤
   │                                                                   │
   │  ┌─────────────────────────────────────────────────────────┐     │
   │  │                  ☰ HAMBURGER DRAWER                      │     │
   │  │  ┌──────────────── ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐    │     │
   │  │  │  🐉 KALI  │  🦜 PARROT  │  ALL  │  [VNC GUI]  │    │     │
   │  │  │  ──────────────────────────────────────────────┤    │     │
   │  │  │  RAM: 3.4 GB / 8.0 GB                          │    │     │
   │  │  │  Session 1 (12.4 MB) ● [VPN IGNORED]      │    │     │
   │  │  └─────────────────────────────────────────────────┘    │     │
   │  │                    └── ProotManager.kt                  │     │
   │  └─────────────────────────────────────────────────────────┘     │
   │                                                                   │
   │  ┌─────────────────────────────────────────────────────────┐     │
   │  │              TERMINAL ACTIVITY (TerminalActivity.kt)      │     │
   │  │  ┌─────────────────────────────────────────────────┐    │     │
   │  │  │  [☰] [🏠] 🐉 KALI ▼  [touch] [CLI|GUI]       │    │     │
   │  │  │  ═══════════════════════════════════════════════   │    │     │
   │  │  │  ⚡ SHIZU ●  [code] CODE ○  🔥 PHOENIX ○  ▶↻  │    │     │
   │  │  └─────────────────────────────────────────────────┘    │     │
   │  │  │ Termux TerminalView + HackerKeyboard                 │     │
   │  └─────────────────────────────────────────────────────────┘     │
   │                                                                   │
   │  ┌─────────────────────────────────────────────────────────┐     │
   │  │               VPN CENTER (VpnCenterScreen.kt)            │     │
   │  │  ┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐    │     │
   │  │  │Panel ││Traffic││Security││ DNS ││ Mesh ││Nastav.│    │     │
   │  │  └──────┘└──────┘└──────┘└──────┘└──────┘└──────┘    │     │
   │  └─────────────────────────────────────────────────────────┘     │
   │                                                                   │
   └─────────────────────────────────────────────────────────────────┘
                                 │
             ┌───────────────────┼─────────────────────┐
             ▼                   ▼                     ▼
   ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────────┐
   │ LOOPBACK API    │ │  VPN CAPTURE    │ │  PRoot KONTEJNER    │
   │ 127.0.0.1:1337  │ │  SERVICE        │ │  (Kali / ParrotOS)  │
   │ LocalApiServer  │ │  VpnCaptureSrvc │ │  ProotManager.kt    │
   ├─────────────────┤ ├─────────────────┤ ├─────────────────────┤
   │ nh CLI bridge   │ │ AdGuard C++ JNI │ │ launcher.sh         │
   │ senzory, VPN    │ │ VpnNatEngine    │ │ bootstrap.sh        │
   │ baterka, wifi   │ │ TlsMitmEngine   │ │ entrypoint.sh       │
   │ GPS, clipboard  │ │ AIBrain (ONNX)  │ │ nh CLI nástroj      │
   │ USB host        │ │ VpnLogManager   │ │ shizuku (root bridge)│
   │                 │ │ VpnFirewallMgr  │ │ code-server (VSCode) │
   └─────────────────┘ └─────────────────┘ └─────────────────────┘
                                 │
                       ┌─────────┴─────────┐
                       ▼                   ▼
             ┌─────────────────┐ ┌─────────────────────┐
             │ AI AGENT DAEMON │ │  SECURITY MODULE     │
             │ :13338           │ │  CertificateManager │
             │ nethunter_agent │ │  RootCaInstaller    │
             │ .py (ReAct LLM) │ │  AttestationVerifier │
             │ analyze_network │ │  BiometricGate       │
             │ nástroj         │ │  KeystoreManager     │
             └─────────────────┘ └─────────────────────┘
 ```

 ────────────────────────────────────────────────────────────────────────────────

 ### 📦 Souborová struktura

 ```
   kali_core_emulator/
   ├── app/
   │   ├── build.gradle.kts          ← Hlavní build konfigurace
   │   ├── release.jks               ← Keystore (debug i release)
   │   ├── proguard-rules.pro
   │   └── src/
   │       ├── main/
   │       │   ├── AndroidManifest.xml    ← 31 permisí, 4 aktivity, 11 service, 3 receivery
   │       │   ├── assets/                ← PRoot binárky, nh CLI, certs, AI model
   │       │   │   ├── certs/             ← MITM CA, attestation root, internal P12
   │       │   │   ├── shizuku/           ← Native server + ADB + rish shell
   │       │   │   ├── proot-* / loader-* ← PRoot pro 4 architektury
   │       │   │   ├── nh                 ← Unified CLI nástroj (74KB)
   │       │   │   ├── nethunter_agent.py ← ReAct AI agent
   │       │   │   └── vpn_brain_v7.onnx  ← ONNX model pro klasifikaci
   │       │   ├── cpp/                   ← C JNI kód (USB fd exporter, USB bridge)
   │       │   ├── jniLibs/              ← Nativní .so pro 4 architektury
   │       │   │   ├── arm64-v8a/        ← AdGuard engine + PRoot
   │       │   │   ├── armeabi-v7a/      ← PRoot only
   │       │   │   ├── x86/              ← PRoot only
   │       │   │   └── x86_64/           ← PRoot only
   │       │   ├── java/com/linux_core/
   │       │   │   ├── MainActivity.kt   ← (1577 lines) Entry point + Compose UI
   │       │   │   ├── core/             ← (46 files) Hlavní logika
   │       │   │   ├── security/         ← (10 files) Bezpečnostní modul
   │       │   │   └── ui/               ← (8 files) UI komponenty
   │       │   ├── java/com/adguard/     ← (53 files) AdGuard JNI wrappery
   │       │   ├── res/                  ← Android resources, network config
   │       │   └── res/xml/network_security_config.xml ← Cert pinning
   │       ├── test/                     ← (8 souborů) Unit testy
   │       └── androidTest/              ← Instrumentované testy
   ├── tools/modal_build.py             ← Modal cloud build
   ├── gradle/
   │   └── libs.versions.toml           ← Version catalog
   ├── docs/                            ← Dokumentace
   ├── mbuild                           ← Build skript (Modal)
   └── nethunter-store-data/            ← Git submodul
 ```
#### 1. PRoot Virtualizace (ProotManager.kt, RootfsManager.kt)

 - Funkce: Spouští Kali/ParrotOS v uživatelském prostoru (bez rootu)
 - Flow: Stažení rootfs (tar.xz) → extrakce → nasazení PRoot loaderů → generování launcher.sh → bootstrap OS
 - Binární strategie: Dynamické PRoot (arm64 + ostatní architektury); standalone/statické binárky odstraněny (2026-08-01, nefungovaly)
 - Deployuje do guestu: nh CLI, shizuku, helper skripty

 #### 2. VPN Engine (VpnCaptureService.kt, VpnNatEngine.kt)

 - Funkce: Android VPN service s AdGuard C++ stackem
 - Komponenty:
     - VpnCaptureService — lifecycle VPN, DNS konfigurace, health check
     - VpnNatEngine — TCP/UDP NAT, packet forwarding, QUIC blokování
     - VpnFirewallManager — IP blokování s validací
     - VpnProxyManager — Custom IP proxy (volitelný, nově bez SOCKS5)
     - VpnPeerManager — Mesh VPN (P2P, ECDH, STUN)
 - Perzistence: Connection: keep-alive pro raw USB transfery

 #### 3. TLS MITM Engine (TlsMitmEngine.kt, TlsClientHelloParser.kt)

 - Funkce: HTTPS dešifrování pro bezpečnostní analýzu
 - Tok: Detekce TLS ClientHello → SNI extrakce → server-side handshake → cert podpis → client-side handshake → proxy plaintext
 - Status: Většina opravena (double-flip, passthrough), ale stále padá do passthrough kvůli SSLException v unwrapu
 - Nastavení: vpn-cli mitm on/off, QUIC blokování jen při aktivním MITM

 #### 4. AI Brain (AIBrain.kt, AIBrainWorker.kt, VerdictEngine.kt)

 - Funkce: ONNX klasifikace síťových toků v reálném čase
 - Model: vpn_brain_v7.onnx (LightGBM, 14 dimenzí)
 - Kategorie: ALLOWED / VERBOSE / SUSPICIOUS / CRITICAL
 - Integrace: TrafficAggregator → VerdictEngine → notifikace

 #### 5. AI Agent Daemon (nethunter_agent.py v assets, port 13338)

 - Funkce: ReAct LLM agent s nástrojem analyze_network
 - CLI: nh agent start/stop/ask/chat
 - Endpoint: Port 13338 (localhost)

 #### 6. LocalApiServer (LocalApiServer.kt, port 1337)

 - Funkce: HTTP REST most mezi hostitelem a guest OS
 - Endpointy:
     - /system/battery, /system/volume, /system/torch, /system/vibrate, /system/toast, /system/clipboard, /system/notification
     - /network/wifi, /network/cell, /network/location, /network/map
     - /vpn/start, /vpn/stop, /vpn/status, /vpn/mitm, /vpn/logs
     - /usb/list, /usb/permission, /usb/claim, /usb/release, /usb/raw_transfer, /usb/stream
     - /app/logs
 - Bezpečnost: Bearer token auth, localhost detection, command blocklist

 #### 7. Shizuku Bridge (ShizukuManager.kt)

 - Funkce: Privilegované příkazy bez rootu (přes Shizuku server)
 - Start strategie: Běžící server → su -c → ADB → setup dialog
 - CLI: shizuku -c "pm list packages"

 #### 8. Security modul (security/)

 - Certifikáty: CertificateManager, RootCaInstaller, SslContextFactory
 - Attestace: AttestationKeyManager, AttestationVerifier, BiometricGate
 - Keystore: KeystoreManager (AES-GCM-256)
 - MITM podpis: MitmCertSigner (BouncyCastle)

 #### 9. USB Host (UsbHostManager.kt, usb_bridge.c, usbfd_jni.c)

 - Funkce: Raw USB přístup z PRootu (pro mtkclient, flashing)
 - JNI: libusbfd_exporter.so (C kód)
 - API: /usb/raw_transfer (raw binary), /usb/stream (persistentní streaming)
 - Stream protokol: Binární frames (0x01=OUT, 0x02=IN, 0xFF=CLOSE)

 #### 10. UI Komponenty (ui/)

 ┌──────────────────────────────┬────────────────────────────────────────┐
 │ Soubor                       │ Účel                                   │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ TerminalActivity.kt (3643 l) │ Terminál + drawer + services panel     │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ VpnSecurityTab.kt (1768 l)   │ Security dashboard, MITM logy, procesy │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ VpnTrafficTab.kt             │ Traffic grafy (Canvas)                 │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ VpnDashboardTab.kt           │ VPN status, proxy, AI staty            │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ VpnDnsTab.kt                 │ DNS dotazy                             │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ VpnMeshTab.kt                │ P2P Mesh VPN                           │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ VpnSettingsTab.kt            │ Nastavení MITM, proxy, firewall        │
 ├──────────────────────────────┼────────────────────────────────────────┤
 │ EditorTab.kt                 │ Nano editor integrace                  │
 └──────────────────────────────┴────────────────────────────────────────┘

 ────────────────────────────────────────────────────────────────────────────────

 ### 🚀 Datové toky (key flows)

 #### A. Klasický provoz (VPN ON, MITM OFF)

 ```
   App (Chrome)
     → TUN fd (Android VpnService)
     → VpnCaptureService.handlePacket()
     → VpnNatEngine.handleTcpPacket()
     → AIBrain klasifikace (ONNX)
     → VpnProxyManager (direct / proxy)
     → Socket k cílovému serveru
 ```

 #### B. MITM HTTPS dešifrování

 ```
   TLS Client Hello
     → VpnNatEngine detekuje TLS
     → TlsMitmEngine.onClientData()
     → TlsClientHelloParser.extractSni()
     → Server-side handshake (SSLEngine)
     → RootCaInstaller signLeafForServer()
     → Client-side handshake (forged cert)
     → proxyLoop: decrypt → forward → encrypt
 ```

 #### C. USB BROM/EDL streaming (přes LocalApiServer)

 ```
   PRoot (mtkclient)
     → TCP :1337 POST /usb/stream
     → UsbHostManager openDevice()
     → usbfd_jni.c (libusbfd_exporter)
     → /dev/bus/usb/ via Android USB Host API
     → binární frame protokol bez HTTP režie
 ```

 ────────────────────────────────────────────────────────────────────────────────
### 🔒 Security postavení

 ┌───────────────────┬───────────────────────────────────────────────────────────┐
 │ Oblast            │ Stav                                                      │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Cert pinning      │ ✅ SHA-256 otisky pro Kali + Parrot domény                │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Token auth        │ ✅ Bearer token na LocalApiServer                         │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ AllowBackup       │ ✅ false                                                  │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Cleartext         │ ✅ false (s network_security_config)                      │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Command blocklist │ ✅ rm -rf, mkfs, reboot blokováno                         │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ MITM autentizace  │ ✅ OffensiveEngine notification (Allow/Deny, 30s)         │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Log sanitizace    │ ✅ hex dump odstraněn z CSV/JSON exportu                  │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ HTTPS enforcement │ ✅ TLS 1.2+, host whitelist v RootfsManager               │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Biometric gate    │ ✅ 30s okno pro citlivé operace                           │
 ├───────────────────┼───────────────────────────────────────────────────────────┤
 │ Zbývá             │ Test na reálném zařízení — MITM stále padá do passthrough │
 └───────────────────┴───────────────────────────────────────────────────────────┘

 ────────────────────────────────────────────────────────────────────────────────

 ### 🔧 Známé technické dluhy / nedodělky

 1. MITM engine stále nefunkční — SSLException při unwrapu, padá do passthrough
 2. Widget — zakomentován v manifestu ("pro later")
 3. DNS tab prázdný — moderní Android používá DoH/TCP, ne UDP/53
 4. mitm-ca.p12 chybí v asset/certs — je jen .crt (build projde jen díky debug fallbacku)

 ────────────────────────────────────────────────────────────────────────────────

 ### 📐 Celková statistika

 - ~29 200 řádků Kotlin kódu (plus ~1700 AdGuard JNI wrapperů)
 - 46 tříd v core/
 - 10 tříd v security/
 - 8 UI komponent (2 activity, 6 tab composables)
 - 5 Service, 2 Activity, 3 Receiver v manifestu (plus widget pozastaven)
 - 4 architektury (arm64, arm32, x86, x86_64)
 - 3 jazyky: Kotlin (primární), C (JNI bridge), Python (AI agent)
 - 8 unit testů — výrazně poddimenzované

## Session 2026-08-14 — Background auto-start (cron automation)

**Požadavek:** app se má sama spustit na pozadí po restartu kvůli cron automatizaci. Permise RECEIVE_BOOT_COMPLETED **v manifestu nebyla** (jen FOREGROUND_SERVICE) — uživatel předpokládal, že je, ale nikdo ji nevolal.

| Soubor | Změna |
|--------|-------|
| `AndroidManifest.xml` | + `RECEIVE_BOOT_COMPLETED`; + receiver `.core.BootReceiver` (BOOT_COMPLETED + MY_PACKAGE_REPLACED, `exported=true`; LOCKED_BOOT_COMPLETED NEPOUŽITO — directBootAware by běžel před odemčením, filesDir je credential-encrypted) |
| `core/BootReceiver.kt` (nový) | čte pref `boot_autostart` (default true, v `vpn_settings`), volá `BackgroundBoot.start()` |
| `core/BackgroundBoot.kt` (nový) | najde aktivní rootfs, deployne `/root/.nh_boot.sh` (start cron/crond + `while true sleep 60` keep-alive), `setupProotEnvironment` + `TerminalService.createSession(view=null)` — headless proot session |
| `core/TerminalService.kt` | START_STICKY restart (intent==null): když `boot_autostart` && žádné session → `BackgroundBoot.start()` — cron se vrátí i po zabití app systémem |
| `MainActivity.kt` | toggle „Auto-start po restartu“ (karta vedle Mount Shared Storage, pref `boot_autostart`) |

Chování: BOOT → BootReceiver → headless proot session s cronem (FGS + START_STICKY = app běží na pozadí). Killed → START_STICKY restart → cron session se obnoví. Aktualizace APK → MY_PACKAGE_REPLACED → restart. Pro spolehlivost v úsporném režimu: `nh device battery-optimize request` (infrastruktura už existuje).

**Adversarial review (3 paralelní revieweři) — aplikované fixy:**
- **Build-breaker:** duplicitní `detectGuestRootfs()` v `RootBridgeTab.kt` (Conflicting overloads) — odstraněn.
- **Dedup cronu (MED-2):** `BackgroundBoot` gate `launching` + `TerminalService.backgroundBootSessionId` (registrovaný přes `sessionIds[created]`) — `MY_PACKAGE_REPLACED` za běhu / race se START_STICKY nespustí 2. cron.
- **Relaunch po čistém stmrnou (MED-3):** `removeSession` — když padne background session a `boot_autostart`, restart s backoffem (max 3 pokusy, 20s); `backgroundBootReloads` reset po úspěšném startu; `stopAll()` resetuje obě pole.
- **Bezpečnost (deny blocklist):** `deny_shell_payload` odstraňuje uvozovky (`rm -rf "/"` == `/`) — uzavírá evazní cestu k celosystemovému wipe-guardu su_daemon.
- **FD leak:** deny path + `recv_fds_and_payload` chyba nyní zavírají `fds[0..2]` (close(-1) no-op).
- **Docs:** stale řádky „na HOST / hostitelský root shell / roo typo“ v `assets/nethunter_docs.md` a README changelogu opraveny; `nh` self-help (`help_fix`, banner, `nh list` + `fix permission`).

## Session 2026-08-14 — su_daemon PRoot re-entry safety fix (real-root confinement)

**Bug (near-brick):** su_daemon executed the requested command **directly on the HOST** under real root (via an optional/incomplete `chroot`) instead of re-entering PRoot. When the launcher/rootfs arg was missing, no confinement at all applied, so `sudo chmod 777 -R /...` hit the host filesystem.

**Target flow (implemented):**
```
proot $ sudo chmod 777 -R /root/cil/dir
  -> su_wrapper (guest) -> magisk_daemon (real root, UNIX socket)
  -> exec launcher-{distro}.sh -- chmod 777 -R /root/cil/dir
  -> launcher re-enters PRoot -> command runs INSIDE guest rootfs
  -> stdout/stderr stream back to the proot terminal (FDs 0,1,2)
  -> proot --kill-on-exit reaps guest children; daemon child exits
```

| File | Change |
|------|--------|
| `assets/launcher.sh` | New `--` raw-exec mode: `launcher.sh -- <args...>` re-enters PRoot as real root, exports guest `PATH`/`HOME`, honours `NH_CWD` (from wrapper getcwd), routes through guest `/bin/sh` only to strip host `LD_PRELOAD`/`PROOT_LOADER`, then `exec "$@"` token-faithfully. Bare `--` = interactive root login shell. Uses existing `-0 --kill-on-exit`. |
| `cpp/su_daemon.c` | Replaced chroot+setresuid+execvp with PRoot re-entry `execv(launcher, {"--", <args>})`. **Fail closed**: no launcher configured → `_exit(126)` (never runs on host). Stays real root. Passes `NH_CWD`. Keep `deny_command` blocklist. |
| `ui/RootBridgeTab.kt` | `startDaemon` passes launcher-`* .sh` as argv[2]; added `detectActiveLauncher()`; removed dead `detectGuestRootfs()`. |

Guarantee: even a host-global command is confined to the **guest rootfs** because it runs under PRoot with real root. `launcher.sh` template is redeployed on next container start; RootBridge fail-closes until then.

### 2. Ownership fix (2 vrstvy) — root-owned files se přepíšou zpět na UID aplikace

**Problém:** příkazy spuštěné pod real rootem vytvoří soubory owned by UID 0 → app (jiný UID) je pak nemůže číst/modifikovat.

**Chytré pravidlo:** fix běží **na HOSTITELI mimo PRoot** — uvnitř prootu jsou bindy (/sdcard, /dev, /proc, /sys, /run/host_ipc...), rekurzivní chown by zasáhl reálná uživatelská data (= katastrofa). Mimo proot jsou bind targety jen (prázdné) složky v rootfs a vidí se **skutečný vlastník**.

**Vrstva 1 — automatická (po každém příkazu daemona):** `fix_permissions(rootfs)` — `nftw` + `lchown` (nikdy nesleduje symlink mimo scope), přepisuje uid/gid != app na app uid/gid. Top-level bind dirs (`dev proc sys run sdcard mnt system vendor product apex storage data`) se **nikdy** nedescendují. Vypínatelné přepínačem v Root Bridge UI (`auto_fix_permissions`, default `true`), předává se daemonu jako argv[6].

**Vrstva 2 — manuální:** `nh fix permission <cesta>` (alias `perms`) → `su_wrapper --fix <cesta>` → payload `@FIX` + cesta → daemon `handle_fix_request()`: odmítá bind/host rooty a `/` (exit 126), `realpath` kontrola že cíl zůstává v rootfs (blokuje symlink escape na /sdcard), pak `fix_permissions(scope)`.

| Soubor | Změna |
|--------|-------|
| `cpp/su_daemon.c` | argv[3]=rootfs, argv[4]/[5]=app uid/gid, argv[6]=auto-fix; `handle_fix_request()` pro `@FIX`; auto-fix po `waitpid` (log `auto-fix: chowned ... in N ms`); `_GNU_SOURCE` + `<ftw.h>`/`<sys/time.h>` |
| `cpp/su_wrapper.c` | nový režim `--fix <path>` → `@FIX` payload |
| `assets/nh` | `nh fix permission <path>` v `fix_dispatch` (guest-side bind check + volá su_wrapper) |
| `ui/RootBridgeTab.kt` | daemon start předává launcher+rootfs+uid+gid+autoFix; UI switch „Auto-fix vlastnictví“; vrácen `detectGuestRootfs()` |

## Session 2026-08-04 — Clipboard paste fix (UTF-8 breakage & control char injection)

Bug report (bug.log → PDF `bug_report_terminal_paste.md`): pasting multi-line text with Czech diacritics (ě, š, č, ž, í) into nano produced `??` replacement chars and control-character injection (`^K`, `^M`, `<ffffffff>` dumps).

### Root cause (verified against termux-app v0.118.0 source)

- `TerminalSession.write(String)` (z `TerminalOutput.write`) už zapisuje celý řetězec jako UTF-8 bytes do PTY — to NEBYL problém (fix č.3 z reportu byl v knihovně už splněn).
- **Skutečný bug:** oba vlastní paste handlery aplikace volaly `session.write(text)` přímo a obešly oficiální `TerminalEmulator.paste()` (bracketed paste). Termux TerminalView sám používá `mEmulator.paste()` pro context-menu paste.
- `TerminalEmulator.paste()` (public, v terminal-emulator v0.118.0): odstraní ESC + C1 control znaky, normalizuje `\r?\n` → `\r` a obalí do `\e[200~`/`\e[201~` **POUZE když běžící aplikace aktivovala DECSET 2004** (emulátor to sleduje interně) — takže mimo bracketed-paste aplikace jde text RAW a nikde neprotečou `200~` literály.
- Locale: user zkusil `cs_UTF-8` a nepomohlo (rootfs ho nemá / bash login přebije). Správné místo je env spawnu PTY childa.

### Fixy (versionCode 13, `4.2-PASTE-FIX`)

| Soubor | Změna |
|--------|-------|
| `TerminalService.kt` (`createSession`) | env sessionu + `LANG=C.UTF-8` + `LC_CTYPE=C.UTF-8` (C.UTF-8 je vestavěné v glibc, funguje bez ohledu na jazyk) |
| `TerminalService.kt` (`ViewHostSessionClient.onPasteTextFromClipboard`) | `session.write(text)` → `session.getEmulator()?.paste(text) ?: session.write(text)` |
| `TerminalActivity.kt` (Hacker Keyboard `KeyType.PASTE`) | `sendKey(text)` → nový helper `pasteToCurrentSession()` (emulator.paste s fallbackem) |

### Ověření

- Build: **BUILD SUCCESSFUL** (Modal, 1m 23s, 39 tasks)
- Dex check APK: `pasteToCurrentSession`, `getEmulator`, `200~`, `201~`, `C.UTF-8` přítomny
- APK: `/root/Download/app-debug.apk` (123.3 MB)

⚠️ `C.UTF-8` vyžaduje glibc ≥ 2.35 — Kali/Parrot (sid/bookworm+) mají OK. Pokud by nějaký rootfs failoval na locale, spadne to jen na POSIX fallback (stejné jako dřív), nic se nerozbije.

## Session 2026-08-04 — MIUI multi-input duplication (#multi-input)

**Bug:** na MIUI zařízenì každý znak napsaný jednou se vložil 2–5× (kompoundace: `ssee`, `bbbbbx`) přímo v terminálu.

**Diagnostika (nh.log = logcat):**
- Jediný relevantní app event je MIUI **APP_SCOUT_WARNING** (Thread:main, PID = app):
  `onCodePoint → onTerminalInput → updateSuggestions → new Button()` — aplikace vytvářela Button view synchronně na main threadu **při každém keystroke**, uvnitř IME `commitText → inputCodePoint`.
- `FileUtils: err write to mi_exception_log` = MIUI neuloží ten scout report (není to FATAL; v logu není ANR ani `FATAL EXCEPTION`).
- Vstup dorazí do TerminalView přesně 1× na znak (`onCodePoint` jednou) → duplikace NENÍ dvojitý zápis v aplikaci.

**NENÍ to viník** (ověřeno: clean one-shot, FD-passing, žádný input relay/echo loop):
- `su_daemon`/`su_wrapper` (C) — SCM_RIGHTS fd + fork/exec root, bez read→write-back.
- `LocalApiServer.handleShell` — `sh -c` s allowlistem, vrací output, žádný PTY relay.
- `handleAshell` — otevírá novou TerminalActivity/session, separátní PTY.

**Root cause:** main-thread jank uvnitř IME commitText → MIUI překomituje znak (multi-input).

**Fix (TerminalActivity.kt):** nahrazen synchronní `updateSuggestions()` za **debounce + přeskočení beze změny**:
- pole `suggestionHandler` (main looper), `rebuildSuggestionsRunnable`, `lastSuggestedList`;
- `updateSuggestions()` = `removeCallbacks + post(rebuild…)` (thread-safe z IME/terminal vlákna);
- `rebuildSuggestions()` vrátí early, když `getSuggestions(...) == lastSuggestedList`, jinak teprve přebuduje tlačítka.
