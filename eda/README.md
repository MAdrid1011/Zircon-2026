# Static evaluation platform

Zircon-2026 keeps a pinned Nangate45 reference platform under
[`platforms/nangate45/`](platforms/nangate45/). It provides the standard-cell
Liberty and LEF data, RAM models, and RC setup used for reproducible synthesis
and placement-based timing repair. Generated RTL, synthesized netlists, logs,
and reports belong in the ignored `generated/`, `build/`, and `reports/`
directories.

The [Nangate45 platform guide](platforms/nangate45/README.md) owns the process
and physical-input contract. The [SRAM model guide](platforms/nangate45/memory/README.md)
describes the active BSG bindings and links separately retained compatibility
assets.

The platform metadata records the upstream revision, checksums, process corner,
voltage, temperature, default output load, physical collateral, and reference
tool identity. The OpenROAD resizer runs in a digest-pinned ORFS container by
default; a native executable can be selected with `OPENROAD_BIN`. Placement RC
is estimated and does not include CTS or detailed-route extraction.

The public `scripts/eda/` directory contains only reusable support code:

| Tool | Purpose |
| --- | --- |
| `adder_mapping.py` | Shared BLevel adder mapping rules |
| `nangate_memories.py` | Nangate45 SRAM model selection and binding |
| `check_openram_1rw1r.py` | 1RW+1R OpenRAM model consistency checks |
| `gate_equivalence.py` | Gate-level equivalence support |
| `standard_cell_buffers.py` | Standard-cell input and output buffer helpers |
| `openroad_resizer.py` | Run the pinned OpenROAD physical resizer |
| `timing_reports.py` | Gate electrical checks and summarize timing reports |
| `test_timing_flow.py` | Unit tests for the synthesis and timing flow |

Module-specific experiments and historical implementation comparisons are not
part of the release source tree.
