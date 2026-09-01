# M3 L2 demand AXI read engine

`AXIDataReadEngine` is the executable transport for L2 demand misses. It owns
the four physical demand slots and AXI IDs 1-4; it is distinct from L1D's
local line reservations and from the exclusive L2 line-transfer store.

## Interface and ownership

| Interface | Direction | Rule |
| --- | --- | --- |
| `request` | client to L2 demand engine | `L2DemandRequest { client, clientMshr, lineAddress }`; accepted only with a free physical L2 owner and no held AR request. |
| `ar` / `r` | L2 demand engine to AXI | One 8-beat 32-bit INCR burst uses the allocated slot plus one as ID. The slot is live from AR handshake through final R handshake. |
| `response` | L2 demand engine to client | One complete line retains `client`, `clientMshr`, all data words, and sticky RRESP fault state until accepted. |

The frozen geometry is four L2 demand MSHRs and 32-byte lines. A request must
be line aligned and its eight-beat burst must fit within one 4 KiB page. The
engine holds an AR payload stable under backpressure. Individual read IDs may
interleave, but each owner expects exactly eight ordered beats and `RLAST` only
on beat seven. Unknown ID, response after owner release, duplicate/extra beat,
wrong `RLAST`, or out-of-range request is an assertion.

An L2 hit bypasses this engine: `ExclusiveL2TransferStore` transfers the only
D copy to L1D. On an L2 miss, L1D sends a `Data` client request and later
matches the response with `clientMshr`. `L1InstructionCache` is an active
`Instruction` client with local token zero; it shares these four physical
owners fairly with L1D and receives only the complete response for its retained
owner. Before consuming an AXI owner, L1I probes an existing resident L2 line
through a non-destructive read-only port. The executable slice does not yet
allocate AXI-refilled instruction lines into L2 or implement final dynamic I/D
allocation.

## Recovery and verification

Once AR fires, the owner cannot be reclaimed by squash or flush. L1D may remove
killed waiters, but the L2 engine drains all beats and preserves the original
client metadata until its response transfer. New tests cover client mapping,
four live physical slots, interleaved bursts, AR backpressure, RRESP, and the
last legal 4 KiB line. Top-level integration continues to demultiplex IDs 1-4
only to this engine.
