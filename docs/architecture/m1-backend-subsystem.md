# M1 Backend Subsystem

`M1BackendSubsystem` closes the architectural loop for the implemented M1
subset: RV32I integer/control execution, Zicsr, precise synchronous traps,
M-mode CSR state, branch recovery, rename commit, and dual retirement. It
composes `IntegerDispatchRecoveryBackend` with `CommitCSRSubsystem` and is the
first backend boundary in which decoded instructions can execute and make
architectural state visible without an external commit test driver.

The module is not the final core. Fetch/I-Cache, both LSU pipelines, FPR/FPU,
frontend redirect arbitration, WFI sleep control, and retire-trace formatting remain
outside this boundary. M2 `ZirconCore` connects its exported `longEnqueue`,
`otherCompletion[0]`, `squash`, PRF-ready, and auxiliary-read interfaces to E2;
LongPipe remains outside this wrapper so M3/M4 endpoints use the same boundary.

## Parameters and interfaces

All widths come from `ZirconCoreConfig`: two decode lanes, two commit lanes, 24
ROB entries, 56 integer physical registers, 12 IntIQ entries, and eight BDB
entries. The main interfaces are:

| Interface | Direction | Contract |
|---|---|---|
| `input[2]` | input | In-order fetch-queue entries; lane 1 cannot be valid alone |
| `longEnqueue/memEnqueue` | output | Compact uops for milestone-external E2 and memory queues |
| `otherCompletion/otherFault` | input | E2, M0, and M1 result/fault placeholders |
| `interrupts/interruptBlocked` | input | M-mode interrupt levels and irrevocable-transaction gate; EPC comes only from the live ROB head |
| `systemSerializingReady` | input | Old stores/device operations and instruction-side invalidation have completed |
| `retired/trapEntry/trapLane/redirect/globalFlush` | output | Architectural retirement, exact trap metadata, and commit-stage control transfer |
| `frontendRecovery/branchTraining` | output | Execute-stage mispredict recovery and commit-stage predictor training |
| maps, PRF auxiliary read, occupancy | debug/performance | Directed-test observation and future performance counter sources |

`fpCommit` preserves the frozen CSR integration contract, but no F instruction
can be dispatched until M4.

## Dataflow and state ownership

An accepted instruction atomically allocates rename, ROB, IQ, and optional BDB
state. Integer and branch uops pass through IntIQ, ROB context lookup, E0/E1,
the two completion ports, and ROB completion. The commit subsystem consumes the
completed ROB head, FirstFault record, and a tagged E0 side effect; its outputs
update the committed rename map and CSR file on the same retirement edge.

CSR state is never speculatively modified. A CSR/System uop may enter E0 only
when its `robTag` equals the current ROB head. E0 reads the CSR file
combinationally, performs Zicsr read/modify/write semantics, and sends the old
value through the ordinary integer completion path. A single register retains:

- 6-bit ROB tag;
- CSR-write valid, 12-bit address, and 32-bit data;
- one bit distinguishing System serialization from an ordinary CSR operation.

Including its valid bit, this is 53 bits of persistent state. It replaces a
per-ROB-entry CSR result payload. The slot is cleared only by matching
retirement, selective squash of a younger owner, or global flush.

## System operations and exceptions

- `ECALL` produces cause 11 and `tval=0`.
- `EBREAK` produces cause 3 and `tval=0`.
- An illegal CSR address or access produces cause 2 and uses the original
  instruction as `tval`; the faulting result cannot write the integer PRF.
- `MRET`, `WFI`, `FENCE`, and `FENCE.I` complete normally, then wait for
  `systemSerializingReady` before isolated lane-0 retirement.
- `MRET` and `FENCE.I` produce commit-stage redirects and global flush;
  `FENCE` does not redirect.

`FENCE.I` readiness is intentionally one aggregate signal at this boundary.
The M3 core derives it from an exact live-head FENCE tag and the LSQ's
wrap-aware older-owner query, so younger speculative LQ/SQ entries cannot
deadlock retirement. `FENCE.I` invalidates frontend cache/BTB state with its
commit redirect. General dirty-cache writeback and external-coherency FENCE
semantics remain outside this partial M3 slice.

## Commit, rollback, and flush ordering

A completed ROB head remains visible to commit even when that commit decision
generates global flush. A new branch rollback request does not suppress this
visibility: if an older instruction retires on the same edge, ROB backpressures
the rollback request and the recovery controller retains it for the next
cycle. An active tail walk still blocks commit.

FirstFault output is register-only. A selective squash removes a younger fault
on the squash edge rather than through a combinational output filter. This is
safe because the older resolving branch is incomplete in the launch cycle, so
the wrong-path fault cannot match a committable ROB head. These rules keep the
BDB-commit, branch-resolution, fault, and commit networks combinationally
acyclic.

Commit-stage redirect has priority over execute-stage recovery. During a
global flush the backend emits neither a branch recovery nor new dispatch, and
the integration asserts that the two redirect sources are mutually exclusive.

## Invariants and verification mapping

- A CSR/System uop cannot enter E0 away from the ROB head.
- A serialized retirement must match the one tagged E0 side-effect slot.
- A faulting CSR may complete the ROB entry but cannot write the PRF or CSR.
- Side-effect slot retirement tag, ROB retirement tag, and lane-0 serialized
  instruction must agree.
- ROB cannot accept rollback and retirement on the same edge.
- Global flush and execute-stage branch recovery cannot escape together.

Directed integration tests cover a dependent `ADDI -> CSRRW` sequence followed
by a committed CSR read, precise M-mode `ECALL`, FENCE serialization
backpressure, and enabled software-interrupt entry followed by MRET. The
AXI-driven `CoreShellSpec` extends that coverage through branch recovery,
direct/indirect jumps, programmed trap handlers, FENCE.I, channel backpressure,
and blocked unimplemented LSU traffic. Lower-level tests cover illegal CSR
access, side-effect retention, head-only System admission, branch misalignment,
selective squash, completion backpressure, and ROB rollback. M1 release still
requires executable ELF/device models plus ACT4 and Spike/Sail differential
testing.
