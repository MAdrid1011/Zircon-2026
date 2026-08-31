# 访存子系统架构文档

本章是 M3 双 LSU、PMA、Cache、AXI4、MMIO 和 RV32A 的实现合同，关联
ADR-0012 与 Issue #47。当前 RTL 已有 `PMAClassifier`、局部
`OrderedIOCombiner`、独立的 8-entry `MemIssueQueue`，以及已单元验证的 8-entry
`LoadStoreQueues`；`ZirconCore` 仍把 memory capacity 置零。LSU、Cache、AXI data
engine 与 MMIO lifecycle 尚未接入，本规格不会把它们描述为已实现。

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
The global three-start arbiter and live LSU handshakes remain integration work.

M1 admission requires all of the following: load operation, naturally aligned
address, readable Memory PMA, no atomic/ordering restriction, no older SQ entry
with an unknown address, and an available LQ/cache resource. A failed admission
does not complete: it replays to M0. M0 handles every store, LR/SC, AMO, device
access, M1 replay, PMA access failure, and any load M1 cannot accept.

Both LSUs receive their PC/instruction/privilege context from the ROB and produce
only ready/valid completions or `FaultCandidate`s indexed by their real ROB tag.
Each endpoint has a two-entry completion buffer. Completion to a stale tag drains
through the existing completion network without PRF or ready-table mutation.

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
