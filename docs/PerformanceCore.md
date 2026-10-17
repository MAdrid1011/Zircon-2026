# SIMD8 性能核心

## 核心配置

Zircon-2026 在同一套源码中提供系统核心与裸机性能核心。配置在 RTL 生成时确定，
共享取指、标量乱序流水线和算术单元。系统核心提供 RV32IMAF、地址翻译与 Linux
运行环境；性能核心面向整数和单精度浮点数组计算，直接访问 32 位物理地址。

| 属性 | `system` | `performance` |
| --- | --- | --- |
| 标量指令 | 现有 RV32IMAF 与系统指令 | RV32IMF、Zicsr、Zicntr |
| 向量指令 | 无 | Zve32f、Zvl256b |
| 向量宽度 | 无 | VLEN=256、ELEN=32、8 lane |
| 特权与地址翻译 | M/S/U、Sv32、ITLB、DTLB、PTW | 直接物理寻址 |
| 原子执行 | 支持 | 不例化 |
| AXI 数据宽度 | 64 bit | 256 bit |
| ICache / DCache / L2 | 2 / 2 / 8 KiB | 2 / 16 / 32 KiB |
| Cache Line | 64 B | 64 B |

性能核心的运行环境由合法程序、有效地址和正常总线响应组成。流水线保留分支恢复、
推测取消、存储顺序和基础 `FENCE`；无特权指令、异常入口、中断或原子单元。
浮点与向量状态始终可用，计时通过 `cycle/time/instret` 及其高半字读取，
`time` 的时基为核心时钟。

## 向量重命名与执行

向量后端独立于标量执行资源，使标量控制流、向量计算和访存能够重叠。
32 个架构向量寄存器映射到 64 个物理寄存器；PRF 按 8 个 32 位 lane 分 bank，
每 bank 三读两写，写端口分别由计算和 Load 使用。数据保存在 PRF，
6 项计算队列和 4 项访存队列保存标签与控制信息。容量由静态配置统一传递到
重命名、ReadyBoard、RF 和队列，物理标签保持六位编码。

向量模块复用标量核心已有的实现。映射表和空闲表由参数化的 `Rename/SRat/PRegFreeList`
组成，RF 的八个 lane 使用同一个 `Regfile` 类；ReadyBoard 与两个 IssueQueue 也使用
原有模块。向量适配层负责寄存器分组、配置快照和元素展开，标量、浮点与向量寄存器
分别保存各自的架构状态。计算、访存和多拍运算共用向量 RF 的三读两写接口。
向量指令使用的标量操作数由公共预译码器分类，并随 FQ 表项保存，Rename 使用相同的
操作数信息读取现有整数与浮点映射表。

Rename–Dispatch 保留三槽段间寄存器，派发组共同进入、共同退出。
FQ 的连续指令前缀在第二条向量指令之前截断，每组最多重命名一条向量指令。
每条架构向量指令只占一个 ROB 项，LMUL 分组通过内部任务展开；所有任务完成后
才向 ROB 发布完成。寄存器分组与源目的重叠按照 Zve32f 的合法规则处理。
重命名准入并行检查各个固定寄存器组的 bank 容量，指令类别与分组大小只选择一位准入结果。
目的 bank 选择随标签进入同一段间寄存器，保持与整组停顿、派发和恢复同步。

`vset*` 在标量操作数就绪后计算 `vl/vtype`。后续 Rename 等待该配置就绪，
每条向量指令携带自己的配置快照。提交维护已提交映射和配置，分支恢复从已提交状态恢复。
配置、完成和退休是不同事件，错误路径上的结果不会修改架构状态。
配置计算使用独立完成槽，与算术流水的最后一组结果共用完成端口；反压期间保持完成内容。

性能配置采用五张带标签历史表，历史长度为 4、8、16、32、64 个取指块，
PHT 为 256 项，RAS 为 8 项。预测历史与返回地址快照随取指包保存，用于推测恢复。
系统配置使用六张历史表和 16 项 RAS；两个版本复用同一套参数化前端。

常用整数逻辑、加减、移位、比较、乘法及 FP32 加减、乘法、FMA 使用八路流水。
操作数收集、运算与写回之间设寄存边界；除法、平方根、归约、排列和复杂访存使用多拍通路。
mask 收集复用现有读口；undisturbed 元素保留旧目的值，agnostic 元素使用标准允许的全一值。
FP 舍入与异常标志、整数饱和标志按指令收集，退休时更新 CSR。

常用分组运算在标量源、mask 和三个向量源的首组就绪后发射。执行端逐组查询公共
ReadyBoard，只有对应物理寄存器已经写入 RF 才收集该组，因此同一条 Load 的前半组返回时，
计算可以与后半组访存重叠。多拍运算和访存仍在全部源组就绪后发射。
计算器在处理当前指令时寄存下一条标量源地址，在接收指令的边界捕获其 RF 数据；
连续 LMUL=4 的 VF 运算可每四拍接收一条指令，每拍向八路流水送入一组。

多拍通路使用 `DivSqrtSRT4`、`FpConvert` 与共享比较逻辑，乘法与浮点归约加法使用
八路算术流水的 lane 0。
按元素执行除法、平方根、转换、归约、排列及宽窄整数运算。七位浮点估值使用标准查表，
规格化与查表之间设置寄存边界。整数乘加复用八路乘法器，并在乘积之后使用共享
加法器实现模 SEW 累加。常用运算通过八 lane
流水处理，多拍通路与其共享计算写回口，向量 Load 使用独立写回口。
计算流水排空后，多拍指令取得 lane 0 的所有权，并保持到完成握手。
八路常用运算仍可每拍接收一组任务；整数与浮点逻辑结果共用对齐寄存器。
八路乘法流水的载荷寄存器仅在对应有效任务推进时更新，空泡仍按原有有效位传递；
反压保持整条流水，恢复清除有效位，载荷不需要复位。
进位、借位、饱和、平均与宽整数运算共享 33 位加法器；操作数与进位策略
在收集阶段寄存，组合算术从寄存器 Q 端开始。

## 向量状态

性能核心保留 `fflags/frm/fcsr` 与七个向量 CSR：`vstart`、`vxsat`、`vxrm`、
`vcsr`、`vl`、`vtype`、`vlenb`。`vcsr` 与 `vxrm/vxsat` 为同一状态的不同视图，
`vlenb` 固定为 32。已完成的向量指令退休时清除 `vstart`，合并异常和饱和标志。

## 指令组织

向量译码按寄存器操作数、执行单元和有效元素宽度组织指令。SEW 支持 8、16、32 bit，
LMUL 支持 mf8、mf4、mf2、m1、m2、m4、m8；组合遵循 ELEN=32 的合法限制。
寄存器组按有效 LMUL 对齐，mask 使用 v0，配置和 mask/tail 策略随指令进入后端。

| 指令族 | 执行方式 | 代表指令 |
| --- | --- | --- |
| 配置与寄存器搬运 | 配置快照、标量收集与组展开 | `vsetvl/vsetvli/vsetivli`、`vmv`、`vfmv`、`vmv*r` |
| 整数算术与逻辑 | 八 lane 流水，窄元素分批收集 | `vadd/vsub`、min/max、移位、AND/OR/XOR、乘法与乘加 |
| 整数宽度与定点 | 共享多拍算术通路 | 宽乘加减、扩展、窄移位、饱和加减、平均、舍入、`vsmul/vnclip` |
| 比较与 mask | 按元素形成位结果或标量结果 | 整数/浮点比较、mask 逻辑、carry、`vcpop/vfirst`、前缀、`viota/vid` |
| 归约与排列 | 共享多拍通路 | 整数与 FP 归约、`vslide`、`vrgather`、`vcompress` |
| FP32 算术 | 八 lane 流水 | 加减、乘法、融合乘加、min/max、符号操作、分类 |
| FP32 多拍运算 | 共享除法、转换和查表模块 | 除法、平方根、整数/浮点转换、`vfrec7/vfrsqrt7` |
| 向量访存 | 宽命中通路与内部元素任务 | 连续、stride、indexed、segment、mask、whole-register、fault-only-first |

FP32 使用标准舍入模式并在退休时累计异常标志。整数定点运算使用 `vxrm` 和 `vxsat`。
fault-only-first Load 在合法地址运行契约下完成全部有效元素；性能核心使用直接物理地址。

## 向量访存与总线

DCache 命中通路每拍处理一个 256 bit 向量访问，与标量访问仲裁并复用 RAM 端口。
连续访问按 Cache Line 拆分，起始地址可按元素对齐而不按 32 B 对齐。
stride、indexed、segment、mask 与 whole-register 访问展开为内部任务。
分组偏移在操作数收集阶段计算并寄存，地址阶段将该偏移与基址、字段偏移合并。

对齐且未屏蔽的完整寄存器组 Load 每个 64 B Line 只发送一次请求，完整行返回后
通过一个 Load 写口分两拍写入相邻的 256 bit 物理寄存器。四项响应缓冲为每个在途请求
预留槽位；retry 归还预约并保留待重发组，恢复清除待写结果并等待在途响应排空。
标量基址读取与向量 RF 读取分别仲裁，完整组 Load 不申请旧目的或 mask 读口。
RF 需求在前一拍寄存，仲裁控制从寄存器 Q 端开始。

标量与向量 Store 共用程序顺序管理，宽数据保存在独立缓冲。Load 等待更老 Store
解析地址；与尚未写入 Cache 的 Store 重叠时等待。Store 仅在提交后写入存储系统。
取消的 Load 丢弃返回值，在途请求在响应结束前保持原有所有权。

256 bit AXI 用两个 beat 传输 64 B Cache Line。普通设备访问保留原始窄传输地址、
尺寸和 strobe；写合并保持原有 8 B beat 的 INCR burst 及顺序，不将总线宽度等同于设备写宽度。

### SRAM 集成接口

性能核心可在生成时启用独立的 256 bit 原生 SRAM 接口。该选项默认关闭，
通过 `runMain Elaborate --profile=performance --scratchpad <output-directory>` 启用。
启用后，`0xb0000000..0xb00fffff` 为设备属性窗口，向量访问经 `PeScratchpadBridge`
直达 SRAM；标量访问仍通过 AXI 完成。窗口不可执行，也不接受原子访问。

原生通路将一次 64 B 向量访存请求拆成两个 32 B 请求，保留数据、字节掩码、
请求身份与错误状态。接口使用 `valid/ready` 握手，在背压期间保持请求；已接受的
Load 在处理器恢复时继续排空响应，再由向量访存单元处理恢复。平台负责实现
该窗口的 SRAM 及标量 AXI 访问。未启用时，核心仅导出原有 AXI 接口。

### 缓存与输入预取

`PREFETCH.R` 将缓存行地址送入两项提示队列，经空闲加载端口复用 DCache
查找、缺失和填充通路。需求加载可以接管同一缓存行的在途预取。提示队列满时
丢弃新提示，恢复和缓存维护清空待发提示；预取仅面向缓存主存窗口。

向量 Store 的相邻数据组可以合并为完整 64 B 缓存行请求。覆盖整行的 Store
在缺失时直接安装新数据，L2 取得写入所有权并保存被替换的数据。
L2 使用一行共享的干净替换缓冲，使命中响应与后续替换插入重叠；
脏替换和缓冲已满的请求按原有顺序完成。

## 编译与程序

在仓库根目录构建性能核心仿真器：

```sh
cmake -S . -B build/performance -DCMAKE_CXX_COMPILER=clang++ \
  -DZIRCON_CORE_PROFILE=performance
cmake --build build/performance --target zircon-sim -j4
cmake --build build/performance --target coremark
make -C RV-Software/simd -j4
make -C RV-Software/simd run
```

系统核心使用独立目录，例如 `build/system`，并将 `ZIRCON_CORE_PROFILE` 设为 `system`。

性能软件位于 `RV-Software/simd/`，使用 Clang 23：

```text
--target=riscv32-unknown-elf
-march=rv32imf_zicsr_zicntr_zve32f_zvl256b
-mabi=ilp32f
```

启动代码初始化栈和 BSS，直接进入 C 程序。ISA 与 ABI 在 runtime、算法和链接对象间一致。
默认 `-O3 -ffp-contract=off`，不使用 fast-math。标量版本关闭 loop/SLP 向量化，
自动版本使用编译器生成的 RVV，专用版本使用标准 RVV intrinsics。
最终 ELF 检查属性和反汇编，保证不引入 A、D、C 或特权指令。

固定程序为 INT32/FP32 的 64×64 矩阵乘、4096 输出且 32 taps 的 FIR，以及
1024 点 FP32 radix-2 FFT。三种版本使用相同输入、算法与运算顺序，整数使用明确的
模 2³² 运算语义，浮点逐元素校验。每个程序预热一次、计时五次，取周期中位数。
计算阶段包含访存和循环控制，同时报告端到端周期。

正式单精度负载使用 EEMBC CoreMark-PRO 的 `linear_alg-mid-100x100-sp` 和
`loops-all-mid-10k-sp`，保留上游算法、数据集与校验，仅适配裸机接口。
两个负载分别报告，不生成完整套件总分。矩阵吞吐另外使用 Netlib BLAS SGEMM 接口。

## 性能测量

计算程序提供标量、自动向量化和 RVV 专用版本。测量使用相同的核心配置、输入、
算法和浮点运算顺序，计算周期包含 Load、Store 与循环控制；预热一次、计时五次，
取周期中位数。端到端周期包含输入准备、参考计算与结果校验。

独立寄存器程序测量八路算术吞吐，数组加法与 SAXPY 用于观察 Cache 和外部带宽。
32×256×32 FP32 矩阵乘显式使用融合累加，以实际退休的 FMA 元素数计算八路利用率，
分别报告 K 循环与完整内核周期。构建、运行与统计方法见
[SIMD8 计算程序](../RV-Software/simd/benchmarks/README.md)。

## 仿真与验证

ZirconSim 按配置选择 Spike ISA，默认差分和动态进度显示。向量退休比较目的寄存器组、
向量 CSR、浮点标志与存储结果。RTL、仿真、PGO 和软件输出按配置、VLEN、AXI 宽度及
工具链版本隔离，避免系统核心与性能核心互用生成物。

定向验证覆盖 VL、全部 LMUL 分组、重叠、mask/tail、跨行访问、存储顺序、反压、
恢复与晚响应；浮点覆盖 NaN、无穷、subnormal、舍入和标志。
RTL 单元测试位于 `src/test/scala/Backend/Vector`，裸机测试及生成器说明见
[向量指令与状态测试](../RV-Software/simd/tests/README.md)。

系统核心的架构测试与 Linux 运行环境使用独立的软件入口，见
[架构测试](../RV-Software/arch-test/README.md)和 [Linux 启动](Linux-Bringup.md)。
性能核心使用非特权裸机运行时。

EDA 按配置生成独立 RTL：Nangate45 使用 BSG Fakeram 进行 1.5 ns 的纯逻辑时序分析，
Vivado 2025.2 使用 VU13P、Xilinx BRAM、100 MHz 时钟与默认综合实现策略，DSP 上限为零。
配置、工程建立方法和时序评估说明分别见
[Nangate45 逻辑时序评估](Nangate45Timing.md)与 [Vivado VU13P 工程](../eda/vivado/README.md)。

向量重命名实现见 [VectorRename](../src/main/scala/Middleend/Rename/VectorRename.scala)，
寄存器堆与八路算术实现见 [VectorRegisterFile](../src/main/scala/Backend/Vector/VectorRegisterFile.scala)
和 [VectorArithmetic](../src/main/scala/Backend/Vector/VectorArithmetic.scala)。
[VectorStateSpec](../src/test/scala/Backend/Vector/VectorStateSpec.scala) 检查分组、配置与映射恢复，
[VectorArithmeticSpec](../src/test/scala/Backend/Vector/VectorArithmeticSpec.scala) 检查八路运算、
浮点标志、连续 FMA 吞吐及反压取消。
