"""Elaborate, map, and report Nangate45 ZirconCore timing."""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time

from nangate_memories import generate as generate_nangate_memories
from logic_only_sta import run as run_logic_only_sta
from openroad_resizer import DEFAULT_OPENROAD_IMAGE, run as run_openroad_resizer
from timing_reports import summarize_target


ROOT = Path(__file__).resolve().parents[2]
PLATFORM = ROOT / "eda/platforms/nangate45"
PLATFORM_JSON = PLATFORM / "platform.json"
CELL_LIB = PLATFORM / "lib/NangateOpenCellLibrary_typical.lib"
PINNED_YOSYS = ROOT / "build/tools/yosys-orfs-0.68/bin/yosys"
LEGACY_LOCAL_YOSYS = ROOT / "build/tools/yosys-native/root/usr/bin/yosys"


def run(argv, log_path, env=None):
    started = time.monotonic()
    with log_path.open("w") as log:
        result = subprocess.run(
            argv,
            cwd=ROOT,
            env=env,
            text=True,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    elapsed = time.monotonic() - started
    if result.returncode:
        tail = log_path.read_text(errors="replace").splitlines()[-80:]
        raise RuntimeError(f"{' '.join(argv)} failed:\n" + "\n".join(tail))
    return elapsed


def quote(path):
    return '"' + str(path).replace('"', '\\"') + '"'


def requested_targets(sweep=False, target_ns=None, sweep_targets=(1.0,), default_ns=1.0):
    if sweep and target_ns is not None:
        raise ValueError("--sweep and --target-ns cannot be combined")
    if target_ns is not None:
        target_delay_ps(target_ns)
        return [float(target_ns)]
    return list(map(float, sweep_targets)) if sweep else [float(default_ns)]


def target_delay_ps(target_ns):
    target_ns = float(target_ns)
    if target_ns <= 0:
        raise ValueError("Timing target must be positive")
    return round(target_ns * 1000)


def elaboration_command(output_dir):
    """Build the synthesis elaboration command without simulation observability."""
    return f"runMain Elaborate --bsg {shlex.quote(str(output_dir))}"


def validate_no_simulation_debug(rtl_sources):
    """Reject accidentally elaborated simulation-only ports before synthesis."""
    forbidden = re.compile(r"\bio_(?:debug|performance)(?:_|\b)")
    offenders = []
    for path in rtl_sources:
        if forbidden.search(Path(path).read_text(errors="replace")):
            offenders.append(str(path))
    if offenders:
        raise RuntimeError(
            "Simulation-only debug logic is present in synthesis RTL: "
            + ", ".join(offenders)
        )


def build_yosys_script(rtl_sources, wrapper_files, top, liberty_inputs, constraints,
                       output_dir, delay_ps):
    from adder_mapping import AdderMapping

    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    mapping = AdderMapping.discover(rtl_sources, delay_ps=delay_ps)
    cell_library = Path(liberty_inputs[0])
    mapped_json = output_dir / f"{top}-mapped.json"
    mapped_verilog = output_dir / f"{top}-mapped.v"
    lines = [f"read_liberty -lib -ignore_miss_func {quote(path)}" for path in liberty_inputs]
    lines += [f"read_verilog -sv -D SYNTHESIS {quote(path)}" for path in rtl_sources]
    lines += [f"read_verilog -sv -D SYNTHESIS {quote(path)}" for path in wrapper_files]
    preserve = mapping.preserve()
    if preserve:
        lines.append(preserve)
    lines += [f"hierarchy -check -top {top}", f"synth -top {top} -noabc"]
    lines.append(f"dfflibmap -liberty {quote(cell_library)}")
    direct_mapping = mapping.commands(
        cell_library, constraints, output_dir, stage="pre_flatten"
    )
    if direct_mapping:
        lines.extend(direct_mapping.splitlines())
    flatten = mapping.flatten()
    if flatten:
        lines.extend(flatten.splitlines())
    lines += mapping.commands(
        cell_library, constraints, output_dir, stage="post_flatten"
    ).splitlines()
    lines += [
        "clean",
        "check -assert",
        "stat " + " ".join(f"-liberty {quote(path)}" for path in liberty_inputs),
        f"write_json {quote(mapped_json)}",
        f"write_verilog -noattr -noexpr {quote(mapped_verilog)}",
    ]
    return "\n".join(lines) + "\n"


def validate_platform():
    metadata = json.loads(PLATFORM_JSON.read_text())
    inputs = [
        metadata["library"],
        *metadata["physical"]["lef_files"],
        metadata["physical"]["track_script"],
        metadata["physical"]["rc_script"],
    ]
    for item in inputs:
        path = PLATFORM / item["file"]
        if not path.is_file():
            raise RuntimeError(f"Missing Nangate45 platform input: {path}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != item["sha256"]:
            raise RuntimeError(f"Nangate45 platform input hash mismatch: {path}")
    return metadata


def _synthesis_summary(target_dir, version, target_ns, elapsed, memory_bindings):
    mapped_json = target_dir / "ZirconCore-mapped.json"
    mapped_verilog = target_dir / "ZirconCore-mapped.v"
    modules = json.loads(mapped_json.read_text())["modules"]
    cells = Counter(
        cell["type"]
        for module in modules.values()
        for cell in module.get("cells", {}).values()
    )
    fakeram_counts = {name: cells[name] for name in memory_bindings["libraries"]}
    if any(count == 0 for count in fakeram_counts.values()):
        raise RuntimeError(f"BSG Fakeram macros were not preserved: {fakeram_counts}")
    unexpected_ram = {
        name: count
        for name, count in cells.items()
        if "ram" in name.lower() and not name.startswith("fakeram45_")
    }
    if unexpected_ram:
        raise RuntimeError(f"Non-BSG SRAM macros remain after BSG-only binding: {unexpected_ram}")
    internal_cells = {name: count for name, count in cells.items() if name.startswith("$")}
    if internal_cells:
        raise RuntimeError(f"Unmapped internal cells remain: {internal_cells}")
    synthesis_log = (target_dir / "synthesis.log").read_text(errors="replace")
    check_problem_counts = [
        int(count)
        for count in re.findall(r"Found and reported (\d+) problems\.", synthesis_log)
    ]
    if not check_problem_counts or any(check_problem_counts):
        raise RuntimeError(f"Yosys checks were not all clean: {check_problem_counts}")
    area_match = re.search(r"Chip area for module '\\ZirconCore': ([0-9.]+)", synthesis_log)
    if not area_match:
        raise RuntimeError("Yosys did not report a mapped ZirconCore area")
    return {
        "top": "ZirconCore",
        "configuration": "BSG Fakeram-only logic timing, L2 sets=16",
        "target_period_ns": target_ns,
        "abc_delay_ps": target_delay_ps(target_ns),
        "yosys": version,
        "cell_count": sum(cells.values()),
        "area_um2": float(area_match.group(1)),
        "dff_cells": sum(count for name, count in cells.items() if "DFF" in name),
        "yosys_check_problem_counts": check_problem_counts,
        "fakeram_instances": fakeram_counts,
        "single_port_bindings": memory_bindings["bindings"],
        "elapsed": elapsed,
        "outputs": {
            "netlist": str(mapped_verilog),
            "repaired_netlist": str(target_dir / "ZirconCore-repaired.v"),
            "json": str(mapped_json),
            "log": str(target_dir / "synthesis.log"),
            "openroad_log": str(target_dir / "openroad.log"),
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/nangate45-core")
    default_yosys = next(
        (
            str(candidate)
            for candidate in (PINNED_YOSYS, LEGACY_LOCAL_YOSYS)
            if candidate.is_file()
        ),
        None,
    ) or shutil.which("yosys") or shutil.which("yowasp-yosys")
    parser.add_argument("--yosys", default=default_yosys)
    parser.add_argument("--skip-elaboration", action="store_true")
    parser.add_argument(
        "--logic-only",
        action="store_true",
        help=(
            "stop after full Nangate45 mapping and zero-interconnect STA; "
            "skip placement and physical repair"
        ),
    )
    parser.add_argument(
        "--placement-density",
        type=float,
        help="override the platform placement density for a reproducible physical A/B run",
    )
    parser.add_argument(
        "--max-repairs-per-pass",
        type=int,
        default=1,
        help="maximum OpenROAD setup repairs batched per pass (default: 1)",
    )
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--sweep", action="store_true", help="run configured timing targets")
    selection.add_argument("--target-ns", type=float, help="run one timing target")
    args = parser.parse_args()
    if not args.yosys:
        parser.error("Yosys was not found; pass --yosys /path/to/yosys")

    metadata = validate_platform()
    targets = requested_targets(
        args.sweep,
        args.target_ns,
        metadata["constraints"]["target_sweep_ns"],
        metadata["constraints"]["clock_period_ns"],
    )
    output = args.output.resolve()
    rtl = output / "rtl"
    output.mkdir(parents=True, exist_ok=True)
    rtl.mkdir(parents=True, exist_ok=True)

    elapsed = {}
    if not args.skip_elaboration:
        env = os.environ.copy()
        env["ZIRCON_USE_EXTERNAL_BSG_RAM"] = "true"
        command = elaboration_command(rtl)
        elapsed["elaboration_seconds"] = run(
            ["sbt", "--batch", command], output / "elaboration.log", env=env
        )

    filelist = rtl / "filelist.f"
    wrappers = [
        ROOT / "src/main/resources/BsgFakeram1RW1R_25.sv",
        ROOT / "src/main/resources/BsgFakeram1RW1R_32.sv",
        ROOT / "src/main/resources/PredictorBsgFakeram_512_16.sv",
        ROOT / "src/main/resources/PredictorBsgFakeram_128_45.sv",
        ROOT / "src/main/resources/PredictorBsgFakeram_64_24.sv",
        ROOT / "src/main/resources/PredictorBsgFakeram_256_8.sv",
    ]
    missing = [path for path in [filelist, *wrappers, CELL_LIB] if not path.is_file()]
    if missing:
        raise RuntimeError("Missing synthesis inputs: " + ", ".join(map(str, missing)))
    rtl_sources = [
        rtl / line.strip()
        for line in filelist.read_text().splitlines()
        if line.strip() and not line.startswith("verification/")
    ]
    missing_sources = [path for path in rtl_sources if not path.is_file()]
    if missing_sources:
        raise RuntimeError("Missing generated RTL: " + ", ".join(map(str, missing_sources)))
    validate_no_simulation_debug(rtl_sources)

    single_port = output / "single-port-memory"
    memory_bindings = generate_nangate_memories(rtl, single_port)
    replaced_modules = {binding["module"] for binding in memory_bindings["bindings"]}
    rtl_sources = [path for path in rtl_sources if path.stem not in replaced_modules]
    fakeram_libs = [Path(item["path"]) for item in memory_bindings["libraries"].values()]
    liberty_inputs = [CELL_LIB, *fakeram_libs]

    env = os.environ.copy()
    if Path(args.yosys).name == "yowasp-yosys" and "PYTHONPATH" not in env:
        candidates = sorted((Path.home() / ".local/lib").glob("python*/site-packages/yowasp_yosys"))
        if candidates:
            env["PYTHONPATH"] = str(candidates[-1].parent)
    version = subprocess.run(
        [args.yosys, "-V"], env=env, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, check=True
    ).stdout.strip()
    all_results = []
    for target_ns in targets:
        target_dir = output / f"target-{target_ns:g}ns"
        target_dir.mkdir(parents=True, exist_ok=True)
        delay_ps = target_delay_ps(target_ns)
        abc_constraints = target_dir / "abc.constr"
        abc_constraints.write_text(
            f"set_driving_cell {metadata['constraints']['driving_cell']}\n"
            f"set_load {metadata['constraints']['load_ff']}\n"
        )
        script = target_dir / "synth.ys"
        script.write_text(build_yosys_script(
            rtl_sources=rtl_sources,
            wrapper_files=[*wrappers, single_port / "wrappers.sv"],
            top="ZirconCore",
            liberty_inputs=liberty_inputs,
            constraints=abc_constraints,
            output_dir=target_dir,
            delay_ps=delay_ps,
        ))
        synthesis_seconds = run(
            [args.yosys, "-Q", "-T", "-s", str(script)],
            target_dir / "synthesis.log",
            env=env,
        )
        result = _synthesis_summary(
            target_dir,
            version=version,
            target_ns=target_ns,
            elapsed={**elapsed, "synthesis_seconds": synthesis_seconds},
            memory_bindings=memory_bindings,
        )
        logic_only = run_logic_only_sta(
            netlist=target_dir / "ZirconCore-mapped.v",
            target_ns=target_ns,
            output_dir=target_dir / "logic-only",
            liberty_files=liberty_inputs,
            image=DEFAULT_OPENROAD_IMAGE,
            top="ZirconCore",
            platform_dir=PLATFORM,
        )
        result["logic_only"] = logic_only
        result["elapsed"]["logic_only_seconds"] = logic_only["elapsed_seconds"]
        if args.logic_only:
            result["mode"] = "logic-only"
            (target_dir / "results.json").write_text(json.dumps(result, indent=4) + "\n")
            all_results.append(result)
            print(json.dumps(result, indent=4))
            continue
        physical = run_openroad_resizer(
            netlist=target_dir / "ZirconCore-mapped.v",
            target_ns=target_ns,
            platform_dir=PLATFORM,
            output_dir=target_dir,
            liberty_files=liberty_inputs,
            image=DEFAULT_OPENROAD_IMAGE,
            top="ZirconCore",
            setup_repair=True,
            placement_density=args.placement_density,
            max_repairs_per_pass=args.max_repairs_per_pass,
        )
        result["openroad"] = physical["tool_identity"]
        result["mode"] = "physical"
        result["openroad_threads"] = physical["threads"]
        result["placement_density"] = physical["placement_density"]
        result["timing_driven_placement"] = physical["timing_driven_placement"]
        result["routability_driven_placement"] = physical["routability_driven_placement"]
        result["max_repairs_per_pass"] = physical["max_repairs_per_pass"]
        result["elapsed"]["openroad_seconds"] = physical["elapsed_seconds"]
        result["timing"] = summarize_target(target_dir, target_ns)
        (target_dir / "results.json").write_text(json.dumps(result, indent=4) + "\n")
        all_results.append(result)
        print(json.dumps(result, indent=4))
    (output / "sweep-results.json").write_text(json.dumps(all_results, indent=4) + "\n")


if __name__ == "__main__":
    main()
