# Nangate45 存储器绑定接口

`generate(rtl, output)` 扫描生成的逻辑 SRAM 模块，依据深度、宽度和面积选择 BSG Fakeram
估算宏，并返回包含宏选择、位宽补齐、面积和库路径的绑定清单。

输出目录中的 `wrappers.sv` 描述宏拼接与掩码连线，`blackboxes.sv` 声明综合端口，
`models.sv` 提供接口级功能模型。绑定按逻辑 bit lane 展开写掩码，并计入实际物理容量。
