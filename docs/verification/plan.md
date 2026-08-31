# Zircon-2026 验证计划

验证以 `RetireEvent` 为统一真值边界。模块单元测试验证局部状态机；完整核心在每条退休指令后与 Spike 比较，nightly 使用 Sail 复核；ACT4、RISCV-DV、TestFloat、形式化性质和性能负载覆盖不同错误类别。

## 回归层级

| 层级 | 内容 | 门槛 |
|---|---|---|
| PR | ChiselSim 单元、directed、ACT4 smoke、20×10k RISCV-DV | 零失败，seed 可复现 |
| Nightly | 完整 ACT4、500×50k、Spike+Sail、AXI stress、bounded formal | 零未分类 mismatch |
| Milestone | 10,000×100k、覆盖闭环、mutation、静态面积/IPC | 至少十亿条退休指令 |

随机源不得从墙钟取 seed。失败包包含 RTL/submodule/tool SHA、ELF hash、generator seed、memory seed、最小化程序、trace 和波形。

## 功能覆盖

覆盖模型按 instruction、operand class、producer/consumer endpoint、hazard、双提交位置、trap/interrupt 时机、PMA、Cache hit/miss、MSHR occupancy、AXI channel ordering、MMIO group length、FP value class 和 rounding mode 建 bin 与 cross。

强制功能 bin 必须达到 100%。代码覆盖门槛为 line 95%、branch 90%、toggle 85%；豁免需要关联 Issue、不可达证明、责任人和失效 milestone。

## 关键不变量

- retirement order 严格单调且 instruction 只退休一次。
- `x0` 恒为零，flush 后错误路径不修改 architectural state。
- FirstFaultRecord 总是最老未处理异常。
- 一个 D block 只属于 L1D、L2 或 transfer buffer。
- MSHR、queue 和 AXI credit 不丢失、不复制。
- ordered MMIO 的 bus sequence 与 program order 一致。
- LR/SC reservation 只在合法条件下成功。
- AXI ID、beat count、`last` 与 response owner 一致。

## 当前 M0 测试

- 配置几何和 4/8 KiB 限定。
- PMA memory/strong-device/burstable-device/inaccessible 分类。
- MMIO 相邻合并、强顺序单拍、4 KiB 边界。
- 两路 fault detection 的最老异常选择。
- 顶层 M0 shell 的 AXI idle 和两个 invalid retire lane。

## 当前 M1 执行边界

以下 M1 检查已经是 deterministic ChiselSim regression 的一部分；它们不是 ACT4、
ELF 或 differential 完成声明。

| Contract | Evidence | Remaining release evidence |
|---|---|---|
| AXI instruction transport | `AXIInstructionFetchSpec`: 4-beat、1/2/3-beat 4 KiB truncation、AR backpressure、redirect drain、RRESP、ID/RLAST assertion | AXI random stress and formal protocol properties |
| Frontend redirect/prediction | `M1FrontendSpec` plus `CoreShellSpec`: earliest control, JALR barrier, commit training, commit-over-recovery priority, cold branch recovery, JAL and JALR link/target behavior | Randomized branch/predictor stress and external reference comparison |
| Precise commit/trap metadata | `CommitControllerSpec`, `CommitCSRSubsystemSpec`, `ReorderBufferSpec`, `RetireTraceFormatterSpec`, `CoreShellSpec`: lane-1 ordering, ECALL, illegal instruction, software interrupt at live head, MRET | Full interrupt timing matrix and external reference comparison |
| Executable top-level | `CoreShellSpec`: AXI-fed dependency, CSR dependency, RRESP fetch fault, FENCE/FENCE.I, AXI AR/R backpressure, and no false LSU completion | Deterministic ELF/AXI harness, ACT4 I/Zicsr subset, Spike then Sail comparison |
| ELF/trace harness | ZirconSim PR #5 at gitlink `7c882e4`: ELF `PT_LOAD`, deterministic seed-1 AXI, held response protocol, ordered JSONL retire trace; RV-Software picotest ELF retired 5 events in 512 cycles | RV32I-only tohost program cannot complete until M3 LSU; then use this same trace for Spike/Sail comparison |
| Spike commit-prefix smoke | ZirconSim `make diff SPIKE=/path/to/locked/spike`: fixed seed-1 RV32I/Zicsr ELF, RTL assertion of 17 retirements, and 17 ordered records matched against Spike for privilege, PC, instruction, valid GPR write, and valid CSR write | This deliberately rejects memory/trap/interrupt/F events and accepts only the expected timeout at the following blocked `tohost` store; it is not ACT4, Sail, full M1, or memory-differential evidence |

The local evidence command is `./scripts/sbtw test`; the current integration
run completed 39 suites and 183 tests. `make verilog` elaborates the same
configuration. New randomized tests must declare a seed and persist the ELF,
trace, tool SHA, and waveform on failure.
