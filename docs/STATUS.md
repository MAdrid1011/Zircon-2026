# Zircon-2026 实施状态

本页按研发计划顺序记录可由代码、测试或报告验证的状态。`completed` 表示已有实现和自动测试，`partially completed` 表示公共契约或局部模块已存在，`missing` 表示尚无可运行实现。

| 计划项 | 状态 | 当前证据 | 下一门槛 |
|---|---|---|---|
| M0 GitHub 主仓与子模块分支 | completed | 公开父仓和两个 `zircon-2026` 子模块分支已建立并启用禁止 force-push/PR required 保护；基线提交写入 `toolchain.lock.json` | 每个后续 submodule bump 继续链接子仓 PR 与测试证据 |
| M0 固定工具链 | partially completed | Scala/sbt/Chisel/Verilator/LLVM 与第三方 SHA 已锁定；CI 从固定 commit 构建 Verilator 5.050，并从 release commit/package version 固定 LLVM 22.1.8；Actions 固定到 commit；Vivado 版本仅保留为可选旁证 | 为 static-area manifest/script 和标准验证工具补齐可复现安装 |
| M0 2024 脏改动审计 | completed | `docs/migration/zircon-2024-audit.md` | 逐项决定是否移植 |
| M0 2024 固定提交复现 | partially completed | 独立 clone 已完成 Java/LLVM/Verilator build 和 picotest smoke；第一版静态资源差异已量化 | 接入确定性同构 AXI profile；以 ADR-0009 manifest/script 重算完整静态面积基线 |
| M0 可复现仿真底座 | partially completed | ZirconSim 已有显式 seed、ELF32 加载、`tohost` 判定、单元测试和有界 smoke；当前 M0 空闲顶层只允许显式 timeout | 接入 AXI memory/device model、退休 trace 与真实程序执行 |
| 顶层 AXI4/interrupt/trace 接口 | completed | `AXI4MasterPort`、`InterruptInputs`、`RetireEvent` 可 elaboration | 连接取指、提交与异常响应 |
| PMA 分类 | completed | Memory/DeviceStrong/DeviceBurstable/空洞单元测试 | 接入 LSU 和 fetch access fault |
| 有序 MMIO 合并 | partially completed | 4-beat 聚合、4 KiB 边界、强顺序单拍测试 | 接入 ROB/M0、AXI response 和精确提交 |
| FirstFaultRecord | completed | 记录仅含 `{robTag,cause,tval}`，以 ROB head 的 modulo-24 距离选择最老异常；commit/CSR 组合已消费/清除同步异常 | 接入完整后端并交叉外部 fault、interrupt 与 recovery |
| M1 RV32I 前后端 | partially completed | 两个后端组合域已接成可运行 `M1BackendSubsystem`：CSR/System 仅在 ROB head 进入 E0，单一 tagged side-effect slot 保留提交写，Zicsr GPR 写回、ECALL/EBREAK 精确异常、MRET/FENCE/FENCE.I/WFI 提交信号和 M-mode CSR state 已闭环；集成测试覆盖依赖 `ADDI→CSRRW→CSR read`、ECALL trap、FENCE 排空回压以及 MSI→MRET，既有双发射 RAW、误预测恢复、连续 branch、lane-1 fault 等回归保留；顶层仍为 M0 shell | 接前端/I-Cache、redirect 仲裁和 `RetireEvent`，形成可执行 RV32I 顶层后运行 ACT4/差分 |
| M2 RV32M/多发射 | missing | `UopRef`/endpoint 类型已定义 | M 扩展和 3-start/2-complete 测试 |
| M3 双 LSU/Cache/A | missing | 配置与 PMA/MMIO 边界已定义 | memory milestone 回归 |
| M4 F/interrupt/miniTAGE | partially completed | 顶层中断/trace 字段与 CSR 内 MEI>MSI>MTI 仲裁、Direct/Vectored trap 状态转换已有 directed tests | FPU、interrupt commit/取消/WFI 和 miniTAGE 后运行 TestFloat/ACT4/中断回归 |
| M5 IPC/静态面积收敛 | partially completed | ADR-0009 已冻结存储 bit、端口复制、CAM/mux 与算术资源代理口径；人工 2024 清单已存在 | 完成自动 manifest/script、完整 2024/2026 静态对照和同环境 IPC 测量 |
| M6 验证闭环 | missing | 覆盖与随机门槛已定义 | 里程碑十亿退休指令 |

状态只能在对应自动测试或报告进入 Git 后更新；控制文档更新本身不等价于硬件进展。
