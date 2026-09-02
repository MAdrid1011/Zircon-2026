# ADR-0022: FPGA release target platform

状态：Accepted

关联 Issue：#48

## 背景

此前的发布文档使用 Nexys4 DDR 和 `xc7a100tcsg324-1` 作为物理实现点。项目所有者已
将目标器件明确改为 `xc7a200tfbg676-2L`。继续保留旧器件会使 `toolchain.lock.json`、时序
报告和发布门槛互相矛盾，也不能成为新目标板卡的旁证。

同一工作区的 LA32R 工程表明该器件的板卡参考设计采用 10.000 ns 时钟约束，但其顶层端口、
复位极性、UART/LED 定义与 Zircon 不同。该工程的 XDC 只能说明约束写法，不能作为
Zircon 的引脚约束或时序证据。

## 决策

Zircon-2026 的唯一 FPGA release target 为 `xc7a200tfbg676-2L`，目标核心时钟为 100 MHz
（`create_clock -period 10.000`）。最终候选版本必须在该器件上，以 Zircon 自己的板级
wrapper 和与其端口完全匹配的 XDC 完成可复现 post-route 实现。

提交的 timing evidence 必须记录 Vivado 版本、RTL 与 submodule SHA、XDC SHA、top module、
器件、时钟名、10.000 ns 约束、setup WNS/TNS、worst hold slack、利用率和完整命令行。
setup WNS 必须非负。未提供上述 Zircon-specific 证据时，该门禁是 **unverified**，而不是
通过或失败。

ADR-0009 的静态面积账本仍是独立且强制的面积签收。时序收敛必须保持冻结 ISA、精确
异常/中断、双 LSU、Cache、FPU、miniTAGE 和验证范围；不得删除功能或隐藏结构以改善 WNS。

## 后果

- `toolchain.lock.json`、验证计划和发布文档统一记录该器件，旧 Nexys4 目标不再用于 Zircon。
- 在 Zircon board wrapper 与已核验 pinout 存在前，不得复制外部工程的 `PACKAGE_PIN` 赋值。
- 若 routing delay 主导，优先减少扇出、复制阵列和宽仲裁；若 logic delay 主导，缩短或
  寄存宽选择/age-arbitration 组合路径，并为新增状态更新静态面积账本。
