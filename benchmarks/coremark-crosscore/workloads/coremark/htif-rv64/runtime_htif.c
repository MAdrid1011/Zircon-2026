#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

volatile uint64_t tohost __attribute__((section(".tohost"), aligned(64)));
volatile uint64_t fromhost __attribute__((section(".tohost"), aligned(64)));

static void htif_send(uint64_t command) {
    while (tohost != 0U) {
    }
    tohost = command;
    while (tohost != 0U) {
    }
}

static void putchar_htif(char ch) {
    htif_send(0x0101000000000000ULL | (uint8_t)ch);
}

static int print_string(const char *text) {
    int count = 0;
    while (*text != '\0') {
        putchar_htif(*text++);
        count++;
    }
    return count;
}

static unsigned long divide_unsigned(unsigned long dividend, unsigned divisor, unsigned *remainder) {
    unsigned long quotient = 0;
    unsigned long partial = 0;

    for (int bit = (int)(sizeof(dividend) * 8U) - 1; bit >= 0; bit--) {
        partial = (partial << 1) | ((dividend >> bit) & 1U);
        if (partial >= divisor) {
            partial -= divisor;
            quotient |= 1UL << bit;
        }
    }
    *remainder = (unsigned)partial;
    return quotient;
}

static int print_unsigned(unsigned long value, unsigned base, int width, char pad) {
    static const char digits[] = "0123456789abcdef";
    char buffer[32];
    int length = 0;
    int count = 0;

    do {
        unsigned remainder;
        value = divide_unsigned(value, base, &remainder);
        buffer[length++] = digits[remainder];
    } while (value != 0);

    while (length < width) {
        putchar_htif(pad);
        count++;
        width--;
    }
    while (length > 0) {
        putchar_htif(buffer[--length]);
        count++;
    }
    return count;
}

int printf(const char *format, ...) {
    va_list args;
    int count = 0;

    va_start(args, format);
    while (*format != '\0') {
        int width = 0;
        char pad = ' ';
        int long_value = 0;

        if (*format != '%') {
            putchar_htif(*format++);
            count++;
            continue;
        }
        format++;
        if (*format == '0') {
            pad = '0';
            format++;
        }
        while (*format >= '0' && *format <= '9') {
            width = width * 10 + (*format++ - '0');
        }
        if (*format == 'l') {
            long_value = 1;
            format++;
        }
        switch (*format++) {
            case 's':
                count += print_string(va_arg(args, const char *));
                break;
            case 'c':
                putchar_htif((char)va_arg(args, int));
                count++;
                break;
            case 'd': {
                long value = long_value ? va_arg(args, long) : va_arg(args, int);
                if (value < 0) {
                    putchar_htif('-');
                    count++;
                    value = -value;
                }
                count += print_unsigned((unsigned long)value, 10, width, pad);
                break;
            }
            case 'u':
                count += print_unsigned(
                    long_value ? va_arg(args, unsigned long) : va_arg(args, unsigned int),
                    10,
                    width,
                    pad
                );
                break;
            case 'x':
                count += print_unsigned(
                    long_value ? va_arg(args, unsigned long) : va_arg(args, unsigned int),
                    16,
                    width,
                    pad
                );
                break;
            case '%':
                putchar_htif('%');
                count++;
                break;
            default:
                putchar_htif('?');
                count++;
                break;
        }
    }
    va_end(args);
    return count;
}

void *memset(void *destination, int value, size_t size) {
    unsigned char *output = (unsigned char *)destination;
    while (size-- != 0U) {
        *output++ = (unsigned char)value;
    }
    return destination;
}

void runtime_exit(int code) {
    tohost = ((uint64_t)(uint32_t)code << 1) | 1U;
    while (1) {
    }
}
