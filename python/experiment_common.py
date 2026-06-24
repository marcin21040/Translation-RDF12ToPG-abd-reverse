"""Shared helpers for round-trip timing experiments."""

from __future__ import annotations

import csv
import os
import subprocess
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies"

DEFAULT_BATCH_DELETE = 500
MIN_BATCH_DELETE = 100
DEFAULT_MAX_CLEAR_ROUNDS = 50000
LARGE_GRAPH_NODES = 50_000

# Container name -> bash script that wipes /data and recreates the container.
RESET_SCRIPTS: dict[str, str] = {
    "neo4j-experiment": "scripts/reset-neo4j-experiment.sh",
    "neo4j-yago": "scripts/reset-neo4j-yago.sh",
    "neo4j-twitch": "scripts/reset-neo4j-twitch.sh",
}


def default_maven_opts() -> str:
    return os.environ.get("MAVEN_OPTS", "-Xmx4g -Xss16m")


def maven_env(base: dict[str, str] | None = None) -> dict[str, str]:
    env = (base or os.environ.copy()).copy()
    env.setdefault("MAVEN_OPTS", default_maven_opts())
    return env


def docker_cmd() -> list[str]:
    raw = os.environ.get("DOCKER_CMD", "docker").strip()
    return raw.split() if raw else ["docker"]


def run_cypher(container: str, password: str, query: str, plain: bool = False) -> subprocess.CompletedProcess:
    cmd = docker_cmd() + [
        "exec", container,
        "cypher-shell", "-u", "neo4j", "-p", password,
        "--non-interactive", query,
    ]
    if plain:
        cmd.insert(-1, "--format")
        cmd.insert(-1, "plain")
    return subprocess.run(cmd, capture_output=True, text=True)


def parse_count(output: str) -> int:
    numbers = []
    for line in output.splitlines():
        s = line.strip()
        if s.isdigit():
            numbers.append(int(s))
    return numbers[-1] if numbers else 0


def _combined_output(proc: subprocess.CompletedProcess) -> str:
    return f"{proc.stderr or ''}\n{proc.stdout or ''}"


def _is_oom(text: str) -> bool:
    t = text.lower()
    return (
        "out of memory" in t
        or "memory pool" in t
        or "51n72" in t
        or "transaction.total.max" in t
    )


def neo4j_stats(container: str, password: str) -> tuple[int, int]:
    """Return (node_count, relationship_count)."""
    proc_n = run_cypher(container, password, "MATCH (n) RETURN count(n)", plain=True)
    if proc_n.returncode != 0:
        err = _combined_output(proc_n).strip()
        raise RuntimeError(f"Cannot count nodes in Neo4j:\n  {err}")
    nodes = parse_count(proc_n.stdout)

    proc_r = run_cypher(container, password, "MATCH ()-[r]->() RETURN count(r)", plain=True)
    if proc_r.returncode != 0:
        err = _combined_output(proc_r).strip()
        raise RuntimeError(f"Cannot count relationships in Neo4j:\n  {err}")
    rels = parse_count(proc_r.stdout)
    return nodes, rels


def assert_neo4j_empty(container: str, password: str) -> None:
    nodes, rels = neo4j_stats(container, password)
    if nodes != 0 or rels != 0:
        raise RuntimeError(
            f"Neo4j not empty after clear: {nodes} nodes, {rels} relationships remaining."
        )


def _try_apoc_clear(container: str, password: str, batch_size: int) -> bool:
    """Run apoc.periodic.iterate delete; return True if graph is empty after."""
    apoc = (
        "CALL apoc.periodic.iterate("
        "'MATCH (n) RETURN id(n) AS id', "
        "'MATCH (n) WHERE id(n) = id DETACH DELETE n', "
        f"{{batchSize: {batch_size}, parallel: false}}) "
        "YIELD batches, total RETURN batches, total"
    )
    proc = run_cypher(container, password, apoc, plain=True)
    if proc.returncode != 0:
        text = _combined_output(proc)
        if "unknown function" in text.lower() or "procedure" in text.lower() and "not" in text.lower():
            return False
        if _is_oom(text):
            return False
    nodes, rels = neo4j_stats(container, password)
    return nodes == 0 and rels == 0


def _delete_batch(container: str, password: str, batch_size: int) -> subprocess.CompletedProcess:
    return run_cypher(
        container,
        password,
        f"MATCH (n) WITH n LIMIT {batch_size} DETACH DELETE n RETURN count(*) AS deleted",
        plain=True,
    )


def clear_neo4j(
    container: str,
    password: str,
    reset_hint: str = "scripts/reset-neo4j-experiment.sh",
    batch_size: int | None = None,
) -> None:
    """Delete all nodes and relationships; verify database is empty before return."""
    if batch_size is None:
        batch_size = int(os.environ.get("NEO4J_CLEAR_BATCH", str(DEFAULT_BATCH_DELETE)))

    nodes, rels = neo4j_stats(container, password)
    if nodes == 0 and rels == 0:
        return

    print(
        f"    Clearing Neo4j ({nodes} nodes, {rels} relationships, batch={batch_size})...",
        flush=True,
    )

    if nodes > 50_000:
        print("    Large graph — trying APOC periodic iterate first...", flush=True)
        for apoc_batch in (batch_size, max(MIN_BATCH_DELETE, batch_size // 2)):
            if _try_apoc_clear(container, password, apoc_batch):
                print("    Cleared via APOC (0 nodes, 0 relationships).", flush=True)
                return
        print("    APOC did not finish in one pass — continuing with batched DELETE...", flush=True)

    prev_nodes = nodes
    stall_rounds = 0
    current_batch = batch_size

    for round_idx in range(1, DEFAULT_MAX_CLEAR_ROUNDS + 1):
        proc = _delete_batch(container, password, current_batch)
        if proc.returncode != 0:
            text = _combined_output(proc)
            if _is_oom(text) and current_batch > MIN_BATCH_DELETE:
                current_batch = max(MIN_BATCH_DELETE, current_batch // 2)
                print(f"    OOM — reducing batch to {current_batch}...", flush=True)
                continue
            raise RuntimeError(
                f"Failed to clear Neo4j (batch delete, batch={current_batch}).\n"
                f"  {text.strip()}\n  Reset DB: bash {reset_hint}"
            )

        nodes, rels = neo4j_stats(container, password)
        if nodes == 0 and rels == 0:
            print("    Cleared (0 nodes, 0 relationships).", flush=True)
            return

        if nodes >= prev_nodes:
            stall_rounds += 1
        else:
            stall_rounds = 0
        prev_nodes = nodes

        if stall_rounds >= 3:
            if current_batch > MIN_BATCH_DELETE:
                current_batch = max(MIN_BATCH_DELETE, current_batch // 2)
                print(f"    No progress — reducing batch to {current_batch}...", flush=True)
                stall_rounds = 0
                continue
            raise RuntimeError(
                f"Clear stuck at {nodes} nodes, {rels} relationships.\n"
                f"  For full wynik.nt import use: bash {reset_hint}"
            )

        if round_idx % 20 == 0 or nodes < 50_000:
            print(f"    ... {nodes} nodes, {rels} rels remaining (batch={current_batch})", flush=True)

    raise RuntimeError(
        f"Clear timed out after {DEFAULT_MAX_CLEAR_ROUNDS} batches.\n  Reset DB: bash {reset_hint}"
    )


def neo4j_clear_mode() -> str:
    """delete | reset | auto (reset when graph is large and a reset script exists)."""
    return os.environ.get("NEO4J_CLEAR_MODE", "auto").strip().lower()


def reset_script_for(container: str) -> str | None:
    return RESET_SCRIPTS.get(container)


def reset_neo4j_via_script(script_relative: str) -> None:
    script = PROJECT_ROOT / script_relative
    if not script.is_file():
        raise RuntimeError(f"Reset script not found: {script}")
    print(f"    Fast reset: {script_relative} (wipe data volume + restart)...", flush=True)
    proc = subprocess.run(
        ["bash", str(script)],
        cwd=PROJECT_ROOT,
        env=os.environ.copy(),
    )
    if proc.returncode != 0:
        raise RuntimeError(f"Neo4j reset failed (exit {proc.returncode}): {script_relative}")


def prepare_neo4j(
    container: str,
    password: str,
    reset_hint: str = "scripts/reset-neo4j-experiment.sh",
    batch_size: int | None = None,
) -> float:
    """
    Prepare an empty Neo4j database between experiment runs.

    Returns seconds spent clearing (not included in Maven stage timings).
    """
    mode = neo4j_clear_mode()
    if mode not in ("delete", "reset", "auto"):
        raise ValueError(f"Unknown NEO4J_CLEAR_MODE={mode!r} (use delete, reset, or auto)")

    nodes, rels = neo4j_stats(container, password)
    if nodes == 0 and rels == 0:
        return 0.0

    use_reset = mode == "reset" or (
        mode == "auto" and nodes > LARGE_GRAPH_NODES and reset_script_for(container) is not None
    )

    t0 = time.perf_counter()
    if use_reset:
        script = reset_script_for(container)
        if script is None:
            print("    reset mode: no script for this container — using batched DELETE", flush=True)
            clear_neo4j(container, password, reset_hint=reset_hint, batch_size=batch_size)
        else:
            reset_neo4j_via_script(script)
            assert_neo4j_empty(container, password)
    else:
        clear_neo4j(container, password, reset_hint=reset_hint, batch_size=batch_size)

    return time.perf_counter() - t0


def run_maven(main_class: str, args: list[str], env: dict[str, str]) -> tuple[float, int]:
    cmd = [
        "mvn", "exec:java",
        f"-Dexec.mainClass={main_class}",
        f"-Dexec.args={' '.join(args)}",
    ]
    print(f"    > {' '.join(cmd)}", flush=True)
    t0 = time.perf_counter()
    proc = subprocess.run(cmd, cwd=PROJECT_ROOT, env=env)
    elapsed = time.perf_counter() - t0
    return elapsed, proc.returncode


def append_csv_row(csv_path: Path, header: list[str], row: dict[str, object]) -> None:
    write_header = not csv_path.exists()
    with csv_path.open("a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=header, extrasaction="ignore")
        if write_header:
            w.writeheader()
        w.writerow(row)


def count_blocks(path: Path) -> int:
    count = 0
    with path.open(encoding="utf-8") as f:
        for line in f:
            if REIFIES in line and "<<" in line:
                count += 1
    return count


def count_triple_lines(path: Path) -> int:
    count = 0
    with path.open(encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#") or s.startswith("VERSION"):
                continue
            count += 1
    return count


def count_graphml_nodes_edges(path: Path) -> tuple[int, int]:
    """Fast line-based count of <node and <edge tags in GraphML."""
    nodes = 0
    edges = 0
    with path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            if "<node " in line or line.strip().startswith("<node"):
                nodes += 1
            if "<edge " in line or line.strip().startswith("<edge"):
                edges += 1
    return nodes, edges


def remove_if_exists(path: Path) -> None:
    if not path.is_file():
        return
    try:
        path.unlink()
    except PermissionError:
        import subprocess
        print(f"    Need elevated permissions to remove {path} ...", flush=True)
        subprocess.run(["sudo", "rm", "-f", str(path)], check=True)
