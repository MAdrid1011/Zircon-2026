# TSMC28 标准单元优化接口

`resize_tsmc28.py` 接收 `synthesize_tsmc28.py` 的输出目录，执行标准单元驱动强度调整、
等价输入交换和组合门复制。输出网表保持触发器、流水周期及 SRAM 边界，通过独立
OpenSTA 和完整边界等价后生成 `results.json`。

## 命令行

```sh
python3 scripts/eda/resize_tsmc28.py \
  --synthesis build/eda/tsmc28/system --output build/eda/tsmc28/system-sized
```

`--synthesis` 指定包含配置、库摘要、映射网表和 SRAM 黑盒的综合结果。
`--output` 必须是仓库内的新目录；已有 manifest 的结果不覆盖。
`--yosys`、`--sta` 指定工具路径，默认与综合入口一致。
`--image` 指定 OpenROAD Docker 镜像，默认 `openroad/flow-ubuntu22.04-builder:latest`，
运行时记录实际 image ID，关闭容器网络并限制为 2 CPU。

已有完整网表可用 `--verify-netlist path/to/complete.v` 复核，直接运行独立 STA 与等价检查，
无需再次调整单元。该网表必须保持参考综合结果的命名寄存器状态和 SRAM 实例接口；
工具重新检查其与本次参考网表的全部边界行为，不依赖先前证明。

`--candidate path/to/raw.v` 接收省略空 SRAM 层次的 flat 导出，在检查实例数后补回参考
SRAM 连接及常量输出，再执行同样的完整证明。它与 `--verify-netlist` 互斥，
不能接收已经包含 SRAM 实例的网表；补回连接本身不构成功能等价结论。

## 优化与验证

真实 TIEH/TIEL 保持常量连接，pin-only SRAM Liberty 保持内存实例。
三阶段依次调整尺寸/输入脚、复制组合门、收敛 20 ps 目标裕量；阶段目标不是通过条件，
最终是否满足周期以独立 OpenSTA 结果为准。

从 Liberty 生成的 LEF 只提供 resizer API 所需的非物理占位，不是 PDK 几何。
不执行 floorplan、placement、routing 或 `estimate_parasitics`。
`set_wire_rc` 的微小值仅满足长度查询 API，最终 STA 不读取该 RC 或占位视图。

寄存器按命名信号及正相缓冲别名对应；相同当前状态与内存读值为任意输入，
比较所有顶层输出、寄存器下一状态和内存输入。证明范围、无负载内存输出例外、
分区与缓存规则由[门级等价接口](gate_equivalence.md)定义。
任何内部连接缺失、状态或接口不匹配、SAT 失败都会拒绝该结果。

输出保留输入与工具摘要、`ZirconCore-sized.v`、`sta/` 和 `equivalence/`。
`standard_cell_target_met` 要求目标 slack 非负且电气违例为零。
所有结果仅覆盖理想时钟、零互连寄生的标准单元逻辑；SRAM 没有时序弧，
`full_core_target_met` 保持 `null`。
