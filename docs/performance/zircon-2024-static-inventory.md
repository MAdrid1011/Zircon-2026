# Zircon-2024 静态资源清单

本清单只依据固定核心提交
`65a3dd381f4c83a5844858a927dafdbc8263c35e` 的 Scala 源码和 elaborated RTL。
它是 Zircon-2026 静态面积基线的第一版人工清单；正式签收将由 ADR-0009 规定的同规则
manifest 和脚本重算，并补齐端口复制与组合复杂度代理。

## 固定配置对比

| 资源 | Zircon-2024 | Zircon-2026 冻结点 | 面积方向 |
|---|---:|---:|---|
| 整数物理寄存器 | 62×32 bit | 56×32 bit | 减少 192 data bit，并减少索引/ready 状态 |
| ROB | 30 项 | 24 项 | 减少 20% entries |
| BDB | 12 项 | 8 项 | 减少 33.3% entries |
| IQ entries | 12+6+6=24 | 12+4+8=24 | entry 数不变，payload 压缩 |
| L1I | 1 KiB，2-way，32 B line | 1 KiB，2-way，32 B line | 容量不变 |
| L1D | 1 KiB，2-way，32 B line | 1 KiB，2-way，32 B line | 容量不变，增加双 tag/bank/4 MSHR |
| L2 | 8 KiB，4-way，64 B line | 默认 4 KiB，4-way，32 B line | data capacity 减少 50% |
| BTB/RAS | 64 项 2-way / 8 项 | 64 项 2-way / 8 项 | 容量不变 |

## IQ payload 预算

Zircon-2024 的每个 `IQEntry` 复制完整 `BackendPackage`。按冻结源码中的
字段宽度计算，`BackendPackage` 为 283 bit；每项再保存 `instExi` 和
与队列深度有关的 `stBefore`。三个队列的直接 payload/state 为：

- IntIQ：12 × (283+1+5) = 3468 bit。
- MulDivIQ：6 × (283+1+4) = 1728 bit。
- MemIQ：6 × (283+1+4) = 1728 bit。
- 合计：6924 bit，不含 free-list、选择器和连线。

Zircon-2026 的 `UopRef` elaborated width 为 86 bit，其中 6-bit ROB tag 包含
wrap generation、5-bit mask 保留 endpoint 调度弹性；三个队列仍合计 24 项，
直接 payload 为 2064 bit。相对旧 payload/state 下界减少 70.2%，超过
“IQ 状态至少下降 30%”门槛。本计算尚未覆盖新增 wakeup、age、valid、replay、CAM
比较和选择 mux，因此不能单独代表完整后端面积结论。

## 新增面积的偿还路径

Zircon-2026 新增第二 LSU、4 个 L1D MSHR、FPR/FPU、A 扩展和 miniTAGE，
因此缩小 ROB/PRF/IQ/L2 只是面积预算，不是达标证明。正式收敛顺序是：

1. IQ 只保存 `UopRef`，完整译码/PC/预测/异常集中于 ROB/BDB。
2. E1/E2 共享取数入口，全部 M 与 F 乘法共享 16×16 部分积阵列。
3. 除法、余数和平方根共享迭代引擎；完成只使用两个统一端口。
4. FPR 使用 2R1W，并用两周期三源读取避免第三读端口。
5. L2 先以 4 KiB 签收；只有性能门槛失败时回退到 8 KiB。

任何“完整 2026 小于完整 2024”的结论都必须等待同一静态面积脚本覆盖完整配置，逐项
披露端口复制、状态 bit 和组合代理；当前人工清单只说明已经落实的缩减方向。
