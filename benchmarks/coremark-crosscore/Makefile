.DEFAULT_GOAL := help
JOBS ?= 2
ITERATIONS ?= 30
NICE_LEVEL ?= 15
TARGETS ?= zircon xiangshan-yanqihu boom-small boom-medium

export JOBS
export ITERATIONS
export NICE_LEVEL

.PHONY: help check fetch build run compare report validate-reference \
    build-zircon build-xiangshan-yanqihu build-boom-small build-boom-medium \
    run-zircon run-xiangshan-yanqihu run-boom-small run-boom-medium clean

help: ; @scripts/help.sh

check: ; @scripts/preflight.sh $(TARGETS)

fetch: ; @scripts/fetch.sh $(TARGETS)

build: $(addprefix build-,$(TARGETS))

run: $(addprefix run-,$(TARGETS))

compare: check fetch build run report

build-zircon: ; @scripts/preflight.sh zircon && scripts/fetch.sh zircon && scripts/build-target.sh zircon

build-xiangshan-yanqihu: ; @scripts/preflight.sh xiangshan-yanqihu && scripts/fetch.sh xiangshan-yanqihu && scripts/build-target.sh xiangshan-yanqihu

build-boom-small: ; @scripts/preflight.sh boom-small && scripts/fetch.sh boom-small && scripts/build-target.sh boom-small

build-boom-medium: ; @scripts/preflight.sh boom-medium && scripts/fetch.sh boom-medium && scripts/build-target.sh boom-medium

run-zircon: build-zircon ; @scripts/run-target.sh zircon

run-xiangshan-yanqihu: build-xiangshan-yanqihu ; @scripts/run-target.sh xiangshan-yanqihu

run-boom-small: build-boom-small ; @scripts/run-target.sh boom-small

run-boom-medium: build-boom-medium ; @scripts/run-target.sh boom-medium

report: ; @python3 scripts/report.py --results-dir results/current

validate-reference: ; @scripts/validate-reference.sh

clean: ; @scripts/clean.sh
