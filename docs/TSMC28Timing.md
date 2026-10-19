# TSMC28 标准单元实现与验证

Zircon 的 TSMC28 入口将系统核或性能核映射到外部 28nm HPC+ 标准单元库，
保留 SRAM 的同步接口边界。它用于检查标准单元逻辑在指定工艺角下的时序、面积与
门级行为；SRAM 宏绑定和物理实现需要独立的工艺视图。

## 配置与时序模型

| 项目 | 配置 |
| --- | --- |
| 顶层 | `ZirconCore`，不含仿真观测接口 |
| 标准单元 | `tcbn28hpcplusbwp40p140`，TT / 0.8 V / 85°C |
| 系统核 | `system`，32-set / 8 KiB L2，64-bit AXI |
| 性能核 | `performance`，16 KiB DCache、32 KiB L2、256-bit AXI；可启用 scratchpad |
| STA 周期 | 1.0 ns；ABC 组合逻辑目标可独立指定 |
| 时钟与互连 | 理想时钟、零互连寄生、零时钟不确定度 |
| I/O | 零输入输出延迟、4 fF 输出负载 |
| SRAM | 保留实例与引脚，使用无时序弧的黑盒 |

系统核的 L2 容量由显式 `--l2-sets=32` 保证，与默认系统配置一致。
性能核的 scratchpad 是独立集成接口；带与不带该接口的结果需要分别标注。
[核心配置](Core.md)和[性能核心接口](PerformanceCore.md#sram-集成接口)定义功能与集成约束。

标准单元面积为已映射逻辑单元的库面积之和，不包含 SRAM。等效周期为 STA 时钟周期
减去最差寄存器路径 slack，包含 clock-to-Q、组合逻辑与 setup。
`standard_cell_target_met` 只在寄存器路径满足目标且电气违例为零时成立；
`full_core_target_met` 保持 `null`，不据此声明整核或布线后的工作频率。

## 逻辑结构与周期约束

实现保持[后端](Backend.md)、[提交](Commit.md)和[性能核心](PerformanceCore.md)
定义的寄存器边界、握手与执行拍数：

- LS0/LS1 的操作分类由 Dispatch 和 typed IssueQueue 保证。Load 接收许可只计算
  本地容量、阻塞与 RF 交接；接受事件断言操作路由合法，LS0 不执行 Store。
- `DivSqrtSRT4` 的 36-bit residual 和 24-bit significand 舍入分别使用已有的
  36-bit、32-bit 分组进位加法器。ASIC 映射保留这些结构边界。
- `VectorSerial` 在既有算术寄存边界保存平均值缩放结果与舍入位。Gather 依据
  VLMAX 为零或二的幂计算索引掩码，保留 32-bit slide 与索引回绕语义。
- `VectorMemory` 在 `collect` 边界形成分组偏移：三项移位部分积经 carry-save
  压缩后进入 32-bit 分组加法器。元素地址累加保持模 2^32 运算。
- `VectorStoreBuffer` 在元素地址读取边界同时保存当前与后继 Cache Line，
  后继行号按 26 bit 回绕；第二段选择使用寄存值，数据、掩码和顺序随同一元素推进。
- `Commit` 的向量中断入口使用 32-bit 分组加法器生成基址加 cause 偏移，
  direct/vectored 模式选择在既有恢复目标寄存器之前完成。

## 综合、单元优化与证明

```sh
python3 scripts/eda/synthesize_tsmc28.py --profile system \
  --unmap-enables --mapping-delay-ps 500 --output build/eda/tsmc28/system
python3 scripts/eda/resize_tsmc28.py \
  --synthesis build/eda/tsmc28/system --output build/eda/tsmc28/system-sized

python3 scripts/eda/synthesize_tsmc28.py --profile performance --scratchpad \
  --unmap-enables --mapping-delay-ps 500 --output build/eda/tsmc28/performance
python3 scripts/eda/resize_tsmc28.py \
  --synthesis build/eda/tsmc28/performance --output build/eda/tsmc28/performance-sized
```

综合使用 Yosys/ABC，STA 使用 OpenSTA。标准单元优化使用 OpenROAD 的尺寸调整、
等价输入交换和组合门复制，使用真实 TIEH/TIEL 保持常量连接。
SRAM pin-only Liberty 保持实例与接口；为调用 resizer 生成的 LEF 仅是非物理占位，
不提供放置、布线或寄生估算依据。工艺库由外部路径提供，不进入仓库。

最终网表由独立 OpenSTA 分析，并通过分区 SAT 比较顶层输出、全部命名寄存器下一状态
和每个 SRAM 输入。两侧对应的当前寄存器状态与 SRAM 读值取任意相同值。
该证明检查标准单元优化保持边界行为；它不替代 RTL 功能验证、复位可达性证明或 SRAM 验证。
只有原端口无连接且另一侧对应位也没有负载时，才允许忽略 writer 产生的空输出线。

脚本参数、工具路径和输出契约见
[`synthesize_tsmc28.py`](../scripts/eda/synthesize_tsmc28.md)、
[`resize_tsmc28.py`](../scripts/eda/resize_tsmc28.md)与
[门级等价接口](../scripts/eda/gate_equivalence.md)。输出目录保存配置、源码/RTL/库/网表摘要、
时序报告和证明范围。正式测量摘要单独保存在 [`eda/tsmc28-results.json`](../eda/tsmc28-results.json)。

## 功能与性能验收

模块验证覆盖 Load 路由、除法/平方根舍入、向量算术与索引、向量访存、跨行 Store、
32-bit 地址回绕、反压与取消。测试入口采用 Verilator：

```sh
sbt --batch 'testOnly SharedDivSqrtSpec VectorSerialSpec VectorMemorySpec LoadPipelineSpec DualLoadPipelineSpec VectorStoreBufferSpec VectorStoreCoalesceSpec CSRSpec'
PYTHONPATH=.:scripts/eda python3 -m unittest scripts.eda.test_timing_flow
```

性能保持按同一配置与同一完整 ELF 的周期数、指令数、CRC 和 Spike 差分检查，
普通 CoreMark 覆盖两核，FP32 CoreMark-PRO 覆盖性能核的
`linear_alg-mid-100x100-sp`、`loops-all-mid-10k-sp`。未完成的运行不能计作通过。
Dandelion 集成还需检查完整 64 PE GEMM，固定输入、程序与迭代次数；全程利用率定义为
`M*K*N / (64*8*execution_cycles)`，执行终点包含结果写入的 AXI B。
`256×256×256` 与 `256×512×256` 的验收门槛均为 90%，不使用局部活动窗口替代全程。

## SRAM 与物理实现

整核时序关闭需要同一电压温度下、实际尺寸的 SRAM 时序与物理视图，包括接口 setup/hold、
clock-to-output 以及读写周期，并接入标准单元/宏 LEF、布线 RC、时钟树与真实接口约束。
混合电源 `tt0p8v0p9v85c` 宏不代表全电源 `tt0p8v85c`。
这些条件未满足时，标准单元通过 1 ns 只构成逻辑实现证据。
