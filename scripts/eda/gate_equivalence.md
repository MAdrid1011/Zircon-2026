# 门级等价检查接口

`prove(reference, mapped, top, library, directory, run, yosys, partition_width=None, workers=4)`
比较参考网表与映射网表的顶层输出、寄存器下一状态和存储器输入。寄存器状态与 BSG Fakeram
输出作为分区边界；返回证明范围、分区数量和结果。`workers` 指定并行证明任务数。

`boundaries()` 识别寄存器和存储器边界，`make_cuts()` 建立对应的切分模块，`partition()`
生成证明锥，`prove_cut()` 使用 Yosys SAT 验证分区。展开后的网表按输入内容摘要缓存。

也支持 TSMC28 `DFQD*` 的 CP/D/Q 边界、`BUFFD*`/`CKBD*` 的正相缓冲别名，以及
`SinglePortMaskedRam_*`、`PredictorBsgFakeram_*`、`BsgFakeram1RW1R_*` 黑盒。
黑盒实例、类型、每个端口宽度必须保持；不能省略内存命令来获得通过。
唯一的输出宽度例外是原先显式无连接的端口被 writer 展开为 dummy 线：只有另一侧的
这些位完全没有任何 cell 输入或顶层输出负载时才忽略，并单独记录数量。所有内存输入
仍严格逐位比较，任何被使用的输出宽度变化都会失败。

SAT 超时可重试精确布尔归一化（`techmap`/`aigmap` 与结构合并）；它不增加 cut、输入
假设或 don't-care。原超时日志另存，缓存记录是否使用该转换与实际脚本/输入摘要。
