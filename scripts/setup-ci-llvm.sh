#!/usr/bin/env bash
set -euo pipefail

readonly llvm_version="22.1.8"
readonly llvm_release_commit="ca7933e47d3a3451d81e72ac174dcb5aa28b59d1"
readonly llvm_package_version="1:22.1.8~++20260714014902+ca7933e47d3a-1~exp1~20260714135019.80"
readonly llvm_key_fingerprint="6084F3CF814B57C1CF12EFD515CF4D18AF4F7421"
readonly downloaded_key="${1:?usage: setup-ci-llvm.sh DOWNLOADED_KEY_PATH}"
readonly dearmored_key="${downloaded_key}.gpg"

curl --fail --location --silent --show-error \
  https://apt.llvm.org/llvm-snapshot.gpg.key \
  --output "${downloaded_key}"

actual_fingerprint="$(
  gpg --batch --show-keys --with-colons "${downloaded_key}" |
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
if [[ "${actual_fingerprint}" != "${llvm_key_fingerprint}" ]]; then
  echo "Unexpected apt.llvm.org signing-key fingerprint: ${actual_fingerprint}" >&2
  exit 2
fi

gpg --batch --yes --dearmor --output "${dearmored_key}" "${downloaded_key}"
sudo install -m 0644 "${dearmored_key}" /usr/share/keyrings/apt.llvm.org.gpg
printf '%s\n' \
  'deb [signed-by=/usr/share/keyrings/apt.llvm.org.gpg] https://apt.llvm.org/noble/ llvm-toolchain-noble-22 main' |
  sudo tee /etc/apt/sources.list.d/llvm-22.list >/dev/null

sudo apt-get update
sudo apt-get install -y \
  "clang-22=${llvm_package_version}" \
  "lld-22=${llvm_package_version}" \
  "llvm-22=${llvm_package_version}"

installed_package_version="$(dpkg-query -W -f='${Version}' clang-22)"
if [[ "${installed_package_version}" != "${llvm_package_version}" ]]; then
  echo "Unexpected clang-22 package version: ${installed_package_version}" >&2
  exit 3
fi
if [[ "$(/usr/lib/llvm-22/bin/llvm-config --version)" != "${llvm_version}" ]]; then
  echo "llvm-config did not report the locked LLVM ${llvm_version}." >&2
  exit 4
fi
if [[ "${installed_package_version}" != *"${llvm_release_commit:0:12}"* ]]; then
  echo "LLVM package was not built from ${llvm_release_commit}." >&2
  exit 5
fi
