module ZirconSram1W1R #(
    parameter DEPTH = 256,
    parameter WIDTH = 32
) (
    input io_clock,
    input io_writeEnable,
    input [$clog2(DEPTH)-1:0] io_writeAddress,
    input [WIDTH-1:0] io_writeData,
    input io_readEnable,
    input [$clog2(DEPTH)-1:0] io_readAddress,
    output reg [WIDTH-1:0] io_readData
);
    (* ram_style = "block" *) reg [WIDTH-1:0] memory [0:DEPTH-1];
    always @(posedge io_clock) begin
        if (io_writeEnable) memory[io_writeAddress] <= io_writeData;
        if (io_readEnable) io_readData <= memory[io_readAddress];
    end
endmodule
