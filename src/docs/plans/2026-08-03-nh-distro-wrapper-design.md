# Design: `nh distro` — proot-distro-like wrapper pro hostitelskou správu kontejnerů

**Datum:** 2026-08-03
**Status:** Schváleno v brainstormingu
**Balíček:** `com.linux_core`

## Cíl

Vytvořit wrapper podobný `proot-distro`, který spravuje PRoot kontejnery (kali, parrot) jako novou kategorii `nh distro ...` ve stávajícím unified CLI. Nástroj musí být volatelný jak z guestu (Kali/Parrot terminál), tak **z hostitele bez rootu** (Termux, adb shell) — kvůli flexibilní diagnostice a opravám chyb.

## Klíčové zjištění (politika validace)

`LocalApiServer.kt` (auth check, ~řádek 355-375) vyžaduje Bearer token **jen pro non-local připojení**;
localhost (`127.0.0.1:1337`) prochází bez auth. Proto:
- **Host bez rootu** → `curl http://127.0.0.1:1337/distro/ps` funguje rovnou, žádný token.
- **Guest** → `nh distro ...` funguje stejně (localhost).
- **Token zůstává povinný jen pro vzdálené/dílené API**.

## Architektura

Jedna kategorie `nh distro <podpříkaz>` + nové endpointy `/distro/*` v `LocalApiServer`.
Guest i hostitel volají **stejné endpointy**.

| Endpoint | Metoda | Funkce | Využije |
|---|---|---|---|
| `/distro/list` | GET | Seznam distro (kali, parrot) + instalační stav + session count | `RootfsManager.DISTROS` + `isRootfsExtracted` |
| `/distro/ps` | GET | Aktivní session (distro, PID, vpn-ignored) | `TerminalService.sessions` |
| `/distro/kill` | POST | Ukončí session podle ID (bezpečnostní potvrzení) | `TerminalService.removeSession` |
| `/distro/remove` | POST | Smaže rootfs distro (vyžaduje `--force` / `force=true`) | `RootfsManager.deleteRootfs` (existuje) |
| `/distro/install` | POST | Stáhne+extrahuje rootfs (async, progress) | `RootfsManager.downloadRootfs` + `extractRootfs` |
| `/distro/progress/<id>` | GET | Průběh instalace `{"phase":"extract","percent":47}` | `RootfsManager` progress state |
| `/distro/login` | POST | Host: spustí `TerminalActivity` s `rootfsDirName=<distro-arm64>` | stejné jako `ashell` bez argů / hlavní UI |
| `/rootfs/backup` | POST | Záloha do tar.gz | **již existuje** |
| `/rootfs/restore` | POST | Obnova z zálohy | **již existuje** |

**Politika auth:** citlivé endpointy (`/distro/remove`, `/distro/install`, `/distro/restore`, `/distro/login`)
se přidají do `sensitiveEndpoints` (auth jen pro vzdálené). Čtecí endpointy (`/distro/list`, `/distro/ps`, `/distro/progress`)
jsou localhost-only citlivé → nesmí uniknout při `share_local_api=on`? — zůstanou bez auth.

## CLI syntaxe

```
nh distro list                          # seznam distro + stav
nh distro ps                            # aktivní session
nh distro kill <session_id> [--force]   # ukončit session
nh distro remove <id> [--force]         # smazat rootfs (kali|parrot)
nh distro install <id> [--force]        # stáhnout+extrahovat (async)
nh distro reset <id>                    # remove + install (reinstal)
nh distro progress <id>                 # průběh instalace
nh distro backup [id] [-o soubor]       # tar.gz do Downloads
nh distro restore <soubor> [--force]    # obnova z backup
nh distro login <id>                    # host: spustit shell; guest: hint
nh distro status                        # alias list
```

## Následující flow

### install (async, progress)
```
nh distro install kali
  → POST /distro/install {"id":"kali"}
  → LocalApiServer spustí download+extract na pozadí
  → odpověď: {"status":"started","eta_minutes":~12}
  → sehen: GET /distro/progress/<id>
```

### login
- Host: `nh distro login kali` → `POST /distro/login` → spustí `TerminalActivity` `rootfsDirName=kali-arm64`.
- Guest: výpí hint `[!] Už jste v kontejneru — použij 'nh distro ps'`.

### kill
- `nh distro kill <id>` → `POST /distro/kill {session_id}` → `TerminalService.removeSession`.
- Používá se zejména na zabití zamrzlé relace.

### bezpečnostní pojistka (remove/reset)
- Defaultně vyžaduje potvrzení: `remove`/`reset` bez `--force` vrátí 409 "confirmation_required".
- `--force` v API = `{"force":true}`.

## Soubory k úpravě

- `app/src/main/assets/nh` — nová funkce `distro_dispatch()` + registrace v main case dispatch.
- `app/src/main/java/com/linux_core/core/LocalApiServer.kt` — nové endpointy `/distro/*` + sensitiveEndpoints update.
- `app/src/main/java/com/linux_core/core/RootfsManager.kt` — vystavit progress state (pokud není), `getDistroStatus`, případně zajistit `extractRootfs` po download flow.
- `app/src/main/java/com/linux_core/core/TerminalService.kt` — helper pro session kill/logout (pokud aug potrzebny).
- `app/src/main/assets/nethunter_docs.md` — sekce `nh distro`.
- `README.md` — sekce věnovaná `nh distro`.

## Rozsah MVP vs fáze 2

MVP: `list, ps, kill, remove, backup, restore, who` — všechny mají existující podpůrání kód/endpointy.
Druhá fáze: `install, progress, reset, login` — vyžadují nové endpointy/progress state a test covert lead (host spuštění TerminalActivity).

## Priorities

- Residualní nová práce je malá (endpointy jsou tenké fasáda nad existujícími RootfsManager/TerminalService činy).
- Nepoužívat nové UI komponenty; vše přes CLI.
- Build výhradně přes `zsh mbuild` (NE lokální build).
- Zachovat token-auth politku a destructive-pattern blocklist na `/shell`.