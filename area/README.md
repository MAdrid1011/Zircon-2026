# Static-area manifests

本目录保存 ADR-0009 的机器可读输入。`zircon-2024.json` 和 `zircon-2026.json` 必须使用
相同 schema 与计量规则；任何一侧未覆盖完整配置时都保持 `completeness=partial`，并在
`known_omissions` 逐项说明。缺失结构不得用零填充。

## Storage entry

每项 storage 使用：

- `class`：register payload/control、memory data 或 tag/metadata；
- `entries × bits_per_entry × instances`：逻辑状态位；
- `replication_factor`：端口或实现约束造成的保守物理复制；
- `source`：配置、类型宽度测试、elaborated RTL 或冻结规格位置。

报告同时给出 logical 和 replicated bit。banking 不等于 replication；只有同一逻辑数据
存在多份物理副本时才增加 factor。若映射尚未确定，采用面积更大的合法实现，或把该项
留在 omissions，不能选择性使用乐观 factor。

## Logic entry

logic proxy 统一计算为 `units × width × fanin`。CAM compare 通常令 `fanin=1`；mux 与
priority selector 使用真实输入数；adder/shifter/partial-product/iterative-engine 等离散
单元通常令 `width=fanin=1`，并在 name/source 说明数据宽度。不同 metric 不相加，也不
换算成 LUT、门数或面积。

## Workflow

```sh
make static-area-check
make static-area
```

第一条命令运行计算器单元测试并验证两个 manifest；第二条输出 Markdown 对照。release
sign-off 还应使用 `--require-complete`，并把 candidate revision 固定为 release commit。
报告显示 `READY` 只代表两侧清单声明完整，所有增长项仍需人工检查架构理由和对应缩减。
