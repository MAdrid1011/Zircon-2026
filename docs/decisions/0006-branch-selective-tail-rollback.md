# ADR-0006：分支选择性 Tail-Walk 回滚

状态：Accepted

## 背景

E0 在 branch/JAL/JALR 执行后即可判断 direction/target misprediction。现有 global
flush 会把 resolving branch 之前尚未提交的正确指令也删除，并把 speculative RAT
退回 committed snapshot，不能用于 execute-time redirect。为 8 个 BDB 项各保存
`32×6-bit RAT + 56-bit free-list` 则至少增加 1984 bit checkpoint，还未计 valid 和
恢复选择逻辑。

Zircon-2024 在 branch 到达提交点后才 flush，此时所有更老指令已经提交，因而可以
直接回到 committed RAT。该路径虽然正确，但不满足 Zircon-2026 在 E0 解析后恢复的
延迟目标。

## 决策

ROB 使用已有的 `{architecturalDestination, oldPhysicalDestination,
newPhysicalDestination, allocatesPhysical}` 作为 rename undo log。mispredict 请求携带
resolving `robTag`；ROB 从 tail 向 branch+1 逆序遍历，每周期原子输出最多两条 undo
record，并删除对应年轻项。Rename 按 newest→older 顺序应用 record，恢复 RAT 并释放
new physical register。branch 与所有更老项保留。

回滚期间禁止 dispatch 和 commit；completion buffer 保持结果，不向 ROB fire。前端
可以恢复 history/RAS 和 redirect，但在 `rollbackDone` 前不得把正确路径 dispatch
到 ROB。最多 23 个年轻项，因此双项 walker 最坏 12 周期。

ROB generation 改为每 slot allocation epoch：每次向某个物理 slot 分配新指令时，
该 slot 的 1-bit generation 翻转并形成 `robTag`。tail rewind 后重新使用同一 index
也会得到不同 tag，错误路径的晚到 completion 不能命中新项。generation 仍不是无限
期消息的保护；各执行端点必须响应 kill。

## 备选方案

- **每分支 RAT/free-list checkpoint**：恢复一周期，但复制至少 1984 bit，并需要
  八路 checkpoint 读写选择；不采用。
- **提交点才恢复**：复用 Zircon-2024 路径，面积最小，但错误路径一直占用 ROB/IQ，
  且误预测延迟受全部更老长延迟指令影响；不采用。
- **组合扫描全部 ROB 重建 RAT**：没有 checkpoint，但形成 24 项×双 WAW 的长组合
  链；不采用。

## 后果

- 持久状态只增加 rollback stop pointer/active 状态，不增加 per-branch RAT 副本。
- recovery latency 与年轻 ROB 项数量相关，必须由性能计数器统计。
- ROB、Rename、IntIQ、BDB、FirstFaultRecord 和 completion network 必须共享同一
  modulo-24 “younger than resolving tag”定义。
- global trap/MRET/FENCE.I flush 保持独立，仍恢复 committed snapshot。
