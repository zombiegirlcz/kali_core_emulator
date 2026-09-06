"""RECOVERY: rebuild usr tools (sed/rsync/nano/rg) na Modal cloudu.

KONTEXT (2026-08-23): Bionic host nástroje zmizely z
  /vol/src/app/src/main/assets/usr/{bin,lib}
na Modal Volume i z lokálních assets (a tím pádem i z posledních APK).
Příčina: rsync -a --delete při upload_src bez předchozího pull_full_assets
(známý pattern z AGENTS.md bugů 2026-08-02 / 2026-08-11) — binárky jsou
build artefakty (gitignored), žijí jen na Volume a po pullu lokálně.

Tento skript je přebuduje PŘÍMO do Volume src/app/src/main/assets/usr/
(tak je další pull_full_assets/upload nerozseká) + regeneruje
/vol/builds/usrtools.tar.gz. Build logika = verze z gitu (1741e3f,
tools/modal_build.py _build_usrtools), beze změn pinů verzí.

Použití:
  modal run tools/rebuild_usrtools_recovery.py rebuild   # build + commit na Volume
  modal volume get --force kali-build-data \
      src/app/src/main/assets/usr/bin/sed app/src/main/assets/usr/bin/sed
  # ... (rsync, nano, rg stejně)

Ověření binárek: verify_bionic() — interpreter MUSÍ být
/system/bin/linker64 (glibc build by na hostu spadl na rseq/SIGSYS).
"""

import modal
import os
import shutil
import subprocess

recovery_app = modal.App("kali-core-usrtools-recovery")

# ── Shodné konstanty s tools/modal_build.py (1741e3f) ────────────────────────
ANDROID_SDK_ROOT = "/opt/android-sdk"
NDK_VERSION = "r28"
NDK_DIR = f"/opt/android-ndk-{NDK_VERSION}"
build_vol = modal.Volume.from_name("kali-build-data", create_if_missing=True)

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
        f"wget -q https://dl.google.com/android/repository/android-ndk-{NDK_VERSION}-linux.zip -O /tmp/ndk.zip",
        f"unzip -q /tmp/ndk.zip -d /opt/",
        "rm /tmp/ndk.zip",
    )
    .env({
        "ANDROID_HOME": ANDROID_SDK_ROOT,
        "ANDROID_SDK_ROOT": ANDROID_SDK_ROOT,
        "JAVA_HOME": "/opt/java/openjdk",
    })
)

USRTOOLS_PREFIX = "/data/user/0/com.linux_core/files/usr"
USRTOOLS_API = 28

NCURSES_VER = "6.5"
SED_VER = "4.9"
RSYNC_VER = "3.3.0"
NANO_VER = "8.2"
RG_VER = "14.1.1"

usrtools_image = (
    base_image
    .run_commands(
        "apt-get update -qq || true",
        "apt-get install -y -qq software-properties-common 2>/dev/null || true",
        "add-apt-repository -y universe 2>/dev/null || true",
        "apt-get update -qq",
        "apt-get install -y -qq "
        "gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu "
        "libc6-dev-arm64-cross libc6-arm64-cross "
        "build-essential make autoconf automake pkg-config libtool cmake "
        "curl wget xz-utils bzip2 file patchelf gawk",
        "curl -sSf https://sh.rustup.rs -o /tmp/rustup-init.sh",
        "sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain stable",
        "/root/.cargo/bin/rustup target add aarch64-linux-android",
    )
    .env({
        "PATH": "/root/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    })
)


@recovery_app.function(
    image=usrtools_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def rebuild():
    """Přebuduje sed/rsync/nano/rg (Bionic) přímo do Volume assets."""
    import tarfile

    # Výstup PŘÍMO do Volume src tree (ne do /tmp), aby ho upload nepřepsal
    assets_usr = "/vol/src/app/src/main/assets/usr"
    builds_dir = "/vol/builds"

    PREFIX = USRTOOLS_PREFIX
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
    LDFLAGS = "-pie -Wl,-rpath='$$ORIGIN/../lib'"

    BIN = os.path.join(assets_usr, "bin")
    LIB = os.path.join(assets_usr, "lib")
    os.makedirs(BIN, exist_ok=True)
    os.makedirs(LIB, exist_ok=True)
    for d in (WORK, STAGE, builds_dir):
        os.makedirs(d, exist_ok=True)

    # strip wrapper (make install volá strip; NDK llvm-strip umí ARM64)
    STRIP_DIR = os.path.join(WORK, "stripbin")
    os.makedirs(STRIP_DIR, exist_ok=True)
    wrapper = os.path.join(STRIP_DIR, "strip")
    if not os.path.lexists(wrapper):
        ndk_strip = os.path.join(tc_bin, "llvm-strip")
        if os.path.exists(ndk_strip):
            os.symlink(ndk_strip, wrapper)
        else:
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

    SYSTEM_LIBS = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so"}

    def verify_bionic(path, name):
        el = subprocess.run([READELF, "-l", path], capture_output=True, text=True).stdout
        interp = next(
            (l.split(":")[-1].strip().rstrip("]") for l in el.splitlines() if "interpreter" in l),
            None,
        )
        print(f"    {name}: interpreter={interp}")
        if interp != "/system/bin/linker64":
            raise SystemExit(f"[usrtools] {name}: NENI Bionic (interpreter={interp})!")

    # ── 1. ncursesw 6.5 (staticky, wide-char) ──────────────────────────────
    print(f"=== ncurses-{NCURSES_VER} ===")
    nc_dir = os.path.join(WORK, f"ncurses-{NCURSES_VER}")
    if not os.path.isdir(nc_dir):
        fetch(f"https://ftp.gnu.org/gnu/ncurses/ncurses-{NCURSES_VER}.tar.gz",
              os.path.join(WORK, "ncurses.tar.gz"))
        extract(os.path.join(WORK, "ncurses.tar.gz"), WORK)
    run(["./configure", "--host=aarch64-linux-android", f"--prefix={STAGE}",
         "--enable-widec", "--without-debug", "--without-ada",
         "--disable-shared", "--disable-db-install", "--without-cxx-binding",
         "--without-manpages", "--without-tests",
         "--with-fallbacks=xterm,xterm-256color,screen,screen-256color,linux,vt100,ansi",
         f"CC={CC}", f"CFLAGS={CFLAGS}", f"LDFLAGS={LDFLAGS}"], cwd=nc_dir)
    run(["make", "-j8"], cwd=nc_dir)
    run(["make", "install"], cwd=nc_dir)

    # ── 2. sed 4.9 ─────────────────────────────────────────────────────────
    print(f"=== sed-{SED_VER} ===")
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

    # ── 3. rsync 3.3.0 (+ známé patche pro nové GCC) ───────────────────────
    print(f"=== rsync-{RSYNC_VER} ===")
    rsync_dir = os.path.join(WORK, f"rsync-{RSYNC_VER}")
    if not os.path.isdir(rsync_dir):
        fetch(f"https://download.samba.org/pub/rsync/src/rsync-{RSYNC_VER}.tar.gz",
              os.path.join(WORK, "rsync.tar.gz"))
        extract(os.path.join(WORK, "rsync.tar.gz"), WORK)
    _sc = os.path.join(rsync_dir, "syscall.c")
    with open(_sc) as _f:
        _src = _f.read()
    _src = _src.replace("OFF_T lseek64();", "extern OFF_T lseek64(int, OFF_T, int);")
    _src = _src.replace("off64_t lseek64();", "extern off64_t lseek64(int, off64_t, int);")
    with open(_sc, "w") as _f:
        _f.write(_src)
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

    # ── 4. nano 8.2 (tiny, static ncursesw ze stage) ───────────────────────
    print(f"=== nano-{NANO_VER} ===")
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

    # ── 5. kontrola NEEDED mimo /system ────────────────────────────────────
    shipped = set()
    for name in ("nano", "sed", "rsync"):
        for lib in needed_libs(os.path.join(BIN, name)):
            if lib in SYSTEM_LIBS or lib in shipped:
                continue
            shipped.add(lib)
            print(f"    ! {name}: NEEDED mimo /system: {lib}")
    if not shipped:
        print("    (žádné host-side .so — vše čistě Bionic)")

    # ── 6. ripgrep 14.1.1 (Rust → aarch64-linux-android) ───────────────────
    print(f"=== ripgrep-{RG_VER} ===")
    rg_top = os.path.join(WORK, f"ripgrep-{RG_VER}")
    if not os.path.isdir(rg_top):
        fetch(f"https://github.com/BurntSushi/ripgrep/archive/refs/tags/{RG_VER}.tar.gz",
              os.path.join(WORK, "ripgrep.tar.gz"))
        extract(os.path.join(WORK, "ripgrep.tar.gz"), WORK)
    if not os.path.isdir(rg_top):
        cand = [d for d in os.listdir(WORK) if d.startswith("ripgrep")]
        rg_top = os.path.join(WORK, cand[0]) if cand else None
    if not rg_top:
        raise SystemExit("[usrtools] ripgrep zdroj nerozbalen!")

    linker = f"{tc_bin}/aarch64-linux-android{USRTOOLS_API}-clang"
    for alt_api in (USRTOOLS_API, 24, 21):
        if os.path.exists(f"{tc_bin}/aarch64-linux-android{alt_api}-clang"):
            linker = f"{tc_bin}/aarch64-linux-android{alt_api}-clang"
            break
    rg_env = dict(os.environ)
    rg_env["PATH"] = "/root/.cargo/bin:" + rg_env.get("PATH", "")
    rg_env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = linker
    cargo_cfg = os.path.join(WORK, "cargo-android.toml")
    with open(cargo_cfg, "w") as f:
        f.write("[target.aarch64-linux-android]\n")
        f.write(f'linker = "{linker}"\n')
    run(["cargo", "--config", cargo_cfg, "build", "--release",
         "--target", "aarch64-linux-android",
         "--no-default-features"],
        cwd=rg_top, env=rg_env)
    rg_src = os.path.join(rg_top, "target/aarch64-linux-android/release/rg")
    if not os.path.exists(rg_src):
        raise SystemExit("[usrtools] rg nebyl vyroben!")
    shutil.copy(rg_src, os.path.join(BIN, "rg"))

    # ── 7. usrtools.tar.gz (konzistence /vol/builds) + shrnutí ─────────────
    tgz = os.path.join(builds_dir, "usrtools.tar.gz")
    with tarfile.open(tgz, "w:gz") as tf:
        for root, _dirs, files in os.walk(assets_usr):
            for fn in files:
                fp = os.path.join(root, fn)
                tf.add(fp, arcname=os.path.relpath(fp, assets_usr))
    print(f"=== {tgz} ({os.path.getsize(tgz)/1024/1024:.1f} MB) ===")
    for name in ("sed", "rsync", "nano", "rg"):
        p = os.path.join(BIN, name)
        print(f"    ✓ {name}: {os.path.getsize(p):,} B")
    build_vol.commit()
    print("[recovery] Volume committed — binárky jsou zpět v src/app/src/main/assets/usr/bin/")
