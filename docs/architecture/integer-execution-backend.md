# Integer Execution Backend

`IntegerExecutionBackend` 把 12 项 IntIQ、双路 ROB/PRF operand read、E0/E1 短流水线
和 [Integer Backend State](integer-backend-state.md) 接成 M1 整数执行闭环。dispatch/rename
在上游原子提供 ROB entry、`UopRef` 与 ready allocation；BDB/recovery 和 commit/CSR policy
由 [M1 Backend Subsystem](m1-backend-subsystem.md) 在外层连接，因此该模块不是最终 CPU 顶层。

## 组成与端口映射

| 路径 | 组成 | 固定资源 |
|---|---|---:|
| issue | `IntegerIssueQueue` | 12 项、2 enqueue、E0/E1 各最多 1 issue |
| operand read | `IntegerOperandRead` | 2 ROB context、4 PRF read |
| execute | `IntegerShortPipes` | E0/E1 各 1 项 result slot |
| completion/state | `IntegerBackendState` | 5→2 completion、24 ROB、56×32 6R2W PRF |

PRF port 0/1 固定给 E0 两源，port 2/3 给 E1；port 4/5 作为
`auxReadPhysical/Data` 暴露给顶层集成。M2 在没有 trace GPR retirement 的周期将其用于
E2 两源读取；有 trace retirement 时 trace 读取优先，E2 issue 停顿，不增加第七个读口。
M3 继续保持该 6R2W 几何：端口 4/5 由全局 arbiter 在 E2 与 M0/M1 之间按 ROB 年龄和
剩余三启动预算分配，M0/M1 不增加 PRF 端口；完整合同见 ADR-0013。
`otherCompletion[0..2]` 分别预留 E2、M0、M1，连同 E0/E1 组成冻结的五端点仲裁顺序。

## 外部接口

- `robEnqueue/Tag/Capacity`、`readyAllocation`：来自 dispatch/rename 的 ROB 与 scoreboard
  原子状态更新。
- `intEnqueue/Capacity/Count`：dispatch 到 IntIQ 的紧凑 uop bundle。
- `otherCompletion[3]`：后续 LongPipe/双 LSU 的 ready/valid 结果。
- `branchResolve`、`e0Fault`：E0 到 BDB 和 FirstFaultTracker 的专属副作用，不进入
  completion payload。
- `csrAccess/commitSideEffect/systemSerializingReady`：E0 对架构 CSR 的组合查询、单项
  tagged 提交副作用，以及 System 指令排空完成条件。
- `commit[2]`、`rollback/Undo`：到 commit controller 和 rename tail-undo。
- `squash`、`recoveryActive`、`flush`：执行期 selective recovery 与提交期 global flush。
- `integerReady`、`wakeup`、accepted/discarded、队列/槽 occupancy：dispatch 反馈、调试和
  性能计数事件。

## 整数流水与依赖旁路

1. dispatch 只在 ROB 与 IntIQ 都报告足够即时容量时，同时 enqueue 两边并清新目的
   physical ready。
2. IntIQ 按 ROB age 选择 E0-exclusive 和 E1-flexible uop；两端 ready/valid 独立。
3. operand-read 用 `robTag` 读取 live ROB context，并从四个 PRF 端口选择 register、PC、
   immediate 或 zero operand。
4. E0/E1 组合执行后把结果写入各自一项槽；completion router 按 age 选择最多两项。
5. ROB accepted 同拍写 PRF、置 ready 并向 IntIQ wakeup。

已在 IntIQ 的 uop 对本周期 wakeup 做组合 source-ready 旁路，因此已排队消费者可在
producer completion 的同周期启动。E1 result slot 支持 producer pop 与 consumer push
同拍，简单整数依赖链无需额外空泡；时钟边界仍把 wakeup 写入队列状态，保证下游
backpressure 时不丢失完成。该路径是 completion→wakeup→IQ select→operand read→E1
enqueue 的组合时序路径，必须在 M5 单独报告 slack，不能为追求频率静默删除旁路。

## Branch 与恢复

Branch/JAL/JALR 只由 E0 接受。E0 先把 BDB resolve payload 握手，再开放该 branch 的
completion；resolve backpressure 不阻止 E1 执行。taken target misaligned 只产生
FirstFault candidate，跳过 BDB resolve，仍完成 ROB entry 以便提交点精确陷入。

`squash` 同周期冻结 IntIQ、短流水槽和 completion arbitration；IntIQ/结果槽删除年轻
tag，ROB tail walk 由外部 recovery controller 通过 `rollback` 启动。`recoveryActive`
阻止 E0 接收新结果，ROB rollback active 使所有 completion 保持；恢复后 late younger
result 由 generation 判定 discarded。global flush 清 IntIQ、ROB 和短结果槽，但不扫描
PRF/ready table。

## CSR 与 System

CSR/System 只允许在 `robTag == robHeadTag` 时进入 E0。CSR 旧值通过普通 completion
写回目的物理寄存器；写地址/数据保留在一个 53-bit tagged side-effect slot，直到同 tag
退休。非法 CSR、ECALL 和 EBREAK 产生 FirstFault candidate，faulting CSR completion
禁止 PRF 写。FENCE、FENCE.I、WFI 和 MRET 在完成后由 `systemSerializingReady` 控制提交。

## 不变量与性能事件

- 同一 dispatch instruction 的 ROB tag 与 IntIQ `UopRef.robTag` 必须相同。
- operand read 只接受 live context，且 source 必须为存储 ready 或本周期 wakeup ready。
- E0/E1 endpoint mask 与 operation class 一致；E1 永不执行 control/system；CSR/System
  不得离开 ROB head 执行。
- accepted completion 的 ROB/PRF/ready/wakeup 原子，discarded 无副作用。
- branch resolve 必须先于其 completion；恢复期间错误路径不修改状态。
- 统计 IntIQ occupancy、E0/E1 slot occupancy、issue 数、wakeup-bypass issue、各端点
  completion stall、accepted/discarded 和 branch-resolve stall。

## 验证映射

- 两条同包 producer/consumer：consumer 初始 not-ready，producer 经 E1 完成后同拍
  wakeup→issue，并用 PRF forwarding 得到正确结果，最终双 ROB commit-ready。
- E0 branch 在 BDB backpressure 下保持 payload，resolve 后才 ROB complete。
- IntIQ 单元覆盖同拍 enqueue+wakeup、queued wakeup→issue、E0/E1 选择、wrap age、满队列
  recycle、flush 和 selective squash。
- 短流水线与 backend-state 单元继续覆盖 misaligned branch、自边界恢复、stale drain、
  dual completion、PRF forwarding 与 rollback hold。
