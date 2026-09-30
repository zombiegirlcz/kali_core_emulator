# nh_freeze_probe

Telemetrie pro diagnostiku **fatálních zamrznutí**, u kterých zařízení ztratí
přístup k úložišti — pak se lokálně neuloží nic (`logcat` je jen v RAM,
`/data/anr` a tombstony se nezapíšou, `dmesg` hard reboot smaže). Jediná cesta,
jak data získat, je posílat je průběžně ven.

## Konfigurace

```sh
cat > /data/adb/nh_probe.conf <<'EOF'
HOST=192.168.1.10
PORT=9999
INTERVAL=1
TAG=nh
EOF
```

Bez tohoto souboru se sonda **nespustí** (aby modul nikomu neposílal data omylem).

## Příjem na serveru

```sh
socat -u UDP-RECV:9999 - | tee -a nh_probe.log            # nejlepší
nc -u -l -p 9999 | tee -a nh_probe.log                    # busybox/netcat
python3 -c 'import socket
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind(("0.0.0.0",9999))
while 1:
    d,_=s.recvfrom(65535); print(d.decode().rstrip(),flush=True)' | tee -a nh_probe.log
```

## Formát

```
nh t=1790500000 psi_cpu=12.34 psi_io_some=3.21 psi_io_full=0.50 load=4.21 procs=1/4520 memav=1234567 dsk_inflight=0 dsk_ioticks=362720
nh t=1790500010 audit_backlog_hits=0 audit_lost=403 avc_tail400=312 seccomp_tail400=0
```

Každou sekundu jeden řádek; každý desátý navíc řádek se stavem audit fronty.

## Co u pádu sledovat

| pole | co znamená |
|---|---|
| `psi_io_full` | **nejdůležitější.** Jde k 100 = všechny úlohy čekají na I/O → zaseknuté úložiště |
| `dsk_inflight` | roste a neklesá = požadavky na disk se nevyřizují |
| `psi_cpu` | vysoké při CPU hladovění (PRoot ptrace overhead) |
| `memav` | prudký propad = tlak na paměť, hrozí lmkd |
| `audit_backlog_hits` | >0 = přetéká audit fronta (`audit_log_start()` blokuje volající) |
| `seccomp_tail400` | >0 = `elf_loader` Go filtr generuje audit záznamy (zatím neprokázáno) |

Poslední přijatý řádek před výpadkem ukazuje, která z těch os spadla první —
to je přesně informace, která dosud po každém pádu chyběla.

## Poznámky k návrhu

- **UDP, ne TCP:** TCP `write()` by se při umírajícím I/O nebo síti zablokoval
  právě v okamžiku, který nás zajímá. Ztracený datagram nevadí, každý vzorek
  stojí samostatně.
- **Na hostu, ne v prootu:** každý start prootu sám generuje stovky SELinux
  audit záznamů (viz `AGENTS.md`, audit bouře) — monitor by zhoršoval to,
  co má měřit. Čte jen `/proc`, forkuje jediné `nc` na celou smyčku.
- **Žádný zápis na disk:** sonda schválně nic neloguje lokálně.

## Ruční spuštění bez instalace modulu

```sh
ashell -c '/data/adb/nh_freeze_probe/probe.sh &'
```
