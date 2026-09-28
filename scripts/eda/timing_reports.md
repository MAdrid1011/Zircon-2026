# 时序报告解析接口

`timing_reports.py` 将 OpenROAD/OpenSTA 的电容、转换时间、WNS、TNS、端点和 SRAM
路径报告整理为结构化结果。命令行入口读取目标输出目录与时钟周期，写入
`timing-summary.json`。

`summarize_target()` 汇总电气检查和 setup 时序；路径解析器保留每个端点的最差路径，
并将映射后的寄存器实例关联到顶层模块。电气检查通过后才解释 WNS、TNS 与路径分布。
