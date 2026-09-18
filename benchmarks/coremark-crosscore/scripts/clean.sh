#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
HARNESS_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

printf 'Removing generated cross-core work and current results...\n'
rm -rf "$HARNESS_DIR/work" "$HARNESS_DIR/results/current"
