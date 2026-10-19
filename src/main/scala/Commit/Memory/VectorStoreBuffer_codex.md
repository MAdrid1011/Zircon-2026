# VectorStoreBuffer implementation

The wide store side buffer retains vector data across retirement while the
ordinary SQ/SB token owns program order. Governing design:
[Memory](../../../../../docs/Memory.md) and
[PerformanceCore](../../../../../docs/PerformanceCore.md).

Collection fills eight payload groups and a 256-entry element-address memory.
Commit marks the completed collection irrevocable. A drain token starts the
`idle` → `readElementAddress` (scattered only) → `prepare` → `response` loop.
The `done` cycle returns ownership. Flush discards only uncommitted data;
same-cycle matching commit wins over flush. Requests hold until accepted and
only an accepted response advances group/element/second-fragment state.

Contiguous successor lines are prepared on collection. Scattered successor
lines are prepared alongside `elementAddress` at the existing element-read
boundary, with 26-bit wrap matching the original 32-bit aligned increment.
`second` selects two registered line candidates, removing a late conditional
adder without adding a state or changing address/data/mask pairing. Adjacent
contiguous groups can share a cache-line request. Ordered scattered stores
visit fields within an element before advancing the element.

Assertions check collection order, filled-before-commit, ownership, group
one-hot, and exception-free committed stores. `VectorStoreBufferSpec` and
`VectorStoreCoalesceSpec` check masks, crossing, ordered aliases, backpressure,
and recovery. TSMC28 logic timing is tracked in
[TSMC28Timing](../../../../../docs/TSMC28Timing.md); SRAM and physical closure
require separate evidence.
