# ADR-0029: Narrow integer wakeup state

状态：Accepted

关联 Issue：#47

## 背景

The fixed-target place report for `xc7a200tfbg676-2L` showed the worst path
starting at the registered integer wakeup and traversing every IntIQ entry's
wide `UopRef` source-ready update, oldest selectors, operand issue, and the
E0 fault observation cone.  The path measured 49.932 ns and 87 logic levels;
the majority of delay was routing.

## 决策

`IntegerIssueQueue` stores dynamic `sourceReady` state in a separate narrow
three-bit register bank.  `entryUop` retains static payload and source
physical numbers only.  Wakeup compares against those static physical
numbers, updates the narrow bank, and issue output overlays the current
readiness onto the unchanged `UopRef` payload.

Enqueue still observes same-cycle registered wakeup, queued entries still
observe wakeup before selection, and source 2 remains non-wakeup state.  The
ready/valid, age ordering, endpoint exclusivity, squash, flush, occupancy, and
full-queue recycling contracts are unchanged.

## Consequences

The wakeup-to-selector path no longer writes a wide per-entry payload and
does not fan out through unrelated metadata bits.  The queue retains the same
architectural latency and may still be limited by the remaining oldest-entry
and fault metadata cones; those paths must be measured again on the fixed
device before claiming timing improvement.

## Verification

`IntegerIssueQueueSpec`, `IntegerExecutionBackendSpec`, and
`IntegerDispatchRecoveryBackendSpec` pass (12 tests).  `make platform-verilog`
and the deterministic RV32I/RV32M/RV32A ZirconSim `tohost` suite pass.  A new
fixed-target synthesis/place/route run is required for quantitative timing
evidence.
