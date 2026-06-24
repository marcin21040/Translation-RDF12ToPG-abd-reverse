#!/usr/bin/env python3
"""
Run PG -> RDF12 -> PG round-trip experiments (GraphML via Neo4j) with timing.

Usage:
  python run_pg_experiments.py --graphml pole-all.graphml --repeats 5 --warmup 1
  python run_pg_experiments.py --samples samples/pole-sample-33pct.graphml samples/pole-sample-67pct.graphml /path/pole-all.graphml
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
    count_graphml_nodes_edges,
    default_maven_opts,
    maven_env,
    prepare_neo4j,
    remove_if_exists,
    run_maven,
)

CSV_HEADER = [
    "timestamp",
    "direction",
    "graphml_file",
    "dataset",
    "sample_scale",
    "graphml_bytes",
    "nodes",
    "edges",
    "run",
    "warmup",
    "t_clear_s",
    "t_graphml_s",
    "t_rdf_export_s",
    "t_rdf_import_s",
    "t_rdf_reexport_s",
    "t_verify_s",
    "t_total_s",
    "verify_ok",
]

META_FILENAME = "experiment_meta_pg.json"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run PG round-trip timing experiments (GraphML)")
    p.add_argument("--graphml", type=Path, default=None, help="Single GraphML input")
    p.add_argument(
        "--samples",
        nargs="*",
        help="Multiple GraphML files (overrides --graphml)",
    )
    p.add_argument("--repeats", type=int, default=5)
    p.add_argument("--warmup", type=int, default=1)
    p.add_argument("--results-dir", type=Path, default=PROJECT_ROOT / "results")
    p.add_argument(
        "--metrics-csv",
        type=Path,
        default=None,
        help="Metrics CSV path (default: <results-dir>/metrics_pg.csv)",
    )
    p.add_argument(
        "--neo4j-container",
        default=os.environ.get("NEO4J_TWITCH_CONTAINER", "neo4j-twitch"),
    )
    p.add_argument(
        "--neo4j-uri",
        default=os.environ.get("NEO4J_URI", "bolt://localhost:7691"),
    )
    p.add_argument(
        "--neo4j-password",
        default=os.environ.get("NEO4J_PASSWORD", "098e540851"),
    )
    p.add_argument("--skip-clear", action="store_true")
    p.add_argument(
        "--neo4j-clear-mode",
        default=os.environ.get("NEO4J_CLEAR_MODE", "auto"),
        choices=("auto", "reset", "delete"),
    )
    return p.parse_args()


def rel_path(path: Path) -> str:
    if path.is_relative_to(PROJECT_ROOT):
        return str(path.relative_to(PROJECT_ROOT))
    return str(path)


def fmt_time(key: str, times: dict[str, float]) -> str:
    if key not in times:
        return ""
    return f"{times[key]:.3f}"


def dataset_tag_from_path(path: Path) -> str:
    """Short name for output RDF files (pole, twitch, fib25, pole-33pct, …)."""
    m = re.search(r"pole-sample-(\d+)pct", path.name)
    if m:
        return f"pole-{m.group(1)}pct"
    m = re.search(r"^([\w]+)-all\.graphml$", path.name)
    if m:
        return m.group(1)
    if path.name == "pole-all.graphml":
        return "pole"
    return path.stem.replace(".", "_")


def sample_scale_from_path(path: Path) -> str:
    m = re.search(r"pole-sample-(\d+)pct\.graphml$", path.name)
    if m:
        return f"{int(m.group(1)) / 100:.6f}"
    return ""


def rdf_paths_for_graphml(graphml_path: Path, results_dir: Path) -> tuple[Path, Path]:
    tag = dataset_tag_from_path(graphml_path)
    return (
        results_dir / f"{tag}-experiment-rdf.nt",
        results_dir / f"{tag}-experiment-roundtrip-rdf.nt",
    )


def write_meta(results_dir: Path, meta: dict) -> None:
    (results_dir / META_FILENAME).write_text(
        json.dumps(meta, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def reset_hint_for(container: str) -> str:
    if container == "neo4j-twitch":
        return "scripts/reset-neo4j-twitch.sh"
    if container == "neo4j-experiment":
        return "scripts/reset-neo4j-experiment.sh"
    return "scripts/clear-neo4j-yago.sh"


def run_pipeline(
    graphml_path: Path,
    rdf_out: Path,
    rdf_rt: Path,
    env: dict[str, str],
    container: str,
    password: str,
    skip_clear: bool,
) -> tuple[dict[str, float], float, str]:
    hint = reset_hint_for(container)
    t_clear = 0.0

    if not skip_clear:
        t_clear += prepare_neo4j(container, password, reset_hint=hint)

    remove_if_exists(rdf_out)

    t_graphml, rc = run_maven(
        "org.example.rdf2pg.GraphmlToNeo4jApp",
        [str(graphml_path)],
        env,
    )
    if rc != 0:
        return {"t_graphml_s": t_graphml}, t_clear, "fail_graphml"

    t_export, rc = run_maven(
        "org.example.rdf2pg.StreamingNeo4jToRdf12App",
        [rel_path(rdf_out)],
        env,
    )
    if rc != 0:
        return {"t_graphml_s": t_graphml, "t_rdf_export_s": t_export}, t_clear, "fail_rdf_export"

    if not skip_clear:
        t_clear += prepare_neo4j(container, password, reset_hint=hint)

    remove_if_exists(rdf_rt)

    t_import, rc = run_maven(
        "org.example.rdf2pg.StreamingRdf12ToNeo4jApp",
        [rel_path(rdf_out)],
        env,
    )
    if rc != 0:
        return {
            "t_graphml_s": t_graphml,
            "t_rdf_export_s": t_export,
            "t_rdf_import_s": t_import,
        }, t_clear, "fail_rdf_import"

    t_reexport, rc = run_maven(
        "org.example.rdf2pg.StreamingNeo4jToRdf12App",
        [rel_path(rdf_rt)],
        env,
    )
    if rc != 0:
        return {
            "t_graphml_s": t_graphml,
            "t_rdf_export_s": t_export,
            "t_rdf_import_s": t_import,
            "t_rdf_reexport_s": t_reexport,
        }, t_clear, "fail_rdf_reexport"

    t_verify, rc = run_maven(
        "org.example.rdf2pg.CanonicalRdfVerifier",
        [rel_path(rdf_out), rel_path(rdf_rt)],
        env,
    )
    times = {
        "t_graphml_s": t_graphml,
        "t_rdf_export_s": t_export,
        "t_rdf_import_s": t_import,
        "t_rdf_reexport_s": t_reexport,
        "t_verify_s": t_verify,
    }
    return times, t_clear, "ok" if rc == 0 else "fail_verify"


def main() -> None:
    args = parse_args()

    if args.samples:
        graphml_files = [Path(s) for s in args.samples]
    elif args.graphml:
        graphml_files = [args.graphml]
    else:
        gml = os.environ.get("GRAPHML", "")
        graphml_files = [Path(gml)] if gml else []

    graphml_files = [p for p in graphml_files if p.is_file()]
    if not graphml_files:
        print("GraphML required: --samples PATH ... or --graphml PATH or env GRAPHML", file=sys.stderr)
        sys.exit(1)

    args.results_dir.mkdir(parents=True, exist_ok=True)
    csv_path = args.metrics_csv if args.metrics_csv else args.results_dir / "metrics_pg.csv"

    env = maven_env()
    env["NEO4J_URI"] = args.neo4j_uri
    env["NEO4J_PASSWORD"] = args.neo4j_password
    env.setdefault("NEO4J_USER", "neo4j")
    env["NEO4J_CLEAR_MODE"] = args.neo4j_clear_mode
    if "MAVEN_OPTS" not in os.environ:
        env["MAVEN_OPTS"] = "-Xmx8g -Xss16m"

    write_meta(args.results_dir, {
        "direction": "pg_rdf12_pg",
        "samples": [rel_path(p if p.is_absolute() else PROJECT_ROOT / p) for p in graphml_files],
        "repeats": args.repeats,
        "warmup": args.warmup,
        "neo4j_uri": args.neo4j_uri,
        "neo4j_container": args.neo4j_container,
        "neo4j_clear_mode": args.neo4j_clear_mode,
        "maven_opts": env.get("MAVEN_OPTS", default_maven_opts()),
    })

    total_per_sample = args.warmup + args.repeats
    print(f"Neo4j URI: {args.neo4j_uri}")
    print(f"GraphML samples: {len(graphml_files)}, warmup: {args.warmup}, measured: {args.repeats}")
    print(f"Clear mode: {args.neo4j_clear_mode}")
    print(f"Results: {csv_path}")
    print()

    for graphml in graphml_files:
        abs_gml = graphml if graphml.is_absolute() else PROJECT_ROOT / graphml
        rel_gml = rel_path(abs_gml)
        graphml_bytes = abs_gml.stat().st_size
        nodes, edges = count_graphml_nodes_edges(abs_gml)
        sample_scale = sample_scale_from_path(abs_gml)
        dataset = dataset_tag_from_path(abs_gml)
        rdf_out, rdf_rt = rdf_paths_for_graphml(abs_gml, args.results_dir)

        scale_label = f", scale={sample_scale}" if sample_scale else ""
        print(f"=== GraphML: {rel_gml} [dataset={dataset}] (~{nodes} nodes, ~{edges} edges{scale_label}) ===")

        for run_idx in range(1, total_per_sample + 1):
            is_warmup = run_idx <= args.warmup
            label = "warmup" if is_warmup else "measure"
            measured_num = run_idx - args.warmup if not is_warmup else 0
            print(
                f"  Run {run_idx}/{total_per_sample} ({label}"
                + (f" {measured_num}/{args.repeats}" if not is_warmup else "")
                + ") ..."
            )

            try:
                times, t_clear, status = run_pipeline(
                    abs_gml,
                    rdf_out,
                    rdf_rt,
                    env,
                    args.neo4j_container,
                    args.neo4j_password,
                    args.skip_clear,
                )
            except RuntimeError as e:
                print(e, file=sys.stderr)
                sys.exit(1)

            t_total = sum(times.values())
            print(
                f"    clear={t_clear:.1f}s "
                f"graphml={times.get('t_graphml_s', 0):.1f}s "
                f"export={times.get('t_rdf_export_s', 0):.1f}s "
                f"import={times.get('t_rdf_import_s', 0):.1f}s "
                f"reexport={times.get('t_rdf_reexport_s', 0):.1f}s "
                f"verify={times.get('t_verify_s', 0):.1f}s "
                f"pipeline={t_total:.1f}s [{status}]"
            )

            if is_warmup:
                print("    (warmup — not recorded)")
                continue

            append_csv_row(csv_path, CSV_HEADER, {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "direction": "pg_rdf12_pg",
                "graphml_file": rel_gml,
                "dataset": dataset,
                "sample_scale": sample_scale,
                "graphml_bytes": graphml_bytes,
                "nodes": nodes,
                "edges": edges,
                "run": measured_num,
                "warmup": args.warmup,
                "t_clear_s": f"{t_clear:.3f}",
                "t_graphml_s": fmt_time("t_graphml_s", times),
                "t_rdf_export_s": fmt_time("t_rdf_export_s", times),
                "t_rdf_import_s": fmt_time("t_rdf_import_s", times),
                "t_rdf_reexport_s": fmt_time("t_rdf_reexport_s", times),
                "t_verify_s": fmt_time("t_verify_s", times),
                "t_total_s": f"{t_total:.3f}",
                "verify_ok": status,
            })

        print()

    print(f"Done. Metrics saved to {csv_path}")


if __name__ == "__main__":
    main()
