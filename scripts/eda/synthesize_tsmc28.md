# TSMC28 标准单元逻辑初筛

从当前源码生成 `system` 或 `performance` 配置，以外部 TSMC 28nm HPC+ Liberty
执行 Yosys/ABC 映射及 OpenSTA 分析。默认目标为 1.0 ns（1 GHz），库为 TT、0.8 V、85°C。系统核默认使用
32-set / 8 KiB L2，与普通仿真和 Dandelion 主控一致；不沿用旧 Nangate45 的 4 KiB 缩减配置。

```sh
python3 scripts/eda/synthesize_tsmc28.py --profile system --unmap-enables
python3 scripts/eda/synthesize_tsmc28.py --profile performance --scratchpad --unmap-enables
```

默认使用 `build/tools/yosys-orfs-0.68/bin/yosys` 和
`build/tools/OpenSTA-src/build/sta`，可通过 `--yosys`、`--sta` 指定工具。
`--system-l2-sets` 可显式选择系统核 L2 set 数，`--scratchpad` 包含 Dandelion PE
实际使用的 scratchpad 接口（只适用 performance）。不带 scratchpad 的性能核对应独立
CoreMark-PRO 配置；两种配置必须分别标注。
`--liberty` 指定映射库，`--sta-liberty` 可重复使用，在其他工艺角复查同一网表。
`--mapping-delay-ps` 独立指定 ABC 组合逻辑延迟目标（默认是周期的 ps 值），
可为 clock-to-Q 和 setup 留出余量；它不改变 STA 时钟周期。
`--target-ns` 指定周期，`--output` 指定输出目录，`--skip-elaboration` 复用输出目录的 RTL；必须有匹配的 `configuration.json`，
以免把旧 4 KiB/无 scratchpad 的 RTL 错记为实际配置。
`--skip-mapping --skip-elaboration` 复查已有网表，适合补跑其他工艺角；应保持原始输入文件不变。
`--unmap-enables` 在触发器映射前将时钟使能和同步复位展开为 D 端 mux，让 ABC
同时优化控制网络；可用于比较使能脚负载导致的时序问题。
`--logic-mapping direct` 使用 ABC 的直接映射流程保留输入逻辑结构，用于和默认重写流程比较。
两种流程都记录实际脚本和库哈希，是否达标以 OpenSTA 报告为准。
Liberty 必须属于含 `BUFFD1BWP40P140` 的上述库系列；工艺文件保持在 `/opt/eda`，不复制进仓库。

默认输出为 `build/eda/tsmc28/<profile>/`，包含 RTL、仅有端口声明的 SRAM 黑盒、
映射脚本、网表、输入摘要以及 `results.json`。每个分析库有独立的报告目录，记录
setup、hold、电气检查、未约束边界和寄存器之间的详细路径。

本流程保留原有 BSG 配置的 SRAM 接口，以及 BLevel 加法器映射策略。Cache、预测器和
单端口阵列替换为无时序弧的黑盒，**不使用 Nangate45 SRAM 时序或面积**。
SRAM 内部、SRAM 到寄存器及寄存器到 SRAM 的路径不参与频率结论，标准单元面积也不含 SRAM。
其他寄存器阵列继续映射为标准单元。

STA 使用理想时钟、零互连寄生、零时钟不确定度、零 I/O 延迟和 4 fF 输出负载，
不做布局、时钟树或电气修复。`reg_to_reg_target_met` 仅表示所报告寄存器路径是否满足目标；
`full_core_target_met` 始终为 `null`。等效周期为目标周期减最差 slack，仅用于评估该网表。
整核 1 GHz 收敛仍需匹配的 TSMC SRAM 绑定、宏时序与功能验证，以及物理实现和多角分析。
