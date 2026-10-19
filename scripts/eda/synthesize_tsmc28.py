"""Map Zircon logic to an external TSMC28 library; exclude SRAM timing."""

import argparse
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

from adder_mapping import AdderMapping, quote
from synthesize_core import validate_no_simulation_debug

ROOT = Path(__file__).resolve().parents[2]
PDK = Path('/opt/eda/pdk/TSMC28/logic')
LIB_NAME = 'tcbn28hpcplusbwp40p140tt0p8v85c.lib'
SRAM = re.compile(r'(?:SinglePortMaskedRam_\d+_\d+_\d+|'
                  r'PredictorBsgFakeram_\d+_\d+|BsgFakeram1RW1R_(?:\d+_)?(?:25|32))')


def run(command, log, env=None):
    start = time.monotonic()
    with log.open('w') as stream:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream,
                                stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        raise RuntimeError(f'{command[0]} failed; see {log}\n' +
                           '\n'.join(log.read_text(errors='replace').splitlines()[-30:]))
    return time.monotonic() - start


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def memory_stubs(sources, output):
    """Keep the original SRAM pin boundary, without invented timing arcs."""
    stubs = {}
    remaining = []
    text = '\n'.join(p.read_text() for p in sources)
    for path in sources:
        if SRAM.fullmatch(path.stem):
            header = re.search(r'\bmodule\s+.*?\);', path.read_text(), re.S)[0]
            stubs[path.stem] = header + '\nendmodule\n'
        else:
            remaining.append(path)
    for name in sorted(set(SRAM.findall(text)) - stubs.keys()):
        if name.startswith('PredictorBsgFakeram_'):
            depth, width = map(int, name.split('_')[-2:])
        elif name.startswith('BsgFakeram1RW1R_'):
            dimensions = list(map(int, name.split('_')[1:]))
            depth, width = dimensions if len(dimensions) == 2 else (16, dimensions[0])
        else:
            raise RuntimeError(f'Missing generated SRAM interface: {name}')
        address = max(1, (depth - 1).bit_length())
        mask = '  input [3:0] wmask0,\n' if name.startswith('BsgFakeram1RW1R_') and width == 32 else ''
        stubs[name] = f'''module {name} (
  input clock, csb0, csb1, web0,
  input [{address - 1}:0] addr0, addr1,
  input [{width - 1}:0] din0,
{mask}  output [{width - 1}:0] dout0, dout1
);
endmodule
'''
    if not stubs:
        raise RuntimeError('No SRAM boundaries found')
    output.write_text('\n'.join(stubs.values()))
    return remaining, sorted(stubs)


def sta_script(library, netlist, stubs, output, period, load_pf):
    # Register-to-register paths do not traverse the untimed SRAM boundaries.
    return f'''read_liberty {{{library}}}
read_verilog {{{stubs}}}
read_verilog {{{netlist}}}
link_design ZirconCore
create_clock -name core -period {period:g} [get_ports clock]
set data_inputs {{}}
foreach port [all_inputs] {{
  if {{[get_full_name $port] ne "clock"}} {{ lappend data_inputs $port }}
}}
set_input_delay 0 -clock core $data_inputs
set_output_delay 0 -clock core [all_outputs]
set_driving_cell -lib_cell BUFFD1BWP40P140 $data_inputs
set_load {load_pf:g} [all_outputs]
report_units
check_setup -verbose > {{{output / 'coverage.rpt'}}}
report_checks -path_delay max -group_path_count 30 -format full_clock_expanded -fields {{slew cap fanout input net}} -digits 6 > {{{output / 'setup.rpt'}}}
report_checks -from [all_registers -clock_pins] -to [all_registers -data_pins] -path_delay max -group_path_count 30 -format full_clock_expanded -fields {{slew cap fanout input net}} -digits 6 > {{{output / 'reg-to-reg.rpt'}}}
report_checks -from [all_registers -clock_pins] -to [all_registers -data_pins] -path_delay max -slack_max 0 -group_path_count 1000000 -format slack_only -digits 6 > {{{output / 'violating-register-endpoints.rpt'}}}
report_checks -path_delay min -group_path_count 10 -digits 6 > {{{output / 'hold.rpt'}}}
report_check_types -max_slew -max_capacitance -max_fanout -violators > {{{output / 'electrical.rpt'}}}
report_worst_slack -max -digits 6 > {{{output / 'worst-slack.rpt'}}}
report_tns -digits 6 > {{{output / 'tns.rpt'}}}
exit
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=('system', 'performance'), required=True)
    parser.add_argument('--system-l2-sets', type=int, default=32,
                        help='system L2 geometry; 32 sets matches the simulation/Dandelion host')
    parser.add_argument('--scratchpad', action='store_true',
                        help='include the performance PE scratchpad interface used by Dandelion')
    parser.add_argument('--target-ns', type=float, default=1.0)
    parser.add_argument('--mapping-delay-ps', type=float,
                        help='ABC combinational delay goal; default is the clock period in ps')
    parser.add_argument('--liberty', type=Path)
    parser.add_argument('--sta-liberty', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--yosys', type=Path, default=ROOT / 'build/tools/yosys-orfs-0.68/bin/yosys')
    parser.add_argument('--sta', type=Path, default=ROOT / 'build/tools/OpenSTA-src/build/sta')
    parser.add_argument('--skip-elaboration', action='store_true')
    parser.add_argument('--skip-mapping', action='store_true', help='reuse the output directory mapped netlist')
    parser.add_argument('--logic-mapping', choices=('default', 'direct'), default='default',
                        help='compare ABC default rewriting with structure-preserving direct mapping')
    parser.add_argument('--unmap-enables', action='store_true',
                        help='expose flip-flop enable/reset muxes to ABC before cell mapping')
    args = parser.parse_args()
    if args.scratchpad and args.profile != 'performance':
        parser.error('--scratchpad requires --profile performance')
    if args.system_l2_sets < 2 or args.system_l2_sets & (args.system_l2_sets - 1):
        parser.error('--system-l2-sets must be a power of two >= 2')
    if args.skip_mapping and not args.skip_elaboration:
        parser.error('--skip-mapping requires --skip-elaboration')
    if not math.isfinite(args.target_ns) or args.target_ns <= 0:
        parser.error('--target-ns must be finite and positive')
    mapping_delay = args.mapping_delay_ps if args.mapping_delay_ps is not None else args.target_ns * 1000
    if not math.isfinite(mapping_delay) or mapping_delay <= 0:
        parser.error('--mapping-delay-ps must be finite and positive')
    library = args.liberty or next(PDK.rglob(LIB_NAME))
    library = library.resolve()
    output = (args.output or ROOT / 'build/eda/tsmc28' / args.profile).resolve()
    output.mkdir(parents=True, exist_ok=True)
    rtl = output / 'rtl'
    rtl.mkdir(exist_ok=True)
    configuration = {'profile': args.profile,
                     'system_l2_sets': args.system_l2_sets if args.profile == 'system' else None,
                     'scratchpad': args.scratchpad}
    configuration_path = output / 'configuration.json'
    if args.skip_elaboration:
        if not configuration_path.exists() or json.loads(configuration_path.read_text()) != configuration:
            raise RuntimeError('Reused RTL must have a matching configuration.json; elaborate into a fresh output directory')
    if not args.skip_elaboration:
        env = os.environ.copy()
        env['ZIRCON_USE_EXTERNAL_BSG_RAM'] = 'true'
        for name in ('ZIRCON_USE_EXTERNAL_VIVADO_RAM', 'ZIRCON_INCLUDE_VIVADO_RAM_SOURCE',
                     'ZIRCON_VIVADO_NATIVE_ADDERS'):
            env.pop(name, None)
        options = f'--bsg --profile={args.profile}'
        if args.profile == 'system':
            options += f' --l2-sets={args.system_l2_sets}'
        if args.scratchpad:
            options += ' --scratchpad'
        run(['sbt', '--batch', f'runMain Elaborate {options} {shlex.quote(str(rtl))}'],
            output / 'elaboration.log', env)
        configuration_path.write_text(json.dumps(configuration, indent=2) + '\n')
    sources = [rtl / line for line in (rtl / 'filelist.f').read_text().splitlines()
               if line and not line.startswith('verification/')]
    validate_no_simulation_debug(sources)
    source_hashes = {str(p.relative_to(ROOT)): digest(p) for p in
                     sorted((ROOT / 'src').rglob('*')) if p.is_file()}
    stubs = output / 'sram-blackboxes.v'
    logic, memory_names = memory_stubs(sources, stubs)
    mapped = output / 'ZirconCore-mapped.v'
    mapped_json = output / 'ZirconCore-mapped.json'
    constraint = output / 'abc.constr'
    # Liberty uses pF; ABC's set_load uses fF.
    constraint.write_text('set_driving_cell BUFFD1BWP40P140\nset_load 4\n')
    mapping = AdderMapping.discover(logic, delay_ps=mapping_delay)
    logic_command = mapping.commands(library, constraint, output, stage='post_flatten')
    if args.logic_mapping == 'direct':
        direct_script = output / 'logic.abc'
        direct_script.write_text(
            f'strash; &get -n; &nf -D {mapping_delay:g}; &put; buffer; '
            f'upsize -D {mapping_delay:g}; dnsize -D {mapping_delay:g}; stime -p\n')
        logic_command = f'abc -liberty {quote(library)} -constr {quote(constraint)} -script {quote(direct_script)}'
    lines = [f'read_liberty -lib -ignore_miss_func {quote(library)}',
             f'read_verilog -lib {quote(stubs)}']
    lines += [f'read_verilog -sv -D SYNTHESIS {quote(p)}' for p in logic]
    lines += [mapping.preserve(), 'hierarchy -check -top ZirconCore',
              'synth -top ZirconCore -noabc',
              'dffunmap' if args.unmap_enables else '',
              f'dfflibmap -liberty {quote(library)}',
              mapping.commands(library, constraint, output, stage='pre_flatten'),
              mapping.flatten(),
              logic_command,
              'clean', 'check -assert', f'stat -liberty {quote(library)}',
              f'write_json {quote(mapped_json)}',
              f'write_verilog -noattr -noexpr {quote(mapped)}']
    script = output / 'synth.ys'
    script_text = '\n'.join(line for line in lines if line) + '\n'
    if args.skip_mapping:
        if script.read_text() != script_text:
            raise RuntimeError('Mapping inputs or target differ from the saved synthesis script')
        elapsed = None
    else:
        script.write_text(script_text)
        elapsed = run([str(args.yosys), '-Q', '-T', '-s', str(script)], output / 'synthesis.log')
    modules = json.loads(mapped_json.read_text())['modules']
    cells = Counter(c['type'] for c in modules['ZirconCore']['cells'].values())
    unknown = {n: c for n, c in cells.items() if n.startswith('$')}
    if unknown:
        raise RuntimeError(f'Unmapped logic cells: {unknown}')
    memories = {name: cells[name] for name in memory_names if cells[name]}
    if not memories:
        raise RuntimeError('SRAM boundaries were lost')
    synthesis_log = (output / 'synthesis.log').read_text()
    area = re.findall(r"Chip area for (?:top )?module '\\ZirconCore':\s*([\d.]+)", synthesis_log)
    summary = {
        'profile': args.profile, 'target_period_ns': args.target_ns,
        'configuration': configuration,
        'unmap_enables': args.unmap_enables, 'logic_mapping': args.logic_mapping,
        'mapping_delay_ps': mapping_delay,
        'scope': 'Standard-cell synthesis and STA; SRAM internal and interface paths excluded',
        'full_core_target_met': None,
        'source_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'source_status': subprocess.check_output(['git', 'status', '--short'], cwd=ROOT, text=True),
        'source_sha256': source_hashes,
        'rtl_sha256': {str(p.relative_to(output)): digest(p) for p in sources},
        'library': str(library), 'library_sha256': digest(library),
        'yosys': subprocess.check_output([str(args.yosys), '-V'], text=True).strip(),
        'opensta': subprocess.check_output([str(args.sta), '-version'], text=True).strip(),
        'synthesis_seconds': elapsed, 'mapped_netlist_sha256': digest(mapped),
        'standard_cell_count': sum(cells.values()) - sum(memories.values()),
        'standard_cell_area_library_units': float(area[-1]) if area else None,
        'sram_blackboxes': memories,
        'conditions': {'clock': 'ideal', 'interconnect': 'zero parasitics',
                       'clock_uncertainty_ns': 0, 'io_delay_ns': 0,
                       'output_load_pf': 0.004, 'electrical_repair': False},
        'timing': [],
    }
    for sta_lib in [library, *args.sta_liberty]:
        sta_lib = sta_lib.resolve()
        sta_out = output / sta_lib.stem
        sta_out.mkdir(exist_ok=True)
        sta_tcl = sta_out / 'sta.tcl'
        sta_tcl.write_text(sta_script(sta_lib, mapped, stubs, sta_out, args.target_ns, 0.004))
        run([str(args.sta), '-no_init', '-exit', str(sta_tcl)], sta_out / 'sta.log')
        log = (sta_out / 'sta.log').read_text()
        if re.search(r'(?m)^Error(?:\s|:)', log):
            raise RuntimeError(f'OpenSTA reported errors: {sta_out / "sta.log"}')
        report = (sta_out / 'reg-to-reg.rpt').read_text()
        slacks = [float(s) for s in re.findall(
            r'(?m)^\s*([-+]?\d+(?:\.\d+)?)\s+slack\s+\([^)]*\)', report)]
        if not slacks:
            raise RuntimeError(f'No timed register paths: {sta_out}')
        worst = min(slacks)
        summary['timing'].append({
            'library': str(sta_lib), 'library_sha256': digest(sta_lib),
            'reg_to_reg_worst_slack_ns': worst,
            'reg_to_reg_target_met': worst >= 0,
            'reg_to_reg_equivalent_period_ns': args.target_ns - worst,
            'reg_to_reg_equivalent_frequency_ghz': 1 / (args.target_ns - worst),
            'reports': str(sta_out),
        })
    (output / 'results.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({k: v for k, v in summary.items() if not k.endswith('sha256')}, indent=2))


if __name__ == '__main__':
    main()
