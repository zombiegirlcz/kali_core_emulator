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
  modal run modal_build.py native      # NDK cross-compile C binaries + usr tools (nano/rsync/sed/rg) + USB tools (libusbgx/usbutils/usbrelayd)
  modal run modal_build.py usbtools    # USB tools only (faster iteration)
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
    # nano/rsync/sed/rg + libs — generuje je build_native do assets/usr/.
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

# Image pro build_native: k Android SDK/NDK přidá Rust toolchain s targetem
# aarch64-linux-android (rg). nano/rsync/sed se staví přímo NDK (Bionic).
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
        "build-essential make autoconf automake pkg-config libtool cmake "
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


# ── USB gadget tools: libusbgx / usbutils / usbrelayd ────────────────────
# Pinned sources — see tools/usbtools/README.md. All outputs are Bionic
# (aarch64-linux-android, /system/bin/linker64): they run on the Android host
# (ashell/su, where configfs /config and /dev/bus/usb live) AND inside the
# PRoot guest. glibc builds would die on the host (rseq blocked by app
# seccomp) — see AGENTS.md session 2026-08-11.
ARGP_VER = "1.5.0"
ARGP_URL = f"https://github.com/argp-standalone/argp-standalone/archive/refs/tags/{ARGP_VER}.tar.gz"
LIBUSB_VER = "1.0.27"
LIBUSB_URL = f"https://github.com/libusb/libusb/releases/download/v{LIBUSB_VER}/libusb-{LIBUSB_VER}.tar.bz2"
HIDAPI_TAG = "hidapi-0.14.0"
HIDAPI_URL = "https://github.com/libusb/hidapi.git"
LIBUSBGX_TAG = "libusbgx-v0.3.0"
LIBUSBGX_URL = "https://github.com/libusbgx/libusbgx.git"
USBRELAY_TAG = "v0.8"
USBRELAY_URL = "https://github.com/darrylb123/usbrelay.git"
USBUTILS_TAG = "v007"
USBUTILS_URL = "https://github.com/gregkh/usbutils.git"
USBIDS_URL = "https://raw.githubusercontent.com/usbids/usbids/master/usb.ids"


def _usb_run(cmd, **kw):
    print(f"  $ {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    subprocess.run(cmd, check=True, **kw)


def _usb_fetch(url, dest):
    if not os.path.exists(dest):
        _usb_run(["wget", "-q", url, "-O", dest])


def _usb_extract(archive, dest):
    _usb_run(["tar", "xf", archive, "-C", dest])


def _usb_git(tag, url, dest, submodules=True):
    if not os.path.isdir(dest):
        cmd = ["git", "clone", "--depth", "1", "--branch", tag]
        if submodules:
            cmd += ["--recurse-submodules", "--shallow-submodules"]
        cmd += [url, dest]
        _usb_run(cmd)


def _usb_readelf_needed(path):
    dyn = subprocess.run(["aarch64-linux-gnu-readelf", "-d", path],
                         capture_output=True, text=True).stdout
    return [l.split("[")[-1].rstrip("]") for l in dyn.splitlines() if "NEEDED" in l]


def _usb_verify(path, name, is_shared=False):
    """Bionic check: executables must be PIE with /system/bin/linker64;
    shared libs must not pull glibc (ld-linux / libc.so.6 / libgcc)."""
    re = "aarch64-linux-gnu-readelf"
    out = subprocess.run([re, "-d", path], capture_output=True, text=True).stdout
    needed = [l.split("[")[-1].rstrip("]") for l in out.splitlines() if "NEEDED" in l]
    bad = [n for n in needed if "ld-linux" in n or n == "libc.so.6" or n.startswith("libgcc")]
    if bad:
        raise SystemExit(f"[usbtools] {name}: glibc NEEDED {bad} — na hostu by spadl!")
    if is_shared:
        print(f"    {name}: NEEDED={needed}")
        return
    el = subprocess.run([re, "-l", path], capture_output=True, text=True).stdout
    interp = next((l.split(":")[-1].strip().rstrip("]") for l in el.splitlines() if "interpreter" in l), None)
    print(f"    {name}: interpreter={interp} NEEDED={needed}")
    if interp != "/system/bin/linker64":
        raise SystemExit(f"[usbtools] {name}: NENI Bionic (interpreter={interp}) — na hostu by spadl!")

@app.function(
    image=usrtools_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build_native():
    """Cross-compile native binaries into the APK assets.

    NDK (Bionic):
      jniLibs/arm64-v8a/libusbfd_exporter.so
      assets/usb_bridge, assets/su_daemon, assets/su_wrapper

    Usr tools (assets/usr/ — běží přímo na hostu, bez PRootu):
      assets/usr/bin/{sed,rsync,nano,rg}  Bionic (aarch64-linux-android, linker64)
      assets/usr/lib/                   (prázdné — ncursesw staticky v nano)
      /vol/builds/usrtools.tar.gz         bin/+lib/ → extrahovat do $PREFIX
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

    # ── 5. Usr tools: sed/rsync/nano/rg (vše Bionic) ─────────────────────────
    # Výstup: assets/usr/{bin,lib} (jde do APK) + /vol/builds/usrtools.tar.gz.
    print("─" * 60)
    print("[native] Building usr tools (sed/rsync/nano/rg) ...")
    _build_usrtools(
        os.path.join(assets_dir, "usr"),
        "/vol/builds",
    )

    # ── 6. USB gadget tools: libusbgx / usbutils / usbrelay / usbrelayd ────
    # Musí běžet PO _build_usrtools (ten na začátku smaže celý assets/usr).
    print("─" * 60)
    print("[native] Building USB gadget tools (libusbgx/usbutils/usbrelayd) ...")
    _build_usb_tools(
        os.path.join(assets_dir, "usr"),
        "/vol/builds",
        repo_dir=src_dir,
    )

    # Commit to Volume
    build_vol.commit()
    print(f"[native] Binaries committed to Volume.")


# ── USB tools only (rychlejší iterace — bez nano/rsync/sed/rg) ─────────────
@app.function(
    image=usrtools_image,
    volumes={"/vol": build_vol},
    timeout=1800,
    memory=8192,
    cpu=4,
)
def build_usb_tools():
    """Cross-compile only the USB gadget tools (libusbgx/usbutils/usbrelayd)
    into assets/usr — bez 15 min trvajícího usrtools buildu."""
    src_dir = "/vol/src"
    assets_dir = os.path.join(src_dir, "app/src/main/assets")
    print("[native] Building USB tools only ...")
    _build_usb_tools(os.path.join(assets_dir, "usr"), "/vol/builds", repo_dir=src_dir)
    build_vol.commit()
    print("[native] USB tools committed to Volume.")


# ── Usr tools build: nano/rsync/sed (glibc bridge) + ripgrep (Bionic) ───────
def _build_usrtools(assets_usr, builds_dir):
    """Cross-compile sed/rsync/nano (Bionic/NDK) + ripgrep (Bionic).

    assets_usr: /vol/src/app/src/main/assets/usr  (bin/ → jde do APK)
    builds_dir: /vol/builds                       (usrtools.tar.gz → $PREFIX)

    Všechny host-side nástroje jsou Bionic (aarch64-linux-android, NDK) —
    běží přímo na Android hostu přes /system/bin/linker64. NESMÍ to být
    glibc buildy: glibc 2.40+ volá při startu syscall `rseq`, který app
    seccomp policy blokuje (SIGSYS / „Bad system call"). PRootův vlastní
    seccomp filtr (SECCOMP_RET_TRACE) to v guestu maskuje, takže glibc
    binárky fungují jen uvnitř PRootu, nikdy na hostu (ashell -c, /shell).
    Diagnóza + fix: AGENTS.md session 2026-08-11.
    """
    import tarfile

    PREFIX = USRTOOLS_PREFIX  # layout usrtools.tar.gz ($PREFIX/bin, $PREFIX/lib)
    WORK = "/tmp/usrtools"
    STAGE = "/tmp/usrtools-stage"
    READELF = "aarch64-linux-gnu-readelf"

    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    CC = f"{tc_bin}/aarch64-linux-android{USRTOOLS_API}-clang"
    for alt_api in (USRTOOLS_API, 28, 24, 21):
        if os.path.exists(f"{tc_bin}/aarch64-linux-android{alt_api}-clang"):
            CC = f"{tc_bin}/aarch64-linux-android{alt_api}-clang"
            break
    CFLAGS = "-O2 -fPIE -D__USE_FORTIFY_LEVEL=0"
    # -pie: Android 24+ odmítá non-PIE executables. $ORIGIN rpath: bionic
    # loader najde libncursesw.so.6 v $PREFIX/lib i bez LD_LIBRARY_PATH.
    # -D__USE_FORTIFY_LEVEL=0: gnulib (sed/nano) taha vlastni cdefs.h, ktery
    # shadowne bionic sys/cdefs.h -> __USE_FORTIFY_LEVEL nikdy nedefinovany ->
    # bionic fortify hlavicky spadnou na undeclared identifier.
    LDFLAGS = "-pie -Wl,-rpath='$$ORIGIN/../lib'"

    BIN = os.path.join(assets_usr, "bin")
    LIB = os.path.join(assets_usr, "lib")
    # Čistý stav: smaž staré buildy (glibc libs z dřívějška atd.) — binárky
    # se generují od nuly, nic se neakumuluje.
    shutil.rmtree(assets_usr, ignore_errors=True)
    for d in (WORK, STAGE, BIN, LIB, builds_dir):
        os.makedirs(d, exist_ok=True)

    # strip wrapper: `make install -s` vola `strip` z PATH; host strip (x86_64)
    # nerozpozna ARM binarku => symlink na NDK llvm-strip (nebo no-op).
    STRIP_DIR = os.path.join(WORK, "stripbin")
    os.makedirs(STRIP_DIR, exist_ok=True)
    wrapper = os.path.join(STRIP_DIR, "strip")
    if not os.path.lexists(wrapper):
        ndk_strip = os.path.join(tc_bin, "llvm-strip")
        if os.path.exists(ndk_strip):
            os.symlink(ndk_strip, wrapper)
        else:
            # NDK strip neni -> no-op strip (binarky zustanou unstripped).
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

    # Bionic systémové libs — NEkopírují se (jsou v /system).
    SYSTEM_LIBS = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so"}

    def verify_bionic(path, name):
        """Kontrola, ze binarka je Bionic PIE (interpreter /system/bin/linker64).

        Glibc build (interpreter $PREFIX/lib/ld-linux-*) by na hostu spadl
        s „Bad system call" (rseq blokovany app seccompem) — takovy artefakt
        nechceme nikdy dostat do APK.
        """
        el = subprocess.run([READELF, "-l", path], capture_output=True, text=True).stdout
        interp = next(
            (l.split(":")[-1].strip().rstrip("]") for l in el.splitlines() if "interpreter" in l),
            None,
        )
        print(f"    {name}: interpreter={interp}")
        if interp != "/system/bin/linker64":
            raise SystemExit(f"[usrtools] {name}: NENI Bionic (interpreter={interp}) — na hostu by spadl!")

    # ── 1. ncursesw 6.5 (wide-char, terminfo fallbacky zabudované) ─────────
    print("─" * 60)
    print(f"[usrtools] ncurses-{NCURSES_VER} (wide-char) ...")
    nc_dir = os.path.join(WORK, f"ncurses-{NCURSES_VER}")
    if not os.path.isdir(nc_dir):
        fetch(f"https://ftp.gnu.org/gnu/ncurses/ncurses-{NCURSES_VER}.tar.gz",
              os.path.join(WORK, "ncurses.tar.gz"))
        extract(os.path.join(WORK, "ncurses.tar.gz"), WORK)
    run(["./configure", "--host=aarch64-linux-android", f"--prefix={STAGE}",
         "--enable-widec", "--without-debug", "--without-ada",
         "--disable-shared",      # STATICKY: -pie v LDFLAGS koliduje s -shared
                                  # (GNU ld error); libncursesw.a → nano bez .so
         "--disable-db-install",  # terminfo DB neinstalujeme (fallbacky zabudovane
                                  # v lib) => preskokneme install data (cross tic na hostu)
         "--without-cxx-binding",  # C++ binding nepotřebujeme (C-only nástroje);
                                  # mimo jiné obejde ncurses-C++ vs GCC>=13 bug
         "--without-manpages", "--without-tests",
         "--with-fallbacks=xterm,xterm-256color,screen,screen-256color,linux,vt100,ansi",
         f"CC={CC}", f"CFLAGS={CFLAGS}", f"LDFLAGS={LDFLAGS}"], cwd=nc_dir)
    run(["make", "-j8"], cwd=nc_dir)
    run(["make", "install"], cwd=nc_dir)

    # ── 2. sed ─────────────────────────────────────────────────────────────
    print("─" * 60)
    print(f"[usrtools] sed-{SED_VER} ...")
    sed_dir = os.path.join(WORK, f"sed-{SED_VER}")
    if not os.path.isdir(sed_dir):
        fetch(f"https://ftp.gnu.org/gnu/sed/sed-{SED_VER}.tar.xz", os.path.join(WORK, "sed.tar.xz"))
        extract(os.path.join(WORK, "sed.tar.xz"), WORK)
    run(["./configure", "--host=aarch64-linux-android", f"--prefix={PREFIX}",
         "--disable-nls",
         f"CC={CC}", f"CFLAGS={CFLAGS}", f"LDFLAGS={LDFLAGS}"], cwd=sed_dir)
    run(["make", "-j8"], cwd=sed_dir)
    shutil.copy2(os.path.join(sed_dir, "sed/sed"), os.path.join(BIN, "sed"))
    verify_bionic(os.path.join(BIN, "sed"), "sed")
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
    run(["./configure", "--host=aarch64-linux-android", f"--prefix={PREFIX}",
         "--disable-openssl", "--disable-xxhash", "--disable-zstd", "--disable-lz4",
         "--with-included-zlib=yes", "--with-included-popt", "--disable-md2man",
         f"CC={CC}", f"CFLAGS={CFLAGS} -Wno-incompatible-pointer-types -Wno-implicit-function-declaration",
         f"LDFLAGS={LDFLAGS}"], cwd=rsync_dir)
    run(["make", "-j8"], cwd=rsync_dir)
    shutil.copy(os.path.join(rsync_dir, "rsync"), os.path.join(BIN, "rsync"))
    verify_bionic(os.path.join(BIN, "rsync"), "rsync")
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
    run(["./configure", "--host=aarch64-linux-android", f"--prefix={PREFIX}",
         "--enable-tiny", "--disable-nls",
         f"CC={CC}", f"CFLAGS={CFLAGS}",
         f"CPPFLAGS=-I{STAGE}/include -I{STAGE}/include/ncursesw",
         f"LDFLAGS={LDFLAGS} -L{STAGE}/lib",
         f"LIBS=-lncursesw"], cwd=nano_dir, env=nano_env)
    run(["make", "-j8"], cwd=nano_dir, env=nano_env)
    shutil.copy(os.path.join(nano_dir, "src", "nano"), os.path.join(BIN, "nano"))
    verify_bionic(os.path.join(BIN, "nano"), "nano")
    print(f"    ✓ nano ({os.path.getsize(os.path.join(BIN, 'nano')):,} B)")

    # ── 5. Host-side libs podle NEEDED (mimo /system) ──────────────────────
    # ncursesw je staticky v nano → NEEDED = jen bionic systémové libs, nic
    # se nekopíruje. Kdyby některý nástroj v budoucnu NEEDED mimo /system,
    # přidej sem kopii (např. libncursesw.so.6 → $LIB + rpath $ORIGIN/../lib).
    print("─" * 60)
    print("[usrtools] libs (NEEDED mimo /system) ...")
    shipped = set()
    for name in ("nano", "sed", "rsync", "rg"):
        for lib in needed_libs(os.path.join(BIN, name)):
            if lib in SYSTEM_LIBS or lib in shipped:
                continue
            shipped.add(lib)
            print(f"    ! {name}: NEEDED mimo /system: {lib} (není v /system — zkontrolovat!)")
    if not shipped:
        print("    (žádné host-side .so — vše je čistě Bionic)")
    print(f"    libs mimo /system: {sorted(shipped) if shipped else 'žádné'}")

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


# ── USB gadget tools build: libusbgx / usbutils / usbrelay / usbrelayd ──────
# Vše Bionic (aarch64-linux-android, linker64). Tools run on the Android host
# (su/ashell — configfs /config + /dev/bus/usb) i uvnitř PRoot guestu.
def _build_usb_tools(assets_usr, builds_dir, repo_dir=None):
    """Cross-compile USB gadget tools (Bionic/arm64) into assets/usr.

    repo_dir: root repa (cesta k tools/usbtools/usbrelayd.c) — na Modal
    je to /vol/src; když je None, zkusí os.path.dirname(__file__).

    Produces (relative to assets_usr):
      bin/  lsusb usbhid-dump usb-devices usbrelay usbrelayd
            show-gadgets show-udcs gadget-{acm-ecm,ffs,hid,ms,midi,printer,
                    uvc,rndis-os-desc,export,import,vid-pid-remove}
      lib/  libusb-1.0.so.0 libhidapi-libusb.so libusbgx.so.3 libusbrelay.so
      share/usb.ids
    Plus /vol/builds/usbtools.tar.gz (bin/ + lib/ + share/ → $PREFIX).

    DAG: libusb → hidapi(→libusb) ; libusbgx ; argp+hidapi → usbrelay ;
         usbutils(→libusb) → lsusb/usbhid-dump."""
    import tarfile

    WORK = "/tmp/usbtools"
    SYSTEM_LIBS = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so"}
    RPATH = "-Wl,-rpath='$$ORIGIN/../lib'"
    CFLAGS = "-O2 -fPIE -D__USE_FORTIFY_LEVEL=0"

    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    CC = f"{tc_bin}/aarch64-linux-android28-clang"
    for alt_api in (28, 24, 21):
        if os.path.exists(f"{tc_bin}/aarch64-linux-android{alt_api}-clang"):
            CC = f"{tc_bin}/aarch64-linux-android{alt_api}-clang"
            break
    CXX = CC[: -len("-clang")] + "-clang++"

    BIN = os.path.join(assets_usr, "bin")
    LIB = os.path.join(assets_usr, "lib")
    SHARE = os.path.join(assets_usr, "share")
    for d in (WORK, BIN, LIB, SHARE, builds_dir):
        os.makedirs(d, exist_ok=True)

    env = dict(os.environ)
    env["PKG_CONFIG_PATH"] = os.path.join(WORK, "libusb-install/lib/pkgconfig")

    # ── 1. argp-standalone (staticky; usbrelay používá glibc argp, bionic ho nemá)
    print("─" * 60)
    print(f"[usbtools] argp-standalone-{ARGP_VER} ...")
    argp_dir = os.path.join(WORK, f"argp-standalone-{ARGP_VER}")
    if not os.path.isdir(argp_dir):
        _usb_fetch(ARGP_URL, os.path.join(WORK, "argp.tar.gz"))
        _usb_extract(os.path.join(WORK, "argp.tar.gz"), WORK)
    argp_stage = os.path.join(WORK, "argp-install")
    os.makedirs(argp_stage, exist_ok=True)
    if not os.path.exists(os.path.join(argp_stage, "lib", "libargp.a")):
        _usb_run(["autoreconf", "-fi"], cwd=argp_dir)
        _usb_run(["./configure", "--host=aarch64-linux-android", f"--prefix={argp_stage}",
                  f"CC={CC}", f"CFLAGS={CFLAGS}"], cwd=argp_dir)
        _usb_run(["make", "-j8"], cwd=argp_dir)
        _usb_run(["make", "install"], cwd=argp_dir)
        # argp-standalone deklaruje libargp.a jako noinst_LIBRARIES — make
        # install ho NEinstaluje („Nothing to be done“); lib i hlavička zůstanou
        # v build dir. Kopírujeme je ručně do stage.
        for rel, dst in (("libargp.a", os.path.join(argp_stage, "lib", "libargp.a")),
                         ("argp.h", os.path.join(argp_stage, "include", "argp.h"))):
            src = os.path.join(argp_dir, rel)
            if not os.path.exists(src):
                raise RuntimeError(f"argp build artifact missing: {src}")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
    print(f"    ✓ libargp.a ({os.path.getsize(os.path.join(argp_stage, 'lib', 'libargp.a')):,} B)")

    # ── 2. libusb 1.0.27 (host-side; lsusb + hidapi + usbrelay) ────────────
    print("─" * 60)
    print(f"[usbtools] libusb-{LIBUSB_VER} ...")
    libusb_dir = os.path.join(WORK, f"libusb-{LIBUSB_VER}")
    if not os.path.isdir(libusb_dir):
        _usb_fetch(LIBUSB_URL, os.path.join(WORK, "libusb.tar.bz2"))
        _usb_extract(os.path.join(WORK, "libusb.tar.bz2"), WORK)
    libusb_stage = os.path.join(WORK, "libusb-install")
    os.makedirs(libusb_stage, exist_ok=True)
    # libtool při cross-compile na Android ne vždy vyprodukuje verzovaná
    # jména — dejme si OR přes kandidáty místo tvrdého .so.0.
    def _libusb_candidates(stage, bases):
        return [os.path.join(stage, "lib", b) for b in bases if os.path.exists(os.path.join(stage, "lib", b))]
    libusb_bases = ("libusb-1.0.so.0", "libusb-1.0.so", "libusb-1.0.so.0.0.0")
    if not _libusb_candidates(libusb_stage, libusb_bases):
        _usb_run(["./configure", "--host=aarch64-linux-android", f"--prefix={libusb_stage}",
                  "--disable-udev", "--disable-tests", "--disable-dependency-tracking",
                  f"CC={CC}", f"CFLAGS={CFLAGS}"], cwd=libusb_dir)
        _usb_run(["make", "-j8"], cwd=libusb_dir)
        _usb_run(["make", "install"], cwd=libusb_dir)
    libusb_so = _libusb_candidates(libusb_stage, libusb_bases)[0]
    print(f"    ✓ libusb ({os.path.basename(libusb_so)}: {os.path.getsize(libusb_so):,} B)")

    # ── 3. hidapi 0.14.0 (libusb backend) — standalone build se PTÁ přes
    # pkg-config na EXTERNÍ libusb (libusb/CMakeLists: if(TARGET usb-1.0)
    # ... else pkg_check_modules). Submodul libusb/ je potřeba jen jako
    # CMake wrapper — jeho knihovna se NIKDY neveze.
    print("─" * 60)
    print(f"[usbtools] hidapi-{HIDAPI_TAG} (libusb backend) ...")
    hidapi_dir = os.path.join(WORK, "hidapi")
    if not os.path.isdir(hidapi_dir):
        _usb_git(HIDAPI_TAG, HIDAPI_URL, hidapi_dir)
    hidapi_stage = os.path.join(WORK, "hidapi-install")
    os.makedirs(hidapi_stage, exist_ok=True)
    hidapi_build = os.path.join(WORK, "hidapi-build")
    os.makedirs(hidapi_build, exist_ok=True)
    if not os.path.exists(os.path.join(hidapi_stage, "lib", "libhidapi-libusb.so")):
        _usb_run(["cmake", "-S", hidapi_dir, "-B", hidapi_build,
                  "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",  # Modal cmake je moc nový na min 3.5
                  f"-DCMAKE_TOOLCHAIN_FILE={NDK_DIR}/build/cmake/android.toolchain.cmake",
                  "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-28",
                  "-DANDROID_STL=none", "-DCMAKE_BUILD_TYPE=Release",
                  f"-DCMAKE_INSTALL_PREFIX={hidapi_stage}",
                  "-DHIDAPI_WITH_HIDRAW=OFF", "-DHIDAPI_WITH_LIBUSB=ON",
                  "-DHIDAPI_BUILD_HIDTEST=OFF", "-DBUILD_SHARED_LIBS=ON",
                  # hidapi linkuje jen ${USB_LIBRARIES} (-lusb-1.0) a zapomíná
                  # na USB_LIBRARY_DIRS → přidáváme -L ručně.
                  f"-DCMAKE_SHARED_LINKER_FLAGS=-L{libusb_stage}/lib",
                  f"-DCMAKE_EXE_LINKER_FLAGS=-L{libusb_stage}/lib"], env=env)
        _usb_run(["cmake", "--build", hidapi_build, "-j8"])
        _usb_run(["cmake", "--install", hidapi_build])
    hidapi_so = _libusb_candidates(hidapi_stage, ("libhidapi-libusb.so", "libhidapi-libusb.so.0", "libhidapi-libusb.so.0.0.0"))[0]
    print(f"    ✓ libhidapi ({os.path.basename(hidapi_so)}: {os.path.getsize(hidapi_so):,} B)")

    # ── 4. libusbgx 0.3.0 (knihovna + show-gadgets/show-udcs + gadget-*) ──
    print("─" * 60)
    print(f"[usbtools] libusbgx-{LIBUSBGX_TAG} ...")
    libusbgx_dir = os.path.join(WORK, "libusbgx")
    if not os.path.isdir(libusbgx_dir):
        _usb_git(LIBUSBGX_TAG, LIBUSBGX_URL, libusbgx_dir)
    libusbgx_stage = os.path.join(WORK, "libusbgx-install")
    os.makedirs(libusbgx_stage, exist_ok=True)
    libusbgx_bases = ("libusbgx.so.3", "libusbgx.so", "libusbgx.so.3.0.0")
    def _find_libusbgx_so():
        for base in libusbgx_bases:
            p = os.path.join(libusbgx_stage, "lib", base)
            if os.path.exists(p):
                return [p]
        hit = []
        for root, _dirs, files in os.walk(libusbgx_stage):
            for fn in sorted(files):
                if fn.startswith("libusbgx.so"):
                    hit.append(os.path.join(root, fn))
        return hit
    if not _find_libusbgx_so():
        if not os.path.exists(os.path.join(libusbgx_dir, "configure")):
            _usb_run(["autoreconf", "-fi"], cwd=libusbgx_dir)
        _usb_run(["./configure", "--host=aarch64-linux-android", f"--prefix={libusbgx_stage}",
                  "--without-libconfig", "--disable-gadget-schemes", "--disable-tests",
                  f"CC={CC}", f"CXX={CXX}", f"CFLAGS={CFLAGS}", f"CXXFLAGS={CFLAGS}",
                  f"LDFLAGS={RPATH}"], cwd=libusbgx_dir)
        _usb_run(["make", "-j8"], cwd=libusbgx_dir)
        _usb_run(["make", "install"], cwd=libusbgx_dir)
    libusbgx_so = _find_libusbgx_so()
    if not libusbgx_so:
        raise RuntimeError("libusbgx: .so nenalezen po build/install")
    print(f"    ✓ libusbgx ({os.path.basename(libusbgx_so[0])}: {os.path.getsize(libusbgx_so[0]):,} B)")

    # ── 5. usbrelay v0.8 (libusbrelay.so + usbrelay, HIDAPI=libusb, -largp)
    print("─" * 60)
    print(f"[usbtools] usbrelay-{USBRELAY_TAG} ...")
    urelay_dir = os.path.join(WORK, "usbrelay")
    if not os.path.isdir(urelay_dir):
        _usb_git(USBRELAY_TAG, USBRELAY_URL, urelay_dir)
    if not os.path.exists(os.path.join(urelay_dir, "usbrelay")):
        _usb_run(["make", "HIDAPI=libusb",
                  f"CC={CC}",
                  f"CPPFLAGS=-I{hidapi_stage}/include -I{argp_stage}/include",
                  # −fPIC PŘEPÍNE −fPIE (poslední vyhrává): libusbrelay.so je
                  # -shared → lld by jinak odmítl PIE relokace (R_AARCH64_*)
                  f"CFLAGS={CFLAGS} -fPIC",
                  f"LDFLAGS=-L{hidapi_stage}/lib -lhidapi-libusb -L{argp_stage}/lib -largp {RPATH}"],
                 cwd=urelay_dir)
    shutil.copy2(os.path.join(urelay_dir, "usbrelay"), os.path.join(BIN, "usbrelay"))
    shutil.copy2(os.path.join(urelay_dir, "libusbrelay.so"), os.path.join(LIB, "libusbrelay.so"))
    _usb_verify(os.path.join(BIN, "usbrelay"), "usbrelay")
    print(f"    ✓ usbrelay ({os.path.getsize(os.path.join(BIN, 'usbrelay')):,} B)")

    # ── 6. usbrelayd — náš C TCP daemon (tools/usbtools/usbrelayd.c) ───────
    print("─" * 60)
    print("[usbtools] usbrelayd (custom C TCP daemon) ...")
    if repo_dir is None:
        repo_dir = os.path.dirname(__file__)
    daemon_src = os.path.join(repo_dir, "tools", "usbtools", "usbrelayd.c")
    daemon_out = os.path.join(BIN, "usbrelayd")
    _usb_run([CC, *CFLAGS.split(), "-fPIE", "-pie",
              f"-I{urelay_dir}", f"-I{hidapi_stage}/include", f"-I{argp_stage}/include",
              daemon_src,
              f"-L{urelay_dir}", "-lusbrelay",
              f"-L{hidapi_stage}/lib", "-lhidapi-libusb",
              "-Wl,-rpath='$$ORIGIN/../lib'", "-o", daemon_out])
    _usb_verify(daemon_out, "usbrelayd")
    print(f"    ✓ usbrelayd ({os.path.getsize(daemon_out):,} B)")

    # ── 7. usbutils v007 (lsusb + usbhid-dump + usb-devices) + usb.ids ────
    # v015+ přešlo na udev hwdb (#include <libudev.h>) — na bionicu
    # nezkompiluješ; v007 je poslední, které čte usb.ids přímo.
    print("─" * 60)
    print(f"[usbtools] usbutils-{USBUTILS_TAG} ...")
    usbutils_dir = os.path.join(WORK, "usbutils")
    if not os.path.isdir(usbutils_dir):
        # usbutils v007 má submodul usbhid-dump na MRTvém git://sourceforge
        # (clone selže) — submoduly přeskočíme, usbhid-dump je volitelný.
        _usb_git(USBUTILS_TAG, USBUTILS_URL, usbutils_dir, submodules=False)
    usbutils_stage = os.path.join(WORK, "usbutils-install")
    os.makedirs(usbutils_stage, exist_ok=True)
    if not os.path.exists(os.path.join(usbutils_stage, "bin", "lsusb")):
        # usbhid-dump jsme neklonovali (submodul = mrtvý sourceforge git) —
        # Makefile.am stejně požaduje ten adresář, takže tam vytvoříme PRÁZDNÝ
        # stub sub-projekt (autoreconf pak projde a binárka nevznikne — je
        # volitelná, lsusb/usb-devices fungují bez ní).
        hid_dump = os.path.join(usbutils_dir, "usbhid-dump")
        if os.path.isdir(hid_dump) and not os.listdir(hid_dump):
            os.rmdir(hid_dump)
        os.makedirs(hid_dump, exist_ok=True)
        for fn, body in (
            ("configure.ac", "AC_INIT([usbhid-dump-stub],[0.0])\nAM_INIT_AUTOMAKE([foreign])\nAC_PROG_CC\nAC_CONFIG_FILES([Makefile])\nAC_OUTPUT\n"),
            ("Makefile.am", "# stub — usbhid-dump submodul (sourceforge) se neklonuje\n"),
        ):
            with open(os.path.join(hid_dump, fn), "w", encoding="utf-8") as _f:
                _f.write(body)
        cac = os.path.join(usbutils_dir, "configure.ac")
        # Pozn.: AC_CONFIG_SUBDIRS([usbhid-dump]) NECHÁVÁME — stub sub-projekt
        # má vlastní configure.ac, takže configure do něj čistě sestoupí a
        # make tam nic nepostaví (prázdný stub).
        if not os.path.exists(os.path.join(usbutils_dir, "configure")):
            # autogen.sh by selhal (cd usbhid-dump — submodul přeskočen) →
            # generujeme configure přímo.
            _usb_run(["autoreconf", "-fi"], cwd=usbutils_dir)
        _usb_run(["./configure", "--host=aarch64-linux-android",
                  f"--prefix={usbutils_stage}",
                  f"--datadir={USRTOOLS_PREFIX}/share",
                  "--disable-zlib", "--disable-usbids",
                  f"CC={CC}", f"CFLAGS={CFLAGS}", f"LDFLAGS={RPATH}"],
                 cwd=usbutils_dir, env=env)
        _usb_run(["make", "-j8"], cwd=usbutils_dir, env=env)
        _usb_run(["make", "install"], cwd=usbutils_dir, env=env)
    for tool in ("lsusb", "usbhid-dump", "usb-devices"):
        src = os.path.join(usbutils_stage, "bin", tool)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(BIN, tool))
    _usb_fetch(USBIDS_URL, os.path.join(SHARE, "usb.ids"))
    print(f"    ✓ usb.ids ({os.path.getsize(os.path.join(SHARE, 'usb.ids')):,} B)")

    # ── 8. knihovny → assets/usr/lib ───────────────────────────────────────
    print("─" * 60)
    print("[usbtools] libs → assets/usr/lib ...")
    def copy_lib(src, dst):
        shutil.copy2(src, dst)
        _usb_verify(dst, os.path.basename(dst), is_shared=True)
    def ship_lib(stage, bases, names):
        """Vezmi první existující kandidát (libtool cross někdy jmenuje .so
        bez verze) a materiálizuj pod VŠEMI potřebnými jmény — DT_NEEDED
        linkeru může ukazovat na libusb-1.0.so.0 i libusb-1.0.so podle toho,
        jaké SONAME libtool do knihovny zapsal."""
        cand = _libusb_candidates(stage, bases)
        if not cand:
            return False
        for nm in names:
            dst = os.path.join(LIB, nm)
            if not os.path.exists(dst):
                copy_lib(cand[0], dst)
        return True
    ship_lib(libusb_stage, libusb_bases, ("libusb-1.0.so.0", "libusb-1.0.so"))
    ship_lib(hidapi_stage, ("libhidapi-libusb.so", "libhidapi-libusb.so.0", "libhidapi-libusb.so.0.0.0"),
             ("libhidapi-libusb.so", "libhidapi-libusb.so.0"))
    ship_lib(libusbgx_stage, libusbgx_bases, ("libusbgx.so.3", "libusbgx.so"))
    # gadget-* a show-* nástroje libusbgx → assets/usr/bin
    examples_src = os.path.join(libusbgx_stage, "bin")
    if os.path.isdir(examples_src):
        for fn in sorted(os.listdir(examples_src)):
            fp = os.path.join(examples_src, fn)
            if os.path.isfile(fp) and not fn.endswith(".py"):
                shutil.copy2(fp, os.path.join(BIN, fn))
                _usb_verify(os.path.join(BIN, fn), fn)
                print(f"    ✓ {fn} ({os.path.getsize(fp):,} B)")

    # ── 9. sanity: NEEDED mimo /system ─────────────────────────────────────
    print("─" * 60)
    print("[usbtools] NEEDED mimo /system ...")
    shipped_libs = set(os.listdir(LIB))
    for fn in sorted(os.listdir(BIN)):
        fp = os.path.join(BIN, fn)
        if not os.path.isfile(fp) or not os.access(fp, os.X_OK):
            continue
        for lib in _usb_readelf_needed(fp):
            if lib in SYSTEM_LIBS or lib.startswith("libgcc"):
                continue
            if lib not in shipped_libs and not os.path.exists(os.path.join("/system/lib64", lib)):
                print(f"    ! {fn}: NEEDED {lib} — mimo assets/usr/lib i /system!")

    # ── 10. tarball (bin/ + lib/ + share/ → $PREFIX) ────────────────────────
    print("─" * 60)
    print("[usbtools] tarball ...")
    tgz = os.path.join(builds_dir, "usbtools.tar.gz")
    with tarfile.open(tgz, "w:gz") as tf:
        for sub in ("bin", "lib", "share"):
            base = os.path.join(assets_usr, sub)
            if not os.path.isdir(base):
                continue
            for root, _dirs, files in os.walk(base):
                for fn in files:
                    fp = os.path.join(root, fn)
                    arc = os.path.relpath(fp, assets_usr)
                    tf.add(fp, arcname=arc)
    print(f"    ✓ {tgz} ({os.path.getsize(tgz)/1024/1024:.1f} MB)")



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
    elif cmd == "usbtools":
        build_usb_tools.remote()
    elif cmd == "build":
        build.remote()
    elif cmd == "all":
        build_native.remote()
        build.remote()
    elif cmd == "list":
        list_volume.remote()
    else:
        print("Usage: modal run modal_build.py [init|upload|native|usbtools|build|all|list]")
