# Compact ITTAGE Metrics

CoreMark seed 1 used the same ELF, Spike differential checker, instruction count, and metric definitions as the baseline.

| Metric | Baseline | Compact ITTAGE | Delta |
| --- | ---: | ---: | ---: |
| Cycles | 371,523 | 325,946 | -45,577 (-12.268%) |
| Retired instructions | 374,266 | 374,266 | 0 |
| IPC | 1.007383 | 1.148245 | +0.140862 (+13.983%) |
| Conditional accuracy | 96.163% | 96.009% | -0.154 pp |
| Direct jump accuracy | 100.000% | 100.000% | 0 pp |
| Call accuracy | 99.348% | 99.348% | 0 pp |
| Return accuracy | 100.000% | 100.000% | 0 pp |
| Ordinary indirect accuracy | 40.571% (1,863/4,592) | 99.129% (4,552/4,592) | +58.558 pp |
| `0x80000820`, cycles 120000-121999 | 30.909% (17/55) | 98.936% (93/94) | +68.027 pp |

The fixed cycle window contains a different number of dynamic hotspot executions because removing recoveries changes program progress. The window comparison therefore uses accuracy, not raw event-count equality.

All primary acceptance gates passed: ordinary indirect accuracy exceeds 75%, the conditional change stays inside the 0.2-point limit, returns remain at 100%, and Spike differential testing passes.
