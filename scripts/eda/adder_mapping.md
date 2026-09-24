# adder_mapping.py

Shared Yosys/ABC mapping policy for generated `BLevelPAdder` modules.

## External Interface

`AdderMapping.discover(files, mode='direct', delay_ps=1000)` discovers supported BLevel modules from generated SystemVerilog. `preserve()` and `flatten()` return Yosys hierarchy commands. `commands(library, constraint, directory, additional_modules=())` returns ABC commands, generating `adder.abc` for direct mapping. `manifest()` returns the selected mapping policy for result metadata.

The supported modes are `direct`, `isolated`, and `flat`. Delay is expressed in picoseconds and must be positive when specified.

## Internal Helpers

`quote()` escapes Yosys path arguments. Direct mode applies ABC `&nf`, `buffer`, `upsize`, `dnsize`, and `stime` to discovered adder modules before mapping the remaining logic.
