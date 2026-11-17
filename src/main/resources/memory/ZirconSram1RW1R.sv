module ZirconSram1RW1R #(
    parameter DEPTH = 128,
    parameter LANES = 64,
    parameter LANE_BITS = 8,
    parameter WRITE_PORT = 0
) (
    input  logic [$clog2(DEPTH)-1:0] addra,
    input  logic [$clog2(DEPTH)-1:0] addrb,
    input  logic [LANES*LANE_BITS-1:0] dina,
    input  logic [LANES*LANE_BITS-1:0] dinb,
    input  logic                         clka,
    input  logic [LANES-1:0]            wea,
    input  logic [LANES-1:0]            web,
    input  logic                         ena,
    input  logic                         enb,
    output logic [LANES*LANE_BITS-1:0] douta,
    output logic [LANES*LANE_BITS-1:0] doutb
);
    (* ram_style = "block" *) logic [LANES*LANE_BITS-1:0] memory [0:DEPTH-1];
    logic [$clog2(DEPTH)-1:0] readAddressA;
    logic [$clog2(DEPTH)-1:0] readAddressB;

    always_ff @(posedge clka) begin
        if (ena) readAddressA <= addra;
        if (enb) readAddressB <= addrb;
        if (WRITE_PORT == 0) begin
            if (ena)
                for (integer lane = 0; lane < LANES; lane = lane + 1)
                    if (wea[lane])
                        memory[addra][lane*LANE_BITS +: LANE_BITS] <=
                            dina[lane*LANE_BITS +: LANE_BITS];
        end else begin
            if (enb)
                for (integer lane = 0; lane < LANES; lane = lane + 1)
                    if (web[lane])
                        memory[addrb][lane*LANE_BITS +: LANE_BITS] <=
                            dinb[lane*LANE_BITS +: LANE_BITS];
        end
    end

    assign douta = memory[readAddressA];
    assign doutb = memory[readAddressB];
endmodule
