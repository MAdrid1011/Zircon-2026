# ADR-0036: Local age cutpoints for production selectors

状态：Accepted

## 背景

The fixed-device reports still showed the ROB head tag entering the IntIQ,
MemIQ, and L1D selector/write-enable cones after the narrow head-tag source
change. These cones contain multiple age comparisons and wide candidate muxes;
their route delay dominates the logic delay.

## 决策

Add an opt-in `registeredAgeHead` mode to the production IntIQ, MemIQ, LSQ
ingress, and L1D instances. The mode captures the already domain-scoped ROB
head tag at the consumer boundary and uses that local copy for age selection
and selective squash decisions. Standalone modules retain the original direct-input mode so
their same-cycle unit-test contract is unchanged.

The extra snapshot cycle is safe for the frozen modulo-24 live window: age
ordering is invariant while all retained tags remain in one bounded ROB
traversal, and generation-tag checks still reject stale completions. Flush and
commit authorization continue to use their live control inputs.

## 验证

Compile, platform RTL generation, and focused IntIQ/MemIQ/L1D tests must pass.
A fixed-device Vivado implementation on `xc7a200tfbg676-2L` is required before
claiming a timing improvement; synthesis-only utilization is not timing
evidence.
