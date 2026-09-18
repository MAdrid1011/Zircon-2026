#ifndef CORE_PORTME_H
#define CORE_PORTME_H

#include <stddef.h>
#include <stdint.h>

#ifndef ITERATIONS
#define ITERATIONS 30
#endif

#define MEM_METHOD MEM_STATIC
#define COREMARK_CYCLE_TIMER 1
#define HAS_FLOAT 0
#define HAS_TIME_H 0
#define USE_CLOCK 0
#define HAS_STDIO 0
#define HAS_PRINTF 1
#define MAIN_HAS_NOARGC 1
#define MAIN_HAS_NORETURN 0
#define SEED_METHOD SEED_VOLATILE
#define MULTITHREAD 1

typedef uint32_t CORE_TICKS;

#define COMPILER_VERSION "Clang " __clang_version__
#define COMPILER_FLAGS "-O2 -static -march=rv64gc_zicsr_zifencei -mabi=lp64d"
#define MEM_LOCATION "Code and data in simulated RAM; L1 cache at core clock"

typedef signed short ee_s16;
typedef unsigned short ee_u16;
typedef signed int ee_s32;
typedef double ee_f32;
typedef unsigned char ee_u8;
typedef unsigned int ee_u32;
typedef unsigned long ee_ptr_int;
typedef size_t ee_size_t;

typedef struct CORE_PORTABLE_S {
    ee_u8 portable_id;
} core_portable;

extern ee_u32 default_num_contexts;

int printf(const char *format, ...);
ee_u32 coremark_score_milli(CORE_TICKS cycles, ee_u32 iterations);
void portable_init(core_portable *p, int *argc, char *argv[]);
void portable_fini(core_portable *p);

#define align_mem(x) (void *)(4 + (((unsigned long)(x) - 1) & ~3UL))

#if !defined(PROFILE_RUN) && !defined(PERFORMANCE_RUN) && !defined(VALIDATION_RUN)
#if (TOTAL_DATA_SIZE == 1200)
#define PROFILE_RUN 1
#elif (TOTAL_DATA_SIZE == 2000)
#define PERFORMANCE_RUN 1
#else
#define VALIDATION_RUN 1
#endif
#endif

#endif
