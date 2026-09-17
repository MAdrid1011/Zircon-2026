# OpenRAM 1RW+1R 评估模型

本目录保存 FreePDK45 的 1RW+1R SRAM 评估模型，用于与 Nangate45 标准单元共同综合、分析面积、时序和功耗。两个模型已接入 DCache，并通过三后端功能及替换回归；完整结果见 [DCache OpenRAM 评估](../../../../../docs/DCache-OpenRAM-Evaluation.md)。Vivado 后端继续使用 Zircon-2024 的真双端口 BRAM 模板。

## 模型与来源

模型由 [OpenRAM](https://github.com/VLSIDA/OpenRAM/tree/b2b069ce119d1488cbe6883b2240bceb5c7ce29a) v1.2.49、提交 `b2b069ce119d1488cbe6883b2240bceb5c7ce29a` 的未修改源码生成。工艺条件为 FreePDK45 TT、1.1 V、25°C，与项目 Nangate45 typical 库的电压和温度一致。[manifest.json](manifest.json) 记录配置、来源和文件摘要。

| 宏 | 深度 × 位宽 | 写掩码粒度 | Liberty / LEF 面积 |
| --- | --- | --- | --- |
| `openram45_1rw1r_16x25` | 16 × 25 bit | 整字 | 6,999.189375 µm² |
| `openram45_1rw1r_16x32` | 16 × 32 bit | 8 bit，共四个掩码 | 9,569.3470125 µm² |

面积来自生成布局的包围框，并与 LEF 的 `SIZE` 相乘核对。时延、setup/hold 和周期约束来自 OpenRAM 的解析模型，未运行 SPICE 表征、DRC、LVS、PEX 或电源布线。文件能够被工具读取不代表已经通过物理实现验证；本目录模型不用于流片签核。

每个宏同时提供 `.v` 行为模型、`.lib` 时序与面积模型、`.lef` 几何视图和 `configs/` 下的生成参数。生成器及工艺相关许可原文分别保存在 `LICENSE.OpenRAM` 和 `LICENSE.FreePDK45`。GDS、SPICE 网表和其他过程文件保留在 `build/dcache-ram-probe/`，不作为当前静态评估输入。

## 端口与时序

`clk0/csb0/web0/addr0/din0/dout0` 构成读写端口；`clk1/csb1/addr1/dout1` 构成只读端口。`csb`、`web` 均低有效，数据宏的 `wmask0[3:0]` 高有效。两个时钟可以连接到同一个系统时钟；RW 端口读模式与 R 端口可以同拍读取不同地址，也可以读取相同地址。

行为模型在上升沿采样请求，下降沿执行读写，读出经过 `DELAY` 后更新。`DELAY=3` 和 `T_HOLD=1` 是上游明确标注的任意仿真参数，不能作为物理时延。Liberty 的输入约束参考上升沿，两个读出时序弧参考下降沿；连接上升沿接收寄存器时，OpenSTA 必须保留半周期的时间预算。

模型每次上升沿后将读出置为未知，不能依赖关闭端口后保持有效数据。它与 `DualPortMaskedRam` 的 Registers/Vivado 后端的寄存地址、保持地址处实时输出行为不同。DCache 已通过启用延时事件的 Verilator 回归，验证现有 S2 重读与 S3 快照适配该行为；原模型未修改，适配层将任意仿真 `DELAY/T_HOLD` 设为 0.1 ns 以适配单测时钟步长，物理时延仍取 Liberty。测试避开同地址跨端口读写冲突，不定义该情况下返回旧值还是新值。

Vivado 的 [Simple Dual Port 模板](https://docs.amd.com/r/en-US/ug901-vivado-synthesis/Simple-Dual-Port-Block-RAM-with-Single-Clock-Verilog) 是 1W+1R，只有一个读端口，不能满足双 load。Zircon-2024 的 Xilinx 目录只有一个单端口模板和两个真双端口模板；现有真双端口模板能够承载受限为 1RW+1R 的访问。

## 独立验证

从仓库根目录运行：

```sh
python3 scripts/eda/check_openram_1rw1r.py
```

脚本使用 PATH 中的 `iverilog`、`vvp`、`yowasp-yosys` 和项目现有 `build/prf-tools/sta-build/sta`。输入模型和标准单元库均先校验 SHA-256；输出位于 `build/openram-1rw1r-probe/`。本次保存的结果见 [verification/results.json](verification/results.json)。

行为仿真共 1,552 拍，覆盖所有行、每行全部 16 种字节掩码、相同及不同地址双读、RW 写入并行 R 读取、独立端口关闭和固定种子的随机访问。统计为 1,535 次读、520 次写、394 拍双读、371 拍并行读写；每次读均同时检查 Tag 和 Data 模型。

综合测试电路由两个 SRAM 宏、输出异或逻辑和输出寄存器组成。映射后保留两个宏、114 个 `XOR2_X1` 和 114 个 `DFF_X1`，没有将 SRAM 阵列展开成触发器。时序检查使用 10 ns 同源时钟、50 ps 时钟转换时间；OpenSTA 能追踪两个宏各自的两个读口到输出寄存器，并检查周期、脉宽、负载和转换时间约束。历史探测脚本的 `set_load 0.004` 在 Nangate45 的 fF 单位下实际为 0.004 fF，原先称为 4 fF 有误；当前探测脚本与完整 DCache 评估均已改为正确的 `set_load 4`。独立探测已重新通过，见 [4 fF 复核](verification/recheck-4ff-results.json)；历史独立记录保留，不作为完整 DCache 的面积或频率结果。

## DCache 接入范围

当前 Tag 每路使用一块 16×25 宏。Data 每路的 16×256 数组由八块 16×32 宏按位宽拼接，共享地址、使能和时钟，各自接收四个字节掩码。这保持每块存储器最多两个端口，不增加访问并行度。完整拼接已经过宏实例数量检查及 DCache 功能回归。

当前 refill 和已提交 store 统一写 Data B，Data A 为只读；Tag 仍是 A 读写、B 只读。原有安装与写槽互斥约束保持，refill、store、双 load、取消、背压及替换均通过回归，三个后端每拍两条 load 的命中吞吐和三拍响应延迟一致。流水级与独立 `miss` 寄存器保持原设计。

八块数据宏拼接会重复译码、控制及读写外围电路。按两路 Tag 和两路 Data 直接相加，候选宏面积为 167,107.93095 µm²，尚不包含 DCache 控制逻辑和连接开销；不能据此声称优于已有寄存器实现。后续应比较更宽的宏及其他组织方式后，再确定正式评估模型。

功耗同样来自解析模型。固定版本的 `characterizer/elmore.py` 给读、写和 disabled 状态填写相同的动态值，`characterizer/lib.py` 将其写入时钟引脚的内部功耗表；这批表不能反映关闭 SRAM 使能后的节能效果，也没有 SPICE 校准的每次访问能量。当前 OpenSTA 结果只用于统一活动率下的模型敏感性分析，不能作为程序功耗、空闲功耗或流片功耗承诺。

16×256 的首次完整布局生成在 180 秒的探测时限内未完成，没有产出可用模型；这不代表该规格无法生成。生成时还发现上游 `only_use_config_corners=True` 分支存在 `nom_corner` 未定义的问题，本目录使用上游已有的 `use_specified_corners` 选项指定同一工艺角，没有修改生成器源码。负载与转换时间采样范围已扩展以覆盖项目评估条件。

## 重新生成

先获取上述固定提交的 OpenRAM 源码并配置其 Python 依赖，再从仓库根目录运行以下命令。配置关闭 Nix 自动安装、物理检查和 SPICE 表征，使用解析时延模型。

```sh
OPENRAM_HOME=/path/to/OpenRAM/compiler OPENRAM_TECH=/path/to/OpenRAM/technology \
    python3 /path/to/OpenRAM/sram_compiler.py \
    eda/platforms/nangate45/memory/openram-1rw1r/configs/openram45_1rw1r_16x25.py
OPENRAM_HOME=/path/to/OpenRAM/compiler OPENRAM_TECH=/path/to/OpenRAM/technology \
    python3 /path/to/OpenRAM/sram_compiler.py \
    eda/platforms/nangate45/memory/openram-1rw1r/configs/openram45_1rw1r_16x32.py
```

生成结果位于 `build/openram-generated/`。替换本目录模型前应检查生成日志、重新运行独立验证并更新来源摘要，不能只改 Liberty 中的面积或时延数值。
