set script_dir [file normalize [file dirname [info script]]]
set root_dir [file normalize [file join $script_dir ../..]]
set build_dir [file join $root_dir build eda vivado-vu13p]
set rtl_dir [file join $build_dir rtl]
set report_dir [file join $build_dir reports]
set project_file [file join $build_dir project ZirconCore.xpr]
set target_part xcvu13p-fhgb2104-2-i
set_param general.maxThreads 8

if {[llength $argv] > 1 || ([llength $argv] == 1 && [lindex $argv 0] ne "--create-only")} {
    error "Usage: vivado -mode batch -source eda/vivado/create_project.tcl ?-tclargs --create-only?"
}

set filelist [file join $rtl_dir filelist.f]
if {![file exists $filelist]} {
    error "Generate Vivado RTL first: python3 scripts/eda/prepare_vivado_rtl.py"
}
set stream [open $filelist r]
set source_names [split [read $stream] "\n"]
close $stream
set sv_files {}
foreach source_name $source_names {
    set source_name [string trim $source_name]
    if {$source_name eq "" || [string match "verification/*" $source_name]} {
        continue
    }
    set source_file [file join $rtl_dir $source_name]
    if {![file exists $source_file]} {
        error "Missing generated RTL: $source_file"
    }
    lappend sv_files $source_file
}
foreach module {
    XilinxSinglePortRamReadFirst
    XilinxTrueDualPortReadFirst1ClockRam
    XilinxTrueDualPortReadFirstByteWrite1ClockRam
} {
    if {![file exists [file join $rtl_dir "$module.sv"]]} {
        error "Missing Xilinx BRAM source: $module.sv"
    }
    lappend sv_files [file join $rtl_dir "$module.sv"]
}
set sv_files [lsort -unique $sv_files]

file mkdir $build_dir
if {[file exists $project_file]} {
    open_project $project_file
    if {[get_property part [current_project]] ne $target_part} {
        error "Existing project does not target $target_part: $project_file"
    }
    set old_sources [get_files -quiet -of_objects [get_filesets sources_1]]
    if {[llength $old_sources]} {
        remove_files -fileset sources_1 $old_sources
    }
} else {
    create_project ZirconCore [file join $build_dir project] -part $target_part
}

add_files -fileset sources_1 $sv_files
set_property top ZirconCore [get_filesets sources_1]
set old_constraints [get_files -quiet -of_objects [get_filesets constrs_1]]
if {[llength $old_constraints]} {
    remove_files -fileset constrs_1 $old_constraints
}
add_files -fileset constrs_1 [file join $script_dir core_clock_100mhz.xdc]
update_compile_order -fileset sources_1
set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
set_property INCREMENTAL_CHECKPOINT "" [get_runs synth_1]
puts "Created $project_file for $target_part with [llength $sv_files] SystemVerilog files"

if {[llength $argv] == 1} {
    close_project
    return
}

file mkdir $report_dir
reset_run impl_1
reset_run synth_1
launch_runs synth_1 -jobs 1
wait_on_run synth_1
set synth_status [get_property STATUS [get_runs synth_1]]
if {![string match "*Complete*" $synth_status]} {
    error "Synthesis failed: $synth_status"
}
open_run synth_1
report_utilization -file [file join $report_dir synth_utilization.rpt]
report_timing_summary -file [file join $report_dir synth_timing_summary.rpt]
set bram_count [llength [get_cells -quiet -hierarchical -filter {REF_NAME =~ RAMB*}]]
puts "Inferred $bram_count RAMB cells"
if {$bram_count == 0} {
    error "Synthesis did not infer block RAM"
}
close_design

launch_runs impl_1 -to_step route_design -jobs 1
wait_on_run impl_1
set impl_status [get_property STATUS [get_runs impl_1]]
if {![string match "*Complete*" $impl_status]} {
    error "Implementation failed: $impl_status"
}
open_run impl_1
report_utilization -file [file join $report_dir routed_utilization.rpt]
report_timing_summary -file [file join $report_dir routed_timing_summary.rpt]
report_timing -max_paths 50 -file [file join $report_dir routed_paths.rpt]
report_drc -file [file join $report_dir routed_drc.rpt]
close_project
