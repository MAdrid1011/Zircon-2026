# 加法器映射接口

`AdderMapping.discover(files, mode='direct', delay_ps=1000)` 从生成的 SystemVerilog 中识别
`BLevelPAdder` 模块。`preserve()`、`flatten()` 与 `commands()` 为 Yosys/ABC 生成层次保留、展开
和映射命令；`manifest()` 返回结果中使用的映射参数。

支持 `direct`、`isolated` 和 `flat` 三种模式。`delay_ps` 以皮秒为单位，指定时必须为正数。
默认直接映射模式先处理加法器，再映射整核其余逻辑。`quote()` 用于转义 Yosys 命令中的路径。
