# Prompt: Boot refactor — Fáze 3: UI, testování, cleanup, finální review

**Předpoklad:** Fáze 1 (struktura/migrace) a Fáze 2 (boot skript) jsou implementované.
**Cíl:** UI oddělení Docker pull logiky, kompletní testovací sada (včetně regresních
testů na oba bugy z Fáze 2), cleanup starých souborů, finální konsolidovaný checklist.

> **SYNCHRONIZACE 2026-08-14:** Statický proot je primární. Cleanup zahrnuje
> odstranění dynamických binárek a starého launcher.sh template.

---

## UI — Docker pull do samostatné obrazovky

- Nový soubor `app/src/main/java/com/linux_core/ui/docker/DockerPullScreen.kt`
  (Compose), přesunout tam veškerou pull logiku z `MainActivity.kt`
- Tlačítko "Pull Docker Image" v `MainActivity` otevře `DockerPullScreen`
- Po dokončení/zavření se seznam docker images v hlavním UI aktualizuje
  (zdroj: `nh/distro/docker/*` z Fáze 1, procházet podsložky)
- Restore obrazovka: nové Compose okno zobrazující obsah `nh/distro/backup/`
  (viz `backupRootfs()`/`restoreRootfs()` z Fáze 1), tlačítko "vybrat soubor" otevírá
  správce souborů jako dosud

---

## Cleanup (až po ověřeném testu na zařízení, ne dřív)

- [ ] Smazat `app/src/main/assets/launcher.sh` (template už není potřeba)
- [ ] Smazat `app/src/main/assets/proot-{aarch64,arm,i686,x86_64}` (dynamické, nahrazeny statickými)
- [ ] Smazat `app/src/main/assets/loader-{aarch64,arm,i686,x86_64}` (dynamické, nahrazeny statickými)
- [ ] Smazat `app/src/main/assets/loader-aarch64.orig`, `loader-orig-aarch64` (staré zálohy)
- [ ] Smazat `app/src/main/assets/libtalloc-{aarch64,arm,i686,x86_64}.so` (static proot nepotřebuje)
- [ ] Odstranit `deployBinaries()` z `ProotManager.kt` (nebo nechat jen deploy do usr/bin)
- [ ] Odstranit `deployLauncherScript()` a `renderCompatLauncher()` z `ProotManager.kt`
- [ ] Odstranit legacy `filesDir/terminalmap` deploy (wrapper teď čte z `usr/bin/terminalmap`)
- [ ] Aktualizovat `AGENTS.md`/`README.md` s novým layoutem (`usr/bin/boot`, `nh/distro/`)

---

## Testovací sada na zařízení

Rozšířeno oproti původnímu plánu o regresní testy na oba nalezené bugy:

| # | Test | Očekávaný výsledek |
|---|------|---------------------|
| 1 | `boot kali` na čisté rootfs | naběhne login shell |
| 2 | `boot parrot` na čisté rootfs | naběhne login shell |
| 3 | **`boot parrot` s ručně smazaným `/root`** | skončí v `/`, ne pád (regrese Bugu #1) |
| 4 | **`echo "$0"` hned po bootu klasické distra** | žádný syntax error, `-c` dostal script vcelku (regrese Bugu #2) |
| 5 | `boot docker <termux image>` | naběhne, `IS_TERMUX_IMAGE=1` větev |
| 6 | `boot docker <alpine>` | naběhne přes `find` fallback |
| 7 | Docker image bez shellu | chybová hláška, `exit 1`, app otevře ashell |
| 8 | Restore parrot když existuje jen kali | odmítne, nepřepíše (kompatibilita z Fáze 1) |
| 9 | Migrace na zařízení se starým layoutem | `usr/bin` obsahuje původní i nové nástroje, nic neschází |
| 10 | Migrace spuštěná podruhé | no-op, `migration_v2_done` flag funguje |
| 11 | Bump `USR_TOOLS_VERSION` | `proot`/`loader`/`libtalloc.so.2` se skutečně redeployují |
| 12 | `/dev/bus/usb` bez připojeného USB hostu | žádný warning v logu |
| 13 | Statický proot funguje bez libtalloc | `ldd` nehlásí chybějící závislosti |

---

## Finální konsolidovaný checklist (všechny tři fáze)

- [ ] Jeden `boot` skript v `usr/bin/boot`, žádný template launcher
- [ ] `boot_classic()` a `boot_docker()` mají **shodnou** `PROOT_CWD` fallback logiku (Bug #1)
- [ ] Obě funkce volají `$GUEST_SH -c "$TARGET_CMD" -- "$@"` — uvozovkovaně, žádné
      zabalené apostrofy v proměnné (Bug #2)
- [ ] Statický proot + statický loader jako primární (žádná závislost na libtalloc)
- [ ] `usr/bin`/`usr/lib` po migraci obsahují **původní i nové** soubory, nic neztraceno
- [ ] `files/bin/*` (pokud existoval) sloučen do `usr/bin/*`
- [ ] `migrateLayout()` používá `safeMove()` (copy+verify+delete), ne slepý `renameTo()`
- [ ] `proot`/`loader` deploy napojen na `USR_TOOLS_VERSION` gate (stale-binary bug vyřešen)
- [ ] Docker fallback na `find` funguje pro neznámé image (Alpine)
- [ ] Restore neumožní přepsat rootfs jiného typu
- [ ] UI docker pull přesunut do `DockerPullScreen`
- [ ] `AGENTS.md`/`README.md` aktualizované
- [ ] Staré dynamické binárky odstraněny z assets (po ověření na zařízení)

## Otevřené otázky pro architekta

- Před spuštěním testu #9 (migrace) na ostrém zařízení: zálohovat `filesDir` celý (přes
  `adb backup` nebo ruční `tar`), aby šlo v případě problému vrátit stav.
- Chceme `PROOT_CWD`/`-c` regresní testy (#3, #4) zařadit i jako trvalou součást
  `deployArchBinaries()` self-testu při startu appky (rychlá kontrola v Logcatu), nebo
  jen jednorázově ručně ověřit a nechat být?
