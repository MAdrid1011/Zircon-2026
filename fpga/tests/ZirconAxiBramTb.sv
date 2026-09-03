`timescale 1ns/1ps

module ZirconAxiBramTb;
  reg clk = 1'b0;
  reg reset = 1'b1;
  reg [3:0] s_axi_awid = 4'b0;
  reg [31:0] s_axi_awaddr = 32'b0;
  reg [7:0] s_axi_awlen = 8'b0;
  reg [2:0] s_axi_awsize = 3'b010;
  reg [1:0] s_axi_awburst = 2'b01;
  reg s_axi_awvalid = 1'b0;
  wire s_axi_awready;
  reg [31:0] s_axi_wdata = 32'b0;
  reg [3:0] s_axi_wstrb = 4'b0;
  reg s_axi_wlast = 1'b0;
  reg s_axi_wvalid = 1'b0;
  wire s_axi_wready;
  wire [3:0] s_axi_bid;
  wire [1:0] s_axi_bresp;
  wire s_axi_bvalid;
  reg s_axi_bready = 1'b0;
  reg [3:0] s_axi_arid = 4'b0;
  reg [31:0] s_axi_araddr = 32'b0;
  reg [7:0] s_axi_arlen = 8'b0;
  reg [2:0] s_axi_arsize = 3'b010;
  reg [1:0] s_axi_arburst = 2'b01;
  reg s_axi_arvalid = 1'b0;
  wire s_axi_arready;
  wire [3:0] s_axi_rid;
  wire [31:0] s_axi_rdata;
  wire [1:0] s_axi_rresp;
  wire s_axi_rlast;
  wire s_axi_rvalid;
  reg s_axi_rready = 1'b0;

  always #5 clk = ~clk;

  ZirconAxiBram #(.MEM_INIT_FILE("none")) dut (
    .clk(clk), .reset(reset),
    .s_axi_awid(s_axi_awid), .s_axi_awaddr(s_axi_awaddr),
    .s_axi_awlen(s_axi_awlen), .s_axi_awsize(s_axi_awsize),
    .s_axi_awburst(s_axi_awburst), .s_axi_awvalid(s_axi_awvalid),
    .s_axi_awready(s_axi_awready), .s_axi_wdata(s_axi_wdata),
    .s_axi_wstrb(s_axi_wstrb), .s_axi_wlast(s_axi_wlast),
    .s_axi_wvalid(s_axi_wvalid), .s_axi_wready(s_axi_wready),
    .s_axi_bid(s_axi_bid), .s_axi_bresp(s_axi_bresp),
    .s_axi_bvalid(s_axi_bvalid), .s_axi_bready(s_axi_bready),
    .s_axi_arid(s_axi_arid), .s_axi_araddr(s_axi_araddr),
    .s_axi_arlen(s_axi_arlen), .s_axi_arsize(s_axi_arsize),
    .s_axi_arburst(s_axi_arburst), .s_axi_arvalid(s_axi_arvalid),
    .s_axi_arready(s_axi_arready), .s_axi_rid(s_axi_rid),
    .s_axi_rdata(s_axi_rdata), .s_axi_rresp(s_axi_rresp),
    .s_axi_rlast(s_axi_rlast), .s_axi_rvalid(s_axi_rvalid),
    .s_axi_rready(s_axi_rready)
  );

  task automatic send_aw(input [3:0] id, input [31:0] address);
    begin
      @(negedge clk);
      s_axi_awid = id;
      s_axi_awaddr = address;
      s_axi_awvalid = 1'b1;
      while (!s_axi_awready) @(posedge clk);
      @(posedge clk);
      @(negedge clk);
      s_axi_awvalid = 1'b0;
    end
  endtask

  task automatic send_w(input [31:0] data, input [3:0] strb, input last);
    begin
      @(negedge clk);
      s_axi_wdata = data;
      s_axi_wstrb = strb;
      s_axi_wlast = last;
      s_axi_wvalid = 1'b1;
      while (!s_axi_wready) @(posedge clk);
      @(posedge clk);
      @(negedge clk);
      s_axi_wvalid = 1'b0;
      s_axi_wlast = 1'b0;
    end
  endtask

  task automatic expect_b(input [3:0] id);
    begin
      while (!s_axi_bvalid) begin
        @(posedge clk);
        #1;
      end
      if (s_axi_bid !== id || s_axi_bresp !== 2'b00)
        $fatal(1, "unexpected B response id=%h resp=%h", s_axi_bid, s_axi_bresp);
      @(negedge clk);
      s_axi_bready = 1'b1;
      @(posedge clk);
      @(negedge clk);
      s_axi_bready = 1'b0;
    end
  endtask

  task automatic send_ar(input [3:0] id, input [31:0] address, input [7:0] len);
    begin
      @(negedge clk);
      s_axi_arid = id;
      s_axi_araddr = address;
      s_axi_arlen = len;
      s_axi_arvalid = 1'b1;
      while (!s_axi_arready) @(posedge clk);
      @(posedge clk);
      @(negedge clk);
      s_axi_arvalid = 1'b0;
    end
  endtask

  task automatic expect_r(input [3:0] id, input [31:0] data, input last);
    begin
      while (!s_axi_rvalid) begin
        @(posedge clk);
        #1;
      end
      if (s_axi_rid !== id || s_axi_rdata !== data || s_axi_rresp !== 2'b00 ||
          s_axi_rlast !== last)
        $fatal(1, "unexpected R id=%h data=%h resp=%h last=%b",
          s_axi_rid, s_axi_rdata, s_axi_rresp, s_axi_rlast);
    end
  endtask

  initial begin
    repeat (3) @(posedge clk);
    @(negedge clk);
    reset = 1'b0;

    send_aw(4'h5, 32'h8000_0040);
    send_w(32'hdead_beef, 4'b1111, 1'b0);
    send_w(32'h1122_3344, 4'b1111, 1'b1);
    expect_b(4'h5);

    send_ar(4'h3, 32'h8000_0040, 8'd1);
    expect_r(4'h3, 32'hdead_beef, 1'b0);
    repeat (2) begin
      @(posedge clk);
      #1;
      if (!s_axi_rvalid || s_axi_rid !== 4'h3 || s_axi_rdata !== 32'hdead_beef ||
          s_axi_rlast)
        $fatal(1, "R payload changed while RREADY was low");
    end
    @(negedge clk);
    s_axi_rready = 1'b1;
    @(posedge clk);
    #1;
    if (!s_axi_rvalid || s_axi_rid !== 4'h3 || s_axi_rdata !== 32'h1122_3344 ||
        !s_axi_rlast)
      $fatal(1, "second R beat was not retained after first-beat handshake");
    @(posedge clk);
    #1;
    if (s_axi_rvalid)
      $fatal(1, "RVALID remained set after the final read handshake");
    @(negedge clk);
    s_axi_rready = 1'b0;

    send_aw(4'h6, 32'h8000_0040);
    send_w(32'haaaa_bbbb, 4'b0011, 1'b1);
    expect_b(4'h6);
    send_ar(4'h7, 32'h8000_0040, 8'd0);
    expect_r(4'h7, 32'hdead_bbbb, 1'b1);
    @(negedge clk);
    s_axi_rready = 1'b1;
    @(posedge clk);
    #1;
    if (s_axi_rvalid)
      $fatal(1, "single-beat RVALID remained set after handshake");

    // ARLEN=255 is the maximum AXI burst and must represent 256 beats rather
    // than wrapping an eight-bit internal counter back to zero.
    send_ar(4'h8, 32'h8000_0000, 8'hff);
    for (integer beat = 0; beat < 256; beat = beat + 1) begin
      while (!s_axi_rvalid) begin
        @(posedge clk);
        #1;
      end
      if (s_axi_rid !== 4'h8 ||
          s_axi_rdata !== ((beat == 16) ? 32'hdead_bbbb :
            ((beat == 17) ? 32'h1122_3344 : 32'b0)) ||
          s_axi_rresp !== 2'b00 || s_axi_rlast !== (beat == 255))
        $fatal(1, "unexpected maximum-burst beat %0d id=%h data=%h last=%b",
          beat, s_axi_rid, s_axi_rdata, s_axi_rlast);
      @(posedge clk);
      #1;
    end
    if (s_axi_rvalid)
      $fatal(1, "RVALID remained set after maximum burst");

    $display("ZirconAxiBramTb PASS");
    $finish;
  end
endmodule
