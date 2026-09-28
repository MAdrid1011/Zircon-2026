# CoreMark 跨处理器比较

本目录在 Zircon、香山雁栖湖、BOOM Small 和 BOOM Medium 上运行同一 CoreMark
工作负载，并按计时周期报告 CoreMark/MHz。外部仓库和生成的仿真器位于忽略的 `work/`；
仓库只保存移植层、版本锁定、运行脚本和参考结果。

## 运行

从 Zircon-2026 仓库根目录执行：

```sh
make -C benchmarks/coremark-crosscore compare
```

脚本会初始化 Zircon 子模块并获取锁定版本的外部处理器。默认使用两个构建任务、一个
Verilator 运行线程和较低主机调度优先级；资源充足时可指定 `JOBS=4`。首次运行需要下载
并构建多个仿真器，应预留数小时和至少 8 GiB 磁盘空间。

也可只运行一个目标：

```sh
make -C benchmarks/coremark-crosscore run-zircon
make -C benchmarks/coremark-crosscore run-xiangshan-yanqihu
make -C benchmarks/coremark-crosscore run-boom-small
make -C benchmarks/coremark-crosscore run-boom-medium
```

当前结果写入 `results/current/results.csv` 和 `results/current/report.md`。结果校验包括
CoreMark 验证标记、五个 CRC 和计时周期与分数的一致性。

## 主机依赖

需要 Git、GNU Make、CMake 3.25、Python 3、curl、Verilator 5、sbt 和 Java。
LLVM 需要包含 RISC-V 后端、LLD、`llvm-objcopy` 与 `llvm-objdump`；Spike/FESVR 开发
库通过 `riscv-riscv.pc` 或 `RISCV` 提供。BOOM 构建使用 JDK 11，可通过
`COREMARK_JAVA_HOME` 指定。`LLVM_BIN` 可指定 LLVM 工具目录。

## 测量方法

- 使用 CoreMark v1.01，默认运行 30 轮，算法文件均以 Clang `-O2` 编译。
- 在 `iterate()` 前后读取 `mcycle`，以 `轮数 × 1,000,000 / 计时周期` 计算 CoreMark/MHz。
- 仿真器随机种子固定为 `1`；每个目标都校验 CoreMark 的结果标记和 CRC。
- 香山与两个 BOOM 配置使用 RV64GC/LP64D 构建；Zircon 使用对应的 RV32IMAF/ILP32F
  构建。工作负载源码与测量方法相同，目标 ELF 不按字节相同。

此分数衡量每周期吞吐，不代表面积、功耗或最高时钟频率。EEMBC 正式提交还要求在目标实现
上运行至少十秒。参考分数与配置见[结果](results/reference/2026-09-18/report.md)和
[配置说明](results/reference/2026-09-18/CONFIGURATION.md)。

## 文件组织

| 路径 | 内容 |
| --- | --- |
| `configs/revisions.lock` | 外部源码版本 |
| `patches/chipyard/` | Chipyard 主机构建兼容补丁 |
| `workloads/coremark/` | RV64 平台移植层 |
| `scripts/` | 获取、构建、运行与结果校验 |
| `results/reference/` | 固定的参考结果 |
| `results/current/` | 最近一次运行的结果 |
| `work/` | 外部源码与构建产物 |

上游来源与许可见[来源说明](PROVENANCE.md)。
