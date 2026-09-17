# Static evaluation platform

Zircon-2026 keeps a pinned Nangate45 reference platform under
[`platforms/nangate45/`](platforms/nangate45/). It provides the standard-cell
Liberty data and RAM timing models used for reproducible static area and timing
analysis. Generated RTL, synthesized netlists, logs, and reports belong in the
ignored `generated/`, `build/`, and `reports/` directories.

The platform metadata records the upstream revision, checksums, process corner,
voltage, temperature, default output load, and reference tool versions. Nangate45
is an analysis library rather than a complete physical implementation platform;
reported timing excludes clock-tree and routed interconnect effects unless an
external flow adds them.

The public `scripts/eda/` directory contains only reusable support code:

| Tool | Purpose |
| --- | --- |
| `adder_mapping.py` | Shared BLevel adder mapping rules |
| `nangate_memories.py` | Nangate45 SRAM model selection and binding |
| `check_openram_1rw1r.py` | 1RW+1R OpenRAM model consistency checks |
| `gate_equivalence.py` | Gate-level equivalence support |
| `standard_cell_buffers.py` | Standard-cell input and output buffer helpers |

Module-specific experiments and historical implementation comparisons are not
part of the release source tree.
