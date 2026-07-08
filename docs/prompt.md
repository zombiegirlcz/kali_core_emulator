# Brief: Accessibility API rozšíření — NetHunter AI Operator

## Kontext

`LocalApiServer.kt` (port 1337) aktuálně exponuje pouze **read-only** endpoint
`/accessibility/hierarchy`, který vrací JSON strom z
`NetHunterAccessibilityService.getScreenHierarchy()`. Chybí:

1. **`bounds`** (souřadnice) u jednotlivých uzlů — bez nich nejde spočítat, kam
   kliknout.
2. **Zápisové akce** — tap, click podle textu, long-click, swipe, vepsání
   textu, scroll, globální gesta (BACK/HOME/RECENTS/...). Bez nich AI agent
   umí obrazovku jen *číst*, ne na ní *jednat*.
3. **CLI wrappery** (`nh`) pro obojí, ve dvou výstupních režimech: lidsky
   čitelný a `--json` pro strojové zpracování AI agentem.

## ⚠️ Bezpečnostní požadavek (NEPODCEŇOVAT)

Zápisové accessibility akce (tap/click/text/swipe/global) jsou svou závažností
na úrovni **C2** (`/shell` RCE) ze `SECURITY_AUDIT.md` — kdo umí kliknout
kamkoli na obrazovce a psát do libovolného pole, ovládá celé zařízení
(odemčení, potvrzení dialogů, vpisování hesel). Všechny nové endpointy musí
projít **stejným gatingem** jako `/shell`: Bearer token + attestation, žádné
výjimky, žádné bypassy pro "jen localhost".

---

## Úkol 1 — `NetHunterAccessibilityService.kt`: přidat `bounds` do `nodeToJSON`

Přesná náhrada funkce `nodeToJSON` (řádky 33-61 ve stávajícím souboru):

```kotlin
private fun nodeToJSON(node: AccessibilityNodeInfo): JSONObject {
    val json = JSONObject()
    json.put("className", node.className?.toString() ?: "")
    json.put("packageName", node.packageName?.toString() ?: "")
    if (node.text != null) {
        json.put("text", node.text.toString())
    }
    if (node.contentDescription != null) {
        json.put("contentDescription", node.contentDescription.toString())
    }
    if (node.viewIdResourceName != null) {
        json.put("viewId", node.viewIdResourceName)
    }
    json.put("clickable", node.isClickable)
    json.put("enabled", node.isEnabled)
    json.put("focused", node.isFocused)

    val rect = Rect()
    node.getBoundsInScreen(rect)
    json.put("bounds", JSONObject().apply {
        put("left", rect.left)
        put("top", rect.top)
        put("right", rect.right)
        put("bottom", rect.bottom)
    })

    if (node.childCount > 0) {
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                children.put(nodeToJSON(child))
            }
        }
        json.put("children", children)
    }
    return json
}
```

Přidat import na začátek souboru:

```kotlin
import android.graphics.Rect
```

---

## Úkol 2 — `LocalApiServer.kt`: rozšířit `sensitiveEndpoints`

Řádek ~290-294, přidat `"/accessibility/"` (s koncovým lomítkem, pokryje
všechny nové akce přes `startsWith`):

```kotlin
val sensitiveEndpoints = listOf("/shell", "/clipboard", "/location", "/cellinfo",
    "/notifications/active", "/accessibility/hierarchy", "/accessibility/", "/voice_input",
    "/device/admin", "/device/lock", "/apps/usage", "/rootfs/backup", "/rootfs/restore",
    "/vpn/logs", "/map", "/agent/query", "/wifi", "/torch", "/volume",
    "/battery/optimize", "/app/logs", "/editor/")
```

---

## Úkol 3 — `LocalApiServer.kt`: nové routy

Do `routeRequest`, za řádek `path == "/accessibility/hierarchy" && ...`:

```kotlin
path == "/accessibility/status" && method == "GET" -> handleAccessibilityStatus(out)
path == "/accessibility/tap" && method == "POST" -> handleAccessibilityTap(body, out)
path == "/accessibility/click" && method == "POST" -> handleAccessibilityClick(body, out)
path == "/accessibility/longclick" && method == "POST" -> handleAccessibilityLongClick(body, out)
path == "/accessibility/swipe" && method == "POST" -> handleAccessibilitySwipe(body, out)
path == "/accessibility/text" && method == "POST" -> handleAccessibilityText(body, out)
path == "/accessibility/scroll" && method == "POST" -> handleAccessibilityScroll(body, out)
path == "/accessibility/global" && method == "POST" -> handleAccessibilityGlobal(body, out)
```

---

## Úkol 4 — `LocalApiServer.kt`: nové handlery

Za `handleAccessibilityHierarchy`:

```kotlin
private fun handleAccessibilityStatus(out: OutputStream) {
    val enabled = NetHunterAccessibilityService.isServiceRunning()
    sendResponse(out, 200, "OK", JSONObject().put("enabled", enabled).toString())
}

private fun requireServiceOrError(out: OutputStream): Boolean {
    if (!NetHunterAccessibilityService.isServiceRunning()) {
        sendResponse(out, 200, "OK", JSONObject().apply {
            put("error", "Accessibility Service not enabled")
            put("needs_permission", "android.settings.ACCESSIBILITY_SETTINGS")
        }.toString())
        return false
    }
    return true
}

private fun handleAccessibilityTap(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val ok = NetHunterAccessibilityService.tap(j.getInt("x"), j.getInt("y"))
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}

private fun handleAccessibilityClick(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val text = j.optString("text", null)
        val ok = if (text != null) {
            NetHunterAccessibilityService.clickByText(text)
        } else {
            NetHunterAccessibilityService.tap(j.getInt("x"), j.getInt("y"))
        }
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}

private fun handleAccessibilityLongClick(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val text = j.optString("text", null)
        val ok = if (text != null) {
            NetHunterAccessibilityService.longClickByText(text)
        } else {
            NetHunterAccessibilityService.longTap(j.getInt("x"), j.getInt("y"))
        }
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}

private fun handleAccessibilitySwipe(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val ok = NetHunterAccessibilityService.swipe(
            j.getInt("x1"), j.getInt("y1"), j.getInt("x2"), j.getInt("y2"),
            j.optLong("duration_ms", 300L)
        )
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}

private fun handleAccessibilityText(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val text = j.getString("text")
        val targetText = j.optString("target_text", null)
        val ok = NetHunterAccessibilityService.setText(text, targetText)
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}

private fun handleAccessibilityScroll(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val forward = j.optString("direction", "forward") == "forward"
        val targetText = j.optString("text", null)
        val ok = NetHunterAccessibilityService.scroll(forward, targetText)
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}

private fun handleAccessibilityGlobal(body: String, out: OutputStream) {
    try {
        if (!requireServiceOrError(out)) return
        val j = JSONObject(body)
        val ok = NetHunterAccessibilityService.globalAction(j.getString("action"))
        sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
    } catch (e: Exception) {
        sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
    }
}
```

---

## Úkol 5 — `NetHunterAccessibilityService.kt`: nové funkce v companion objektu

Ověřeno proti skutečnému souboru (89 řádků, `instance` je přímo
`@Volatile private var instance: NetHunterAccessibilityService?` v companion
objektu — žádný extra holder). Přidat tyto funkce do companion objektu,
za `getScreenHierarchy()`:

```kotlin
fun tap(x: Int, y: Int): Boolean {
    val inst = instance ?: return false
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        .build()
    return inst.dispatchGesture(gesture, null, null)
}

fun longTap(x: Int, y: Int): Boolean {
    val inst = instance ?: return false
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
        .build()
    return inst.dispatchGesture(gesture, null, null)
}

fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
    val inst = instance ?: return false
    val path = Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
    }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        .build()
    return inst.dispatchGesture(gesture, null, null)
}

private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
    return root.findAccessibilityNodeInfosByText(text).firstOrNull()
}

fun clickByText(text: String): Boolean {
    val inst = instance ?: return false
    val root = inst.rootInActiveWindow ?: return false
    val node = findNodeByText(root, text) ?: return false
    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
}

fun longClickByText(text: String): Boolean {
    val inst = instance ?: return false
    val root = inst.rootInActiveWindow ?: return false
    val node = findNodeByText(root, text) ?: return false
    return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
}

fun setText(text: String, targetText: String?): Boolean {
    val inst = instance ?: return false
    val root = inst.rootInActiveWindow ?: return false
    val node = if (targetText != null) {
        findNodeByText(root, targetText) ?: return false
    } else {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
    }
    val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}

fun scroll(forward: Boolean, targetText: String?): Boolean {
    val inst = instance ?: return false
    val root = inst.rootInActiveWindow ?: return false
    val node = if (targetText != null) findNodeByText(root, targetText) else root
    node ?: return false
    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                 else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    return node.performAction(action)
}

fun globalAction(action: String): Boolean {
    val inst = instance ?: return false
    val a = when (action) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        else -> return false
    }
    return inst.performGlobalAction(a)
}
```

Přidat importy na začátek souboru (za stávající importy):

```kotlin
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
```

(`Rect` už je přidán v Úkolu 1.)

---

## Úkol 6 — `nh` CLI: `--json` režim pro čtení + nové akční příkazy

### 6a) Nahradit `device_accessibility()` (podporuje `human` i `--json`)

```bash
device_accessibility() {
    local mode="${1:-human}"
    local raw
    raw=$(api_get "/accessibility/hierarchy")

    if [ "$mode" = "--json" ] || [ "$mode" = "json" ]; then
        echo "$raw" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    out = []
    def walk(n):
        tx = n.get('text','') or n.get('contentDescription','') or ''
        b = n.get('bounds')
        if tx and b:
            cx = (b['left'] + b['right']) // 2
            cy = (b['top'] + b['bottom']) // 2
            out.append({
                'text': tx, 'x': cx, 'y': cy,
                'clickable': bool(n.get('clickable', False)),
                'enabled': bool(n.get('enabled', True)),
                'class': n.get('className','')
            })
        for ch in n.get('children', []): walk(ch)
    walk(d)
    out.sort(key=lambda e: (e['y'], e['x']))
    print(json.dumps(out, ensure_ascii=False))
except Exception as e:
    print(json.dumps({'error': str(e)}))
"
    else
        echo "$raw" | fmt "
import sys, json
G,R,Y,N,Gy=chr(27)+'[92m',chr(27)+'[91m',chr(27)+'[93m',chr(27)+'[0m',chr(27)+'[90m'
try:
    d=json.load(sys.stdin)
    if 'error' in d: print(f'{R}✘  {d[\"error\"]}{N}')
    else:
        rows=[]
        def walk(n):
            tx=n.get('text','') or n.get('contentDescription','') or ''
            b=n.get('bounds')
            if tx and b:
                cx=(b['left']+b['right'])//2
                cy=(b['top']+b['bottom'])//2
                ck='clickable' if n.get('clickable') else ''
                rows.append((cy, cx, tx, ck))
            for ch in n.get('children',[]): walk(ch)
        walk(d)
        rows.sort()
        for cy,cx,tx,ck in rows:
            tag=f' {Gy}[{ck}]{N}' if ck else ''
            print(f'{G}[{tx}]{N}  x={cx} y={cy}{tag}')
except Exception as e:
    print(f'{R}Parse error: {e}{N}')
"
    fi
}
```

### 6b) Nové akční funkce a rozšíření `device_dispatch()`

```bash
device_dispatch() {
    case "$1" in
        admin) device_admin ;;
        battery-optimize) device_battery_optimize ;;
        accessibility) shift; device_accessibility "$@" ;;
        tap) shift; device_tap "$@" ;;
        click) shift; device_click "$@" ;;
        longclick) shift; device_longclick "$@" ;;
        swipe) shift; device_swipe "$@" ;;
        text) shift; device_text "$@" ;;
        scroll) shift; device_scroll "$@" ;;
        global) shift; device_global "$@" ;;
        *) echo "Usage: nh device admin|battery-optimize|accessibility|tap|click|longclick|swipe|text|scroll|global"; exit 1 ;;
    esac
}

device_tap() {
    local x="$1" y="$2"
    [ -z "$y" ] && { echo "Usage: nh device tap <x> <y>"; exit 1; }
    api_post_json "/accessibility/tap" "{\"x\":$x,\"y\":$y}" | fmt "
import sys, json
G,R,N=chr(27)+'[92m',chr(27)+'[91m',chr(27)+'[0m'
try:
    d=json.load(sys.stdin)
    if d.get('success'): print(f'{G}✔ tap $x,$y{N}')
    else: print(f'{R}✘ tap failed: {d.get(\"error\",\"unknown\")}{N}')
except: print(f'{R}✘ parse error{N}')
"
}

device_click() {
    local text="$*"
    [ -z "$text" ] && { echo "Usage: nh device click <text>"; exit 1; }
    api_post_json "/accessibility/click" "{\"text\":$(python3 -c "import json,sys; print(json.dumps(sys.argv[1]))" "$text")}" | fmt "
import sys, json
G,R,N=chr(27)+'[92m',chr(27)+'[91m',chr(27)+'[0m'
try:
    d=json.load(sys.stdin)
    if d.get('success'): print(f'{G}✔ click \"$text\"{N}')
    else: print(f'{R}✘ click failed: {d.get(\"error\",\"not found\")}{N}')
except: print(f'{R}✘ parse error{N}')
"
}

device_longclick() {
    local text="$*"
    [ -z "$text" ] && { echo "Usage: nh device longclick <text>"; exit 1; }
    api_post_json "/accessibility/longclick" "{\"text\":$(python3 -c "import json,sys; print(json.dumps(sys.argv[1]))" "$text")}" >/dev/null
    echo "sent"
}

device_swipe() {
    local x1="$1" y1="$2" x2="$3" y2="$4" dur="${5:-300}"
    [ -z "$y2" ] && { echo "Usage: nh device swipe <x1> <y1> <x2> <y2> [duration_ms]"; exit 1; }
    api_post_json "/accessibility/swipe" "{\"x1\":$x1,\"y1\":$y1,\"x2\":$x2,\"y2\":$y2,\"duration_ms\":$dur}" >/dev/null
    echo "sent"
}

device_text() {
    local text="$1" target="$2"
    [ -z "$text" ] && { echo "Usage: nh device text <text> [target_field_label]"; exit 1; }
    local payload
    if [ -n "$target" ]; then
        payload=$(python3 -c "import json,sys; print(json.dumps({'text':sys.argv[1],'target_text':sys.argv[2]}))" "$text" "$target")
    else
        payload=$(python3 -c "import json,sys; print(json.dumps({'text':sys.argv[1]}))" "$text")
    fi
    api_post_json "/accessibility/text" "$payload" >/dev/null
    echo "sent"
}

device_scroll() {
    local dir="${1:-forward}" target="$2"
    local payload
    if [ -n "$target" ]; then
        payload=$(python3 -c "import json,sys; print(json.dumps({'direction':sys.argv[1],'text':sys.argv[2]}))" "$dir" "$target")
    else
        payload=$(python3 -c "import json,sys; print(json.dumps({'direction':sys.argv[1]}))" "$dir")
    fi
    api_post_json "/accessibility/scroll" "$payload" >/dev/null
    echo "sent"
}

device_global() {
    local action="$1"
    [ -z "$action" ] && { echo "Usage: nh device global back|home|recents|notifications|quick_settings|lock_screen|screenshot"; exit 1; }
    api_post_json "/accessibility/global" "{\"action\":\"$action\"}" >/dev/null
    echo "sent"
}
```

### 6c) Doplnit help/list

- `help_device` (pokud existuje) doplnit o `tap`, `click`, `longclick`, `swipe`, `text`, `scroll`, `global`
- `list_cmds` doplnit řádky: `"device tap" "device click" "device longclick" "device swipe" "device text" "device scroll" "device global"`

---

## Použití po implementaci

```bash
nh device accessibility --json        # strojově čitelný strom pro AI
nh device tap 540 1512                # tap na souřadnice
nh device click "Chat"                # klik podle textu (spolehlivější)
nh device swipe 540 2000 540 500 300  # swipe nahoru
nh device text "hledaný výraz" "Search"  # vepsání do pole se štítkem "Search"
nh device global back                 # systémové tlačítko zpět
```

## Kontrolní seznam pro agenta

- [ ] `bounds` přidán do JSON serializace uzlu v `NetHunterAccessibilityService.kt`
- [ ] `sensitiveEndpoints` rozšířen o `/accessibility/`
- [ ] Všech 7 nových routů zapojeno v `routeRequest`
- [ ] Všech 7 nových handlerů implementováno v `LocalApiServer.kt`
- [ ] Companion funkce v `NetHunterAccessibilityService.kt` doplněny (ověřeno
      proti skutečnému souboru, přesný diff v Úkolu 5)
- [ ] `nh` doplněn o `--json` režim a 6 nových `device` příkazů
- [ ] Rebuild přes Modal: `cd kali_core_emulator && zsh mbuild` (NIKDY lokální `./gradlew`)
- [ ] Ověřeno, že nové endpointy vrací 401 bez Bearer tokenu ze vzdáleného připojení
