# Rename 与整数物理寄存器文件

本模块服务 M1 Issue #7，并冻结为 2-wide rename、56×32 bit integer PRF、6R2W。
物理寄存器 p0 永久对应 x0；reset 时 x0–x31 映射 p0–p31，p32–p55 空闲。

## 双路 rename

两条输入必须保持程序序，lane 1 valid 隐含 lane 0 valid。整个 bundle 只在
`accept && canAllocate` 时改变状态；如果本周期目的寄存器所需的 free entries
不足，两条都不接受，避免半包 dispatch。

lane 1 的源和旧目的映射必须旁路 lane 0 的新映射。因此以下序列在同一周期
rename 时不经过 RAT 的下一周期状态：

```text
lane 0: add x5, x1, x2   -> p32
lane 1: sub x6, x5, x3   -> prs1=p32
```

对 x0 的写不分配物理寄存器；x0 源始终映射 p0。

## Speculative 与 committed 状态

模块分别保存 speculative map/free-list 和 committed map/free-list。dispatch 从
speculative free-list 分配；commit 按 lane 0、lane 1 程序序更新 committed map，
释放每条指令的 old physical destination。trap/global rollback 使用本周期 commit
后的 committed snapshot 恢复 speculative 状态。

分支误预测的 execute-time 恢复不能退回 committed snapshot。Rename 从 ROB tail
walker 接收最多两项、newest→older 的 undo bundle，使用 ROB 已保存的 old/new
physical destination 恢复 speculative map/free-list，不在每个 BDB 项复制整份状态。
该接口与 IQ/completion selective kill 全部接入前，rename 仍为 M1 partial。

## 6R2W PRF

六个组合读端口对应 E0 两源、共享 E1/E2 两源和双 LSU 各一个基址/数据调度
预算；具体端口映射可在 operand-read stage 复用，但总端口数不得增加。两个写
端口来自统一 completion arbiter。

- 写 p0 为协议错误并由 assertion 捕获。
- 两个有效写端口命中同一非零 physical register 为协议错误。
- 同周期 read-after-write 使用 completion forwarding，避免额外一周期 wakeup。
- reset 将全部寄存器清零；p0 的读值恒为零。

## Integer Ready Table

56-bit ready table 与 PRF 分离：dispatch 为每个实际分配的 integer physical
destination 清 ready，两个统一 completion 为对应位置 ready。completion mask 组合
旁路到 `ready` 输出，使“producer completion 与 consumer dispatch 同周期”不会把
consumer 永久留在 not-ready；新 allocation mask 最后清零，禁止新 producer 被误判为
已完成。p0 永远 ready。

branch rollback 和 global flush 不扫描 ready table。被放回 free-list 的错误路径
physical register 在下次 allocation 时必然再次清 busy；surviving prefix 的旧映射保持
原 readiness。这个选择避免一条 56-bit 恢复写路径，但要求所有 endpoint kill 晚到的
错误路径 completion。

## 不变量与验证映射

- speculative map 和 committed map 的 x0 项恒为 p0。
- free physical 不能同时出现在对应 map 中。
- 两条 rename 不得获得相同 physical destination。
- 同周期 WAW 时，lane 1 old destination 等于 lane 0 new destination。
- 双 commit 同一 architectural destination 时，最终 committed map 指向 lane 1，
  lane 0 new destination成为 free。
- flush 恢复 committed map/free-list，所有未提交分配重新可用。
- branch rollback bundle 必须 newest→older；连续 WAW undo 后 RAT 指向 surviving prefix
  的最后一个 physical destination，所有被撤销 new physical 均回到 free-list。
- dual allocation/dual completion 的 physical destination 各自唯一；同一 physical
  不得在同周期 allocation 和 completion。
- PRF 覆盖六路同时读、两路写、write-forwarding、x0 和同目标写 assertion。
