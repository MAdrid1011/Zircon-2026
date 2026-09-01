# M3 Deterministic `tohost`, Bounded Spike, and Sail Evidence

This record binds deterministic, `tohost`-completing M3 ELF runs and the first
bounded committed-memory comparisons to the exact local source revisions and
generated artifacts. It is local execution evidence, not an M3 release claim.

## Revisions and invocation

The first completion pass used parent source
`88b6a278ccbb7dcaecca7c5702c5ce784ded3b06`; the committed-memory comparison
below used parent source `fe35bbb32e08f1d23445107780c58e64f4a1b5e5`. Both use
ZirconSim `2bce488562b756a572be9a9004d720a5eb4bab42` and RV-Software
`11d6eae150d47aab32aca3340e30ba61ddcbb2f0`. All runs used deterministic AXI
seed `1` and the locked toolchain:

```bash
make -C ZirconSim tohost
```

The runner required both an ordered normal `RetireEvent` for the selected
`tohost` store and the matching nonzero value in AXI backing memory. Each run
returned `exit: 0`.

| ELF | Cycle limit | Observed cycles | Retirements | ELF SHA256 | Trace SHA256 |
| --- | ---: | ---: | ---: | --- | --- |
| `rv32i-commit-prefix.elf` | 1024 | 241 | 19 | `d9447a5b7fc720653c3ea4fce72425863a4652351e49787f6839a94a4e6eb51a` | `00d2b73b4ccb00f360233d0396888f7e6223a12ed996b66fdebc7c240312a481` |
| `rv32i-alu-branch-prefix.elf` | 1024 | 292 | 34 | `a80a3031831e9c419f713b2285212281e6968a21a04dbcaab1b0c63f90f00369` | `0bd14cdb862f80f5c1ba40810b0aecc6a742d8e01d96f6fa6be1879967b27a4e` |
| `rv32m-commit-prefix.elf` | 2048 | 274 | 19 | `300f8e44499937f7a3b916b52010ff3fc91d4ed06d504cd1cc3f1e6da2b93a88` | `a58356c0448f0dc58505c1870ab6591bdb025cb337a3b380556f063d291c91aa` |
| `rv32a-tohost.elf` | 2048 | 228 | 12 | `ca0ef3266e7ff359f5c73ebf749062e6c7f0a1e95bed536a6fef888b7b6ae0ab` | `f9e2ffbb07b4efcc72c7ada38752d1d259563a1a51a68c4dd7fa44f4279244a7` |

The RV32I trace's final store has `memoryWriteMask=15` and
`memoryWriteData=1` at the ELF-resolved `tohost` address. The runner verifies
that the trace metadata and backing memory agree before reporting success.

## Remaining proof obligations

The earlier M1/M2 prefix comparator intentionally rejected memory metadata.
The M3 committed-memory paths below compare memory metadata and final
backing-memory state against locked Spike and Sail. They still reject traps,
interrupts, floating state, and unsupported memory encodings; they do not
establish full ISA equivalence of loads, stores, atomics, or cache ordering.
The next verification changes are explicit-seed AXI error/backpressure stress
and the remaining cache/LSU proof obligations.

## Committed-memory differential harness status

ZirconSim commit `2bce488562b756a572be9a9004d720a5eb4bab42` adds the bounded
Spike committed-memory comparison and exact sorted AXI backing-memory snapshots.
Commit `b22137e40c8331608930acc6494197bc72054840` adds the independently
parsed Sail committed-memory path. Both cover the four ELFs above through
their trailing `tohost` store. The comparator checks committed load/store/AMO
metadata and every touched backing word reconstructed from the ELF image and
reference committed stores.

At this revision the following local prerequisites passed:

```bash
make -C ZirconSim unit
make -C ZirconSim tohost
```

The latter reproduced the table's four seed-1 completions and produced a
backing-memory snapshot for each run.

On 2026-09-01, an isolated checkout of
`riscv-software-src/riscv-isa-sim` at the locked source SHA
`c09c0cce98696f52abe0fe8c11f93f9ed74dc2bb` was configured and built locally.
Its `Spike RISC-V ISA Simulator 1.1.1-dev` binary ran:

```bash
make -C ZirconSim diff-memory-spike \
  SPIKE=/home/madrid/.cache/zircon-2026-spike-c09c0cce/build/spike
```

All four bounded comparisons passed: RV32I CSR prefix `19` ordered
retirements, RV32I ALU/branch prefix `34`, RV32M prefix `19`, and RV32A
AMO/LR/SC prefix `12`. For every case `CommitTraceDiff` matched the ordered
retire fields, committed memory address/masks/read/write data, and all touched
words in the deterministic AXI backing-memory snapshot against Spike.

| ELF | Spike log SHA256 | Backing-memory SHA256 |
| --- | --- | --- |
| `rv32i-commit-prefix.elf` | `12442ec45c455dda9b7d726c5bad817128b3dabc608d8f1134fed785f7e05ece` | `799b638079395e494dcabf864a0f34478b17d0d51b8680e6ae882b1e56ba2444` |
| `rv32i-alu-branch-prefix.elf` | `9281adf96724b536b6fd645fb45aa57d0502a3a4b3f60c5c0dbaa1d58a5d5064` | `32742d6c5d31b82db209a695f66065ca1beb088927dedbee5b50441dc779a411` |
| `rv32m-commit-prefix.elf` | `5017a789b5f38771a065152b74b182bafe2f54d93d59ecfd5e05c30dfd546977` | `f59164e11cf286b691649dad57479455d701ef3483c0ccb827bc10424cf00a91` |
| `rv32a-tohost.elf` | `ad6e0bce0c695896484efc8a6ae13554d6fe0fe8e3665278b17e86bd40d869d2` | `e5123413fd054435036ee98d7356c29de14101ba14ad0dac7878002b3b05028b` |

On 2026-09-01, parent source `8702e3fd132653d072554824bce8be4225baf815`,
ZirconSim `b22137e40c8331608930acc6494197bc72054840`, and the same
RV-Software revision ran the locked Sail-RISC-V source
`beaf44991eee362a062fcaaf6fcb78ca428ff710`. The model binary was built with
Sail compiler `0.20.2` (binary SHA256
`26b59bcab2d66e9f220d317dfe45f8b09170ed70e59a824553d6f525134d1ff6`) and
was invoked as follows:

```bash
make -C ZirconSim diff-memory-sail \
  SAIL=/home/madrid/.cache/zircon-2026-sail-riscv-beaf4499/build/c_emulator/sail_riscv_sim
```

All four bounded Sail comparisons passed: RV32I CSR prefix `19` ordered
retirements, RV32I ALU/branch prefix `34`, RV32M prefix `19`, and RV32A
AMO/LR/SC prefix `12`. `mem[X,...]` instruction fetches were excluded; Sail
`R/W/RW` records were compared as committed load/store/AMO metadata and every
touched backing-memory word was matched against the deterministic AXI snapshot.

| ELF | Sail memory log SHA256 | Backing-memory SHA256 |
| --- | --- | --- |
| `rv32i-commit-prefix.elf` | `f69ec9a316697531adb9ac25574a819edebf1ee8ce53b990c40b9216870f87cc` | `799b638079395e494dcabf864a0f34478b17d0d51b8680e6ae882b1e56ba2444` |
| `rv32i-alu-branch-prefix.elf` | `16ad8c7ca2b54c18bcec05c475e957b4ee10400f13990dffab4d88f3c7d976f6` | `32742d6c5d31b82db209a695f66065ca1beb088927dedbee5b50441dc779a411` |
| `rv32m-commit-prefix.elf` | `e8dc51e1ce8b3149cc2f58d13eb04c03bb817da170feb37fb2e2f9769b9e55c8` | `f59164e11cf286b691649dad57479455d701ef3483c0ccb827bc10424cf00a91` |
| `rv32a-tohost.elf` | `6e1048db60259e0097ff07b1c2e03489912a68ab638d6968e7b56e608c61ae70` | `e5123413fd054435036ee98d7356c29de14101ba14ad0dac7878002b3b05028b` |

The comparisons are deliberately bounded to these four directed ELFs.

## Cache-global FENCE/I-D coherence

On 2026-09-01, the parent working tree added ADR-0019 production
`CacheFenceDrainController`. It first waits for the exact-age LSQ barrier, then
sweeps dirty L1D into L2, sweeps dirty L2 into ID-5, and waits for the retained
writeback owner's successful B response before `FENCE` or `FENCE.I` may retire.
This is separate from the trace-only host flush bridge.

The following local directed command passed in approximately four minutes and
fifteen seconds, within the five-minute component regression budget:

```bash
make test-m3-ordering
```

It completed 65 module tests plus four top-level cases. In addition to the
existing age-tagged and `aq` checks, the new cases prove all of the following:

- a dirty `FENCE` has issued the complete ID-5 AW/W burst but neither it nor its
  successor retires while B is withheld;
- `FENCE.I` writes a dirty replacement instruction through ID-5, invalidates
  L1I/BTB at commit redirect, and refetches that instruction from mutable
  deterministic AXI backing memory;
- the local L1D and L2 sweeps retain exclusive ownership and backpressure new
  cache ingress until their dirty state reaches the next owner;
- the controller reports serializing completion only after L1D, L2 victim FIFO,
  and ID-5 B completion have all drained.

The deterministic top-level memory model was also corrected to capture AW/W
payloads on the pre-edge handshake. This prevents a test-only one-beat write
shift and makes its backing-memory observations faithful to AXI timing.

Explicit-seed random AXI error/backpressure stress, full dual-LSU conflict
coverage, formal properties, external coherency, and broader
Spike/Sail/ACT4 campaigns remain required.
