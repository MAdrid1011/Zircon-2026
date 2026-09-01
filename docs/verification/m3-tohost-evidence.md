# M3 Deterministic `tohost` Evidence

This record binds the first deterministic, `tohost`-completing M3 ELF runs to
the exact local source revisions and generated artifacts. It is execution
evidence, not a Spike/Sail committed-memory differential result and not an M3
release claim.

## Revisions and invocation

The parent source was `88b6a278ccbb7dcaecca7c5702c5ce784ded3b06`; the
ZirconSim submodule was `cdb4786dc8b31e1cafcad770ea4ba943a477bc6a`; and the
RV-Software submodule was `11d6eae150d47aab32aca3340e30ba61ddcbb2f0`.
All runs used the locked toolchain and deterministic AXI seed `1`:

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

`CommitTraceDiff` intentionally rejects events with memory metadata, traps,
interrupts, and FPR state. These four passes therefore do not establish ISA
equivalence of their loads, stores, or atomics. The next verification change
must compare committed load/store/atomic metadata and final backing-memory
state against locked Spike and Sail, then add error and backpressure stress
with explicit seeds.
