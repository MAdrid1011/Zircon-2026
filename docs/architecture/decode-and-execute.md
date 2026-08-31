# RV32I 译码与整数执行

本规格对应 M1 Issue #7。译码器是组合逻辑；它不读取寄存器、不分配 ROB/IQ，
也不直接产生 trap 或 redirect。非法编码以 `legal=false` 进入精确异常路径。

当前实现：`RV32IDecoder`、`EndpointAdmission`、无状态 `IntegerOperandRead` 和
`IntegerExecute` 已有 directed tests；E0/E1 skid buffer、ROB 写回和 redirect 尚未
接入顶层。

## 接口

| 信号 | 方向 | 宽度 | 语义 |
|---|---|---:|---|
| `instruction` | input | 32 | 原始 RV32 instruction |
| `decoded` | output | bundle | 操作、寄存器、立即数、端点和副作用分类 |
| `lhs/rhs/pc/immediate` | execute input | 32 | 已完成取数/旁路的操作数 |
| `result` | execute output | 32 | 整数写回值或地址计算结果 |
| `controlTaken/controlTarget` | execute output | 1/32 | 仅供 E0 redirect 检查 |

## Operand Read

IntIQ 的 E0/E1 输出分别携带 compact `UopRef`。`IntegerOperandRead` 同周期生成两个
ROB context read tag 与四个 PRF read address；只有 context valid 且 tag 与 UopRef
相同，才允许对应 endpoint request fire。两条通道 ready/valid 独立。

`SourceKind.IntegerRegister` 选择 PRF data，`Immediate` 选择 UopRef immediate，
`ProgramCounter` 选择 ROB context PC，`None` 输出零。integer path 不接受
`FloatingRegister`。IntIQ 已保证三个 source ready；operand-read 再断言该条件，避免
未完成 producer 被绕过。

## 端点映射

| 指令类 | 允许端点 | 说明 |
|---|---|---|
| LUI/AUIPC、整数立即数/寄存器运算 | E0、E1 | E1 只接收无控制副作用操作 |
| Branch、JAL、JALR | E0 | 唯一 redirect source |
| CSR、ECALL、EBREAK、MRET、WFI、FENCE、FENCE.I | E0 | 状态修改仍在 commit |
| Load | M0、M1 | M1 admission 还须检查对齐、PMA 和 cacheable |
| Store | M0 | 提交前只写 SQ |

M/A/F opcode 在对应里程碑到来前必须译为非法；不能把未知 `funct3/funct7` 当作
相近整数操作。保留的 shift immediate 高位、JALR 非零 `funct3`、未知 branch/
load/store width、未知 system immediate 和保留 CSR funct3 都是 illegal。按基础
ISA 的前向兼容要求，FENCE 的保留 mode/集合以及 FENCE/FENCE.I 的保留 rs1、rd、
immediate 字段例外：硬件忽略这些字段并执行保守的完整 fence。

## 立即数

I/S/B/J immediate 必须按 ISA 符号扩展，U immediate 保留低 12 位为零。Branch
和 JAL target 为 `pc+immediate`；JALR target 为 `(lhs+immediate)&~1`。目标地址
bit 1 非零引发的 instruction-address-misaligned 不在组合 ALU 中直接 trap，而由
E0 完成结果写入 FirstFaultRecord。

## 阻塞、回滚与异常

译码、operand-read 和整数语义模块没有内部状态。E0/E1 的 one-entry completion skid buffer
负责 ready/valid 回压。redirect kill 年轻结果；已经进入统一 completion port 的
结果仍须用 ROB generation/tag 检查后才能写 PRF。非法指令不允许产生 GPR、CSR、
memory 或 control side effect。

## 验证映射

- 每条 RV32I/Zicsr/Zifencei 指令至少一个合法 directed vector。
- 每个保留 `funct3/funct7/system imm` 至少一个非法 vector。
- 立即数最小/最大值、x0、同源、溢出、移位 0/31、signed/unsigned 比较边界。
- 所有 branch taken/not-taken；JALR bit 0 清除；E1 admission 对 control/system
  操作恒为 false。

参考：[RV32I FENCE](https://docs.riscv.org/reference/isa/unpriv/rv32.html)、
[Zifencei](https://docs.riscv.org/reference/isa/unpriv/zifencei.html)。
