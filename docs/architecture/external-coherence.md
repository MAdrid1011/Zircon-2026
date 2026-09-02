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

`ZirconCore` now exposes the production port and connects the controller to
the L1I drain/invalidate path, L1D/L2 cleanup arbiters, ID-5 completion, and
line-scoped atomic reservation clearing. It blocks a request until a retained
I-side demand or lookahead owner drains, so an old instruction refill cannot
repopulate the target after acknowledgement. The L1D and L2 cleanup endpoints
acknowledge exact dirty, clean, and absent targets: dirty L1D cleanup transfers
to L2, dirty L2 cleanup reports its ID-5 obligation through `flushLineDirty`,
and clean/absent targets create no victim. Matching in-flight L1D owners still
block cleanup. ZirconSim drives the sideband explicitly idle because it remains
a single-hart/private-memory model. `ExternalCoherenceAdapter` is the
synthesizable one-request platform gate: it retains a modifier, sends the core
request, and only exposes `authorized` after the exact response handshake. A
`ZirconPlatformCore` instantiates that gate with the production `ZirconCore`
port and exposes AXI, interrupts, `modifier`, and `authorized` as one
no-observation integration boundary. A board/SoC wrapper must still connect
`authorized` to its real external master and to verified board pins. The
current local checks are:

```bash
./scripts/sbtw 'testOnly zircon.ExternalCoherenceControllerSpec'
./scripts/sbtw 'testOnly zircon.ExternalCoherenceAdapterSpec'
./scripts/sbtw 'testOnly zircon.L1DLoadCacheSpec zircon.ExclusiveL2TransferStoreSpec'
./scripts/sbtw 'testOnly zircon.AtomicMemoryEngineSpec'
./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "external cacheable invalidation"'
./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "dirty external-coherence writeback"'
./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "failing coherence writeback"'
./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "drops a dirty coherence writeback"'
./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves line-scoped LR reservations across seeded external coherence"'
make test-m3-external-coherence
make platform-verilog
make -C ZirconSim tohost-rv32a
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
selective squash, global flush, and reset. Matching/disjoint LR reservations
now have complete-core coverage under three explicit AXI-backpressure seeds per
case: after a retired `LR.W`, a matching write-invalidate makes `SC.W` return
failure without an ID-7 write; a disjoint atomic-invalidate retains the
reservation and permits exactly one ID-7 write. Both cases require the write
to remain behind the exact sideband acknowledgement.
Bounded formal properties must prove response uniqueness, owner exclusivity,
acknowledgement-after-invalidation, and AXI credit conservation. The adapter
unit test demonstrates that the external modifier cannot execute before its
response and rejects a core acknowledgement with a different kind or line.
`ExternalCoherenceControllerSpec` additionally holds a response under
explicit backpressure while offering a second legal request, proving that the
first kind/line payload remains stable, cacheable ingress stays blocked, and
the replacement is accepted only after the original response fires.
The controller's dirty-cleanup reset case resets after L2 cleanup has created
the writeback dependency, presents a stale completion from that discarded
epoch, then completes a different clean request. The stale completion produces
no response or authorization; the fresh request performs its own cleanup.
`CoreShellSpec` applies the same rule at the production port: it holds an
atomic-invalidate acknowledgement for three cycles, checks the exact retained
payload every cycle, then permits one response and requires refetch through
`EBREAK`. A second top-level scenario first fills a clean L1D line, submits its
external write-invalidate request, then requires a later same-address load to
own a new AXI read rather than reuse the invalidated local line. Integration
still needs a concrete platform master, board wrapper, and full pressure matrix.
