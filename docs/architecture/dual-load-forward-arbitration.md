# M3 Dual Load-Forward Arbitration

`DualLoadForwardArbiter` is the retained one-request boundary from ADR-0020.
It remains unit-tested to preserve the LQ ready/valid ownership contract, but
ADR-0021 removes it from the active `ZirconCore` path: both LQ forward records
now enter `L1DLoadCache` directly. The active L1D accepts different-bank hit
pairs and retains exact results; its miss-resource policy remains incomplete.

| Interface | Rule |
| --- | --- |
| `in[0:1]` | Two `Decoupled[LoadStoreForward]` records from the LQ; each holds its exact ROB tag, address, byte-forward mask/data, and cacheability while unaccepted. |
| `out` | One existing L1D request. The selected input handshakes only when this port does. |
| `robHeadTag` | Defines modulo-24 age; the older live load wins. |
| `squash` / `flush` | Suppress grants and clear a backpressure selection lock. |

The arbiter chooses the oldest valid cacheable candidate. If L1D is not ready,
it latches the selected lane and keeps `out.bits` stable until handshake. The
other LQ record remains valid without losing its update/forward ownership.
Normal `Memory`-PMA loads from M0 and M1 both take this path; `m1Owner` is not
used for selection and still routes the later completion. Device and atomic
forwards are acknowledged by their existing ordered M0 paths and never enter
L1D.

This module does not create a second L1D read, MSHR allocation, or completion
port. It remains a verified deterministic reference for stalled one-request
consumers, asserting unique candidate tags, stable locked ownership, and no
grant during recovery. It is not instantiated by the production top level.
