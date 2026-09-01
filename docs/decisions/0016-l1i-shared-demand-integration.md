# ADR-0016: L1I shared-demand integration

Status: Accepted

Related Issue: #47

## Context

`AXIInstructionFetch` made M1 executable by owning a single ID-0 AXI burst.
It is not an instruction cache: every packet can reach external memory, and
its owner cannot participate in the frozen four-credit L2 demand pool. ADR-0014
already preserves an `Instruction` client kind in `AXIDataReadEngine`, but the
client has not been connected to a cache or to `ZirconCore`.

The frozen M3 geometry requires a 1 KiB, two-way, 32-byte-line L1I with one
local MSHR. L1I is non-inclusive with respect to L2. The current executable L2
slice does not yet contain an I-side resident array or I-hit path, so this
change must not claim either of those functions.

## Decision

`L1InstructionCache` replaces `AXIInstructionFetch` inside `M1Frontend`. It
retains the existing packet, predictor, redirect, and fetch-fault boundary:

- a request starts at the frontend PC and returns a contiguous prefix of one to
  four words which never crosses its 32-byte cache line;
- a tag hit returns cached words without an AXI request; a miss uses the one
  local L1I MSHR and sends `{client=Instruction, clientMshr=0, lineAddress}` to
  the shared L2 demand-read engine;
- a completed successful line response fills L1I and presents the original
  packet; an error response fills no cache state and produces exact per-word
  instruction-access faults;
- a redirect before L2-demand acceptance cancels the local request. A redirect
  after acceptance drains its retained L2 response, discards its packet, and
  does not reuse the local MSHR early;
- a committed `FENCE.I` invalidates all L1I valid lines while its existing
  frontend redirect retains priority over speculative recovery.
- while a first-half sequential packet is held, L1I may use its now-free local
  MSHR for one retained next-line lookahead. `M1Frontend` suppresses this for a
  predicted redirect or targetless JALR; an accepted lookahead follows the same
  redirect drain rule and never creates a second local MSHR.

`ZirconCore` arbitrates L1I and L1D `L2DemandRequest`s fairly into the one
`AXIDataReadEngine` and demultiplexes each complete response by its retained
client kind. The engine remains the only owner of AXI IDs 1--4. The obsolete
ID-0 fetch owner is removed from the production top-level AR/R mux; ID-5,
ID-6, and ID-7 keep their writeback, ordered-device, and atomic ownership.

## Alternatives Considered

- Keep a direct ID-0 path next to L1I: rejected because it creates two
  instruction refill mechanisms and makes the active L1I demand client
  optional rather than architectural.
- Let L1I allocate an L1D MSHR or use a fixed AXI ID: rejected because local
  cache state is not physical L2 read-owner state.
- Refill L1I from the D-exclusive transfer store: rejected because I-side is
  non-inclusive and a D-owned line cannot become a duplicate L1I owner through
  that boundary.
- Call a direct external demand a complete L2 I-hit implementation: rejected.
  A resident, dynamically shared L2 I-side lookup remains required M3 work.

## Consequences

- M1Frontend tests move from AXI packet injection to complete, retained L2
  line responses. The packet/prediction contract remains unchanged.
- L1I tests must cover hit/miss, line-end prefixes, RRESP faults, request
  backpressure, sequential lookahead, pre-accept cancellation, post-accept
  normal/lookahead drain, and FENCE.I invalidation. Core tests must prove I/D
  client token demultiplexing.
- Every accepted L2 demand continues to drain through `AXIDataReadEngine`.
  No redirect, cache invalidation, or response error can free IDs 1--4 early.
- This is an active L1I demand slice, not final dynamic L2 I/D allocation,
  formal L1I proof, full cache ordering, or final M3 release evidence.
