# 来源与许可

本目录不复制香山、Chipyard、BOOM 或 EEMBC CoreMark 的上游仓库。
`scripts/fetch.sh` 按锁定版本将外部源码下载至忽略的 `work/`，其原始许可证仍以各上游
仓库为准。

| 组件 | 来源 | 版本依据 | 许可依据 |
| --- | --- | --- | --- |
| Zircon CoreMark | `RV-Software` 子模块 | 主仓库子模块指针 | `RV-Software/coremark/LICENSE.md` |
| 香山雁栖湖 | `OpenXiangShan/XiangShan` | `configs/revisions.lock` | 上游 `LICENSE` |
| BOOM / Chipyard | `ucb-bar/chipyard` 与 `riscv-boom` | `configs/revisions.lock` | 上游 `LICENSE*` |
| Mill 启动器 | `com-lihaoyi/mill` | 固定下载地址与 SHA-256 | 上游 Apache-2.0 许可 |

本地 RV64 文件只包含启动、链接布局、控制台与退出通道、周期测量等平台适配代码。
CoreMark 的五个算法文件直接来自锁定的 `RV-Software` 子模块，不在此重复保存。
