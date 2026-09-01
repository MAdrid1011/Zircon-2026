# ADR-0014: L2 demand-read AXI ownership

Status: Accepted

Related Issue: #47

## Context

The first executable M3 load slice attached the four physical AXI refill IDs
directly to the four local L1D MSHRs. That wiring preserved exact D-side refill
data, but it made an AXI read owner indistinguishable from an L1D waiter/way
reservation. It cannot become the frozen four-entry L2 demand-MSHR boundary:
an L2 hit already transfers a line directly to L1D, while a future L1I miss
must share the same L2 demand credits without claiming an L1D-local slot.

## Decision

`AXIDataReadEngine` is the executable L2 demand-read owner. It allocates one
of the four physical L2 MSHRs when it accepts an `L2DemandRequest`, retains the
requesting client kind and client-local MSHR token, and maps the allocated slot
to AXI IDs 1 through 4 only when AR handshakes. R beats are owned by that L2
slot through the expected eighth beat and are then returned as an
`L2DemandResponse` carrying the original client token.

L1D continues to own its four local way reservations, store-pending state, and
load waiters. After an L2 lookup miss it sends an L2 demand request tagged with
its local MSHR. An L2 hit continues to move the sole D-side line through the
exclusive transfer buffer and never allocates an AXI demand slot. The present
integration admits only `Data` demand requests; the retained `Instruction`
client field and token are the future L1I input contract, not evidence that
L1I is implemented.

Accepted AR requests remain non-cancellable. Recovery may suppress L1D
completion as before, but a live L2 owner retains its ID, beat counter, line
buffer, response status, client metadata, and credit until the final R beat is
drained. The data response buffer then retains the complete line independently
of the freed physical AXI owner.

## Alternatives considered

- Keep L1D MSHR number as AXI ID: rejected because it prevents one L2 credit
  pool from serving future I and D demand independently of local cache state.
- Allocate a duplicate L2 MSHR next to the existing data owner: rejected
  because it duplicates credits and creates ambiguous response ownership.
- Wait for L1I before changing ownership: rejected because it leaves the
  required L2 MSHR contract untestable and ties later integration to a local
  L1D implementation detail.

## Consequences

- IDs 1-4 identify L2 demand owners, not L1D MSHR indices. An AXI response is
  mapped back to its exact client only after L2 ownership validation.
- L2 demand tests must cover client-token preservation, four physical owners,
  interleaved IDs, RRESP, RLAST, AR backpressure, and 4 KiB legality.
- This change does not implement an L2 data refill array, L1I, or dynamic
  I/D request admission. Those remain explicit M3 work and must not be
  represented as complete in status or area reports.
