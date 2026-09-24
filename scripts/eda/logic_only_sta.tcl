set netlist {@NETLIST@}
set top {@TOP@}
set period_ns @PERIOD_NS@
set driving_cell {@DRIVING_CELL@}
set output_load_ff @LOAD_FF@
set input_arrival_ns @INPUT_ARRIVAL_NS@
set output_delay_ns @OUTPUT_DELAY_NS@
set lef_files {@LEF_FILES@}
set liberty_files {@LIBERTY_FILES@}
set output_dir {@OUTPUT_DIR@}

file mkdir $output_dir
foreach lef $lef_files {
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

# LEF is read only to link abstract masters. Deliberately do not initialize a
# floorplan or estimate RC, so timing uses Liberty cell delay and zero-delay nets.
puts "LOGIC_ONLY_STA no_floorplan no_placement no_parasitics"
report_worst_slack -max -digits 6 > [file join $output_dir logic-only-wns.rpt]
report_tns -max -digits 6 > [file join $output_dir logic-only-tns.rpt]
report_checks -path_delay max -group_path_count 50 -endpoint_path_count 1 \
  -format full_clock_expanded -fields capacitance,slew,fanout,input_pin,net \
  -digits 6 -no_line_splits > [file join $output_dir logic-only-worst-paths.rpt]
report_checks -path_delay max -slack_max 0 -group_path_count 1000000 \
  -endpoint_path_count 1 -unique_paths_to_endpoint -format end -digits 6 \
  -no_line_splits > [file join $output_dir logic-only-violating-endpoints.rpt]
report_checks -path_delay max -slack_max 0 -group_path_count 1000000 \
  -endpoint_path_count 1 -unique_paths_to_endpoint -format summary -digits 6 \
  -no_line_splits > [file join $output_dir logic-only-violating-paths-summary.rpt]
exit
