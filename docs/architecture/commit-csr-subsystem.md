# Commit/CSR Subsystem

`CommitCSRSubsystem` 把无状态 `CommitController` 与有状态 `MachineCSRFile` 组成 M1
架构提交域，并在该边界执行单端口 BDB 的 branch retirement 调度。E0 CSR/System
side-effect 生成、memory/device drain、interrupt EPC 选择、WFI 睡眠状态和 frontend
redirect consumer 仍在外部，因此该模块尚未与整数后端组成完整 core。

## 接口与状态

| 方向 | 接口 | 作用 |
|---|---|---|
| 输入 | `rob[2]` | 已完成且连续的 ROB head，lane 1 依赖 lane 0 |
| 输入 | `sideEffect[2]` | CSR 写地址/数据与 serializing ready |
| 输入 | `FirstFaultRecord` | 当前最老同步异常 |
| 输入 | interrupt pins/EPC/block | M-mode interrupt 选择和不可撤销事务门控 |
| 输入 | CSR access、FP commit | E0 组合查询与提交级浮点状态更新 |
| 输出 | ROB ready、retired、rename commit | 0–2 条精确退休事务 |
| 输出 | BDB commit | 每周期最多一条 branch training 读取 |
| 输出 | CSR/trap/MRET、flush/redirect | 唯一架构状态与提交级重定向事务 |

持久状态只位于 `MachineCSRFile`：M-mode CSR、`mcycle`、`minstret`、floating CSR 和
interrupt enable。提交仲裁与 BDB lane gating 均为组合逻辑，不复制 ROB entry 或
side-effect payload。

## 单端口 BDB 提交调度

BDB 固定 1R1W，因此每周期最多退休一条 `hasBranchData` 指令。组合层先根据
`branchCommit.ready` 决定向 commit controller 显示哪些 ROB lane：

- lane 0 是 branch 且 BDB 不可接收时，两个 lane 均阻塞；
- lane 0/1 连续两条 branch 时，只显示 lane 0；
- lane 0 普通、lane 1 branch 且 BDB ready 时，允许双退休；
- lane 0 branch、lane 1 普通且 BDB ready 时，也允许双退休。

输出的 `{robTag, branchDataIndex}` 必须匹配同周期 `retired.valid` 的 branch。连续 branch
在下一周期自然前移后再训练；不得丢弃第二条训练，也不得为维持名义双提交复制 BDB
读口。

## Flush 当拍的退休语义

exception-after-lane0、MRET 和 `FENCE.I` 都可能在允许一个 older instruction 退休的
同周期产生 global flush。为避免 `flush→ROB.valid→commit decision→flush` 组合环，ROB
的 completed head visibility 不受 `flush` 抑制；`flush` 只立即阻止 enqueue、completion、
execution-context read 和 rollback。真正的 ROB ready 仍由 commit controller 决定，
因此 lane-0 exception 和 interrupt 的退休数保持零。

同一边界上：

1. 允许的 ROB prefix、rename committed map、BDB training、`minstret` 和 CSR/trap event
   在该 edge 提交；
2. ROB 清除 faulting/年轻项，BDB 清除剩余 checkpoint；
3. speculative rename map 恢复到包含同拍 committed update 的 map；
4. frontend 使用 redirect 开始新 stream。

BDB commit read 在 `flushAll` 当拍保持 ready，使 lane-1 exception 前已经退休的 lane-0
branch 不丢训练；resolve 和 allocate 仍被 flush 禁止，sequential clear 优先删除所有
剩余项。

## CSR、trap 与 interrupt

commit controller 的 CSR write、trap commit 和 MRET commit 直接驱动 CSR file，三者
严格互斥。`retiredInstructions` 同拍增加 `minstret`；显式 counter CSR 写继续具有优先级。
CSR file 返回 eligible interrupt、Direct/Vectored trap target 和 MRET target，供提交
仲裁生成 redirect。

同步异常优先于 interrupt；lane-0 fault 不退休，lane-1 fault 可先退休普通 lane 0；
interrupt 在下一条未退休指令之前进入。`interruptBlocked` 用于 MMIO/AMO 等不可撤销
事务，最终实现必须另外记录其阻塞延迟。

## 不变量与验证

- 每周期 `PopCount(retired branch) <= 1`；双 branch 时 lane 1 ready 必须为零。
- BDB commit 必须 ready，且匹配同周期退休 branch。
- flush 可以伴随合法 older retirement，但任一 ROB ready 必须有 matching retired event。
- CSR write、trap commit、MRET commit 互斥；faulting lane 不产生 rename commit。
- `minstret` 增量严格等于本周期 retired count。
- lane-1 exception 可与 lane-0 branch training 同拍；BDB/ROB 在 edge 后均清除年轻状态。

定向测试覆盖普通双退休、连续 branch 序列化、lane-1 exception+lane-0 branch+flush、
serialized CSR write、ROB flush 当拍 prefix visibility 和 BDB flush 当拍 training。后续
集成交叉必须加入 interrupt+CSR、MRET/FENCE.I drain、WFI、FP commit、trace order，以及
真实后端 ROB/BDB/rename 的同拍连接。
