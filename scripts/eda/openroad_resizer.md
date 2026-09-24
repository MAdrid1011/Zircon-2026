# openroad_resizer.py

Runs the Nangate45 placement-based OpenROAD physical resizer using a digest-pinned ORFS image or a native binary.

## External Interface

The module accepts a mapped netlist, target period, platform directory, Liberty files, and output directory. Set `OPENROAD_BIN` to select a native executable. Otherwise Docker runs `/OpenROAD-flow-scripts/tools/install/OpenROAD/bin/openroad` from the configured image digest. The default Tcl flow reads the Nangate45 and SRAM LEFs, creates the platform-density floorplan, places pins and SRAM macros, runs global placement with placement-based RC, then applies electrical repair, setup repair, and two final electrical-repair passes separated by parasitic re-estimation. Outputs include the repaired Verilog netlist, OpenROAD log, and electrical-check reports. The optional `--placement-density` argument supports reproducible physical-density A/B runs without changing the platform default.

The supported whole-core entry prepares the complete BSG Liberty/LEF set and
invokes the resizer automatically:

```sh
python3 scripts/eda/synthesize_core.py --target-ns 1.0
```

`openroad_resizer.py` remains available for controlled physical A/B runs that
reuse one mapped netlist. Such runs must pass the exact BSG Liberty files
recorded by the synthesis result and a separate output directory; run
`python3 scripts/eda/openroad_resizer.py --help` for its low-level interface.

`--slew-margin` changes only the resizer's conservative repair target; the final electrical checks still use the unchanged Liberty limits. `--max-repairs-per-pass` controls how many setup transforms OpenROAD may batch before recalculating timing. Its default is one, and a larger value must be accepted only after a full A/B run proves that WNS, TNS, endpoint coverage, and electrical checks do not regress. Timing reports are emitted only when both electrical checks are clean.

## Internal Helpers

The runner binds the workspace into the container, provides stable `/work` paths, verifies the required Nangate45 physical inputs, and fills the checked-in Tcl template with the exact target constraints. It uses OpenROAD's standard `-threads max` by default; `OPENROAD_THREADS` can select a positive thread count. The formal flow runs `repair_design`, setup `repair_timing`, and two final `repair_design -slew_margin 70` passes. Re-estimating parasitics between the final passes lets the second pass repair marginal violations exposed by the first pass's placement changes. It uses unchanged constraints and reports timing paths only when both electrical counts are zero.
