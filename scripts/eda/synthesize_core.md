# synthesize_core.py

Elaborates the BSG SRAM ZirconCore configuration and runs constrained Nangate45 mapping and timing repair.

## External Interface

```sh
python3 scripts/eda/synthesize_core.py [--sweep | --target-ns NS] [--output DIR] [--yosys PATH] [--skip-elaboration] [--logic-only] [--placement-density DENSITY] [--max-repairs-per-pass COUNT]
```

`--sweep` currently evaluates only the 1.0 ns target. Lower-period experiments are out of scope.

`--target-ns` selects one target and defaults to 1.0 ns. `--sweep` runs the configured target list, but the current timing objective is a single 1.0 ns run. Each target writes mapped netlists, logs, timing reports, and `results.json` under `target-<period>ns/`; `sweep-results.json` collects the targets. `--skip-elaboration` requires generated RTL at the output root. `--logic-only` still performs complete Yosys synthesis and Nangate45 mapping, then runs zero-interconnect STA and stops before placement and physical repair. Without it, the flow also writes the repaired netlist and physical reports. `--placement-density` selects a reproducible placement density. `--max-repairs-per-pass` defaults to one and exposes OpenROAD's standard setup-repair batching knob for formal A/B runs. The runner prefers the Yosys 0.68 installation extracted from the pinned ORFS image, then falls back to the legacy local installation or `PATH`. OpenROAD uses `-threads max` by default; `OPENROAD_THREADS` can set a positive count. A nonzero max-capacitance or max-slew count is retained as a failed electrical gate and suppresses WNS/TNS and path interpretation.

## Internal Helpers

`run()` captures subprocess output and elapsed time. `build_yosys_script()` preserves and directly maps BLevel adders before flattening and mapping the rest with the target ABC delay. Platform input hashes are verified before the flow. The flow checks that all BSG Fakeram instances survive mapping and that no internal unmapped cells remain. `openroad_resizer.py` can also run independently on a saved mapped netlist so physical settings can be evaluated without repeating elaboration and technology mapping.
