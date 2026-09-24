"""Run placement-aware Nangate45 electrical and setup repair in OpenROAD."""

import argparse
import os
import json
from pathlib import Path
import shutil
import subprocess
import time

from timing_reports import summarize_target


ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(__file__).with_name("openroad_resizer.tcl")
OPENROAD_PATH = "/OpenROAD-flow-scripts/tools/install/OpenROAD/bin/openroad"
DEFAULT_OPENROAD_IMAGE = (
    "openroad/orfs@sha256:0f74b1bb4e3d7d290e0a0b989839cfdeebe85feca1df236a60f4087124827637"
)
DEFAULT_SLEW_MARGIN = 70
DEFAULT_MAX_REPAIRS_PER_PASS = 1


def _container_path(path):
    path = Path(path).resolve()
    try:
        relative = path.relative_to(ROOT)
    except ValueError as error:
        raise ValueError(f"Docker OpenROAD inputs must be under the workspace: {path}") from error
    return "/work/" + relative.as_posix()


def _tcl_list(paths):
    return " ".join("{" + str(path).replace("}", "\\}") + "}" for path in paths)


def run(netlist, target_ns, platform_dir, output_dir, liberty_files, image,
        top="ZirconCore", setup_repair=True, slew_margin=DEFAULT_SLEW_MARGIN,
        placement_density=None,
        max_repairs_per_pass=DEFAULT_MAX_REPAIRS_PER_PASS):
    netlist = Path(netlist).resolve()
    platform_dir = Path(platform_dir).resolve()
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    metadata = json.loads((platform_dir / "platform.json").read_text())
    physical = metadata["physical"]
    constraints = metadata["constraints"]
    density = physical["placement_density"] if placement_density is None else float(placement_density)
    if not 0.0 < density < 1.0:
        raise ValueError("Placement density must be between zero and one")
    if int(max_repairs_per_pass) != max_repairs_per_pass or max_repairs_per_pass < 1:
        raise ValueError("Maximum repairs per pass must be a positive integer")
    max_repairs_per_pass = int(max_repairs_per_pass)
    macro_lefs = [platform_dir / item["file"] for item in physical["lef_files"]]
    track_script = platform_dir / physical["track_script"]["file"]
    rc_script = platform_dir / physical["rc_script"]["file"]
    required = [netlist, *macro_lefs, track_script, rc_script, *map(Path, liberty_files)]
    missing = [path for path in required if not path.is_file()]
    if missing:
        raise RuntimeError("Missing OpenROAD input files: " + ", ".join(map(str, missing)))

    native = os.environ.get("OPENROAD_BIN")
    if native:
        executable = shutil.which(native) if "/" not in native else native
        if not executable or not Path(executable).is_file():
            raise RuntimeError(f"OPENROAD_BIN is not executable: {native}")
        path = lambda value: str(Path(value).resolve())
        identity = subprocess.run(
            [executable, "-version"], text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, check=True
        ).stdout.strip()
    else:
        if not shutil.which("docker"):
            raise RuntimeError("Docker was not found and OPENROAD_BIN is not set")
        path = _container_path
        identity = image

    values = {
        "NETLIST": path(netlist),
        "TOP": top,
        "PERIOD_NS": f"{float(target_ns):g}",
        "SITE": physical["site"],
        "DENSITY": f"{density:g}",
        "HALO_WIDTH_UM": f"{physical['macro_halo_um'][0]:g}",
        "HALO_HEIGHT_UM": f"{physical['macro_halo_um'][1]:g}",
        "DRIVING_CELL": constraints["driving_cell"],
        "LOAD_FF": f"{constraints['load_ff']:g}",
        "INPUT_ARRIVAL_NS": f"{constraints['input_arrival_ns']:g}",
        "OUTPUT_DELAY_NS": f"{constraints['output_delay_ns']:g}",
        "PLATFORM_LEFS": _tcl_list(path(item) for item in macro_lefs),
        "LIBERTY_FILES": _tcl_list(path(item) for item in liberty_files),
        "PLATFORM_DIR": path(platform_dir),
        "TRACK_SCRIPT": path(track_script),
        "RC_SCRIPT": path(rc_script),
        "OUTPUT_DIR": path(output_dir),
        "RUN_SETUP_REPAIR": "1" if setup_repair else "0",
        "SLEW_MARGIN": str(int(slew_margin)),
        "MAX_REPAIRS_PER_PASS": str(max_repairs_per_pass),
    }
    script_text = TEMPLATE.read_text()
    for key, value in values.items():
        script_text = script_text.replace("@" + key + "@", value)
    if "@" in script_text:
        raise RuntimeError("Unexpanded OpenROAD Tcl template token")
    script = output_dir / "openroad_resizer.tcl"
    script.write_text(script_text)
    log = output_dir / "openroad.log"
    threads = os.environ.get("OPENROAD_THREADS", "max").strip().lower()
    if threads != "max":
        try:
            if int(threads) < 1:
                raise ValueError
        except ValueError as error:
            raise ValueError("OPENROAD_THREADS must be 'max' or a positive integer") from error
    command = (
        [executable, "-threads", threads, "-no_init", "-exit", path(script)]
        if native
        else [
            "docker", "run", "--rm", "-v", f"{ROOT}:/work", "-w", "/work",
            image, OPENROAD_PATH, "-threads", threads, "-no_init", "-exit", path(script),
        ]
    )
    started = time.monotonic()
    with log.open("w") as stream:
        result = subprocess.run(
            command, cwd=ROOT, text=True, stdout=stream, stderr=subprocess.STDOUT
        )
    elapsed = time.monotonic() - started
    if result.returncode:
        tail = log.read_text(errors="replace").splitlines()[-100:]
        raise RuntimeError(f"OpenROAD resizer failed:\n" + "\n".join(tail))
    return {
        "tool_identity": identity,
        "threads": threads,
        "setup_repair": setup_repair,
        "slew_margin_percent": int(slew_margin),
        "max_repairs_per_pass": max_repairs_per_pass,
        "placement_density": density,
        "timing_driven_placement": True,
        "routability_driven_placement": True,
        "elapsed_seconds": elapsed,
        "log": str(log),
        "script": str(script),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--netlist", type=Path, required=True)
    parser.add_argument("--target-ns", type=float, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--liberty", type=Path, action="append", required=True)
    parser.add_argument("--image", default=DEFAULT_OPENROAD_IMAGE)
    parser.add_argument("--top", default="ZirconCore")
    parser.add_argument("--slew-margin", type=int, default=DEFAULT_SLEW_MARGIN)
    parser.add_argument(
        "--max-repairs-per-pass",
        type=int,
        default=DEFAULT_MAX_REPAIRS_PER_PASS,
        help="maximum setup repairs batched per pass (default: 1)",
    )
    parser.add_argument("--placement-density", type=float)
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    if (output_dir / "openroad.log").exists():
        parser.error(f"{output_dir / 'openroad.log'} already exists; choose a fresh output directory")

    result = run(
        netlist=args.netlist,
        target_ns=args.target_ns,
        platform_dir=ROOT / "eda/platforms/nangate45",
        output_dir=output_dir,
        liberty_files=args.liberty,
        image=args.image,
        top=args.top,
        setup_repair=True,
        slew_margin=args.slew_margin,
        placement_density=args.placement_density,
        max_repairs_per_pass=args.max_repairs_per_pass,
    )
    timing = summarize_target(output_dir, args.target_ns)
    result["timing"] = timing
    (output_dir / "timing-summary.json").write_text(json.dumps(timing, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
