# Reproducible post-route flow for the sole Zircon FPGA target.
if {$argc != 1} {
  puts stderr "usage: vivado -mode batch -source fpga/vivado.tcl -tclargs <run-directory>"
  exit 2
}

set target_part xc7a200tfbg676-2L
set run_dir [file normalize [lindex $argv 0]]
set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ..]]
set generated_dir [file join $repo_root generated-platform]
set wrapper [file join $repo_root fpga src ZirconBoard.sv]
set constraints [file join $repo_root fpga constraints zircon_board.xdc]

if {![file exists [file join $generated_dir ZirconPlatformCore.sv]]} {
  puts stderr "generated-platform/ZirconPlatformCore.sv is missing; run make platform-verilog first"
  exit 2
}

file mkdir $run_dir
create_project -in_memory -part $target_part
set_param general.maxThreads 8
foreach source [glob -nocomplain [file join $generated_dir *.sv]] {
  read_verilog -sv $source
}
read_verilog -sv $wrapper
read_xdc $constraints

if {[info exists ::env(FPGA_PARSE_ONLY)] && $::env(FPGA_PARSE_ONLY) eq "1"} {
  puts "Zircon FPGA RTL parse-only run completed: $target_part"
  exit 0
}

synth_design -top ZirconBoard -part $target_part -directive AreaOptimized_high
write_checkpoint -force [file join $run_dir zircon_board_synth.dcp]
report_utilization -file [file join $run_dir utilization_synth.rpt]

# Keep a fast, reproducible synthesis checkpoint for structural area work.
# Full implementation remains the release path below; this mode never emits
# timing or bitstream evidence.
if {[info exists ::env(FPGA_SYNTH_ONLY)] && $::env(FPGA_SYNTH_ONLY) eq "1"} {
  puts "Zircon FPGA synthesis-only run completed: $run_dir"
  exit 0
}

opt_design
write_checkpoint -force [file join $run_dir zircon_board_opt.dcp]
place_design
write_checkpoint -force [file join $run_dir zircon_board_place.dcp]
phys_opt_design
route_design
phys_opt_design

report_timing_summary -delay_type min_max -report_unconstrained -check_timing_verbose \
  -max_paths 20 -file [file join $run_dir timing_summary.rpt]
report_utilization -file [file join $run_dir utilization.rpt]
report_clock_utilization -file [file join $run_dir clock_utilization.rpt]
report_drc -file [file join $run_dir drc.rpt]
write_checkpoint -force [file join $run_dir zircon_board_routed.dcp]
write_bitstream -force [file join $run_dir zircon_board.bit]
puts "Zircon FPGA implementation completed: $run_dir"
