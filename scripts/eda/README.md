# EDA Scripts

These scripts elaborate ZirconCore, bind Nangate45 RAM models, map standard
cells, run the OpenROAD resizer, and summarize timing reports. Generated netlists,
logs, and reports stay under ignored `build/` directories. Each Python entry
point has a companion Markdown interface document.

| File | Purpose |
| --- | --- |
| [`synthesize_core.py`](synthesize_core.md) | Elaborate, map, repair, and sweep timing targets |
| [`adder_mapping.py`](adder_mapping.md) | Configure direct BLevel mapping and ABC delay constraints |
| [`openroad_resizer.py`](openroad_resizer.md) | Invoke the pinned container or a native OpenROAD binary |
| [`openroad_resizer.tcl`](openroad_resizer.tcl.md) | Place and repair the mapped netlist |
| [`timing_reports.py`](timing_reports.md) | Enforce the electrical gate and summarize STA results |
| [`test_timing_flow.py`](test_timing_flow.md) | Unit tests for mapping and report handling |
| [`nangate_memories.py`](nangate_memories.md) | Bind logical single-port memories to estimate macros |
| [`check_openram_1rw1r.py`](check_openram_1rw1r.md) | Validate archived OpenRAM models |
| [`gate_equivalence.py`](gate_equivalence.md) | Prove mapped sequential and memory-boundary equivalence |
| [`standard_cell_buffers.py`](standard_cell_buffers.md) | Legacy function-preserving buffer-tree helper; not used for physical closure |
