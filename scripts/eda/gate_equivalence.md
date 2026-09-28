# 门级等价检查接口

`prove(reference, mapped, top, library, directory, run, yosys, partition_width=None, workers=4)`
比较参考网表与映射网表的顶层输出、寄存器下一状态和存储器输入。寄存器状态与 BSG Fakeram
输出作为分区边界；返回证明范围、分区数量和结果。`workers` 指定并行证明任务数。

`boundaries()` 识别寄存器和存储器边界，`make_cuts()` 建立对应的切分模块，`partition()`
生成证明锥，`prove_cut()` 使用 Yosys SAT 验证分区。展开后的网表按输入内容摘要缓存。
