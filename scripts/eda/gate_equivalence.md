# gate_equivalence.py

Builds and proves a partitioned sequential equivalence miter between reference and mapped netlists. It first flattens project hierarchy while preserving standard-cell and RAM black boxes, then caches the flattened JSON by content hash.

## External Interface

`prove(reference, mapped, top, library, directory, run, yosys, partition_width=None, workers=4)` verifies top-level outputs, every named next-state bit, and every memory input while treating register states and both Fakeram and OpenRAM outputs as explicit cut points. Independent partitions use the requested worker count and stop dispatching after the first failed proof. It returns proof scope, partition count, and status.

## Internal Helpers

`boundaries()` discovers register and memory cut points. `make_cuts()` builds matching cut modules and checks state correspondence. `partition()` makes proof cones. `prove_cut()` creates a Yosys SAT miter and validates cached proof inputs by hash.
