# Integer Dispatch/Recovery Backend

`IntegerDispatchRecoveryBackend` 是当前 M1 的推测执行组合边界。它把双路
`BackendDispatch`、`IntegerRename`、`IntegerExecutionBackend`、8 项 BDB/recovery
controller 和 `FirstFaultTracker` 接成一个状态一致域。LongPipe、LSU、commit/CSR
policy 和 frontend 仍通过显式端口连接；commit/CSR 已由上层
[M1 Backend Subsystem](m1-backend-subsystem.md) 接入，但该组合仍不是完整 RV32I core。

## 组成与数据流

| 输入或事件 | 组合路径 | 持久状态 |
|---|---|---|
| 两条 decode entry | decode→容量规划→rename response→ROB/IQ/BDB | speculative RAT/free-list、ROB、IntIQ、BDB |
| E0/E1 completion | completion arbitration→ROB disposition→PRF/ready/wakeup | ROB complete、PRF、ready table |
| branch resolution | E0→BDB compare→recovery controller | BDB actual result、pending rollback |
| rollback | controller→ROB tail walker→rename undo | ROB tail/count、speculative RAT/free-list |
| fault candidate | dispatch/E0/预留 E2/M0/M1→oldest select | 单一 FirstFaultRecord |
| global flush | 外部 commit policy→所有推测结构 | speculative RAT 恢复到 committed RAT |

dispatch 读取 ROB、IntIQ、LongIQ、MemIQ、BDB 和 physical free-list 的即时 0–2 容量，
只接受最长合法程序序前缀。接受周期内，rename allocation、ROB entry/tag、目标 IQ uop、
BDB checkpoint、ready allocation 和 dispatch fault candidate 必须原子出现。任何一个
容量承诺与 ready 不一致都由下层断言终止仿真。

LongIQ 和 MemIQ 尚未实现，本模块把两组压紧后的 `UopRef` 与容量端口原样暴露；
E2/M0/M1 completion 和 fault 各保留三个对应输入。Integer/Branch/CSR/System 已能在
M1 集成层完成执行、精确 trap 和提交；M/A/F 和 memory uop 必须在各自里程碑完成后才
允许进入可运行顶层。

## 分支恢复事务

E0 branch 先完成 BDB resolution handshake。正确预测只更新 BDB，之后 branch 可完成；
提交侧以 `{robTag, branchDataIndex}` 读取一次 BDB 并产生 predictor training。误预测在
resolution 接受周期原子输出：

- backend selective `squash`；
- 包含 history/RAS checkpoint 和 redirect target 的 frontend recovery；
- dispatch block；
- 指向 resolving branch 的 ROB rollback request。

ROB 以 newest→older 的双项 bundle 驱动 rename undo。controller 在 ROB 接受 request 后
仍保持 block，直到零年轻项即时完成或 tail walker 拉高 `rollbackDone`。恢复期间 ROB、
IntIQ、completion slot 和 FirstFaultRecord 删除年轻项，resolving branch 自身保留；
global flush 优先并取消整个事务。

## 提交边界

本模块把两个 ROB head lane 暴露给外部 commit controller，并接收同 lane
`RenameCommit`。每项 rename commit 必须与同周期 `ROBCommit.fire` 的 architectural、
old physical 和 new physical 字段完全匹配。BDB 只有一个 commit/training read port；
branch commit 也必须匹配同周期退休的 ROB branch。

因此提交组合层必须保证每周期最多退休一条带 BDB 记录的 branch。未来如果连续两条
branch 同时到达 head，应阻止 lane 1，而不是丢弃第二条训练或复制 BDB 读口。普通双提交
以及 branch+non-branch 双提交仍可保留。

## Fault 与排空

`FirstFaultTracker` 当前接收六个候选：两条 dispatch-time fault、E0 fault，以及预留的
E2/M0/M1 fault。它以 ROB head 为年龄原点保存最老记录；selective squash 同拍过滤新候选
并在边沿删除已保存的年轻 fault，输出保持 register-only。外部 commit controller 消费
head fault 后拉高 `firstFaultClear`；
exception、interrupt、MRET 或 `FENCE.I` 产生的 global flush 清 ROB/IQ/BDB/FirstFault，
并把 speculative rename 状态恢复到包含同周期退休更新的 committed 状态。

## 不变量与性能事件

- recovery/global flush 周期的 decode accepted count 必须为零。
- IntIQ uop、BDB allocation、fault candidate 和 ready allocation 的 tag 必须来自同周期
  ROB allocation。
- branch resolution 必须先于 branch completion；branch commit 必须对应已 resolved BDB
  entry 和同周期 ROB retirement。
- rollback undo 与 rename/commit 互斥，且 rollback 完成后 speculative map/free-list 等于
  surviving ROB prefix。
- rename commit 必须与同 lane ROB retirement 完全匹配。
- global flush 不得产生 PRF write、wakeup、execute recovery 或新 dispatch。

性能计数至少记录 accepted dispatch、ROB/IntIQ/BDB occupancy、rename free count、
branch rollback 次数/周期/项数、completion accepted/discarded 和各容量原因的 dispatch
stall。当前组合层先暴露 occupancy/event 信号；计数器阵列在完整 core 集成时统一放置，
避免各子模块重复保存宽计数器。

## 验证映射

- 双 ADDI 同包 RAW：lane 1 读取 lane 0 新 physical，初始 not-ready，随后通过
  completion→wakeup 同拍旁路执行，最终双提交并更新 committed map。
- branch+年轻整数：错误预测同拍阻止新 dispatch，年轻 IQ/result/ROB 项被删除，rename
  释放其 physical，branch 恢复后单独完成并产生一次训练。
- illegal+正常整数：最老 illegal fault 进入 FirstFaultRecord；global flush 同时清 ROB、
  IntIQ、fault，并把 speculative map/free-list 恢复到 committed 状态。

后续还需覆盖 ROB/BDB wrap、rollback 与 completion backpressure、外部 fault 与 branch
squash 同拍、连续 branch 提交限制，以及 commit/global flush 与 rollback 的竞争。
