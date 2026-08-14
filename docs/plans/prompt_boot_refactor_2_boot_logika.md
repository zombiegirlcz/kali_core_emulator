# Prompt: Boot refactor — Fáze 2: Boot skript a ProotManager.kt

**Předpoklad:** Fáze 1 (struktura, `usr/bin/proot`, `usr/bin/loader`, `nh/distro/`) je hotová.
**Cíl:** jeden `usr/bin/boot` skript nahrazující `launcher-{kali,parrot,docker}.sh`,
se dvěma opravenými kritickými bugy z review původního plánu.

> **SYNCHRONIZACE 2026-08-14 (Fáze 0):** Statický proot je primární.
> `PROOT="$PREFIX/bin/proot"` (static), `LOADER="$PREFIX/bin/loader"` (static loader).
> `libtalloc.so.2` v `$PREFIX/lib/` už NENÍ potřeba pro statický proot —
> ponechán jen jako fallback. `LD_LIBRARY_PATH` zahrnuje `$PREFIX/lib` pro kompatibilitu.

---

## Bug #1 — chybějící `/root` fallback v `boot_classic()`

Původní návrh měl `PROOT_CWD` fallback (řešení pro rootfs bez `/root`) jen v `boot_docker()`.
`boot_classic()` (kali/parrot) měl `-w /root` a `cd /root` **natvrdo**, bez fallbacku —
to je přesně bug, který teď rozbíjí ostrý provoz při jakékoliv poškozené/nekompatibilní
rootfs. **Fix:** stejná `PROOT_CWD` logika v obou funkcích.

## Bug #2 — rozbitá konstrukce `-c` argumentu v `boot_classic()` (kritičtější)

Původní návrh stavěl `TARGET` jako string obsahující doslovné apostrofy a expandoval
ho **needuotovaně**:

```sh
# ROZBITO:
TARGET="$GUEST_SH -c 'unset ...; cd /root && exec /bin/bash --login \"\$@\"' -- \"\$@\""
...
exec "$PROOT" ... -w /root ... $TARGET     # ← bez uvozovek!
```

Needuotovaná expanze prochází jen word-splittingem podle mezer — apostrofy uvnitř
`$TARGET` se **neinterpretují znovu** jako shell syntax. `-c` tak dostane jako svůj
argument jen první token (`'unset`, s doslovným apostrofem), zbytek se rozsype na
samostatné argumenty → syntakticky nedokončený řetězec v guestu → **selhání při
každém spuštění kali/parrot**, nezávisle na stavu rootfs.

`boot_docker()` to má správně — `-c "$TARGET_CMD"` je uvozovkované, `TARGET_CMD`
je čistý text bez zabalených apostrofů. `boot_classic()` musí kopírovat přesně
tenhle vzor.

---

## Kompletní opravený `boot` skript

```sh
#!/system/bin/sh
set -e

PREFIX="${PREFIX:-/data/user/0/com.linux_core/files/usr}"
FILES_DIR="${FILES_DIR:-/data/user/0/com.linux_core/files}"
PROOT="$PREFIX/bin/proot"
LOADER="$PREFIX/bin/loader"
TALLOC="$PREFIX/lib/libtalloc.so.2"

export LD_LIBRARY_PATH="$PREFIX/lib:/system/lib64:/system/lib"
export PROOT_LOADER="$LOADER"
export PROOT_TMP_DIR="$FILES_DIR/tmp"
export TMPDIR="$FILES_DIR/tmp"

log() { [ -n "$BOOT_DEBUG" ] && echo "[boot] $*" >&2 || true; }
err() { echo "[boot] ERROR: $*" >&2; }

boot_classic() {
    local distro="$1"
    shift || true

    local ROOTFS="$FILES_DIR/nh/distro/$distro"
    if [ ! -d "$ROOTFS" ]; then
        err "Rootfs not found: $ROOTFS (hint: pull $distro first)"
        return 1
    fi

    local GUEST_SH=""
    for _cand in "$ROOTFS/bin/sh" "$ROOTFS/usr/bin/sh"; do
        if [ -x "$_cand" ]; then GUEST_SH="${_cand#"$ROOTFS"}"; break; fi
    done
    if [ -z "$GUEST_SH" ]; then
        err "Guest shell not found in $ROOTFS (tried: bin/sh, usr/bin/sh)"
        return 1
    fi

    # FIX #1: fallback cwd — rootfs bez /root nesmí shodit celý boot
    local PROOT_CWD="/root"
    [ ! -d "$ROOTFS/root" ] && PROOT_CWD="/"

    if [ -x "$ROOTFS/root/bootstrap.sh" ] && \
       { [ -f "$ROOTFS/root/.bootstrap_required" ] || \
         [ ! -f "$ROOTFS/root/.setup_done" ]; }; then
        echo "[boot] First run detected — running bootstrap..." >&2
        local BOOTSTRAP_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; exec /bin/bash /root/bootstrap.sh"
        if "$PROOT" -v 0 --kill-on-exit -0 --link2symlink --sysvipc -L \
            -r "$ROOTFS" -w "$PROOT_CWD" -b /dev -b /proc -b /sys \
            $GUEST_SH -c "$BOOTSTRAP_CMD"; then
            rm -f "$ROOTFS/root/.bootstrap_required"
        else
            echo "[boot] WARNING: bootstrap failed — keeping .bootstrap_required" >&2
        fi
    fi

    # FIX #2: čistý text, volaný přes -c "$TARGET_CMD" (uvozovkovaně) — žádné
    # zabalené apostrofy, žádná needuotovaná expanze na konci exec řádku.
    local TARGET_CMD
    if [ -f "$ROOTFS/root/entrypoint.sh" ]; then
        TARGET_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                    cd $PROOT_CWD 2>/dev/null || cd /; \
                    exec /bin/sh /root/entrypoint.sh \"\$@\""
    else
        TARGET_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                    cd $PROOT_CWD 2>/dev/null || cd /; \
                    exec /bin/bash --login \"\$@\""
    fi

    # menší fix: /dev/bus/usb bind jen když existuje (ať nešpiní log warningem)
    local BINDS="-b /dev -b /proc -b /sys \
        -b $FILES_DIR/tmp:$FILES_DIR/tmp \
        -b $FILES_DIR/ipc:/run/host_ipc \
        -b /sdcard \
        -b /system:/mnt/system -b /vendor:/mnt/vendor \
        -b /data/local/tmp:/mnt/tmp"
    [ -e /dev/bus/usb ] && BINDS="$BINDS -b /dev/bus/usb:/mnt/usb"

    exec "$PROOT" -v 0 --kill-on-exit -0 --link2symlink --sysvipc -L \
        --kernel-release='\Linux\proot\6.17.0-proot-distro\6.17.0-proot-distro\aarch64\localdomain\-1\' \
        -r "$ROOTFS" -w "$PROOT_CWD" \
        $BINDS \
        $GUEST_SH -c "$TARGET_CMD" -- "$@"
}

boot_docker() {
    local image_name="${1:-}"
    shift || true

    local ROOTFS="$FILES_DIR/nh/distro/docker/$image_name"
    if [ ! -d "$ROOTFS" ]; then
        err "Docker rootfs not found: $ROOTFS (hint: pull image first)"
        return 1
    fi

    local IS_TERMUX_IMAGE=0
    [ -d "$ROOTFS/data/data/com.termux" ] && IS_TERMUX_IMAGE=1

    local GUEST_SH=""
    if [ "$IS_TERMUX_IMAGE" = "1" ]; then
        for _cand in "$ROOTFS/data/data/com.termux/files/usr/bin/bash" \
                    "$ROOTFS/data/data/com.termux/files/usr/bin/sh" \
                    "$ROOTFS/system/bin/sh"; do
            if [ -x "$_cand" ]; then GUEST_SH="${_cand#"$ROOTFS"}"; break; fi
        done
    else
        for _cand in "$ROOTFS/bin/sh" "$ROOTFS/usr/bin/sh" \
                    "$ROOTFS/bin/bash" "$ROOTFS/usr/bin/bash"; do
            if [ -x "$_cand" ]; then GUEST_SH="${_cand#"$ROOTFS"}"; break; fi
        done
    fi

    if [ -z "$GUEST_SH" ]; then
        log "Known shell paths failed, falling back to find"
        local found
        found=$(find "$ROOTFS" -maxdepth 6 -type f \( -name 'sh' -o -name 'bash' \) \
                -executable 2>/dev/null | head -1)
        [ -n "$found" ] && GUEST_SH="${found#"$ROOTFS"}"
    fi

    if [ -z "$GUEST_SH" ]; then
        err "No shell found in $ROOTFS (tried: bin/sh, usr/bin/sh, termux paths, find)"
        err "Dropping to app shell — rootfs structure:"
        find "$ROOTFS" -maxdepth 2 -type d 2>/dev/null | head -20 >&2
        return 1
    fi
    log "Guest shell: $GUEST_SH"

    local ENTRYPOINT=""
    for _cand in /entrypoint.sh /entrypoint_root.sh /docker-entrypoint.sh \
                /start.sh /init.sh /run.sh; do
        if [ -x "$ROOTFS$_cand" ]; then ENTRYPOINT="$_cand"; break; fi
    done
    log "Entrypoint: ${ENTRYPOINT:-(none, will use login shell)}"

    local GUEST_PATH=""
    for _d in /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin; do
        [ -d "$ROOTFS$_d" ] && GUEST_PATH="${GUEST_PATH:+$GUEST_PATH:}$_d"
    done
    [ -d "$ROOTFS/data/data/com.termux/files/usr/bin" ] && \
        GUEST_PATH="$GUEST_PATH:/data/data/com.termux/files/usr/bin"

    # FIX: fallback, kdyby GUEST_PATH vyšel prázdný (shell nalezen přes find,
    # ale žádný standardní adresář neexistuje) — prázdné PATH by rozbilo úplně
    # všechno, i ten nalezený shell.
    GUEST_PATH="${GUEST_PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"

    local BINDS="-b /dev -b /proc -b /sys -b /sdcard"
    if [ "$IS_TERMUX_IMAGE" = "1" ]; then
        BINDS="$BINDS -b /dev/urandom:/dev/random"
        [ ! -e /dev/fd ] && BINDS="$BINDS -b /proc/self/fd:/dev/fd"
        for _p in /apex /odm /product /system /system_ext /vendor; do
            [ -d "$_p" ] && [ -x "$_p" ] && BINDS="$BINDS -b $_p"
        done
        BINDS="$BINDS -b $FILES_DIR/tmp:/data/data/com.termux/files/usr/tmp"
        mkdir -p "$ROOTFS/tmp" 2>/dev/null || true
        BINDS="$BINDS -b $ROOTFS/tmp:/dev/shm"
    else
        BINDS="$BINDS -b $FILES_DIR/tmp:/tmp"
        BINDS="$BINDS -b $FILES_DIR/ipc:/run/host_ipc"
    fi

    local PROOT_CWD="/root"
    [ ! -d "$ROOTFS/root" ] && PROOT_CWD="/"

    local TARGET_CMD
    if [ -n "$ENTRYPOINT" ]; then
        TARGET_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                    export PATH=$GUEST_PATH; \
                    cd $PROOT_CWD 2>/dev/null || cd /; \
                    exec $ENTRYPOINT \"\$@\""
    else
        TARGET_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                    export PATH=$GUEST_PATH; \
                    cd $PROOT_CWD 2>/dev/null || cd /; \
                    exec $GUEST_SH --login \"\$@\""
    fi

    exec "$PROOT" -v 0 --kill-on-exit -0 --link2symlink --sysvipc -L \
        --kernel-release='\Linux\proot\6.17.0-proot-distro\6.17.0-proot-distro\aarch64\localdomain\-1\' \
        -r "$ROOTFS" -w "$PROOT_CWD" \
        $BINDS \
        $GUEST_SH -c "$TARGET_CMD" -- "$@"
}

main() {
    case "${1:-}" in
        kali|parrot) boot_classic "$@" ;;
        docker) shift; boot_docker "$@" ;;
        --help|-h|"")
            echo "Usage: boot {kali|parrot} [-- cmd args...]"
            echo "       boot docker <image-name> [-- cmd args...]"
            ;;
        *)
            err "Unknown command: $1"
            echo "Usage: boot {kali|parrot|docker} ..." >&2
            return 1
            ;;
    esac
}

main "$@"
```

---

## `ProotManager.kt` — sjednocené volání

Nahrazuje `setupProotEnvironment()` (řádky 25-145), ruší `deployLauncherScript()`
(smazat celou funkci — už není potřeba, žádný template launcher se negeneruje):

```kotlin
fun setupProotEnvironment(
    context: Context,
    distroId: String,
    rootfsSubdir: String = distroId,  // "parrot", "kali", "docker/<name>"
    mountStorage: Boolean = false,
    customCommand: String? = null,
    hasRoot: Boolean = false
): ProotConfig {
    val prefix = File(context.filesDir, "usr")
    val prootBin = File(prefix, "bin/proot")
    val loaderBin = File(prefix, "bin/loader")
    val tallocLib = File(prefix, "lib/libtalloc.so.2")
    val bootScript = File(prefix, "bin/boot")
    val rootfsDir = File(context.filesDir, "nh/distro/$rootfsSubdir")

    // ... deploy kroky z Fáze 1 (deployDir, deployArchBinaries, bootstrap sentinely,
    //     zshrc, motd atd. — zbytek beze změny oproti současné implementaci) ...

    val bootArgs = mutableListOf(distroId)
    if (!customCommand.isNullOrEmpty()) {
        bootArgs.add("--")
        bootArgs.add(customCommand)
    }
    val fullCommand = mutableListOf("/system/bin/sh", bootScript.absolutePath) + bootArgs

    return ProotConfig(
        command = fullCommand.toTypedArray(),
        cwd = context.filesDir.absolutePath,
        env = emptyArray(),
        prootPath = prootBin.absolutePath,
        rootfsDir = rootfsDir.absolutePath
    )
}
```

Volající místa (dřívější `setupProotEnvironment(context, rootfsDirName = "kali-arm64", ...)`)
přemapovat na `distroId = "kali"`/`"parrot"`, `rootfsSubdir = "docker/$imageName"` pro Docker.

---

## Acceptance criteria

- [ ] `boot kali` a `boot parrot` fungují na **nepoškozené** rootfs (základní regrese)
- [ ] **`boot parrot` na rootfs bez `/root`** (simulovat: `rmdir nh/distro/parrot/root` na
      testovacím zařízení) — skončí v `/`, ne pádem s chybou
- [ ] Ověřit, že `-c` dostává **jeden** argument — v guestu spustit `echo "$0"` a potvrdit,
      že se nerozsype na syntax error (tohle přesně chytí regresi Bugu #2)
- [ ] `boot docker <termux-image>` funguje (klasický i entrypoint případ)
- [ ] `boot docker <alpine-image>` funguje přes `find` fallback
- [ ] Docker image bez shellu → chybová hláška + `exit 1`, app otevře ashell
- [ ] Prázdný `GUEST_PATH` v `boot_docker()` nikdy neprojde do `export PATH=` (test: rootfs jen
      s binárkou nalezenou přes `find`, žádné standardní `bin/` adresáře)
- [ ] `/dev/bus/usb` warning v logu zmizí na zařízeních bez připojeného USB hostu
- [ ] Statický proot + statický loader fungují (žádný `libtalloc.so.2` needed)
