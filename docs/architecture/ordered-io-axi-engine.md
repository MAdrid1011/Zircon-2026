# M3 ordered-device AXI owner

`AXIOrderedIOEngine` is the transport owner below `OrderedIOCombiner` for the
frozen M3 ordered-MMIO contract in ADR-0012 and Issue #47. It is independently
verified and is connected to `ZirconCore` through the exact-head LSQ owner. An
exact-head non-atomic device load is retained by the LQ until its ID-6 response
reaches the real `LoadCompletion`, while an exact-head authorized device store
waits for its B response before the SQ effect completes. `DeviceStrong` remains
one beat; `DeviceBurstable` collects one through four consecutive members before
the first AXI acceptance.

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
must never reconstruct metadata from a current ROB head. `LoadStoreQueues`
previews a DeviceBurstable head plus up to three contiguous following ROB tags.
It waits six full cycles for the fixed M0/M1 replay and LQ/SQ update path before
sealing the preview. `OrderedIOGroupStreamer` retains that immutable preview and
feeds every member through `OrderedIOCombiner`; only the combiner's output fire
marks every participating LQ/SQ entry effect-issued. A flush or squash before
that fire cancels the local streamer/combiner state. Once the AXI owner accepts
the group, normal response drain is irreversible.

The engine reserves AXI ID 6. IDs 0, 1--4, and 5 belong to fetch, L1D refill,
and the executable L2 dirty-writeback owner respectively. `ZirconCore` locks
the shared AR owner across backpressure, demultiplexes R/B by live owners, and
locks the shared W channel from any accepted AW through its matching WLAST so
ID-5/6/7 data cannot interleave without WID. It emits only 32-bit INCR bursts
with device `cache=0`, `lock=0`, `prot=001`, and `qos=0`.

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
recovery cancel path. The current core makes a device load externally eligible
only for its live ROB head and a device store only after commit authorization;
it blocks interrupts from group acceptance through the load retirement or store
effect completion.

## Verification and area mapping

`AXIOrderedIOEngineSpec` covers four-beat DeviceBurstable write ownership with
W-before-AW and B-error fanout, three-beat read AR backpressure with per-beat
RRESP attribution and response holding, plus one-beat DeviceStrong traffic.
Its explicit-seed component tier (`0x5eed0101`--`0x5eed0104`) additionally
covers every group size from one through four, read/write direction, independent
AR/AW/W pressure, per-read-beat RRESP error, group BRESP error, and held local
responses. A failure records the seed, group geometry, direction, fault choice,
and base address under `target/zircon-failures`.
`OrderedIOCombinerSpec` covers adjacency, force flush, strong-order groups, the
4 KiB split, full four-member streaming, and pre-accept cancellation.
`LoadStoreQueuesSpec` covers cross-generation ROB-wrap preview/acceptance.
`CoreShellSpec` covers exact-head single-beat traffic and one four-beat
DeviceBurstable load and store group with exact per-member retire metadata and
RRESP/BRESP faults through the top-level ID-6 demultiplexer. The focused
top-level four-seed tier (`0x5eed0201`--`0x5eed0204`) covers one through four
beat groups, alternating read/write direction, independent device AW/W/B
backpressure, exact `len`, and the retained LQ/SQ group members while an older
long divide keeps ROB pressure live. Its fetch warm-up deliberately presents the
complete group before the frozen six-cycle collection timer starts; general fetch
backpressure may legally seal a smaller group and is covered separately by the
AXI stress tiers. The focused commands are `make test-m3-ordered-io`,
`make test-m3-ordered-io-top`, and `make test-m3-device-io`.

The static ledger explicitly charges the retained AXI, combiner, and streamer
four-request groups, collection control, response hold, ID comparisons, and
dynamic request muxes. This does not make the ledger complete or claim final
MMIO integration; its omissions remain disclosed in `area/zircon-2026.json`.
