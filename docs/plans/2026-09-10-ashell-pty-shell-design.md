# ashell — plnohodnotný streaming PTY shell mezi prootem a hostem

Datum: 2026-09-10 · Stav: schváleno (přístup 1 — nativní `ashell_pty` daemon)

## Cíl

`ashell -c '...'` se má chovat jako lokální shell: streaming stdout, stdin,
pravý exit code, cwd z guestu (přeložené na host rootfs) a reálné PTY
(job control, `isatty`, resize). Už opraveno (hotovo): **exit code** v
`assets/ashell` (dřív `rm -f` přepisoval `$?` a parser `sys.exit(0/1)`
zahazoval přesný kód).

## Architektura

1. **Nativní `ashell_pty`** (bionic C, app UID, `app/src/main/cpp/ashell_pty.c`)
   — TCP listener na `127.0.0.1:13340` (SO_REUSEADDR). Vzor: `su_daemon.c`
   (open_pty, relay, waitpid, exit code). Fork-per-connection.
   - Peer povolen jen z `127.0.0.1` (stejný trust model jako `/shell` v
     `LocalApiServer`).
   - Handshake = frame `0x05 HELLO` s NUL-separovanými poli `cmd\0cwd\0rootfs\0term\0`.
   - `fork` + `/dev/ptmx` pty → `sh -c cmd` s env (PATH/HOME/PREFIX/TERM,
     bez `LD_LIBRARY_PATH`). cwd = `rootfs + guest_cwd` (fallback `rootfs`, `/`).
   - Binární frame protokol (5B hlavička `u8 type | u32 BE len` + payload):
     client→server `0x01 STDIN`, `0x03 WINCH` (u32 cols, rows), `0xFF CLOSE`;
     server→client `0x02 STDOUT`, `0x04 EXIT` (u32 code), `0xFF CLOSE`.

2. **Spawn v `LocalApiServer`** — deploy `assets/ashell_pty` → `filesDir/ashell_pty`
   (vzor `deploySuBridge`), spawn `ashell_pty 13340 <filesDir>`; zabít při `stop()`.

3. **`assets/ashell`** — `-c` primárně přes python3 socket klienta (termios raw,
   select, SIGWINCH), fallback na stávající HTTP JSON.

4. **Build** — kompilační krok v `_build_native_bin()` (`tools/modal_build.py`):
   `aarch64-linux-android24-clang -o assets/ashell_pty cpp/ashell_pty.c`
   (dynamický, jako `su_daemon`).

## Soubory

- `app/src/main/cpp/ashell_pty.c` (nový)
- `app/src/main/java/com/linux_core/core/LocalApiServer.kt` (spawn/stop + deploy)
- `tools/modal_build.py` (`_build_native_bin` + `ashell_pty`)
- `app/src/main/assets/ashell` (python3 PTY klient + fallback)

## Ověření na zařízení

- `ashell -c 'exit 7'; echo $?` → 7
- `ashell -c 'echo a; echo b | tr a-z A-Z'` → a, ABC
- `ashell -c 'cat <soubor> | tail 5'` (relativní cesta z guest cwd) → funguje
- `ashell -c 'read x; echo got=$x' <<< hello` → stdin funguje (EOF)
- resize / `less` — ruční test na device
