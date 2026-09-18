#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "$SCRIPT_DIR/common.sh"

target=${1:?usage: run-target.sh TARGET}
validate_target "$target"
log_file="$CURRENT_RESULTS_DIR/$target.log"

case "$target" in
    zircon)
        simulator="$WORK_DIR/zircon-cmake/bin/zircon-sim"
        image="$REPO_ROOT/RV-Software/coremark/build/coremark-i${ITERATIONS}-rv32imaf_zicsr_zifencei-ilp32f.elf"
        [[ -x "$simulator" && -f "$image" ]] || die "Zircon artifacts are missing; run make build-zircon"
        python3 "$SCRIPT_DIR/run_simulator.py" --log "$log_file" -- \
            "$simulator" --elf "$image" --seed 1 --max-cycles 15000000 --stall-cycles 10000 --no-progress
        ;;
    xiangshan-yanqihu)
        simulator="$WORK_DIR/xiangshan-yanqihu/build/verilator-compile/emu"
        image="$WORK_DIR/coremark-rv64/coremark-i${ITERATIONS}-rv64.bin"
        [[ -x "$simulator" && -f "$image" ]] || die "XiangShan artifacts are missing; run make build-xiangshan-yanqihu"
        python3 "$SCRIPT_DIR/run_simulator.py" --log "$log_file" --stop-after-validation -- \
            "$simulator" --no-diff --seed 1 --max-cycles 20000000 --image "$image"
        ;;
    boom-small|boom-medium)
        if [[ "$target" == "boom-small" ]]; then
            boom_config=SmallBoomConfig
        else
            boom_config=MediumBoomConfig
        fi
        simulator="$WORK_DIR/chipyard/sims/verilator/simulator-chipyard-$boom_config"
        image="$WORK_DIR/coremark-rv64/coremark-i${ITERATIONS}-boom.elf"
        [[ -x "$simulator" && -f "$image" ]] || die "BOOM artifacts are missing; run make build-$target"
        python3 "$SCRIPT_DIR/run_simulator.py" --log "$log_file" -- \
            "$simulator" --seed 1 --max-cycles 20000000 "$image"
        ;;
esac

python3 "$SCRIPT_DIR/validate.py" --target "$target" --log "$log_file"
