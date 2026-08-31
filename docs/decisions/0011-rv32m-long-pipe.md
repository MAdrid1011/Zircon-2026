# ADR-0011: RV32M E2 LongPipe

状态: Accepted

关联 Issue: #45

## 背景

M1 的 `ZirconCore` 必须把所有 M opcode 阻塞在 LongPipe capacity 为零的
边界，不能产生占位 completion。M2 需要解除这个限制，同时保留冻结的 24-entry
ROB、56x32 integer PRF six-read/two-write geometry、每周期最多三项启动和两项
completion，以及 E0 是唯一 control/system endpoint 的约束。

RV32M 的 multiply 和 divide/remainder 都具有多周期实现空间。固定 latency
会把未来 F multiply/FMA 或 div/sqrt 的共享约束泄露到调用者，且在 completion
backpressure、selective squash 与 global flush 下容易把已 kill 的结果误写回。

## 决策

M2 新增 E2 `LongIssueQueue` 和 `LongPipe`，只接受 `Multiply` 或 `Divide`
uop。LongIQ 为四项 compact `UopRef` 队列；它与 E1 共用既有的 operand-read
入口，不增加 PRF read port。全局 start arbiter 最多允许 E0、E1、E2 三项启动，E2
每周期最多接受一项。

E2 对外仅暴露 ready/valid。乘法使用可被后续 F multiply/FMA 复用的 16x16
partial-product datapath；divide/remainder 使用可被后续 F divide/sqrt 复用的
iterative engine。M2 不向 consumer 承诺固定 latency。结果先进入两个 E2
completion buffer，再进入现有五端点到两端口 completion arbitration。

所有八条 RV32M 指令均写整数 destination。其结果由 ISA 固定：

- `MUL` 为低 32 bit；`MULH`、`MULHSU`、`MULHU` 分别为 signed-signed、
  signed-unsigned、unsigned-unsigned 64-bit product 的高 32 bit。
- 除数为零时 `DIV`/`DIVU` 返回 all ones，`REM`/`REMU` 返回 dividend。
- `INT_MIN / -1` 的 `DIV` 返回 `INT_MIN`，`REM` 返回 zero。

E2 active work 和两个 result buffer 都接受 selective squash 与 global flush。
active work 的 tag 比 resolving branch 年轻时在时钟边界取消；result buffer 用同一
age relation 删除。已经送入统一 completion 的结果仍只允许 ROB live-tag acceptance
写 PRF/ready table。E2 是纯本地计算，没有被 kill 后仍须 drain 的 AXI transaction。

## 备选方案

- 将 M 指令送入 E1 并用 combinational multiply/divide: 违反 E1/E2 endpoint
  划分并导致不受控组合时延，不采用。
- 为 M 指令制造零 latency completion: 将把除法、backpressure 和 kill 语义伪装成
  已完成架构进度，不采用。
- 为 E2 增加独立 PRF read port: 违反冻结的 six-read PRF 资源点，且会掩盖静态面积，
  不采用。
- 固定 M2-only multiplier/divider: 阻断冻结的 M/F shared-resource 面积策略，不采用。

## 后果

- RV32M opcode 在 M2 成为 E2-only legal uop；未知 M encoding 仍精确 illegal。
- M2 的 LongPipe 不实现 F、LSU、Cache 或 AXI data traffic；M0/M1 capacity 继续为零。
- 对 E2 的 source-ready/wakeup、occupancy、start、completion stall、accepted、
  discarded 与 kill 必须计数，并进入 M5 IPC/static-area ledger。
- 后续 M4 只能在不改变 E2 ready/valid、completion 或 kill 合同的前提下复用乘法和
  divider/sqrt resource。
