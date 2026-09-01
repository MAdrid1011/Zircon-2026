# M3 L1 Instruction Cache

`L1InstructionCache` is the active M3 instruction-side cache boundary between
`M1Frontend` and the shared L2 demand AXI owner. It preserves the frontend's
four-wide packet contract while replacing M1's direct AXI ID-0 transport.

## Geometry and ownership

| Item | Value |
|---|---:|
| capacity | 1 KiB |
| associativity | 2 ways |
| line size | 32 bytes / 8 words |
| sets | 16 |
| local MSHRs | 1 |
| external refill owner | one shared ID 1--4 L2-demand slot |

L1I holds clean instruction lines only and is non-inclusive with D-side
ownership. A line returned to L1I may be retained locally after its L2 demand
slot has been released. This executable slice does not implement an L2
resident I-side lookup, an I-side L2 fill array, or I/D coherence.

## Request and packet lifecycle

When `enable` is accepted in `Idle`, L1I captures the frontend PC. It requests
at most four consecutive words, clipped at the 32-byte line boundary. Thus a
request at word six returns two words rather than constructing a cross-line
packet. `responseNextPc` changes the next idle PC only when the packet is
accepted by the frontend.

A tag hit copies the captured prefix into a held packet. A local miss first
issues a read-only probe to the resident L2 store. An L2 hit copies the line
into L1I without removing the L2 D-side owner. An L2 miss chooses an invalid
way first, then the one-bit replacement way, and emits exactly one
`L2DemandRequest` with `client=Instruction`, `clientMshr=0`, and a line-aligned
address. After the request handshake, the cache waits for the complete retained
`L2DemandResponse`. A successful response fills the selected line and creates
the packet. Any response fault creates exact instruction-access faults at each
selected word address and never marks the line valid.

While a first-half, sequential packet is held, the single local MSHR may issue
one lookahead for the next 32-byte line. `M1Frontend` permits this only when
predecode finds neither a predicted redirect nor a targetless JALR barrier. The
lookahead never creates a packet or a second MSHR: a normal lookup that reaches
the retained line waits for the lookahead response, then uses the filled line.
An errored lookahead is discarded and the later demand receives the architectural
fault normally.

The frontend does not start a continuation or lookahead from a packet containing
an instruction access fault; precise fault dispatch owns the next control flow.

## Redirect, invalidation, and drain

Redirect has priority over normal progress. Before a resident-L2 probe or local
demand request handshakes it cancels that request. After a probe handshakes it
drains its held response without changing L1I; after a demand handshakes, L1I
remains ready for its exact complete response, enters `Drain`, and discards the
returned line and packet. A held packet is suppressed by the redirect. The
latest redirect target becomes the next request PC.

The same rule applies to an accepted lookahead: a redirect suppresses its held
packet, drains the retained response, and does not install the speculative line.

`FENCE.I` supplies `invalidate` with its commit redirect. It clears all L1I
valid bits, suppresses a held packet, and uses the same accepted-refill drain
rule. It does not claim to drain stores or device actions; `CommitController`
must already have satisfied that serialization condition before emitting the
redirect.

## Shared demand interface

L1I first probes `ExclusiveL2TransferStore` through a read-only port; the probe
never transfers or invalidates a resident D line. L1I and L1D arbitrate into
`AXIDataReadEngine` on an L2 miss. The request arbiter has no
cache-reserved physical slots: the engine selects one free owner, retains the
client/token until the eighth R beat, then returns one complete response.
`ZirconCore` routes the response only to the retained `Instruction` or `Data`
client. An unknown client is an assertion failure. L1I never sees raw AXI R
beats and cannot reclaim an ID before the engine has fully drained it.

## Verification mapping

`L1InstructionCacheSpec` covers cache hit/miss, resident-L2 hit, refill data, replacement,
line-end packet clipping, response faults, held demand backpressure, sequential
lookahead, redirect before and after accepted normal/lookahead demand, and
invalidation. `M1FrontendSpec` covers
prediction and decode behavior using complete L2 line responses. `CoreShellSpec`
covers shared I/D demand arbitration and retained client response demultiplexing.
