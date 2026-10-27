#!/usr/bin/env python3
"""Validate the sharded complexity-map relationship graph."""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path


FACT_TYPES = {"creates", "requires", "addresses"}
NON_CAUSAL_TYPES = {
    "coordinates",
    "depends",
    "preserves",
    "independent-authority",
    "conflicts",
    "motivates",
    "conformance-pending",
}
SUBSYSTEM_PATHS = {
    "AP": "complexity-map/java-assets-and-presentation/causal-graph.md",
    "MN": "complexity-map/java-models-and-network/causal-graph.md",
    "AN": "complexity-map/java-animation-and-integration/causal-graph.md",
    "NR": "complexity-map/native-capabilities/causal-graph.md",
}
CROSS_PATH = "complexity-map/governance/cross-subsystem-relations.md"
FANOUT_PATH = "complexity-map/governance/global-fanout.md"
MECHANISM_PATHS = {
    "AP": "complexity-map/java-assets-and-presentation/mechanisms.md",
    "MN": "complexity-map/java-models-and-network/mechanisms.md",
    "AN": "complexity-map/java-animation-and-integration/mechanisms.md",
    "NR": "complexity-map/native-capabilities/mechanisms.md",
}
EXPECTED_MECHANISMS = {"AP": 9, "MN": 24, "AN": 14, "NR": 17}
HEADING_RE = re.compile(r"^## (?P<title>.+?)\s*$", re.MULTILINE)
MECHANISM_RE = re.compile(r"^\|\s*((?:AP|MN|AN|NR)-\d{2})\b", re.MULTILINE)
NAMESPACE_RE = re.compile(r"^(AP|MN|AN|NR)-")


def section(text: str, title: str) -> str:
    matches = list(HEADING_RE.finditer(text))
    for index, match in enumerate(matches):
        if match.group("title") != title:
            continue
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        return text[match.end() : end]
    return ""


def cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.split("|")[1:-1]]


def relation_rows(text: str, title: str, allowed: set[str]) -> list[tuple[str, str, str, str]]:
    rows: list[tuple[str, str, str, str]] = []
    for line in section(text, title).splitlines():
        if not line.startswith("|"):
            continue
        values = cells(line)
        if len(values) != 4:
            continue
        relation = values[1].split(" / ", 1)[0]
        if relation in allowed:
            rows.append((values[0], values[1], values[2], values[3]))
    return rows


def split_targets(value: str) -> list[str]:
    return [target.strip() for target in value.split(",") if target.strip()]


def mechanism_namespace(node: str) -> str | None:
    match = NAMESPACE_RE.match(node)
    return match.group(1) if match else None


def reachable_namespaces(
    node: str,
    adjacency: dict[str, set[str]],
    seen: frozenset[str] = frozenset(),
) -> set[str]:
    if node in seen:
        return set()
    namespace = mechanism_namespace(node)
    if namespace:
        return {namespace}
    result: set[str] = set()
    next_seen = seen | {node}
    for target in adjacency.get(node, set()):
        result.update(reachable_namespaces(target, adjacency, next_seen))
    return result


def row_owner(
    row: tuple[str, str, str, str],
    adjacency: dict[str, set[str]],
    causal: bool,
) -> str:
    source, _, target_cell, _ = row
    nodes = split_targets(target_cell) if causal else [source, *split_targets(target_cell)]
    namespaces: set[str] = set()
    for node in nodes:
        namespaces.update(reachable_namespaces(node, adjacency))
        namespace = mechanism_namespace(node)
        if namespace:
            namespaces.add(namespace)
    if not namespaces:
        namespace = mechanism_namespace(source)
        if namespace:
            namespaces.add(namespace)
    return next(iter(namespaces)) if len(namespaces) == 1 else "cross"


def fanout_rows(text: str) -> list[tuple[list[str], int, int]]:
    result: list[tuple[list[str], int, int]] = []
    for line in text.splitlines():
        values = cells(line) if line.startswith("|") else []
        if len(values) != 4 or not values[0].startswith(("D-", "A-")):
            continue
        try:
            reachable = int(values[1])
            direct = int(values[2])
        except ValueError:
            continue
        result.append(([key.strip() for key in values[0].split("/")], reachable, direct))
    return result


def run(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    shard_paths = {**SUBSYSTEM_PATHS, "cross": CROSS_PATH}
    fact_by_shard: dict[str, list[tuple[str, str, str, str]]] = {}
    non_causal_by_shard: dict[str, list[tuple[str, str, str, str]]] = {}

    for owner, relative in shard_paths.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"{relative}: relationship shard is missing")
            continue
        text = path.read_text("utf-8")
        fact_by_shard[owner] = relation_rows(text, "Fact 因果关系", FACT_TYPES)
        non_causal_by_shard[owner] = relation_rows(
            text, "非因果关系与候选扩展", NON_CAUSAL_TYPES
        )

    if errors:
        return errors

    mechanisms: set[str] = set()
    for namespace, relative in MECHANISM_PATHS.items():
        ids = MECHANISM_RE.findall((root / relative).read_text("utf-8"))
        if len(ids) != EXPECTED_MECHANISMS[namespace]:
            errors.append(
                f"{relative}: expected {EXPECTED_MECHANISMS[namespace]} mechanisms, found {len(ids)}"
            )
        for mechanism in ids:
            if mechanism in mechanisms:
                errors.append(f"{relative}: duplicate mechanism {mechanism}")
            mechanisms.add(mechanism)

    adjacency: dict[str, set[str]] = defaultdict(set)
    fact_edges: dict[tuple[str, str, str], str] = {}
    for owner, rows in fact_by_shard.items():
        for source, relation, target_cell, _ in rows:
            for target in split_targets(target_cell):
                key = (source, relation, target)
                if key in fact_edges:
                    errors.append(
                        f"{shard_paths[owner]}: duplicate Fact edge {source} {relation} {target}; "
                        f"first seen in {fact_edges[key]}"
                    )
                else:
                    fact_edges[key] = shard_paths[owner]
                adjacency[source].add(target)
                for node in (source, target):
                    if mechanism_namespace(node) and node not in mechanisms:
                        errors.append(f"{shard_paths[owner]}: unknown mechanism endpoint {node}")

    non_causal_keys: dict[tuple[str, str, str], str] = {}
    for owner, rows in non_causal_by_shard.items():
        for source, relation, target_cell, _ in rows:
            key = (source, relation, target_cell)
            if key in non_causal_keys:
                errors.append(
                    f"{shard_paths[owner]}: duplicate non-causal relation {source} {relation} {target_cell}; "
                    f"first seen in {non_causal_keys[key]}"
                )
            else:
                non_causal_keys[key] = shard_paths[owner]
            for node in [source, *split_targets(target_cell)]:
                if mechanism_namespace(node) and node not in mechanisms:
                    errors.append(f"{shard_paths[owner]}: unknown mechanism endpoint {node}")

    for owner, rows in fact_by_shard.items():
        for row in rows:
            expected = row_owner(row, adjacency, causal=True)
            if expected != owner:
                errors.append(
                    f"{shard_paths[owner]}: Fact row belongs to {expected}: {row[0]} {row[1]} {row[2]}"
                )
    for owner, rows in non_causal_by_shard.items():
        for row in rows:
            expected = row_owner(row, adjacency, causal=False)
            if expected != owner:
                errors.append(
                    f"{shard_paths[owner]}: non-causal row belongs to {expected}: "
                    f"{row[0]} {row[1]} {row[2]}"
                )

    for keys, expected_reachable, expected_direct in fanout_rows(
        (root / FANOUT_PATH).read_text("utf-8")
    ):
        direct_nodes: set[str] = set()
        for key in keys:
            direct_nodes.update(adjacency.get(key, set()))
        seen: set[str] = set()
        stack = list(direct_nodes)
        while stack:
            node = stack.pop()
            if node in seen:
                continue
            seen.add(node)
            stack.extend(adjacency.get(node, set()) - seen)
        reachable = len(seen & mechanisms)
        if len(direct_nodes) != expected_direct or reachable != expected_reachable:
            errors.append(
                f"{FANOUT_PATH}: {' / '.join(keys)} declares R={expected_reachable}, D={expected_direct}; "
                f"recomputed R={reachable}, D={len(direct_nodes)}"
            )

    return sorted(set(errors))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    errors = run(parser.parse_args().root)
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        print(f"FAILED: {len(errors)} complexity relationship error(s)")
        return 1
    print("OK: complexity relationships are complete and internally consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
