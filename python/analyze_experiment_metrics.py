#!/usr/bin/env python3
"""
Analyze experiment metrics: minimal stats (ms), markdown tables, and plots.

Reads results/metrics.csv (RDF) and/or results/metrics_pg.csv (PG) and writes:
  results/summary_rdf.md
  results/summary_pg.md
  results/experiment_conditions.md
  results/summary.txt          (legacy text summary for RDF scaling)
  results/plots/rdf_total_boxplot.png, rdf_stages_mean.png, rdf_stages_boxplot.png,
  rdf_stage_fraction.png, rdf_clear_boxplot.png, scaling_total.png
  results/plots/pg_total_boxplot.png, pg_stages_mean.png, pg_stages_boxplot.png,
  pg_stage_fraction.png, pg_clear_boxplot.png, pg_scaling_by_nodes.png, pg_throughput.png
  results/plots/compare_directions_mean_total.png, compare_scaling_loglog.png

Usage:
  pip install -r python/requirements-experiments.txt
  python analyze_experiment_metrics.py
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import platform
from datetime import datetime, timezone
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from stats_report import (
    MINIMAL_HEADER,
    format_markdown_table,
    stats_row,
)

PROJECT_ROOT = Path(__file__).resolve().parent.parent

PREFERRED_DATASET_ORDER = ["pole", "fib25", "twitch"]

RDF_STAGE_COLS = [
    ("Clear", "t_clear_s"),
    ("Import", "t_import_s"),
    ("Export", "t_export_s"),
    ("Verify", "t_verify_s"),
]

PG_STAGE_COLS = [
    ("Clear", "t_clear_s"),
    ("GraphML", "t_graphml_s"),
    ("Export", "t_rdf_export_s"),
    ("Import", "t_rdf_import_s"),
    ("Re-export", "t_rdf_reexport_s"),
    ("Verify", "t_verify_s"),
]


def ordered_datasets(found: set[str]) -> list[str]:
    ordered = [d for d in PREFERRED_DATASET_ORDER if d in found]
    ordered.extend(sorted(d for d in found if d not in PREFERRED_DATASET_ORDER))
    return ordered


def group_values(rows: list[dict], col: str) -> list[float]:
    return [v for r in rows if (v := float_col(r, col)) is not None]


def plot_stage_boxplots(
    groups: dict[float, list[dict]],
    x_values: list[float],
    labels: list[str],
    stage_cols: list[tuple[str, str]],
    xlabel: str,
    title: str,
    path: Path,
) -> None:
    n_groups = len(x_values)
    n_stages = len(stage_cols)
    if n_groups == 0 or n_stages == 0:
        return

    fig, axes = plt.subplots(1, n_stages, figsize=(max(3 * n_stages, 10), 5), squeeze=False)
    for stage_idx, (stage_name, col) in enumerate(stage_cols):
        ax = axes[0, stage_idx]
        data = [group_values(groups[x], col) for x in x_values]
        data = [d if d else [float("nan")] for d in data]
        ax.boxplot(data, labels=labels)
        ax.set_title(stage_name)
        ax.set_xlabel(xlabel if stage_idx == 0 else "")
        ax.set_ylabel("Time (s)")
        ax.grid(True, axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=15)
    fig.suptitle(title)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def plot_stage_fraction_stacked(
    groups: dict[float, list[dict]],
    x_values: list[float],
    labels: list[str],
    stage_cols: list[tuple[str, str]],
    xlabel: str,
    title: str,
    path: Path,
) -> None:
    means: list[list[float]] = []
    for x in x_values:
        group = groups[x]
        stage_means = []
        for _, col in stage_cols:
            vals = group_values(group, col)
            stage_means.append(float(np.mean(vals)) if vals else 0.0)
        total = sum(stage_means) or 1.0
        means.append([100.0 * v / total for v in stage_means])

    fig, ax = plt.subplots(figsize=(max(6, len(labels) * 1.5), 5))
    indices = np.arange(len(labels))
    bottom = np.zeros(len(labels))
    for stage_idx, (stage_name, _) in enumerate(stage_cols):
        vals = [means[j][stage_idx] for j in range(len(labels))]
        ax.bar(indices, vals, bottom=bottom, label=stage_name)
        bottom += np.array(vals)
    ax.set_xticks(indices)
    ax.set_xticklabels(labels)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Share of pipeline time (%)")
    ax.set_title(title)
    ax.legend(fontsize=7, loc="upper right")
    ax.grid(True, axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def plot_throughput_bars(
    groups: dict[float, list[dict]],
    x_values: list[float],
    labels: list[str],
    xlabel: str,
    title: str,
    path: Path,
) -> None:
    nodes_per_s = []
    edges_per_s = []
    for x in x_values:
        group = groups[x]
        totals = group_values(group, "t_total_s")
        if not totals:
            nodes_per_s.append(0.0)
            edges_per_s.append(0.0)
            continue
        mean_total = float(np.mean(totals))
        nodes = float(group[0].get("nodes") or 0)
        edges = float(group[0].get("edges") or 0)
        nodes_per_s.append(nodes / mean_total if mean_total > 0 else 0.0)
        edges_per_s.append(edges / mean_total if mean_total > 0 else 0.0)

    fig, ax = plt.subplots(figsize=(max(6, len(labels) * 1.5), 5))
    indices = np.arange(len(labels))
    width = 0.35
    ax.bar(indices - width / 2, nodes_per_s, width, label="Nodes / s")
    ax.bar(indices + width / 2, edges_per_s, width, label="Edges / s")
    ax.set_xticks(indices)
    ax.set_xticklabels(labels)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Throughput")
    ax.set_title(title)
    ax.legend()
    ax.grid(True, axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def plot_scaling_by_size(
    x_numeric: list[float],
    labels: list[str],
    mean_totals: list[float],
    std_totals: list[float],
    xlabel: str,
    title: str,
    path: Path,
    log_x: bool = False,
) -> None:
    if len(x_numeric) < 2:
        return
    x = np.array(x_numeric, dtype=float)
    y = np.array(mean_totals, dtype=float)
    yerr = np.array(std_totals, dtype=float)
    r2, _ = r_squared(x, y)

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.errorbar(x, y, yerr=yerr, fmt="o", capsize=4, label="Mean ± std")
    x_line = np.linspace(x.min(), x.max(), 100)
    coeffs = np.polyfit(x, y, 1)
    ax.plot(x_line, np.polyval(coeffs, x_line), "r--", label=f"Linear fit (R²={r2:.4f})")
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Total time (s)")
    ax.set_title(title)
    if log_x:
        ax.set_xscale("log")
    ax.legend()
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def plot_compare_directions(
    rdf_ok: list[dict],
    pg_ok: list[dict],
    plots_dir: Path,
) -> None:
    _, rdf_x, rdf_groups = rdf_group_keys(rdf_ok)
    _, pg_x, pg_groups = pg_group_keys(pg_ok)

    rdf_labels = [format_scale_label(x) for x in rdf_x]
    pg_labels = [
        pg_groups[x][0].get("dataset", str(int(x)))
        if pg_groups[x]
        else str(int(x))
        for x in pg_x
    ]

    rdf_means = [
        float(np.mean(group_values(rdf_groups[x], "t_total_s"))) if rdf_groups[x] else 0.0
        for x in rdf_x
    ]
    pg_means = [
        float(np.mean(group_values(pg_groups[x], "t_total_s"))) if pg_groups[x] else 0.0
        for x in pg_x
    ]

    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    axes[0].bar(rdf_labels, rdf_means, color="steelblue")
    axes[0].set_title("RDF12 → PG → RDF12")
    axes[0].set_ylabel("Mean total time (s)")
    axes[0].grid(True, axis="y", alpha=0.3)

    axes[1].bar(pg_labels, pg_means, color="darkorange")
    axes[1].set_title("PG → RDF12 → PG")
    axes[1].set_ylabel("Mean total time (s)")
    axes[1].grid(True, axis="y", alpha=0.3)

    fig.suptitle("Round-trip mean total time — dataset comparison")
    fig.tight_layout()
    fig.savefig(plots_dir / "compare_directions_mean_total.png", dpi=150)
    plt.close(fig)

    rdf_blocks = [float(rdf_groups[x][0].get("blocks") or 0) for x in rdf_x if rdf_groups[x]]
    pg_nodes = [float(pg_groups[x][0].get("nodes") or 0) for x in pg_x if pg_groups[x]]
    if rdf_blocks and pg_nodes:
        fig, ax = plt.subplots(figsize=(8, 5))
        ax.plot(rdf_blocks, rdf_means[: len(rdf_blocks)], "o-", label="RDF (blocks)")
        ax.plot(pg_nodes, pg_means[: len(pg_nodes)], "s-", label="PG (nodes)")
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("Dataset size (blocks / nodes, log)")
        ax.set_ylabel("Mean total time (s, log)")
        ax.set_title("Scaling overview — RDF vs PG")
        ax.legend()
        ax.grid(True, alpha=0.3)
        fig.tight_layout()
        fig.savefig(plots_dir / "compare_scaling_loglog.png", dpi=150)
        plt.close(fig)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Analyze experiment metrics and generate plots")
    p.add_argument(
        "--rdf-metrics",
        type=Path,
        default=PROJECT_ROOT / "results" / "metrics.csv",
    )
    p.add_argument(
        "--pg-metrics",
        type=Path,
        default=PROJECT_ROOT / "results" / "metrics_pg.csv",
    )
    p.add_argument(
        "--output-dir",
        type=Path,
        default=PROJECT_ROOT / "results",
    )
    p.add_argument(
        "--no-plots",
        action="store_true",
        help="Skip PNG generation",
    )
    return p.parse_args()


def load_csv(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    rows = []
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    return rows


def load_json(path: Path) -> dict:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def float_col(row: dict, key: str) -> float | None:
    val = row.get(key, "")
    if val is None or str(val).strip() == "":
        return None
    try:
        return float(val)
    except ValueError:
        return None


def r_squared(x: np.ndarray, y: np.ndarray) -> tuple[float, np.ndarray]:
    if len(x) < 2:
        return float("nan"), np.array([])
    coeffs = np.polyfit(x, y, 1)
    y_pred = np.polyval(coeffs, x)
    ss_res = np.sum((y - y_pred) ** 2)
    ss_tot = np.sum((y - np.mean(y)) ** 2)
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    return r2, y_pred


def format_scale_label(scale: float) -> str:
    if math.isclose(scale, 1 / 3, abs_tol=0.02):
        return "1/3"
    if math.isclose(scale, 2 / 3, abs_tol=0.02):
        return "2/3"
    if math.isclose(scale, 1.0, abs_tol=0.02):
        return "1"
    return f"{scale:.0%}"


def rdf_group_keys(ok_rows: list[dict]) -> tuple[str, list[float], dict[float, list[dict]]]:
    """Group RDF rows by sample_scale (preferred) or blocks."""
    if any((r.get("sample_scale") or "").strip() for r in ok_rows):
        scales = sorted({
            float(r["sample_scale"])
            for r in ok_rows
            if (r.get("sample_scale") or "").strip()
        })
        by_scale = {s: [r for r in ok_rows if float_col(r, "sample_scale") == s] for s in scales}
        return "scale", scales, by_scale

    blocks_list = sorted({int(r["blocks"]) for r in ok_rows if r.get("blocks")})
    by_blocks = {float(b): [r for r in ok_rows if int(r["blocks"]) == b] for b in blocks_list}
    return "blocks", [float(b) for b in blocks_list], by_blocks


def pg_group_keys(ok_rows: list[dict]) -> tuple[str, list[float], dict[float, list[dict]]]:
    if any((r.get("dataset") or "").strip() for r in ok_rows):
        datasets = ordered_datasets({r["dataset"] for r in ok_rows if (r.get("dataset") or "").strip()})
        if len(datasets) > 1:
            by_ds = {float(i): [r for r in ok_rows if r.get("dataset") == ds] for i, ds in enumerate(datasets)}
            return "dataset", [float(i) for i in range(len(datasets))], by_ds

    if any((r.get("sample_scale") or "").strip() for r in ok_rows):
        scales = sorted({
            float(r["sample_scale"])
            for r in ok_rows
            if (r.get("sample_scale") or "").strip()
        })
        by_scale = {s: [r for r in ok_rows if float_col(r, "sample_scale") == s] for s in scales}
        return "scale", scales, by_scale

    nodes_list = sorted({int(r["nodes"]) for r in ok_rows if r.get("nodes")})
    by_nodes = {float(n): [r for r in ok_rows if int(r["nodes"]) == n] for n in nodes_list}
    return "nodes", [float(n) for n in nodes_list], by_nodes


def analyze_rdf(rows: list[dict], output_dir: Path, plots_dir: Path, no_plots: bool) -> list[str]:
    ok_rows = [r for r in rows if r.get("verify_ok") == "ok"]
    if not ok_rows:
        return ["No successful RDF runs."]

    group_mode, x_values, groups = rdf_group_keys(ok_rows)

    md_sections = ["# RDF12 → PG → RDF12 — statystyki minimalne", ""]
    md_sections.append(f"Źródło: `{output_dir / 'metrics.csv'}`")
    md_sections.append(f"Pomyślne pomiary: {len(ok_rows)}")
    md_sections.append("")

    summary_lines = [
        "RDF experiment metrics summary",
        "========================",
        f"Successful runs: {len(ok_rows)}",
        "",
    ]

    mean_x = []
    mean_totals = []
    mean_imports = []
    mean_exports = []
    mean_verifies = []
    std_totals = []
    box_data = []
    box_labels = []

    for x in x_values:
        group = groups[x]
        if group_mode == "scale":
            title = f"Próbka: {format_scale_label(x)} datasetu ({int(group[0].get('blocks', 0))} bloków)"
        else:
            title = f"Próbka: {int(x)} bloków reifikacji"
        stages = []
        if any(float_col(r, "t_clear_s") is not None for r in group):
            stages.append(("Clear (Neo4j)", "t_clear_s"))
        stages.extend([
            ("Import (RDF→Neo4j)", "t_import_s"),
            ("Export (Neo4j→RDF)", "t_export_s"),
            ("Verify", "t_verify_s"),
            ("Total (pipeline)", "t_total_s"),
        ])
        stage_rows = []
        for label, col in stages:
            vals = [float_col(r, col) for r in group]
            vals = [v for v in vals if v is not None]
            stage_rows.append(stats_row(label, vals))

        md_sections.append(
            format_markdown_table(title, MINIMAL_HEADER, stage_rows)
        )

        totals = np.array([float_col(r, "t_total_s") for r in group if float_col(r, "t_total_s") is not None])
        imports = np.array([float_col(r, "t_import_s") for r in group if float_col(r, "t_import_s") is not None])
        exports = np.array([float_col(r, "t_export_s") for r in group if float_col(r, "t_export_s") is not None])
        verifies = np.array([float_col(r, "t_verify_s") for r in group if float_col(r, "t_verify_s") is not None])

        if len(totals) == 0:
            continue

        m_total = float(np.mean(totals))
        s_total = float(np.std(totals, ddof=1)) if len(totals) > 1 else 0.0
        mean_x.append(x)
        mean_totals.append(m_total)
        mean_imports.append(float(np.mean(imports)))
        mean_exports.append(float(np.mean(exports)))
        mean_verifies.append(float(np.mean(verifies)))
        std_totals.append(s_total)
        box_data.append(totals)
        box_labels.append(format_scale_label(x) if group_mode == "scale" else str(int(x)))

        summary_lines.append(
            f"{'Scale' if group_mode == 'scale' else 'Blocks'} {box_labels[-1]}: "
            f"runs={len(group)} mean_total={m_total:.3f}s std={s_total:.3f}s"
        )

    (output_dir / "summary_rdf.md").write_text("\n".join(md_sections), encoding="utf-8")

    if not no_plots and box_data:
        fig, ax = plt.subplots(figsize=(max(6, len(box_labels) * 1.2), 5))
        ax.boxplot(box_data)
        ax.set_xticks(range(1, len(box_labels) + 1))
        ax.set_xticklabels(box_labels)
        ax.set_xlabel("Dataset fraction" if group_mode == "scale" else "Reification blocks")
        ax.set_ylabel("Total time (s)")
        ax.set_title("RDF round-trip — distribution over repetitions")
        ax.grid(True, axis="y", alpha=0.3)
        fig.tight_layout()
        fig.savefig(plots_dir / "rdf_total_boxplot.png", dpi=150)
        plt.close(fig)

        fig, ax = plt.subplots(figsize=(max(6, len(box_labels) * 1.2), 5))
        width = 0.25
        indices = np.arange(len(mean_x))
        ax.bar(indices - width, mean_imports, width, label="Import")
        ax.bar(indices, mean_exports, width, label="Export")
        ax.bar(indices + width, mean_verifies, width, label="Verify")
        ax.set_xticks(indices)
        ax.set_xticklabels(box_labels)
        ax.set_xlabel("Dataset fraction" if group_mode == "scale" else "Reification blocks")
        ax.set_ylabel("Mean time (s)")
        ax.set_title("RDF round-trip — mean time per stage")
        ax.legend()
        ax.grid(True, axis="y", alpha=0.3)
        fig.tight_layout()
        fig.savefig(plots_dir / "rdf_stages_mean.png", dpi=150)
        plt.close(fig)

        if len(mean_x) >= 2:
            x = np.array(mean_x, dtype=float)
            y = np.array(mean_totals, dtype=float)
            r2, _ = r_squared(x, y)
            fig, ax = plt.subplots(figsize=(8, 5))
            ax.errorbar(x, y, yerr=std_totals, fmt="o", capsize=4, label="Mean ± std")
            x_line = np.linspace(x.min(), x.max(), 100)
            coeffs = np.polyfit(x, y, 1)
            ax.plot(x_line, np.polyval(coeffs, x_line), "r--", label=f"Linear fit (R²={r2:.4f})")
            ax.set_xticks(x)
            ax.set_xticklabels(box_labels)
            ax.set_xlabel("Dataset fraction" if group_mode == "scale" else "Reification blocks")
            ax.set_ylabel("Total time (s)")
            ax.set_title("RDF round-trip scaling")
            ax.legend()
            ax.grid(True, alpha=0.3)
            fig.tight_layout()
            fig.savefig(plots_dir / "scaling_total.png", dpi=150)
            plt.close(fig)

            summary_lines.extend([
                "",
                f"Scaling R² = {r2:.4f}",
                f"Slope (s/block) = {coeffs[0]:.6f}",
            ])

        plot_stage_boxplots(
            groups, x_values, box_labels, RDF_STAGE_COLS,
            "Dataset fraction" if group_mode == "scale" else "Reification blocks",
            "RDF round-trip — stage distributions (all runs)",
            plots_dir / "rdf_stages_boxplot.png",
        )
        plot_stage_fraction_stacked(
            groups, x_values, box_labels, RDF_STAGE_COLS,
            "Dataset fraction" if group_mode == "scale" else "Reification blocks",
            "RDF round-trip — mean stage time share (%)",
            plots_dir / "rdf_stage_fraction.png",
        )
        if any(float_col(r, "t_clear_s") is not None for r in ok_rows):
            plot_stage_boxplots(
                groups, x_values, box_labels, [("Clear", "t_clear_s")],
                "Dataset fraction" if group_mode == "scale" else "Blocks",
                "RDF — Neo4j clear time",
                plots_dir / "rdf_clear_boxplot.png",
            )
        if group_mode == "blocks" and len(mean_x) >= 2:
            blocks_x = [float(groups[x][0].get("blocks") or x) for x in x_values]
            plot_scaling_by_size(
                blocks_x, box_labels, mean_totals, std_totals,
                "Reification blocks", "RDF total time vs blocks",
                plots_dir / "rdf_blocks_scaling.png",
            )

    return summary_lines


def analyze_pg(rows: list[dict], output_dir: Path, plots_dir: Path, no_plots: bool) -> list[str]:
    ok_rows = [r for r in rows if r.get("verify_ok") == "ok"]
    if not ok_rows:
        return ["No successful PG runs."]

    meta = load_json(output_dir / "experiment_meta_pg.json")
    group_mode, x_values, groups = pg_group_keys(ok_rows)

    md = [
        "# PG → RDF12 → PG — statystyki minimalne",
        "",
        f"Źródło: `{output_dir / 'metrics_pg.csv'}`",
        f"Pomyślne pomiary: {len(ok_rows)}",
        "",
    ]

    summary_lines = ["", "PG experiment summary", f"Successful runs: {len(ok_rows)}"]

    mean_x = []
    mean_totals = []
    std_totals = []
    box_data = []
    box_labels = []
    stage_means_by_sample: list[list[float]] = []
    stage_labels = [
        ("GraphML→Neo4j", "t_graphml_s"),
        ("Neo4j→RDF", "t_rdf_export_s"),
        ("RDF→Neo4j", "t_rdf_import_s"),
        ("Neo4j→RDF RT", "t_rdf_reexport_s"),
        ("Verify", "t_verify_s"),
    ]

    for x in x_values:
        group = groups[x]
        if group_mode == "scale":
            title = f"Próbka: {format_scale_label(x)} datasetu (~{group[0].get('nodes', '?')} węzłów)"
        elif group_mode == "dataset":
            title = f"Dataset: {group[0].get('dataset', '?')} (~{group[0].get('nodes', '?')} węzłów)"
        else:
            title = f"Próbka: ~{int(x)} węzłów"

        stages = []
        if any(float_col(r, "t_clear_s") is not None for r in group):
            stages.append(("Clear (Neo4j)", "t_clear_s"))
        stages.extend([
            ("GraphML → Neo4j", "t_graphml_s"),
            ("Neo4j → RDF", "t_rdf_export_s"),
            ("RDF → Neo4j", "t_rdf_import_s"),
            ("Neo4j → RDF (round-trip)", "t_rdf_reexport_s"),
            ("Verify", "t_verify_s"),
            ("Total (pipeline)", "t_total_s"),
        ])
        stage_rows = []
        for label, col in stages:
            vals = [float_col(r, col) for r in group]
            vals = [v for v in vals if v is not None]
            stage_rows.append(stats_row(label, vals))
        md.append(format_markdown_table(title, MINIMAL_HEADER, stage_rows))

        totals = [float_col(r, "t_total_s") for r in group]
        totals = [v for v in totals if v is not None]
        if not totals:
            continue

        mean_x.append(x)
        mean_totals.append(float(np.mean(totals)))
        std_totals.append(float(np.std(totals, ddof=1)) if len(totals) > 1 else 0.0)
        box_data.append(np.array(totals))
        box_labels.append(
            group[0].get("dataset", "")
            if group_mode == "dataset"
            else format_scale_label(x) if group_mode == "scale"
            else str(int(x))
        )

        sample_stages = []
        for _, col in stage_labels:
            vals = [float_col(r, col) for r in group]
            vals = [v for v in vals if v is not None]
            sample_stages.append(float(np.mean(vals)) if vals else 0.0)
        stage_means_by_sample.append(sample_stages)

        summary_lines.append(
            f"{'Dataset' if group_mode == 'dataset' else 'Scale' if group_mode == 'scale' else 'Nodes'} {box_labels[-1]}: "
            f"runs={len(group)} mean_total={mean_totals[-1]:.3f}s"
        )

    (output_dir / "summary_pg.md").write_text("\n".join(md), encoding="utf-8")

    if not no_plots and box_data:
        fig, ax = plt.subplots(figsize=(max(6, len(box_labels) * 1.5), 5))
        ax.boxplot(box_data)
        ax.set_xticks(range(1, len(box_labels) + 1))
        ax.set_xticklabels(box_labels)
        ax.set_xlabel(
            "Dataset"
            if group_mode == "dataset"
            else "Dataset fraction" if group_mode == "scale"
            else "Nodes"
        )
        ax.set_ylabel("Total time (s)")
        ax.set_title("PG round-trip — distribution over repetitions")
        ax.grid(True, axis="y", alpha=0.3)
        fig.tight_layout()
        fig.savefig(plots_dir / "pg_total_boxplot.png", dpi=150)
        plt.close(fig)

        if stage_means_by_sample:
            fig, ax = plt.subplots(figsize=(max(8, len(box_labels) * 2), 5))
            indices = np.arange(len(box_labels))
            width = 0.15
            for i, (label, _) in enumerate(stage_labels):
                vals = [stage_means_by_sample[j][i] for j in range(len(box_labels))]
                ax.bar(indices + (i - 2) * width, vals, width, label=label)
            ax.set_xticks(indices)
            ax.set_xticklabels(box_labels)
            ax.set_xlabel(
            "Dataset"
            if group_mode == "dataset"
            else "Dataset fraction" if group_mode == "scale"
            else "Nodes"
        )
            ax.set_ylabel("Mean time (s)")
            ax.set_title("PG round-trip — mean time per stage")
            ax.legend(fontsize=7)
            ax.grid(True, axis="y", alpha=0.3)
            fig.tight_layout()
            fig.savefig(plots_dir / "pg_stages_mean.png", dpi=150)
            plt.close(fig)

        if len(mean_x) >= 2:
            x = np.array(mean_x, dtype=float)
            y = np.array(mean_totals, dtype=float)
            r2, _ = r_squared(x, y)
            fig, ax = plt.subplots(figsize=(8, 5))
            ax.errorbar(x, y, yerr=std_totals, fmt="o", capsize=4, label="Mean ± std")
            x_line = np.linspace(x.min(), x.max(), 100)
            coeffs = np.polyfit(x, y, 1)
            ax.plot(x_line, np.polyval(coeffs, x_line), "r--", label=f"Linear fit (R²={r2:.4f})")
            ax.set_xticks(x)
            ax.set_xticklabels(box_labels)
            ax.set_xlabel(
            "Dataset"
            if group_mode == "dataset"
            else "Dataset fraction" if group_mode == "scale"
            else "Nodes"
        )
            ax.set_ylabel("Total time (s)")
            ax.set_title("PG round-trip scaling")
            ax.legend()
            ax.grid(True, alpha=0.3)
            fig.tight_layout()
            fig.savefig(plots_dir / "pg_scaling_total.png", dpi=150)
            plt.close(fig)

            summary_lines.extend([
                "",
                f"PG scaling R² = {r2:.4f}",
            ])

        xlabel = (
            "Dataset"
            if group_mode == "dataset"
            else "Dataset fraction" if group_mode == "scale"
            else "Nodes"
        )
        plot_stage_boxplots(
            groups, x_values, box_labels, PG_STAGE_COLS,
            xlabel, "PG round-trip — stage distributions (all runs)",
            plots_dir / "pg_stages_boxplot.png",
        )
        plot_stage_fraction_stacked(
            groups, x_values, box_labels, PG_STAGE_COLS,
            xlabel, "PG round-trip — mean stage time share (%)",
            plots_dir / "pg_stage_fraction.png",
        )
        if any(float_col(r, "t_clear_s") is not None for r in ok_rows):
            plot_stage_boxplots(
                groups, x_values, box_labels, [("Clear", "t_clear_s")],
                xlabel, "PG — Neo4j clear time",
                plots_dir / "pg_clear_boxplot.png",
            )
        if group_mode == "dataset":
            nodes_x = [float(groups[x][0].get("nodes") or 1) for x in x_values]
            plot_scaling_by_size(
                nodes_x, box_labels, mean_totals, std_totals,
                "Nodes (log scale)", "PG total time vs graph size",
                plots_dir / "pg_scaling_by_nodes.png", log_x=True,
            )
            plot_throughput_bars(
                groups, x_values, box_labels, xlabel,
                "PG throughput (nodes/s and edges/s)",
                plots_dir / "pg_throughput.png",
            )

    return summary_lines


def write_experiment_conditions(output_dir: Path, rdf_meta: dict, pg_meta: dict) -> None:
    lines = [
        "# Warunki eksperymentu",
        "",
        f"Data analizy: {datetime.now(timezone.utc).isoformat()}",
        f"System: {platform.system()} {platform.release()}, Python {platform.python_version()}",
        "",
    ]

    if rdf_meta:
        lines.extend([
            "## RDF12 → PG → RDF12",
            "",
            "| Parametr | Wartość |",
            "| --- | --- |",
            f"| Uruchomienia pomiarowe | {rdf_meta.get('repeats', '?')} |",
            f"| Rozgrzewka (pominięta w CSV) | {rdf_meta.get('warmup', '?')} |",
            f"| Neo4j URI | `{rdf_meta.get('neo4j_uri', '?')}` |",
            f"| Kontener | `{rdf_meta.get('neo4j_container', '?')}` |",
            f"| MAVEN_OPTS | `{rdf_meta.get('maven_opts', '?')}` |",
            f"| Próbki | {', '.join(str(s) for s in rdf_meta.get('samples', []))} |",
            "",
        ])

    if pg_meta:
        lines.extend([
            "## PG → RDF12 → PG",
            "",
            "| Parametr | Wartość |",
            "| --- | --- |",
            f"| Uruchomienia pomiarowe | {pg_meta.get('repeats', '?')} |",
            f"| Rozgrzewka (pominięta w CSV) | {pg_meta.get('warmup', '?')} |",
            f"| Neo4j URI | `{pg_meta.get('neo4j_uri', '?')}` |",
            f"| Kontener | `{pg_meta.get('neo4j_container', '?')}` |",
            f"| GraphML | `{pg_meta.get('graphml_file', '?')}` |",
            f"| Rozmiar pliku [B] | {pg_meta.get('graphml_bytes', '?')} |",
            f"| Węzły / krawędzie | ~{pg_meta.get('nodes', '?')} / ~{pg_meta.get('edges', '?')} |",
            f"| MAVEN_OPTS | `{pg_meta.get('maven_opts', '?')}` |",
            "",
        ])

    lines.extend([
        "## Metodologia",
        "",
        "- Czas mierzony jako wall-clock całego `mvn exec:java` per etap (łącznie ze startem JVM).",
        "- Przed każdym runem baza Neo4j jest czyszczona (reset wolumenu Docker lub batch delete).",
        "- Pierwsze uruchomienia (warmup) są pomijane w statystykach — typowe dla JVM.",
        "- Statystyki minimalne: n, średnia, mediana, odchylenie standardowe, min, max (w ms).",
        "",
    ])

    (output_dir / "experiment_conditions.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    plots_dir = args.output_dir / "plots"
    plots_dir.mkdir(parents=True, exist_ok=True)

    rdf_rows = load_csv(args.rdf_metrics)
    pg_rows = load_csv(args.pg_metrics)

    if not rdf_rows and not pg_rows:
        raise SystemExit(
            f"No metrics found.\n  RDF: {args.rdf_metrics}\n  PG: {args.pg_metrics}"
        )

    all_summary: list[str] = ["Experiment analysis", "=================", ""]

    if rdf_rows:
        all_summary.extend(analyze_rdf(rdf_rows, args.output_dir, plots_dir, args.no_plots))
    if pg_rows:
        all_summary.extend(analyze_pg(pg_rows, args.output_dir, plots_dir, args.no_plots))

    rdf_ok = [r for r in rdf_rows if r.get("verify_ok") == "ok"]
    pg_ok = [r for r in pg_rows if r.get("verify_ok") == "ok"]
    if not args.no_plots and rdf_ok and pg_ok:
        plot_compare_directions(rdf_ok, pg_ok, plots_dir)

    rdf_meta = load_json(args.output_dir / "experiment_meta_rdf.json")
    pg_meta = load_json(args.output_dir / "experiment_meta_pg.json")
    write_experiment_conditions(args.output_dir, rdf_meta, pg_meta)

    summary_path = args.output_dir / "summary.txt"
    summary_path.write_text("\n".join(all_summary) + "\n", encoding="utf-8")
    print("\n".join(all_summary))
    print(f"\nMarkdown: {args.output_dir}/summary_rdf.md, summary_pg.md, experiment_conditions.md")
    print(f"Plots: {plots_dir}/")


if __name__ == "__main__":
    main()
