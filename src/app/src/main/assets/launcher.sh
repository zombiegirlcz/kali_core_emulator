#!/system/bin/sh
# [__LOG_PREFIX__] launcher.sh — PRoot container startup for __DISTRO_ID__
#
# Deployed by ProotManager.deployLauncherScript() from template.
# USAGE: launcher.sh [command...]
#   Without arguments: start interactive login shell (entrypoint.sh)
#   With arguments:   execute command inside PRoot container

set -e

# ─── Paths (filled by template engine) ───────────────────
PROOT_BIN="__PROOT_BIN__"
LOADER_BIN="__LOADER_BIN__"
TALLOC_LIB="__TALLOC_LIB__"
ROOTFS_DIR="__ROOTFS_DIR__"
ROOTFS_NAME="__ROOTFS_NAME__"
TMP_DIR="__TMP_DIR__"
FILES_DIR="__FILES_DIR__"
SDCARD_MOUNT="__SDCARD_MOUNT__"
DOCKER_MODE="__DOCKER_MODE__"
LOG_PREFIX="__LOG_PREFIX__"
DISTRO_ID="__DISTRO_ID__"

# ─── Verbose session messages ────────────────────────────
# Běžně tichý launcher: proot/session/su_daemon debug patří do stderr,
# který u su_daemon re-entry proteče přímo do terminálu uživatele. Debug
# se zapíná až při NH_LAUNCHER_DEBUG=1 (ladění launcheru/su_daemon).
log() { [ -n "$NH_LAUNCHER_DEBUG" ] && echo "[$LOG_PREFIX] $*" >&2 || true; }

# ─── Termux environment support ─────────────────────────
# Detekce místa běhu jako proot-distro (constants.py: IS_TERMUX = os.access($PREFIX)):
#   • vevnitř Termuxu JE $PREFIX_UTX čitelný (vlastní data app) → TERMUX_MODE=1;
#   • v app kontextu (jiný UID) je /data/data/com.termux Permission denied → TERMUX_MODE=0
#     i kdyby byl Termux nainstalovaný (perfektní — app stack zůstává);
#   • TERMUX_VERSION je jen bonusový signál (bývá stripped v su/wrapper shellu).
# App filesDir (/data/user/0/...) není pro Termux UID čitelný, proto:
#   • proot/talloc/loader bereme z Termux instalace (PREFIX_TUX);
#   • RUNPATH termux prootu ($PREFIX/lib) řeší libtalloc + libandroid-shmem
#     sám — LD_PRELOAD/LD_LIBRARY_PATH nejsou potřeba;
#   • ROOTFS_DIR se hledá v proot-distro layoutu (NH_ROOTFS → installed-rootfs/ →
#     containers/<name>/rootfs → discovery jediné rootfs) — app cesta NENÍ
#     použitelná (Termux UID na ni nevidí);
#   • bindy na app tmp/ipc se v Termux módu vynechají (nejsou čitelné).
TERMUX_MODE=0
PREFIX_TUX="/data/data/com.termux/files/usr"
TUX_PREFIX_ACCESSIBLE=0
if { [ -n "$TERMUX_VERSION" ] || { [ -r "$PREFIX_TUX" ] && [ -x "$PREFIX_TUX" ]; }; } \
   && [ -x "$PREFIX_TUX/bin/proot" ] && [ -f "$PREFIX_TUX/lib/libtalloc.so.2" ]; then
    TERMUX_MODE=1
    PROOT_TUX="$PREFIX_TUX/bin/proot"
    LOADER_TUX="$PREFIX_TUX/libexec/proot/loader"
    if [ -x "$LOADER_TUX" ]; then
        export PROOT_LOADER="$LOADER_TUX"
    fi
    TMP_DIR="${TMPDIR:-$PREFIX_TUX/tmp}"
    # ─── Rootfs discovery (proot-distro layout) ────────────────
    if [ -n "$NH_ROOTFS" ] && [ -d "$NH_ROOTFS" ]; then
        ROOTFS_DIR="$NH_ROOTFS"
        ROOTFS_NAME="$(basename "$NH_ROOTFS")"
    else
        FOUND_RF=""
        for _cand in \
            "$PREFIX_TUX/var/lib/proot-distro/installed-rootfs/$DISTRO_ID" \
            "$PREFIX_TUX/var/lib/proot-distro/containers/$DISTRO_ID/rootfs"; do
            if [ -d "$_cand" ] && [ -d "$_cand/bin" ]; then
                FOUND_RF="$_cand"; break
            fi
        done
        # fallback: jediná rootfs v proot-distro layoutu
        if [ -z "$FOUND_RF" ]; then
            _cnt=0; _single=""
            for _base in "$PREFIX_TUX/var/lib/proot-distro/installed-rootfs" "$PREFIX_TUX/var/lib/proot-distro/containers"; do
                for _d in "$_base"/*; do
                    if [ -d "$_d" ] && { [ -d "$_d/rootfs" ] || [ -d "$_d/bin" ]; }; then
                        _cnt=$((_cnt + 1)); _single="$_d"
                    fi
                done
            done
            if [ "$_cnt" = "1" ]; then
                if [ -d "$_single/rootfs" ]; then FOUND_RF="$_single/rootfs"; else FOUND_RF="$_single"; fi
            fi
        fi
        if [ -n "$FOUND_RF" ]; then
            ROOTFS_DIR="$FOUND_RF"
            ROOTFS_NAME="$(basename "$FOUND_RF")"
        fi
    fi
    log "Termux PRoot mode: $PROOT_TUX (rootfs=$ROOTFS_DIR, tmp=$TMP_DIR)"
fi

# V Termuxu bez nainstalovaného `proot` balíčku nedává smysl padat na app cesty
# (Termux UID na ně nevidí) — skončíme s jasnou chybou a hintem.
if [ "$TERMUX_MODE" = "0" ] && { [ -n "$TERMUX_VERSION" ] || { [ -r "$PREFIX_TUX" ] && [ -x "$PREFIX_TUX" ]; }; }; then
    echo "[$LOG_PREFIX] ERROR: Termux je nainstalovaný, ale proot stack v $PREFIX_TUX chybí (nebo je nečitelný):" >&2
    echo "[$LOG_PREFIX]   termux proot exists=$([ -x "$PREFIX_TUX/bin/proot" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   libtalloc exists=$([ -f "$PREFIX_TUX/lib/libtalloc.so.2" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   hint: v Termuxu spust 'pkg install proot proot-distro', pak launcher znovu" >&2
    exit 1
fi

# ─── Detect usable PRoot binary ──────────────────────────
PR=""
LOADER=""
if [ "$TERMUX_MODE" = "1" ]; then
    PR="$PROOT_TUX"
    log "Using Termux PRoot: $PROOT_TUX"
elif [ -x "$PROOT_BIN" ] && [ -x "$LOADER_BIN" ] && [ -f "$TALLOC_LIB" ]; then
    PR="$PROOT_BIN"
    LOADER="$LOADER_BIN"
    export PROOT_LOADER="$LOADER"
    # ⚠️ NEPOUŽÍVAT LD_PRELOAD pro talloc! App proot je Termux build s RUNPATH
    # /data/data/com.termux/files/usr/lib (na zařízení neexistuje), takže talloc
    # se najde jen přes env. LD_PRELOAD ale TVRDĚ rozbije guesty s bionic linkerem
    # (Termux rootfs): "CANNOT LINK EXECUTABLE /bin/sh: library libtalloc.so.2
    # not found" — preload cesta je host-only a v guestu neexistuje, bionic na
    # rozdíl od glibc selže napevno. LD_LIBRARY_PATH=$FILES_DIR je bezpečná:
    # talloc najde proot (NEEDED libtalloc.so.2), ale guest (bionic i glibc) nemá
    # v NEEDED nic z filesDir → projde na /system/lib64 resp. rootfs knihovny.
    export LD_LIBRARY_PATH="$FILES_DIR:/system/lib64:/system/lib"
    log "Using dynamic PRoot: $PROOT_BIN (LD_LIBRARY_PATH=$FILES_DIR + Android libdirs)"
else
    echo "[$LOG_PREFIX] ERROR: No usable PRoot binary found" >&2
    echo "[$LOG_PREFIX]   dynamic: exists=$([ -x "$PROOT_BIN" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   loader: exists=$([ -x "$LOADER_BIN" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   talloc: exists=$([ -f "$TALLOC_LIB" ] && echo yes || echo no)" >&2
    echo "[$LOG_PREFIX]   termux: available=$([ -n "$TERMUX_VERSION" ] && [ -x "$PREFIX_TUX/bin/proot" ] && echo yes || echo no)" >&2
    exit 1
fi

export PROOT_TMP_DIR="$TMP_DIR"
export TMPDIR="$TMP_DIR"

# ─── Rootfs validation ───────────────────────────────────
if [ ! -d "$ROOTFS_DIR" ]; then
    echo "[$LOG_PREFIX] ERROR: Rootfs dir not found: $ROOTFS_DIR" >&2
    if [ "$TERMUX_MODE" = "1" ]; then
        echo "[$LOG_PREFIX]   hint: v Termux módu nastav NH_ROOTFS=/cesta/k/rootfs (app dir není pro Termux čitelný)" >&2
    fi
    exit 1
fi

# ─── Guest bootstrap shell (GUEST_SH) ────────────────────
# Různé obrazy mají sh na různých místech:
#   • klasický rootfs (kali/parrot/debian): /bin/sh
#   • termux-docker image: /bin je prázdný — shell je /system/bin/sh
#     (bundled Android) a/nebo /data/data/com.termux/files/usr/bin/sh
#     (guest TERMUX PREFIX uvnitř image)
# GUEST_SH je cesta RELATIVNÍ k rootfs (předává se přes proot).
GUEST_SH=""
for _cand in "$ROOTFS_DIR/bin/sh" "$ROOTFS_DIR/system/bin/sh" \
        "$ROOTFS_DIR/data/data/com.termux/files/usr/bin/sh" \
        "$ROOTFS_DIR/usr/bin/sh"; do
    if [ -x "$_cand" ]; then
        GUEST_SH="${_cand#"$ROOTFS_DIR"}"
        break
    fi
    # Termux mód: app filesDir rootfs není čitelný -> -x selže matoucím
    # Permission denied, ale proot by pak taky spadl — hlídáme nahoře.
    if [ "$TERMUX_MODE" = "1" ] && [ -e "$_cand" ] && [ ! -r "$_cand" ]; then
        echo "[$LOG_PREFIX] ERROR: Termux mód: shell v rootfs je nečitelný: $_cand" >&2
        echo "[$LOG_PREFIX]   hint: nainstaluj distro: 'proot-distro install debian' a spouštěj s NH_ROOTFS," >&2
        echo "[$LOG_PREFIX]   nebo nastav NH_ROOTFS=/cesta/k/rootfs na čitelnou cestu" >&2
        exit 1
    fi
done
if [ -z "$GUEST_SH" ]; then
    echo "[$LOG_PREFIX] ERROR: No guest shell found in rootfs (tried: $ROOTFS_DIR/bin/sh, $ROOTFS_DIR/system/bin/sh, guest PREFIX usr/bin/sh)" >&2
    exit 1
fi
log "Guest shell: $GUEST_SH"

log "Starting $DISTRO_ID session ($ROOTFS_NAME, docker=$DOCKER_MODE)"

# ─── PRoot bind mounts ──────────────────────────────────
BINDS="-b /dev -b /proc -b /sys"
if [ "$TERMUX_MODE" = "1" ]; then
    # App filesDir je pro Termux nečitelný — bindneme Termux tmp, ipc vynecháme.
    # Device/system bindy dle proot-distro (login/bindings.py): /dev/random
    # mapping, /dev/fd, Android system diry (jen čitelné/přechoditelné).
    BINDS="$BINDS -b $TMP_DIR"
    BINDS="$BINDS -b /dev/urandom:/dev/random"
    if [ ! -e /dev/fd ]; then
        BINDS="$BINDS -b /proc/self/fd:/dev/fd"
    fi
    for _p in /apex /odm /product /system /system_ext /vendor; do
        if [ -d "$_p" ] && [ -x "$_p" ]; then
            BINDS="$BINDS -b $_p"
        fi
    done
    # /dev/shm: proot-distro binduje $rootfs/tmp → /dev/shm (Termux tmp nemá shm)
    mkdir -p "$ROOTFS_DIR/tmp" 2>/dev/null || true
    BINDS="$BINDS -b $ROOTFS_DIR/tmp:/dev/shm"
else
    BINDS="$BINDS -b $FILES_DIR/tmp:$TMP_DIR"
    BINDS="$BINDS -b $FILES_DIR/ipc:/run/host_ipc"
fi
BINDS="$BINDS $SDCARD_MOUNT"
BINDS="$BINDS __EXTRA_ROOT_MOUNTS__"
# Extra bindy z `nh distro login <distro> --bind src:dst` (host → guest).
# Předává se jako NH_EXTRA_BINDS="-b /abs/src:/dst" odvolatele (nh skript).
if [ -n "$NH_EXTRA_BINDS" ]; then
    BINDS="$BINDS $NH_EXTRA_BINDS"
fi

# PROOT_LOADER / LD_LIBRARY_PATH: proot sám načte talloc (má je v environ),
# ale tyto host cesty se NESMÍ předat guest procesům — jinak guest ld.so hlásí
# "ERROR: ld.so: object '.../libtalloc.so.2' from LD_PRELOAD cannot be preloaded"
# u každého /bin/sh pod prootem (bionic linker rovnou selže CANNOT LINK).
#
# proot NEMÁ flag na exclude proměnné z guest environu (-E neexistuje —
# odtud "unknown option '-E'"). Jediná funkční cesta: nechat proot spustit
# /bin/sh jako první guest proces (zdědí LD_LIBRARY_PATH/PROOT_LOADER z hosta),
# a hned v něm `unset` obě proměnné PŘED exec cílového programu — po exec()
# už v novém image nejsou.
# Proot extensions dle proot-distro (login/proot_cmd.py): --sysvipc (SysV IPC
# emulace), -L (lstat→stat fix pro dpkg symlink warningy), --kernel-release
# (fake utsname — proot parsuje pole oddělená \, formát bez mezer kvůli
# word-splitting PROOT_FLAGS; hwcap pole (-1) je povinné).
PROOT_FLAGS="-v 0 --kill-on-exit -0 --link2symlink --sysvipc -L --kernel-release=\\Linux\\proot\\6.17.0-proot-distro\\6.17.0-proot-distro\\aarch64\\localdomain\\-1\\"
PROOT_CWD="/root"
# Termux-mód: termux-type rootfs nemusí mit /root → adaptivní cwd
if [ "$TERMUX_MODE" = "1" ] && [ ! -d "$ROOTFS_DIR/root" ]; then
    if [ -d "$ROOTFS_DIR/data/data/com.termux/files/home" ]; then
        PROOT_CWD="/data/data/com.termux/files/home"
    else
        PROOT_CWD="/"
    fi
fi

# ─── Guest env parity s proot-distro (Termux mód) ────────
# proot-distro předává guestu ČISTÉ environment (execvpe(proot, argv, env) —
# host env se do guesta NEkopíruje): distro PATH + $PREFIX/bin + /system/bin,
# HOME/USER/TERM, MOZ_FAKE_NO_SANDBOX=1, PULSE_SERVER. V Termux módu to
# napodobíme `env -i` (proot zdědí jen tohle → guest taky). PROOT_LOADER a
# PROOT_TMP_DIR/TMPDIR si proot musí dostat (jinak ztratí loader/tmp).
# V app módu se env ponechává (launcher env je už čistý, LD_LIBRARY_PATH
# necháváme — proot ho potřebuje pro talloc).
ENV_PREFIX=""
if [ "$TERMUX_MODE" = "1" ]; then
    ENV_PREFIX="env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games:$PREFIX_TUX/bin:/system/bin:/system/xbin HOME=/root USER=root TERM=${TERM:-xterm-256color} MOZ_FAKE_NO_SANDBOX=1 PULSE_SERVER=127.0.0.1 PROOT_TMP_DIR=$TMP_DIR TMPDIR=$TMP_DIR"
    if [ -x "$LOADER_TUX" ]; then
        ENV_PREFIX="$ENV_PREFIX PROOT_LOADER=$LOADER_TUX"
    fi
    log "Guest env: env -i ($ENV_PREFIX)"
fi

# ─── su_daemon raw-exec mode: launcher.sh -- <guest-program> [args...] ──────
# Used by the host su_daemon for real root escalation. Instead of running the
# requested command on the bare host (which could damage the device), we
# RE-ENTER this PRoot sandbox as real root. PRoot then confines every path
# access to the guest rootfs, so `sudo chmod -R / ...` can never touch the
# host filesystem. Every token after `--` is forwarded verbatim to proot so
# no shell re-quoting loss occurs.
#
#   launcher.sh --           -> interactive root login shell (bare `su`)
#   launcher.sh -- cmd a b   -> exec cmd(a,b) inside proot
#   launcher.sh -- /bin/sh -c '<cmd>' -> shell command string (su -c)
if [ "$1" = "--" ]; then
    shift
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export HOME=/root
    WDIR="$PROOT_CWD"
    if [ -n "$NH_CWD" ] && [ -d "$ROOTFS_DIR/${NH_CWD#/}" ]; then
        WDIR="$NH_CWD"
    fi
    # su_daemon posílá "/system/bin/sh" pro `su -c` a bare `su` (a po svém
    # rewrite i "/bin/sh") — guest shell ale může bydlet jinde (termux-docker
    # image: /system/bin/sh, klasický rootfs: /bin/sh). Namapujeme na GUEST_SH.
    if [ $# -gt 0 ] && [ "$1" != "$GUEST_SH" ] && \
       { [ "$1" = "/bin/sh" ] || [ "$1" = "/system/bin/sh" ]; }; then
        _gsh="$GUEST_SH"
        shift
        set -- "$_gsh" "$@"
    fi
    log "su_daemon exec: $*"
    if [ $# -gt 0 ]; then
        # Route through guest shell only to strip the host LD_LIBRARY_PATH, then
        # exec the exact args verbatim (no shell re-quoting). PRoot confines
        # every path access to the guest rootfs.
        exec $ENV_PREFIX "$PR" $PROOT_FLAGS -r "$ROOTFS_DIR" -w "$WDIR" $BINDS \
            $GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; exec "$@"' -- "$@"
    else
        # Bare `su` -> interactive root login shell inside proot. Rootfs bez
        # bash/zsh (termux-docker) dostane login přes samotný GUEST_SH.
        exec $ENV_PREFIX "$PR" $PROOT_FLAGS -r "$ROOTFS_DIR" -w "$WDIR" $BINDS \
            $GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; if command -v zsh >/dev/null 2>&1; then exec zsh --login; elif command -v bash >/dev/null 2>&1; then exec bash --login; else exec '"$GUEST_SH"' -l; fi'
    fi
fi

# ─── Bootstrap phase (first run only) ────────────────────
# Bootstrap MUST run BEFORE entrypoint: first launch configures the OS
# (apt, packages, users), only then the interactive session starts.
if [ "$DOCKER_MODE" != "1" ] && [ -x "$ROOTFS_DIR/root/bootstrap.sh" ] && { [ -f "$ROOTFS_DIR/root/.bootstrap_required" ] || [ ! -f "$ROOTFS_DIR/root/.setup_done" ]; }; then
    echo "[$LOG_PREFIX] First run detected — running bootstrap..." >&2
    if $ENV_PREFIX "$PR" $PROOT_FLAGS -r "$ROOTFS_DIR" -w "$PROOT_CWD" $BINDS \
        $GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; exec /bin/bash /root/bootstrap.sh'; then
        rm -f "$ROOTFS_DIR/root/.bootstrap_required"
        log "Bootstrap completed"
    else
        echo "[$LOG_PREFIX] WARNING: bootstrap failed — keeping .bootstrap_required (retry next launch)" >&2
    fi
fi

# ─── Build and execute PRoot command ────────────────────
# Docker-mode: preferuje /bin/bash --login; termux-docker image má /bin prázdný
# → shell je /system/bin/sh (mksh, -l funguje) nebo guest PREFIX bash.
LOGIN_SHELL="/bin/bash --login"
if [ "$DOCKER_MODE" = "1" ]; then
    if [ -x "$ROOTFS_DIR/data/data/com.termux/files/usr/bin/bash" ]; then
        LOGIN_SHELL="/data/data/com.termux/files/usr/bin/bash --login"
    elif [ -x "$ROOTFS_DIR/system/bin/sh" ]; then
        LOGIN_SHELL="/system/bin/sh -l"
    elif [ ! -x "$ROOTFS_DIR/bin/bash" ]; then
        LOGIN_SHELL="$GUEST_SH -l"
    fi
fi

if [ "$DOCKER_MODE" = "1" ]; then
    set -- $ENV_PREFIX "$PR" $PROOT_FLAGS \
        -r "$ROOTFS_DIR" \
        -w "$PROOT_CWD" \
        $BINDS \
        $GUEST_SH -c "unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; cd /root && exec $LOGIN_SHELL \"\$@\"" -- "$@"
elif [ -f "$ROOTFS_DIR/root/entrypoint.sh" ]; then
    set -- $ENV_PREFIX "$PR" $PROOT_FLAGS \
        -r "$ROOTFS_DIR" \
        -w "$PROOT_CWD" \
        $BINDS \
        $GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; exec /bin/sh /root/entrypoint.sh "$@"' -- "$@"
else
    set -- $ENV_PREFIX "$PR" $PROOT_FLAGS \
        -r "$ROOTFS_DIR" \
        -w "$PROOT_CWD" \
        $BINDS \
        $GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; cd /root && exec /bin/bash --login "$@"' -- "$@"
fi

log "exec: $@"
exec "$@"