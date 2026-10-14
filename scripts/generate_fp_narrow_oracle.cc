// Zve32f integer/FP32 width-conversion oracle using Spike's SoftFloat library.
#include <array>
#include <cstdint>
#include <cstdio>
#include <random>
extern "C" {
#include <softfloat.h>
}

int main(int argc, char **argv) {
    if (argc != 2) { return 1; }
    FILE *output = std::fopen(argv[1], "w");
    if (output == nullptr) { return 1; }
    std::mt19937 random(20261013);
    const std::array<uint32_t, 24> specials{{0, 0x80000000, 0x7f800000, 0xff800000,
        0x7fc00000, 0x7f800001, 1, 0x007fffff, 0x7f7fffff, 0x3f800000,
        0xbf800000, 0x3f000000, 0xbf000000, 0x3fc00000, 0xbfc00000,
        0x46ffff00, 0x47000000, 0xc7000000, 0xc7000080, 0x477fff00,
        0x47800000, 0x477fff80, 0x46fffe00, 0xc6ffff00}};
    for (unsigned operation : {10u, 11u, 16u, 17u, 22u, 23u}) {
        for (unsigned rounding = 0; rounding < 5; ++rounding) {
            for (unsigned i = 0; i < 64; ++i) {
                const uint32_t input = i < specials.size() ? specials[i] : random();
                softfloat_roundingMode = rounding;
                softfloat_exceptionFlags = 0;
                uint32_t result;
                if (operation == 10) { result = ui32_to_f32(input & 65535u).v; }
                else if (operation == 11) { result = i32_to_f32(int16_t(input)).v; }
                else {
                    const unsigned rm = operation >= 22 ? 1u : rounding;
                    result = (operation & 1u) ? uint16_t(f32_to_i16(float32_t{input}, rm, true)) :
                        uint16_t(f32_to_ui16(float32_t{input}, rm, true));
                }
                std::fprintf(output, "%x %x %08x %08x %x\n", operation, rounding, input,
                    result, unsigned(softfloat_exceptionFlags));
            }
        }
    }
    return std::fclose(output) != 0;
}
