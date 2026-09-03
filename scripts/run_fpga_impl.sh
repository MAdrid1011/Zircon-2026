#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
vivado_bin=${VIVADO_BIN:-/opt/Xilinx/Vivado/2023.1/bin/vivado}
revision=${FPGA_REVISION:-$(git -C "$repo_root" rev-parse HEAD)}
run_dir=${FPGA_RUN_DIR:-"$repo_root/fpga/runs/$revision"}
mkdir -p "$run_dir"

if [[ ! -x "$vivado_bin" ]]; then
  printf 'Vivado is unavailable at %s; set VIVADO_BIN to the batch executable.\n' "$vivado_bin" >&2
  exit 2
fi

"$vivado_bin" -mode batch -nojournal -nolog \
  -source "$repo_root/fpga/check_target_part.tcl"
make -C "$repo_root" platform-verilog
exec "$vivado_bin" -mode batch \
  -log "$run_dir/vivado.log" -journal "$run_dir/vivado.jou" \
  -source "$repo_root/fpga/vivado.tcl" -tclargs "$run_dir"
