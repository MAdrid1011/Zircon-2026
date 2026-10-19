# LoadPipeline implementation

`LoadPipeline.scala` implements LS0 and the load/address side of LS1 described
in [Backend](../../../../../docs/Backend.md). `withStore` enables the LS1
store-address/data interfaces; LS0 accepts only loads. Dispatch and each typed
IssueQueue own FU routing. Acceptance asserts the FU/store contract; `ready`
depends on local capacity, issue blocking, and RF turnover rather than a
post-selection FU decode. LS0 store predicates are elaboration-time false.

Issue reserves a free context, captures the package and SQ-tail one-hot, and
enters RF/AGU. The DCache owns D1/D2/WB and returns a context slot. A response or
flush clears a context, with same-cycle new issue taking priority for reuse.
The held-RF address stays stable under cache backpressure. LS1 separately
buffers translated store addresses and their permission context.

D1 speculative wakeup captures tag/mask with zero invalid payload; later
resolution confirms or fails tokens. Completions, PRF writes, and forwarding
retain their original cycles. No interface or pipeline stage is added by the
TSMC28 capacity-path optimization.

Verification: `LoadPipelineSpec`, `DualLoadPipelineSpec`, whole-core CoreMark
and FP workloads, and Dandelion GEMM. Timing evidence is tracked in
[TSMC28Timing](../../../../../docs/TSMC28Timing.md); capacity-path simplification
does not establish SRAM or physical timing closure.
