# Claim Validation

| Claim | Metric | Expected | Observed | Verdict |
| --- | --- | --- | --- | --- |
| History-tagged targets fix the CoreMark multi-target JALR | Ordinary indirect accuracy | At least 75% | 99.129% | supported |
| The hotspot no longer behaves as a last-target predictor | `0x80000820` short-window accuracy and target diversity | Clear gain across multiple targets | 98.936%, seven targets observed | supported |
| The change improves useful work throughput | Cycles and IPC | Fewer cycles, higher IPC | -12.268% cycles, +13.983% IPC | supported |
| Direction prediction is not materially regressed | Conditional accuracy | No more than -0.2 pp | -0.154 pp | supported |
| RAS behavior is preserved | Return accuracy | At least 100% baseline | 100% | supported |
| Architectural correctness is preserved | Spike differential result | Pass | Pass | supported |
| The design meets implementation timing and area targets | Synthesis results | No regression outside budget | Not synthesized in this run | inconclusive |
