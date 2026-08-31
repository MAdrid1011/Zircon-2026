#!/usr/bin/env python3
"""Validate and compare Zircon static-area manifests.

The report deliberately uses structural proxies. It never converts them to
vendor LUT, flip-flop, BRAM, timing, power, or silicon-area claims.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


STORAGE_CLASSES = {
    "register_payload",
    "register_control",
    "memory_data",
    "tag_metadata",
}

LOGIC_METRICS = {
    "cam_compare_bits",
    "mux_input_bits",
    "priority_select_bits",
    "adder32_units",
    "comparator32_units",
    "shifter32_units",
    "partial_product16_units",
    "iterative_divsqrt_units",
    "other_units",
}


class ManifestError(ValueError):
    pass


def _positive_int(value: Any, field: str, *, allow_zero: bool = False) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ManifestError(f"{field} must be an integer")
    minimum = 0 if allow_zero else 1
    if value < minimum:
        raise ManifestError(f"{field} must be >= {minimum}")
    return value


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ManifestError(f"cannot read {path}: {error}") from error
    validate_manifest(data, path)
    return data


def validate_manifest(data: Any, source: Path | str = "manifest") -> None:
    if not isinstance(data, dict):
        raise ManifestError(f"{source}: root must be an object")
    if data.get("schema_version") != 1:
        raise ManifestError(f"{source}: schema_version must be 1")
    for field in ("design", "revision", "configuration"):
        if not isinstance(data.get(field), str) or not data[field].strip():
            raise ManifestError(f"{source}: {field} must be a non-empty string")
    if data.get("completeness") not in {"partial", "complete"}:
        raise ManifestError(
            f"{source}: completeness must be partial or complete")

    omissions = data.get("known_omissions")
    if not isinstance(omissions, list) or not all(
            isinstance(item, str) and item.strip() for item in omissions):
        raise ManifestError(f"{source}: known_omissions must be a string list")
    if data["completeness"] == "complete" and omissions:
        raise ManifestError(
            f"{source}: a complete manifest cannot retain known omissions")
    if data["completeness"] == "partial" and not omissions:
        raise ManifestError(
            f"{source}: a partial manifest must explain known omissions")

    storage = data.get("storage")
    if not isinstance(storage, list):
        raise ManifestError(f"{source}: storage must be a list")
    storage_names: set[str] = set()
    for index, entry in enumerate(storage):
        prefix = f"{source}: storage[{index}]"
        if not isinstance(entry, dict):
            raise ManifestError(f"{prefix} must be an object")
        name = entry.get("name")
        if not isinstance(name, str) or not name.strip():
            raise ManifestError(f"{prefix}.name must be non-empty")
        if name in storage_names:
            raise ManifestError(f"{source}: duplicate storage name {name}")
        storage_names.add(name)
        if entry.get("class") not in STORAGE_CLASSES:
            raise ManifestError(f"{prefix}.class is not recognized")
        for field in ("entries", "bits_per_entry", "instances",
                      "replication_factor"):
            _positive_int(entry.get(field), f"{prefix}.{field}")
        if not isinstance(entry.get("source"), str) or not entry["source"]:
            raise ManifestError(f"{prefix}.source must be non-empty")

    logic = data.get("logic")
    if not isinstance(logic, list):
        raise ManifestError(f"{source}: logic must be a list")
    logic_names: set[str] = set()
    for index, entry in enumerate(logic):
        prefix = f"{source}: logic[{index}]"
        if not isinstance(entry, dict):
            raise ManifestError(f"{prefix} must be an object")
        name = entry.get("name")
        if not isinstance(name, str) or not name.strip():
            raise ManifestError(f"{prefix}.name must be non-empty")
        if name in logic_names:
            raise ManifestError(f"{source}: duplicate logic name {name}")
        logic_names.add(name)
        metric = entry.get("metric")
        if metric not in LOGIC_METRICS:
            raise ManifestError(f"{prefix}.metric is not recognized")
        _positive_int(entry.get("units"), f"{prefix}.units")
        _positive_int(entry.get("width", 1), f"{prefix}.width")
        _positive_int(entry.get("fanin", 1), f"{prefix}.fanin")
        if not isinstance(entry.get("source"), str) or not entry["source"]:
            raise ManifestError(f"{prefix}.source must be non-empty")


def storage_totals(data: dict[str, Any]) -> dict[str, int]:
    totals: defaultdict[str, int] = defaultdict(int)
    for entry in data["storage"]:
        logical = (entry["entries"] * entry["bits_per_entry"] *
                   entry["instances"])
        replicated = logical * entry["replication_factor"]
        totals[f"logical::{entry['class']}"] += logical
        totals[f"replicated::{entry['class']}"] += replicated
        totals["logical::total"] += logical
        totals["replicated::total"] += replicated
    return dict(totals)


def logic_totals(data: dict[str, Any]) -> dict[str, int]:
    totals: defaultdict[str, int] = defaultdict(int)
    for entry in data["logic"]:
        totals[entry["metric"]] += (
            entry["units"] * entry.get("width", 1) * entry.get("fanin", 1))
    return dict(totals)


def _delta(before: int, after: int) -> str:
    difference = after - before
    sign = "+" if difference > 0 else ""
    if before == 0:
        percent = "new" if after else "0.0%"
    else:
        percent = f"{difference * 100.0 / before:+.1f}%"
    return f"{sign}{difference} ({percent})"


def render_report(baseline: dict[str, Any], candidate: dict[str, Any]) -> str:
    base_storage = storage_totals(baseline)
    cand_storage = storage_totals(candidate)
    base_logic = logic_totals(baseline)
    cand_logic = logic_totals(candidate)
    ready = (baseline["completeness"] == "complete" and
             candidate["completeness"] == "complete")

    lines = [
        "# Zircon static-area comparison",
        "",
        f"- Baseline: `{baseline['design']}` @ `{baseline['revision']}` "
        f"({baseline['completeness']})",
        f"- Candidate: `{candidate['design']}` @ `{candidate['revision']}` "
        f"({candidate['completeness']})",
        f"- Sign-off readiness: **{'READY' if ready else 'PARTIAL'}**",
        "- Units are structural bits or named proxy units, not vendor resources.",
        "",
        "## Storage",
        "",
        "| Metric | Baseline | Candidate | Delta |",
        "|---|---:|---:|---:|",
    ]
    storage_keys = sorted(set(base_storage) | set(cand_storage))
    for key in storage_keys:
        before = base_storage.get(key, 0)
        after = cand_storage.get(key, 0)
        lines.append(f"| `{key}` | {before} | {after} | {_delta(before, after)} |")

    lines.extend([
        "",
        "## Logic proxies",
        "",
        "| Metric | Baseline | Candidate | Delta |",
        "|---|---:|---:|---:|",
    ])
    logic_keys = sorted(set(base_logic) | set(cand_logic))
    if logic_keys:
        for key in logic_keys:
            before = base_logic.get(key, 0)
            after = cand_logic.get(key, 0)
            lines.append(
                f"| `{key}` | {before} | {after} | {_delta(before, after)} |")
    else:
        lines.append("| _none inventoried_ | 0 | 0 | 0 |")

    for label, manifest in (("Baseline", baseline), ("Candidate", candidate)):
        lines.extend(["", f"## {label} known omissions", ""])
        if manifest["known_omissions"]:
            lines.extend(f"- {item}" for item in manifest["known_omissions"])
        else:
            lines.append("- None.")

    lines.extend([
        "",
        "## Interpretation",
        "",
    ])
    if ready:
        lines.append(
            "Both manifests declare complete coverage. Architectural sign-off "
            "still requires reviewing every growing category and its rationale.")
    else:
        lines.append(
            "This comparison is not an area sign-off: at least one manifest is "
            "partial. Missing structures must not be treated as zero-area wins.")
    return "\n".join(lines) + "\n"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true",
                        help="validate and render without requiring completeness")
    parser.add_argument("--require-complete", action="store_true",
                        help="fail unless both manifests are complete")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        baseline = load_manifest(args.baseline)
        candidate = load_manifest(args.candidate)
    except ManifestError as error:
        print(f"static-area error: {error}", file=sys.stderr)
        return 2
    if args.require_complete and (
            baseline["completeness"] != "complete" or
            candidate["completeness"] != "complete"):
        print("static-area error: sign-off requires two complete manifests",
              file=sys.stderr)
        return 3
    report = render_report(baseline, candidate)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    elif not args.check:
        sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
