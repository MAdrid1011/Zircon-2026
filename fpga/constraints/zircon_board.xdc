# Recovered from the existing LA32R project for xc7a200tfbg676-2L.  Only
# documented clock, reset, and LED pins are used; no DDR or imaginary AXI
# pinout is inferred from that project.
set_property PACKAGE_PIN AC19 [get_ports clk]
set_property IOSTANDARD LVCMOS33 [get_ports clk]
create_clock -name clk -period 10.000 -waveform {0.000 5.000} [get_ports clk]

set_property PACKAGE_PIN Y3 [get_ports rstn]
set_property IOSTANDARD LVCMOS33 [get_ports rstn]

set_property PACKAGE_PIN K23 [get_ports {led[0]}]
set_property PACKAGE_PIN J21 [get_ports {led[1]}]
set_property PACKAGE_PIN H23 [get_ports {led[2]}]
set_property PACKAGE_PIN J19 [get_ports {led[3]}]
set_property PACKAGE_PIN G9 [get_ports {led[4]}]
set_property PACKAGE_PIN J26 [get_ports {led[5]}]
set_property PACKAGE_PIN J23 [get_ports {led[6]}]
set_property PACKAGE_PIN J8 [get_ports {led[7]}]
set_property PACKAGE_PIN H8 [get_ports {led[8]}]
set_property PACKAGE_PIN G8 [get_ports {led[9]}]
set_property PACKAGE_PIN F7 [get_ports {led[10]}]
set_property PACKAGE_PIN A4 [get_ports {led[11]}]
set_property PACKAGE_PIN A5 [get_ports {led[12]}]
set_property PACKAGE_PIN A3 [get_ports {led[13]}]
set_property PACKAGE_PIN D5 [get_ports {led[14]}]
set_property PACKAGE_PIN H7 [get_ports {led[15]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[*]}]

# rstn and LEDs are asynchronous board interfaces, not synchronous data
# endpoints.  The constrained core path is the 100 MHz clk domain above.
set_false_path -from [get_ports rstn]
