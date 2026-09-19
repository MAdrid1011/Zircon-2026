module XilinxTrueDualPortReadFirst1ClockRam #(
    parameter RAMWIDTH = 18,
    parameter RAMDEPTH = 1024
) (
    input [clogb2(RAMDEPTH-1)-1:0] addra,
    input [clogb2(RAMDEPTH-1)-1:0] addrb,
    input [RAMWIDTH-1:0] dina,
    input [RAMWIDTH-1:0] dinb,
    input clka,
    input wea,
    input web,
    input ena,
    input enb,
    output [RAMWIDTH-1:0] douta,
    output [RAMWIDTH-1:0] doutb
);
    (* ram_style = "block" *)
    reg [RAMWIDTH-1:0] BRAM [RAMDEPTH-1:0];
    reg [$clog2(RAMDEPTH)-1:0] addrRA;
    reg [$clog2(RAMDEPTH)-1:0] addrRB;
    integer ramIndex;

    initial begin
        for (ramIndex = 0; ramIndex < RAMDEPTH; ramIndex = ramIndex + 1)
            BRAM[ramIndex] = {RAMWIDTH{1'b0}};
    end

    always @(posedge clka) begin
        if (ena) begin
            if (wea)
                BRAM[addra] <= dina;
            addrRA <= addra;
        end
        if (enb) begin
            if (web)
                BRAM[addrb] <= dinb;
            addrRB <= addrb;
        end
    end

    assign douta = BRAM[addrRA];
    assign doutb = BRAM[addrRB];

    function integer clogb2;
        input integer depth;
        for (clogb2 = 0; depth > 0; clogb2 = clogb2 + 1)
            depth = depth >> 1;
    endfunction
endmodule
