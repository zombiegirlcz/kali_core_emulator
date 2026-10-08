#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
unset LD_PRELOAD
# === [NetHunter] UTF-8 locale fix ===
mkdir -p /etc /etc/profile.d
if [ ! -f /etc/.nethunter_locale_done ]; then
    if [ -f /etc/locale.gen ]; then
        grep -q 'C.UTF-8' /etc/locale.gen 2>/dev/null || printf 'C.UTF-8 UTF-8\n' >> /etc/locale.gen
        grep -q 'en_US.UTF-8' /etc/locale.gen 2>/dev/null || printf 'en_US.UTF-8 UTF-8\n' >> /etc/locale.gen
    else
        printf 'C.UTF-8 UTF-8\nen_US.UTF-8 UTF-8\n' > /etc/locale.gen 2>/dev/null || true
    fi
    if command -v locale-gen >/dev/null 2>&1; then
        locale-gen C.UTF-8 en_US.UTF-8 >/dev/null 2>&1 || true
    elif command -v localedef >/dev/null 2>&1; then
        localedef -i C -f UTF-8 C.UTF-8 >/dev/null 2>&1 || true
        localedef -i en_US -f UTF-8 en_US.UTF-8 >/dev/null 2>&1 || true
    fi
    touch /etc/.nethunter_locale_done 2>/dev/null || true
fi
# /etc/default/locale doplnit jen pokud chybi/je prazdne (neresetujem uzivatelovo nastaveni)
if [ ! -s /etc/default/locale ] || ! grep -q '^LANG=' /etc/default/locale 2>/dev/null; then
    if command -v update-locale >/dev/null 2>&1; then
        update-locale LANG=C.UTF-8 LC_CTYPE=C.UTF-8 >/dev/null 2>&1 || true
    else
        printf 'LANG=C.UTF-8\nLC_CTYPE=C.UTF-8\n' > /etc/default/locale 2>/dev/null || true
    fi
fi
cat > /etc/profile.d/nethunter-locale.sh << 'NLOC_EOF'
# NetHunter: UTF-8 locale pro vsechny login shelly (bash/zsh/sh)
export LANG=C.UTF-8
export LC_CTYPE=C.UTF-8
NLOC_EOF
chmod 644 /etc/profile.d/nethunter-locale.sh 2>/dev/null || true
export LANG=C.UTF-8
export LC_CTYPE=C.UTF-8
# === [NetHunter] Password fix: smazat auto-hesla + zapisovatelny /etc/shadow ===
chmod 600 /etc/shadow /etc/gshadow 2>/dev/null || true
chmod 644 /etc/passwd /etc/group 2>/dev/null || true
if [ ! -f /etc/.nethunter_password_reset_done ]; then
    for u in root kali parrot; do
        if id "$u" >/dev/null 2>&1; then
            awk -F: -v u="$u" 'BEGIN{OFS=":"} $1==u{$2=""} {print}' /etc/shadow > /tmp/.nh_shadow 2>/dev/null && cat /tmp/.nh_shadow > /etc/shadow 2>/dev/null; rm -f /tmp/.nh_shadow
        fi
    done
    touch /etc/.nethunter_password_reset_done 2>/dev/null || true
fi
# printf (ne `echo -e`): entrypoint bezi i pod POSIX sh/dash/busybox, kde echo nezna
# -e -> do resolv.conf se jinak zapise literalni "-e nameserver ...\n..." a DNS je rozbite.
printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > /etc/resolv.conf 2>/dev/null || true
# Zámky dpkg mazat jen když neběží apt/dpkg (jiná session) — jinak hrozí poškození dpkg db.
if ! pgrep -x 'apt|apt-get|dpkg|aptitude' >/dev/null 2>&1; then
  rm -f /var/lib/dpkg/lock* 2>/dev/null || true
fi
# Restore passwd if it was previously diverted by mistake
for prefix in /usr/sbin /sbin /usr/bin /bin; do
  path="$prefix/passwd"
  if [ -L "$path" ] && [ -f "$path.distrib" ]; then
    rm -f "$path"
    dpkg-divert --remove --local --rename "$path" 2>/dev/null || true
  fi
done
setup_user_zsh() {
    local target_home="$1"
    local user_name="$2"
    local zrc="$target_home/.zshrc"
    [ ! -d "$target_home" ] && return
    # Zkopiruje se optimalizovany zshrc pouze pokud neexistuje
    if [ ! -f "$zrc" ]; then
        if [ -f /etc/skel/.zshrc.nethunter ]; then
            cp /etc/skel/.zshrc.nethunter "$zrc"
        elif [ -f /etc/skel/.zshrc ]; then
            cp /etc/skel/.zshrc "$zrc"
        fi
    fi
    # Clean old fragments
    grep -v -e 'NetHunter AI Operator' -e 'FORCE_ZSH_' -e 'source /etc/nethunter.zshrc' "$zrc" > /tmp/.nh_zrc 2>/dev/null || true
    cat /tmp/.nh_zrc > "$zrc" 2>/dev/null || true
    rm -f /tmp/.nh_zrc 2>/dev/null || true
    [ -n "$user_name" ] && chown "$user_name:$user_name" "$zrc" 2>/dev/null || true
}
setup_user_zsh /root root
[ -d /home/parrot ] && setup_user_zsh /home/parrot parrot
[ -d /home/kali ] && setup_user_zsh /home/kali kali
chmod 4755 /usr/bin/sudo /usr/bin/su /bin/su /bin/sudo 2>/dev/null || true
# Dropbear se automaticky NESPOUŠTÍ (naslouchal na všech rozhraních, účty bez hesla).
# Ručně: dropbear -p 127.0.0.1:2222
[ -f /etc/motd ] && cat /etc/motd
echo '[*] Starting session...'
ENTRY_SHELL=$(command -v zsh || echo /bin/bash)
if [ $# -gt 0 ]; then
    exec "$ENTRY_SHELL" -c "$*"
else
    exec "$ENTRY_SHELL" --login
fi
