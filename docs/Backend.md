# Backend

`Backend` 包含六个压缩式发射队列、两条整数/分支流水线、一条共享乘除浮点流水线、两条访存
流水线、原子执行单元、整数与浮点物理寄存器堆、旁路网络、唤醒网络和 DCache。ROB、SQ 与
Store Buffer 由 `Commit` 持有，通过专用接口连接。

## 发射队列

两条整数/分支队列各 7 项，`MixArith` 队列 8 项，LS0 Load 队列 6 项，LS1 地址队列 8 项，
Store Data 队列 6 项。队列按物理顺序保存年龄，支持每拍接收一个三条派发组并选择最老的可执行
任务。两条整数队列各有 4 项独立 replay 存储，用于保留带推测依赖的已发射任务。Load 发射后
保留原队列表项，直到完成或 replay；replay 直接重新激活原表项。

## 执行流水线

`Arith0` 和 `Arith1` 均使用 `ArithBranch`，执行整数 ALU、条件分支、JAL 和 JALR。两条流水线
具有相同的 RF、EX、WB 边界，并可互相接收旁路结果。

`MixArithPipeline` 执行整数乘除、FP32 加减乘、融合乘加、除法、平方根、比较、分类、符号操作、
搬运与整数/浮点转换。CSR 指令也由该队列发射，并在获得 `Commit` 的按序授权后访问 CSR 状态。

LS0 只执行 Load。LS1 执行 Load 和 Store Address，并从独立 Store Data 队列读取写数据。两条
Load 通路都查询 SQ 与 Store Buffer，按字节合并比 Cache 更新的 Store 数据。

`AtomicUnit` 执行 `LR.W`、`SC.W` 和九条 `AMO.W`。地址与写数据仍经 LS1 的地址和数据任务写入
SQ；指令到达 ROB 头且更早的存储访问排空后，原子单元复用 DCache 的 LS1 Load 端口与 Store
端口完成操作。Reservation 以物理字地址记录，普通 Store、成功或失败的 `SC.W` 以及其他 AMO
会按相应规则清除它。

## PRF、旁路与唤醒

整数 PRF 为 72 x 32 bit、9 读 5 写；浮点 PRF 为 48 x 32 bit、4 读 3 写。读端口在执行单元
停顿时保持地址。旁路网络包含两条 Arith、MixArith 和两条 Load 共五个生产者。

唤醒网络区分计算唤醒与访存唤醒。Arith 根据消费者类型在发射或 RF 阶段提供唤醒；MixArith
在 EX2、EX3 和 WB 边界提供唤醒；Load 在 D1 发布带推测标签的唤醒，并在 WB 发布确定性唤醒。
`LoadSpeculationTracker` 在命中、异常或 replay 后确认或撤销对应标签。

## 取消与完成

提交恢复清空所有发射队列和执行流水有效位。执行结果通过专用完成端口送入 ROB；分支流水线
同时返回实际方向、目标和误预测状态。Load 完成还携带虚拟地址与访存异常，Store 地址和数据
分别写入 SQ。原子结果通过独立完成端口写回 ROB 和整数 PRF。
