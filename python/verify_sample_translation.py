#!/usr/bin/env python3
"""
Weryfikuje spójność wynik-sample-42.nt z oczekiwaniami translacji RDF 1.2 → PG.

Opcjonalnie: porównanie z Neo4j (bolt://localhost:7688) jeśli neo4j driver zainstalowany
  pip install neo4j
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies"
INNER_RE = re.compile(
    r"reifies>\s*<<\(\s*<([^>]+)>\s*<([^>]+)>\s*(.+?)\s*\)>>",
    re.DOTALL,
)
ANNOT_RE = re.compile(r"^(_:\S+)\s+<([^>]+)>\s+(.+)$")


def parse_nt(path: Path) -> list[dict]:
    blocks: dict[str, dict] = {}
    order: list[str] = []

    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("VERSION") or s.startswith("#"):
            continue
        if REIFIES in s and "<<" in s:
            m = INNER_RE.search(s)
            if not m:
                continue
            subj, pred, obj_raw = m.group(1), m.group(2), m.group(3).strip()
            rid = s.split()[0]
            obj_is_literal = obj_raw.startswith('"')
            blocks[rid] = {
                "reifier": rid,
                "subject": subj,
                "predicate": pred,
                "object": obj_raw,
                "object_literal": obj_is_literal,
                "annotations": {},
            }
            order.append(rid)
            continue
        m = ANNOT_RE.match(s)
        if m and m.group(1) in blocks:
            blocks[m.group(1)]["annotations"][m.group(2)] = m.group(3)

    return [blocks[r] for r in order]


def verify_nt(facts: list[dict]) -> list[str]:
    errors: list[str] = []
    n = len(facts)
    if n != 42:
        errors.append(f"Oczekiwano 42 bloków reifikacji, jest {n}")

    res_res = sum(1 for f in facts if not f["object_literal"])
    res_lit = sum(1 for f in facts if f["object_literal"])
    if res_res != 40:
        errors.append(f"Oczekiwano 40 faktów Resource→Resource, jest {res_res}")
    if res_lit != 2:
        errors.append(f"Oczekiwano 2 faktów Resource→Literal, jest {res_lit}")

    carolina = [f for f in facts if "Carolina_Dodge_Dealers_400" in f["subject"]]
    if not carolina:
        errors.append("Brak bloku Carolina_Dodge_Dealers_400")
    elif "http://schema.org/endDate" not in carolina[0]["annotations"]:
        errors.append("Carolina: brak annotacji endDate w NT (ucięty blok?)")
    elif "2004" not in carolina[0]["annotations"]["http://schema.org/endDate"]:
        errors.append("Carolina: endDate powinno zawierać 2004")

    subjects = {f["subject"] for f in facts}
    for uri in (
        "http://yago-knowledge.org/resource/_Q56236170",
        "http://yago-knowledge.org/resource/TarO&JirO_Q17213378",
    ):
        if uri not in subjects:
            errors.append(f"Brak podmiotu w NT: {uri}")

    return errors


def verify_neo4j(uri: str, user: str, password: str) -> list[str]:
    try:
        from neo4j import GraphDatabase
    except ImportError:
        return ["neo4j driver nie zainstalowany (pip install neo4j) — pomijam test bazy"]

    errors: list[str] = []
    try:
        with GraphDatabase.driver(uri, auth=(user, password)) as driver:
            with driver.session() as session:
                rel = session.run("MATCH ()-[r:REL]->() RETURN count(r) AS c").single()["c"]
                rr = session.run(
                    "MATCH (:Resource)-[r:REL]->(:Resource) RETURN count(r) AS c"
                ).single()["c"]
                rl = session.run(
                    "MATCH (:Resource)-[r:REL]->(:Literal) RETURN count(r) AS c"
                ).single()["c"]
                if rel != 42:
                    errors.append(f"Neo4j: oczekiwano 42 REL, jest {rel}")
                if rr != 40:
                    errors.append(f"Neo4j: oczekiwano 40 Resource→Resource, jest {rr}")
                if rl != 2:
                    errors.append(f"Neo4j: oczekiwano 2 Resource→Literal, jest {rl}")

                sub = session.run(
                    "MATCH (n:Resource) WHERE n.id IN $ids RETURN n.id AS id",
                    ids=[
                        "http://yago-knowledge.org/resource/_Q56236170",
                        "http://yago-knowledge.org/resource/TarO&JirO_Q17213378",
                    ],
                ).values()
                found = {row[0] for row in sub}
                if len(found) != 2:
                    errors.append(f"Neo4j: brak węzłów dissolutionDate subjects, jest {found}")

                car = session.run(
                    """
                    MATCH (a:Resource {id: $s})-[r:REL]->(b:Resource {id: $t})
                    WHERE r.predicate CONTAINS 'superEvent'
                    RETURN r.http___schema_org_startDate AS sd,
                           r.http___schema_org_endDate AS ed
                    """,
                    s="http://yago-knowledge.org/resource/Carolina_Dodge_Dealers_400",
                    t="http://yago-knowledge.org/resource/NASCAR_Cup_Series",
                ).single()
                if not car:
                    errors.append("Neo4j: brak krawędzi Carolina → NASCAR_Cup_Series")
                elif str(car.get("ed")) != "2004":
                    errors.append(f"Neo4j: Carolina endDate={car.get('ed')}, oczekiwano 2004")

                extra = session.run(
                    """
                    MATCH ()-[r:REL]->()
                    WHERE r.predicate CONTAINS 'subEvent' OR r.predicate CONTAINS 'author'
                    RETURN count(r) AS c
                    """
                ).single()["c"]
                if extra != 0:
                    errors.append(
                        f"Neo4j: nieoczekiwane subEvent/author ({extra}) — baza nie jest czystą próbką?"
                    )
    except Exception as e:
        errors.append(f"Neo4j niedostępny ({uri}): {e}")

    return errors


def write_expected_csv(facts: list[dict], out: Path) -> None:
    """Zapis oczekiwanych krawędzi PG do CSV (do diffu z eksportem Neo4j)."""
    import csv

    rows = []
    for f in facts:
        obj = f["object"]
        if f["object_literal"]:
            m = re.match(r'"([^"]*)"', obj)
            target = m.group(1) if m else obj
        else:
            m = re.search(r"<([^>]+)>", obj)
            target = m.group(1) if m else obj
        row = {
            "source": f["subject"],
            "predicate": f["predicate"],
            "target": target,
            "startDate": "",
            "endDate": "",
        }
        sd = f["annotations"].get("http://schema.org/startDate", "")
        ed = f["annotations"].get("http://schema.org/endDate", "")
        if sd:
            m = re.search(r'"([^"]+)"', sd)
            row["startDate"] = m.group(1) if m else sd
        if ed:
            m = re.search(r'"([^"]+)"', ed)
            row["endDate"] = m.group(1) if m else ed
        rows.append(row)

    with out.open("w", newline="", encoding="utf-8") as fp:
        w = csv.DictWriter(fp, fieldnames=["source", "predicate", "target", "startDate", "endDate"])
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: (r["source"], r["predicate"], r["target"])))
    print(f"Zapisano oczekiwane krawędzie: {out} ({len(rows)} wierszy)")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument(
        "nt_file",
        nargs="?",
        type=Path,
        default=Path("wynik-sample-42.nt"),
    )
    p.add_argument("--neo4j-uri", default="bolt://localhost:7688")
    p.add_argument("--neo4j-user", default="neo4j")
    p.add_argument("--neo4j-password", default="098e540851")
    p.add_argument("--skip-neo4j", action="store_true")
    p.add_argument(
        "--write-expected-csv",
        type=Path,
        metavar="FILE",
        help="Zapisz oczekiwane 42 krawędzie do CSV (porównanie z Neo4j)",
    )
    args = p.parse_args()

    facts = parse_nt(args.nt_file)
    print(f"Plik NT: {args.nt_file} — {len(facts)} bloków reifikacji")

    if args.write_expected_csv:
        write_expected_csv(facts, args.write_expected_csv)

    all_errors: list[str] = []
    all_errors.extend(verify_nt(facts))

    if not args.skip_neo4j:
        print(f"Neo4j: {args.neo4j_uri}")
        all_errors.extend(
            verify_neo4j(args.neo4j_uri, args.neo4j_user, args.neo4j_password)
        )

    if all_errors:
        print("BŁĘDY:")
        for e in all_errors:
            print(f"  - {e}")
        sys.exit(1)

    print("OK — próbka NT (i Neo4j jeśli sprawdzono) zgodne z oczekiwaniami.")


if __name__ == "__main__":
    main()
