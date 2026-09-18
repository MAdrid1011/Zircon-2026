#!/usr/bin/env bash

set -euo pipefail

HARNESS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO_ROOT=$(git -C "$HARNESS_DIR" rev-parse --show-toplevel)
WORK_DIR=${COREMARK_WORK_DIR:-$HARNESS_DIR/work}
CURRENT_RESULTS_DIR=${COREMARK_RESULTS_DIR:-$HARNESS_DIR/results/current}
JOBS=${JOBS:-2}
ITERATIONS=${ITERATIONS:-30}
NICE_LEVEL=${NICE_LEVEL:-15}

if command -v llvm-config >/dev/null 2>&1; then
    LLVM_BIN=${LLVM_BIN:-$(llvm-config --bindir)}
else
    LLVM_BIN=${LLVM_BIN:-}
fi
CLANG_BIN=${CLANG_BIN:-${LLVM_BIN:+$LLVM_BIN/}clang}
OBJCOPY_BIN=${OBJCOPY_BIN:-${LLVM_BIN:+$LLVM_BIN/}llvm-objcopy}
OBJDUMP_BIN=${OBJDUMP_BIN:-${LLVM_BIN:+$LLVM_BIN/}llvm-objdump}
if [[ -z "${LD_LLD_BIN:-}" ]]; then
    if [[ -x "${LLVM_BIN:+$LLVM_BIN/}ld.lld" ]]; then
        LD_LLD_BIN="${LLVM_BIN:+$LLVM_BIN/}ld.lld"
    else
        LD_LLD_BIN=$(command -v ld.lld || true)
    fi
fi

# shellcheck disable=SC1091
source "$HARNESS_DIR/configs/revisions.lock"
# shellcheck disable=SC1091
source "$HARNESS_DIR/configs/benchmark.env"

mkdir -p "$WORK_DIR" "$CURRENT_RESULTS_DIR"

if [[ "$ITERATIONS" != "$ITERATIONS_DEFAULT" ]]; then
    printf 'error: only the validated %s-iteration contract is supported\n' "$ITERATIONS_DEFAULT" >&2
    exit 1
fi

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

note() {
    printf '[coremark-crosscore] %s\n' "$*"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_executable() {
    [[ -n "$1" && -x "$1" ]] || die "required executable not found: $1"
}

validate_target() {
    case "$1" in
        zircon|xiangshan-yanqihu|boom-small|boom-medium)
            ;;
        *)
            die "unknown target: $1"
            ;;
    esac
}

has_target() {
    local wanted=$1
    shift
    local target
    for target in "$@"; do
        if [[ "$target" == "$wanted" ]]; then
            return 0
        fi
    done
    return 1
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

setup_java11() {
    if [[ -n "${COREMARK_JAVA_HOME:-}" ]]; then
        export JAVA_HOME=$COREMARK_JAVA_HOME
    elif [[ "$(uname -s)" == "Darwin" ]] && /usr/libexec/java_home -v 11 >/dev/null 2>&1; then
        export JAVA_HOME
        JAVA_HOME=$(/usr/libexec/java_home -v 11)
    elif [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "11[.]'; then
        :
    elif java -version 2>&1 | head -1 | grep -Eq 'version "11[.]'; then
        return 0
    else
        die "BOOM requires JDK 11; set COREMARK_JAVA_HOME to a JDK 11 installation"
    fi
    export PATH="$JAVA_HOME/bin:$PATH"
}

setup_riscv_prefix() {
    if [[ -n "${RISCV:-}" ]]; then
        export RISCV
        return 0
    fi
    if command -v pkg-config >/dev/null 2>&1 && pkg-config --exists riscv-riscv; then
        export RISCV
        RISCV=$(pkg-config --variable=prefix riscv-riscv)
        return 0
    fi
    die "Chipyard requires a Spike/FESVR prefix; set RISCV or install riscv-isa-sim with pkg-config metadata"
}

apply_patch_once() {
    local checkout=$1
    local patch_file=$2
    if git -C "$checkout" apply --unidiff-zero --reverse --check "$patch_file" >/dev/null 2>&1; then
        note "patch already applied: $(basename "$patch_file")"
    elif git -C "$checkout" apply --unidiff-zero --check "$patch_file"; then
        git -C "$checkout" apply --unidiff-zero "$patch_file"
    else
        die "patch does not apply cleanly: $patch_file"
    fi
}
