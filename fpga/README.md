# FPGA Evidence

This directory holds reproducible physical-implementation evidence for the
sole Zircon release target, `xc7a200tfbg676-2L`.

## Files

- `src/ZirconBoard.sv` is the board wrapper. It connects production
  `ZirconPlatformCore` to a legal single-slave AXI4 BRAM endpoint so that a
  complete core, rather than an empty top, is implemented. The BRAM is for
  bring-up and does not represent external DDR.
- `constraints/zircon_board.xdc` contains only the recovered LA32R `clk`,
  `rstn`, and LED pin assignments. `clk` is constrained to 10.000 ns.
- `vivado.tcl` is the fixed-part synthesis, place, route, report, checkpoint,
  and bitstream flow. It always uses `xc7a200tfbg676-2L`.
- `timing-evidence.template.json` is the required metadata shape for one
  post-route implementation run. Its `unverified` status is intentionally
  rejected by the release checker.

Run the complete local implementation flow with:

```bash
make fpga-impl
```

The default script invokes `/opt/Xilinx/Vivado/2023.1/bin/vivado`, the version
recorded by the recovered LA32R implementation. It checks that this exact part
is installed before regenerating RTL; the local 2025.2 installation contains
only UltraScale/UltraScale+ device packages and must not be used as a fallback.
Set `VIVADO_BIN` only when another installation contains the same target part.
The flow regenerates
the production no-observation platform RTL, then writes implementation outputs
under `fpga/runs/<git-revision>/`, which is intentionally ignored. Copy only
measured reports selected for release evidence beneath `fpga/reports/`.

For bounded synthesis experiments, `FPGA_SYNTH_ONLY=1` exits after synthesis
and `FPGA_SYNTH_DIRECTIVE=AreaOptimized_medium` selects an alternate Vivado
directive. The default remains `AreaOptimized_high`; changing the directive
does not create release timing or utilization evidence.

The recovered LA32R source tree contains no board-level DDR wiring or physical
external coherence initiator. Consequently this wrapper keeps the production
coherence adapter instantiated but idle. It is valid physical timing evidence
for the full Zircon core and local AXI endpoint, but it does not close the
separate external-master integration requirement.

## Validation

The local AXI/BRAM wrapper regression runs quickly in XSIM and exercises the
XPM block-memory implementation used by the fixed target:

```bash
make test-fpga-bram
```

Copy the template, populate it from an actual Vivado post-route run, and run:

```bash
python3 scripts/verify_fpga_timing.py --evidence fpga/reports/<run>.json
```

The command checks the frozen part, 10.000 ns clock, complete provenance, and
non-negative setup WNS. It validates metadata only; it does not claim that
Vivado has run or that a board wrapper is present.
