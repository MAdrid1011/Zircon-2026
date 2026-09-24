set netlist {@NETLIST@}
set top {@TOP@}
set period_ns @PERIOD_NS@
set site {@SITE@}
set placement_density @DENSITY@
set macro_halo_width @HALO_WIDTH_UM@
set macro_halo_height @HALO_HEIGHT_UM@
set driving_cell {@DRIVING_CELL@}
set output_load_ff @LOAD_FF@
set input_arrival_ns @INPUT_ARRIVAL_NS@
set output_delay_ns @OUTPUT_DELAY_NS@
set platform_lefs {@PLATFORM_LEFS@}
set liberty_files {@LIBERTY_FILES@}
set platform_dir {@PLATFORM_DIR@}
set track_script {@TRACK_SCRIPT@}
set rc_script {@RC_SCRIPT@}
set output_dir {@OUTPUT_DIR@}
set run_setup_repair @RUN_SETUP_REPAIR@
set slew_margin @SLEW_MARGIN@
set max_repairs_per_pass @MAX_REPAIRS_PER_PASS@
file mkdir $output_dir
file mkdir [file join $output_dir macro-placement]

foreach lef $platform_lefs {
  read_lef $lef
}
foreach liberty $liberty_files {
  read_liberty $liberty
}
read_verilog $netlist
link_design $top

create_clock -name core_clock -period $period_ns [get_ports clock]
set_clock_transition 0.05 [get_clocks core_clock]
set_input_delay $input_arrival_ns -clock core_clock [all_inputs -no_clocks]
set_output_delay $output_delay_ns -clock core_clock [all_outputs]
set_driving_cell -lib_cell $driving_cell [all_inputs -no_clocks]
set_load $output_load_ff [all_outputs]

initialize_floorplan -utilization [expr {$placement_density * 100.0}] \
  -aspect_ratio 1.0 -core_space 10 -site $site
source $track_script
source $rc_script
place_pins -hor_layers metal5 -ver_layers metal6
rtl_macro_placer -halo_width $macro_halo_width -halo_height $macro_halo_height \
  -target_util $placement_density \
  -report_directory [file join $output_dir macro-placement]
global_placement -timing_driven -routability_driven -density $placement_density
estimate_parasitics -placement
puts "RESIZER_STAGE repair_design"
repair_design
estimate_parasitics -placement
puts "RESIZER_SETUP_REPAIR $run_setup_repair"
if {$run_setup_repair} {
  puts "RESIZER_STAGE repair_timing_setup"
  puts "RESIZER_MAX_REPAIRS_PER_PASS $max_repairs_per_pass"
  repair_timing -setup -max_repairs_per_pass $max_repairs_per_pass
  estimate_parasitics -placement
}
puts "RESIZER_STAGE repair_design_final"
puts "RESIZER_SLEW_MARGIN $slew_margin"
repair_design -slew_margin $slew_margin
estimate_parasitics -placement
puts "RESIZER_STAGE repair_design_final_convergence"
repair_design -slew_margin $slew_margin
estimate_parasitics -placement
write_verilog [file join $output_dir ZirconCore-repaired.v]

report_check_types -max_capacitance -violators -verbose -max_count 2000000 \
  > [file join $output_dir max-capacitance.rpt]
report_check_types -max_slew -violators -verbose -max_count 2000000 \
  > [file join $output_dir max-slew.rpt]
set cap_stream [open [file join $output_dir max-capacitance.rpt] r]
set cap_text [read $cap_stream]
close $cap_stream
set slew_stream [open [file join $output_dir max-slew.rpt] r]
set slew_text [read $slew_stream]
close $slew_stream
set cap_count [regexp -all {VIOLATED} $cap_text]
set slew_count [regexp -all {VIOLATED} $slew_text]
puts "ELECTRICAL_GATE cap_count=$cap_count slew_count=$slew_count"

if {$cap_count == 0 && $slew_count == 0} {
  report_worst_slack -max -digits 6 > [file join $output_dir wns.rpt]
  report_tns -max -digits 6 > [file join $output_dir tns.rpt]
  report_checks -path_delay max -slack_max 0 -group_path_count 1000000 \
    -endpoint_path_count 1 -unique_paths_to_endpoint -format end -digits 6 \
    -no_line_splits > [file join $output_dir violating-endpoints.rpt]
  report_checks -path_delay max -group_path_count 50 -endpoint_path_count 1 \
    -format full_clock_expanded -fields capacitance,slew,fanout,input_pin,net \
    -digits 6 -no_line_splits > [file join $output_dir worst-paths.rpt]

  set bsg_sram_outputs [concat \
    [get_pins -hierarchical */dout*] \
    [get_pins -hierarchical */rd_out*]]
  if {[llength $bsg_sram_outputs] > 0} {
    report_checks -through $bsg_sram_outputs -path_delay max -group_path_count 100 \
      -endpoint_path_count 1 -format full_clock_expanded \
      -digits 6 -no_line_splits > [file join $output_dir bsg-sram-output-worst.rpt]
    report_checks -through $bsg_sram_outputs -path_delay max -slack_max 0 \
      -group_path_count 100000 -endpoint_path_count 1 \
      -format full_clock_expanded -digits 6 -no_line_splits \
      > [file join $output_dir bsg-sram-output-violators.rpt]
  } else {
    set stream [open [file join $output_dir bsg-sram-output-worst.rpt] w]
    puts $stream "No BSG SRAM output pins found"
    close $stream
    set stream [open [file join $output_dir bsg-sram-output-violators.rpt] w]
    puts $stream "No BSG SRAM output pins found"
    close $stream
  }
} else {
  puts "PATH_REPORTS_SKIPPED electrical violations remain"
}
exit
