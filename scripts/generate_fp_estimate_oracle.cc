// RVV estimate oracle from Spike's Berkeley SoftFloat implementation.
// clang++ -I<spike>/include/softfloat scripts/generate_fp_estimate_oracle.cc
//   -L<spike>/lib -Wl,-rpath,<spike>/lib -lsoftfloat -o build/fp-estimate-oracle
#include <array>
#include <cstdint>
#include <cstdio>
#include <random>
extern "C" {
#include <softfloat.h>
}

int main() {
    std::mt19937 random(20261013);
    const std::array<uint32_t, 16> specials{{0, 0x80000000, 0x7f800000, 0xff800000,
        0x7fc00000, 0x7f800001, 0xff800001, 1, 0x007fffff, 0x00200000,
        0x00400000, 0x7f7fffff, 0x3f800000, 0x40000000, 0xbf800000, 0x00800000}};
    for (unsigned sqrt = 0; sqrt < 2; ++sqrt) {
        for (unsigned rounding = 0; rounding < 5; ++rounding) {
            for (unsigned i = 0; i < 512; ++i) {
                const uint32_t bits = i < specials.size() ? specials[i] :
                    i < 144 ? 0x3f800000 | ((i - 16) << 16) : random();
                softfloat_roundingMode = rounding;
                softfloat_exceptionFlags = 0;
                const float32_t input{bits};
                const auto result = sqrt ? f32_rsqrte7(input) : f32_recip7(input);
                std::printf("%x %x %08x %08x %x\n", sqrt, rounding, bits, result.v,
                    unsigned(softfloat_exceptionFlags));
            }
        }
    }
}
