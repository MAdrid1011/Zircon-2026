# OpenROAD 物理评估接口

`openroad_resizer.py` 接收映射网表、目标周期、Nangate45 平台目录、Liberty 文件和输出目录，
执行宏与标准单元布局、RC 估算及电气和 setup 修复。默认使用固定摘要的 ORFS 容器；
`OPENROAD_BIN` 可指定本机 OpenROAD，`OPENROAD_THREADS` 可指定线程数。

整核入口为：

```sh
python3 scripts/eda/synthesize_core.py --target-ns 1.0
```

输出包括修复后的网表、OpenROAD 日志、电容与转换时间检查报告，以及时序报告。
`--placement-density` 调整布局密度，`--max-repairs-per-pass` 控制每轮 setup 修复数量。
脚本校验物理输入，并将平台文件与目标约束传给
[`openroad_resizer.tcl`](openroad_resizer.tcl.md)。
