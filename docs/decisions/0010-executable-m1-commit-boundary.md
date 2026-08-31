# ADR-0010：可执行 M1 的精确提交与 retire 边界

状态：Accepted

关联 Issue：#7

## 背景

M1 已分别具备 AXI instruction transport、frontend、整数后端、ROB、commit/CSR 和
trap state，但它们没有构成可执行顶层。若 interrupt EPC 从 fetch PC、预测 PC 或测试
平台输入取得，redirect、drain 或未完成 head 都会使 `mepc` 失真。若 retire trace 从
AXI 响应或重新 decode 推导 PC/instruction/fault，又会在 lane-1 exception 和 branch
recovery 时报告错误的架构事件。

与此同时，M2 LongPipe 和 M3 memory endpoints 尚未存在。让它们以占位 completion
接受 uop 会制造不存在的 architectural progress，破坏精确异常和后续 differential 的
真值边界。

## 决策

`ZirconCore` 在 M1 使用 `M1Frontend -> M1BackendSubsystem` 连接 AXI read channel。
redirect 仲裁顺序固定为 commit-time trap/MRET/FENCE.I、高于 execute-time recovery、
高于 frontend predicted next PC。AXI write channel 在真实 LSU/MMIO 接入前保持 idle。

ROB 以 live `head: Valid[ROBCommit]` 暴露最老尚未退休项，不要求该项已完成。
commit controller 只在该 head 有效时接受 interrupt，并以 `head.entry.pc` 写 `mepc`。
同步 trap 直接携带 faulting `ROBCommit` 与 lane index；interrupt 携带被中断 live head。

trace-enabled top-level 例化 `RetireTraceFormatter`，直接消费 normal retired entries、
PRF/CSR commit data 和上述 exact trap metadata。它以 64-bit 单调 order 编号；lane-1
exception 的 lane-0 retire 先于 trap。`enableTrace=false` 时 formatter、trace port 和
order state 均不生成。

LongPipe、M0 LSU 和 M1 LSU 的 capacity 为零，并且 enqueue ready 为零，直到真实
ready/valid endpoint、completion 与 fault/replay 规则接入。它们不能产生占位 completion。

## 备选方案

- **以 frontend PC 作为 interrupt EPC**：fetch 可能已越过 commit boundary，且 redirect
  或 drain 时无法恢复精确 PC；不采用。
- **由 trace formatter 重新 decode/读取 AXI 数据**：将验证边界耦合到推测状态，无法
  正确表达 precise trap；不采用。
- **为未实现 endpoint 返回零 latency completion**：会让 memory/M/A/F 指令伪退休；
  不采用。

## 后果

- M1 能执行已实现的 RV32I/Zicsr/System 整数路径，并在提交点给 differential harness
  提供真实 retire/trap metadata；ELF harness 和 Spike smoke 仍是 M1 release 的必要工作。
- 没有实际 endpoint 的 M/A/F/访存 uop 会因 dispatch capacity 停止，不会静默完成。
- `head` 是既有 ROB storage 的只读视图；默认综合配置不例化 trace formatter，因此该
  验证状态不进入面积 ledger。将来静态 ledger 覆盖完整 core 时，top-level wiring 和
  live-head mux 仍须如实计入组合代理。
- M3 L1I/LSU 替换暂时 fetch transport 时必须保留 AXI fault、redirect drain、live-head
  EPC 和 commit trace contract。
