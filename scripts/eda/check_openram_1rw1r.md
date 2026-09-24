# check_openram_1rw1r.py

Checks the pinned OpenRAM 1RW+1R models with behavioral simulation, Yosys mapping, and OpenSTA timing probes.

## External Interface

Run with `python3 scripts/eda/check_openram_1rw1r.py`. The script verifies input hashes, simulates read/write/mask behavior, maps both macros, checks cell counts, and validates the falling-edge read timing arcs. Results and logs are written under `build/openram-1rw1r-probe/`.

## Internal Helpers

`run()` captures tool output; `liberty()` maps macro bases to timing libraries; generated Verilog testbench, Yosys, and OpenSTA scripts isolate the macro interface from the full DCache.
