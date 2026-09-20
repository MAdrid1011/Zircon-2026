# Middleend

`Middleend` 连接 Fetch Queue 与后端发射队列，负责单周期最多三条指令的选择、译码、整数与
浮点寄存器重命名、源操作数就绪查询、ROB/SQ/FTQ 资源分配和派发。

## 输入保持与译码

Fetch Queue 以压缩后的指令条目保存取指结果，并向中端提供全局最老的三条指令；同一组输出
可以包含前一个 fetch packet 的尾部和后一个 packet 的头部。三份 `Decoder` 产生执行单元、
操作码、源寄存器、目的寄存器、访存类型和系统操作字段。`packetStart`、`packetEnd` 与
`fetchToken` 保留 packet 边界，确保跨 packet 派发时仍只为每个 packet 分配一次 FTQ 表项。

## Rename

整数和浮点各例化一份 `Rename`。整数域默认包含 72 个物理寄存器并保留物理寄存器 0；浮点域
包含 48 个物理寄存器。每份 Rename 维护推测映射、提交映射和空闲物理寄存器队列，并在同一
派发组内解决 RAW 与 WAW 依赖。

Rename 输出进入一个三项宽的压缩段间寄存器。派发只能接受有序前缀，未接受项保留在该寄存器中，
并立即向前端反压。恢复时，映射表回到提交状态并丢弃未派发或未提交的年轻指令。

## ReadyBoard 与派发

`ReadyBoard` 跟踪统一物理标签的可用状态。新目的寄存器在分配时标记为未就绪；后端的确定性
唤醒和访存推测唤醒更新源状态。Load replay 会撤销对应的推测唤醒。

`Dispatcher` 将指令送入六个压缩式发射队列：两条整数/分支队列、`MixArith` 队列、LS0 Load
队列、LS1 Load/Store Address 队列和 Store Data 队列。Store 同时生成地址和数据任务；只有
两个目标队列都能接受时才完成该条 Store 的派发。ROB、SQ 和 FTQ 的空间许可与发射队列空间
共同形成有序接受前缀。
