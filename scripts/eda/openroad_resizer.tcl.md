# openroad_resizer.tcl

OpenROAD Tcl flow for Nangate45 floorplanning, global placement, and standard-cell electrical/timing repair.

## External Interface

The script is invoked by `openroad_resizer.py`. It reads the standard-cell and macro LEFs, Liberty files, RC settings, mapped Verilog, and generated SDC from the per-target build directory. It writes a repaired netlist and max-capacitance/max-transition reports.

The current flow uses the 1.0 ns clock period supplied by the runner. It does not set false paths, multicycle paths, or relaxed IO delays. Critical path inventories are emitted only after both electrical reports are clean.

## Internal Helpers

The script initializes placement, runs global placement and standard OpenROAD repair commands, estimates placement parasitics, checks electrical violations, and writes the repaired design. Setup repair accepts a positive `max_repairs_per_pass` value from the runner; its formal default is one until a full-result A/B justifies changing it.
