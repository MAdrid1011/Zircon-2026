# ADR-0037: Reject the local age cutpoint experiment after synthesis review

状态：Rejected after fixed-device synthesis review

## 背景

ADR-0036 added optional registered ROB-age snapshots inside IntIQ, MemIQ,
LSQ ingress, and L1D. The intent was to cut the remaining ROB-head-to-LSU
forwarding route. Focused Chisel tests and RTL generation passed.

## 证据

On `xc7a200tfbg676-2L` with Vivado 2023.1, synthesis of commits `8e86f98`
and `ebb0de4` repeatedly terminated during `Cross Boundary and Area
Optimization` before producing a checkpoint or utilization report. Changing
the synthesis directive from `AreaOptimized_high` to `Default` did not change
the termination point. No timing or area improvement can therefore be
claimed.

## 决策

Remove the production local age registers and retain the last measurable
baseline (`c0eeb63`) with narrow ROB head-tag fanout, balanced FirstFault
selection, registered production wakeup, and the existing LSU operand
boundary. Future timing work must first demonstrate a complete synthesis
checkpoint before adding another broad replicated control boundary.
