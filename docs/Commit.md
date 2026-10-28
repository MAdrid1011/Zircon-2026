# 提交与恢复

`Commit` 是顺序状态的所有者，例化 `ReorderBuffer`、`FetchTargetQueue`、`StoreQueue`、
`StoreBuffer` 和 `CSR`。默认每拍最多退休三条指令，并统一产生恢复、分支训练、寄存器释放、
Store 排空和系统维护控制。

性能配置沿用同一个 ROB、FTQ、SQ 和 Store Buffer，并加入向量完成、宽 Store 缓冲和
`PerformanceCSR`。所有标量与向量指令通过同一 ROB 按程序顺序退休。

## ROB 与退休

ROB 默认 36 项。派发时，ROB 与 SQ 共同返回可接受的有序前缀及分配编号。后端的两条 Arith、
MixArith、两条 Load 和两条 Store 写入完成端口；原子操作复用 LS1 的完成端口。退休只从 ROB 头部连续选择
已完成且无阻塞的指令。

异常、分支误预测、特权返回和需要串行化的系统指令从 ROB 头窗口选择恢复点。恢复信息寄存一拍
后广播，使 ROB 头判定不直接进入前端、中端和后端的清空扇出路径。

向量指令只占一个 ROB 表项，LMUL 或复杂操作的内部任务全部结束后才发布完成。
退休同时更新已提交向量映射、释放旧物理组并汇总运算标志。

## FTQ 与分支训练

FTQ 默认 16 项，在中端接收新取指块时分配。表项保存取指 PC、预测信息和提交训练所需元数据。
分支执行结果写回所属 FTQ 表项；退休后，训练信息进入一个小型队列，再发送给前端预测器。

## SQ 与 Store Buffer

SQ 默认 12 项，在 Store 或原子指令派发时分配。Store Address 与 Store Data 可以独立到达，
SQ 保存两者并为年轻 Load 提供逐字节前递。Store 退休后按程序顺序进入 4 项 Store Buffer；
Store Buffer 继续向 DCache 发送请求，因此提交不需要等待每笔 Cache 写完成。原子指令不会进入
Store Buffer，而是在 ROB 头等待更早的已提交 Store 排空，再授权后端原子单元执行。

Store Buffer 通过寄存的在途状态选择当前项或后继项的请求载荷。响应握手只控制
请求有效和出队更新；有效请求断言所选后继项恰好对应当前请求完成后的替换项。

向量排空接口的 `start.bits` 提前携带候选项类别，只有 `start.fire` 授权排空。
宽 Store 的准备阶段由当前 token 持有 Cache 载荷，其他阶段按候选类别选择标量或向量
载荷；响应交接使用寄存的阶段选择后继数据，实际请求有效和状态更新仍等待握手。
仿真退休跟踪按每条退休通道的 SQ 编号选择载荷行，覆盖仍在排空的主项以及已排队的后继项；
同拍退休的多个向量 Store 各自保留组偏移和布局，不复用主项的观察元数据。
普通 Cache 请求有效与原生 SRAM 的立即交接请求分别形成，再合并到共享 Store 接口。
载荷的原生窗口分类与跨行地址并行计算，由 Commit 传给 Backend；Cache 有效、原生端口
选择和 ready 所有者使用同一分类，并断言它等于物理地址译码，保持接受与响应周期。

PMA 可合并写窗口中的 Store 仍按程序顺序从 Store Buffer 进入 DCache。DCache 接收后即可释放
对应 Store Buffer 表项，并在内部完成 burst 聚合与下级响应；后续强顺序 Store 会等待该 burst
结束。`FENCE` 的存储系统排空条件同时覆盖写合并队列。

## CSR 与系统指令

CSR 保存 M/S 特权状态、异常向量、`satp`、浮点状态和计数器。CSR 指令在 ROB 头部获得授权，
保证读改写与异常顺序一致。`FENCE` 等待 SQ、Store Buffer 和存储系统排空；`FENCE.I` 随后
请求 Cache 维护；`SFENCE.VMA` 还清空 ITLB、DTLB 和 PTW 状态。

中断恢复目标在既有延迟恢复边界保存。Direct 模式使用对齐向量基址，vectored 模式
使用基址加四倍中断 cause；加法通过 32-bit 分组进位结构完成，地址按模 2^32 计算。

性能配置的 `PerformanceCSR` 保存 `fcsr`、七个向量 CSR 和非特权计数器。
`vset*` 的执行结果供后续 Rename 使用，退休更新已提交配置；恢复时，Rename 从已提交状态
恢复向量配置与映射。FP 异常标志和整数饱和标志随指令退休汇总，已退休向量指令清除 `vstart`。
基础 `FENCE` 等待标量与宽 Store 缓冲以及存储系统排空。
