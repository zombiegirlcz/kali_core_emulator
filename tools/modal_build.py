"""Modal build pipeline for kali_core_emulator.

Zdroj pravdy je GitHub (zombiegirlcz/kali_core_emulator), ne lokální disk.
Modal si zdrojový strom natahuje sám přes git clone/fetch přímo z GitHubu —
žádný upload z telefonu, žádný rsync přes mobilní síť.

Volume layout after setup:
  /vol/keys/release.jks        – signing keystore (persistent)
  /vol/src/                    – project source tree (git clone z GitHubu)
  /vol/gradle-cache/           – gradle dependency cache (persistent)
  /vol/builds/app-debug.apk    – latest built APK

Setup:
  1) modal secret create build-secrets RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)
  2) modal secret create github-token GITHUB_TOKEN=<personal access token>
  3) modal run modal_build.py init     # store keystore
  4) modal run modal_build.py sync     # git clone/pull zdroje z GitHubu
  5) modal run modal_build.py all      # compile native + build APK

Sync (VŽDY samostatně — buildy ho nikdy nevolají):
  modal run modal_build.py sync   # git clone (poprvé) nebo fetch+reset --hard (dál)
  modal run modal_build.py clean  # smaže src + gradle-cache na Volume (keys/builds zůstanou)

Pravidlo zůstává stejné jako dřív: lokální git push na GitHub je jediný krok
z telefonu. Modal si zbytek (stažení, sestavení) dělá sám přes svou vlastní,
mnohem stabilnější síť.

Individual steps:
  modal run modal_build.py native      # NDK cross-compile C binaries + usr tools (nano/rsync/sed/rg)
  modal run modal_build.py build       # Gradle assembleDebug only
  modal run modal_build.py list        # show Volume contents
"""

# Force rebuild marker: 2026-07-07T02:00:00Z

import json
import modal
import os
import shutil
import subprocess
import sys

APP_NAME = "kali-gui-build"
VOLUME_NAME = "kali-build-data"
APK_OUTPUT = "app-debug.apk"

app = modal.App(APP_NAME)

ANDROID_SDK_ROOT = "/opt/android-sdk"

build_vol = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)

# ── GitHub jako zdroj pravdy ─────────────────────────────────────────────────
# Repo se klonuje/pulluje přímo na Modal straně, nikdy se z telefonu neuploaduje
# nic ručně. .gitignore v repu (build/, .gradle/, __pycache__, *.log, ...) řeší
# excludes stejně, jako to dřív dělal _IGNORE_PARTS — build artefakty (su_daemon,
# su_wrapper, usb_bridge, assets/usr/*) jsou po native buildu commitnuté zpět
# do repa (pull_full_assets → git add/commit/push), takže sync vždy odpovídá
# 1:1 tomu, co je na GitHubu.
# HARDCODEOVANO na projekt (nasazeno pres sed). ZAMERNE bez os.environ.get -
# Modal forwarduje env z lokalniho shellu, takze GITHUB_REPO v exportu by
# prepisoval default a GUI/assistant by klonovaly core. Proto literál.
GITHUB_REPO = "zombiegirlcz/kali_core_emulator"
GITHUB_BRANCH = "master"


# ── Image with Android SDK + JDK 21 + NDK ────────────────────────────────────
NDK_VERSION = "r28"
NDK_DIR = f"/opt/android-ndk-{NDK_VERSION}"

base_image = (
    modal.Image.from_registry("eclipse-temurin:21-jdk")
    .apt_install("unzip", "wget", "git", "file", "rsync", "python3", "python3-pip", "python-is-python3",
                  "bison", "flex", "cmake", "make", "ninja-build", "pkg-config", "libssl-dev", "build-essential")
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

# (Dřívější blok tady pekl lokální adresář z telefonu do image přes
# add_local_dir — to byl přesně ten nespolehlivý "upload" krok. Nahrazeno
# funkcí sync() níže, která běží na Modal straně a stahuje z GitHubu sama.)

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
        "curl wget xz-utils bzip2 file patchelf gawk",
        # Rust + Android target (Bionic)
        "curl -sSf https://sh.rustup.rs -o /tmp/rustup-init.sh",
        "sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain stable",
        "/root/.cargo/bin/rustup target add aarch64-linux-android",
    )
    .env({
        "PATH": "/root/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    })
)


# ── Sync source from GitHub to Volume ────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("github-token")],
    timeout=600,
    memory=1024,
)
def sync():
    """Git clone (poprvé) nebo fetch + reset --hard (dál) přímo z GitHubu.

    Nahrazuje upload_basic/upload_force/upload_clean. Žádný rsync z telefonu —
    Modal si repo stahuje sám přes vlastní síť. Výsledný strom na Volume vždy
    1:1 odpovídá GITHUB_BRANCH na GitHubu (deterministické, žádná otázka
    "mazat, nebo ne" jako u rsyncu).
    """
    token = os.environ.get("GITHUB_TOKEN", "")
    auth = f"{token}@" if token else ""
    repo_url = f"https://{auth}github.com/{GITHUB_REPO}.git"
    dest = "/vol/src"

    if os.path.isdir(os.path.join(dest, ".git")):
        print(f"[sync] Repo už existuje na Volume — fetch + reset --hard origin/{GITHUB_BRANCH}")
        subprocess.run(["git", "remote", "set-url", "origin", repo_url], cwd=dest, check=True)
        subprocess.run(["git", "fetch", "origin", GITHUB_BRANCH], cwd=dest, check=True)
        subprocess.run(["git", "reset", "--hard", f"origin/{GITHUB_BRANCH}"], cwd=dest, check=True)
        # ZÁMĚRNĚ BEZ `git clean`: ponechává postavené build artefakty
        # (proot-static-*, loader-static-*, *.so, binárky v assets/), které NEjsou
        # v gitu (untracked). Díky tomu `smart_build` najde proot/lib/bin na
        # Volume a podle `git diff` rozhodne o skipu. `reset --hard` aktualizuje
        # tracked soubory 1:1 na GitHub; untracked artefakty přežijí sync.
    else:
        print(f"[sync] Klonuji {GITHUB_REPO}@{GITHUB_BRANCH} -> {dest}")
        if os.path.isdir(dest):
            shutil.rmtree(dest)
        subprocess.run(
            ["git", "clone", "--branch", GITHUB_BRANCH, repo_url, dest],
            check=True,
        )

    # Zamaskovat token v remote URL, kdyby si někdo dal `git remote -v`.
    subprocess.run(
        ["git", "remote", "set-url", "origin", f"https://github.com/{GITHUB_REPO}.git"],
        cwd=dest, check=True,
    )
    build_vol.commit()
    print("[sync] Hotovo. Tracked strom = 1:1 GitHub; build artefakty (proot/lib/bin) zachovány.")


@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=600,
    memory=1024,
)
def clean():
    """Smaže /vol/src a /vol/gradle-cache (keys/builds zůstanou).
    Následuj sync() pro čerstvý baseline z GitHubu."""
    for p in ("/vol/src", "/vol/gradle-cache"):
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
            print(f"[clean] removed {p}")
        else:
            print(f"[clean] {p} absent")
    build_vol.commit()
    print("[clean] Done.")




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

def _deploy_usb_gadget_module(src_dir):
    """Kopíruje custom_usb_g2_setup zip z repa na Volume do magisk-modules/."""
    src_zip = os.path.join(src_dir, "magisk-modules", "custom_usb_g2_setup-v2.1.zip")
    if not os.path.exists(src_zip):
        print("[usb-module] custom_usb_g2_setup zip nenalezen — PŘESKOČEN")
        return
    # Na Volume: src/magisk-modules/custom_usb_g2_setup-v2.1.zip
    vol_magisk = "/vol/src/magisk-modules"
    os.makedirs(vol_magisk, exist_ok=True)
    dest_zip = os.path.join(vol_magisk, "custom_usb_g2_setup-v2.1.zip")
    if os.path.exists(dest_zip) and os.path.samefile(src_zip, dest_zip):
        print(f"[usb-module] ZIP už na Volume — PŘESKOČEN")
        return
    shutil.copy2(src_zip, dest_zip)
    print(f"[usb-module] Kopíruji USB gadget Magisk modul → {dest_zip}")
    print(f"           ({os.path.getsize(dest_zip):,} B)")


@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build_native():
    """Full native build (lib + bin + usr tools)."""
    src_dir = "/vol/src"
    cpp_dir = os.path.join(src_dir, "app/src/main/cpp")
    if not os.path.isdir(cpp_dir):
        print(f"[native] {cpp_dir} neexistuje — projekt nemá nativní kód, "
              "NDK binárky budou přeskočeny.")
    else:
        _build_native_lib(src_dir)
        _build_native_bin(src_dir)
    # linux-x11 je v samostatném adresáři (linux-x11/src/main/cpp), ne v cpp/
    _build_linux_x11(src_dir)
    _build_usrtools(
        os.path.join(src_dir, "app/src/main/assets", "usr"),
        "/vol/builds",
    )
    _deploy_usb_gadget_module(src_dir)
    build_vol.commit()
    print("[native] Binaries committed to Volume.")


# ── Granulární native buildy (pro smart_build) ──────────────────────────────
def _build_native_lib(src_dir):
    """libusbfd_exporter.so (JNI shared library)."""
    cpp_dir = os.path.join(src_dir, "app/src/main/cpp")
    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    cc = f"{tc_bin}/aarch64-linux-android24-clang"
    jnilibs_dir = os.path.join(src_dir, "app/src/main/jniLibs/arm64-v8a")
    assets_dir = os.path.join(src_dir, "app/src/main/assets")
    os.makedirs(jnilibs_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)
    so_path = os.path.join(jnilibs_dir, "libusbfd_exporter.so")
    jni_src = os.path.join(cpp_dir, "usbfd_jni.c")
    if not os.path.isdir(cpp_dir):
        print(f"[native-lib] {cpp_dir} neexistuje — libusbfd_exporter.so PŘESKOČEN")
        return
    if not os.path.exists(jni_src):
        print(f"[native-lib] {jni_src} chybí — libusbfd_exporter.so PŘESKOČEN")
        return
    cmd = [cc, "-shared", "-fPIC", "-o", so_path, jni_src, "-llog", "-landroid"]
    print("─" * 60)
    print("[native-lib] Building libusbfd_exporter.so ...")
    print(f"  {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"  OK  ({os.path.getsize(so_path):,} B)")


def _build_native_bin(src_dir):
    """usb_bridge (static) + su_daemon + su_wrapper."""
    cpp_dir = os.path.join(src_dir, "app/src/main/cpp")
    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    cc = f"{tc_bin}/aarch64-linux-android24-clang"
    assets_dir = os.path.join(src_dir, "app/src/main/assets")
    os.makedirs(assets_dir, exist_ok=True)
    if not os.path.isdir(cpp_dir):
        print(f"[native-bin] {cpp_dir} neexistuje — binárky PŘESKOČENY")
        return
    # usb_bridge
    print("─" * 60)
    print("[native-bin] Building usb_bridge (static)...")
    bridge_src = os.path.join(cpp_dir, "usb_bridge.c")
    bin_path = os.path.join(assets_dir, "usb_bridge")
    if not os.path.exists(bridge_src):
        print(f"[native-bin] {bridge_src} chybí — usb_bridge PŘESKOČEN")
    else:
        cmd = [cc, "-static", "-o", bin_path, bridge_src]
        print(f"  {' '.join(cmd)}")
        subprocess.run(cmd, check=True)
        print(f"  OK  ({os.path.getsize(bin_path):,} B)")
    # su_daemon
    print("─" * 60)
    print("[native-bin] Building su_daemon...")
    daemon_src = os.path.join(cpp_dir, "su_daemon.c")
    daemon_bin_path = os.path.join(assets_dir, "su_daemon")
    if not os.path.exists(daemon_src):
        print(f"[native-bin] {daemon_src} chybí — su_daemon PŘESKOČEN")
    else:
        cmd = [cc, "-o", daemon_bin_path, daemon_src]
        print(f"  {' '.join(cmd)}")
        subprocess.run(cmd, check=True)
        print(f"  OK  ({os.path.getsize(daemon_bin_path):,} B)")
    # su_wrapper
    print("─" * 60)
    print("[native-bin] Building su_wrapper (static)...")
    wrapper_src = os.path.join(cpp_dir, "su_wrapper.c")
    wrapper_bin_path = os.path.join(assets_dir, "su_wrapper")
    if not os.path.exists(wrapper_src):
        print(f"[native-bin] {wrapper_src} chybí — su_wrapper PŘESKOČEN")
    else:
        cmd = [cc, "-static", "-o", wrapper_bin_path, wrapper_src]
        print(f"  {' '.join(cmd)}")
        subprocess.run(cmd, check=True)
        print(f"  OK  ({os.path.getsize(wrapper_bin_path):,} B)")


def _build_linux_x11(src_dir):
    """Build linux-x11 X server using CMake (X11 headers, dix-config.h, pixman-version.h).

    X server vyžaduje generované config headers (dix-config.h, pixman-version.h,
    globals.h, xkb-config.h, ...) které vznikají při CMake configure fázi.
    Ruční kompilace tyto headery nevygeneruje, takže je nutné použít CMake.
    """
    lorie_cpp = os.path.join(src_dir, "app/src/main/linux-x11/src/main/cpp")
    if not os.path.isdir(lorie_cpp):
        print(f"[linux-x11] {lorie_cpp} neexistuje — linux-x11 PŘESKOČEN")
        return
    assets_dir = os.path.join(src_dir, "app/src/main/assets")
    bin_dir = os.path.join(assets_dir, "usr/bin")
    lib_dir = os.path.join(assets_dir, "usr/lib")
    os.makedirs(bin_dir, exist_ok=True)
    os.makedirs(lib_dir, exist_ok=True)
    linux_x11_bin = os.path.join(lib_dir, "linux-x11")

    # libepoxy upstream files missing from repo (gen_dispatch.py + gl.xml)
    # Fetch them before CMake configure so GL/gl.h can be generated.
    epoxy_dir = os.path.join(lorie_cpp, "libepoxy")
    gen_dispatch = os.path.join(epoxy_dir, "src", "gen_dispatch.py")
    gl_xml = os.path.join(epoxy_dir, "registry", "gl.xml")
    if not os.path.exists(gen_dispatch) or not os.path.exists(gl_xml):
        print("  [linux-x11] Fetching missing libepoxy upstream files...")
        os.makedirs(os.path.dirname(gen_dispatch), exist_ok=True)
        os.makedirs(os.path.dirname(gl_xml), exist_ok=True)
        subprocess.run(["wget", "-q",
                        "https://raw.githubusercontent.com/anholt/libepoxy/1.5.10/src/gen_dispatch.py",
                        "-O", gen_dispatch], check=True)
        subprocess.run(["wget", "-q",
                        "https://raw.githubusercontent.com/anholt/libepoxy/1.5.10/registry/gl.xml",
                        "-O", gl_xml], check=True)
        print(f"    ✓ {gen_dispatch}")
        print(f"    ✓ {gl_xml}")
    # Apply libepoxy.patch to gen_dispatch.py if not already applied
    patch_file = os.path.join(lorie_cpp, "patches", "libepoxy.patch")
    if os.path.exists(patch_file):
        result = subprocess.run(
            ["patch", "-p1", "-d", epoxy_dir, "-i", patch_file, "--dry-run"],
            capture_output=True, text=True)
        if result.returncode == 0:
            print("  [linux-x11] Applying libepoxy.patch...")
            subprocess.run(["patch", "-p1", "-d", epoxy_dir, "-i", patch_file], check=True)
            print("    ✓ libepoxy.patch applied")
        elif result.returncode != 0:
            # Patch was already applied (dry-run failed), skip re-application
            print("  [linux-x11] libepoxy.patch already applied, skipping")
        else:
            print("  [linux-x11] Error applying libepoxy.patch:", result.stderr)

    print("─" * 60)
    print("[linux-x11] Building X server via CMake (NDK cross-compile)...")
    print(f"  Source: {lorie_cpp}")
    print(f"  Output: {linux_x11_bin}")

    # CMake build dir (mimo /vol/src, aby se necetoval do APK)
    build_dir = "/tmp/linux-x11-build"
    if os.path.exists(build_dir):
        import shutil as _sh
        _sh.rmtree(build_dir)
    os.makedirs(build_dir, exist_ok=True)

    # NDK toolchain file
    ndk_toolchain = os.path.join(NDK_DIR, "build/cmake/android.toolchain.cmake")
    if not os.path.exists(ndk_toolchain):
        print(f"[linux-x11] NDK toolchain nenalezen: {ndk_toolchain}")
        return

    # CMake configure
    # Cílíme na Android API 24+ (minSdk 24), arch arm64-v8a
    cmake_cmd = [
        "cmake",
        "-G", "Ninja",
        "-S", lorie_cpp,
        "-B", build_dir,
        f"-DCMAKE_TOOLCHAIN_FILE={ndk_toolchain}",
        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-24",
        "-DANDROID_STL=c++_static",
        "-DCMAKE_INSTALL_PREFIX=/tmp/linux-x11-install",
    ]
    print(f"  $ {' '.join(cmake_cmd)}")
    proc = subprocess.run(cmake_cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        print(f"  CMAKE CONFIGURE FAILED (rc={proc.returncode})")
        if proc.stdout:
            print(f"  stdout: {proc.stdout[-2000:]}")
        if proc.stderr:
            print(f"  stderr: {proc.stderr[-2000:]}")
        return
    print(f"  ✓ CMake configure OK")

    # CMake build
    build_cmd = ["cmake", "--build", build_dir, "--parallel", "8"]
    print(f"  $ {' '.join(build_cmd)}")
    proc = subprocess.run(build_cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        print(f"  CMAKE BUILD FAILED (rc={proc.returncode})")
        if proc.stdout:
            print(f"  stdout: {proc.stdout[-2000:]}")
        if proc.stderr:
            print(f"  stderr: {proc.stderr[-2000:]}")
        return
    print(f"  ✓ CMake build OK")

    # Najít výstupní binárku — lorie obvykle produkuje 'lorie'/'Xlorie', Xorg 'Xserver'
    # Najít výstupní binárku — lorie obvykle produkuje 'libXlorie.so', případně 'lorie'/'Xlorie'
    candidates = ["libXlorie.so", "lorie", "Xlorie", "linux-x11", "xserver", "Xserver"]
    search_dirs = [build_dir] + [os.path.join(build_dir, d) for d in ("xserver", "X11", "src", "bin")]
    built_bin = None
    for cand in candidates:
        for d in search_dirs:
            cand_path = os.path.join(d, cand)
            if os.path.isfile(cand_path) and os.access(cand_path, os.X_OK):
                built_bin = cand_path
                break
        if built_bin:
            break
    if not built_bin:
        for root, _dirs, files in os.walk(build_dir):
            for f in files:
                if f in ("makekeys",):
                    continue
                fp = os.path.join(root, f)
                if os.path.isfile(fp) and fp.endswith(".so") and os.path.getsize(fp) > 50000:
                    built_bin = fp
                    break
            if built_bin:
                break

    if not built_bin or not os.path.exists(built_bin):
        print(f"  BUILD OK ale výstupní binárka nenalezena v {build_dir}")
        print(f"  Obsah: {os.listdir(build_dir)[:20]}")
        return

    shutil.copy2(built_bin, linux_x11_bin)
    print(f"  OK  {built_bin} → {linux_x11_bin} ({os.path.getsize(linux_x11_bin):,} B)")





@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build_native_lib():
    _build_native_lib("/vol/src")
    build_vol.commit()
    print("[native-lib] committed")


@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build_native_bin():
    _build_native_bin("/vol/src")
    build_vol.commit()
    print("[native-bin] committed")


@app.function(
    image=usrtools_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build_usrtools():
    _build_usrtools(
        os.path.join("/vol/src", "app/src/main/assets", "usr"),
        "/vol/builds",
    )
    build_vol.commit()
    print("[usrtools] committed")
    _build_linux_x11("/vol/src")
    build_vol.commit()
    print("[native-linux-x11] committed")


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
    os.makedirs(BIN, exist_ok=True)
    os.makedirs(LIB, exist_ok=True)

    # Boot refactor fáze 1 (2026-08-14): ŽÁDNÉ rmtree(assets_usr)!
    # assets/usr/bin kromě usrtools obsahuje i další artefakty (terminalmap,
    # ifconfig z repa; usb tools; proot-static-* / loader-static-*). Starý
    # rmtree by je smazal. Build se přeskočí, pokud všechny 4 výstupy už
    # existují (FORCE_USRTOOLS=1 vynutí rebuild).
    _outputs = [os.path.join(BIN, n) for n in ("sed", "rsync", "nano", "rg")]
    if os.environ.get("FORCE_USRTOOLS") != "1" and all(
        os.path.exists(p) and os.path.getsize(p) > 0 for p in _outputs
    ):
        print("[usrtools] sed/rsync/nano/rg už v assets/usr/bin — build PŘESKOČEN "
              "(FORCE_USRTOOLS=1 pro rebuild)")
        # tarball přesto obnov z existujícího obsahu (konzistence /vol/builds)
        tgz = os.path.join(builds_dir, "usrtools.tar.gz")
        with tarfile.open(tgz, "w:gz") as tf:
            for root, _dirs, files in os.walk(assets_usr):
                for fn in files:
                    fp = os.path.join(root, fn)
                    tf.add(fp, arcname=os.path.relpath(fp, assets_usr))
        print(f"    ✓ {tgz} ({os.path.getsize(tgz)/1024/1024:.1f} MB)")
        return

    for d in (WORK, STAGE, builds_dir):
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

# ── Static PRoot build (Termux fork, talloc statically linked) ──────────────
# Source: termux/proot fork (Android patches: link2symlink, kompat, fake_id0,
# ashmem_memfd, shm-helper).  talloc is built from samba source as a static
# library and linked directly → no libtalloc.so.2 needed at runtime.
# Loader is built separately (PROOT_UNBUNDLE_LOADER) to match existing
# ProotManager/launcher.sh deployment model (PROOT_LOADER env var).
PROOT_TAG = "v5.1.107.90"
PROOT_GIT = "https://github.com/termux/proot.git"
TALLOC_VER = "2.4.3"
TALLOC_URL = f"https://www.samba.org/ftp/talloc/talloc-{TALLOC_VER}.tar.gz"

# (asset suffix, NDK triple, uname machine for talloc cross-answers)
PROOT_ARCHS = [
    ("aarch64", "aarch64-linux-android", "aarch64"),
    ("arm",     "armv7a-linux-androideabi", "armv7l"),
    ("i686",    "i686-linux-android", "i686"),
    ("x86_64",  "x86_64-linux-android", "x86_64"),
]
PROOT_API = 24
PROOT_UNBUNDLE_PATH = "/data/data/com.linux_core/files"

_TALLOC_CROSS_ANSWERS = """\
Checking uname sysname type: "Linux"
Checking uname machine type: "{machine}"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: NO
Checking getconf large file support flags work: OK
"""

# Portable replacement for loader-info.awk (avoids gawk strtonum dependency)
_LOADER_INFO_AWK = """\
# Note: This file is included only for targets which have pokedata workaround

function hextodec(h,    i, c, d, v) {
    v = 0
    for (i = 1; i <= length(h); i++) {
        c = tolower(substr(h, i, 1))
        d = index("0123456789abcdef", c) - 1
        if (d < 0) return 0
        v = v * 16 + d
    }
    return v
}

/\\ypokedata_workaround\\y/ { pokedata_workaround = hextodec($2) }
/\\y_start\\y/              { start = hextodec($2) }

END {
    print "#include <unistd.h>"
    print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround - start) ";"
}
"""


def _proot_run(cmd, **kw):
    print(f"  $ {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    subprocess.run(cmd, check=True, **kw)



def _build_proot_static(assets_dir, builds_dir):
    """Cross-compile static proot + loader for all target architectures."""
    import glob as _glob

    WORK = "/tmp/proot-static"
    SRC_CACHE = "/vol/proot-src"
    os.makedirs(WORK, exist_ok=True)
    os.makedirs(SRC_CACHE, exist_ok=True)
    os.makedirs(builds_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)

    tc_bin = f"{NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    READELF = os.path.join(tc_bin, "llvm-readelf")
    STRIP = os.path.join(tc_bin, "llvm-strip")
    AR = os.path.join(tc_bin, "llvm-ar")

    # ── Fetch sources (cached on Volume) ────────────────────────────────────
    proot_clone = os.path.join(SRC_CACHE, "proot")
    if not os.path.isdir(proot_clone):
        _proot_run(["git", "clone", "--depth", "1", "--branch", PROOT_TAG,
                    PROOT_GIT, proot_clone])
    talloc_tar = os.path.join(SRC_CACHE, f"talloc-{TALLOC_VER}.tar.gz")
    if not os.path.exists(talloc_tar):
        _proot_run(["wget", "-q", TALLOC_URL, "-O", talloc_tar])

    results = {}
    for suffix, triple, machine in PROOT_ARCHS:
        # Find the right NDK compiler
        cc = f"{tc_bin}/{triple}{PROOT_API}-clang"
        if not os.path.exists(cc):
            for alt_api in (PROOT_API, 28, 21):
                cand = f"{tc_bin}/{triple}{alt_api}-clang"
                if os.path.exists(cand):
                    cc = cand
                    break
        if not os.path.exists(cc):
            results[suffix] = "SKIP: compiler not found"
            continue

        try:
            _build_proot_one_arch(suffix, cc, triple, machine, proot_clone,
                                  talloc_tar, WORK, assets_dir, READELF, STRIP, AR)
            results[suffix] = "OK"
        except Exception as e:
            results[suffix] = f"FAIL: {e}"

    print("─" * 60)
    print("[proot-static] Results:")
    for k, v in results.items():
        print(f"  {k}: {v}")
    if "FAIL" in results.get("aarch64", "FAIL"):
        raise SystemExit("[proot-static] aarch64 build FAILED — aborting")


def _build_proot_one_arch(suffix, cc, triple, machine, proot_clone,
                          talloc_tar, work, assets_dir, readelf, strip, ar):
    """Build static proot + loader for one architecture."""
    import glob as _glob

    build_dir = os.path.join(work, suffix)
    if os.path.isdir(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(build_dir)

    talloc_src = os.path.join(build_dir, "talloc")
    talloc_lib = os.path.join(build_dir, "talloc-lib")
    proot_src = os.path.join(build_dir, "proot-src")
    os.makedirs(talloc_lib, exist_ok=True)

    # ── 1. Build talloc (static .a) ─────────────────────────────────────────
    print(f"\n  [{suffix}] Building talloc {TALLOC_VER} ...")
    os.makedirs(talloc_src, exist_ok=True)
    _proot_run(["tar", "xzf", talloc_tar, "-C", talloc_src, "--strip-components=1"])

    # Write cross-answers
    cross_file = os.path.join(build_dir, "cross-answers.txt")
    with open(cross_file, "w") as f:
        f.write(_TALLOC_CROSS_ANSWERS.format(machine=machine))

    env = dict(os.environ)
    env["CC"] = cc
    env["CFLAGS"] = "-O2 -fPIC"
    env["LDFLAGS"] = ""

    _proot_run(["./configure",
                f"--prefix={os.path.join(build_dir, 'talloc-install')}",
                "--cross-compile",
                f"--cross-answers={cross_file}",
                "--disable-python",
                "--without-gettext"],
               cwd=talloc_src, env=env)
    _proot_run(["make", "-j4"], cwd=talloc_src, env=env)

    # Collect object files into static archive
    objs = sorted(_glob.glob(os.path.join(talloc_src, "bin", "default", "talloc.c.*.o")))
    if not objs:
        objs = sorted(_glob.glob(os.path.join(talloc_src, "bin", "default", "*.o")))
    if not objs:
        raise RuntimeError(f"talloc: no object files found in {talloc_src}/bin/default/")
    _proot_run([ar, "rcs", os.path.join(talloc_lib, "libtalloc.a"), *objs])
    print(f"    ✓ libtalloc.a ({os.path.getsize(os.path.join(talloc_lib, 'libtalloc.a')):,} B)")

    # ── 2. Prepare proot source ─────────────────────────────────────────────
    print(f"  [{suffix}] Preparing proot source ...")
    _proot_run(["cp", "-R", proot_clone, proot_src])

    # Patch 1: add #include <string.h> to ashmem_memfd.c if missing
    ashmem_c = os.path.join(proot_src, "src", "extension", "ashmem_memfd", "ashmem_memfd.c")
    if os.path.exists(ashmem_c):
        with open(ashmem_c, "r") as f:
            content = f.read()
        if "#include <string.h>" not in content:
            lines = content.split("\n")
            # Insert after first line (#if defined(...))
            lines.insert(1, "#include <string.h>")
            with open(ashmem_c, "w") as f:
                f.write("\n".join(lines))
            print(f"    patch: added #include <string.h> to ashmem_memfd.c")

    # Patch 2: replace loader-info.awk with portable version (no strtonum)
    awk_file = os.path.join(proot_src, "src", "loader", "loader-info.awk")
    if os.path.exists(awk_file):
        with open(awk_file, "w") as f:
            f.write(_LOADER_INFO_AWK)
        print(f"    patch: replaced loader-info.awk (portable, no gawk needed)")

    # ── 3. Build proot ──────────────────────────────────────────────────────
    print(f"  [{suffix}] Building proot (static talloc, PIE) ...")
    env2 = dict(os.environ)
    env2["CPPFLAGS"] = f"-I{talloc_src} -DARG_MAX=131072"
    env2["CFLAGS"] = "-O2 -fPIE -ffunction-sections -fdata-sections"
    env2["LDFLAGS"] = f"-pie -Wl,--gc-sections -L{talloc_lib}"

    _proot_run(["make", "-C", "src", "-j4",
                f"CC={cc}",
                f"LD={cc}",
                f"STRIP={strip}",
                "HAS_LOADER_32BIT=",
                f"PROOT_UNBUNDLE_LOADER={PROOT_UNBUNDLE_PATH}",
                "proot"],
               cwd=proot_src, env=env2)

    proot_bin = os.path.join(proot_src, "src", "proot")
    loader_bin = os.path.join(proot_src, "src", "loader", "loader")
    if not os.path.exists(proot_bin):
        raise RuntimeError("proot binary not found after build")
    if not os.path.exists(loader_bin):
        raise RuntimeError("loader binary not found after build")

    # ── 4. Strip and copy to assets ─────────────────────────────────────────
    _proot_run([strip, proot_bin])
    _proot_run([strip, loader_bin])

    dest_proot = os.path.join(assets_dir, f"proot-static-{suffix}")
    dest_loader = os.path.join(assets_dir, f"loader-static-{suffix}")
    shutil.copy2(proot_bin, dest_proot)
    shutil.copy2(loader_bin, dest_loader)

    # ── 5. Verify ───────────────────────────────────────────────────────────
    print(f"  [{suffix}] Verifying ...")
    # Check no libtalloc dependency
    dyn_out = subprocess.run([readelf, "-d", dest_proot],
                             capture_output=True, text=True).stdout
    needed = [l.split("[")[-1].rstrip("]") for l in dyn_out.splitlines() if "NEEDED" in l]
    if any("talloc" in n for n in needed):
        raise RuntimeError(f"proot-static-{suffix} still links libtalloc dynamically!")
    print(f"    NEEDED: {needed}")

    # Check interpreter
    interp_out = subprocess.run([readelf, "-l", dest_proot],
                                capture_output=True, text=True).stdout
    interp = next((l.split(":")[-1].strip().rstrip("]")
                   for l in interp_out.splitlines() if "interpreter" in l), None)
    print(f"    interpreter: {interp}")

    # Check extensions
    strings_out = subprocess.run(["strings", dest_proot],
                                 capture_output=True, text=True).stdout
    for feat in ["--link2symlink", "--kill-on-exit"]:
        if feat in strings_out:
            print(f"    ✓ {feat} present")
        else:
            print(f"    ⚠ {feat} NOT found")

    print(f"    ✓ proot-static-{suffix} ({os.path.getsize(dest_proot):,} B)")
    print(f"    ✓ loader-static-{suffix} ({os.path.getsize(dest_loader):,} B)")


def build_proot_static():
    """Cross-compile static PRoot (Termux fork) + loader for all ABIs."""
    src_dir = "/vol/src"
    assets_dir = os.path.join(src_dir, "app/src/main/assets")
    _build_proot_static(assets_dir, "/vol/builds")
    build_vol.commit()
    print("[proot-static] Done. Binaries committed to Volume.")


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
            "Sync first (sync je vždy samostatný krok):\n"
            "  modal run modal_build.py::sync",
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
    dest = os.path.join(out_dir, APK_OUTPUT)
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


# ── Smart incremental build orchestrator ────────────────────────────────────
_STATE_FILE = f"/vol/.build_state.{GITHUB_REPO.replace('/', '_')}.json"

_PROOT_OUTPUTS = [
    "app/src/main/assets/proot-static-aarch64",
    "app/src/main/assets/proot-static-arm",
    "app/src/main/assets/proot-static-i686",
    "app/src/main/assets/proot-static-x86_64",
    "app/src/main/assets/loader-static-aarch64",
    "app/src/main/assets/loader-static-arm",
    "app/src/main/assets/loader-static-i686",
    "app/src/main/assets/loader-static-x86_64",
]

@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build_linux_x11():
    _build_linux_x11("/vol/src")
    build_vol.commit()
    print("[native-linux-x11] committed")


_NATIVE_COMPONENTS = {
    "lib": {
        "sources": ["app/src/main/cpp/usbfd_jni.c"],
        "outputs": ["app/src/main/jniLibs/arm64-v8a/libusbfd_exporter.so"],
        "fn": build_native_lib,
    },
    "bin": {
        "sources": ["app/src/main/cpp/usb_bridge.c",
                     "app/src/main/cpp/su_daemon.c",
                     "app/src/main/cpp/su_wrapper.c"],
        "outputs": ["app/src/main/assets/usb_bridge",
                     "app/src/main/assets/su_daemon",
                     "app/src/main/assets/su_wrapper"],
        "fn": build_native_bin,
    },
    "linux-x11": {
        "sources": ["app/src/main/linux-x11/src/main/cpp/lorie"],
        "outputs": ["app/src/main/assets/usr/lib/linux-x11"],
        "fn": build_linux_x11,
    },
    "usrtools": {
        "sources": [],  # externí downloads; self-skip na outputs
        "outputs": ["app/src/main/assets/usr/bin/sed",
                    "app/src/main/assets/usr/bin/rsync",
                    "app/src/main/assets/usr/bin/nano",
                    "app/src/main/assets/usr/bin/rg"],
        "fn": build_usrtools,
    },
}
_GRADLE_SOURCES = [
    "app/src/main/java", "app/src/main/res", "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts", "build.gradle", "settings.gradle",
    "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
    "app/proguard-rules.pro", "app/src/main/assets",
]


def _load_state():
    try:
        with open(_STATE_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_state(state):
    with open(_STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)


def _outputs_exist(outputs, src_dir):
    for o in outputs:
        p = os.path.join(src_dir, o)
        if not os.path.exists(p) or os.path.getsize(p) == 0:
            return False, o
    return True, None


def _git_changed(since_commit, src_dir="/vol/src"):
    if not since_commit:
        return None
    try:
        out = subprocess.check_output(
            ["git", "-C", src_dir, "diff", "--name-only", since_commit, "HEAD"],
            stderr=subprocess.DEVNULL,
        ).decode().split()
        return set(out)
    except Exception:
        return None


def _touches(changed, prefixes):
    if changed is None:
        return None
    for f in changed:
        for p in prefixes:
            if f == p or f.startswith(p + "/"):
                return True
    return False


@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=3600,
    memory=2048,
)
def smart_build(force: bool = False):
    """Chytrý inkrementální build (proot + native + gradle).

    Nejdřív zkontroluje, jestli artefakty (proot/lib/bin) existují. Pokud ne →
    build. Pokud ano → porovná změny zdrojáků přes `git diff` od posledního
    buildu; beze změn → PŘESKOČENO, se změnou → rebuild jen dané komponenty.
    -f/--force vynutí plný rebuild všeho.
    """
    src_dir = "/vol/src"
    if not os.path.isdir(os.path.join(src_dir, ".git")):
        print("[smart] /vol/src není git repo — spusť nejdřív `sync`.")
        sys.exit(1)

    current_commit = subprocess.check_output(
        ["git", "-C", src_dir, "rev-parse", "HEAD"]).decode().strip()
    state = _load_state()
    print(f"[smart] current_commit={current_commit[:10]}  force={force}")

    rebuilt = {}

    # ── proot (externí zdroj → verze podle PROOT_TAG) ──
    proot_state = state.get("proot", {})
    proot_ok, missing = _outputs_exist(_PROOT_OUTPUTS, src_dir)
    proot_need = force or (not proot_ok) or (proot_state.get("tag") != PROOT_TAG)
    if proot_need:
        why = ("force" if force else
               ("chybí " + str(missing) if not proot_ok else
                f"PROOT_TAG {proot_state.get('tag')} -> {PROOT_TAG}"))
        print(f"[smart] proot: BUILD ({why})")
        build_proot_static.remote()
        rebuilt["proot"] = True
    else:
        print("[smart] proot: beze změn — PŘESKOČENO")
        rebuilt["proot"] = False

    # ── native (lib / bin / usrtools) ──
    for name, comp in _NATIVE_COMPONENTS.items():
        cstate = state.get(name, {})
        ok, missing = _outputs_exist(comp["outputs"], src_dir)
        changed = _git_changed(cstate.get("built_commit"), src_dir)
        if name == "usrtools":
            need = force or (not ok)
            why = "force" if force else ("chybí " + str(missing) if not ok else "beze změn")
        else:
            touched = _touches(changed, comp["sources"]) if changed is not None else None
            need = force or (not ok) or (touched is True) or (changed is None and ok)
            why = ("force" if force else
                   ("chybí " + str(missing) if not ok else
                    ("změna: " + ", ".join(sorted(set(comp["sources"]) & changed)) if touched else
                     ("baseline" if changed is None else "beze změn"))))
        if need:
            print(f"[smart] {name}: BUILD ({why})")
            comp["fn"].remote()
            rebuilt[name] = True
        else:
            print(f"[smart] {name}: beze změn — PŘESKOČENO")
            rebuilt[name] = False

    # ── gradle (APK) ──
    gradle_state = state.get("gradle", {})
    apk_path = os.path.join("/vol/builds", APK_OUTPUT)
    apk_ok = os.path.exists(apk_path) and os.path.getsize(apk_path) > 0
    g_changed = _git_changed(gradle_state.get("built_commit"), src_dir)
    g_touched = _touches(g_changed, _GRADLE_SOURCES) if g_changed is not None else None
    if force or any(rebuilt.values()) or (g_touched is True) or (g_changed is None and apk_ok):
        why = ("force" if force else
               ("rebuild nativních/proot" if any(rebuilt.values()) else
                ("změna gradle zdrojů" if g_touched else "baseline")))
        print(f"[smart] gradle: BUILD ({why})")
        build.remote()
        gradle_built = True
    else:
        print("[smart] gradle: beze změn — PŘESKOČENO")
        gradle_built = False

    # ── uložit stav ──
    new_state = dict(state)
    new_state["proot"] = {
        "tag": PROOT_TAG,
        "built_commit": current_commit if rebuilt.get("proot") else proot_state.get("built_commit"),
    }
    for name in _NATIVE_COMPONENTS:
        cstate = state.get(name, {})
        new_state[name] = {"built_commit": current_commit} if rebuilt.get(name) else cstate
    new_state["gradle"] = {"built_commit": current_commit} if gradle_built else gradle_state
    _save_state(new_state)
    build_vol.commit()
    print("[smart] Hotovo. Stav uložen na Volume.")


@app.local_entrypoint()
def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "init":
        init_keys.remote()
    elif cmd == "sync":
        sync.remote()
    elif cmd == "clean":
        clean.remote()
    elif cmd == "native":
        build_native.remote()
    elif cmd == "proot":
        build_proot_static.remote()
    elif cmd == "build":
        build.remote()
    elif cmd == "all":
        smart_build.remote(False)
    elif cmd == "smart":
        smart_build.remote(False)
    elif cmd == "list":
        list_volume.remote()
    else:
        print("Usage: modal run modal_build.py [init|sync|clean|native|proot|build|all|list]")


