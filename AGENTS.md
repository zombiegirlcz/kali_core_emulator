# AGENTS.md — NetHunter AI Operator

## Sestavení a ověření

**ZÁKAZ LOKÁLNÍHO BUILDU!**
Nikdy nespouštěj `./gradlew assembleDebug` nebo jiné buildovací příkazy lokálně. Lokální prostředí pro to není uzpůsobené a build spadne nebo se zablokuje. Pro sestavení APK VŽDY používej Modal:

```bash
modal run modal_build.py::upload_src && modal run modal_build.py::build # sestaveni se provadi na modalu
./gradlew test                        # unit testy (app/src/test/) - tyto lze poustet lokalne
./gradlew test --tests "*ProotManager*"  # jeden test class
./gradlew lint                        # Android lint (checkReleaseBuilds=false, abortOnError=false)
./gradlew clean                       # smazat výstupy sestavení
```

CI: `.github/workflows/build.yml` — spouští `./gradlew assembleDebug` při pushi do `master` (běží na GitHub Actions, ne lokálně).

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
- **Statické (arm64 záložní):** `proot-static-aarch64` + `loader-static-aarch64`
- **Ostatní architektury:** `proot_static` + `loader_static` / `loader-static-arm32`
- Python skripty `extract_proot.py` / `extract_libtalloc.py` / `update_static_binaries.py` aktualizují tyto binárky

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

