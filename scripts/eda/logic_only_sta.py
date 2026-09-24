"""Report mapped-cell timing with zero interconnect parasitics."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from openroad_resizer import (
    DEFAULT_OPENROAD_IMAGE,
    OPENROAD_PATH,
    ROOT,
    _container_path,
    _tcl_list,
)


TEMPLATE = Path(__file__).with_name("logic_only_sta.tcl")
PLATFORM = ROOT / "eda/platforms/nangate45"


def _scalar(path):
    values = re.findall(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)", path.read_text(errors="replace"))
    if not values:
        raise RuntimeError(f"No timing metric found in {path}")
    return float(values[-1])


def _worst_data_arrival(path):
    arrivals = [
        float(value)
        for value in re.findall(
            r"(?m)^\s*([-+]?\d+(?:\.\d+)?)\s+data arrival time\s*$",
            path.read_text(errors="replace"),
        )
    ]
    if not arrivals:
        raise RuntimeError(f"No data arrival time found in {path}")
    return max(arrivals)


def _violating_endpoint_count(path):
    return sum(
        1
        for line in path.read_text(errors="replace").splitlines()
        if line.rstrip().endswith("(VIOLATED)")
    )


def run(netlist, target_ns, output_dir, liberty_files, image=DEFAULT_OPENROAD_IMAGE,
        top="ZirconCore", platform_dir=PLATFORM):
    netlist = Path(netlist).resolve()
    output_dir = Path(output_dir).resolve()
    platform_dir = Path(platform_dir).resolve()
    liberty_files = [Path(path).resolve() for path in liberty_files]
    required = [netlist, *liberty_files]
    missing = [path for path in required if not path.is_file()]
    if missing:
        raise RuntimeError("Missing logic-only STA inputs: " + ", ".join(map(str, missing)))
    output_dir.mkdir(parents=True, exist_ok=True)

    native = os.environ.get("OPENROAD_BIN")
    if native:
        executable = shutil.which(native) if "/" not in native else native
        if not executable or not Path(executable).is_file():
            raise RuntimeError(f"OPENROAD_BIN is not executable: {native}")
        path = lambda value: str(Path(value).resolve())
        identity = subprocess.run(
            [executable, "-version"], text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, check=True,
        ).stdout.strip()
    else:
        if not shutil.which("docker"):
            raise RuntimeError("Docker was not found and OPENROAD_BIN is not set")
        path = _container_path
        identity = image

    metadata = json.loads((platform_dir / "platform.json").read_text())
    constraints = metadata["constraints"]
    lef_files = [
        platform_dir / item["file"]
        for item in metadata["physical"]["lef_files"]
    ]
    lef_files += [path.with_suffix(".lef") for path in liberty_files if path.with_suffix(".lef").is_file()]
    missing_lefs = [path for path in lef_files if not path.is_file()]
    if missing_lefs:
        raise RuntimeError("Missing logic-only STA LEFs: " + ", ".join(map(str, missing_lefs)))
    values = {
        "NETLIST": path(netlist),
        "TOP": top,
        "PERIOD_NS": f"{float(target_ns):g}",
        "DRIVING_CELL": constraints["driving_cell"],
        "LOAD_FF": f"{constraints['load_ff']:g}",
        "INPUT_ARRIVAL_NS": f"{constraints['input_arrival_ns']:g}",
        "OUTPUT_DELAY_NS": f"{constraints['output_delay_ns']:g}",
        "LEF_FILES": _tcl_list(path(item) for item in lef_files),
        "LIBERTY_FILES": _tcl_list(path(item) for item in liberty_files),
        "OUTPUT_DIR": path(output_dir),
    }
    script_text = TEMPLATE.read_text()
    for key, value in values.items():
        script_text = script_text.replace("@" + key + "@", value)
    if "@" in script_text:
        raise RuntimeError("Unexpanded logic-only STA Tcl token")
    script = output_dir / "logic_only_sta.tcl"
    script.write_text(script_text)
    log = output_dir / "logic_only_sta.log"
    threads = os.environ.get("OPENROAD_THREADS", "max").strip().lower()
    if threads != "max":
        try:
            if int(threads) < 1:
                raise ValueError
        except ValueError as error:
            raise ValueError("OPENROAD_THREADS must be 'max' or a positive integer") from error
    command = (
        [executable, "-threads", threads, "-no_init", "-exit", path(script)]
        if native else
        ["docker", "run", "--rm", "-v", f"{ROOT}:/work", "-w", "/work",
         image, OPENROAD_PATH, "-threads", threads, "-no_init", "-exit", path(script)]
    )
    started = time.monotonic()
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, text=True, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = log.read_text(errors="replace").splitlines()[-80:]
        raise RuntimeError("Logic-only STA failed:\n" + "\n".join(tail))
    elapsed = time.monotonic() - started
    wns_ns = _scalar(output_dir / "logic-only-wns.rpt")
    worst_paths = output_dir / "logic-only-worst-paths.rpt"
    violating_endpoints = output_dir / "logic-only-violating-endpoints.rpt"
    summary = {
        "mode": "logic-only",
        "top": top,
        "target_period_ns": float(target_ns),
        "parasitics": "zero (no floorplan, placement, extraction, or estimate_parasitics)",
        "tool_identity": identity,
        "threads": threads,
        "elapsed_seconds": elapsed,
        "wns_ns": wns_ns,
        "tns_ns": _scalar(output_dir / "logic-only-tns.rpt"),
        "worst_data_arrival_ns": _worst_data_arrival(worst_paths),
        "violating_endpoint_count": _violating_endpoint_count(violating_endpoints),
        "target_met": wns_ns >= 0.0,
        "reports": {
            "log": str(log),
            "wns": str(output_dir / "logic-only-wns.rpt"),
            "tns": str(output_dir / "logic-only-tns.rpt"),
            "worst_paths": str(worst_paths),
            "violating_endpoints": str(violating_endpoints),
            "violating_paths_summary": str(
                output_dir / "logic-only-violating-paths-summary.rpt"
            ),
        },
    }
    (output_dir / "logic-only-results.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--netlist", type=Path, required=True)
    parser.add_argument("--target-ns", type=float, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--liberty", type=Path, action="append", required=True)
    parser.add_argument("--image", default=DEFAULT_OPENROAD_IMAGE)
    parser.add_argument("--top", default="ZirconCore")
    args = parser.parse_args()
    result = run(args.netlist, args.target_ns, args.output_dir, args.liberty, args.image, args.top)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
