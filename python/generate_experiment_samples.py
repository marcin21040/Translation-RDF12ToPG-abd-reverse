#!/usr/bin/env python3
"""
Generate experiment sample NT files with complete reification blocks.

Reads a source NT file once and writes samples/{stem}-sample-{N}blocks.nt
for each target block count (must be increasing).

Fraction mode (1/3, 2/3, full):
  python generate_experiment_samples.py yago_annotations.nt --fractions 1/3,2/3,1
  → samples/yago_annotations-sample-33pct.nt, ...-67pct.nt (+ input file for full)

Usage:
  python generate_experiment_samples.py wynik.nt --sizes 42,500,1000,2000,5000
"""

from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies"
SUBJECT_RE = re.compile(r"^(_:\S+)\s")

FRACTION_NAME = {
    1 / 3: "33pct",
    2 / 3: "67pct",
    1.0: "full",
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Generate experiment NT samples by block count")
    p.add_argument("input", type=Path, help="Source NT file (e.g. wynik.nt)")
    p.add_argument(
        "--sizes",
        default=None,
        help="Comma-separated block counts (ascending)",
    )
    p.add_argument(
        "--fractions",
        default=None,
        help="Comma-separated fractions of all blocks, e.g. 1/3,2/3,1",
    )
    p.add_argument(
        "--output-dir",
        type=Path,
        default=Path("samples"),
        help="Output directory (default: samples/)",
    )
    return p.parse_args()


def parse_fraction(text: str) -> float:
    text = text.strip()
    if "/" in text:
        num, den = text.split("/", 1)
        return float(num.strip()) / float(den.strip())
    return float(text)


def fraction_to_name(frac: float) -> str:
    for key, name in FRACTION_NAME.items():
        if math.isclose(frac, key, rel_tol=0, abs_tol=0.001):
            return name
    pct = int(round(frac * 100))
    return f"{pct}pct"


def resolve_sizes(args: argparse.Namespace, total_blocks: int) -> list[tuple[int, float | None]]:
    """Return list of (block_count, fraction_or_none)."""
    if args.fractions:
        fracs = [parse_fraction(s) for s in args.fractions.split(",") if s.strip()]
        sizes: list[tuple[int, float | None]] = []
        for frac in sorted(set(fracs)):
            if frac >= 1.0 or math.isclose(frac, 1.0, rel_tol=1e-6):
                sizes.append((total_blocks, 1.0))
            else:
                n = max(1, int(total_blocks * frac))
                sizes.append((n, frac))
        return sizes

    raw = args.sizes or "42,500,1000,2000,5000"
    nums = sorted(set(int(s.strip()) for s in raw.split(",") if s.strip()))
    return [(n, None) for n in nums]


def count_reifies(lines: list[str]) -> int:
    return sum(1 for ln in lines if REIFIES in ln and "<<" in ln)


def extract_blocks(lines: list[str], max_blocks: int) -> tuple[list[str], int]:
    preamble: list[str] = []
    reifier_order: list[str] = []
    blocks: dict[str, list[str]] = {}

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

    out: list[str] = list(preamble)
    taken = reifier_order[:max_blocks]
    for rid in taken:
        out.extend(blocks[rid])
    return out, len(taken)


def main() -> None:
    args = parse_args()
    if not args.input.is_file():
        raise SystemExit(f"Input file not found: {args.input}")

    args.output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Reading {args.input} (large file — may take ~30s) ...", flush=True)
    text = args.input.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    if not lines and text:
        lines = [text]

    # Count available blocks
    _, total_blocks = extract_blocks(lines, 10**18)
    size_specs = resolve_sizes(args, total_blocks)
    if not size_specs:
        raise SystemExit("No block sizes specified")

    manifest: list[str] = []
    for n, frac in size_specs:
        if frac is not None and frac >= 1.0:
            # Full dataset — reference original file, no copy
            actual = total_blocks
            out_path = args.input.resolve()
            out_lines, _ = extract_blocks(lines, actual)
            triples = len([
                ln for ln in out_lines
                if ln.strip() and not ln.strip().startswith("#") and not ln.strip().startswith("VERSION")
            ])
            reifies = count_reifies(out_lines)
            manifest.append(f"{out_path},{actual},{triples},{reifies},1.0,full")
            print(f"  {out_path}: {actual} blocks (full, no copy)")
            continue

        out_lines, actual = extract_blocks(lines, n)
        if actual < n:
            print(f"Warning: only {actual} blocks available (requested {n})")
        if frac is not None:
            name = fraction_to_name(frac)
            out_path = args.output_dir / f"{args.input.stem}-sample-{name}.nt"
            scale = f"{frac:.6f}"
        else:
            out_path = args.output_dir / f"{args.input.stem}-sample-{n}blocks.nt"
            scale = ""
        out_path.write_text("".join(out_lines), encoding="utf-8")
        triples = len([
            ln for ln in out_lines
            if ln.strip() and not ln.strip().startswith("#") and not ln.strip().startswith("VERSION")
        ])
        reifies = count_reifies(out_lines)
        manifest.append(f"{out_path},{actual},{triples},{reifies},{scale},{frac or ''}")
        label = f"{frac:.0%}" if frac else f"{actual} blocks"
        print(f"  {out_path}: {actual} blocks ({label}), {triples} triple lines")

    manifest_path = args.output_dir / "manifest.csv"
    manifest_path.write_text(
        "file,blocks,triple_lines,reifies,sample_scale,fraction\n" + "\n".join(manifest) + "\n",
        encoding="utf-8",
    )
    print(f"Manifest: {manifest_path}")


if __name__ == "__main__":
    main()
