# Design: Open-with + ~/share, PiP, Floating Terminal (`nh float`)

Date: 2026-08-16 · Status: implemented, build green (APK 12:04, 144 MB)

## Feature 1 — „Open with" / share → `~/share` (Termux-style)

- **`ShareReceiverActivity`** (`core/ShareReceiverActivity.kt`, `exported=true`, translucent, `excludeFromRecents`, own taskAffinity):
  - Intent filters: `ACTION_VIEW` (file/content, `*/*`), `ACTION_SEND`, `ACTION_SEND_MULTIPLE` → app appears in the system „Open with / Share" chooser.
  - Copies received files into `filesDir/share/` (name from `OPENABLE_COLUMNS`, sanitized against path traversal, collisions → `name (1).ext`).
  - Toast + opens TerminalActivity in the active distro with `customCommand = "cd /root/share && ls -la && exec $(command -v zsh || command -v bash) --login"`.
- **`boot` script**: `build_binds()` now always adds `-b $FILES_DIR/share:/root/share` (mkdir -p first); non-termux docker images get the same bind. → guest sees `~/share`.
- Decision: `~/share` = private `filesDir/share` (option A) — no storage permissions needed, content:// copies work directly.

## Feature 2 — Permissions + PiP

- Manifest: `SYSTEM_ALERT_WINDOW` permission added.
- `TerminalActivity`: `supportsPictureInPicture=true`, `resizeableActivity=true`.
- `onUserLeaveHint()` → auto PiP (16:9) when a session is running.
- `onPictureInPictureModeChanged()` hides all chrome (topBar, services panels, suggestion bar, extra-keys toolbar, keypad, drawer; drawer locked closed), restores saved visibility on exit.

## Feature 3 — Floating terminal (`FloatingTerminalService`)

- Foreground service + `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`, focusable so IME works, `FLAG_NOT_TOUCH_MODAL`).
- **Expanded window**: title bar (drag to move, ◐ alpha cycle 100/85/70/55/40 %, ▁ minimize, ✕ close) + Termux `TerminalView` + corner ◢ resize handle (drag w/h, clamped 240×160 dp … screen size).
- **Minimized**: 56 dp chat-head bubble (Messenger-like), draggable, tap → restore, long-press → close.
- All geometry + alpha persisted in SharedPreferences `float_terminal`.
- Session handling (decision C):
  - `nh float` → **new** session of the active distro (`nh/.active_distro` marker) in the overlay.
  - `nh float here` → **moves the current session**: `TerminalService.detachView` + `floatedSessionIds` + `onSessionFloated` callback → TerminalActivity switches to another session or creates a new one.
  - ✕ close → borrowed session **returns** to TerminalActivity (`returnSessionId` extra + `onSessionReturned` callback → `switchToSession`); owned session is removed.
- `TerminalService` additions: `getSessionById()`, `floatedSessionIds`, `onSessionFloated` / `onSessionReturned` callbacks.
- View-attach is deferred via `view.post {}` so the overlay is laid out before `attachSession` (avoids 0-column emulator init).

## Feature 4 — API + CLI

- `POST /terminal/float` on LocalApiServer, JSON `{"mode":"new|here|close","session_id":...}` (plain-text fallback `here:<id>`). Overlay-permission check → opens system settings + returns `overlay_permission_required`.
- `nh float` / `nh float here` / `nh float close` in the `nh` script (uses `$NETHUNTER_SESSION_ID`, already propagated into the guest — same mechanism as `nh vpn ignore`).

## Known risks / to verify on device

- IME focus inside overlay window (tap-to-focus + showSoftInput implemented; needs real-device test).
- Overlay permission must be granted manually once (system settings deep-link provided).
- PiP: terminal keeps rendering in PiP; input not possible in PiP mode (Android limitation).
