#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
vivado_bin=${VIVADO_BIN:-/opt/Xilinx/Vivado/2023.1/bin/vivado}
tool_dir=$(dirname "$vivado_bin")
xvlog_bin="$tool_dir/xvlog"
xelab_bin="$tool_dir/xelab"
xsim_bin="$tool_dir/xsim"
xpm_memory="$tool_dir/../data/ip/xpm/xpm_memory/hdl/xpm_memory.sv"

for executable in "$xvlog_bin" "$xelab_bin" "$xsim_bin"; do
  if [[ ! -x "$executable" ]]; then
    printf 'required Vivado simulation executable is unavailable: %s\n' "$executable" >&2
    exit 2
  fi
done
if [[ ! -f "$xpm_memory" ]]; then
  printf 'required XPM memory model is unavailable: %s\n' "$xpm_memory" >&2
  exit 2
fi

run_dir=$(mktemp -d "${TMPDIR:-/tmp}/zircon-fpga-bram.XXXXXX")
trap 'rm -rf "$run_dir"' EXIT

pushd "$run_dir" >/dev/null
"$xvlog_bin" -sv "$xpm_memory" \
  "$repo_root/fpga/src/ZirconBoard.sv" \
  "$repo_root/fpga/tests/ZirconAxiBramTb.sv" >/dev/null
"$xelab_bin" -L unisims_ver -L xpm -s zircon_axi_bram_tb ZirconAxiBramTb >/dev/null
"$xsim_bin" zircon_axi_bram_tb -runall
popd >/dev/null
