# M1 BTB、RAS 与目标选择

该模块组为四路取指提供目标预测。方向在 M1 来自 Base bimodal，M4 换成
miniTAGE；目标和调用栈契约不随方向预测器变化。

## 参数与组织

| 项目 | 固定值 | 说明 |
|---|---:|---|
| BTB entries | 64 | 32 sets×2 ways |
| BTB banks | 4 | set 低两位选择 bank |
| bank rows | 8 | `pc[6:4]` |
| BTB tag | 25 bit | `pc[31:7]` |
| BTB target | 32 bit | 四字节对齐目标 |
| RAS depth | 8 | 32-bit return address |
| query width | 4 | `fetchBase + 4*i` |

BTB 每项保存 valid、tag、target、conditional、call 和 return 属性。call/return
可以同时为真，用于 `JALR` coroutine 的 pop-then-push RAS hint。BTB 不保存方向
counter，也不根据 opcode 自行重新译码。

## 接口

### `BankedBranchTargetBuffer`

| 信号 | 方向 | 含义 |
|---|---|---|
| `fetchBase` | in | 四字节对齐的 slot 0 PC |
| `predictions[4]` | out | 每个 slot 的 hit、way、target 和控制流属性 |
| `train.valid/bits` | in | 提交后的 PC、实际 target 和属性 |
| `invalidate` | in | `FENCE.I` 请求 BTB 全失效 |
| `ready` | out | 四路 query 本周期有效 |

### `ReturnAddressStack`

| 信号 | 方向 | 含义 |
|---|---|---|
| `topValid/top` | out | 当前可用于 return 的栈顶 |
| `pointer/count` | out | BDB 在推测事件前保存的检查点 |
| `speculate` | in | accepted fetch 的 push/pop/return-address 事件 |
| `recover` | in | 分支错误恢复后的 pointer/count 与正确事件 |
| `clear` | in | reset/FENCE.I/全前端清空时丢弃预测栈 |

`FetchTargetSelector` 输入四项 control predecode、BTB 查询、方向预测和 RAS top，
输出至多一个 redirect，并给出 owner slot、target、call/return。direct branch/JAL
使用 predecode target，不依赖 BTB hit；JALR return 在 RAS 非空时使用 RAS top，
否则使用 BTB target。两者都没有时输出 unresolved-indirect barrier。

## 流水与端口时序

BTB query 是组合读，正常周期四个 bank 各查询一个 set。提交训练把目标 bank 的
读地址切换到 training set，以完成 hit/update 或 invalid-first/replacement 分配；
该周期 `ready=0`，fetch group 保持不变。训练后的下一周期可以查询到新值。

硬复位和 `invalidate` 使用 row scrub。四个 bank、两个 way 在同一 scrub 周期写同一
row 的 invalid entry，共 8 周期。scrub 期间 `ready=0`，不接受训练。replacement
状态随相应 set 的 scrub 清零。

RAS 是寄存器阵列。优先级为 `clear > recover > speculate`。普通 push 写 pointer
指向的项后 pointer 加一；pop 在非空时 pointer 减一；pop+push 先 pop，再在弹出位置
写入新的 return address。overflow 保持 count=8 并循环覆盖最旧项；underflow 不改
pointer/count。

## redirect 与恢复规则

slot 按 0→3 程序序扫描，控制流身份以来自当前 instruction word 的 predecode 为准：

1. conditional 只有方向 provider 给出 taken 时才是 redirect 候选，direct target 不依赖 BTB。
2. JAL 总是 direct redirect 候选。
3. JALR 必须有 RAS 或 BTB target；否则成为 unresolved barrier。
4. 第一项 redirect/barrier 独占 owner；更晚 slot 不得更新 GHR 或 RAS。
5. return 优先使用有效 RAS top，否则使用该 BTB 项保存的 target。

BDB 在每条被接受的控制流 uop 中保存预测前的 GHR、RAS pointer/count、BTB way 和
预测 target。错误预测时先删除年轻 BDB 项，再恢复检查点并应用解析出的实际结果。
call 与 return 同时为真时，恢复动作是 pop-then-push；count 非零时保持不变，空栈
时变为 1。

## 排空、重放与异常

- training 周期和 scrub 周期通过 `ready=0` 重放当前 fetch group，不得丢弃 PC。
- `FENCE.I` 在提交点排空旧 memory/device 操作后，触发 BTB scrub、RAS clear 和前端
  redirect；scrub 完成后才允许新 query。
- trap、MRET 和外部 flush 可以 clear RAS；BTB 只由 reset/`FENCE.I` 失效。
- 预测器不产生架构异常。非法或未对齐控制流目标由取指/执行路径生成精确异常，不能
  通过写入 BTB 绕过检查。

## 不变量与性能计数器

- 每个 query slot 最多命中一个 way，同一 fetch group 最多一个 redirect owner。
- `ready=0` 时所有 prediction hit 和 redirect 必须无效。
- query 不能修改 valid、target 或 replacement 状态。
- reset/`FENCE.I` scrub 完成后所有 64 项均 invalid。
- RAS count 始终位于 0..8；underflow/overflow 不越界。
- recovery 周期不同时接受普通 speculative event。

前端集成后至少提供 `btb_hit`、`btb_miss`、`ras_hit`、`ras_empty_return`、
`btb_train_stall` 和 `btb_invalidate_stall` 计数。独立模块暂只暴露事件，不在阵列旁
复制 64-bit 性能计数器。

## 验证映射

| 功能 | Directed test / assertion |
|---|---|
| cold start 与 invalidation | reset scrub、`FENCE.I` scrub 后 64 项 miss |
| bank/index/tag | 四连续 PC、跨 16-byte group、同 set 不同 tag |
| replacement | invalid-first、两 way 满、hit 后 replacement 更新 |
| 多控制流 | conditional not-taken 后跳转、多个 taken 只选最早 slot |
| RAS | nesting、overflow、underflow、pop+push、clear、recovery |
| 端口冲突 | train/scrub 时 query invalid，下一周期看到更新 |
| 不变量 | duplicate hit、count 越界、非对齐 query/train 断言 |

随机前端回归还需交叉 BTB alias、Base alias、RAS depth、fetch slot、mispredict 原因和
flush 类型；完整覆盖在取指与 BDB 接入顶层后统计。
