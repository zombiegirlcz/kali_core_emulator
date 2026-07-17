"""Modal build pipeline for kali_core_emulator.

Volume layout after setup:
  /vol/keys/release.jks        – signing keystore (persistent)
  /vol/src/                    – project source tree (upload once, update on change)
  /vol/gradle-cache/           – gradle dependency cache (persistent)
  /vol/builds/app-debug.apk    – latest built APK

Setup:
  1) modal secret create build-secrets RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)
  2) modal run modal_build.py::init_keys
  3) modal run modal_build.py::upload_src
  4) modal run modal_build.py::build

  Or do upload+build in one step:
     modal run modal_build.py all
"""

# Force rebuild marker: 2026-07-07T02:00:00Z

import modal
import os
import shutil
import subprocess
import sys
from pathlib import Path

# Auto-detect local repo root — directory containing this script
REPO_ROOT = Path(__file__).resolve().parent

app = modal.App("kali-core-build")

ANDROID_SDK_ROOT = "/opt/android-sdk"

build_vol = modal.Volume.from_name("kali-build-data", create_if_missing=True)

_IGNORE_PARTS = frozenset({".git", ".gradle", "__pycache__", "node_modules", "logcat.log", "build.log"})


def _ignore_path(p):
    """Return True for paths that should be EXCLUDED (ignore=True = skip)."""
    parts = p.parts
    for i, part in enumerate(parts):
        if part in _IGNORE_PARTS:
            return True
        if part == "build" and i > 0 and parts[i - 1] == "app":
            return True
    return False


# ── Image with Android SDK + JDK 21 ──────────────────────────────────────────
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
    str(REPO_ROOT),
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


# ── Clean source on Volume ───────────────────────────────────────────────────
@app.function(
    image=source_image,
    volumes={"/vol": build_vol},
    timeout=300,
    memory=512,
)
def clean_src():
    """Smaže /vol/src na Volume – kompletní reset zdrojáků.

    Použití:
        modal run modal_build.py clean

    Poté je nutné znovu nahrát zdrojáky přes `upload` (nebo `build`,
    pokud main() zavolá upload_src automaticky). Čistí se jen adresář
    `src/`, ostatní Volume data (gradle cache, klíče, builds) zůstávají.
    """
    src_path = "/vol/src"
    if os.path.isdir(src_path):
        size_before = sum(
            os.path.getsize(os.path.join(root, f))
            for root, _, files in os.walk(src_path)
            for f in files
        )
        print(f"[clean] Removing {src_path} ({size_before:,} bytes) ...")
        shutil.rmtree(src_path)
        build_vol.commit()
        print(f"[clean] Done. Source tree wiped from Volume.")
    else:
        print(f"[clean] {src_path} does not exist – nothing to remove.")


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


# ── Build APK ────────────────────────────────────────────────────────────────
@app.function(
    image=source_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("build-secrets")],
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build():
    """Build the debug APK from the source tree on the Volume."""
    # Prefer /vol/src (uploaded via upload_src) if it has gradlew, else fall back to /src-baked
    if os.path.isfile("/vol/src/gradlew"):
        src_dir = "/vol/src"
        print(f"[build] Using Volume source: {src_dir}")
    else:
        src_dir = "/src-baked"
        print(f"[build] Using baked-in source: {src_dir}")

    vol_keys = "/vol/keys"

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

    # ---- list source dir contents for diagnostics ----
    print(f"[build] Contents of {src_dir}:")
    for entry in sorted(os.listdir(src_dir)):
        full = os.path.join(src_dir, entry)
        sz = os.path.getsize(full) if os.path.isfile(full) else 0
        print(f"  {'FILE' if os.path.isfile(full) else 'DIR '}  {entry}  ({sz:,} bytes)")
    print(f"[build] gradlew exists: {os.path.isfile(os.path.join(src_dir, 'gradlew'))}")

    # ---- gradle wrapper ----
    gradlew = os.path.join(src_dir, "gradlew")
    os.chmod(gradlew, 0o755)

    # ---- ensure gradle cache dir ----
    os.makedirs("/vol/gradle-cache", exist_ok=True)

    # ---- build ----
    print("[build] Running: ./gradlew assembleDebug")
    result = subprocess.run(
        [str(gradlew), "assembleDebug", "--no-daemon", "--stacktrace"],
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
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "init":
        init_keys.remote()
    elif cmd == "upload":
        upload_src.remote()
    elif cmd == "clean":
        clean_src.remote()
    elif cmd == "build":
        build.remote()
    elif cmd == "list":
        list_volume.remote()
    elif cmd in ("all", "pipeline"):
        print("[pipeline] Running: upload_src → build")
        upload_src.remote()
        build.remote()
    else:
        print("Usage: modal run modal_build.py [init|upload|clean|build|list|all]")
