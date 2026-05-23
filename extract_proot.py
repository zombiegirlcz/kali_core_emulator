import struct, os, sys, tarfile, io

ASSETS = r"C:\Users\zombiegirlcz\AndroidStudioProjects\nethunteraioperator\app\src\main\assets"
TMP = r"C:\Users\zombiegirlcz\AppData\Local\Temp\proot_extract"
BASE_URL = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-71"

arches = ["aarch64", "arm", "i686", "x86_64"]

os.makedirs(TMP, exist_ok=True)

for arch in arches:
    deb_path = os.path.join(TMP, f"proot_{arch}.deb")
    
    # Download
    url = f"{BASE_URL}_{arch}.deb"
    print(f"Downloading {url}...")
    import urllib.request
    urllib.request.urlretrieve(url, deb_path)
    
    # Parse ar archive
    with open(deb_path, 'rb') as f:
        data = f.read()
    
    assert data[:8] == b"!<arch>\n", "Not an ar archive"
    
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
        print(f"  data.tar.xz not found for {arch}")
        os.remove(deb_path)
        continue
    
    # Write tar.xz and extract
    tarxz_path = os.path.join(TMP, f"data_{arch}.tar.xz")
    with open(tarxz_path, 'wb') as f:
        f.write(found_data)
    
    outdir = os.path.join(TMP, f"data_{arch}")
    os.makedirs(outdir, exist_ok=True)
    
    import subprocess
    subprocess.run(["tar", "-xf", tarxz_path, "-C", outdir], check=False, capture_output=True)
    
    # Find proot binary
    proot_found = None
    for root, dirs, files in os.walk(outdir):
        for f in files:
            if f == "proot" and "libexec" not in root:
                proot_found = os.path.join(root, f)
                break
        if proot_found:
            break
    
    if proot_found:
        dest = os.path.join(ASSETS, f"proot-{arch}")
        import shutil
        shutil.copy2(proot_found, dest)
        size = os.path.getsize(dest)
        print(f"  proot-{arch}: {size} bytes OK")
    else:
        print(f"  proot-{arch}: NOT FOUND")
        # Show what we extracted
        for root, dirs, files in os.walk(outdir):
            for f in files:
                print(f"    {os.path.join(root, f)}")
    
    # Cleanup
    os.remove(deb_path)
    os.remove(tarxz_path)
    import shutil
    shutil.rmtree(outdir, ignore_errors=True)

print("DONE")
