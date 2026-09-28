# EDA 接口测试

`test_timing_flow.py` 检查加法器映射命令、目标周期换算、BSG SRAM 绑定、等价检查边界、
OpenROAD 调用参数，以及电气和时序报告的解析规则。测试同时核对纯逻辑报告中的数据到达
时间、违例端点和存储宏路径。

在仓库根目录运行：

```sh
PYTHONPATH=.:scripts/eda python3 -m unittest scripts.eda.test_timing_flow
```
