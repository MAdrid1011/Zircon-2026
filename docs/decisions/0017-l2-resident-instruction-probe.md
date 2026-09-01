# ADR-0017: L2 resident instruction probe

Status: Accepted

Related Issue: #47

## Context

The active L1I shared-demand slice always consumed an AXI-backed L2 demand
owner on a local miss, even when the exclusive L2 store already retained the
same line after a D-side eviction. The frozen hierarchy requires dynamic I/D
use without violating D-side exclusivity.

## Decision

`L1InstructionCache` first issues a line-aligned read-only probe to
`ExclusiveL2TransferStore`. A hit snapshots the resident line into L1I but
does not remove, invalidate, or change the L2 owner. A miss follows the
existing retained `Instruction` request through `AXIDataReadEngine`. Accepted
probe responses drain on redirect before L1I accepts another request.

## Consequences

- D-side exclusive transfer remains unchanged: only D lookup removes an L2
  line, and a probe never exposes an AXI ID.
- This is not final dynamic I/D allocation: AXI-refilled I lines do not yet
  allocate into L2, and resident I fill/coherence/formal proof remain M3 work.
- The static ledger charges the extra L2 response hold, tag comparisons, and
  line snapshot mux. Directed L1I, L2, frontend, and top-level tests cover the
  new boundary.
