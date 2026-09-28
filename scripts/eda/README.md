# EDA 脚本

本目录提供 Vivado RTL 生成、Nangate45 标准单元映射、SRAM 绑定、时序评估和结果解析的入口。
生成文件位于 `build/eda/`，各脚本的参数和输出见同名说明文档。

| 脚本 | 用途 |
| --- | --- |
| [`prepare_vivado_rtl.py`](prepare_vivado_rtl.md) | 生成使用 Xilinx BRAM 的 VU13P 核心 RTL |
| [`synthesize_core.py`](synthesize_core.md) | 生成 BSG SRAM 配置 RTL 并执行 Nangate45 综合与时序分析 |
| [`adder_mapping.py`](adder_mapping.md) | 配置 BLevel 加法器的 Yosys/ABC 映射 |
| [`nangate_memories.py`](nangate_memories.md) | 将逻辑 SRAM 接口绑定到 Nangate45 估算宏 |
| [`openroad_resizer.py`](openroad_resizer.md) | 执行布局估算和标准单元电气修复 |
| [`openroad_resizer.tcl`](openroad_resizer.tcl.md) | OpenROAD 布局与修复命令 |
| [`timing_reports.py`](timing_reports.md) | 解析时序和电气检查报告 |
| [`gate_equivalence.py`](gate_equivalence.md) | 比较映射前后的时序逻辑与存储边界 |
| [`test_timing_flow.py`](test_timing_flow.md) | 验证综合与报告接口 |
