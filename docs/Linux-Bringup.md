# Linux 启动与复现

Zircon-2026 已在 Verilator 中启动 Linux 6.1.44，进入交互式 BusyBox shell，并在全过程保持
Spike 逐提交差分。验证覆盖 OpenSBI、M/S 特权切换、Sv32、定时器中断、原子操作、静态
RV32IMAF 用户空间、initramfs 挂载和 UART 输入输出。

## 默认入口

在仓库根目录运行：

```sh
make -C RV-Software/linux-system linux
```

该命令是完整 Linux 仿真的发布入口，无需选择额外优化开关。默认行为如下：

| 项目 | 默认值 |
| --- | --- |
| 主机编译优化 | Release `O3`、ThinLTO、主机原生指令 |
| Verilator 运行线程 | 5 |
| Verilator 模型编译并行度 | 4 |
| 差分 | 进程内 Spike，逐提交开启 |
| 检查点 | 每 4000 万周期原子替换，只保留最新一份 |
| 进度 | 每 30 秒输出 cycle、instruction、IPC 和 cycles/s |
| UART | 连接当前终端，可直接输入 BusyBox 命令 |
| 日志 | `RV-Software/linux-system/build/linux-simulation.log` |

macOS 通过 Docker 构建 Linux 软件镜像，Linux 主机直接使用本机工具。构建只从标准 `PATH`
查找 `docker`，也可用 `DOCKER` make 变量指定其他兼容命令，不依赖开发者机器路径。
正式仿真器的默认 PGO 构建需要 Clang/AppleClang 与匹配的 `llvm-profdata`；未设置 `CXX` 时，
脚本会从 `PATH` 自动选择 `clang++`，并把实际编译器版本纳入 profile 指纹。

## PGO 与增量构建

默认入口对下列输入计算内容指纹：Chisel RTL、顶层构建配置、ZirconSim 源码与头文件、线程配置、
编译工具版本和 `fw_payload.elf`。profile 缺失或指纹变化时，构建系统会自动执行一次 2000 万周期
Linux 差分训练，合并 LLVM profile，再重新构建正式仿真器。RTL、仿真器或 payload 未变化时，
直接复用 profile 和增量构建结果。

PGO 训练日志位于：

```text
RV-Software/linux-system/build/pgo-training.log
```

正式仿真器与 profile 默认位于：

```text
build/linux-sim/bin/zircon-sim
build/linux-pgo-generate/zircon-linux.profdata
```

PGO 训练仍启用 Spike 差分，并以到达周期上限作为正常结束条件。这样每次改变 RTL 或仿真器后，
正式二进制都使用与当前执行路径匹配的 profile。

训练必须完整到达 2000 万周期，生成的 `.profraw` 才会合并为可复用的 `.profdata`。如果训练被
中断，下一次运行可以复用已经构建的插桩仿真器，但会删除未完成采样并从 0 周期重新训练。

终端会用 `PGO PROFILE TRAINING START/COMPLETE` 标出采样阶段，并明确说明该阶段不是交互 Linux；
正式会话开始前会输出 `INTERACTIVE DIFFERENTIAL LINUX START`。profile 可复用时则输出
`PGO PROFILE REUSE`，不会再次运行训练负载。

## 软件镜像

软件栈使用固定版本，避免主机发行版变化影响复现：

| 组件 | 版本或配置 |
| --- | --- |
| Buildroot | `2024.02.12` |
| Linux | `6.1.44`，Zircon RV32 F-only 分支 |
| OpenSBI | `1.2` generic platform |
| 用户空间 | BusyBox + musl 1.2.5，静态 `ilp32f` |
| ISA | `rv32imaf_zicsr_zifencei`，无 `C`、无 `D` |
| 根文件系统 | 内嵌 initramfs |

构建产物位于 `RV-Software/linux-system/build/`：

```text
fw_payload.elf
Image
zircon-2026.dtb
```

`fw_payload.elf` 同时携带 OpenSBI、DTB、Linux Image 和 initramfs。rootfs 中的
`zircon-validation` 在进入 shell 前检查基础系统调用、文件访问、整数、单精度浮点和原子路径，
成功后输出 `ZIRCON_LINUX_BOOT_PASS`。

## 启动链和地址布局

```text
reset 0x80000000
    -> OpenSBI 1.2 M-mode
    -> Linux 6.1.44 S-mode at 0x80400000
    -> embedded initramfs
    -> /init
    -> zircon-validation
    -> interactive BusyBox shell
```

| 区域 | 地址 | 说明 |
| --- | --- | --- |
| RAM | `0x80000000`，64 MiB | 可缓存 PMA |
| OpenSBI | `0x80000000` | 复位入口 |
| Linux payload | `0x80400000` | 4 MiB 对齐 |
| DTB copy | `0x82200000` | OpenSBI payload FDT |
| CLINT/MTIMER | `0xa0000000` | device PMA |
| 16550 UART | `0xa1000000` | 轮询控制台 |

PLIC、磁盘、网络和 SMP 尚未接入。根文件系统使用 initramfs，Linux 控制台使用
`console=hvc0 earlycon=sbi`，因此当前启动不依赖这些设备。

## 差分边界

ZirconSim 的 Linux 平台使用 MSU 配置的 Spike 参考核，并保持与 DUT 相同的内存和 UART 输入。
逐提交比较检查 PC、指令、整数或浮点写回。`cycle`、`time` 和 `instret` 等动态计数器读取采用
状态同步，因为 Spike 指令步进与 RTL 周期不是同一时间基准。

异步中断在 DUT 实际接受 trap 时注入 Spike；instruction、load 和 store page fault 同步检查
cause、epc、tval 与目标特权级。精确退休 WFI 后，watchdog 暂停无退休计数，直到后续退休或
trap，避免空闲等待被误判为流水线锁死。Spike 允许 WFI 因任意原因恢复，参考端按同一规则推进。

## 检查点与交互

默认检查点文件为：

```text
RV-Software/linux-system/build/linux-latest.rtl
RV-Software/linux-system/build/linux-latest.host
```

每次保存先写临时文件，再原子替换同一路径，因此只保留最新检查点。host 文件保存稀疏内存、
AXI 未完成事务、CLINT、UART、统计信息、差分状态和 WFI 等待状态；RTL 文件保存 Verilator 模型。
恢复时校验 ELF 指纹、RTL/host 配对、平台、随机种子和差分模式。当前读取器兼容 v1 检查点，
新保存文件使用 v2。

默认 `linux` 目标把 UART 连接到当前终端。BusyBox 显示 `~ #` 后可以直接输入命令；输入同时送入
DUT 与 Spike。需要从检查点手工恢复时，应使用相同的 ELF、平台、差分和 seed，并增加：

```sh
build/linux-sim/bin/zircon-sim \
    --elf RV-Software/linux-system/build/fw_payload.elf \
    --platform linux \
    --uart-stdio \
    --checkpoint-load RV-Software/linux-system/build/linux-latest \
    --checkpoint-save RV-Software/linux-system/build/linux-latest \
    --checkpoint-interval 40000000 \
    --max-cycles 0xffffffffffffffff \
    --stall-cycles 5000000 \
    --progress-interval 30 \
    --no-color 2>&1 | tee -a RV-Software/linux-system/build/linux-simulation.log
```

## 已验证结果

发布前的完整差分运行进入 shell，并成功执行：

```text
~ # echo ZIRCON_INTERACTIVE_OK; uname -a; cat /proc/mounts; echo STATUS:$?
ZIRCON_INTERACTIVE_OK
Linux (none) 6.1.44 ... riscv32 GNU/Linux
rootfs / rootfs ...
devtmpfs /dev ...
proc /proc ...
sysfs /sys ...
STATUS:0
```

该次运行在 3.2 亿周期保存最新检查点。稳定区间通常达到约 100k 至 112k cycles/s；启动至交互
验证期间累计 IPC 约 0.32。

## 当前限制

当前实现没有 PMP。OpenSBI 把 PMP CSR 视为可选功能，所以这不阻塞启动，但 M 模式固件与
S 模式内核之间没有物理内存保护。TLB 的 `SFENCE.VMA` 当前执行全清空，功能正确但仍有性能
优化空间。Linux 启动验证覆盖单核内嵌根文件系统；PLIC、外部设备中断、持久存储、网络、SMP
和长时间用户负载压力测试仍属于后续工作。

## 参考资料

- [Buildroot 2024.02.12 RV32 virt defconfig](https://github.com/buildroot/buildroot/blob/2024.02.12/configs/qemu_riscv32_virt_defconfig)
- [Linux 6.1 RISC-V Kconfig](https://github.com/torvalds/linux/blob/v6.1/arch/riscv/Kconfig)
- [Linux 6.1 RISC-V PTE definitions](https://github.com/torvalds/linux/blob/v6.1/arch/riscv/include/asm/pgtable-bits.h)
- [OpenSBI 1.2 generic platform](https://github.com/riscv-software-src/opensbi/blob/v1.2/docs/platform/generic.md)
