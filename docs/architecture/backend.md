# 后端架构文档

这一章记录由研发计划冻结的后端契约。当前 M0 代码提供 `UopRef`、执行端点类型和 `FirstFaultTracker`；rename、issue、execution、ROB 和 commit 尚未接入。

<!-- 图：后端模块关系和数据通路 -->
<!-- ![后端模块关系和数据通路](./assets/backend-overview.svg) -->

译码结果写入 ROB，dispatch 只向 IQ 发送紧凑 `UopRef`。IntIQ 选择 E0/E1，LongIQ 选择 E2，MemIQ 选择 M0/M1；全局启动仲裁每周期选择最多三项。完成结果先进入各端点 buffer，再由两个 completion port 写 PRF/ROB。commit 按 ROB 年龄每周期退休最多两项，更新 architectural map、CSR/FPR 和提交级预测状态。

## UopRef

`UopRef` 保存 `robTag`、目标端点、uop class、operation、三项源类型、两项整数物理源、源 ready、目的物理寄存器、写整数/浮点标志和 immediate。PC、instruction、预测数据和完整 architectural side effect 不在 IQ 中复制。

## 执行端点

- `E0IntCtrl`：整数、分支和 system/CSR，是唯一 redirect source。
- `E1IntSimple`：无控制副作用的简单整数操作。
- `E2LongPipe`：RV32M 与 RV32F compute。
- `M0General`：load/store/atomic/MMIO。
- `M1Load`：对齐、可缓存的整数或浮点 load。

E1/E2 共享 operand admission。全局端点选择必须满足最多三条启动和两个完成端口限制。

## FirstFaultTracker

执行端点提交 `FaultCandidate {valid, order, robTag, cause, trapValue}`。`FirstFaultTracker` 在同周期和跨周期候选中保留最小 `order`，commit 消费异常或全局 rollback 时通过 `clear/flush` 释放记录。

## 阻塞、回滚和异常

短端点完成 buffer 满时阻塞对应 issue；长延迟端点通过 ready/valid 保持结果。redirect 清除年轻 ROB/IQ/LSQ 项并恢复 rename、history 和 free-list。异常只记录在 FFR，直到异常指令位于 ROB head 才修改 CSR 和 PC。
