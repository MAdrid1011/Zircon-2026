# M-mode 特权态

Zircon-2026 只实现 M-mode。当前顶层已有 `meip/msip/mtip` 输入和 trace 中的 trap 字段；`MachineCSRFile` 的冻结契约见 [CSR 与 trap 状态模块](csr-and-traps.md)，commit trap controller 在 M1 后续 PR 接入。

<!-- 图：CSR、FFR、commit 和 redirect 关系 -->
<!-- ![M-mode trap flow](./assets/privileged-flow.svg) -->

## CSR

实现集合为 `mstatus/misa/mie/mip/mtvec/mscratch/mepc/mcause/mtval/mhartid/mvendorid/marchid/mimpid/mcycle[h]/minstret[h]/fflags/frm/fcsr`。`mstatus` 实现 MIE/MPIE/MPP/FS/SD，MPP 读写均收敛为 M。`misa` 固定报告 RV32 I/M/A/F。

## Trap 提交

执行单元只产生 fault candidate。异常指令到达 ROB head 时，commit 写 `mepc/mcause/mtval`，更新 `mstatus.MPIE/MIE/MPP`，按 `mtvec` Direct/Vectored 规则产生 redirect。MRET 在提交时恢复 MIE/MPIE 并跳转 `mepc`。

中断优先级为 MEI、MSI、MTI。没有不可撤销 device/atomic 请求时，长延迟 compute 和可缓存 load 可以取消，保证中断在有效 pending 后八个核心周期内进入 handler。
