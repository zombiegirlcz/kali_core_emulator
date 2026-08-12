# nh-test — REFERENCE

## Mapování: nh příkaz → HTTP → očekávaný logcat řádek

| nh příkaz (v testu) | HTTP Request | EXECUTED řádek (hledat) | Očekávání |
|---|---|---|---|
| `nh device accessibility` | `GET /accessibility/hierarchy` | `Accessibility hierarchy dump EXECUTED (N B)` | ok, N>0 |
| `nh device tap X Y` | `POST /accessibility/tap` | `Accessibility tap(X,Y) EXECUTED ok=true` | ok=true (souřadnice na obrazovce) |
| `nh device click 'text'` | `POST /accessibility/click` | `Accessibility click EXECUTED ok=true` | ok=false → text není na scéně |
| `nh device longclick 'text'` | `POST /accessibility/longclick` | `Accessibility longclick EXECUTED ok=…` | totéž co click |
| `nh device swipe x1 y1 x2 y2 ms` | `POST /accessibility/swipe` | `Accessibility swipe EXECUTED ok=true` | ok=true |
| `nh device text 't'` | `POST /accessibility/text` | `Accessibility text-input EXECUTED ok=…` | ok=false bez fokusu/textarea |
| `nh device scroll fwd` | `POST /accessibility/scroll` | `Accessibility scroll EXECUTED ok=…` | ok=false, není-li co scrollovat |
| `nh device global recents` | `POST /accessibility/global` | `Accessibility global(recents) EXECUTED ok=true` | ok=true |
| `nh device admin status` | `GET /device/admin` | `DeviceAdmin status EXECUTED active=true` | active=true = ADMIN aktivní |
| `nh device battery-optimize status` | `GET /battery/optimize` | `Battery-optimize READ EXECUTED ignored=true` | ignored=true = optimalizace vypnutá |
| `nh system battery` | `GET /battery` | `Battery EXECUTED: level=…% status=…` | reálná data (level 0-100) |
| `nh system volume` | `GET /volume` | `Volume READ EXECUTED: music=…` | 0-150 |
| `nh system torch on` | `POST /torch` | `Torch EXECUTED: on (camera=N)` | on/off |
| `nh system vibrate 100` | `POST /vibrate` | `Vibrate EXECUTED: 100ms` | ms sedí |
| `nh system toast 'x'` | `POST /toast` | `Toast EXECUTED: "x"` | přítomno |
| `nh system clipboard read` | — | (klipboard je read-only endpoint; žádný EXECUTED) | žádný Request = OK, viz pozn. |
| `nh system notification 'x'` | `POST /notification` | `Notification POSTED: title="NetHunter" \| x` | POSTED |
| `nh system speech 'x'` | `GET /voice_input` | `Speech error: Insufficient permissions (9)` | očekáván fail bez mic permission |

## Interpretace ok=true / ok=false

- **ok=true** = akce proběhla na hostiteli (real tap, real swipe, real toast…).
- **ok=false** = požadavek dorazil (Request je v logu), ale akce selhala:
  - `click/longclick` — hledaný text není v accessibility hierarchii (použij text z `nh device accessibility`).
  - `text` — není aktivní textové pole.
  - `scroll` — nic scrollovatelného.
- To je **korektní chování**, ne bug. Očekáváný-fail se nezaměňuje za chybu testu.

## Souhrnná čísla (jak číst)

- `Request zachycen: N` — kolik requestů host app přijala. ~18 = batch proběhl celý.
- `EXECUTED (ok): N` — z toho vykonáno. High je dobrý.
- `chyby/failed: N` — error/denied/Insufficient řádky. `speech` + případné SELinux `avc: denied` patří sem.

## Kam jít při reálné chybě (Request je, ale EXECUTED chybí)

1. `nethunter-log -g LocalApiServer` — živý logcat app.
2. Endpoint: `curl -s http://127.0.0.1:1337/app/logs?limit=100` (host logcat bez adb).
3. Hledej `Request: <path>` → další řádek má být `EXECUTED`/`error`; není-li → handler v `LocalApiServer.kt` neskončil (exception) → dívej se na výjimku v logu.
4. Oprav → znovu `zsh tools/nh_test.sh`.

## Poznámky

- `nh system clipboard read` píše „Usage" (get|set) — test ho používá záměrně jako CLI-syntax check; žádný Request je očekáván.
- SELinux `avc: granted/denied` řádky v logu jsou normální (exec z app data dir), nejsou to app chyby.
- Soubor `tools/nh_test.sh` je jediný zdroj pravdy pro dávku příkazů — skill jen čte stejný proces.
