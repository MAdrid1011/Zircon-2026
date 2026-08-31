# M1 分支选择性恢复

branch recovery 连接 E0/BDB、ROB tail walker、Rename、IQ、completion network 和
前端 checkpoint。该路径只处理 execute-time control misprediction；trap、interrupt、
MRET 和 `FENCE.I` 仍在提交点执行 global flush。

<!-- 图：E0 分支解析到 ROB/Rename/前端恢复的数据流 -->
<!-- ![分支选择性恢复](./assets/branch-recovery.svg) -->

## 恢复请求

E0 先向 BDB 提交 `{index, robTag, actualTaken, actualTarget}`。BDB 验证引用并输出
`resolution`；只有 `mispredict=1` 时启动恢复。请求中的 resolving `robTag` 必须仍是
ROB valid 项。branch 本身保留并写 complete，年轻项被删除。

前端在请求被接受后清空 fetch/decode queue，安装 `recoveryHistory`，恢复 RAS 的
pointer/count 并重做实际 call/return，redirect 到 actual taken target 或 `pc+4`。
前端可以提前 fetch，但 `rollbackDone` 前 dispatch 必须阻塞。

## ROB Tail Walker

ROB tail 指向下一个空 slot。walker 保存 `stopIndex = branchIndex + 1 mod 24`，每周期
读取 tail 之前的最近两项：lane0 是 newest，lane1 是 next-older。输出 bundle count
为 1 或 2；Rename 通过 ready/valid 原子接收。fire 后 ROB 清 valid、tail 逆向移动、
count 减少。新 tail 等于 stopIndex 时产生 `rollbackDone`。

| 字段 | 宽度 | 用途 |
|---|---:|---|
| `robTag` | 6 | 调试、stale/ordering 检查 |
| `architecturalDestination` | 5 | 恢复 speculative RAT 项 |
| `oldPhysicalDestination` | 6 | 恢复后的映射 |
| `newPhysicalDestination` | 6 | 放回 speculative free-list |
| `allocatesPhysical` | 1 | x0/无目的指令不修改 Rename |

零年轻项时，rollback request 当周期直接完成，不进入 active。最多 23 项时输出 11 个
双项 bundle 和 1 个单项 bundle，共 12 个 fire 周期。rollback bundle 被 backpressure
时 tail、count 和输出 entry 必须保持。

## Rename Undo

Rename 每周期接收一个 1/2 项 bundle，按 lane0 newest、lane1 next-older 顺序处理。
每条有效 allocation 执行：

```text
speculativeMap[architecturalDestination] = oldPhysicalDestination
speculativeFree[newPhysicalDestination] = 1
speculativeFree[oldPhysicalDestination] = 0
```

逆序是 WAW 正确性的必要条件。例如两条年轻指令连续写 x5，先撤销最年轻映射，再撤销
较老映射，最终回到 branch 之前的 physical register。rollback 期间 rename request
和 commit update 均不得 fire。global `flushToCommitted` 优先于 rollback。

## ROB Tag Epoch

`robTag = slotGeneration ## index`。`slotGeneration[index]` 不再由 tail wrap 推导，而在
该 slot 每次 allocation 时翻转。commit、global flush 或 rollback 只清 valid，不提前
翻转；下一次复用时再翻转。这样 natural wrap、global flush 后复用和 tail rewind 后
复用遵循同一个 stale-tag 规则。

## 其他结构的年轻项删除

IntIQ、BDB 和 FirstFaultRecord 以当前 ROB head 为原点计算 modulo-24 age。若
`age(entry.robTag) > age(resolving.robTag)`，该项在恢复请求时清除。completion skid
buffer 对年轻 tag 执行同一 kill；较老结果保持 backpressure，rollback 结束后继续
写回。LongPipe/LSU 内部 operation 必须接收 kill，已进入 AXI 的事务转为后台 drain。

当前实现分两步进入：先实现 ROB walker、per-slot generation 和 Rename undo；随后接入
IQ/FirstFault/completion selective kill 后，才能把 E0 redirect 连到顶层。

## 不变量、计数器与验证

- rollback 不删除 resolving branch 或更老 ROB 项。
- 每个 rollback record 恰好 fire 一次，newest→older 严格有序。
- Rename undo 后 RAT/free-list 等于只执行 surviving ROB prefix 的状态。
- 被删除 slot 再分配时 generation 必须变化；stale completion 不得 complete 新项。
- rollback active 时 ROB enqueue/commit/completion 均不 fire。
- global flush 与 rollback 同周期时 global flush 获胜并取消 walker。

性能计数器至少记录 `branch_rollback_count`、`branch_rollback_cycles`、
`branch_rollback_entries` 和最大 observed entries。directed tests 覆盖 0/1/2/23 项、
ROB wrap、双 WAW undo、backpressure、slot reuse stale completion、重复恢复和 global
flush race；形式化阶段证明 surviving prefix、credit 与 tag epoch 不变量。
