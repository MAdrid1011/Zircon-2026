# Zircon-2024 基线复现记录

## 固定输入

- Core：`65a3dd381f4c83a5844858a927dafdbc8263c35e`
- RV-Software：`5f81f2ad378f537182e4cf1a0fcb45159509a2ec`
- ZirconSim：`b1694da4a92046edeead50c9b2a1c086a13e6511`
- 主机日期：2026-08-31
- 已验证工具：Java 21 可完成 elaboration；Verilator 5.050 可构建旧顶层；
  LLVM/Clang/LLD 22.1.8 可生成 `rv32im/ilp32` picotest。

三个提交在 `build/baseline/Zircon-2024` 的独立 clone 中复现。用户现有
`/Users/madrid/Code/Zircon-2024` 脏工作区没有被 checkout、stash 或 reset。

## 临时 smoke 结果

旧 picotest 在固定 RTL 上完成 11633 条退休指令，观察到 33158 cycles、
IPC 0.350835，并通过旧提交级差分检查。这一结果只证明固定提交仍可运行，
不得进入正式 IPC 对比表。

原因是旧 `AXIMemory.cc` 在构造时使用 `srand(time(NULL))`，AR/R/AW/W/B
ready/valid 延迟来自未记录的随机序列；旧程序还以非法指令哨兵结束。
正式基线必须由新的确定性 AXI/device adapter 使用显式 seed、相同 ELF、
相同 nominal/fast/slow profile 和 `tohost` 判定重新测量。

## 未完成门槛与可选旁证

- 确定性旧顶层 AXI adapter 与完整 stall breakdown 尚未接入。
- 静态面积基线仍需迁移到 ADR-0009 的 manifest/脚本并加入端口复制和组合代理。
- 当前主机未安装 Vivado 2026.1，因此没有可选 post-route LUT/FF/BRAM/DSP/WNS 旁证；
  这不再阻塞 M0 或发布。
- 在确定性 IPC adapter 与静态面积脚本完成前，M0 Issue 保持打开，不创建
  `v0.1-baseline` release。
