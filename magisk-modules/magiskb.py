#!/usr/bin/env python3
"""Magisk module builder — zabalí adresáře modulů na flashable zipy.

Pro každý podadresář s platným module tree (module.prop +
META-INF/com/google/android/update-binary) vytvoří vedle tohoto skriptu zip
<id>-<version>.zip. Zachová unixová exec práva a module.prop v kořeni zipu.

CLI:
    python3 magiskb.py                # zabalí všechny moduly ve složce skriptu
    python3 magiskb.py nh_cpuctl ...  # jen vyjmenované moduly

Import (např. z tools/modal_build.py):
    from magiskb import build_all, build_module
"""
import os
import stat
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def _read_prop(module_dir, key, default=""):
    prop = os.path.join(module_dir, "module.prop")
    try:
        with open(prop, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith(key + "="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return default


def _is_module(module_dir):
    return os.path.isfile(os.path.join(module_dir, "module.prop")) and os.path.isfile(
        os.path.join(module_dir, "META-INF", "com", "google", "android", "update-binary")
    )


def build_module(module_dir, out_dir=HERE):
    """Zabalí jeden modul → cesta k vytvořenému zipu, nebo None při přeskočení."""
    module_dir = module_dir.rstrip("/")
    if not _is_module(module_dir):
        print(f"SKIP: {module_dir} (chybí module.prop nebo update-binary)")
        return None

    mod_id = _read_prop(module_dir, "id") or "module"
    mod_ver = _read_prop(module_dir, "version")
    zip_path = os.path.join(out_dir, f"{mod_id}-{mod_ver}.zip")
    if os.path.exists(zip_path):
        os.remove(zip_path)

    print(f"[{mod_id}] zipuji {module_dir} {mod_ver} ...")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _dirs, files in os.walk(module_dir):
            for f in sorted(files):
                p = os.path.join(root, f)
                rel = os.path.relpath(p, module_dir)
                if rel.startswith("."):
                    continue
                zi = zipfile.ZipInfo.from_file(p, rel)
                zi.compress_type = zipfile.ZIP_DEFLATED
                mode = os.stat(p).st_mode
                zi.external_attr = (stat.S_IMODE(mode) << 16) | 0o100000
                with open(p, "rb") as fh:
                    z.writestr(zi, fh.read())

    with zipfile.ZipFile(zip_path) as z:
        if "module.prop" not in z.namelist():
            os.remove(zip_path)
            raise RuntimeError(f"[{mod_id}] module.prop není v kořeni zipu")

    print(f"[{mod_id}] OK: {zip_path} ({os.path.getsize(zip_path):,} B)")
    return zip_path


def build_all(base=HERE, names=None):
    """Zabalí všechny (nebo vyjmenované) moduly. Vrací seznam zip cest."""
    if names:
        dirs = [os.path.join(base, n) for n in names]
    else:
        dirs = sorted(
            os.path.join(base, d)
            for d in os.listdir(base)
            if os.path.isdir(os.path.join(base, d))
        )
    built = []
    for d in dirs:
        z = build_module(d, out_dir=base)
        if z:
            built.append(z)
    print(f"[done] built={len(built)}")
    return built


if __name__ == "__main__":
    build_all(names=sys.argv[1:] or None)
