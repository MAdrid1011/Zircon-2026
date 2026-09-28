# Linux 启动

Zircon-2026 可在 Verilator 仿真中启动 Linux 6.1.44，进入交互式 BusyBox shell。
仿真器在退休点与进程内 Spike 逐条比较，覆盖 OpenSBI、M/S 特权切换、Sv32、
定时器中断、原子操作、initramfs 和 UART 输入输出。

## 运行方式

在仓库根目录执行：

```sh
make -C RV-Software/linux-system linux
```

该入口构建软件镜像与仿真器，使用 Clang/AppleClang 的 `O3`、ThinLTO 和 PGO，默认以
5 个 Verilator 运行线程执行 Spike 差分，并连接当前终端的 UART。macOS 使用 Docker
构建软件镜像，Linux 主机使用本机工具。首次构建需要 CMake、sbt、Verilator、Spike
开发库、Clang/LLVM 和 RISC-V 软件工具链；详细依赖见 [项目入口](../README.md#环境依赖)。

生成的镜像与日志位于 `RV-Software/linux-system/build/`，仿真器位于
`build/linux-sim/bin/zircon-sim`。PGO 配置与输入内容绑定；RTL、仿真器或软件镜像变化时，
构建系统会重新生成匹配的优化数据。

## 软件栈

| 组件 | 版本或配置 |
| --- | --- |
| Buildroot | `2024.02.12` |
| Linux | `6.1.44`，RV32 单精度浮点配置 |
| OpenSBI | `1.2` 通用平台 |
| 用户空间 | BusyBox 与 musl 1.2.5，静态 `ilp32f` |
| ISA | `rv32imaf_zicsr_zifencei` |
| 根文件系统 | 内嵌 initramfs |

`fw_payload.elf` 包含 OpenSBI、设备树、Linux Image 和 initramfs。启动顺序为
`0x80000000` 复位入口、OpenSBI M 模式、`0x80400000` 的 Linux S 模式、`/init`，
最终进入 BusyBox shell。内存布局如下：

| 区域 | 地址 | 用途 |
| --- | --- | --- |
| RAM | `0x80000000`，64 MiB | 主存 |
| CLINT/MTIMER | `0xa0000000` | 定时器与软件中断 |
| 16550 UART | `0xa1000000` | 交互控制台 |

控制台使用 `console=hvc0 earlycon=sbi`。当前平台以 initramfs 提供文件系统，
以单核 AXI 内存和 UART 设备模型运行 Linux。

## 差分验证

ZirconSim 与 Spike 使用一致的内存和 UART 输入。逐提交比较覆盖 PC、指令、整数和浮点
目的寄存器；同步异常检查 cause、epc、tval 与目标特权级。`cycle`、`time` 和
`instret` 等动态计数器采用状态同步，因为参考核的指令步进与 RTL 周期并非同一时间基准。

根文件系统中的 `zircon-validation` 在进入 shell 前运行系统调用、文件访问、整数、
单精度浮点和原子操作检查。成功启动时出现 `ZIRCON_LINUX_BOOT_PASS` 和 `~ #` 提示符；
此后可在当前终端输入 BusyBox 命令。

## 检查点

长时间仿真默认每 4000 万周期保存一次检查点，仅保留最新的 RTL 与主机状态文件：

```text
RV-Software/linux-system/build/linux-latest.rtl
RV-Software/linux-system/build/linux-latest.host
```

检查点包含处理器模型、稀疏内存、AXI 事务、设备与差分状态。恢复时使用同一
`fw_payload.elf` 和平台配置，并向仿真器传入
`--checkpoint-load RV-Software/linux-system/build/linux-latest`。

## 已验证结果

使用 Clang 23.1.2 构建的 PGO 仿真器完成了 Spike 差分启动、用户空间检查和交互命令：

```text
ZIRCON_LINUX_BOOT_PASS
~ # whoami
root
```

启动标记约在 1.38 亿周期出现。验证主机上的仿真吞吐为每秒约 12.3 万周期；
该速度描述主机仿真能力，不是处理器的工作频率。

## 参考资料

- [Buildroot 2024.02.12 RV32 配置](https://github.com/buildroot/buildroot/blob/2024.02.12/configs/qemu_riscv32_virt_defconfig)
- [Linux 6.1 RISC-V 配置](https://github.com/torvalds/linux/blob/v6.1/arch/riscv/Kconfig)
- [OpenSBI 1.2 通用平台](https://github.com/riscv-software-src/opensbi/blob/v1.2/docs/platform/generic.md)
