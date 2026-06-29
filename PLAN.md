```
- [ ] **Konfigurace Intent Filtru pro Activity:**
Zajistit, aby se aplikace probudila nebo nabídla otevření hned po zasunutí kabelu:
```xml
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data 
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/usb_device_filter" />

```
 * [ ] **Vytvoření res/xml/usb_device_filter.xml:**
   Definovat filtry pro konkrétní čipy (např. MediaTek BROM, Qualcomm EDL - s obecnými nebo prázdnými ID pro zachycení všeho):
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <usb-device />
   </resources>
   
   ```
## Fáze 2: Životní cyklus USB a zachycení File Descriptoru
**Cíl:** Získat od uživatele oprávnění k hardwaru a vytáhnout surový linuxový int souborový deskriptor (fd), se kterým umí pracovat C knihovny.
 * [ ] **Implementace Broadcast Receiveru pro runtime permission:**
   ```java
   private static final String ACTION_USB_PERMISSION = "com.linux_core.USB_PERMISSION";
   private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
       public void onReceive(Context context, Intent intent) {
           String action = intent.getAction();
           if (ACTION_USB_PERMISSION.equals(action)) {
               synchronized (this) {
                   UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                   if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                       if(device != null) {
                           // Krok 2.2: Otevření zařízení
                           extractFileDescriptor(device);
                       }
                   }
               }
           }
       }
   };
   
   ```
 * [ ] **Vyžádání oprávnění:**
   Pokud zařízení nemá perms, vyvolat systémové okno:
   ```java
   UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
   PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
   manager.requestPermission(device, permissionIntent);
   
   ```
 * [ ] **Surová extrakce FD:**
   ```java
   private void extractFileDescriptor(UsbDevice device) {
       UsbDeviceConnection connection = manager.openDevice(device);
       if (connection != null) {
           int fd = connection.getFileDescriptor();
           // Tento int 'fd' je klíč k nízkoúrovňové komunikaci!
           sendFdToPRoot(fd);
       }
   }
   
   ```
## Fáze 3: Most do PRootu (Předání FD do sandboxu)
**Cíl:** Protokol PRoot standardně izoluje souborový systém, takže /dev/bus/usb/ je prázdný. Musíme předat otevřený FD dovnitř běžícího Linuxu.
 * [ ] **Varianta A: Předání jako argument při startu (Pokud se zařízení připojuje před startem Kali):**
   Předat FD přímo do environment proměnné nebo skriptu:
   ```bash
   proot -r ./rootfs -b /dev -b /proc --env=PASSTHROUGH_USB_FD=3 ...
   
   ```
 * [ ] **Varianta B: Přenos za běhu přes Unix Domain Socket (Doporučeno):**
   V aplikaci na straně Androidu vytvořit LocalServerSocket. Uvnitř PRootu se skript připojí na tento socket a Android aplikace mu pomocí metody AncillaryData (odpovídá Linuxovému SCM_RIGHTS) pošle zduplikovaný FD přímo do běžícího Python/C procesu.
## Fáze 4: Nativní C/Python implementace v Kali Linuxu
**Cíl:** Přinutit nástroje v Kali (např. exploity, skripty, libusb), aby se nepokoušely prohledávat /dev/bus/usb/, ale rovnou adoptovaly náš FD.
 * [ ] **Úprava libusb inicializace (Kompilace vlastní verze pro Kali):**
   Standardní libusb_open_device_with_vid_pid selže. Je nutné v kódu zneužít neoficiální/přidanou funkci v libusb:
   ```c
   // Vytvoření libusb device handle přímo ze souborového deskriptoru, který prošel z Javy
   libusb_device_handle *handle = NULL;
   libusb_wrap_sys_device(ctx, (intptr_t)passed_fd, &handle);
   
   ```
 * [ ] **Injekce do Pythonu (PyUSB wrapper):**
   Pokud tvůj exploit/skript běží v Pythonu, vytvořit C-extension wrapper, který zavolá libusb_wrap_sys_device na předaném čísle FD a vrátí instanci objektu, se kterým už PyUSB dokáže posílat nízkoúrovňové control_transfer nebo bulk zápisy/čtení (vhodné pro BROM/EDL manipulaci).
## Fáze 5: Vlastní Android Launcher 🧪 (EXPERIMENTALNÍ)
**Cíl:** Vytvořit finální grafické rozhraní (Launcher), které po stisknutí tlačítka Home nahradí systémovou plochu a zprostředkuje UI pro Kali i Android aplikace.
 * [ ] **Registrace jako Home Screen (AndroidManifest.xml):**
   ```xml
   <activity android:name=".LauncherActivity" android:launchMode="singleInstance">
       <intent-filter>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.HOME" />
           <category android:name="android.intent.category.DEFAULT" />
       </intent-filter>
   </activity>
   
   ```
 * [ ] **Vykreslení XFCE Desktopu:**
   V LauncherActivity inicializovat vestavěné VNC View (např. založené na bVNC core). Toto okno zabere 70-80 % obrazovky a bude zobrazovat běžící instanci VS Code / Terminálu z Kali PRootu.
 * [ ] **Tvorba Android App Drawer / Sidebar:**
   Vytvořit postranní vysouvací panel v launcheru. Načíst aplikace ze systému:
   ```java
   PackageManager pm = getPackageManager();
   Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
   mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
   List<ResolveInfo> pkgAppsList = pm.queryIntentActivities(mainIntent, 0);
   
   ```
 * [ ] **Spouštění v režimu Freeform / Plovoucích oken (MIUI/HyperOS):**
   Když v launcheru klikneš na ikonu Android aplikace, launcher ji vynutí otevřít jako překryvné okno nad běžícím Kali Linuxem:
   ```java
   Intent intent = pm.getLaunchIntentForPackage(resolvedApp.activityInfo.packageName);
   ActivityOptions options = ActivityOptions.makeBasic();
   try {
       // Využití skrytých/Freeform metod (podporováno na Xiaomi zařízeních)
       Method setLaunchWindowingMode = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
       setLaunchWindowingMode.invoke(options, 5); // 5 je standardně WINDOWING_MODE_FREEFORM
   } catch (Exception e) {
       intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   }
   startActivity(intent, options.toBundle());
   
   ```
"""
with open("usb_plan.md", "w", encoding="utf-8") as f:
f.write(usb_plan.strip())
print("usb_plan.md vygenerovan.")
```
Byl vygenerován komplexní a specifický plán zaměřený čistě na tvou USB hardwarovou komunikaci a stavbu experimentálního launcheru. Soubor je uložen pod názvem `usb_plan.md`.

[file-tag: code-generated-file-0-1782602153097065077]

### Klíčové body, které plán řeší:

1. **USB Passthrough bez Rootu:** Ukazuje přesný postup od zachycení zařízení v Android Manifestu (`usb_device_filter.xml`) přes vyžádání runtime oprávnění až po magický moment v Javě – vytáhnutí surového `int fd = connection.getFileDescriptor();`.
2. **Most do PRoot sandboxu:** Jak tenhle souborový deskriptor protlačit skrz izolaci PRootu. Popisuje metodu předání za běhu pomocí Unix Domain Socketu (`SCM_RIGHTS`), což ti umožní připojovat zařízení dynamically bez nutnosti restartovat celý linuxový kontejner.
3. **Nativní adopce (C/Python v Kali):** Jak přinutit nízkoúrovňové exploity nebo PyUSB, aby namísto prohledávání prázdného `/dev/bus/usb/` adoptovaly tento deskriptor pomocí funkce `libusb_wrap_sys_device`.
4. **Experimentální Launcher:** Kompletní návod na registraci aplikace jako `android.intent.category.HOME`. Obsahuje kód pro načtení nainstalovaných aplikací ze systému do sidebaru a hlavně trik s reflexí (`setLaunchWindowingMode`), který na HyperOS/MIUI vynutí spuštění Android aplikací ve Freeform plovoucích oknech přímo nad tvým běžícím Linux GUI desktopem.

```
# SYSTEM SECURITY SPECIFICATION: CERTIFICATE & ATTESTATION IMPLEMENTATION

## 1. Kontext a cíle
Tento modul slouží k zajištění (a) analýzy síťového provozu a (b) integrity aplikace pomocí hardwarového zabezpečení. Implementace musí striktně oddělovat koncepty Root CA (síťový dohled) a Platformní/Hardwarové atestace (integrita).

## 2. Implementace Root CA (MITM/TLS Security)
Účelem je umožnit aplikaci důvěřovat vlastním certifikátům pro analýzu provozu.

- **Mechanismus:** Použij `res/xml/network_security_config.xml`.
- **Požadavek:** Definuj `<base-config>` a `<debug-overrides>` pro povolení vlastních uživatelských certifikátů v rámci vývojového buildu.
- **Kritické upozornění:** V produkčním buildu musí být tento config striktně omezen (`cleartextTrafficPermitted=false`), aby nedošlo k úniku dat.
- **Implementace:** Vytvoř třídu pro správu `KeyStore`, která načte certifikát z interního úložiště a inicializuje `TrustManagerFactory` pro vytvoření `SSLContext`.

## 3. Implementace Hardwarové Atestace (Hardware Attestation)
Účelem je prokázat, že aplikace běží na neupraveném, bezpečném hardwaru a klíče jsou chráněny v TEE (Trusted Execution Environment) nebo StrongBox.

- **Generování klíčů:** Použij `KeyGenParameterSpec.Builder`.
    - `setKeySize(256)`
    - `setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))`
    - `setIsStrongBoxBacked(true)` (při selhání fallback na TEE).
    - `setAttestationChallenge(nonce)` (proti replay útokům).
- **Verifikace:**
    1. Aplikace vygeneruje klíč s atestací.
    2. Aplikace odešle certifikát z `KeyStore.getCertificateChain()` na backend.
    3. Backend verifikuje certifikát oproti Root certifikátu výrobce zařízení (Google/OEM) a kontroluje, zda příznaky v certifikátu (`attestationSecurityLevel`) potvrzují TEE/StrongBox.
- **Biometrická vazba:** Pro zvýšení bezpečnosti přidej `.setUserAuthenticationRequired(true)` a nastav timeout, aby klíč nebyl přístupný bez fyzického ověření uživatele.

## 4. Architektonické zásady
- **Separace:** Nikdy nemíchej logiku pro MITM (Root CA) a logiku pro zabezpečení integrity (Attestation).
- **Fail-safe:** Pokud atestace selže (např. detekce rootu/emulátoru), aplikace musí zamezit přístupu k citlivým funkcím.
- **Robustnost:** Ošetři výjimky `KeyPermanentlyInvalidatedException` (nastane při změně otisků prstů).

## 5. Security Checklist při implementaci
- [ ] Jsou certifikáty uloženy v `KeyStore` a ne v prostém souborovém systému?
- [ ] Jsou citlivé API požadavky podepisovány hardwarově chráněným klíčem?
- [ ] Je v `network_security_config.xml` produkční prostředí bezpečné- [ ] Probíhá ověření atestačního řetězce na serveru, ne lokálně v aplikaci?
