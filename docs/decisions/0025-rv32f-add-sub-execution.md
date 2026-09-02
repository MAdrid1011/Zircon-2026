# ADR-0025: Precise RV32F add/sub execution

Status: Accepted

Related Issue: #49

## Context

The executable M4 path already carries two FPR operands through the tagged
scoreboard, retains a completed result until its matching ROB commit, and
accumulates `fflags` only at that commit. `FADD.S` and `FSUB.S` remain illegal,
despite their decoder metadata and operand read path already being available.
They must not be made live by a host floating-point shortcut or a completion
that bypasses `FloatingResultBridge`: host behavior is not a synthesizable
architecture contract and a direct write would violate precise recovery.

## Decision

`FloatingMovePipe` is the sole first arithmetic endpoint. It receives the
frozen effective rounding mode and two captured IEEE-754 single bit patterns.
`FSUB.S` inverts the second sign before applying the common add/sub datapath.
The datapath unpacks operands, classifies NaN/infinity/zero/subnormal, aligns a
27-bit `{hidden,fraction,guard,round,sticky}` significand, performs
sign-magnitude add/subtract, normalizes, rounds, and packs one single result.

Every NaN result is the canonical quiet NaN `0x7fc00000`. A signaling NaN, or
addition of opposite-sign infinities, raises `NV` and returns that canonical
NaN. Same-sign infinity returns its infinity. Exact zero from cancellation is
`-0` only under RDN; other exact-cancellation and unlike-signed-zero results
are `+0`; equal signed zeros retain their common sign.

RNE, RTZ, RDN, RUP, and RMM use the frozen `roundingMode`. Overflow raises
`OF|NX` and yields infinity unless the selected directed mode requires the
largest finite value. Underflow is detected after rounding: a tiny final
subnormal or zero result raises `UF` only when inexact, and every discarded
nonzero bit raises `NX`. Flags use the existing bit order `{NV,DZ,OF,UF,NX}`.

An arithmetic result remains a normal FPR-writing `FloatingMoveResult`; it
must enter `FloatingResultBridge` and `FloatingResultQueue` before the ROB can
complete the tag. Squash and global flush discard the retained result exactly
as they do for the conversion slice. No new core IO, FPR ports, or bypass path
is permitted.

## Alternatives considered

- Use JVM/host IEEE arithmetic in Chisel: rejected because it is not
  synthesizable RTL and cannot establish bit-exact architectural rounding.
- Add a separate FPU result/commit path: rejected because it duplicates the
  tagged result ownership protocol and risks speculative FPR/flag mutation.
- Admit FADD/FSUB before special-case and five-mode coverage: rejected because
  illegal instruction behavior is preferable to an incomplete completion.

## Consequences

- `FloatingAdmission` can admit only FADD/FSUB after the common datapath and
  all specified edge conditions have tests.
- The static ledger must charge alignment selection, significand arithmetic,
  normalization, rounding, and special-value comparison logic; it remains
  partial until all M4 endpoints are inventoried.
- Component tests cover all five rounding modes, normal/subnormal boundaries,
  zeros, NaNs, infinities, overflow/underflow, and recovery. AXI-fed core
  tests cover dynamic `frm`, precise `fflags`, FPR dependency, and killed work.
