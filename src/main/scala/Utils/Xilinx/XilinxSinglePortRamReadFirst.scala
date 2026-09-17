import chisel3._
import chisel3.util._

/** Zircon-2024 registered-address BRAM. The legacy name is kept; writes are visible after the edge. */
class XilinxSinglePortRamReadFirst(RAMWIDTH: Int, RAMDEPTH: Int)
    extends ExtModule(Map("RAMWIDTH" -> RAMWIDTH, "RAMDEPTH" -> RAMDEPTH)) {
    require(RAMWIDTH > 0 && RAMDEPTH >= 1 && isPow2(RAMDEPTH))
    val io = FlatIO(new Bundle {
        val addra = Input(UInt(math.max(1, log2Ceil(RAMDEPTH)).W))
        val dina = Input(UInt(RAMWIDTH.W))
        val clka = Input(Clock())
        val wea = Input(Bool())
        val ena = Input(Bool())
        val douta = Output(UInt(RAMWIDTH.W))
    })

    // Preserve the registered-address template used by the Zircon-2024 ICache.
    setInline(
        "XilinxSinglePortRamReadFirst.sv",
        """module XilinxSinglePortRamReadFirst #(
    parameter RAMWIDTH = 18,
    parameter RAMDEPTH = 1024,
    parameter ADDRWIDTH = (RAMDEPTH > 1) ? $clog2(RAMDEPTH) : 1
) (
    input [ADDRWIDTH-1:0] addra,
    input [RAMWIDTH-1:0] dina,
    input clka,
    input wea,
    input ena,
    output [RAMWIDTH-1:0] douta
);
    (* ram_style = "block" *)
    reg [RAMWIDTH-1:0] BRAM [RAMDEPTH-1:0];
    reg [ADDRWIDTH-1:0] addrR;

    // FPGA configuration initialization, not a runtime array reset.
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
endmodule
"""
    )
}
