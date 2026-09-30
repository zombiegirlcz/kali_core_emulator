# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Source of truth: AGENTS.md

**`AGENTS.md` (v kořeni, česky) is the authoritative operational contract for this repo — read it first.**
It carries the full non-negotiable rules, the identity/signing table, the class-level architecture,
the native-module→assets pipeline, and an extensive "co nevrátit zpět" (do-not-revert) list of hard-won
pitfall fixes. This CLAUDE.md only surfaces the essentials and points back to it; do not duplicate its
content here. Communicate with the user in **Czech**.

Deeper docs live in `docs/` (`SECURITY_AUDIT.md`, `SIGNING_AND_VERSIONS.md`, `proot-cmd-mod.md`, `BUILD.md`,
`plans/`), MITM details in `assets/nethunter_docs.md`, and the full `nh` CLI in `README.md`.

## Non-negotiable rules (see AGENTS.md §0 for the full list)

- **NEVER build locally** — no `./gradlew …` on this machine. Builds go **only through Modal** (`zsh mbuild …`).
- **GitHub is the source of truth** (`zombiegirlcz/kali_core_emulator`, branch `dev`). `git commit && git push`
  *before* every build; `mbuild sync` clones/pulls from GitHub, never uploads from the device.
- **Never `chmod`/`chown` device system dirs** (`/system`, `/data`, …) → caused a bootloop. Deploy only into
  the app `filesDir`, `/data/adb`, or a Magisk module.
- **Do not change:** `--link2symlink` in proot, `jniLibs` load order, `useLegacyPackaging = true`.
- Destructive ops (`rm -rf /`, `mkfs`, `reboot`, …) are double-blocked (`su_daemon` blocklist + `LocalApiServer`
  + `ashell`) — don't bypass.

## Build & verify (always Modal)

```zsh
# 1) commit + push to GitHub first (else changes won't be built)
zsh mbuild sync     # git clone/pull sources on the Modal side (NEVER upload from phone)
zsh mbuild all      # sync + SMART incremental build (proot/native/gradle) → APK
zsh mbuild build    # Gradle assembleDebug + download APK only
zsh mbuild native   # NDK/C compile only + pull_full_assets()
zsh mbuild smart    # incremental build without sync
zsh mbuild clean    # wipe src + gradle-cache on the Volume
```

- APK lands at `~/Download/kali_core.apk`. **Rule: `sync` is always run separately; `build` never calls `sync`.**
- After a native build, `pull_full_assets()` overwrites local `app/src/main/assets/` from the Volume — you must
  then **commit + push the produced binaries** (`assets/su_wrapper`, `usb_bridge`, `usr/bin/*`, `usr/lib/*`, `jniLibs/*.so`),
  or the next `rsync --delete` erases them and they won't be in the APK. See AGENTS.md §4 for the exact rule.
- On-device logs without ADB: `nethunter-log [-n N] [-g PATTERN]` (also `nh log`; HTTP `GET /app/logs?limit=N`).

### Native C modules → assets pipeline (AGENTS.md §4)

C sources in `app/src/main/cpp/*.c` (`ashell`, `ashell_pty`, `shell_daemon`, `su_daemon`, `su_wrapper`, `cpuctl`,
`usb_bridge`, `usbfd_jni`) are cross-compiled on Modal via `tools/modal_build.py` (`build_native()`), pulled back
into the repo, and **must be committed** — an uncommitted binary will be missing from the APK.

### Tests

JVM unit tests live under `app/src/test/java/com/linux_core/` (run on Modal, e.g. `./gradlew test --tests "*ProotManager*"`
in the Modal env — never locally). Key suites: `security/` (attestation, MITM cert signing), `core/rootfs/`
(boot modes, CPU pin persistence), `core/terminal/` (shell_daemon protocol/deploy), `core/device/AshellConfigParserTest`.

## Identity (AGENTS.md §2)

- Package **`com.linux_core`**; sources under `app/src/main/java/com/linux_core/`.
- `versionCode = 20`, `versionName = "4.5-MULTI-ROOTFS"` (`app/build.gradle.kts`); minSdk/targetSdk **28/28**; JVM 17.
- Same keystore (`app/release.jks`) for debug & release. **Bump `versionCode` before each distributed build.**

## Architecture (big picture — full class table in AGENTS.md §3)

Single-module app (`:app`) that runs **Kali NetHunter / ParrotOS in an unrooted PRoot container** (Termux terminal),
with an AdGuard C++ VPN, TLS MITM, and an Xvfb X11 server. The desktop GUI is **not** part of core — it is rendered
by the *external* `kali_GUI` X11 launcher app (`com.linux_core.xlauncher`).

Host loopback services: **1337** `LocalApiServer` (REST bridge: sensors, `/shell`, `/distro/*`, VPN, MITM),
**13338** AI agent daemon, **13339** VPN bypass proxy, **6000** Xvfb `:0`, **13340** `ashell_pty`, **13341** `shell_daemon`.

Layers to know:
- **PRoot runtime** — `ProotManager` (arch detection, deploys static `proot-static-*`/`loader-static-*` + the universal
  `assets/usr/bin/boot` launcher), `RootfsManager` (download + tar.xz extraction), `TerminalActivity`/`TerminalService`.
  Boot modes D/I/M, CPU pinning, and bind construction all live in the `boot` script + `ProotManager`.
- **VPN + MITM** — `VpnCaptureService`/`VpnNatEngine` (NAT, QUIC gating), `TlsMitmEngine`/`TlsClientHelloParser`
  (`enable_mitm`, default off), `AIBrain`/`VerdictEngine` (ONNX flow classification).
- **Privileged-without-root** — `shell_daemon` (uid 2000, adb-shell-equivalent over TCP 13341; `ShellDaemonClient` speaks
  its **native little-endian** protocol — never `DataOutputStream.writeInt`), `su_daemon`/`su_wrapper` (fake-root re-entry
  into PRoot), `ashell` (native host-shell client, HTTP + `ashell_pty` PTY protocol).

Optional root add-on: Magisk modules in `magisk-modules/` (`nh_cpuctl` for CPU control, `nh_freeze_guard` for audit-flood mitigation + UDP freeze telemetry, `anti_phantom`).

## Repository

- **Git LFS required** (`.apk` via LFS, see `.gitattributes`). Submodule `nethunter-store-data` (GitLab).
- Clone with `git clone --recurse-submodules` + `git lfs pull`.
