module XilinxSinglePortRamReadFirst #(
    parameter RAMWIDTH = 18,
    parameter RAMDEPTH = 1024
) (
    input [((RAMDEPTH > 1) ? $clog2(RAMDEPTH) : 1)-1:0] addra,
    input [RAMWIDTH-1:0] dina,
    input clka,
    input wea,
    input ena,
    output [RAMWIDTH-1:0] douta
);
    (* ram_style = "block" *)
    reg [RAMWIDTH-1:0] BRAM [RAMDEPTH-1:0];
    reg [((RAMDEPTH > 1) ? $clog2(RAMDEPTH) : 1)-1:0] addrR;
    integer ramIndex;

    initial begin
        for (ramIndex = 0; ramIndex < RAMDEPTH; ramIndex = ramIndex + 1)
            BRAM[ramIndex] = {RAMWIDTH{1'b0}};
    end

    always @(posedge clka) begin
        if (ena) begin
            if (wea)
                BRAM[addra] <= dina;
            addrR <= addra;
        end
    end

    assign douta = BRAM[addrR];

    function integer clogb2;
        input integer depth;
        for (clogb2 = 0; depth > 0; clogb2 = clogb2 + 1)
            depth = depth >> 1;
    endfunction
endmodule
