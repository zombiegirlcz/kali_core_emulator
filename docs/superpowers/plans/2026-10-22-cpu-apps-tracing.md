# CPU Apps — trasování, mapa jader a A/B benchmark: implementační plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rozšířit `CpuAppsActivity` o trasování CPU appek v čase, mapu jader a A/B benchmark pinu, poháněné novou verzí root daemona `cpuctl`.

**Architecture:** `cpuctld` (C, root) vzorkuje CPU appek ve své existující 5s HB smyčce, drží dvoupásmový ring buffer v RAM a hrubý pás na disku; nové podpříkazy `cpuctl` vracejí TSV. `LocalApiServer` je vystavuje jako REST (Bearer, i z loopbacku), appka je čte přes `su -c cpuctl` stejným vzorem jako dnes. UI jsou tři taby v Compose, grafy ručně v `Canvas`.

**Tech Stack:** C (NDK cross-compile přes Modal), Kotlin/Compose (JVM 17, Compose BOM z `gradle/libs.versions.toml`), `sh`+`awk` pro `nh`. Žádná nová závislost.

**Spec:** `docs/superpowers/specs/2026-10-22-cpu-apps-tracing-design.md`

## Global Constraints

- **ZÁKAZ LOKÁLNÍHO BUILDU.** Žádné `./gradlew` ani `clang` lokálně. C: `zsh mbuild native`, APK: `zsh mbuild build`/`all`. JVM testy: na Modalu.
- **Před každým buildem `git commit && git push`** na `dev`; `mbuild sync` klonuje z GitHubu.
- **Binárku `magisk-modules/nh_cpuctl/system/bin/cpuctl` commitnout** po každém `mbuild native` (AGENTS.md §4) — necommitnutý artefakt v APK/modulu nebude.
- **Afinita vždy per-vlákno:** v C `set_aff_tasks()`, v shellu `taskset -a -p`. Nikdy `taskset -p` bez `-a`.
- **Nikdy nečíst `ufstw_*` / `ufshpb_*` sysfs** (kernel panika, AGENTS.md §13).
- **Pinovat jen appky** (`uid >= APP_UID_MIN` = 10000), systémové procesy nikdy.
- Nové endpointy patří do `sensitiveEndpoints` v `LocalApiServer.kt:480-486` → Bearer token i z loopbacku.
- `CPUCTL_VERSION` = `"1.4"`, `magisk-modules/nh_cpuctl/module.prop` `version` stejně.
- Clustery **nikdy hardcoded** — vždy z `cpuinfo_max_freq`.
- Rozpočet vzorkování: ≤ 1 % jednoho jádra. Při překročení se zvětší krok hrubého pásu, neslevuje se z rozpočtu.
- Barvy UI: little `#3987e5`, big `#d95926`, OK `#199e70`, surface `#1a1a19`, text `#f0efec`/`#c3c2b7`. Rozlišení nikdy jen odstínem.
- Komunikace s uživatelem a komentáře v kódu česky, kód a identifikátory anglicky (jako zbytek `cpuctl.c`).
- **Device testy se spouštějí z repa, ne kopií na `/sdcard`** (vzor `tools/test-boot-session.sh`):
  `ashell -c "su -c 'sh <repo>/tools/test-cpuctl-14.sh'"`. Skript běží jako root
  na hostu, takže cesty bere z argumentů / proměnných, nikdy relativně k cwd.
  Kde plán níže píše `sh /sdcard/test-cpuctl-14.sh`, platí tahle cesta.
- **Restart daemona v testech:** `pkill -x cpuctld` a znovu `cpuctl daemon &`
  (jak to dělá `service.sh`). Podpříkaz `cpuctl daemon stop` **neexistuje**.

## Review Focus

1. **Zařízení s jedním clusterem** (všechna jádra stejná `cpuinfo_max_freq`) — `cores` musí vrátit 1 cluster a UI nabídnout jen „všechna jádra", ne prázdné chipy. → test v Tasku 1.
2. **Poškozený / starší `history.bin`** (jiná verze formátu, zkrácený soubor, nesmysl v hlavičce) — daemon startuje s prázdnou historií a loguje to, nikdy nespadne ani nečte mimo buffer. → test v Tasku 2.
3. **Benchmark přerušený signálem nebo zmizením appky** — maska se vrátí na původní hodnotu, proces neskončí v nekonečné smyčce. → test v Tasku 4.
4. **Víc balíčků než strop ring bufferu** — LRU podle maxima vyřadí nejnižší, nikdy se nepřepíše cizí slot. → test v Tasku 2.
5. **Stará binárka `cpuctl` 1.3 proti novému UI/API** — `history`/`cores`/`bench` neznámé → UI hlásí „přeflashuj nh_cpuctl 1.4", nekreslí prázdné grafy. → test v Tasku 6 a 9.

---

## File Structure

| Soubor | Odpovědnost |
|---|---|
| `app/src/main/cpp/cpuctl.c` (M) | vzorkování, ring buffer, clustery, `history`/`cores`/`events`/`bench`/`layout` |
| `magisk-modules/nh_cpuctl/module.prop` (M) | verze 1.4 |
| `magisk-modules/nh_cpuctl/system/bin/cpuctl` (M) | zbuilděná binárka (commit!) |
| `tools/test-cpuctl-14.sh` (C) | device test nových podpříkazů |
| `app/src/main/java/com/linux_core/core/cpu/CpuData.kt` (C) | modely + parsery TSV (čistá logika, JVM-testovatelná) |
| `app/src/test/java/com/linux_core/core/cpu/CpuDataTest.kt` (C) | JVM testy parserů |
| `app/src/main/java/com/linux_core/core/LocalApiServer.kt` (M) | REST endpointy, async bench job |
| `app/src/main/java/com/linux_core/ui/cpu/CpuCharts.kt` (C) | sdílené `Canvas` grafy (sparkline, timeline, bar) |
| `app/src/main/java/com/linux_core/ui/cpu/MonitorTab.kt` (C) | tab Monitor + detail appky |
| `app/src/main/java/com/linux_core/ui/cpu/CoresTab.kt` (C) | tab Jádra |
| `app/src/main/java/com/linux_core/ui/cpu/BenchTab.kt` (C) | tab Benchmark |
| `app/src/main/java/com/linux_core/ui/CpuAppsActivity.kt` (M) | hostuje taby, drží stav, volá `cpuctl` |
| `app/src/main/assets/nh` (M) | `nh cpu trace/cores/bench app/layout` |

---

### Task 1: Clustery jader + `cpuctl cores`

**Files:**
- Modify: `app/src/main/cpp/cpuctl.c` (nové funkce vedle `all_mask()`/`fastest_core()` ~ř. 189/971, dispatch v `main()` ~ř. 1312)
- Test: `tools/test-cpuctl-14.sh` (create)

**Interfaces:**
- Produces: `int cpu_clusters(cluster_t *out, int max)` — vyplní `{int id; unsigned long mask; long max_khz; int ncores;}` vzestupně podle `max_khz`, vrací počet; `static int cmd_cores(void)` tiskne TSV `core<TAB>max_khz<TAB>cur_khz<TAB>busy_promile<TAB>cluster`.
- `busy_promile` per jádro z delty `/proc/stat` řádku `cpuN` mezi dvěma odečty 500 ms od sebe.

- [ ] **Step 1: Napsat padající test**

```sh
# tools/test-cpuctl-14.sh — sekce cores
out=$(cpuctl cores) || fail "cpuctl cores selhal (stará binárka?)"
n=$(printf '%s\n' "$out" | grep -c .)
[ "$n" -eq "$(nproc --all)" ] || fail "cores: $n řádků, jader $(nproc --all)"
# každý řádek 5 polí, cluster je číslo, max_khz > 0
printf '%s\n' "$out" | awk -F'\t' 'NF!=5 || $2<=0 || $5!~/^[0-9]+$/ {exit 1}' || fail "cores: tvar řádku"
# clustery vzestupně podle max_khz: cluster 0 nesmí mít vyšší max_khz než cluster 1
printf '%s\n' "$out" | sort -t"$(printf '\t')" -k5,5n -k2,2n |
  awk -F'\t' '{if ($5==p5 && $2<p2) exit 1; if ($5>p5 && $2<p2) exit 1; p5=$5; p2=$2}' ||
  fail "cores: clustery nejsou vzestupně podle frekvence"
# jeden cluster je legální stav (emulátor, homogenní SoC) — nesmí to být chyba
```

- [ ] **Step 2: Spustit a ověřit, že padá**

Run: `ashell -c 'su -c "sh /sdcard/test-cpuctl-14.sh"'`
Expected: FAIL na `cpuctl cores selhal (stará binárka?)` — 1.3 příkaz nezná.

- [ ] **Step 3: Implementovat `cpu_clusters()` a `cmd_cores()` v `cpuctl.c`**

`cpu_clusters()`: projít `/sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq`, seskupit shodné frekvence, setřídit vzestupně. Jádro bez `cpufreq` (offline) → cluster `-1`, do masky se nepočítá. Homogenní SoC dá jeden cluster — není to chyba.

- [ ] **Step 4: Bump `CPUCTL_VERSION` na `"1.4"` a `module.prop`**

- [ ] **Step 5: Build, commit binárky, flash**

```bash
zsh mbuild native
git add magisk-modules/nh_cpuctl/system/bin/cpuctl magisk-modules/nh_cpuctl/module.prop app/src/main/cpp/cpuctl.c
git commit -m "feat(cpuctl): detekce clusterů + cpuctl cores (v1.4)" && git push origin dev
python3 magisk-modules/magiskb.py nh_cpuctl
```
Pak flash modulu a reboot.

- [ ] **Step 6: Spustit test, ověřit PASS**

Run: `ashell -c 'su -c "sh /sdcard/test-cpuctl-14.sh"'`
Expected: sekce `cores` PASS, `cpuctl version` → `1.4`.

- [ ] **Step 7: Commit testu**

```bash
git add tools/test-cpuctl-14.sh && git commit -m "test(cpuctl): device test pro cores"
```

---

### Task 2: Vzorkování (hybrid roster) + ring buffer + `cpuctl history`

**Files:**
- Modify: `app/src/main/cpp/cpuctl.c` (`daemon_main()` ~ř. 858, vedle `scan_apprules()` ~ř. 600)
- Modify: `tools/test-cpuctl-14.sh`

**Interfaces:**
- Consumes: `cpu_clusters()` z Tasku 1.
- Produces:
  - `void sample_tick(void)` — volá se z HB smyčky; jemný pás každých 5 s, hrubý každých 60 s.
  - `static int cmd_history(const char *pkg, const char *band)` — TSV `ts<TAB>promile<TAB>core<TAB>mask_hex`, nejstarší první; `band` = `fine`|`coarse` (default `fine`).
  - `int history_load(void)` / `void history_save(void)` — `/data/adb/cpuctl/history.bin`.
- Formát slotu: `{char pkg[128]; unsigned short fine[120]; unsigned short coarse[720]; unsigned char fine_core[120], coarse_core[720]; int fine_head, coarse_head; unsigned long mask; unsigned short peak;}`. Hlavička: magic `"NHCH"`, `u32 version = 1`, `u32 nslots`, `u32 slot_size`, `i64 saved_at`. Nativní endianness (soubor čte jen `cpuctl` na témže zařízení).
- `#define MAX_HIST_SLOTS 64` (spec §6.2: ≤ 64 balíčků ≈ 215 kB RAM). Při plném stropu se vyřadí slot s nejnižším `peak`.

- [ ] **Step 1: Napsat padající testy**

```sh
# sekce history
cpuctl history com.linux_core fine >/dev/null || fail "history neexistuje"
# po 12 s musí být aspoň 2 jemné vzorky u nějaké appky nad prahem
sleep 12
rows=$(cpuctl history "$BUSY_PKG" fine | grep -c .)
[ "$rows" -ge 2 ] || fail "history fine: $rows vzorků za 12 s (čekáno ≥2)"
# tvar: ts rostoucí, promile 0..1000*ncores, core < nproc
cpuctl history "$BUSY_PKG" fine |
  awk -F'\t' -v nc="$(nproc --all)" '{if ($1<p) exit 1; if ($2<0) exit 1; if ($3>=nc) exit 1; p=$1}' ||
  fail "history: tvar nebo pořadí vzorků"
# hrubý pás přežije restart daemona
before=$(cpuctl history "$BUSY_PKG" coarse | grep -c .)
pkill -f cpuctld; sleep 8   # service.sh/daemon ho nastartuje znovu
after=$(cpuctl history "$BUSY_PKG" coarse | grep -c .)
[ "$after" -ge "$before" ] || fail "coarse historie se restartem ztratila ($before → $after)"
# poškozený history.bin → prázdná historie, daemon žije
pkill -x cpuctld; printf 'ROZBITO' > /data/adb/cpuctl/history.bin
# (daemon start) ; pak:
cpuctl history "$BUSY_PKG" coarse >/dev/null || fail "poškozený history.bin daemon složil"
pidof cpuctld >/dev/null || fail "daemon po poškozeném history.bin neběží"
# strop slotů: víc balíčků než MAX_HIST_SLOTS nesmí nic přepsat
[ "$(cpuctl history --slots | grep -c .)" -le "$MAX_HIST_SLOTS" ] || fail "přetečení slotů"
```

- [ ] **Step 2: Spustit, ověřit FAIL**

Run: `ashell -c 'su -c "sh /sdcard/test-cpuctl-14.sh"'`
Expected: FAIL `history neexistuje`.

- [ ] **Step 3: Implementovat ring buffer a `sample_tick()`**

Roster podle spec §6.1: **hrubý krok** = full scan `/proc` (uid ≥ 10000, balíček z `get_pkg()`) pro všechny appky; **jemný krok** = podmnožina (balíček nad 5 % jednoho jádra v posledním hrubém vzorku, `/top-app` z `get_cpuset()`, nebo pin pravidlo v `g_apps[]`). Appka, co spadne pod práh, dojede okno a vypadne. Hrubý vzorek = průměr 12 jemných, tam kde jemné jsou; jinak z vlastního scanu. Dominantní jádro z pole 39 `/proc/<tid>/stat`. Při plném stropu vyřadit slot s nejnižším `peak`.

- [ ] **Step 4: Implementovat `history_load()` / `history_save()`**

Load při startu daemona: nesouhlasí-li magic, `version`, `slot_size` nebo velikost souboru, zahodit a logovat — nikdy nečíst mimo buffer. Vzorky starší 12 h zahodit. Save: hrubý pás + events každých 5 min a na SIGTERM (`g_running == 0` cesta v `daemon_main()`).

- [ ] **Step 5: Implementovat `cmd_history()` + `--slots`**

- [ ] **Step 6: Změřit režii full scanu**

Run: `ashell -c 'su -c "cpuctl apps 0 >/dev/null"'` v cyklu a odečíst `utime+stime` `cpuctld` z `/proc/<pid>/stat` za 5 minut.
Expected: < 1 % jednoho jádra (tj. < 3 s CPU za 5 min). Při překročení zvětšit krok hrubého pásu (60 s → 120 s) a přeměřit.

- [ ] **Step 7: Build, commit binárky, flash, spustit testy**

Expected: sekce `history` PASS, včetně restartu a poškozeného souboru.

- [ ] **Step 8: Commit**

```bash
git commit -m "feat(cpuctl): dvoupásmový ring buffer + cpuctl history"
```

---

### Task 3: `cpuctl events` + `cpuctl layout`

**Files:**
- Modify: `app/src/main/cpp/cpuctl.c` (`cmd_app_pin()` ~ř. 1270, `scan_apprules()` ~ř. 600, `repin_apps()` ~ř. 633, `handle_new_app()` ~ř. 621)
- Modify: `tools/test-cpuctl-14.sh`

**Interfaces:**
- Produces:
  - `void record_event(const char *pkg, unsigned long old_mask, unsigned long new_mask, const char *who)` — `who` ∈ `cli`/`api`/`rule`/`repin`; kruhový buffer 256 záznamů, ukládá se s hrubým pásem.
  - `static int cmd_events(int limit)` — TSV `ts<TAB>pkg<TAB>old_mask<TAB>new_mask<TAB>who`, nejnovější první.
  - `static int cmd_layout(const char *name)` — `free-big` (všechny appky mimo `/top-app` na nejnižší cluster), `proot-cpu<N>` (ostatní appky z jádra N, jako `nh cpu isolate`), `clear` (zahodit všechna `app.*` pravidla + obnovit `all_mask()` per-vlákno).

Pozor (spec §6.3): u appek se dnes změna masky **neloguje vůbec** — tohle je nová funkcionalita, ne obal nad `cpuctl.log`.

- [ ] **Step 1: Napsat padající testy**

```sh
# events
cpuctl app-pin "$TEST_PKG" 3 >/dev/null
ev=$(cpuctl events 5 | head -1)
printf '%s' "$ev" | awk -F'\t' -v p="$TEST_PKG" '$2!=p || $4!="3" || $5!="cli" {exit 1}' ||
  fail "events: poslední záznam není náš pin ($ev)"
cpuctl app-pin "$TEST_PKG" off >/dev/null
cpuctl events 5 | head -1 | awk -F'\t' '$5!="cli" {exit 1}' || fail "events: off se nezaznamenal"
# layout clear smaže pravidla a vrátí všechna jádra VŠEM VLÁKNŮM
cpuctl app-pin "$TEST_PKG" 3 >/dev/null
cpuctl layout clear >/dev/null
ls /data/user/0/com.linux_core/files/nh/cpu/ | grep -q '^app\.' && fail "layout clear nesmazal pravidla"
for t in /proc/$(pidof -s "$TEST_PKG")/task/*; do
  m=$(sed -n 's/^Cpus_allowed:[[:space:]]*//p' "$t/status" | tr -d ,)
  [ "$m" = "$ALL_MASK" ] || fail "layout clear: vlákno ${t##*/} má masku $m"
done
```

- [ ] **Step 2: Spustit, ověřit FAIL** — Expected: FAIL `events` (příkaz neznámý).

- [ ] **Step 3: Implementovat `record_event()` a volání ze všech čtyř cest**

Volat na každé skutečné změně masky (ne při no-op repinu, jinak buffer zaplaví `repin`).

- [ ] **Step 4: Implementovat `cmd_events()` a `cmd_layout()`**

`layout` používá `pin_pkg()` / `set_aff_tasks()`, takže je per-vlákno zdarma. `free-big` nechá na místě appku v `/top-app`.

- [ ] **Step 5: Build, commit binárky, flash, testy PASS**

- [ ] **Step 6: Commit** — `feat(cpuctl): events + layout`

---

### Task 4: `cpuctl bench` (A/B pinu)

**Files:**
- Modify: `app/src/main/cpp/cpuctl.c`
- Modify: `tools/test-cpuctl-14.sh`

**Interfaces:**
- Produces: `static int cmd_bench(const char *pkg, const char *maskA, const char *maskB, int sec)` — TSV `phase<TAB>mask<TAB>jiffies_per_s<TAB>samples<TAB>mA<TAB>dirty`, dva řádky (`A`, `B`).
- `jiffies_per_s` = delta `utime+stime` všech procesů balíčku / reálný čas.
- `mA` z `/sys/class/power_supply/*/current_now` **v obou oknech**, hlásí se hodnota za okno; rozdíl počítá volající. Nečitelný zdroj → prázdné pole.
- `dirty` = `1`, když jiné appky (uid ≥ 10000) spotřebovaly > 15 % součtu všech jader, nebo se během okna změnila `scaling_cur_freq` clusteru o > 20 %.
- **Původní maska se vrací vždy** — i při SIGINT/SIGTERM (handler + `atexit`) a při zmizení appky.

- [ ] **Step 1: Napsat padající testy**

```sh
# bench vrátí dva řádky a obnoví masku
orig=$(cpuctl apps 0 | awk -F'\t' -v p="$BUSY_PKG" '$1==p {print $4}')
out=$(cpuctl bench "$BUSY_PKG" 3f c0 5)
[ "$(printf '%s\n' "$out" | grep -c .)" -eq 2 ] || fail "bench: nevrátil 2 řádky"
printf '%s\n' "$out" | awk -F'\t' 'NF!=6 {exit 1}' || fail "bench: tvar řádku"
now=$(cpuctl apps 0 | awk -F'\t' -v p="$BUSY_PKG" '$1==p {print $4}')
[ "$now" = "$orig" ] || fail "bench neobnovil masku ($orig → $now)"
# přerušení signálem taky obnoví masku
cpuctl bench "$BUSY_PKG" 3f c0 30 & bp=$!; sleep 6; kill -TERM $bp; wait $bp 2>/dev/null
now=$(cpuctl apps 0 | awk -F'\t' -v p="$BUSY_PKG" '$1==p {print $4}')
[ "$now" = "$orig" ] || fail "bench po SIGTERM neobnovil masku ($orig → $now)"
# neexistující balíček → nenulový exit, žádné čekání
start=$(date +%s); cpuctl bench nic.takoveho.neni 3f c0 10 && fail "bench prošel pro neexistující pkg"
[ $(( $(date +%s) - start )) -lt 5 ] || fail "bench čekal na neexistující pkg"
```

- [ ] **Step 2: Spustit, ověřit FAIL**

- [ ] **Step 3: Implementovat `cmd_bench()`**

Vzorkovat 1× za sekundu. Balíček bez živého procesu → chyba hned, ne čekání. Obnovu masky registrovat **před** první změnou.

- [ ] **Step 4: Build, commit binárky, flash, testy PASS**

- [ ] **Step 5: Commit** — `feat(cpuctl): A/B benchmark pinu`

---

### Task 5: Kotlin modely a parsery TSV

**Files:**
- Create: `app/src/main/java/com/linux_core/core/cpu/CpuData.kt`
- Create: `app/src/test/java/com/linux_core/core/cpu/CpuDataTest.kt`

**Interfaces:**
- Produces (čistá logika, žádný Android kontext — proto JVM-testovatelná):
  - `data class CoreRow(val core: Int, val maxKhz: Long, val curKhz: Long, val busyPromile: Int, val cluster: Int)`
  - `data class Cluster(val id: Int, val cores: List<Int>, val maxKhz: Long, val label: String)` — `label` z `cores` a pořadí clusteru: nejnižší = `little`, nejvyšší = `big`, prostřední = `mid`; **jediný cluster = `všechna`**.
  - `data class Sample(val ts: Long, val promile: Int, val core: Int, val mask: String)`
  - `data class PinEvent(val ts: Long, val pkg: String, val oldMask: String, val newMask: String, val who: String)`
  - `data class BenchPhase(val phase: String, val mask: String, val jiffiesPerS: Double, val samples: Int, val mA: Int?, val dirty: Boolean)`
  - `object CpuTsv { fun cores(s: String): List<CoreRow>; fun clusters(rows: List<CoreRow>): List<Cluster>; fun history(s: String): List<Sample>; fun events(s: String): List<PinEvent>; fun bench(s: String): List<BenchPhase>; fun maskOf(cores: List<Int>): String }`
  - `sealed interface CpuctlState { object Ok; data class TooOld(val version: String); object NoRoot }` + `fun parseVersion(s: String): CpuctlState`

- [ ] **Step 1: Napsat padající testy**

```kotlin
class CpuDataTest {
    @Test fun `cores parsuje TSV a ignoruje hlavicku`()           // 8 řádků → 8 CoreRow
    @Test fun `clusters radi vzestupne a pojmenuje little a big`() // 0-5@1804000, 6-7@2304000
    @Test fun `jediny cluster se jmenuje vsechna`()                // homogenní SoC → 1 Cluster, label "všechna"
    @Test fun `maskOf prevadi jadra na hex`()                      // [6,7] → "c0"; [0..5] → "3f"; [] → "0"
    @Test fun `history preskoci poskozene radky a zachova poradi`() // 1 rozbitý řádek z 5 → 4 Sample, ts rostoucí
    @Test fun `bench cte prazdne mA jako null a dirty jako Boolean`()
    @Test fun `parseVersion pozna starou binarku`()                // "1.3" → TooOld("1.3"); "1.4" → Ok
    @Test fun `prazdny vstup da prazdny seznam, ne vyjimku`()       // všechny parsery
}
```

- [ ] **Step 2: Spustit, ověřit FAIL**

Run (Modal): `./gradlew test --tests "*CpuDataTest*"`
Expected: FAIL — `CpuData.kt` neexistuje.

- [ ] **Step 3: Implementovat `CpuData.kt`**

Parsery tolerantní: neúplný nebo nečitelný řádek se zahodí, nikdy výjimka — TSV přichází z `su` a může být zkrácené timeoutem.

- [ ] **Step 4: Testy PASS** — Run (Modal): `./gradlew test --tests "*CpuDataTest*"`

- [ ] **Step 5: Commit** — `feat(cpu): modely a TSV parsery`

---

### Task 6: API endpointy

**Files:**
- Modify: `app/src/main/java/com/linux_core/core/LocalApiServer.kt` (routing ~ř. 612-615, `sensitiveEndpoints` ~ř. 486, vedle `cpuctlRoot()` ~ř. 2539)
- Create: `app/src/test/java/com/linux_core/core/cpu/BenchJobsTest.kt`

**Interfaces:**
- Consumes: `CpuTsv`, `CpuctlState` z Tasku 5.
- Produces:
  - `GET /cpu/history?pkg=&band=` → `{"pkg":…,"band":…,"samples":[{ts,promile,core,mask}]}`
  - `GET /cpu/cores` → `{"cores":[…],"clusters":[{id,cores,maxKhz,label}]}`
  - `GET /cpu/events?limit=` → `{"events":[…]}`
  - `POST /cpu/bench {pkg,maskA,maskB,sec}` → `{"job":"<id>"}`; `GET /cpu/bench?job=` → `{"state":"running"|"done"|"error","phases":[…]}`
  - `POST /cpu/layout {layout}` → `{"layout":…,"changed":N}`
  - `object BenchJobs { fun submit(…): String; fun poll(id: String): BenchJob? }` — mapa v paměti, nepřežívá restart appky (spec §8).
- Všechny nové cesty přidat do `sensitiveEndpoints`.

- [ ] **Step 1: Napsat padající test**

```kotlin
class BenchJobsTest {
    @Test fun `submit vrati id a poll hlasi running`()
    @Test fun `poll neznameho id vrati null`()
    @Test fun `dokonceny job vrati faze a jde precist opakovane`()
}
```

- [ ] **Step 2: Spustit, ověřit FAIL** — Run (Modal): `./gradlew test --tests "*BenchJobsTest*"`

- [ ] **Step 3: Implementovat `BenchJobs` + handlery**

`handleCpuBenchPost` spustí `cpuctlRoot("bench …")` na vlákně a vrátí id **hned** — HTTP smyčka nesmí blokovat na 60 s. Stará binárka (`CpuctlState.TooOld`) → HTTP 409 s `{"error":"cpuctl 1.3 — přeflashuj nh_cpuctl 1.4"}`, ne 500.

- [ ] **Step 4: Testy PASS**

- [ ] **Step 5: Ověřit na zařízení**

```bash
T=$(cat /data/data/com.linux_core/files/api.token)
curl -s -H "Authorization: Bearer $T" 127.0.0.1:1337/cpu/cores | head -c 400
curl -s 127.0.0.1:1337/cpu/cores   # bez tokenu → 401
```
Expected: s tokenem JSON s `clusters`, bez tokenu 401.

- [ ] **Step 6: Commit** — `feat(api): /cpu/history, /cpu/cores, /cpu/events, /cpu/bench, /cpu/layout`

---

### Task 7: `nh` CLI

**Files:**
- Modify: `app/src/main/assets/nh` (`cpu_dispatch()` ~ř. 4052, `help_cpu()` ~ř. 4078, sekce CPU od ~ř. 3200)

**Interfaces:**
- Consumes: endpointy z Tasku 6 (`api_get`/`api_post_json` už v `nh` existují).
- Produces: `cpu_trace <pkg> [5m|1h|12h]`, `cpu_cores`, `cpu_bench_app <pkg> [sec]`, `cpu_layout <name>` + dispatch `trace`/`cores`/`bench app`/`layout`.
- Čistý `sh`+`awk`, **žádný python3** (vzor `cpu_apps_raw`/`cpu_appmon`).

- [ ] **Step 1: Napsat padající test**

```sh
# tools/test-cpuctl-14.sh — sekce nh
nh cpu cores | grep -qE 'little|big|všechna' || fail "nh cpu cores nepojmenoval clustery"
nh cpu trace "$BUSY_PKG" 5m | grep -q . || fail "nh cpu trace nic nevypsal"
nh cpu 2>&1 | grep -q 'trace' || fail "help nezmiňuje trace"
```

- [ ] **Step 2: Spustit, ověřit FAIL**

- [ ] **Step 3: Implementovat funkce a dispatch**

`cpu_trace` kreslí sparkline z `/cpu/history`; znakovou sadu vybrat podle toho, co unese guest terminál (spec §8) — blokové znaky, fallback `#`.

- [ ] **Step 4: Testy PASS** (po `ProotManager` deployi nového `nh`, nebo ručně `cp` do `/usr/local/bin/nh` pro test)

- [ ] **Step 5: Commit** — `feat(nh): nh cpu trace/cores/bench app/layout`

---

### Task 8: Sdílené grafy v Compose

**Files:**
- Create: `app/src/main/java/com/linux_core/ui/cpu/CpuCharts.kt`
- Test: `app/src/test/java/com/linux_core/ui/cpu/CpuChartsTest.kt`

**Interfaces:**
- Consumes: `Sample`, `PinEvent`, `CoreRow`, `Cluster` z Tasku 5.
- Produces (vše `@Composable`, kreslené v `Canvas`, žádná knihovna):
  - `Sparkline(samples: List<Sample>, color: Color, modifier: Modifier)` — 2dp linka, bez osy.
  - `TimelineChart(samples: List<Sample>, events: List<PinEvent>, modifier: Modifier)` — osa Y s popisky, svislé přerušované čáry pro `events`, bod + popisek na maximu.
  - `CoreBars(rows: List<CoreRow>, clusters: List<Cluster>, modifier: Modifier)` — bar per jádro, barva podle clusteru, popisek čísla jádra.
  - `HBarPair(labelA: String, valueA: Double, labelB: String, valueB: Double, modifier: Modifier)` — pro benchmark.
  - `object CpuColors { val little; val big; val ok; val surface; val ink; val inkDim }` — hodnoty z Global Constraints.

- [ ] **Step 1: Prázdné vstupy nesmí spadnout — napsat test**

```kotlin
class CpuChartsTest {   // app/src/test/… — čistá logika škálování, bez Compose runtime
    @Test fun `scale prazdneho seznamu nedeli nulou`()
    @Test fun `scale jednoho vzorku da platny rozsah`()
    @Test fun `scale stejnych hodnot nedeli nulou`()
}
```
Škálování vytknout do `internal fun yScale(samples: List<Sample>): ClosedFloatingPointRange<Float>` v `CpuCharts.kt`, aby šlo testovat bez Compose.

- [ ] **Step 2: Spustit, ověřit FAIL** — Run (Modal): `./gradlew test --tests "*CpuChartsTest*"`

- [ ] **Step 3: Implementovat `CpuCharts.kt`**

- [ ] **Step 4: Testy PASS**

- [ ] **Step 5: Commit** — `feat(ui): Canvas grafy pro CPU`

---

### Task 9: Tab Monitor + detail appky

**Files:**
- Create: `app/src/main/java/com/linux_core/ui/cpu/MonitorTab.kt`
- Test: `app/src/test/java/com/linux_core/ui/cpu/MonitorStateTest.kt`
- Modify: `app/src/main/java/com/linux_core/ui/CpuAppsActivity.kt` (taby, stav, `cpuctl()` ~ř. 62, `parseApps()` ~ř. 73 → nahradit `CpuTsv`)

**Rozhodnuto (pre-flight ruling):** `enum CpuTab` zavést se **všemi třemi**
hodnotami už tady a taby CORES/BENCH vykreslit jako prázdný stav s textem
„připravuje se" — Task 10 je vyplní. `TabRow` se třemi taby je jedna
komponenta; dělit ji mezi tasky by znamenalo skončit T9 s rozbitým UI.

**Interfaces:**
- Consumes: `CpuTsv`, `CpuctlState`, `CpuCharts`.
- Produces: `MonitorTab(state, onPinApp, onOpenDetail)`, `AppDetail(pkg, …)`, a v aktivitě `enum class CpuTab { MONITOR, CORES, BENCH }` + `TabRow`.

Chování: pás jader nahoře, seznam appek se sparkline a `teď / max`, přepínač okna 5 min / 1 h / 12 h, řazení podle CPU. Výstražný řádek, když appka drží nad prahem déle než pár minut. Detail: `TimelineChart` s `events`, rozpad „kde appka běžela", pinovací chipy **z clusterů** (tím padá dnešní špatné `0-3`/`4-7`), přepínač „držet i po restartu appky" (pravidlo `app.<pkg>` vs. jednorázový pin), textová historie.

Prázdné / chybové stavy: `NoRoot` → „pinování vyžaduje modul nh_cpuctl"; `TooOld` → „cpuctl 1.3 neumí history — přeflashuj nh_cpuctl 1.4"; žádná data → vysvětlení, **nikdy prázdný graf vydávaný za měření**.

- [ ] **Step 1: Napsat test stavové logiky**

```kotlin
class MonitorStateTest {
    @Test fun `appka nad prahem po tri vzorky da varovani`()
    @Test fun `appka pod prahem varovani nema`()
    @Test fun `stara binarka da TooOld stav a zadne grafy`()
    @Test fun `chipy jader vznikaji z clusteru, ne z konstanty`()  // 0-5/6-7, ne 0-3/4-7
}
```
Logiku vytknout do `internal object MonitorLogic` v `MonitorTab.kt`.

- [ ] **Step 2: Spustit, ověřit FAIL**

- [ ] **Step 3: Implementovat `MonitorTab.kt` a přepojit aktivitu na taby**

Sjednotit barvy na `CpuColors` (dnešní `#00FF41` na `#08090D` v této aktivitě zmizí).

- [ ] **Step 4: Testy PASS**

- [ ] **Step 5: Build APK, nainstalovat, projít na zařízení**

Run: `zsh mbuild all` → `adb install -r ~/Download/kali_core.apk` (nebo ruční instalace)
Expected: taby jdou přepnout, Monitor kreslí sparkliny, detail otevře graf, pin z chipů změní masku (ověřit `cpuctl apps 0`).

- [ ] **Step 6: Commit** — `feat(ui): tab Monitor s trasováním a detailem appky`

---

### Task 10: Tab Jádra + tab Benchmark

**Files:**
- Create: `app/src/main/java/com/linux_core/ui/cpu/CoresTab.kt`
- Create: `app/src/main/java/com/linux_core/ui/cpu/BenchTab.kt`
- Test: `app/src/test/java/com/linux_core/ui/cpu/BenchVerdictTest.kt`
- Modify: `app/src/main/java/com/linux_core/ui/CpuAppsActivity.kt` (nahradit prázdné stavy tabů CORES/BENCH z Tasku 9)

**Interfaces:**
- Consumes: `/cpu/cores`, `/cpu/layout`, `/cpu/bench`, `CpuCharts`.
- Produces: `CoresTab(rows, clusters, assignments, onLayout, onAssign)`, `BenchTab(pkgs, onRun, result)`.

`CoresTab`: řádek per jádro/cluster s obsazeností a dlaždicemi appek, tři hotová rozvržení (`free-big`, `proot-cpu<N>`, `clear`), poznámka že systémové procesy se nepinují. `BenchTab`: výběr appky a dvou masek, průběh, výsledek jako `HBarPair` (práce, odběr) + verdikt; `dirty` výsledek **označit a nenabídnout doporučení**; dokud měření neběželo, prázdný stav bez čísel.

- [ ] **Step 1: Napsat test**

```kotlin
class BenchVerdictTest {
    @Test fun `dirty vysledek nedava doporuceni`()
    @Test fun `verdikt porovnava praci i odber`()
    @Test fun `chybejici mA vynecha radek odberu, ne cely verdikt`()
}
```
Logiku do `internal object BenchVerdict` v `BenchTab.kt`.

- [ ] **Step 2: Spustit, ověřit FAIL**

- [ ] **Step 3: Implementovat oba taby**

- [ ] **Step 4: Testy PASS**

- [ ] **Step 5: Build, instalace, projít na zařízení**

Expected: `clear` smaže pravidla a appky mají `all_mask` (ověřit `cpuctl apps 0`); benchmark doběhne a vrátí masku.

- [ ] **Step 6: Commit** — `feat(ui): taby Jádra a Benchmark`

---

### Task 11: Dokumentace a uzavření

**Files:**
- Modify: `AGENTS.md` (sekce o `cpuctl`/`nh cpu` — nové příkazy a past „binárka musí jít spolu se zdrojem")
- Modify: `README.md` (`nh cpu trace/cores/bench app/layout`)

- [ ] **Step 1: Dopsat nové příkazy do `AGENTS.md` a `README.md`**

Zmínit: `cpuctl` 1.4, `history.bin` formát a že se při neznámé verzi zahazuje, hybrid roster a jeho práh, a že `events` u appek je nová věc.

- [ ] **Step 2: Spustit celou testovou sadu**

Run: `ashell -c 'su -c "sh /sdcard/test-cpuctl-14.sh"'` a (Modal) `./gradlew test --tests "*Cpu*"`
Expected: vše PASS.

- [ ] **Step 3: Commit a push** — `docs: cpuctl 1.4 a nové nh cpu příkazy`
