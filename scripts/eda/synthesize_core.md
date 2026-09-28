# Nangate45 综合入口

在仓库根目录运行纯逻辑时序评估：

```sh
python3 scripts/eda/synthesize_core.py --logic-only --target-ns 1.0 --sta-target-ns 1.5
```

默认输出位于 `build/eda/nangate45/`，包含 BSG 配置 RTL、SRAM 绑定、标准单元网表、
`results.json` 和时序报告。`--target-ns` 指定 Yosys/ABC 映射周期；`--sta-target-ns`
指定纯逻辑 STA 周期，仅与 `--logic-only` 同用。不带 `--logic-only` 时还会运行
OpenROAD 布局估算与修复。

`--output DIR` 指定输出目录，`--yosys PATH` 指定 Yosys，`--skip-elaboration` 复用该目录
已有 RTL。`--sweep` 评估平台配置中的周期集合。脚本检查库文件摘要、综合结构和 BSG
Fakeram 实例；目标结果保存在 `target-<周期>ns/`，汇总保存在 `sweep-results.json`。
