# ADR-0015: Trace-only host dirty-line flush

Status: Accepted

Related Issue: #47

## Context

M3 cacheable stores correctly retire after a commit-authorized L1D update, but
a resident dirty line need not immediately reach AXI. `ZirconSim` learns an
ELF's `tohost` address only at run time, so it cannot express that address as a
fixed hardware PMA region. Treating the retire event itself as a host-memory
write would make an ELF pass before backing memory observed the result.

## Decision

Trace elaboration may expose `HostFlushControl { enable, address }`. This input
does not exist in the production configuration and can only be enabled together
with trace. For a commit-authorized cacheable store whose effective address
matches the control address, `HostStoreFlush` retains the exact store result and
performs this ownership sequence:

1. transfer the dirty L1D line to the exclusive L2 store;
2. evict that exact dirty L2 line into the existing victim FIFO;
3. wait for `AXIL2WritebackEngine` to report a successful ID-5 B response for
   the same line;
4. release the original `StoreWriteResult` to the SQ/ROB.

The store's SQ effect therefore remains in flight through the actual external
write. A B error retries inside the retained ID-5 owner and cannot release the
store. The controller ignores recovery flush because its source store has
already been commit authorized.

This is a deterministic simulation host-exit bridge, not a new ISA operation,
PMA classification, or a replacement for the final `FENCE` cache/order rules.
It must not be instantiated, represented in static production area, or exposed
on trace-disabled top-level RTL.

## Consequences

- `L1DLoadCache` and `ExclusiveL2TransferStore` gain exact-line transfer/evict
  controls that preserve the existing exclusive ownership invariant.
- `AXIL2WritebackEngine` exposes only an accepted successful completion pulse;
  failed B responses remain private retries.
- Directed integration tests must show no retirement before the ID-5 response,
  exact line payload/address, and no control port in the production top.
- ZirconSim must later drive this control from ELF `tohost` and require its
  observed backing-memory write before reporting an ELF pass.
