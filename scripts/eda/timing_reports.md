# timing_reports.py

Parses OpenROAD/OpenSTA outputs and enforces the electrical gate for interpreting timing.

## External Interface

The module accepts max-capacitance and max-transition reports, WNS/TNS output, endpoint-path reports, and RAM edge-path reports. It returns structured per-target metrics and a pass/fail electrical status. The command-line entry point consumes a target output directory and writes a JSON summary.

## Internal Helpers

Report readers count max-capacitance and max-slew violations, parse Resizer counts, WNS/TNS, and one worst setup path per violating endpoint, and preserve RAM launch/capture edge information. After the electrical gate passes, the parser also maps anonymous sequential-cell endpoints back to their `Q` register names in `ZirconCore-repaired.v` and records endpoint counts by top-level module. The original endpoint report remains the complete inventory. `summarize_target()` deliberately omits WNS/TNS and path inventories when either electrical count is nonzero.
