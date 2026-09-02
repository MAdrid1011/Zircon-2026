# FPGA Evidence

This directory holds reproducible physical-implementation evidence for the
sole Zircon release target, `xc7a200tfbg676-2L`. It does not contain a board
wrapper, XDC, or passing timing report yet.

## Files

- `timing-evidence.template.json` is the required metadata shape for one
  post-route implementation run. Its `unverified` status is intentionally
  rejected by the release checker.

When a Zircon-specific wrapper and XDC exist, commit the measured evidence and
its referenced reports beneath `fpga/reports/`. Do not use an XDC or report for
another top module or device as a substitute.

## Validation

Copy the template, populate it from an actual Vivado post-route run, and run:

```bash
python3 scripts/verify_fpga_timing.py --evidence fpga/reports/<run>.json
```

The command checks the frozen part, 10.000 ns clock, complete provenance, and
non-negative setup WNS. It validates metadata only; it does not claim that
Vivado has run or that a board wrapper is present.
