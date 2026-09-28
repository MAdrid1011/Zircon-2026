# Vivado RTL 生成入口

在仓库根目录执行：

```sh
python3 scripts/eda/prepare_vivado_rtl.py
```

脚本生成 `build/eda/vivado-vu13p/rtl/`，选择 Xilinx BRAM 后端和适合 FPGA 进位链的
32/36 位原生加法，并加入三个 Verilog RAM 模板。完成后检查 RTL 文件清单、RAM 封装
和加法器实现。`--output DIR` 可指定单独的 RTL 输出目录。

默认目录可由 [Vivado 工程脚本](../../eda/vivado/README.md)直接读取；Nangate45 使用独立的
BSG Fakeram RTL 配置和输出目录。
