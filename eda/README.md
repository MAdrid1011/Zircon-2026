# EDA 平台

本目录保存公开的 Nangate45 标准单元与 SRAM 估算视图、Vivado 工程定义，以及
TSMC28 标准单元测量摘要。TSMC 工艺库由外部安装提供，不复制到仓库。

| 入口 | 内容 |
| --- | --- |
| [Nangate45 平台](platforms/nangate45/README.md) | 工艺角、标准单元、SRAM 视图和输入来源 |
| [Nangate45 逻辑时序](../docs/Nangate45Timing.md) | BSG SRAM 绑定、约束与复现方法 |
| [TSMC28 实现与验证](../docs/TSMC28Timing.md) | TT / 0.8 V / 85°C 的逻辑实现、等价检查与 SRAM 边界 |
| [TSMC28 测量摘要](tsmc28-results.json) | 绑定输入版本、配置和约束的机器可读结果 |
| [Vivado 工程](vivado/README.md) | VU13P 器件与时钟约束 |
| [EDA 脚本](../scripts/eda/README.md) | RTL 生成、综合与时序接口 |

RTL、网表、日志和完整报告写入 `build/eda/`。标准单元逻辑结果与包含 SRAM、
时钟树和布线寄生的物理实现结果使用不同的验证范围。
