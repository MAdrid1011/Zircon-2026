# ADR-0026: Vivado FPGA release evidence

状态：Accepted

关联 Issue：#48

## 背景

原始交接合同将 bounded formal 列为 M3 和最终 release 的强制门槛，但项目所有者已明确
最终物理目标是 `xc7a200tfbg676-2L` 上的 Vivado FPGA 实现。现有仓库已经保留 runtime
assertion、固定 seed 压力、Spike/Sail 差分、coverage 和 mutation 的验证路径；它们应继续
约束 RTL，但不应由缺失的 formal 工具链阻塞板级交付。

## 决策

最终 release gate 必须包含 Zircon-specific board wrapper、实际 AXI/coherence external
master、匹配的 XDC、Vivado post-route timing/utilization report，以及在
`xc7a200tfbg676-2L` 的 10.000 ns `clk` 约束下非负 setup WNS。板级程序运行、retire trace
和设备 AXI 行为必须与同一 RTL revision 的本地差分证据匹配。

bounded formal 与 riscv-formal/RVFI 可以在调试时使用，但不再是 M3 或 `v1.0.0` 的发布
阻塞项。每个协议变化仍必须保留可执行 assertion、显式 seed、失败 bundle、定向压力、
coverage 和 mutation 证据；不得以删除这些证据替代 formal。

## 后果

- 验证计划和 release checklist 以 Vivado FPGA evidence 为物理最终门槛。
- `xc7a200tfbg676-2L` 以外的报告、XDC 或板卡不能作为替代证据。
- Vivado 结果不能掩盖 ISA、异常精确性、双 LSU、Cache、MMIO、FPU 或差分失败。
