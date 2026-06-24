"""Minimal timing statistics and markdown tables for experiment reports."""

from __future__ import annotations

import statistics
from typing import Any


def seconds_to_ms(values_s: list[float]) -> list[float]:
    return [v * 1000.0 for v in values_s]


def minimal_stats(times_ms: list[float]) -> dict[str, Any]:
    if not times_ms:
        return {
            "n": 0,
            "mean": float("nan"),
            "median": float("nan"),
            "stdev": float("nan"),
            "min": float("nan"),
            "max": float("nan"),
        }
    n = len(times_ms)
    mean = statistics.mean(times_ms)
    median = statistics.median(times_ms)
    stdev = statistics.stdev(times_ms) if n > 1 else 0.0
    return {
        "n": n,
        "mean": mean,
        "median": median,
        "stdev": stdev,
        "min": min(times_ms),
        "max": max(times_ms),
    }


def detect_outliers_iqr(times_ms: list[float]) -> list[float]:
    if len(times_ms) < 4:
        return []
    sorted_vals = sorted(times_ms)
    q1 = statistics.quantiles(sorted_vals, n=4)[0]
    q3 = statistics.quantiles(sorted_vals, n=4)[2]
    iqr = q3 - q1
    lower = q1 - 1.5 * iqr
    upper = q3 + 1.5 * iqr
    return [v for v in times_ms if v < lower or v > upper]


def fmt_ms(value: float) -> str:
    if value != value:  # NaN
        return "—"
    return f"{value:.1f}"


MINIMAL_HEADER = [
    "Etap / grupa",
    "Liczba pomiarów",
    "Średnia [ms]",
    "Mediana [ms]",
    "Odchylenie standardowe [ms]",
    "Minimum [ms]",
    "Maksimum [ms]",
]


def stats_row(label: str, times_s: list[float]) -> list[str]:
    stats = minimal_stats(seconds_to_ms(times_s))
    return [
        label,
        str(stats["n"]),
        fmt_ms(stats["mean"]),
        fmt_ms(stats["median"]),
        fmt_ms(stats["stdev"]),
        fmt_ms(stats["min"]),
        fmt_ms(stats["max"]),
    ]


def format_markdown_table(title: str, headers: list[str], rows: list[list[str]]) -> str:
    lines = [f"## {title}", ""]
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join("---:" if i > 0 else "---" for i in range(len(headers))) + " |")
    for row in rows:
        lines.append("| " + " | ".join(row) + " |")
    lines.append("")
    return "\n".join(lines)


def write_conditions_md(
    path,
    *,
    direction: str,
    repeats: int,
    warmup: int,
    neo4j_uri: str,
    neo4j_container: str,
    input_description: str,
    maven_opts: str,
    extra_notes: list[str] | None = None,
) -> None:
    lines = [
        f"# Warunki eksperymentu — {direction}",
        "",
        "| Parametr | Wartość |",
        "| --- | --- |",
        f"| Kierunek translacji | {direction} |",
        f"| Uruchomienia pomiarowe | {repeats} |",
        f"| Uruchomienia rozgrzewkowe (pominięte w statystykach) | {warmup} |",
        f"| Neo4j URI | `{neo4j_uri}` |",
        f"| Kontener Neo4j | `{neo4j_container}` |",
        f"| Dane wejściowe | {input_description} |",
        f"| MAVEN_OPTS | `{maven_opts}` |",
        "| Czas mierzony | Wall-clock całego `mvn exec:java` per etap (w tym start JVM) |",
        "| Czyszczenie bazy | Przed każdym runem (batch delete / APOC) |",
        "| Start JVM | Pierwsze uruchomienia mniej reprezentatywne — oddzielone warmup |",
        "",
    ]
    if extra_notes:
        lines.append("## Uwagi")
        lines.append("")
        for note in extra_notes:
            lines.append(f"- {note}")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")
