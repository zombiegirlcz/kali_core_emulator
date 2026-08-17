# Plan: `assistent_bridge` — daemon most mezi KaliCore PRoot a Kali AI Assistant

**Audience:** AI agent provádějící implementaci (Kotlin / Android API + CLI assets)
**Repo:** `~/kali_core_emulator` (hlavní app) + dopad na `~/repos/kali_ai_assistant` (klient)
**Cíl:** Bezpečné, atomické a obousměrné propojení chat agenta s rootfs v PRootu — pro práci se soubory, spouštění nástrojů, perzistenci projektů a stahování z GitHubu.

---

## 1. Proč

`Kali AI Assistant` potřebuje pro každý projekt (workspace):
- **číst a zapisovat soubory** v `/root/<projekt>` v PRootu (nejen přes `/proot/exec` ad-hoc),
- **vědět, jaké nástroje v daném distru jsou k dispozici** (verze, cesty, capability flags — `nmap --version`, `sqlmap --version`, dostupnost `python3`, `git`, `pipx` atd.),
- **stahovat zdrojáky z GitHubu** přímo do projektu (`/proot/exec git clone …`),
- **spouštět dlouhé joby** (tool běží > 30 s) bez timeoutu chatu — async s progressem,
- **sdílet `~/share` a tmp soubory** mezi app a guestem bez copy-paste.

Současné API (`POST /proot/exec`, `GET /api/share`) je dobrý základ, ale chybí mu discovery, file metadata, project lifecycle a progress streaming.

---

## 2. Rozhraní — nové endpointy v `LocalApiServer`

Všechny endpointy jen **pro lokální** spojení (127.0.0.1 / Unix domain socket). Auth: stávající `localAuthToken`.

### 2.1 Discovery (GET, idempotentní)

| Method | Path | Vstup | Výstup |
|--------|------|-------|--------|
| GET | `/bridge/v1/info` | — | JSON `{ version, distro, rootfsPath, arch, hostname, uptimeMs, capabilities[] }` |
| GET | `/bridge/v1/capabilities` | — | JSON `[{ name, version, path, flags: ["network","gpu","root","cve"] }, …]` |
| GET | `/bridge/v1/projects` | — | JSON `[{ id, name, workingDir, systemPrompt, allowedTools[], env{}, createdAt, updatedAt }]` |
| GET | `/bridge/v1/projects/{id}` | path param | JSON (jako výše) nebo `404 { error }` |

`capabilities` se získávají jednou při startu (cache 5 min) skriptem `nh capabilities` — viz §5.

### 2.2 Filesystem (atomické, scoped na projekt)

| Method | Path | Vstup | Výstup |
|--------|------|-------|--------|
| GET | `/bridge/v1/fs/{projectId}/list` | `?path=` | JSON `[{ name, type: file\|dir, size, mode, mtime, mime }]` |
| GET | `/bridge/v1/fs/{projectId}/read` | `?path=` (≤ 5 MB) | buď `{ contentBase64, mime }`, nebo `400 too_large` s instrukcí `attach_to_chat` |
| POST | `/bridge/v1/fs/{projectId}/write` | `{ path, contentBase64, mime, mode? }` | `{ ok, mtime }` |
| DELETE | `/bridge/v1/fs/{projectId}/delete` | `{ path }` | `{ ok }` |
| POST | `/bridge/v1/fs/{projectId}/mkdir` | `{ path }` | `{ ok }` |

**Path traversal guard:** každý `path` se normalizuje a musí zůstat uvnitř `workingDir` projektu; `..` nebo absolutní cesty → `403 forbidden`.

**Atomic write:** write se provede přes `tmp + fsync + rename` (ne atomic na většině FS Androidu, ale simulujeme: write do `*.tmp`, fsync, `rename`, smazat původní). Chrání guest FS před poškozením při přerušení.

### 2.3 Projects (CRUD)

| Method | Path | Vstup | Výstup |
|--------|------|-------|--------|
| POST | `/bridge/v1/projects` | `{ name, workingDir, systemPrompt?, allowedTools?, env? }` | `{ id, name, … }` — vytvoří adresář, uloží metadata do `~/.nh/projects.json` |
| PATCH | `/bridge/v1/projects/{id}` | `{ systemPrompt?, allowedTools?, env? }` | `{ id, … }` |
| DELETE | `/bridge/v1/projects/{id}` | — | `{ ok }` — smaže metadata; soubory jen pokud `?purge=1` |
| POST | `/bridge/v1/projects/{id}/activate` | — | `{ ok }` — nastaví tento projekt jako aktivní (soubor `~/.nh/active_project`) |

Soubor projektů: `~/.nh/projects.json` (JSON pole). Práva `0600`. Migrace: chybějící soubor = prázdné pole.

### 2.4 Exec (sync + async)

| Method | Path | Vstup | Výstup |
|--------|------|-------|--------|
| POST | `/bridge/v1/exec` | `{ projectId, command, cwd?, env?, timeoutMs? (default 120_000, max 3_600_000) }` | `{ jobId, status:"running" }` nebo sync `{ stdout, stderr, exitCode, durationMs }` pokud `timeoutMs <= 2000` |
| GET | `/bridge/v1/jobs/{jobId}` | — | `{ status, stdout, stderr, exitCode, durationMs }` |
| GET | `/bridge/v1/jobs/{jobId}/stream` | — | NDJSON `{ts,stream:"stdout"|"stderr",chunk}…` + finální `{status,exitCode}` |
| DELETE | `/bridge/v1/jobs/{jobId}` | — | `{ ok }` (SIGTERM → po 5 s SIGKILL) |

Job runner je v Kotlinu jednoduchý `ProcessBuilder` + `BufferedReader` v `Dispatchers.IO`. Výstup se posílá do `Channel<String>`; `GET /jobs/{id}/stream` čte z kanálu a posílá po řádcích (NDJSON). Maximálně 16 paralelních jobů.

### 2.5 GitHub download (do projektu)

| Method | Path | Vstup | Výstup |
|--------|------|-------|--------|
| POST | `/bridge/v1/github/clone` | `{ projectId, url, ref? (branch/tag/sha), targetDir?, shallow?: true (default) }` | `{ jobId, status:"running" }` — spustí `git clone --depth 1` (nebo full při `shallow:false`) v `workingDir/targetDir` |
| GET | `/bridge/v1/github/status` | — | `[{ cloneId, projectId, url, status, startedAt, finishedAt, error? }]` (posledních 20) |

`url` musí být `https://github.com/<owner>/<repo>` nebo `git@github.com:…`. SSH klíč: čte se z `~/.ssh/id_ed25519` (generuje `nh` při prvním spuštění, nabídne uložení do `keychain`). Token fallback: `x-github-token` header → dočasně uloží do `~/.netrc` (práva 0600), smaže po klonu.

---

## 3. Lifecycle projektu

```
┌────────────┐  POST /projects   ┌──────────────┐
│ (chat UI)  │ ────────────────► │ projects.json│
│            │ ◄──── { id } ──── │ + dir create │
└────────────┘                  └──────────────┘
        │ activate
        ▼
┌─────────────────────────┐
│ ~/.nh/active_project    │ (symlink? obyč. soubor s id)
└─────────────────────────┘
        │ chat start
        ▼
┌─────────────────────────┐
│ ChatViewModel injectne: │
│ - systemPrompt          │
│ - workingDir = /root/x  │
│ - allowedTools filter   │
└─────────────────────────┘
```

`ChatViewModel` zavolá `loadActiveProject()` → `GET /projects/{id}`. Při startu chatu agent dostane v system promptu `workingDir` a v tools whitelistu jen povolené capabilities.

---

## 4. Změny v `~/repos/kali_ai_assistant`

### 4.1 Nové třídy

```
domain/model/ProjectConfig.kt        @Serializable data class
data/api/KaliBridgeClient.kt         + ProotEnvironment, + projects API
data/api/ProjectApi.kt               wrapper pro /projects/* + /github/*
ui/projects/ProjectsScreen.kt        CRUD + activate
ui/projects/ProjectInfoSheet.kt      modal s detaily
ui/chat/ChatScreen.kt                + project pill, info button, file attach
domain/model/ChatMessage.kt           + attachments: List<Attachment>
data/files/Attachment.kt              data class (mime, name, size, base64)
data/files/AttachmentPicker.kt        ACTION_GET_CONTENT + ACTION_PICK_IMAGES
data/api/AiProviderClient.kt          attachments → image_url / file refs
```

### 4.2 Attachment flow

- UI: v `ChatScreen` InputBar přibude tlačítko 📎 (paperclip). Otevře `ACTION_OPEN_DOCUMENT` s mime filtrem `*/*`. Vyberu obrázek / soubor.
- `AttachmentPicker`:
  - čte `ContentResolver.openInputStream(uri)` → `ByteArray` (limit 20 MB jinak `Result.failure(TooLarge)`);
  - pokud `> 20 MB` → UI zobrazí chybu: *„Soubor je větší než 20 MB. Prosím vlož ho do rootfs projektu (např. `~/share/` nebo přes `nh push …`) a v chatu na něj odkazuj absolutní cestou."*
  - pokud OK: vytvoří `Attachment(mime, name, size, base64)` + thumb (pro obrázky).
- `ChatMessage.attachments` se uloží do historie (jen metadata — base64 se znovu pošle pokaždé, ale `size`/`name`/`mime` perzistují).
- `AiProviderClient.buildOpenAiBody`: detekuje `image/*` → OpenAI `image_url` formát; jinak → `file` ref nebo přeskočí (server rozhodne).

### 4.3 Project flow

- `SecureKeyStore` přidá `setActiveProjectId`, `getActiveProjectId`, `getProjects`, `saveProject`, `deleteProject` — JSON v `EncryptedSharedPreferences` pod klíčem `projects`.
- `ChatViewModel.loadActiveProject()` při `init` i po `onProviderChanged` (kdyby se projekt změnil v Settings).
- TopBar `ChatScreen`: pod `providerName · modelId` přibude `•  ${projectName} ⓘ`. `�` otevře `ProjectInfoSheet`.

### 4.4 GitHub download

- V `ProjectsScreen` tlačítko „Import from GitHub" → dialog (URL, ref, target dir).
- Zavolá `POST /bridge/v1/github/clone` → dostane `jobId`; polling `/jobs/{id}` každé 2 s; UI ukáže progress + tlačítko Cancel (`DELETE /jobs/{id}`).
- Po dokončení refresh `GET /projects/{id}` a `GET /fs/{projectId}/list` → nově naklonované soubory se objeví v chat agentovi jako system context „Projekt obsahuje nové soubory: …".

---

## 5. CLI (`nh` skript) — doplnění

```
nh capabilities                       # vypíše verze nainstalovaných nástrojů → JSON do /tmp
nh project new <name> [dir]           # vytvoří projekt (volá POST /projects)
nh project list                       # GET /projects
nh project use <id|name>              # nastaví aktivní
nh project info                       # GET /projects/{active}
nh project rm <id> [--purge]          # DELETE
nh project import <github-url>        # POST /github/clone
nh project ls [path]                  # GET /fs/{id}/list?path=
nh project cat <path>                 # GET /fs/{id}/read?path=
nh project push <local-path>          # kopíruje do projektu (přes /api/share + rename)
nh project pull <remote-path>         # opačně
```

`nh capabilities` parsne výstup těchto utilit (best-effort, pokud chybí, přeskočí):
`nmap sqlmap hydra john hashcat metasploit msfconsole nikto gobuster ffuf feroxbuster wfuzz dirb sqlmap burpsuite netcat ncat socat python3 git curl wget pipx node npm yarn go rustc cargo make cmake gcc clang aircrack-ng bettercap wireshark tshark tcpdump responder mitmproxy jq yq fzf ripgrep fd`

---

## 6. Bezpečnost

- **Auth:** vše přes existující `localAuthToken` (128-bit, v `EncryptedSharedPreferences` Kali AI Assistant + per-request `X-Nh-Auth`).
- **Path traversal:** normalizace + kontrola uvnitř `workingDir` (viz §2.2).
- **Rate limit:** `LocalApiServer` má už globální 60 req/s; pro `/exec` a `/github/clone` zvlášť 5 req/s.
- **Audit log:** každý `/bridge/v1/exec` a `/fs/*` zapíše do `~/.nh/bridge.log` (práva 0600): `ts, projectId, method, path, exitCode`.
- **Timeout:** default 120 s, max 1 h, job killnutelný.
- **GitHub token:** nikdy se nepersistuje v klientu; jen dočasně v `~/.netrc` hostu, smaže se po klonu.
- **Velikost souborů:** server povolí max 5 MB v `read` base64; větší → 400 + instrukce (viz §4.2).
- **Projekty:** `~/.nh/projects.json` a `~/.nh/bridge.log` jsou mimo `filesDir` (v `$HOME` neboli `~`), takže přežijí wipe aplikace; klient je čte přes API, nikdy přímo.

---

## 7. Verifikace (TDD pořadí)

1. **Unit:** `PathGuard` — 10 testů (`..`, absolutní, symlink mimo workingDir, unicode, prázdné).
2. **Unit:** `ProjectsJson` — roundtrip 50 projektů, parsování malformed.
3. **Unit:** `AtomicWrite` — simulated interrupt, kontrola že tmp nezůstane.
4. **Integration:** `LocalApiServer` — 8 endpointů s happy + 4 error paths, každý s curl fixture.
5. **E2E (na zařízení):**
   - Vytvoř projekt → aktivuj → pošli `cat /etc/os-release` v chatu → agent odpoví s výstupem.
   - Připoj obrázek `< 20 MB` → AI ho popíše.
   - Připoj soubor `> 20 MB` → chybová hláška s instrukcí.
   - `git clone https://github.com/rapid7/metasploit-framework` do projektu → progress stream → soubory viditelné v chat agentovi.
   - Změň `systemPrompt` projektu → agent odpovídá v novém tónu.
   - Smaž projekt s `?purge=1` → workingDir pryč.

---

## 8. Rollout

- **Milník 1 (1 den):** §2.1 discovery + §2.3 projects CRUD + §6 path guard. Build `assistent_bridge-v0.1`.
- **Milník 2 (1 den):** §2.2 filesystem (read/write/list/delete/mkdir) + §5 `nh project ls/cat/push`.
- **Milník 3 (1 den):** §2.4 exec sync + async + §2.5 GitHub clone + §5 `nh project import`.
- **Milník 4 (½ dne):** §4 Kali AI Assistant integrace (ProjectsScreen, AttachmentPicker, ProjectInfoSheet).
- **Milník 5 (½ dne):** §7 E2E testy na reálném zařízení + bugfixy.

---

## 9. Otevřené otázky (vyřešit PRIMA implementací)

- **A:** Má `nh capabilities` volat `nmap --version` atd. v PRootu, nebo v hostitelském Android shellu? → **V PRootu**, jinak výstup nekoresponduje s tím, co agent uvidí.
- **B:** Jobs: kde se persistují po restartu? → **nepersistují**, jen in-memory; chat dostane `job_lost` a může znovu spustit. Tím se vyhneme zombie procesům.
- **C:** Může klient číst `~/.nh/projects.json` přímo přes SAF? → **ne**, jen přes API (single source of truth, audit log).
- **D:** GitHub rate limit (60/h bez tokenu)? → wrapper zobrazí chybu s instrukcí „přidej token v Settings".

---

## 10. Rizika

- **R1:** `nh push` velkých souborů přes `/api/share` → pomalé. Mitigation: použít `POST /fs/{id}/write` s base64 pro ≤ 20 MB; pro větší doporučit `adb push` nebo SCP (out of scope).
- **R2:** Agent může omylem smazat projekt (`DELETE /projects/{id}?purge=1`). Mitigation: default `purge=0` (jen metadata), purge vyžaduje potvrzení v UI.
- **R3:** Dlouhý `git clone` (200 MB repo) běží hodiny → velký `bridge.log`. Mitigation: loguj jen metadata (url, exit, durationMs), ne výstup.
- **R4:** Konflikty jmen projektů → ID se generuje jako `proj-${timestamp}-${rand4}`, jméno je jen label (může být duplicitní).
