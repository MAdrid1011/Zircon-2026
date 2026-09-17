# Nangate45 静态评估平台

本目录保存 Zircon-2026 共用的 Nangate45 典型角标准单元库，供 Yosys/ABC 进行单元映射、面积统计和布局布线前的延迟估计。

## 文件与来源

库文件位于 [lib/NangateOpenCellLibrary_typical.lib](lib/NangateOpenCellLibrary_typical.lib)，内容与首次 ALU 评估时使用的文件一致。固定来源为 [OpenROAD-flow-scripts 的 Nangate45 目录](https://github.com/The-OpenROAD-Project/OpenROAD-flow-scripts/tree/31e744df6a12ae09760820dc977d2b2488c9b0fe/flow/platforms/nangate45)。

[platform.json](platform.json) 记录下载 URL、提交号、库文件 SHA-256，以及 typical、1.10 V、25°C 的工艺条件。文件路径相对于该 JSON 所在目录解析。库及其说明作为仓库输入保留，不放入构建输出目录。

[LICENSE](LICENSE) 和 [UPSTREAM.md](UPSTREAM.md) 原样取自同一上游提交。Liberty 文件内的原始版权声明完整保留。上游将 Nangate Open Cell Library 定义为用于研究、测试和探索 EDA 流程的通用参考库，并明确说明其不用于制造。

## 使用与更新

评估脚本读取 `platform.json` 并校验库摘要后，再将本地路径传入 `read_liberty`、`abc -liberty` 和 `stat -liberty`。默认输入驱动与输出负载也从该文件读取。

本目录只保存当前使用的 Liberty 数据；LEF、GDS、RC 模型和其他工艺角尚未导入。后续如果需要新工艺角，应另存库文件及其元数据，并重新评估基线，避免覆盖已有比较条件。

完整默认评估约定见 [eda/README.md](../../README.md)。
