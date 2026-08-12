# Brief: Termux-native launcher větev pro `termux/termux-docker` image

## Kontext

`termux/termux-docker:latest` (viz `manifest.json` z uživatele) není běžná linuxová
distribuce — je to kopie kompletního Termux prefix stromu
(`/data/data/com.termux/files/usr`, `WorkingDir=/data/data/com.termux/files/home`)
zabalená jako Docker vrstva. Reálné `proot-distro login <distro>` na Termuxu
tento typ image spouští jinak než klasický rootfs: **ignoruje Docker
Entrypoint/Cmd** (`/entrypoint.sh`, `login` z manifestu) a spouští přímo gost
binárku `login` s dodatečnými Android ART/APEX bindy. Přesný formát je
zachycen v uživatelově `top.log` (živý `ps`/`top` capture z proot-distro
session):

```
proot --kill-on-exit --sysvipc \
  --kernel-release=\Linux\localhost\6.17.0-PRoot-Distro\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\aarch64\localdomain\-1\ \
  -L \
  --rootfs=<rootfs> \
  --cwd=/data/data/com.termux/files/home \
  --bind=/dev --bind=/proc --bind=/sys \
  --bind=/data/app --bind=/data/dalvik-cache \
  --bind=/data/misc/apexdata/com.android.art/dalvik-cache \
  --bind=/storage/self/primary:/mnt/sdcard \
  --bind=/storage/self/primary:/sdcard \
  --bind=/storage/self/primary:/storage/emulated/0 \
  --bind=/storage/self/primary:/storage/self/primary \
  --bind=/apex --bind=/odm --bind=/product --bind=/system \
  --bind=/system_ext --bind=/vendor \
  --bind=/linkerconfig/ld.config.txt \
  --bind=/linkerconfig/com.android.art/ld.config.txt \
  /data/data/com.termux/files/usr/bin/login
```

## Root cause blokující funkčnost (musí se opravit první)

**Soubor:** `app/src/main/java/com/linux_core/core/ProotManager.kt:116`

Dnes **všechny** Docker image dostanou `distroId = "docker"` bez ohledu na to,
který image byl stažen:

```kotlin
// SOUČASNÝ STAV — termux image se nikdy neodliší od jiných docker images
val launcherDistroId = if (isDockerImage) "docker" else distroId
```

`RootfsManager.pullDockerImage()` (řádek ~699) přitom ukládá rootfs do
`docker-${imageRef.namespace}-${imageRef.repository}` — pro
`termux/termux-docker` to bude `docker-termux-termux-docker`. Tento string
je dostupný jako `rootfsDirName` parametr `setupProotEnvironment()`, takže
detekce je bez zásahu do RootfsManager.kt možná čistě v ProotManager.kt.

### Oprava

```kotlin
// NOVÝ STAV
val launcherDistroId = when {
    isDockerImage && rootfsDirName.contains("termux") -> "termux"
    isDockerImage -> "docker"
    else -> distroId
}
```

Dále v `deployLauncherScript()` (řádek ~1325) doplnit `logPrefix` větev:

```kotlin
val logPrefix = when (distroId) {
    "kali" -> "KaliLauncher"
    "parrot" -> "ParrotLauncher"
    "docker" -> "DockerLauncher"
    "termux" -> "TermuxLauncher"   // ← přidat
    else -> if (isDockerImage) "DockerLauncher" else "${distroId.replaceFirstChar { it.uppercase() }}Launcher"
}
```

A v KDoc komentáři nad funkcí (řádek ~1300) rozšířit výčet:
`@param distroId identifikátor distra ("kali", "parrot", "docker", "termux")`.

## Hlavní změna: nová větev v `launcher.sh`

**Soubor:** `app/src/main/assets/launcher.sh`

Aktuální `DOCKER_MODE` blok (řádky 304–336) řeší všechny docker images
jednotně — přes `$GUEST_SH -c "... exec $LOGIN_SHELL"`. Pro `DISTRO_ID=termux`
to nahradit samostatnou větví, která:

1. exec-uje gost `login` binárku přímo (ne přes shell wrapper),
2. přidává Android ART/APEX bindy, které se dnes nikde nedělají
   (`/data/app`, `dalvik-cache` ×2, `/apex /odm /product /system
   /system_ext /vendor`, `linkerconfig` ×2),
3. mapuje storage na 4 cíle (`/mnt/sdcard`, `/sdcard`,
   `/storage/emulated/0`, `/storage/self/primary`) místo dnešního
   jednoho `-b /sdcard`,
4. používá `--bind=`/`--rootfs=`/`--cwd=` long-flag syntaxi shodnou s
   `top.log`.

**Vlož před řádek `LOGIN_SHELL="/bin/bash --login"` (řádek 307):**

```sh
# ─── DISTRO_ID="termux": nativní proot-distro replika ────
# termux/termux-docker image kopíruje celý Termux prefix strom a Docker
# Entrypoint/Cmd (/entrypoint.sh, "login") ignoruje — přesně jako reálné
# `proot-distro login`, které si samo spouští /bin/login s Android
# ART/APEX bindy. Formát ověřen z top.log (live proot-distro capture).
if [ "$DOCKER_MODE" = "1" ] && [ "$DISTRO_ID" = "termux" ]; then
    TERMUX_LOGIN_REL="/data/data/com.termux/files/usr/bin/login"
    TERMUX_LOGIN="$ROOTFS_DIR$TERMUX_LOGIN_REL"
    TERMUX_CWD="/data/data/com.termux/files/home"
    STORAGE_SRC="/storage/self/primary"

    if [ -x "$TERMUX_LOGIN" ]; then
        TBINDS="--bind=/dev --bind=/proc --bind=/sys"
        TBINDS="$TBINDS --bind=/data/app"
        TBINDS="$TBINDS --bind=/data/dalvik-cache"
        TBINDS="$TBINDS --bind=/data/misc/apexdata/com.android.art/dalvik-cache"
        if [ -d "$STORAGE_SRC" ]; then
            TBINDS="$TBINDS --bind=$STORAGE_SRC:/mnt/sdcard"
            TBINDS="$TBINDS --bind=$STORAGE_SRC:/sdcard"
            TBINDS="$TBINDS --bind=$STORAGE_SRC:/storage/emulated/0"
            TBINDS="$TBINDS --bind=$STORAGE_SRC:/storage/self/primary"
        fi
        for _p in /apex /odm /product /system /system_ext /vendor; do
            if [ -d "$_p" ] && [ -x "$_p" ]; then
                TBINDS="$TBINDS --bind=$_p"
            fi
        done
        [ -f /linkerconfig/ld.config.txt ] && \
            TBINDS="$TBINDS --bind=/linkerconfig/ld.config.txt"
        [ -f /linkerconfig/com.android.art/ld.config.txt ] && \
            TBINDS="$TBINDS --bind=/linkerconfig/com.android.art/ld.config.txt"
        TBINDS="$TBINDS __EXTRA_ROOT_MOUNTS__"
        [ -n "$NH_EXTRA_BINDS" ] && TBINDS="$TBINDS $NH_EXTRA_BINDS"

        set -- $ENV_PREFIX "$PR" --kill-on-exit --sysvipc \
            "--kernel-release=\\Linux\\localhost\\6.17.0-PRoot-Distro\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\\aarch64\\localdomain\\-1\\" \
            -L \
            "--rootfs=$ROOTFS_DIR" \
            "--cwd=$TERMUX_CWD" \
            $TBINDS \
            "$TERMUX_LOGIN_REL" "$@"

        log "exec (termux-native): $*"
        exec "$@"
    else
        log "WARN: $TERMUX_LOGIN nenalezen v gost rootfs — fallback na obecnou docker větev"
    fi
fi
```

Zbytek souboru (generický `DOCKER_MODE` blok) zůstává jako fallback pro
ostatní Docker image beze změny.

## Otevřená otázka (potvrď před nasazením)

`/bin/login` (na rozdíl od `bash --login`) nepřijímá předané argumenty jako
příkaz k provedení. Větev výše proto `"$@"` sice předává (shoda s
existujícím vzorem v souboru), ale **pro neinteraktivní spouštění příkazů**
(`nh distro login termux -- <cmd>`) to nebude fungovat stejně — ten flow
ale stejně prochází dřívější `su_daemon` větví (`if [ "$1" = "--" ]`,
řádek 258) a týhle nové větve se vůbec netýká, takže pro tebe by to
neměl být problém. Pokud ale plánuješ i běžné neinteraktivní volání
`launcher-termux.sh <cmd>` bez `--` prefixu, dej vědět — bude potřeba
routovat přes gost shell místo přímého `login` exec.

## Acceptance criteria

- [ ] `ProotManager.kt:116` — `launcherDistroId` rozlišuje `termux` od
      generického `docker` podle `rootfsDirName.contains("termux")`
- [ ] `logPrefix` a KDoc doplněny o `"termux"` case
- [ ] `launcher.sh` — nová větev vygeneruje `launcher-termux.sh` s exec
      formátem odpovídajícím `top.log` (dlouhé `--bind=`/`--rootfs=`/`--cwd=`
      flagy, přímý exec `login`, Android ART/APEX bindy)
- [ ] Fallback: pokud `$TERMUX_LOGIN` neexistuje (poškozený/jiný typ image),
      spadne zpět do generické `docker` větve, ne do chyby
- [ ] Otestováno reálným pullem `termux/termux-docker:latest` —
      `launcher-termux.sh` se spustí, `login` naběhne, `$PREFIX/bin` je
      na `PATH`
EOF 


