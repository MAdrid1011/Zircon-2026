# CoreMark cross-core results

| Processor | CoreMark/MHz | Timed cycles | ISA / ABI | Relative to Zircon | Validation |
| --- | ---: | ---: | --- | ---: | --- |
| Zircon | 3.799 | not retained | RV32IMAF / ILP32F | +0.0% | user-confirmed baseline |
| XiangShan Yanqihu | 7.781 | 3,855,514 | RV64GC / LP64D | +104.8% | CRC valid |
| BOOM Small | 3.140 | 9,551,518 | RV64GC / LP64D | -17.3% | CRC valid |
| BOOM Medium | 5.322 | 5,636,123 | RV64GC / LP64D | +40.1% | CRC valid |

Scores use 30 iterations, `-O2`, simulator seed 1, and an `mcycle` interval around `iterate()`. The RV64 targets share one workload build; Zircon uses the analogous RV32 build. CoreMark/MHz measures per-cycle performance, not area or energy efficiency.
