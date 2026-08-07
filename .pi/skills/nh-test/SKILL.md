---
name: nh-test
description: Run NetHunter's full nh-vs-logcat correlation test on a live device and interpret the single test.log — Request/EXECUTED match, ok=true/false verdict, expected-vs-real failures. Use when you need to verify that nh CLI commands and the LocalApiServer actually executed on the host, or when troubleshooting nh/device/system behavior.
disable-model-invocation: true
---

# nh-test

<what-to-do>

Run `tools/nh_test.sh` (one pass: plain `nh` batch + background logcat keyed to the app UID):

```bash
cd /root/kali_core_emulator
zsh tools/nh_test.sh
```

Logcat capture is **adb `logcat --uid=<appUID>` by default**; if adb is not reachable it **falls back to the host-endpoint** `GET http://127.0.0.1:1337/app/logs?limit=N` (app must be running, no adb needed). Everything lands in `~/kali_core_emulator/test.log`.

Wait for exit 0, then **read test.log** and classify every command:

- **EXECUTED ok=true** — host did it; a real action was performed.
- **EXECUTED ok=false** — reached host but action failed (target not found, permission, etc.).
- **FAILED / error** — e.g. `Speech error: Insufficient permissions (9)`.
- **Request missing** — nh reported "sent" but no matching `Request: <METHOD> <path>` line appears: the CLI and host log disagree → real bug to investigate.

## Expected failures (do NOT fix as bugs)

- `device click/longclick/text/scroll 'NetHunter'` → `ok=false` (text not on the current screen). Pick a visible label from the accessibility dump, or treat the fail as expected.
- `device speech`/`system speech` → `Insufficient permissions (9)` until the mic-permission dialog is granted on the device.

## Harness problems (not app bugs)

- `LocalApiServer na 127.0.0.1:1337 nedostupný` → host app not running / no rootfs shell. Start the app, rerun.
- Empty LOGCAT CORRELATION → `curl -s 'http://127.0.0.1:1337/app/logs?limit=50'` manually; still empty = the app is not producing logs, not a harness fault.
- adb line shows `adb nedostupné` but the rest worked → fallback used; that's fine; correlation is still valid.

</what-to-do>

<supporting-info>

Which `nh` command maps to which `Request` path and what EXECUTED ok-value is expected is in [REFERENCE.md](REFERENCE.md). `tools/nh_test.sh` is the reference implementation — edit it there, never keep a divergent copy.

</supporting-info>