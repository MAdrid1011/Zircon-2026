# M1 Banked Bimodal Predictor

M1 在完整 miniTAGE 接入前使用冻结 Base 表本身作为方向预测器：512 项、2-bit 饱和计数器。该模块已经按 4 指令取指宽度组织成四个物理 bank，M4 直接复用它作为 miniTAGE Base provider。

## 组织与索引

Base index 为 `pc[10:2]`。bank 取 `pc[3:2]`，每 bank 128 行，row 取 `pc[10:4]`。任意四条连续、四字节对齐指令恰好各访问一个 bank；即使 fetch group 跨越 16-byte 边界，也只发生 bank 轮转而不会增加端口。

接口输入一个 `fetchBase`，组合输出四个 counter/taken。counter 的高位为方向预测。模块要求 `fetchBase[1:0]=0`，输出 slot i 对应 `fetchBase + 4*i`。

## 初始化与训练

counter 编码为 0/1=not-taken、2/3=taken。硬件复位后用 7-bit row pointer 在 128 周期内同时把四个 bank 的同一行写为 weak-not-taken（1）；scrub 期间 `ready=0` 且输出强制 not-taken。这样 data array 不需要逐项 reset，避免在 FPGA 上为 1024 个状态位插入 reset mux。

训练只来自 BDB 的提交记录，每周期最多一项。训练周期把目标 bank 的唯一 read port 从 fetch query 切换到 training row，读取旧 counter 后饱和加/减；因此该周期 `ready=0`，前端保持 fetch group。四个 bank 仍各只有一个 data read port，训练只向一个 bank 写入。初始 scrub 未完成时不允许训练，违反触发断言。

这一单周期提交训练停顿是 M1 的保守行为，不是最终性能承诺。M5 若 stall breakdown 表明它显著影响 IPC，可在不改变表容量的前提下加入一项 training queue 或 read-bypass；不能复制整张 Base 表来掩盖端口冲突。

## 不变量与验证

- 512 个 counter 精确分布为 4×128，query group 每 bank 恰有一项。
- scrub 完成前 `ready=0`，完成后所有未训练 counter 为 1。
- taken 在 3 饱和，not-taken 在 0 饱和，阈值只取 bit 1。
- training 周期 `ready=0`，下一周期查询看到更新值。
- 跨 16-byte group 的 bank 轮转保持 PC/index 对应关系。
- 预测表只接受 commit training，不提供 resolve/speculative update 端口。

BTB、RAS、speculative history 与 BDB recovery 是独立模块；方向表本身不产生 redirect，也不持有 ROB tag。
