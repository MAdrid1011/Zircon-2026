# ADR-0031: Narrow ROB control sideband

状态：Accepted

关联 Issue：#47

## 背景

The `a7930a3` post-route report still had a worst path from
`ROB.entryData[*].decoded.operation` through commit serialization and
redirect control into LSU/L1D ready logic: WNS `-38.261 ns`, with 82% of the
48.197 ns path in routing. The complete `ROBEntry` payload was being used for
both precise retirement/trace and narrow control decisions.

## 决策

The ROB stores a parallel `ROBControlInfo` register per entry containing only
valid, uop class, operation, load/store, and fence bits. Commit policy and the
top-level Load/Store/FENCE controls consume this sideband. The complete entry
remains on the ROB commit/retire interface for EPC, fault metadata, rename
retirement, and trace. Standalone `CommitController` users retain a decoded
fallback when the sideband is not marked valid.

## Consequences

Control paths no longer need to route the full decoded operation bundle from
ROB storage into LSU/L1D arbitration. The sideband adds a small amount of
registered state and keeps exact retirement and redirect semantics unchanged.
The fixed-device implementation must be rerun before claiming a timing gain.

## Verification

`CommitControllerSpec` 6/6, `IntegerExecutionBackendSpec` 2/2,
`IntegerDispatchRecoveryBackendSpec` 3/3, compile, and the focused top-level
RV32I CoreShell test 1/1 pass. The previous full CoreShell run remains
documented separately with its known parent-baseline failures.
