# Shizuku + Services Integration into NetHunter Terminal

## Status: Approved Design

## Overview

Integrate Shizuku privilege escalation into the NetHunter AI Operator app,
providing a self-contained Shizuku server + Rish shell bridge from PRoot, plus
a unified services dashboard in the terminal top bar for managing Shizuku,
code-server, and Phoenix services.

## Architecture

### Terminal Top Bar Services Panel

A collapsible second row in `TerminalActivity.topBar` (below the existing
row, above the topBarDivider) that contains:

- SHIZU — Shizuku server status/control
- CODE — code-server (VS Code in browser) status/control
- PHOENIX — Phoenix OTLP telemetry status/control
- START ALL / Refresh button

Each service shows a status indicator: ● running (zelená) / ○ stopped (šedá).
Clicking a service expands its detail panel below the services row.

### Shizuku Integration — Self-Contained

Files bundled in the APK (from user's existing extracted Shizuku binaries):

| Source | Destination | Purpose |
|---|---|---|
| `arm64/libshizuku.so` | `app/src/main/assets/shizuku/libshizuku.so` | PIE executable - Shizuku server |
| `arm64/libadb.so` | `app/src/main/assets/shizuku/libadb.so` | ADB client library |
| `arm64/librish.so` | `app/src/main/assets/shizuku/librish.so` | Rish shell library |
| `shi/rish` | `app/src/main/assets/shizuku/rish.sh` | Rish shell script (modified for com.linux_core) |
| `shi/rish_shizuku.dex` | `app/src/main/assets/shizuku/rish_shizuku.dex` | Rish Java classes |

#### ShizukuManager (new file)

`ShizukuManager.kt` v `com.linux_core.core`:

- `startServer(context)` — deploy and start the Shizuku server
- `stopServer(context)` — kill the Shizuku server
- `status(context)` → `ShizukuStatus(running, pid, uid, port)`
- `deployRish(context, rootfsDir)` — copy rish script + dex into PRoot
- `serverExec(context, command)` — run a command through the Shizuku server

Modes:
1. **Existing Shizuku** — detect if Shizuku server already running
2. **ADB Pairing** — wireless ADB pairing flow, then start own server
3. **Fallback** — show instructions if neither works

### Code-Server Integration (Existing)

Already implemented via `code-server-ctl` (in assets) + `LocalApiServer.kt`
endpoints. The services panel will call the same `runCodeServerCtl()` wrapper.

### Phoenix Integration (Existing)

Already implemented via `PhoenixExporter.kt`. Services panel will provide
start/stop and configuration dialog.

## Files To Change

| File | Change |
|---|---|
| `TerminalActivity.kt` | Add services panel, detail panel, expand/collapse logic |
| `ShizukuManager.kt` | **NEW** — Server lifecycle, ADB pairing, rish deploy |
| `ProotManager.kt` | Add rish deployment to `deployApiScripts()` |
| `AndroidManifest.xml` | Add `moe.shizuku.manager.permission.API_V23` |
| `app/build.gradle.kts` | Add Shizuku API dependency (optional) |
| Assets | Bundle `shizuku/` binaries and rish files |

## Service Status Checks

Each service has a `checkStatus()` method returning `ServiceStatus`:

```kotlin
data class ServiceStatus(
    val running: Boolean,
    val pid: Int? = null,
    val uid: Int? = null,      // Shizuku specific
    val port: Int? = null,     // code-server specific
    val extra: String = ""     // human-readable status line
)
```

- **Shizuku:** check `pidof libshizuku.so` or socket existence
- **Code-server:** `code-server-ctl status` (already exists)
- **Phoenix:** quick connectivity check to configured OTLP endpoint

## Data Flow

```
PRoot Terminal                   TerminalActivity              Android Host
─────────────────                ────────────────              ────────────
$ shizuku -c "pm list"           servicesPanel UI              Shizuku server
        │                              │                            │
        │ POST :1337/shizuku/exec      │                            │
        ├─────────────────────────────►│                            │
        │                              │ ShizukuManager.exec()     │
        │                              ├───────────────────────────►│
        │                              │   app_process + rish.dex   │
        │                              │◄───────────────────────────┤
        │◄─────────────────────────────┤                            │
        │  JSON {stdout, exitCode}     │                            │
```

For direct PRoot access (without :1337):

```
PRoot Terminal:
  RISH_APPLICATION_ID=com.linux_core \
  /system/bin/app_process \
    -Djava.class.path=/usr/local/lib/rish_shizuku.dex \
    /system/bin --nice-name=shizuku \
    rikka.shizuku.shell.ShizukuShellLoader -c "pm list packages"
```

## Security

- Shizuku server runs as shell UID (2000) — elevated but not root
- ADB pairing requires user interaction (QR scan / pairing code)
- rish commands go through Shizuku's existing permission model
- The app declares `moe.shizuku.manager.permission.API_V23` in manifest
