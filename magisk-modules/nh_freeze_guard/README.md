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

### 3. UDP telemetrie (volitelná)

Spustí se **jen pokud existuje `/data/adb/nh_probe.conf`**:

```sh
cat > /data/adb/nh_probe.conf <<'EOF'
HOST=192.168.1.10
PORT=9999
INTERVAL=1
TAG=nh
EOF
```

Bez konfigurace se probe.sh neaktivuje — vrstvy 1 a 2 platí dál.

**Formát:**

```
nh t=1790500000 psi_cpu=12.34 psi_io_some=3.21 psi_io_full=0.50 load=4.21 procs=1/4520 memav=1234567 dsk_inflight=0 dsk_ioticks=362720
nh t=1790500010 audit_backlog_hits=0 audit_lost=403 avc_tail400=312 seccomp_tail400=0
```

Každou sekundu jeden řádek; každý desátý navíc řádek se stavem audit fronty.

**Klíčová pole u pádu:**

| pole | co znamená |
|---|---|
| `psi_io_full` | **nejdůležitější.** K 100 = všechny úlohy čekají na I/O → zaseknutý storage subsystem |
| `dsk_inflight` | roste a neklesá = požadavky na disk se nevyřizují (UFS resume failure) |
| `dsk_ioticks` | roste = zařízení stráví čas v I/O, ale bez pokroku |
| `psi_cpu` | vysoké při CPU hladovění (PRoot ptrace overhead) |
| `memav` | prudký propad = tlak na paměť, hrozí lmkd |
| `audit_backlog_hits` | >0 = přetéká audit fronta (`audit_log_start()` blokuje volající) |
| `seccomp_tail400` | >0 = seccomp trap enforcement (potenciálně `elf_loader` Go filtr) |

Poslední přijatý řádek před výpadkem ukazuje, která z těch os spadla první —
přesně informace, která po pádech 26., 27. a 30. 9. chyběla lokálně.

## Příjem na serveru

```sh
socat -u UDP-RECV:9999 - | tee -a nh_probe.log            # nejlepší
nc -u -l -p 9999 | tee -a nh_probe.log                    # busybox/netcat
python3 -c 'import socket
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind(("0.0.0.0",9999))
while 1:
    d,_=s.recvfrom(65535); print(d.decode().rstrip(),flush=True)' | tee -a nh_probe.log
```

## Poznámky k návrhu

- **UDP, ne TCP:** TCP `write()` by se při umírajícím I/O nebo síti zablokoval
  právě v okamžiku, který nás zajímá. Ztracený datagram nevadí, každý vzorek
  stojí samostatně.
- **Na hostu, ne v proot:** každý start proot sám generuje stovky SELinux
  audit záznamů (viz vrstva 2) — monitor uvnitř by zhoršoval to, co má měřit.
  Čte jen `/proc`, forkuje jediné `nc` na celou smyčku.
- **Žádný zápis na disk:** sonda schválně nic neloguje lokálně, aby přežila
  I/O výpadek. Vše jde přes síť.

## Ruční spuštění probe bez modulu

```sh
sudo /data/adb/modules/nh_freeze_guard/probe.sh &
```

## Migrace ze starých modulů

Pokud jsou nainstalované `audit_flood_fix` (id `nh_audit_execute_dontaudit`)
nebo `nh_freeze_probe`, odinstaluj je v Magisk Manageru a flashni jen tenhle
modul — jeho id `nh_freeze_guard` je nové, nekonflikt.
