# ADR-0005：四项 Fetch/Decode Queue

状态：Accepted

## 背景

前端一次可接受四条指令，正式译码和 dispatch 每周期最多处理两条。队列项还必须携带
64-bit history checkpoint、预测目标和精确 fetch fault，单项明显宽于普通 instruction
FIFO。直接沿用 8 项或更深队列会复制大量低频 metadata，不符合 Zircon-2026 的面积
目标。

## 决策

fetch/decode queue 固定为 4 项，支持每周期原子 enqueue 1..4 项，并支持 dequeue lane0
或 lane0+lane1。同周期 dequeue 产生的 credit 立即参与 enqueue ready，因此满队列在
第二个双路 drain 周期可同时收下下一组四条指令，稳态仍维持 2 instruction/cycle。

队列项保存 instruction、完整 BDB prediction metadata、privilege 和 fetch fault
`{valid,cause,tval}`。不保存完整 decode result；两路正式 decoder 在出队后生成结果，
随后写入 ROB。head/tail 使用 2-bit 环形 pointer，3-bit count 区分 full 与 empty。

## 后果

- 相比 8 项设计少保存四份宽 metadata，同时不降低 2-wide decode 的理论吞吐。
- I-Cache miss latency 必须由 line/refill buffer 和 MSHR 隐藏，不能用加深本队列替代。
- 四项 enqueue 必须是 prefix 原子事务；credit 不足时整个 fetch group 保持重放。
- flush 在一周期内令 count 归零，旧数据位无需清零，因为 count 是唯一有效性来源。
