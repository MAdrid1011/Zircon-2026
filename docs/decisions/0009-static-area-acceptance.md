# ADR-0009：以可复现静态账本作为面积验收

状态：Accepted

关联 Issue：#39

## 背景

原计划要求在 Vivado 2026.1、目标 FPGA 上完成同约束 post-route 比较。
该流程依赖未安装的商业工具、运行时间长，并会让 RTL 功能研发受外部 runner 阻塞。
项目所有者在 2026-08-31 决定：Zircon-2026 的面积目标改用静态评估签收，不要求
厂商工具实测。

简单相加源码数组容量仍不足以支撑“更小”结论，因为多读写端口可能引入复制，CAM、
大 mux、旁路和仲裁也会产生显著逻辑面积。因此静态评估必须同时记录持久状态和主要
组合复杂度代理，且对 Zircon-2024/2026 使用同一计算规则。

## 决策

面积签收的正式证据是仓库内可复现的 static-area manifest、计算脚本和报告。固定比较点
仍为 Zircon-2024 core `65a3dd381f4c83a5844858a927dafdbc8263c35e` 与完整默认
Zircon-2026 配置。报告至少包含：

- register-backed payload、valid/age/replay/control bit；
- SRAM/BRAM candidate 的 data、tag、metadata bit；
- 为满足逻辑 read/write port 所需的保守 replication factor 与 replicated storage bit；
- CAM compare-bit product、mux input-bit product 和主要 priority/age selector 规模；
- 32-bit adder/compare/shift 单元数量、16×16 partial-product 单元数量、迭代 divide/sqrt
  engine 数量；
- Cache/BTB/TAGE/BDB/ROB/IQ/LQ/SQ/MSHR/victim/result queue 的逐项明细；
- 每个增长项的架构原因、共享策略和对应缩减项。

脚本不得把静态代理伪称为 LUT、FF、BRAM、频率或功耗。签收结论以逐类账本和完整配置
总量为依据；若端口实现存在多种合法映射，采用面积更大的保守映射，除非 RTL 明确实例化
了更小实现。4 KiB/8 KiB L2 仍只按冻结 IPC 门槛选择，不凭面积账本改变性能规则。

Vivado、Yosys 或其他综合报告可以作为可选旁证，但缺失时不阻塞里程碑，也不覆盖静态
账本中已经披露的结构增长。

目标器件和 100 MHz 的独立时序验收由 ADR-0022 定义。它不改变本 ADR 的静态面积签收
方法，也不允许以某个器件的综合结果替代完整静态账本。

## 后果

- 面积评估可在普通开发主机和 CI 中确定性复现，不再等待商业工具。
- 无法直接声称特定 FPGA 的 LUT/FF/BRAM、WNS、最大频率或功耗达标；发布文档必须明确
  这是静态结构结论。
- 所有新增多端口阵列必须在模块规格中写明物理实现假设，否则按保守复制计费。
- M5 仍需做性能收敛和 4/8 KiB L2 对照；只移除 vendor post-route 硬门槛，不降低 ISA、
  corner case、形式化、差分和覆盖率要求。
