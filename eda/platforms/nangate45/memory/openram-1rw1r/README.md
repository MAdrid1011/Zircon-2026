# OpenRAM 1RW+1R 参考模型

本目录保存 FreePDK45 的 1RW+1R SRAM 参考资产，用于独立验证 OpenRAM 行为模型、Liberty 和
LEF 的工具兼容性。它不进入 Zircon-2026 当前整核综合或 STA；活动 Cache、BTB 和 Predictor
SRAM 均由上一级目录的 BSG Fakeram 模型提供。

## 来源与文件

模型由 [OpenRAM](https://github.com/VLSIDA/OpenRAM/tree/b2b069ce119d1488cbe6883b2240bceb5c7ce29a)
v1.2.49 的未修改源码生成，工艺条件为 FreePDK45 TT、1.1 V、25 C。`manifest.json` 是模型
集合、生成配置、面积和 SHA-256 的权威清单。

每个模型包含：

- `.v`：功能行为模型；
- `.lib`：解析时延、约束、面积和功耗表；
- `.lef`：宏几何与引脚视图；
- `configs/`：可复现的 OpenRAM 生成参数。

OpenRAM 和 FreePDK45 的许可原文分别保存在 `LICENSE.OpenRAM` 与
`LICENSE.FreePDK45`。

## 端口与时序契约

`clk0/csb0/web0/addr0/din0/dout0` 构成读写端口，
`clk1/csb1/addr1/dout1` 构成只读端口。`csb` 与 `web` 低有效；带写掩码的模型使用
高有效 `wmask0`。

行为模型在上升沿采样请求、下降沿执行读写，并在 `DELAY` 后更新输出。`DELAY` 和
`T_HOLD` 是功能仿真的任意参数，物理分析必须使用 Liberty。Liberty 的输入约束参考上升沿，
读出时序弧参考下降沿，因此连接上升沿接收寄存器时只有半周期预算。

关闭端口后不能依赖输出保持。同地址跨端口读写的返回值也不作为项目接口契约，使用者必须在
外层仲裁或前递。

## 独立验证

从仓库根目录运行：

```sh
python3 scripts/eda/check_openram_1rw1r.py
```

检查器先验证 manifest 中的文件摘要，再运行读写与掩码行为测试、Yosys 宏保留检查和 OpenSTA
时序探测。测试使用 4 fF 输出负载，生成结果写入 `build/openram-1rw1r-probe/`。仓库中的
`verification/results.json` 和 `verification/recheck-4ff-results.json` 是固定输入下的
参考结果，不代表整核面积或频率。

## 精度边界

这些模型使用 OpenRAM 解析表征，没有执行 SPICE 表征、DRC、LVS、PEX 或电源布线。解析功耗表
也不用于声明程序功耗或空闲功耗。它们适合接口和工具链验证，不是可制造 SRAM 或流片签核数据。

重新生成时应使用 manifest 记录的 OpenRAM 提交和 `configs/` 参数，并在替换任何模型后重新
运行独立验证、核对 LEF 尺寸并更新文件摘要。
