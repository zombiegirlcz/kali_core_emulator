# VPN AI Brain — Kompletní implementační plán
## Smart Traffic Decision System + Phoenix Telemetrie

> Tento dokument slouží jako jediný zdroj pravdy pro implementaci. Lze ho vložit
> jako prompt/kontext pro AI coding agenta (picoding) při postupné realizaci.

---

## 0. Kontext projektu

- **Aplikace:** NetHunter AI Operator (`com.linux_core`), Android, PRoot Kali/Parrot
- **Existující komponenty využité tímto plánem:**
  - `AIBrain.kt` — ONNX klasifikátor síťových flow (size, entropie, timing)
  - `TlsMitmEngine.kt` / `RootCaInstaller.createCaptureOnlySslContext()` — selektivní capture-only MITM
  - `OffensiveEngine.kt` — vzor notifikace s Allow/Deny + 30s timeout (fail-safe = Deny)
  - `LocalApiServer.kt` (port 1337) — Bearer token auth, sem přibudou nové endpointy
  - `ai-agent.py` (port 13338) — ReAct agent, sem přibudou nové tool-cally
  - Existující Phoenix instance (localhost:6006) používaná pro picoding agenta — **znovupoužít**, ne duplikovat
- **Bezpečnostní rámec:** navazuje na `SECURITY_AUDIT.md` — fail-safe defaults, žádné nové neautentizované endpointy, žádné globální MITM zapnutí bez capture-only omezení

---

## 1. Architektura (3 rozhodovací vrstvy)

```
┌─────────────────────────────────────────────────────────┐
│ VRSTVA 1: AIBrain.kt (existuje, upravit výstup)          │
│ → Klasifikace KAŽDÉHO flow → confidence 0.0–1.0           │
│ → >0.9 nebo <0.1 = rozhoduje sama, žádná eskalace          │
└─────────────────────────────────────────────────────────┘
                          │ confidence 0.1–0.9 (nejisté pásmo)
                          ▼
┌─────────────────────────────────────────────────────────┐
│ VRSTVA 2: TrafficAggregator.kt (nový, čistě mechanický)  │
│ → SQLite dedup/suppress, dávkuje flow po 60–120s oknech    │
│ → Známé adresy potichu aplikují starý verdikt, bez LLM     │
└─────────────────────────────────────────────────────────┘
                          │ jen nové/neznámé adresy, dávkově
                          ▼
┌─────────────────────────────────────────────────────────┐
│ VRSTVA 3: LLM Arbiter (rozšíření ai-agent.py)            │
│ → Kompaktní JSON vstup, tool-cally, verdikt, notifikace    │
│ → Telemetrie z KAŽDÉHO rozhodnutí → Phoenix (async, batch) │
└─────────────────────────────────────────────────────────┘
```

**Analogie:** vrátný (Vrstva 1) → vedoucí ostrahy (Vrstva 2, jen dedup a rozdělování práce) → majitel budovy (Vrstva 3, rozhoduje a učí se). Phoenix je kamerový archiv, do kterého se nahrává záznam z každého rozhodnutí majitele — ne z každého kroku vrátného.

---

## 2. SQLite schéma (operační paměť, lokální, rychlá)

```sql
-- Hlavní tabulka známých adres/domén
CREATE TABLE known_addresses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    address TEXT NOT NULL UNIQUE,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    occurrence_count INTEGER DEFAULT 1,
    avg_interval_sec INTEGER,
    avg_entropy REAL,
    typical_port INTEGER,
    verdict TEXT DEFAULT 'unknown',        -- unknown/allowed/blocked/pending_user
    verdict_source TEXT,                   -- ai_auto/user_confirmed/ai_brain
    verdict_confidence REAL,
    notified_user INTEGER DEFAULT 0,
    notes TEXT,
    trace_id TEXT                          -- FK na Phoenix span (viz sekce 5)
);

-- Denní agregace pro 24h souhrn
CREATE TABLE daily_stats (
    date TEXT PRIMARY KEY,
    total_flows INTEGER,
    new_addresses INTEGER,
    blocked_count INTEGER,
    allowed_count INTEGER,
    pending_count INTEGER,
    top_entropy_address TEXT
);

-- Krátkodobý buffer flow čekajících na dávkové zpracování
CREATE TABLE pending_flows (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    address TEXT NOT NULL,
    detected_at INTEGER NOT NULL,
    brain_confidence REAL,
    escalated_to_llm INTEGER DEFAULT 0,
    expires_at INTEGER NOT NULL             -- auto-cleanup po 5 min
);
```

**Klíčový princip:** jakmile `verdict != 'unknown'`, adresa mizí z běžného výpisu (`get_pending_flows()` ji nevrací). LLM ji vidí znovu jen přes explicitní `get_address_history(address)`.

---

## 3. Kompaktní JSON formát pro `get_pending_flows()`

```json
{
  "pending": [
    {
      "a": "185.220.101.4",
      "n": 6,
      "iv": 3600,
      "ent": 0.91,
      "p": 443,
      "sni": null,
      "b_conf": 0.55
    }
  ]
}
```

| Klíč | Význam |
|------|--------|
| `a` | adresa (IP nebo SNI) |
| `n` | počet výskytů od prvního zachycení |
| `iv` | průměrný interval mezi výskyty (s) |
| `ent` | entropie payloadu (0–1) |
| `p` | cílový port |
| `sni` | TLS SNI pokud zachyceno, jinak `null` |
| `b_conf` | confidence z `AIBrain` |

Odhad: ~25–30 tokenů/flow → dávka 10 flow ≈ 300 tokenů vstupu. Žádné absolutní timestampy v promptu (zbytečné pro rozhodnutí, zůstávají jen v SQLite).

---

## 4. Tool-call rozhraní pro LLM (rozšíření `ai-agent.py`)

| Tool | Vstup | Výstup | Kdy volat |
|------|-------|--------|-----------|
| `get_pending_flows()` | — | JSON dávka nových adres (sekce 3) | začátek cyklu |
| `get_address_history(address)` | adresa | plná historie jen pro 1 adresu | jen při potřebě detailu |
| `enable_mitm_for_flow(address, duration_sec≤60)` | adresa, doba | capture-only MITM zapnutý selektivně | jen u nejasného flow |
| `set_verdict(address, verdict, confidence, note)` | allow/block/pending_user | zápis do `known_addresses` + Phoenix span | vždy na konci rozhodování |
| `notify_user(address, question)` | text otázky | Android notifikace Allow/Deny, timeout 30s | jen když `verdict='pending_user'` |
| `summarize_24h()` | — | čte jen `daily_stats` | 1×/den, WorkManager cron |

**Symetrický formát odpovědi `set_verdict()`:**
```json
{"a": "185.220.101.4", "v": "blocked", "conf": 0.82, "note": "periodic beacon, high entropy, no SNI"}
```

---

## 5. Telemetrie → napojení na existující Phoenix (ne vlastní klon)

### Rozhodnutí a zdůvodnění

Nevytváříme vlastní observabilitu — napojujeme se na **stávající Phoenix instanci** (localhost:6006), kterou už používáš pro picoding agenta. Důvody:
- Phoenix řeší jen vizualizaci/store traces, ne rozhodovací stav (ten zůstává v SQLite jako zdroj pravdy)
- SQLite dotaz "má adresa verdikt?" musí být lokální a okamžitý — žádný network round-trip do trace serveru na hot pathu
- Jednotný přehled obou agentů (coding + VPN) v jednom Phoenix projektu, jen s odlišným `project_name`

**Analogie:** SQLite je provozní deník mistra v kapse, který kouká okamžitě. Phoenix je centrální archiv, kam se deník kopíruje dávkově — pro tebe jako architekta, ne pro běh systému samotného.

### Kdy se odesílá span

**Ne per-packet, ne z hot pathu klasifikace.** Odesílá se výhradně po `set_verdict()` — tedy jednou za rozhodnutí, ne za paket. Batch, asynchronně, mimo hlavní vlákno.

### Minimální OTLP export (bez těžkého OTel SDK)

Python (`ai-agent.py`), žádné závislosti navíc kromě `requests`/`httpx`:

```python
import time
import uuid
import json
import threading
import requests

PHOENIX_OTLP_URL = "http://localhost:6006/v1/traces"
PROJECT_NAME = "vpn_ai_brain"

def _now_ns() -> int:
    return int(time.time() * 1_000_000_000)

def send_verdict_span(
    address: str,
    pending_input: dict,
    tool_calls: list[dict],
    verdict: dict,
    trace_id: str | None = None,
) -> str:
    """
    Odešle jeden span do Phoenixu po dokončení rozhodovacího cyklu.
    Volat asynchronně (viz _fire_and_forget), aby to neblokovalo ai-agent.py.
    """
    trace_id = trace_id or uuid.uuid4().hex
    span_id = uuid.uuid4().hex[:16]
    start_ns = _now_ns()

    span = {
        "resourceSpans": [{
            "resource": {
                "attributes": [
                    {"key": "service.name", "value": {"stringValue": PROJECT_NAME}},
                ]
            },
            "scopeSpans": [{
                "spans": [{
                    "traceId": trace_id,
                    "spanId": span_id,
                    "name": f"verdict:{address}",
                    "startTimeUnixNano": str(start_ns),
                    "endTimeUnixNano": str(_now_ns()),
                    "attributes": [
                        {"key": "openinference.span.kind", "value": {"stringValue": "AGENT"}},
                        {"key": "input.value", "value": {"stringValue": json.dumps(pending_input)}},
                        {"key": "output.value", "value": {"stringValue": json.dumps(verdict)}},
                        {"key": "tool_calls", "value": {"stringValue": json.dumps(tool_calls)}},
                        {"key": "vpn.address", "value": {"stringValue": address}},
                        {"key": "vpn.verdict", "value": {"stringValue": verdict.get("v", "")}},
                        {"key": "vpn.confidence", "value": {"doubleValue": verdict.get("conf", 0.0)}},
                    ],
                }]
            }]
        }]
    }

    try:
        requests.post(PHOENIX_OTLP_URL, json=span, timeout=3)
    except Exception as e:
        # Telemetrie nesmí nikdy shodit rozhodovací smyčku
        print(f"[phoenix-export] warning: {e}")

    return trace_id


def send_verdict_span_async(*args, **kwargs):
    """Fire-and-forget wrapper, aby export nezdržoval set_verdict()."""
    threading.Thread(target=send_verdict_span, args=args, kwargs=kwargs, daemon=True).start()
```

**Napojení v `set_verdict()` tool handleru:**
```python
def set_verdict(address, verdict, confidence, note, pending_input, tool_calls):
    # 1. zápis do SQLite (known_addresses) — synchronně, je to zdroj pravdy
    trace_id = write_verdict_to_sqlite(address, verdict, confidence, note)

    # 2. telemetrie do Phoenixu — asynchronně, nikdy neblokuje
    send_verdict_span_async(
        address=address,
        pending_input=pending_input,
        tool_calls=tool_calls,
        verdict={"v": verdict, "conf": confidence, "note": note},
        trace_id=trace_id,
    )
    return {"a": address, "v": verdict, "conf": confidence, "note": note}
```

### Co je ve spanu k dispozici pro pozdější trénink

- `input.value` — přesně to, co LLM vidělo (kompaktní JSON dávka)
- `tool_calls` — sekvence volání (`enable_mitm_for_flow`, `notify_user`) s parametry
- `output.value` — finální verdikt + confidence + zdůvodnění
- `vpn.verdict` / `vpn.confidence` — filtrovatelné atributy přímo v Phoenix UI (lze třídit/exportovat jen `user_confirmed` verdikty pro fine-tuning)

---

## 6. Notifikace uživateli (vzor z `OffensiveEngine`)

```
"Adresa xyz.example.com se ozývá každou hodinu, neobvyklá entropie. Povolit?"
[Allow] [Deny] — timeout 30s → default Deny (fail-safe)
```

Odpověď uživatele přepíše `verdict_source` na `user_confirmed` — nejvyšší váha, prioritní zdroj pro budoucí fine-tuning nad `ai_auto` verdikty.

---

## 7. 24h souhrn

Samostatný job (WorkManager, 1×/den), čte **pouze** `daily_stats` — nikdy raw flow záznamy ani `known_addresses` v plném rozsahu. Výstup: krátký text pro notifikaci/log, žádné volání LLM s velkým kontextem.

---

## 8. Pořadí implementace

1. `TrafficAggregator.kt` + SQLite schéma (čistě mechanické, bez AI, testovatelné samostatně)
2. Úprava `AIBrain.kt` výstupu na confidence float místo binárního verdiktu
3. Rozšíření `ai-agent.py` o 6 tool-callů (sekce 4) + kompaktní JSON parser (sekce 3)
4. Napojení `enable_mitm_for_flow` na `createCaptureOnlySslContext()` (existující, jen nový trigger)
5. Notifikační smyčka Allow/Deny → zápis `verdict_source='user_confirmed'`
6. Phoenix export (sekce 5) — poslední krok, protože je čistě observabilita, nic nekritického na ní nezávisí
7. `vpn_memory.md` — oddělený soubor od picoding memory skillu, stejný princip (LLM čte trasování → zapisuje ponaučení)
8. Až bude dost `user_confirmed` dat v Phoenixu → export pro fine-tuning

---

## 9. Výběr modelů pro LLM Arbiter

### 9.1 Dvě role, dva různé modely

`AIBrain.kt` (Vrstva 1) zůstává tabulkový ONNX klasifikátor — netýká se ho fine-tune LLM. Fine-tune/výběr modelu se řeší jen pro **LLM Arbiter** (Vrstva 3) a nově navrhovanou **odlehčenou mezivrstvu**.

### 9.2 FunctionGemma — nová mezivrstva mezi Vrstvou 2 a 3

Google vydal **FunctionGemma** (postaven na Gemma 3 270M), model specializovaný na function calling, popsaný přímo jako "traffic controller": zvládá běžné případy on-device a složité eskaluje na větší model. To se kryje s architekturou tohoto plánu skoro 1:1:

```
FunctionGemma (270M, on-device, Termux)   →   Claude/Gemma 3 27B (cloud, dnešní Vrstva 3)
= vyřeší jednoduché/jasné verdikty            = jen nejasné případy, které
  přímo v telefonu, bez volání cloudu           FunctionGemma neumí rozhodnout
```

- Běží přes llama.cpp/Ollama v Termuxu, kvantizovaný GGUF, nízká paměťová náročnost
- LoRA fine-tune na `user_confirmed` datech z Phoenixu (trénink mimo telefon, na Colab T4 zdarma; na telefon se nasadí jen hotový kvantizovaný model)
- Efekt: méně volání na cloud model → nižší náklady i spotřeba dat/baterie

### 9.3 MET-LLM vs. TrafficLLM (Vrstva 1, volitelné vylepšení)

- **MET-LLM** — teoreticky přesně sedí na detekci malicious encrypted traffic, ale kód je zatím **jen částečně zveřejněný** (autoři slibují zbytek "brzy") — nespoléhat se na něj v produkci teď
- **TrafficLLM** — plně dostupná alternativa se stejnou myšlenkou (univerzální adaptace LLM na reprezentaci síťového toku), veřejné váhy (6B), funkční repo — použitelné jako experiment pro přesnější klasifikaci ve Vrstvě 1, **nenahrazuje** ONNX `AIBrain.kt`, jen ho může doplnit/porovnat

### 9.4 Doporučené pořadí zkoušení

1. FunctionGemma jako lokální mezivrstva — nejrychlejší reálný přínos, přímo zapadá do stávajícího návrhu
2. TrafficLLM jako paralelní experiment nad Vrstvou 1 (A/B proti `AIBrain.kt`, ne náhrada)
3. MET-LLM sledovat, až zveřejní zbytek kódu

---

## 10. Dodatek — ochrana proti "zastaralému" verdiktu (drift detekce)

### Problém, který současný návrh neřeší

Jakmile má adresa jednou `verdict != unknown`, systém ji **navždy potichu suppressuje** (sekce 2–4). To je správné pro úsporu kontextu, ale má slabinu: IP adresy a domény se v čase mění vlastníka (sdílený cloud hosting, CDN, expirované domény přebrané útočníkem). Adresa jednou správně vyhodnocená jako `allowed` může za měsíc sloužit úplně jinému, škodlivému účelu — a systém by o tom už nikdy nevěděl.

### Navrhované řešení: baseline drift check

Do `TrafficAggregator.kt` přidat lehkou kontrolu **odchylky od vlastní historie** adresy, ne jen jednorázové rozhodnutí:

```sql
ALTER TABLE known_addresses ADD COLUMN baseline_entropy REAL;
ALTER TABLE known_addresses ADD COLUMN baseline_interval_sec INTEGER;
ALTER TABLE known_addresses ADD COLUMN last_reverify_at INTEGER;
```

- Při každém výskytu už **známé** adresy se porovná aktuální `entropy`/`interval` s uloženým baseline (z doby, kdy padl verdikt)
- Pokud odchylka překročí práh (např. entropie skočí o >0.3, nebo se interval zkrátí 5×) → adresa se **i přes existující verdikt** znovu zařadí do `pending_flows` s příznakem `reason=drift`
- Toto je čistě mechanická kontrola (žádný LLM), stejně levná jako dedup logika — LLM se volá jen když drift skutečně nastane
- `last_reverify_at` navíc umožňuje i pasivní re-check (např. jednou za 30 dní i bez driftu) pro adresy s `verdict=allowed` a vysokým `occurrence_count`, jako druhou pojistku

**Analogie:** i důvěryhodný zaměstnanec se známou kartou dostane pravidelnou revizi přístupu, ne jen jednorázové schválení navždy — a pokud se najednou začne chovat nezvykle (jiná doba příchodu, jiné oddělení), ostraha si ho znovu všimne, i když kartu má platnou.

### Kam patří v pořadí implementace (návaznost na sekci 8)

Zařadit **po kroku 1** (`TrafficAggregator.kt` + SQLite), protože je to rozšíření stejné tabulky/logiky, ne samostatná fáze.

---

## 11. Bezpečnostní kontrolní seznam (návaznost na SECURITY_AUDIT.md)

- [ ] Nové tool-cally v `ai-agent.py` nesmí obcházet Bearer token auth `LocalApiServer`
- [ ] `enable_mitm_for_flow` limitovat na max 60s a jen capture-only (nikdy proxy-through globálně)
- [ ] Fail-safe default = Deny při vypršení notifikačního timeoutu
- [ ] Phoenix export nesmí nikdy blokovat ani shodit rozhodovací smyčku (viz try/except v sekci 5)
- [ ] `pending_flows` TTL cleanup, aby tabulka nerostla neomezeně
- [ ] Žádné absolutní GPS/cellinfo data v telemetrii — jen síťové atributy
- [ ] Drift re-verifikace (sekce 10) nesmí sama vytvořit nekonečnou smyčku eskalací — limitovat na 1 re-eskalaci za 24h na adresu
