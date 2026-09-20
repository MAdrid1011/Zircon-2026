# 存储系统

Zircon-2026 的存储系统包含独立 ICache 与 DCache、ITLB 与 DTLB、共享 Sv32 PTW、偏非包含式
L2 Cache，以及连接外部 AXI4 主接口的 `L2AXI4Bridge`。L1 与 L2 使用 32 B Cache Line，
物理地址宽度为 34 位。

## L1 Cache

ICache 和 DCache 默认均为 2 路、16 set、32 B line，总容量各 1 KiB。ICache 提供四条指令的
块读取；DCache 提供两个固定 Load 端口和一个已提交 Store 端口。两者都使用三级命中流水，
并将 miss 状态保存在独立寄存器中，使状态机只读取末级请求。

DCache 采用 write-back、write-allocate。Store hit 在 L1 更新并置脏；Store miss 先取得整行
再合并字节 mask。DCache 使用单项 miss 单元，支持无冲突命中的 hit-under-miss；同一资源冲突
的 Load 返回 retry，由原发射队列表项重发。

原子操作只允许对可缓存、自然对齐的 32 位字执行。原子执行单元在提交授权后独占 LS1 Load
端口和 DCache Store 端口，以先读后写方式完成 AMO；不满足 PMA 或对齐要求的请求产生访存异常。

## 地址翻译

ITLB 有一个查询口，DTLB 有两个查询口。每个 TLB 的 4 KiB 页表项采用 4 set x 4 way 组织，
另有 4 项全相连的 4 MiB superpage 表。表项保存 VPN、PPN、权限、页大小、PMA 属性和当前
ASID 范围结果；查询路径使用 `VPN + inScope` 匹配。

TLB miss 进入共享两级 Sv32 `PageTableWalker`。PTW 在 I/D miss 之间仲裁，并分别复用 L2 的
I 侧和 D 侧查询通道。PTE 的 A/D 位必须已由软件设置，当前 PTW 不执行内存中的 A/D 位更新。

## L2 Cache

L2 默认 4 路、32 set、32 B line，总容量 4 KiB。I 侧和 D 侧各有独立的 S1、S2、S3 命中
流水；ICache 与 ITLB PTW 共享只读端口，DCache 与 DTLB PTW 共享读写端口。L1 请求具有首次
优先级，deferred 标志保证持续 Cache 流量不会饿死 PTW。

L2 主要保存 L1 替换出的 victim。L1 与 L2 同时 miss 时，外部填充直接返回 L1；只有 L1 victim
进入 L2。L2 命中后通常将数据所有权交给请求方并使条目失效。ICache 读取 dirty 数据时，L2
保留唯一 dirty 所有权。每个 set 最多使用两路保存指令 victim，剩余容量可由数据动态使用。

I/D 普通命中可以并行返回。Miss、victim 查询、dirty writeback、uncached 访问和安装操作共享
一个维护引擎及外部存储接口。L2 没有多项 MSHR；同一时刻只允许一个下级事务在途。

## RAM 后端与 PMA

Cache RAM 通过统一封装选择寄存器、Vivado 双口 BRAM 或 OpenRAM `1RW+1R` 实现。Vivado
配置保留两个可读写物理端口；ASIC 分析配置将 I 侧绑定到只读端口，将 D 侧和安装写绑定到
读写端口。

日常 Chisel 与 Verilator 回归默认使用与 OpenRAM 接口同周期的 Chisel 模型，因此不依赖宏文件，
也不会把第三方生成模型的 warning 混入项目 RTL。设置 `ZIRCON_USE_EXTERNAL_OPENRAM=true` 后，
elaboration 改为生成外部宏壳。此时 EDA 流程必须同时提供
`src/main/resources/OpenRam1RW1R_25.sv`、`src/main/resources/OpenRam1RW1R_32.sv`，以及
`eda/platforms/nangate45/memory/openram-1rw1r/` 下对应的两个 Verilog 宏模型、Liberty 和 LEF。
Vivado 后端仍使用原有外部 Verilog BRAM 模板，不经过该 OpenRAM 选择路径。

PMA 根据物理地址产生 cacheable、uncached memory 或 device 属性。TLB refill 时把静态 PMA
属性写入表项；地址翻译关闭时，PMA 与直接映射路径并行计算。Device 和 uncached 请求绕过
Cache line 分配，以 32 位事务送入下级接口。
