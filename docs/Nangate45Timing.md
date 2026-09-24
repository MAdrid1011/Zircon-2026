# Nangate45 逻辑时序评估

Zircon-2026 使用固定的 Nangate45 typical 工艺库和 BSG Fakeram 时序模型评估整核组合逻辑。
当前发布结果是 zero-interconnect logic-only STA：它回答映射后标准单元和 SRAM 宏本身能否满足
目标周期，不包含布局、时钟树、布线和寄生参数。

## 评估配置

| 项目 | 配置 |
| --- | --- |
| 顶层 | `ZirconCore` |
| 工艺角 | Nangate45 typical，1.10 V，25 C |
| 目标周期 | 1.0 ns |
| 输入驱动 / 输出负载 | `BUF_X1` / 4 fF |
| SRAM | 固定版本 BSG Fakeram，全部使用上升沿同步接口 |
| L2 配置 | 4 路、16 set、64 B line |
| 综合 | Yosys 0.68 + ABC，Nangate45 标准单元映射 |
| STA | OpenSTA，理想时钟，零互连寄生 |

综合入口使用 `Elaborate --bsg`，不会传入 `--simulation`。退休观测、性能计数器和其他仿真
调试接口不会出现在综合顶层；脚本还会在映射前检查生成 RTL，防止仿真接口意外进入网表。

Cache、BTB 和 Predictor 阵列均保留为 SRAM 宏。原生单读写阵列映射到对应深度的 BSG
Fakeram；Cache 使用的 `1RW+1R` 接口采用同深度 BSG 模型派生的双读口时序抽象。普通仿真和
Vivado 生成不使用这套 ASIC 绑定，默认 L2 仍为 32 set。

## 流程

1. Chisel 生成不含仿真观测逻辑的 BSG 配置 SystemVerilog。
2. 内存绑定器生成精确宽度的宏 wrapper，并校验所有 SRAM 实例在映射后仍被保留。
3. Yosys 对 BLevel 加法器执行结构化映射，再由 ABC 完成其余组合逻辑和寄存器映射。
4. OpenSTA 使用同一组 Liberty、时钟和 I/O 约束运行 zero-interconnect setup 分析。
5. `results.json` 记录工具版本、输入配置、面积、运行时间和完整报告位置。

该流程不设置 false path、multicycle path 或放宽后的 I/O 约束。Yosys 的三次结构检查必须全部
为零，且网表中不得残留未映射内部单元。

## 当前结果

当前整核结果如下：

| 指标 | 结果 |
| --- | ---: |
| 最长数据到达时间 | 1.891587 ns |
| 1.0 ns WNS | -0.941587 ns |
| 1.0 ns TNS | -37900.574219 ns |
| 1.0 ns 违例端点 | 88423 |
| 映射单元数 | 801785 |
| 触发器单元数 | 112057 |
| 估算面积 | 2061474.996 um2 |
| SRAM 宏实例 | 184 |
| Yosys 检查问题 | 0 / 0 / 0 |
| 展开 / 综合 / STA | 9.16 s / 514.45 s / 25.33 s |

最长路径终止于 Frontend ICache 的 SRAM 地址输入。当前纯逻辑最长延迟已经低于 2 ns，但
当前逻辑映射的最大逻辑频率约为 528.7 MHz。面积包含 BSG Fakeram 的模型面积，适合在相同配置下做相对比较，不应作为
流片面积声明。

## 结果边界

Logic-only STA 是物理实现前的逻辑下界，不是布局布线结果，也不是 signoff：

- 时钟网络为理想网络，没有 CTS 插入延迟或偏斜；
- 组合网络没有 placement、routing 或提取寄生；
- SRAM 使用研究用途的 Fakeram 时序与面积模型；
- Nangate45 平台用于研究和 EDA 探索，不代表可制造工艺签核。

因此，1.891587 ns 表示当前映射网表的纯逻辑数据到达时间。实际布局布线只能在此基础上增加
互连和时钟代价；需要物理频率结论时，应使用完整 PDK、宏视图、CTS、布线和提取后的 STA。

## 复现

在仓库根目录运行：

```sh
python3 scripts/eda/synthesize_core.py --logic-only --target-ns 1.0
```

默认输出位于：

```text
build/nangate45-core/rtl/
build/nangate45-core/target-1ns/results.json
build/nangate45-core/target-1ns/logic-only/
```

`results.json` 是机器可读摘要；`logic-only-worst-paths.rpt`、
`logic-only-violating-endpoints.rpt` 和 `logic-only-violating-paths-summary.rpt` 分别保存详细路径、
完整违例端点和聚类摘要。平台文件与输入来源见 [Nangate45 平台说明](../eda/platforms/nangate45/README.md)，
脚本接口见 [synthesize_core.py](../scripts/eda/synthesize_core.md)。
