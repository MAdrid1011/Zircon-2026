# ADR-0003：四银行 BTB 与可恢复 RAS

状态：Accepted

## 背景

Zircon-2026 前端每周期查询四条连续、四字节对齐的指令地址。若直接把 64 项、
2-way BTB 做成四读口阵列，会复制数据或产生大规模读端口选择逻辑；若只保留一个
读口，又会把取指宽度退化成一条。RAS 还必须在分支解析前推测更新，否则连续调用与
返回无法得到正确的栈顶预测。

## 决策

BTB 使用 32 sets×2 ways，并按 set 的低两位 `pc[3:2]` 分成四个 bank；bank 内
row 为 `pc[6:4]`，tag 为 `pc[31:7]`。四条连续 PC 恰好各访问一个 bank，因此每个
way/bank 只需一个数据读口。每个 set 使用一位 replacement 状态，分配优先选择
invalid way，两个 way 都有效时再选择 replacement way。命中和分配只在提交训练时
更新 replacement 状态，错误路径查询不能污染 BTB。

BTB 只接受提交后的训练。训练周期复用目标 bank 的读口并暂停取指查询；`FENCE.I`
触发八周期 invalid scrub，在 scrub 完成前不提供预测。一个 fetch group 中按程序序
扫描 BTB 命中：not-taken conditional 可以继续扫描，第一条 predicted-taken
conditional 或 unconditional control instruction 是唯一 redirect owner。

RAS 使用 8×32-bit 环形栈，pointer 指向下一次 push 的位置，count 在 0..8 饱和。
推测事件支持 push、pop 以及 coroutine 所需的 pop-then-push。BDB 保存事件发生前的
pointer/count；错误预测恢复时优先恢复该检查点，再由解析结果重做正确的 push/pop。
栈内容是非架构预测状态，恢复只保证 pointer/count 精确；环形溢出造成的旧内容覆盖
允许影响预测质量，但不能影响程序正确性。

## 后果

- 四路查询不复制完整 BTB，物理结构为 4 banks×2 ways×8 rows。
- 提交训练和 `FENCE.I` scrub 会产生明确、可计数的前端暂停周期。
- redirect 选择、BTB 存储和 RAS 状态分成独立模块，可分别进行穷举 directed test。
- 后续 miniTAGE 只替换 conditional direction provider；BTB、RAS 和最早 redirect
  规则保持不变。
- BDB 的 call/return 恢复逻辑必须允许二者同时为真，并按 pop-then-push 处理。
