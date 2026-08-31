# 访存子系统架构文档

这一章记录双 LSU、PMA、L1/L2 和 AXI4 的冻结契约。当前实现包含 PMA classifier 和 ordered IO combiner；Cache、LSQ、refill 与 writeback path 尚未接入。

<!-- 图：M0/M1、LSQ、L1D、L2 与 AXI4 数据通路 -->
<!-- ![访存子系统](./assets/memory-overview.svg) -->

M0 接收全部 load、store、atomic 和 device 请求，M1 只接收对齐且可缓存的 load。地址生成后先查询 PMA，再查询更老 SQ 地址和数据；device 或 atomic 在 M1 检出时重放到 M0。Cacheable load 在所有更老 store 地址已知后才能访问 L1D。

## PMA

`PMAClassifier` 对 `address` 按配置顺序 first-match，输出 `kind/readable/writable/executable/atomic`。默认配置包括 `0x8000_0000–0x8fff_ffff` memory、`0xa000_0000–0xa000_ffff` strong device 和 `0xb000_0000–0xbfff_ffff` burstable device；未命中地址不可访问。

## OrderedIOCombiner

每项 `OrderedIORequest` 保存 order、ROB tag、地址、方向、size、写数据/mask、burstable 和 region tag。combiner 仅合并 order 连续、地址按 size 相邻、方向/size/region 相同且不跨 4 KiB 的请求。`forceFlush` 结束当前 group；不兼容的年轻请求保持 backpressure，直到旧 group 完成输出。

## Cache 目标结构

L1I/L1D 均为 1 KiB、2-way、32 B line。L1D 提供 4 个 MSHR 和四个 word bank。L2 默认 4 KiB、4-way、32 sets、4 个 MSHR，8 KiB 仅作为参数对照点。D block 在 L1D、L2 和 transfer buffer 中最多存在一份；I 侧 non-inclusive。

## 异常和顺序

非对齐访问直接生成 misaligned exception。AXI error 生成 access fault。Store、AMO 和 MMIO 在提交或总线成功响应前不能修改不可回滚状态。FENCE、aq/rl 和 FENCE.I 通过排空对应队列建立顺序。
