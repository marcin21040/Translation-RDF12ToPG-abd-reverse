#!/usr/bin/env python3
"""Set verify_ok=ok for rows matching dataset (after manual verify succeeded)."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    p = argparse.ArgumentParser(description="Mark experiment CSV rows as verify_ok=ok")
    p.add_argument("--csv", type=Path, default=PROJECT_ROOT / "results" / "metrics_multi_pg.csv")
    p.add_argument("--dataset", default=None, help="dataset column value (PG metrics)")
    p.add_argument("--sample-file", default=None, help="sample_file column value (RDF metrics)")
    p.add_argument("--from-status", default="fail_verify", help="only rows with this verify_ok")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    if not args.csv.is_file():
        raise SystemExit(f"CSV not found: {args.csv}")

    if not args.dataset and not args.sample_file:
        args.dataset = "twitch"

    rows: list[dict[str, str]] = []
    updated = 0
    with args.csv.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames
        if not fieldnames:
            raise SystemExit("Empty CSV")
        for row in reader:
            match = row.get("verify_ok") == args.from_status
            if args.sample_file is not None:
                match = match and row.get("sample_file") == args.sample_file
            elif args.dataset is not None:
                match = match and row.get("dataset") == args.dataset
            if match:
                row["verify_ok"] = "ok"
                updated += 1
            rows.append(row)

    if updated == 0:
        label = args.sample_file or args.dataset
        print(f"No rows to update ({label}, verify_ok={args.from_status})")
        return

    print(f"Updated {updated} row(s) in {args.csv} -> verify_ok=ok")
    if args.dry_run:
        return

    with args.csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
