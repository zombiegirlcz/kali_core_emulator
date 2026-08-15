# Boot Universal Launcher Refactor — plán

**Datum:** 2026-08-13
**Cíl:** Sjednotit tři launchery (kali/parrot/docker) do jednoho `boot` skriptu,
přesunout assets do `usr/` prefix layoutu, rootfs do `nh/distro/` adresáře.

---

## Motivace

Současný stav:
- `launcher.sh` (template) + 3 generované launchery (kali/parrot/docker)
- Assets rozházené v `filesDir/` (proot, libtalloc, loader — vše na jedné hromadě)
- Docker launcher **chybně detekuje Termux prostředí** podle `/data/data/com.termux` existenci
  (místo podle struktury rootfs) → rozbíjí to běh když root testuje z root shellu
- Rootfs uloženy přímo v `filesDir/` (`parrot-arm64/`, `kali-arm64/`, `docker-*/`)
- Backup logika rozbitá — restore může smazat jiný distro
- Docker pull UI inline v MainActivity (duplikace kódu)

---
---
# proměne app uživatele
```
PREFIX=/data/user/0/com.linux_core/files/usr
ROOTFS_DIR=$PREFIX/nh/distro
LD_LIBRARY_PATH=$PREFIX/lib:/usr/lib:/usr/lib64
PATH=$PREFIX/bin:/usr/bin:/usr/xbin:/usr/sbin:/system/bin:/product/bin
```



---

## Cílový stav

### Layout filesDir

```
/data/user/0/com.linux_core/files/
├── usr/
│   ├── bin/
│   │   ├── boot              ← univerzální launcher (nový)
│   │   └── proot             ← proot binárka
│   └── lib/
│       └── libtalloc.so.2    ← talloc knihovna
├── loader                    ← proot loader (statický ELF, /usr/libexec sem nepatří)
├── nh/
│   └── distro/
│       ├── parrot/           ← parrot rootfs (bylo parrot-arm64/)
│       ├── kali/             ← kali rootfs (bylo kali-arm64/)
│       ├── docker/
│       │   └── <image-name>/ ← docker rootfs (bylo docker-<host>-<name>/)
│       └── backup/           ← zálohy (jen když user explicitně zabalí .tar.gz)
├── tmp/
└── ipc/
```

### Jeden `boot` skript

`$PREFIX/usr/bin/boot` — jeden shell skript, tři dispatch funkce:

```sh
boot() {
    case "$1" in
        kali|parrot)  boot_classic "$@" ;;
        docker)       boot_docker "$@" ;;
        *)            echo "Usage: boot {kali|parrot|docker} [args...]" >&2; return 1 ;;
    esac
}
```

- **Žádná detekce Termux zařízení** (nebojíme se `/data/data/com.termux` — to je uvnitř rootfs, ne hostitelský)
- **Docker detekuje strukturu rootfs** přes `find` (fallback), preferuje známé cesty
- **Pokud docker nenajde shell ani entrypoint** → chybová hláška + exit 1 (app zachytí a otevře ashell)

### UI flow

```
┌─────────────────────────────────┐
│  MainActivity                   │
│  - seznam distro (kali/parrot/  │
│    docker)                      │
│  - dropdown pro docker images   │
│  - [Boot Up] tlačítko           │
└─────────────┬───────────────────┘
              │ zavolá: ProotConfig("boot", distro [args])
              ▼
┌─────────────────────────────────┐
│  TerminalActivity (ashell/boot) │
│  - spustí /system/bin sh boot   │
└─────────────┬───────────────────┘
              ▼
┌─────────────────────────────────┐
│  boot skript                    │
│  - najde rootfs                 │
│  - detekuje strukturu           │
│  - sestaví proot příkaz        │
│  - exec proot ...               │
└─────────────────────────────────┘
```

### Docker pull UI — samostatný dialog/screen

Docker pull logika se přesune do **samostatné Compose obrazovky** (např. `DockerPullScreen`),
která se otevře když user klikne "Pull Docker Image" v hlavním UI. Po dokončení pull
nebo zavření okna se seznam dostupných docker images aktualizuje v hlavním UI.
### Backup strategie

**Nová chování pro restore**
- `backupRootfs()` — zabalí rootfs do `.tar.gz` a uloži jej do $ROOTFS_DIR/backup | pokud uživatel klikne na ui restore zobrazi se**samostatna compose obrazovky** zobrazujci obsah složky *backup* 
- `vybrat soubor` bude tlačitko dole v okne restore ktery otevře spravce souboru toto již restor dela ted akorad přidame okno z obsahem backup složky
- `restoreRootfs()` — před restore přejmenuje existující rootfs na `<name>.bak`,po restore se <name>.bak se přesune do noveho rootfs z restore 


---

## Struktura úkolů

### Fáze 1 — Příprava assets a adresářové struktury (žádný kód v Kotlin)

- [ ] **1.1** Přesunout assets v repu:
  - `app/src/main/assets/launcher.sh` → `app/src/main/assets/usr/bin/boot`
  - `app/src/main/assets/proot-aarch64` → `app/src/main/assets/usr/bin/proot`
  - `app/src/main/assets/libtalloc-aarch64.so` → `app/src/main/assets/usr/lib/libtalloc.so.2`
  - `loader` zůstává v `app/src/main/assets/` (statický, není binárka pro hostitele)

- [ ] **1.2** Vytvořit nový `boot` skript v `app/src/main/assets/usr/bin/boot`
  - Funkce `boot_kali()`, `boot_parrot()`, `boot_docker()`
  - Univerzální helpery: `find_sh()`, `find_libs()`, `find_entrypoint()`, `build_path()`
  - Žádná detekce `/data/data/com.termux` na hostitelské straně
  - Docker fallback na `find` když klasické cesty selžou

### Fáze 2 — Změny v `RootfsManager.kt`

- [ ] **2.1** Změnit rootfs layout konstanty:
  ```kotlin
  // staré:
  // val parrotRootfsDir = File(filesDir, "parrot-arm64")
  // val kaliRootfsDir = File(filesDir, "kali-arm64")
  // val dockerRootfsDir = File(filesDir, "docker-$namespace-$repository")

  // nové:
  val nhDistroDir = File(filesDir, "nh/distro")
  val parrotRootfsDir = File(nhDistroDir, "parrot")
  val kaliRootfsDir = File(nhDistroDir, "kali")
  val dockerRootfsDir = File(nhDistroDir, "docker/$imageName")
  val backupDir = File(nhDistroDir, "backup")
  ```

- [ ] **2.2** Aktualizovat všechna místa kde se používají staré cesty
  - `pullDockerImage()` — výstupní adresář
  - `extractRootfs()` — cílový adresář
  - `backupRootfs()` / `restoreRootfs()` — pracovat s novými cestami
  - `.docker_image` marker soubor v novém umístění

- [ ] **2.3** Restore: přidat kontrolu kompatibility (zabalený tar.gz vs existující rootfs)
  - Pokud struktura nesedí (např. tar.gz je docker ale rootfs je parrot) → chyba
  - `.bak` ukládat do `nh/distro/backup/<name>.bak/`

### Fáze 3 — Změny v `ProotManager.kt`

- [ ] **3.1** Sjednotit assets deploy do `usr/` prefixu:
  ```kotlin
  // staré:
  // prootBin = File(context.filesDir, "proot")
  // loaderBin = File(context.filesDir, "loader")
  // tallocLib = File(context.filesDir, "libtalloc.so.2")

  // nové:
  val prefix = File(context.filesDir, "usr")
  val prootBin = File(prefix, "bin/proot")
  val loaderBin = File(context.filesDir, "loader")  // loader je statický, nepatří do usr/
  val tallocLib = File(prefix, "lib/libtalloc.so.2")
  val bootScript = File(prefix, "bin/boot")
  ```

- [ ] **3.2** Smazat `deployLauncherScript()` — už nebude potřeba
  - Místo toho `setupProotEnvironment()` vrátí `ProotConfig` s command `/system/bin/sh $bootScript <distro>`

- [ ] **3.3** Sjednotit command konstrukci:
  ```kotlin
  // staré:
  // val fullCommand = mutableListOf("/system/bin/sh", launcherFile.absolutePath)

  // nové:
  val bootCmd = when (distroId) {
      "kali" -> listOf("kali")
      "parrot" -> listOf("parrot")
      "docker" -> listOf("docker", imageName)
      else -> listOf(distroId)
  }
  val fullCommand = mutableListOf("/system/bin/sh", bootScript.absolutePath) + bootCmd
  ```

### Fáze 4 — Změny v UI

- [ ] **4.1** Docker pull UI přesunout do samostatné Compose obrazovky
  - Nový soubor `app/src/main/java/com/linux_core/ui/docker/DockerPullScreen.kt`
  - Tlačítko "Pull Docker Image" v MainActivity otevře tuto obrazovku
  - Po dokončení/zavření se aktualizuje seznam docker images v hlavním UI

- [ ] **4.2** Skupina docker images v UI
  - Místo inline dropdown v MainActivity → použít nový layout
  - Zdroj: `nh/distro/docker/*` (procházet podsložky)

### Fáze 5 — Testování a migrace

- [ ] **5.1** Migrační skript pro existující uživatele
  - Při prvním spuštění nové verze: přesunout `parrot-arm64/` → `nh/distro/parrot/`,
    `kali-arm64/` → `nh/distro/kali/`, `docker-*/` → `nh/distro/docker/<name>/`
  - Smazat staré launchery (`launcher-kali.sh`, `launcher-parrot.sh`, `launcher-docker.sh`)
  - Přesunout `proot`, `libtalloc.so.2` z `filesDir/` do `filesDir/usr/{bin,lib}/`

- [ ] **5.2** Test na zařízení:
  - Boot parrot (klasický) — OK?
  - Boot kali (klasický) — OK?
  - Boot docker (Termux image) — OK?
  - Boot docker (úplně cizí image, třeba alpine) — OK? (fallback na find)
  - Docker image bez shellu → chybová hláška + ashell — OK?
  - Restore parrot když existuje jen kali → chyba (nepřepíše) — OK?

### Fáze 6 — Cleanup

- [ ] **6.1** Smazat `app/src/main/assets/launcher.sh` (nahrazeno `usr/bin/boot`)
- [ ] **6.2** Smazat `app/src/main/assets/proot-*` soubory (kromě `usr/bin/proot`)
- [ ] **6.3** Smazat `app/src/main/assets/libtalloc-*.so` soubory (kromě `usr/lib/libtalloc.so.2`)
- [ ] **6.4** Smazat `renderCompatLauncher()` z ProotManager.kt
- [ ] **6.5** Aktualizovat AGENTS.md / README.md s novým layoutem

---

## Detailní specifikace `boot` skriptu

### Společná logika

```sh
#!/system/bin/sh
set -e

PREFIX="${PREFIX:-/data/user/0/com.linux_core/files/usr}"
FILES_DIR="${FILES_DIR:-/data/user/0/com.linux_core/files}"
PROOT="$PREFIX/bin/proot"
LOADER="$FILES_DIR/loader"
TALLOC="$PREFIX/lib/libtalloc.so.2"

# proot RUNPATH = /data/data/com.termux/files/usr/lib (na zařízení neexistuje)
# proto musíme nastavit LD_LIBRARY_PATH aby proot našel libtalloc
export LD_LIBRARY_PATH="$PREFIX/lib:/system/lib64:/system/lib"
export PROOT_LOADER="$LOADER"
export PROOT_TMP_DIR="$FILES_DIR/tmp"
export TMPDIR="$FILES_DIR/tmp"

log() { [ -n "$BOOT_DEBUG" ] && echo "[boot] $*" >&2 || true; }
err() { echo "[boot] ERROR: $*" >&2; }
```

### `boot_classic()` (kali/parrot)

```sh
boot_classic() {
    local distro="$1"
    shift || true

    local ROOTFS="$FILES_DIR/nh/distro/$distro"
    if [ ! -d "$ROOTFS" ]; then
        err "Rootfs not found: $ROOTFS (hint: pull $distro first)"
        return 1
    fi

    # Guest shell: standardní linux rootfs má /bin/sh
    local GUEST_SH=""
    for _cand in "$ROOTFS/bin/sh" "$ROOTFS/usr/bin/sh"; do
        if [ -x "$_cand" ]; then GUEST_SH="${_cand#"$ROOTFS"}"; break; fi
    done
    if [ -z "$GUEST_SH" ]; then
        err "Guest shell not found in $ROOTFS (tried: bin/sh, usr/bin/sh)"
        return 1
    fi

    # Bootstrap (jen pro klasické distribuce)
    if [ -x "$ROOTFS/root/bootstrap.sh" ] && \
       { [ -f "$ROOTFS/root/.bootstrap_required" ] || \
         [ ! -f "$ROOTFS/root/.setup_done" ]; }; then
        echo "[boot] First run detected — running bootstrap..." >&2
        if "$PROOT" ... -r "$ROOTFS" -w /root -b /dev -b /proc -b /sys \
            $GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                          exec /bin/bash /root/bootstrap.sh'; then
            rm -f "$ROOTFS/root/.bootstrap_required"
        else
            echo "[boot] WARNING: bootstrap failed — keeping .bootstrap_required" >&2
        fi
    fi

    # Entrypoint nebo login shell
    local TARGET
    if [ -f "$ROOTFS/root/entrypoint.sh" ]; then
        TARGET="$GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                              exec /bin/sh /root/entrypoint.sh \"\$@\"' -- \"\$@\""
    else
        TARGET="$GUEST_SH -c 'unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                              cd /root && exec /bin/bash --login \"\$@\"' -- \"\$@\""
    fi

    exec "$PROOT" -v 0 --kill-on-exit -0 --link2symlink --sysvipc -L \
        --kernel-release='\Linux\proot\6.17.0-proot-distro\6.17.0-proot-distro\aarch64\localdomain\-1\' \
        -r "$ROOTFS" -w /root \
        -b /dev -b /proc -b /sys \
        -b "$FILES_DIR/tmp:$FILES_DIR/tmp" \
        -b "$FILES_DIR/ipc:/run/host_ipc" \
        -b /sdcard \
        -b /system:/mnt/system -b /vendor:/mnt/vendor \
        -b /data/local/tmp:/mnt/tmp -b /dev/bus/usb:/mnt/usb \
        $TARGET
}
```

### `boot_docker()`

```sh
boot_docker() {
    local image_name="${1:-}"
    shift || true

    local ROOTFS="$FILES_DIR/nh/distro/docker/$image_name"
    if [ ! -d "$ROOTFS" ]; then
        err "Docker rootfs not found: $ROOTFS (hint: pull image first)"
        return 1
    fi

    # Detekce termux-style image (rootfs obsahuje /data/data/com.termux/)
    local IS_TERMUX_IMAGE=0
    if [ -d "$ROOTFS/data/data/com.termux" ]; then
        IS_TERMUX_IMAGE=1
    fi

    # Najdi shell — fall back na find pokud známé cesty selžou
    local GUEST_SH=""
    if [ "$IS_TERMUX_IMAGE" = "1" ]; then
        for _cand in "$ROOTFS/data/data/com.termux/files/usr/bin/bash" \
                    "$ROOTFS/data/data/com.termux/files/usr/bin/sh" \
                    "$ROOTFS/system/bin/sh"; do
            if [ -x "$_cand" ]; then
                GUEST_SH="${_cand#"$ROOTFS"}"
                break
            fi
        done
    else
        for _cand in "$ROOTFS/bin/sh" "$ROOTFS/usr/bin/sh" \
                    "$ROOTFS/bin/bash" "$ROOTFS/usr/bin/bash"; do
            if [ -x "$_cand" ]; then
                GUEST_SH="${_cand#"$ROOTFS"}"
                break
            fi
        done
    fi

    # Fallback na find pro neznámé struktury (Alpine busybox, ...)
    if [ -z "$GUEST_SH" ]; then
        log "Known shell paths failed, falling back to find"
        local found
        found=$(find "$ROOTFS" -maxdepth 6 -type f \( -name 'sh' -o -name 'bash' \) \
                -executable 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            GUEST_SH="${found#"$ROOTFS"}"
        fi
    fi

    if [ -z "$GUEST_SH" ]; then
        err "No shell found in $ROOTFS (tried: bin/sh, usr/bin/sh, termux paths, find)"
        err "Dropping to app shell — rootfs structure:"
        err ""
        find "$ROOTFS" -maxdepth 2 -type d 2>/dev/null | head -20 >&2
        return 1
    fi
    log "Guest shell: $GUEST_SH"

    # Najdi entrypoint
    local ENTRYPOINT=""
    for _cand in /entrypoint.sh /entrypoint_root.sh /docker-entrypoint.sh \
                /start.sh /init.sh /run.sh; do
        if [ -x "$ROOTFS$_cand" ]; then
            ENTRYPOINT="$_cand"
            break
        fi
    done
    log "Entrypoint: ${ENTRYPOINT:-(none, will use login shell)}"

    # Sestav PATH z rootfs
    local GUEST_PATH=""
    for _d in /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin; do
        if [ -d "$ROOTFS$_d" ]; then
            GUEST_PATH="${GUEST_PATH:+$GUEST_PATH:}$_d"
        fi
    done
    if [ -d "$ROOTFS/data/data/com.termux/files/usr/bin" ]; then
        GUEST_PATH="$GUEST_PATH:/data/data/com.termux/files/usr/bin"
    fi

    # Bindy podle typu image
    local BINDS="-b /dev -b /proc -b /sys -b /sdcard"
    if [ "$IS_TERMUX_IMAGE" = "1" ]; then
        BINDS="$BINDS -b /dev/urandom:/dev/random"
        [ ! -e /dev/fd ] && BINDS="$BINDS -b /proc/self/fd:/dev/fd"
        for _p in /apex /odm /product /system /system_ext /vendor; do
            [ -d "$_p" ] && [ -x "$_p" ] && BINDS="$BINDS -b $_p"
        done
        # Termux tmp
        BINDS="$BINDS -b $FILES_DIR/tmp:/data/data/com.termux/files/usr/tmp"
        mkdir -p "$ROOTFS/tmp" 2>/dev/null || true
        BINDS="$BINDS -b $ROOTFS/tmp:/dev/shm"
    else
        BINDS="$BINDS -b $FILES_DIR/tmp:/tmp"
        BINDS="$BINDS -b $FILES_DIR/ipc:/run/host_ipc"
    fi

    # Sestav finální příkaz
    local PROOT_CWD="/root"
    [ ! -d "$ROOTFS/root" ] && PROOT_CWD="/"

    local TARGET_CMD
    if [ -n "$ENTRYPOINT" ]; then
        TARGET_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                    export PATH=$GUEST_PATH; \
                    cd $PROOT_CWD 2>/dev/null || cd /; \
                    exec $ENTRYPOINT \"\$@\""
    else
        TARGET_CMD="unset LD_PRELOAD PROOT_LOADER LD_LIBRARY_PATH; \
                    export PATH=$GUEST_PATH; \
                    cd $PROOT_CWD 2>/dev/null || cd /; \
                    exec $GUEST_SH --login \"\$@\""
    fi

    exec "$PROOT" -v 0 --kill-on-exit -0 --link2symlink --sysvipc -L \
        --kernel-release='\Linux\proot\6.17.0-proot-distro\6.17.0-proot-distro\aarch64\localdomain\-1\' \
        -r "$ROOTFS" -w "$PROOT_CWD" \
        $BINDS \
        $GUEST_SH -c "$TARGET_CMD" -- "$@"
}
```

### Dispatch

```sh
main() {
    case "${1:-}" in
        kali|parrot)
            boot_classic "$@"
            ;;
        docker)
            shift
            boot_docker "$@"
            ;;
        --help|-h|"")
            echo "Usage: boot {kali|parrot} [-- cmd args...]"
            echo "       boot docker <image-name> [-- cmd args...]"
            ;;
        *)
            err "Unknown command: $1"
            echo "Usage: boot {kali|parrot|docker} ..." >&2
            return 1
            ;;
    esac
}

main "$@"
```

---

## Detailní specifikace změn v Kotlin

### `RootfsManager.kt` — nové konstanty

```kotlin
companion object {
    private const val NH_DISTRO_DIR = "nh/distro"
    private const val BACKUP_DIR = "backup"

    fun distroRootfsDir(context: Context, distroId: String): File =
        File(context.filesDir, "$NH_DISTRO_DIR/$distroId")

    fun dockerRootfsDir(context: Context, imageName: String): File =
        File(context.filesDir, "$NH_DISTRO_DIR/docker/$imageName")

    fun backupDir(context: Context): File =
        File(context.filesDir, "$NH_DISTRO_DIR/$BACKUP_DIR")
}
```

### `ProotManager.kt` — nový setup

```kotlin
fun setupProotEnvironment(
    context: Context,
    distroId: String,
    rootfsSubdir: String = distroId,  // "parrot", "kali", "docker/<name>"
    mountStorage: Boolean = false,
    customCommand: String? = null,
    hasRoot: Boolean = false
): ProotConfig {
    val prefix = File(context.filesDir, "usr")
    val prootBin = File(prefix, "bin/proot")
    val loaderBin = File(context.filesDir, "loader")
    val tallocLib = File(prefix, "lib/libtalloc.so.2")
    val bootScript = File(prefix, "bin/boot")
    val rootfsDir = File(context.filesDir, "nh/distro/$rootfsSubdir")

    // ... deploy kroky (mkdir, deploy binaries atd.) ...

    // Command: /system/bin/sh <boot> <distro> [args]
    val bootArgs = mutableListOf(distroId)
    if (!customCommand.isNullOrEmpty()) {
        bootArgs.add("--")
        bootArgs.add(customCommand)
    }

    val fullCommand = mutableListOf("/system/bin/sh", bootScript.absolutePath) + bootArgs

    return ProotConfig(
        command = fullCommand.toTypedArray(),
        cwd = context.filesDir.absolutePath,
        env = emptyArray(),
        prootPath = prootBin.absolutePath,
        rootfsDir = rootfsDir.absolutePath
    )
}
```

### Migrace

Přidat `migrateLayout()` do `ProotManager.kt`, která se zavolá při startu app:

```kotlin
private fun migrateLayout(context: Context) {
    val filesDir = context.filesDir
    val nhDistro = File(filesDir, "nh/distro")
    val backup = File(filesDir, "nh/distro/backup")

    // 1. Vytvoř novou strukturu
    nhDistro.mkdirs()
    File(nhDistro, "parrot").mkdirs()
    File(nhDistro, "kali").mkdirs()
    File(nhDistro, "docker").mkdirs()
    backup.mkdirs()

    // 2. Přesuň staré rootfs
    File(filesDir, "parrot-arm64").renameTo(File(nhDistro, "parrot"))
    File(filesDir, "kali-arm64").renameTo(File(nhDistro, "kali"))

    // 3. Přesuň docker images
    filesDir.listFiles()?.filter {
        it.isDirectory && (it.name.startsWith("docker-") || it.name.startsWith("oci-"))
    }?.forEach { dockerDir ->
        // docker-termux-termux-docker → docker/termux-termux-docker
        val imageName = dockerDir.name.removePrefix("docker-").removePrefix("oci-")
        val target = File(nhDistro, "docker/$imageName")
        target.mkdirs()
        dockerDir.renameTo(target)
    }

    // 4. Přesuň binárky do usr/
    val prefix = File(filesDir, "usr")
    File(prefix, "bin").mkdirs()
    File(prefix, "lib").mkdirs()

    File(filesDir, "proot").renameTo(File(prefix, "bin/proot"))
    File(filesDir, "libtalloc.so.2").renameTo(File(prefix, "lib/libtalloc.so.2"))

    // 5. Smaž staré launchery
    listOf("launcher-kali.sh", "launcher-parrot.sh", "launcher-docker.sh", "launcher.sh")
        .forEach { File(filesDir, it).delete() }
}
```

---

## Rizika

1. **Migrace existujících dat** — špatný rename by mohl způsobit ztrátu rootfs
   - Mitigace: před rename zkopírovat soubory, ne přesouvat; ověřit integritu po přesunu

2. **Boot skript závislost na `/system/bin/sh`** — funguje všude, ale některé
   staré Android verze mohou mít `mksh` s omezeními
   - Mitigace: testovat na cílových Android verzích

3. **Docker find fallback** — může trvat dlouho na velkých rootfs (parrot 300MB+)
   - Mitigace: find omezen na maxdepth 6, preferovat známé cesty

4. **Backup `.bak` v restore** — při neúspěšném restore zůstane `.bak`,
   ale app to musí umět detekovat a nabídnout obnovu

---

## Akceptační kritéria

- [ ] Jeden `boot` skript v `usr/bin/boot`, žádný template
- [ ] Tři dispatch funkce: kali, parrot, docker
- [ ] Docker fallback na `find` funguje pro neznámé image (test: Alpine)
- [ ] Docker image bez shellu → chybová hláška + exit 1 (app zachytí a otevře ashell)
- [ ] Restore neumožní přepsat rootfs jiného typu
- [ ] Migrace existujícího rootfs proběhne bez ztráty dat
- [ ] UI docker pull se přesune do samostatné obrazovky
- [ ] AGENTS.md / README.md aktualizované
