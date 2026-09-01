# M3 ordered-device AXI owner

`AXIOrderedIOEngine` is the transport owner below `OrderedIOCombiner` for the
frozen M3 ordered-MMIO contract in ADR-0012 and Issue #47. This module is an
independently verified AXI lifecycle boundary. It is not yet connected to the
LSQ/ROB in this revision, so device instructions remain blocked by the core
until the following integration change.

## Parameters and interface

The owner is fixed at four requests per `OrderedIOGroup`; each group has a
nonzero `count` from one through four. Every live member must have the same
direction, transfer width, PMA-region tag, and contiguous INCR address. A
non-burstable (`DeviceStrong`) group has exactly one request. These constraints
are asserted at the input boundary, including the 4 KiB rule.

The `group` Decoupled input transfers ownership of one already-authorized group.
The `response` Decoupled output produces one exact `OrderedIOResponse` per
member: `{robTag, address, write, readData, accessFault}`. Future LSQ wiring
maps read responses to LQ completion and writes to SQ effect completion; it
must never reconstruct metadata from a current ROB head.

The engine reserves AXI ID 6. IDs 0, 1--4, and 5 remain fetch, L1D refill, and
cacheable-store ownership respectively. It emits only 32-bit INCR bursts with
device `cache=0`, `lock=0`, `prot=001`, and `qos=0`.

## State machine and drain rules

After a group handshake, the owner retains the complete group record, address
handshake state, beat count, write-response mode, sticky write fault, and one
response hold register. No following group is accepted until every required
AXI response and every local response handshake has drained.

For reads, AR is held until accepted; each R beat is checked against ID 6 and
the exact expected `RLAST`, then held as one response before the next beat may
be accepted. An RRESP error marks only that group member faulting.

For writes, AW and W are independently held and W may precede AW. B remains
backpressured until both channels complete. Its OKAY/EXOKAY or error status is
then emitted once for every group member in program order. Thus no member can
retire before the irreversible transaction has a B response.

Unknown ID, read/write direction mismatch, premature B, incorrect RLAST,
zero/oversized group count, mixed group properties, non-contiguity, 4 KiB
crossing, or empty write masks trigger assertions. An accepted group has no
recovery cancel path: future LSQ integration may suppress architectural
completion for a killed speculative record only if it first guarantees the
group was never externally authorized.

## Verification and area mapping

`AXIOrderedIOEngineSpec` covers four-beat DeviceBurstable write ownership with
W-before-AW and B-error fanout, three-beat read AR backpressure with per-beat
RRESP attribution and response holding, plus one-beat DeviceStrong traffic.
`OrderedIOCombinerSpec` covers adjacency, force flush, strong-order groups, and
the 4 KiB split. The focused command is `make test-m3-ordered-io`.

The static ledger explicitly charges the retained four-request group, group
control, response hold, ID comparisons, and dynamic request muxes. This does
not make the ledger complete or claim final MMIO integration; its omissions
remain disclosed in `area/zircon-2026.json`.
