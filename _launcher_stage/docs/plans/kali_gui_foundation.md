# kali_GUI — External X11 Launcher (foundation)

Standalone Android app (`com.linux_core.xlauncher`) that renders the Kali/Parrot
XFCE4 desktop. The host app (`com.linux_core`) only runs the X server
(`nh desktop start` → Xvnc `:1`); this app renders and handles input.

## Architecture decision
- **Transport:** connect to the Xvnc **VNC port 5901** (RFB 3.8), not the raw
  X11 port 6001. RFB gives incremental framebuffer updates + built-in input
  events; an X11-client framebuffer poll (XGetImage) would be slow and
  cross-UID MIT-SHM is unavailable.
- **Renderer (foundation):** Canvas-drawn `Bitmap` on a `SurfaceView`. The
  planned production renderer uploads the framebuffer as a GLES texture
  (Termux-X11 style) — a localized swap behind `FramebufferView.drawFrame()`.
- **Auth:** VNC Authentication (DES, password `kali_operator` from `nh`).

## Connection defaults (match `nh desktop start`)
- host `127.0.0.1`, port `5901`, password `kali_operator`
- geometry `1280x720`, depth 24
- overridable via intent extras: `host`, `port`, `password`

## Files
- `ConnectionConfig.kt` — target config + defaults
- `VncAuth.kt` — VNC DES challenge (bit-reversed key via `javax.crypto`)
- `VncClient.kt` — RFB 3.8 client (handshake, Raw decode, pointer/key events)
- `FramebufferView.kt` — SurfaceView renderer + touch→pointer mapping
- `LauncherActivity.kt` — connect on launch, fullscreen immersive

## Build
- Gradle 9.5, AGP 9.2.1, Kotlin 2.2.10, minSdk/targetSdk 28 (mirrors host).
- Never built locally — uses Modal with a **separate volume**
  (`kali-gui-build-data`) so it cannot clobber the host source:
  `zsh mbuild upload && zsh mbuild build`
- Signing: `app/release.jks` (same debug keystore as host).

## TODO (next iterations)
- GLES texture renderer (replace Canvas)
- Right-click (long-press / 2-finger) and keyboard input (KeyEvent)
- HOME-launcher category (phase B) + auto-launch on desktop start
- DesktopSize live handling (already wired; verify on rotate/resize)
- Clipboard / file drop (ServerCutText already parsed & ignored)
