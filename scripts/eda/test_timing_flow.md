# test_timing_flow.py

Unit tests for the Nangate45 synthesis, physical repair, and timing-report contract.

## Test Cases

- Verify BLevel discovery, hierarchy preservation, ABC delay/constraint arguments, and the standard direct-mapping sequence.
- Verify mapping-period conversion and reject invalid targets.
- Verify a separate logic-only STA period requires `--logic-only` and a positive value.
- Verify generated ABC commands include BLevel direct mapping, the requested `-D` target, `-constr`, and the standard buffer/upsize/downsize sequence.
- Verify the OpenROAD command script uses ordinary placement repair and contains no false-path, multicycle, or relaxed-clock exceptions.
- Verify report parsing counts max-cap/max-transition violations and preserves worst-path-per-endpoint inventory rows.
- Verify a rounded-zero summary row remains in the logic-only inventory when its endpoint is marked `VIOLATED`.
- Verify the full-path parser excludes boundary paths reported as `MET`.
- Verify RAM reports retain falling-edge launch information.
- Verify the electrical gate rejects path interpretation until both violation counts are zero.
