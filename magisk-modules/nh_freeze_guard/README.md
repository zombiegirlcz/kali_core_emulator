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
```

`up=` je `/proc/uptime`; `clock` řádky (start, každých 10 min, při burstu)
mapují uptime na reálný čas.

**Pravidla, díky kterým sonda běží i během stallu:**

- žádný `exec` v hlavní smyčce: čas z `/proc/uptime`, spánek = `read -t` na FIFO
- `date`/`dmesg` (exec z `/system`, taky na UFS) jen v podprocesu na pozadí
- žádné here-docy (mksh je zapisuje do dočasného souboru na disk)
- mksh na Androidu má **32bit aritmetiku** → kumulativní čítače se ořezávají
  na 9 číslic, delta modulo 1e9; celkové jiffies = `DCS × NCPU`
- síť se nikdy neobnovuje během burstu (otevření FIFO / exec `nc` by mohlo viset)
- celá smyčka je jeden složený příkaz → shell po startu ze skriptu na `/data`
  už nic nečte

## Příjem na serveru (volitelné)

```sh
socat -u UDP-RECV:9999 - | tee -a nh_probe.log
```

## Ruční spuštění

```sh
sudo /system/bin/sh /data/adb/modules/nh_freeze_guard/probe.sh &
```

Test burstu bez skutečného stallu: `RUNDIR=/dev/nh_probe_test TRIG_PCT=0 TRIG_N=1 PMSG=0`.
Pod `sudo` (re-entry do PRoot) je každé čtení `/proc` ~10× dražší kvůli ptrace;
čísla o režii měřit jen z Magisk služby na hostu.

## Migrace ze starých modulů

`audit_flood_fix` (id `nh_audit_execute_dontaudit`) a `nh_freeze_probe`
odinstalovat — `nh_freeze_guard` je nahrazuje.
