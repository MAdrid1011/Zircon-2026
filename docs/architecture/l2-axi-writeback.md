# M3 L2 dirty-victim AXI writeback

`AXIL2WritebackEngine` is the executable M3 owner for a dirty D-side L2
replacement under ADR-0012 and Issue #47. It receives the sole copy from
`ExclusiveL2TransferStore`'s two-entry victim FIFO and uses AXI ID 5. It is a
writeback transport only: it neither allocates L2 MSHRs nor supplies I-side or
D-side demand data.

## Ownership and protocol

The engine accepts only a dirty, 32-byte-aligned `CacheLineTransfer`. After its
input handshake, it retains the complete `{line address, eight 32-bit words,
dirty}` record. The FIFO may reuse that slot, but the retained engine record is
now the sole dirty owner. The record survives squash, trap, and interrupt.

It emits exactly one aligned INCR burst per attempt:

| AXI field | Value |
| --- | --- |
| `AWID` | 5 |
| `AWADDR` | retained line address |
| `AWLEN` / `AWSIZE` | 7 / 2 (eight 32-bit beats) |
| `AWBURST` | INCR |
| `WSTRB` | `1111` on every beat |
| `WLAST` | only on beat 7 |

AW and W have independent valid/ready state inside the owner. `ZirconCore`
adds the required AXI4 cross-owner sequencing: after any AW handshake it locks
the global W channel to that owner until its `WLAST` handshake. This prevents
ID-5, ID-6, and ID-7 write data from interleaving without WID. B responses are
demultiplexed independently by ID.

An OKAY or EXOKAY B response releases the retained line. Any other `BRESP`
does not discard it: the owner resets only its AW/W progress and retries the
same retained burst. `retryObserved` records that this recovery path was used.
Unexpected B ID, premature B, non-dirty input, non-aligned line, oversized beat
count, and a 4 KiB-crossing burst are assertions.

## Integration Limits

The owner makes evicted dirty lines externally visible, including a line whose
first word is a `tohost` value after it has reached L2 replacement. Trace-only
`HostStoreFlush` can request an exact L1D-to-L2 transfer and then an exact L2
victim, but this is not a general `tohost` region, cache flush, or `FENCE`
semantic. `AXIDataReadEngine` separately owns
the four L2 demand-read MSHRs; active I-side allocation and external coherent
atomics remain later M3 work.

## Verification Mapping

`AXIL2WritebackEngineSpec` covers AW backpressure, retained eight-beat payload,
the 4 KiB-edge legal line, and retry after a failing B response. `CoreShellSpec`
executes seven cacheable stores mapping to one L1D/L2 set, forces a dirty L2
replacement, and observes one ID-5 burst with the merged dirty first word and
the seven retained refill words. `AtomicMemoryEngineSpec` additionally verifies
that ID-7 works when the shared scheduler supplies AW before W.
