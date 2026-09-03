# Report hierarchy for an already synthesized Zircon checkpoint.
if {$argc != 2} {
  puts stderr "usage: vivado -mode batch -source scripts/report_fpga_hierarchy.tcl -tclargs <checkpoint> <report>"
  exit 2
}
set checkpoint [file normalize [lindex $argv 0]]
set report [file normalize [lindex $argv 1]]
if {![file exists $checkpoint]} {
  puts stderr "synthesis checkpoint is missing: $checkpoint"
  exit 2
}
open_checkpoint $checkpoint
report_utilization -hierarchical -hierarchical_depth 5 -file $report
close_design
exit 0
