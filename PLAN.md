``` - [ ] **Konfigurace Intent Filtru pro Activity:** Zajistit, aby se aplikace probudila nebo nabídla otevření hned po 
zasunutí kabelu: ```xml <intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" /> </intent-filter> <meta-data
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
## Architektura

Přirovnání: dnešní noVNC je jako **kabelová televize** — server (guest OS) vysílá celý obraz plochy jako video, telefon jen pasivně sleduje a klika. Code-server bude fungovat jako **webová aplikace** (podobně jako Google Docs) — HTML/JS/CSS se pošlou jednou do prohlížeče, a pak už se přenášejí jen malé "zprávy" (obsah souboru, klávesa stisknutá, uložit). Je to lehčí, rychlejší a hlavně to umí komunikovat s rozšířeními stejně jako desktopový VS Code, protože code-server *je* VS Code jádro (jen běžící na serveru místo na tvém stroji).

Zapadá to do tvého existujícího vzoru "guest proces + loopback port + CLI toggle + Compose tab", stejně jako `nethunter-desktop` (XFCE/noVNC na 6080) nebo `vpn-cli mitm` (MITM na 1337). Přidáváš jen čtvrtý pilíř vedle Terminálu, VPN a Desktopu: **Editor**.

```
┌─────────────────────────────────────────────┐
│ Android Host                                 │
│  MainActivity / Compose UI                   │
│   ├─ TerminalActivity (dnes)                 │
│   ├─ VPN Center tab (dnes)                   │
│   ├─ Desktop tab → noVNC :6080 (dnes)        │
│   └─ NOVÝ: Editor tab → WebView :8443        │
│              │                               │
│  LocalApiServer :1337 (bridge)               │
│              │                               │
│  PRoot guest (Kali/Parrot rootfs)            │
│   └─ code-server proces (Node.js)            │
│        poslouchá 127.0.0.1:8443              │
│        ├─ Open VSX marketplace (rozšíření)   │
│        └─ /root/projects (tvůj kód)          │
└─────────────────────────────────────────────┘
```

---

## Fáze 1 — Instalace code-serveru do guest rootfs

Analogie: je to jako instalace nové aplikace do "vnitřního telefonu" (Kali), ne do Androidu samotného.

- Ověřit dostupnou Node.js verzi v Kali/Parrot rootfs (code-server potřebuje Node ≥18)
- Instalovat přes oficiální instalační skript code-serveru (funguje na arm64) nebo přes `npm install -g code-server`
- Otestovat ruční spuštění v Termuxu: `code-server --bind-addr 127.0.0.1:8443 --auth password`
- Zapsat cestu k binárce a datovému adresáři (`~/.local/share/code-server`) do dokumentace, podobně jako máš zdokumentované cesty k MITM CA

## Fáze 2 — Deploy skript v ProotManager (podle vzoru desktopu)

Analogie: stejně jako se ti "sám nastěhuje nábytek", když poprvé spustíš appku — `ProotManager` dnes deployuje `vpn-cli`, `nethunter-desktop` atd. do `/usr/local/bin/`. Přidáš tam nový skript.

- Nový soubor `code-server-ctl` v `app/src/main/assets/`, deploynutý stejným mechanismem jako `vpn-cli`
- Příkazy: `code-server-ctl start|stop|status|password`
- Heslo generuj náhodně při prvním spuštění a ulož do `SharedPreferences` (stejný vzor jako `api_security` token) — **nikdy** natvrdo v kódu (poučení z C1 v tvém security auditu)
- Log soubor pro `nethunter-log -g "code-server"` diagnostiku

## Fáze 3 — Bezpečnostní vrstva (kritické, podle tvého vlastního auditu)

Analogie: LocalApiServer bez auth byl jako nechat vchodové dveře bytu dokořán na chodbě plné lidí (C2/C3 v tvém auditu). Editor se stejnou chybou = kdokoli v Wi-Fi síti čte/edituje tvůj kód.

- **Bind pouze na `127.0.0.1`**, nikdy `0.0.0.0` — žádný `--host` toggle jako u `share_local_api`
- **Vždy `--auth password`**, heslo z `SharedPreferences`, ne z argumentu viditelného v `ps aux`
- Pokud budeš chtít přístup i z jiného zařízení v síti (např. notebook), tunelovat přes stejný bezpečnostní model jako VPN bypass proxy (13339) — ne přímo vystavit port
- Přidat endpoint do `LocalApiServer` (`/editor/status`, `/editor/start`, `/editor/stop`) chráněný stejným Bearer tokenem jako `/vpn/mitm`

## Fáze 4 — UI integrace (Compose)

Analogie: nová záložka vedle "Terminal / VPN / Desktop" v drawer menu — stejná dlaždice, jiná ikona.

- Nový tab `EditorTab.kt` po vzoru `VpnSettingsTab.kt`
- Přepínač "Code Server" (start/stop) + zobrazení vygenerovaného hesla + QR/copy tlačítko
- `WebView` uvnitř tabu, který načte `http://127.0.0.1:8443` (Android WebView, ne systémový Chrome — víc kontroly, méně tabů)
- Stavová ikonka v horní liště podobně jako žlutý MITM indikátor

## Fáze 5 — Rozšíření (Open VSX)

Analogie: místo App Store (Microsoft Marketplace, licenčně zamčený) použiješ F-Droid ekvivalent — Open VSX. Většina věcí tam je, ale ne úplně všechno.

- Code-server je defaultně napojený na `open-vsx.org` — funguje out of the box
- Ověřit klíčová rozšíření pro tvůj use-case (Python, Kotlin, ShellCheck, GitLens) — zkontrolovat dostupnost na Open VSX před spoléháním na ně
- Pokud něco chybí (typicky C/C++ od Microsoftu), řešení: `.vsix` soubor ručně nahraný přes `code-server --install-extension soubor.vsix`

## Fáze 6 — Perzistence a workspace

- Guest projekt adresář: navrhuji `/root/projects` (mimo `/tmp`, aby přežil restart kontejneru)
- Nastavení code-serveru (`~/.local/share/code-server/User/settings.json`) verzovat/zálohovat stejně jako session names dnes perzistují v SharedPreferences

## Fáze 7 — Testování

- Spustit `code-server-ctl start`, ověřit `nethunter-log -g "code-server"` na chyby portu/bindu
- Zkontrolovat, že port 8443 **není** dostupný zvenčí (test z jiného zařízení v síti — mělo by selhat spojení)
- Otestovat instalaci rozšíření a otevření reálného projektu z `/root/projects`

---

## Architektura

Analogie: `code-server-ctl` bude fungovat jako **vrátný v budově** — nespouští ani neřídí nic sám, jen na povel (od tebe přes terminál, nebo od appky přes API) otevře/zavře dveře a řekne, kdo má klíč (heslo). Samotná "kancelář" (code-server proces) běží pořád stejně, vrátný jen kontroluje přístup a hlásí stav.

`LocalApiServer` endpointy jsou pak jako **recepce v přízemí budovy** — Android appka (Compose UI) se neptá přímo vrátného v Kali kontejneru, ale zavolá na recepci (HTTP na 127.0.0.1:1337), recepce ověří tvůj Bearer token a teprve pak zavolá vrátnému uvnitř. Stejný vzor, jaký už máš u `/vpn/mitm`.

```
Compose UI (EditorTab.kt)
     │  HTTP + Bearer token
     ▼
LocalApiServer :1337  ← "recepce"
     │  volá shell v guest PRootu
     ▼
code-server-ctl        ← "vrátný"
     │  spouští/zastavuje
     ▼
code-server proces :8443 (jen 127.0.0.1)
```

---

## 1. Specifikace `code-server-ctl`

Umístění: `app/src/main/assets/code-server-ctl`, deploy mechanismem jako `vpn-cli` (přes `ProotManager` do guest `/usr/local/bin/`).

**Kontrakt příkazů** (vstup/výstup, žádné vedlejší efekty navíc):

| Příkaz | Co dělá | Výstup (stdout) |
|---|---|---|
| `code-server-ctl start` | Zkontroluje, jestli neběží (PID soubor); pokud ne, vygeneruje/přečte heslo, spustí proces na pozadí s `nohup`, zapíše PID | `{"status":"started","port":8443}` nebo `{"status":"already_running"}` |
| `code-server-ctl stop` | Přečte PID soubor, pošle `SIGTERM`, smaže PID soubor | `{"status":"stopped"}` |
| `code-server-ctl status` | Zkontroluje, jestli PID žije (`kill -0`) | `{"status":"running","port":8443}` / `{"status":"stopped"}` |
| `code-server-ctl password` | Vygeneruje nové náhodné heslo (pokud ještě neexistuje) nebo vrátí existující | `{"password":"xxxx-xxxx"}` |

**Klíčové návrhové body** (analogie: je to jako trezor s kódem, ne visací zámek s klíčem pod rohožkou):

- Heslo se generuje jednou (`openssl rand -base64 12` nebo podobně) a uloží do `~/.config/code-server/config.yaml` — **nikdy** jako argument příkazové řádky (jinak je vidět v `ps aux` = díra typu C1 z tvého auditu).
- Config soubor musí mít práva `600` (jen vlastník čte) — stejný princip jako oprava C15 (world-readable token file).
- Bind adresa v configu natvrdo `127.0.0.1:8443` — žádná proměnná, žádný `--host` parametr zvenčí. Toto je jediné místo, kde se to nastavuje, aby nešlo omylem přepnout na `0.0.0.0`.
- PID soubor v `/tmp/code-server.pid` — analogie jako "cedulka na dveřích, jestli je vrátný uvnitř".

**Struktura config.yaml, kterou skript zapisuje:**

```yaml
bind-addr: 127.0.0.1:8443
auth: password
password: <vygenerované heslo>
cert: false
```

---

## 2. Specifikace endpointů v `LocalApiServer`

Analogie: recepce má tři nová tlačítka na pultu vedle těch, co už má pro MITM. Stejný vzor autentizace (Bearer token z `api_security` SharedPreferences), stejný vzor odpovědí (JSON).

| Endpoint | Metoda | Co dělá | Odpověď |
|---|---|---|---|
| `/editor/start` | POST | Zavolá `code-server-ctl start` přes guest shell | `{"status":"started"}` |
| `/editor/stop` | POST | Zavolá `code-server-ctl stop` | `{"status":"stopped"}` |
| `/editor/status` | GET | Zavolá `code-server-ctl status` | `{"status":"running","port":8443}` |
| `/editor/password` | GET | Vrátí heslo pro zobrazení v UI (copy tlačítko) | `{"password":"xxxx-xxxx"}` |

**Bezpečnostní pravidla pro tyto endpointy** (stejná logika jako u MITM endpointů — nic nového nevymýšlet):

- Vyžadovat `Authorization: Bearer <token>` — stejný token jako zbytek API
- `/editor/password` navíc omezit na `localhost` origin check (stejně jako mají citlivé endpointy dnes) — heslo se nemá posílat, pokud appka náhodou běží v `share_local_api=on` režimu
- Žádný z těchto endpointů nesmí přijímat volný text/shell příkaz jako parametr (na rozdíl od starého `/shell` endpointu z C2) — jen pevné, neparametrizované akce start/stop/status/password

---

## 3. Compose vrstva — kontrakt, ne kód

Analogie: `EditorTab.kt` je jen "displej na recepci", nemusí nic vědět o vnitřním fungování vrátného.

- Tlačítko Start/Stop volá `/editor/start` nebo `/editor/stop`, čeká na odpověď, přepne stav ikonky (stejný vzor jako toggle u MITM)
- Po startu: `WebView` načte `http://127.0.0.1:8443`, přihlašovací obrazovka code-serveru se zeptá na heslo — to natáhneš z `/editor/password` a buď předvyplníš, nebo zobrazíš vedle jako "copy to clipboard"
- Stavová kontrola při otevření tabu: zavolat `/editor/status` a podle toho rozhodnout, jestli ukázat WebView rovnou, nebo tlačítko Start

---

## 4. Pořadí implementace (pro zadání dál)

1. Ručně v Termuxu ověřit, že `code-server --bind-addr 127.0.0.1:8443 --auth password` vůbec naběhne na tvé architektuře (arm64) — toto je nutná validace před psaním čehokoli dalšího
2. Napsat a nasadit `code-server-ctl` skript, otestovat všechny 4 příkazy ručně z Termuxu
3. Přidat 4 endpointy do `LocalApiServer`, otestovat přes `curl` s Bearer tokenem (bez UI)
4. Teprve pak stavět `EditorTab.kt` a napojovat WebView

Chceš, abych rozepsal i konkrétní `curl` testovací sadu pro krok 3 (aby sis to na telefonu ověřil ještě před psaním UI), nebo je tenhle plán dost detailní na předání dál?
## Architektura

Analogie: navrhni to jako **letištní terminál v miniatuře** — málo gest, velké piktogramy, jasně oddělené "brány" (Start editoru / Nastavení / Samotný kód). Na telefonu nemáš myš ani velký monitor, takže stejně jako se na letišti nespoléháš na drobné cedulky, ale na obří ikony a barevné pruhy, i tady musí každý stav (běží/neběží/chyba) být čitelný na první pohled z 15 cm vzdálenosti, palcem, na sluníčku.

Vychází to z tvého existujícího vizuálního jazyka (Kali cyan/zelená, Termius-styl klávesnice, žluté MITM indikátory) — Editor tab bude čtvrtá "budova" vedle Terminal / VPN / Desktop, ne cizí prvek.

---

## 1. Vstupní obrazovka tabu — dva stavy

Analogie: jako výtah s jedním tlačítkem — buď je "v přízemí" (vypnuto, čekáš), nebo "jede" (zobrazuje obsah). Žádné komplikované menu navíc.

**Stav A — Editor vypnutý (výchozí)**

```
┌─────────────────────────────┐
│  🖥️  CODE EDITOR             │
│                               │
│      [ ⚪ STOPPED ]           │
│                               │
│   ┌───────────────────────┐  │
│   │   ▶  START EDITOR     │  │  ← velké tlačítko, 56dp výška
│   └───────────────────────┘  │
│                               │
│   Workspace: /root/projects  │
│   Port: 127.0.0.1:8443       │
│                               │
└─────────────────────────────┘
```

- Status pilulka nahoře (šedá/zelená/žlutá) — stejný vzor jako máš u VPN stavu
- Jedno primární tlačítko na celou šířku, palec dosáhne odkudkoli
- Drobné info dole (workspace cesta, port) — pomocné, ne rušivé

**Stav B — Editor běží (po startu, ~2–5s loading)**

```
┌─────────────────────────────┐
│ ☰  🟢 RUNNING     🔑  ⏹️  ⋮  │  ← horní lišta, 44dp
├─────────────────────────────┤
│                               │
│                               │
│      [ WebView: VS Code ]    │
│                               │
│                               │
├─────────────────────────────┤
│ 🎛️ 🔣 🧭 ⚡ 🛠️              │  ← existující hacker klávesnice
└─────────────────────────────┘
```

- Horní lišta je tenký pruh: hamburger (drawer se session tabs zůstává dostupný), stavová tečka, **klíč ikona = zobrazit/kopírovat heslo**, **stop ikona**, **⋮ menu** (restart, otevřít v prohlížeči, log)
- Dole zůstává tvoje existující Hacker Keyboard (Ctrl kombinace, F-klávesy) — to je klíčové, protože VS Code v prohlížeči na telefonu bez fyzické klávesnice je bez `Ctrl+P`, `Ctrl+~` prakticky nepoužitelný. Nemusíš nic nového vymýšlet, jen napojit stejnou lištu na WebView input.

---

## 2. Heslo — jak ho ukázat, ne aby to bylo otravné

Analogie: jako kód od schránky na balíčky — nechceš ho pořád vypisovat, ale chceš ho mít po ruce jedním klepnutím.

- Klíč ikona v horní liště → bottom sheet (vyjede zdola, ne nový screen):

```
┌─────────────────────────────┐
│  Editor Password              │
│                               │
│   ┌───────────────────────┐  │
│   │  xk29-mVq7-plz4        │  │
│   └───────────────────────┘  │
│                               │
│   [ 📋 Copy ]  [ 🔄 Regenerate ]│
│                               │
└─────────────────────────────┘
```

- Regenerate je oddělené, s potvrzením (protože zneplatní aktivní session) — stejný princip opatrnosti jako máš u `event_delete`.

---

## 3. Onboarding při prvním spuštění

Analogie: jako první spuštění bankovní appky — jedna obrazovka, co ti řekne, kde je klíč, než tě pustí dál.

Při úplně prvním `START EDITOR` (heslo ještě neexistuje) — krátký dialog:

```
┌─────────────────────────────┐
│  🔐 First-time setup         │
│                               │
│  A password has been         │
│  generated for this editor.  │
│  It's stored only on this    │
│  device.                     │
│                               │
│  xk29-mVq7-plz4               │
│                               │
│  [ 📋 Copy & Continue ]      │
└─────────────────────────────┘
```

Tím zajistíš, že si heslo všimneš hned, ne až budeš hledat, kde ho appka schovala.

---

## 4. Chybové stavy — čitelné bez nutnosti chodit do logů

Analogie: jako červené světlo na sporáku "je zapnuto" — okamžitá vizuální zpětná vazba, ne text, který musíš číst.

| Situace | Pilulka nahoře | Akce nabídnutá uživateli |
|---|---|---|
| Port obsazený jiným procesem | 🔴 `PORT BUSY` | tlačítko `Force restart` |
| Node/code-server chybí v rootfs | 🔴 `NOT INSTALLED` | tlačítko `Install now` (spustí instalační skript) |
| Proces spadl po startu | 🔴 `CRASHED` | tlačítko `View logs` → otevře `nethunter-log -g "code-server"` v terminálu |
| Vše OK | 🟢 `RUNNING` | — |
| Startuje (2–5s) | 🟡 `STARTING…` | spinner, tlačítka disabled |

---

## 5. Umístění v navigaci

Analogie: nepřidávat novou "budovu daleko od centra", ale novou dlaždici vedle existujících vchodů.

- Drawer (session panel) dostane čtvrtou ikonu vedle 🐉/🦜 distro badgí a VNC launcheru: **`</> `** ikona pro Editor
- V minimalizovaném 70dp drawer view (peek gesto, který už máš) přibude jen jedna malá ikonka vedle stávajících — žádné rozšiřování šířky panelu

---

## 6. Barevná signalizace (konzistence s existujícím systémem)

Máš už zavedený kód barev — Editor do něj jen zapadá, nevymýšlí nový:

| Barva | Dnešní použití | Nové použití |
|---|---|---|
| 🟡 žlutá | MITM intercept aktivní | Editor startuje |
| 🟢 zelená | success/established v logu | Editor running |
| 🔴 červená | error/fail v logu | Editor crashed/port busy |
| 🟠 oranžová | VPN ignored badge | (nepoužito zde, ponecháno) |

---



				**MOTD REFACTOR AND NEW LOGO FOR PARROT OS AND KALI LINUX**



- udelej refactoring welcome motd a pridej do kazdeho distra specificke logo viz *↓↓↓*


kali_banner() {
    clear
    printf "${blue}##################################################\n"
    printf "${blue}##                                              ##\n"
    printf "${blue}##  88      a8P         db        88        88  ##\n"
    printf "${blue}##  88    .88'         d88b       88        88  ##\n"
    printf "${blue}##  88   88'          d8''8b      88        88  ##\n"
    printf "${blue}##  88 d88           d8'  '8b     88        88  ##\n"
    printf "${blue}##  8888'88.        d8YaaaaY8b    88        88  ##\n"
    printf "${blue}##  88P   Y8b      d8''''''''8b   88        88  ##\n"
    printf "${blue}##  88     '88.   d8'        '8b  88        88  ##\n"
    printf "${blue}##  88       Y8b d8'          '8b 888888888 88  ##\n"
    printf "${blue}##                                              ##\n"
    printf "${blue}####  ############# NetHunter ####################${reset}\n\n"
}

red='\033[1;31m'
green='\033[1;32m'
yellow='\033[1;33m'
blue='\033[1;34m'
light_cyan='\033[1;96m'
reset='\033[0m'




R="$(printf '\033[1;31m')"
G="$(printf '\033[1;32m')"
Y="$(printf '\033[1;33m')"
B="$(printf '\033[1;34m')"
C="$(printf '\033[1;36m')"
W="$(printf '\033[1;37m')"

parrot_banner() {
    clear
    printf "\033[33m╭━━━╮╱╱╱╱╱╱╱╱╱╭╮╱╭━━━┳━━━╮\033[0m\n"
    printf "\033[33m┃╭━╮┃╱╱╱╱╱╱╱╱╭╯╰╮┃╭━╮┃╭━╮┃\033[0m\n"
    printf "\033[33m┃╰━╯┣━━┳━┳━┳━┻╮╭╯┃┃╱┃┃╰━━╮\033[0m\n"
    printf "\033[33m┃╭━━┫╭╮┃╭┫╭┫╭╮┃┃╱┃┃╱┃┣━━╮┃\033[0m\n"
    printf "\033[33m┃┃╱╱┃╭╮┃┃┃┃┃╰╯┃╰╮┃╰━╯┃╰━╯┃\033[0m\n"
    printf "\033[33m╰╯╱╱╰╯╰┻╯╰╯╰━━┻━╯╰━━━┻━━━╯\033[0m\n"
    printf "\033[32m   A modded gui of parrot\033[0m\n"
    printf "\033[32m      Code by @sabamdarif \033[0m\n"

}
