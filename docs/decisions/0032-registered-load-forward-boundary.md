# ADR-0032: Registered load-forward boundary

状态：Accepted

## 背景

The fixed-device timing report showed the dominant setup cluster ending at
`LoadStoreQueues.lqForwardData[*]`.  The L1D ready signal could travel back
through `loadForward` and the memory ingress into the load queue's write-enable
cone, where the wide memory operation payload remained visible in the same
cycle.  Registering the operand-to-address handoff did not isolate this
load-response admission path.

## 决策

The production `ZirconCore` inserts one non-fall-through registered boundary
per load-forward lane between `DualLSUIngress` and L1D.  Its input ready depends
only on local occupancy and recovery state; downstream L1D ready cannot feed
back into the LSQ in the same cycle.  A held request is discarded on global
flush or when its ROB tag is younger than a selective squash boundary.

The boundary does not emit completion or fault information.  It only retains a
real `LoadStoreForward` transaction until L1D accepts it, preserving existing
cache/MMIO ownership and exact retirement behavior.  A full boundary may insert
one bubble, which is an allowed ready/valid behavior.

## Verification

Run the boundary unit tests, the focused CoreShell RV32I/LSU tests, compile, and
`make platform-verilog` before the next fixed-device implementation on
`xc7a200tfbg676-2L`.
