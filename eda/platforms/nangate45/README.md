# Nangate45 静态评估平台

本目录保存 Zircon-2026 共用的 Nangate45 典型角 Liberty、标准单元 LEF、RC 数据和 SRAM 宏物理视图，供 Yosys/ABC 映射与 OpenROAD Resizer 做布局估算和电气修复。

## 文件与来源

库文件位于 [lib/NangateOpenCellLibrary_typical.lib](lib/NangateOpenCellLibrary_typical.lib)，内容与首次 ALU 评估时使用的文件一致。固定来源为 [OpenROAD-flow-scripts 的 Nangate45 目录](https://github.com/The-OpenROAD-Project/OpenROAD-flow-scripts/tree/31e744df6a12ae09760820dc977d2b2488c9b0fe/flow/platforms/nangate45)。

[platform.json](platform.json) 记录下载 URL、提交号、文件 SHA-256，以及 typical、1.10 V、25°C 的工艺条件。活动平台只声明 Nangate45 标准单元和 BSG Fakeram 宏视图；精确宽度的 `1RW+1R` logic-only 视图由综合流程从固定版本 BSG Fakeram 时序模型生成。文件路径相对于该 JSON 所在目录解析，综合与布局估算使用的输入作为仓库数据保留，不放入构建输出目录。

[LICENSE](LICENSE) 和 [UPSTREAM.md](UPSTREAM.md) 原样取自同一上游提交。Liberty 文件内的原始版权声明完整保留。上游将 Nangate Open Cell Library 定义为用于研究、测试和探索 EDA 流程的通用参考库，并明确说明其不用于制造。

## 使用与更新

综合脚本读取 `platform.json` 并校验库摘要后，将数据传入 Yosys/ABC。物理修复优先使用镜像 `openroad/orfs@sha256:0f74b1bb4e3d7d290e0a0b989839cfdeebe85feca1df236a60f4087124827637`；设置 `OPENROAD_BIN` 可改用本机二进制。输入驱动、输出负载和 RC 层从固定平台配置读取。

该流程使用 placement-based RC 估算和标准 Resizer 电气修复，不运行时钟树综合或详细布线，也不等同于 signoff。若需要新工艺角，应另存库文件及其元数据，并重新评估基线，避免覆盖已有比较条件。

完整默认评估约定见 [eda/README.md](../../README.md)。
