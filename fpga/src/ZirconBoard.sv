// Board-level Zircon integration for the verified xc7a200tfbg676-2L pinout.
//
// The local memory is an AXI4 BRAM slave, not a claim about an external DDR
// controller.  It gives the production core a legal, synthesizable endpoint
// for timing closure and for board bring-up while a board-specific external
// master contract is completed separately.
module ZirconBoard #(
  parameter string MEM_INIT_FILE = ""
) (
  input  wire        clk,
  input  wire        rstn,
  output wire [15:0] led
);

  wire        axi_aw_ready;
  wire        axi_aw_valid;
  wire [3:0]  axi_aw_id;
  wire [31:0] axi_aw_addr;
  wire [7:0]  axi_aw_len;
  wire [2:0]  axi_aw_size;
  wire [1:0]  axi_aw_burst;
  wire        axi_aw_lock;
  wire [3:0]  axi_aw_cache;
  wire [2:0]  axi_aw_prot;
  wire [3:0]  axi_aw_qos;
  wire        axi_w_ready;
  wire        axi_w_valid;
  wire [31:0] axi_w_data;
  wire [3:0]  axi_w_strb;
  wire        axi_w_last;
  wire        axi_b_ready;
  wire        axi_b_valid;
  wire [3:0]  axi_b_id;
  wire [1:0]  axi_b_resp;
  wire        axi_ar_ready;
  wire        axi_ar_valid;
  wire [3:0]  axi_ar_id;
  wire [31:0] axi_ar_addr;
  wire [7:0]  axi_ar_len;
  wire [2:0]  axi_ar_size;
  wire [1:0]  axi_ar_burst;
  wire        axi_ar_lock;
  wire [3:0]  axi_ar_cache;
  wire [2:0]  axi_ar_prot;
  wire [3:0]  axi_ar_qos;
  wire        axi_r_ready;
  wire        axi_r_valid;
  wire [3:0]  axi_r_id;
  wire [31:0] axi_r_data;
  wire [1:0]  axi_r_resp;
  wire        axi_r_last;

  // The recovered board sources expose no physical external-coherence
  // initiator.  Keep the production adapter present but idle, rather than
  // manufacturing a non-existent AXI or DDR pin contract.
  wire        modifier_ready;
  wire        authorized_valid;
  wire        authorized_kind;
  wire [31:0] authorized_line_address;

  ZirconPlatformCore core (
    .clock(clk),
    .reset(~rstn),
    .io_axi_aw_ready(axi_aw_ready),
    .io_axi_aw_valid(axi_aw_valid),
    .io_axi_aw_bits_id(axi_aw_id),
    .io_axi_aw_bits_addr(axi_aw_addr),
    .io_axi_aw_bits_len(axi_aw_len),
    .io_axi_aw_bits_size(axi_aw_size),
    .io_axi_aw_bits_burst(axi_aw_burst),
    .io_axi_aw_bits_lock(axi_aw_lock),
    .io_axi_aw_bits_cache(axi_aw_cache),
    .io_axi_aw_bits_prot(axi_aw_prot),
    .io_axi_aw_bits_qos(axi_aw_qos),
    .io_axi_w_ready(axi_w_ready),
    .io_axi_w_valid(axi_w_valid),
    .io_axi_w_bits_data(axi_w_data),
    .io_axi_w_bits_strb(axi_w_strb),
    .io_axi_w_bits_last(axi_w_last),
    .io_axi_b_ready(axi_b_ready),
    .io_axi_b_valid(axi_b_valid),
    .io_axi_b_bits_id(axi_b_id),
    .io_axi_b_bits_resp(axi_b_resp),
    .io_axi_ar_ready(axi_ar_ready),
    .io_axi_ar_valid(axi_ar_valid),
    .io_axi_ar_bits_id(axi_ar_id),
    .io_axi_ar_bits_addr(axi_ar_addr),
    .io_axi_ar_bits_len(axi_ar_len),
    .io_axi_ar_bits_size(axi_ar_size),
    .io_axi_ar_bits_burst(axi_ar_burst),
    .io_axi_ar_bits_lock(axi_ar_lock),
    .io_axi_ar_bits_cache(axi_ar_cache),
    .io_axi_ar_bits_prot(axi_ar_prot),
    .io_axi_ar_bits_qos(axi_ar_qos),
    .io_axi_r_ready(axi_r_ready),
    .io_axi_r_valid(axi_r_valid),
    .io_axi_r_bits_id(axi_r_id),
    .io_axi_r_bits_data(axi_r_data),
    .io_axi_r_bits_resp(axi_r_resp),
    .io_axi_r_bits_last(axi_r_last),
    .io_interrupts_meip(1'b0),
    .io_interrupts_msip(1'b0),
    .io_interrupts_mtip(1'b0),
    .io_modifier_ready(modifier_ready),
    .io_modifier_valid(1'b0),
    .io_modifier_bits_kind(1'b0),
    .io_modifier_bits_lineAddress(32'b0),
    .io_authorized_ready(1'b1),
    .io_authorized_valid(authorized_valid),
    .io_authorized_bits_kind(authorized_kind),
    .io_authorized_bits_lineAddress(authorized_line_address)
  );

  ZirconAxiBram #(.MEM_INIT_FILE(MEM_INIT_FILE)) memory (
    .clk(clk),
    .reset(~rstn),
    .s_axi_awid(axi_aw_id),
    .s_axi_awaddr(axi_aw_addr),
    .s_axi_awlen(axi_aw_len),
    .s_axi_awsize(axi_aw_size),
    .s_axi_awburst(axi_aw_burst),
    .s_axi_awvalid(axi_aw_valid),
    .s_axi_awready(axi_aw_ready),
    .s_axi_wdata(axi_w_data),
    .s_axi_wstrb(axi_w_strb),
    .s_axi_wlast(axi_w_last),
    .s_axi_wvalid(axi_w_valid),
    .s_axi_wready(axi_w_ready),
    .s_axi_bid(axi_b_id),
    .s_axi_bresp(axi_b_resp),
    .s_axi_bvalid(axi_b_valid),
    .s_axi_bready(axi_b_ready),
    .s_axi_arid(axi_ar_id),
    .s_axi_araddr(axi_ar_addr),
    .s_axi_arlen(axi_ar_len),
    .s_axi_arsize(axi_ar_size),
    .s_axi_arburst(axi_ar_burst),
    .s_axi_arvalid(axi_ar_valid),
    .s_axi_arready(axi_ar_ready),
    .s_axi_rid(axi_r_id),
    .s_axi_rdata(axi_r_data),
    .s_axi_rresp(axi_r_resp),
    .s_axi_rlast(axi_r_last),
    .s_axi_rvalid(axi_r_valid),
    .s_axi_rready(axi_r_ready)
  );

  // Keep a visible board-health indicator and the active fetch address.  The
  // heartbeat proves that the constrained fabric clock is present even before
  // a program image is loaded into the local BRAM.
  reg [25:0] heartbeat;
  always @(posedge clk) begin
    if (!rstn)
      heartbeat <= 26'b0;
    else
      heartbeat <= heartbeat + 1'b1;
  end
  assign led = {axi_ar_addr[31:17], heartbeat[25]};
endmodule

module ZirconAxiBram #(
  parameter string MEM_INIT_FILE = ""
) (
  input  wire        clk,
  input  wire        reset,
  input  wire [3:0]  s_axi_awid,
  input  wire [31:0] s_axi_awaddr,
  input  wire [7:0]  s_axi_awlen,
  input  wire [2:0]  s_axi_awsize,
  input  wire [1:0]  s_axi_awburst,
  input  wire        s_axi_awvalid,
  output wire        s_axi_awready,
  input  wire [31:0] s_axi_wdata,
  input  wire [3:0]  s_axi_wstrb,
  input  wire        s_axi_wlast,
  input  wire        s_axi_wvalid,
  output wire        s_axi_wready,
  output wire [3:0]  s_axi_bid,
  output wire [1:0]  s_axi_bresp,
  output wire        s_axi_bvalid,
  input  wire        s_axi_bready,
  input  wire [3:0]  s_axi_arid,
  input  wire [31:0] s_axi_araddr,
  input  wire [7:0]  s_axi_arlen,
  input  wire [2:0]  s_axi_arsize,
  input  wire [1:0]  s_axi_arburst,
  input  wire        s_axi_arvalid,
  output wire        s_axi_arready,
  output wire [3:0]  s_axi_rid,
  output wire [31:0] s_axi_rdata,
  output wire [1:0]  s_axi_rresp,
  output wire        s_axi_rlast,
  output wire        s_axi_rvalid,
  input  wire        s_axi_rready
);
  reg        read_active;
  reg [3:0]  read_id;
  reg [31:0] read_address;
  reg [7:0]  read_remaining;
  reg        read_valid;
  reg        read_last;
  reg        write_address_valid;
  reg [3:0]  write_id;
  reg [31:0] write_address;
  reg        write_response_valid;

  // Keep the 64 Kiword image in true block RAM.  The previous inferred array
  // used a byte-enable loop in the same process as the read path; Vivado
  // dissolved that array into LUTRAM (and then registers) because it saw
  // multiple writes.  XPM exposes one write port and one synchronous read
  // port explicitly, preserving the AXI protocol while making the intended
  // RAMB36 implementation unambiguous.
  localparam string XPM_MEMORY_INIT_FILE =
    (MEM_INIT_FILE == "") ? "none" : MEM_INIT_FILE;
  wire [31:0] bram_read_data;
  wire        bram_sbiterr;
  wire        bram_dbiterr;
  wire        bram_read_issue;
  wire        write_fire;

  assign bram_read_issue = read_active &&
    (!read_valid || (s_axi_rready && (read_remaining != 8'b0)));
  assign write_fire = s_axi_wvalid && s_axi_wready;

  xpm_memory_sdpram #(
    .ADDR_WIDTH_A(16),
    .ADDR_WIDTH_B(16),
    .AUTO_SLEEP_TIME(0),
    .BYTE_WRITE_WIDTH_A(8),
    .CASCADE_HEIGHT(0),
    .CLOCKING_MODE("common_clock"),
    .ECC_MODE("no_ecc"),
    .MEMORY_INIT_FILE(XPM_MEMORY_INIT_FILE),
    .MEMORY_INIT_PARAM(""),
    .MEMORY_OPTIMIZATION("true"),
    .MEMORY_PRIMITIVE("block"),
    .MEMORY_SIZE(2097152),
    .MESSAGE_CONTROL(0),
    .READ_DATA_WIDTH_B(32),
    .READ_LATENCY_B(1),
    .READ_RESET_VALUE_B("0"),
    .RST_MODE_A("SYNC"),
    .RST_MODE_B("SYNC"),
    .SIM_ASSERT_CHK(0),
    .USE_EMBEDDED_CONSTRAINT(0),
    .USE_MEM_INIT(1),
    .WAKEUP_TIME("disable_sleep"),
    .WRITE_DATA_WIDTH_A(32),
    .WRITE_MODE_B("read_first")
  ) memory (
    .addra(write_address[17:2]),
    .addrb(read_address[17:2]),
    .clka(clk),
    .clkb(clk),
    .dina(s_axi_wdata),
    .doutb(bram_read_data),
    .sbiterrb(bram_sbiterr),
    .dbiterrb(bram_dbiterr),
    .ena(write_fire),
    .enb(bram_read_issue),
    .injectdbiterra(1'b0),
    .injectsbiterra(1'b0),
    .regceb(1'b1),
    .rstb(reset),
    .sleep(1'b0),
    .wea(write_fire ? s_axi_wstrb : 4'b0)
  );

  assign s_axi_arready = !read_active;
  assign s_axi_rvalid = read_valid;
  assign s_axi_rid = read_id;
  assign s_axi_rdata = bram_read_data;
  assign s_axi_rresp = 2'b00;
  assign s_axi_rlast = read_last;

  assign s_axi_awready = !write_address_valid && !write_response_valid;
  assign s_axi_wready = write_address_valid && !write_response_valid;
  assign s_axi_bid = write_id;
  assign s_axi_bresp = 2'b00;
  assign s_axi_bvalid = write_response_valid;

  always @(posedge clk) begin
    if (reset) begin
      read_active <= 1'b0;
      read_id <= 4'b0;
      read_address <= 32'b0;
      read_remaining <= 8'b0;
      read_valid <= 1'b0;
      read_last <= 1'b0;
      write_address_valid <= 1'b0;
      write_id <= 4'b0;
      write_address <= 32'b0;
      write_response_valid <= 1'b0;
    end else begin
      if (s_axi_arvalid && s_axi_arready) begin
        read_active <= 1'b1;
        read_id <= s_axi_arid;
        read_address <= s_axi_araddr;
        read_remaining <= s_axi_arlen;
        read_valid <= 1'b0;
        read_last <= 1'b0;
      end else if (read_active) begin
        if (read_valid && s_axi_rready) begin
          if (read_remaining == 8'b0) begin
            read_active <= 1'b0;
            read_valid <= 1'b0;
            read_last <= 1'b0;
          end else begin
            // A ready handshake on the current beat launches the next
            // synchronous BRAM read in the same cycle.
            read_address <= read_address + 32'd4;
            read_remaining <= read_remaining - 1'b1;
            read_valid <= 1'b1;
            read_last <= read_remaining == 8'd1;
          end
        end else if (!read_valid) begin
          // The first beat is issued one cycle after AR, matching the XPM
          // synchronous read latency.
          read_address <= read_address + 32'd4;
          read_valid <= 1'b1;
          read_last <= read_remaining == 8'b0;
        end
      end

      if (s_axi_awvalid && s_axi_awready) begin
        write_address_valid <= 1'b1;
        write_id <= s_axi_awid;
        write_address <= s_axi_awaddr;
      end

      if (write_fire) begin
        write_address <= write_address + 32'd4;
        if (s_axi_wlast) begin
          write_address_valid <= 1'b0;
          write_response_valid <= 1'b1;
        end
      end

      if (write_response_valid && s_axi_bready)
        write_response_valid <= 1'b0;
    end
  end
endmodule
