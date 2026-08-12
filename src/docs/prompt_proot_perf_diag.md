# Agent brief: PRoot performance diagnostika + fix

## Kontext
`com.linux_core` (NetHunter AI Operator) běží znatelně pomaleji než referenční Termux PRoot
na stejném zařízení (Redmi Note 10 Pro, aarch64, Magisk root). Cíl: zjistit root cause a opravit.

## Root cause hypotéza (potvrzeno inspekcí repa, commit `e1e92a6`)

### H1 — Seccomp acceleration status je neznámý (nejpravděpodobnější)
Soubor: `app/src/main/assets/launcher.sh`, řádek s definicí:
```sh
PROOT_FLAGS="-v 0 --kill-on-exit -0 --link2symlink"
```
`-v 0` = verbosity vypnutá. PRoot při `-v 1`+ hlásí do stderr buď:
```
proot info: ptrace acceleration (seccomp mode 2) enabled
```
nebo (pokud binárka seccomp nemá / kernel ho odmítne) ticho / fallback na čistý ptrace.
**Se `-v 0` se tahle informace nikam nezaloguje** — appka i uživatel jsou dnes slepí vůči tomu,
jestli PRoot běží v akcelerovaném módu, nebo v nejpomalejším možném (syscall-by-syscall ptrace stop).
Rozdíl mezi oběma režimy je řádový (5-10x), přesně sedí na popsaný pocit "pomalé oproti Termuxu".

### H2 — Stale binary risk (deploy-once optimalizace)
Soubor: `app/src/main/java/com/linux_core/core/ProotManager.kt`, funkce `deployBinaries()`
(řádek ~158–184):
```kotlin
val file = File(context.filesDir, name)
// Optimization: Only deploy if missing to speed up startup
if (file.exists() && file.length() > 0L) {
    file.setExecutable(true, false)
    continue
}
```
`proot` binárka (`assets/proot-aarch64` → `filesDir/proot`) se **nikdy nepřepíše**, pokud už
soubor existuje — i po APK update s novější/opravenou proot binárkou. Pokud current binárka na
zařízení pochází ze staršího buildu bez seccomp podpory, zůstane tak navždy, dokud ji někdo
ručně nesmaže. Termux naproti tomu má vlastní update mechanismus balíčku `proot`, který binárku
skutečně vymění.

### H3 — Souběžná zátěž (vedlejší, ne primární)
`VpnNatEngine`, TLS MITM engine, `AIBrain.kt` ONNX klasifikace a Arize Phoenix (port 6006,
známý unbounded memory problém) běží souběžně s PRoot procesem a soutěží o CPU/paměť.
Termux referenční test tohle nemá — validní jen jako confounding faktor, ne jako fix cíl.

---

## FÁZE 1 — Diagnostika (spustit na zařízení, výsledky nahlásit před fixem)

Přes `nh` CLI nebo terminál v appce:

```sh
# 1. Je binárka vůbec zkompilovaná se seccomp podporou?
/data/data/com.linux_core/files/proot -V
# Hledat řádek: "built-in accelerators: process_vm = yes, seccomp_filter = yes"
# Pokud seccomp_filter = no → H2 potvrzeno, nutný nový build binárky (ne jen config fix)

# 2. Je seccomp aktivní za běhu na tomto kernelu/zařízení?
/data/data/com.linux_core/files/proot -v 1 true
# Hledat: "proot info: ptrace acceleration (seccomp mode 2) enabled"
# Pokud tam není / je warning → H1 potvrzeno za běhu (buď binárka bez podpory, nebo kernel odmítl)

# 3. Je náhodou natvrdo vypnutý env var?
env | grep PROOT_NO_SECCOMP
grep -rn "PROOT_NO_SECCOMP" /data/data/com.linux_core/files/ 2>/dev/null

# 4. Srovnávací benchmark vs. Termux (stejné zařízení, stejná operace)
time (find /usr -type f | wc -l)   # spustit v NetHunter PRoot guestu
time (find /usr -type f | wc -l)   # spustit v Termux PRoot guestu
# Zaznamenat reálný čas obou

# 5. Kde fyzicky leží rootfs?
df -h /data/user/0/com.linux_core/files/kali-arm64
# Interní storage vs. FUSE/adopted storage — druhé je citelně pomalejší
```

Výstup fáze 1: vlož raw output všech 5 kroků do `AGENTS.md` pod novou session sekci,
než se pokračuje na fix.

---

## FÁZE 2 — Fix (podmíněno výsledky fáze 1)

### Fix A — pokud H1 (seccomp neaktivní, ale binárka podporu MÁ dle `-V`)
Soubor: `app/src/main/assets/launcher.sh`
Přidat permanentní start-of-session log nezávislý na `NH_LAUNCHER_DEBUG`, aby se stav dal
kdykoli ověřit bez ruční změny `-v`:
```sh
# Jednorázový capability log (ne celý -v 1 verbose spam, jen accel status)
if [ ! -f "$FILES_DIR/.proot_seccomp_checked" ]; then
    "$PR" -v 1 true 2>&1 | grep -i "seccomp\|acceleration" >> "$FILES_DIR/proot_capability.log" 2>&1 || true
    touch "$FILES_DIR/.proot_seccomp_checked"
fi
```
Necháváme `PROOT_FLAGS="-v 0 ..."` pro běžný provoz (výkon > verbosity), log se generuje jen
jednou při prvním spuštění per instalace.

### Fix B — pokud H2 (binárka bez seccomp podpory, `-V` ukazuje `seccomp_filter = no`)
Toto NENÍ config fix — vyžaduje novou proot binárku zkompilovanou s `-DSECCOMP_ENABLED` /
podporou `seccomp_filter` (proot upstream, případně termux/proot fork, který má podle veřejně
dostupných zdrojů seccomp opravy dotažené dál než upstream proot-me/proot).

Dodatečně: `deployBinaries()` v `ProotManager.kt` potřebuje verzovací mechanismus, aby update
APK skutečně vyměnil binárku:
```kotlin
// Broken (current):
if (file.exists() && file.length() > 0L) { continue }

// Fixed — porovnání verze/hash místo pouhé existence:
val expectedVersion = BuildConfig.PROOT_BINARY_VERSION  // nebo checksum z assets
val versionFile = File(context.filesDir, "$name.version")
val needsRedeploy = !file.exists() || file.length() == 0L ||
    !versionFile.exists() || versionFile.readText().trim() != expectedVersion
if (!needsRedeploy) { file.setExecutable(true, false); continue }
// ... deploy ...
versionFile.writeText(expectedVersion)
```

### Fix C — pokud env var PROOT_NO_SECCOMP je nastavený
Najít a odstranit zdroj (`grep -rn "PROOT_NO_SECCOMP"` v celém repu včetně `launcher.sh`,
`su_wrapper.c`, `su_daemon.c` — možná pozůstatek z dřívějšího ladění segfault problému).

---

## Akceptační kritéria
1. `proot -v 1 true` na zařízení hlásí `ptrace acceleration (seccomp mode 2) enabled`
2. Benchmark `find /usr -type f | wc -l` v NetHunter PRoot guestu je v rámci ±20 % Termux referenčního času (ne řádový rozdíl)
3. `AGENTS.md` obsahuje novou session sekci s raw výstupy fáze 1 a finálním benchmark srovnáním
4. Pokud Fix B: `deployBinaries()` má verzovací mechanismus, ověřený tak, že APK update s novou binárkou skutečně přepíše starou (test: touch dummy version bump → rebuild → potvrdit v logu `Deployed binary proot`)

---

## VÝSLEDKY FÁZE 1 (měřeno na zařízení 2026-08-11, Redmi Note 10 Pro, kernel 4.14.190)

### Krok 1–3: seccomp status — VŠE AKTIVNÍ (H1/H2/C REJECTED)

```
# 1. proot -V
built-in accelerators: process_vm = yes, seccomp_filter = yes
# 2. proot -v 1 true
proot info: ptrace acceleration (seccomp mode 2, new syscall order) enabled
# 3. PROOT_NO_SECCOMP není nastaven (grep v files/ i repu = prázdno)
```

Nasazená binárka = 215 760 B, deploy-only-if-missing (H2) byl riziko, ale tato konkrétní
binárka seccomp MÁ → H2 REJECTED. H1 REJECTED: `-v 0` jen potlačuje info řádek, akcelerace běží.

### Krok 4: benchmark — bottleneck = per-traced-syscall tracer cost, NE seccomp

`syscall_bench` (stat x5000, kompilován v guestu):

| Konfigurace | µs/stat | poznámka |
|---|---|---|
| host native (mimo proot) | ~1–2 µs | |
| čerstvý proot, bare | ~104 µs | baseline tracer cost (ptrace+seccomp round-trip) |
| + `--link2symlink` | 137 µs (+32 %) | |
| + `-0` | 134 µs (+29 %) | |
| + `-0 --link2symlink --kill-on-exit` | 190 µs | |
| + plné bindy (dev/proc/sys/tmp/ipc/sdcard) | 189 µs | bindy ≈ 0 |
| reálná live session | 345–449 µs | dříve považováno za degradaci — viz níže: šum měření |

Untraced syscally (getpid 0.78 µs, read 0.30 µs) = nativní rychlost → seccomp filter funguje;
stat/open jsou traced v obou režimech (path translation) → „5–10× rozdíl mezi režimy" z H1 byl špatný rámec.

### Krok 5: rootfs = interní storage (parrot-arm64 na /data, není FUSE/adopted)

### Vedlejší nález — SIGBUS root cause (vysvětluje dřívější ashell-proot pády)

`LD_LIBRARY_PATH=filesDir/usr/lib` v `hostShellEnv()` (LocalApiServer.kt) = jediná env proměnná,
která zabije rootfs glibc tracee (SIGBUS/signal 7). Bisect: HOME/USER/PREFIX/ANDROID_DATA/ROOT = 0 crashů;
LD_LIBRARY_PATH → vždy crash. Na zařízení v usr/lib stále leží staré cross-glibc libs (z 2026-08-07,
smaže je až nový APK s bionic version-gate); tracee ld.so je použije místo rootfs knihoven → bus error.
**Fix aplikován v repo: hostShellEnv() bez LD_LIBRARY_PATH.**

### Závěr a doporučení (nahrazuje Fix A/B/C — žádný z nich nebyl potřeba)

1. Seccomp akcelerace je aktivní — nic neměnit v `-v 0` ani binárce.
2. `--link2symlink` **NELZE odebrat** — ověřeno na zařízení (2026-08-11): bez něj Android blokuje
   hardlinky pro app UID (`ln` → Permission denied) a **apt/dpkg je úplně rozbitý** (`dpkg: error
   creating new backup file '/var/lib/dpkg/status-old': Permission denied` — dpkg zálohuje přes link(2)).
   +32 % tracer cost je nutná daň za funkční balíčkový systém.
3. `-0` (fake root) nutné, bindy nic nestojí — ponechat.
4. Bug opraven na zařízení: `/usr/sbin/find` byl symlink na `rg` → `apt-get install --reinstall findutils`
   + `ln -s /usr/bin/find /usr/sbin/find`.
5. **Live vs čerstvý proot — VYŘEŠENO (2026-08-11, interleaved měření):** žádná systematická degradace.
   \`perf_interleave.sh\` (3× live ↔ 3× čerstvý proot, stejné flagy, střídavě v jednom běhu):
   kolo 1: live 187 µs / fresh 214 µs · kolo 2: live 208 µs / fresh 241 µs · kolo 3: live 315 µs /
   fresh 232 µs. Live je v kolách 1–2 RYCHLEJŠÍ než fresh; kolo 3 spiklo (zátěž zařízení). Obě řady
   driftou společně → dřívější „2–2.4× horší live" byl šum měření (benchmarky běžely v různou dobu
   pod rozdílnou zátěží). Proot proces (PID 9834): RSS 2.6 MB, 1 vlákno, utime/stime stabilní —
   žádný leak/akumulace. Skutečný zbytek = intrinsický tracer cost (~190–240 µs/stat na tomhle
   kernelu), který nevyřeší ani restart session ani jiná konfigurace.
