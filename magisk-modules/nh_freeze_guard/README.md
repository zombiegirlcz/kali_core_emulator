# nh_freeze_guard

Magisk modul kombinující **mitigaci SELinux audit floodu** (aktivní vždy) a
**volitelnou UDP telemetrii** pro diagnostiku fatálních zamrznutí zařízení.

Historicky vzniklo sloučením `audit_flood_fix` v1.1 a `nh_freeze_probe` v1.0
(2026-09-30). Kontext v `AGENTS.md §11` (audit flood) a `§13` (UFS resume
failure — první ověřený případ, kdy audit není příčina, ale kdy telemetrie
zachytí to, co ramoops neuvidí).

## Vrstvy

### 1. `auditctl -r 1000` (vždy)

Zvedne `audit_rate_limit` z default 5 msg/s na 1000 msg/s. Bez tohohle
propustí Android auditctl při PRoot spawnu (execve chain, chmod na
`/dev/__properties__`) záznamy takovou rychlostí, že `audit_log_start()`
— synchronní kernelová cesta sdílená všemi procesy — začne blokovat i
`system_server`. Ověřeno: `audit_lost` klesl z 21095 na 403 za boot.

Zdejší Android auditctl podporuje **jen** `-r rate`, ne `-b backlog` ani
`-s` (`Usage: /system/bin/auditctl [-r rate]`, 30. 9. 2026).

### 2. `dontaudit` na property setattr (vždy)

Aplikováno dvakrát — v `sepolicy.rule` během early boot (Magisk to načte
před service skripty) a fallback přes `magiskpolicy --live` v `service.sh`
(pro případ instalace za běhu bez rebootu).

Pokrývá 3778 ze 4514 denials (84 %) z jediného 5,4min měření: proot při
každém startu chmodne `property_info`, `properties_serial`, `*_prop` v
`/dev/__properties__/`; SELinux to vždy zamítne (funkčně správně — proot
tam nemá co měnit), ale každé zamítnutí = audit záznam.

**Primární oprava je v proot binárce** (`tools/modal_build.py`
`_SELINUX_FIX_C` — `__wrap_chmod` přeskakuje `/dev`, kromě dřívějšího
`/proc` a `/sys`). Tenhle modul je druhá vrstva: platí okamžitě bez
rebuildu proot a pokryje i jiné cesty, které by binárku obcházely.

### 3. Sonda s včasnou detekcí stallu (vždy běží)

**Precursor** (incidenty 2026-09-30 a 2026-10-01): I/O tlak roste **desítky
sekund** předtím, než se cokoli projeví na displeji nebo v ANR. 10-01: 70 %
iowait v 28s okně před prvním ANR, `f2fs_ckpt-253:4` zablokovaný, jeden
`kworker` na 56 % kernel času.

Sonda měří **okamžitý** podíl času stallu z PSI `total=` (ne `avg10`, ten
reaguje se zpožděním ~10 s) a při překročení prahu sama přepne do **burstu**:

| režim | perioda | co navíc |
|---|---|---|
| baseline `m=N` | 1 s | — (do pstore jen každý 10. řádek) |
| burst `m=B` | 200 ms | D-state úlohy + `wchan` (~1 s), kernel stacky f2fs vláken a D-úloh, filtrovaný `dmesg` při startu i konci |

Burst se spustí, když je `TRIG_N` vzorků po sobě `io_full` nebo `mem_full`
≥ `TRIG_PCT` %, nebo `procs_blocked` ≥ `TRIG_BLOCKED`. Trvá ještě
`BURST_SECS` po posledním překročení.

**Kde data najít:**

| výstup | přežije | čtení |
|---|---|---|
| `/dev/nh_probe/probe.log` (+ `.1`) | stall, který se sám zotavil (tmpfs = RAM) | `sudo cat /dev/nh_probe/probe.log` |
| `/dev/pmsg0` → pstore | i watchdog reboot | po bootu `sudo cat /sys/fs/pstore/pmsg-ramoops-0` |
| UDP `HOST:PORT` | vše, pokud je server | jen s `HOST=` v konfiguraci |

Rychlé vyhledání incidentu: `sudo grep -E 'TRIGGER|END|dstate|stack' /dev/nh_probe/probe.log`

**Konfigurace** (volitelná, `/data/adb/nh_probe.conf`):

```sh
HOST=              # UDP cíl, prázdné = jen lokálně
PORT=9999
INTERVAL=1
BURST_INTERVAL=0.2
BURST_SECS=30
TRIG_PCT=50        # % času, kdy VŠECHNY úlohy čekaly na I/O (na nečinném telefonu i 1 fsync = 100 %)
TRIG_N=3           # ~3 s v řadě; běžné zápisové špičky trvají 1–2 s
TRIG_BLOCKED=8
PMSG=1
PROBE=0            # úplně vypne sondu
```

**Formát řádků:**

```
nh up=17641.82 m=N io_full=0 io_some=0 mem_full=0 cpu_some=2 iowait=0 blocked=0 load=1.21 procs=2/5287 memav=1376808 dsk_inflight=0 dsk_ioticks=1327840
nh TRIGGER up=… io_full=… mem_full=… iowait=… blocked=…
nh dstate up=… n=3 812:kworker/u16:12:blk_mq_get_tag 753:f2fs_ckpt-253:4:f2fs_write_checkpoint …
nh stack up=… pid=753 comm=f2fs_ckpt-253:4 <__switch_to+…<issue_checkpoint_thread+…
nh kmsg [17562.819963] ufshpb_resume:3189 ufshpb_lu 0 resume. …
nh clock up=17575.01 wall=2026-10-01T08:11:46+0200
nh ufs up=23545.75 h8_en=1 h8_ms=100 cg_pwr=150 cg_perf=150 hpb_rd_dis=0 tw=1
```

`up=` je `/proc/uptime`; `clock` řádky (start, každých 10 min, při burstu)
mapují uptime na reálný čas. `ufs` řádky (start, každých 10 min, TRIGGER, END)
zaznamenávají nastavení UFS linku (`hibern8_on_idle_*`, `clkgate_delay_ms_*`,
HPB, TurboWrite), aby šel každý incident spárovat s tím, co zrovna platilo.

**Pravidla, díky kterým sonda běží i během stallu:**

- **interpret je statický Magisk busybox (ash) zkopírovaný do tmpfs**
  (`/dev/nh_probe/busybox` + `probe.sh`, `ASH_STANDALONE=1`). Do v1.2 běžela
  pod `/system/bin/sh` (mksh) a při incidentu 10-01 09:16 sama vypadla na
  29,5 s: text mksh + bionic libc jsou file-backed stránky na UFS a page fault
  během stallu čeká stejně jako ostatní. Statická binárka v RAM nic z UFS
  nepotřebuje; `date`, `dmesg`, `mkfifo`, `nc` jsou applety bez exec.
  Kopie se musí jmenovat `busybox` (podle `argv[0]` se vybírá applet).
- žádný `exec` v hlavní smyčce: čas z `/proc/uptime`, spánek = `read -t` na
  FIFO, výstup builtin `printf`
- `dmesg`, `date` a čtení sysfs UFS ovladače (může čekat na zámek hosta) jen
  v podprocesu na pozadí
- ash má 64bit aritmetiku, ale úvodní `0` = osmičková soustava → desetinné
  části `/proc/uptime` se převádí trikem `1$f - 100`
- síť se nikdy neobnovuje během burstu (otevření FIFO / exec `nc` by mohlo viset)
- celá smyčka je jeden složený příkaz → shell po startu ze skriptu už nic nečte
- když busybox chybí, `service.sh` spustí sondu pod `/system/bin/sh` (záložní
  varianta, skript je kompatibilní s mksh i ash)

## Příjem na serveru (volitelné)

```sh
socat -u UDP-RECV:9999 - | tee -a nh_probe.log
```

## Ruční spuštění

```sh
ashell -c 'su -c "kill \$(cat /dev/nh_probe/pid); sh /data/adb/modules/nh_freeze_guard/service.sh"'
```

(`service.sh` počká na `sys.boot_completed`, které už platí, a sondu zkopíruje a spustí.)

Test burstu bez skutečného stallu (busybox v testovacím adresáři):
`RUNDIR=/dev/nh_probe_test TRIG_PCT=0 TRIG_N=1 PMSG=0 ASH_STANDALONE=1 /dev/nh_probe_test/busybox sh probe.sh`.
Pod `sudo` (re-entry do PRoot) je každé čtení `/proc` ~10× dražší kvůli ptrace;
čísla o režii měřit jen z Magisk služby na hostu.

## Migrace ze starých modulů

`audit_flood_fix` (id `nh_audit_execute_dontaudit`) a `nh_freeze_probe`
odinstalovat — `nh_freeze_guard` je nahrazuje.
