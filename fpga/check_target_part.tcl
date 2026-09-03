set target_part xc7a200tfbg676-2L
set found [get_parts $target_part]
if {[llength $found] != 1} {
  puts stderr "required FPGA part '$target_part' is not installed in this Vivado instance"
  puts stderr "installed matching parts: [get_parts *7a200*]"
  exit 2
}
puts "Zircon target part available: $found"
exit 0
