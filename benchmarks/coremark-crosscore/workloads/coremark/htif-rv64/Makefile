COREMARK ?= ../../../../../RV-Software/coremark
BUILD ?= build
CLANG ?= clang
LD_LLD ?= ld.lld
OBJCOPY ?= llvm-objcopy
OBJDUMP ?= llvm-objdump
ITERATIONS ?= 30

ARCH := rv64gc_zicsr_zifencei
ABI := lp64d
COMMON_FLAGS := --target=riscv64 -march=$(ARCH) -mabi=$(ABI) -mcmodel=medany \
    -O2 -static -ffreestanding -fno-builtin -fno-pic -fno-pie \
    -fno-stack-protector -fno-asynchronous-unwind-tables -msmall-data-limit=0 \
    -ffunction-sections -fdata-sections -Wall -Wextra \
    -I$(COREMARK)/include -include core_portme_rv64.h \
    -DITERATIONS=$(ITERATIONS)

CORE_SRCS := \
    $(COREMARK)/src/core_list_join.c \
    $(COREMARK)/src/core_main.c \
    $(COREMARK)/src/core_matrix.c \
    $(COREMARK)/src/core_state.c \
    $(COREMARK)/src/core_util.c
XIANGSHAN_OBJS := $(patsubst $(COREMARK)/src/%.c,$(BUILD)/%.o,$(CORE_SRCS)) \
    $(BUILD)/core_portme_rv64.o $(BUILD)/runtime.o $(BUILD)/start.o
BOOM_OBJS := $(patsubst $(COREMARK)/src/%.c,$(BUILD)/boom-%.o,$(CORE_SRCS)) \
    $(BUILD)/boom-core_portme_rv64.o $(BUILD)/runtime_htif.o $(BUILD)/start_htif.o

.PHONY: all boom clean

all: $(BUILD)/coremark-i$(ITERATIONS)-rv64.elf $(BUILD)/coremark-i$(ITERATIONS)-rv64.bin

boom: $(BUILD)/coremark-i$(ITERATIONS)-boom.elf

$(BUILD)/coremark-i$(ITERATIONS)-rv64.elf: $(XIANGSHAN_OBJS) linker.ld ; @mkdir -p $(BUILD) && $(LD_LLD) -m elf64lriscv -static --gc-sections -T linker.ld -o $@ $(XIANGSHAN_OBJS) && $(OBJDUMP) -d $@ > $(BUILD)/coremark-i$(ITERATIONS)-rv64.txt

$(BUILD)/coremark-i$(ITERATIONS)-rv64.bin: $(BUILD)/coremark-i$(ITERATIONS)-rv64.elf ; $(OBJCOPY) -O binary $< $@

$(BUILD)/coremark-i$(ITERATIONS)-boom.elf: $(BOOM_OBJS) linker_htif.ld ; @mkdir -p $(BUILD) && $(LD_LLD) -m elf64lriscv -static --gc-sections -T linker_htif.ld -o $@ $(BOOM_OBJS) && $(OBJDUMP) -d $@ > $(BUILD)/coremark-i$(ITERATIONS)-boom.txt

$(BUILD)/boom-%.o: $(COREMARK)/src/%.c core_portme_rv64.h ; @mkdir -p $(BUILD) && $(CLANG) $(COMMON_FLAGS) -c $< -o $@

$(BUILD)/boom-core_portme_rv64.o: core_portme_rv64.c core_portme_rv64.h ; @mkdir -p $(BUILD) && $(CLANG) $(COMMON_FLAGS) -c $< -o $@

$(BUILD)/runtime_htif.o: runtime_htif.c ; @mkdir -p $(BUILD) && $(CLANG) $(COMMON_FLAGS) -c $< -o $@

$(BUILD)/start_htif.o: start_htif.S ; @mkdir -p $(BUILD) && $(CLANG) --target=riscv64 -march=$(ARCH) -mabi=$(ABI) -mcmodel=medany -c $< -o $@

$(BUILD)/%.o: $(COREMARK)/src/%.c core_portme_rv64.h ; @mkdir -p $(BUILD) && $(CLANG) $(COMMON_FLAGS) -c $< -o $@

$(BUILD)/%.o: %.c core_portme_rv64.h ; @mkdir -p $(BUILD) && $(CLANG) $(COMMON_FLAGS) -c $< -o $@

$(BUILD)/start.o: start.S ; @mkdir -p $(BUILD) && $(CLANG) --target=riscv64 -march=$(ARCH) -mabi=$(ABI) -mcmodel=medany -c $< -o $@

clean: ; rm -rf $(BUILD)
