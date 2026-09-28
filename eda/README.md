# EDA 评估

本目录提供 Nangate45 静态时序评估所需的标准单元库、SRAM 模型和物理参数，以及面向 VU13P
器件的 Vivado 核心工程定义。两条路径使用独立的 RTL 生成配置：Nangate45 将 SRAM 绑定到
BSG Fakeram 模型，Vivado 使用可推断 Xilinx BRAM 的模板。

| 入口 | 内容 |
| --- | --- |
| [Nangate45 平台](platforms/nangate45/README.md) | 工艺角、标准单元、SRAM 视图和输入来源 |
| [Vivado 工程](vivado/README.md) | VU13P 器件、100 MHz 时钟约束与工程创建命令 |
| [EDA 脚本](../scripts/eda/README.md) | RTL 生成、综合和时序报告接口 |

生成的 RTL、网表、Vivado 工程及报告分别保存在 `build/eda/nangate45/` 与
`build/eda/vivado-vu13p/`，均不进入版本库。Nangate45 评估方法和测量结果见
[逻辑时序评估](../docs/Nangate45Timing.md)。
