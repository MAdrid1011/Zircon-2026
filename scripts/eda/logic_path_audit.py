"""Audit every endpoint-limited logic-only setup path using full STA details."""

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from timing_reports import normalize_register_family, parse_dff_registers


START = re.compile(r"^Startpoint: (\S+)")
END = re.compile(r"^Endpoint: (\S+)")
SLACK = re.compile(r"^\s*([-+]?\d+(?:\.\d+)?)\s+slack \(VIOLATED\)\s*$")
STAGE = re.compile(
    r"^\s*(?:\d+\.\d+\s+)?([-+]?\d+\.\d+)\s+([-+]?\d+\.\d+)"
    r"\s+[\^v]\s+(\S+)\s+\(([^)]+)\)\s*$"
)


def parse_full_paths(report):
    """Stream one worst setup path per violating endpoint from OpenSTA."""
    current = None
    in_data = False
    with Path(report).open(errors="replace") as stream:
        for line in stream:
            start = START.match(line)
            if start:
                if current is not None:
                    raise ValueError("Path without a violated slack line")
                current = {"startpoint": start.group(1), "stages": []}
                in_data = True
                continue
            if current is None:
                continue
            end = END.match(line)
            if end:
                current["endpoint"] = end.group(1)
                continue
            if "data arrival time" in line:
                in_data = False
            if in_data:
                stage = STAGE.match(line)
                if stage:
                    delay, arrival, pin, cell = stage.groups()
                    if pin.lower().endswith("/ck") or pin.lower().endswith("/clock"):
                        continue
                    current["stages"].append({
                        "pin": pin,
                        "cell": cell,
                        "delay_ns": float(delay),
                        "arrival_ns": float(arrival),
                    })
            slack = SLACK.match(line)
            if slack:
                if "endpoint" not in current or not current["stages"]:
                    raise ValueError("Incomplete violating path")
                current["slack_ns"] = float(slack.group(1))
                yield current
                current = None
    if current is not None:
        raise ValueError("Unterminated violating path")


def audit(report, netlist, clusters):
    paths = list(parse_full_paths(report))
    instances = {
        name.split("/", 1)[0]
        for path in paths
        for name in (path["startpoint"], path["endpoint"])
        if name.startswith("_")
    }
    registers = parse_dff_registers(netlist, instances)
    families = defaultdict(lambda: {"count": 0, "tns_ns": 0.0, "worst": None})
    cells = defaultdict(lambda: {"endpoints": 0, "families": set()})
    for path in paths:
        # OpenSTA names a macro endpoint by instance in the header, while the
        # inventory report names its actual input/output pin. Use the first and
        # last data pins for macro paths so both reports describe the same edge.
        source_pin = path["stages"][0]["pin"]
        endpoint_pin = path["stages"][-1]["pin"]
        source_instance = source_pin.split("/", 1)[0]
        endpoint_instance = endpoint_pin.split("/", 1)[0]
        source = registers.get(source_instance, source_pin)
        endpoint = registers.get(endpoint_instance, endpoint_pin)
        family = (
            f"{normalize_register_family(source)} -> "
            f"{normalize_register_family(endpoint)}"
        )
        path["source_register"] = source
        path["endpoint_register"] = endpoint
        path["family"] = family
        path["buffer_delay_ns"] = round(sum(
            stage["delay_ns"] for stage in path["stages"]
            if stage["cell"].startswith("BUF_")
        ), 6)
        path["buffer_count"] = sum(
            stage["cell"].startswith("BUF_") for stage in path["stages"]
        )
        group = families[family]
        group["count"] += 1
        group["tns_ns"] += path["slack_ns"]
        if group["worst"] is None or path["slack_ns"] < group["worst"]["slack_ns"]:
            group["worst"] = path
        for cell in {stage["pin"].split("/", 1)[0] for stage in path["stages"]}:
            if cell.startswith("_"):
                cells[cell]["endpoints"] += 1
                cells[cell]["families"].add(family)

    expected = {item["name"]: item for item in clusters["family_pairs"]}
    if len(paths) != clusters["path_count"] or set(families) != set(expected):
        raise ValueError("Full paths do not match the endpoint/family inventory")
    for name, values in families.items():
        if values["count"] != expected[name]["count"]:
            raise ValueError(f"Family count mismatch: {name}")
        if abs(values["tns_ns"] - expected[name]["tns_ns"]) > 0.001:
            raise ValueError(f"Family TNS mismatch: {name}")
        if abs(values["worst"]["slack_ns"] - expected[name]["worst_slack_ns"]) > 0.000001:
            raise ValueError(f"Family worst slack mismatch: {name}")
    inventory = [
        {
            "name": name,
            "count": values["count"],
            "tns_ns": round(values["tns_ns"], 6),
            "worst_slack_ns": values["worst"]["slack_ns"],
            "representative": values["worst"],
        }
        for name, values in families.items()
    ]
    inventory.sort(key=lambda item: (-item["count"], item["worst_slack_ns"]))
    hubs = [
        {"cell": name, "endpoints": value["endpoints"],
         "family_count": len(value["families"])}
        for name, value in cells.items()
    ]
    hubs.sort(key=lambda item: (-item["endpoints"], -item["family_count"]))
    return {
        "path_count": len(paths),
        "family_count": len(inventory),
        "buffer_count_histogram": dict(sorted(Counter(
            path["buffer_count"] for path in paths
        ).items())),
        "families": inventory,
        "shared_cells": hubs[:100],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--netlist", type=Path, required=True)
    parser.add_argument("--clusters", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.report, args.netlist, json.loads(args.clusters.read_text()))
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f'{result["path_count"]} endpoints, {result["family_count"]} families')


if __name__ == "__main__":
    main()
