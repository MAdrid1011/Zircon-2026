.PHONY: compile test test-m3-store test-m3-load-boundary test-m3-dual-load-merge test-m3-dual-resource test-m3-partial-store-forward test-m3-mshr-pressure test-m3-l2 test-m3-ordered-io test-m3-ordered-io-top test-m3-ordered-io-fetch-pressure test-m3-device-io test-m3-axi-stress test-m3-axi-wrong-path-drain test-m3-axi-reset test-m3-axi-long test-m3-axi-faults test-m3-fence-pressure test-m3-axi-mixed test-m3-atomic test-m3-atomic-axi test-m3-atomic-random test-m3-lrsc-random test-m3-lrsc-interrupt test-m3-lrsc-errors test-m3-sc-errors test-m3-lrsc-granularity test-m3-lrsc-replacement test-m3-atomic-errors test-m3-external-coherence test-m3-ordering test-m4-fp-move test-m4-interrupt-priority verilog platform-verilog platform-verilog-8k trace-verilog fpga-impl test-fpga-bram software sim-unit sim-smoke static-area \
	static-area-check verify-m0 test-fpga-timing-evidence verify-fpga-timing clean status

compile:
	./scripts/sbtw compile

test:
	./scripts/sbtw test

# The JSON schema checker is deliberately independent of Vivado. A release
# timing claim still needs a measured post-route record for the frozen target.
test-fpga-timing-evidence:
	python3 -m unittest scripts/tests/test_verify_fpga_timing.py

# Fast wrapper-only AXI/BRAM protocol regression. It runs in XSIM and checks
# the XPM implementation used to avoid LUTRAM over-utilization on the frozen
# FPGA target.
test-fpga-bram:
	./scripts/test_fpga_bram.sh

verify-fpga-timing:
	@test -n "$(FPGA_TIMING_EVIDENCE)" || \
		(echo "Set FPGA_TIMING_EVIDENCE=fpga/reports/<measured-run>.json" >&2; exit 2)
	python3 scripts/verify_fpga_timing.py --evidence "$(FPGA_TIMING_EVIDENCE)"

# Fast, focused M3 cacheable-store regression. Each invocation is intentionally
# bounded well below the five-minute component-simulation target.
test-m3-store:
	./scripts/sbtw "testOnly zircon.AXIDataStoreEngineSpec zircon.AXIL2WritebackEngineSpec zircon.DualMemoryLoadCompletionSpec zircon.ExclusiveL2TransferStoreSpec zircon.HostStoreFlushSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "write-allocates a cacheable store" -z "keeps a cacheable store local" -z "delays a trace-selected cacheable store retirement"'

# Focused M3 component suites have one canonical Make target each. Other M3
# targets below retain only unique component suites plus their top-level
# CoreShell scenarios, so running several slices does not rerun whole suites.

# Fast, focused M3 M0/M1 load-admission regression. It verifies that the
# unfinished M0 device/atomic owner cannot leak through the executable L1D
# path, and remains bounded below the five-minute component-simulation gate.
test-m3-load-boundary:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z inaccessible'

# Six explicit-seed complete-core same-bank merge and same-address resident-hit
# replay runs. Kept separate from the broad component target so each M3
# component simulation remains bounded.
test-m3-dual-load-merge:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "replays seeded same-bank M0 and M1 loads" -z "replays seeded same-address resident hits"'

# Focused mixed-resource matrix: a live-MSHR merge is accepted while an
# independent younger miss remains replayable instead of fabricating an owner.
test-m3-dual-resource:
	./scripts/sbtw 'testOnly zircon.L1DLoadCacheSpec -- -z "merges a live MSHR waiter while accepting an independent miss"'

# Nine explicit-seed complete-core partial-store forwarding runs. They require
# older SB and both aligned SH lane pairs to merge with a cacheable refill read
# by a younger LW.
test-m3-partial-store-forward:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "merges an older partial store forward with a cacheable refill" -z "merges an older halfword store forward with a cacheable refill" -z "merges an older low-halfword store forward with a cacheable refill"'

# Four explicit-seed complete-core MSHR pressure runs. Four data lines hold all
# retained owners; a fifth cannot send AR before one exact data R handshake.
test-m3-mshr-pressure:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "holds a fifth cache miss until a seeded live owner releases credit"'

# Fast external-coherence tier. It covers the reusable platform gate plus clean,
# dirty/retry, I-side and D-side in-flight drain, reset epochs, a blocked
# cacheable-store effect, and seeded matching/disjoint LR reservation
# invalidation at the complete-core boundary.
test-m3-external-coherence:
	./scripts/sbtw "testOnly zircon.ExternalCoherenceControllerSpec zircon.ExternalCoherenceAdapterSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "external cacheable" -z "external-coherence" -z "external coherence" -z "external invalidation" -z "external modifier"'

# Fast, focused exclusive L1D-L2 transfer regression. It covers clean victim
# handoff, L2 hit ownership transfer, dirty-victim FIFO backpressure, recovery
# drain, and one complete-core no-refill path within the component budget.
test-m3-l2:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "serves an evicted L1D line"'

# Fast, focused device-group AXI transport regression. LSQ/ROB integration is
# covered separately once the ordered M0 owner becomes executable.
test-m3-ordered-io:
	./scripts/sbtw "testOnly zircon.OrderedIOCombinerSpec zircon.AXIOrderedIOEngineSpec"

# Four explicit-seed complete-core DeviceBurstable group runs. They retain
# 1--4 live members behind a long divide and verify one exact ID-6 transaction.
test-m3-ordered-io-top:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "runs seeded one-to-four beat DeviceBurstable groups under ROB pressure"'

# Explicit fetch-pressure sealing case. The first group ends at a fetch-packet
# boundary and must seal before the delayed next packet arrives.
test-m3-ordered-io-fetch-pressure:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "seals a DeviceBurstable group early when fetch crosses a backpressured packet"'

# Fast, focused device ownership regression. It covers the exact-head LQ/SQ
# bridge through ID 6 and stays below the five-minute component-simulation gate.
# OrderedIOCombinerSpec and AXIOrderedIOEngineSpec belong to the standalone
# transport target above; rerunning them here added no device-ownership coverage.
test-m3-device-io:
	./scripts/sbtw "testOnly zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec"
		# Keep this slice to the basic M0/ID-6 ownership cases; the exact
		# selectors avoid rerunning the dedicated mixed-AXI, grouped-MMIO,
		# fetch-pressure, and interrupt cases below.
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "executes a DeviceStrong load" -z "executes a DeviceBurstable load" -z "turns a DeviceStrong RRESP error" -z "executes a DeviceStrong store" -z "turns a DeviceBurstable store BRESP error"'

# Fast full-channel AXI stress tier. It keeps the explicit-seed read/write
# backpressure, bounded two/four-owner data beat interleaving, and RRESP/BRESP
# fault cases below the five-minute component budget.
test-m3-axi-stress:
	./scripts/sbtw 'testOnly zircon.AXIDataReadEngineSpec'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves cross-ID AXI read ownership under seeded response interleaving" -z "retains four data owners before seeded cross-ID AXI drain" -z "starts clean AXI read and write owner epochs across reset" -z "preserves ordered device writes through explicitly seeded all-channel AXI backpressure" -z "preserves exact data RRESP and device BRESP faults under seeded AXI backpressure"'

# One explicit-seed core regression: an accepted wrong-path data refill must
# drain every R beat after recovery, but may not generate a load completion.
test-m3-axi-wrong-path-drain:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "drains an accepted wrong-path cache refill without retiring its load"'

# Separate reset-owner tier so the broad AXI stress target remains within its
# five-minute component-simulation budget.
test-m3-axi-reset:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "resets an accepted ID-5 writeback before its response" -z "resets an accepted ID-7 atomic write before its response"'

# Long-read ownership tier. It remains separate from the short pressure and
# reset tiers while exercising physical demand-owner reuse across eight lines.
test-m3-axi-long:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "reuses all data AXI owners across a seeded long cross-ID load stream"'

# Precise-fault tier. It reverses two- and four-owner legal cross-ID RRESP
# order, then exercises an older ID-6 BRESP against a younger RRESP fault.
test-m3-axi-faults:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "keeps the older load fault when a younger RRESP fault drains first" -z "keeps the oldest of four RRESP faults after reverse cross-ID drain" -z "keeps an older device BRESP fault when a younger RRESP fault drains first"'

# Cache-global FENCE must drain every dirty L1D/L2 line through the retained
# ID-5 owner before it or younger instructions may retire.
test-m3-fence-pressure:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "drains every dirty line before a cache-global FENCE retires" -z "retries an errored dirty FENCE writeback before retirement" -z "fills the dirty L2 victim FIFO before a cache-global FENCE can retire" -z "retries the oldest dirty FENCE writeback before draining the next victim"'

# Mixed cache refill/writeback and ordered-device traffic runs in a separate
# four-seed tier, keeping the more focused AXI slices quick to reproduce.
test-m3-axi-mixed:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves mixed cache and device AXI traffic" -z "drains two dirty cache lines and device AXI traffic through seeded backpressure" -z "retries a dirty writeback with mixed device AXI traffic through seeded backpressure"'

# Fast RV32A ownership regression. It remains below the five-minute component
# budget and covers the ID-7 owner, exact M0 completion, reservation loss, and
# L1D same-line exclusion/invalidation.
test-m3-atomic:
	./scripts/sbtw "testOnly zircon.AtomicMemoryEngineSpec zircon.MemIssueQueueSpec"
	# Use exact scenario selectors: the former broad selectors overlapped
	# heavily and also pulled in the dedicated AXI/error/ordering cases.
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "executes response-gated LR/SC through the ID-7 atomic owner" -z "invalidates LR/SC reservation on a conflicting local store" -z "returns the old AMO value only after its ID-7 read-modify-write response"'

# Three deterministic full-channel RV32A streams. Each mixes cache refill,
# ID-7 AMO read/modify/write, ID-6 device traffic, and FENCE ID-5 writeback
# while retaining a short standalone reproduction path.
test-m3-atomic-axi:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves ID-7 AMO ownership through seeded mixed AXI traffic"'

# Seeded RV32A programs cover every AMO.W encoding under independently varied
# AXI channels. Failure bundles include the generated instruction stream.
test-m3-atomic-random:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "runs seeded random RV32A AMO programs through the ID-7 owner"'

# Each seed performs one response-gated successful SC then invalidates a fresh
# reservation with a local store and proves the following SC is no-write.
test-m3-lrsc-random:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves seeded LR/SC success and local reservation loss under AXI backpressure"'

# An interrupt after LR must clear the reservation. The post-MRET SC is a
# no-write result even with independent five-channel AXI backpressure.
test-m3-lrsc-interrupt:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "clears seeded LR/SC reservations across an interrupt and MRET under AXI backpressure"'

# A failing LR response must trap precisely and prevent the following SC from
# owning an ID-7 write.
test-m3-lrsc-errors:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "turns seeded non-line-base LR RRESP errors into one exact trap"'

# A successful LR followed by a failing SC response must produce the exact SC
# trap after the sole ID-7 write response.
test-m3-sc-errors:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "turns seeded non-line-base SC BRESP errors into one exact trap"'

# Reservation granularity is a word: an unrelated local store must not turn a
# valid LR/SC pair into an artificial no-write failure.
test-m3-lrsc-granularity:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves seeded LR/SC reservations across a disjoint local store"'

# A newer LR owns the sole word-granularity reservation and makes an SC to
# the former address a local no-write failure.
test-m3-lrsc-replacement:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "replaces seeded LR/SC reservations with a later LR"'

# AMO B-response errors after a non-line-base ID-7 write must trap exactly once.
test-m3-atomic-errors:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "turns seeded non-line-base AMO BRESP errors into one exact trap"'

# Fast FENCE/aq/rl ordering tier. It covers pre-LSQ atomic gating, age-tagged
# LQ/SQ FENCE drain, and the executable FENCE/aq pressure cases below five minutes.
test-m3-ordering:
	./scripts/sbtw "testOnly zircon.CacheFenceDrainControllerSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "allows FENCE to retire while a younger cacheable load owns LQ state" -z "does not retire a dirty cache-global FENCE before the ID-5 B response" -z "writes back dirty code before FENCE.I invalidates the I-side and refetches it" -z "holds a younger cacheable load behind an aq atomic until its read response"'

# Focused M4 E2 bit-move/sign-injection tier. The AXI-fed CoreShell portion
# exercises the executable commit path without claiming the full RV32F ISA.
test-m4-fp-move:
	./scripts/sbtw 'testOnly zircon.FloatingAdmissionSpec zircon.FloatingMovePipeSpec zircon.FloatingResultBridgeSpec'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "RV32F" -z "preserves distinct FPR data for two floating stores in the dual LSU"'

# One AXI-fed top-level M-mode interrupt priority run. It exercises the
# architectural MEI > MSI > MTI ordering and MRET resume without rerunning
# the broader RV32F or M3 interrupt slices.
test-m4-interrupt-priority:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "prioritizes MEI over simultaneous MSI and MTI"'

verilog:
	./scripts/sbtw "runMain zircon.Elaborate --target-dir generated"

platform-verilog:
	./scripts/sbtw "runMain zircon.Elaborate --platform --target-dir generated-platform"

# M5 capacity A/B elaboration point. The default production RTL remains the
# 4 KiB configuration; this target writes an isolated 8 KiB platform tree.
platform-verilog-8k:
	./scripts/sbtw "runMain zircon.Elaborate --platform --l2-8k --target-dir generated-platform-8k"

fpga-impl:
	./scripts/run_fpga_impl.sh

trace-verilog:
	./scripts/sbtw "runMain zircon.Elaborate --trace --target-dir generated-trace"

software:
	$(MAKE) -C RV-Software/picotest image
	$(MAKE) -C RV-Software/picotest image RISCV_ARCH=rv32im RISCV_ABI=ilp32

sim-unit:
	$(MAKE) -C ZirconSim unit

sim-smoke:
	$(MAKE) -C ZirconSim smoke

static-area:
	python3 scripts/static_area_report.py \
		--baseline area/zircon-2024.json \
		--candidate area/zircon-2026.json

static-area-check:
	python3 -m unittest scripts/tests/test_static_area_report.py
	python3 scripts/static_area_report.py \
		--baseline area/zircon-2024.json \
		--candidate area/zircon-2026.json \
		--check

verify-m0: test static-area-check software sim-unit sim-smoke

clean:
	./scripts/sbtw clean
	$(MAKE) -C ZirconSim clean

status:
	git status --short
	git submodule status
