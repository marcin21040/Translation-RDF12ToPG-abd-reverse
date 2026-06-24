#!/usr/bin/env python3
"""
Wyciąga z pliku N-Triples RDF 1.2 pierwsze N kompletnych bloków reifikacji.

Blok = wszystkie linie z tym samym reifikatorem (blank node będący podmiotem
rdf:reifies oraz annotacji na tym reifikatorze).

Użycie:
  python extract_sample_nt.py wynik.nt wynik-sample-42.nt --blocks 42
  python extract_sample_nt.py wynik.nt wynik-sample-42.nt --lines 101
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies"
SUBJECT_RE = re.compile(r"^(_:\S+)\s")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Wyciąga kompletne bloki reifikacji z NT RDF 1.2")
    p.add_argument("input", type=Path, help="Plik wejściowy (np. wynik.nt)")
    p.add_argument("output", type=Path, help="Plik wyjściowy (np. wynik-sample-42.nt)")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--blocks", type=int, metavar="N", help="Liczba pierwszych bloków reifikacji")
    g.add_argument("--lines", type=int, metavar="N", help="Pierwsze N linii pliku (szybka ścieżka)")
    return p.parse_args()


def extract_by_lines(lines: list[str], max_lines: int) -> list[str]:
    return lines[:max_lines]


def extract_by_blocks(lines: list[str], max_blocks: int) -> list[str]:
    out: list[str] = []
    reifier_order: list[str] = []
    blocks: dict[str, list[str]] = {}
    preamble: list[str] = []

    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("VERSION") or stripped.startswith("#"):
            preamble.append(line if line.endswith("\n") else line + "\n")
            continue

        m = SUBJECT_RE.match(stripped)
        if not m:
            continue
        reifier = m.group(1)

        if REIFIES in stripped and "<<" in stripped:
            if reifier not in blocks:
                reifier_order.append(reifier)
                blocks[reifier] = []
            blocks[reifier].append(line if line.endswith("\n") else line + "\n")
        elif reifier in blocks:
            blocks[reifier].append(line if line.endswith("\n") else line + "\n")

    out.extend(preamble)
    for rid in reifier_order[:max_blocks]:
        out.extend(blocks[rid])
    return out


def main() -> None:
    args = parse_args()
    text = args.input.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    if not lines and text:
        lines = [text]

    if args.lines is not None:
        result = extract_by_lines(lines, args.lines)
    else:
        result = extract_by_blocks(lines, args.blocks)

    args.output.write_text("".join(result), encoding="utf-8")
    reifies_count = sum(1 for ln in result if REIFIES in ln and "<<" in ln)
    print(f"Zapisano: {args.output} ({len(result)} linii, {reifies_count} bloków reifikacji)")


if __name__ == "__main__":
    main()
