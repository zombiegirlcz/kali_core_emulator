#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Obarvi motd assety (kali/parrot) skutečnými ANSI ESC bajty.

Použití: python3 tools/colorize_motd.py

IDEMPOTENTNÍ: před obarvením odstraní všechny existující ANSI escape
sekvence, takže opakované spuštění nezdvojí barvy (což se stalo při
první verzi — 124 -> 248 ESC bajtů).

Barvy odpovídají welcome scriptu v ProotManager.kt:
  logo:        kali 1;34 (modrá), parrot 1;33 (žlutá)
  oddělovače:  1;36 (cyan)
  sekce 📡:     nadpis 1;32, příkazy 0;32
  ostatní:      nadpis 1;33, příkazy 0;33
  patička 📖:   0;90 (šedá)
"""
import re
import sys
from pathlib import Path

ESC = "\x1b"
RST = ESC + "[0m"
C_LOGO_KALI = ESC + "[1;34m"
C_LOGO_PARR = ESC + "[1;33m"
C_SEP = ESC + "[1;36m"
C_HDR_HELP = ESC + "[1;32m"
C_HDR_OTHER = ESC + "[1;33m"
C_CMD_HELP = ESC + "[0;32m"
C_CMD_OTHER = ESC + "[0;33m"
C_FOOT = ESC + "[0;90m"

# Všechny ANSI CSI sekvence (ESC [ ... písmeno) — použijeme na strip.
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def strip_ansi(s: str) -> str:
    return ANSI_RE.sub("", s)


def colorize(path: Path) -> None:
    raw = path.read_text(encoding="utf-8")
    # Idempotence: sundat všechny předchozí barvy, pak obarvit znovu.
    lines = strip_ansi(raw).split("\n")

    is_parrot = "parrot" in path.name
    logo_col = C_LOGO_PARR if is_parrot else C_LOGO_KALI

    # konec loga: posledni neprazdny radek pred prvnim prazdnym radkem
    logo_end = 0
    for i, l in enumerate(lines):
        if l.strip():
            logo_end = i
        elif i > 0:
            break

    out = []
    section = None
    for i, l in enumerate(lines):
        if i <= logo_end:
            out.append(logo_col + l + RST)
            continue
        if not l.strip():
            out.append(l)
            continue
        stripped = l.strip()
        # oddelovaci linka z ─ (U+2500)
        if re.match(r"^[\s]*\u2500{10,}\s*$", l):
            out.append(C_SEP + l + RST)
            continue
        if stripped.startswith("\U0001F4E1"):  # 📡
            section = "HELP"
            out.append(C_HDR_HELP + l + RST)
            continue
        if stripped.startswith(("\U0001F6E1", "\U0001F511", "\U0001F5A5", "</>")):
            section = "OTHER"
            out.append(C_HDR_OTHER + l + RST)
            continue
        if stripped.startswith("\U0001F4D6"):  # 📖
            out.append(C_FOOT + l + RST)
            continue
        if section == "HELP":
            out.append(C_CMD_HELP + l + RST)
        else:
            out.append(C_CMD_OTHER + l + RST)

    path.write_text("\n".join(out), encoding="utf-8")


def count_esc(path: Path) -> int:
    return path.read_bytes().count(b"\x1b")


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    paths = [root / "app/src/main/assets/motd-kali", root / "app/src/main/assets/motd-parrot"]
    for p in paths:
        before = count_esc(p)
        colorize(p)
        after = count_esc(p)
        status = "OK" if before == 0 or before == after else "CHANGED"
        print(f"{p.name}: {before} -> {after} ESC bajtu  [{status}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
