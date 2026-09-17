# Compact ITTAGE Experiment Summary

## Outcome

The compact ITTAGE line is a go. CoreMark seed 1 passes Spike differential testing, ordinary indirect accuracy rises from 40.571% to 99.129%, and execution falls from 371,523 to 325,946 cycles. The conditional accuracy change is -0.154 percentage points, inside Issue #53's 0.2-point gate, while calls and returns are unchanged.

## Mechanism

Four 128-row tagged target tables use folded global histories of 4, 8, 16, and 32 events. A provider with nonzero confidence overrides the Main BTB only for `FrontendCfi.Indirect`; a weak provider uses its alternate or the Main BTB. Returns keep RAS priority, and commit training uses indices/tags captured at prediction time through the FTQ.

Each predictor row is 44 bits: 9-bit tag, 2-bit slot, 30-bit target, 2-bit confidence, and 1 useful bit. Table data consumes 22,528 bits and valid vectors consume 512 bits, for 23,040 bits (2.8125 KiB) total predictor storage. Each table has two combinational reads in the current Chisel implementation (ahead lookup and commit training) and at most one write; this favors register/LUT implementation rather than a single-port SRAM macro.

The predictor adds no frontend stage. Tag/provider selection is in IF1 from registered ahead rows; IF2 adds the confidence/alternate/base target mux before the existing main selector. Prediction metadata retained per block is 473 bits before normal bundle replication through frontend queues. Synthesis and timing are therefore the main remaining caveat.

## Verification

The deterministic unit regression proves one static indirect branch can retain distinct targets for distinct histories. Five affected frontend suites pass 11 tests. The full `sbt test` run completed 204 tests: 142 passed and 62 failed in pre-existing backend, cache, decoder, and test-fixture areas outside this change. No unrelated failures were modified.

## Decision

Keep compact ITTAGE as the Issue #53 implementation. The next justified step is synthesis/timing and a broader workload set; BLBP or a larger/path-history ITTAGE should be considered only if those workloads expose residual multi-target misses that justify their cost.
