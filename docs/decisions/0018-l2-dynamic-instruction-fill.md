# ADR-0018: Dynamic L2 instruction-fill allocation

Status: Accepted

Related Issue: #47

## Context

ADR-0017 lets L1I read a resident D-side L2 line, but an AXI-refilled
instruction line is installed only in L1I. That leaves the L2 partition
effectively D-only after an external refill and violates the frozen dynamic I/D
allocation contract.

## Decision

`L1InstructionCache` presents every complete, non-faulting `Instruction` demand
response as a clean `instructionInsert` to `ExclusiveL2TransferStore`. The
L2 handshake is required before L1I installs or presents the line. L2 allocates
the line in its ordinary four-way sets without a client-reserved way; the L1I
copy is non-inclusive.

The single L2 array port gives D-side insertion priority, then instruction
insertion, then exclusive D lookup, then read-only I probe. A clean I insert
may displace a dirty resident line only after the normal two-entry victim FIFO
accepts it. If an exact L2 line appears between L1I's earlier probe miss and
its AXI refill, the I insert is accepted as a merge rather than a duplicate:
L2 retains its resident copy and returns that line to L1I for the local fill.
This prevents stale AXI data from silently replacing or bypassing a newer
resident D line.

Faulting and redirect-drained responses never allocate an I line. This ADR
does not make self-modifying code or external coherency complete; cache-global
FENCE semantics and their formal proof remain M3 work.

## Consequences

- L2 way usage is now genuinely dynamic across clean I fills and exclusive D
  transfers, while stable D-line exclusivity is unchanged.
- An L1I AXI response remains backpressured until its L2 allocation or merge
  can complete; the retained AXI owner remains responsible for the response.
- Directed tests must cover clean allocation, dirty-victim backpressure,
  D-priority, and the probe-miss/refill collision data selection.
