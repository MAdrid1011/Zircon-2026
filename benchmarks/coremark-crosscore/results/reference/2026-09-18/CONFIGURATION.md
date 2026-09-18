# Reference configuration details

The architectural values below were taken from the generated or default
configuration source used by the verified run. They are descriptive; only the
CoreMark scores and CRCs are validated automatically by this harness.

Reference host tools were Clang/LLVM 23.1.1, Verilator 5.052, Mill 0.12.15,
sbt 1.5.5, and Temurin JDK 11.0.32.1. Source revisions are recorded separately in
`configs/revisions.lock`.

| Item | Zircon | XiangShan Yanqihu | BOOM Small | BOOM Medium |
| --- | --- | --- | --- | --- |
| CoreMark/MHz | **3.799** | **7.781** | **3.140** | **5.322** |
| Relative to Zircon | baseline | **+104.8%** | **-17.3%** | **+40.1%** |
| Timed cycles, 30 iterations | not retained | 3,855,514 | 9,551,518 | 5,636,123 |
| ISA / ABI | RV32IMAF, ILP32F | RV64GC, LP64D | RV64GC, LP64D | RV64GC, LP64D |
| Fetch width | 4 | 8 | 4 | 4 |
| Decode / dispatch width | 2 | 6 | 1 | 2 |
| Retire width | 3 | 6 | 1 | 2 |
| ROB entries | 48 | 192 | 32 | 64 |
| Integer / FP physical registers | 72 / 48 | 160 / 160 | 52 / 48 | 80 / 64 |
| Issue queue entries | 40 across specialized queues | Multiple 16-entry queues | 8 / 8 / 8 MEM/INT/FP | 12 / 20 / 16 MEM/INT/FP |
| Load / store queue | 4 tracking slots / 12 SQ | 64 / 48 | 8 / 8 | 16 / 16 |
| Speculative branch window | 8 | 32 | 8 | 12 |
| FTQ / instruction buffer | 16 / 8 | 64 / 48 | 16 / 8 | 32 / 16 |
| L1 ICache / DCache | 2 KiB / 2 KiB, 2-way | 16 KiB / 32 KiB, 4-way / 8-way | 16 KiB / 16 KiB, 4-way | 16 KiB / 16 KiB, 4-way |
| Resource interpretation | comparison baseline | substantially larger | smaller backend, larger RV64/cache boundary | probably larger overall |

BOOM Medium and XiangShan use more structural resources than Zircon in this
comparison, but CoreMark/MHz alone cannot establish area efficiency. A defensible
area comparison needs a common technology library, cache boundary, synthesis flow,
and timing constraint.

The first exploratory runs, before the simulator seed was pinned, measured 7.782 for
XiangShan and 3.143 for BOOM Small. The committed reference uses simulator seed 1;
the resulting changes of 0.001 and 0.003 CoreMark/MHz are reset/memory-model
variation, not a workload or RTL change. BOOM Medium remained at 5.322.
