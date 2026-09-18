#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
HARNESS_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
REFERENCE_DIR="$HARNESS_DIR/results/reference/2026-09-18"

python3 "$SCRIPT_DIR/validate.py" --target xiangshan-yanqihu --log "$REFERENCE_DIR/xiangshan-yanqihu.log"
python3 "$SCRIPT_DIR/validate.py" --target boom-small --log "$REFERENCE_DIR/boom-small.log"
python3 "$SCRIPT_DIR/validate.py" --target boom-medium --log "$REFERENCE_DIR/boom-medium.log"
python3 "$SCRIPT_DIR/report.py" --results-dir "$REFERENCE_DIR" --allow-missing-zircon --zircon-baseline 3.799
