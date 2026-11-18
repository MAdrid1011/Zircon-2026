module ZirconSram1RW1R #(
    parameter DEPTH = 128,
    parameter LANES = 64,
    parameter LANE_BITS = 8,
    parameter WRITE_PORT = 0,
    parameter FULL_WRITE_ONLY = 0,
    parameter WRITE_CYCLE_READ_UNUSED = 0
) (
    input  logic [$clog2(DEPTH)-1:0] addra,
    input  logic [$clog2(DEPTH)-1:0] addrb,
    input  logic [$clog2(DEPTH)-1:0] writeAddress,
    input  logic [LANES*LANE_BITS-1:0] dina,
    input  logic [LANES*LANE_BITS-1:0] dinb,
    input  logic                         clka,
    input  logic [LANES-1:0]            wea,
    input  logic [LANES-1:0]            web,
    input  logic                         writeAllA,
    input  logic                         writeAllB,
    input  logic                         ena,
    input  logic                         enb,
    output logic [LANES*LANE_BITS-1:0] douta,
    output logic [LANES*LANE_BITS-1:0] doutb
);
    (* ram_style = "block" *) logic [LANES*LANE_BITS-1:0] memory [0:DEPTH-1];
    logic [$clog2(DEPTH)-1:0] readAddressA;
    logic [$clog2(DEPTH)-1:0] readAddressB;
    wire writeA = WRITE_PORT == 0 &&
        (FULL_WRITE_ONLY ? writeAllA : |wea);
    wire writeB = WRITE_PORT == 1 &&
        (FULL_WRITE_ONLY ? writeAllB : |web);

    always_ff @(posedge clka) begin
        if (ena && !(WRITE_CYCLE_READ_UNUSED && writeA)) readAddressA <= addra;
        if (enb && !(WRITE_CYCLE_READ_UNUSED && writeB)) readAddressB <= addrb;
    end

    generate
        if (FULL_WRITE_ONLY) begin : whole_word
            always_ff @(posedge clka) begin
                if (WRITE_PORT == 0 && ena && writeAllA)
                    memory[writeAddress] <= dina;
                if (WRITE_PORT == 1 && enb && writeAllB)
                    memory[writeAddress] <= dinb;
            end
        end else begin : masked
            always_ff @(posedge clka) begin
                if (WRITE_PORT == 0 && ena)
                    for (integer lane = 0; lane < LANES; lane = lane + 1)
                        if (wea[lane])
                            memory[writeAddress][lane*LANE_BITS +: LANE_BITS] <=
                                dina[lane*LANE_BITS +: LANE_BITS];
                if (WRITE_PORT == 1 && enb)
                    for (integer lane = 0; lane < LANES; lane = lane + 1)
                        if (web[lane])
                            memory[writeAddress][lane*LANE_BITS +: LANE_BITS] <=
                                dinb[lane*LANE_BITS +: LANE_BITS];
            end
        end
    endgenerate

    assign douta = memory[readAddressA];
    assign doutb = memory[readAddressB];
endmodule
