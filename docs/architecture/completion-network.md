# Completion 缓冲与双端口仲裁

每个执行端点先把结果写入本地 ready/valid buffer，再由统一网络输出两个 completion。
E0/E1 各 1 项；E2、M0、M1 各 2 项。E0 的一项槽额外保存 branch-resolution payload
和 `resolutionSent` 状态；E1 使用通用一项 `CompletionBuffer`。执行单元不能假设
completion 在固定周期被接受，LongPipe/LSU 的外部契约始终是 ready/valid。

## CompletionResult

统一 payload 只包含 `robTag`、integer physical destination、integer write enable
和 32-bit data。ROB complete、PRF write 和 wakeup 使用同一次 fire。branch/BDB、
FirstFaultRecord、F result queue、LSQ 与 memory trace 的专属载荷走各自状态结构，
不复制进五个 completion buffer。

## 仲裁

五个输入按当前 ROB head 的 modulo-24 age 选择最老两项。port 0 获得最老项，
port 1 获得次老项；两端口 ready 独立，一个端口 backpressure 不阻止另一个端口
接受不同结果。该顺序不是架构可见顺序，只用于减少 ROB head 等待。

两个有效输出不得携带相同 ROB tag，也不得写同一非零 integer physical register。
这些条件违反 rename/issue 的唯一性，必须由 assertion 立即终止仿真。

## Flush 与 Selective Squash

global flush 在同周期撤销 buffer 的 in.ready/out.valid，并在时钟边界清空 count；
因此错误路径结果不能在 flush 边界 fire。对已经被 AXI 接收的事务，memory
controller 继续后台 drain，但只有 tag 仍存活时才能重新进入 M0/M1 completion
buffer。

branch-selective `squash` 同样在当周期冻结 enqueue/dequeue/arbiter transfer，但只删除
相对 resolving `robTag` 更年轻的 buffered result。2 项 buffer 将 surviving result
按原 FIFO 顺序压紧，较老结果在 ROB rollback backpressure 解除后继续完成。正在执行、
尚未进入 buffer 的 uop 必须由所属 endpoint 接收同一 squash；仅依赖 ROB generation
拒收并不足以防止旧结果误写已重新分配的 physical destination。

E0 是上述冻结规则的唯一特例：待解析 branch 必须能在产生自身 `squash` 的同周期完成
BDB resolve handshake，否则形成 `resolve→squash→resolve.ready` 组合环。E0 槽先记录
结果，branch resolve fire 后才开放 completion；自身 boundary 保留，更年轻槽清除，
registered recovery-active 期间不允许 enqueue 或 completion fire。该状态机由
[ADR-0008](../decisions/0008-e0-two-phase-result-slot.md) 固化。

## 不变量与性能事件

- E0 branch-resolution tag 必须等于其 completion tag。
- E0/E1 只接收各自 endpoint mask 允许的 RV32I uop；E1 永不执行 control operation。
- active recovery 期间 E0 不发生 enqueue/completion transfer；global flush 清空本地槽。
- 集成层分别统计 E0 resolve、E0 completion、E1 completion 的 `valid && !ready` 周期；
  统一仲裁器另统计五端点竞争导致的等待周期。

## 覆盖

- 1 项 buffer 的 full backpressure、同周期 pop/push、flush。
- E0 的非控制完成、branch resolve→completion 次序、self-boundary squash、younger
  squash、misaligned target FirstFault 和 E0/E1 独立前进。
- 2 项 buffer 的 FIFO 顺序、full 同周期 pop/push、flush、环形 head 下 selective compact。
- 五输入少于/等于/多于两项、ROB index wrap、两个输出独立 backpressure。
- 同 ROB tag、同 physical destination 和 out-of-range tag 的断言由 mutation/
  assertion tests 覆盖。
