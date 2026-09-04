# ADR-0030: ROB age fanout domains

状态：Accepted

关联 Issue：#47

## 背景

The fixed-target `xc7a200tfbg676-2L` post-route implementation at
`393e07e` reports WNS `-40.479 ns`, TNS `-1,445,001.875 ns`, and 62,874
setup-failing endpoints. Physical optimization repeatedly replicated the
single `scheduledRobHeadTag` driver, yet the reported critical clusters still
include IntIQ, MemIQ, L1D/LSU, LongOperandBoundary, FloatingMovePipe, and
FirstFault control. The common structural cause is a shared ROB-age snapshot
reconverging into independent queue selection and recovery/squash logic.

## 决策

`ZirconCore` now creates parallel one-cycle ROB-head snapshots for Long,
Floating, MemIQ, LSU, L1D, and auxiliary-read age domains. `IntegerExecutionBackend`
does the same for IntIQ and short-pipe recovery. Every replica samples the
same live ROB head at the same edge; replicas are not chained.

The existing modulo-24 tag ordering, generation checks, selective squash,
global flush, and one-cycle scheduling latency remain unchanged. The change
only replaces one high-fanout physical driver with local registered drivers.

The similarly high-fanout integer-ready bitmap is split into parallel Long,
Floating, and MemIQ snapshots at the same edge.

## Consequences

The post-route placer can keep each age/ready consumer cluster local and no
longer needs to route a single 6-bit ROB tag and wide ready bitmap across all
execution domains. This does not by itself establish a timing gain: a new
fixed-device implementation is required before reporting WNS, TNS, frequency,
or area results.

## Verification

`./scripts/sbtw compile` and `make platform-verilog` pass. `CoreShellSpec`
is run as the integration regression; its pre-existing wrong-path refill
failure is tracked separately and must not be attributed to this change until
the parent-commit comparison is complete.
