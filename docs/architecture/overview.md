# Zircon-2026 整体架构设计

Zircon-2026 的目标配置为 `RV32IMAF_Zicsr_Zifencei`、单 hart、M-mode。处理器由 4-wide frontend、2-wide rename/dispatch/commit backend、整数和长延迟执行端点、M0/M1 两条访存流水线、L1I/L1D、共享 L2、PMA、AXI4 master 和提交级验证接口组成。当前 M0 代码已冻结顶层接口、参数、PMA、ordered IO group 与 first-fault 状态；取指、执行、Cache 和提交数据通路将在后续里程碑接入同一接口。

<!-- 图：Zircon-2026 整体架构及五个执行端点 -->
<!-- ![Zircon-2026 整体架构](./assets/zircon-overview.svg) -->

具体接口见 [顶层接口](interfaces.md)，后端目标结构见 [后端](backend.md)，访存目标结构见 [访存子系统](memory.md)，特权行为见 [特权态](privileged.md)。

关于缩写：

- BDB：Branch Data Buffer
- FFR：First Fault Record
- LQ/SQ：Load Queue / Store Queue
- MSHR：Miss Status Handling Register
- PMA：Physical Memory Attribute
- ROB：Reorder Buffer
