.PHONY: compile test test-m3-store test-m3-load-boundary test-m3-dual-load-forward test-m3-dual-load-merge test-m3-partial-store-forward test-m3-mshr-pressure test-m3-l2 test-m3-ordered-io test-m3-ordered-io-top test-m3-device-io test-m3-axi-stress test-m3-axi-wrong-path-drain test-m3-axi-reset test-m3-axi-long test-m3-axi-faults test-m3-fence-pressure test-m3-axi-mixed test-m3-atomic test-m3-atomic-axi test-m3-atomic-random test-m3-lrsc-random test-m3-lrsc-interrupt test-m3-lrsc-errors test-m3-sc-errors test-m3-lrsc-granularity test-m3-lrsc-replacement test-m3-atomic-errors test-m3-external-coherence test-m3-ordering verilog platform-verilog trace-verilog software sim-unit sim-smoke static-area \
	static-area-check verify-m0 clean status

compile:
	./scripts/sbtw compile

test:
	./scripts/sbtw test

# Fast, focused M3 cacheable-store regression. Each invocation is intentionally
# bounded well below the five-minute component-simulation target.
test-m3-store:
	./scripts/sbtw "testOnly zircon.AXIDataStoreEngineSpec zircon.AXIL2WritebackEngineSpec zircon.DualMemoryLoadCompletionSpec zircon.ExclusiveL2TransferStoreSpec zircon.HostStoreFlushSpec zircon.LoadStoreQueuesSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "write-allocates a cacheable store"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "keeps a cacheable store local"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "delays a trace-selected cacheable store retirement"'

# Fast, focused M3 M0/M1 load-admission regression. It verifies that the
# unfinished M0 device/atomic owner cannot leak through the executable L1D
# path, and remains bounded below the five-minute component-simulation gate.
test-m3-load-boundary:
	./scripts/sbtw "testOnly zircon.DualLSUIngressSpec zircon.LoadStoreQueuesSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "without L1D"'

# Fast M3 two-candidate load-forward tier. It checks the retained-LQ boundary
# plus direct two-lane L1D different-bank hits, same-bank/address replay,
# same-line secondary merge, different-set hit/miss and invalid-way dual-miss
# ownership, recovery release/drain of killed dual-miss owners, MSHR/waiter/
# victim backpressure, conservative same-set/victim replay, and exact
# result-slot ordering before broader concurrent miss-resource work.
test-m3-dual-load-forward:
	./scripts/sbtw "testOnly zircon.DualLoadForwardArbiterSpec zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "independent cacheable loads through both M0 and M1"'

# Three explicit-seed complete-core same-bank load replay/merge runs. Kept
# separate from the broad component target so each M3 component simulation
# remains bounded.
test-m3-dual-load-merge:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "replays seeded same-bank M0 and M1 loads"'

# Six explicit-seed complete-core partial-store forwarding runs. They require
# older SB and SH byte lanes to merge with the cacheable refill read by a younger LW.
test-m3-partial-store-forward:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "merges an older partial store forward with a cacheable refill"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "merges an older halfword store forward with a cacheable refill"'

# Four explicit-seed complete-core MSHR pressure runs. Four data lines hold all
# retained owners; a fifth cannot send AR before one exact data R handshake.
test-m3-mshr-pressure:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "holds a fifth cache miss until a seeded live owner releases credit"'

# Fast external-coherence tier. It covers the reusable platform gate plus clean,
# dirty/retry, I-side and D-side in-flight drain, reset epochs, and seeded
# matching/disjoint LR reservation invalidation at the complete-core boundary.
test-m3-external-coherence:
	./scripts/sbtw "testOnly zircon.ExternalCoherenceControllerSpec zircon.ExternalCoherenceAdapterSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "external"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "failing coherence writeback"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "drops a dirty coherence writeback"'

# Fast, focused exclusive L1D-L2 transfer regression. It covers clean victim
# handoff, L2 hit ownership transfer, dirty-victim FIFO backpressure, recovery
# drain, and one complete-core no-refill path within the component budget.
test-m3-l2:
	./scripts/sbtw "testOnly zircon.ExclusiveL2TransferStoreSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "serves an evicted L1D line"'

# Fast, focused device-group AXI transport regression. LSQ/ROB integration is
# covered separately once the ordered M0 owner becomes executable.
test-m3-ordered-io:
	./scripts/sbtw "testOnly zircon.OrderedIOCombinerSpec zircon.AXIOrderedIOEngineSpec"

# Four explicit-seed complete-core DeviceBurstable group runs. They retain
# 1--4 live members behind a long divide and verify one exact ID-6 transaction.
test-m3-ordered-io-top:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "runs seeded one-to-four beat DeviceBurstable groups under ROB pressure"'

# Fast, focused device ownership regression. It covers the exact-head LQ/SQ
# bridge through ID 6 and stays below the five-minute component-simulation gate.
test-m3-device-io:
	./scripts/sbtw "testOnly zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec zircon.AXIOrderedIOEngineSpec zircon.OrderedIOCombinerSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "Device"'

# Fast full-channel AXI stress tier. It keeps the explicit-seed read/write
# backpressure, bounded two/four-owner data beat interleaving, and RRESP/BRESP
# fault cases below the five-minute component budget.
test-m3-axi-stress:
	./scripts/sbtw 'testOnly zircon.AXIDataReadEngineSpec'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves cross-ID AXI read ownership under seeded response interleaving"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "retains four data owners before seeded cross-ID AXI drain"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "starts clean AXI read and write owner epochs across reset"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves ordered device writes through explicitly seeded all-channel AXI backpressure"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves exact data RRESP and device BRESP faults under seeded AXI backpressure"'

# One explicit-seed core regression: an accepted wrong-path data refill must
# drain every R beat after recovery, but may not generate a load completion.
test-m3-axi-wrong-path-drain:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "drains an accepted wrong-path cache refill without retiring its load"'

# Separate reset-owner tier so the broad AXI stress target remains within its
# five-minute component-simulation budget.
test-m3-axi-reset:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "resets an accepted ID-5 writeback before its response"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "resets an accepted ID-7 atomic write before its response"'

# Long-read ownership tier. It remains separate from the short pressure and
# reset tiers while exercising physical demand-owner reuse across eight lines.
test-m3-axi-long:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "reuses all data AXI owners across a seeded long cross-ID load stream"'

# Precise-fault tier. It reverses two- and four-owner legal cross-ID RRESP
# order, then exercises an older ID-6 BRESP against a younger RRESP fault.
test-m3-axi-faults:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "keeps the older load fault when a younger RRESP fault drains first"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "keeps the oldest of four RRESP faults after reverse cross-ID drain"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "keeps an older device BRESP fault when a younger RRESP fault drains first"'

# Cache-global FENCE must drain every dirty L1D/L2 line through the retained
# ID-5 owner before it or younger instructions may retire.
test-m3-fence-pressure:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "drains every dirty line before a cache-global FENCE retires"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "retries an errored dirty FENCE writeback before retirement"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "fills the dirty L2 victim FIFO before a cache-global FENCE can retire"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "retries the oldest dirty FENCE writeback before draining the next victim"'

# Mixed cache refill/writeback and ordered-device traffic runs in a separate
# four-seed tier, keeping the more focused AXI slices quick to reproduce.
test-m3-axi-mixed:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "device AXI traffic through seeded backpressure"'

# Fast RV32A ownership regression. It remains below the five-minute component
# budget and covers the ID-7 owner, exact M0 completion, reservation loss, and
# L1D same-line exclusion/invalidation.
test-m3-atomic:
	./scripts/sbtw "testOnly zircon.AtomicMemoryEngineSpec zircon.DualMemoryLoadCompletionSpec zircon.LoadStoreQueuesSpec zircon.L1DLoadCacheSpec zircon.MemIssueQueueSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "atomic"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "LR/SC"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "reservation"'

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
	./scripts/sbtw "testOnly zircon.CacheFenceDrainControllerSpec zircon.MemIssueQueueSpec zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec zircon.ExclusiveL2TransferStoreSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "allows FENCE to retire while a younger cacheable load owns LQ state"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "does not retire a dirty cache-global FENCE before the ID-5 B response"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "writes back dirty code before FENCE.I invalidates the I-side and refetches it"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "holds a younger cacheable load behind an aq atomic until its read response"'

verilog:
	./scripts/sbtw "runMain zircon.Elaborate --target-dir generated"

platform-verilog:
	./scripts/sbtw "runMain zircon.Elaborate --platform --target-dir generated-platform"

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
