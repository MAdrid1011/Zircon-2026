package zircon.frontend

import chisel3._
import chisel3.util.{HasBlackBoxInline, log2Ceil}

/**
  * Asynchronous-read BTB storage.  The functional contract intentionally
  * matches Chisel Mem: writes occur on the clock edge while reads remain
  * combinational, so prediction latency is unchanged.  Vivado maps the
  * production branch to distributed RAM instead of expanding each tiny BTB
  * bank into a wide register/mux network.
  */
class BranchTargetMemory(depth: Int, width: Int) extends BlackBox
    with HasBlackBoxInline {
  require(depth > 0 && (depth & (depth - 1)) == 0)
  require(width > 0 && width % 8 == 0)

  private val addressWidth = log2Ceil(depth)
  private val moduleName = s"BranchTargetMemory_${depth}x${width}"
  override def desiredName: String = moduleName

  val io = IO(new Bundle {
    val clk = Input(Clock())
    val readAddress = Input(UInt(addressWidth.W))
    val readData = Output(UInt(width.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val writeData = Input(UInt(width.W))
  })

  setInline(s"$moduleName.sv",
    s"""module $moduleName #(
       |  parameter integer DEPTH = $depth,
       |  parameter integer WIDTH = $width,
       |  parameter integer ADDR_WIDTH = $addressWidth
       |) (
       |  input  wire                 clk,
       |  input  wire [ADDR_WIDTH-1:0] readAddress,
       |  output wire [WIDTH-1:0]      readData,
       |  input  wire                 writeEnable,
       |  input  wire [ADDR_WIDTH-1:0] writeAddress,
       |  input  wire [WIDTH-1:0]      writeData
       |);
       |
       |`ifndef SYNTHESIS
       |  reg [WIDTH-1:0] memory [0:DEPTH-1];
       |  integer initIndex;
       |  initial begin
       |    for (initIndex = 0; initIndex < DEPTH; initIndex = initIndex + 1)
       |      memory[initIndex] = {WIDTH{1'b0}};
       |  end
       |  always @(posedge clk) begin
       |    if (writeEnable)
       |      memory[writeAddress] <= writeData;
       |  end
       |  assign readData = memory[readAddress];
       |`else
       |  wire sbiterr_unused;
       |  wire dbiterr_unused;
       |  xpm_memory_sdpram #(
       |    .ADDR_WIDTH_A(ADDR_WIDTH),
       |    .ADDR_WIDTH_B(ADDR_WIDTH),
       |    .AUTO_SLEEP_TIME(0),
       |    .BYTE_WRITE_WIDTH_A(WIDTH),
       |    .CASCADE_HEIGHT(0),
       |    .CLOCKING_MODE("common_clock"),
       |    .ECC_MODE("no_ecc"),
       |    .MEMORY_INIT_FILE("none"),
       |    .MEMORY_INIT_PARAM(""),
       |    .MEMORY_OPTIMIZATION("true"),
       |    .MEMORY_PRIMITIVE("distributed"),
       |    .MEMORY_SIZE(DEPTH * WIDTH),
       |    .MESSAGE_CONTROL(0),
       |    .READ_DATA_WIDTH_B(WIDTH),
       |    .READ_LATENCY_B(0),
       |    .READ_RESET_VALUE_B("0"),
       |    .RST_MODE_A("SYNC"),
       |    .RST_MODE_B("SYNC"),
       |    .SIM_ASSERT_CHK(0),
       |    .USE_EMBEDDED_CONSTRAINT(0),
       |    .USE_MEM_INIT(0),
       |    .WAKEUP_TIME("disable_sleep"),
       |    .WRITE_DATA_WIDTH_A(WIDTH),
       |    .WRITE_MODE_B("read_first")
       |  ) memory (
       |    .addra(writeAddress),
       |    .addrb(readAddress),
       |    .clka(clk),
       |    .clkb(clk),
       |    .dina(writeData),
       |    .doutb(readData),
       |    .sbiterrb(sbiterr_unused),
       |    .dbiterrb(dbiterr_unused),
       |    .ena(writeEnable),
       |    .enb(1'b1),
       |    .injectdbiterra(1'b0),
       |    .injectsbiterra(1'b0),
       |    .regceb(1'b1),
       |    .rstb(1'b0),
       |    .sleep(1'b0),
       |    .wea(writeEnable)
       |  );
       |`endif
       |endmodule
       |""".stripMargin)
}
