# NetHunter VPN MITM & CLI Documentation

## TLS MITM Inspection

TLS MITM (Man-in-the-Middle) inspection umožňuje dešifrovat a prohlížet plný datový provoz na portu 1337 VPN API, včetně zabezpečeného HTTPS provozu.

### Jak to funguje

1. **Detekce TLS Client Hello** — parser `TlsClientHelloParser` rozpozná zahájení TLS spojení z klienta
2. **Navigace k cílovému serveru** — `TlsMitmEngine` naváže spojení k skutečnému vzdálenému serveru
3. **Podepsání certifikátu** — `RootCaInstaller` pomocí `MitmCertSigner` dynamicky přepíše server certifikát pomocí vestavěné MITM CA
4. **Vytvoření TLS proxy** — klient komunikuje s proxy, proxy komunikuje se serverem, obousměrně dešifruje provoz
5. **Ukládání dešifrovaných dat** — části plaintextu se ukládají do `TlsMitmSession` pro prohlížení v UI a CLI

### Konstanty a cesty

- MITM CA certifikát: `assets/certs/mitm-ca.crt`
- MITM CA privátní klíč: `assets/certs/mitm-ca.p12`
- Alias v PKCS12: `nethunter_mitm_ca`
- BuildConfig příznak: `ENABLE_MITM` (default `true`)

### Nastavení

Hodnota se ukládá v `SharedPreferences` pod klíčem `enable_mitm`.

#### V Compose UI (VpnSettingsTab)

```
VPN → Settings → "1. VPN CAPTURE & ROUTING OPTIONS" → TLS MITM Inspection
```

#### Z Terminálu / CLI

```bash
# Zapnutí MITM
vpn-cli mitm on

# Vypnutí MITM
vpn-cli mitm off

# Zobrazení stavu MITM
vpn-cli mitm status
```

#### HTTP API (port 1337)

```http
POST /vpn/mitm
Body: on

GET /vpn/mitm
Response: {"mitm":"on","active_sessions":2,"sessions":[{"port":54321,"snippet":"[CLIENT->SERVER] GET / ..."}]}
```

### Zobrazení dešifrovaného provozu

#### V UI (VpnSecurityTab)

```
VPN → Security → Přepni na SOCKETS
- TLS MITM spojení mají žlutý štítek "TLS MITM INTERCEPT • <SNI>"
- Pokud jsou aktivní MITM session, pod seznamem socketů se zobrazí karta "LIVE DECRYPTED TLS TRAFFIC"
```

#### V CLI

```bash
# Formátovaný textový výpis (výchozí)
vpn-cli logs

# JSON výstup
vpn-cli logs json
```

Výstup `vpn-cli logs`:
```
=== Port 54321 ===
[CLIENT->SERVER] GET / HTTP/1.1
[CLIENT->SERVER] Host: example.com
[CLIENT->SERVER] User-Agent: curl/7.88.1
[SERVER->CLIENT] HTTP/1.1 200 OK
[SERVER->CLIENT] Content-Type: text/html
```

### Klíčové třídy

| Třída | Role |
|-------|------|
| `TlsClientHelloParser` | Parser TLS Client Hello zprávy, extrakce SNI |
| `TlsMitmEngine` | Singleton, spravuje aktivní MITM session |
| `TlsMitmSession` | Jedna MITM relace — klient ↔ server přes SSLEngine |
| `RootCaInstaller` | Načte MITM CA, podepíše server certifikát, vytvoří SSLContext |
| `MitmCertSigner` | BouncyCastle podepisování certifikátů |
| `VpnNatEngine` | Hlavní NAT engine, detekuje TLS Client Hello a předává payload do `TlsMitmEngine` |
| `VpnSecurityTab` | UI záložka pro zobrazení MITM provozu |
| `VpnSettingsTab` | UI přepínač pro zapnutí/vypnutí MITM |

### HTTP API Endpointy

| Metoda | Cesta | Popis |
|--------|-------|-------|
| `POST` | `/vpn/mitm` | Zapne/vypne MITM (`body: on` nebo `off`) |
| `GET` | `/vpn/mitm` | Stav MITM + aktivní session + snippet preview |
| `GET` | `/vpn/mitm/logs` | Plný dešifrovaný provoz |
| `GET` | `/vpn/mitm/logs?format=json` | JSON formát dešifrovaného provozu |

Ověření: `Authorization: Bearer <token>` (token v `api_security` SharedPreferences).

### CLI (vpn-cli)

Skript: `app/src/main/assets/vpn-cli`

```bash
# Mitm ovládání
vpn-cli mitm on
vpn-cli mitm off
vpn-cli mitm status

# Logy
vpn-cli logs
vpn-cli logs json

# Ostatní VPN příkazy
vpn-cli status
vpn-cli start
vpn-cli stop
```

Token se čte z `/data/data/com.linux_core/shared_prefs/api_security.xml`.

## Editor (code-server / VS Code in browser)

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
