#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "$SCRIPT_DIR/common.sh"

target=${1:?usage: build-target.sh TARGET}
validate_target "$target"

build_rv64_workload() {
    [[ -d "$REPO_ROOT/RV-Software/coremark/src" ]] || die "RV-Software is not initialized; run make fetch"
    make -C "$HARNESS_DIR/workloads/coremark/htif-rv64" -j"$JOBS" \
        COREMARK="$REPO_ROOT/RV-Software/coremark" \
        BUILD="$WORK_DIR/coremark-rv64" \
        ITERATIONS="$ITERATIONS" \
        CLANG="$CLANG_BIN" \
        LD_LLD="$LD_LLD_BIN" \
        OBJCOPY="$OBJCOPY_BIN" \
        OBJDUMP="$OBJDUMP_BIN" \
        all boom
}

case "$target" in
    zircon)
        note "building Zircon CoreMark and simulator"
        nice -n "$NICE_LEVEL" make -C "$REPO_ROOT/RV-Software/coremark" -j"$JOBS" ITERATIONS="$ITERATIONS"
        nice -n "$NICE_LEVEL" cmake -S "$REPO_ROOT" -B "$WORK_DIR/zircon-cmake" -DCMAKE_BUILD_TYPE=Release
        nice -n "$NICE_LEVEL" cmake --build "$WORK_DIR/zircon-cmake" --target zircon-sim --parallel "$JOBS"
        ;;
    xiangshan-yanqihu)
        build_rv64_workload
        note "building XiangShan Yanqihu simulator"
        PATH="$WORK_DIR/bin:$PATH" nice -n "$NICE_LEVEL" make \
            -C "$WORK_DIR/xiangshan-yanqihu" -j"$JOBS" emu EMU_THREADS=1
        ;;
    boom-small|boom-medium)
        build_rv64_workload
        setup_java11
        setup_riscv_prefix
        if [[ "$target" == "boom-small" ]]; then
            boom_config=SmallBoomConfig
        else
            boom_config=MediumBoomConfig
        fi
        note "building Chipyard $boom_config simulator"
        nice -n "$NICE_LEVEL" make -C "$WORK_DIR/chipyard/sims/verilator" -j"$JOBS" \
            CONFIG="$boom_config" VERILATOR_THREADS=1
        ;;
esac

note "build complete: $target"
