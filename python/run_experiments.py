#!/usr/bin/env python3
"""
Run round-trip experiments (RDF -> Neo4j -> RDF -> verify) with timing.

For each sample file and repetition (after optional warmup):
  1. Clear Neo4j
  2. Import (RdfToPropertyGraphApp)
  3. Export (StreamingNeo4jToRdf12App)
  4. Verify (CanonicalRdfVerifier)

Results append to results/metrics.csv

Usage:
  python run_experiments.py --repeats 5 --warmup 1
  python run_experiments.py --samples samples/wynik-sample-42blocks.nt --repeats 3
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from experiment_common import (
    PROJECT_ROOT,
    append_csv_row,
    count_blocks,
    count_triple_lines,
    default_maven_opts,
    maven_env,
    prepare_neo4j,
    remove_if_exists,
    run_maven,
)

CSV_HEADER = [
    "timestamp",
    "direction",
    "sample_file",
    "sample_scale",
    "blocks",
    "triple_lines",
    "run",
    "warmup",
    "t_clear_s",
    "t_import_s",
    "t_export_s",
    "t_verify_s",
    "t_total_s",
    "verify_ok",
]

META_FILENAME = "experiment_meta_rdf.json"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run RDF round-trip timing experiments")
    p.add_argument(
        "--samples-dir",
        type=Path,
        default=PROJECT_ROOT / "samples",
        help="Directory with sample NT files",
    )
    p.add_argument(
        "--samples",
        nargs="*",
        help="Explicit sample files (overrides --samples-dir glob)",
    )
    p.add_argument("--repeats", type=int, default=5, help="Measured repetitions per sample (default: 5)")
    p.add_argument(
        "--warmup",
        type=int,
        default=1,
        help="Warmup runs per sample discarded from CSV/stats (default: 1)",
    )
    p.add_argument(
        "--results-dir",
        type=Path,
        default=PROJECT_ROOT / "results",
        help="Output directory for round-trip NT files",
    )
    p.add_argument(
        "--metrics-csv",
        type=Path,
        default=None,
        help="Metrics CSV path (default: <results-dir>/metrics.csv)",
    )
    p.add_argument(
        "--neo4j-container",
        default=os.environ.get("NEO4J_EXPERIMENT_CONTAINER", "neo4j-experiment"),
    )
    p.add_argument(
        "--neo4j-uri",
        default=os.environ.get("NEO4J_URI", "bolt://localhost:7690"),
    )
    p.add_argument(
        "--neo4j-password",
        default=os.environ.get("NEO4J_PASSWORD", "098e540851"),
    )
    p.add_argument(
        "--skip-clear",
        action="store_true",
        help="Do not clear Neo4j between runs (debug only)",
    )
    p.add_argument(
        "--neo4j-clear-mode",
        default=os.environ.get("NEO4J_CLEAR_MODE", "auto"),
        choices=("auto", "reset", "delete"),
        help="How to empty Neo4j between runs (default: auto, or NEO4J_CLEAR_MODE)",
    )
    return p.parse_args()


def discover_samples(samples_dir: Path) -> list[Path]:
    if not samples_dir.is_dir():
        return []
    pct = sorted(
        list(samples_dir.glob("*-sample-*pct.nt")) + list(samples_dir.glob("wynik-sample-*pct.nt")),
        key=lambda p: int(re.search(r"(\d+)pct", p.name).group(1)),  # type: ignore[union-attr]
    )
    # dedupe while preserving order
    seen: set[Path] = set()
    pct_unique = []
    for p in pct:
        if p not in seen:
            seen.add(p)
            pct_unique.append(p)
    if pct_unique:
        return pct_unique
    blocks = sorted(
        list(samples_dir.glob("*-sample-*blocks.nt")) + list(samples_dir.glob("wynik-sample-*blocks.nt")),
        key=lambda p: int(re.search(r"(\d+)blocks", p.name).group(1)),  # type: ignore[union-attr]
    )
    return blocks


def blocks_from_filename(path: Path) -> int | None:
    m = re.search(r"-sample-(\d+)blocks\.nt$", path.name)
    if m:
        return int(m.group(1))
    m = re.search(r"wynik-sample-(\d+)blocks\.nt$", path.name)
    return int(m.group(1)) if m else None


def sample_scale_from_path(path: Path) -> str:
    if path.name in ("wynik.nt", "yago_annotations.nt"):
        return "1.0"
    m = re.search(r"-sample-(\d+)pct\.nt$", path.name)
    if m:
        return f"{int(m.group(1)) / 100:.6f}"
    m = re.search(r"wynik-sample-(\d+)pct\.nt$", path.name)
    if m:
        return f"{int(m.group(1)) / 100:.6f}"
    return ""


def roundtrip_output_path(sample_path: Path, results_dir: Path) -> Path:
    m = re.search(r"-sample-(\d+)blocks\.nt$", sample_path.name)
    if m:
        return results_dir / f"{sample_path.stem}-roundtrip.nt"
    m = re.search(r"wynik-sample-(\d+)blocks\.nt$", sample_path.name)
    if m:
        return results_dir / f"wynik-roundtrip-{m.group(1)}blocks.nt"
    m = re.search(r"-sample-(\d+)pct\.nt$", sample_path.name)
    if m:
        stem = sample_path.stem.replace(f"-sample-{m.group(1)}pct", "")
        return results_dir / f"{stem}-roundtrip-{m.group(1)}pct.nt"
    m = re.search(r"wynik-sample-(\d+)pct\.nt$", sample_path.name)
    if m:
        return results_dir / f"wynik-roundtrip-{m.group(1)}pct.nt"
    if sample_path.name in ("wynik.nt", "yago_annotations.nt"):
        return results_dir / f"{sample_path.stem}-roundtrip.nt"
    return results_dir / f"{sample_path.stem}-roundtrip.nt"


def rel_path(path: Path) -> str:
    if path.is_relative_to(PROJECT_ROOT):
        return str(path.relative_to(PROJECT_ROOT))
    return str(path)


def fmt_time(key: str, times: dict[str, float]) -> str:
    if key not in times:
        return ""
    return f"{times[key]:.3f}"


def write_meta(results_dir: Path, meta: dict) -> None:
    (results_dir / META_FILENAME).write_text(
        json.dumps(meta, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    args = parse_args()
    args.results_dir.mkdir(parents=True, exist_ok=True)
    csv_path = args.metrics_csv if args.metrics_csv else args.results_dir / "metrics.csv"

    if args.samples:
        sample_files = [Path(s) for s in args.samples]
    else:
        sample_files = discover_samples(args.samples_dir)

    if not sample_files:
        print(
            f"No sample files found in {args.samples_dir}. "
            "Run: python python/generate_experiment_samples.py wynik.nt",
            file=sys.stderr,
        )
        sys.exit(1)

    env = maven_env()
    env["NEO4J_URI"] = args.neo4j_uri
    env["NEO4J_PASSWORD"] = args.neo4j_password
    env.setdefault("NEO4J_USER", "neo4j")
    env["NEO4J_CLEAR_MODE"] = args.neo4j_clear_mode
    env.setdefault("RDF12_INPUT_PROFILE_LENIENT", os.environ.get("RDF12_INPUT_PROFILE_LENIENT", "true"))

    if args.neo4j_container == "neo4j-experiment" and ":7691" in args.neo4j_uri:
        print(
            "Error: RDF experiments use neo4j-experiment on bolt://localhost:7690, "
            f"not {args.neo4j_uri}.\n"
            "  unset NEO4J_URI   # or: RDF_NEO4J_URI=bolt://localhost:7690\n"
            "  sudo -E bash scripts/run-rdf-experiments-only.sh",
            file=sys.stderr,
        )
        sys.exit(1)
    if args.neo4j_container == "neo4j-twitch" and ":7690" in args.neo4j_uri:
        print(
            "Error: PG experiments use neo4j-twitch on bolt://localhost:7691, "
            f"not {args.neo4j_uri}.",
            file=sys.stderr,
        )
        sys.exit(1)

    reset_hint = (
        "scripts/reset-neo4j-experiment.sh"
        if args.neo4j_container == "neo4j-experiment"
        else "scripts/reset-neo4j-yago.sh"
        if args.neo4j_container == "neo4j-yago"
        else "scripts/clear-neo4j-experiment.sh"
    )

    write_meta(args.results_dir, {
        "direction": "rdf12_pg_rdf12",
        "samples": [rel_path(p if p.is_absolute() else PROJECT_ROOT / p) for p in sample_files],
        "repeats": args.repeats,
        "warmup": args.warmup,
        "neo4j_uri": args.neo4j_uri,
        "neo4j_container": args.neo4j_container,
        "neo4j_clear_mode": args.neo4j_clear_mode,
        "rdf12_input_profile_lenient": env.get("RDF12_INPUT_PROFILE_LENIENT"),
        "maven_opts": env.get("MAVEN_OPTS", default_maven_opts()),
    })

    total_per_sample = args.warmup + args.repeats
    print(f"Neo4j URI: {args.neo4j_uri}")
    print(f"Samples: {len(sample_files)}, warmup: {args.warmup}, measured: {args.repeats}")
    print(f"Neo4j clear mode: {args.neo4j_clear_mode}")
    print(f"Results: {csv_path}")
    print()

    for sample_path in sample_files:
        if not sample_path.is_file():
            print(f"Skip missing file: {sample_path}", file=sys.stderr)
            continue

        rel_sample = rel_path(
            sample_path if sample_path.is_absolute() else PROJECT_ROOT / sample_path
        )
        blocks = blocks_from_filename(sample_path) or count_blocks(sample_path)
        triple_lines = count_triple_lines(sample_path)
        roundtrip_path = roundtrip_output_path(sample_path, args.results_dir)
        sample_scale = sample_scale_from_path(
            sample_path if sample_path.is_absolute() else PROJECT_ROOT / sample_path
        )

        print(f"=== Sample: {rel_sample} ({blocks} blocks, {triple_lines} triple lines) ===")

        for run_idx in range(1, total_per_sample + 1):
            is_warmup = run_idx <= args.warmup
            label = "warmup" if is_warmup else "measure"
            measured_num = run_idx - args.warmup if not is_warmup else 0
            print(
                f"  Run {run_idx}/{total_per_sample} ({label}"
                + (f" {measured_num}/{args.repeats}" if not is_warmup else "")
                + ") ..."
            )

            if not args.skip_clear:
                try:
                    t_clear = prepare_neo4j(
                        args.neo4j_container,
                        args.neo4j_password,
                        reset_hint=reset_hint,
                    )
                except RuntimeError as e:
                    print(e, file=sys.stderr)
                    sys.exit(1)
                if t_clear > 0:
                    print(f"    clear={t_clear:.1f}s")
            else:
                t_clear = 0.0

            remove_if_exists(roundtrip_path)

            times: dict[str, float] = {}
            status = "ok"

            t_import, rc_import = run_maven(
                "org.example.rdf2pg.RdfToPropertyGraphApp",
                [rel_sample, "neo4j"],
                env,
            )
            times["t_import_s"] = t_import
            if rc_import != 0:
                status = "fail_import"
            else:
                t_export, rc_export = run_maven(
                    "org.example.rdf2pg.StreamingNeo4jToRdf12App",
                    [rel_path(roundtrip_path)],
                    env,
                )
                times["t_export_s"] = t_export
                if rc_export != 0:
                    status = "fail_export"
                else:
                    t_verify, rc_verify = run_maven(
                        "org.example.rdf2pg.CanonicalRdfVerifier",
                        [rel_sample, rel_path(roundtrip_path)],
                        env,
                    )
                    times["t_verify_s"] = t_verify
                    status = "ok" if rc_verify == 0 else "fail_verify"

            t_total = sum(times.values())
            print(
                f"    clear={t_clear:.1f}s "
                f"import={times.get('t_import_s', 0):.1f}s "
                f"export={times.get('t_export_s', 0):.1f}s "
                f"verify={times.get('t_verify_s', 0):.1f}s "
                f"pipeline={t_total:.1f}s [{status}]"
            )

            if is_warmup:
                print("    (warmup — not recorded in CSV)")
                continue

            append_csv_row(csv_path, CSV_HEADER, {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "direction": "rdf12_pg_rdf12",
                "sample_file": rel_sample,
                "sample_scale": sample_scale,
                "blocks": blocks,
                "triple_lines": triple_lines,
                "run": measured_num,
                "warmup": args.warmup,
                "t_clear_s": f"{t_clear:.3f}",
                "t_import_s": fmt_time("t_import_s", times),
                "t_export_s": fmt_time("t_export_s", times),
                "t_verify_s": fmt_time("t_verify_s", times),
                "t_total_s": f"{t_total:.3f}",
                "verify_ok": status,
            })

        print()

    print(f"Done. Metrics saved to {csv_path}")
    print("Run analysis: python python/analyze_experiment_metrics.py")


if __name__ == "__main__":
    main()
