# 后端架构文档

这一章记录由研发计划冻结的后端契约。当前 M1 partial 实现已有 `UopRef`、执行端点类型、组合译码/整数语义、[双路 dispatch](dispatch.md)、integer rename、[IntIQ→E0/E1→completion 整数执行闭环](integer-execution-backend.md)、`FirstFaultTracker`、[BDB 与 lossless recovery 闭环](branch-recovery.md)、M-mode CSR 状态与 [commit controller](commit-control.md)。dispatch、rename、整数执行、BDB recovery 和 FirstFault 已形成[组合后端](integer-dispatch-recovery-backend.md)，提交仲裁、CSR state 和单端口 BDB retirement 已形成 [Commit/CSR 组合](commit-csr-subsystem.md)；两个组合域、E0 system side effect 和前端尚未接成可执行顶层。

<!-- 图：后端模块关系和数据通路 -->
<!-- ![后端模块关系和数据通路](./assets/backend-overview.svg) -->

译码结果写入 ROB，dispatch 只向 IQ 发送紧凑 `UopRef`。IntIQ 选择 E0/E1，LongIQ 选择 E2，MemIQ 选择 M0/M1；全局启动仲裁每周期选择最多三项。完成结果先进入各端点 buffer，再由两个 completion port 请求 ROB disposition；live accepted 结果同拍写 ROB/PRF/ready table/IntIQ wakeup，stale discarded 结果只排空。commit 按 ROB 年龄每周期退休最多两项，更新 architectural map、CSR/FPR 和提交级预测状态。

## UopRef

`UopRef` 保存带 wrap generation 的 6-bit `robTag`、5-bit allowed endpoint mask、uop class、operation、三项源类型、两项整数物理源、源 ready、目的物理寄存器、写整数/浮点标志和 immediate。PC、instruction、预测数据和完整 architectural side effect 不在 IQ 中复制。

## 执行端点

- `E0IntCtrl`：整数、分支和 system/CSR，是唯一 redirect source。
- `E1IntSimple`：无控制副作用的简单整数操作。
- `E2LongPipe`：RV32M 与 RV32F compute。
- `M0General`：load/store/atomic/MMIO。
- `M1Load`：对齐、可缓存的整数或浮点 load。

E1/E2 共享 operand admission。全局端点选择必须满足最多三条启动和两个完成端口限制。

## FirstFaultTracker

执行端点提交 `FaultCandidate {valid, robTag, cause, trapValue}`。`FirstFaultTracker` 以当前 ROB head 为基准计算 modulo-24 age，在同周期和跨周期候选中保留最老 tag；commit 消费异常、global flush 或 branch-selective squash 释放失效记录。

## 阻塞、回滚和异常

短端点完成 buffer 满时阻塞对应 issue；长延迟端点通过 ready/valid 保持结果。redirect 清除年轻 ROB/IQ/LSQ 项并恢复 rename、history 和 free-list。异常只记录在 FFR，直到异常指令位于 ROB head 才修改 CSR 和 PC。
