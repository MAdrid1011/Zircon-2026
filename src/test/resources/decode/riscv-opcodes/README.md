# 译码编码参考

本目录的指令编码文件原样取自
[riscv/riscv-opcodes](https://github.com/riscv/riscv-opcodes/tree/f5befa291a2562f3194921265b7f5ac5681bc8b0)。
版本记录在 `REVISION`，上游 BSD 许可证保存在 `LICENSE`。

`DecoderReference.scala` 独立解析固定位域，用于核对处理器的 `Instructions` 与
`DecodeTable`。参考集合包含普通指令；伪指令仅保留 `rv32_i` 中三条 RV32 移位立即数
定义，并排除对应的 `_rv32` 别名，避免重复。

控制信号与立即数的预期值由测试源码独立定义。Zircon 策略拒绝静态舍入模式 5 和 6；
原始编码文件仍将 `rm` 作为变量。此处只说明指令编码，不代表执行、CSR、存储顺序或
异常路径的验证范围。
