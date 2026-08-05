# Security Analysis — NetHunter AI Operator (com.linux_core)

**Datum:** 2026-07-11  
**Verze kódu:** 4.2-MITM-LOG-FIX (versionCode 8)  
**Analyst:** OpenMythos AI Security Agent  
**Zdroj:** Přímý review aktuálních zdrojových souborů + `git diff` proti HEAD  

---

## Executive Summary

Projekt prošel v posledních dnech někol bezpečnostních oprav. Část zranitelností byla plně nebo částečně zmírněna, přetrvává ale několik kritických rizik, která brání plnému bezpečnostnímu确保ování aplikace. Nejzávažnější je **autentizační bypass agent démona na portu 13338** (nesedí cesta k tokenu mezi hostovským Androidem a PRoot kontejnerem). Dále přetrvává **povolení cleartext provozu** v `network_security_config.xml` navzdory `usesCleartextTraffic="false"` v manifestu, a **HTTP stahování GPG klíčů** pro ParrotOS rootfs.

| Kategorie | Počet | Hlavní finding |
|-----------|-------|----------------|
| 🔴 CRITICAL | 2 | Agent daemon auth bypass, MITM handshake nefunkční |
| 🟠 HIGH | 3 | Cleartext traffic povolen, GPG stahování přes HTTP, Debug signing bez fallbacku |
| 🟡 MEDIUM | 2 | Shell blocklist obejitelný, shareLocalApi VPN subnet exposice |
| 🔵 LOW | 1 | MITM capture-only nezachytává server odpovědi |

---

## A. Opravy potvrzené v aktuálním kódu

| Č. | Původní finding | Stav | Důkaz v kódu |
|----|-----------------|------|--------------|
| H2 | `allowBackup="true"` | ✅ OPRAVENO | `AndroidManifest.xml:57` — `allowBackup="false"` + `dataExtractionRules` + `fullBackupContent` |
| H3 | Rootfs download bez cert pinning | ✅ OPRAVENO | `RootfsManager.kt:93-141` — HTTPS only, host whitelist, TLS 1.2+, `SslSocketFactory` |
| H4 | VPN log payload hex dump | ✅ OPRAVENO | `VpnLogManager.kt:44-81` — `LogEntry` data class nemá `payloadHex` |
| C14-C18 | Attestation/auth/token chyby | ✅ OPRAVENO | `LocalApiServer.kt:196-250` — Bearer token auth, localhost detection, attestation headers, shell blocklist + length limit |
| OffensiveEngine | Auto-exploit bez potvrzení | ✅ OPRAVENO | `OffensiveEngine.kt:30-112` — notification Allow/Deny, 30s timeout |
| MITM double-flip | `writeToServer()` flip bug | ✅ OPRAVENO | `TlsMitmEngine.kt:875-885` — `writeToServer` nevolá `flip()` |
| MITM passthrough poisoned socket | reuse kanálu | ✅ OPRAVENO | `TlsMitmEngine.kt:983-1029` — fresh socket reconnect před passthrough |
| QUIC blokování při MITM failure | blok i při passthrough | ✅ OPRAVENO | `VpnNatEngine.kt:252` — `TlsMitmEngine.shouldBlockQuic()` gate |
| DistroDocumentsProvider | `exported="true"` | ✅ OPRAVENO | `AndroidManifest.xml:173-176` — `android:exported="false"` |
| Token plain-text fallback na disk | ukládání plain textu do SharedPreferences | ✅ OPRAVENO | `LocalApiServer.kt:173-178` — fallback teď volá `prefs.edit().remove("auth_token").apply()` a token žije pouze in-memory |
| Debug signing hardcoded `"password123"` | debug build fallback heslo | ✅ OPRAVENO | `app/build.gradle.kts:40-45` — debug fallback změněn z `"password123"` na `""` |

---

## B. Aktuální zranitelnosti

### 🔴 CRITICAL

#### CRIT-1: Agent daemon auth bypass — token path mismatch (port 13338)

**Soubory:**
- `app/src/main/java/com/linux_core/core/LocalApiServer.kt:1145-1165`
- `app/src/main/assets/nethunter_agent.py:11,379-395`
- `app/src/main/java/com/linux_core/core/ProotManager.kt:30,121`

**CVSS:** 9.8 (AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H)

**Popis:**
Hostovský Android kód ukládá bearer token do:
```
context.filesDir/tmp/nethunter_agent_token
= /data/data/com.linux_core/files/tmp/nethunter_agent_token
```

PRoot `launcher.sh` mountuje:
```
-b ${rootfsDir}/tmp:/tmp
= -b /data/data/com.linux_core/files/kali-arm64/tmp:/tmp
```

Jde o **různé adresáře**. Agent daemon (Python, běží uvnitř PRootu na `127.0.0.1:13338`) čte `/tmp/nethunter_agent_token`, ale token je v `filesDir/tmp/`, nikoliv v `filesDir/kali-arm64/tmp/`. `check_auth()` tedy nikdy nenajde token → vrací `True` → **autentizace je zcela obejita**.

```python
# nethunter_agent.py:382-387
def check_auth(headers):
    stored_token = ""
    if os.path.exists(AUTH_TOKEN_PATH):  # /tmp/nethunter_agent_token
        with open(AUTH_TOKEN_PATH, 'r') as f:
            stored_token = f.read().strip()
    if not stored_token:
        return True  # No token = allow all (backward compat) ← BYPASS
```

**Exploit scénář:**
1. PRoot kontejner běží, agent daemon naslouchá na `127.0.0.1:13338`
2. Jakýkoli proces na hostitelském zařízení nebo uvnitř PRootu odešle:
   ```bash
   curl -s -X POST http://127.0.0.1:13338/query \
     -H "Content-Type: application/json" \
     -d '{"prompt":"run_shell_command(\"id\")}'
   ```
3. Agent démon spustí libovolný shell příkaz bez ověření identity
4. Pomocí `run_shell_command` lze získat root přístup uvnitř PRoot kontejneru

**Dopad:** Plná RCE uvnitř PRoot kontejneru bez nutnosti znalosti bearer tokenu.

**Doporučení:** Zápis tokenu přesunout do adresáře, který je skutečně bindován do `/tmp`, např.:
```kotlin
val tokenFile = java.io.File(File(context.filesDir, "kali-arm64"), "tmp/nethunter_agent_token")
```

---

#### CRIT-2: MITM engine — server-side handshake stále selhává

**Soubor:** `app/src/main/java/com/linux_core/core/TlsMitmEngine.kt:222-246,726-811`

**CVSS:** 7.5 (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H)

**Popis:**
I po opravě double-flip a `initialClientHello` forwarding, server-side handshake v `runEngineHandshake()` stále nezvládne dokončit TLS handshake se skutečným serverem. Logy ukazují:
```
Server handshake failed: status=NEED_UNWRAP, cipher=SSL_NULL_WITH_NULL_NULL
```

Příčina: server-side `SSLEngine` v `CLIENT_MODE` se uvízne v `NEED_UNWRAP` → handshake se ukončí po ~1s (max 200 underflows) → engine vrací `false` → spustí se passthrough. Aktivní TLS interception nikdy neproběhne.

**Dopad:**
- MITM je v praxi pouze **passthrough** — žádné plné dešifrování TLS
- Uživatelé kteří zapnou MITM nastavení mohou mít pomalejší internet (QUIC blokován pokud proxyLoop aktivní)
- Dešifrovaný provoz není dostupný

**Stav:** `ENABLE_MITM=false` (výchozí) chrání uživatele. Capture-only režim funguje ale pouze pro client-side data.

---

### 🟠 HIGH

#### HIGH-1: Cleartext traffic povolen přes network_security_config override

**Soubory:**
- `app/src/main/AndroidManifest.xml:65-66`
- `app/src/main/res/xml/network_security_config.xml:3`

**CVSS:** 7.4 (AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N)

**Popis:**
Manifest deklaruje:
```xml
android:usesCleartextTraffic="false"
android:networkSecurityConfig="@xml/network_security_config"
```

Ale `network_security_config.xml` obsahuje:
```xml
<base-config cleartextTrafficPermitted="true">
```

V Androidu: pokud je `networkSecurityConfig` přítomen, `base-config` v tomto XML **přebírá** nastavení z manifestu. Explicitní `cleartextTrafficPermitted="true"` tedy znamená, že **celá aplikace může používat HTTP** k libovolným doménám.

**Dopad:** Útočník s MITM pozicí (fake WiFi hotspot, compromised router) může odchytávat a modifikovat veškerý HTTP provoz aplikace.

---

#### HIGH-2: GPG klíč Parrot rootfs stahován přes HTTP

**Soubor:** `app/src/main/java/com/linux_core/core/ProotManager.kt:290-292`

**CVSS:** 6.5 (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:H/A:N)

**Popis:**
Bootstrap skript pro ParrotOS stahuje archive GPG klíč přes nezabezpečené HTTP:
```kotlin
append("  curl -sSL -o /etc/apt/trusted.gpg.d/parrot-archive-key.asc http://archive.parrotsec.org/parrot/misc/archive.gpg 2>/dev/null || true")
append("  wget -qO /etc/apt/trusted.gpg.d/parrot-archive-key.asc http://archive.parrotsec.org/parrot/misc/archive.gpg 2>/dev/null || true")
```

**Dopad:** Pokud je uživatel na nezabezpečené WiFi, útočník může nahradit GPG klíč vlastním. Všechny následné `apt install` balíčky budou podepsané útočníkem → **spuštění škodlivého kódu v rootfs**.

**Opozitor:** Kali rootfs nepoužívá toto schéma — stahuje z `images.kali.org` přes HTTPS s cert pinningem.

---

#### HIGH-3: Debug build signing — prázdný fallback password

**Soubor:** `app/build.gradle.kts:40-45`

**CVSS:** 5.3 (AV:L/AC:H/PR:L/UI:N/S:U/C:N/I:L/A:L)

**Popis:**
```kotlin
getByName("debug") {
    storeFile = file("release.jks")
    storePassword = System.getenv("KEYSTORE_PASSWORD") ?: propertyOrNull("keystore.password") ?: ""
    keyAlias = System.getenv("KEY_ALIAS") ?: propertyOrNull("key.alias") ?: ""
    keyPassword = System.getenv("KEY_PASSWORD") ?: propertyOrNull("key.password") ?: ""
}
```

Pokud nejsou nastaveny env vars ani gradle properties, debug build se pokusí podepsat prázdným heslem. Pokud je `release.jks` chráněný heslem, build **selže**.

`AGENTS.md` specifikuje, že debug a release používají stejný keystore pro `adb install -r` bez odinstalace. Prázdný fallback tento workflow porušuje. Not working builds mohou motivovat vývojáře k opětovnému hardcodování hesel.

---

### 🟡 MEDIUM

#### MED-1: Shell command blocklist lze obejít

**Soubor:** `app/src/main/java/com/linux_core/core/LocalApiServer.kt:841-884`

**CVSS:** 5.0 (AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:L)

**Popis:**
Blocklist kontroluje `commandLower.contains(blocked.lowercase())`:
```kotlin
private val SHELL_BLOCKLIST = listOf(
    "rm -rf /", "mkfs", "dd if=/dev/zero", "dd if=/dev/random",
    ">:", "format", "mkswap", "reboot", "shutdown", "poweroff",
    "halt", "init 0", "init 6"
)
```

**Obejití:**
| Payload | Proč projde |
|---------|-------------|
| `cd /; rm -rf *` | Neobsahuje `rm -rf /` (obsahuje `rm -rf *`) |
| `python3 -c "import os; os.system('rm -rf /')"` | Python řetězec není detekován |
| `busybox rm -rf /` | `busybox` prefix není v blocklist |
| `cat /dev/zero > /dev/mem` | Nikdo z řetězců neobsahuje blokované vzory |

**Dopad:** Pokud útočník získá přístup k `/shell` endpointu (např. ukradeným bearer tokenem), může obejít omezení a spustit destruktivní příkazy.

**Doporučení:** Nahradit whitelistou povolených bezpečných příkazů, nebo přesunout shell izolovanému sandboxu.

---

#### MED-2: shareLocalApi toggle — API vystaveno na VPN subnet

**Soubor:** `app/src/main/java/com/linux_core/core/LocalApiServer.kt:81-86`

**CVSS:** 4.3 (AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N)

**Popis:**
```kotlin
val bindAddress = if (shareLocalApi && VpnCaptureService.isRunning()) {
    VpnCaptureService.getVpnAddress()  // např. 172.18.11.218
} else {
    "127.0.0.1"
}
```

Zlepšení oproti `0.0.0.0`, ale stále umožňuje přístup k API ze všech zařízení v VPN subnet. Výchozí je stále `127.0.0.1`. Chráněno Bearer tokenem, ale token může být odcizen na rootnutém zařízení.

---

### 🔵 LOW

#### LOW-1: MITM capture-only režim nezachytává server odpovědi

**Soubor:** `app/src/main/java/com/linux_core/core/TlsMitmEngine.kt:341-443`

**CVSS:** 3.7 (AV:N/AC:H/PR:L/UI:N/S:U/C:L/I:N/A:N)

**Popis:** `startCaptureOnly()` vytvoří forged cert a proběhne handshake s klientem, ale nenaváže spojení ke skutečnému serveru. `captureLoop()` tedy zachytává pouze data **odeslaná klientem** (CLIENT→SERVER), nikoliv odpovědi serveru.

---

## C. Detaily k vybraným findingům

### Agent daemon token path mismatch (CRIT-1)

**Root cause:** Nesoulad mezi hostovským kódem a Python agentem.

| Component | Cesta pro token | Soubor |
|-----------|----------------|--------|
| Host app (`LocalApiServer`) | `context.filesDir/tmp/nethunter_agent_token` | `LocalApiServer.kt:1155` |
| PRoot bind mount | `${rootfsDir}/tmp:/tmp` | `ProotManager.kt:121` |
| Agent daemon (`nethunter_agent.py`) | `/tmp/nethunter_agent_token` | `nethunter_agent.py:11` |

`rootfsDir` = `context.filesDir/kali-arm64` nebo `parrot-arm64`.  
`context.filesDir/tmp` ≠ `context.filesDir/kali-arm64/tmp`. Jsou to **různé adresáře**.

**Oprava:** Zápis tokenu přesunout do adresáře, který je skutečně bindován do `/tmp`:
```kotlin
val tokenFile = java.io.File(File(context.filesDir, "kali-arm64"), "tmp/nethunter_agent_token")
```
nebo do `context.filesDir` s přímočarým bind mountem do `/tmp`.

---

### network_security_config.xml cleartext override (HIGH-1)

**Root cause:** Explicitní `cleartextTrafficPermitted="true"` v `base-config` přebíjí `usesCleartextTraffic="false"` z manifestu.

**Fix:** Odstranit `cleartextTrafficPermitted="true"` nebo explicitně nastavit `cleartextTrafficPermitted="false"` v `network_security_config.xml:3`.

---

### GPG key download over HTTP (HIGH-2)

**Root cause:** ProotManager generuje bootstrap skript s HTTP URL pro Parrot GPG klíč.

**Fix:** Nahradit `http://archive.parrotsec.org/` za `https://archive.parrotsec.org/` v `ProotManager.kt:290-292`.

---

## D. Doporučení na Q3 2026

| Priorita | Úkol | Soubor |
|----------|------|--------|
| 🔴 P1 | Opravit token path mismatch mezi hostovským a PRoot `tmp/` | `LocalApiServer.kt:1155`, `ProotManager.kt:121` |
| 🟠 P2 | Opravit `cleartextTrafficPermitted="true"` → `"false"` | `network_security_config.xml:3` |
| 🟠 P2 | Nahradit HTTP za HTTPS pro Parrot GPG klíč | `ProotManager.kt:290-292` |
| 🟠 P2 | Přidat rate limiting na `/shell` endpoint | `LocalApiServer.kt:841` |
| 🟡 P3 | Nahradit shell blacklist za whitelist nebo sandbox | `LocalApiServer.kt:841-884` |
| 🟡 P3 | Opravit MITM server-side handshake `NEED_UNWRAP` lockup | `TlsMitmEngine.kt` |
| 🟡 P3 | Migrovat agent token do Keystore-enabled encrypted storage | `LocalApiServer.kt:1145-1165` |
| 🔵 P4 | Přidat TLS do proxy TCP tunnel | `VpnProxyManager.kt` |

---

## E. Shrnutí bezpečnostních kontrol

| Kontrola | Stav |
|----------|------|
| Hardcoded credentials | ✅ |
| allowBackup=false | ✅ |
| usesCleartextTraffic=false | ⚠️ Network security config override |
| Certificate pinning | ✅ |
| Bearer token auth API | ✅ |
| Localhost-only citlivé endpointy | ✅ |
| Token encrypted storage / plain-text fallback | ✅ |
| Agent daemon token auth | ❌ Path mismatch → bypass |
| MITM plné dešifrování | ❌ Neaktivní |
| Rootfs download HTTPS + whitelist | ✅ |
| OffensiveEngine confirm | ✅ |
| Shell blocklist + length limit | ⚠️ Obejitelná blacklist |
| GPG key download HTTPS | ❌ Parrot používá HTTP |
| Exported komponenty | Částečně (`DistroDocumentsProvider` → ✅, další → stále exported="true" ale chráněny permissions) |
| shareLocalApi VPN subnet exposice | ⚠️ Omezené |

---

## F. Reference

- Původní audit: `docs/SECURITY_AUDIT.md` (2026-06-27)
- Aktuální analýza: `docs/security_analysis.md`
- Build config: `app/build.gradle.kts`
- Local API: `app/src/main/java/com/linux_core/core/LocalApiServer.kt`
- MITM engine: `app/src/main/java/com/linux_core/core/TlsMitmEngine.kt`
- VPN NAT: `app/src/main/java/com/linux_core/core/VpnNatEngine.kt`
- Rootfs manager: `app/src/main/java/com/linux_core/core/RootfsManager.kt`
- Agent daemon: `app/src/main/assets/nethunter_agent.py`
- Proot manager: `app/src/main/java/com/linux_core/core/ProotManager.kt`
- Network security: `app/src/main/res/xml/network_security_config.xml`

---

*Dokument vygenerován na základě přímého review aktuálních zdrojových souborů a `git diff` proti HEAD (2026-07-11).*
