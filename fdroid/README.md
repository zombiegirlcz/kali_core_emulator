# fdroid/ — NetHunter plugin marketplace katalog

Vlastní katalog pluginů tohoto monorepa (viz `docs/plans/2026-09-26-plugin-system-design.md`).
Formát vychází z F-Droid (`index.json` + per-balík metadata), ale je to **naše vlastní
infrastruktura** — ne oficiální F-Droid store a ne oficiální NetHunter store
(ten používá `com.linux_core.yml` + `fastlane/` + submodul `nethunter-store-data`,
to je nezávislý publikační kanál mimo tento adresář).

- `index.json` — **generovaný soubor, needituj ručně.** Vzniká z `metadata/*.yml`
  přes `python3 tools/fdroid_gen.py`. CI (Fáze 9, zatím nezapojeno) ho bude
  regenerovat a podepisovat při release tagu.
- `metadata/<package_id>.yml` — per-balík zdroj pravdy (kind, verze, sha256, url,
  minCore, bridgeApi, deps). `package_id` je Android `applicationId` pro app/root
  pluginy, nebo holé jméno pro asset pluginy (např. `cpu`).
- Payloady (APK, `.tar.xz`) **nejsou v gitu** — nahrávají se na GitHub Releases
  tohoto repa; `metadata/*.yml` na ně jen odkazuje přes `url`.

## Přidání pluginu do katalogu

1. Publikuj payload na GitHub Releases (tag, asset).
2. Vytvoř/aktualizuj `metadata/<package_id>.yml` s novou verzí (sha256 spočítaný
   z uploadnutého souboru).
3. `python3 tools/fdroid_gen.py` → přegeneruje `index.json`.
4. Commit obojího (`metadata/*.yml` i `index.json`).

`python3 tools/fdroid_gen.py --check` ověří, že `index.json` odpovídá `metadata/*.yml`
(bez zápisu) — vhodné jako CI gate.

**Bezpečnost (zatím neúplné, Fáze 9):** `index.json` má být podepsán jarsignerem
stejným klíčem jako `release.jks`, klient (`RemotePluginCatalog.kt`) má podpis
ověřit před instalací. Dokud podepisování v CI neexistuje, katalog je jen
read-only zdroj pro zobrazení — `nh plugin install` na něj zatím nespoléhá.
