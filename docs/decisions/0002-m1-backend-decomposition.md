# ADR-0002：M1 后端按架构状态与执行引用分离

状态：Accepted

## 背景

Zircon-2024 在 IQ 中复制 PC、立即数、操作数数据、预测结果、异常和写回字段，
造成至少 6924 bit 的直接 IQ payload/state。M1 必须建立可扩展到双 LSU 与 FPU
的后端骨架，同时保持 E0 为唯一控制流/系统重定向源。

## 决策

完整 `DecodedInstruction`、原始 instruction、PC、预测元数据和精确异常归 ROB
所有。IQ 只保存 83-bit `UopRef` 和独立的 valid/age/replay 状态。译码器输出
允许端点 mask：简单整数允许 E0/E1，control/system/CSR 只允许 E0，memory
只允许 M0/M1 的合法子集。非法编码不进入执行端点，而是携带 illegal-instruction
异常进入 ROB，最终只在提交点 trap。

E0 和 E1 复用同一整数语义单元定义，但 E1 的 admission 必须拒绝 branch、jump、
CSR、system、fence 和 memory。这样避免维护两份易分叉的 ISA 实现，同时维持唯一
redirect source。

## 后果

- 译码合法性与端点合法性可独立穷举测试。
- ROB 比较宽，但只有 24 份；IQ 中不再复制 PC、instruction 或预测/异常载荷。
- 后续 M/A/F 扩展只增加操作枚举和对应端点，不改变精确提交边界。
- E0/E1 共用语义不代表共用物理 ALU；综合阶段可按时序/面积结果决定是否复制
  轻量加法/逻辑器件。
