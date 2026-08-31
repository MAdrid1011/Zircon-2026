#!/usr/bin/env bash
set -euo pipefail

readonly verilator_version="5.050"
readonly verilator_commit="848d926ebd4addacacd294dc84e35d9d4ae8078c"
readonly install_prefix="${1:?usage: setup-ci-verilator.sh INSTALL_PREFIX SOURCE_DIR}"
readonly source_dir="${2:?usage: setup-ci-verilator.sh INSTALL_PREFIX SOURCE_DIR}"
readonly completion_marker="${install_prefix}/.zircon-verilator-${verilator_commit}"

if [[ -f "${completion_marker}" && -x "${install_prefix}/bin/verilator" ]]; then
  installed_version="$(${install_prefix}/bin/verilator --version)"
  if [[ "${installed_version}" == Verilator\ ${verilator_version}* ]]; then
    exit 0
  fi
fi

if [[ -e "${source_dir}" ]]; then
  echo "Refusing to reuse non-empty Verilator source path: ${source_dir}" >&2
  exit 2
fi

mkdir -p "${install_prefix}"
git clone --branch "v${verilator_version}" --depth 1 \
  https://github.com/verilator/verilator.git "${source_dir}"

actual_commit="$(git -C "${source_dir}" rev-parse HEAD)"
if [[ "${actual_commit}" != "${verilator_commit}" ]]; then
  echo "Verilator v${verilator_version} resolved to ${actual_commit}, expected ${verilator_commit}." >&2
  exit 3
fi

(
  cd "${source_dir}"
  autoconf
  ./configure --prefix="${install_prefix}"
  make -j2
  make install
)

installed_version="$(${install_prefix}/bin/verilator --version)"
if [[ "${installed_version}" != Verilator\ ${verilator_version}* ]]; then
  echo "Unexpected Verilator version after install: ${installed_version}" >&2
  exit 4
fi

touch "${completion_marker}"
