module BsgFakeram1RW1R_32 (
    input clock,
    input csb0,
    input csb1,
    input web0,
    input [3:0] addr0,
    input [3:0] addr1,
    input [31:0] din0,
    input [3:0] wmask0,
    output [31:0] dout0,
    output [31:0] dout1
);
    wire csb0_buffered;
    wire csb1_buffered;
    wire web0_buffered;
    wire [3:0] addr0_buffered;
    wire [3:0] addr1_buffered;

    BUF_X1 csb0_buffer (.A(csb0), .Z(csb0_buffered));
    BUF_X1 csb1_buffer (.A(csb1), .Z(csb1_buffered));
    BUF_X1 web0_buffer (.A(web0), .Z(web0_buffered));
    for (genvar bit_index = 0; bit_index < 4; bit_index = bit_index + 1) begin : address_buffers
        BUF_X1 addr0_buffer (.A(addr0[bit_index]), .Z(addr0_buffered[bit_index]));
        BUF_X1 addr1_buffer (.A(addr1[bit_index]), .Z(addr1_buffered[bit_index]));
    end

    fakeram45_1rw1r_16x32 ram (
        .clock(clock),
        .csb0(csb0_buffered),
        .csb1(csb1_buffered),
        .web0(web0_buffered),
        .addr0(addr0_buffered),
        .addr1(addr1_buffered),
        .din0(din0),
        .wmask0(wmask0),
        .dout0(dout0),
        .dout1(dout1)
    );
endmodule
