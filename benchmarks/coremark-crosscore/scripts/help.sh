#!/usr/bin/env bash

set -euo pipefail

printf '%s\n' \
    'CoreMark cross-core comparison' \
    '' \
    '  make compare                  Fetch, build, run, validate, and report all targets' \
    '  make run-boom-small           Build and run one target' \
    '  make validate-reference       Revalidate the committed reference logs' \
    '  make report                   Regenerate results/current/report.md' \
    '' \
    'Overrides: JOBS=2 NICE_LEVEL=15 TARGETS="..."'
