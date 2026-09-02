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
| M2 E2 directed | `DecoderSpec`, `LongIssueQueueSpec`, `LongPipeSpec`, and `CoreShellSpec`: all eight OP/funct7=1 encodings; ROB-age issue, source-ready wakeup, squash/flush; 16x16 partial-product halves; iterative div/rem including zero and signed-overflow rules; two E2 result slots; E1->E2 and E2->E1 RAW through true retire trace; observed E0/E1/E2 three-start with recovery kill; simultaneous E1/E2 completion and dual retirement; four explicit-seed (`0x5eed`, `0x5eed1001`, `0x5eed2002`, `0x5eed3003`) AXI AR/R backpressure recovery runs that preserve failure seed/pattern/retire trace. ZirconSim PR #6 at `b51863c` runs `make diff` with the locked Spike revision: 17 RV32M events, plus the retained 17 RV32I/Zicsr and 32 RV32I ALU/branch events, matched at seed 1; `make diff-sail-rv32m` also matches the same 17 RV32M records against locked Sail-RISC-V `beaf44991eee362a062fcaaf6fcb78ca428ff710`. `make micro-ipc-rv32m` and `make baseline-ipc-rv32m` measure 0.07234 IPC (235 cycles) and 0.09140 IPC (186 cycles) on the same fixed prefix and seed, respectively; this is not the M0/M5 full workload profile. Each differential run then reaches only the expected unimplemented-LSU `tohost` timeout | M3 memory path, full IPC comparison, and `v0.3-rv32im` release evidence |

The local evidence command is `./scripts/sbtw test`; the currently retained
JUnit reports contain 63 reports and 371 tests with zero failures or errors.
`make verilog` elaborates the same configuration. New randomized tests must declare a seed and persist the ELF,
trace, tool SHA, and waveform on failure.

## M3 planned verification contract

M3 is partially executable. The following records actual local directed
evidence and the remaining required fail-to-pass coverage for Issue #47. Passing
this table is not a memory release, ELF, or differential claim:

| Boundary | Required tests and properties |
| --- | --- |
| MemIQ/M0/M1 | `MemIssueQueueSpec` passes two enqueue, source wakeup, ROB-age selection, same-cycle recycle, free-only admission, recovery, and the live-atomic barrier against younger M0 and M1 issue before LSQ allocation. `DualLSUAdmissionSpec`, `M0RequestArbiterSpec`, `AuxiliaryReadArbiterSpec`, and `ReorderBufferSpec` cover exact-tag M0/M1 ownership and operand context. `CoreShellSpec` passes inaccessible M1 replay, cacheable AXI load plus L1D write-allocate store, ID-6 device operations, and executable ID-7 LR/SC/AMO. Full dual-LSU conflict integration remains required. |
| M0 cacheable load | `LoadStoreQueuesSpec` proves that a non-atomic `Memory`-PMA load is cacheable for either retained M0/M1 owner while DeviceStrong is rejected, including two same-cycle LQ load queries. `MemoryQueueIngressSpec` emits two pending loads by ROB age across ingress lanes. `DualLoadForwardArbiterSpec` preserves the earlier wrap-aware one-request ownership contract. `L1DLoadCacheSpec` now proves direct two-lane different-bank hits, ROB-age ordered exact results, same-bank/same-address younger replay, same-line miss merge into one refill with two exact waiter completions, concurrent different-set hit/miss with an exact result/waiter pair, two invalid-way different-set misses with distinct MSHR IDs, and conservative same-set replay without unowned L2 work. Two recovery cases prove that a squashed younger dual-miss owner is released before L2-probe acceptance, but an accepted younger probe drains its response without completion before its older survivor proceeds. `CoreShellSpec` observes two independent loads enter both M0/M1, retire their exact data and memory metadata, and reach the correct completion owner. Victim-transfer-safe hit/miss and dual-miss, merge contention, MSHR/waiter/victim-full, L2-backpressure, and remaining recovery conflict coverage remain required. |
| LQ/SQ | `LoadStoreQueuesSpec` covers older-store ordering/byte forwarding, metadata-to-retire, fault cleanup, wrap recovery, ID-6 ownership, an AMO LQ/SQ pair whose exact response generates one M0 result plus paired read/write retire metadata, and age-tagged FENCE readiness across ROB wrap. `DualMemoryLoadCompletionSpec` covers the atomic writable-result/cause-7 split. `CoreShellSpec` proves a FENCE can retire while a younger cacheable load owns LQ state, then proves a dirty FENCE remains unretired while ID-5 B is withheld; LR/SC success, SC no-write failure, local-store and trap/MRET reservation invalidation, AMO old-value retirement, and aq ordering are also covered. Explicit-seed random pressure and full dual-LSU conflict coverage remain required. |
| PMA/fault | `MemoryAddressUnitSpec` currently passes byte-mask/data alignment, load/store misalignment priority, inaccessible and atomic-PMA denial causes, and M1 cacheable-load eligibility. All default-region permissions, AXI RRESP/BRESP exact-tag faults, and oldest-fault integration remain required. |
| AXI data | `AXIDataReadEngineSpec` covers four physical L2 demand owners, preserved client token, AR/R backpressure, 4 KiB legality, interleaved IDs, RRESP, and RLAST; `AXIL2WritebackEngineSpec` covers ID 5 retained eight-beat writeback, AW/W backpressure, 4 KiB-edge legality, and B-error retry; ID-6 group tests cover device traffic. `AtomicMemoryEngineSpec` covers ID 7 single-beat ID/RLAST checks, LR/SC, every AMO operation, independent AW/W, RRESP/BRESP errors, flush drain, and top-level AW-before-W scheduling. `CoreShellSpec` covers shared fetch/L2-demand/writeback/device/atomic demux. Explicit-seed randomized AXI stress remains required. |
| Seeded AXI stress | `CoreShellSpec` drives four explicit seeds `0x5eed3004`--`0x5eed3007` through independently randomized AR/AW/W ready and R/B valid schedules. Its success path observes AR/R/AW/W/B handshakes and exact ID-6 store retirement; its error path alternates cacheable-load `RRESP` fault and device-store `BRESP` fault while checking precise PC/cause/tval. Offered R/B responses remain valid until handshake, and a failure persists the seed, all five channel sequences, and retire trace. `make test-m3-axi-stress` runs only these two top-level cases, each observed below 40 seconds. | Add long random streams across multi-owner ID/beat interleaving, reset, error combinations, external response ordering, and bounded formal credit/protocol proofs. |
| Cache | `L1InstructionCacheSpec` covers active 1 KiB/2-way L1I hit/miss, resident-L2 hit, AXI I-fill allocation/merge backpressure, collision data selection, replacement, line-end prefixes, fault/no-fill, sequential lookahead, redirect drain, and FENCE.I invalidation. `CacheFenceDrainControllerSpec`, `L1DLoadCacheSpec`, and `ExclusiveL2TransferStoreSpec` cover ordered L1D/L2 dirty sweeps, local ingress backpressure, victim ownership, and the final ID-5 B gate. `CoreShellSpec` with mutable deterministic AXI backing memory proves a dirty self-modifying word executes only after `FENCE.I` writeback, invalidate, redirect, and refetch; a separate B-withheld run proves ordinary FENCE cannot retire early. The fixed-seed RV32I/M/A `tohost` runs plus locked-Spike and locked-Sail bounded committed-memory results are recorded in `m3-tohost-evidence.md`. Full dual-port conflict matrix, random error/backpressure stress, external coherency, and final write-back integration remain required. |
| MMIO/A | ID-6 strong/burstable groups plus LR/SC success and local/trap reservation failure, every AMO function, exact R/B fault conversion, and aq ordering have local directed evidence. Remaining: external reservation-loss cases, FENCE pressure, formal AXI/LSQ properties, random error injection, and committed-memory differential. |
| Core/differential | deterministic RV32IMA ELF including `tohost`; explicit-seed stress starting at `0x5eed3004`; trace memory fields; Spike and Sail committed-memory comparison; applicable ACT4 IMA smoke |

Each random failure bundle contains the generator and memory seeds, ELF and
SHA256, RTL/submodule/tool SHA, minimized stream, retire trace, and waveform
path. No timeout increase, response filtering, or seed replacement is a valid
fix for an unexplained mismatch.

## FPGA and Throughput Gate

[`nexys4-timing.md`](nexys4-timing.md) defines the mandatory Nexys4 DDR
post-route 100 MHz timing gate and the component/full-core simulation throughput
evidence. The current codebase has no FPGA wrapper/XDC/report, so this gate is
unverified and remains a release blocker; it does not justify waiting instead
of implementing the remaining M3--M6 work. `make test-m3-store` is the focused
cacheable-store regression tier and must remain below the five-minute component
simulation budget.
