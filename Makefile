.PHONY: compile test test-m3-store test-m3-load-boundary test-m3-l2 test-m3-ordered-io test-m3-device-io test-m3-axi-stress test-m3-atomic test-m3-ordering verilog trace-verilog software sim-unit sim-smoke static-area \
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

# Fast, focused device ownership regression. It covers the exact-head LQ/SQ
# bridge through ID 6 and stays below the five-minute component-simulation gate.
test-m3-device-io:
	./scripts/sbtw "testOnly zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec zircon.AXIOrderedIOEngineSpec zircon.OrderedIOCombinerSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "Device"'

# Fast full-channel AXI stress tier. It keeps the explicit-seed read/write
# backpressure and RRESP/BRESP fault cases below the five-minute component budget.
test-m3-axi-stress:
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves ordered device writes through explicitly seeded all-channel AXI backpressure"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "preserves exact data RRESP and device BRESP faults under seeded AXI backpressure"'

# Fast RV32A ownership regression. It remains below the five-minute component
# budget and covers the ID-7 owner, exact M0 completion, reservation loss, and
# L1D same-line exclusion/invalidation.
test-m3-atomic:
	./scripts/sbtw "testOnly zircon.AtomicMemoryEngineSpec zircon.DualMemoryLoadCompletionSpec zircon.LoadStoreQueuesSpec zircon.L1DLoadCacheSpec zircon.MemIssueQueueSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "atomic"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "LR/SC"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "reservation"'

# Fast FENCE/aq/rl ordering tier. It covers pre-LSQ atomic gating, age-tagged
# LQ/SQ FENCE drain, and the executable FENCE/aq pressure cases below five minutes.
test-m3-ordering:
	./scripts/sbtw "testOnly zircon.MemIssueQueueSpec zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "allows FENCE to retire while a younger cacheable load owns LQ state"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "holds a younger cacheable load behind an aq atomic until its read response"'

verilog:
	./scripts/sbtw "runMain zircon.Elaborate --target-dir generated"

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
