# ADR-0008：E0 两阶段 Branch Result Slot

状态：Accepted

## 背景

E0 是唯一 branch resolution 源，同时必须把 branch completion 送入统一完成网络。
若 branch 在同一组合周期直接发 BDB resolve 和 completion，mispredict 会立刻产生
squash/ROB rollback；completion 网络因此反压，而 resolve 又若要求 completion ready，
会形成恢复反馈组合环。让通用 completion buffer 在 squash 周期同时接收 resolving
branch 还需要复杂的“旧项压紧+新项保留”多路状态更新。

## 决策

E0 使用一项专用 result slot。执行请求先把 completion payload、可选 BDB resolve
payload 和“是否需要 resolve”写入槽。branch 槽先完成 BDB resolve handshake，并设置
`resolutionSent`；之后才允许 completion 输出。正确预测最早多一周期完成。误预测时
squash boundary 等于该 branch tag，因此槽保留；ROB tail rollback 期间 completion
保持 backpressure，结束后继续完成。

若槽 tag 比另一 resolving branch 更年轻，selective squash 直接清除。taken control
target misaligned 的 branch/JAL/JALR 产生 FirstFault candidate，不发送 BDB resolve，
但仍完成 ROB；未提交 BDB entry 最终由 precise trap 的 global flush 清除。

E1 没有控制或系统副作用，继续使用普通一项 `CompletionBuffer`，允许 completion pop
与下一条 result push 同周期发生。

## 备选方案

- **resolve 与 completion 原子同拍**：最低延迟，但与 mispredict recovery backpressure
  构成组合环；不采用。
- **扩展通用 CompletionBuffer 接受 squash-boundary enqueue**：需要同时 compact 旧项
  和插入新项，增加所有端点面积；不采用。
- **branch 到提交点才 resolve**：面积小但恢复延迟不可接受；不采用。

## 后果

- E0 branch 占用 result slot 至少 resolve/completion 两个阶段；需统计 E0 branch slot
  stall，后续只有证据显示瓶颈时再考虑旁路。
- branch 自身 completion 晚于 recovery launch，但 branch ROB entry 保留且最终精确完成。
- E0 result slot 必须共享 `ROBTagOrder` selective squash 定义，并接受 registered
  recovery-active 阻塞；不能用自身 combinational squash 反向屏蔽 BDB resolve valid。
