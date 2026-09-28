"""Generate the existing Vivado project's RTL with Xilinx RAM inference enabled."""

import argparse
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "build/vivado-2025-xc7a200t-bram/rtl"
RAM_MODULES = (
    "XilinxSinglePortRamReadFirst",
    "XilinxTrueDualPortReadFirst1ClockRam",
    "XilinxTrueDualPortReadFirstByteWrite1ClockRam",
)


def validate(output):
    sources = [output / line.strip() for line in (output / "filelist.f").read_text().splitlines()
               if line.strip() and not line.startswith("verification/")]
    missing = [source for source in sources if not source.is_file()]
    if missing:
        raise RuntimeError(f"Missing generated RTL: {missing}")
    wrappers = [source for source in sources if source.name.startswith(
        ("SinglePortMaskedRam", "DualPortMaskedRam", "PredictorTableRam"))]
    text = "\n".join(source.read_text() for source in wrappers)
    missing_ram = [name for name in RAM_MODULES if name not in text]
    if missing_ram or "BsgFakeram" in text or "PredictorBsgFakeram" in text:
        raise RuntimeError(f"Vivado RTL has the wrong RAM backend: missing {missing_ram}")
    adder = (output / "BLevelPAdder32.sv").read_text()
    if not re.search(r"\bio_src1\s*\+\s*io_src2\b", adder):
        raise RuntimeError("Vivado RTL did not select native 32-bit addition")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["ZIRCON_USE_EXTERNAL_VIVADO_RAM"] = "true"
    env["ZIRCON_INCLUDE_VIVADO_RAM_SOURCE"] = "false"
    env["ZIRCON_VIVADO_NATIVE_ADDERS"] = "1"
    env.pop("ZIRCON_USE_EXTERNAL_BSG_RAM", None)
    subprocess.run(
        ["sbt", "--batch", f"runMain Elaborate {shlex.quote(str(output))}"],
        cwd=ROOT, env=env, check=True,
    )
    for name in RAM_MODULES:
        shutil.copy2(ROOT / "src/main/resources/Xilinx" / f"{name}.sv", output / f"{name}.sv")
    validate(output)
    print(f"Vivado RTL ready: {output}")


if __name__ == "__main__":
    main()
