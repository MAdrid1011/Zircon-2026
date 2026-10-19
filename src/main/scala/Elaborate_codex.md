# Core elaboration entry

`Elaborate` emits `ZirconCore` SystemVerilog for the static profiles in
[Core](../../../docs/Core.md) and [PerformanceCore](../../../docs/PerformanceCore.md).
Options choose simulation observability, BSG versus Vivado RAM interfaces, the
system/performance profile and the optional PE scratchpad. Synthesis omits
simulation debug/performance ports and lowers local variables for Yosys.

`--l2-sets=N` explicitly chooses system L2 sets, validated by `L2CacheParams`;
performance geometry remains fixed by its profile and rejects this override.
With no override, legacy BSG system elaboration keeps 16 sets (4 KiB), while
ordinary system elaboration keeps 32 (8 KiB). TSMC28 evaluation must pass 32 to
match the benchmark/Dandelion host. This option changes only explicit
elaboration configuration; default hardware and pipeline semantics are unchanged.
