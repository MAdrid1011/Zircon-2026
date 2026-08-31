# Zircon-2026 服务器接管与完工手册

> 更新日期：2026-08-31（Asia/Shanghai）  
> 本文是后续执行者的首要入口。它既描述当前断点，也保存从当前断点一直做到 `v1.0.0` 的完整研发合同。任何执行者都必须先完整阅读本文，再阅读本文引用的规格、ADR 和验证文档，不能只根据 README 或当前能通过的单元测试推断项目已经完成。

## 0. 一句话状态

Zircon-2026 目前约完成总目标的 **20%–25%**：公共接口、部分前端预测积木、整数乱序后端、提交/CSR/精确异常骨架已经形成并有单元测试，但顶层 `ZirconCore` 仍是 AXI 空闲壳，尚不能执行 ELF；双 LSU、非阻塞 Cache、A/F、miniTAGE、完整中断、差分验证、性能和最终静态面积闭环仍未完成。

当前正在把已完成的 M1 后端接成第一个可执行 RV32I 核。新加入的 `AXIInstructionFetch` 已通过 `sbt compile`，但尚无定向测试，也尚未接入顶层。

## 1. 接管仓库：必须使用的分支和命令

上游仓库：<https://github.com/MAdrid1011/Zircon-2026>

```bash
git clone --recurse-submodules https://github.com/MAdrid1011/Zircon-2026.git
cd Zircon-2026
git fetch origin --prune
git switch feat/m1-executable-core
git submodule update --init --recursive
git status --short --branch
git submodule status
```

接管后首先执行：

```bash
./scripts/sbtw compile
./scripts/sbtw test
make verilog
```

如果服务器没有同名本地分支，使用：

```bash
git switch --track origin/feat/m1-executable-core
```

### 1.1 当前分支栈

- `origin/main` 在交接时指向 `809dc4d`，包含已合并的静态面积验收 ADR #41。
- PR #42：`feat/static-area-ledger` → `main`，标题为 “M5: add reproducible static area ledger”。交接时 OPEN、MERGEABLE、检查成功：<https://github.com/MAdrid1011/Zircon-2026/pull/42>。
- PR #43：`feat/m1-backend-commit-integration` → `main`，标题为 “M1: integrate CSR system execution with architectural commit”。交接时 OPEN、MERGEABLE、检查成功：<https://github.com/MAdrid1011/Zircon-2026/pull/43>。
- 接续分支 `feat/m1-executable-core` 以 PR #43 的提交 `223e843` 为父节点，并包含本文及尚未完成的 AXI 取指工作。

不要假定两个 OPEN PR 已经进入 `main`。接管时重新查询状态。推荐先合并 #42 和 #43；若不能立即合并，也可以在 `feat/m1-executable-core` 上继续。#43 合并后，把接续分支 rebase 到最新 `main`，解决 `docs/STATUS.md` 等文档冲突，并重新跑本地全回归。禁止 force-push 受保护分支；feature branch 是否需要 `--force-with-lease` 必须先确认仓库规则。

### 1.2 子模块状态

父仓交接分支固定的 gitlink 为：

- `RV-Software`: `11d6eae150d47aab32aca3340e30ba61ddcbb2f0`，长期分支 `zircon-2026`。
- `ZirconSim`: `fc777e010fc16583243dc165f12175d80e786543`，长期分支 `zircon-2026`。

`.gitmodules` 使用 HTTPS，并声明 `branch = zircon-2026`；实际构建必须服从父仓 gitlink，而不是无条件拉取子模块分支最新提交。任何 submodule bump 都要在父仓 PR 中链接对应子仓 PR、测试报告和精确 SHA。

用于比较的不可变 Zircon-2024 基线不是上述当前 gitlink，而是：

- 核心：`65a3dd381f4c83a5844858a927dafdbc8263c35e`
- RV-Software：`5f81f2ad378f537182e4cf1a0fcb45159509a2ec`
- ZirconSim：`b1694da4a92046edeead50c9b2a1c086a13e6511`

## 2. 用户最新指令和不可擅改的执行原则

1. 最终目标是完整交付 Zircon-2026，不是只完成当前 M1，也不是只搭骨架。
2. ISA 固定为 `RV32IMAF_Zicsr_Zifencei`，单 hart、仅 M-mode。
3. 远端 CI/门禁暂时不必等待，避免浪费交互时间；每个阶段仍必须完成本地验证并把可复现命令、seed 和结果写入 Git。可以推送后继续开发，但不能把尚未观察到的远端结果写成“通过”。
4. 面积签收以 ADR-0009 定义的**可复现静态面积评估**为强制门槛。无需为了签收等待或安装 Vivado 2026.1；若已有 Vivado 数据，只作为可选旁证，不能取代静态 ledger。
5. 不允许通过删减冻结 ISA 行为、Cache/LSU 功能、异常精确性或验证范围来伪造面积/进度达标。
6. GitHub 留痕顺序仍是 Issue → ADR（仅架构变化）→ 模块规格 → RTL/软件/仿真 PR → 本地/CI 证据 → 合并。远端门禁可异步，但工作记录不可省略。
7. 不得把未实现结构在面积表中记为 0，不得把空顶层的低资源数字当成完整 Zircon-2026 面积。
8. 不得使用非法指令作为仿真 pass/fail 哨兵，不得使用 `time(NULL)` 或墙钟作为隐式随机 seed。

**执行口令：NO REMOTE WAIT。** 每次完成本地门槛后直接 commit、push，然后立即做下一项，不轮询 GitHub Actions、不因远端 job 排队或运行而暂停；只有用户后来明确要求查看远端门禁时才检查。远端失败一旦被明确告知仍要修复，但不能把等待远端结果放在关键路径上。

## 3. 最终产品与验收定义

最终核心必须同时满足以下条件，才可以标记 goal 完成并发布 `v1.0.0`：

### 3.1 功能

- 完整实现 RV32I、M、A、F、Zicsr、Zifencei，以及本文冻结的 M-mode CSR、异常和中断。
- 顶层可以从默认 reset vector `0x8000_0000` 通过 AXI4 取指并执行 ELF；可运行程序不能依赖测试平台替 CPU 修改架构状态。
- 两路提交、三路最多启动、两路统一完成、双 LSU、非阻塞 L1D、排他 L1D-L2、ordered MMIO 合并、miniTAGE 和完整 FPU 都是可运行实现，不是接口占位。
- trap、interrupt、MRET、CSR 写和不可撤销 MMIO/AMO 均满足精确提交语义。

### 3.2 正确性与验证

- 标准测试、Spike 快速差分、Sail 夜间/争议复核、ACT4、RISCV-DV、TestFloat、适用 riscv-formal 和项目定向测试全部无未解释失败。
- 强制功能覆盖点 100%；代码覆盖目标 line ≥95%、branch ≥90%、toggle ≥85%。覆盖豁免必须有 Issue、原因/不可达证明、责任人和失效里程碑。
- milestone 回归累计至少十亿条退休指令：10,000 seeds × 100k，所有失败都可由保存的 seed、ELF、工具 SHA、trace 和波形复现。
- decoder、exception priority、MSHR、MMIO arbiter 的预设 mutation 必须全部被验证集杀死。

### 3.3 性能

- 与 Zircon-2024 使用相同 RV32IM ELF、设备模型、初始内存、seed 和 AXI 延迟模型。
- 通用负载几何平均 IPC 不低于 Zircon-2024；控制/不规则访存重点负载至少提升 20%。
- L2 默认 4 KiB。相对 8 KiB 点，通用负载几何平均下降不超过 3%，任一关键负载下降不超过 10%；否则正式配置回退 8 KiB。
- nominal memory profile：首 beat 30 周期、额外确定性抖动 0–15 周期、10% channel backpressure；另报告 fast/slow profile。
- 报告 IPC、周期、branch MPKI、L1/L2 miss、MSHR 占用、平均并行 miss、store-forward 命中、MMIO 合并率、各类 stall 和中断延迟。

### 3.4 面积

- 静态面积 ledger 必须覆盖完整 Zircon-2024 和完整 Zircon-2026，而不是阶段性子集。
- 按 ADR-0009 对存储 bit、多端口复制、CAM/比较、宽 mux、仲裁器、乘除/FPU 算术资源做代理并提供逐模块 manifest、生成脚本和差异解释。
- 完整 Zircon-2026 的所有强制静态面积类别都不得高于 Zircon-2024；不同资源类别不得互相抵消。
- DSP/乘积资源设计目标仍是共享 16×16 部分积单元，等价 DSP 预算不超过 4；实现中不得复制整数乘法、浮点乘法与 FMA 乘积资源。
- 当前 PR #42 的**部分**清单曾得到 2024 为 90,828 bit、2026 为 54,032 bit（-40.5%），但报告明确为 `PARTIAL`；这不是最终面积签收。

## 4. 冻结的微体系结构合同

### 4.1 前端和预测

- 4 指令取指、2 指令译码、2 指令提交。
- 最终 miniTAGE：Base 512 项、2-bit counter；3 个 tagged table 各 128 项，历史长度 4/16/64，tag 7/8/9 bit，3-bit counter、2-bit useful。
- 64 项、2-way BTB；8 项 RAS。
- 推测更新全局历史；分支检查点支持误预测恢复；预测表仅在提交后训练，避免错误路径污染。
- 条件分支、JAL、JALR 只能进入 E0，E0 是唯一控制流重定向源。
- `FENCE.I` 提交时排空旧 store/MMIO，失效 I-Cache 与 BTB，清空前端并从后继 PC 重新取指。
- 当前已有 banked bimodal、BTB/RAS、predecode、speculative history 和 4-entry FetchDecodeQueue。先复用这些模块完成可执行 M1；M4 再以 miniTAGE 替换临时 bimodal。不要新造一套平行预测状态。

### 4.2 后端容量和数据放置

- ROB 24 项；整数物理寄存器 56×32 bit、逻辑目标 6R2W。
- IntIQ 12、LongIQ 4、MemIQ 8；LQ 8、SQ 8。
- Branch Data Buffer 8 项、1R1W。
- 每周期最多启动 3、完成/写回 2、提交 2。
- IQ 只保存紧凑 `UopRef`；PC、完整译码、预测元数据、异常信息集中在 ROB/BDB。最终 IQ 状态 bit 相对 Zircon-2024 至少下降 30%。
- 每个短流水线 1 项 completion skid buffer；LongPipe 和 LSU 各 2 项完成缓冲；两个统一完成端口仲裁。
- ROB 不为每项保存完整异常载荷；单个按 ROB 年龄更新的 `FirstFaultRecord {robTag,cause,tval}` 保存最老异常。

### 4.3 执行端点

| 端点 | 操作 | 硬约束 |
|---|---|---|
| E0 IntCtrl | RV32I 整数、比较、branch、JAL/JALR、CSR、ECALL/EBREAK、MRET、WFI、FENCE/FENCE.I | 唯一控制流和系统端点 |
| E1 IntSimple | RV32I 简单整数、移位、比较、地址无关操作 | 不接受 branch、CSR、M、F |
| E2 LongPipe | RV32M；F add/sub/mul/div/sqrt/FMA/转换/比较/分类/min/max/sign-inject/move | E1/E2 共享一组取数入口；ready/valid 可变延迟 |
| M0 LSU-General | 全部整数/浮点 load、全部 store、LR/SC、AMO、MMIO | 唯一 store、atomic、device 端点 |
| M1 LSU-Load | 对齐、可缓存 LB/LBU/LH/LHU/LW/FLW | device/store/atomic/非法 PMA 必须 replay 到 M0 |

E2 面积优先：整数乘、浮点乘、FMA 共享 16×16 部分积单元；整数 div/rem、浮点 div、sqrt 共享迭代引擎。任何调用者都只能依赖 ready/valid，不能假设固定 latency。

FPR 为 32×32 bit、2R1W，不做浮点重命名。scoreboard 阻塞 FPR RAW/WAR/WAW；FMA 分两周期读取三个源；FP 结果进入 4-entry result queue，在提交时写 FPR 并累积 `fflags`。

### 4.4 Cache、LSU 和内存顺序

- L1I：1 KiB、2-way、32 B line。
- L1D：1 KiB、2-way、32 B line、4 word bank、双 tag 查询。
- L1D 非阻塞：4 MSHR，支持 hit-under-miss、miss-under-miss、同 line secondary merge。
- L2：默认 4 KiB、4-way、32 B line、32 set、4 MSHR、2-entry victim/writeback queue；保留 8 KiB 参数用于对照/回退。
- L2 的 I/D 占用动态分配，不固定 way。
- D 侧严格排他：稳定状态下一个数据 block 只能属于 L1D、L2 或 transfer buffer。L1D fill 时删除 L2 副本；L1D eviction 时写入 L2。I 侧 non-inclusive。
- write-back、write-allocate。store 提交前只在 SQ，提交后才能对 Cache 产生不可撤销修改。
- load 只有在所有更老 store 地址已知后才可发射；按 byte 从 SQ 转发；部分覆盖时合并 SQ 与 Cache 数据。不实现大面积 memory-dependence predictor。
- 非对齐 instruction/load/store/AMO 一律精确异常，不跨 beat 拆分。
- LR/SC reservation 粒度 32-bit word；本 hart 冲突 store/AMO、trap、reservation 替换使其失效；device 不允许 atomic。
- `aq/rl`、FENCE 通过排空相应 load/store/device 队列满足 RVWMO。
- 双 LSU 必须定义并覆盖 dual hit、hit/miss、dual miss、same bank/set/address、partial forwarding、MSHR full、victim full 的 stall/replay 行为。

### 4.5 连续 MMIO 合并

- PMA 将地址分为 `Memory`、`DeviceStrong`、`DeviceBurstable` 和不可访问；只有 M0 生成 device request。
- `DeviceStrong` 每条访问独立上总线。
- `DeviceBurstable` 最多合并 ROB 中 4 条连续、同方向、同访问宽度、地址相邻的 MMIO 指令。
- 不跨 PMA 区域或 AXI 4 KiB 边界；方向/宽度/连续性变化立即结束 group。
- 总线上同时最多一个 ordered device group；后续 device request 不得越过。
- read beat 精确映射回原指令；整组成功响应后才允许相关指令提交。
- MMIO、AMO、device write 等不可撤销操作必须等待 AXI response。普通 Cache miss、div、FPU 可以逻辑取消来响应中断，但已被 AXI 接受的事务必须后台 drain。

### 4.6 特权、异常、中断和 FPU

只实现 M-mode；不实现 U/S、MMU、页表、PMP、delegation、debug module、NMI。

必须实现 CSR：`mstatus`、`misa`、`mie`、`mip`、`mtvec`、`mscratch`、`mepc`、`mcause`、`mtval`、`mhartid`、`mvendorid`、`marchid`、`mimpid`、`mcycle[h]`、`minstret[h]`、`fflags`、`frm`、`fcsr`。

- `mstatus`：MIE/MPIE/MPP/FS/SD，MPP 是 M-mode WARL。
- `misa` 固定报告 RV32 I/M/A/F。
- `mtvec` 支持 Direct 和 Vectored。
- 外部 `meip/msip/mtip`，优先级 MEI > MSI > MTI。
- 同步异常：instruction misaligned/access fault、illegal、breakpoint、load/store/AMO misaligned/access fault、M-mode ECALL。
- trap、CSR write、MRET redirect 只在 commit point 发生。
- 没有不可撤销总线事务时，pending interrupt 到进入 handler 最多 8 core cycle；等待 MMIO/AMO response 时可由外部 response 延长，并由性能计数器记录。
- FPU 支持 RNE/RTZ/RDN/RUP/RMM、dynamic rounding、subnormal、±0、NaN、Infinity 和累积 flags。浮点异常只更新 `fflags`，不 trap；`FS=Off` 时 F instruction 为 illegal instruction。

### 4.7 顶层 AXI4 和 trace

- 32-bit address/data、4-bit ID 的标准 AXI4 master，完整 AR/AW/W/R/B 以及 LEN/SIZE/BURST/LOCK/CACHE/PROT/QOS/RESP；无 USER；master 只生成 INCR burst。
- 最多 4 个 outstanding read burst、1 个 write burst；Cache refill 8 beat，MMIO burst 最多 4 beat。
- 仿真专用 `RetireEvent[2]` 含 order、PC、instruction、privilege、GPR/FPR writeback、CSR change、memory address/mask/data、trap/cause/tval、interrupt、fflags；综合配置完全删除 trace IO。
- AXI RRESP/BRESP error 转成对应指令的精确 access fault；未知 ID、重复 beat、错误 RLAST 必须由 assertion 立即终止仿真。

## 5. 已完成实现与证据

以下是代码中已经存在的能力，接管者应复用并维护，不要无理由推倒重写：

- 配置、AXI4 类型、顶层 interrupt/optional trace 接口。
- PMA 分类和 `OrderedIOCombiner` 的局部合并逻辑。
- banked bimodal predictor、BTB/RAS、control predecode、speculative history、FetchDecodeQueue。
- 24-entry ROB tag order、56-entry rename/PRF/ready table、12-entry integer IQ、operand read、E0/E1 short pipe、completion/writeback 仲裁。
- BranchDataBuffer、branch recovery controller、selective tail rollback。
- 紧凑 `UopRef` 和足够的 dispatch/rename/issue/recovery 组合。
- `FirstFaultTracker`、two-wide `CommitController`、`MachineCSR`、`CommitCSRSubsystem`。
- `M1BackendSubsystem` 已覆盖：依赖 `ADDI → CSRRW → CSR read`、ECALL trap、FENCE 排空回压、MSI → handler → MRET，以及既有 dual-issue RAW、mispredict recovery、连续 branch、lane-1 fault。
- 交接前 `./scripts/sbtw compile` 成功，`./scripts/sbtw test` 完成 36 suites / 154 tests、154 全部成功，`make verilog` 成功；PR #43 的远端 `rtl-unit` 检查成功。新增取指模块尚无单元测试，因此上述回归只证明它可编译，不代表它已经完成验证。

关键合并历史：

- #35：integer issue/execute/writeback loop。
- #36：BDB 与 branch recovery controller。
- #37：dispatch/rename/integer execution/recovery/FirstFault composition。
- #38：commit 与 machine CSR subsystem。
- #41：静态面积签收 ADR。
- #42：静态面积 ledger（交接时待合并）。
- #43：M1 backend + CSR/System commit integration（交接时待合并）。

详细进度以 [`docs/STATUS.md`](docs/STATUS.md) 为证据索引；规格在 [`docs/architecture`](docs/architecture)，ADR 在 [`docs/decisions`](docs/decisions)，验证总计划在 [`docs/verification/plan.md`](docs/verification/plan.md)。

## 6. 当前 WIP：第一个可执行 M1 核

### 6.1 已写但未完成

[`src/main/scala/zircon/frontend/AXIInstructionFetch.scala`](src/main/scala/zircon/frontend/AXIInstructionFetch.scala) 是一个临时的单 outstanding AXI instruction-fetch transport，用于在完整 L1I 之前尽快建立可执行 RV32I 顶层。它当前：

- 以最多 4-word INCR burst 取指，并在 4 KiB 边界缩短 burst；
- AR backpressure 时保持请求；
- redirect 后对已经接受的旧 AXI read 做后台 drain；
- 把非 OKAY/EXOKAY RRESP 转成逐 word instruction access fault；
- 对未知 ID、错误 RLAST、错误对齐 redirect、跨 4 KiB burst 做断言；
- 已通过编译，尚未有 test，尚未接入 `ZirconCore`。

该 transport 是 M1 执行闭环的过渡实现，M3/M4 的正式 L1I 可替换它，但替换时必须保留已验证的 AXI、redirect/drain、fault 语义。

### 6.2 立即续作顺序

严格按以下最短闭环推进，除非测试证明需要先修复基础模块：

1. 为 `AXIInstructionFetch` 增加定向测试：正常 4 beat、临近 4 KiB 的 1/2/3 beat、AR 长时间 backpressure、AR 接受前/后 redirect、Receive/Present/Drain redirect、RRESP error 的 fault address、未知 ID、早/晚 RLAST。
2. 修正测试发现的状态机问题；尤其证明“未接受 AR 不会被静默撤回”和“已接受事务一定被完整 drain”。
3. 建立 `M1Frontend`，复用现有 predecode、bimodal/BTB/RAS、speculative history 和 FetchDecodeQueue，把 fetch packet 转成两路 decode/dispatch 流。
4. 明确每个 fetch packet 内多 control-flow instruction 的选择、taken mask、next PC 和历史 checkpoint；只让最早有效 redirect 生效。
5. 补足 ROB/commit 暴露：interrupt 精确 EPC 所需的 ROB head PC/valid，以及 trace 所需的真正 faulting entry/retire metadata。禁止通过猜测 PC 或重新 decode 来伪造精确状态。
6. 实现 `RetireTraceFormatter` 或等价模块；`order` 对双退休严格单调，每条指令只出现一次；trace-disabled 配置不保留硬件端口/状态。
7. 把 frontend、`M1BackendSubsystem`、commit redirect、execute recovery 和 AXI read channel 接入 `ZirconCore`。重定向优先级至少为 commit-time trap/MRET/FENCE.I 高于 execute-time mispredict，高于预测 next PC。
8. 当前尚无 LSU/LongPipe 时，不得让 memory/M/A/F uop 进入会假完成的路径；将对应 capacity/ready 设为 0 或产生合法 illegal/fault，直到真实端点存在。
9. 写完整顶层 directed tests：顺序 RV32I 程序、双发射依赖、taken/not-taken branch、JAL/JALR、mispredict flush、illegal、ECALL trap、MRET、CSR、FENCE、FENCE.I、interrupt、AXI backpressure/error。测试必须从 AXI memory model 喂指令，最终观察 retire/architectural state。
10. 在 `ZirconSim` 接入确定性 ELF loader、AXI memory/device model 和 retire trace，运行最小 `tohost` 程序；子模块修改独立 PR，再 bump 父仓 gitlink。
11. 完成 Spike commit-level differential smoke，才能把 M1 标为“可执行 RV32I”。
12. 更新模块规格、`docs/STATUS.md`、验证映射和静态面积 manifest；本地运行 `./scripts/sbtw test`、`make verilog`，提交 feature PR。远端门禁可以不等待。

## 7. 从当前断点到最终交付的里程碑清单

每个项目只有在实现、规格、定向测试、随机/差分证据和状态页均更新后才能打勾。

### M1 / `v0.2-rv32i`：先完成当前闭环

- [ ] 完成第 6 节所有工作，使顶层真正执行 RV32I ELF。
- [ ] 4-fetch/2-decode/2-commit 前端到后端完整连接。
- [ ] bimodal 临时预测、branch checkpoint、mispredict recovery 可运行。
- [ ] illegal/ECALL/EBREAK/MRET/FENCE/FENCE.I/WFI、计数器和精确异常全部进入 commit。
- [ ] 全部 RV32I directed、ACT4 I/Zicsr 适用子集、Spike/Sail 差分通过。
- [ ] 发布 `v0.2-rv32i`，附规格、测试命令、覆盖和已知限制。

### M2 / `v0.3-rv32im`：RV32M 和多发射

- [ ] 实现 E2 LongPipe 的全部 MUL/MULH/MULHSU/MULHU/DIV/DIVU/REM/REMU。
- [ ] 建立共享 16×16 部分积资源和迭代 divider，外部仅 ready/valid。
- [ ] 完成三启动/两完成仲裁和 E1/E2 共享取数入口。
- [ ] 覆盖所有 endpoint producer→consumer RAW、完成同周期、回压、kill/replay、双提交边界。
- [ ] 覆盖 div-by-zero、INT_MIN/-1、signed/unsigned 边界。
- [ ] 开始统一 RV32IM IPC 对比并给回退归因。
- [ ] 发布 `v0.3-rv32im`。

### M3 / `v0.4-memory`：双 LSU、Cache、A、AXI4、MMIO

- [ ] 实现 M0/M1、MemIQ、LQ/SQ 和地址/PMA/replay 路由。
- [ ] 完整按 byte store-forwarding 和 partial merge；更老 store 地址未知时阻塞 load。
- [ ] 实现正式 L1I、4 MSHR non-blocking L1D、exclusive L1D-L2、动态 I/D L2、victim/writeback queue。
- [ ] 实现 AXI 最多 4 read burst/1 write burst、8-beat refill、ID/beat/RESP accounting。
- [ ] 实现 LR/SC、全部 RV32A AMO、reservation invalidation、aq/rl、FENCE。
- [ ] 把 `OrderedIOCombiner` 接入 ROB/M0/AXI/commit，完成 1–4 beat ordered device groups。
- [ ] 完成 Cache/LSQ/AXI assertion/formal、random backpressure/error injection、memory stress。
- [ ] 发布 `v0.4-memory`。

### M4 / `v0.5-imaf-priv`：F、完整中断、miniTAGE

- [ ] 实现 FPR 2R1W、scoreboard、two-cycle FMA source read、4-entry result queue。
- [ ] 实现全部 RV32F 运算、转换、compare/class/min/max/sign-inject/move。
- [ ] 正确实现五种 rounding、dynamic rounding、NaN/subnormal/±0/Infinity 和 `fflags` 累积。
- [ ] 完成 MEI/MSI/MTI、WFI、Direct/Vectored mtvec、interrupt cancel/drain、8-cycle latency gate。
- [ ] trap/interrupt 破坏 LR/SC reservation；不可撤销 bus transaction 延迟中断并计数。
- [ ] 以冻结的 512-base + 3×128 tagged miniTAGE 替换临时 bimodal，完成 allocation/useful aging/history restore/commit-only training。
- [ ] TestFloat、F differential、ACT4 IMAF/privileged 和 interrupt injection 通过。
- [ ] 发布 `v0.5-imaf-priv`。

### M5 / `v0.9-closure`：性能和面积收敛

- [ ] 合并/扩展 PR #42，使静态 manifest 覆盖最终全部模块和 2024 固定基线。
- [ ] 使用相同镜像和 nominal/fast/slow AXI profile 跑完整 workload suite。
- [ ] 运行 4 KiB/8 KiB L2 A/B，只按 3%/10%冻结正式容量。
- [ ] 达成通用 IPC 不退、重点负载 +20%。
- [ ] 优先压缩多端口阵列、IQ/ROB 重复字段、completion ports 和 LongPipe；不得删 ISA 行为。
- [ ] 每个静态面积强制类别不高于 2024，乘积/DSP 等价预算 ≤4。
- [ ] 连续两轮仍不能同时达标时，停止盲目微调，提交带数据的架构决策 Issue，选择可验证的结构性方案后再继续。
- [ ] 发布 `v0.9-closure`。

### M6 / `v1.0.0`：验证闭环和发布

- [ ] PR、nightly、milestone 三层回归都落地并可复现。
- [ ] 完整 ACT4、10,000×100k differential、Spike+Sail、AXI stress、TestFloat、formal、mutation 全部关闭。
- [ ] 强制功能 coverage 100%，代码覆盖达标或只有合规豁免。
- [ ] 至少十亿条退休指令，零未解释失败。
- [ ] 发布架构总规格、程序员手册、集成手册、验证报告、性能报告、静态面积报告、已知限制。
- [ ] 父仓固定两个通过 release gate 的子模块 commit。
- [ ] 建 GitHub Release `v1.0.0`；此时才可声明整个 GOAL 完成。

## 8. 必须实现的验证矩阵

### 8.1 指令与流水线组合

- 每条指令：min/max immediate、x0、same source、same destination、符号边界、overflow、div-zero、INT_MIN/-1。
- 每个 producer endpoint 到每个 consumer endpoint：RAW/WAR/WAW、same-cycle completion、双提交、slot0/slot1 fault、branch+fault、CSR+interrupt。
- branch：冷启动、alias、历史折叠冲突、tag replacement、useful aging、同 fetch block 多 branch、RAS overflow/underflow、wrong-path restore。

### 8.2 LSU/Cache/A/MMIO/AXI

- dual hit、hit/miss、dual miss、same bank/set/line/address、MSHR full、secondary merge、dirty victim、partial byte forward、load/store order reversal。
- L1D↔L2 所有转移、invalidate、refill/writeback overlap，并持续 assertion “同 block 唯一 owner”。
- 每个 AMO、LR/SC 成功和全部失败原因、aq/rl、trap/interrupt 破坏 reservation。
- MMIO 1–4 beat、read/write、不同 width、地址断开、PMA/4 KiB boundary、strong-order、各 AXI channel 独立 backpressure 和 RESP error。
- AXI 合法任意 channel interleaving、跨 ID read reorder、random backpressure、reset、unknown ID、duplicate/early/late beat。

### 8.3 F 和特权

- 五种 rounding、dynamic rounding、reserved encoding、qNaN/sNaN、Infinity、±0、subnormal、overflow/underflow/inexact、FMA single-round、`fflags` accumulation、FS=Off。
- 每种 exception/interrupt 在每个 pipeline stage、双提交边界、Cache miss、long div/FPU、WFI、MRET、MMIO response 前后到达。
- 验证 MEI > MSI > MTI、Direct/Vectored address、mstatus MIE/MPIE/MPP transition、mepc/mcause/mtval exactness。

## 9. 回归、失败证据和参考模型

固定第三方 SHA 在 `toolchain.lock.json`。主要版本为 Java 21、Scala 2.13.18、sbt 1.12.4、Chisel 7.14.0、Verilator 5.050、LLVM/Clang/LLD 22.1.8。软件 F 配置使用：

```bash
-march=rv32imaf_zicsr_zifencei -mabi=ilp32f
```

与 Zircon-2024 公共性能比较使用：

```bash
-march=rv32im -mabi=ilp32
```

参考模型和套件：Spike（快速退休级差分）、Sail（夜间/争议）、ACT4 + Unified DB（认证）、RISCV-DV（随机流）、riscv-tests（基础宏）、TestFloat（FP 边界）、riscv-formal/RVFI（适用性质）。性能负载包括 Embench IoT、CoreMark、Dhrystone、现有 CFFT/matmul/cholesky，以及固定数据集 pointer chasing、hash table、BFS、branch state machine。

最低回归层级：

- PR：全部 unit/directed、ACT4 smoke、20 seeds × 10k differential。
- Nightly：完整 ACT4、500 × 50k、Spike+Sail、AXI random stress、bounded formal。
- Milestone：10,000 × 100k，累计至少 1B retired instructions。

每次失败保存并在报告中链接：generator seed、memory seed、RTL/submodule/tool SHA、ELF/hash、最小化 instruction stream、retire trace、waveform；按 root cause 聚类。禁止仅通过增加 timeout 或过滤 mismatch 让回归变绿。

持续断言至少包括：retire order monotonic、x0 zero、wrong-path no architectural update、precise exception、request no loss/duplication、MSHR credit conservation、Cache exclusivity、MMIO sequence monotonic、LR/SC legality、AXI beat/ID/last matching。

## 10. GitHub 与文档纪律

- `main` 和两个子仓 `zircon-2026` 分支禁止 force-push；修改经 feature branch + PR。
- 每个模块规格必须包含参数、接口表、pipeline stage、state machine、drain/replay/exception rule、invariant、performance counter、verification mapping。
- PR 描述必须给出 interface change、architecture reason、test commands、seeds、coverage delta、IPC/static-area impact、related Issue。
- 大波形/日志做 artifact；仓库保存稳定摘要和复现命令。
- 每个 milestone 都更新 `docs/STATUS.md`，生成 release，并附规格、coverage、IPC、静态面积、known limitations。
- 代码是实现真值，规格是行为合同，测试/报告是完成证据；三者冲突时必须开 Issue 并修到一致，不能静默选择对自己有利的一项。

## 11. 关键不变量和常见陷阱

继续工作时始终保护以下约束：

1. commit-time trap/MRET/FENCE.I redirect 优先于 execute mispredict；错误路径永不修改架构状态。
2. E0 是唯一 control-flow/system endpoint；当前 CSR/System side effect 使用单个 tagged slot，只有命中 ROB head 才提交。
3. 已接受 AXI transaction 即使被 redirect/interrupt 逻辑取消也必须 drain；valid 在 ready 前不得改变 payload 或静默撤回。
4. 尚未实现的 Mem/Long endpoint 必须 backpressure/illegal，不能返回虚假 completion。
5. store 在 commit 前不可修改 Cache；MMIO/AMO/device write 必须等 response 才能 commit。
6. FirstFaultRecord 永远指向按 ROB 年龄最老 fault；lane-1 fault 不得越过 lane-0。
7. trace 必须来自真实 commit metadata；不要从当前 fetch PC 或总线响应重新构造退休事件。
8. 同一 D block 稳定时只能在 L1D、L2、transfer buffer 三者之一。
9. FPR 不 rename；所有 RAW/WAR/WAW 依赖必须由 scoreboard 保证。
10. 静态面积清单缺项必须标 `missing/PARTIAL`，绝不能按 0 计入优势。

## 12. 接管后的第一次提交应该达到什么

最合理的第一个服务器 PR 是“完成并验证 AXI instruction fetch transport”，而不是一次性把整个 M1 顶层塞进一个巨型提交。它至少应包含：

- `AXIInstructionFetchSpec` 覆盖第 6.2 节第 1 项全部情形；
- 修正后的 transport RTL；
- 一份模块规格，明确状态、redirect/drain/fault/AXI invariant；
- 本地 `./scripts/sbtw test` 和 `make verilog` 证据；
- `docs/STATUS.md` 仍将可执行顶层标为 partial，直到真实 ELF 运行和差分通过。

然后继续 M1Frontend、top integration、ZirconSim 和 differential，不应在完成首个小 PR 后停止整个 GOAL。

## 13. 停止条件

只有以下两种情况可以停止并向用户请求决策：

1. 需要改变本文冻结的 ISA/架构/验收门槛，且现有证据无法在原合同内继续；必须给出可复现数据、至少两个方案及其面积/性能/验证影响。
2. 缺少无法从仓库、公开上游或现有工具恢复的凭据/硬件/外部权限，且已经完成所有不依赖该条件的工作。

普通 bug、测试失败、未安装可选 Vivado、远端 CI 尚未结束、实现工作量大，都不是停止理由。最终任务的终点是完整 `v1.0.0`，不是“给出下一步建议”。
