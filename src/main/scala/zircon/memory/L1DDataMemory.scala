package zircon.memory

import chisel3._
import chisel3.util._

/** Two-read, one-write line memory used by the executable L1D data array.
  *
  * Both read ports use the registered-address contract from the Zircon-2024
  * Xilinx RAM helpers. The L1D controller therefore retains request metadata
  * for one cycle before consuming a hit value.
  */
class L1DDataMemory(depth: Int, width: Int) extends BlackBox
    with HasBlackBoxInline {
  require(depth > 0 && (depth & (depth - 1)) == 0)
  require(width > 0 && width % 8 == 0)
  private val addressWidth = log2Ceil(depth)
  private val moduleName = s"L1DDataMemory_${depth}x${width}"
  override def desiredName: String = moduleName

  val io = IO(new Bundle {
    val clk = Input(Clock())
    val readEnableA = Input(Bool())
    val readAddressA = Input(UInt(addressWidth.W))
    val readDataA = Output(UInt(width.W))
    val readEnableB = Input(Bool())
    val readAddressB = Input(UInt(addressWidth.W))
    val readDataB = Output(UInt(width.W))
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
       |  input  wire                 readEnableA,
       |  input  wire [ADDR_WIDTH-1:0] readAddressA,
       |  output wire [WIDTH-1:0]      readDataA,
       |  input  wire                 readEnableB,
       |  input  wire [ADDR_WIDTH-1:0] readAddressB,
       |  output wire [WIDTH-1:0]      readDataB,
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
       |  assign readDataA = memory[readAddressA];
       |  assign readDataB = memory[readAddressB];
       |`else
       |  wire sbiterrA_unused;
       |  wire dbiterrA_unused;
       |  wire sbiterrB_unused;
       |  wire dbiterrB_unused;
       |  xpm_memory_tdpram #(
       |    .ADDR_WIDTH_A(ADDR_WIDTH),
       |    .ADDR_WIDTH_B(ADDR_WIDTH),
       |    .AUTO_SLEEP_TIME(0),
       |    .BYTE_WRITE_WIDTH_A(WIDTH),
       |    .BYTE_WRITE_WIDTH_B(WIDTH),
       |    .CASCADE_HEIGHT(0),
       |    .CLOCKING_MODE("common_clock"),
       |    .ECC_MODE("no_ecc"),
       |    .MEMORY_INIT_FILE("none"),
       |    .MEMORY_INIT_PARAM(""),
       |    .MEMORY_OPTIMIZATION("true"),
       |    .MEMORY_PRIMITIVE("block"),
       |    .MEMORY_SIZE(DEPTH * WIDTH),
       |    .MESSAGE_CONTROL(0),
       |    .READ_DATA_WIDTH_A(WIDTH),
       |    .READ_DATA_WIDTH_B(WIDTH),
       |    .READ_LATENCY_A(1),
       |    .READ_LATENCY_B(1),
       |    .READ_RESET_VALUE_A("0"),
       |    .READ_RESET_VALUE_B("0"),
       |    .RST_MODE_A("SYNC"),
       |    .RST_MODE_B("SYNC"),
       |    .SIM_ASSERT_CHK(0),
       |    .USE_EMBEDDED_CONSTRAINT(0),
       |    .USE_MEM_INIT(0),
       |    .WAKEUP_TIME("disable_sleep"),
       |    .WRITE_DATA_WIDTH_A(WIDTH),
       |    .WRITE_DATA_WIDTH_B(WIDTH),
       |    .WRITE_MODE_A("read_first"),
       |    .WRITE_MODE_B("read_first")
       |  ) memory (
       |    .addra(writeEnable ? writeAddress : readAddressA),
       |    .addrb(writeEnable ? writeAddress : readAddressB),
       |    .clka(clk),
       |    .clkb(clk),
       |    .dina(writeData),
       |    .dinb({WIDTH{1'b0}}),
       |    .douta(readDataA),
       |    .doutb(readDataB),
       |    .ena(writeEnable || readEnableA),
       |    .enb(writeEnable || readEnableB),
       |    .injectdbiterra(1'b0),
       |    .injectdbiterrb(1'b0),
       |    .injectsbiterra(1'b0),
       |    .injectsbiterrb(1'b0),
       |    .regcea(1'b1),
       |    .regceb(1'b1),
       |    .rsta(1'b0),
       |    .rstb(1'b0),
       |    .sleep(1'b0),
       |    .wea({WIDTH/8{writeEnable}}),
       |    .web({WIDTH/8{1'b0}}),
       |    .sbiterra(sbiterrA_unused),
       |    .dbiterra(dbiterrA_unused),
       |    .sbiterrb(sbiterrB_unused),
       |    .dbiterrb(dbiterrB_unused)
       |  );
       |`endif
       |endmodule
       |""".stripMargin)
}
