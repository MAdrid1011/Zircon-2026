# IPC 与静态面积基线

当前状态：正式数据尚未测量。固定提交的可运行性和非正式 smoke 已记录在
[`zircon-2024-reproduction.md`](zircon-2024-reproduction.md)，静态面积预算见
[`zircon-2024-static-inventory.md`](zircon-2024-static-inventory.md)。

## Zircon-2024 固定点

- Core SHA：`65a3dd381f4c83a5844858a927dafdbc8263c35e`
- 软件 SHA：`5f81f2ad378f537182e4cf1a0fcb45159509a2ec`
- 仿真器 SHA：`b1694da4a92046edeead50c9b2a1c086a13e6511`

## IPC 报告字段

每项 workload 记录 ELF SHA256、memory profile、seed、instruction、cycle、IPC、branch MPKI、L1I/L1D/L2 miss、平均 MSHR occupancy、AXI beat、MMIO merge 和 stall breakdown。

主比较使用 nominal profile：首 beat 30 cycles、0–15 cycles 确定性抖动、10% channel backpressure。fast/slow profile 只作敏感性分析。

## 静态面积报告字段

按 [ADR-0009](../decisions/0009-static-area-acceptance.md) 对两个固定配置使用同一脚本，
记录 register-backed bit、memory-macro candidate bit、端口复制后的 storage bit、CAM
compare-bit product、mux input-bit product、主要算术/迭代引擎数量和分层资源明细。
debug/trace 在正式配置关闭，验证接口不进入账本。

报告必须区分“精确源码/生成 RTL 位数”和“实现相关的保守代理”，不得把后者写成
LUT、FF、BRAM、频率或功耗。Vivado/Yosys 报告只作为可选旁证，不是发布门槛。

当前可执行入口为：

```sh
make static-area-check
make static-area
```

前一命令验证 manifest/schema 和计算器单元测试；后一命令输出 Markdown 对照。任一
manifest 为 `partial` 时报告必须显示 `PARTIAL`，缺失结构不得按零面积计入优势。
