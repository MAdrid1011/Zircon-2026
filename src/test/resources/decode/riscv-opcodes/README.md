# Decoder encoding reference

These files are unmodified copies from [riscv/riscv-opcodes](https://github.com/riscv/riscv-opcodes/tree/f5befa291a2562f3194921265b7f5ac5681bc8b0), revision recorded in `REVISION`, retrieved on 2026-09-13. The upstream BSD license is included as `LICENSE`.

`DecoderReference.scala` parses fixed bit assignments independently of the production `Instructions` and `DecodeTable`. Ordinary instruction rows are included; pseudo-operations are excluded except the three RV32 shift-immediate definitions in `rv32_i`. Their aliases ending in `_rv32` are excluded to avoid duplicates. Source paths were `extensions/<filename>`.

Control expectations and immediate evaluation are defined independently in the test source. Reserved static rounding modes 5 and 6 are rejected by Zircon policy; the raw encoding files leave `rm` variable. These references describe encodings, not completion of execution, CSR, memory ordering, or trap support.
