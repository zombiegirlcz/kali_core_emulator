# Prompt: Boot refactor — Fáze 1: Adresářová struktura a bezpečná migrace

**Kontext projektu:** NetHunter AI Operator (`com.linux_core`), PRoot-based Kali/Parrot na Androidu bez rootu.
**Cíl tohoto promptu:** sjednotit layout `filesDir` na `usr/` prefix + `nh/distro/`, BEZ ztráty
existujícího obsahu `usr/bin` a `usr/lib`, které už na zařízení běží.

> **SYNCHRONIZACE 2026-08-14 (Fáze 0 + Modal rework):** Plán aktualizován o realitu po
> statických proot buildech (větev `proot-static-build`) a po přepracování Modal uploadu.
> Statické binárky (`proot-static-*`, `loader-static-*`) jsou v `assets/usr/bin/` a jsou
> PRIMÁRNÍ; dynamické (`proot-*`, `loader-*`, `libtalloc-*.so` v kořeni assets) zůstávají
> jako fallback pro starý `launcher.sh`, dokud Fáze 2/3 nepřejde na `usr/bin/boot`.

---

## Aktuální stav (co už je hotovo — neopakovat)

| Položka | Stav |
|---------|------|
| Static proot/loader pro 4 ABI v `assets/usr/bin/proot-static-*`, `loader-static-*` (Fáze 0) | ✅ |
| `_build_proot_static()` + `mbuild proot` v Modal pipeline (Fáze 0) | ✅ |
| `assets/bin/{terminalmap,ifconfig}` → `assets/usr/bin/` přesunuty (`git mv`), `assets/bin/` smazán | ✅ |
| `.gitignore`: `assets/usr/**` ignorováno kromě `usr/bin/terminalmap` + `usr/bin/ifconfig` (repo-owned) | ✅ |
| `ProotManager.kt` asset reference `"bin/..."` → `"usr/bin/..."` (3 místa) | ✅ |
| `modal_build.py`: `_build_usrtools` BEZ `rmtree(assets_usr)`, skip-if-exists (`FORCE_USRTOOLS=1` rebuild) | ✅ |
| `modal_build.py`: `upload_src` nahrazen `upload_basic`/`upload_force`/`upload_clean`, žádné excludy | ✅ |
| `mbuild`: upload VŽDY samostatně, buildy ho nevolají | ✅ |
| Volume baseline (čistý profil): celý strom vč. `assets/usr/` nahrán přes `upload_basic` | ✅ |

---

## Zjištění z inspekce repa

`ProotManager.kt` — `usr/bin` a `usr/lib` **už existují** jako **host-side** adresáře
(mimo proot, přímo v `filesDir`), spravované verzovaným deployem:

```kotlin
private const val USR_TOOLS_VERSION = "bionic-20260811-1"
// deployDir(context, "usr/bin", File(rootDir, "usr/bin"), executable = true, version = USR_TOOLS_VERSION)
// deployDir(context, "usr/lib", File(rootDir, "usr/lib"), executable = false, version = USR_TOOLS_VERSION)
```

`deployDir()` při změně `USR_TOOLS_VERSION` dělá `targetDir.deleteRecursively()` — cokoliv
v `usr/bin`/`usr/lib`, co nepřijde z assets, přežije jen do příštího version bumpu.

`deployBinaries()` deployuje **dynamické** `proot`, `loader`, `libtalloc.so.2` do KOŘENE
`filesDir` způsobem "deploy jen když chybí" → **stale-binary bug** (APK update nikdy
nenahradí existující binárku). Přesun na statické prooty do `usr/bin` to řeší napojením
na verzovaný mechanismus.

**Statický proot** (termux fork v5.1.107.90, talloc statically linked): NEEDED jen
`libdl.so` + `libc.so` (Bionic) — `libtalloc.so.2` nepotřebuje. **Statický loader** je plně
statický. Spolu tvoří pár pro seccomp akceleraci (`PROOT_LOADER`).

---

## Cílový layout (po Fázi 1)

```
/data/user/0/com.linux_core/files/
├── usr/
│   ├── bin/                  ← sed, rsync, nano, rg, usb tools, terminalmap, ifconfig (z assets)
│   │   ├── proot             ← NOVÝ: static proot (kanonické jméno pro boot skript Fáze 2)
│   │   ├── loader            ← NOVÝ: static loader (pár k proot)
│   │   └── proot-static-*, loader-static-*  ← raw assety (deployDir je sem kopíruje 1:1)
│   └── lib/
│       └── libtalloc.so.2    ← NOVÝ: jen pro dynamický fallback
├── proot, loader, libtalloc.so.2   ← LEGACY dynamické (kořen, pro starý launcher.sh; maže Fáze 3)
├── terminalmap               ← LEGACY kopie (guest wrapper ji odkazuje; maže Fáze 3 po update wrapperu)
├── launcher-{kali,parrot,docker}.sh, launcher.sh  ← LEGACY (nahradí usr/bin/boot ve Fázi 2)
├── nh/
│   └── distro/
│       ├── kali/             ← bylo kali-arm64/
│       ├── parrot/           ← bylo parrot-arm64/
│       ├── docker/<image>/   ← bylo docker-<ns>-<repo>/
│       └── backup/
├── tmp/
└── ipc/
```

---

## Úkol 1.1 — `deployArchBinaries()`: static proot/loader do `usr/bin`, napojeno na version gate

`deployDir()` kopíruje 1:1 podle jména ( neumí arch-select) — proto vedle něj malá funkce:

```kotlin
/**
 * Deploy arch-specific binárek pod KANONICKÝM jménem do usr/bin / usr/lib.
 * Primární: STATIC proot/loader (assets/usr/bin/proot-static-$suffix).
 * Fallback: dynamické (assets/proot-$suffix) jen pokud static asset chybí.
 * Běží AŽ PO deployDir(usr/bin)/deployDir(usr/lib) — nikdy nemaže, jen dopisuje.
 * Po version bumpu (wipe v deployDir) obnoví kanonické soubory automaticky,
 * protože deploy-if-missing je najde chybějící.
 */
private fun deployArchBinaries(context: Context, suffix: String) {
    val usrBin = File(context.filesDir, "usr/bin")
    val usrLib = File(context.filesDir, "usr/lib")
    usrBin.mkdirs(); usrLib.mkdirs()

    // 1) proot: static primární
    val prootTargets = listOf(
        "usr/bin/proot-static-$suffix" to "STATIC",
        "proot-$suffix" to "DYNAMIC-FALLBACK",
    )
    deployFirstAvailable(context, prootTargets, File(usrBin, "proot"))

    // 2) loader: static primární (pár k static proot kvůli seccomp akceleraci)
    val loaderTargets = listOf(
        "usr/bin/loader-static-$suffix" to "STATIC",
        "loader-$suffix" to "DYNAMIC-FALLBACK",
    )
    deployFirstAvailable(context, loaderTargets, File(usrBin, "loader"))

    // 3) libtalloc: jen pro dynamický fallback (static proot ho nepotřebuje)
    deployFirstAvailable(
        context,
        listOf("libtalloc-$suffix.so" to "DYNAMIC-FALLBACK"),
        File(usrLib, "libtalloc.so.2")
    )
}

private fun deployFirstAvailable(
    context: Context,
    candidates: List<Pair<String, String>>,
    target: File
) {
    if (target.exists() && target.length() > 0L) {
        target.setExecutable(true, false)
        return
    }
    for ((asset, label) in candidates) {
        try {
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
            Log.i(TAG, "deployArchBinaries: $target.name <- $asset ($label, ${target.length()} B)")
            return
        } catch (e: Exception) {
            Log.d(TAG, "deployArchBinaries: asset $asset nedostupný ($label): ${e.message}")
        }
    }
    Log.e(TAG, "deployArchBinaries: ŽÁDNÝ kandidát pro ${target.name} (suffix) neexistuje!")
}
```

**Volání:** v `setupProotEnvironment` hned po `deployDir(usr/bin)`/`deployDir(usr/lib)`:
```kotlin
val suffix = detectArchSuffix()   // viz níže — NE podle jména rootfs adresáře!
deployArchBinaries(context, suffix)
```

**Suffix detekce podle zařízení (fix):** starý kód `rootfsDir.name.contains("arm64")` selže
s novými jmény adresářů (`nh/distro/kali`). Nahradit:
```kotlin
private fun detectArchSuffix(): String = when {
    android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "aarch64"
    android.os.Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "arm"
    android.os.Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
    else -> "i686"
}
```

**Version bump:** `USR_TOOLS_VERSION = "layout-20260814-1"` (nebo podobně) + komentář:
bump verze redeployuje CELÝ toolchain (sed/rsync/nano/rg + proot + loader + libtalloc),
ne jen textové nástroje.

**Legacy `deployBinaries()` (kořen filesDir) zůstává VE FÁZI 1 beze změny** — starý
`launcher.sh` template pořád používá `__PROOT_BIN__=$FILES_DIR/proot` (dynamický).
Maže se až ve Fázi 3.

---

## Úkol 1.2 — `migrateLayout()`: copy+verify+delete, ne slepý `renameTo()`

`File.renameTo()` na Androidu tiše vrací `false` při selhání → bezpečný helper:

```kotlin
/**
 * Bezpečný přesun adresáře/souboru. Nikdy nepřepíše existující neprázdný cíl.
 * Zdroj se maže AŽ po ověření, že se přesun povedl.
 */
private fun safeMove(src: File, dst: File): Boolean {
    if (!src.exists()) return true // nic k přesunu, OK
    if (dst.exists() && ((dst.isDirectory && dst.listFiles()?.isNotEmpty() == true)
                          || (dst.isFile && dst.length() > 0L))) {
        // Cíl existuje: u souborů stejné velikosti považ za migrované a smaž zdroj
        if (src.isFile && dst.isFile && src.length() == dst.length()) {
            src.delete()
            return true
        }
        Log.w(TAG, "safeMove: cíl už existuje a není prázdný, přeskočeno: $dst")
        return false
    }
    if (src.renameTo(dst)) return true   // rychlá cesta (stejný filesystem)
    return try {                          // fallback: kopie + ověření + smazání
        src.copyRecursively(dst, overwrite = false)
        val ok = if (src.isDirectory) countFiles(src) == countFiles(dst)
                 else dst.exists() && dst.length() == src.length()
        if (ok) { src.deleteRecursively(); true }
        else { Log.e(TAG, "safeMove: ověření selhalo, zdroj NEmažu: $src -> $dst"); false }
    } catch (e: Exception) {
        Log.e(TAG, "safeMove failed: ${e.message}"); false
    }
}

private fun countFiles(f: File): Int =
    if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1
```

**Umístění:** `RootfsManager` (domain owner rootfs layoutů; `BackgroundBoot` volá
`detectActiveRootfsDir` PŘED `setupProotEnvironment` → migrace musí být nezávislá na ProotManageru).

Pravidla `migrateLayout(context)`:

1. `usr/bin`/`usr/lib` se **nikdy nemažou** — legacy `filesDir/bin/*` se přesouvá
   soubor po souboru přes `safeMove()` (kolize → přeskočit + log).
2. Rootfs: `kali-arm64` → `nh/distro/kali`, `parrot-arm64` → `nh/distro/parrot`.
3. Docker: `docker-*`/`oci-*` → `nh/distro/docker/<jméno bez prefixu>`.
4. Legacy `filesDir/terminalmap` → `usr/bin/terminalmap` (safeMove; cíl obvykle už
   nasazený deployDir-em ze stejného assetu → stejná velikost → zdroj se smaže).
5. `filesDir/proot`, `filesDir/loader`, `filesDir/libtalloc.so.2` se v Fázi 1
   **NEPŘESOUVAJÍ** (starý launcher.sh je aktivně používá; cleanup ve Fázi 3).
6. Staré launchery (`launcher-*.sh`) se mažou až na konci, jen když všechny
   `safeMove()` vrátily true.
7. Guard: `SharedPreferences("nh_migration").getBoolean("migration_v2_done")`,
   nastavený až po úspěšném doběhnutí.

```kotlin
fun ensureMigrated(context: Context) {
    val prefs = context.getSharedPreferences("nh_migration", Context.MODE_PRIVATE)
    if (prefs.getBoolean("migration_v2_done", false)) return
    migrateLayout(context, prefs)
}
```

Volání: na začátku `ProotManager.setupProotEnvironment()` A v `RootfsManager` cestách,
které běží před ním (instalace/backup detekce v MainActivity).

---

## Úkol 1.3 — `RootfsManager.kt`: konstanty + helpery

```kotlin
companion object {
    const val NH_DISTRO_DIR = "nh/distro"

    fun distroRootfsDir(context: Context, distroId: String): File =
        File(context.filesDir, "$NH_DISTRO_DIR/$distroId")

    fun dockerRootfsDir(context: Context, imageName: String): File =
        File(context.filesDir, "$NH_DISTRO_DIR/docker/$imageName")

    fun backupDir(context: Context): File =
        File(context.filesDir, "$NH_DISTRO_DIR/backup")
}
```

**Rozhodnutí (z review):** `Distro.rootfsDirName` hodnoty se změní na
`"nh/distro/kali"` / `"nh/distro/parrot"` (relativní k filesDir) — všechna místa
`File(context.filesDir, distro.rootfsDirName)` fungují automaticky. Docker target dir:
`"nh/distro/docker/${ns}-${repo}"`.

**Backup/restore prefix fix:** tar prefix = `distro.id` ("kali"/"parrot"), NE
`distro.rootfsDirName` (ten je teď cesta). Restore: stripPrefix `distro.id` +
kompatibilně i staré prefixy (`kali-arm64`); kontrola kompatibility rootfs
(peek na charakteristický soubor, ne slepě věřit UI výběru).

---

## Úkol 1.4 — Aktualizace callsites (kompletní inventář)

| Soubor | Místo | Změna |
|--------|-------|-------|
| `ProotManager.kt:27` | default `rootfsDirName = "kali-arm64"` | `"nh/distro/kali"` |
| `ProotManager.kt:106` | `suffix` podle `rootfsDir.name.contains("arm64")` | `detectArchSuffix()` (Build.SUPPORTED_ABIS) |
| `ProotManager.kt:1402-1419` | `renderCompatLauncher` detekce `${d}-arm64`, `docker-*` | přidat `nh/distro/$d` a `nh/distro/docker/*` |
| `TerminalService.kt:60` | `getSessionDistro` default `"kali-arm64"` | `"nh/distro/kali"` |
| `TerminalService.kt:167` | `sessionDistros[id] = File(config.rootfsDir).name` | relativní cesta k filesDir (`nh/distro/kali`), ne jen `name` |
| `TerminalActivity.kt:2565,2589` | docker detekce `startsWith("docker-")`/`"oci-"` | + `startsWith("nh/distro/docker/")` |
| `TerminalActivity.kt:2584` | fallback `"kali-arm64"` | `"nh/distro/kali"` |
| `TerminalActivity.kt:2655` | `knownDirs = listOf("kali-arm64", "parrot-arm64")` | `listOf("nh/distro/kali", "nh/distro/parrot")` |
| `TerminalActivity.kt:1366-1371` | `.setup_done` paths | přes helpery RootfsManager |
| `ShortcutHelper.kt:21,34,56` | hardcoded distro dirnames | nové cesty |
| `WidgetProvider.kt:94,255-256` | hardcoded list | nové cesty |
| `BackgroundBoot.kt:71-89` | `detectActiveRootfsDir` (`endsWith("-arm64")`) | nejdřív `RootfsManager.ensureMigrated()`, pak scan `nh/distro/{kali,parrot}` + fallback staré cesty |
| `RootBridgeTab.kt:71-82` | `detectGuestRootfs` (`endsWith("-arm64")`) | totéž |
| `MainActivity.kt:395` | docker scan `startsWith("docker-")` | scan `nh/distro/docker/` |
| `NetHunterAssistantSession.kt:318-319` | `"kali-arm64/tmp/..."` | helper `RootfsManager.distroRootfsDir` |
| `DistroDocumentsProvider.kt:41` | `File(filesDir, d.rootfsDirName)` | funguje automaticky po změně rootfsDirName |

**Display jména:** UI (drawer badge 🐉/🦜, topbar) používá `contains("kali")`/`contains("parrot")`
— funguje i s cestou `nh/distro/kali`. `ProcessResolver`/`LocalApiServer` session labely:
zobrazovat `distroName.substringAfterLast("/")` (hezké jméno), ne celou cestu.

---

## Rizika

1. **`USR_TOOLS_VERSION` wipe teď zasáhne i `usr/bin/proot`/`loader`** — záměr (version gate
   řeší stale-binary bug); dokumentováno komentářem u konstanty.
2. **Kolize jmen v `usr/bin`** mezi legacy `files/bin/*` a assets — `safeMove()` přeskočí + log.
3. **Běh pořadí:** `ensureMigrated()` musí proběhnout PŘED `File(rootDir, rootfsDirName)`
   resolvováním v `setupProotEnvironment` — jinak nová cesta neexistuje.
4. **Statický proot netestovaný na zařízení** — dynamický fallback (kořen filesDir +
   launcher.sh) zůstává aktivní celou Fázi 1; přepnutí až ve Fázi 2.

---

## Acceptance criteria

- [ ] `usr/bin` po migraci obsahuje původní nástroje (sed, rsync, nano, rg, usb tools)
      + `proot` (static), `loader` (static), `terminalmap`, `ifconfig`
- [ ] `usr/lib` obsahuje `libtalloc.so.2`
- [ ] `files/bin/` po úspěšné migraci prázdný/smazaný
- [ ] Žádný soubor v `usr/bin`/`usr/lib` ztracen — `ls -la` před/po sedí
- [ ] Migrace se nespustí podruhé (`migration_v2_done`)
- [ ] Bump `USR_TOOLS_VERSION` redeployuje `proot`/`loader`/`libtalloc.so.2`
- [ ] `nh/distro/{kali,parrot,docker,backup}` existují, velikosti sedí
- [ ] Starý launcher.sh stále funguje (dynamický proot v kořeni nedotčen)
- [ ] APK build na Modalu projde (upload_basic → build)
