# Frontend

`Frontend` 负责下一取指地址选择、分支预测、指令缓存访问、预译码和 Fetch Queue 入队。
默认每个取指块包含四条 32 位指令，复位地址为 `0x80000000`。

## 流水

前端使用 PF、IF1、IF2 和 PD 四个逻辑阶段。PF 的 `NPC` 在顺序地址、预测地址、预译码修正
和提交恢复地址之间选择请求 PC。IF1 保存取指身份并执行早期方向预测。IF2 接收同步 BTB、
ICache 和预测表结果。PD 对返回指令执行预译码，校验控制流边界，并将有效指令写入
`FetchQueue`。

每一级都使用有效位和接受条件保存请求。下游反压时，当前级保持载荷；提交恢复会取消所有
年轻请求。ICache 响应携带 fetch token，前端只消费与当前 IF2 请求匹配的响应。

## 分支预测

`Predict` 包含基础 PHT、六张带标签历史表、Tagged Corrector、小 BTB、两路同步主 BTB、
RAS 和推测历史。IF1 产生早期方向和目标，IF2 合并同步表结果。PD 发现预测范围或目标错误时
产生局部修正；提交阶段通过 FTQ 提供最终训练信息。

## ICache 与 ITLB

ICache 为 2 路、16 set、64 B line，总容量 2 KiB。虚拟地址在 IF1 查询 ITLB；命中后使用
34 位物理地址完成 Tag 比较。TLB miss 交给共享 `PageTableWalker`，Cache miss 通过 I 侧 L2
接口处理。`FENCE.I` 维护请求会失效 ICache 内容。

## 队列与恢复

`FetchQueue` 默认保存 8 个四指令取指块的容量。入队时移除无效槽，出队时直接提供全局最老的
三条指令，因此一个三宽组可以跨越相邻 fetch packet。FTQ 位于 `Commit`，默认 16 项；
`packetStart` 和 `packetEnd` 标记确保每个 packet 只分配一个 FTQ 表项。提交重定向优先于 PD
修正，并同时清空前端流水和 Fetch Queue。
