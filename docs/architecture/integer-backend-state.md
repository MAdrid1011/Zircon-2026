# Integer Backend State

`IntegerBackendState` 将 M1 的 ROB、统一 completion 仲裁/写回、整数 PRF 和 ready table
组合成一个握手域。它是可复用的后端状态切片，不是完整 CPU：dispatch/rename、IntIQ、
执行端点、BDB/recovery controller 和 commit controller 仍由上层连接。

## 固定参数与状态归属

| 项目 | 配置 | 状态所在模块 |
|---|---:|---|
| ROB | 24 项、2 enqueue/complete/commit | `ReorderBuffer` |
| endpoint completion | E0/E1 各 1 项，E2/M0/M1 各 2 项 | 端点外部 buffer |
| completion arbitration | 5 输入、2 输出、按 ROB age | `CompletionWritebackRouter` |
| integer PRF | 56×32 bit、6R2W | `IntegerPhysicalRegisterFile` |
| ready scoreboard | 56 bit、2 allocate/2 complete | `IntegerReadyTable` |

该切片不复制 ROB entry、completion payload 或 PRF 数据。`robTag` 是 generation+index；
错误路径结果由 ROB live-generation 比较给出 accepted/discarded disposition。

## 接口

| 接口 | 方向 | 语义 |
|---|---|---|
| `enqueue[2]` / `enqueueTag[2]` / `enqueueCapacity` | in/out | dispatch 的原子双路 ROB 分配 |
| `readyAllocation[2]` | in | rename 新目的物理寄存器清 busy |
| `endpointCompletion[5]` | in, ready/valid | 五个端点的本地结果 buffer |
| `completionAccepted/Discarded[2]` | out | 每周期 ROB disposition/性能事件 |
| `wakeup[2]` | out | accepted integer completion 的 IntIQ wakeup |
| `readPhysical[6]` / `readData[6]` | in/out | PRF 六个组合读端口，含同拍写转发 |
| `integerReady[56]` | out | scoreboard 状态与同拍 completion forwarding |
| `executionRead/Context[2]` | in/out | E0/E1 的 live ROB context read |
| `commit[2]` | out, ready/valid | 已完成的 ROB head 前缀 |
| `rollback` / `rollbackUndo` | in/out, ready/valid | branch tail walk 与 rename undo |
| `squash` / `flush` | in | completion selective freeze 与全局清空 |

## 每周期行为

1. dispatch 同拍写 ROB entry，并通过 `readyAllocation` 清新物理目的寄存器的 ready。
2. unified arbiter 从五个 endpoint head 中选择相对 ROB head 最老的两项。
3. ROB 比较 valid/index/generation，并在未被 flush/rollback 阻塞时返回 accepted 或
   discarded。
4. accepted 和 discarded 都产生 endpoint ready；只有
   `accepted && writesInteger` 同拍写 PRF、置 ready 并产生 wakeup。
5. PRF read 与 ready mask 组合旁路本周期 accepted write，使 consumer dispatch/issue
   不多等待一周期；时钟边界后 ROB complete、PRF 和 ready state 同步保持结果。

没有 completion 状态机位于该 wrapper 内；所有持久状态仍在四个被组合模块。两条
ROB disposition 端口可独立前进，port 0 backpressure 不阻止 port 1 接受不同结果。

## Flush、rollback 与晚到结果

global flush 当周期屏蔽 completion transfer并清空 ROB；PRF 和 ready table 不扫描恢复。
rename 把错误路径物理寄存器放回 free-list，下一次 allocation 会重新清 busy。执行端点
必须同时接收 flush/kill 以清本地在途状态；若迭代单元仍产生晚到结果，旧 generation
在恢复结束后返回 discarded，只释放 buffer，不写 PRF、不置 ready、不发 wakeup。

branch squash 当周期冻结 completion arbiter，同时由上层向 ROB 发起 rollback。tail walk
active 期间 accepted/discarded 均为零，surviving result 保持 payload；walk 完成后较老 live
结果可 accepted，已删除的年轻结果返回 discarded。`rollbackUndo` 继续按 newest→older
送给 rename，本切片不擅自修改 RAT/free-list。

## 不变量与性能事件

- accepted/discarded 对每端口互斥，且只能对应 valid result。
- accepted integer result 的 ROB complete、PRF write、ready complete 和 wakeup 是同一次
  握手；任一消费者不得单独提前。
- discarded result 不产生 PRF/ready/wakeup 副作用。
- flush 当周期无 PRF write 或 wakeup；rollback block 期间无 disposition。
- 双写目的寄存器不得相同，p0 永不写且恒 ready。
- 上层累计 `endpoint.valid && !ready` 为 completion stall，累计
  `completionAccepted`/`completionDiscarded` 分别为有效完成和晚到丢弃计数。

## 验证映射

- 两条 ROB entry 乱序到达、按 age 双 accepted，并同拍更新 PRF/ready/wakeup。
- PRF 同拍 forwarding 与时钟边界后的持久数据一致。
- global flush 后复用同一 ROB index，旧 generation result 被 drain 且无副作用。
- branch squash 冻结 result，rollback active 保持，tail walk 后年轻 result 被 discard。
- 其余 ROB wrap、duplicate completion、completion port 独立 backpressure、PRF/ready table
  单元 corner case 继续由各自模块测试覆盖。
