"""Modal build pipeline for kali_core_emulator.

Volume layout after setup:
  /vol/keys/release.jks        – signing keystore (persistent)
  /vol/src/                    – project source tree (upload once, update on change)
  /vol/gradle-cache/           – gradle dependency cache (persistent)
  /vol/builds/app-debug.apk    – latest built APK

Setup:
  1) modal secret create build-secrets RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)
  2) modal run modal_build.py init     # store keystore
  3) modal run modal_build.py upload   # upload source
  4) modal run modal_build.py all      # compile native + build APK

Individual steps:
  modal run modal_build.py native      # NDK cross-compile C binaries + usr tools (nano/rsync/sed/rg)
  modal run modal_build.py build       # Gradle assembleDebug only
  modal run modal_build.py list        # show Volume contents
"""

# Force rebuild marker: 2026-07-07T02:00:00Z

import modal
import os
import shutil
import subprocess
import sys

app = modal.App("kali-core-build")

ANDROID_SDK_ROOT = "/opt/android-sdk"

build_vol = modal.Volume.from_name("kali-build-data", create_if_missing=True)

_IGNORE_PARTS = frozenset({".git", ".gradle", "__pycache__", "node_modules", "logcat.log", "build.log", "top.log"})

# Cross-compiled native binaries that MUST NOT be deleted by upload_src's
# rsync --delete (they are NOT present in the baked image; they only exist
# on the Volume after build_native compiles them). Without these excludes,
# `mbuild build` (upload → build, no native step) would wipe them and the
# APK would ship without su_daemon/su_wrapper/usb_bridge.
# Keep in sync with build_native outputs and the NATIVE_BINARIES list in
# the mbuild script. AGENTS.md „Native build pipeline“.
_NATIVE_ASSET_EXCLUDES = [
    "assets/su_daemon",
    "assets/su_wrapper",
    "assets/usb_bridge",
    # nano/rsync/sed/rg + glibc libs — generuje je build_native do assets/usr/.
    # rsync --delete by je jinak smazal (nejsou v baked image).
    "assets/usr",
]


def _ignore_path(p):
    """Return True for paths that should be EXCLUDED (ignore=True = skip)."""
    parts = p.parts
    for i, part in enumerate(parts):
        if part in _IGNORE_PARTS:
            return True
        if part == "build" and i > 0 and parts[i - 1] == "app":
            return True
    return False


# ── Image with Android SDK + JDK 21 + NDK ────────────────────────────────────
NDK_VERSION = "r28"
NDK_DIR = f"/opt/android-ndk-{NDK_VERSION}"

base_image = (
    modal.Image.from_registry("eclipse-temurin:21-jdk")
    .apt_install("unzip", "wget", "git", "file", "rsync", "python3", "python3-pip", "python-is-python3")
    .run_commands(
        "mkdir -p /opt/android-sdk/cmdline-tools",
        "wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        " -O /tmp/cmdline-tools.zip",
        "unzip -q /tmp/cmdline-tools.zip -d /opt/android-sdk/cmdline-tools",
        "mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest",
        "rm /tmp/cmdline-tools.zip",
        "yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true",
        "/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --install"
        " 'platforms;android-36' 'build-tools;36.0.0'",
        # Install NDK for C/C++ compilation
        f"wget -q https://dl.google.com/android/repository/android-ndk-{NDK_VERSION}-linux.zip -O /tmp/ndk.zip",
        f"unzip -q /tmp/ndk.zip -d /opt/",
        "rm /tmp/ndk.zip",
        f"ls {NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin/*clang* | head -3",
    )
    .env({
        "ANDROID_HOME": ANDROID_SDK_ROOT,
        "ANDROID_SDK_ROOT": ANDROID_SDK_ROOT,
        "JAVA_HOME": "/opt/java/openjdk",
        "GRADLE_USER_HOME": "/vol/gradle-cache",
    })
)

# Static image: base + source baked in (for upload_src)
source_image = base_image.add_local_dir(
    "/root/kali_core_emulator",
    remote_path="/src-baked",
    ignore=_ignore_path,
)


# ── Usr tools: nano/rsync/sed (glibc bridge) + ripgrep (Bionic) ──────────────
# Runtime prefix na zařízení — APK je nese v assets/usr/, aplikace je pak
# extrahuje sem. Binárky jdou na $PREFIX/bin, glibc/ncursesw na $PREFIX/lib.
USRTOOLS_PREFIX = "/data/user/0/com.linux_core/files/usr"
USRTOOLS_API = 28  # minSdk/targetSdk z app/build.gradle.kts

# Verze zdrojáků (piny — ftp.gnu.org a tagy na GitHubu staré verze archivují)
NCURSES_VER = "6.5"
SED_VER = "4.9"
RSYNC_VER = "3.3.0"
NANO_VER = "8.2"
RG_VER = "14.1.1"

# Image pro build_native: k Android SDK/NDK přidá glibc cross toolchain
# (nano/rsync/sed) a Rust toolchain s targetem aarch64-linux-android (rg).
usrtools_image = (
    base_image
    .run_commands(
        # glibc cross kompilátor (gcc-aarch64-linux-gnu je v universe)
        "apt-get update -qq || true",
        "apt-get install -y -qq software-properties-common 2>/dev/null || true",
        "add-apt-repository -y universe 2>/dev/null || true",
        "apt-get update -qq",
        "apt-get install -y -qq "
        "gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu "
        "libc6-dev-arm64-cross libc6-arm64-cross "
        "build-essential make autoconf automake pkg-config "
        "curl wget xz-utils bzip2 file patchelf",
        # Rust + Android target (Bionic)
        "curl -sSf https://sh.rustup.rs -o /tmp/rustup-init.sh",
        "sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain stable",
        "/root/.cargo/bin/rustup target add aarch64-linux-android",
    )
    .env({
        "PATH": "/root/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    })
)


# ── Upload source to Volume ──────────────────────────────────────────────────
@app.function(
    image=source_image,
    volumes={"/vol": build_vol},
    timeout=600,
    memory=2048,
)
def upload_src():  # force rebuild marker 2026-07-07
    """Copy source tree from image into the persistent Volume."""
    dest = "/vol/src"

    if os.path.isdir(dest):
        print(f"[upload] Source already exists at {dest}.  Updating with rsync...")
        cmd = ["rsync", "-a", "--delete", "/src-baked/", dest]
        for excl in _NATIVE_ASSET_EXCLUDES:
            cmd += ["--exclude", excl]
        subprocess.run(cmd, check=True)
    else:
        print(f"[upload] Copying source tree to {dest} ...")
        shutil.copytree("/src-baked", dest, symlinks=True)

    build_vol.commit()
    print("[upload] Done.  Source tree committed to Volume.")


# ── Initialize signing key on Volume ─────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("build-secrets")],
    timeout=600,
    memory=1024,
)
def init_keys():
    """Store the signing keystore on the persistent Volume.

    Reads RELEASE_JKS_BASE64 from a Modal Secret.  Create the secret first:

        modal secret create build-secrets \\
          RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)
    """
    keys_dir = "/vol/keys"
    os.makedirs(keys_dir, exist_ok=True)

    key_path = os.path.join(keys_dir, "release.jks")

    if os.path.exists(key_path):
        print(f"[init] Key already exists at {key_path} (skipping).")
        return

    secret_key = os.environ.get("RELEASE_JKS_BASE64")
    if secret_key:
        import base64
        with open(key_path, "wb") as f:
            f.write(base64.b64decode(secret_key))
    else:
        print(
            "[init] RELEASE_JKS_BASE64 not set.  Create the secret first:\n"
            "  modal secret create build-secrets \\\n"
            "    RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)"
        )
        return

    os.chmod(key_path, 0o600)
    build_vol.commit()
    print(f"[init] Key stored at {key_path}")


# ── Build Native USB bridge binaries + usr tools (NDK / glibc / Rust) ──────
@app.function(
    image=usrtools_image,
    volumes={"/vol": build_vol},
    timeout=1800,
    memory=8192,
    cpu=4,
)
def build_native():
    """Cross-compile native binaries into the APK assets.

    NDK (Bionic):
      jniLibs/arm64-v8a/libusbfd_exporter.so
      assets/usb_bridge, assets/su_daemon, assets/su_wrapper

    Usr tools (assets/usr/ — běží přímo na hostu, bez PRootu):
      assets/usr/bin/{sed,rsync,nano}   glibc bridge ($ORIGIN/../lib rpath)
      assets/usr/lib/*                  glibc + libncursesw.so.6 + terminfo fallbacky
      assets/usr/bin/rg                 nativní Bionic (aarch64-linux-android)
      /vol/builds/usrtools.tar.gz       bin/+lib/ → extrahovat do $PREFIX
    """
    src_dir = "/vol/src"
    cpp_dir = os.path.join(src_dir, "app/src/main/cpp")

    # NDK toolchain
    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    cc = f"{tc_bin}/aarch64-linux-android24-clang"

    # Output paths
    jnilibs_dir = os.path.join(src_dir, "app/src/main/jniLibs/arm64-v8a")
    assets_dir = os.path.join(src_dir, "app/src/main/assets")
    os.makedirs(jnilibs_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)

    so_path = os.path.join(jnilibs_dir, "libusbfd_exporter.so")
    bin_path = os.path.join(assets_dir, "usb_bridge")

    # ── 1. Build libusbfd_exporter.so (JNI shared library) ──────────────────
    print("─" * 60)
    print("[native] Building libusbfd_exporter.so ...")
    jni_src = os.path.join(cpp_dir, "usbfd_jni.c")
    cmd = [cc, "-shared", "-fPIC", "-o", so_path, jni_src, "-llog", "-landroid"]
    print(f"  {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"  OK  ({os.path.getsize(so_path):,} B)")

    # ── 2. Build usb_bridge (static binary for PRoot) ────────────────────────
    print("─" * 60)
    print("[native] Building usb_bridge (static)...")
    bridge_src = os.path.join(cpp_dir, "usb_bridge.c")
    cmd = [cc, "-static", "-o", bin_path, bridge_src]
    print(f"  {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"  OK  ({os.path.getsize(bin_path):,} B)")

    # ── 3. Build su_daemon (host root daemon) ──────────────────────────────────
    print("─" * 60)
    print("[native] Building su_daemon...")
    daemon_src = os.path.join(cpp_dir, "su_daemon.c")
    daemon_bin_path = os.path.join(assets_dir, "su_daemon")
    cmd = [cc, "-o", daemon_bin_path, daemon_src]
    print(f"  {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"  OK  ({os.path.getsize(daemon_bin_path):,} B)")

    # ── 4. Build su_wrapper (static binary for PRoot) ─────────────────────────
    print("─" * 60)
    print("[native] Building su_wrapper (static)...")
    wrapper_src = os.path.join(cpp_dir, "su_wrapper.c")
    wrapper_bin_path = os.path.join(assets_dir, "su_wrapper")
    cmd = [cc, "-static", "-o", wrapper_bin_path, wrapper_src]
    print(f"  {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"  OK  ({os.path.getsize(wrapper_bin_path):,} B)")

    # ── 5. Usr tools: sed/rsync/nano (glibc bridge) + rg (Bionic) ───────────
    # Výstup: assets/usr/{bin,lib} (jde do APK) + /vol/builds/usrtools.tar.gz.
    print("─" * 60)
    print("[native] Building usr tools (sed/rsync/nano/rg) ...")
    _build_usrtools(
        os.path.join(assets_dir, "usr"),
        "/vol/builds",
    )

    # Commit to Volume
    build_vol.commit()
    print(f"[native] Binaries committed to Volume.")


# ── Usr tools build: nano/rsync/sed (glibc bridge) + ripgrep (Bionic) ───────
def _build_usrtools(assets_usr, builds_dir):
    """Cross-compile sed/rsync/nano (glibc bridge) + ripgrep (native Bionic).

    assets_usr: /vol/src/app/src/main/assets/usr  (bin/ + lib/ → jde do APK)
    builds_dir: /vol/builds                       (usrtools.tar.gz → $PREFIX)

    Šířka "$ORIGIN/../lib" rpathu se nastavuje přes LDFLAGS s '$$ORIGIN' (make
    expanduje $$ na $; jednoduché uvozovky nechá shellu). Interpreter (PT_INTERP)
    je absolutní: $PREFIX/lib/ld-linux-aarch64.so.1 — kvůli tomu musí binárky
    ležet přesně v $PREFIX/bin a glibc v $PREFIX/lib.
    """
    import tarfile

    PREFIX = USRTOOLS_PREFIX
    WORK = "/tmp/usrtools"
    STAGE = "/tmp/usrtools-stage"
    SYSROOT_LIB = "/usr/aarch64-linux-gnu/lib"
    READELF = "aarch64-linux-gnu-readelf"
    CC = "aarch64-linux-gnu-gcc"
    CFLAGS = "-O2"
    LDFLAGS = (
        f"-Wl,--dynamic-linker={PREFIX}/lib/ld-linux-aarch64.so.1 "
        f"-Wl,-rpath='$$ORIGIN/../lib'"
    )

    BIN = os.path.join(assets_usr, "bin")
    LIB = os.path.join(assets_usr, "lib")
    for d in (WORK, STAGE, BIN, LIB, builds_dir):
        os.makedirs(d, exist_ok=True)

    # Cross-strip wrapper: `install -r` vola `strip` z PATH; host strip (x86_64)
    # nerozpozna cross binачku => vlozme symlink `strip`->aarch64-linux-gnu-strip.
    STRIP_DIR = os.path.join(WORK, "stripbin")
    os.makedirs(STRIP_DIR, exist_ok=True)
    wrapper = os.path.join(STRIP_DIR, "strip")
    if not os.path.lexists(wrapper):
        cross_strip = shutil.which("aarch64-linux-gnu-strip")
        if cross_strip:
            os.symlink(cross_strip, wrapper)
        else:
            # Cross-strip v obrazu neni -> no-op strip (binarky zustanou
            # unstripped; install -s tak projde bez host strip chyby).
            with open(wrapper, "w") as f:
                f.write("#!/bin/sh\nexit 0\n")
            os.chmod(wrapper, 0o755)

    def run(cmd, **kw):
        print(f"  $ {' '.join(cmd) if isinstance(cmd, list) else cmd}")
        env = kw.pop("env", None) or dict(os.environ)
        env["PATH"] = STRIP_DIR + os.pathsep + env.get("PATH", "")
        kw["env"] = env
        subprocess.run(cmd, check=True, **kw)

    def fetch(url, dest):
        if not os.path.exists(dest):
            run(["wget", "-q", url, "-O", dest])

    def extract(archive, dest):
        run(["tar", "xf", archive, "-C", dest])

    def needed_libs(path):
        dyn = subprocess.run([READELF, "-d", path], capture_output=True, text=True).stdout
        return [l.split("[")[-1].rstrip("]") for l in dyn.splitlines() if "NEEDED" in l]

    def verify_glibc_bridge(path, name):
        el = subprocess.run([READELF, "-l", path], capture_output=True, text=True).stdout
        interp = next(
            (l.split(":")[-1].strip().rstrip("]") for l in el.splitlines() if "interpreter" in l),
            None,
        )
        dyn = subprocess.run([READELF, "-d", path], capture_output=True, text=True).stdout
        rpath = [l.strip() for l in dyn.splitlines() if "RPATH" in l or "RUNPATH" in l]
        print(f"    {name}: interpreter={interp}")
        print(f"    {name}: {'; '.join(rpath) if rpath else '(no rpath)'}")
        if interp != f"{PREFIX}/lib/ld-linux-aarch64.so.1" or \
                not any("$ORIGIN/../lib" in r for r in rpath):
            # Bezpečnostní síť: oprava přes patchelf
            print(f"    {name}: oprava interpreter/rpath přes patchelf")
            run(["patchelf", "--set-interpreter", f"{PREFIX}/lib/ld-linux-aarch64.so.1",
                 "--set-rpath", "$ORIGIN/../lib", path])
            el = subprocess.run([READELF, "-l", path], capture_output=True, text=True).stdout
            interp = next(
                (l.split(":")[-1].strip().rstrip("]") for l in el.splitlines() if "interpreter" in l),
                None,
            )
            if interp != f"{PREFIX}/lib/ld-linux-aarch64.so.1":
                raise SystemExit(f"[usrtools] {name}: interpreter se nepodařilo opravit")

    # ── 1. ncursesw 6.5 (wide-char, terminfo fallbacky zabudované) ─────────
    print("─" * 60)
    print(f"[usrtools] ncurses-{NCURSES_VER} (wide-char) ...")
    nc_dir = os.path.join(WORK, f"ncurses-{NCURSES_VER}")
    if not os.path.isdir(nc_dir):
        fetch(f"https://ftp.gnu.org/gnu/ncurses/ncurses-{NCURSES_VER}.tar.gz",
              os.path.join(WORK, "ncurses.tar.gz"))
        extract(os.path.join(WORK, "ncurses.tar.gz"), WORK)
    run(["./configure", "--host=aarch64-linux-gnu", f"--prefix={STAGE}",
         "--enable-widec", "--without-debug", "--without-ada",
         "--disable-db-install",  # terminfo DB neinstalujeme (fallbacky zabudovane
                                  # v lib) => preskokneme install data (cross tic na hostu)
         "--without-cxx-binding",  # C++ binding nepotřebujeme (C-only nástroje);
                                  # mimo jiné obejde ncurses-C++ vs GCC>=13 bug
         "--without-manpages", "--without-tests",
         "--with-fallbacks=xterm,xterm-256color,screen,screen-256color,linux,vt100,ansi",
         f"CC={CC}", f"CFLAGS={CFLAGS}"], cwd=nc_dir)
    run(["make", "-j8"], cwd=nc_dir)
    run(["make", "install"], cwd=nc_dir)

    # ── 2. sed ─────────────────────────────────────────────────────────────
    print("─" * 60)
    print(f"[usrtools] sed-{SED_VER} ...")
    sed_dir = os.path.join(WORK, f"sed-{SED_VER}")
    if not os.path.isdir(sed_dir):
        fetch(f"https://ftp.gnu.org/gnu/sed/sed-{SED_VER}.tar.xz", os.path.join(WORK, "sed.tar.xz"))
        extract(os.path.join(WORK, "sed.tar.xz"), WORK)
    run(["./configure", "--host=aarch64-linux-gnu", f"--prefix={PREFIX}",
         "--disable-nls",
         f"CC={CC}", f"CFLAGS={CFLAGS}", f"LDFLAGS={LDFLAGS}"], cwd=sed_dir)
    run(["make", "-j8"], cwd=sed_dir)
    shutil.copy2(os.path.join(sed_dir, "sed/sed"), os.path.join(BIN, "sed"))
    verify_glibc_bridge(os.path.join(BIN, "sed"), "sed")
    print(f"    ✓ sed ({os.path.getsize(os.path.join(BIN, 'sed')):,} B)")

    # ── 3. rsync ───────────────────────────────────────────────────────────
    print("─" * 60)
    print(f"[usrtools] rsync-{RSYNC_VER} ...")
    rsync_dir = os.path.join(WORK, f"rsync-{RSYNC_VER}")
    if not os.path.isdir(rsync_dir):
        fetch(f"https://download.samba.org/pub/rsync/src/rsync-{RSYNC_VER}.tar.gz",
              os.path.join(WORK, "rsync.tar.gz"))
        extract(os.path.join(WORK, "rsync.tar.gz"), WORK)
    # rsync 3.3.0 bug: syscall.c do_lseek() deklaruje `off64_t lseek64();`
    # bez prototypu a pak ji vola se 3 arg -> konflikt s glibc deklaraci.
    # Opravime na plny prototyp (shodny s glibc).
    _sc = os.path.join(rsync_dir, "syscall.c")
    with open(_sc) as _f:
        _src = _f.read()
    _src = _src.replace("OFF_T lseek64();", "extern OFF_T lseek64(int, OFF_T, int);")
    _src = _src.replace("off64_t lseek64();", "extern off64_t lseek64(int, off64_t, int);")
    with open(_sc, "w") as _f:
        _f.write(_src)
    # rsync 3.3.0 + GCC>=14: `void (*bomb)();` (K&R bez prototype) se vola se
    # 3 agy -> tvrdy error. Prototyp doplnime (shodny s pool_alloc.h).
    _pa = os.path.join(rsync_dir, "lib", "pool_alloc.c")
    with open(_pa) as _f:
        _psrc = _f.read()
    _psrc = _psrc.replace("(*bomb)();", "(*bomb)(const char*, const char*, int);")
    with open(_pa, "w") as _f:
        _f.write(_psrc)
    run(["./configure", "--host=aarch64-linux-gnu", f"--prefix={PREFIX}",
         "--disable-openssl", "--disable-xxhash", "--disable-zstd", "--disable-lz4",
         "--with-included-zlib=yes", "--with-included-popt", "--disable-md2man",
         f"CC={CC}", f"CFLAGS={CFLAGS} -Wno-incompatible-pointer-types -Wno-implicit-function-declaration",
         f"LDFLAGS={LDFLAGS}"], cwd=rsync_dir)
    run(["make", "-j8"], cwd=rsync_dir)
    shutil.copy(os.path.join(rsync_dir, "rsync"), os.path.join(BIN, "rsync"))
    verify_glibc_bridge(os.path.join(BIN, "rsync"), "rsync")
    print(f"    ✓ rsync ({os.path.getsize(os.path.join(BIN, 'rsync')):,} B)")

    # ── 4. nano (tiny, ncursesw ze stage) ──────────────────────────────────
    print("─" * 60)
    print(f"[usrtools] nano-{NANO_VER} (tiny) ...")
    nano_dir = os.path.join(WORK, f"nano-{NANO_VER}")
    if not os.path.isdir(nano_dir):
        fetch(f"https://ftp.gnu.org/gnu/nano/nano-{NANO_VER}.tar.xz", os.path.join(WORK, "nano.tar.xz"))
        extract(os.path.join(WORK, "nano.tar.xz"), WORK)
    nano_env = dict(os.environ)
    nano_env["PKG_CONFIG_LIBDIR"] = os.path.join(STAGE, "lib/pkgconfig")
    nano_env["PKG_CONFIG_PATH"] = os.path.join(STAGE, "lib/pkgconfig")
    run(["./configure", "--host=aarch64-linux-gnu", f"--prefix={PREFIX}",
         "--enable-tiny", "--disable-nls",
         f"CC={CC}", f"CFLAGS={CFLAGS}",
         f"CPPFLAGS=-I{STAGE}/include -I{STAGE}/include/ncursesw",
         f"LDFLAGS={LDFLAGS} -L{STAGE}/lib",
         f"LIBS=-lncursesw"], cwd=nano_dir, env=nano_env)
    run(["make", "-j8"], cwd=nano_dir, env=nano_env)
    shutil.copy(os.path.join(nano_dir, "src", "nano"), os.path.join(BIN, "nano"))
    verify_glibc_bridge(os.path.join(BIN, "nano"), "nano")
    print(f"    ✓ nano ({os.path.getsize(os.path.join(BIN, 'nano')):,} B)")

    # ── 5. glibc libs + ncursesw podle NEEDED ──────────────────────────────
    print("─" * 60)
    print("[usrtools] libs (podle NEEDED) ...")
    # ld-linux + libm + libpthread vždy (brief: layout $PREFIX/lib), i když
    # mladý glibc (2.34+) pthread slouil do libc → libpthread jen pokud existuje
    extra = ["ld-linux-aarch64.so.1", "libm.so.6", "libpthread.so.0"]
    shipped = set()
    for name in ("sed", "rsync", "nano"):
        for lib in needed_libs(os.path.join(BIN, name)):
            src = None
            for base in (SYSROOT_LIB, os.path.join(STAGE, "lib")):
                cand = os.path.join(base, lib)
                if os.path.exists(cand):
                    src = cand
                    break
            if src is None:
                raise SystemExit(f"[usrtools] {name}: NEEDED '{lib}' nenalezeno v sysrootu/stage!")
            if lib not in shipped:
                shutil.copy(src, os.path.join(LIB, lib))
                shipped.add(lib)
                print(f"    ✓ {lib} ({os.path.getsize(os.path.join(LIB, lib)):,} B)")
    for lib in extra:
        src = os.path.join(SYSROOT_LIB, lib)
        if os.path.exists(src) and lib not in shipped:
            shutil.copy(src, os.path.join(LIB, lib))
            shipped.add(lib)
            print(f"    ✓ {lib} ({os.path.getsize(os.path.join(LIB, lib)):,} B)")
        elif lib in shipped:
            pass  # už zkopírováno
        else:
            print(f"    ⚠ {lib} není v sysrootu (netřeba pro glibc 2.34+)")
    print(f"    libs: {sorted(shipped)}")

    # ── 6. ripgrep (Bionic, NDK linker) ────────────────────────────────────
    print("─" * 60)
    print(f"[usrtools] ripgrep-{RG_VER} (aarch64-linux-android) ...")
    rg_top = os.path.join(WORK, f"ripgrep-{RG_VER}")
    if not os.path.isdir(rg_top):
        fetch(f"https://github.com/BurntSushi/ripgrep/archive/refs/tags/{RG_VER}.tar.gz",
              os.path.join(WORK, "ripgrep.tar.gz"))
        extract(os.path.join(WORK, "ripgrep.tar.gz"), WORK)
        # tarball rozbalí do ripgrep-<ver>
    if not os.path.isdir(rg_top):
        cand = [d for d in os.listdir(WORK) if d.startswith("ripgrep")]
        rg_top = os.path.join(WORK, cand[0]) if cand else None
    if not rg_top:
        raise SystemExit("[usrtools] ripgrep zdroj nerozbalen!")

    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    linker = f"{tc_bin}/aarch64-linux-android{USRTOOLS_API}-clang"
    for alt_api in (USRTOOLS_API, 24, 21):
        if os.path.exists(f"{tc_bin}/aarch64-linux-android{alt_api}-clang"):
            linker = f"{tc_bin}/aarch64-linux-android{alt_api}-clang"
            break
    rg_env = dict(os.environ)
    rg_env["PATH"] = "/root/.cargo/bin:" + rg_env.get("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    rg_env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = linker
    cargo_cfg = os.path.join(WORK, "cargo-android.toml")
    with open(cargo_cfg, "w") as f:
        f.write("[target.aarch64-linux-android]\n")
        f.write(f'linker = "{linker}"\n')
    # rg 14+ nemá feature `gzip` (decompress byl z rg vyrazen). => nepoužívat
    # defaultni (pcre2) ani gzip; cliste ryzí rg bez pcre2.
    run(["cargo", "--config", cargo_cfg, "build", "--release",
         "--target", "aarch64-linux-android",
         "--no-default-features"],
        cwd=rg_top, env=rg_env)
    rg_src = os.path.join(rg_top, "target/aarch64-linux-android/release/rg")
    if not os.path.exists(rg_src):
        raise SystemExit("[usrtools] rg nebyl vyroben!")
    shutil.copy(rg_src, os.path.join(BIN, "rg"))
    print(f"    ✓ rg ({os.path.getsize(os.path.join(BIN, 'rg')):,} B)")
    out = subprocess.run(["file", os.path.join(BIN, "rg")], capture_output=True, text=True).stdout.strip()
    print(f"    file: {out}")
    dyn = subprocess.run([READELF, "-d", os.path.join(BIN, "rg")], capture_output=True, text=True).stdout
    print(f"    NEEDED: {[l.split('[')[-1].rstrip(']') for l in dyn.splitlines() if 'NEEDED' in l]}")

    # ── 7. tarball (bin/+lib/ → extrahovat do $PREFIX) ─────────────────────
    print("─" * 60)
    print("[usrtools] tarball ...")
    tgz = os.path.join(builds_dir, "usrtools.tar.gz")
    with tarfile.open(tgz, "w:gz") as tf:
        for root, _dirs, files in os.walk(assets_usr):
            for fn in files:
                fp = os.path.join(root, fn)
                arc = os.path.relpath(fp, assets_usr)
                tf.add(fp, arcname=arc)
    print(f"    ✓ {tgz} ({os.path.getsize(tgz)/1024/1024:.1f} MB)")
    with tarfile.open(tgz, "r:gz") as tf:
        for m in tf.getmembers():
            print(f"      {m.name} ({m.size:,} B)")


# ── Build APK ────────────────────────────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("build-secrets")],
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build():
    """Build the debug APK from the source tree on the Volume."""
    src_dir = "/vol/src"
    vol_keys = "/vol/keys"

    if not os.path.isdir(src_dir):
        print(
            "[build] Source directory not found on Volume.  "
            "Run upload_src first:\n"
            "  modal run modal_build.py::upload_src",
            file=sys.stderr,
        )
        sys.exit(1)

    # ---- local.properties ----
    with open(os.path.join(src_dir, "local.properties"), "w") as f:
        f.write(f"sdk.dir={ANDROID_SDK_ROOT}\n")

    # ---- signing key ----
    key_src = os.path.join(vol_keys, "release.jks")
    key_dst = os.path.join(src_dir, "app", "release.jks")
    if os.path.exists(key_src):
        shutil.copy2(key_src, key_dst)
        os.chmod(key_dst, 0o600)
        print(f"[build] Signing key copied from Volume: {key_dst}")
    elif not os.path.exists(key_dst):
        print(
            "[build] WARNING: No signing key found! "
            "Run init_keys first (or ensure app/release.jks is in source tree)."
        )

    # ---- gradle wrapper ----
    gradlew = os.path.join(src_dir, "gradlew")
    os.chmod(gradlew, 0o755)

    # ---- ensure gradle cache dir ----
    os.makedirs("/vol/gradle-cache", exist_ok=True)

    # ---- build ----
    print("[build] Running: ./gradlew assembleDebug")
    result = subprocess.run(
        ["./gradlew", "assembleDebug", "--no-daemon", "--stacktrace"],
        cwd=src_dir,
        capture_output=False,
        text=True,
    )

    if result.returncode != 0:
        print("[build] BUILD FAILED", file=sys.stderr)
        sys.exit(result.returncode)

    apk_path = os.path.join(src_dir, "app/build/outputs/apk/debug/app-debug.apk")
    if not os.path.exists(apk_path):
        print("[build] APK not found at expected path!", file=sys.stderr)
        sys.exit(1)

    size_mb = os.path.getsize(apk_path) / (1024 * 1024)
    print(f"[build] APK built: {apk_path} ({size_mb:.1f} MB)")

    out_dir = "/vol/builds"
    os.makedirs(out_dir, exist_ok=True)
    dest = os.path.join(out_dir, "app-debug.apk")
    shutil.copy2(apk_path, dest)
    build_vol.commit()
    print(f"[build] APK copied to Volume: {dest}")


# ── Verify APK signature ─────────────────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=300,
)
def verify_apk():
    """Print the signer certificate of the built APK (must match release.jks)."""
    apk = "/vol/builds/app-debug.apk"
    if not os.path.exists(apk):
        print("[verify] APK not found on Volume!")
        sys.exit(1)
    apksigner = f"{ANDROID_SDK_ROOT}/build-tools/36.0.0/apksigner"
    print(f"[verify] {apk}")
    subprocess.run(
        [apksigner, "verify", "--print-certs", apk],
        check=False,
        text=True,
    )


# ── Utility ──────────────────────────────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=300,
)
def list_volume():
    """Print files stored on the build Volume."""
    for root, dirs, files in os.walk("/vol"):
        for f in files:
            fp = os.path.join(root, f)
            try:
                size = os.path.getsize(fp)
                print(f"  {fp}  ({size:,} bytes)")
            except OSError:
                print(f"  {fp}  (unreadable)")


@app.local_entrypoint()
def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "init":
        init_keys.remote()
    elif cmd == "upload":
        upload_src.remote()
    elif cmd == "native":
        build_native.remote()
    elif cmd == "build":
        build.remote()
    elif cmd == "all":
        build_native.remote()
        build.remote()
    elif cmd == "list":
        list_volume.remote()
    else:
        print("Usage: modal run modal_build.py [init|upload|native|build|all|list]")
