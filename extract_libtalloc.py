import struct, os, sys, tarfile, io, urllib.request

ASSETS = r"C:\Users\zombiegirlcz\AndroidStudioProjects\nethunteraioperator\app\src\main\assets"
TMP = r"C:\Users\zombiegirlcz\AppData\Local\Temp\libtalloc_extract"
BASE_URL = "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3"

arches = ["aarch64", "arm", "i686", "x86_64"]

os.makedirs(TMP, exist_ok=True)

for arch in arches:
    deb_path = os.path.join(TMP, f"libtalloc_{arch}.deb")
    url = f"{BASE_URL}_{arch}.deb"
    print(f"Downloading {url}...")
    try:
        urllib.request.urlretrieve(url, deb_path)
    except Exception as e:
        print(f"  FAILED: {e}")
        continue
    
    with open(deb_path, 'rb') as f:
        data = f.read()
    
    assert data[:8] == b"!<arch>\n", "Not ar"
    
    pos = 8
    found_data = None
    while pos < len(data):
        hdr = data[pos:pos+60]
        name = hdr[0:16].rstrip(b' ').rstrip(b'/').decode()
        size_str = hdr[48:58].rstrip().decode()
        if not size_str:
            break
        size = int(size_str)
        entry_start = pos + 60
        if entry_start + size <= len(data):
            chunk = data[entry_start:entry_start+size]
            if name == "data.tar.xz":
                found_data = chunk
                break
        pos = pos + 60 + size
        if size % 2:
            pos += 1
    
    if found_data is None:
        print(f"  data.tar.xz not found")
        os.remove(deb_path)
        continue
    
    tarxz_path = os.path.join(TMP, f"data_{arch}.tar.xz")
    with open(tarxz_path, 'wb') as f:
        f.write(found_data)
    
    outdir = os.path.join(TMP, f"data_{arch}")
    os.makedirs(outdir, exist_ok=True)
    
    # Use Python tarfile + lzma instead of system tar (works on Windows)
    import lzma, io as io_mod
    with lzma.open(tarxz_path, 'rb') as xz_f:
        tar_bytes = xz_f.read()
    with tarfile.open(fileobj=io_mod.BytesIO(tar_bytes), mode='r:') as tar:
        tar.extractall(path=outdir)
    
    # Find libtalloc.so.2
    for root, dirs, files in os.walk(outdir):
        for f in files:
            if f.startswith("libtalloc.so"):
                src = os.path.join(root, f)
                dest = os.path.join(ASSETS, f"libtalloc-{arch}.so")
                import shutil
                shutil.copy2(src, dest)
                print(f"  {f} -> libtalloc-{arch}.so ({os.path.getsize(dest)} bytes)")
    
    os.remove(deb_path)
    os.remove(tarxz_path)
    import shutil
    shutil.rmtree(outdir, ignore_errors=True)

# Also download the static loader for aarch64
print("Downloading static loader...")
url = "https://raw.githubusercontent.com/ZhymabekRoman/proot-static/main/bin/loader"
try:
    loader_data = urllib.request.urlopen(url).read()
    dest = os.path.join(ASSETS, "loader-aarch64")
    # backup original
    shutil.copy2(dest, dest + ".orig")
    with open(dest, 'wb') as f:
        f.write(loader_data)
    print(f"  loader-aarch64 replaced with static loader ({len(loader_data)} bytes)")
except Exception as e:
    print(f"  FAILED: {e}")

print("DONE")
