# Zircon-2026 架构与验证文档

本目录描述当前 RTL 的模块边界、关键状态、流水线数据流和可复现验证。配置数值以
`src/main/scala/Config` 的默认参数为准；Nangate45 专用配置会在对应文档中明确标出差异。

## 推荐阅读顺序

1. 从仓库 [README](../README.md#架构概览) 了解整核目标、默认配置和主数据流。
2. 阅读 [处理器顶层](Core.md)，再按数据流进入 Frontend、Middleend、Backend 和 Commit。
3. 阅读 [存储系统](Memory.md) 了解 Cache、地址翻译、PMA 和 RAM 后端。
4. 使用 Linux 与 Nangate45 文档复现软件启动和静态时序评估。

## 模块设计

- [处理器顶层](Core.md)：整核连接、外部接口、恢复与维护控制
- [前端](Frontend.md)：取指流水、分支预测、ICache、ITLB 与 Fetch Queue
- [中端](Middleend.md)：Decode、Rename、ReadyBoard 与 Dispatch
- [后端](Backend.md)：Issue Queue、执行流水线、PRF、旁路与唤醒
- [提交与恢复](Commit.md)：ROB、FTQ、SQ、Store Buffer、CSR 与异常恢复
- [存储系统](Memory.md)：L1、L2、TLB、PTW、PMA 与 RAM 后端

## 复现与验证

- [Linux 启动与复现](Linux-Bringup.md)：软件镜像、PGO 仿真、Spike 差分、检查点与限制
- [Nangate45 逻辑时序评估](Nangate45Timing.md)：BSG SRAM 配置、logic-only STA、当前结果与边界

## 贡献规范

- [Git message tags](git-msg-tags.md)：提交消息使用的模块标签

文档中的类名、接口名和流水级名称与源码保持一致，便于从设计说明直接定位实现。Git 历史负责
保存演进过程；本目录只描述当前可用设计和验证入口。
