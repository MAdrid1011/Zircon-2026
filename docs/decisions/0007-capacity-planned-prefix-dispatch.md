# ADR-0007：容量规划的最长前缀 Dispatch

状态：Accepted

## 背景

双路 decode bundle 需要同时更新 Rename、ROB、目标 IQ、BDB、ready table 和
FirstFaultRecord。若先向多个 ready/valid 子接口分别拉高 valid，再用各自 ready 决定
是否成功，可能出现 ROB 已接收而 IQ 未接收的半包。把所有 ready 组合成一个 fork
handshake 又会与 ROB/IQ“ready 依赖 requested valid 数量”的逻辑形成组合环。

BDB 每周期只分配一项，因此两个相邻 branch 不能同拍 dispatch。ROB、IntIQ、未来
LongIQ/MemIQ 还可能只剩一项容量；完全按双路原子 bundle 阻塞会浪费可用 lane 0，
并让双 branch bundle 无法前进。

## 决策

ROB 和各 IQ 在观察本周期 retire/issue 回收后，提供与 enqueue valid 无关的 0/1/2
饱和即时容量。dispatch 分别检查前两条和第一条所需的：ROB 项、integer physical
destination、各目标 IQ 项和 BDB allocation。优先选择满足全部资源的两条前缀；
否则选择 lane 0；否则停顿。

选定前缀后，dispatch 在一个周期内原子产生 Rename accept、ROB enqueue、按目标队列
压紧的 `UopRef`、最多一个 BDB allocation、ready-table allocation 和 fault candidate。
下游 ready 必须与此前容量承诺一致，否则 assertion 终止仿真。

illegal instruction 和 fetch fault 只进入 ROB，设置 `initiallyComplete` 并产生
FirstFault candidate；它们不分配 physical destination、不进入 IQ、不分配 BDB。
两个 fault 可同拍记录，FirstFaultTracker 继续按 ROB 年龄选择最老者。

## 备选方案

- **多个 Decoupled 接口直接 fork**：接口简单，但存在半包或组合环；不采用。
- **始终双路原子**：无需最长前缀选择，但双 branch 永久阻塞，单项资源被浪费；不采用。
- **dispatch 前预留每个资源**：可消除组合容量检查，但引入 reservation 状态、取消和
  credit 恢复；M1 不采用。

## 后果

- dispatch 选择逻辑读取少量饱和 credit，而不扫描 ROB/IQ payload。
- 每个目标 IQ 的 lane 在 dispatch 输出端压紧，满足 lane 1 valid 隐含 lane 0 valid。
- 当前解码器没有 M/F 操作，但 LongIQ route 与 capacity 接口仍冻结，M2/M4 不修改
  dispatch 原子性。
- 最长前缀策略必须覆盖不同队列组合、双 branch、fault+normal、同拍 RAW/WAW、
  BDB/ROB/IQ/physical-register 各类单项资源边界。
