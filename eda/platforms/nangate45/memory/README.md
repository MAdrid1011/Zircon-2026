# Nangate45 存储估算模型

本目录保存 OpenROAD-flow-scripts 固定提交 `31e744df6a12ae09760820dc977d2b2488c9b0fe` 的 `fakeram45` Liberty 文件。逐文件来源、几何尺寸和 SHA-256 见 [manifest.json](./manifest.json)。这些文件用于架构面积与时序估算，不是经过制造验证的 SRAM 宏；同一平台的来源及许可文件位于上一级目录。

`scripts/eda/nangate_memories.py` 将 `SinglePortMaskedRam_<depth>_<lanes>_<laneBits>` 模块绑定到单端口宏。对深度足够的候选按面积选择宽度拼接，实际物理深度和补齐位数全部计入容量和面积，不缩减表容量。每个逻辑写掩码展开为对应 lane 的位掩码；未使用的地址高位、数据位和写掩码置零。

生成的 `wrappers.sv` 保存实际拼接连线，`blackboxes.sv` 为综合声明宏端口，`models.sv` 是项目接口的功能模型。模型采用有效时钟边沿读取、位掩码写入，写入或关闭使能后主动将输出设为未知，验证消费者的响应保持逻辑。这些功能模型用于检查逻辑接口及拼接，不代表估算宏存在对应的晶体管级实现。

FPGA 路径由 `SinglePortMaskedRam.scala` 按 lane 例化 `Utils/Xilinx/XilinxSinglePortRamReadFirst.scala`，沿用 Zircon-2024 的 `ram_style="block"`、寄存地址、数组输出和配置初始化写法，不使用上述估算模型。ASIC 绑定整体替换外层封装，Xilinx 子模块不被例化；ASIC 数组初始内容仍未定义，BTB 依靠独立复位的有效位屏蔽未训练数据。

[openram-1rw1r/](openram-1rw1r/README.md) 保存 FreePDK45 1RW+1R 宏，包括 16×25 的整字写模型和 16×32 的字节掩码模型。它们已接入 DCache 的 `openram` 后端，通过功能仿真、宏链接及 OpenSTA 检查，使用解析时延和功耗模型；不由现有单端口绑定脚本选取。完整面积、功耗与精度边界见 [DCache OpenRAM 评估](../../../../docs/DCache-OpenRAM-Evaluation.md)。
