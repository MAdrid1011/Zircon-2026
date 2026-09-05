# ADR-0035: Narrow ROB head tag snapshots

状态：Accepted

## 背景

Timing reports repeatedly showed `ROB.headIndex` routing into LSU, L1D,
MemIQ, and auxiliary scheduling domains. The production age snapshots were
fed from `robHead.bits.robTag`, which is selected alongside the complete ROB
entry payload.

## 决策

All core age-domain snapshots are now sourced from the dedicated narrow
`backend.io.robHeadTag` output before their existing per-domain register. This
removes the complete ROB head payload mux from the age-control driver while
preserving the same one-cycle snapshot latency and tag-generation semantics.

## 验证

Run compile, platform RTL generation, backend smoke, and a fixed-device
implementation before claiming a timing improvement.
