ENTRY(_start)

SECTIONS {
    . = 0x80000000;

    .text : {
        KEEP(*(.text.init))
        *(.text*)
    }

    .rodata : {
        *(.rodata*)
    }

    .data : {
        *(.data*)
    }

    . = ALIGN(8);
    __bss_start = .;
    .bss (NOLOAD) : {
        *(.bss*)
        *(COMMON)
    }
    . = ALIGN(8);
    __bss_end = .;

    . = ALIGN(16);
    . += 64K;
    __stack_top = .;
}
