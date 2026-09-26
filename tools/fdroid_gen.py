#!/usr/bin/env python3
"""Generuje fdroid/index.json z fdroid/metadata/*.yml.

Zdroj pravdy = fdroid/metadata/<package_id>.yml (per-balík metadata, viz
docs/plans/2026-09-26-plugin-system-design.md, Fáze 2/9). Tento skript
NEFETCHUJE nic ze sítě — payloady/URL/sha256 už musí být v YAML (psané ručně,
nebo doplněné publish krokem po nahrání na GitHub Releases). Jen validuje
schéma a slévá do jednoho fdroid/index.json.

TODO(Fáze 9): podepsat výsledný index.json (jarsigner, stejný klíč jako
release.jks) a doplnit `sig` do každé verze balíku. Dokud CI signing
neexistuje, `RemotePluginCatalog` (Kotlin klient) smí index použít jen
READ-ONLY (zobrazení katalogu), ne k instalaci.

Použití:
    python3 tools/fdroid_gen.py            # zapíše fdroid/index.json
    python3 tools/fdroid_gen.py --check    # jen validuje, nic nezapisuje (CI)

Per-balík YAML (fdroid/metadata/<id>.yml), id = klíč v "packages" (Android
applicationId pro app/root pluginy, holé jméno pro asset pluginy):
    kind: app | asset | root
    versions:
      - versionCode: 4500       # app/root (int) -- NEBO 'version: "1.2.0"' pro asset
        sha256: "..."
        sig: null                # TODO Fáze 9: jarsigner podpis
        url: "https://github.com/.../releases/download/v4.5.0/vpn-4.5.0.apk"
        minCore: 20
        bridgeApi: 1
        deps: []
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("[-] chybi PyYAML (pip install pyyaml)", file=sys.stderr)
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
FDROID_DIR = REPO_ROOT / "fdroid"
METADATA_DIR = FDROID_DIR / "metadata"
INDEX_PATH = FDROID_DIR / "index.json"

REPO_INFO = {
    "name": "NetHunter Hacker Store",
    "address": "https://github.com/zombiegirlcz/kali_core_emulator/releases",
}

VALID_KINDS = {"app", "asset", "root"}
REQUIRED_VERSION_FIELDS = {"sha256", "url", "minCore"}


class ValidationError(Exception):
    pass


def load_package(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}

    kind = data.get("kind")
    if kind not in VALID_KINDS:
        raise ValidationError(f"{path.name}: 'kind' musi byt jedno z {sorted(VALID_KINDS)}, je {kind!r}")

    versions = data.get("versions") or []
    if not versions:
        raise ValidationError(f"{path.name}: 'versions' je prazdne")

    for v in versions:
        missing = REQUIRED_VERSION_FIELDS - v.keys()
        if missing:
            raise ValidationError(f"{path.name}: verzi chybi pole {sorted(missing)}")
        if "versionCode" not in v and "version" not in v:
            raise ValidationError(
                f"{path.name}: verze musi mit 'versionCode' (app/root) nebo 'version' (asset)",
            )

    return {"kind": kind, "versions": versions}


def generate(check_only: bool) -> int:
    if not METADATA_DIR.is_dir():
        print(f"[-] {METADATA_DIR} neexistuje", file=sys.stderr)
        return 1

    packages: dict[str, dict] = {}
    errors: list[str] = []

    for path in sorted(METADATA_DIR.glob("*.yml")):
        try:
            packages[path.stem] = load_package(path)
        except ValidationError as e:
            errors.append(str(e))

    if errors:
        for e in errors:
            print(f"[-] {e}", file=sys.stderr)
        return 1

    index = {"repo": REPO_INFO, "packages": packages}
    rendered = json.dumps(index, indent=2, sort_keys=True, ensure_ascii=False) + "\n"

    if check_only:
        current = INDEX_PATH.read_text(encoding="utf-8") if INDEX_PATH.exists() else ""
        if current != rendered:
            print(
                f"[-] {INDEX_PATH} neodpovida fdroid/metadata/*.yml "
                "(spust 'python3 tools/fdroid_gen.py' bez --check a commitni výsledek)",
                file=sys.stderr,
            )
            return 1
        print(f"[+] {INDEX_PATH} je aktualni ({len(packages)} balicku)")
        return 0

    INDEX_PATH.write_text(rendered, encoding="utf-8")
    print(f"[+] zapsano {INDEX_PATH} ({len(packages)} balicku)")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--check", action="store_true", help="jen validuj, nezapisuj (pro CI)")
    args = parser.parse_args()
    sys.exit(generate(check_only=args.check))


if __name__ == "__main__":
    main()
