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
| ICache / DCache / L2 | 2 / 2 / 8 KiB | 2 / 16 / 64 KiB |
| Cache Line | 64 B | 64 B |

性能核心的运行环境由合法程序、有效地址和正常总线响应组成。流水线保留分支恢复、
推测取消、存储顺序和基础 `FENCE`；无特权指令、异常入口、中断或原子单元。
浮点与向量状态始终可用，计时通过 `cycle/time/instret` 及其高半字读取，
`time` 的时基为核心时钟。

## 向量重命名与执行

向量后端独立于标量执行资源，使标量控制流、向量计算和访存能够重叠。
32 个架构向量寄存器映射到 64 个物理寄存器；PRF 按 8 个 32 位 lane 分 bank，
每 bank 三读两写，写端口分别由计算和 Load 使用。数据保存在 PRF，
12 项计算队列和 8 项访存队列保存标签与控制信息。

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

`vset*` 在标量操作数就绪后计算 `vl/vtype`。后续 Rename 等待该配置就绪，
每条向量指令携带自己的配置快照。提交维护已提交映射和配置，分支恢复从已提交状态恢复。
配置、完成和退休是不同事件，错误路径上的结果不会修改架构状态。

常用整数逻辑、加减、移位、比较、乘法及 FP32 加减、乘法、FMA 使用八路流水。
操作数收集、运算与写回之间设寄存边界；除法、平方根、归约、排列和复杂访存使用多拍通路。
mask 收集复用现有读口；undisturbed 元素保留旧目的值，agnostic 元素使用标准允许的全一值。
FP 舍入与异常标志、整数饱和标志按指令收集，退休时更新 CSR。

多拍通路复用 `DivSqrtSRT4`、`FpConvert`、`FpLogic` 与 `MulBooth2Wallce`，
按元素执行除法、平方根、转换、归约、排列及宽窄整数运算。七位浮点估值使用标准查表，
规格化与查表之间设置寄存边界。整数乘加复用八路乘法器，并在乘积之后使用共享
加法器实现模 SEW 累加。常用运算通过八 lane
流水处理，多拍通路与其共享计算写回口，向量 Load 使用独立写回口。

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

标量与向量 Store 共用程序顺序管理，宽数据保存在独立缓冲。Load 等待更老 Store
解析地址；与尚未写入 Cache 的 Store 重叠时等待。Store 仅在提交后写入存储系统。
取消的 Load 丢弃返回值，在途请求在响应结束前保持原有所有权。

256 bit AXI 用两个 beat 传输 64 B Cache Line。普通设备访问保留原始窄传输地址、
尺寸和 strobe；写合并保持原有 8 B beat 的 INCR burst 及顺序，不将总线宽度等同于设备写宽度。

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

以下结果使用 Clang 23.1.2、上述性能核心配置及 Spike 退休差分。计算阶段周期取五次
计时的中位数，包含 Load、Store 和循环控制；三种版本共用输入、算法与浮点运算顺序。

| 程序 | 标量周期 | 自动向量化周期 | RVV 周期 | 自动加速比 | RVV 加速比 |
| --- | ---: | ---: | ---: | ---: | ---: |
| INT32 矩阵乘，64×64 | 1174930 | 838967 | 416689 | 1.400 | 2.820 |
| INT32 FIR，4096 输出、32 taps | 801211 | 688077 | 210183 | 1.164 | 3.812 |
| FP32 矩阵乘，64×64 | 2399105 | 861879 | 430953 | 2.784 | 5.567 |
| FP32 FIR，4096 输出、32 taps | 1304221 | 680237 | 199937 | 1.917 | 6.523 |
| FP32 FFT，1024 点 | 180753 | 252190 | 224282 | 0.717 | 0.806 |

五项 RVV 加速比的几何平均为 **3.159**。FFT 包含排列、短向量与标量控制，
适合与矩阵乘、FIR 一起观察不同计算结构的向量化效果。全部结果通过逐元素校验。
整个五项程序的端到端周期包含输入准备、参考计算和校验：标量为 42623371，
自动向量化为 27223415，RVV 为 16180572。

性能核心的标量 CoreMark 为 **5.177 CoreMark/MHz**，IPC 为 1.308324，
30 次迭代计时 5794057 周期，CRC 为 `0xf8b3`。Netlib BLAS SGEMM 接口测试中，
同算法标量与 RVV 分别使用 2348544 和 442378 周期，加速比为 **5.309**。

十六组独立寄存器的吞吐程序执行 32768 个 INT32 加法或 FP32 运算：
INT32 加法为 4118 周期，FP32 加法与乘法为 4133 周期，FMA 为 4121 周期。
计时包含循环控制，独立 FMA 流水每拍接受八个元素。

CoreMark-PRO 的 `linear_alg-mid-100x100-sp` 使用上游 100×100 单精度数据集，
通过原始校验，计时为 71337989 周期，端到端为 75740668 周期。

带宽程序分别测量 Cache 内数组与超过 Cache 容量的数组，计算周期如下：

| 程序 | 标量周期 | RVV 周期 | 加速比 |
| --- | ---: | ---: | ---: |
| Cache 内 INT32 数组加法 | 4152 | 4603 | 0.902 |
| Cache 内 FP32 SAXPY | 6204 | 4674 | 1.327 |
| 大数组 INT32 加法 | 560336 | 671247 | 0.835 |
| 大数组 FP32 SAXPY | 878927 | 679452 | 1.294 |

向量化的收益同时取决于每个元素的运算量、任务长度和访存带宽。矩阵乘与 FIR
具有较高数据复用，数组加法更适合用于观察 Cache 和外部总线的吞吐限制。

## 仿真与验证

ZirconSim 按配置选择 Spike ISA，默认差分和动态进度显示。向量退休比较目的寄存器组、
向量 CSR、浮点标志与存储结果。RTL、仿真、PGO 和软件输出按配置、VLEN、AXI 宽度及
工具链版本隔离，避免系统核心与性能核心互用生成物。

| 项目 | 验收指标 |
| --- | --- |
| 常用 INT32 / FP32 吞吐 | 每拍 8 个元素，独立 FMA 每拍 8 次 |
| 五项 RVV 专用程序 | 相对同配置标量的计算阶段加速比几何平均 ≥3 |
| 性能核 CoreMark | Spike 差分通过，≥5.0 CoreMark/MHz |
| Nangate45 | BSG SRAM，logic-only 1.5 ns，WNS≥0、TNS=0、零违例端点 |
| FPGA | Vivado 2025、VU13P、100 MHz、默认策略、setup/hold 通过、DSP=0 |
| 系统核 | 现有架构测试与 Linux shell 启动通过 |

定向验证覆盖 VL、全部 LMUL 分组、重叠、mask/tail、跨行访问、存储顺序、反压、恢复与
晚响应；浮点覆盖 NaN、无穷、subnormal、舍入和标志。RTL 单元测试位于
`src/test/scala/Backend/Vector`，裸机编码、访存与配置测试位于 `RV-Software/simd`。
系统配置使用架构测试和 Linux shell 验证运行环境。

Nangate45 BSG logic-only 分析中，性能核心在 1.5 ns 周期下的 WNS 为
+0.000925 ns，TNS 为 0，违例端点为 0，最长数据到达时间为 1.467417 ns。
配置、工具和复现入口见 [Nangate45 逻辑时序评估](Nangate45Timing.md)。

向量重命名实现见 [VectorRename](../src/main/scala/Middleend/Rename/VectorRename.scala)，
寄存器堆与八路算术实现见 [VectorRegisterFile](../src/main/scala/Backend/Vector/VectorRegisterFile.scala)
和 [VectorArithmetic](../src/main/scala/Backend/Vector/VectorArithmetic.scala)。
[VectorStateSpec](../src/test/scala/Backend/Vector/VectorStateSpec.scala) 检查分组、配置与映射恢复，
[VectorArithmeticSpec](../src/test/scala/Backend/Vector/VectorArithmeticSpec.scala) 检查八路运算、
浮点标志、连续 FMA 吞吐及反压取消。
