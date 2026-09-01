# M3 Deterministic `tohost` and Bounded Spike Evidence

This record binds deterministic, `tohost`-completing M3 ELF runs and the first
bounded committed-memory comparisons to the exact local source revisions and
generated artifacts. It is local execution evidence, not an M3 release claim
and not Sail committed-memory evidence.

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
The M3 `diff-memory-spike` path used below does compare memory metadata and
final backing-memory state against locked Spike. It still rejects traps,
interrupts, floating state, unsupported memory encodings, and Sail logs; it
does not establish full ISA equivalence of loads, stores, atomics, or cache
ordering. The next verification change must add a Sail memory adapter and
error/backpressure stress with explicit seeds.

## Committed-memory differential harness status

ZirconSim commit `2bce488562b756a572be9a9004d720a5eb4bab42` adds the bounded
Spike committed-memory comparison and exact sorted AXI backing-memory snapshots.
It covers the four ELFs above through `make -C ZirconSim diff-memory-spike
SPIKE=/path/to/locked/spike`, including their trailing `tohost` store. The
comparator checks committed load/store/AMO metadata and every touched backing
word reconstructed from the ELF image and reference committed stores.

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

The comparison is deliberately bounded to these four directed ELFs. A Sail
memory-trace adapter, cache-global FENCE/I-D coherence, explicit-seed random
AXI error/backpressure stress, full dual-LSU conflict coverage, and broader
Spike/Sail/ACT4 campaigns remain required.
