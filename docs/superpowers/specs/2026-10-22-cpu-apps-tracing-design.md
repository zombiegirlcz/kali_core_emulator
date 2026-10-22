# CPU Apps — trasování, mapa jader a A/B benchmark (design)

Datum: 2026-10-22. Stav: návrh schválený uživatelem (mockup
`https://claude.ai/artifact/9tyGQeM98wsjtB1yZiPRDp`), čeká na plán.

## 1. Proč

`CpuAppsActivity` dnes umí jediný snímek: `cpuctl apps 0` → seznam
`balíček / CPU% / uid / maska` + tři chipy na pin. Uživatel ji otevírá, aby
**appku zrychlil přišpendlením na rychlá jádra, nebo naopak uvolnil jádro pro
jinou appku**. Na to mu snímek nestačí:

- Nepozná „žere teď" od „žere pořád" — dvě vteřiny měření nic neříkají o tom,
  jestli má smysl zasahovat.
- Po pinu nevidí, **jestli pin pomohl**. Žádné před/po.
- Nevidí, na kterých jádrech appka skutečně běžela — jen masku, tj. kde *smí*.
- Nevidí obsazenost jader, takže „uvolni jádro pro jinou appku" dělá naslepo.

Spouštěčem byl konkrétní případ (2026-10-22): uživatel nastavil appce všechna
jádra, cpu7 přesto držel 100 %. Příčina byla jinde (per-vlákno afinita, viz
`memory/cpu_affinity_per_thread.md`), ale diagnostika trvala dlouho **protože
UI neukazuje ani historii, ani reálné obsazení jader**.

## 2. Cíle

1. **Trasování** — u každé appky průběh CPU v čase (sparkline v seznamu, graf
   v detailu), s vyznačenými momenty, kdy se změnilo pravidlo pinu.
2. **Mapa jader** — obrácený pohled „jádro → kdo na něm smí a kdo na něm
   skutečně běžel", plus hotová rozvržení na jedno ťuknutí.
3. **A/B benchmark pinu** — nechat appku běžet dvě okna na různých maskách a
   porovnat odvedenou práci a (orientačně) odběr.
4. **Opravit hardcoded clustery** — dnešní chipy `little (0-3)` / `big (4-7)`
   jsou na zařízení uživatele **špatné** (reálně 0–5 little 1804 MHz, 6–7 big
   2304 MHz). Clustery se musí detekovat z `cpuinfo_max_freq`.

## 3. Ne-cíle

- **Ne**-benchmark samotných jader: to už je `nh cpu bench`. Tady se měří
  appka, ne křemík.
- **Ne** ovládání proot session. `nh cpu pin/core/unpin` (session) a
  `nh cpu app` (cizí appka) zůstávají oddělené — plést je dohromady už jednou
  stálo hodinu diagnostiky (AGENTS.md na to varuje).
- **Ne** vlastní vzorkování v appce. Viz omezení.
- **Ne** governor/frekvenční tuning per appka. `cpuctl boost` existuje a zůstává
  jak je.
- **Ne** automatická pravidla („appka přes 30 % → zahoď ji na little").
  Rozhodnutí zůstává na uživateli; návrh ho jen doporučí textem.

## 4. Anti-cíle (tím by to selhalo, i kdyby to fungovalo)

- **Předstírat měření bez rootu.** Bez `nh_cpuctl` musí UI říct, že to nejde,
  ne zobrazit prázdné nebo vymyšlené grafy. Pinování cizích appek je fyzicky
  root-only (`sched_setaffinity` na cizí uid chce `CAP_SYS_NICE`) a
  neslibuje se ani v UI, ani v CLI.
- **Zatížit telefon monitoringem.** Sonda, která sama žere CPU, je horší než
  žádná. Rozpočet: vzorkování ≤ 1 % jednoho jádra.
- **Psát na disk tolik, že to přispěje k UFS stallům.** Projekt má
  zdokumentovanou historii tvrdých zámrazů z I/O tlaku (AGENTS.md §13).
  Historie se drží v RAM daemona a na disk padá zřídka (viz 6.2).
- **Tvrdit, že A/B výsledek je měřitelně přesný,** když na pozadí běželo něco
  jiného. Znečištěné měření se musí označit (na to narazil `nh cpu bench`,
  kde výsledky kazila běžící agent session).

## 5. Omezení (daná prostředím, ne volbou)

| Omezení | Důsledek pro návrh |
|---|---|
| App uid nevidí cizí `/proc` (`hidepid`) | Appka **nesmí** vzorkovat sama. Vzorkuje `cpuctld` (root), appka čte hotová data. |
| Pin cizí appky = root (`CAP_SYS_NICE`) | Vše kromě čtení je za rootem; UI bez modulu nabídne jen čtení (uid 2000 `dumpsys cpuinfo`) nebo chybu. |
| `cpuctld` nemá přístup do `filesDir` appky jako vlastník | Historie žije v `/data/adb/cpuctl/` (root-only), appka ji čte přes `su -c cpuctl …`, stejný vzor jako dnešní `cpuctlRoot()`. |
| Afinita se nastavuje **per vlákno** | Všechny nové cesty používají `set_aff_tasks()` (C) / `taskset -a -p` (shell). Nikdy `taskset -p` bez `-a`. |
| `cpuctl` binárka a `cpuctl.c` se musí verzovat spolu | Bump `CPUCTL_VERSION` + `module.prop`, `nh`/UI hlásí starou binárku (už existuje `cpu_ctl_new`). Historie: `apps`/`app-pin` zůstaly jednou nezbuilděné a modul na telefonu je neznal. |
| `cpuctl` běží na všech zařízeních projektu | Počet jader a clustery nejsou konstanty. Detekce z `cpuinfo_max_freq`. |

## 6. Rozhodnutá architektura

### 6.1 Vzorkování (cpuctld)

`cpuctld` už má HB smyčku (`HB_INTERVAL 5 s`) s `repin_sessions()` /
`repin_apps()` a netlink proc connector na EXEC/FORK. Vzorkování se přidá do
téže smyčky — **žádné nové vlákno, žádný nový proces**.

Jeden vzorek per balíček: součet `utime+stime` delty všech procesů balíčku
(už existuje `get_jiffies()`), převedený na promile jednoho jádra, plus
dominantní jádro (nejčastější `task_cpu` z `/proc/<tid>/stat` pole 39) a
aktuální maska. Appky jen (`uid >= APP_UID_MIN`) — systémové procesy se
nečtou, jako dnes.

**Které appky (roster) — rozhodnuto: hybrid.** Dnešní daemon si v `g_apps[]`
drží *jen* appky s pin pravidlem (`scan_apprules()` čte výhradně soubory
`app.<pkg>`); `handle_new_app()` i `repin_apps()` pracují jen s tímto
seznamem. Seznam všech běžících appek tedy v daemonu neexistuje a dodělává se:

| Pás | Roster | Jak se zjistí |
|---|---|---|
| hrubý (1 min) | **všechny** appky | full scan `/proc` (uid ≥ 10000, balíček z `get_pkg()`) — stejná práce jako dnešní `cmd_apps`, ale jen jednou za minutu |
| jemný (5 s) | appky nad prahem, v popředí a pinnuté | podmnožina z posledního hrubého scanu + `g_apps[]` + popředí |

Full scan je drahá část (959 procesů na zařízení uživatele), proto běží na
hrubém kroku, ne na jemném. Jemný pás tak sleduje jen ~5–10 balíčků.
Appka, která přestane být nad prahem, dojede jemný pás do konce okna a pak
z něj vypadne (bez trhaného zapínání/vypínání).

Prahy: do jemného pásu vstupuje balíček nad 5 % jednoho jádra v posledním
hrubém vzorku; „popředí" se bere z `/proc/<pid>/cpuset` (`/top-app`), což
daemon už čte v `get_cpuset()`. **Režie full scanu je potřeba změřit** a
porovnat s rozpočtem ≤ 1 % jednoho jádra (anti-cíl v §4); pokud by ho
minutový scan překračoval, krok hrubého pásu se zvětší, ne že se sleví
z rozpočtu.

### 6.2 Dvě rozlišení, ring buffer v RAM

| Pás | Krok | Hloubka | Vzorků |
|---|---|---|---|
| jemný | 5 s | 10 min | 120 |
| hrubý | 1 min | 12 h | 720 |

Hrubý vzorek = průměr dvanácti jemných. Per balíček 840 vzorků × 4 B
(`u16` promile, `u8` jádro, `u8` příznaky) = 3,4 kB; strop `MAX_APPRULES`-krát,
tj. ≤ 64 balíčků ≈ 215 kB RAM. Balíčky nad strop: drží se ty s nejvyšším
dosavadním maximem (LRU podle maxima, ne podle abecedy).

Na disk (`/data/adb/cpuctl/history.bin`) se zapisuje **jen hrubý pás, jednou
za 5 minut** a při SIGTERM — kvůli anti-cíli „nepřispívat k I/O tlaku".
Jemný pás se po restartu daemona ztrácí; to je přijatelné.

**Hrubý pás restart přežít musí** a `cpuctld` ho při startu načítá zpět do ring
bufferu (`history.bin` tedy potřebuje čtecí i zápisovou cestu). Restart daemona
na tomhle projektu není vzácný — každý bump `module.prop`, reflash modulu a
každý boot. Bez načtení by se „12 h" view tiše resetoval a tvrdil by o
telefonu něco, co neodpovídá — což je přesně anti-cíl „nepředstírat měření".
Vzorky starší než 12 h se při načtení zahodí; mezera mezi vypnutím a startem
se v grafu kreslí jako mezera, ne jako nula.

### 6.3 Nové příkazy `cpuctl` (verze 1.4)

```
cpuctl history <pkg> [fine|coarse]   TSV: ts  promile  core  mask_hex
cpuctl cores                         TSV: core  max_khz  cur_khz  busy_promile  cluster
cpuctl events [N]                    TSV: ts  pkg  old_mask  new_mask  who
cpuctl bench <pkg> <maskA> <maskB> [sec]
                                     TSV: phase  mask  jiffies_per_s  samples  mA_delta  dirty
cpuctl layout free-big|proot-cpu<N>|clear
```

`events` je **nová funkcionalita, ne obal nad dnešním logem.** `cpuctl.log`
dnes loguje změnu masky jen u *sessions* (`repin session … was=…`); u appek
(`cmd_app_pin`, `scan_apprules`, `repin_apps`, `handle_new_app`) se nic
nezapisuje. Přidá se tedy strukturovaný záznam při každé změně masky appky
(kdo: `cli` / `api` / `rule` / `repin`), držený v RAM (kruhově, posledních
~256 událostí) a zapisovaný do `history.bin` spolu s hrubým pásem. Z toho se
v grafu kreslí svislé čáry „tady se změnil pin".

### 6.4 A/B benchmark

Dvě okna po `sec` (default 60) sekundách: `maskA`, pak `maskB`, pak **návrat
na původní masku** (i při chybě/signálu — `atexit`). Měří se:

- **odvedená práce**: `(utime+stime)` delta / reálný čas → „jiffies/s". Je to
  proxy, ne průchodnost; v UI se pojmenuje „práce odvedená za vteřinu", ne
  „výkon".
- **odběr**: `/sys/class/power_supply/*/current_now` — **celosystémový**, takže
  se nehlásí absolutní hodnota ani rozdíl proti „baseline bez appky" (appku
  nelze spolehlivě utišit, a kdyby zrovna něco dělala, baseline by v sobě měla
  její vlastní spotřebu). Měří se odběr v **obou** oknech A i B a hlásí se
  **rozdíl mezi nimi**: co je v systému konstantní, se vykrátí, a právě tu
  konstantnost hlídá `dirty`. Když se zdroj nečte, pole je prázdné a UI řádek
  odběru nekreslí.
- **`dirty`**: nastaví se, když během okna jiné appky (uid ≥ 10000) spotřebovaly
  nad prahem (návrh: > 15 % součtu všech jader) nebo když se měnila frekvence
  clusteru. Znečištěný výsledek UI označí a nenabídne doporučení.

**Nikdy nečíst `ufstw_*` / `ufshpb_*` sysfs atributy** — to je zdokumentovaná
příčina kernel paniky (AGENTS.md §13, čtvrtý incident). Benchmark se jich
nedotkne.

### 6.5 Clustery místo hardcoded jader

`cpuctl cores` vrací `cluster` jako index skupiny jader se stejným
`cpuinfo_max_freq` (vzestupně: 0 = nejpomalejší). UI i `nh` pojmenovávají
chipy z toho (`little 0–5`, `big 6–7`), nikdy z konstanty. **Tím se opravuje
dnešní špatný pin na `0-3`/`4-7`.**

### 6.6 API (1337, Bearer, `sensitiveEndpoints`)

```
GET  /cpu/history?pkg=<p>&band=fine|coarse
GET  /cpu/cores
GET  /cpu/events?limit=N
POST /cpu/bench      {pkg, maskA, maskB, sec}      → výsledek A/B
POST /cpu/layout     {layout: "free-big"|"proot-cpu7"|"clear"}
```

`/cpu/apps` a `/cpu/apps/pin` zůstávají. Všechny nové jdou do
`sensitiveEndpoints` (dnes už tam `/cpu/apps` je) — Bearer token i z loopbacku,
protože na 127.0.0.1 se dostane každá appka v zařízení a uid volajícího zjistit
nelze (AGENTS.md §7).

`POST /cpu/bench` běží desítky sekund → vrací `{"job":"<id>"}` hned a výsledek
se čte přes `GET /cpu/bench?job=<id>`; HTTP server nesmí blokovat na 60 s.

### 6.7 UI — tři taby v `CpuAppsActivity`

Dnešní obrazovka se stane tabem **Monitor**; přidají se **Jádra** a
**Benchmark**. Compose, žádná nová knihovna (grafy jako `Canvas`, projekt
nemá chart lib a nebude ji přidávat).

- **Monitor**: pás zátěže jader nahoře (bar per jádro, barva podle clusteru),
  seznam appek s řádkovou sparkline a `teď / max`, přepínač okna 5 min / 1 h /
  12 h, řazení. Výstražný řádek, když appka drží nad prahem déle než pár minut.
- **Detail appky**: graf za zvolené okno se svislými čarami z `events`, rozpad
  „kde appka skutečně běžela" po jádrech, pinovací chipy z clusterů, přepínač
  „držet i po restartu appky" (= zapsat `app.<pkg>` pravidlo vs. jen jednorázový
  pin), textová historie.
- **Jádra**: řádek per jádro/cluster s obsazeností a dlaždicemi appek, tři
  hotová rozvržení (`free-big`, `proot-cpu<N>` = dnešní `nh cpu isolate`,
  `clear`).
- **Benchmark**: výběr appky, dvě masky, průběh, výsledek jako dva pruhy
  (práce, odběr) + verdikt. Dokud měření neběželo, **žádná čísla** — prázdný
  stav s vysvětlením.

Barvy: clustery se liší barvou i světlostí (ověřená paleta — blue `#3987e5`
little, orange `#d95926` big, aqua `#199e70` stav OK; validováno na dark
surface `#1a1a19`, všech 6 kontrol prošlo). Nikdy jen odstín jako jediný nosič
informace. Dnešní neonová zeleň `#00FF41` na `#08090D` v této aktivitě se
sjednotí na tuhle paletu.

Prázdné / chybové stavy: bez rootu „pinování vyžaduje modul nh_cpuctl", s
starou binárkou „cpuctl 1.3 neumí history — přeflashuj nh_cpuctl 1.4".

### 6.8 `nh` CLI

```
nh cpu trace <pkg> [5m|1h|12h]    graf v terminálu (sparkline, čistý sh+awk)
nh cpu cores                      tabulka jader s clustery
nh cpu bench app <pkg> [sec]      A/B big vs little
nh cpu layout free-big|clear
```

Bez pythonu (vzor `cpu_appmon`, čistý `sh`+`awk`).

## 7. Postup a ověření

Pořadí je dané závislostmi: C → API → UI, protože bez `cpuctl 1.4` nemá
UI co čí­st.

1. `cpuctl.c` → verze 1.4: vzorkování, ring buffer, `history` / `cores` /
   `events` / `bench` / `layout`, clustery. Bump `module.prop`.
2. `zsh mbuild native` → **commit binárky** `magisk-modules/nh_cpuctl/system/bin/cpuctl`
   (AGENTS.md §4 — necommitnutý artefakt v APK nebude),
   `python3 magisk-modules/magiskb.py nh_cpuctl`, flash, reboot.
3. `LocalApiServer` endpointy + `nh` příkazy.
4. `CpuAppsActivity` → taby a grafy.
5. Testy: JVM unit testy na parsery (TSV → model, clustery z frekvencí,
   ring buffer agregace jemný→hrubý) v `app/src/test/java/com/linux_core/`;
   `tools/test-cpu-affinity.sh` zůstává pro per-vlákno invariant.
6. Build **jen přes Modal** (`zsh mbuild all`), nikdy lokálně.

Ověření na zařízení: `cpuctl version` hlásí 1.4; `cpuctl history com.linux_core
fine` vrací ≥ 2 vzorky po 10 s; režie daemona pod 1 % jednoho jádra
(`nh cpu apps` na `cpuctld`); A/B benchmark vrátí masku do původního stavu i
po `kill -TERM` během měření.

## 8. Co zbývá na builderovi

- Přesný binární formát `history.bin` (hlavička s verzí a počtem slotů,
  endianness nativní — soubor čte jen `cpuctl` na stejném zařízení).
- Rozvržení Compose komponent a tvar `Canvas` kreslení grafů.
- Jak se job id pro `/cpu/bench` drží v `LocalApiServer` (mapa v paměti stačí;
  benchmark nepřežívá restart appky).
- Prahy pro výstražný řádek a pro `dirty` (čísla v 6.4 jsou návrh, ne měření).
- Jestli `nh cpu trace` kreslí braille/blokové znaky nebo jen `#` — podle toho,
  co unese guest terminál.
