# 访存子系统架构文档

本章是 M3 双 LSU、PMA、Cache、AXI4、MMIO 和 RV32A 的实现合同，关联
ADR-0012 与 Issue #47。当前 RTL 已有 `PMAClassifier`、局部
`OrderedIOCombiner`、已接入顶层 dispatch/recovery 的 8-entry `MemIssueQueue`，以及
已单元验证的 8-entry `LoadStoreQueues`。`ZirconCore` 已通过全局 auxiliary-read
arbiter 将 MemIQ 的 M0/M1 outputs 接入 `DualLSUIngress`；Cache、AXI data engine、
MMIO lifecycle 和 store effect 仍未实现，本规格不会把它们描述为已实现。

## 参数和边界

| Structure | Frozen value | Notes |
| --- | --- | --- |
| MemIQ | 8 compact `UopRef` | two enqueue, one M0 plus one M1 start maximum |
| LQ / SQ | 8 / 8 entries | tags identify live ROB owners |
| M0 | all integer loads/stores, atomics, device | only source of store/AMO/device actions |
| M1 | aligned cacheable integer loads | special candidates replay to M0 |
| L1I / L1D | 1 KiB, 2-way, 32 B line | L1D has four word banks and four MSHRs |
| L2 | 4 KiB default or 8 KiB comparison, 4-way, 32 B line | four MSHRs, dynamic I/D allocation |
| AXI4 | 32-bit address/data, four-bit ID | four read bursts and one write burst maximum |
| ordered device group | 1-4 beats | INCR only; never crosses 4 KiB |

M3 adds no second SoC memory port. The single `AXI4MasterPort` is shared between
L1I, refills, writebacks, and device groups by `AXIDataEngine`; no client drives a
top-level channel directly.

## Request and ownership records

The implementation creates typed records for the following boundaries:

| Record | Required content | Owner and lifetime |
| --- | --- | --- |
| `MemoryRequest` | `robTag`, operation, address, size, masks, store data, aq/rl | MemIQ until an LSU accepts it |
| `LoadQueueEntry` | `robTag`, address, width, destination, completion/forward state | allocation through retire, squash, or fault |
| `StoreQueueEntry` | `robTag`, address/data readiness, mask, committed state | allocation through committed effect, squash, or fault |
| `MemoryRetireMetadata` | `robTag`, address, read/write masks and data | LSQ/transaction state until exact retirement or kill |
| `AxiReadOwner` | ID, owner kind, tag, expected/received beats, cancelled, fault state | accepted AR through final R handshake |
| `AxiWriteOwner` | ID, owner kind, tag/group, expected W beats, response state | accepted AW through B handshake |

Ownership is exclusive. A request moves, rather than copies, between MemIQ,
LSQ/MSHR, device group, and AXI owner state. Every allocation, response, recovery,
and retirement path asserts credit conservation. An accepted AXI transaction always
remains in an AXI owner slot until it drains, even when its architectural owner was
killed.

## Dispatch, issue, and LSU routing

`BackendDispatch` sends every legal memory uop to MemIQ. The implemented
`MemIssueQueue` uses ROB age with source-ready wakeup, accepts two dispatch lanes,
and can choose one M0 plus one M1 request per cycle. It gives the oldest M1-eligible
load to M1, then lets M0 choose the oldest distinct M0-eligible uop; this permits
an atomic/store beside a load and two independent loads on separate LSU paths. It
drops only uops younger than a resolving branch and all local uops on global flush.
`ZirconCore` uses the frozen global three-start arbiter to pass these issue
channels to `DualLSUIngress`; the ready/valid handshake remains live through
operand read, admission, replay arbitration, and LQ/SQ ingress.

`ZirconCore` selects MemIQ's documented free-only admission mode: an issue in a
full queue frees its dispatch credit on the following cycle rather than feeding
same-cycle source wakeup back into backend dispatch capacity. The standalone
queue retains its tested same-cycle recycle mode. M0/M1 issue is backpressured
until the live LSU ingress accepts the uop, and Cache/data execution cannot
create a completion until its transaction owners are implemented.

M1 admission requires all of the following: load operation, naturally aligned
address, readable Memory PMA, no atomic/ordering restriction, no older SQ entry
with an unknown address, and an available LQ/cache resource. A failed admission
does not complete: it replays to M0. M0 handles every store, LR/SC, AMO, device
access, M1 replay, PMA access failure, and any load M1 cannot accept.

### Current dual-LSU admission boundary

`DualLSUAdmission` now applies that M1 rule before either LSU can own a request.
It sends only `MemoryAddressUnit.m1Eligible` normal loads to M1. A device,
misaligned, inaccessible, atomic, or otherwise ineligible M1 candidate enters a
one-entry replay owner with stable payload and exact tag; its explicit M0 replay
output is what the later M0/direct-input arbiter must consume. Global flush
removes this local speculative state, while selective recovery removes only a
younger replay. Neither the M1 path nor replay path has a completion, Cache, or
AXI effect at this stage.

`M0RequestArbiter` is the next ownership boundary after admission. It compares
the direct M0 and retained M1-replay candidates by ROB age and exposes exactly
one M0 request. When the downstream LSU backpressures the output, it locks the
selected source until its handshake; a newly arriving older replay cannot change
the held valid payload. Recovery suppresses both input ready signals and clears
the source lock. It neither allocates LSQ state nor creates a completion or
external effect.

Both LSUs receive their PC/instruction/privilege context from the ROB and produce
only ready/valid completions or `FaultCandidate`s indexed by their real ROB tag.
Each endpoint has one two-entry completion buffer shared by load results and
classified no-write fault completions. A faulting ingress batch is accepted only
when its endpoint buffer has credit, so `FirstFaultRecord` and the matching ROB
completion cannot diverge. Completion to a stale tag drains through the existing
completion network without PRF or ready-table mutation.

`MemoryOperandRead` is the current bridge from MemIQ to that future LSU boundary.
For each request it performs an exact-tag ROB context read, obtains base and
optional store operands from the shared integer PRF interface, and carries the
ROB-owned atomic `aq/rl` bits in `MemoryAddressRequest`. A missing/mismatched
context or global flush blocks the handshake. This ensures an LSU never rebuilds
ordering metadata by re-decoding a current instruction stream.

### Auxiliary PRF and start arbitration

M0/M1 have four virtual operand positions but do not add integer PRF ports.
E0/E1 retain ports 0-3; retirement trace has exclusive priority for auxiliary
ports 4/5. On a non-trace cycle the global arbiter selects E2, M0, and M1 by
ROB age subject to the remaining `3 - E0Starts - E1Starts` launch budget and
two auxiliary physical reads. It compacts only integer-register sources onto
those two ports. Thus an M0 store/atomic consumes both ports and serializes an
M1 load, whereas M0/M1 loads can start together. An ungranted request remains
in LongIQ or MemIQ with its original tag and never receives substituted data or
a completion. The two LSU ROB context views are exact live-tag reads separate
from the existing E0/E1 views; their mux cost is included in the M3 static-area
ledger. ADR-0013 freezes this interface and arbitration rule.

`DualLSUIngress` composes `MemoryOperandRead`, `DualLSUAdmission`,
`M0RequestArbiter`, and `MemoryQueueIngress` as one module-level request path.
It retains the two M0/M1 MemIQ inputs, four shared-PRF read signals, two exact
ROB-context reads, two `FaultCandidate` outputs, and LSQ forwarding/retirement
interfaces. An eligible cacheable load reaches LQ ownership through M1; a
device, atomic, or alignment/PMA-rejected candidate follows the replay-to-M0
path. The selected owner bit is retained in the LQ until a later external
`LoadCompletion` response. `DualMemoryLoadCompletion` then routes that result
to separate two-entry M0 or M1 completion buffers; backpressure is therefore
per owner and recovery accepts no new result. This is only the response-to-
completion ownership boundary: no Cache, AXI, or irreversible store action is
generated here. `ZirconCore` wires the two buffers to frozen completion endpoints
M0/M1 and shares their operand reads with E2 through the top-level PRF-port
arbiter.

## PMA and precise exceptions

`PMAClassifier` remains first-match by configuration order. Its default regions
are Memory `0x8000_0000-0x8fff_ffff`, DeviceStrong
`0xa000_0000-0xa000_ffff`, and DeviceBurstable
`0xb000_0000-0xbfff_ffff`; unmatched space is inaccessible. Read/write/atomic
permission is checked after address generation and before LQ/SQ/cache/AXI
allocation.

All load/store/AMO addresses must be naturally aligned. A misaligned operation
creates an exact load or store/AMO misaligned fault with its effective address as
`tval`; it never splits across words or AXI beats. PMA denial and AXI RRESP/BRESP
failure similarly create one exact access fault for the owning ROB entry. The
`FirstFaultTracker` selects the oldest live fault and recovery removes younger
metadata before any trap redirect.

### Current address boundary

`MemoryAddressUnit` is the shared combinational M0/M1 boundary. It derives the
effective address, width, unsigned-load flag, byte masks, shifted store data,
and natural-alignment result from the decoded RV32I/A operation. It invokes the
configured `PMAClassifier` before any queue allocation: M0 receives exact load
or store/AMO misalignment/access fault classification and the effective address
as `tval`. M1 admission is deliberately narrower, accepting only naturally
aligned, readable `Memory`-PMA non-atomic integer loads; every other candidate
must replay to M0. Atomic `aq/rl` remains in the request/ROB context and is
carried through the typed result rather than being reconstructed later.

## LQ, SQ, forwarding, and commit effects

Loads wait while any older store address is unresolved. After all are resolved,
the youngest older same-byte store wins per byte lane. Full forwarding completes
without a cache request; partial forwarding merges forwarded byte lanes with the
returned cache word. A load never observes a younger store.

Stores calculate address/data speculatively into SQ, but are not cache-visible
until their ROB entry reaches commit authorization. A committed cacheable store
may update L1D or allocate/write back according to the cache protocol; committed
device writes wait for the B response. Stores, AMOs, and device operations block
their retirement until their irreversible action succeeds. A squash deletes only
uncommitted SQ/LQ entries and suppresses their completion, never a previously
accepted AXI drain.

`MemoryRetireMetadata` is populated by the true LSU/LSQ effect. The trace
formatter reads it only when that tag retires, filling address, masks, and data;
it must not infer effects from a current AXI response or fetch PC.

### Current LSQ boundary

`MemoryTypes.scala` and `LoadStoreQueues.scala` now define the local ownership
boundary below MemIQ. The queue has two-wide `MemoryQueueAllocate` admission,
but an allocation reserves only speculative LQ/SQ state and cannot perform an
external memory action. Address and store data use separate ready/valid updates:
this lets an older known-address store block a same-word load until its data is
also available, rather than accidentally exposing stale cache data.

| Interface | Rule implemented by the queue |
| --- | --- |
| `LoadAddressQuery` / `LoadStoreForward` | accepts only a live LQ owner after every older SQ address is known and every matching older store has data; selects the youngest older store independently for each byte lane |
| `loadContextRead` / `LoadQueueContext` | reads the allocated width, signedness, integer destination, atomic and `aq/rl` ownership by exact ROB tag; this is the later LSU completion source, never reconstructed from a bus response |
| `LoadCompletion` | merges the retained byte-forward mask/data with a cache word and creates the `MemoryRetireMetadata` record keyed by the real ROB tag |
| `StoreAddressUpdate` / `StoreDataUpdate` | fill separate SQ readiness state; its retained access width, mask, data and atomic ordering are emitted only with a commit-authorized effect; no cache, AXI, or device interface is present at this point |
| `commitAuthorize` / `StoreEffect` / `StoreEffectComplete` | only an address-and-data-ready exact tag can be authorized; only an authorized tag can issue an effect, and success is required before it may retire |
| `retire`, `squash`, `flush` | retirement reads metadata before releasing its local owner; selective squash removes only younger, non-authorized work; a flush asserts that it cannot discard an authorized store |

The module asserts queue depth, unique live ownership by ROB tag, legal two-wide
allocation, no transfers during recovery, and no retirement before the relevant
load or committed store effect completes. `LoadStoreQueuesSpec` currently covers
unknown address/data blocking, full and partial byte forwarding, same-address
youngest-store selection, both queues full, commit-only store effects, atomic
read/write metadata composition, metadata lifetime, and ROB-wrap selective
recovery. Dual-LSU conflict resolution, PMA,
fault creation, cache access, and AXI drains remain the next integration layer.

`LoadCompletion` is now a ready/valid response boundary. The LQ publishes a
`MemoryLoadResult` only when its corresponding result sink is ready, and marks
the LQ complete plus writes retire metadata only on that same handshake. The
result contains the byte-forwarded word, integer destination/write intent, and
load width/sign rule. `MemoryLoadCompletion` converts this record to the
architectural byte/halfword/word value and retains it in one two-entry
`CompletionBuffer`; a third response backpressures instead of being lost. Its
`m1Owner` is allocated with the LQ record rather than reconstructed from a bus
response. `DualMemoryLoadCompletion` uses it to select one independent M0 or
M1 two-entry buffer, so an M0 response cannot consume M1 capacity and vice
versa. The same endpoint buffer also accepts an exact classified access fault
as a non-writing `CompletionResult`; an older load result wins local admission
when both contend, and the fault waits with its original ingress request for a
credit. `ZirconCore` connects the two outputs to completion endpoints M0/M1 and
connects the accepted fault candidates to the matching backend fault inputs;
the top-level still has no Cache/data-AXI response producer.

`MemoryQueueIngress` is the first live lifecycle layer above those queues. It
accepts up to two already address-classified M0/M1 requests, emits an exact
`FaultCandidate` for a misaligned/access-fault request without allocating queue
state, but keeps that request unaccepted until its M0/M1 completion owner has
credit. The accepted fault simultaneously enters its endpoint buffer as a
non-writing completion. Normal requests perform a two-stage handoff: an atomic
LQ/SQ allocation batch followed on a later cycle by load-address and/or
store-address/store-data updates. It schedules each update channel by ROB age
and holds the batch through LSQ backpressure. Selective recovery or global flush
blocks every handoff and removes only killed ingress state while propagating the
same signal to the queues. This module exposes forwarding and later
completion/commit interfaces but does not yet produce a completion, Cache, AXI,
or store side effect.

## Cache hierarchy

L1I and L1D use 32-byte lines and two ways. L1D is write-back/write-allocate,
has four word banks and four MSHRs, and supports hit-under-miss, miss-under-miss,
and same-line secondary merge. Its two LSU requests define an explicit conflict
matrix: dual hit may proceed when bank/port resources allow; hit/miss, dual miss,
same bank/set/line/address, MSHR full, and victim-full cases either allocate their
specified resource or backpressure/replay deterministically.

L2 has four ways and four MSHRs. The 4 KiB (32-set) configuration is the default;
8 KiB is solely the M5 A/B point. L2 dynamically serves I and D demand and does
not reserve ways by client. I-side is non-inclusive. D-side is exclusive: each
stable D line belongs to exactly one of L1D, L2, or a transfer buffer. L1D fill
removes any L2 copy; L1D eviction transfers the line to L2. A two-entry
victim/writeback queue owns dirty evictions until L2 or AXI accepts them.

`FENCE.I` commit drains old stores/device actions, invalidates L1I and BTB, then
uses the existing commit redirect. `FENCE`, `aq`, and `rl` prevent retirement or
issue until their required LQ/SQ/device/outstanding-owner sets drain; no broad
memory-dependence predictor is permitted.

## AXI4 data engine

The engine grants at most four outstanding accepted AR bursts and one AW/W/B burst.
It assigns a unique live read ID, holds every channel payload stable while
`valid && !ready`, and emits only aligned INCR bursts. Cache line refills are
eight 32-bit beats; device groups are at most four; all obey the 4 KiB boundary.

Each R beat is checked against its owner ID, expected beat count, and RLAST. An
unknown ID, duplicate beat, early/late RLAST, response after owner release, or
credit underflow is an immediate assertion. RRESP or BRESP errors are retained on
the original owner until it yields its exact fault. Read responses may interleave
across IDs; individual beats of one ID remain ordered. On selective squash/global
flush, non-architectural accepted reads switch to `cancelled` and drain without
completion; neither their IDs nor their MSHR credits are reused early.

## Ordered MMIO

Only M0 may create `OrderedIORequest`. `DeviceStrong` produces a one-beat group.
`DeviceBurstable` groups one to four consecutive ROB orders with equal direction,
size, and PMA region and exactly adjacent addresses, terminating at a direction,
width, region, address, 4 KiB, or force-flush boundary. The next device request
cannot overtake the active group. For reads, each returning beat updates its own
ROB-tag metadata/completion; for writes, each member becomes irreversible only
after the group B response. PMA boundaries and AMOs never join a group.

## RV32A

LR/SC and AMOs are M0-only, naturally aligned, and permitted only by atomic
Memory PMA. Reservation is one 32-bit word. LR installs a reservation after a
successful read. SC returns zero only if the matching reservation is live; it
returns one otherwise and clears the reservation either way. A conflicting local
store/AMO, trap, interrupt, or replacement invalidates it. AMOs serialize their
read-modify-write effect and await any required AXI/cache response. Device space
and non-atomic PMA generate precise faults rather than fake atomic completion.

## Recovery, drain, and counters

Selective recovery removes younger MemIQ/LQ/SQ/completion entries. Global flush
removes all speculative state after preserving accepted AXI owner drains. A kill
cannot write GPR state, cache state, device state, or trace data. Every MSHR,
victim, queue, completion buffer, device group, and AXI owner has occupancy and
credit-conservation assertions.

M3 exposes counters for M0/M1 issue/replay, LQ/SQ/MemIQ occupancy, full stalls,
forward full/partial hits, L1/L2 hit/miss, MSHR/victim occupancy, outstanding AXI
read/write count, response errors, MMIO group size, and interrupted/blocked
irreversible work. M5 reports these counters with IPC; their absence cannot be
represented as zero activity.

## Verification mapping

`MemIssueQueueSpec`, `LoadStoreQueuesSpec`, `DualLSUSpec`, `AXIDataEngineSpec`,
`L1DataCacheSpec`, `L2CacheSpec`, and `MemoryCoreIntegrationSpec` are required
before M3 can advance from missing to implemented. They cover the full dual-LSU
conflict matrix, byte forwarding, MSHR/victim pressure, exclusive ownership,
AXI ID/beat/RLAST/error/drain behavior, MMIO 1-4 beat groups, every RV32A
operation, and deterministic explicit-seed backpressure. ELF/Spike/Sail evidence
must compare the committed memory fields in `RetireEvent`.
