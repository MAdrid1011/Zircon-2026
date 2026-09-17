from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/openram-1rw1r-probe'
OUT.mkdir(parents=True, exist_ok=True)
MEMORY = ROOT / 'eda/platforms/nangate45/memory/openram-1rw1r'
LIB = ROOT / 'eda/platforms/nangate45/lib/NangateOpenCellLibrary_typical.lib'
MODELS = [
    MEMORY / 'openram45_1rw1r_16x25',
    MEMORY / 'openram45_1rw1r_16x32',
]


manifest = json.loads((MEMORY / 'manifest.json').read_text())
for name, expected in manifest['files'].items():
    if hashlib.sha256((MEMORY / name).read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Model input hash mismatch: {name}')
if hashlib.sha256(LIB.read_bytes()).hexdigest() != manifest['nangate45_sha256']:
    raise RuntimeError('Nangate45 library hash mismatch')


def run(argv, name):
    result = subprocess.run(argv, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=180)
    (OUT / name).write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(result.stdout[-4000:])
    return result.stdout


def liberty(model):
    return Path(str(model) + '_TT_1p1V_25C.lib')


def quoted(path):
    return '"' + str(path) + '"'


ports = '''    input clk,
    input csb0, csb1, web0,
    input [3:0] addr0, addr1,
    input [24:0] tag_in,
    input [31:0] data_in,
    input [3:0] mask,
    input invert,
    output reg [24:0] tag_q0, tag_q1,
    output reg [31:0] data_q0, data_q1'''
instances = '''    wire [24:0] tag_out0, tag_out1;
    wire [31:0] data_out0, data_out1;
    openram45_1rw1r_16x25 tag (
        .clk0(clk), .clk1(clk), .csb0(csb0), .csb1(csb1), .web0(web0),
        .addr0(addr0), .addr1(addr1), .din0(tag_in),
        .dout0(tag_out0), .dout1(tag_out1)
    );
    openram45_1rw1r_16x32 data (
        .clk0(clk), .clk1(clk), .csb0(csb0), .csb1(csb1), .web0(web0),
        .addr0(addr0), .addr1(addr1), .din0(data_in), .wmask0(mask),
        .dout0(data_out0), .dout1(data_out1)
    );'''
(OUT / 'probe.sv').write_text('module RamProbe (\n' + ports + '\n);\n' + instances + '''
    always @(posedge clk) begin
        tag_q0 <= tag_out0 ^ {25{invert}};
        tag_q1 <= tag_out1 ^ {25{invert}};
        data_q0 <= data_out0 ^ {32{invert}};
        data_q1 <= data_out1 ^ {32{invert}};
    end
endmodule
''')

tb = '''`timescale 1ns/1ps
module RamProbeTb;
    reg clk = 0;
    reg csb0, csb1, web0;
    reg [3:0] addr0, addr1, mask;
    reg [24:0] tag_in;
    reg [31:0] data_in;
''' + instances.replace('tag (', ' #(.VERBOSE(0)) tag (').replace(
    'data (', ' #(.VERBOSE(0)) data (') + '''
    reg [24:0] tag_ref [0:15];
    reg [31:0] data_ref [0:15];
    reg [31:0] random_state = 32'h20260911;
    integer reads = 0, writes = 0, dual_reads = 0, mixed = 0, cycles = 0;
    integer i;

    function automatic [31:0] next_random;
        begin
            random_state = random_state ^ (random_state << 13);
            random_state = random_state ^ (random_state >> 17);
            random_state = random_state ^ (random_state << 5);
            next_random = random_state;
        end
    endfunction

    task automatic tick;
        integer lane;
        begin
            #5 clk = 1;
            #2;
            if (tag_out0 !== {25{1'bx}} || tag_out1 !== {25{1'bx}} ||
                data_out0 !== {32{1'bx}} || data_out1 !== {32{1'bx}})
                $fatal(1, "Outputs must become invalid after the sampling edge");
            #3 clk = 0;
            #4;
            if (!csb0 && !web0) begin
                tag_ref[addr0] = tag_in;
                for (lane = 0; lane < 4; lane = lane + 1)
                    if (mask[lane])
                        data_ref[addr0][8*lane +: 8] = data_in[8*lane +: 8];
                writes = writes + 1;
            end
            if (!csb0 && web0) begin
                if (tag_out0 !== tag_ref[addr0] || data_out0 !== data_ref[addr0])
                    $fatal(1, "RW port read mismatch at cycle %0d", cycles);
                reads = reads + 1;
            end
            if (!csb1) begin
                if (tag_out1 !== tag_ref[addr1] || data_out1 !== data_ref[addr1])
                    $fatal(1, "R port read mismatch at cycle %0d", cycles);
                reads = reads + 1;
            end
            if (!csb0 && web0 && !csb1) dual_reads = dual_reads + 1;
            if (!csb0 && !web0 && !csb1) mixed = mixed + 1;
            cycles = cycles + 1;
            #6;
        end
    endtask

    initial begin
        csb0 = 0; csb1 = 1; web0 = 0; addr1 = 0; mask = 15;
        for (i = 0; i < 16; i = i + 1) begin
            addr0 = i; tag_in = next_random(); data_in = next_random(); tick();
        end
        // Exercise every mask on every row while the other port reads another row.
        for (i = 0; i < 256; i = i + 1) begin
            csb0 = 0; csb1 = 0; web0 = 0;
            addr0 = i / 16; addr1 = addr0 + 1; mask = i;
            tag_in = next_random(); data_in = next_random(); tick();
            web0 = 1; addr1 = addr0; tick();
        end
        for (i = 0; i < 1024; i = i + 1) begin
            csb0 = next_random(); csb1 = next_random(); web0 = next_random();
            addr0 = next_random(); addr1 = next_random(); mask = next_random();
            if (!csb0 && !web0 && !csb1 && addr0 == addr1) addr1 = addr1 + 1;
            tag_in = next_random(); data_in = next_random(); tick();
        end
        $display("PASS cycles=%0d reads=%0d writes=%0d dual_reads=%0d mixed=%0d",
                 cycles, reads, writes, dual_reads, mixed);
        $finish;
    end
endmodule
'''
(OUT / 'tb.sv').write_text(tb)
run(['iverilog', '-g2012', '-s', 'RamProbeTb', '-o', str(OUT / 'tb.vvp'),
     str(OUT / 'tb.sv'), *(str(m) + '.v' for m in MODELS)], 'iverilog.log')
simulation = run(['vvp', str(OUT / 'tb.vvp')], 'simulation.log')

mapping = '\n'.join(f'read_liberty -lib {quoted(p)}' for p in [LIB, *map(liberty, MODELS)])
mapping += f'''
read_verilog -sv {quoted(OUT / 'probe.sv')}
synth -top RamProbe -flatten -noabc
dfflibmap -liberty {quoted(LIB)}
abc -liberty {quoted(LIB)}
clean
check -assert
stat -liberty {quoted(LIB)} {' '.join('-liberty ' + quoted(liberty(m)) for m in MODELS)}
write_json {quoted(OUT / 'mapped.json')}
write_verilog -noattr -noexpr {quoted(OUT / 'mapped.v')}
'''
(OUT / 'map.ys').write_text(mapping)
run(['yowasp-yosys', '-Q', '-T', '-s', str(OUT / 'map.ys')], 'mapping.log')
cells = json.loads((OUT / 'mapped.json').read_text())['modules']['RamProbe']['cells']
counts = Counter(c['type'] for c in cells.values())
for m in MODELS:
    assert counts[m.name] == 1, counts
assert counts['DFF_X1'] == 114 and counts['XOR2_X1'] == 114, counts
assert set(counts) == {m.name for m in MODELS} | {'DFF_X1', 'XOR2_X1'}, counts

timing = '\n'.join('read_liberty ' + quoted(p) for p in [LIB, *map(liberty, MODELS)])
timing += f'''
read_verilog {quoted(OUT / 'mapped.v')}
link_design RamProbe
create_clock -name clk -period 10 [get_ports clk]
set_clock_transition 0.05 [get_clocks clk]
set_input_delay 0 -clock clk [get_ports {{csb* web0 addr* tag_in* data_in* mask* invert}}]
set_input_transition 0.05 [get_ports {{csb* web0 addr* tag_in* data_in* mask* invert}}]
set_output_delay 0 -clock clk [all_outputs]
set_load 4 [all_outputs]
check_setup -verbose
report_checks -path_delay max -group_path_count 4 -digits 6
'''
for name in ['tag', 'data']:
    for port in [0, 1]:
        timing += f'puts {{RAM_READ_{name}_{port}}}\n'
        timing += f'report_checks -through [get_pins {{{name}/dout{port}*}}] -path_delay max -group_path_count 1 -digits 6\n'
timing += 'report_check_types -max_slew -max_capacitance -min_pulse_width -min_period\n'
timing += 'exit\n'
(OUT / 'timing.tcl').write_text(timing)
sta = run([str(ROOT / 'build/prf-tools/sta-build/sta'), '-exit', str(OUT / 'timing.tcl')], 'timing.log')
if re.search(r'^Error:', sta, re.M):
    raise RuntimeError(sta)
for name in ['tag', 'data']:
    for port in [0, 1]:
        section = sta.split(f'RAM_READ_{name}_{port}\n')[1].split('RAM_READ_')[0]
        assert f'{name}/dout{port}[' in section and 'clock clk (fall edge)' in section, section

result = {
    'verified_at_utc': datetime.now(timezone.utc).isoformat(),
    'model_manifest_sha256': hashlib.sha256((MEMORY / 'manifest.json').read_bytes()).hexdigest(),
    'nangate45_sha256': manifest['nangate45_sha256'],
    'tools': {
        'yosys': run(['yowasp-yosys', '-V'], 'yosys-version.log').strip(),
        'iverilog': run(['iverilog', '-V'], 'iverilog-version.log').splitlines()[0],
        'opensta': run([str(ROOT / 'build/prf-tools/sta-build/sta'), '-version'], 'sta-version.log').strip(),
    },
    'scope': 'Standalone macro behavioral simulation and Nangate45/OpenSTA linking; full DCache evaluated separately',
    'output_load_ff': 4,
    'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'simulation': simulation.strip(),
    'cell_counts': dict(counts),
    'macro_areas_um2': {m.name: float(re.search(r'\barea\s*:\s*([\d.]+)', liberty(m).read_text())[1]) for m in MODELS},
    'timing_arcs': 'Both read ports of both macros launch from the falling edge and reach rising-edge output registers',
    'limitations': ['Analytical delay model, no SPICE characterization', 'No DRC/LVS/PEX or power routing', 'No full DCache verification'],
}
(OUT / 'results.json').write_text(json.dumps(result, indent=4) + '\n')
print(json.dumps(result, indent=4))
