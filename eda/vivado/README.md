# Vivado VU13P 核心工程

该工程在 SCARF Stage-B 使用的 `xcvu13p-fhgb2104-2-i` 器件上实现独立的
`ZirconCore`，时钟约束为 100 MHz，存储阵列采用 Xilinx BRAM。运行前需要将
Vivado 2025.2 和 `sbt` 加入 `PATH`。

在仓库根目录生成 RTL，并创建工程、执行综合与布线：

```sh
python3 scripts/eda/prepare_vivado_rtl.py
vivado -mode batch -source eda/vivado/create_project.tcl -nolog -nojournal
```

仅创建工程时使用：

```sh
vivado -mode batch -source eda/vivado/create_project.tcl -nolog -nojournal -tclargs --create-only
```

工程脚本读取 `build/eda/vivado-vu13p/rtl/filelist.f`，并加入 Xilinx BRAM 模板。
`ZirconCore.xpr`、生成的 SystemVerilog、检查点和报告均位于
`build/eda/vivado-vu13p/`。仓库保存的是本目录中的 Tcl 与 100 MHz XDC，克隆后可重建工程。

此 XDC 只约束核心时钟。PCIe、DDR4 和管脚约束由板级 SoC wrapper 工程提供，其接口与
独立 `ZirconCore` 顶层不同。

## 性能配置

SIMD8 性能核心使用相同器件、100 MHz 时钟与默认综合实现策略，DSP 使用上限为零：

```sh
python3 scripts/eda/prepare_vivado_rtl.py --profile performance
vivado -mode batch -source eda/vivado/create_project.tcl -nolog -nojournal \
  -tclargs --profile=performance
```

RTL 与工程输出位于 `build/eda/vivado-vu13p-performance/`，与默认系统核心分开。
存储阵列使用 Xilinx BRAM 模板，ASIC 路径的 BSG 绑定由独立生成入口选择。

RTL 版本 `c5bfd68` 的性能配置在 100 MHz 布线后达到 setup WNS +1.084 ns、
TNS 0，hold WHS +0.010 ns、THS 0，setup/hold 均无违例端点。
资源用量为 237700 LUT、111780 FF、135.5 BRAM、0 DSP，LUT 占器件的 13.76%。
