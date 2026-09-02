# 顶层接口

`ZirconCoreIO` 是处理器与 SoC、仿真器和差分参考模型之间的稳定边界。当前 M1 实现生成 AXI4 master、中断输入和可选提交跟踪端口；AXI read channel 已可驱动 directed RV32I retire trace，write channel 仍等待 M3 LSU/MMIO。

<!-- 图：ZirconCoreIO 信号方向 -->
<!-- ![ZirconCoreIO 信号方向](./assets/core-io.svg) -->

## AXI4 master

`AXI4MasterPort` 使用 32-bit 地址、32-bit 数据和 4-bit ID。AR/AW 包含 `id/addr/len/size/burst/lock/cache/prot/qos`；W 包含 `data/strb/last`；R/B 包含 ID 与 `resp`。处理器只生成 INCR burst，Cache line refill 使用 8 个 32-bit beat，ordered MMIO group 最多使用 4 beat。

valid 在握手前必须保持，ready 可独立回压。R channel 可在不同 ID 间交错，相同 ID 的 beat 保持顺序。M3 接入后，未知 ID、beat 数错误和 `last` 错误由仿真断言报告，`SLVERR/DECERR` 转换为归属指令的 access fault。

## 中断输入

`InterruptInputs` 包含 `meip/msip/mtip`。这些信号是电平输入；CSR/commit controller 在提交边界选择中断。M0 shell 只保留端口，不解释中断。

## ExternalCoherencePort

`externalCoherence` 是 production `ZirconCoreIO` 的一请求 sideband，不是 AXI
snoop slave。平台在外部 cacheable store、LR/SC 或 AMO 前提交
`request { kind, lineAddress }`，其中 `lineAddress` 必须 32-byte 对齐，`kind`
为 `WriteInvalidate` 或 `AtomicInvalidate`。平台只能在同一 payload 的
`response` 握手后发出外部 modifier。

接收请求会封锁新的 cacheable I/D/store/atomic ingress，等待已接受的 I-side
demand/lookahead owner drain，清理目标 L1D 和 L2 line。dirty L2 cleanup 仅在目标
ID-5 successful B 后继续；随后 invalidates L1I/BTB 并清除同一 cache line 内所有
LR reservation word，最后返回 response。clean 或 absent target 不产生虚假
writeback。ZirconSim 作为 single-hart/private-memory 平台将该端口显式保持 idle；
FPGA/SoC adapter 负责在真正的外部 cacheable modifier 前驱动该协议。

## RetireEvent

启用 `enableTrace` 时生成两个 `RetireEvent` lane。每项包含：

- `valid/order/pc/instruction/privilege`
- GPR/FPR 写地址和数据
- CSR 地址和写数据
- memory 地址、读写 mask 和数据
- `trap/interrupt/cause/trapValue/fflags`

`order` 是跨双退休 lane 单调递增的 64-bit 指令序号。事件直接使用 retired ROB entry 或 trap 的真实 faulting/interrupted ROB entry；lane-1 exception 保留 older lane-0 retirement 后的 trap 顺序。综合配置不生成 trace 端口、formatter 或 order counter，避免验证状态进入面积结果。

## Trace host flush

仅当 `enableTrace=true` 且 `enableHostFlush=true` 时，`ZirconCoreIO` 额外生成输入
`hostFlush { enable, address }`。它允许 ZirconSim 在解析 ELF `tohost` 符号后选择一个
精确地址；命中的 committed cacheable store 必须等其 dirty line 经 ID-5 成功 B response
外部可见才退休。默认综合配置没有该端口、控制器或任何相关状态，因此它不进入生产面积或
ISA 行为。完整协议见 [`trace-host-flush.md`](trace-host-flush.md)。
