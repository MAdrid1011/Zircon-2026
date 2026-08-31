# 双路 Rename/Dispatch

`BackendDispatch` 接收 FetchDecodeQueue 的两个程序序 lane，内部各使用一个
`RV32IDecoder`，并把选定前缀原子送入 Rename、ROB、BDB、IntIQ/LongIQ/MemIQ、
IntegerReadyTable 和 FirstFaultTracker。模块本身不保存流水状态；所有输出只在同一
`dispatchFire` 周期有效。

## 容量与最长前缀

候选 prefix 只能为 0、1、2。对长度 N，资源需求为：

- ROB：N 项；
- integer physical：合法且无 fetch fault、写 `rd!=x0` 的指令数；
- Int/Long/MemIQ：按 execution endpoint class 分别计数；
- BDB：合法 branch/JAL/JALR 数，必须不超过 1，且 allocate ready。

先检查 N=2；不满足时检查 N=1。`blocked`（branch recovery/global flush）使两个计划
都无效。输入 lane 1 valid 必须隐含 lane 0 valid。选定两条若分属不同 IQ，各自在目标
队列 lane 0 输出；同属一个 IQ 时压紧到 lane 0/1。

## Rename、ROB 与 UopRef

Rename request 对 fetch fault/illegal 清除 reads/writes；正常指令保留 decoder 的
rs1/rs2/rd 意图。lane 1 response包含 lane 0 RAW/WAW bypass。ROB entry 保存 PC、原始
instruction、完整 decode、privilege、old/new physical destination 和可选 3-bit BDB
index。

ROB enqueue handshake 同周期返回 `robTag`。该 tag 写入目标 `UopRef`、BDB allocation
和 fault candidate。Integer source readiness 来自 56-bit ready table；同拍 lane 1
读取 lane 0 新 destination 时强制 not-ready。无 integer source 的位置 ready=1，
source kind 为 None。

## Dispatch-Time Fault

fetch fault 优先于 decoder illegal：cause/tval 直接来自 `FetchFault`。否则非法编码产生
illegal-instruction cause=2，tval 为原 instruction。fault 指令：

- ROB `initiallyComplete=1`；
- `allocatesPhysical=0`；
- 不进入 IQ/BDB；
- 输出 `{robTag,cause,tval}` 给 FirstFaultTracker。

ECALL、EBREAK、CSR illegal 等 execute-time fault 不属于本路径，由 E0 产生 candidate。

## 不变量与验证

- 任一 dispatchFire 时，所有选中 lane 的 ROB tag 都 valid，所有目标接口 ready。
- 未选中的 lane 不修改 Rename/ROB/IQ/BDB/ready/fault 状态。
- 各 IQ 输出 lane 1 valid 必须隐含 lane 0 valid，并保持原程序序。
- 每周期最多一个 BDB allocation；双 branch 必须先 dispatch lane 0。
- fault 指令不分配 physical、不进入 IQ；同拍两个 fault 的 tag 保持 lane 顺序。
- 覆盖双整数、跨队列、ROB/IQ/physical 单容量、双 branch、lane 1 branch BDB stall、
  lane 0 fault+lane 1 normal、双 fault、同拍 RAW/WAW 和全局 block。
