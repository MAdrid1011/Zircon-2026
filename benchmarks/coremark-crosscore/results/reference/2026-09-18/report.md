# CoreMark 跨处理器结果

| 处理器 | CoreMark/MHz | 计时周期 | ISA / ABI | 相对 Zircon | 校验 |
| --- | ---: | ---: | --- | ---: | --- |
| Zircon | 3.799 | 未保留 | RV32IMAF / ILP32F | +0.0% | 用户确认的基准 |
| 香山雁栖湖 | 7.781 | 3,855,514 | RV64GC / LP64D | +104.8% | CRC 正确 |
| BOOM Small | 3.140 | 9,551,518 | RV64GC / LP64D | -17.3% | CRC 正确 |
| BOOM Medium | 5.322 | 5,636,123 | RV64GC / LP64D | +40.1% | CRC 正确 |

测量使用 30 轮、Clang `-O2`、仿真器随机种子 `1`，在 `iterate()` 前后读取 `mcycle`。RV64 目标共用同一工作负载构建；Zircon 使用对应的 RV32 构建。CoreMark/MHz 反映每周期性能，不衡量面积或能效。
