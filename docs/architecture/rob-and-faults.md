# ROB、completion 与 FirstFaultRecord

M1 ROB 固定为 24 项、双入队、双完成、双提交。ROB 保存 PC、原始 instruction、
完整译码、逻辑/物理目的寄存器和 BDB 引用；IQ 不复制这些字段。分支预测完整元数据
保存在 8 项 BDB，store/load side effect 保存在 SQ/LQ。

## ROB tag

`robTag` 为 6 bit：`generation[0] ## index[4:0]`，只有 index 0–23 合法。
generation 是 per-slot allocation epoch：每个物理 slot 每次重新分配时翻转。natural
wrap、global flush 后复用和 branch tail rewind 后复用均得到不同于上一次 occupant 的
tag，使已 kill 的 completion 不能命中新 stream 的同一 index。

每个 completion 同时比较 valid、index 和 generation；不匹配的陈旧结果被丢弃，
匹配但已经 complete 的第二份结果触发 duplicate-completion assertion。LongPipe、LSU
和 AXI controller 仍须在 kill 时清空/标记其本地结果；ROB generation 不是允许无限
延迟陈旧消息的替代品。

## 入队与提交

- lane 1 valid 隐含 lane 0 valid；两条按一个原子 bundle 获得容量。
- full ROB 可在同周期退休两条并接收两条，count 保持 24。
- commit lane 1 只有在 lane 0 complete 时才 valid，consumer 也不得只 ready lane 1。
- illegal/fetch-fault 等 dispatch-time fault 可用 `initiallyComplete` 入队，但 fault
  载荷只进入 FirstFaultRecord。
- global flush 禁止新入队、清除全部 valid，并从 flush 后的新 generation 重新开始。
- branch rollback 保留 resolving branch 和所有更老项，从 tail 每周期逆序删除最多两项；
  rollback active 时禁止 enqueue、completion 和 commit。

## FirstFaultRecord

记录严格采用 `{robTag, cause, tval}`，不保存 64-bit program order。候选的年龄由
当前 ROB head 到候选 index 的 modulo-24 距离计算；最小距离是最老 fault。这比每个
候选和 ROB entry 保存绝对 order 更小，并在 head 移动时保持正确。

FirstFaultRecord 只在下列事件清除：fault 位于 head 且由 commit/trap controller
消费，或包含该 fault 的 instruction stream 被 flush。exception priority 先在每条
指令内部解析，再把最多两个已经唯一化的候选送入 tracker。

## 必须断言

- `count <= 24`，且 count 等于 valid entry 数。
- lane 1 enqueue/commit 不越过 lane 0。
- completion tag index 合法，同周期两端口 tag 不重复。
- 不匹配 generation 的 completion 不修改 entry。
- commit PC/instruction/tag 在 backpressure 下稳定。
- FirstFaultRecord 始终选择相对 head 最老的有效候选。
