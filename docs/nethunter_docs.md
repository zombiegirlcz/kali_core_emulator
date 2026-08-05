# 🐉 NetHunter AI Operator - Kompletní Dokumentace Funkcí

Tento dokument obsahuje přehled všech dostupných příkazů a API funkcí, které můžete používat z terminálu NetHunter AI Operator. Tyto příkazy zajišťují integraci s Android systémem a správu VPN.

## 🚀 Životní cyklus spouštění & struktura PRootu

Při každém startu terminálu zajišťuje `ProotManager` inicializaci a úpravu virtuálního prostředí:

### 📁 Vytvářené adresáře
V rootfs se automaticky ověřuje a vytváří tato adresářová struktura:
`system`, `dev`, `proc`, `sys`, `tmp`, `root`, `sdcard` (pokud je sdcard povolena), `bin`, `usr/bin`, `usr/sbin`, `sbin`, `lib`, `lib64`, `usr/lib`, `etc`.

### 🏳️ Stavové soubory (Sentinely)
- `/root/.hushlogin` - Vypíná výchozí uvítací zprávy shellu.
- `/root/.bootstrap_required` - Vytvoří se při první instalaci a dává pokyn ke spuštění `bootstrap.sh`. Po dokončení se smaže.
- `/root/.setup_done` - Vytvoří se po úspěšném dokončení bootstrap skriptu.

### ⚙️ Automatické úpravy a opravy prostředí
- **Přesměrování systemd příkazů:** Nástroje jako `systemctl`, `service`, `update-rc.d`, `resolvconf`, `journalctl` atd. jsou v unrooted prostředí přesměrovány na `/bin/true`, čímž se předchází selhání instalací balíčků.
- **Oprava zavaděče (linker):** Dynamický zavaděč se kopíruje do `lib/ld-linux-aarch64.so.1` a `lib64/ld-linux-aarch64.so.1`. Knihovna `libtalloc.so.2` se umísťuje do `lib/libtalloc.so.2`.
- **Oprava nefunkčních shell odkazů:** Pokud jsou `bin/sh` nebo `bin/bash` rozbité symlinky, nahradí se skutečnými kopiemi shellů.
- **Předpřipravené API Wrappery:** V `/usr/local/bin` jsou nasazeny vlastní verze `apt`/`apt-get` ošetřující pády `debconf`, `dcheck` pro diagnostiku, `vpn-bypass` pro obcházení VPN filtru (port 13339) a sjednocený CLI nástroj `nh` aliasy starších příkazů (zpětná kompatibilita).

## 🆕 Sjednocený CLI příkaz `nh`

Od verze 4.2 jsou všechny dřívější `nethunter-*`, `vpn-*` a `vpn-cli` příkazy sjednoceny do jednoho CLI:

```bash
nh <kategorie> <akce> [argumenty]
```

**Hlavní kategorie:** `system`, `network`, `vpn`, `agent`, `log`, `device`, `api`, `desktop`, `fix`, `apps`, `usb`, `distro`, `help`, `list`

**Příklady:**
```bash
nh list                          # seznam všech příkazů
nh help <kategorie>              # nápověda pro kategorii
nh network location              # GPS + Google Maps
nh system battery                # stav baterie
nh vpn on                        # zapnout VPN
nh vpn mitm on                   # zapnout TLS MITM
nh log -n 50 -g TlsMitm          # logcat viewer
```

Staré názvy (`nethunter-toast`, `vpn-cli`, `vpn-on`, `vpn-bypass`, `ignore-vpn`, ...) zůstávají funkční jako symlinky na `nh`.

## 📦 Správa kontejnerů — `nh distro` (proot-distro-like)

`nh distro` je wrapper inspirovaný `proot-distro` pro správu PRoot kontejnerů (kali, parrot). Dá se volat z guestu **i z hostitele bez rootu** (localhost API bez auth).

```bash
nh distro list                          # seznam distro + stav
nh distro status                        # alias list
nh distro ps                            # aktivní session (ID, distro, vpn-ignored)
nh distro kill <session_id> [--force]   # ukončit session
nh distro remove <id> [--force]         # smazat rootfs (kali|parrot)
nh distro backup [id]                   # záloha rootfs do Downloads (default kali)
nh distro restore <soubor> [--force]    # obnova rootfs ze souboru v Downloads
nh distro help                          # nápověda
```

**Bezpečnostní pojistka:** `kill`, `remove`, `restore` vyžadují `--force`, jinak vrtnou 409 `confirmation_required`.

**Z hostitele bez rootu (Termux / adb shell):**
```bash
curl http://127.0.0.1:1337/distro/ps          # localhost = bez auth
curl -X POST http://127.0.0.1:1337/distro/remove -d '{"id":"kali","force":true}'
```

> **Fáze 2 (plánováno):** `install`, `progress`, `reset`, `login` (download+extract rootfs s progresem, resp. spuštění shellu).

## 🔑 Root Bridge (`sudo` / `su`)

Od verze 4.2-MITM-LOG-FIX je implementován **root bridge** přes hostitelský Magisk/roo daemon (`su_daemon`). Příkazy `sudo` a `su` v hostovaném OS jsou wrappery, které posílají příkazy na HOST a spouští je tam s root právy.

### Jak to funguje
- `/usr/local/bin/su` a `/usr/local/bin/sudo` jsou symlinky (nebo kopie) na `su_wrapper`.
- `su_wrapper` komunikuje přes Unix socket s `su_daemon` (běží na hostu jako root v `filesDir/ipc/magisk_daemon.sock`, s bind do `/run/host_ipc`).
- Původní `/usr/bin/su`, `/bin/su`, `/usr/bin/sudo` jsou přejmenovány na `.orig` zálohy.
- Daemon spustí příkaz jako **root na hostiteli** (host-root sémantika — Kali binárky jako `ifconfig` se na hostu neresolvují).

### CLI
```bash
sudo id                        # GID/UID root na hostiteli
su -c 'whoami'                # root shell / příkaz
su                            # hostitelský root shell
```

### Ovládání daemona (ROOT BRIDGE tab / RootBridgeManager)
- Přepínač v terminálu → ROOT BRIDGE.
- `startDaemon` nejdřív dělá `pkill -f su_daemon`, smaže staré socket/pid soubory a loguje do `ipc/su_daemon.log`.
- `stopDaemon` dělá `pkill -f su_daemon`.
- PID: `ipc/su_daemon.pid`, socket: `ipc/magisk_daemon.sock`.
- Daemon zapisuje PID soubor a čistí ho při ukončení.

## 💻 ashell (-c)

Terminálový helper `ashell` kromě otevření host `TerminalActivity` podporuje vykonání příkazu na hostu vrácením výstupu do terminálu:

```bash
ashell -c 'id'                 # vykoná na hostu a vrátí stdout/stderr + exit code
ashell --cmd 'ls /'            # totéž
```

Technicky POSTuje na `http://127.0.0.1:1337/shell` (s bearer tokenem), který je chráněn allowlistem + blokem destruktivních vzorů.

## 📡 ifconfig bez su

Síťové rozhraní hostitelského Androidu získáte i bez root bridge:

```bash
ifconfig            # wlan0 + tun0 (hostitelská síť)
# nebo
nh network ifconfig
```

Užitečné pro orientaci v síti i bez zapnutého daemonu.
Klasický příkaz `ifconfig` je k dispozici jako wrapper v `/usr/local/bin/ifconfig` (deleguje na `nh network ifconfig`).

## 📱 Hardwarové a Systémové Funkce (Android API Bridge)

Tyto příkazy volají lokální API server (`127.0.0.1:1337`) a umožňují ovládat a číst senzory hostitelského zařízení. Všechny jsou dostupné jak přes sjednocený CLI `nh <kategorie> <akce>`, tak přes staré aliasy (symlinky).

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nh system battery` | Vypíše aktuální stav baterie ve formátu JSON. | `nh system battery` |
| `nh system toast <msg>` | Zobrazí na obrazovce vyskakovací Toast upozornění. | `nh system toast "Úkol úspěšně dokončen!"` |
| `nh system vibrate [ms]` | Rozvibruje zařízení (výchozí doba je 500ms). | `nh system vibrate 1000` |
| `nh system tts-speak <text>` | Přečte zadaný text pomocí Text-to-Speech (syntéza řeči). | `echo "Firewall breach detected" | nh system tts-speak` |
| `nh system clipboard get` | Přečte obsah hostitelské schránky. | `nh system clipboard get` |
| `nh system clipboard set <text>`| Zapíše text do hostitelské schránky. | `nh system clipboard set "MojeTajneHeslo123"` |
| `nh system notification -t <t> -c <c>`| Pošle standardní systémovou notifikaci. | `nh system notification -t "Upozornění" -c "Skenování hotovo"` |
| `nh network wifi`| Vrátí informace o Wi-Fi síti ve formátu JSON. | `nh network wifi` |
| `nh network cell` | Zobrazí informace o mobilní síti — operátor, signál (dBm), typ sítě (5G/4G/3G), věže. | `nh network cell` |
| `nh network location` | Vrátí aktuální GPS souřadnice + odkaz na Google Maps pro otevření v mapách. | `nh network location` |
| `nh network map` | Spustí TerminalMap interaktivní mapovač OpenStreetMap s aktuální lokací. | `nh network map` |
| `nh network ifconfig [rozhraní]` | Zobrazí síťová rozhraní hostitelského Androidu (IP, MAC, MTU, statistiky). | `nh network ifconfig wlan0` |
| `ifconfig [rozhraní]` | Stejné jako `nh network ifconfig`, dostupné jako samostatný wrapper v `/usr/local/bin/ifconfig`. | `ifconfig wlan0` |
| `nh system volume [level]` | Získá nebo nastaví hlasitost médií (0-15/100). | `nh system volume 10` |
| `nh system torch on|off` | Zapne nebo vypne svítilnu zařízení. | `nh system torch on` |
| `nh log [-n N] [-g P]`| Barevné zobrazení logcat záznamů aplikace (V=šedá, D=modrá, I=zelená, W=žlutá, E/F=červená). | `nh log -n 50 -g "LocalApiServer"` |
| `nh api share on|off|status`| Ovládá sdílení API serveru do sítě (0.0.0.0 vs 127.0.0.1). | `nh api share on` |
| `nh log set lvl 1-5` | Nastaví úroveň logování v settings UI (1=Error, 2=Warn, 3=Info, 4=Debug, 5=Verbose). | `nh log set lvl 4` |

## 🔧 Diagnostické nástroje

### nethunter-log

Python skript pro barevné formátované zobrazení logcat záznamů aplikace bez nutnosti ADB. Od verze 4.2 je dostupný přes sjednocený CLI `nh log` (případně starý alias `nethunter-log`).

```bash
# Výchozí: posledních 100 řádků
nh log
nethunter-log

# Posledních 50 řádků
nh log 50
nh log -n 50

# Filtrování podle vzoru (case-insensitive)
nh log -g "TlsMitm"
nh log -n 200 -g "LocalApiServer"

# Nastavení úrovně logování (sync s UI)
nh log set lvl 1   # Error
nh log set lvl 2   # Warn
nh log set lvl 3   # Info (výchozí)
nh log set lvl 4   # Debug
nh log set lvl 5   # Verbose
```

Barevné schéma podle log úrovně:
- **V** (Verbose): šedá / tlumená
- **D** (Debug): modrá
- **I** (Info): zelená
- **W** (Warn): žlutá
- **E/F** (Error/Fatal): červená tučná

Automatické zvýraznění klíčových slov: `error`/`denied`/`fail` = červeně, `success`/`established` = zeleně.

HTTP API endpoint: `GET /app/logs?limit=N`

## 🛡️ Správa AdGuard VPN Firewallu

Tyto skripty umožňují plnou kontrolu nad zabudovaným prémiovým filtrovacím strojem. Všechny jsou dostupné jak přes sjednocený CLI `nh vpn`, tak přes staré aliasy (symlinky).

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nh vpn on` | Zapne globální VPN / NAT. | `nh vpn on` |
| `nh vpn off` | Vypne globální VPN / NAT. | `nh vpn off` |
| `nh vpn status` | Vrátí stav VPN (running/stopped) + uptime + bytes. | `nh vpn status` |
| `nh vpn mitm on|off` | Zapne/vypne TLS MITM rozhraní. | `nh vpn mitm on` |
| `nh vpn mitm status` | Stav MITM rozhraní + aktivní session. | `nh vpn mitm status` |
| `nh vpn mitm ca` | Zobrazí/exportuje Root CA certifikát. | `nh vpn mitm ca > /tmp/ca.crt` |
| `nh vpn logs [json]` | Formátovaný výpis dešifrovaného MITM provozu. | `nh vpn logs` |
| `nh vpn bypass <cmd>` | Spustí konkrétní příkaz tak, že úplně obejde VPN zachytávání. | `nh vpn bypass curl ipinfo.io` |
| `nh vpn ignore on|off` | Přepne ignorování VPN pro aktuální terminálovou relaci. | `nh vpn ignore on` |
| `nh firewall block|unblock <ip>` | Přidá/odebere IP z blocklistu firewallu. | `nh firewall block 1.2.3.4` |

## 🔍 TLS MITM Inspection

TLS MITM (Man-in-the-Middle) umožňuje plně dešifrovat HTTPS a další TLS provoz přímo ve VPN tunelu. Proxy provádí TLS handshake jak s klientem, tak se vzdáleným serverem a transparentně přeposílá plaintext.

### Konfigurace MITM v runtime

- MITM CA cert: `assets/certs/mitm-ca.crt`
- MITM CA privátní klíč: `assets/certs/mitm-ca.p12`
- Alias v PKCS12: `nethunter_mitm_ca`

### 🔐 Instalace Root CA certifikátu

Pro správné fungování MITM dešifrování musí být Root CA certifikát nainstalován.

#### Získání certifikátu

```bash
# Zobrazit certifikát v terminálu
nh vpn mitm ca

# Uložit do souboru
nh vpn mitm ca > /tmp/nethunter-ca.crt

# Nebo přímo přes HTTP API
curl -s http://127.0.0.1:1337/vpn/mitm/ca > /tmp/nethunter-ca.crt
```

#### Instalace do Kali/PRoot trust store

```bash
nh vpn mitm ca > /usr/local/share/ca-certificates/nethunter-mitm.crt
update-ca-certificates
```

#### Instalace do Androidu

1. Uložit certifikát: `nh vpn mitm ca > /sdcard/nethunter-ca.crt`
2. Na telefonu: **Nastavení → Zabezpečení → Šifrování a přihlašovací údaje → Instalovat certifikát → Certifikát CA**
3. Vybrat soubor `nethunter-ca.crt` ze storage
4. Potvrdit instalaci (systém vyžádá PIN/otisk prstu)

> **Omezení:** Od Androidu 7.0+ aplikace standardně nedůvěřují uživatelským certifikátům. Pro dešifrování provozu ostatních appek je nutný root a přesun CA do systémového trust store (`/system/etc/security/cacerts/`). Aplikace s certificate pinning (banky, Google, Signal, WhatsApp) odmítnou MITM spojení i s nainstalovaným CA.

### CLI příkazy (`nh vpn mitm`)

```bash
# Zapnutí MITM rozhraní
nh vpn mitm on

# Vypnutí MITM rozhraní
nh vpn mitm off

# Stav MITM rozhraní + aktivní session
nh vpn mitm status

# Stáhnout/zobrazit Root CA certifikát
nh vpn mitm ca

# Uložit Root CA certifikát do souboru pro instalaci
nh vpn mitm ca > /tmp/nethunter-ca.crt

# Formátovaný dešifrovaný provoz
nh vpn logs

# JSON výstup dešifrovaného provozu
nh vpn logs json
```

### HTTP API (port 1337)

```http
POST /vpn/mitm
Body: on|off

GET /vpn/mitm
{"mitm":"on","active_sessions":2,"sessions":[{"port":54321,"snippet":"[CLIENT->SERVER] GET / ..."}]}

GET /vpn/mitm/ca
(vrátí PEM certifikát s Content-Type: application/x-x509-ca-cert)

GET /vpn/mitm/logs
GET /vpn/mitm/logs?format=json
```

### Zobrazení v terminálové relaci

Po zapnutí MITM se průběžně ukládá dešifrovaný provoz do snippet bufferu. Využijte `nh vpn logs` pro čitelné zobrazení.

### Klíčové třídy

| Třída | Role |
| :--- | :--- |
| `TlsClientHelloParser` | Parser TLS Client Hello zprávy, detekce TLS a extrakce SNI |
| `TlsMitmEngine` | Singleton, spravuje aktivní MITM session (`TlsMitmSession`) |
| `TlsMitmSession` | Jedna MITM relace — naváže spojení ke vzdálenému serveru, provede TLS handshake, podepíše certifikát a proxy plaintext |
| `RootCaInstaller` | Načte MITM CA, podepíše server certifikát, vytvoří `SSLContext` s forged certem |
| `MitmCertSigner` | BouncyCastle podepisování listových certifikátů |
| `VpnNatEngine.kt` | Hlavní NAT engine, detekuje TLS Client Hello v `handleTcpPacket` a předává payload do `TlsMitmEngine` |
| `VpnSecurityTab.kt` | UI záložka pro zobrazení MITM provozu (žlutý indikátor `TLS MITM INTERCEPT` + karta `LIVE DECRYPTED TLS TRAFFIC`) |
| `VpnSettingsTab.kt` | Přepínač `TLS MITM Inspection` v Nastavení |
| `LocalApiServer.kt` | Endpointy `/vpn/mitm`, `/vpn/mitm/ca` a `/vpn/mitm/logs` pro vzdálené ovládání |

## 🧠 AI Mozek VPN (Inference Engine)

NetHunter AI Operator obsahuje lokální AI model pro analýzu síťového provozu, který klasifikuje pakety a detekuje anomálie.

| Příkaz | Popis |
| :--- | :--- |
| `nh agent start` | Spustí na pozadí démona, který monitoruje spojení a upozorňuje na rizika (vyskakovací Toasty při detekci anomálie). |
| `nh agent chat` | Otevře konzoli lokálního AI experta pro analýzu síťových dat. |
| `nh agent status` | Zobrazí stav agenta (port 13338). |
| `nh agent analyze` | Spustí jednorázovou analýzu aktuálního provozu. |

## 🎙️ Hlasový Asistent

Aplikace funguje také jako plnohodnotný hlasový asistent integrovaný do systému Android.
Pro jeho správnou funkci je nutné provést následující kroky:

1. **Nastavení API klíče:** V nastavení aplikace vložte platný API klíč vámi vybraného poskytovatele (OpenAI, Anthropic atd.).
2. **Výchozí asistent:** V nastavení samotného Androidu (Aplikace -> Výchozí aplikace -> Digitální asistent) nastavte NetHunter AI Operator jako výchozího asistenta.
3. **Oprávnění mikrofonu:** Ujistěte se, že má aplikace povoleno oprávnění přistupovat k mikrofonu.

## 🔌 USB Host Mode — Ovládání připojených zařízení

Od verze 4.3 je k dispozici plná podpora **USB Host (OTG)** přes Android `UsbManager` API.
Připojená zařízení (např. druhá deska, USB flashdisk, sériový adaptér) jsou dostupná přes API bridge.

### CLI příkazy (`nh usb`)

| Příkaz | Popis | Příklad použití |
| :--- | :--- | :--- |
| `nh usb list` | Zobrazí všechna připojená USB zařízení (VID:PID, rozhraní, endpointy) | `nh usb list` |
| `nh usb permission <device>` | Vyžádá oprávnění pro přístup k zařízení (Android dialog) | `nh usb permission /dev/bus/usb/001/002` |
| `nh usb claim <device> [iface]` | Claimuje rozhraní (výchozí 0) a otevře spojení | `nh usb claim /dev/bus/usb/001/002 0` |
| `nh usb release <device>` | Uvolní rozhraní a zavře spojení | `nh usb release /dev/bus/usb/001/002` |
| `nh usb send <device> <file>` | Pošle binární soubor přes první OUT bulk endpoint | `nh usb send /dev/bus/usb/001/002 exploit.bin` |
| `nh usb bulk <device> <ep> [file]` | Bulk transfer na konkrétní endpoint (IN čte, OUT zapisuje) | `nh usb bulk /dev/bus/usb/001/002 2 data.bin` |
| `nh usb control <device> [req] [val] [idx] [file]` | Control transfer na endpoint 0 | `nh usb control /dev/bus/usb/001/002 64 0 0 config.bin` |
| `nh usb raw <device> <ep> <in\|out> [file]` | Raw binary bulk transfer (bez Base64/JSON, keep-alive) | `nh usb raw /dev/bus/usb/001/002 2 out exploit.bin` |
| `nh usb stream <device> <in_ep> <out_ep>` | Persistentní BROM/EDL streaming session | `nh usb stream /dev/bus/usb/001/002 1 2` |

### 🚀 USB BROM/EDL Optimalizace (v4.3+)

Pro časově kritické USB protokoly (Qualcomm BROM/EDL, bootloader flashing) byly přidány dva optimalizované endpointy, které eliminují HTTP/JSON/Base64 režii:

#### `/usb/raw_transfer` — Raw binary bulk transfer

```bash
# OUT: pošli data na zařízení
curl -X POST -H 'X-USB-Device: /dev/bus/usb/001/002' \
     -H 'X-USB-Endpoint: 2' --data-binary @programmer.bin \
     http://127.0.0.1:1337/usb/raw_transfer
# Response: 4-byte big-endian transferred count

# IN: přečti data ze zařízení (prázdné body)
curl -X POST -H 'X-USB-Device: /dev/bus/usb/001/002' \
     -H 'X-USB-Endpoint: 1' -H 'X-USB-Direction: IN' \
     http://127.0.0.1:1337/usb/raw_transfer -o response.bin
```

Parametry se předávají v HTTP hlavičkách. Response je čistě binární. Keep-alive pro vícenásobné transfery.

#### `/usb/stream` — Persistentní BROM/EDL streaming

Po HTTP handshake přepne na raw binární frame protokol:

```
0x01 OUT: [1B cmd][3B rezerva][4B délka][payload...] → [4B zapsáno]
0x02 IN:  [1B cmd][3B rezerva][4B max_read]          → [4B přečteno][data...]
0xFF CLOSE
```

```bash
# Z PRoot guest (bash TCP socket)
exec 3<>/dev/tcp/127.0.0.1/1337
echo -e 'POST /usb/stream HTTP/1.1\r\nContent-Length: 50\r\n\r\n{"device_name":"/dev/bus/usb/001/002","endpoint_in":1,"endpoint_out":2}' >&3
head -1 <&3
# Raw binární frames:
printf '\\x01\\x00\\x00\\x00\\x00\\x00\\x10\\x00' >&3
dd if=/dev/zero bs=4096 count=1 >&3
dd bs=4 count=1 <&3 2>/dev/null | od -An -tu4
printf '\\xff' >&3; exec 3>&-
```

**64KB buffer:** IN transfery používají 64KB buffer (oproti původním 4KB).

### HTTP API (port 1337)

```http
GET /usb/devices                     → seznam zařízení (JSON array)
POST /usb/permission                 → vyžádat oprávnění (body: device_name)
POST /usb/claim                      → claim rozhraní (JSON: device_name, interface_id)
POST /usb/release                    → uvolnit rozhraní (JSON: device_name)
POST /usb/bulk_transfer              → bulk transfer (JSON: device_name, endpoint, data_base64, timeout, direction)
POST /usb/control_transfer           → control transfer (JSON: device_name, request_type, request, value, index, data_base64)
POST /usb/send                       → raw send (JSON: device_name, data_base64)
POST /usb/raw_transfer               → raw binary bulk transfer (hlavičky X-USB-*, raw body)
POST /usb/stream                     → persistentní BROM/EDL streaming (binární frame protokol)
POST /usb/endpoint_info              → endpoint metadata (JSON: device_name, endpoint)
```

### Příklad poslání exploitu

```bash
# 1. Zjisti zařízení
nh usb list

# 2. Vyžádej oprávnění
nh usb permission "/dev/bus/usb/001/002"

# 3. Claimni rozhraní
nh usb claim "/dev/bus/usb/001/002" 0

# 4. Pošli binární data
nh usb send "/dev/bus/usb/001/002" exploit.bin
```

> **Poznámka:** USB Host vyžaduje, aby první telefon podporoval OTG. Druhé zařízení se musí tvářit jako USB device (gadget režim) - jinak ho `UsbManager` neuvidí.

## 🌐 Přímé HTTP API Volání

Všechny nástroje výše používají pod kapotou HTTP volání na localhost. Můžete je používat i přímo pomocí `curl`:

* **Kontrola VPN stavu:** `curl -s http://127.0.0.1:1337/vpn`
* **Zapnutí VPN:** `curl -s -X POST http://127.0.0.1:1337/vpn/start`
* **Vypnutí VPN:** `curl -s -X POST http://127.0.0.1:1337/vpn/stop`
* **Stažení Root CA:** `curl -s http://127.0.0.1:1337/vpn/mitm/ca > ca.crt`
* **Logcat záznamy:** `curl -s http://127.0.0.1:1337/app/logs?limit=100`
* **USB zařízení:** `curl -s http://127.0.0.1:1337/usb/devices`
* **USB poslat data:** `curl -s -X POST -H "Content-Type: application/json" -d '{"device_name":"/dev/bus/usb/001/002","data_base64":"$(base64 -w0 exploit.bin)"}' http://127.0.0.1:1337/usb/send`

## ⚡ Shizuku Integration — Privilege Escalation

Shizuku umožňuje spouštět příkazy s vyššími právy (root/shell UID) přímo z PRoot terminálu, bez nutnosti rootovat zařízení.

### Services Panel v Terminálu

V horní liště terminálu (vedle `🐉 KALI`) je tlačítko `▼`, které rozbalí ovládací panel služeb:

| Služba | Status | Akce |
|---|---|---|
| `⚡ SHIZU` | `●` běží / `○` zastaven | START / STOP / SETUP |
| `[code] CODE` | `●` běží / `○` zastaven | START / STOP / OPEN :8443 |
| `🔥 PHOENIX` | `○` vždy (není health check) | CONFIGURE |

### CLI příkaz `shizuku`

Automaticky nasazen do `/usr/local/bin/shizuku`:

```bash
# Spuštění příkazu s vyššími právy
shizuku -c "pm list packages"
shizuku -c "settings put global airplane_mode 1"
shizuku -c "appops set com.twitter POST_NOTIFICATIONS deny"
shizuku -c "svc wifi disable"
shizuku -c "dumpsys battery set level 15"

# Interaktivní shell
shizuku
```

### Spuštění Shizuku serveru

Aplikace automaticky zkouší:
1. Existující Shizuku server (z nainstalované Shizuku app)
2. `su -c "libshizuku.so --apk=/path/to/shizuku.apk"` (root)
3. Raw `su -c` fallback pro příkazy
4. ADB shell (pokud je dostupný)

### Status indikátory

- `●` zelená — služba běží
- `○` šedá — služba zastavena
- `su available` — root přes `su` k dispozici
- `Shizuku APK ready` — Shizuku app je nainstalována

## </> Editor (code-server / VS Code v prohlížeči)

Editor provozuje code-server (VS Code jádro) uvnitř PRoot guestu na `127.0.0.1:8443`.

### CLI (uvnitř guestu)

| Příkaz | Popis |
|--------|-------|
| `code-server-ctl start` | Spustí code-server na pozadí |
| `code-server-ctl stop` | Zastaví code-server |
| `code-server-ctl status` | JSON stav: running/stopped/port_busy |
| `code-server-ctl password` | Zobrazí heslo |
| `code-server-ctl install` | Nainstaluje code-server pokud chybí |
| `code-server-ctl info` | Konfigurace (port, workspace, cesty) |
| `code-server-ctl log` | Posledních 50 řádků logu |

### HTTP API (port 1337)

Všechny endpointy pod `/editor/*` vyžadují Bearer token.
`/editor/password` je navíc omezen na localhost (nesmí uniknout při `share_local_api=on`).

| Endpoint | Metoda | Popis |
|----------|--------|-------|
| `/editor/start` | POST | Spustí code-server |
| `/editor/stop` | POST | Zastaví code-server |
| `/editor/status` | GET | Stav (`running`/`stopped`/`port_busy`) |
| `/editor/password` | GET | Heslo (JSON) |
| `/editor/info` | GET | Bind, port, workspace, cesty |

### Bezpečnostní pravidla (analogie s VPN/MITM)

1. **Bind na `127.0.0.1`** — nikdy `0.0.0.0`, natvrdo v config.yaml
2. **Auth password** — vždy zapnutý, heslo generované náhodně, uložené v `config.yaml` (chmod 600)
3. **Heslo není v `ps aux`** — ukládá se do configu, ne jako argument příkazové řádky (poučení z C1 security auditu)
4. **/editor/password** — localhost-only i při `share_local_api=on`

### Perzistence

- Workspace: `/root/projects` (přežije restart kontejneru)
- Nastavení a rozšíření: `/root/.local/share/code-server/`
- Config: `/root/.config/code-server/config.yaml` (chmod 600)
- PID: `/tmp/code-server.pid`
- Log: `/tmp/code-server.log`

### Rozšíření (Open VSX)

Code-server defaultně používá `open-vsx.org` místo Microsoft Marketplace.
Pro chybějící rozšíření: stáhnout `.vsix` a nainstalovat ručně:
```bash
code-server --install-extension /cesta/k/souboru.vsix
```

### Omezení a TODO

- code-server musí být nainstalovaný v rootfs (automaticky přes `code-server-ctl install` nebo ručně)
- WebView nezachytává `Ctrl+` kombinace — použití Hacker Keyboard je nutné pro pokročilé operace
- Port 8443 je vázán pouze na localhost — pro přístup z jiného zařízení použít SSH tunel nebo VPN bypass proxy :13339
- Workspace je omezen na `/root/projects` — nelze otevřít adresář mimo guest bez symlinku

---
*Dokument byl automaticky vygenerován NetHunter AI Operatorem.*
