#include "coremark.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

static CORE_TICKS read_cycle(void) {
    unsigned long cycles;
    __asm__ volatile("csrr %0, mcycle" : "=r"(cycles));
    return (CORE_TICKS)cycles;
}

static CORE_TICKS start_time_val;
static CORE_TICKS stop_time_val;

void start_time(void) {
    start_time_val = read_cycle();
}

void stop_time(void) {
    stop_time_val = read_cycle();
}

CORE_TICKS get_time(void) {
    return stop_time_val - start_time_val;
}

secs_ret time_in_secs(CORE_TICKS ticks) {
    return ticks;
}

typedef struct {
    ee_u32 high;
    ee_u32 low;
} coremark_u64_parts;

static coremark_u64_parts multiply_u32(ee_u32 lhs, ee_u32 rhs) {
    const ee_u32 lhs_low = lhs & 0xffffU;
    const ee_u32 lhs_high = lhs >> 16;
    const ee_u32 rhs_low = rhs & 0xffffU;
    const ee_u32 rhs_high = rhs >> 16;
    const ee_u32 product_low = lhs_low * rhs_low;
    const ee_u32 product_middle =
        (product_low >> 16) + (lhs_low * rhs_high & 0xffffU) + (lhs_high * rhs_low & 0xffffU);
    coremark_u64_parts product;

    product.low = (product_low & 0xffffU) | (product_middle << 16);
    product.high = lhs_high * rhs_high + (lhs_low * rhs_high >> 16)
        + (lhs_high * rhs_low >> 16) + (product_middle >> 16);
    return product;
}

static int less_than_or_equal(coremark_u64_parts lhs, coremark_u64_parts rhs) {
    return lhs.high < rhs.high || (lhs.high == rhs.high && lhs.low <= rhs.low);
}

ee_u32 coremark_score_milli(CORE_TICKS cycles, ee_u32 iterations) {
    const coremark_u64_parts scaled_iterations = multiply_u32(iterations, 1000000000U);
    ee_u32 low = 0;
    ee_u32 high = 1000000000U;

    if (cycles == 0U) {
        return 0U;
    }
    while (low < high) {
        const ee_u32 midpoint = low + (high - low + 1U) / 2U;
        if (less_than_or_equal(multiply_u32(midpoint, cycles), scaled_iterations)) {
            low = midpoint;
        } else {
            high = midpoint - 1U;
        }
    }
    return low;
}

ee_u32 default_num_contexts = 1;

void portable_init(core_portable *p, int *argc, char *argv[]) {
    (void)argc;
    (void)argv;
    p->portable_id = 1;
}

void portable_fini(core_portable *p) {
    p->portable_id = 0;
}
