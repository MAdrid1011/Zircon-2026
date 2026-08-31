# Branch Data Buffer 与历史检查点

`BranchDataBuffer`（BDB）固定为 8 项、单读单写。它保存控制指令从预测到提交所需的低频大字段，使 IntIQ 继续只保存紧凑 `UopRef`，ROB 每项只保存 3-bit BDB index。M1 的 bimodal 和 M4 的 miniTAGE 共用本契约，替换预测器不改变后端接口。

## 每项内容

| 字段 | 宽度 | 用途 |
|---|---:|---|
| `robTag` | 6 | 拒绝 flush 后的陈旧 resolve/commit |
| `pc` | 32 | 提交后重算 bimodal/TAGE/BTB 索引 |
| `historyBefore` | 64 | 分支预测前的 speculative global-history checkpoint |
| `predictedTaken/Target` | 1+32 | 判断 direction/target misprediction |
| `conditional/call/return` | 3 | 决定 GHR、BTB 与 RAS 训练种类 |
| `provider/alternateProvider` | 2+2 | miniTAGE 的 Base/T0/T1/T2 provider 身份 |
| `providerPrediction/alternatePrediction` | 1+1 | 提交训练与 useful 更新依据 |
| `btbWay` | 1 | 64 项 2-way BTB 的替换/命中 way |
| `rasPointerBefore/rasCountBefore` | 3+4 | 8 项 RAS 的 next-push 指针和有效深度 checkpoint |
| `resolved/actualTaken/actualTarget` | 1+1+32 | E0 resolve 后、commit training 前保存实际结果 |

索引、tag 和折叠历史不在 BDB 重复保存；提交训练以 `pc + historyBefore` 重新计算，换取更少状态位。JAL/JALR 也分配 BDB 以保存 target/BTB/RAS 数据，但只有 `conditional` 项推进 global history。

## 接口与端口仲裁

- `allocate`: dispatch 写入一项预测/checkpoint，返回 3-bit index。每周期最多分配一条控制指令；同一 decode bundle 出现两条控制指令时由 dispatch 保留第二条。
- `resolve`: E0 携带 `{index, robTag, actualTaken, actualTarget}`；`resolution` 采用
  ready/valid，可被恢复控制器回压。只有两个接口同拍握手后才写 actual 字段并改变
  BDB 状态；输出包含 mispredict、history、RAS checkpoint/action 和最终 redirect target。
- `commit`: commit controller 携带 `{index, robTag}`；读取完整 training record，并只清 valid 位。commit read 在同拍 `flushAll` 时仍可握手，以保留 lane-1 exception 前已经退休的 older branch training。
- `flushAll`: trap、MRET、FENCE.I 或其他全局 rollback 清除所有未提交 BDB 项。

端口优先级为 commit read > resolve read。成功握手的 resolve 同时占用 read/write，
因此阻塞 allocate；被回压的 resolve 只保持组合读结果，仍允许向其他 free entry 做一次
allocate write。commit 只读 data 并清 valid bit，可与一次 allocate write 并行；若同拍
`flushAll`，allocate 和 resolve 被禁止，training 仍由覆盖前 entry 产生，edge 后由 flush
清空全部 valid。
free-index 选择把同周期 commit 的槽位视为可用，因此满 BDB 可以一进一出而不产生
气泡。这个调度在任意周期至多一次 data read、一次 data write。

commit 请求必须命中 valid、相同 `robTag` 且已经 resolved 的项；resolve 请求必须命中 valid 和相同 tag。违反均为内部协议错误。预测器训练没有 ready，`training.valid` 与 commit handshake 同周期出现。

## 错误恢复

条件分支的恢复历史为 `{historyBefore[62:0], actualTaken}`；JAL/JALR 不写 GHR，恢复为原 checkpoint。RAS pointer/count 从预测前 checkpoint 出发，先执行 taken return 的 pop，再执行 taken call 的 push：空栈 pop 不改变 pointer/count，push 将 count 饱和到 8，call/return 同时为真时按 coroutine pop-then-push 处理。resolution 同时输出原 pointer/count、push/pop 和 `pc+4`，供 RAS 恢复检查点并重做正确事件。`redirectTarget` 在 taken 时为 `actualTarget`，not-taken 时为 metadata `pc+4`，不信任 E0 请求中无意义的 not-taken target。溢出后被覆盖的 RAS 内容属于允许的预测近似，不影响 JALR 的架构执行结果。mispredict 条件为 direction 不同，或 actual taken 且 predicted/actual target 不同。not-taken 指令忽略 target 字段差异。

resolve 检测到 mispredict 时，BDB 以当前 ROB head 的 modulo-24 age 清除所有比 resolving branch 更年轻的项，保留 resolving branch 和更老、尚未提交的分支。前端同周期使用 `recoveryHistory`，ROB/IQ/rename 的年轻项由 E0 全局恢复路径清除。trap/MRET/FENCE.I 不依赖年龄，直接 `flushAll`。

## 不变量与验证

- valid 项数量恒等于 `count`，且不超过 8。
- 同一周期最多一次 data read、一次 data write；resolve 不与 allocate/commit fire。
- commit+allocate 可复用同一 index，training 输出仍必须是覆盖前的旧 entry。
- stale `robTag` 不得修改任何项。
- resolution backpressure 下 valid、tag、history、RAS 和 redirect target 保持稳定，
  BDB entry 不得提前标记 resolved。
- commit 训练只来自 resolved entry；错误路径项永不训练预测表。
- mispredict 后不存在 ROB 年龄大于 resolving branch 的 valid BDB 项。
- direction、taken-target、not-taken-target、RAS overflow/underflow、coroutine pop-then-push 和 64-bit 历史边界均有定向测试。

M1 先用 `provider=Base` 驱动 bimodal，并由独立 8 项 RAS 消费恢复事件；M4 miniTAGE 增加 provider 选择、提交分配和老化，但不改变 BDB 容量和外部事务。
