# Commit

`Commit` 是顺序状态的所有者，例化 `ReorderBuffer`、`FetchTargetQueue`、`StoreQueue`、
`StoreBuffer` 和 `CSR`。默认每拍最多退休三条指令，并统一产生恢复、分支训练、寄存器释放、
Store 排空和系统维护控制。

## ROB 与退休

ROB 默认 48 项。派发时，ROB 与 SQ 共同返回可接受的有序前缀及分配编号。后端的两条 Arith、
MixArith、两条 Load 和两条 Store 结果分别写入完成端口。退休只从 ROB 头部连续选择已完成且
无阻塞的指令。

异常、分支误预测、特权返回和需要串行化的系统指令从 ROB 头窗口选择恢复点。恢复信息寄存一拍
后广播，使 ROB 头判定不直接进入前端、中端和后端的清空扇出路径。

## FTQ 与分支训练

FTQ 默认 16 项，在中端接收新取指块时分配。表项保存取指 PC、预测信息和提交训练所需元数据。
分支执行结果写回所属 FTQ 表项；退休后，训练信息进入一个小型队列，再发送给前端预测器。

## SQ 与 Store Buffer

SQ 默认 12 项，在 Store 派发时分配。Store Address 与 Store Data 可以独立到达，SQ 保存两者
并为年轻 Load 提供逐字节前递。Store 退休后按程序顺序进入 4 项 Store Buffer；Store Buffer
继续向 DCache 发送请求，因此提交不需要等待每笔 Cache 写完成。

## CSR 与系统指令

CSR 保存 M/S 特权状态、异常向量、`satp`、浮点状态和计数器。CSR 指令在 ROB 头部获得授权，
保证读改写与异常顺序一致。`FENCE` 等待 SQ、Store Buffer 和存储系统排空；`FENCE.I` 随后
请求 Cache 维护；`SFENCE.VMA` 还清空 ITLB、DTLB 和 PTW 状态。
