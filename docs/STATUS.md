# Zircon-2026 实施状态

2026-09-04 本地增量证据：FLW/FSW 接入双 LSU、L1D/L2、精确 FPR completion 和
retire metadata；`FloatingAdmissionSpec`、`MemoryAddressUnitSpec`、
`DualMemoryLoadCompletionSpec`、`FloatingScoreboardSpec` 聚焦回归为 17/17，
`make test-m4-fp-move` 为 20/20（组件）加 11/11（顶层 RV32F），`make verilog`
成功。修复了 source-less floating operation 的 scoreboard release 幂等性；新增
`MiniTagePredictor` 并接入 `M1Frontend`，其最小回归为 2/2、前端回归为 4/4，
顶层 AXI-fed RV32I/FMA 子集为 2/2。完整
`CoreShellSpec` 当前 108 个场景，不能作为每次改动的快速门禁；其中“selected dirty
L2 victim”曾可在未改动的 `af0904f` 基线复现；本轮修复了显式 BRAM 一拍读地址未匹配
就消费旧 line payload 的问题，该场景及相关 L2/L1I 回归现已通过。后续采用聚焦门禁
加完整夜间回归，保留所有非重复 corner-case 覆盖。

同日 `LongPipe` 将 MUL/MULH/MULHSU/MULHU 的 signedness 变换统一到一个 raw
unsigned product 和符号修正网络，保持四个 16x16 partial-product 单元边界；
`LongPipeSpec` 5/5、顶层 RV32M 2/2 通过。固定器件综合正在后台运行，尚未据此宣称
DSP/LUT 或 100 MHz 时序达标。

随后撤回了仅供实验的 24-bit LUT 浮点乘法，改为 `ZirconCore` 内唯一的
`ZirconSharedMultiplier`；整数 MUL 与浮点 MUL/FMA 通过互斥 E2 请求共享四个
16x16 partial-product 单元。`FloatingMovePipeSpec` 12/12、`LongPipeSpec` 5/5、
两条顶层 FMA/RV32M 场景 2/2 及 `make platform-verilog` 均通过。此前的
`mul24-lut` synthesis-only 运行超过 28 分钟未产生 utilization report，已终止，
不能作为当前结构或 FPGA 门槛证据。

2026-09-04 对当前共享乘法器/L2 修复运行固定器件 `xc7a200tfbg676-2L` 的
`FPGA_SYNTH_ONLY=1` 实验；Vivado 初步映射显示 L1D/L2 与 BRAM wrapper 采用
XPM/RAMB，DSP 表显示四个 `16x16` 乘法 primitive。该运行在约 30 分钟时仍停在
timing optimization，按约定终止，未生成 utilization/WNS/checkpoint，不能作为
面积或 100 MHz 通过证据。
随后以 `AreaOptimized_medium` 完成同一固定器件的 synthesis-only 实验并保存
checkpoint：synthesis LUT 71,457/134,600（53.09%）、FF 35,185、BRAM tile
133/365（36.44%）、DSP 4/740（0.54%）。层级热点为 M1Frontend 20,888 LUT、
M1BackendSubsystem 20,612、ExclusiveL2TransferStore 9,958 和 DualLSUIngress
6,133；这些数字仍是 synthesis 初步值，正式门禁必须继续执行 place/route、WNS
和最终 utilization。
同一配置的 medium 完整 implementation 已完成 synth/opt/place 并保存三个
checkpoint，但在 route 的 30 分钟预算内因拥塞未完成（overlap 最后降至 2,990）。
post-place 报告为 LUT 72,090（53.56%），setup WNS `-209.860 ns`、TNS
`-6,066,633.854 ns`，共有 56,094 个 failing endpoints；该结果明确不满足
100 MHz，也不能生成 bitstream。层级报告显示 backend/frontend 的大规模组合
选择网络和 floatingMovePipe、memQueue 是主要时序热点，后续必须进行结构流水化
或减少逻辑扇出。

在提交 `83f9345` 后重新生成平台 RTL，并以固定器件 `xc7a200tfbg676-2L`、
`AreaOptimized_medium` 完成 synthesis-only 复核（`fpga/runs/mini-tage-synth`，
10 分 39 秒）：LUT `71,401/134,600 = 53.05%`、FF `35,202`、BRAM
`133/365 = 36.44%`、DSP `4/740 = 0.54%`。该结果比此前 medium synthesis
的 53.13% 略低，但仍高于 50% 目标，且没有 place/route、WNS 或 bitstream
证据，不能视为 FPGA release gate 通过。

随后在同一固定器件和 `AreaOptimized_medium` 指令下复跑了 FMA 结果寄存器版本
（`FPGA_REVISION=fma-reg-synth`）。综合在 11 分钟内完成并生成 checkpoint：LUT
71,513/134,600（53.13%）、FF 35,221、BRAM tile 133/365（36.44%）、DSP 4/740
（0.54%）。相对 `medium-synth` 的 LUT 71,457、FF 35,185，寄存器边界没有带来
面积下降；该运行没有 place/route/WNS 证据，不能作为 100 MHz 通过结果。后续应优先
优化 FloatingMovePipe、backend/frontend 选择网络和高扇出控制，而不是继续复制此类
面积近似不变的综合实验。

同日新增顶层 `MEI > MSI > MTI` 优先级回归，`make test-m4-interrupt-priority`
以三线同时 pending 验证 `mcause=0x8000000b`、精确 EPC、MRET 和被中断指令单次
重执行，结果 1/1。ZirconSim `make -C ZirconSim tohost` 在同一 RTL 上完成 RV32I
prefix 19 条、ALU/branch 34 条、RV32M 19 条和 RV32A 12 条退休的 backing-memory
gate，四项均 `status=tohost`、退出码 0。

同日补齐 WFI 顶层 quiescent/wakeup：WFI 提交会 flush 年轻推测状态并停止前端，启用的
MSI pending 后恢复取指，在下一条 live ROB 指令处产生精确 EPC，再经 MRET 恢复一次。
`CoreShellSpec` 新增确定性场景 1/1（约 18 秒），`CommitControllerSpec` 的 6/6
单元回归也通过。

miniTAGE 当前已完成可执行方向 provider 的 Base/tagged 表、commit-only training、
误预测分配、useful 饱和更新和 scrub；当三张 tagged 表均命中时，误预测不会再因
`PriorityEncoder(0)` 静默覆盖表 0，新增全命中保护回归。`MiniTagePredictorSpec`
与 `M1FrontendSpec` 聚焦回归共 7/7 通过。逐 slot folded-history 查询、完整
alias/替换矩阵和性能收敛仍未完成。

同日将固定深度 2 的 L2 dirty-victim FIFO 从通用 `Queue` 改为显式寄存器环形队列，
`ExclusiveL2TransferStoreSpec` 为 12/12、顶层 FIFO 压力场景为 1/1；目标是消除综合
层级中约 30k LUT 的小深度宽 payload 异步读 mux，待新提交的 Vivado 结果确认收益。

最新本地全回归（2026-09-02）为 63 suites、410 tests，全部通过，耗时 36 分 17 秒；
`make test-m3-dual-load-forward` 最新本地结果为 5 suites、81 tests 加 1 条顶层 core 用例，全部通过，耗时约 4 分 23 秒。
2026-09-03 新增同 set 单 invalid-way 双 miss 矩阵：`make test-m3-dual-resource`
为 1/1（约 5 秒）。该用例验证一条 resident line 占用一个 way 时，较老 miss
独占唯一 invalid way/MSHR，年轻 miss 保持 replay，不产生第二笔 L2 transfer；
older refill 完成后年轻请求才重新取得 ingress。该证据补齐了“两路都 invalid”和
“两路都需替换”之间的资源边界，未改变当前 M3 仍为 partially completed 的结论。
同日新增 `make test-m3-ordered-io-fetch-pressure` 为 1/1（约 11 秒）。固定
seed `0x5eed0401` 在 32-byte 取指包边界故意延迟第二个 ID-1 AR，实测 fetch
backpressure 后首个 DeviceBurstable group 提前合法封口，四个 load 仍以精确
地址/数据 metadata 退休；失败时保存完整 AXI schedule、程序和 retire trace。
`make test-m3-axi-mixed` 的标准入口现已覆盖短流、双 dirty line 长流和
writeback retry 三个独立场景；2026-09-03 实测 3/3 全部通过，约 126 秒。
最新完整 `L1DLoadCacheSpec`（2026-09-03）为 57/57 tests、约 59 秒，仍低于五分钟组件门槛。除既有 MSHR、waiter、dirty-victim L2 backpressure、dirty-victim hit/miss/dual-miss replay、反向 refill response order 和饱和 owner recovery 外，新增用例明确覆盖 dirty victim 已 transfer 到 L2、但本地 demand 尚未发 L2 probe 时的 global flush：flush 只能释放本地 MSHR，不能产生 stale probe、AXI refill 或 completion；后续 fresh miss 建立独立 owner。新压力交叉让一个 dirty-victim transfer 已交给 L2、但其本地 probe 尚未发出，并以三条独立 miss 填满其余 MSHR；global flush 必须释放四项本地 owner、不产生 stale L2/AXI/completion，并允许 fresh miss 获得新 credit。另一个新增 cell 令 lane 1 的 dirty-victim transfer 已被 L2 接收、lane 0 仍是未接受 replay；global flush 必须移除两个 local demand、不产生 stale probe/refill/completion，并允许 fresh miss 获得新 MSHR credit。另一个新增 case 使两个 lane 在同一 cold word 上同时进入 one-MSHR merge，要求仅一次 refill、两个 exact waiter 和按 ROB age 的相同 word completion。2026-09-03 新增 `make test-m3-dual-resource` 资源交叉：已有 live MSHR 接收第二个 exact waiter 时，较年轻的独立 miss 保持 valid，不伪造第二 owner，待旧 refill 完成后再获得独立 MSHR；该 focused test 1/1 通过。最新 waiter recovery cases 则让一条 MSHR 用满八个 waiter 后仅保留最老 tag：在 probe 前，选择性 squash 必须释放其余 credit、允许新同 line waiter 合并；在 probe 已接受后，必须保留 L2/AXI ownership并只完成该 survivor。
`LoadStoreQueuesSpec` 新增两个不重叠 partial store 的逐 byte 合并用例；完整队列回归为 17/17（约 68 秒），验证 mask=0xa 的 forward payload 与 cache word 合并后精确产生 `0xaa22bb44`。该证据通过 `make test-m3-partial-store-forward` 的组件入口复用。
最新完整 `ExclusiveL2TransferStoreSpec`（2026-09-03）为 12/12 tests、75 秒。新增 exact dirty-line cleanup 在两项 victim FIFO 已满时的回压与 dequeue/enqueue 时序验证：原 FIFO 头先 drain，下一周期 cleanup target 才进入 tail，三项 dirty line 的地址与 payload 顺序保持精确。
同 set hit/miss 在存在另一个 invalid way 时现已同拍受理；两路 resident/dirty-victim replacement 仍保持 oldest-only。
不同 set pair 中若年轻 miss 需要 resident victim，`L1DLoadCacheSpec` 证明较老 hit 单独握手，年轻 miss 只在下一周期得到唯一 L1D-to-L2 transfer owner。

最新完整 `L1DLoadCacheSpec`（2026-09-02）为 `40/40`、138.1 秒，取代上述较早的 `39/39` 记录；新增用例证明一个 MSHR 占满八个 waiter 后，被回压的第九条 same-line request 在 refill 后会以 exact-word hit 重新受理。

最新完整 `L1DLoadCacheSpec`（2026-09-02）为 `42/42`、128.0 秒，取代上述 `41/41` 记录；四 MSHR 满载时的第五条独立 miss 现已证明会持续回压，直到真实 AXI refill 返回且该 owner 的唯一 completion 释放 MSHR credit 后才重新受理。新增 dirty-victim miss 在 L2 insert backpressure 下被 full flush 取消的用例，证明未接受 transfer 不会保留 MSHR/L2/AXI/completion owner，原 dirty line 仍为可命中的唯一 L1D owner。
最新完整 `L1DLoadCacheSpec`（2026-09-02）为 `43/43`、127.877 秒，取代上述 `42/42` 记录；新增 full-flush 饱和 case 让四个 MSHR 已满、仅最老 L2 probe 已被接受，证明 flush 会释放三个未发 probe owner，同时把已接受 probe 的 L2 miss response 作为 drain-only 事件消费，不发 AXI refill/不产生 completion；该 owner 释放 credit 后新 miss 可重新受理。

最新完整 `L1DLoadCacheSpec`（2026-09-02）为 `45/45`、132 秒，取代上述 `43/43` 记录；新增较老 different-set hit 在 L2 transfer backpressure 下独立完成、较年轻 dirty-victim miss 保持精确 payload 且不提前分配 owner，以及 same-line merged MSHR 在 squash 仅删除较年轻 waiter 后继续为较老 waiter 唯一完成。组件仿真保持低于五分钟门槛。

different-set 的较老 hit 加较年轻 clean-victim miss 现可在同一周期接收：hit 进入其 retained result slot，miss 只有在唯一 `l2Insert` 真正握手时才得到 MSHR/waiter 和 clean victim transfer；`L1DLoadCacheSpec` 完整 `45/45` 本地为 132 秒。dirty-victim hit/miss 与 dual-miss 仍执行 oldest-only replay，未被此 clean-victim increment 放开。

同一 different-set row 已扩展到 dirty victim：较老 hit 与较年轻 miss 同拍接收时，唯一 `l2Insert` 保留 dirty line 和精确 word payload；L2 backpressure 期间 offer 保持、较年轻 request `ready=0`，只有 transfer fire 才能分配 MSHR/waiter。完整 `L1DLoadCacheSpec` 为 `45/45`、132 秒。dirty-victim dual-miss 仍为 oldest-only replay。

新增 `L1DLoadCacheSpec` 双 lane 资源交叉：L2 insert 被 backpressure 时，较年轻 dirty-victim miss 必须保持其精确 victim payload 且不能提前分配 MSHR；同拍的较老不同 set hit 仍独立被接收和完成。该 directed case 本地为 1/1、3.863 秒；waiter 饱和和 squash/flush 的交叉矩阵仍待完成。

新增同 line secondary-merge recovery：两个 waiter 合并到一个未发 L2 probe 的 MSHR 后，squash 仅清除较年轻 ROB tag；较老 waiter 保留该 owner 并在 refill 后唯一完成，错误路径 waiter 不产生 completion。该 directed case 本地为 1/1、4.380 秒。

新增 `CoreShellSpec` 明确 seed `0x5eed0301` 的错误路径 refill drain 用例：一个错误路径 load 已取得独立 L1D/AXI owner 后，取分支的 older load 和 taken branch 恢复架构路径；分支退休后才释放错误路径的八个 R beat，全部必须 drain，但错误路径 load 不得退休，target `EBREAK` 正常产生 precise trap。`make test-m3-axi-wrong-path-drain` 本地为 1/1、12.545 秒。更广泛的 squash/flush/resource-pressure 矩阵仍未完成。
`make test-m3-dual-load-merge` 的既有 same-bank 子集（2026-09-02）为 `1/1`、33.322 秒；三个显式 seed `0x5eedfc01`--`0x5eedfc03` 以独立 AR/R/AW/W/B backpressure 运行同一 cache line、同一 L1D word bank 的 M0/M1 load pair。新增 same-address resident-hit 子集使用相同三个 seed：先通过 zero-valued preload 和依赖地址计算确保该 line 已 resident，再要求 M0/M1 同周期 ingress、L1D 不得同周期双接受，年轻 owner 必须 replay；随后两个不同 request owner 精确完成，且不得产生额外 AXI refill，并保留两条精确 GPR/memory retire metadata。该子集于 2026-09-03 本地为 `1/1`、32.629 秒；失败包保存 seed、五通道 schedule、程序和 retire trace。
AXI data owner slice 为 9/9 tests（随机部分使用 4 个显式 seed，约 9 秒），并覆盖四 owner 尚未 drain、其中一个已记录 RRESP fault 时的 reset/ID reuse，以及 response credit 未释放时第二个 final R beat 的 backpressure；新增交叉用例使第二 owner 在 final R beat 前累积 `SLVERR`，要求该 beat 持续 backpressure，直至第一 response fire 后原子产生带完整 line、client token 和 access-fault metadata 的第二 response。顶层 cross-ID 双 LSU、四-owner drain 和 reset-owner-epoch slice 各为 1/1（均为 4 个显式 seed）。`make test-m3-axi-stress` 实测约 4 分 16 秒；`make test-m3-axi-long` 为 1/1、约 52 秒，随机交错八条独立 cache-line load 并确认四 physical owner 回收复用；`make test-m3-axi-faults` 当前覆盖两条各 4-seed 的反向 fault-order 路径：年轻 RRESP fault burst 先完成仍保持更老 load 的精确 trap，且年轻 cacheable-load RRESP 先 drain 后仍由更老 ID-6 device-store BRESP 取得精确 trap。`make test-m3-axi-mixed` 现为三个四-seed 场景，实测 3/3 约 143.0 秒：短场景以 `0x5eede001`--`0x5eede004` 混合两条 cache refill、cacheable store、ID-6 device store 和一个 FENCE ID-5 writeback；长场景以 `0x5eedf101`--`0x5eedf104` 扩展为三条 cache refill、两条不同 dirty line 的 cacheable store、ID-6 device store 和 FENCE，并逐 seed 要求两个 ID-5 burst/response、一个 ID-6 transaction 与精确 load/store/FENCE retirement；retry 场景以 `0x5eedf201`--`0x5eedf204` 将首个 ID-5 BRESP 置 error，要求同地址 retry、两个成功 ID-5 completion、一个 ID-6 completion后 FENCE 才可退休。三个 seed `0x5eedf301`--`0x5eedf303` 的 `make test-m3-atomic-axi` 在独立 AR/AW/W ready 与 R/B valid 下连续执行 cache refill、ID-7 AMO、ID-6 device store、FENCE ID-5 writeback；逐 seed 要求 ID-5/6/7 的完整 owner/response 生命周期以及 AMO/cache/device 精确退休 metadata，单次 3-seed 运行 24.8 秒。新增该项前完整 `CoreShellSpec` 为 49/49、12 分 6 秒。reset slice 先 reset 四个 data owner 和一个 partial fault，再 reset ID-6 AW/W、ID-5 WLAST 和 ID-7 AMO AW/W（均在 B 前），最终冷启动重跑并检查无旧 owner/fault/credit 泄漏；ID-5 的 3/3 tests 覆盖 error retry 中的 partial AW/W reset，ID-7 的 7/7 tests 覆盖 AwaitRead、partial AW/W 和 LR reservation reset；两 suite合计 10/10、约 9 秒。更长 multi-owner/组合错误 stress 仍未闭环。
四 seed `0x5eee0001`--`0x5eee0004` 的独立四 owner RRESP-fault slice 先允许所有四条 logical data line 取得 AXI owner，再严格按 youngest 到 oldest drain 全部 eight-beat error burst；每次都只由最老 load 退休为精确 cause-5/tval trap。该 slice 已纳入 `make test-m3-axi-faults`，三条 fault 路径合计约 2 分 25 秒。
`test-m3-atomic-axi` 的最新独立运行（2026-09-02）为 `1/1`、37.1 秒；先前的 24.8 秒测量来自不同筛选集合，不能作为该 target 的当前耗时。
`test-m3-atomic-random` 的最新独立运行（2026-09-03）为 `1/1`、约 36 秒；三个显式 seed 各生成并执行覆盖九种 AMO.W 的程序，在独立五通道 AXI 调度下要求九次 ID-7 AR/AW/B 和每条精确 read/write retire metadata。该回归发现并修复了非 line-base AMO 将 word address 误传给 L2 invalidate 的断言失败；顶层现将 L2 invalidate 对齐到 32-byte line，而 AXI/trace 保留原 word address。
`test-m3-lrsc-random` 的最新独立运行（2026-09-02）为 `1/1`、34.0 秒；三个显式 seed 在独立五通道 AXI 调度下先验证 response-gated LR/SC 成功，再以同 hart store 使新 reservation 失效，要求第二条 SC 无 ID-7 write、`rd=1` 和零 write mask。
`test-m3-lrsc-interrupt` 使用三个显式 seed，在独立五通道 AXI 调度下于 LR retirement 后注入 MSI，并要求 MRET 后的 SC 无 ID-7 write、`rd=1` 和零 write mask；失败包保存 seed、五通道 schedule、程序和 retire trace。
`test-m3-lrsc-errors` 使用三个显式 seed，在 non-line-base word address 的 ID-7 LR response 上注入 `SLVERR`；每例要求唯一 cause-7/tval trap、无 GPR completion 和无后续 SC/ID-7 write，失败包保存完整复现输入。
`test-m3-sc-errors` 使用三个显式 seed，先要求非 line-base LR 成功退休，再在 ID-7 SC response 上注入 `SLVERR`；每例要求唯一 ID-7 write/B response、保留 LR metadata，并由 SC 产生 cause-7/tval trap 而不写回 `rd`。
`test-m3-lrsc-granularity` 使用三个显式 seed，在 LR 与 SC 之间执行不同 cache line 的本 hart store；每例要求 reservation 保持、SC 恰好一次 ID-7 AW/B、`rd=0` 与精确 write metadata，失败包保存完整复现输入。
`test-m3-lrsc-replacement` 使用三个显式 seed，先后对两个不同 word 执行 LR，再对第一个 word 执行 SC；每例要求第二条 LR 替换唯一 reservation、SC `rd=1` 且无 ID-7 write。
`test-m3-atomic-errors` 的最新独立运行（2026-09-02）为 `1/1`、33.7 秒；三个非 line-base AMO seed 在 ID-7 AR/AW/W 已接受后注入 `BRESP` error，逐例要求恰好一次 ID-7 transaction、无 GPR completion 和保存 word fault address 的 cause-7 trap。
`AXIDataReadEngineSpec` 还显式扣留一条已完成 response，同时确认另一 owner 的 final R beat 在 response credit 释放前保持 backpressure，释放后才产生第二条 exact response。ADR-0026 将 formal 置为可选诊断；以上运行时 assertion、directed evidence、coverage、mutation 和 Vivado FPGA evidence 是发布所需的验证链。
cache-global FENCE 的四 seed 压力用例使两条不同 dirty L1D line 依次经 ID-5 writeback；FENCE 与后续指令只在两条成功 B response 后退休。另一四 seed 用例让单条 dirty line 的第一份 ID-5 `BRESP` 为 error、第二份为 OKAY，要求对同一地址恰有两次八 beat writeback，且第二次成功前 FENCE 不得退休。第三个四 seed FIFO-order case 对两条 dirty line 的第一份 B 注入 error，要求 ID-5 AW 严格为 first-line、first-line retry、second-line，禁止 retry 越过下一 FIFO victim。另一个 top-level resource-pressure case 将六条同 L2 set 的 dirty line 推入 L1D/L2，在 B 被扣留时观察两项 retained L2 victim FIFO 满载与 ID-5 busy，并禁止 FENCE/年轻 EBREAK 退休。`make test-m3-fence-pressure` 保持为独立、快速的顶层回归；本地实测 multi-line drain 1/1 为 48.0 秒、single-line retry 1/1 为 47.6 秒、victim-FIFO-full 1/1 为 13.5 秒、FIFO-order retry 1/1 为 48.5 秒，合计约 2 分 45 秒。

不同 set pair 中，较老 hit 或较老 invalid-way miss 与较年轻 dirty-victim miss 的组合现在先只接受较老请求，随后让年轻 miss 独占 L1D-to-L2 transfer，并检查写脏 word 进入 transfer payload；完整 L1D suite 的结果见上。四 MSHR 满载的第五 independent miss 只会在一个 L2-hit owner 完成且释放 credit 后重新受理。victim/L2 saturation 和更广 recovery/flush matrix 仍未闭环。

本页按研发计划顺序记录可由代码、测试或报告验证的状态。`completed` 表示已有实现和自动测试，`partially completed` 表示公共契约或局部模块已存在，`missing` 表示尚无可运行实现。

| 计划项 | 状态 | 当前证据 | 下一门槛 |
|---|---|---|---|
| M0 GitHub 主仓与子模块分支 | completed | 公开父仓和两个 `zircon-2026` 子模块分支已建立并启用禁止 force-push/PR required 保护；基线提交写入 `toolchain.lock.json` | 每个后续 submodule bump 继续链接子仓 PR 与测试证据 |
| M0 固定工具链 | partially completed | Scala/sbt/Chisel/Verilator/LLVM 与第三方 SHA 已锁定；CI 从固定 commit 构建 Verilator 5.050，并从 release commit/package version 固定 LLVM 22.1.8；Actions 固定到 commit；Vivado 版本仅保留为可选旁证 | 为 static-area manifest/script 和标准验证工具补齐可复现安装 |
| M0 2024 脏改动审计 | completed | `docs/migration/zircon-2024-audit.md` | 逐项决定是否移植 |
| M0 2024 固定提交复现 | partially completed | 独立 clone 已完成 Java/LLVM/Verilator build 和 picotest smoke；ADR-0009 static-area manifest/script 已能验证并生成 `PARTIAL` 对照 | 接入确定性同构 AXI profile；补齐 2024 ROB/BDB/Cache/control/logic proxy 清单 |
| M0 可复现仿真底座 | partially completed | ZirconSim 已有显式 seed、ELF32 加载、`tohost` 判定、单元测试和有界 smoke；当前 M0 空闲顶层只允许显式 timeout | 接入 AXI memory/device model、退休 trace 与真实程序执行 |
| 顶层 AXI4/interrupt/trace 接口 | completed | `AXI4MasterPort`、`InterruptInputs`、`RetireEvent` 可 elaboration | 连接取指、提交与异常响应 |
| PMA 分类 | completed | Memory/DeviceStrong/DeviceBurstable/空洞单元测试 | 接入 LSU 和 fetch access fault |
| 有序 MMIO 合并 | partially completed | `LoadStoreQueues` 对 live head 的 DeviceBurstable 等待固定六周期后，跨 ROB generation wrap 预览连续 1--4 beat；`OrderedIOGroupStreamer` 将 immutable group 经 `OrderedIOCombiner` 送往 ID 6，只有 combiner output fire 才统一标记成员 effect-issued，flush/squash 在此之前取消本地 group。`CoreShellSpec` 已验证四连续 load/store 成为一笔 `len=3` ID-6 事务、逐成员 retire metadata 和 AXI completion；DeviceStrong 保持单拍。组件四 seed 覆盖 1--4 beat read/write、独立 AR/AW/W 回压、held response、RRESP/BRESP errors；顶层四 seed 则在 long-div ROB 压力下要求 1--4 retained device owners 成为唯一精确 ID-6 group。完整 group 在六周期 collection 前已由 fetch warm-up 呈现，fetch-pressure 下合法 group-sealing 与更长 mixed traffic 仍待覆盖 | fetch-pressure group sealing、长 mixed traffic、精确提交压力与 formal 矩阵 |
| FirstFaultRecord | completed | 记录仅含 `{robTag,cause,tval}`，以 ROB head 的 modulo-24 距离选择最老异常；commit/CSR 组合已消费/清除同步异常 | 接入完整后端并交叉外部 fault、interrupt 与 recovery |
| M1 RV32I 前后端 | partially completed | 两个后端组合域已接成可运行 `M1BackendSubsystem`：CSR/System 仅在 ROB head 进入 E0，单一 tagged side-effect slot 保留提交写，Zicsr GPR 写回、ECALL/EBREAK 精确异常、MRET/FENCE/FENCE.I/WFI 提交信号和 M-mode CSR state 已闭环；`AXIInstructionFetch` 已有 normal、4 KiB boundary、backpressure、redirect/drain、RRESP 与 protocol-assertion 测试及模块规格；`M1Frontend` 已复用 Base/BTB/RAS/history/FetchDecodeQueue，覆盖 earliest control、targetless JALR barrier、commit training 和 redirect priority；`ZirconCore` 已接入 frontend、M1 backend、commit/execute redirect、AXI read 和 optional `RetireEvent`。`CoreShellSpec` 从确定性 AXI memory 覆盖依赖、branch/JAL/JALR、CSR、ECALL→MRET、FENCE/FENCE.I、software interrupt、AR/R backpressure、RRESP、illegal trap 与 LSU 阻塞；ZirconSim PR #5 的 gitlink `f9086e8` 提供 deterministic ELF/AXI/JSONL retire harness，并以 seed 1 将两组固定 RV32I/Zicsr ELF 的 17 条 CSR/control 和 32 条 ALU/branch 正常 retirements 与锁定 Spike revision 逐项匹配（privilege、PC、instruction、有效 GPR/CSR write）。各后续 `tohost` store 因 M1 无 LSU 而阻塞，明确记为 expected timeout 而非 pass；ROB live head 是 interrupt EPC 的唯一来源，trap trace 使用真实 fault entry/lane | 扩展 RV32I/Zicsr Spike 矩阵；运行 ACT4 I/Zicsr 适用子集和 Sail；真实 `tohost` completion 与 memory differential 等待 M3 LSU |
| M2 RV32M/多发射 | partially completed | `RV32IDecoder` 将八条 `OP/funct7=1` 指令唯一送往 E2；4-entry `LongIssueQueue` 按 ROB 年龄、PRF-ready table 和 squash/flush 维护 compact `UopRef`；`LongPipe` 使用四个 16x16 partial products 和 32-cycle restoring divider，覆盖 MUL/MULH/MULHSU/MULHU/DIV/DIVU/REM/REMU 的零除和 signed-overflow 规则，并经 2-entry E2 completion buffer 接入现有五端点/两完成 router。`ZirconCore` 复用两条 auxiliary PRF read port：trace GPR retirement 优先，其他周期 E2 取数；`CoreShellSpec` 已验证 E1->E2、E2->E2 和 E2->E1 RAW、E0/E1/E2 同周期启动后的 recovery kill、E1/E2 同周期 completion 与双退休，并以 `0x5eed`、`0x5eed1001`、`0x5eed2002`、`0x5eed3003` 覆盖确定性 AXI AR/R backpressure recovery。失败包保存 seed、ready pattern 与 retire trace；测试观测端口不进入默认生产配置。ZirconSim PR #6 的 gitlink `b51863c` 以 seed 1 运行三条 bounded Spike prefix，分别匹配 17 条 RV32I/Zicsr、32 条 RV32I ALU/branch 和 17 条 RV32M 退休记录；同一 RV32M prefix 还与锁定 Sail-RISC-V `beaf44991eee362a062fcaaf6fcb78ca428ff710` 匹配 17 条退休记录。相同 deterministic AXI slave 上，17-retirement RV32M prefix 的 2026 IPC 为 0.07234（235 cycles），固定 2024 baseline 为 0.09140（186 cycles），仅作为 documented microbenchmark，不能代表 release workload。所有 prefix 随后均只接受无 LSU 的预期 `tohost` timeout，不称为 ELF pass | M3 memory path、完整 RV32IM IPC 对照和 `v0.3-rv32im` 发布 |
| M3 双 LSU/Cache/A | partially completed | ADR-0012--0023 冻结了 transaction ownership、双 LSU、Cache、AXI、MMIO、A、L1I/L2 demand ownership、cache-global FENCE、双 lane L1D conflict policy 与 external write/atomic coherence boundary。`ZirconCore` 已接入 MemIQ、DualLSUIngress、直连两路 `L1DLoadCache` request、1 KiB/2-way L1I、1 KiB/2-way/4-MSHR L1D、四个 retained demand owners、ID-5 writeback、ID-6 device、ID-7 RV32A 及精确 memory retire metadata。L1D 会并行 lookup 两条 request，对不同 word bank 的双 hit 捕获两条精确 result；same-bank/same-address pair 只接收最老 ROB tag，两个同 line miss 在有两个 waiter credit 时合并到一个 MSHR/refill，并保留两个精确 owner；different-set cache hit/miss 在 retained hit slot 和 exact miss owner 都可用、且 miss 可合并或拿到 invalid way 时同拍接收；两个 different-set miss 在各自拥有 invalid way、free MSHR 和 waiter credit 时同时分配，后续 L2 probe 保持单发。三个显式 seed 的完整顶层 byte-1 `sb` 与 byte-2 `sh` -> younger `lw` refill 回归要求唯一 data-line refill、精确 store/load metadata 和 `0x11223344 -> 0x1122bb44`/`0xaabb3344` 合并结果。same-set、resident/dirty-victim hit/miss 与需要 merge/transfer 仲裁的 dual-miss 仍只接收最老 tag，绝不伪造第二 MSHR/L2 transfer。FENCE/FENCE.I 先用精确 ROB tag 排空更老 LSQ owner，再依次排空 dirty L1D、L2 victim FIFO 和 ID-5 B response；普通 FENCE 在 B 被扣留时不能退休，FENCE.I 在自修改程序中只会在 writeback、L1I/BTB invalidate、redirect 后重取更新指令。live atomic 在进入 LSQ 前已阻止年轻 M0/M1 memory issue，LSQ `aq` barrier 继续阻止之后 memory issue。L1I local miss 会先 non-destructively probe resident L2 line，命中不占 AXI ID且不转移 D-side owner；成功 AXI I-fill 必须以 clean `instructionInsert` 进入同一 L2 ways，若与已驻留 D line 冲突则 L2 返回驻留数据给 L1I。L2 为动态 I/D 的 4/8 KiB store，D-side 保持 exclusive，含 two-entry dirty victim FIFO 与 ID-5 retry writeback。固定 seed-1 的 RV32I、RV32M 和 RV32A ELF 已通过 trace-retired store 与 AXI backing-memory 双重 `tohost` gate，精确版本、输出和哈希见 `docs/verification/m3-tohost-evidence.md`。同文档记录了锁定 Spike `c09c0cce…` 和 Sail-RISC-V `beaf4499…` 的有界 committed-memory 实测：四个 ELF 各自匹配 19/34/19/12 个有序退休记录及最终 backing memory；Sail adapter 会过滤取指 `X` access，并从 RV32 instruction 推导 `R/W/RW` 的 byte mask。ADR-0023 controller、adapter 和 generic platform boundary 已有 directed unit/full-core evidence；具体 platform master 仍仅允许 single-hart/private-memory 语义。2026-09-03 的 `make test-m3-ordering` 已通过 52+55 项组件测试及 4 个 CoreShell FENCE/atomic 场景；`make test-m3-axi-stress`、`make test-m3-axi-mixed` 和 `make test-m3-external-coherence` 亦全部通过，且提交 `710e7d0` 修复了 BRAM 同步读地址匹配和 resident-L2 store merge。random/formal A stress、剩余 hit/miss/dual-miss resource matrix、具体 platform master 和最终 FPGA 证据仍缺，不得宣称 M3 memory release 已完成 | 扩展 explicit-seed AXI/error/backpressure 与 external-coherence pressure，并完成 dual-LSU conflict matrix、platform wiring 和 FPGA gate |
| M3 direct dual-L1D hit ingress | partially completed | `MemoryQueueIngress` 按 ROB age 从两个 pending load 生成两个 `Decoupled` forward；`LoadStoreQueues` 保留每条精确 byte-forward payload。`ZirconCore` 不再实例化 active `DualLoadForwardArbiter`，而是将两个 cacheable forward 直接交给 L1D 的并行 tag/data lookup；双不同-bank hit 同拍接收并存入两个 exact `LoadCompletion` slot，completion 按 ROB age 输出。same-bank 与 same-address 同拍 pair 只允许最老 tag，年轻 lane 保持 valid 供 replay；两个同 line miss 在有两个 waiter credit 时合并到一个 MSHR/refill，并按 ROB age 返回各自 word 的 completion。一个未发 L2 probe 的 eight-waiter MSHR 在 selective squash 删除全部 waiter 后会释放，fresh miss 不会继承任何 stale owner；一条已 transfer 到 L2 的 dirty-victim miss 则仅释放其本地 MSHR，绝不再发 stale probe/refill/completion。不同 set 的 hit/miss 在 hit slot、waiter 和 MSHR/invalid-way credit 都可用时同拍接收，保留一个 immediate result 与一个精确 waiter；两个 different-set miss 在两个 free waiter、两个 free MSHR 和两个 invalid way 都可用时同时接收，分配 distinct MSHR ID，并由 one-wide L2 probe 依次完成。两个 issued refill 可按任意 MSHR 顺序返回，且每条 response 保持其 exact waiter tag/data。不同 set pair 若年轻 miss 需要 clean 或 dirty victim，则较老 hit 单独握手、年轻 lane replay，下一周期才取得唯一 L1D-to-L2 transfer；两个不同 set dirty-victim miss 同时到达时只接受最老 tag，年轻 lane在下一周期取得独立 transfer，两个 payload 均保持其原 dirty word；若较老 transfer 已接收，squash 较年轻 replay 不得再产生第二笔 transfer。same-set 与需要 merge/transfer 仲裁的 pair 仍保守 replay。recovery 会释放尚未接受 L2 probe 的 killed younger MSHR；已接受 probe 的 killed owner 只 drain response、不产生 completion，随后 survivor 才可 probe；已发 AXI refill 的 squashed younger owner 同样先 drain response，再服务 older survivor。`make test-m3-dual-load-forward` 最新为 5 suites、81 tests 加 1 条 core 用例，约 4 分 23 秒；完整 `L1DLoadCacheSpec` 为 49/49、140.593 秒 | 完成 waiter/victim/L2 pressure 和剩余 squash/flush 矩阵 |
| M3 AXI stress | partially completed | `CoreShellSpec` 以 `0x5eed3004`--`0x5eed3007` 独立扰动 AR/AW/W ready 与 R/B valid；四个 device-write 成功路径要求全部五个 channel 握手，另外四个 case 交替注入 L1D `RRESP` 和 ID-6 `BRESP` error 并检查精确 cause/tval。`0x5eed5001`--`0x5eed5004` 顶层双 LSU case 随机选择 cross-ID R beat、保持每个 ID 内顺序，且确认两个 data owner 均出现并实际交错；`0x5eed6001`--`0x5eed6004` 则先仅放行取指 R，证明四个不同 physical data owner 都在首个 data R 前取得 AR，再由 cross-ID stream drain。`0x5eedfe01`--`0x5eedfe04` 顶层填满四个 retained data owner，并要求第五 cache-line miss 至少等到一个实际 data R handshake 才能获得重用 credit；final R 与第五 AR 同周期保持合法。`0x5eed7001`--`0x5eed7004` reset read/ID-6 owner；`0x5eed8001`--`0x5eed8004` 分别在 ID-5 WLAST 和 ID-7 AMO AW/W 后、B 前 reset；`0x5eed9001`--`0x5eed9004` 随机 interleave 八条 data line，确认四个 owner 均被再分配并保持精确 metadata；`0x5eeda001`--`0x5eeda004` 先 drain younger RRESP fault，仍由 older load 退休 trap；`0x5eedb001`--`0x5eedb004` 已接受更老 ID-6 device write 后先 drain 年轻 cacheable-load RRESP fault，再送更老 BRESP fault，仍由 store 精确 trap。R/B offer 会保持到握手；失败包保存 seed、五条 schedule、response-selector seed 与 retire trace。`make test-m3-axi-stress`、`test-m3-axi-reset`、`test-m3-axi-long`、`test-m3-mshr-pressure` 与 `test-m3-axi-faults` 分别覆盖压力、reset、owner reuse、MSHR saturation 和 reversed-fault slice | 扩展至更长流、组合错误和 formal AXI/credit properties |
| M4 F/interrupt/miniTAGE | partially completed | 顶层中断/trace、MEI>MSI>MTI 仲裁和 Direct/Vectored trap 已有 directed evidence；`FloatingRegisterFile`、四项 ROB-tagged `FloatingScoreboard`、4-entry `FloatingResultQueue` 与 `FloatingCommitState` 已使 FPR、`fflags` 和 FS-dirty 仅在精确 commit 变化。可执行 E2 子集为 `FADD.S`/`FSUB.S`/`FMUL.S`/`FDIV.S`/`FSQRT.S`/四条 FMA、`FMV.W.X`/`FMV.X.W`、三个 `FSGNJ`、`FMIN.S`/`FMAX.S`、`FEQ.S`/`FLT.S`/`FLE.S`、`FCLASS.S`、`FCVT.S.W/U` 与 `FCVT.W[U].S`。FADD/FSUB/FMUL/FMA 与两个转换方向用纯整数 significand/guard/sticky 路径实现 RNE/RTZ/RDN/RUP/RMM；FDIV 使用 51 周期 restoring divider，FSQRT 使用 27 周期 restoring root，并统一覆盖规格化、非正规数、异号抵消、NaN/无穷、除零、overflow/underflow/NX/NV flags。FMA 先锁存完整 48 位乘积，再以 57 位保留精度完成加减和单次舍入。float-to-integer 以 `NX` 报告有效非精确结果，并对 NaN、无穷和舍入后越界产生指定 invalid integer 与 `NV`。dynamic `rm` 从提交态 `frm` 在 dispatch 冻结，5/6 精确 illegal。`mstatus`/`frm`/`fcsr` 未退休写通过 ROB-tagged barrier 阻止之后 F opcode，squash 解除被 kill writer。`FloatingMovePipeSpec` 当前 12/12，相关组件回归 19/19，顶层 RV32F 12/12；已覆盖 FPR RAW/WAR/WAW、NaN/NV、FS-Off、branch/interrupt recovery、无 fetch gap 的两方向 dynamic-RUP conversion、reserved dynamic `frm` trap；FMA memory、TestFloat、完整 interrupt matrix 和 miniTAGE 仍未完成 | 扩展 FPR-source/interrupt-cancellation matrix、miniTAGE，并运行 TestFloat/ACT4/中断回归 |
| M5 IPC/静态面积收敛 | partially completed | ADR-0009 的 validator、comparison report、3 项脚本测试和 CI 入口可用。账本已计入 active L1I/L1D/L2、MSHR/AXI/MMIO/A、external-coherence platform gate、114-bit 浮点 IQ、有效舍入 mode request、result bridge、`mstatus/frm/fcsr` barrier，以及两个方向 FCVT 的 leading-one、align、rounding、range/invalid 组合资源；`make static-area` 当前报告 70,357 storage bit、73,054 mux-input-bit proxy、1,400 priority-select-bit proxy、242 comparator32 proxy、6 adder32 和 2 shifter32 proxy。两侧 manifest 仍不完整，报告为 `PARTIAL`，不是面积签收 | 随 RTL 补齐两侧 manifest，最终以 `--require-complete` 生成完整静态对照并完成同环境 IPC 测量 |
| M6 验证闭环 | missing | 覆盖与随机门槛已定义 | 里程碑十亿退休指令 |

### M4 Executable F Update

The current component evidence is 19 passing tests across the floating move
and admission suites. It covers `FADD.S`/`FSUB.S`/`FMUL.S`/`FDIV.S`/`FSQRT.S`
and all four FMA signs, including a cancellation vector that distinguishes
single-round FMA from multiply-then-add. The existing `make test-m4-fp-move`
evidence also includes 12 AXI-fed CoreShell tests. It covers normal, rounding, non-normal, NaN/Inf, and overflow
vectors, plus dynamic `FCVT.S.W` and `FCVT.W.S` with no fetch gap after an `frm=RUP`
write, exact GPR/FPR retirement, committed `NX`, qNaN `FCVT.WU.S` invalid
result plus accumulated `NV`, and an exact illegal trap for dynamic `FCVT.W.S`
with reserved `frm=5`.

### M1 BTB Distributed-RAM Update

`BankedBranchTargetBuffer` now stores its eight 8-entry way banks through an
asynchronous-read `BranchTargetMemory` wrapper. Simulation keeps the existing
zero-latency read/write contract while Vivado maps the 64-bit storage to
distributed RAM. On `xc7a200tfbg676-2L` with `AreaOptimized_medium`, the
synthesis-only report (`fpga/runs/btb-lutram-synth/utilization_synth.rpt`)
measured LUT 65,546/134,600 (48.70%), LUT-as-memory 352, FF 31,491, BRAM
133/365, and DSP 4/740. This is structural evidence only; no place/route,
WNS, or bitstream release claim is made. `BranchTargetBufferSpec` and
`M1FrontendSpec` pass 9/9 after regenerating platform RTL.

### M3 L2 BRAM Update

`ExclusiveL2TransferStore` now stores each way in an explicit `L2LineMemory`
bank. Its simulation branch follows the Zircon-2024 registered-address RAM
pattern, while Vivado selects XPM block RAM with one-cycle read latency.
`ExclusiveL2TransferStoreSpec` (12/12), `L1DLoadCacheSpec` (56/56), and
`L1InstructionCacheSpec` (11/11) pass after the change. Vivado preliminary
mapping on `xc7a200tfbg676-2L` reports four `32 x 256` banks using 16
`RAMB36` total, with the L2 line store absent from the distributed-RAM table.
The synthesis-only run was stopped during timing optimization, so final LUT,
post-route WNS, and bitstream evidence remain open.

### M3 L1D BRAM Update

`L1DLoadCache` now uses replicated `L1DDataMemory` line views instead of the
large distributed `Reg(Vec(...))` data array. The behavioral branch keeps the
existing zero-latency unit-test model; the Vivado branch uses read-first XPM
true-dual-port block RAM with retained hit/store metadata. The focused cache
and top-level checks pass: 56/56 L1D tests, 85/85 combined L1I/L1D/L2/dual-LSU
tests, and four selected CoreShell ownership cases. Vivado preliminary mapping
on `xc7a200tfbg676-2L` reports eight `16 x 256` L1D RAM objects in block RAM.
The synthesis run was intentionally stopped during timing optimization after
the mapping report because it consumed excessive host swap; final utilization,
timing, and post-route evidence remain open.
The fixed-target wrapper regression `make test-fpga-bram` also passes locally on
Vivado/XSim 2023.1 (`ZirconAxiBramTb PASS`, 2856 ns); this validates only the
AXI BRAM wrapper protocol and does not close the full-core FPGA gate.

### M3 External Coherence Update

`ExternalCoherenceController`、`ExternalCoherenceAdapter` 和
`ZirconPlatformCore` 的一请求 sideband 已完成可综合连接；controller/adapter
组件测试与 `CoreShellSpec` 的 clean、dirty、retry、I-side/D-side drain、reset、
LR reservation 和 response backpressure 场景均有本地证据。`ZirconBoard` 在固定
`xc7a200tfbg676-2L` wrapper 中保留该接口但将 modifier 置 idle，因为恢复的 LA32R
工程只提供 clk/reset/LED 和本地 AXI BRAM，没有可验证的外部 master/DDR pinout。
因此 core boundary 已闭环，真实 platform-master wiring 仍是 M3 发布前的明确缺口，
不能用 idle 输入或 local BRAM 代替该证据。
2026-09-03 当前提交的 `make test-m3-external-coherence` 实测组件 9/9、
CoreShell 14/14 全部通过，耗时约 234 秒；该结果覆盖 response backpressure、
I/D in-flight drain、dirty writeback/retry、reset、LR reservation 和 cacheable
store gating。它仍不替代真实平台 master 的板级接线证据。

### M3 ZirconSim AXI-owner Update

`ZirconSim` 的 deterministic AXI slave 已从单一 read owner 扩展为四个独立
owner，可同时接受 4 个 outstanding burst，并在不同 ID 间交错 R beat；当
`r_ready` 回压时会保持同一 owner 的完整 payload。`make -C ZirconSim unit`
新增四 ID/双 beat 单元覆盖；更新后的四个 `tohost` ELF、Spike 和 Sail
committed-memory differential 均在 2026-09-03 本地通过。该项关闭仿真器的
单 owner 限制，但不等于完整 M3 随机 AXI、Cache conflict 或平台 master
矩阵已完成。
2026-09-04 在当前 RTL 上重跑 `make -C ZirconSim tohost`：RV32I prefix、
ALU/branch、RV32M 和 RV32A 四项均 `status=tohost`、退出码 0，分别退休
19/34/19/12 条指令，耗时 246/294/277/226 cycles；seed 均为 1。

The earlier M3 row's statement that ADR-0023 had no controller or integration
is superseded. `ZirconCoreIO.externalCoherence` now implements the retained
single-request port through `ExternalCoherenceController`: new cacheable
ingress is blocked, I-side owners drain, exact L1D/L2 cleanup occurs, dirty
targets wait for matching ID-5 B, L1I/BTB and line-scoped LR reservations are
invalidated, then the original response returns. `CoreShellSpec` has clean and
dirty complete-core paths; its delayed instruction-refill slice accepts the
request after AR and before RLAST, forbids a new AR or response until drain,
then reaches EBREAK after invalidation. The dirty path observes one target ID-5
eight-beat burst and response only after B. `ZirconSim` gitlink `3023715` explicitly drives the
port idle in its private-memory model and its RV32A `tohost` run remains 228
cycles/12 retirements. A directed error slice retries the same target line
after a failing ID-5 B and suppresses response until the retry succeeds.
The reset slices drop an accepted request before its held instruction RLAST,
and separately reset after a dirty ID-5 AW/W but before B; each permits only a
fresh-epoch request/response, and the latter proves a new writeback for the
same dirty line completes normally.
The matching-L1D-refill slice accepts the request after target AR, delays the
target RLAST, and verifies no response before that exact owner drains while
the original load retains exact retire metadata. Its paired RRESP-fault slice
returns `SLVERR` for every target beat, requires the full faulting burst to
drain before one acknowledgement, and then observes the load's exact cause-5
and `tval` trap.
`ExternalCoherenceAdapter` now provides the reusable one-request platform
gate: it retains a modifier until the matching core response, holds the exact
authorized payload under downstream backpressure without accepting a
replacement, and drops it on reset; its directed component tests pass locally.
A complete-core cacheable-store pressure slice adds three explicit seeds
(`0x5eedec01`--`0x5eedec03`): an external response is held for eight cycles
after its request has been accepted while a younger, already fetched cacheable
store reaches the ROB head. That store must not issue its local effect or retire
until the response fires, then must retire with its exact address, mask, and
data. A complete-core LR/SC slice adds
three explicit AXI-backpressure seeds each for matching and disjoint external
invalidations: the matching case clears the reservation and permits no ID-7
write, while the disjoint case retains it and permits exactly one write only
after the acknowledgement. `ExternalCoherenceControllerSpec` additionally
resets a dirty-cleanup epoch before its writeback completion, supplies that
discarded epoch's stale completion, and requires a later request to perform
its own cleanup before its only response. `make test-m3-external-coherence`
includes the component, top-level cache-state, and explicit-seed store/atomic
pressure tiers. Concrete
`ZirconPlatformCore` elaborates the adapter with production core I/O, but FPGA/SoC
external-master wiring, full pressure/broader-reset/error
matrix, bounded formal, and multi-master system integration remain incomplete.

状态只能在对应自动测试或报告进入 Git 后更新；控制文档更新本身不等价于硬件进展。
