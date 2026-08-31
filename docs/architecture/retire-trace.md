# Retire Trace

`RetireTraceFormatter` is the simulation-only conversion from commit-boundary
metadata to `RetireEvent[2]`. It is instantiated only when
`ZirconCoreConfig.enableTrace` is true; the default synthesized core has no
trace port, formatter, or 64-bit order counter.

## Geometry And Pipeline

The formatter is frozen for the two-wide M1 commit geometry. It is a single
combinational formatting stage after commit decision, with one 64-bit `order`
register as its only state. It has no ready/valid feedback path into the ROB,
CSR file, AXI, or frontend: commit remains the sole owner of architectural
progress.

## Inputs And Ownership

| Input | Owner | Meaning |
|---|---|---|
| `retired[2]` | Commit controller | Actual normal retirement entries. |
| `gprData[2]` | Integer PRF | Committed physical-destination data. |
| `csrWrite` | Commit controller | The single committed M1 CSR write. |
| `trapCommit` | Commit controller | Precise interrupt/cause/tval event. |
| `trapEntry/trapLane` | ROB/commit | Real faulting entry or interrupted live head. |
| `currentFflags` | CSR file | Architecturally visible floating flags. |

The formatter never reconstructs PC, instruction, privilege, or trap metadata
from fetch state or an AXI response. Normal events read those fields from the
retired ROB entry. Trap events read them from `trapEntry`: synchronous faults
use the exact faulting entry, while interrupts use the current unretired ROB
head that supplied `mepc`.

## Ordering

`order` starts at zero and advances once per valid event. Ordinary dual
retirement produces orders `N` and `N+1`. A lane-1 exception produces the
older lane-0 retirement at `N` and the fault event at `N+1`; a lane-0 exception
or interrupt produces only the lane-0 trap event. A trap and a normal
retirement are prohibited in the same lane.

M1 emits GPR and CSR state when implemented. FPR and memory fields are zero
until M3/M4 provide their true commit metadata; no placeholder completion is
allowed to synthesize a trace event.

## Flush, Exception, And Observability

The formatter does not drain or replay transactions. A branch recovery that
removes younger entries cannot appear here because its entries never retire.
A commit flush can yield the current synchronous trap event, but no normal
event for the faulting entry. The order register advances only for events that
are valid at this boundary.

There is intentionally no hardware performance counter in the formatter: it
is absent from the synthesized configuration. Simulation can count valid
events from this stable interface; architectural retirement counters remain
owned by the CSR file.

## Invariants And Verification

- Trace lane 1 cannot be valid without lane 0.
- `trapLane` must name a valid two-wide lane, and cannot overlap a retirement.
- `trapEntry.pc == trapCommit.exceptionPc` for synchronous exceptions.
- Trace-disabled elaboration removes the trace interface and formatter state.

`RetireTraceFormatterSpec` covers normal dual retirement with GPR data,
lane-1 exception ordering, and an interrupt at the actual head entry.
`CoreShellSpec` adds AXI-fed RV32I and RRESP-error integration coverage.
