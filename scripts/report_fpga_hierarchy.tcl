# Emit a hierarchical utilization report from a synthesized Zircon checkpoint.
# Usage: vivado -mode batch -source scripts/report_fpga_hierarchy.tcl \
#   -tclargs <checkpoint.dcp> <report.rpt>
if {$argc != 2} {
  puts stderr "usage: vivado -mode batch -source scripts/report_fpga_hierarchy.tcl -tclargs <checkpoint.dcp> <report.rpt>"
  exit 2
}

set checkpoint [file normalize [lindex $argv 0]]
set report [file normalize [lindex $argv 1]]
if {![file exists $checkpoint]} {
  puts stderr "checkpoint does not exist: $checkpoint"
  exit 2
}

open_checkpoint $checkpoint
report_utilization -hierarchical -file $report
report_utilization -hierarchical -hierarchical_depth 5 \
  -file "${report}.depth5.rpt"
report_timing_summary -delay_type min_max -report_unconstrained \
  -file "${report}.timing.rpt"
puts "Hierarchical FPGA utilization report written: $report"
exit 0
