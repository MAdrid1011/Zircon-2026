# Run Log Summary

- Focused predictor regression: passed.
- Affected frontend surface: 11 tests across 5 suites passed.
- CoreMark simulator build: passed.
- CoreMark seed 1 with Spike differential testing: passed, 325,946 cycles and 374,266 instructions.
- Bounded VCD build and 2,000-cycle capture: passed.
- Hotspot analysis: 93/94 correct at `0x80000820`; provider tables 1 and 2 both active.
- Full repository test run: completed in 15 minutes; 142 passed, 62 unrelated failures.
- Formatting check: `git diff --check` passed before packaging.

The full-test failures include uninitialized backend test-fixture inputs, existing DCache forwarding assertions, decoder expectations, and independent queue/cache tests. None point into the changed frontend files or the new predictor regression.
