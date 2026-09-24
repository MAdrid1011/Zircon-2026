"""Parse timing reports and enforce the Nangate45 electrical-report gate."""

import argparse
import json
from pathlib import Path
import re


NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)"
ENDPOINT_ROW = re.compile(
    rf"^\s*(\S+)(?:\s+\([^)]*\))?\s+(\S+)(?:\s+\([^)]*\))?\s+({NUMBER})\s*$"
)
ENDPOINT_ONLY_ROW = re.compile(
    rf"^\s*(\S+)(?:\s+\([^)]*\))?\s+{NUMBER}\s+{NUMBER}\s+({NUMBER})"
    rf"(?:\s+\(VIOLATED\))?\s*$",
    re.IGNORECASE,
)


def count_violations(report):
    return len(re.findall(r"\bVIOLATED\b", report, re.IGNORECASE))


def require_electrically_clean(max_capacitance, max_slew):
    cap_count = count_violations(max_capacitance)
    slew_count = count_violations(max_slew)
    if cap_count or slew_count:
        raise ValueError(
            f"Electrical checks are not clean: max-cap={cap_count}, max-slew={slew_count}"
        )


def parse_endpoint_inventory(report):
    endpoints = {}
    startpoint_header = re.compile(r"^\s*Startpoint\s+Endpoint\s+Slack\s*$", re.IGNORECASE)
    endpoint_header = re.compile(r"^\s*Endpoint\s+Delay\s+Delay\s+Slack\s*$", re.IGNORECASE)
    format_kind = None
    for line in report.splitlines():
        if startpoint_header.match(line):
            format_kind = "startpoint"
            continue
        if endpoint_header.match(line):
            format_kind = "endpoint"
            continue
        if not line.strip() or set(line.strip()) == {"-"}:
            continue
        if format_kind == "endpoint":
            match = ENDPOINT_ONLY_ROW.match(line)
            if not match:
                continue
            endpoint, slack = match.groups()
            startpoint = None
        else:
            match = ENDPOINT_ROW.match(line)
            if not match:
                continue
            startpoint, endpoint, slack = match.groups()
        item = {"startpoint": startpoint, "endpoint": endpoint, "slack_ns": float(slack)}
        previous = endpoints.get(endpoint)
        if previous is None or item["slack_ns"] < previous["slack_ns"]:
            endpoints[endpoint] = item
    return sorted(endpoints.values(), key=lambda item: (item["slack_ns"], item["endpoint"]))


def parse_dff_registers(netlist, instances=None):
    """Map mapped DFF instance names to their hierarchical Q register names."""
    netlist = Path(netlist)
    wanted = set(instances) if instances is not None else None
    cell = re.compile(r"^\s*DFF\S*\s+(\S+)\s*\(")
    q_pin = re.compile(r"\.Q\((.*?)\)")
    current = None
    registers = {}
    with netlist.open(errors="replace") as stream:
        for line in stream:
            if current is None:
                match = cell.match(line)
                if match and (wanted is None or match.group(1) in wanted):
                    current = match.group(1)
                else:
                    continue
            match = q_pin.search(line)
            if match:
                name = match.group(1).strip()
                if name.startswith("\\"):
                    name = re.sub(r"\s+(?=\[)", "", name[1:].rstrip())
                registers[current] = name
            if line.rstrip().endswith(");"):
                current = None
    return registers


def classify_endpoint_modules(endpoints, registers):
    """Group endpoint inventory by the mapped register's top-level module."""
    module_counts = {}
    mapped = 0
    for endpoint in endpoints:
        instance = endpoint["endpoint"].split("/", 1)[0]
        register = registers.get(instance)
        if register is None:
            module = instance.split(".", 1)[0] if "." in instance else "top_or_macro"
        else:
            mapped += 1
            module = register.split(".", 1)[0] if "." in register else "top"
        module_counts[module] = module_counts.get(module, 0) + 1
    return {
        "mapped_sequential_endpoints": mapped,
        "unmapped_endpoints": len(endpoints) - mapped,
        "module_counts": dict(sorted(module_counts.items())),
    }


def parse_ram_edge_paths(report):
    results = []
    current = None

    def finish(item):
        if not item:
            return
        edges = item.pop("edges")
        item["launch_edge"] = edges[0] if edges else None
        item["capture_edge"] = edges[-1] if edges else None
        if item.get("startpoint") or item.get("endpoint"):
            results.append(item)

    for line in report.splitlines():
        start = re.match(r"^\s*Startpoint:\s*(.*?)\s*$", line)
        if start:
            finish(current)
            current = {"startpoint": start.group(1), "edges": []}
            continue
        if current is None:
            continue
        endpoint = re.match(r"^\s*Endpoint:\s*(.*?)\s*$", line)
        if endpoint:
            current["endpoint"] = endpoint.group(1)
        edge = re.search(r"\bclock\s+\S+\s+\((rise|fall) edge\)", line)
        if edge:
            current["edges"].append(edge.group(1))
    finish(current)
    return results


def parse_repair_counts(log):
    stages = {}
    stage = None
    in_table = False
    for line in log.splitlines():
        marker = re.search(r"RESIZER_STAGE\s+(\S+)", line)
        if marker:
            stage = marker.group(1)
            in_table = False
            continue
        if stage is None:
            continue
        if "Resized" in line and "Buffers" in line and "Nets repaired" in line:
            in_table = True
            continue
        if not in_table:
            continue
        columns = [column.strip() for column in line.split("|")]
        if len(columns) < 6 or not (columns[0].isdigit() or columns[0].lower() == "final"):
            continue
        stages[stage] = {
            "iteration": int(columns[0]) if columns[0].isdigit() else columns[0].lower(),
            "resized_cells": int(columns[2]),
            "inserted_buffers": int(columns[3]),
            "repaired_nets": int(columns[4]),
            "remaining": int(columns[5]),
        }
    return stages


def _report_scalar(path):
    text = Path(path).read_text(errors="replace")
    values = re.findall(NUMBER, text)
    if not values:
        raise ValueError(f"No numeric timing metric in {path}")
    return float(values[-1])


def summarize_target(target_dir, target_ns):
    target_dir = Path(target_dir)
    cap_path = target_dir / "max-capacitance.rpt"
    slew_path = target_dir / "max-slew.rpt"
    if not cap_path.is_file() or not slew_path.is_file():
        raise FileNotFoundError("OpenROAD electrical reports are missing")
    cap_count = count_violations(cap_path.read_text(errors="replace"))
    slew_count = count_violations(slew_path.read_text(errors="replace"))
    summary = {
        "target_period_ns": float(target_ns),
        "electrical_gate_pass": cap_count == 0 and slew_count == 0,
        "max_capacitance_violations": cap_count,
        "max_slew_violations": slew_count,
        "reports": {
            "max_capacitance": str(cap_path),
            "max_slew": str(slew_path),
        },
    }
    log_path = target_dir / "openroad.log"
    if log_path.is_file():
        log = log_path.read_text(errors="replace")
        summary["repair_counts"] = parse_repair_counts(log)
        setup_repair = re.search(r"RESIZER_SETUP_REPAIR\s+([01])", log)
        if setup_repair:
            summary["setup_repair_performed"] = setup_repair.group(1) == "1"
            summary["timing_reference"] = (
                "after setup repair" if setup_repair.group(1) == "1"
                else "after electrical repair, before setup repair"
            )
        slew_margin = re.search(r"RESIZER_SLEW_MARGIN\s+(\d+)", log)
        if slew_margin:
            summary["slew_repair_margin_percent"] = int(slew_margin.group(1))
    if not summary["electrical_gate_pass"]:
        summary["path_analysis"] = "skipped: electrical violations remain"
        return summary

    wns_path = target_dir / "wns.rpt"
    tns_path = target_dir / "tns.rpt"
    endpoint_path = target_dir / "violating-endpoints.rpt"
    if not all(path.is_file() for path in (wns_path, tns_path, endpoint_path)):
        raise FileNotFoundError("Timing path reports are missing after the electrical gate passed")
    endpoints = parse_endpoint_inventory(endpoint_path.read_text(errors="replace"))
    repaired_netlist = target_dir / "ZirconCore-repaired.v"
    register_instances = {
        endpoint["endpoint"].split("/", 1)[0]
        for endpoint in endpoints
        if endpoint["endpoint"].startswith("_")
    }
    registers = (
        parse_dff_registers(repaired_netlist, register_instances)
        if repaired_netlist.is_file()
        else {}
    )
    endpoint_classification = classify_endpoint_modules(endpoints, registers)
    worst_endpoint = dict(endpoints[0]) if endpoints else None
    if worst_endpoint is not None:
        instance = worst_endpoint["endpoint"].split("/", 1)[0]
        worst_endpoint["mapped_register"] = registers.get(instance)
    summary.update({
        "wns_ns": _report_scalar(wns_path),
        "tns_ns": _report_scalar(tns_path),
        "violating_endpoint_count": len(endpoints),
        "worst_endpoint": worst_endpoint,
        "endpoint_classification": endpoint_classification,
        "path_analysis": "complete",
        "reports": {
            **summary["reports"],
            "wns": str(wns_path),
            "tns": str(tns_path),
            "violating_endpoints": str(endpoint_path),
            "worst_paths": str(target_dir / "worst-paths.rpt"),
            "bsg_sram_output_worst": str(target_dir / "bsg-sram-output-worst.rpt"),
            "bsg_sram_output_violators": str(target_dir / "bsg-sram-output-violators.rpt"),
        },
    })
    for kind in ("openram", "fakeram"):
        path = target_dir / f"{kind}-output-violators.rpt"
        summary[f"{kind}_violating_output_paths"] = len(
            parse_ram_edge_paths(path.read_text(errors="replace"))
        ) if path.is_file() else None
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target_dir", type=Path)
    parser.add_argument("--target-ns", required=True, type=float)
    args = parser.parse_args()
    summary = summarize_target(args.target_dir, args.target_ns)
    (args.target_dir / "timing-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
