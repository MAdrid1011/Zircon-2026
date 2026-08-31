# M1 Frontend

`M1Frontend` is the executable RV32I frontend boundary. It combines the
temporary one-outstanding `AXIInstructionFetch`, the frozen 512-entry banked
bimodal direction predictor, 64-entry BTB, 8-entry speculative RAS,
predecode/history scan, and the four-entry FetchDecodeQueue. It emits two
in-order `FetchQueueEntry` decode lanes; the backend remains the sole owner of
decode, rename, execution, and commit.

## Fixed Geometry

| Item | Value |
|---|---:|
| AXI fetch packet | 1--4 32-bit words |
| frontend fetch width | 4 |
| decode output width | 2 |
| queue depth | 4 entries |
| direction provider | 512-entry four-bank bimodal Base |
| target provider | 64-entry two-way BTB plus 8-entry RAS |

M3 replaces only the temporary AXI transport with the formal L1I/cache
scheduler. It must preserve the packet, prediction metadata, redirect, drain,
and precise fetch-fault behavior at this boundary.

## Interfaces

| Interface | Direction | Contract |
|---|---|---|
| `enable`, `ar`, `r` | fetch input / AXI | Starts a transport request when the predictors and indirect barrier permit it. |
| `decode[2]` | output | Ordered FetchDecodeQueue entries; lane 1 is never valid without lane 0. |
| `branchTraining` | input | Commit-only BDB training record for bimodal and BTB. |
| `executeRecovery` | input | Mispredict recovery history, RAS checkpoint/action, and actual redirect target. |
| `commitRedirect` | input | Precise trap, interrupt, MRET, or FENCE.I redirect. |
| status outputs | output | Current PC, AXI busy/drain state, predictor readiness, indirect barrier, and queue occupancy. |

Every enqueue entry carries the original instruction/fetch fault, M-mode
privilege, exact PC, history/RAS checkpoint, predictor provider metadata, and
predicted direction/target. The frontend does not re-decode instructions to
reconstruct this information.

## Packet Acceptance And Prediction

`FetchControlPrediction` scans slots in program order. The earliest taken
conditional, JAL, or target-resolved JALR selects the accepted prefix and
next-PC. Later slots are not queued or allowed to update speculative history
or RAS. A not-taken conditional does not stop the scan.

The AXI packet is held until both predictors are ready, the queue has room for
the complete accepted prefix, and a required RAS action is admissible. The
queue, speculative-history advance, and RAS action all fire on this same
packet-accept edge. No partial fetch bundle can enter the queue.

Conditional direction uses the bimodal Base prediction. Direct targets use
predecode, and JALR targets use RAS for returns then BTB. `branchTraining` is
the only predictor mutation input, so wrong-path branches never train either
table. Predictor training stalls acceptance for the documented single-port
cycle.

## Redirect, Drain, And Barrier Rules

Redirect priority is exact: `commitRedirect` has priority over
`executeRecovery`; both override the predicted next PC. An external redirect
flushes the FetchDecodeQueue, clears history/RAS for a commit redirect or
restores the BDB checkpoint/action for execute recovery, and is passed to the
AXI transport. A transaction accepted before redirect is therefore drained by
`AXIInstructionFetch`; an unconsumed response is discarded.

A JALR without an RAS or BTB target is queued as the last accepted prefix entry
with `predictedTaken=false`. The frontend sets an indirect barrier immediately
after that packet fires and issues no further AR until execute recovery or a
commit redirect supplies the architectural target. This prevents speculative
execution beyond an unresolved indirect branch.

`FENCE.I` is a commit redirect. It clears history/RAS and initiates BTB
invalidation; the external system-serialization input must already have
drained older stores and device work before the backend emits this event.

## Invariants And Verification Mapping

- The accepted mask is non-empty and no longer than the AXI packet.
- Queue enqueue, history advance, and RAS speculation are one atomic packet
  event; a higher-priority redirect prevents all three.
- `commitRedirect` wins over execute recovery, and no AR is issued while an
  indirect barrier is active.
- Bimodal/BTB mutation is driven only by commit training; FENCE.I never races
  with training.
- Fetch queue fault data is copied directly from the AXI packet and remains in
  program order through decode.

`M1FrontendSpec` verifies packet-to-decode transfer, earliest-control target
selection, targetless-JALR blocking and recovery, commit-training prediction,
and commit-over-recovery redirect priority while draining an accepted AXI
burst. `AXIInstructionFetchSpec` owns the transport's lower-level boundary,
backpressure, RRESP, ID, and RLAST cases.
