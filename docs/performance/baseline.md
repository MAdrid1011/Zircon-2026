# IPC 与 PPA 基线

当前状态：尚未测量。该文件定义稳定报告字段，不预填推测数字。

## Zircon-2024 固定点

- Core SHA：`65a3dd381f4c83a5844858a927dafdbc8263c35e`
- 软件 SHA：`5f81f2ad378f537182e4cf1a0fcb45159509a2ec`
- 仿真器 SHA：`b1694da4a92046edeead50c9b2a1c086a13e6511`

## IPC 报告字段

每项 workload 记录 ELF SHA256、memory profile、seed、instruction、cycle、IPC、branch MPKI、L1I/L1D/L2 miss、平均 MSHR occupancy、AXI beat、MMIO merge 和 stall breakdown。

主比较使用 nominal profile：首 beat 30 cycles、0–15 cycles 确定性抖动、10% channel backpressure。fast/slow profile 只作敏感性分析。

## PPA 报告字段

Vivado 2026.1、`xc7a100tcsg324-1`、10 ns clock、debug/trace 关闭。记录 post-route LUT、FF、RAMB18 equivalent、DSP48E1、WNS、TNS、最大收敛频率、关键路径和分层资源。
