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

clone_at_revision() {
    local url=$1
    local revision=$2
    local destination=$3
    if [[ ! -d "$destination/.git" ]]; then
        note "cloning $url"
        git clone --filter=blob:none --no-checkout "$url" "$destination"
    fi
    if [[ ! -f "$destination/.git/index" ]] \
        || [[ "$(git -C "$destination" rev-parse HEAD 2>/dev/null || true)" != "$revision" ]]; then
        git -C "$destination" fetch --depth 1 origin "$revision"
        git -C "$destination" checkout --detach "$revision"
    fi
}

if has_target zircon "${targets[@]}"; then
    note "initializing Zircon software and simulator submodules"
    git -C "$REPO_ROOT" submodule update --init RV-Software ZirconSim
fi

if has_target xiangshan-yanqihu "${targets[@]}"; then
    xiangshan_dir="$WORK_DIR/xiangshan-yanqihu"
    clone_at_revision "$XIANGSHAN_URL" "$XIANGSHAN_REV" "$xiangshan_dir"
    git -C "$xiangshan_dir" submodule sync --recursive
    git -C "$xiangshan_dir" submodule update --init --recursive --jobs "$JOBS"

    mill_launcher="$WORK_DIR/bin/mill"
    if [[ ! -x "$mill_launcher" ]]; then
        mkdir -p "$(dirname "$mill_launcher")"
        curl -LfsS "$MILL_LAUNCHER_URL" -o "$mill_launcher"
        [[ "$(sha256_file "$mill_launcher")" == "$MILL_LAUNCHER_SHA256" ]] || die "Mill launcher checksum mismatch"
        chmod +x "$mill_launcher"
    fi
fi

if has_target boom-small "${targets[@]}" || has_target boom-medium "${targets[@]}"; then
    chipyard_dir="$WORK_DIR/chipyard"
    clone_at_revision "$CHIPYARD_URL" "$CHIPYARD_REV" "$chipyard_dir"
    if [[ ! -f "$chipyard_dir/.coremark-submodules-ready" ]]; then
        note "initializing Chipyard submodules without the bundled RISC-V toolchain"
        PATH="$SCRIPT_DIR:/opt/homebrew/opt/coreutils/libexec/gnubin:$PATH" \
            "$chipyard_dir/scripts/init-submodules-no-riscv-tools.sh"
        touch "$chipyard_dir/.coremark-submodules-ready"
    fi
    actual_boom_revision=$(git -C "$chipyard_dir/generators/boom" rev-parse HEAD)
    [[ "$actual_boom_revision" == "$BOOM_REV" ]] || die "unexpected BOOM revision: $actual_boom_revision"

    apply_patch_once "$chipyard_dir" "$HARNESS_DIR/patches/chipyard/root.patch"
    apply_patch_once "$chipyard_dir/sims/firesim" "$HARNESS_DIR/patches/chipyard/firesim.patch"
    apply_patch_once "$chipyard_dir/tools/barstools" "$HARNESS_DIR/patches/chipyard/barstools.patch"
    apply_patch_once "$chipyard_dir/generators/testchipip" "$HARNESS_DIR/patches/chipyard/testchipip.patch"
fi

note "fetch complete"
