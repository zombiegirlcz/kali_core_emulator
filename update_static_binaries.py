import os, urllib.request, shutil

ASSETS = r"C:\Users\zombiegirlcz\AndroidStudioProjects\nethunteraioperator\app\src\main\assets"
BASE_URL = "https://github.com/ZhymabekRoman/proot-static/raw/main"

# This repo seems to provide mostly arm64/armhf binaries
# Based on the file sizes in assets, proot-aarch64 is ~215KB (dynamic)
# but proot_static is ~550KB.
# The user wants to use this specific repo.

downloads = [
    (f"{BASE_URL}/proot_static", "proot-static-aarch64"),
    (f"{BASE_URL}/bin/loader", "loader-static-aarch64")
]

os.makedirs(ASSETS, exist_ok=True)

for url, filename in downloads:
    dest = os.path.join(ASSETS, filename)
    print(f"Downloading {url} -> {filename}...")
    try:
        # Use a custom User-Agent to avoid potential blocks
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response, open(dest, 'wb') as out_file:
            shutil.copyfileobj(response, out_file)
        print(f"  OK: {os.path.getsize(dest)} bytes")
    except Exception as e:
        print(f"  FAILED: {e}")

print("DONE")
