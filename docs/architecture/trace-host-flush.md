# Trace host dirty-line flush

`HostFlushControl` is a trace-only simulation input used to make an ELF host
exit externally observable without treating a retire event as memory state. It
is absent when `enableTrace` is false and may only be elaborated with trace.

## Interface and state

The controller has no parameter beyond the frozen 32-byte cache line in
`ZirconCoreConfig`. It is instantiated only when both `enableTrace` and
`enableHostFlush` are true; the configuration rejects host flush without trace.

| Interface | Direction | Rule |
| --- | --- | --- |
| `input: StoreWriteResult` | L1D to controller | A matching nonfaulting cacheable result is consumed and retained locally. Nonmatching or faulting results bypass unchanged. |
| `output: StoreWriteResult` | controller to SQ/ROB | The retained result becomes valid only after a successful matching ID-5 completion. |
| `enabled/address` | trace host to controller | Selects one exact effective store address at run time. |
| `l1dFlush` | controller to L1D | Requests transfer of an exact resident, dirty, non-MSHR line into L2. |
| `l2Flush` | controller to L2 | Requests eviction of that exact dirty L2 line into the retained victim FIFO. |
| `writebackComplete` | ID-5 owner to controller | A one-cycle successful-B pulse carrying the written-back line address. |

When `enable` is high, `HostStoreFlush` compares the exact `StoreWriteResult`
address against `address`. A matching result is held in a controller register,
not left dependent on upstream `valid`, until this sequence finishes:

| State | Owner/action | Release condition |
| --- | --- | --- |
| L1D transfer | move the matching dirty L1D line to exclusive L2 | L1D-to-L2 handshake |
| L2 eviction | remove that exact dirty L2 line into victim FIFO | L2 flush handshake |
| AXI writeback | retained ID-5 eight-beat write and B response | matching OKAY/EXOKAY completion |
| result | original store result returns to SQ/ROB | downstream result handshake |

The controller never creates a new store result. A B error retries the retained
line in `AXIL2WritebackEngine`; squash, trap, or interrupt cannot cancel this
already commit-authorized store. An exact-line flush only accepts a resident,
non-MSHR dirty L1D line and the corresponding dirty L2 line, preserving the
rule that the D copy is owned by exactly one of L1D, L2, or a transfer buffer.

L1D gives a valid host flush exclusive use of its `l2Insert` boundary and
invalidates the selected L1D line only on that handshake. L2 gives its flush
exclusive use of the single array port, moves the selected dirty line into its
two-entry victim FIFO, and invalidates the L2 copy on the same handshake. A
clean, absent, or MSHR-owned line backpressures instead of manufacturing a
completion. The retained ID-5 owner keeps B-error retry private, so only
OKAY/EXOKAY can create `writebackComplete`.

## Invariants and verification

- The selected line address is always 32-byte aligned.
- The original store can retire exactly once and only after the matching
  successful external writeback.
- The bridge never changes production cache behavior, `FENCE`, PMA, or AXI
  ownership; it only serializes one trace-selected committed L1D result.
- There is no production performance counter or static-area entry: trace,
  `HostStoreFlush`, its control port, and its state are absent from the
  synthesized configuration.

`HostStoreFlushSpec` covers retained-result backpressure, wrong-line completion
rejection, and nonmatching/faulting bypass. `L1DLoadCacheSpec` and
`ExclusiveL2TransferStoreSpec` cover exact dirty-line transfer/eviction and
clean-line rejection. `AXIL2WritebackEngineSpec` proves failed B responses do
not emit completion. `CoreShellSpec` observes the full trace-selected store,
its exact ID-5 address/payload, and its retirement only after B acceptance.

This bridge does not change ordinary cacheable-store behavior, `FENCE`, PMA, or
production static area. It is verification infrastructure until final general
cache flush/order semantics replace the targeted control.
