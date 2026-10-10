# Boot sessions — shellová syntaxe, `--attach`, `-p`, `nh distro login --tmux`

Datum: 2026-10-10 · Stav: **implementováno 2026-10-10** (odchylky v §9) · Rozsah: jen shell (`assets/usr/bin/boot`, `assets/nh`,
nový `tools/test-boot-session.sh`). Nativní kód ani Kotlin se nemění.

## 1. Cíl a kritéria úspěchu

Dnes je každé `boot <distro>` samostatná proot instance, která žije, jen dokud žije její terminál.
Chceme:

1. `boot kali -- "ls | grep x && echo ok"` funguje (shellová syntaxe v jednom argumentu).
2. Session na pozadí (`--attach`), do které jde z hosta posílat příkazy (`-p`) bez startu nového
   proot — levné opakované příkazy a dlouho běžící úlohy, které přežijí zavření terminálu.
3. `nh distro login <distro> --tmux` — interaktivní login, jehož panely přežijí detach.

Úspěch = smoke test `tools/test-boot-session.sh` projde na zařízení a stávající cesty (`sudo` přes
`su_daemon`, `boot <distro>` bez voleb, MainActivity karta Active Sessions) se chovají beze změny.

**Mimo rozsah (V1):** stdin pro příkazy přes `-p` (je `/dev/null`), PTY pro `-p` příkazy, přístup
z jiného UID než toho, kdo session spustil, změny v appce/UI.

## 2. Shellová syntaxe za `--`

Platí pro re-entry větve v `boot_classic` i `boot_docker` (obě dnes dělají `exec "$@"`).

**Pravidlo:** když je za `--` **přesně jeden** argument a obsahuje mezeru nebo metaznak
(`| & ; < > ( ) $ \` * ? [ ] { } ~` nebo tab/newline), **a** v guestu nejde o existující příkaz
(`command -v -- "$1"` selže), spustí se `exec "$GUEST_SH" -c "$1"`. Jinak beze změny `exec "$@"`.

- Víc argumentů → vždy verbatim. `su_daemon` posílá `boot -- <argv…>`, takže `sudo ls -la`,
  `sudo sh -c '…'` atd. se nemění.
- Kontrola `command -v` chrání vzácný případ, kdy je jediný argument skutečná cesta s mezerou.
- Rozhodnutí se dělá **v guest wrapperu** (uvnitř `-c` řetězce), ne na hostu — host nevidí guest
  `PATH` ani soubory. Hodnota jde jako argument, nic se necituje do `-c` řetězce (stejný vzor jako
  `_launch`).
- `sudo "ls | wc -l"` tím začne fungovat (dřív „command not found“) — vědomý vedlejší efekt.

## 3. `boot --attach` — session na pozadí

```
boot [volby] <distro|docker <image>> --attach[=jméno] [-- prvotní příkaz…]
```

### 3.1 Spuštění

`--attach` se rozpozná v `main()` při parsování voleb (před `--`), **před** `cpu_pin_apply`.

1. **Rodič** (volající proces) nic nepinuje ani nebootuje. Vytvoří handshake soubor
   `nh/sessions/attach.<rodič-PID>.ready`, spustí sám sebe odpojeně:
   `setsid "$0" <původní volby> <distro> --attach-child=<rodič-PID>[,jméno] [-- …] </dev/null >log 2>&1 &`
   a čeká (polling po 0,2 s), až dítě zapíše do `.ready` svoje `sid`, nebo až dítě umře.
   Timeout se nedává — první start může spouštět bootstrap (minuty); místo toho rodič
   vypisuje „čekám na bootstrap…“ a končí, jakmile dítě umře (pak vytiskne konec logu a vrátí 1).
2. **Dítě** běží normální cestou (`apply_mode`, `cpu_pin_apply`, bindy, bootstrap). Session id
   `sid=<distro>.<PID dítěte>` (stávající `nh_session_id`). Před `exec` do proot:
   - vytvoří session adresář `$FILES_DIR/ipc/sessions/<sid>/` (0700) s FIFO `ctl` a adresářem `req/`,
   - zapíše `.pid`/`.info` jako dnes; `.info` navíc `attach=1 name=<jméno>`,
   - u jména zapíše `nh/sessions/name.<jméno>` = `<sid>`; když jméno už patří **živé** session,
     skončí chybou „jméno obsazené“ (mrtvý záznam přepíše),
   - zapíše `<sid>` do `.ready`,
   - zajistí, že ho guest vidí jako `/run/host_ipc/sessions/<sid>` (viz 3.4),
   - `exec` proot s inline command serverem jako prvním procesem (viz 3.2).
3. Rodič vytiskne `PID=<pid> SID=<sid>[ NAME=<jméno>]` a vrátí 0.

Prvotní příkaz za `--` se po startu pošle jako první požadavek v quiet režimu (`-q`, viz §4).

### 3.2 Inline command server (guest)

Předává se jako `-c` řetězec guest shellu, do rootfs se nic nenasazuje. Pseudokód:

```sh
unset LD_PRELOAD PROOT_LOADER PROOT_TMP_DIR LD_LIBRARY_PATH; export PATH="$1"
S=/run/host_ipc/sessions/$2
set -m 2>/dev/null || true          # každá úloha vlastní process group (kvůli kill)
exec 3<>"$S/ctl"                    # otevřeno RW → read nikdy nedostane EOF
while read -r op rid <&3; do
  case "$op" in
    run)  d="$S/req/$rid"
          ( cd "$(cat "$d/cwd" 2>/dev/null || echo /root)" 2>/dev/null
            "$0" -c "$(cat "$d/cmd")" </dev/null >"$d/out" 2>"$d/err"
            echo $? >"$d/rc.tmp"; mv "$d/rc.tmp" "$d/rc" ) &
          echo $! >"$d/pid" ;;
    kill) [ -f "$S/req/$rid/pid" ] && kill -TERM -"$(cat "$S/req/$rid/pid")" 2>/dev/null \
            || kill -TERM "$(cat "$S/req/$rid/pid")" 2>/dev/null ;;
    exit) break ;;
  esac
done
```

- Požadavky běží **souběžně** (každý na pozadí).
- Proot má `--kill-on-exit`: dokud server žije, žije session; `exit` serveru ukončí vše.
- Řádek na `ctl` je `run <rid>` / `kill <rid>` / `exit` — vždy < `PIPE_BUF`, tedy atomický zápis
  i při souběžných klientech.
- Server má jediný režim (FIFO `out`/`err`). Quiet řeší klient na hostu (§4), server o něm neví.
- `$2` = sid (předává se jako argument wrapperu, necituje se do `-c`).

### 3.3 Evidence a úklid

| Soubor | Obsah |
|---|---|
| `nh/sessions/<sid>.pid`, `.info` | jako dnes (čte MainActivity, filtr `*.pid`) |
| `ipc/sessions/<sid>/` | `ctl` (FIFO), `req/<rid>/` |
| `tmp/boot-<sid>.log` | výstup `-q` požadavků |
| `nh/sessions/name.<jméno>` | `<sid>` |

Úklid mrtvých sessions (`nh_session_start`, stávající smyčka `kill -0`) navíc maže `ipc/sessions/<sid>/`, `tmp/boot-<sid>.log` a
`name.*`, které na mrtvé `sid` ukazují. Zabití session z UI (kill PID proot) tedy nechá jen
sirotčí adresář, který zmizí při dalším startu nebo při `-p` na tu session.

> Odchylka od schváleného směru: `ctl` a `req/` nejsou v `nh/sessions/` vedle `.pid`, ale v jednom
> adresáři pod `ipc/` — guest ho potřebuje vidět přes **jeden** bind a pod `/run/host_ipc`.

### 3.4 `/run/host_ipc` ve všech módech („fake host_ipc“)

- **D:** beze změny — `-b $FILES_DIR/ipc:/run/host_ipc` už existuje, session adresář je vidět sám.
- **I / M:** celé `ipc` se **nebinduje** (leží v něm `su_daemon`/magisk sockety a logy, porušilo by
  to izolaci). `build_binds` dostane parametr se sid a pro attach session přidá jen
  `-b $FILES_DIR/ipc/sessions/<sid>:/run/host_ipc/sessions/<sid>` — guest tak vidí „fake
  host_ipc“ obsahující jen vlastní session, analogicky k fake `/proc` a `/sys` (proot si
  mezilehlé adresáře `/run/host_ipc/sessions` vytvoří virtuálně).
- Neattach session (obyčejné `boot <distro>`) v I/M dál `/run/host_ipc` nemá — beze změny chování.

## 4. `boot -p` — příkaz do běžící session

```
boot -p|--pid <pid|sid|jméno> [-q|--quiet] -- příkaz…
boot -p <pid|sid|jméno> --kill
```

Klient běží na hostu (mksh), nebootuje nic a nepinuje CPU (rozpozná se v `main()` hned na začátku).

1. **Resolve:** číslo → najdi `*.pid` s tímto obsahem; jinak `name.<x>` → sid; jinak `<x>.pid`.
   Nenalezeno → `boot: session '<x>' neexistuje`, exit 2.
2. **Živost:** `kill -0 <pid>`; mrtvá → `boot: session <sid> neběží (PID <pid>), uklízím`, smaže
   `.pid/.info/.d/name.*`, exit 3. Chybí `ctl` nebo není FIFO → stejná hláška.
3. **Příkaz:** argumenty za `--`. Jeden argument → použije se jako shellový řetězec (to samé
   pravidlo jako v §2 — tady vždy, protože server spouští `sh -c`). Víc argumentů → klient je
   složí do jednoho řetězce s bezpečným quotingem (`'…'` + `'\''`), takže `boot -p x -- ls -la "a b"`
   dělá totéž co přímé spuštění.
4. **Požadavek:** `rid=<PID klienta>.<čítač>`; `mkdir ipc/sessions/<sid>/req/<rid>`, zapiš `cmd`, `cwd`
   (aktuální `$PWD` klienta, jen když existuje i v guestu — jinak `/root`);
   `mkfifo out err`. Pak `echo "run $rid" > ctl`.
5. **Streamování:** `cat out` na pozadí → stdout, `cat err` → stderr, `wait`. Pak počká na `rc`
   (krátký polling, rc se zapisuje přes `mv`, takže je vždy celý) a skončí s tímto kódem.
   Úklid `req/<rid>`.
6. **Quiet (`-q`) = bez výstupu na terminál:** klient se po zápisu `run` odpojí (`setsid … &`)
   a odpojená část vyčte `out`+`err` do `$FILES_DIR/tmp/boot-<sid>.log` (append, hlavička
   `--- <rid> <cmd>`, na konci `--- <rid> rc=N`), pak uklidí `req/<rid>`. Popředí hned vrátí 0
   a vypíše jen cestu k logu na stderr (`-qq` ani to ne — neimplementovat, dokud nebude potřeba).
7. **Ctrl-C:** `trap` na INT/TERM/HUP v klientovi → `echo "kill $rid" > ctl`, počká na `rc`
   (max 3 s), exit 130.
8. **`--kill`:** `echo exit > ctl`; počká až 3 s na smrt PID, jinak `kill -TERM <pid>`;
   pak úklid evidence. Exit 0.

**Známé limity V1:** stdin = `/dev/null`; příkaz, který nechá na pozadí proces se zděděným
stdout (daemon bez přesměrování), drží FIFO otevřené → klient čeká, dokud ten proces nezavře
výstup (stejně jako ssh). `-p` funguje jen ze stejného UID, které session spustilo (app UID,
nebo root pro session spuštěnou pod `sudo`) — FIFO mají 0600.

## 5. `nh distro login <distro> --tmux[=jméno]`

V `distro_login` (`assets/nh`) nová volba `--tmux[=jméno]`:

```sh
exec env LTMUX_SHELL="$FILES_DIR/usr/bin/boot-tmux-shell" \
     ltmux new-session -A -s "nh-$DISTRO${NAME:+-$NAME}" \
           -e NH_TMUX_DISTRO="$DISTRO" -e NH_EXTRA_BINDS="$EXTRA_BINDS"
```

- tmux server běží **na hostu** (glibc tmux z rootfs přes `elf_loader`, viz `ltmux`), proot je jen
  proces v panelu → po detachi (`C-b d`) panely i proot běží dál; `nh distro login kali --tmux`
  znovu se připojí (`-A`).
- `LTMUX_SHELL` musí být jedna cesta (tmux ji spouští jako `$SHELL`), proto wrapper
  `boot-tmux-shell`. Není to nový asset: `nh` ho při `--tmux` (idempotentně, když chybí nebo se
  liší) zapíše do `$FILES_DIR/usr/bin/` s tímto obsahem:

  ```sh
  #!/system/bin/sh
  B=/data/user/0/com.linux_core/files/usr/bin/boot
  case "$NH_TMUX_DISTRO" in
      docker/*) exec "$B" docker "${NH_TMUX_DISTRO#docker/}" ;;
      ?*)       exec "$B" "$NH_TMUX_DISTRO" ;;
      *)        exec /system/bin/sh ;;
  esac
  ```

- Distro a bindy jdou přes **session prostředí** (`new-session -e`, tmux ≥ 3.0), které dědí každý
  panel té session — nová okna/panely (`C-b c`, `C-b %`) tak spouštějí `boot` se stejným distrem
  a různé tmux sessions můžou mít různá distra na jednom serveru. `NH_EXTRA_BINDS` čte `boot` sám.
- `docker/<image>` se mapuje na `boot docker <image>` stejně jako dnes.
- Chybí `ltmux` nebo tmux v rootfs → srozumitelná chyba (hláška z `ltmux`), exit 127.

## 6. Testy — `tools/test-boot-session.sh`

Smoke skript spouštěný na zařízení přes `ashell -c` (host, app UID). Každý krok PASS/FAIL, na konci
souhrn a nenulový exit při chybě. Uklízí po sobě (`--kill` všech svých sessions).

1. `boot kali -- "echo a | tr a b && echo ok"` → `b`, `ok`.
2. `boot kali -- ls -la /` (víc argumentů) → verbatim, exit 0.
3. `boot kali --attach=t1` → vypíše PID/SID, `name.t1` existuje, `.pid` živý.
4. `boot -p t1 -- "echo x | wc -c"` → `2`; `boot -p t1 -- false` → exit 1;
   `boot -p t1 -- sh -c 'echo e >&2; exit 7'` → stderr `e`, exit 7.
5. Souběh: dva `-p` s `sleep 2` paralelně dokončí za < 4 s.
6. `-q`: `boot -p t1 -q -- "echo quiet"` vrátí 0 hned, po chvíli je `quiet` v `tmp/boot-<sid>.log`;
   v I a M módu `ls /run/host_ipc` v guestu ukáže jen `sessions/<sid>`.
7. Ctrl-C: `boot -p t1 -- sleep 100 &`, po 1 s `kill -INT` klienta → exit 130, `sleep` v guestu
   do 3 s zmizí.
8. `boot -p t1 --kill` → PID mrtvý, evidence smazaná.
9. Mrtvé PID: `boot -p t1 -- true` → exit 3 s hláškou; podvržený mrtvý `.pid` se uklidí.
10. `--tmux`: `nh distro login kali --tmux=t` v detached režimu (`ltmux new-session -d …`),
    `ltmux send-keys` + `capture-pane` ukáže guest prompt; `kill-session` po testu.
11. Regrese sudo: `sudo true` z guestu, pokud je root k dispozici (jinak SKIP).

## 7. Dotčené soubory

- `app/src/main/assets/usr/bin/boot` — `main()` (volby `--attach`, `--attach-child`, `-p`, `-q`,
  `--kill`), re-entry wrappery v `boot_classic`/`boot_docker`, nové funkce `session_attach_parent`,
  `session_prepare`, `session_client`, `session_resolve`, rozšíření `nh_session_start` o úklid,
  usage/`--help`.
- `app/src/main/assets/nh` — `distro_login` (`--tmux`), nápověda.
- `tools/test-boot-session.sh` — nový.
- `AGENTS.md` §11 — krátký odstavec o boot sessions (protokol `ctl`, fake `/run/host_ipc` v I/M).
- Paměť `fix_tmux_not_a_terminal.md` — aktualizovat (tmux v módu D funguje).

## 8. Rozhodnutí

1. ~~`-q`~~ potvrzeno: bez výstupu do `tmp/boot-<sid>.log`, nečeká, hned vrací 0.
2. Log `tmp/boot-<sid>.log` je V1 bez rotace; maže se při úklidu mrtvé session.

## 9. Odchylky implementace od návrhu

- **Ctrl-C bez `kill` operace na `ctl`:** guest procesy jsou obyčejné procesy hosta se stejným UID
  (proot = ptrace), takže klient zabije strom příkazu přímo (`pgrep -P` rekurzivně, TERM → po 3 s
  KILL). Server nepotřebuje job control (`set -m` v dash/busybox bez tty nefunguje). Protokol `ctl`
  má jen `run <rid>` a `exit`.
- **Příkaz se spouští jako skript** `"$GUEST_SH" req/<rid>/cmd` (ne `sh -c "$(cat cmd)"`) — bez
  dalšího `cat` execu a bez problémů s quotingem.
- **`-q` nic nevypisuje** (ani cestu k logu) — „bez výstupu“.
- **Re-exec přes `/system/bin/sh "$0"`** (attach dítě, `-q` drain) — funguje i bez exec bitu
  (kopie v repu) a nezávisí na `PATH`.
- `rid` = `<PID klienta>.<$RANDOM>` (PID odpojeného `-q` klienta se může recyklovat).
- `--tmux` nastavuje navíc `default-shell` volbu session, protože když už tmux server běží
  (jiné `ltmux`), `LTMUX_SHELL` se neuplatní.
