# M4 executable floating-point path

This specification defines the transition from the metadata-only RV32F
decoder to an executable M4 path. It preserves the frozen single-hart
`RV32IMAF_Zicsr_Zifencei` contract: FPRs are architectural, not renamed;
all state changes occur at ROB commit; and E2 remains the sole long/F
execution endpoint.

## Incremental admission

The first executable slice admits the non-rounding operations `FMV.W.X`,
`FMV.X.W`, `FSGNJ.S`, `FSGNJN.S`, `FSGNJX.S`, `FMIN.S`, `FMAX.S`, `FEQ.S`,
`FLT.S`, `FLE.S`, and `FCLASS.S`. It exercises both register namespaces
without claiming arithmetic, conversion, or load/store support. `FMIN.S` and
`FMAX.S` implement one-NaN/both-NaN selection, canonical NaN, and signed-zero
rules; signaling NaN raises `fflags.NV`. `FEQ.S` raises NV only for signaling
NaN, whereas `FLT.S` and `FLE.S` raise NV for every NaN. `FCLASS.S` never
raises an exception. Every other F encoding remains an illegal-instruction
exception until its complete execution and commit path is implemented.

All admitted F operations are illegal when `mstatus.FS=Off`. A legal F
operation that writes an FPR, or changes `fflags`, makes `FS=Dirty` only at
its successful ROB commit. `FMV.X.W` and `FCLASS.S` do not change `FS` or
`fflags`; a finite comparison leaves them unchanged as well.

## Dispatch and issue contract

`RV32FMetadataDecoder` is consulted alongside the integer decoder. Dispatch
constructs exactly one canonical decoded record, carrying the F operation,
up to three architectural FPR source names, the optional FPR destination, and
the integer source or destination when applicable. The ROB retains this record
and the compact E2 issue reference retains only tags, source names/readiness,
and destination metadata.

Before a live F instruction enters E2, `FloatingScoreboard` must atomically
reserve each FPR source and its optional FPR destination. It rejects RAW, WAR,
and WAW hazards and has a hard four-live-operation limit. The scoreboard sees
the same squash boundary and global flush as the ROB. A killed reservation is
removed; an older survivor is retained. A non-F destination remains under the
existing integer rename/PRF protocol.

E2 may start at most one F operation in a cycle and still participates in the
global three-start limit. The first slice reads at most two FPRs and at most
one integer PRF source. FMA later consumes its third FPR source over the frozen
two source-read cycles; it must not silently use a third combinational FPR
port. The FPR operand-consumption event releases scoreboard read reservations
only after all required operand values are captured by E2.

## Completion and precise commit

E2 reports every live F operation to the existing ROB completion fabric using
its true ROB tag. An operation with a GPR result uses the normal accepted
completion/rename/PRF wakeup path. An operation with an FPR result additionally
enqueues one `{robTag, fprAddress, fprData, flags}` record in
`FloatingResultQueue`; it cannot declare the ROB entry complete unless that
retained record has accepted ownership. Thus a later squash can discard both
records, while an older pending F result cannot be overwritten by a younger
one.

`FloatingResultBridge` is the ownership boundary for this rule. For an
FPR-writing result, or an integer-writing result carrying nonzero exception
flags, it first holds the upstream E2 handshake until the result queue accepts
the tag, then retains a one-entry ordinary completion until the ROB accepts it.
This eliminates a ROB-completion/result-queue combinational loop while ensuring
that a completed entry with floating architectural state already has its exact
result record. The bridge receives the same squash/flush boundary as the result
queue and drops its pending completion for the same killed tag. Integer-only,
flag-free operations such as `FMV.X.W` and `FCLASS.S` use the ordinary
completion path alone.

At retirement, at most one FPR-writing instruction can commit in a cycle,
matching the single FPR write port. The commit controller must hold a younger
second FPR writer for a later cycle rather than retire it without a matching
FPR write. The only matching result-queue tag may fire `FloatingCommitState`.
That one fire simultaneously writes the FPR, clears the scoreboard destination
reservation, updates `fflags`, and raises the CSR `fpCommit` event. Trap,
interrupt, selective squash, and global flush prohibit this fire. No F result
may update an FPR or `fflags` merely because it completed E2.

`FMV.X.W` has no FPR result record, but its FPR source reservation is released
when E2 captures the source and its GPR result uses the ordinary completion
path. The move/sign/class slice always reports zero flags; min/max and compare
may report NV through the retained-result protocol. Later arithmetic,
conversion, divide, sqrt, and FMA must use the same retained-result protocol
and may not add a direct FPR bypass around it.

## Trace, recovery, and memory boundaries

The trace formatter receives the actual FPR commit tag, address, and data from
`FloatingCommitState`. It places those fields only in the matching retired
event; a trap event and a non-F retirement retain zero FPR fields. The event's
`fflags` is the architectural value associated with that commit boundary, not
a value reconstructed from an instruction word or from E2 data.

`FLW` and `FSW` remain non-live until the M0/M1 LSU carries FPR destination or
store-data metadata through its response-gated ownership and precise memory
retirement path. The first slice creates no AXI request, Cache request, or
memory completion for either encoding.

## Required invariants

- Every legal live F instruction selects E2; no integer or memory queue may
  accept it as a fallback.
- FPR state, `fflags`, and `FS=Dirty` change only from a matching commit fire.
- E2 cannot produce a visible completion, FPR write, or trace FPR metadata for
  a squashed or flushed tag.
- A result-queue entry, scoreboard reservation, and ROB entry share the same
  tag; duplicate live tags are assertions.
- The single FPR write port never accepts two writers in one retirement cycle.
- Unsupported F operations, `FS=Off` F operations, and reserved F encodings
  take the existing precise illegal-instruction path.

## Verification mapping

The initial integration tests must run AXI-fed instructions through
`ZirconCore`, not merely instantiate component state. They cover:

- `FS=Off` illegal traps for each admitted encoding, with exact `mepc` and
  `mtval` and no FPR/GPR side effect;
- enabling FS through committed `mstatus`, then `FMV.W.X` followed by
  `FMV.X.W`, including exact GPR/FPR retire metadata and `FS=Dirty`;
- all three `FSGNJ` forms with sign-distinct operands, min/max NaN and signed
  zero cases, comparison NaN/NV behavior, and all ten `FCLASS.S` categories;
- FPR RAW, WAR, WAW, two-wide dispatch, result-queue backpressure, and the
  single-write commit gate;
- branch squash, trap/interrupt flush, and delayed E2 completion without an
  FPR update, `fflags` update, or trace record for the killed tag; and
- explicit-seed top-level AXI backpressure while F instructions coexist with
  RV32I/M and M3 memory traffic.

The focused component target must remain below five minutes and record its
explicit seeds and failure artifacts. TestFloat, `FLW`/`FSW`, arithmetic,
rounding, NaN, subnormal, FMA, division, and conversion coverage remain M4
release gates after their full execution paths are implemented.
