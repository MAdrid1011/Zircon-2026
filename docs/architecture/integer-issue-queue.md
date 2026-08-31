# IntIQ 与 E0/E1 选择

IntIQ 固定 12 项、双入队、最多双发射。每项只保存 compact `UopRef` 与独立 valid；
PC、instruction、完整 decode、预测和异常不进入 IQ。`UopRef` 保存 5-bit allowed
endpoint mask，而不是在 dispatch 时静态绑定一个 endpoint。

## 选择策略

所有 ready uop 以当前 ROB head 为基准比较 modulo-24 age：

1. 如果存在 E0-exclusive uop（branch/CSR/system/fence），E0 选择其中最老项。
2. E1 从剩余的 E1-eligible 简单整数中选择最老项。
3. 如果没有 E0-exclusive uop，E1 先选择最老 flexible uop，E0 再从剩余项选择。

这保证 control/system 不被简单整数长期占用 E0，同时仍允许两个 simple integer
同周期启动。E0/E1 输出各自使用 ready/valid；一端 backpressure 不阻止另一端
启动不同 uop。

## Wakeup 与容量

两个统一 completion physical-destination wakeup 在周期边界更新源 ready。入队项和
已经排队的 issue candidate 都组合观察同周期 wakeup：前者避免“完成与 consumer
dispatch 同周期”丢失唤醒，后者允许 producer completion 与 dependent consumer issue
同周期发生。issue 输出携带已经旁路后的 source-ready 位，operand-read 不会把它误判为
未就绪。满队列
可用本周期 issue.fire 的空位接收同样数量的新项，不产生额外 full bubble。
`enqueueCapacity` 将包含本周期 issue.fire 回收项的即时容量饱和报告为 0/1/2；
selective squash 或 global flush 时固定为 0，供 dispatch 在产生任何子事务前选择
最长可接受前缀。

lane 1 enqueue valid 隐含 lane 0 valid；两个输入按原子 bundle 获得容量。global
flush 清除全部项并禁止当周期 issue/enqueue。branch-selective `squash` 携带 resolving
`robTag`；队列以当前 ROB head 为原点删除所有更年轻项并保留 branch 之前的工作，
当周期同样禁止 issue/enqueue。BDB、ROB、IQ 与 completion 使用统一的
`ROBTagOrder` modulo-24 判定；完整 redirect 仍需顶层 dispatch/operand-read 接线。

## 不变量与覆盖

- valid count 与 occupancy credit 相等，范围 0–12。
- 两个 issue 端口不能选择同一 entry/ROB tag。
- E1 输出的 endpoint mask 必须包含 E1，且不得包含 E0-exclusive 操作。
- 不 ready 且本周期未被 wakeup 的 source 不能 issue；每个 producer completion port 都能
  唤醒两个源，queued consumer 可同拍 issue。
- 覆盖 E0-exclusive+simple、两个 simple、E0/E1 独立 backpressure、ROB wrap age、
  full 同周期双 issue/双 enqueue、global flush、selective squash 全删/部分保留。
