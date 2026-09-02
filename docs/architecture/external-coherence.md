# External Coherence Boundary

The core has no AXI snoop slave. Multi-master cacheable memory therefore uses
the explicit `ExternalCoherencePort` defined by ADR-0023, not observation of
unrelated AXI traffic. A platform adapter must submit one request before an
external cacheable write, LR/SC, or AMO, wait for the matching response, then
perform that external action.

## Request Lifecycle

`request` carries a 32-byte-aligned line address and `WriteInvalidate` or
`AtomicInvalidate` kind. The controller retains one accepted request and blocks
new cacheable ingress. It first lets already accepted local AXI/L2 owners drain
using their original IDs and beat counts. If the line is dirty in L1D or L2, it
moves the sole D owner through the existing L2 victim and ID-5 writeback path.

Before `response` may handshake, the target line is absent from L1I, L1D, and
L2; no dirty victim or accepted data transaction for it remains. The controller
also drives `AtomicMemoryEngine.invalidate` for the line, clearing a matching
word LR reservation. ID-5 errors retry in place and suppress the response.

The adapter must not issue the external modifier before response handshake. It
must retry an unacknowledged request after reset. The port is not a shared-cache
read protocol and supplies no implicit coherent external reads.

## Controller Foundation

`ExternalCoherenceController` now implements the retained one-request
lifecycle as an independently tested M3 component. It blocks cacheable ingress
from request acceptance through response, requests exact-line L1D then L2
cleanup, waits for the matching ID-5 completion only when the L2 cleanup
reported a dirty victim, invalidates I-side state and the line-scoped LR
reservation, and finally presents the original `{kind, lineAddress}` response.
The component accepts only 32-byte-aligned `WriteInvalidate` and
`AtomicInvalidate` requests and does not accept a replacement request while
the retained request is active.

This is not yet an externally coherent core. `ZirconCore` has not yet exposed
the port or connected its existing L1D/L2/atomic endpoints to the controller.
The L1D and L2 cleanup endpoints now acknowledge exact dirty, clean, and
absent targets: dirty L1D cleanup transfers to L2, dirty L2 cleanup reports
its ID-5 obligation through `flushLineDirty`, and clean/absent targets create
no victim. Matching in-flight L1D owners still block cleanup. The current
local foundation checks are:

```bash
./scripts/sbtw 'testOnly zircon.ExternalCoherenceControllerSpec'
./scripts/sbtw 'testOnly zircon.L1DLoadCacheSpec zircon.ExclusiveL2TransferStoreSpec'
```

## Required Invariants

- One accepted coherence request has one exact response, except reset.
- An acknowledged line has no local I, D, L2, refill, or dirty-victim owner.
- A matching external write or atomic clears the LR reservation before it can
  complete locally.
- Accepted local AXI owners drain; cancellation never releases an AXI ID early.
- A sideband request neither creates a retirement record nor permits a younger
  cacheable request to bypass the controller.

## Verification Matrix

The eventual controller needs explicit tests for clean and dirty L1I/L1D/L2
copies, L1D/L2 transfer pressure, issued refill/writeback drain, B-error retry,
selective squash, global flush, matching/disjoint LR reservations, and reset.
Bounded formal properties must prove response uniqueness, owner exclusivity,
acknowledgement-after-invalidation, and AXI credit conservation. Integration
tests must drive a platform-side request and demonstrate that the external
modifier cannot execute before its response.
