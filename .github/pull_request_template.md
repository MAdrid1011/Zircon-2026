## 改动

- 关联 Issue：
- 解决的问题：
- 最终行为：
- 影响的模块或公开接口：
- 子模块提交变化：无

## 验证

- [ ] `sbt test`
- [ ] `sbt "runMain Elaborate --simulation generated"`
- [ ] 硬件或仿真器改动已运行 `cmake --build build/cmake --target coremark --parallel`
- [ ] CoreMark 已通过 Spike 提交级差分
- [ ] 未生成无边界的波形文件

CoreMark 指令数 / 周期 / IPC：

报告路径或失败证据：

## 实现影响

- 性能变化：未测量
- 面积变化：未测量
- 时序变化：未测量
- RAM / FPGA / ASIC 映射变化：无

文档改动可将不适用的验证项标为不适用，并说明原因。其余未测量项目请明确写“未测量”。
