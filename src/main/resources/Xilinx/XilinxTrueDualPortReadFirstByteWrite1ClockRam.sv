module XilinxTrueDualPortReadFirstByteWrite1ClockRam #(
    parameter NBCOL = 4,
    parameter COLWIDTH = 9,
    parameter RAMDEPTH = 1024
) (
    input [clogb2(RAMDEPTH-1)-1:0] addra,
    input [clogb2(RAMDEPTH-1)-1:0] addrb,
    input [(NBCOL*COLWIDTH)-1:0] dina,
    input [(NBCOL*COLWIDTH)-1:0] dinb,
    input clka,
    input [NBCOL-1:0] wea,
    input [NBCOL-1:0] web,
    input ena,
    input enb,
    output [(NBCOL*COLWIDTH)-1:0] douta,
    output [(NBCOL*COLWIDTH)-1:0] doutb
);
    (* ram_style = "block" *)
    reg [(NBCOL*COLWIDTH)-1:0] BRAM [RAMDEPTH-1:0];
    reg [$clog2(RAMDEPTH)-1:0] addrRA;
    reg [$clog2(RAMDEPTH)-1:0] addrRB;
    integer ramIndex;

    initial begin
        for (ramIndex = 0; ramIndex < RAMDEPTH; ramIndex = ramIndex + 1)
            BRAM[ramIndex] = {(NBCOL*COLWIDTH){1'b0}};
    end

    always @(posedge clka) begin
        if (ena)
            addrRA <= addra;
        if (enb)
            addrRB <= addrb;
    end

    genvar i;
    generate
        for (i = 0; i < NBCOL; i = i + 1) begin : byteWrite
            always @(posedge clka) begin
                if (ena && wea[i])
                    BRAM[addra][(i+1)*COLWIDTH-1:i*COLWIDTH] <= dina[(i+1)*COLWIDTH-1:i*COLWIDTH];
                if (enb && web[i])
                    BRAM[addrb][(i+1)*COLWIDTH-1:i*COLWIDTH] <= dinb[(i+1)*COLWIDTH-1:i*COLWIDTH];
            end
        end
    endgenerate

    assign douta = BRAM[addrRA];
    assign doutb = BRAM[addrRB];

    function integer clogb2;
        input integer depth;
        for (clogb2 = 0; depth > 0; clogb2 = clogb2 + 1)
            depth = depth >> 1;
    endfunction
endmodule
