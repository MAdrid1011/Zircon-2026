# M1 AXI Instruction Fetch Transport

`AXIInstructionFetch` is the executable-M1 instruction transport before the
formal L1I is implemented. It has one outstanding AXI4 read burst and turns a
read response into one four-or-fewer word fetch packet. It is not an I-Cache:
every accepted packet may cause an AXI transaction. M3 may replace it only if
the AXI, redirect-drain, and per-word fault contract below stays true.

## Fixed Parameters

| Item | Value |
|---|---:|
| instruction width | 32 bit |
| fetch packet maximum | 4 words |
| outstanding read bursts | 1 |
| AXI ID | 0 |
| AXI size / burst | 4 bytes / INCR |
| instruction alignment | 4 bytes |
| response fault cause | instruction access fault (`1`) |

The request size is `min(4, (4096 - pc[11:0]) / 4)`. A request never crosses
an AXI 4 KiB boundary. `ARLEN` is the resulting word count minus one.

## Interface

| Signal | Direction | Meaning |
|---|---|---|
| `enable` | input | Allows an idle transport to capture `currentPc` and start a request. |
| `redirect` | input | Valid, four-byte-aligned frontend/commit/execution target. |
| `ar` | output | AXI read-address channel for the captured request. |
| `r` | input | AXI read-data channel for the sole outstanding request. |
| `response` | output | Complete packet of 1--4 words, each with independent fetch-fault data. |
| `responseNextPc` | input | Sequential/predicted PC captured only when `response` fires. |
| `currentPc` | output | Target used by the next idle request. |
| `busy` / `draining` | output | Transaction occupancy and redirect-driven discard visibility. |

## State Machine

- `Idle`: no captured request. With `enable`, capture `currentPc`, compute the
  bounded burst length, and enter `Request`.
- `Request`: keep every AR payload field stable until handshake. A redirect
  changes only the next `currentPc`; it marks the captured request for discard.
  If that AR later fires, the transport enters `Drain`, not `Receive`.
- `Receive`: accept exactly the captured number of beats. `OKAY` and `EXOKAY`
  retain their data; `SLVERR` and `DECERR` write zero data and a fault with the
  exact beat address. The expected final beat enters `Present`.
- `Present`: hold one complete packet until accepted. Redirect suppresses the
  packet and returns to `Idle` without exposing it as a new response.
- `Drain`: after redirect of an accepted transaction, consume every remaining
  beat through the expected `RLAST`, discard all data, then return to `Idle`.

Redirect takes priority over normal state progress. Redirect in `Request`
therefore cannot silently withdraw a backpressured AR. Redirect in `Receive`
or `Drain` retains the outstanding transaction until its final beat, while a
redirect in `Present` discards the unconsumed packet. The latest redirect PC is
the base of the following request.

## Errors And Invariants

- Redirect targets must satisfy `IALIGN=32`.
- The captured burst has 1--4 beats and ends at or before the next 4 KiB
  boundary.
- Every accepted R beat has ID 0 and an `RLAST` value equal to its expected
  final-beat position. Unknown IDs, duplicate/early/late beats, and wrong
  `RLAST` terminate simulation through assertions.
- An accepted AR is never cancelled. It produces either one ordered packet or
  a complete background drain.
- A discarded burst never produces `response.valid`; it never changes
  `currentPc` after the redirect target is installed.

## Verification Mapping

`AXIInstructionFetchSpec` covers normal four-beat delivery; 1/2/3 beat 4 KiB
boundary bursts; sustained AR backpressure; redirects before AR acceptance and
in `Receive`, `Present`, and `Drain`; per-word `RRESP` faults; and unknown-ID,
early-`RLAST`, late-`RLAST` assertion failures. Core-level AXI ordering,
multi-ID, refill, and response ownership verification starts with M3, when the
single outstanding M1 transport is replaced by the cache/AXI scheduler.
