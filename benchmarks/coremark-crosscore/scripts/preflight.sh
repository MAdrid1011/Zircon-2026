#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "$SCRIPT_DIR/common.sh"

if (( $# == 0 )); then
    set -- zircon
fi
targets=("$@")
for target in "${targets[@]}"; do
    validate_target "$target"
done

for command_name in git make python3 curl llvm-config verilator; do
    require_command "$command_name"
done
for llvm_tool in "$CLANG_BIN" "$LD_LLD_BIN" "$OBJCOPY_BIN" "$OBJDUMP_BIN"; do
    require_executable "$llvm_tool"
done
"$CLANG_BIN" --print-targets | grep 'riscv' >/dev/null || die "the selected Clang does not include the RISC-V backend"

if has_target zircon "${targets[@]}"; then
    for command_name in cmake sbt pkg-config; do
        require_command "$command_name"
    done
    pkg-config --exists riscv-riscv || die "zircon-sim requires the riscv-riscv pkg-config package"
fi

if has_target xiangshan-yanqihu "${targets[@]}"; then
    require_command java
    if [[ "$(uname -s)" == "Darwin" ]]; then
        require_command realpath
    fi
fi

if has_target boom-small "${targets[@]}" || has_target boom-medium "${targets[@]}"; then
    require_command sbt
    setup_java11
    setup_riscv_prefix
fi

available_kb=$(df -Pk "$HARNESS_DIR" | awk 'NR == 2 {print $4}')
if [[ "$available_kb" =~ ^[0-9]+$ ]] && (( available_kb < 8388608 )); then
    note "warning: less than 8 GiB is free; a full comparison may run out of disk space"
fi

note "preflight passed for: ${targets[*]}"
