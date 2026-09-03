# ADR-0027：冻结 miniTAGE 方向预测器

状态：Accepted

## 背景

M1 的四银行 Base bimodal 只能提供低成本基线方向预测。M4 合同要求在不改变
BTB、RAS、BDB 和恢复接口的前提下加入三张 tagged history table。预测结果同时
参与同一 fetch group 的 speculative history 更新，直接让每个 slot 读取更新后的
history 会形成组合环。

## 决策

`MiniTagePredictor` 使用 512-entry、2-bit Base 表和 3 张 128-entry tagged 表，
历史长度 4/16/64，tag 7/8/9 bit，counter 3 bit，useful 2 bit。四路查询按 PC
低两位分 bank；提交训练更新 provider，误预测分配首个未命中表并初始化为弱偏向
实际方向。前端查询统一使用本 fetch group 的起始 history，逐 slot history 仍由
`SpeculativeGlobalHistory` 记录并用于 BDB recovery。这样保持可综合的无环 ready/valid
边界，且不改变控制流正确性；逐 slot folded-history 查询留作后续性能优化。

## 后果

- 预测器已替换 M1Frontend 中的临时 bimodal，冷启动仍由 Base provider 提供结果。
- scrub 和训练周期使 `ready=0`，fetch group 保持不丢失。
- tagged 表增加有限的状态位，但不复制 BTB/RAS 或后端恢复状态。
