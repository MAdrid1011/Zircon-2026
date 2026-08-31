# Completion 缓冲与双端口仲裁

每个执行端点先把结果写入本地 ready/valid buffer，再由统一网络输出两个 completion。
E0/E1 各 1 项；E2、M0、M1 各 2 项。执行单元不能假设 completion 在固定周期被
接受，LongPipe/LSU 的外部契约始终是 ready/valid。

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

## 覆盖

- 1 项 buffer 的 full backpressure、同周期 pop/push、flush。
- 2 项 buffer 的 FIFO 顺序、full 同周期 pop/push、flush、环形 head 下 selective compact。
- 五输入少于/等于/多于两项、ROB index wrap、两个输出独立 backpressure。
- 同 ROB tag、同 physical destination 和 out-of-range tag 的断言由 mutation/
  assertion tests 覆盖。
