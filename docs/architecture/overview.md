# Zircon-2026 整体架构设计

Zircon-2026 的目标配置为 `RV32IMAF_Zicsr_Zifencei`、单 hart、M-mode。处理器由 4-wide frontend、2-wide rename/dispatch/commit backend、整数和长延迟执行端点、M0/M1 两条访存流水线、L1I/L1D、共享 L2、PMA、AXI4 master 和提交级验证接口组成。当前 partial 代码已将 [M1Frontend](m1-frontend.md)、dispatch/rename/整数执行/BDB recovery/FirstFault、commit/CSR/BDB retirement、E2 [LongPipe](long-pipe.md) 与 M3 的 MemIQ/LQ/SQ、cacheable load/store、ID-6 MMIO 和 ID-7 RV32A slices 接到 `ZirconCore`，可执行 directed RV32IMA 指令流；正式 I-Cache、write-back/exclusive L1D-L2、FPU 和完整数据通路仍未实现。

双路资源接纳与路由见 [dispatch](dispatch.md)，execute-time 分支恢复见
[branch recovery](branch-recovery.md)，当前组合边界见
[Integer Dispatch/Recovery Backend](integer-dispatch-recovery-backend.md)。

<!-- 图：Zircon-2026 整体架构及五个执行端点 -->
<!-- ![Zircon-2026 整体架构](./assets/zircon-overview.svg) -->

具体接口见 [顶层接口](interfaces.md)，后端目标结构见 [后端](backend.md)，M1 方向预测见 [Banked Bimodal Predictor](bimodal-predictor.md)，目标与调用栈预测见 [BTB、RAS 与目标选择](target-prediction.md)，控制预译码和历史见 [控制流预译码与推测历史](speculative-history.md)，四路到两路缓冲见 [Fetch/Decode Queue](fetch-decode-queue.md)，执行期错误预测处理见 [分支选择性恢复](branch-recovery.md)，访存目标结构见 [访存子系统](memory.md)，特权行为见 [特权态](privileged.md)。

可执行 M1 在正式 L1I 前使用单 outstanding 的 [AXI instruction fetch transport](axi-instruction-fetch.md)；它只负责 AXI 请求、redirect drain 和逐 word fetch fault，不替代 M3 的 I-Cache。[M1Frontend](m1-frontend.md) 复用现有 Base/BTB/RAS/history/queue，将该 transport 接到两路 decode 流。[Retire trace](retire-trace.md) 从真实 commit/fault metadata 形成可选仿真边界。

关于缩写：

- BDB：Branch Data Buffer
- FFR：First Fault Record
- LQ/SQ：Load Queue / Store Queue
- MSHR：Miss Status Handling Register
- PMA：Physical Memory Attribute
- ROB：Reorder Buffer
