# M1 Fetch/Decode Queue

`FetchDecodeQueue` 将四路 fetch acceptance 转换为两路 decode/dispatch stream。它只做
顺序缓冲和 credit 管理，不译码、不训练预测器，也不修改架构状态。

## 参数与队列项

| 项目 | 固定值 |
|---|---:|
| depth | 4 |
| enqueue width | 1..4，单一原子 bundle |
| dequeue width | 0..2，连续 prefix |
| head/tail | 各 2 bit，modulo-4 |
| count | 3 bit，0..4 |

每项保存：

- 32-bit instruction。
- `BranchPredictionMetadata`：PC、64-bit history-before、direction/target、provider、
  BTB way 和 RAS pointer/count。
- 2-bit privilege；M1 固定写 M-mode，但字段保留在验证边界。
- fetch fault 的 valid、6-bit cause 和 32-bit tval。

完整 `DecodedInstruction` 不进入本队列，避免四份宽组合译码结果常驻。正式两路 decoder
在 dequeue 之后运行，decode result 与 instruction/PC 一起集中写入 24 项 ROB。

## 接口

| 信号 | 方向 | 含义 |
|---|---|---|
| `enqueue.valid/ready` | handshake | 一整个 fetch prefix |
| `enqueue.bits.count` | in | 1..4 项；valid 时不得为 0 |
| `enqueue.bits.entries[4]` | in | 前 count 项有效 |
| `dequeue[2].valid/ready` | handshake | lane0 或 lane0+lane1 顺序消费 |
| `flush` | in | branch/trap/MRET/FENCE.I 清空 |
| `count` | out | 当前 occupancy |

lane1 只有在至少两项有效时才 valid；消费者不得令 lane1 ready 而 lane0 not-ready。
enqueue 是 bundle-level 原子事务，不允许只写一部分 fetch group。

## credit 与状态转换

组合 credit 为 `depth - count + dequeueFireCount`。当 credit 不小于 enqueue count 时
`enqueue.ready=1`。这允许以下满队列稳态：

1. 第一个周期 dequeue 两项，occupancy 4→2，四项输入仍保持。
2. 第二个周期再 dequeue 两项，同时 enqueue 四项，occupancy 保持 4。
3. 重复上述过程，decode 每周期持续得到两项。

出队读取 edge 前的旧 head 项；同周期 enqueue 可覆盖刚被消费的物理位置。时钟沿后
head 前进 dequeue count、tail 前进 enqueue count，count 做加减。count 而非 data
reset 位决定有效性。

## 排空、回压与异常

- credit 不足时 `enqueue.ready=0`，上游必须保持整组 instruction/metadata。
- lane1 被阻塞但 lane0 ready 时只消费一项，下一项在下周期成为 lane0。
- `flush` 优先级最高：禁止 enqueue/dequeue fire，并把 head/tail/count 归零。
- fetch fault 与普通项按同一顺序出队；fault 只在 ROB/commit 形成精确 trap，不能在
  queue 内提前 redirect 或丢弃更老项。

## 不变量、性能计数器与验证

- `count <= 4`，full/empty 不依赖 head==tail 推断。
- lane1 fire 蕴含 lane0 fire；输入 count 位于 1..4。
- enqueue/dequeue 后顺序、payload 和总 credit 守恒。
- flush 后下一周期 count=0，旧 entryData 永不输出为 valid。

集成后记录 `fetch_queue_full_stall`、occupancy histogram 和单路/双路 dequeue 周期。
directed tests 覆盖 1..4 项组、满/空、单/双 drain、同周期 recycle、pointer wrap、
backpressure、预测/fault payload 和 flush。形式化阶段证明 bounded FIFO ordering 与
credit conservation。
