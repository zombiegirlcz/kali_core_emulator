Now I have all the information needed for the review. Here is my complete analysis:

---

## Review

### Strengths

1. **Shell script security is excellent.** `code-server-ctl` implements all security rules from the plan correctly:
   - Bind-addr hardcoded to `127.0.0.1:8443` (no CLI override possible) ✅
   - Password stored in `config.yaml` with `chmod 600`, never passed as CLI argument ✅
   - `set -e` for fail-fast behavior ✅
   - PID file cleanup on stale detection ✅
   - Graceful SIGTERM → SIGKILL shutdown sequence with 5s timeout ✅

2. **PRoot guest integration follows established patterns.** The asset deployment in `ProotManager.kt` (line 1447) and MOTD/welcome banner additions (lines 1827-1833 and 1919-1925) correctly mirror the `vpn-cli` and `nethunter-desktop` patterns.

3. **WebView security is good.** `allowFileAccess=false` and `allowContentAccess=false` prevent file:// content access.

4. **Documentation is comprehensive.** The `nethunter_docs.md` section covers all CLI commands, HTTP API endpoints, security rules, persistence paths, and extension setup.

5. **UI is well-structured.** The Compose UI follows the visual language (Kali green, monospace, dark theme) and state patterns (polling, status pill, bottom sheet for password, first-time dialog).

### Issues

#### Critical (Must Fix)

**C1 — Missing authentication on 4 of 5 editor endpoints (`LocalApiServer.kt:290-294, 1698-1699`)**

Only `/editor/password` is included in the `sensitiveEndpoints` list (line 294). The other four endpoints (`/editor/start`, `/editor/stop`, `/editor/status`, `/editor/info`) are **not** in the list and are therefore accessible without any authentication from remote connections. The code comment on lines 1698-1699 claims "All endpoints require a Bearer token when accessed remotely (added to the sensitive-endpoint list at the connection handler)" but this is incorrect — only one of five was added.

The plan explicitly requires: *"Vyžadovat Authorization: Bearer <token> — stejný token jako zbytek API"* for all editor endpoints. An attacker on the same Wi-Fi network as a device with `share_local_api=on` could start/stop the code-server or read its configuration without knowing any token.

**Fix:** Add these paths to `sensitiveEndpoints`:
```kotlin
"/editor/start", "/editor/stop", "/editor/status", "/editor/info"
```

**C2 — `/editor/password` localhost-only enforcement is not implemented (`LocalApiServer.kt:1776-1782`)**

The `handleEditorPassword` handler contains:
```kotlin
val remoteAddr = try {
    "127.0.0.1"   // ← hardcoded, never actually checks the socket
} catch (e: Exception) { "?" }
```
This is dead code. The variable is assigned but never used. The plan requires `/editor/password` to be localhost-restricted even with a valid Bearer token: *"`/editor/password` navíc omezit na localhost origin check"*. Currently, a remote attacker who obtains a valid Bearer token (or if attestation is disabled) can retrieve the code-server password over the network.

The inline comment says "rely on the sensitive-endpoint gate already applied above", but that gate only checks whether auth is *present*, not whether the connection is *local*. The handler needs an explicit socket-address check before returning the password.

#### Important (Should Fix)

**I1 — False positive on start/stop/failure (`LocalApiServer.kt:1745, 1758`)**

`handleEditorStart` and `handleEditorStop` use `parseScriptJsonOrWrap(raw, "started"/"stopped")`. If `code-server-ctl start` exits with a non-zero exit code and outputs non-JSON text (e.g., `[!] code-server binary not found`), this function wraps it with `{"status":"started","raw":"[!] code-server binary not found..."}` — reporting success despite failure.

The root cause is that `runCodeServerCtl` (line 1716) never checks `proc.exitValue()`. The script returns exit code 1 on failures, but the Java code ignores it.

**I2 — No process exit code checking in `runCodeServerCtl` (`LocalApiServer.kt:1708-1730`)**

The function captures stdout/stderr but never inspects `proc.exitValue()`. If the script crashes with exit code 1, the function returns whatever output the script produced before dying, including error messages, which the callers then misinterpret as successful JSON.

**I3 — Missing `install` endpoint in HTTP API (`EditorTab.kt` vs plan)**

The plan's UI spec includes an "Install now" action for the "NOT INSTALLED" error state (Fáze 4, chybové stavy). The `code-server-ctl install` command exists in the script and is documented in the MOTD, but there is no `/editor/install` HTTP endpoint, and the UI only handles `Running`, `Stopped`, `Starting`, and `Error` states — not a distinct `Not Installed` state with an install action.

#### Minor (Nice to Have)

**M1 — Docs "Omezení a TODO" section is about MITM, not editor (`nethunter_docs.md:183-187`)**

The "Omezení a TODO" list mentions MITM-only-for-TCP, TLS 1.3 handshake issues, binary payload limits, and session buffer limits — all of which relate to the TLS MITM feature, not to code-server. This appears to be a copy-paste artifact from the MITM documentation section and should be replaced with editor-specific future work items.

**M2 — `handleEditorPassword` premature password generation (`code-server-ctl:84-87`)**

The `cmd_password()` function in the shell script calls `ensure_password()` which generates and writes a password/config even when the command is called just to display it, not to start the editor. The UI calls `/editor/password` before the editor is running (to show in the first-time dialog), which creates a `config.yaml` with a generated password even if the user never starts the editor. Consider reading the password only if config exists, generating only on `start`.

### Recommendations

1. **Fix C1 immediately** by adding all four missing `/editor/*` paths to `sensitiveEndpoints`.
2. **Fix C2** by adding a real `socket.inetAddress?.hostAddress` check in `handleEditorPassword` (or passing the remote address into the handler from the connection loop).
3. **Add exit-code checking** in `runCodeServerCtl` — return a proper JSON error object when `proc.exitValue() != 0`.
4. **Consider adding `/editor/install` endpoint** and a "Not Installed" UI state so the user can install code-server directly from the UI without needing the terminal.

### Assessment

**Not ready to merge.**

**Reasoning:** Two critical security gaps (missing auth on 4 endpoints and ineffective localhost-only enforcement on the password endpoint) directly contradict the security requirements laid out in the plan. These must be fixed before deployment — leaving them open would allow remote attackers to start/stop the editor and read the code-server password over the network when `share_local_api` is enabled.

---

## Acceptance Report