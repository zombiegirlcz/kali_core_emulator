---
name: modal-build
description: Build NetHunter AI Operator (com.linux_core) via Modal cloud: native cross-compile (su_daemon, usrtools) + Gradle assembleDebug + APK verification. Use when you need a reliable, repeatable build that handles known GCC/NDK/rsync/ncurses/cargo pitfalls without manual iteration.
disable-model-invocation: true
---

# modal-build

<what-to-do>

## Pipeline

1. **native** — `./mbuild native`
   - Cross-compiles C binaries (`su_daemon`, `su_wrapper`, `usb_bridge`, `libusbfd_exporter.so`) via NDK clang.
   - Cross-compiles usrtools: sed, rsync, nano (glibc bridge, static ncursesw) + ripgrep (Bionic, Cargo).
   - Outputs to `app/src/main/assets/usr/{bin,lib}` and top-level assets.
   - Polls Modal until `build_native` completes; pulls artifacts to local assets.

2. **build** — `./mbuild build`
   - `pull_binaries` (native + usrtools from Modal Volume → local assets).
   - `upload_src` (rsync source to Modal, respects `_NATIVE_ASSET_EXCLUDES`).
   - Gradle `assembleDebug` on Modal (AGP 9.2.1, JVM 17).
   - Pulls `app-debug.apk` to `/sdcard/Download/app-debug.apk`.

3. **verify** — runs automatically after build:
   - APK size > 120 MB (sanity).
   - `apksigner verify` — release key (CN=NetHunter Developer, SHA-256 `11e785a4e151a0da70491a969a6a6298e0e769986a69d37d9782a417915fd6e1`).
   - `unzip -l` contains: `assets/su_daemon`, `assets/su_wrapper`, `assets/usr/bin/{sed,rsync,nano,rg}`, `assets/usr/lib/libc.so.6`, `classes*.dex` strings `EXECUTED`, `PREFIX=`, `usr/bin`.

## Invocation

```bash
cd /root/kali_core_emulator
# Full pipeline (native → build → verify)
.piz/skills/modal-build/scripts/mbuild-loop.sh

# Or step-wise
zsh mbuild native
zsh mbuild build
.piz/skills/modal-build/scripts/mbuild-loop.sh verify-only
```

## Error handling

- Any step fails → script exits non-zero, prints last 50 lines of Modal log.
- On Gradle failure: re-run `mbuild build` (incremental).
- On native failure: see REFERENCE.md TROUBLESHOOTING.

</what-to-do>

<supporting-info>

See [REFERENCE.md](REFERENCE.md) for:
- TROUBLESHOOTING playbook (every pitfall we hit + fix).
- Asset layout expectations.
- Manual recovery commands.

Helper script: `scripts/mbuild-loop.sh` (deterministic orchestrator).

</supporting-info>