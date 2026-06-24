#!/usr/bin/env python3
"""
Generate GraphML samples by taking the first N nodes (and induced edges).

Usage:
  python generate_graphml_samples.py pole-all.graphml --fractions 1/3,2/3,1
  → samples/pole-sample-33pct.graphml (+ 67pct; full = original path)
"""

from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

NODE_ID_RE = re.compile(r'\bid="([^"]+)"')
EDGE_SRC_RE = re.compile(r'\bsource="([^"]+)"')
EDGE_TGT_RE = re.compile(r'\btarget="([^"]+)"')

FRACTION_NAME = {
    1 / 3: "33pct",
    2 / 3: "67pct",
    1.0: "full",
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Generate GraphML samples by node fraction")
    p.add_argument("input", type=Path, help="Source GraphML (e.g. pole-all.graphml)")
    p.add_argument(
        "--fractions",
        default="1/3,2/3,1",
        help="Comma-separated fractions of nodes (default: 1/3,2/3,1)",
    )
    p.add_argument(
        "--output-dir",
        type=Path,
        default=Path("samples"),
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
    return f"{int(round(frac * 100))}pct"


def count_nodes(path: Path) -> int:
    count = 0
    with path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.lstrip().startswith("<node "):
                count += 1
    return count


def write_sample(input_path: Path, output_path: Path, max_nodes: int) -> tuple[int, int]:
    """Write GraphML with first max_nodes nodes and edges between them."""
    preamble: list[str] = []
    selected_nodes: list[str] = []
    node_ids: set[str] = set()
    edges: list[str] = []
    footer = "</graph>\n</graphml>\n"
    phase = "preamble"
    node_count = 0

    with input_path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            stripped = line.strip()
            if phase == "preamble":
                preamble.append(line)
                # <graphml> also starts with "<graph" — match only the <graph> element.
                if stripped == "<graph>" or stripped.startswith("<graph "):
                    phase = "nodes"
                continue

            if phase == "nodes":
                if stripped.startswith("<node "):
                    if node_count < max_nodes:
                        selected_nodes.append(line)
                        m = NODE_ID_RE.search(line)
                        if m:
                            node_ids.add(m.group(1))
                        node_count += 1
                    continue
                if stripped.startswith("<edge "):
                    phase = "edges"
                    # fall through to process this edge line
                elif stripped == "</graph>":
                    footer = line
                    phase = "done"
                    break
                else:
                    continue

            if phase == "edges":
                if stripped.startswith("<edge "):
                    sm = EDGE_SRC_RE.search(line)
                    tm = EDGE_TGT_RE.search(line)
                    if sm and tm and sm.group(1) in node_ids and tm.group(1) in node_ids:
                        edges.append(line)
                    continue
                if stripped == "</graph>":
                    footer = line
                    phase = "done"
                    break

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as out:
        out.writelines(preamble)
        out.writelines(selected_nodes)
        out.writelines(edges)
        if not footer.endswith("\n"):
            footer += "\n"
        out.write(footer)
        if "</graphml>" not in footer:
            out.write("</graphml>\n")

    return node_count, len(edges)


def main() -> None:
    args = parse_args()
    if not args.input.is_file():
        raise SystemExit(f"Input not found: {args.input}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    total_nodes = count_nodes(args.input)
    print(f"{args.input}: {total_nodes} nodes")

    fracs = sorted(set(parse_fraction(s) for s in args.fractions.split(",") if s.strip()))
    manifest: list[str] = []

    for frac in fracs:
        if frac >= 1.0 or math.isclose(frac, 1.0, rel_tol=1e-6):
            manifest.append(f"{args.input.resolve()},{total_nodes},,1.0,full")
            print(f"  full: {args.input} ({total_nodes} nodes, no copy)")
            continue

        max_nodes = max(1, int(total_nodes * frac))
        name = fraction_to_name(frac)
        out_path = args.output_dir / f"pole-sample-{name}.graphml"
        nodes, edge_count = write_sample(args.input, out_path, max_nodes)
        manifest.append(f"{out_path},{nodes},{edge_count},{frac:.6f},{name}")
        print(f"  {out_path}: {nodes} nodes, {edge_count} edges ({frac:.0%})")

    manifest_path = args.output_dir / "manifest_graphml.csv"
    manifest_path.write_text(
        "file,nodes,edges,sample_scale,name\n" + "\n".join(manifest) + "\n",
        encoding="utf-8",
    )
    print(f"Manifest: {manifest_path}")


if __name__ == "__main__":
    main()
