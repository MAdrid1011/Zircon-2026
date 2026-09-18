#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#define UART_BASE 0x40600000UL
#define UART_TX (*(volatile uint8_t *)(UART_BASE + 4))
#define UART_STATUS (*(volatile uint8_t *)(UART_BASE + 8))
#define UART_CONTROL (*(volatile uint8_t *)(UART_BASE + 12))

static void putchar_uart(char ch) {
    if (ch == '\n') {
        putchar_uart('\r');
    }
    while ((UART_STATUS & 8U) != 0U) {
    }
    UART_TX = (uint8_t)ch;
}

static int print_string(const char *text) {
    int count = 0;
    while (*text != '\0') {
        putchar_uart(*text++);
        count++;
    }
    return count;
}

static int print_unsigned(unsigned long value, unsigned base, int width, char pad) {
    static const char digits[] = "0123456789abcdef";
    char buffer[32];
    int length = 0;
    int count = 0;

    do {
        buffer[length++] = digits[value % base];
        value /= base;
    } while (value != 0);

    while (length < width) {
        putchar_uart(pad);
        count++;
        width--;
    }
    while (length > 0) {
        putchar_uart(buffer[--length]);
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
            putchar_uart(*format++);
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
                putchar_uart((char)va_arg(args, int));
                count++;
                break;
            case 'd': {
                long value = long_value ? va_arg(args, long) : va_arg(args, int);
                if (value < 0) {
                    putchar_uart('-');
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
                putchar_uart('%');
                count++;
                break;
            default:
                putchar_uart('?');
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

void runtime_uart_init(void) {
    UART_CONTROL = 3U;
}
