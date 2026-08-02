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
  modal run modal_build.py native      # NDK cross-compile C binaries
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
        subprocess.run(
            ["rsync", "-a", "--delete", "/src-baked/", dest],
            check=True,
        )
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


# ── Build native USB bridge binaries (NDK) ───────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=600,
    memory=4096,
)
def build_native():
    """Cross-compile USB bridge C binaries for arm64 using NDK.

    Outputs on Volume:
      /vol/src/app/src/main/jniLibs/arm64-v8a/libusbfd_exporter.so
      /vol/src/app/src/main/assets/usb_bridge
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

    # Commit to Volume
    build_vol.commit()
    print(f"[native] Binaries committed to Volume.")


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
