# modal-build — REFERENCE

## TROUBLESHOOTING Playbook

Format: **Symptom** → **Confirm** → **Recover** → **Root fix** (already applied in repo).

---

### 1. `su_daemon.c` FTW failure
**Symptom:** `error: 'FTW_SKIP_SUBTREE' undeclared` in `fix_permissions()`.
**Confirm:** `grep FTW_SKIP_SUBTREE app/src/main/cpp/su_daemon.c` → found.
**Recover:** Replace `nftw` + `FTW_SKIP_SUBTREE` with manual walk (`opendir`/`readdir`/`lstat`/`lchown`); skip bind dirs at depth 1; don't follow symlinks. Remove `#include <ftw.h>`, add `<dirent.h>`.
**Root fix:** ✅ Done in `su_daemon.c` (function `fix_walk`). Verify: `grep fix_walk app/src/main/cpp/su_daemon.c`.

---

### 2. ncurses C++ compilation (GCC ≥ 13)
**Symptom:** `error: '__is_integral_helper<unsigned char>'` / `NCURSES_BOOL` redefinition in `c++/cursesw.cc`.
**Confirm:** `make` in ncurses `c++` dir fails.
**Recover:** Add `--without-cxx-binding` to ncurses `configure`.
**Root fix:** ✅ In `tools/modal_build.py` `_build_usrtools` → `ncurses_cfg += ["--without-cxx-binding"]`.

---

### 3. `install.progs` host strip on aarch64 binary
**Symptom:** `strip: Unable to recognise the architecture of file .../tic` during `make install`.
**Confirm:** `make install` fails in `progs` dir; host `/usr/bin/strip` (x86_64) invoked via `install -s`.
**Recover:** Option A: `--without-progs` (skip tic/infocmp build entirely). Option B: cross-strip wrapper in PATH (`STRIP_DIR/strip` → `aarch64-linux-gnu-strip` or no-op).
**Root fix:** ✅ `--without-progs` added to ncurses `configure` (also `--disable-db-install` avoids cross `tic` exec in `install.data`).

---

### 4. rsync `lseek64` conflicting types (GCC 14)
**Symptom:** `conflicting types for 'lseek64'` in `syscall.c`.
**Confirm:** `rsync-3.3.0/syscall.c` declares `off64_t lseek64(int, off64_t, int)` but system header has different signature.
**Recover:** Patch `syscall.c` prototype to `extern OFF_T lseek64(int, OFF_T, int);`.
**Root fix:** ✅ In `_build_usrtools` sed patch before rsync configure.

---

### 5. rsync `void (*bomb)()` K&R call (GCC 14 `-Wincompatible-pointer-types` hard error)
**Symptom:** `error: function called with no prototype` at `pool_alloc.c:171`.
**Confirm:** `(*pool->bomb)(...)` where `bomb` is `void (*bomb)()`.
**Recover:** Add `-Wno-incompatible-pointer-types -Wno-implicit-function-declaration` to rsync `CFLAGS`. Patch call to `(*bomb)(const char*, const char*, int);`.
**Root fix:** ✅ CFLAGS + patch in `_build_usrtools`.

---

### 6. nano `curses.h: No such file`
**Symptom:** `fatal error: curses.h` during nano compile.
**Confirm:** ncurses headers installed to `$STAGE/include/ncursesw/`.
**Recover:** Add `-I$STAGE/include/ncursesw` to nano `CPPFLAGS`. Binary outputs to `src/nano` (not root).
**Root fix:** ✅ CPPFLAGS + copy from `nano_dir/src/nano`.

---

### 7. ripgrep-14 feature `gzip` not found
**Symptom:** `error: the package 'ripgrep' does not contain this feature: gzip`.
**Confirm:** `cargo build --features gzip` exits 101.
**Recover:** Remove `--features gzip`; keep `--no-default-features` (avoids pcre2).
**Root fix:** ✅ In `_build_usrtools` cargo command.

---

### 8. Kotlin `assets.list()` nullable
**Symptom:** `e: Non-nullable value required to call an 'iterator()' method in a for-loop` at `ProotManager.kt:138`.
**Confirm:** `context.assets.list(assetDir)` returns `Array<String>?`.
**Recover:** `val names = try { context.assets.list(assetDir) ?: return } catch (e: Exception) { return }`.
**Root fix:** ✅ In `deployDir`.

---

### 9. `assets/usr` deleted by `upload_src` rsync --delete
**Symptom:** usrtools missing from APK after build; `[WARN] usr/lib/libncursesw.so.6 nenalezen na Volume`.
**Confirm:** `upload_src` rsyncs `/src-baked/` → dest with `--delete`; baked image lacks `assets/usr`.
**Recover:** Add `"assets/usr"` to `_NATIVE_ASSET_EXCLUDES` in `tools/modal_build.py`.
**Root fix:** ✅ Line 50 of `modal_build.py`.

---

## Asset Layout (expected after native)

```
app/src/main/assets/
  su_daemon         (24 KB)
  su_wrapper        (2.1 MB)
  usb_bridge        (2.1 MB)
  usr/
    bin/  sed  rsync  nano  rg
    lib/  ld-linux-aarch64.so.1  libc.so.6  libm.so.6  libpthread.so.0
```

## Manual Recovery Commands

```bash
# Re-pull native after modal build_native succeeded
cd /root/kali_core_emulator
zsh mbuild native

# Verify APK contents locally
unzip -l /sdcard/Download/app-debug.apk | grep -E 'su_daemon|usr/bin|usr/lib'
python3 -c "
import zipfile, hashlib
z=zipfile.ZipFile('/sdcard/Download/app-debug.apk')
for n in z.namelist():
    if n.endswith('.dex'):
        d=z.read(n)
        for s in [b'EXECUTED',b'PREFIX=',b'usr/bin']:
            if s in d: print(f'{n}: {s.decode()} FOUND')
"
```

## Version Pins (in modal_build.py)

| Tool | Version |
|------|---------|
| ncurses | 6.5 |
| sed | 4.9 |
| rsync | 3.3.0 |
| nano | 8.2 |
| ripgrep | 14.1.1 |
| NDK | r28b (clang 18) |
| Rust | 1.82 (cargo) |