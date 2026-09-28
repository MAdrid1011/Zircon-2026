# prepare_vivado_rtl.py

Generate synthesizable RTL for the existing Vivado 2025 project:

```sh
python3 scripts/eda/prepare_vivado_rtl.py
```

The default output is `build/vivado-2025-xc7a200t-bram/rtl`. The script enables the Xilinx RAM backend and native 32-bit addition for carry-chain inference, copies the three Verilog RAM templates, and checks that the emitted wrappers instantiate those templates rather than the BSG backend. Use `--output DIR` for another Vivado RTL directory. Nangate45 uses its separate `synthesize_core.py --bsg` elaboration path and the parallel 32-bit adder.
