module OpenRam1RW1R_25 (
    input clock,
    input csb0,
    input csb1,
    input web0,
    input [3:0] addr0,
    input [3:0] addr1,
    input [24:0] din0,
    output [24:0] dout0,
    output [24:0] dout1
);
    openram45_1rw1r_16x25
`ifndef SYNTHESIS
    #(.VERBOSE(0), .DELAY(0.1), .T_HOLD(0.1))
`endif
    ram (
        .clk0(clock),
        .clk1(clock),
        .csb0(csb0),
        .csb1(csb1),
        .web0(web0),
        .addr0(addr0),
        .addr1(addr1),
        .din0(din0),
        .dout0(dout0),
        .dout1(dout1)
    );
endmodule
