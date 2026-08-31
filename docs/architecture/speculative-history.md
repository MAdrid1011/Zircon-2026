# M1 控制流预译码与推测历史

本模块组位于 I-Cache 返回和两路正式译码之前，为四条 instruction word 生成控制流
类别、direct target、RAS hint、accepted prefix 和 64-bit global-history checkpoint。

## 参数与接口

固定参数为 4 instruction fetch、32-bit instruction、`IALIGN=32` 和 64-bit history。

### `RV32ControlPredecoder`

| 输入/输出 | 含义 |
|---|---|
| `pc/instruction` | 当前 slot 的 PC 和原始 32-bit instruction |
| `control/conditional` | 合法 branch/JAL/JALR 以及 conditional 分类 |
| `direct/indirect` | branch/JAL 为 direct，JALR 为 indirect |
| `directTarget` | `pc+B-imm` 或 `pc+J-imm`，JALR 为 0 |
| `call/ret` | RAS push/pop hint，可同时为真 |

predecode 不输出 `legal` 的完整 ISA 结论；非控制指令与保留 control encoding 只输出
`control=0`，仍须送正式 decoder。

### `FetchTargetSelector`

新增 `slotValid[4]` 和 `predecode[4]` 输入。输出 `redirect`、`rasAction`、
`acceptedMask[4]` 和 `unresolvedIndirect`。`acceptedMask` 是从 slot 0 开始的有效
prefix，并包含 redirect 或 unresolved JALR owner 本身。

### `SpeculativeGlobalHistory`

| 信号 | 方向 | 含义 |
|---|---|---|
| `slotValid/conditional/predictedTaken[4]` | in | 当前 fetch group 的逐 slot 事件 |
| `acceptedMask` | in | selector 允许进入前端的 prefix |
| `advance` | in | fetch group 被接受，本周期提交推测更新 |
| `historyBefore[4]` | out | 每个 slot 处理之前的 64-bit checkpoint |
| `historyAfter` | out | 当前 group 扫描后的组合值 |
| `recover` | in | BDB mispredict recovery history |
| `clear` | in | 清空非架构预测状态 |

## 预译码与 RAS hint

conditional branch 仅接受 funct3 `000/001/100/101/110/111`；保留 `010/011` 不预测。
JAL 总是 direct，JALR 仅接受 funct3 `000`。direct target 使用同正式 decoder 一致的
B/J immediate 符号扩展和 32-bit wraparound。

link register 定义为 x1 或 x5：

| 指令 | 条件 | RAS 动作 |
|---|---|---|
| JAL | rd 是 link | push |
| JALR | rd 非 link，rs1 是 link | pop |
| JALR | rd 是 link，rs1 非 link | push |
| JALR | rd/rs1 是同一 link | push |
| JALR | rd/rs1 是不同 link | pop 后 push |

## 四 slot 选择和流水行为

selector 按程序序寻找第一个 stop owner：

1. predicted-taken conditional 是 direct redirect，target 来自 predecode。
2. JAL 是 direct redirect，target 来自 predecode。
3. JALR return 且 RAS 非空时使用 RAS top。
4. 其他 JALR 在 BTB hit 时使用 BTB target。
5. 无 RAS/BTB target 的 JALR 是 unresolved barrier，不产生 redirect，但阻止年轻 slot。

not-taken conditional 不停止扫描。BTB 中残留的非控制项不能自行产生 redirect；控制流
身份以当前 instruction word 的 predecode 为准。predictor query 未 ready 时不接受任何
slot，也不产生 redirect、barrier 或历史更新。

history combinational scan 为：slot i 的 `historyBefore(i)` 等于处理所有更早 accepted
conditional 后的值；若 slot i 是 accepted conditional，则下一值为
`{history[62:0], predictedTaken(i)}`。一个 group 可以一次移入 0..4 位。时序优先级为
`clear > recover > advance`。

## 排空、重放与异常

- Base/BTB training 或 BTB scrub 使 predictor not-ready，fetch group 保持并重放。
- unresolved JALR 只允许该 slot 及其更老 slot 进入队列；E0 resolve 后通过正常
  redirect/recovery 继续，不允许顺序取执行年轻指令。
- direct target bit 1 非零仍由 E0 产生精确 instruction-address-misaligned；predecode
  可以提前取错目标，但不能提交该错误路径状态。
- trap、MRET、`FENCE.I` 和 reset 可 clear history；branch mispredict 必须 recover，
  不能简单 clear，否则 miniTAGE 的正确路径上下文会丢失。

## 不变量、计数器与验证映射

- `slotValid` 和 `acceptedMask` 均为低位连续 prefix。
- 每组至多一个 redirect 或 unresolved owner，二者互斥。
- owner 之后没有 RAS/GHR 更新；owner 本身包含在 accepted prefix。
- 非 control 或非法 control encoding 不生成 direction/RAS 事件。
- history recover/clear 周期不能同时应用普通 advance。

集成后记录 `direct_redirect`、`ras_redirect`、`btb_indirect_redirect`、
`unresolved_jalr_stall`、`conditionals_per_fetch` 和 `history_recovery`。directed tests
覆盖保留 funct3、B/J immediate 边界、全部 x1/x5 hint 组合、BTB-miss taken branch、
同组多 branch、JALR barrier、64-bit 边界、recover 和 clear。
