# 提交控制器

`CommitController` 是 ROB、`FirstFaultTracker`、`MachineCSRFile`、rename committed map 和前端 redirect 的唯一架构提交仲裁点。本规格冻结 M1 的决定逻辑；它已与 CSR state 和单端口 BDB 提交调度组成 [Commit/CSR Subsystem](commit-csr-subsystem.md)。完整 `RetireEvent` 格式化、E0 CSR/System side effect、WFI 唤醒状态机和 memory drain 信号仍待接入。

## 参数与接口

宽度来自 `ZirconCoreConfig`：ROB 24 项、commit width 2、6-bit `robTag`。输入为：

- 两条按程序序排列、已经完成的 ROB head `Decoupled[ROBCommit]`；lane 1 valid 隐含 lane 0 valid。
- 两条 `CommitSideEffect`，保存 CSR 写地址/数据以及 system/fence 的 `serializingReady`。
- 当前 `FirstFaultRecord`、CSR 选择出的 `EligibleInterrupt`、interrupt EPC 和 `interruptBlocked`。
- CSR 文件组合产生的 trap vector 与 MRET target。

输出为：

- 两条 ROB ready、正常退休记录、rename commit 和 0–2 的 `retiredInstructions`。
- 最多一项 CSR write、trap commit 或 MRET commit。
- `flush`、带原因的 redirect、`firstFaultClear`、`fenceICommit` 和 `wfiCommit`。

CSR/system 指令只在 lane 0 独占退休；若它出现在 lane 1，本周期只退休 lane 0，使 system 指令下一周期移动到 head。`serializingReady` 对普通指令无意义；对 CSR/system 为零时阻塞其正常退休。MRET、FENCE.I、WFI 和普通 FENCE 的具体排空条件由上游汇总到这一位。

## 每周期优先级

提交决定按以下顺序，任一高优先级事件成立后不再执行低优先级分支：

1. **lane 0 同步异常**：不退休 faulting instruction，产生 trap、清除 FirstFaultRecord，并 flush 全部 speculative state。
2. **可接收 interrupt**：只有 `EligibleInterrupt.valid && !interruptBlocked` 时成立；它发生在下一条未退休指令之前，本周期不退休 ROB 项，`mepc` 使用 `interruptEpc`。lane 1 或更年轻的已知异常不阻止 interrupt；lane 0 已知同步异常仍优先。
3. **lane 1 同步异常**：若 lane 0 是普通指令，则同周期退休 lane 0 并对 lane 1 产生 trap；faulting lane 1 不退休。若 lane 0 是 CSR/system，则先独占退休 lane 0，下一周期再处理已经成为 head 的异常。
4. **正常退休**：普通指令最多两条；CSR/system 最多一条且必须位于 lane 0。

这一选择把 interrupt 明确定义为指令边界事件：若 pending 在某条尚未退休指令之前被采样，handler 返回后仍从该指令继续。同步异常发生时 `trapCommit.exceptionPc` 来自 faulting ROB entry，`cause/tval` 来自 FirstFaultRecord；interrupt 的 `trapValue` 固定为零。

## 特殊指令提交

- CSR write 只由已经正常退休的 lane 0 CSR 指令产生；非法 CSR 必须此前转换为 FirstFaultRecord。
- MRET 正常退休后更新 CSR 状态，flush 年轻指令并 redirect 到 `mepc`。
- FENCE.I 只有 `serializingReady` 表示旧 store/MMIO 排空且 I-Cache/BTB 失效完成后才退休，随后 flush 并从 `pc+4` 重取。
- WFI 正常退休时产生 `wfiCommit`。进入/退出 quiescent 的状态机在集成层实现；控制器不会允许 WFI 与第二条指令同周期退休。
- 普通 FENCE 等待 `serializingReady` 后独占退休，但不产生 redirect。

## Flush 与状态更新顺序

lane 1 exception 允许 lane 0 的 GPR rename 和普通架构结果先提交，再 flush faulting lane 及所有年轻指令。ROB 必须使用同周期 ready fire 数计算 `headAfterCommit`，再翻转新 stream generation。lane 0 exception 和 interrupt 的 ROB fire 数为零。

ROB completed head 的 `valid` 不得被 commit controller 同拍产生的 `flush` 反向抑制，
否则会形成组合环并丢失 MRET/FENCE.I 或 lane-1 exception 前的合法退休。flush 当拍只由
controller 的 ready 选择实际 fire prefix；enqueue、completion、execution read 和
rollback 则必须立即阻塞，edge 后清空其余 ROB 状态。

`firstFaultClear` 只在同步异常被消费时置位；interrupt、MRET 和 FENCE.I 通过全局 `flush` 清除 tracker，因此不伪装为异常消费。所有 redirect 都伴随 `flush`。普通 branch redirect 不经过本模块，由 E0 执行恢复路径处理。

## 不变量与断言

- lane 1 不可在 lane 0 未退休时退休。
- CSR/system 指令不可在 lane 1 退休，也不可与另一条指令同周期退休。
- CSR write、trap commit、MRET commit 每周期至多一项。
- faulting instruction 不得进入正常退休或 rename commit。
- lane 1 exception 同周期只允许 lane 0 的普通结果成为架构可见。
- interruptBlocked 时不得产生 interrupt trap；lane 0 synchronous fault 始终压过 interrupt。
- redirect 必须伴随 flush，且 exception/interrupt/MRET/FENCE.I target 分别来自 trap vector、MRET target 或顺序 PC。
- CSR side effect 只能附属于 CSR uop；无效 ROB lane 的 side effect 不得被采用。

## 验证映射

定向测试覆盖：普通单/双退休、lane0/lane1 fault、fault+interrupt、interrupt blocked、CSR 位于 lane0/lane1、CSR backpressure、MRET、FENCE/FENCE.I、WFI、rename commit 和 redirect/flush。集成阶段再交叉：双提交边界的全部 exception cause、CSR+interrupt、长延迟完成、MMIO/AMO 阻塞、FENCE.I 失效握手、trace order 与 `minstret` 差异。
