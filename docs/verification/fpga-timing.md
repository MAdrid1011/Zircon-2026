# FPGA Timing and Simulation Throughput Gate

This is a release requirement added after the original static-area decision. It
does not change ADR-0009: static structural accounting remains the area gate.
It adds a separate physical implementation and simulation-throughput gate.

## FPGA acceptance

The supported FPGA target is `xc7a200tfbg676-2L`. Its board
wrapper must constrain the core clock with `create_clock -period 10.000` and
must use the same default Zircon configuration as the release RTL.

A release candidate passes only with a reproducible post-route timing summary
that records Vivado version, RTL/submodule SHA, XDC SHA, top module, device,
clock name, requested 10.000 ns period, WNS, TNS, worst hold slack, utilization,
and the command line. Setup WNS must be non-negative at this 100 MHz point;
any negative slack fails the gate. A higher achieved frequency is welcome, but
never substitutes for the 100 MHz timing check.

The repository now has `ZirconBoard`, its board-specific XDC, and a fixed-part
Vivado flow. FPGA timing support remains **failed/unverified** because no
post-route report exists yet. A local run at revision `ddaa7f99f91ee8c57f0aa36571bc39f0759f497c`
successfully elaborated and synthesized the complete production core, but the
pre-placement DRC rejected the synthesized resource map: 318,188 Slice LUTs
(236.40% of 134,600), 131,264 distributed-RAM LUTs, 110,378 F7 muxes, and zero
Block RAM tiles. The run therefore produced synthesis checkpoints only and did
not produce a timing pass or bitstream. This is a measured failure baseline,
not release evidence; it blocks the final `v1.0.0` release until BRAM/resource
mapping is corrected and post-route timing is committed.

On 2026-09-03, a read-only local workspace audit found the LA32R Vivado project
at `/home/madrid/LA32R/LA32R.xpr`, whose project metadata targets
`xc7a200tfbg676-2L`. Its retained generated clock-IP constraint applies a
10.000 ns clock to `clk_in1`; the project's original board-level `soc.xdc`
path is no longer present locally. Other retained generated constraints only
record the LA32R top-level pins. These files are reference evidence for the
device and constraint form only. They are not a Zircon wrapper, XDC, timing
report, or 100 MHz pass result.
The nearby `SCARF/Zircon-SCARF/build/fpga_2025_vu13p/stage_b_bd/project/scarf_stage_b_bd.xpr`
instead targets `xcvu13p-fhgb2104-2-i`; its VU13P/DDR4 constraints likewise
must not enter this project.

## Timing triage

Timing fixes must preserve the frozen ISA, exact exception/interrupt behavior,
dual-LSU roles, cache scope, miniTAGE, FPU, and verification contract.

| Timing report signature | Required first response |
| --- | --- |
| Net/routing delay dominates | Reduce structural pressure and fanout: share comparators/arbiters, avoid replicated wide state, keep ledger entries current, and pipeline protocol boundaries where the ready/valid contract permits. |
| Logic delay dominates | Shorten combinational cones: stage wide selects/age arbitration, balance or pipeline mux trees, and retain exact tags/data across the new registered boundary. |
| A new path regresses WNS | Add the endpoint and its delay class to the timing report, then fix or revert before declaring the feature complete. |

No optimization may delete an ISA operation, turn an endpoint into a fabricated
completion, weaken a fault path, or hide a structure from the static ledger.

## Simulation throughput

Each component regression should finish within five minutes on the reference
development host. `make test-m3-store` is the focused cacheable-store tier: it
runs independent AXI/SQ/cache suites followed by the normal and BRESP-error
top-level tests. `make test-m3-load-boundary` is the equivalent M0/M1 load tier:
it runs LSU/LQ/L1D ownership suites and the three DeviceStrong, DeviceBurstable,
and LR.W no-L1D/no-data-AXI/no-false-retirement checks. New feature work should
add an equivalent focused target instead of requiring the complete test corpus
for every edit.

`make test-m3-ordered-io` exercises the standalone 1--4 beat ordered-device
AXI owner and combiner in seconds. Its eventual LSQ/ROB integration needs a
separate focused top-level target because it carries exact retirement behavior.

`make test-m3-axi-stress` is the focused all-channel tier. It runs the
explicit-seed M3 top-level device-write success path plus data `RRESP` and
device `BRESP` fault paths while independently backpressuring AR, R, AW, W,
and B. The test harness saves the seed, all five channel schedules, and retire
trace under `target/zircon-failures` when a case fails.

`make test-m3-ordering` keeps its component suites in two invocations: the
queue/L2/FENCE group and `L1DLoadCacheSpec` are separate so each remains below
five minutes, followed by the four short complete-core FENCE/aq/rl cases.

`make test-m3-external-coherence` is the focused sideband tier. It runs the
retained core controller and platform gate, then clean/dirty/retry, in-flight
I/D refill, and reset-epoch complete-core cases. On 2026-09-03 it completed
nine component tests plus fourteen core cases in about 274 seconds. The target
uses two SBT invocations rather than four by selecting all sideband cases in one
CoreShell run. This remains within the five-minute component simulation budget.

ZirconSim full-core measurements must record wall time, retired instructions,
retired instructions per second, ELF hash, seed, RTL/submodule/tool SHA, and
whether tracing was enabled. The normal long run must use an optimized
non-waveform build; traces/waveforms are enabled only for a requested artifact
or a captured failure. Differential correctness still consumes the complete
retire trace, so throughput optimization must not remove trace records or relax
AXI backpressure/error behavior.
