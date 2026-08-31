# Zircon-2026

Zircon-2026 is an area-conscious `RV32IMAF_Zicsr_Zifencei` processor for
control-intensive and irregular-memory workloads. The implementation keeps a
four-instruction fetch frontend, a two-wide decode/commit backend, two memory
pipelines, a non-blocking L1 data cache, and a dynamically shared L2 cache.

The repository is the source of truth for RTL, architecture specifications,
verification plans, and reproducible performance/PPA reports. Software and the
Verilator harness remain versioned in the `RV-Software` and `ZirconSim`
submodules on their `zircon-2026` branches.

## Current milestone

`M0 / v0.1-baseline`: repository, toolchain, interfaces, deterministic
simulation contracts, and the immutable Zircon-2024 comparison baseline.

Implementation status and acceptance evidence are tracked in
[`docs/STATUS.md`](docs/STATUS.md). Architectural behavior is specified under
[`docs/architecture`](docs/architecture), and verification closure is defined
under [`docs/verification`](docs/verification).

## Quick start

Requirements: JDK 21, sbt 1.12.4, Verilator 5.050, and LLVM 22.1.8.

```sh
git submodule update --init --recursive
make test
make verilog
```

All randomized tests must receive an explicit seed. CI and failure artifacts
record the RTL commit, submodule commits, tool lock, ELF hash, generator seed,
and memory seed.

## License

Zircon-2026 is licensed under MPL-2.0. Third-party tests and submodules retain
their own licenses.
