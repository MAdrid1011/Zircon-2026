.PHONY: compile test verilog trace-verilog software sim-unit sim-smoke verify-m0 clean status

compile:
	./scripts/sbtw compile

test:
	./scripts/sbtw test

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

verify-m0: test software sim-unit sim-smoke

clean:
	./scripts/sbtw clean
	$(MAKE) -C ZirconSim clean

status:
	git status --short
	git submodule status
