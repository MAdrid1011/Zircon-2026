.PHONY: compile test test-m3-store test-m3-load-boundary test-m3-ordered-io test-m3-device-io verilog trace-verilog software sim-unit sim-smoke static-area \
	static-area-check verify-m0 clean status

compile:
	./scripts/sbtw compile

test:
	./scripts/sbtw test

# Fast, focused M3 cacheable-store regression. Each invocation is intentionally
# bounded well below the five-minute component-simulation target.
test-m3-store:
	./scripts/sbtw "testOnly zircon.AXIDataStoreEngineSpec zircon.DualMemoryLoadCompletionSpec zircon.LoadStoreQueuesSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "executes a cacheable store"'
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "cacheable store BRESP error"'

# Fast, focused M3 M0/M1 load-admission regression. It verifies that the
# unfinished M0 device/atomic owner cannot leak through the executable L1D
# path, and remains bounded below the five-minute component-simulation gate.
test-m3-load-boundary:
	./scripts/sbtw "testOnly zircon.DualLSUIngressSpec zircon.LoadStoreQueuesSpec zircon.L1DLoadCacheSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "without L1D"'

# Fast, focused device-group AXI transport regression. LSQ/ROB integration is
# covered separately once the ordered M0 owner becomes executable.
test-m3-ordered-io:
	./scripts/sbtw "testOnly zircon.OrderedIOCombinerSpec zircon.AXIOrderedIOEngineSpec"

# Fast, focused device ownership regression. It covers the exact-head LQ/SQ
# bridge through ID 6 and stays below the five-minute component-simulation gate.
test-m3-device-io:
	./scripts/sbtw "testOnly zircon.LoadStoreQueuesSpec zircon.MemoryQueueIngressSpec zircon.DualLSUIngressSpec zircon.AXIOrderedIOEngineSpec zircon.OrderedIOCombinerSpec"
	./scripts/sbtw 'testOnly zircon.CoreShellSpec -- -z "Device"'

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
