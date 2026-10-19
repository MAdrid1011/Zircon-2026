# VectorMemory implementation

`VectorMemory.scala` owns vector Load/Store collection and ordered element
address generation under [Backend](../../../../../docs/Backend.md) and
[Memory](../../../../../docs/Memory.md). It accepts one vector instruction,
collects scalar/vector operands, and shares the vector RF and DCache line
interface. Store data enters the ordered SQ path before completion. A canceled
Load retains ownership of an accepted request until its response returns.
Flush cancels instruction work before normal state updates.

At the existing `collect` edge, `groupOffset` captures the low 32 bits of
`withinField * groupStep`. Three masked, shifted partial products are reduced
with a carry-save stage and the existing `BLevelPAdder32`; no phase or state is
added. Per-element increments in gather, store, and merge use the same existing
32-bit grouped adder structure. Every address operation retains modulo-2^32
semantics, including arbitrary strides and overflow.

`VectorMemorySpec` checks masking, tail/start policies, indexed/strided/segmented
accesses, cache requests, PRF writes and cancellation. Module equivalence compares
all outputs and next-state points with unchanged register boundaries; whole-core
functional and timing checks are defined in
[TSMC28Timing](../../../../../docs/TSMC28Timing.md).
