# M3 Dual Load-Forward Arbitration

`DualLoadForwardArbiter` sits between the two LQ forward records and the
current one-port `L1DLoadCache` request interface. It is the explicit M0/M1
cacheable-load ownership boundary from ADR-0020.

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
port. It is intentionally the verified deterministic backpressure stage before
the later true dual-bank L1D implementation. It asserts unique candidate tags,
stable locked ownership, and no grant during recovery.
