# Floating-point register file

`FloatingRegisterFile` is the M4 architectural FPR state. It contains 32
independent 32-bit registers, with two combinational reads and one write port.
Unlike the integer PRF, FPRs are architectural names: `f0` is writable and no
rename map or speculative version exists.

## Interface and timing

| Signal | Direction | Meaning |
|---|---|---|
| `readAddress[2]` | input | Architectural `f0`--`f31` source numbers |
| `readData[2]` | output | Combinational source values |
| `write` | input | One commit-qualified architectural result |

A read of the active write address returns write data combinationally. The
write is committed on the following clock edge, so dependent F operations may
use the same-cycle result only through their M4 scoreboard/admission path.

## Commit and recovery rule

The FPU result queue, not an execute stage, will drive `write`. A write is
asserted only when the matching ROB entry is at commit and its result is live.
Selective squash, global flush, traps, and interrupts therefore cannot mutate
the FPR array. The eventual result queue also supplies the same commit event to
`MachineCSRFile.fpCommit` for `fflags` accumulation.

The module deliberately has no F decode or execute connection yet. Until the
scoreboard, result queue, FPU endpoint, and commit plumbing exist, all F
instruction encodings remain illegal and cannot create a placeholder
completion.

## Verification mapping

`FloatingRegisterFileSpec` verifies reset state, independent reads, writable
`f0`, `f31`, write-first forwarding, and retained data after the write port is
idle. Later M4 integration tests must cover scoreboard RAW/WAR/WAW blocking,
two-cycle FMA source acquisition, result-queue backpressure, precise squash,
and retire-trace FPR metadata.
