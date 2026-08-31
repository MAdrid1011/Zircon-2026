# ADR-0004：前端预译码驱动无遗漏推测历史

状态：Accepted

## 背景

只用 BTB hit 判断 conditional branch 会漏掉第一次出现的分支。若该分支预测为
not-taken 且实际也 not-taken，它不会触发错误恢复，64-bit global history 就永久少
移入一位；后续 Base/miniTAGE 的索引与 BDB checkpoint 因而不一致。四路取指还必须
处理同组多个分支、无目标 JALR 和 x1/x5 coroutine RAS hint。

## 决策

I-Cache 返回的四个 instruction word 先经过轻量 control predecode。predecoder 只识别
合法 RV32 conditional branch、JAL 和 JALR，计算 branch/JAL direct target，并按
`rd/rs1` 是否为 x1/x5 产生 RAS push、pop 或 pop-then-push。完整合法性、寄存器读取
和异常仍由后端 decoder/E0 负责。

redirect selector 以 predecode 为控制流真值：conditional 使用方向 provider，branch/
JAL 即使 BTB miss 也使用立即数 target；JALR 使用有效 RAS top 或 BTB target。没有
RAS/BTB target 的 JALR 作为 fetch barrier，保留该指令并截断年轻 slot，等待 E0 解析，
不能把 fall-through 当成有效预测路径。

64-bit speculative history 按 slot 0→3 扫描 accepted prefix。每个 accepted
conditional 都移入预测方向，包括 BTB miss 和同组中 redirect 之前的 not-taken
branch；同时输出每个 slot 更新前的 history checkpoint。mispredict 直接安装 BDB
给出的 `{historyBefore, actualTaken}`，trap、MRET、`FENCE.I` 或全前端清空可以将
非架构历史重置为零。

## 后果

- 首见且预测正确的 not-taken branch 不再丢失历史。
- predecode 增加少量 opcode/immediate 组合逻辑，但避免在前端复制完整 RV32 decoder。
- JALR target miss 会产生显式前端停顿，性能计数器可以独立归因，不能错误执行年轻
  指令来掩盖停顿。
- M4 miniTAGE 直接消费同一组 per-slot `historyBefore`；替换方向 provider 不改变
  BDB 或恢复协议。
