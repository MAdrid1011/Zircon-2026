# Zircon-2026 实施状态

本页按研发计划顺序记录可由代码、测试或报告验证的状态。`completed` 表示已有实现和自动测试，`partially completed` 表示公共契约或局部模块已存在，`missing` 表示尚无可运行实现。

| 计划项 | 状态 | 当前证据 | 下一门槛 |
|---|---|---|---|
| M0 GitHub 主仓与子模块分支 | completed | 公开父仓和两个 `zircon-2026` 子模块分支已建立并启用禁止 force-push/PR required 保护；基线提交写入 `toolchain.lock.json` | 每个后续 submodule bump 继续链接子仓 PR 与测试证据 |
| M0 固定工具链 | partially completed | Scala/sbt/Chisel/Verilator/LLVM/Vivado 与第三方 SHA 已锁定；CI 从固定 commit 构建 Verilator 5.050，并从 release commit/package version 固定 LLVM 22.1.8；Actions 固定到 commit | Vivado runner 保存版本与 post-route artifact |
| M0 2024 脏改动审计 | completed | `docs/migration/zircon-2024-audit.md` | 逐项决定是否移植 |
| M0 2024 固定提交复现 | partially completed | 独立 clone 已完成 Java/LLVM/Verilator build 和 picotest smoke；静态资源差异已量化 | 接入确定性同构 AXI profile；安装 Vivado 2026.1 后测 post-route |
| M0 可复现仿真底座 | partially completed | ZirconSim 已有显式 seed、ELF32 加载、`tohost` 判定、单元测试和有界 smoke；当前 M0 空闲顶层只允许显式 timeout | 接入 AXI memory/device model、退休 trace 与真实程序执行 |
| 顶层 AXI4/interrupt/trace 接口 | completed | `AXI4MasterPort`、`InterruptInputs`、`RetireEvent` 可 elaboration | 连接取指、提交与异常响应 |
| PMA 分类 | completed | Memory/DeviceStrong/DeviceBurstable/空洞单元测试 | 接入 LSU 和 fetch access fault |
| 有序 MMIO 合并 | partially completed | 4-beat 聚合、4 KiB 边界、强顺序单拍测试 | 接入 ROB/M0、AXI response 和精确提交 |
| FirstFaultRecord | completed | 记录仅含 `{robTag,cause,tval}`，以 ROB head 的 modulo-24 距离选择最老异常 | 接入 commit/trap controller 的 consume/flush |
| M1 RV32I 前后端 | partially completed | 组合译码、双路最长前缀 dispatch/三类 IQ 路由/dispatch-time fault、双路 rename、IntIQ→双路 operand-read→E0/E1→五端点 completion→ROB/PRF/ready/wakeup、BDB→lossless recovery→ROB tail walk→rename undo、六路 FirstFault 候选已形成组合后端，并覆盖双发射 RAW、误预测删除年轻整数与 illegal+flush；另有 24 项 ROB、56×32 6R2W PRF、12 项 IntIQ、8 项 1R1W BDB、512×2-bit 四银行 bimodal、64 项 2-way 四银行 BTB、8 项 RAS、64-bit 推测历史、4 项 fetch/decode queue、六种 Zicsr、M-mode CSR/trap 与精确双提交 directed tests；顶层仍为 M0 shell | 接入 commit/CSR 与 E0 system side effect，再连接前端/I-Cache 形成可执行 datapath |
| M2 RV32M/多发射 | missing | `UopRef`/endpoint 类型已定义 | M 扩展和 3-start/2-complete 测试 |
| M3 双 LSU/Cache/A | missing | 配置与 PMA/MMIO 边界已定义 | memory milestone 回归 |
| M4 F/interrupt/miniTAGE | partially completed | 顶层中断/trace 字段与 CSR 内 MEI>MSI>MTI 仲裁、Direct/Vectored trap 状态转换已有 directed tests | FPU、interrupt commit/取消/WFI 和 miniTAGE 后运行 TestFloat/ACT4/中断回归 |
| M5 IPC/PPA 收敛 | missing | 报告格式已定义 | 2024/2026 同环境测量 |
| M6 验证闭环 | missing | 覆盖与随机门槛已定义 | 里程碑十亿退休指令 |

状态只能在对应自动测试或报告进入 Git 后更新；控制文档更新本身不等价于硬件进展。
