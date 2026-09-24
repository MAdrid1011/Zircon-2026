# standard_cell_buffers.py

Legacy helper that inserts function-preserving `BUF_X4` trees into a mapped JSON design to reduce estimated pin capacitance.

## External Interface

`repair(directory, top_name, output_load, run, yosys)` edits `full-mapped.json`, writes a buffered Verilog netlist, and returns inserted-buffer and affected-driver metadata.

## Internal Helpers

The helper estimates pin loads from the Nangate Liberty data, groups sinks under a fixed leaf-load budget, and verifies the modified JSON with Yosys. It is not the physical closure flow; `openroad_resizer.py` is the supported placement-aware resizer.
