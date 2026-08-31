# 顶层接口

`ZirconCoreIO` 是处理器与 SoC、仿真器和差分参考模型之间的稳定边界。当前实现已生成 AXI4 master、中断输入和可选提交跟踪端口；M0 shell 不产生总线请求或退休事件。

<!-- 图：ZirconCoreIO 信号方向 -->
<!-- ![ZirconCoreIO 信号方向](./assets/core-io.svg) -->

## AXI4 master

`AXI4MasterPort` 使用 32-bit 地址、32-bit 数据和 4-bit ID。AR/AW 包含 `id/addr/len/size/burst/lock/cache/prot/qos`；W 包含 `data/strb/last`；R/B 包含 ID 与 `resp`。处理器只生成 INCR burst，Cache line refill 使用 8 个 32-bit beat，ordered MMIO group 最多使用 4 beat。

valid 在握手前必须保持，ready 可独立回压。R channel 可在不同 ID 间交错，相同 ID 的 beat 保持顺序。M3 接入后，未知 ID、beat 数错误和 `last` 错误由仿真断言报告，`SLVERR/DECERR` 转换为归属指令的 access fault。

## 中断输入

`InterruptInputs` 包含 `meip/msip/mtip`。这些信号是电平输入；CSR/commit controller 在提交边界选择中断。M0 shell 只保留端口，不解释中断。

## RetireEvent

启用 `enableTrace` 时生成两个 `RetireEvent` lane。每项包含：

- `valid/order/pc/instruction/privilege`
- GPR/FPR 写地址和数据
- CSR 地址和写数据
- memory 地址、读写 mask 和数据
- `trap/interrupt/cause/trapValue/fflags`

`order` 是跨双退休 lane 单调递增的 64-bit 指令序号。综合配置不生成 trace 端口，避免验证状态进入面积结果。
