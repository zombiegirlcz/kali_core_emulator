# Protokol o testování kompatibility zařízení a příkazů NetHunter `nh*`

Tento dokument obsahuje podrobné výsledky testování aplikace **NetHunter AI Operator** (`com.linux_core`) na různých hardwarových architekturách, mobilních zařízeních (včetně Samsung, Google Pixel, Xiaomi/MIUI a dalších) a analýzu specifických OEM oprávnění vyžadovaných pro příkaz `nh network cell` (`getAllCellInfo`).

---

## 1. Architektonická kompatibilita (CPU Support Matrix)

NetHunter AI Operator běží v izolovaném nerootovaném PRoot kontejneru a využívá low-latency emulaci terminálu Termux. Kompatibilita nativních binárek a JNI knihoven se liší podle platformy:

| Architektura | Kompatibilita | Binární strategie (PRoot) | JNI AdGuard VPN status | Poznámky |
| :--- | :---: | :--- | :---: | :--- |
| **arm64-v8a** (ARM64) | **Plná (100 %)** | Dynamický `proot-aarch64` + `loader-aarch64` + `libtalloc-aarch64.so` s využitím `LD_PRELOAD`. | **Aktivní (Nativní)** | Primární produkční platforma. Maximální výkon bez režie. |
| **armeabi-v7a** (ARM32) | **Částečná** | Statické binárky `proot-static-arm32` + `loader-static-arm32` + `libtalloc-arm.so`. | **Passthrough** | Použitelné na starších zařízeních a nositelné elektronice. NAT VPN běží v passthrough režimu. |
| **x86_64** (AMD64 Emulátory) | **Částečná** | Statický `proot-x86_64` + `loader-x86_64` + `libtalloc-x86_64.so`. | **Passthrough** | Určeno pro Android Studio Emulátory, Chromebooky nebo Windows Subsystem for Android (WSA). |
| **x86** (Intel 32-bit) | **Částečná** | Statický `proot-i686` + `loader-i686` + `libtalloc-i686.so`. | **Passthrough** | Starší 32-bitové emulátory a speciální x86 tablety. |

*Poznámka k VPN:* JNI knihovny pro AdGuard nativní DNS/VPN jádro (`libadguard-core.so` atd.) jsou optimalizovány a kompilovány prioritně pro `arm64-v8a`. Na jiných architekturách NAT engine funguje bezpečně v passthrough režimu, aby nedošlo k výpadku připojení.

---

## 2. Testovací protokol sjednoceného rozhraní `nh` (Unified CLI)

Všechny `nh*` příkazy (spouštěné přes `/usr/local/bin/nh` nebo symlinky) komunikují s běžícím `LocalApiServer` na hostiteli (port `1337`) za použití Bearer tokenu generovaného při spuštění.

### Systémové příkazy (`nh system`)
- **`nh system battery`** (OK): Spolehlivě načítá procenta, stav (nabíjení/vybíjení), zdraví, teplotu a napětí baterie ze systémového `BatteryManager`.
- **`nh system volume [0-15]`** (OK): Korektně čte a nastavuje hlasitost médií přes Android `AudioManager`.
- **`nh system torch [on/off]`** (OK): Ovládá LED svítilnu přes `CameraManager` s ošetřením výjimek pro zařízení s chybějícím bleskem.
- **`nh system vibrate [ms]`** (OK): Aktivuje haptickou odezvu. Na zařízeních bez vibračního motorku bezpečně vrací JSON status `no_vibrator`.
- **`nh system toast <text>`** (OK): Zobrazuje systémovou textovou bublinu na popředí.
- **`nh system clipboard [get/set]`** (OK): Čte a zapisuje do schránky přes `ClipboardManager`. Na Androidu 10+ je přístup povolen díky aktivní emulaci okna Terminalu (aktivní fokus).
- **`nh system notification -t "Titulek" -c "Obsah"`** (OK): Vytvoří Android notifikaci.
- **`nh system speech`** (OK): Spouští mikrofon a rozpoznávání hlasu na text přes hostitelskou Google Speech API službu.
- **`nh system ashell`** (OK): Spustí interaktivní shell mimo PRoot (pod UID aplikace) s domovským adresářem ve `/data/data/com.linux_core/files`. Umožňuje plný únik z chrootu pro správu interních dat.

### Síťové příkazy (`nh network`)
- **`nh network wifi`** (OK): Vypíše aktuální SSID, BSSID, RSSI sílu signálu (přepočítanou na čitelný graf) a rychlost. Podporuje vyhledávání a připojování k sítím. (Na Androidu 10+ vyžaduje v závislosti na ROM potvrzení uživatele pro změnu stavu).
- **`nh network location`** (OK): Vrátí GPS koordináty z `LocationManager`. Obsahuje přímý odkaz na Google Maps a Geo URI.
- **`nh network map`** (OK): Spustí interaktivní CLI mapu (`TerminalMap`) s vystředěním na GPS lokaci zařízení.
- **`nh network ifconfig`** (OK): Bezkořenový emulátor síťových adaptérů. Čte `/proc/net/dev` a hostitelské API pro zobrazení reálného stavu rozhraní `wlan0` a virtuálního VPN adaptéru `tun0` s MTU 1500.
- **`nh network cell`** (Částečně - podle značky): Načítá informace o BTS vysílačích v dosahu (viz detailní analýza níže).

### VPN a MITM příkazy (`nh vpn`)
- **`nh vpn status|start|stop`** (OK): Kompletní ovládání a monitoring tunelu. Zobrazuje statistiky paketů a lidsky čitelný traffic (B, KB, MB, GB).
- **`nh vpn logs [-f] [-g <pattern>]`** (OK): Vypisuje dešifrovaný HTTPS provoz z TLS MITM proxy na portu 1337.
- **`nh vpn mitm [on|off|status|ca]`** (OK): Zapíná/vypíná inspekci HTTPS provozu, exportuje falešný CA certifikát pro instalaci do úložiště zařízení.
- **`nh vpn ai`** (OK): Umožňuje dotazování na ONNX rozhodovací model, nastavení verdiktů (Allow/Block) a denní statistiky detekovaných anomálií v síti.
- **`nh vpn bypass <příkaz>`** (OK): Spouští síťové operace (např. curl/apt) přes proxy tunel na portu `13339` mimo zachytávání AdGuard VPN.
- **`nh vpn ignore [on|off]`** (OK): Přidá aktuální terminálovou session do whitelistu ignorovaných, takže její provoz není zrcadlen do snifferu.

---

## 3. Analýza OEM specifických oprávnění pro Cellular Telemetry (`nh network cell`)

Příkaz `nh network cell` komunikuje s `/cellinfo` API endpointem a volá interní metodu `TelephonyManager.getAllCellInfo()`. Ta vrací seznam mobilních věží v dosahu (včetně MCC, MNC, LAC/TAC, CID/NCI, typu sítě GSM/LTE/WCDMA/NR a síly signálu v dBm).

Jelikož lze pomocí identifikátorů BTS lokalizovat zařízení s přesností na desítky metrů, Android od verze 10 považuje tyto informace za **vysoce citlivé lokalizační údaje**.

Níže je uvedeno porovnání chování a vyžadovaných oprávnění u hlavních výrobců mobilních telefonů:

### 1. Google Pixel (AOSP / Čistý Android)
Google Pixel striktně implementuje standardní AOSP bezpečnostní model.
* **Vyžadovaná oprávnění:**
  1. `android.permission.ACCESS_FINE_LOCATION` (Přesná poloha - Runtime)
  2. `android.permission.READ_PHONE_STATE` (Stav telefonu - Runtime)
  3. **Globální poloha:** Služby určování polohy (GPS) musí být v systému zapnuté.
* **Specifika chování:**
  * **Klíčový detail:** Pokud uživatel při prvním dialogu povolí pouze "Přibližnou polohu" (Coarse Location), API `getAllCellInfo()` vrátí **prázdný seznam** nebo vyhodí `SecurityException`. Musí být explicitně vybrána možnost **"Přesná poloha"**.
  * Neexistují zde žádná proprietární skrytá OEM oprávnění.

### 2. Samsung (One UI)
Samsung One UI plně respektuje standardní oprávnění AOSP, ale doplňuje je o vlastní podnikové zabezpečení.
* **Vyžadovaná oprávnění:** Standardní `ACCESS_FINE_LOCATION` + zapnuté GPS.
* **Specifika chování (Samsung Knox & Auto Blocker):**
  * Pokud je v nastavení zapnutý **"Auto Blocker"** (představený v One UI 6.0+), může blokovat instalaci a sideloading aplikací mimo Google Play. Na samotný běh API však nemá vliv, pokud je aplikace nainstalována.
  * **MDM / Knox Active Protection:** Pokud je zařízení v korporátní správě, Knox bezpečnostní zásady (Policies) mohou tiše zablokovat volání `getAllCellInfo` pro ne-MDM aplikace. V takovém případě API vrací prázdný seznam bez vyhození výjimky. Pro vyřešení je nutné aplikaci udělit výjimku v MDM konzoli.

### 3. Xiaomi / Redmi / POCO (MIUI / HyperOS)
Xiaomi implementuje extrémně agresivní a proprietární bezpečnostní vrstvu prostřednictvím své systémové aplikace "Security Center" (`com.miui.securitycenter`).
* **Vyžadovaná oprávnění:**
  1. Standardní AOSP `ACCESS_FINE_LOCATION`
  2. **Proprietární OEM oprávnění:** `com.miui.securitycenter.permission.modem_location` (Modem Location)
* **Proč je vyžadováno:**
  * I když uživatel povolí aplikaci přesnou polohu v běžném Android dialogu, MIUI Security Center zablokuje přístup k rádiovému modemu a `getAllCellInfo()` selže.
* **Jak to NetHunter řeší:**
  * Aplikace obsahuje třídu `MiuiSecurityBridge.kt`, která automaticky detekuje Xiaomi zařízení.
  * Pokud zjistí chybějící oprávnění, odešle systémový broadcast `com.miui.securitycenter.action.MODEM_LOCATION_REQUEST` s cílovým balíčkem `com.linux_core`, což vyvolá skrytý MIUI dialog s žádostí o povolení přístupu k informacím o mobilní síti.
  * Jako záloha se aplikace pokouší o tichý grant přes lokální shell: `pm grant com.linux_core com.miui.securitycenter.permission.modem_location`.

### 4. OPPO / OnePlus / Realme (ColorOS / OxygenOS / Realme UI)
Tato zařízení sdílejí společný základ ColorOS, který se zaměřuje na maskování soukromí (Privacy Guard).
* **Vyžadovaná oprávnění:** `ACCESS_FINE_LOCATION` + standardní telefonní práva.
* **Specifika chování (Falešné Telemetrické Údaje):**
  * ColorOS obsahuje funkci **"Prázdné informace o zařízení"** (Empty Device Info / Fake Telemetry).
  * Pokud je tato funkce u aplikace zapnutá, systém nevrátí chybu (SecurityException), ale podvrhne aplikaci **falešná nebo nulová data** (např. MCC=0, MNC=0, CID=0).
  * **Řešení:** Uživatel musí v nastavení aplikace přejít do "Ochrana soukromí -> Práva -> Číst informace o telefonu/buňkách" a ručně přepnout režim z "Poskytnout prázdné informace" na **"Povolit reálné informace"**.

### 5. Vivo / iQOO (Funtouch OS / OriginOS)
Vivo využívá proprietární aplikaci **iManager** pro správu systémové bezpečnosti.
* **Vyžadovaná oprávnění:** Standardní AOSP lokace.
* **Specifika chování:**
  * iManager blokuje jakýkoliv přístup k basebandu a hardware identifikátorům.
  * V sekci "iManager -> Správa oprávnění -> Aplikace -> NetHunter" musí uživatel ručně povolit možnost **"Číst identifikátory telefonu a polohu sítě"** (Read Phone Identifiers and Network Location), jinak jsou výsledky `getAllCellInfo` prázdné.

### 6. Huawei / Honor (EMUI / MagicOS)
Huawei (i po oddělení značky Honor) používá přísný správce oprávnění v aplikaci **Optimizer** (Phone Manager).
* **Vyžadovaná oprávnění:** `ACCESS_FINE_LOCATION`
* **Specifika chování:**
  * EMUI obsahuje vyhrazené oprávnění v "Správci oprávnění -> Přístup k mobilní síti / carrier parametrům". Bez jeho schválení se volání zablokuje. Při prvním spuštění příkazu se obvykle zobrazí systémový dialog s dotazem na povolení čtení mobilních parametrů.

---

## 4. Shrnutí a doporučení pro uživatele

Pro zajištění bezproblémového chování všech pokročilých příkazů NetHunter (především rádiového a mobilního průzkumu přes `nh network cell`) doporučujeme:

1. **Vždy povolit "Přesnou polohu" (Precise Location)** namísto přibližné při zobrazení systémové výzvy.
2. **Ujistit se, že je GPS v telefonu aktivní**, jinak Android API odmítne vrátit seznam buněk z důvodu ochrany soukromí.
3. **Na Xiaomi/MIUI/HyperOS zařízeních** potvrdit dodatečný dialog o přístupu k "Modem Location", případně spustit v hostitelském shellu:
   ```bash
   pm grant com.linux_core com.miui.securitycenter.permission.modem_location
   ```
4. **Na OPPO/OnePlus/Realme zařízeních** zkontrolovat, zda systém neaplikuje "Privacy Guard" a neposílá prázdná data buněk.

*Všechny komponenty, emulované příkazy a bezpečnostní přemostění byly úspěšně otestovány a jsou plně připraveny pro nasazení napříč platformami.*
