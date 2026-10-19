# LoadPipelineSystem verification fixture

Wraps the production load pipeline, integer/FP PRFs, and shared DCache for
`LoadPipelineSpec` and `LoadStorePipelineSpec`, following
[Backend](../../../../../docs/Backend.md). `other` exposes the complete second
DCache load interface, including the output-only `widePending` status. The
fixture supplies a zero-data forwarding response on that port; the test owns
memory responses, store traffic, flush, issue, and PRF initialization.

No extra execution stage or readiness logic is inserted. Registers, Vivado, and
BSG RAM configurations exercise the same production wiring. The complete second-port Bundle preserves DCache integration checks.
