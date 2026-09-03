# FPGA board integration

## Scope

`ZirconBoard` is the physical implementation top for the sole release target
`xc7a200tfbg676-2L`. It binds the production, no-observation
`ZirconPlatformCore` to the recovered LA32R clock/reset/LED pins and one local
AXI4 BRAM slave. It is a board bring-up and post-route timing boundary, not a
replacement for the required platform external master.

The recovered LA32R source gives these only verified board facts:

| Port | Board fact |
| --- | --- |
| `clk` | AC19, LVCMOS33, 10.000 ns period |
| `rstn` | Y3, LVCMOS33, active low |
| `led[15:0]` | the sixteen documented LVCMOS33 LED pins |

Its `main_memory` IP is an on-chip AXI4 block-memory slave. No recovered
source identifies a DDR controller, its pins, or an external AXI/coherence
initiator. The Zircon wrapper must not invent any of those mappings.

## Interface and operation

`ZirconBoard` has exactly `clk`, `rstn`, and `led[15:0]` at the physical top.
It directly clocks `ZirconPlatformCore`; reset is active high internally as
`~rstn`. Interrupt inputs are tied inactive during bare-board bring-up.

`ZirconAxiBram` is a 64 Kiword, 32-bit AXI4 memory slave. It accepts one read
burst at a time, returns all beats in order with the retained ID, and accepts
one write burst at a time. This serialization is legal AXI4 backpressure: the
core can retain its four read owners while the slave advertises `ARREADY` only
when its response path is free. Reads use the low 18 address bits, so the
architectural reset vector `0x80000000` aliases to the first local word. That
is deliberate for bare-board program images and is not a general physical
address-map claim.

The optional `MEM_INIT_FILE` parameter is the only supported bring-up image
mechanism. A build must record the image digest alongside any board execution
or timing evidence.

## Coherence boundary

The production `ExternalCoherenceAdapter` remains instantiated in the emitted
platform RTL. In this first board wrapper its modifier input is idle, because
the recovered board sources contain no physically evidenced initiator. An idle
input is not evidence that external writes or atomics are integrated. A later
platform master must drive the adapter's request/acknowledgement handshake and
must not issue its cacheable modifier before `authorized` fires.

## Invariants

- The sole clock constraint is `clk` at 10.000 ns. No result for another part,
  clock, or top module is FPGA release evidence.
- `ARVALID`, `AWVALID`, `WVALID`, `RVALID`, and `BVALID` are held until their
  matching handshakes; a burst response retains its AXI ID until `RLAST`.
- The BRAM never generates an error response. AXI error-path evidence remains
  in the deterministic simulation regression, which has controllable fault
  injection.
- A local BRAM image or an idle coherence input may not be described as DDR,
  a board external master, or cache-coherence completion.

## Verification mapping

`make fpga-impl` first regenerates `ZirconPlatformCore`, then runs synthesis,
place, route, physical optimization, DRC, timing, utilization, checkpoint, and
bitstream generation with `fpga/vivado.tcl`. The output run directory is
ignored. Release evidence is copied deliberately into `fpga/reports/` and
validated with `make verify-fpga-timing FPGA_TIMING_EVIDENCE=...` only after a
measured post-route result exists.

The normal protocol and ISA evidence remains in `CoreShellSpec`, ZirconSim,
and the explicit-seed M3 targets. This wrapper adds physical implementation
coverage; it does not reduce those simulation or differential obligations.
