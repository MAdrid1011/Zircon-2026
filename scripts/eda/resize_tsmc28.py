#!/usr/bin/env python3
"""Resize equivalent TSMC28 cells at zero wire delay; no physical timing claim."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess

from adder_mapping import quote
from gate_equivalence import file_sha256, prove
from synthesize_tsmc28 import ROOT, sta_script

MEMORY_INSTANCE = re.compile(
    r'^  (?:BsgFakeram\w*|PredictorBsgFakeram\w*|SinglePortMaskedRam\w*)\s+.*?^  \);', re.M | re.S)


def run(command, log):
    with Path(log).open('w') as stream:
        subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)
    text = Path(log).read_text()
    if re.search(r'^Error:', text, re.M):
        raise RuntimeError(f'Tool reported an error: {log}')
    return text


def read_design(netlist, stubs, library, output, yosys):
    script = output.with_suffix('.ys')
    script.write_text(f'read_liberty -lib -ignore_miss_func {quote(library)}\n'
                      f'read_verilog -lib {quote(stubs)}\nread_verilog {quote(netlist)}\n'
                      f'hierarchy -check -top ZirconCore\nwrite_json {quote(output)}\n'
                      f'stat -liberty {quote(library)}\n')
    run([str(yosys), '-Q', '-T', '-s', str(script)], output.with_suffix('.log'))


def make_logic_lef(library, stubs, output, yosys):
    """Geometry only enables the resizer API. All cell data comes from Liberty."""
    script = output / 'library.ys'
    script.write_text(f'read_liberty -lib -ignore_miss_func {quote(library)}\n'
                      f'read_verilog -lib {quote(stubs)}\nwrite_json {quote(output / "library.json")}\n')
    run([str(yosys), '-Q', '-T', '-s', str(script)], output / 'library.log')
    modules = json.loads((output / 'library.json').read_text())['modules']
    lines = ['# NONPHYSICAL PLACEHOLDERS: LOGIC-ONLY SIZING, NOT PDK GEOMETRY.',
             'VERSION 5.8 ;', 'BUSBITCHARS "[]" ;', 'DIVIDERCHAR "/" ;',
             'UNITS', ' DATABASE MICRONS 1000 ;', 'END UNITS', 'MANUFACTURINGGRID 0.001 ;',
             'LAYER logic_metal', ' TYPE ROUTING ;', ' DIRECTION HORIZONTAL ;',
             ' PITCH 0.100 ;', ' WIDTH 0.010 ;', 'END logic_metal',
             'SITE logic_only', ' CLASS CORE ;', ' SIZE 0.001 BY 1.000 ;',
             ' SYMMETRY Y ;', 'END logic_only']
    for name, module in sorted(modules.items()):
        width = max(.1, math.ceil(float(module.get('attributes', {}).get('area', 1)) * 1000) / 1000)
        lines += [f'MACRO {name}', ' CLASS CORE ;', ' ORIGIN 0 0 ;',
                  f' SIZE {width:.3f} BY 1.000 ;', ' SYMMETRY X Y ;', ' SITE logic_only ;']
        for port, desc in module['ports'].items():
            pins = ([port] if len(desc['bits']) == 1 else
                    [f'{port}[{i + desc.get("offset", 0)}]' for i in range(len(desc['bits']))])
            for pin in pins:
                lines += [f' PIN {pin}', f'  DIRECTION {desc["direction"].upper()} ;',
                          '  USE SIGNAL ;', '  PORT', '   LAYER logic_metal ;',
                          '    RECT 0.000 0.000 0.010 0.010 ;', '  END', f' END {pin}']
        lines += [f'END {name}']
    lines += ['END LIBRARY']
    lef = output / 'logic-only-nonphysical.lef'
    lef.write_text('\n'.join(lines) + '\n')
    return lef


def memory_liberty(stubs, output, yosys):
    """Leaf pin definitions preserve SRAM instances; deliberately no timing arcs."""
    script = output / 'memory.ys'
    script.write_text(f'read_verilog -lib {quote(stubs)}\nwrite_json {quote(output / "memory.json")}\n')
    run([str(yosys), '-Q', '-T', '-s', str(script)], output / 'memory.log')
    modules = json.loads((output / 'memory.json').read_text())['modules']
    widths = {len(p['bits']) for m in modules.values() for p in m['ports'].values() if len(p['bits']) > 1}
    lines = ['/* UNTIMED SRAM PIN BOUNDARIES; NOT CHARACTERIZED MACROS. */',
             'library (untimed_sram_boundaries) {', ' time_unit : "1ns";',
             ' capacitive_load_unit (1,pf);', ' voltage_unit : "1V";',
             ' current_unit : "1mA";', ' pulling_resistance_unit : "1kohm";']
    for width in sorted(widths):
        lines += [f' type (bus{width}) {{ base_type : array; data_type : bit; bit_width : {width};',
                  f'  bit_from : {width-1}; bit_to : 0; downto : true; }}']
    for name, module in sorted(modules.items()):
        lines += [f' cell ({name}) {{ area : 1; dont_use : true; dont_touch : true; is_macro_cell : true;']
        for name, port in module['ports'].items():
            width = len(port['bits'])
            desc = f'direction : {port["direction"]};' + (' capacitance : 0;' if port['direction'] == 'input' else '')
            if width == 1:
                lines += [f'  pin ({name}) {{ {desc} }}']
            else:
                lines += [f'  bus ({name}) {{ bus_type : bus{width}; direction : {port["direction"]};',
                          f'   pin ("{name}[{width-1}:0]") {{ {desc} }} }}']
        lines += [' }']
    lines += ['}']
    path = output / 'untimed-sram.lib'
    path.write_text('\n'.join(lines) + '\n')
    return path


def restore_memories(reference, candidate, output, expected):
    """Restore unchanged empty SRAM hierarchy omitted by OpenROAD's writer.

    Acceptance requires the subsequent proof of every memory command, next state
    and output. This function alone establishes no functional equivalence.
    """
    original, raw = reference.read_text(), candidate.read_text()
    blocks = MEMORY_INSTANCE.findall(original)
    if len(blocks) != expected:
        raise ValueError(f'Expected {expected} memory instances; extracted {len(blocks)}')
    if re.search(r'^\s*(?:BsgFakeram\w*|PredictorBsgFakeram\w*|SinglePortMaskedRam\w*)\s+\\', raw, re.M):
        raise ValueError('Candidate already contains SRAM instances; refusing duplicate restoration')
    if raw.count('endmodule') != 1:
        raise ValueError('Expected one flat candidate module')
    # OpenROAD also omits constant primary outputs without inserted tie cells.
    # Restore their original values, never turn them into don't-care outputs.
    ports = json.loads(reference.with_suffix('.json').read_text())['modules']['ZirconCore']['ports']
    constants = []
    for name, port in ports.items():
        if port['direction'] != 'output':
            continue
        for index, bit in enumerate(port['bits']):
            if bit in ('0', '1'):
                suffix = f'[{index + port.get("offset", 0)}]' if len(port['bits']) > 1 else ''
                constants.append(f"assign {name}{suffix} = 1'b{bit};")
    output.write_text(raw.replace('endmodule', '\n' + '\n'.join(blocks + constants) + '\nendmodule'))


def repair_tcl(library, stubs, netlist, lef, directory, period, stage, memories=None):
    options = ('-skip_buffering -skip_gate_cloning -skip_buffer_removal -skip_last_gasp'
               if stage == 0 else '')
    margin = '-setup_margin 0.020' if stage == 2 else ''
    return f'''read_lef {{{lef}}}
read_liberty {{{library}}}
{f'read_liberty {{{memories}}}' if memories else ''}
{f'read_verilog {{{stubs}}}' if not memories else ''}
read_verilog {{{netlist}}}
link_design ZirconCore
create_clock -name core -period {period:g} [get_ports clock]
set_input_delay 0 -clock core [all_inputs -no_clocks]
set_output_delay 0 -clock core [all_outputs]
set_driving_cell -lib_cell BUFFD1BWP40P140 [all_inputs -no_clocks]
set_load 0.004 [all_outputs]
# This configuration only satisfies resizer's max-wire-length API.
# NO placement, estimate_parasitics, or RC annotation is performed.
set_wire_rc -signal -resistance 0.000001 -capacitance 0.000001
repair_timing -setup {margin} -repair_tns 100 -max_passes 20 -max_iterations 1000 \\
  -skip_vt_swap -skip_size_down -max_repairs_per_pass 100 {options}
write_verilog {{{directory / 'raw.v'}}}
report_checks -from [all_registers -clock_pins] -to [all_registers -data_pins] \\
 -path_delay max -group_path_count 30 -digits 6 > {{{directory / 'openroad-paths.rpt'}}}
exit
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--synthesis', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    candidates = parser.add_mutually_exclusive_group()
    candidates.add_argument('--candidate', type=Path, help='diagnose a legacy export with omitted SRAM hierarchy')
    candidates.add_argument('--verify-netlist', type=Path, help='verify an existing complete gate netlist instead of resizing')
    parser.add_argument('--image', default='openroad/flow-ubuntu22.04-builder:latest')
    parser.add_argument('--yosys', type=Path, default=ROOT / 'build/tools/yosys-orfs-0.68/bin/yosys')
    parser.add_argument('--sta', type=Path, default=ROOT / 'build/tools/OpenSTA-src/build/sta')
    args = parser.parse_args()
    source, out = args.synthesis.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'manifest.json').exists():
        raise ValueError('Choose a fresh output directory; existing evidence is immutable')
    previous = json.loads((source / 'results.json').read_text())
    library = Path(previous['library'])
    if file_sha256(library) != previous['library_sha256']:
        raise ValueError('Source Liberty digest changed')
    stubs, original = source / 'sram-blackboxes.v', source / 'ZirconCore-mapped.v'
    manifest = {'scope': 'Zero-parasitic standard-cell sizing. SRAM timing excluded; no physical closure.',
                'source_results': str(source / 'results.json'), 'source_results_sha256': file_sha256(source / 'results.json'),
                'library': str(library), 'library_sha256': file_sha256(library),
                'original_sha256': file_sha256(original), 'full_core_target_met': None}
    manifest.update(tool_sha256=file_sha256(Path(__file__)),
                    yosys=subprocess.check_output([str(args.yosys), '-V'], text=True).strip(),
                    opensta=subprocess.check_output([str(args.sta), '-version'], text=True).strip())
    if not args.candidate and not args.verify_netlist:
        manifest['openroad_image_id'] = subprocess.check_output(
            ['docker', 'image', 'inspect', '--format', '{{.Id}}', args.image], text=True).strip()
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.candidate or args.verify_netlist:
        candidate = (args.candidate or args.verify_netlist).resolve()
    else:
        lef = make_logic_lef(library, stubs, out, args.yosys)
        memories = memory_liberty(stubs, out, args.yosys)
        tied = out / 'tied.v'
        script = out / 'ties.ys'
        script.write_text(f'read_liberty -lib -ignore_miss_func {quote(library)}\n'
                          f'read_verilog -lib {quote(stubs)}\nread_verilog {quote(original)}\n'
                          'hierarchy -check -top ZirconCore\n'
                          'hilomap -hicell TIEHBWP40P140 Z -locell TIELBWP40P140 ZN\n'
                          f'write_verilog -noattr {quote(tied)}\n')
        run([str(args.yosys), '-Q', '-T', '-s', str(script)], out / 'ties.log')
        candidate = tied
        for stage, name in enumerate(('sizing', 'cloning', 'margin')):
            directory = out / name
            directory.mkdir()
            tcl = directory / 'repair.tcl'
            tcl.write_text(repair_tcl(library, stubs, candidate, lef, directory,
                                      previous['target_period_ns'], stage, memories))
            command = ['docker', 'run', '--rm', '--network', 'none', '--cpus', '2',
                       '--user', f'{os.getuid()}:{os.getgid()}',
                       '-v', f'{ROOT}:{ROOT}', '-v', '/opt/eda/pdk/TSMC28:/opt/eda/pdk/TSMC28:ro',
                       '--entrypoint', '/OpenROAD-flow-scripts/tools/install/OpenROAD/bin/openroad',
                       args.image, '-threads', '2', '-no_init', '-exit', str(tcl)]
            run(command, directory / 'repair.log')
            candidate = directory / 'raw.v'
    complete = out / 'ZirconCore-sized.v'
    expected = sum(previous['sram_blackboxes'].values())
    if args.candidate:
        restore_memories(original, candidate, complete, expected)
    else:
        complete.write_bytes(candidate.read_bytes())
    manifest.update(candidate_sha256=file_sha256(candidate), complete_sha256=file_sha256(complete))
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    sta = out / 'sta'
    sta.mkdir()
    (sta / 'sta.tcl').write_text(sta_script(library, complete, stubs, sta, previous['target_period_ns'], .004))
    run([str(args.sta), '-no_init', '-exit', str(sta / 'sta.tcl')], sta / 'sta.log')
    report = (sta / 'reg-to-reg.rpt').read_text()
    values = re.findall(r'([-\d.]+)\s+slack\s+\((?:MET|VIOLATED)\)', report)
    if not values:
        raise RuntimeError('No timed register paths')
    slack = min(map(float, values))
    electrical = len(re.findall(r'VIOLATED', (sta / 'electrical.rpt').read_text()))
    proof = out / 'equivalence'
    proof.mkdir()
    # Compare exactly the original Verilog consumed by OpenROAD. Yosys's JSON
    # before write_verilog also retains unused, unconnected SRAM output ports.
    gold, gate = proof / 'original.json', proof / 'sized.json'
    if args.candidate:
        read_design(original, stubs, library, gold, args.yosys)
    read_design(complete, stubs, library, gate, args.yosys)
    areas = re.findall(r'Chip area for module .*?:\s*([\d.]+)', gate.with_suffix('.log').read_text())
    if len(areas) != 1:
        raise RuntimeError('Expected one flat standard-cell area result')
    reference = gold if args.candidate else source / 'ZirconCore-mapped.json'
    result = prove(reference, gate, 'ZirconCore', library, proof, run, str(args.yosys),
                   partition_width=512, workers=2)
    data = {'manifest': manifest, 'equivalence': result,
            'reg_to_reg_worst_slack_ns': slack,
            'equivalent_period_ns': previous['target_period_ns'] - slack,
            'electrical_violations': electrical,
            'standard_cell_area_um2': float(areas[0]),
            'standard_cell_target_met': slack >= 0 and electrical == 0,
            'full_core_target_met': None}
    (out / 'results.json').write_text(json.dumps(data, indent=2) + '\n')
    print(json.dumps(data, indent=2))


if __name__ == '__main__':
    main()
