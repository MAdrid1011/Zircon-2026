# EDA 接口测试

`test_timing_flow.py` 检查加法器映射命令、目标周期换算、BSG SRAM 绑定、等价检查边界、
OpenROAD 调用参数，以及电气和时序报告的解析规则。测试同时核对纯逻辑报告中的数据到达
时间、违例端点和存储宏路径。

TSMC28 回归检查 36-bit residual 加法器的结构化映射边界、CP/D/Q 触发器、SRAM 边界、以下划线开头的显式状态别名、裁剪总线
的位号，以及 OpenROAD 导出后必须保留的 SRAM 实例和常量输出；不允许将常量当作 don't-care。

在仓库根目录运行：

```sh
PYTHONPATH=.:scripts/eda python3 -m unittest scripts.eda.test_timing_flow
```
