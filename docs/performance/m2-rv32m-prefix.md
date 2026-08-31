# M2 RV32M Prefix IPC Record

## Scope

This record starts, but does not complete, the Handoff RV32IM IPC comparison.
It measures the 17 normal retirements before the trailing `tohost` store in
ZirconSim's fixed RV32M prefix ELF. The immutable Zircon-2024 core and both
locked submodules are checked before the baseline run; both cores use seed 1
and the same `DeterministicAxiMemory` slave.

The measurement stops on the first cycle that observes retirement 17. A
Zircon-2024 dual-commit cycle may also commit the younger `tohost` store, but
that store is excluded from the numerator and never produces a pass result.
Zircon-2026 blocks it as required until M3 provides a real LSU.

## Observed Result

| Core | Cycles to retirement 17 | Retired count | IPC |
| --- | ---: | ---: | ---: |
| Zircon-2024 `65a3dd381f4c83a5844858a927dafdbc8263c35e` | 186 | 17 | 0.09140 |
| Zircon-2026 M2 | 235 | 17 | 0.07234 |

The M2 prefix needs 26.3% more cycles and its IPC is 20.9% lower. The prefix
contains all eight RV32M operations and several divide/remainder cases, so the
result directs later LongPipe/issue work but is not a general-workload result.
Repeated local seed-1 runs produced the same JSON records.

## Reproduction

Build a clean detached Zircon-2024 checkout at the locked core and submodule
SHAs, then run from `ZirconSim`:

```bash
make micro-ipc-rv32m
make baseline-ipc-rv32m BASELINE_2024=/work/Zircon-2024
```

The second target checks the core SHA `65a3dd381f4c83a5844858a927dafdbc8263c35e`,
the RV-Software SHA `5f81f2ad378f537182e4cf1a0fcb45159509a2ec`, the ZirconSim
SHA `b1694da4a92046edeead50c9b2a1c086a13e6511`, and a clean baseline worktree.

## Exclusions

This is not the M0 deterministic nominal/fast/slow memory profile, a
tohost-completing ELF result, a cache/MMIO workload, or an M5 IPC gate. It has
no branch MPKI, cache/miss, MSHR, MMIO, or stall-breakdown measurements. M3
must first provide the actual memory endpoints, then M5 must run the full common
workload suite against the frozen profiles before any release performance claim.
