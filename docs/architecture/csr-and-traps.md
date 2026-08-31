# CSR 与 trap 状态模块

本规格冻结 M1 的 `MachineCSRFile` 和 `CSRInstructionUnit` 契约。它只覆盖单 hart、M-mode、无 MMU/PMP/delegation 的状态与组合语义；ROB 提交仲裁、八周期中断响应计数和流水线 redirect 在后续 commit-controller 模块完成。实现依据为仓库锁定版本对应的 RISC-V Unprivileged ISA 与 Privileged Architecture machine-level 规则。

## 参数与实现集合

模块使用 `ZirconCoreConfig.hartId`，XLEN 固定为 32。实现 CSR 为：

| CSR | 地址 | 可写状态与 WARL 行为 |
|---|---:|---|
| `fflags/frm/fcsr` | `0x001/0x002/0x003` | `FS=Off` 时访问非法；分别保存 5/3/8 bit，合法写使 `FS` 变为 Dirty |
| `mstatus` | `0x300` | 保存 `MIE/MPIE/FS`；`MPP` 固定读回 M，`SD` 由 `FS=Dirty` 组合产生，其余位 WPRI |
| `misa` | `0x301` | 固定 WARL 值，报告 RV32 与 I/M/A/F；写访问合法但不能改变该值 |
| `mie` | `0x304` | 仅保存 `MEIE/MSIE/MTIE` |
| `mtvec` | `0x305` | BASE 四字节对齐；MODE 仅支持 Direct/Vectored，保留编码归一为 Direct |
| `mscratch` | `0x340` | 全 32 bit 可写 |
| `mepc` | `0x341` | IALIGN=32，因此低两位读写为零 |
| `mcause/mtval` | `0x342/0x343` | 全 32 bit 可写，trap 时由硬件覆盖 |
| `mip` | `0x344` | `MEIP/MSIP/MTIP` 直接反映外部电平；CSR 写合法但对这些只读位无效 |
| `mcycle[h]` | `0xb00/0xb80` | 64-bit machine counter，RV32 分半读写 |
| `minstret[h]` | `0xb02/0xb82` | 64-bit retired-instruction counter，RV32 分半读写 |
| `mvendorid/marchid/mimpid` | `0xf11/0xf12/0xf13` | 只读零，表示尚未分配 JEDEC vendor/architecture/implementation ID |
| `mhartid` | `0xf14` | 只读 `ZirconCoreConfig.hartId` |

`misa` 的 MXL 字段固定为 RV32，扩展位只设置 A、F、I、M。未列出的 CSR 地址非法；地址编码为只读的 ID CSR 写访问非法。对 `misa` 或 `mip` 的写入不报 illegal-instruction，但固定 ISA 字段和所有已实现 pending 位均保持不变。

## 接口

`MachineCSRFile` 接口分为四组：

- `access`: 组合查询一个 CSR 地址和读/写意图，返回当前值和 `legal`。该端口不修改状态。
- `commitWrite`: 提交点的一项 CSR 写；只有已经通过 `access.legal` 的操作才能到达该端口。
- `trapCommit/mretCommit`: 提交点的 trap 或 MRET 状态转换。两者与 CSR 写互斥，RTL 断言该协议。
- `retiredInstructions/fpCommit/interrupts`: 驱动计数器、累积浮点标志和外部中断电平。

输出包括当前最优先且已使能的 interrupt、trap target、MRET target、`mstatus.MIE/FS` 和完整 `fflags`。中断选择固定为 MEI > MSI > MTI；只有 `mstatus.MIE` 与对应 `mie` 位同时有效时才对 commit controller 报告 eligible。

`CSRInstructionUnit` 是无状态组合模块。它接收六种 Zicsr operation、源操作数、CSR 当前值和访问合法性，产生 GPR 读回值以及提交时使用的 CSR 写值。立即数指令的源必须由上游零扩展为 32 bit。`CSRRS/CSRRC` 的源为零时不产生写，`CSRRW` 无论源值是否为零都产生写。

## 提交与状态优先级

CSR 和 system 指令在 M1 采用面积优先的提交序列化：它们只能独占发生架构副作用的提交周期，因而一个周期最多有一项 `commitWrite`、`trapCommit` 或 `mretCommit`。`commitWrite` 不与 F 指令的 `fpCommit` 同周期发生；普通双退休仍可把 `retiredInstructions` 设为 0、1 或 2。中断可以在普通指令退休后的同一边界产生 `trapCommit`，因此允许该事件与已退休 F 指令的 `fpCommit` 同周期生效。

每周期先进行普通计数器递增；对 `mcycle[h]` 或 `minstret[h]` 的显式 CSR 写覆盖同周期对应 counter 的自动增量。提交控制器负责在写 `minstret[h]` 的周期传入已经按架构顺序调整的 retire count。

trap 状态转换为：

1. `mepc := faultPc & ~3`；
2. `mcause := {interrupt, cause}`，`mtval := trapValue`；
3. `MPIE := MIE`，随后 `MIE := 0`；
4. redirect 到 `mtvec.BASE`，仅 interrupt 且 MODE=Vectored 时加 `4 × cause`。

MRET 状态转换为 `MIE := MPIE`、`MPIE := 1`，redirect 到 `mepc`。由于只实现 M-mode，`MPP` 在所有读写和状态转换后均为 M。

## 流水、阻塞和异常规则

CSR 读改写在 E0 执行，但只在 ROB head 且没有更老架构副作用时读取状态。计算出的旧值和写值随 ROB/BDB 保存；真正写 CSR 只发生在提交点。CSR 查询非法时 E0 产生 illegal-instruction fault candidate，不允许 `commitWrite`。

外部 interrupt 输入是电平信号，`mip` 不锁存脉冲。commit controller 在无更老精确异常、无不可撤销 device/AMO 事务且提交边界允许时采样 `interruptEligible`。trap 或 MRET 发出全局 flush；CSR 文件本身没有 replay 状态，也不因普通 pipeline flush 回滚已经提交的 CSR。

## 不变量与断言

- 一个周期内 `commitWrite`、`trapCommit`、`mretCommit` 至多一个有效。
- 非法或只读 CSR 写不得进入 `commitWrite`；违反视为内部协议错误。
- `MPP` 恒为 M，`mepc[1:0]` 恒为零，`mtvec.BASE[1:0]` 恒为零。
- `mip` 的三个已实现位在同周期精确等于外部输入。
- interrupt cause 在 MEI/MSI/MTI 同时 pending 时分别选择 11、3、7 中的最高优先级项。
- trap target 只对 interrupt 使用 vectored offset；同步异常即使 MODE=Vectored 仍跳 BASE。
- `mcycle/minstret` 64-bit 进位和 RV32 分半写不得破坏另一半。

## 性能计数与验证映射

`mcycle` 每个未复位核心周期增加一，`minstret` 增加提交控制器报告的退休条数。中断从 pending 到 handler 的延迟、不可撤销事务阻塞周期和 WFI 周期是独立的实现性能计数器，不占用本规格列出的标准 CSR 地址，将在 commit-controller 规格中定义。

| 验证目标 | 定向/随机检查 |
|---|---|
| CSR 地址、读写与 WARL | 遍历实现/未实现地址、只读写、`mstatus/mtvec/mepc/mie/mip` 位掩码 |
| Zicsr 语义 | 六种 operation，`rd=x0`、`rs1=x0`、zimm=0、同值 set/clear |
| Counter | 低半进位、高/低半写、0/1/2 退休、写优先于增量 |
| Trap/MRET | MIE/MPIE 转换、所有同步 cause、Direct/Vectored、`mepc/mtval` 边界 |
| Interrupt | 单独及全组合 pending、全组合 enable、固定优先级、MIE 门控 |
| F CSR | `FS=Off` illegal、五位 flags 累积、`frm/fcsr` 读写、FS Dirty |
| 协议性质 | 互斥提交、非法写断言、低位对齐和组合 `mip` 一致性 |

ACT4 Zicsr/privileged 子集与 Spike/Sail 提交级差分在 commit controller 接入后作为集成门禁；本模块 PR 先以 ChiselSim directed test 覆盖上述局部状态空间。
