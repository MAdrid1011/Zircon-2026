# nangate_memories.py

Generates wrappers and black-box declarations that bind logical single-port masked RAM modules to Nangate45 estimate macros.

## External Interface

`generate(rtl, output)` scans generated RTL modules, selects macros by logical depth/width and area, then writes `wrappers.sv`, `blackboxes.sv`, `models.sv`, and a binding manifest. It returns the manifest containing selected macros, padding, area, and library paths.

## Internal Helpers

The selector partitions logical bit lanes across physical macros, expands masks, and emits functional models for RTL-level interface checking. These models are estimates, not transistor-level SRAM implementations.
