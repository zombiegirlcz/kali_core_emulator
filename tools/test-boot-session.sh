#!/system/bin/sh
# Smoke test boot sessions (docs/superpowers/specs/2026-10-10-boot-sessions-design.md).
# Běží na HOSTU (mksh, app UID), z guestu:
#   ashell -c "sh $FILES/nh/distro/<distro>/root/<repo>/tools/test-boot-session.sh"
# Env: BOOT=<cesta k boot> (default: assets/usr/bin/boot vedle tohoto skriptu),
#      DISTRO=kali|parrot (default: první nainstalované), NH=<cesta k nh>,
#      SKIP_TMUX=1 přeskočí --tmux test.

FILES_DIR=${FILES_DIR:-/data/user/0/com.linux_core/files}
HERE=${0%/*}
BOOT=${BOOT:-$HERE/../app/src/main/assets/usr/bin/boot}
NH=${NH:-$HERE/../app/src/main/assets/nh}
SD=$FILES_DIR/nh/sessions
if [ -z "$DISTRO" ]; then
    for d in kali parrot; do [ -d "$FILES_DIR/nh/distro/$d" ] && { DISTRO=$d; break; }; done
fi
[ -n "$DISTRO" ] || { echo "žádné distro"; exit 2; }
export FILES_DIR

PASS=0 FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS $*"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL $*"; }
check() { # <popis> <očekávané> <skutečné>
    if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: čekáno [$2], je [$3]"; fi
}
B() { sh "$BOOT" "$@"; }

echo "== boot=$BOOT distro=$DISTRO"

# 1–2: shellová syntaxe za --
check "1 roura+&& v jednom argumentu" "b ok" "$(B "$DISTRO" -- "echo a | tr a b && echo ok" 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
check "2 víc argumentů verbatim" "a|b" "$(B "$DISTRO" -- printf '%s' 'a|b' 2>/dev/null)"

# 3: --attach
out=$(B "$DISTRO" --attach=smk 2>&1); rc=$?
check "3 --attach rc" 0 "$rc"
pid=$(echo "$out" | sed -n 's/.*PID=\([0-9]*\).*/\1/p')
sid=$(echo "$out" | sed -n 's/.*SID=\([^ ]*\).*/\1/p')
[ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && ok "3 session žije ($sid)" || bad "3 session nežije: $out"
check "3 name.smk" "$sid" "$(cat "$SD/name.smk" 2>/dev/null)"
check "3 jméno obsazené" 1 "$(B "$DISTRO" --attach=smk >/dev/null 2>&1; echo $?)"

# 4: -p
check "4 roura" 2 "$(B -p smk -- "echo x | wc -c" 2>&1 | tr -d ' ')"
B -p smk -- false; check "4 exit kód false" 1 $?
err=$(B -p "$pid" -- sh -c 'echo e >&2; echo o; exit 7' 2>&1 >/dev/null); rc=$?
check "4 stderr zvlášť" e "$err"; check "4 exit 7 (přes PID)" 7 "$rc"
check "4 stdout zvlášť" o "$(B -p "$sid" -- sh -c 'echo e >&2; echo o' 2>/dev/null)"
check "4 fake host_ipc v D (sessions/<sid>)" yes "$(B -p smk -- "[ -p /run/host_ipc/sessions/$sid/ctl ] && echo yes")"

# 5: souběh
t0=$(date +%s)
B -p smk -- sleep 2 & B -p smk -- sleep 2 & wait
t=$(( $(date +%s) - t0 ))
[ "$t" -lt 4 ] && ok "5 souběh (${t}s)" || bad "5 souběh trval ${t}s"

# 6: -q
o=$(B -p smk -q -- "echo quiet6" 2>&1); rc=$?
check "6 -q bez výstupu" "" "$o"; check "6 -q rc" 0 "$rc"
sleep 2
grep -q '^quiet6$' "$FILES_DIR/tmp/boot-$sid.log" 2>/dev/null && ok "6 výstup v tmp logu" || bad "6 log: $(cat "$FILES_DIR/tmp/boot-$sid.log" 2>&1)"

# 7: Ctrl-C (SIGINT klientovi) zabije příkaz v guestu
sh "$BOOT" -p smk -- sleep 77 & cp=$!   # ne funkce B: & by poslal signál subshellu
sleep 2
kill -INT "$cp"; wait "$cp"; rc=$?
check "7 rc po Ctrl-C" 130 "$rc"
sleep 1
pgrep -f '^sleep 77$' >/dev/null && bad "7 sleep 77 přežil" || ok "7 příkaz zabit"

# 8: --kill
B -p smk --kill >/dev/null 2>&1
sleep 1
kill -0 "$pid" 2>/dev/null && bad "8 session žije po --kill" || ok "8 --kill"
[ -e "$SD/$sid.pid" ] || [ -e "$SD/name.smk" ] || [ -e "$FILES_DIR/ipc/sessions/$sid" ] \
    && bad "8 evidence zůstala" || ok "8 evidence smazaná"

# 9: mrtvé PID
B -p smk -- true >/dev/null 2>&1; check "9 neexistující jméno" 2 $?
echo 999999 > "$SD/dead.1.pid"; echo x > "$SD/dead.1.info"; mkdir -p "$FILES_DIR/ipc/sessions/dead.1"
msg=$(B -p dead.1 -- true 2>&1); rc=$?
check "9 mrtvá session rc" 3 "$rc"
case "$msg" in *neběží*) ok "9 hláška";; *) bad "9 hláška: $msg";; esac
[ -e "$SD/dead.1.pid" ] || [ -e "$FILES_DIR/ipc/sessions/dead.1" ] && bad "9 neuklizeno" || ok "9 uklizeno"

# I/M: fake host_ipc obsahuje jen vlastní session
for m in -i -m; do
    out=$(B "$m" "$DISTRO" --attach=smk$m 2>&1)
    s=$(echo "$out" | sed -n 's/.*SID=\([^ ]*\).*/\1/p')
    check "I/M $m jen vlastní session" "$s" "$(B -p "smk$m" -- "ls /run/host_ipc/sessions")"
    check "I/M $m ipc není vidět" none "$(B -p "smk$m" -- "[ -e /run/host_ipc/su_daemon.pid ] && echo leak || echo none")"
    B -p "smk$m" --kill >/dev/null 2>&1
done

# --attach s prvotním příkazem
out=$(B "$DISTRO" --attach=smkc -- "echo init-cmd" 2>&1)
s=$(echo "$out" | sed -n 's/.*SID=\([^ ]*\).*/\1/p'); sleep 2
grep -q '^init-cmd$' "$FILES_DIR/tmp/boot-$s.log" 2>/dev/null && ok "attach prvotní příkaz" || bad "attach prvotní příkaz: $out"
B -p smkc --kill >/dev/null 2>&1

# 10: nh distro login --tmux (detach přežije)
if [ "${SKIP_TMUX:-0}" != 1 ]; then
    LT=$FILES_DIR/usr/bin/ltmux
    $LT kill-session -t nh-$DISTRO-smk 2>/dev/null
    $LT new-session -d -s smkouter "unset TMUX; sh $NH distro login $DISTRO --tmux=smk"
    sleep 8
    $LT has-session -t "nh-$DISTRO-smk" 2>/dev/null && ok "10 tmux session vznikla" || bad "10 tmux session chybí"
    $LT kill-session -t smkouter 2>/dev/null; sleep 1
    $LT has-session -t "nh-$DISTRO-smk" 2>/dev/null && ok "10 přežila detach" || bad "10 nepřežila detach"
    $LT send-keys -t "nh-$DISTRO-smk" "grep -c ^NAME= /etc/os-release" Enter
    i=0; while [ $i -lt 20 ] && ! $LT capture-pane -p -t "nh-$DISTRO-smk" | grep -qx '1'; do sleep 1; i=$((i+1)); done
    [ $i -lt 20 ] && ok "10 panel běží v guestu" || bad "10 panel: $($LT capture-pane -p -t "nh-$DISTRO-smk" | tail -3)"
    $LT kill-session -t "nh-$DISTRO-smk" 2>/dev/null
fi

echo "== PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ]
