# FPGA Timing Evidence Tests

Tests `scripts/verify_fpga_timing.py` without Vivado.

## Cases

- A fully populated measured record for `xc7a200tfbg676-2L` and a 10.000 ns
  `clk` is accepted when setup WNS is non-negative.
- An unverified template is rejected rather than being treated as timing proof.
- A wrong FPGA part, negative WNS, or incomplete SHA/provenance field is
  rejected with an identifying diagnostic.
