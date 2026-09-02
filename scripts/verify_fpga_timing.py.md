# verify_fpga_timing.py

Validates one committed Zircon FPGA post-route evidence record. It is a
metadata gate, not a Vivado driver and not a timing report generator.

## External Interface

```bash
python3 scripts/verify_fpga_timing.py --evidence <path>
```

The evidence file must be JSON matching `fpga/timing-evidence.template.json`.
Success requires `status: "measured"`, target part `xc7a200tfbg676-2L`, clock
`clk` at 10.000 ns, non-negative setup WNS, numeric timing/utilization fields,
complete source provenance, report/XDC paths, and SHA-256 digests.

Exit status is zero when all checks pass. A malformed file or an unmet release
gate returns nonzero and lists each violated field on stderr.

## Internal Helpers

`validate_evidence` performs deterministic schema and release-gate checks and
returns a list of human-readable violations. `main` loads JSON, formats those
violations, and supplies the command-line exit status.
