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
| ELF/trace harness | ZirconSim PR #5 at gitlink `f9086e8`: ELF `PT_LOAD`, deterministic seed-1 AXI, held response protocol, ordered JSONL retire trace; RV-Software picotest ELF retired 5 events in 512 cycles | RV32I-only tohost program cannot complete until M3 LSU; then use this same trace for Spike/Sail comparison |
| Spike commit-prefix smoke | ZirconSim `make diff SPIKE=/path/to/locked/spike`: fixed seed-1 RV32I/Zicsr ELF pairs, RTL assertions of 17 CSR/control and 32 ALU/branch retirements, and both ordered records matched against Spike for privilege, PC, instruction, valid GPR write, and valid CSR write | This deliberately rejects memory/trap/interrupt/F events and accepts only expected timeout at each following blocked `tohost` store; it is not ACT4, Sail, full M1, or memory-differential evidence |
| M2 E2 directed | `DecoderSpec`, `LongIssueQueueSpec`, `LongPipeSpec`, and `CoreShellSpec`: all eight OP/funct7=1 encodings; ROB-age issue, source-ready wakeup, squash/flush; 16x16 partial-product halves; iterative div/rem including zero and signed-overflow rules; two E2 result slots; E1->E2 and E2->E1 RAW through true retire trace; observed E0/E1/E2 three-start with recovery kill; simultaneous E1/E2 completion and dual retirement; four explicit-seed (`0x5eed`, `0x5eed1001`, `0x5eed2002`, `0x5eed3003`) AXI AR/R backpressure recovery runs that preserve failure seed/pattern/retire trace. ZirconSim PR #6 at `398b3a5` runs `make diff` with the locked Spike revision: 17 RV32M events, plus the retained 17 RV32I/Zicsr and 32 RV32I ALU/branch events, matched at seed 1; `make diff-sail-rv32m` also matches the same 17 RV32M records against locked Sail-RISC-V `beaf44991eee362a062fcaaf6fcb78ca428ff710`. Each run then reaches only the expected unimplemented-LSU `tohost` timeout | IPC comparison and `v0.3-rv32im` release evidence |

The local evidence command is `./scripts/sbtw test`; the current integration
run completed 41 suites and 198 tests. `make verilog` elaborates the same
configuration. New randomized tests must declare a seed and persist the ELF,
trace, tool SHA, and waveform on failure.
