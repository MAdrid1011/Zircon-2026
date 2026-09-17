import chisel3._
import chisel3.util._

/** FreePDK45 macro binding. Simulation delays express edge ordering, not physical timing. */
class OpenRam1RW1R(width: Int) extends ExtModule {
    require(width == 25 || width == 32)
    override def desiredName = s"OpenRam1RW1R_$width"
    val io = FlatIO(new Bundle {
        val clock = Input(Clock())
        val csb0 = Input(Bool())
        val csb1 = Input(Bool())
        val web0 = Input(Bool())
        val addr0 = Input(UInt(4.W))
        val addr1 = Input(UInt(4.W))
        val din0 = Input(UInt(width.W))
        val wmask0 = Input(UInt((if (width == 25) 1 else 4).W))
        val dout0 = Output(UInt(width.W))
        val dout1 = Output(UInt(width.W))
    })
    addPath(s"eda/platforms/nangate45/memory/openram-1rw1r/openram45_1rw1r_16x$width.v")
    setInline(
        s"$desiredName.sv",
        s"""module $desiredName (
    input clock, csb0, csb1, web0,
    input [3:0] addr0, addr1,
    input [${width - 1}:0] din0,
    input [${if (width == 25) 0 else 3}:0] wmask0,
    output [${width - 1}:0] dout0, dout1
);
    openram45_1rw1r_16x$width
`ifndef SYNTHESIS
    #(.VERBOSE(0), .DELAY(0.1), .T_HOLD(0.1))
`endif
    ram (
        .clk0(clock), .clk1(clock), .csb0(csb0), .csb1(csb1), .web0(web0),
        .addr0(addr0), .addr1(addr1), .din0(din0),
        ${if (width == 32) ".wmask0(wmask0)," else ""}
        .dout0(dout0), .dout1(dout1)
    );
endmodule
"""
    )
}
