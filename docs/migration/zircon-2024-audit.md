# Zircon-2024 来源与工作区审计

审计日期：2026-08-31。该审计只记录状态，不修改、stash 或 reset 旧工作区。

## 干净迁移基线

- Core：`65a3dd381f4c83a5844858a927dafdbc8263c35e`
- RV-Software：`5f81f2ad378f537182e4cf1a0fcb45159509a2ec`
- ZirconSim：`b1694da4a92046edeead50c9b2a1c086a13e6511`

两个子仓的 `zircon-2026` 分支均从上述固定提交创建。父仓通过 gitlink 固定提交，不依赖分支浮动状态。

## 旧父仓未提交状态

- 修改：Cache 配置、frontend `NPC.scala`。
- 新增未跟踪：rename/free-list 图、`tools/`、`ucagent_zircon/`。
- 处理：不自动移植；架构图和工具需分别检查来源、许可证和与 2026 接口的一致性。

## RV-Software 未提交状态

- DSP 目录包含大量删除、替换源码、脚本和结果目录。
- base-port、cholesky、dhrystone、matmul 有局部修改。
- functest 有新增 vecadd 文件。
- 处理：编译基础设施和通用测试逐项移植；生成结果不入库；DSP workload 改动需独立正确性测试。

## ZirconSim 未提交状态

- `src/Emulator.cc` 修改了 pass/fail 返回行为。
- `src/main.cc` 混入 workload-specific DSP 输出。
- 处理：两项均不进入 M0 基线；2026 harness 使用 ELF symbol、完整 retire trace 和确定性 device model 重建。
