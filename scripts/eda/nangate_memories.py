"""Bind every synthesized SRAM interface to pinned BSG Fakeram timing models."""
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MEMORY = ROOT / 'eda/platforms/nangate45/memory'
PATTERN = re.compile(r'SinglePortMaskedRam_(\d+)_(\d+)_(\d+)')
DUAL_PORT_MACROS = (
    ("fakeram45_1rw1r_16x25", 16, 25, 0),
    ("fakeram45_1rw1r_16x32", 16, 32, 4),
    ("fakeram45_1rw1r_64x24", 64, 24, 0),
    ("fakeram45_1rw1r_128x45", 128, 45, 0),
    ("fakeram45_1rw1r_256x8", 256, 8, 0),
    ("fakeram45_1rw1r_512x16", 512, 16, 0),
)


def catalog():
    manifest = json.loads((MEMORY / 'manifest.json').read_text())
    result = []
    for item in manifest['files']:
        path = MEMORY / item['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise RuntimeError(f'Memory library hash mismatch: {path}')
        text = path.read_text()
        area = float(re.search(r'\barea\s*:\s*([\d.]+)', text)[1])
        if not all(port in text for port in ('rd_out', 'addr_in', 'we_in', 'wd_in', 'ce_in', 'w_mask_in')):
            raise RuntimeError(f'Unexpected SRAM interface: {path}')
        result.append({**item, 'name': path.stem, 'path': str(path), 'area_um2': area})
    return result


def choose(depth, width, macros):
    candidates = [m for m in macros if m['depth'] >= depth]
    if not candidates:
        raise ValueError(f'No pinned macro can hold {depth} rows')

    @lru_cache(None)
    def best(remaining):
        if remaining <= 0:
            return 0.0, ()
        choices = []
        for i, macro in enumerate(candidates):
            cost, tail = best(remaining - macro['width'])
            choices.append((macro['area_um2'] + cost, (i, *tail)))
        return min(choices)

    return [candidates[i] for i in best(width)[1]]


def ports(depth, width):
    address = max(1, (depth - 1).bit_length())
    return f'''    input clk,
    input ce_in,
    input we_in,
    input [{address - 1}:0] addr_in,
    input [{width - 1}:0] wd_in,
    input [{width - 1}:0] w_mask_in,
    output reg [{width - 1}:0] rd_out'''


def _first_scalar(text, pattern, description):
    match = re.search(pattern, text, re.DOTALL)
    if not match:
        raise RuntimeError(f'Could not extract {description} from BSG Fakeram Liberty')
    return float(match.group(1))


def _timing_parameters(macro):
    text = Path(macro['path']).read_text()
    return {
        'min_period_ns': _first_scalar(text, r'min_period\s*:\s*([\d.]+)', 'minimum period'),
        'clock_to_q_ns': _first_scalar(
            text,
            r'timing_type\s*:\s*rising_edge\s*;.*?cell_rise\s*\(scalar\)\s*\{\s*values\s*\("([\d.]+)"\)',
            'rising-edge clock-to-Q',
        ),
        'max_transition_ns': _first_scalar(
            text, r'default_max_transition\s*:\s*([\d.]+)', 'maximum transition'
        ),
    }


def _constraint(pin, bus_type=None):
    kind = f'bus({pin})' if bus_type else f'pin({pin})'
    type_line = f'        bus_type : {bus_type};\n' if bus_type else ''
    return f'''    {kind} {{
{type_line}        direction : input;
        capacitance : 5.000;
        timing() {{
            related_pin : clock;
            timing_type : setup_rising;
            rise_constraint(scalar) {{ values ("0.050"); }}
            fall_constraint(scalar) {{ values ("0.050"); }}
        }}
        timing() {{
            related_pin : clock;
            timing_type : hold_rising;
            rise_constraint(scalar) {{ values ("0.050"); }}
            fall_constraint(scalar) {{ values ("0.050"); }}
        }}
    }}'''


def _output_timing(pin, bus_type, address, clock_to_q, transition):
    return f'''    bus({pin}) {{
        bus_type : {bus_type};
        direction : output;
        max_capacitance : 500.000;
        memory_read() {{ address : {address}; }}
        timing() {{
            related_pin : "clock";
            timing_type : rising_edge;
            timing_sense : non_unate;
            cell_rise(scalar) {{ values ("{clock_to_q:.3f}"); }}
            cell_fall(scalar) {{ values ("{clock_to_q:.3f}"); }}
            rise_transition(scalar) {{ values ("{transition:.3f}"); }}
            fall_transition(scalar) {{ values ("{transition:.3f}"); }}
        }}
    }}'''


def _dual_port_liberty(name, depth, width, mask_width, base):
    address_width = max(1, (depth - 1).bit_length())
    timing = _timing_parameters(base)
    data_type = f'{name}_DATA'
    address_type = f'{name}_ADDRESS'
    mask_type = f'{name}_MASK'
    mask_declaration = ''
    mask_constraint = ''
    if mask_width:
        mask_declaration = f'''    type ({mask_type}) {{
        base_type : array;
        data_type : bit;
        bit_width : {mask_width};
        bit_from : {mask_width - 1};
        bit_to : 0;
        downto : true;
    }}
'''
        mask_constraint = '\n' + _constraint('wmask0', mask_type)
    # BSG Fakeram emits only 1RW. For logic-only analysis this cell preserves
    # the existing 1RW+1R boundary and applies the matching BSG rising-edge
    # timing to both read outputs. Area is conservatively doubled.
    return f'''library({name}) {{
    technology (cmos);
    delay_model : table_lookup;
    comment : "1RW+1R logic-only model derived from {base['name']} BSG Fakeram";
    time_unit : "1ns";
    voltage_unit : "1V";
    current_unit : "1uA";
    leakage_power_unit : "1nw";
    nom_process : 1;
    nom_temperature : 25.000;
    nom_voltage : 1.1;
    capacitive_load_unit (1,ff);
    default_input_pin_cap : 0.0;
    default_output_pin_cap : 0.0;
    default_max_transition : {timing['max_transition_ns']:.3f};
    type ({data_type}) {{
        base_type : array;
        data_type : bit;
        bit_width : {width};
        bit_from : {width - 1};
        bit_to : 0;
        downto : true;
    }}
    type ({address_type}) {{
        base_type : array;
        data_type : bit;
        bit_width : {address_width};
        bit_from : {address_width - 1};
        bit_to : 0;
        downto : true;
    }}
{mask_declaration}    cell({name}) {{
        area : {2.0 * base['area_um2']:.3f};
        interface_timing : true;
        memory() {{
            type : ram;
            address_width : {address_width};
            word_width : {width};
        }}
        pin(clock) {{
            direction : input;
            capacitance : 25.000;
            clock : true;
            min_period : {timing['min_period_ns']:.3f};
        }}
{_constraint('csb0')}
{_constraint('csb1')}
{_constraint('web0')}
{_constraint('addr0', address_type)}
{_constraint('addr1', address_type)}
{_constraint('din0', data_type)}{mask_constraint}
{_output_timing('dout0', data_type, 'addr0', timing['clock_to_q_ns'], timing['max_transition_ns'])}
{_output_timing('dout1', data_type, 'addr1', timing['clock_to_q_ns'], timing['max_transition_ns'])}
    }}
}}
'''


def _dual_port_lef(name, depth, width, mask_width, area):
    address_width = max(1, (depth - 1).bit_length())
    pins = [
        ('clock', 'INPUT', 'CLOCK'),
        ('csb0', 'INPUT', 'SIGNAL'),
        ('csb1', 'INPUT', 'SIGNAL'),
        ('web0', 'INPUT', 'SIGNAL'),
    ]
    for bus, size, direction in (
        ('addr0', address_width, 'INPUT'),
        ('addr1', address_width, 'INPUT'),
        ('din0', width, 'INPUT'),
        ('wmask0', mask_width, 'INPUT'),
        ('dout0', width, 'OUTPUT'),
        ('dout1', width, 'OUTPUT'),
    ):
        pins.extend((f'{bus}[{bit}]', direction, 'SIGNAL') for bit in range(size))
    side = max(10.0, math.sqrt(area))
    height = max(side, len(pins) * 0.14 + 0.28)
    lines = [
        'VERSION 5.7 ;',
        'BUSBITCHARS "[]" ;',
        'DIVIDERCHAR "/" ;',
        f'MACRO {name}',
        f'  FOREIGN {name} 0 0 ;',
        '  SYMMETRY X Y R90 ;',
        f'  SIZE {side:.3f} BY {height:.3f} ;',
        '  CLASS BLOCK ;',
    ]
    input_index = 0
    output_index = 0
    for pin, direction, use in pins:
        if direction == 'OUTPUT':
            x0, x1 = side - 0.07, side
            y0 = 0.14 + output_index * 0.14
            output_index += 1
        else:
            x0, x1 = 0.0, 0.07
            y0 = 0.14 + input_index * 0.14
            input_index += 1
        lines += [
            f'  PIN {pin}',
            f'    DIRECTION {direction} ;',
            f'    USE {use} ;',
            '    PORT',
            '      LAYER metal3 ;',
            f'      RECT {x0:.3f} {y0:.3f} {x1:.3f} {y0 + 0.07:.3f} ;',
            '    END',
            f'  END {pin}',
        ]
    lines += [f'END {name}', '', 'END LIBRARY', '']
    return '\n'.join(lines)


def generate(rtl, output):
    output.mkdir(parents=True, exist_ok=True)
    macros = catalog()
    wrappers, bindings, used = [], [], {}
    for path in sorted(rtl.glob('SinglePortMaskedRam_*.sv')):
        match = PATTERN.fullmatch(path.stem)
        if not match:
            raise ValueError(f'Unexpected RAM name: {path.name}')
        depth, lanes, lane_bits = map(int, match.groups())
        width = lanes * lane_bits
        address = max(1, (depth - 1).bit_length())
        parts = choose(depth, width, macros)
        mask_present = re.search(r'\bmask\b', path.read_text()) is not None
        port_lines = [
            '    input clock',
            '    input enable',
            '    input write',
            f'    input [{address - 1}:0] address',
            f'    input [{width - 1}:0] dataIn',
        ]
        if mask_present:
            port_lines.append(f'    input [{lanes - 1}:0] mask')
        port_lines.append(f'    output [{width - 1}:0] dataOut')
        lines = [f"module {path.stem} (\n" + ',\n'.join(port_lines) + '\n);']
        mask_parts = [f'{{{lane_bits}{{mask[{lane}]}}}}' for lane in reversed(range(lanes))]
        if mask_present:
            mask_expression = mask_parts[0] if lanes == 1 else '{' + ', '.join(mask_parts) + '}'
        else:
            mask_expression = f"{{{width}{{1'b1}}}}"
        lines.append(f"    wire [{width - 1}:0] bitMask = {mask_expression};")
        offset = 0
        for i, macro in enumerate(parts):
            used[macro['name']] = macro
            bits = min(macro['width'], width - offset)
            padding = macro['width'] - bits
            addr_padding = max(1, (macro['depth'] - 1).bit_length()) - address
            def padded(signal, count):
                return f"{{{count}'b0, {signal}}}" if count else signal
            data = padded(f'dataIn[{offset + bits - 1}:{offset}]', padding)
            mask = padded(f'bitMask[{offset + bits - 1}:{offset}]', padding)
            lines += [f"    wire [{address - 1}:0] address_{i};",
                      f"    wire enable_{i};",
                      f"    wire write_{i};"]
            for bit in range(address):
                lines.append(
                    f"    BUF_X1 address_buffer_{i}_{bit} (.A(address[{bit}]), .Z(address_{i}[{bit}]));"
                )
            lines += [f"    BUF_X1 enable_buffer_{i} (.A(enable), .Z(enable_{i}));",
                      f"    BUF_X1 write_buffer_{i} (.A(write), .Z(write_{i}));",
                      f"    wire [{macro['width'] - 1}:0] data_{i};",
                      f"    {macro['name']} memory_{i} (",
                      f"        .clk(clock), .ce_in(enable_{i}), .we_in(write_{i}),",
                      f"        .addr_in({padded(f'address_{i}', addr_padding)}),",
                      f"        .wd_in({data}), .w_mask_in({mask}), .rd_out(data_{i})",
                      '    );',
                      f'    assign dataOut[{offset + bits - 1}:{offset}] = data_{i}[{bits - 1}:0];']
            offset += bits
        lines.append('endmodule')
        wrappers.append('\n'.join(lines))
        bindings.append({'module': path.stem, 'logical_depth': depth, 'logical_width': width,
                         'macros': [m['name'] for m in parts],
                         'area_per_instance_um2': sum(m['area_um2'] for m in parts),
                         'physical_bits_per_instance': sum(m['depth'] * m['width'] for m in parts)})
    if not bindings:
        raise ValueError('No synchronous RAM wrappers were emitted')

    for name, depth, width, mask_width in DUAL_PORT_MACROS:
        exact_depth = [macro for macro in macros if macro['depth'] == depth]
        parts = choose(depth, width, exact_depth or macros)
        base = max(parts, key=lambda macro: _timing_parameters(macro)['clock_to_q_ns'])
        path = output / f'{name}.lib'
        path.write_text(_dual_port_liberty(name, depth, width, mask_width, base))
        lef_path = output / f'{name}.lef'
        estimated_area = 2.0 * sum(macro['area_um2'] for macro in parts)
        lef_path.write_text(_dual_port_lef(name, depth, width, mask_width, estimated_area))
        derived = {
            'name': name,
            'path': str(path),
            'depth': depth,
            'width': width,
            'area_um2': estimated_area,
            'lef': str(lef_path),
            'source_macros': [macro['name'] for macro in parts],
            'kind': 'BSG Fakeram-derived 1RW+1R logic-only timing model',
        }
        used[name] = derived
        bindings.append({
            'module': name,
            'logical_depth': depth,
            'logical_width': width,
            'ports': '1RW+1R',
            'macros': [name],
            'source_macros': derived['source_macros'],
            'area_per_instance_um2': derived['area_um2'],
            'physical_bits_per_instance': 2 * sum(
                macro['depth'] * macro['width'] for macro in parts
            ),
        })
    (output / 'wrappers.sv').write_text('\n\n'.join(wrappers) + '\n')
    blackboxes, models = [], []
    for name, macro in sorted(used.items()):
        if macro.get('ports') == '1RW+1R' or name.startswith('fakeram45_1rw1r_'):
            continue
        signature = f"module {name} (\n{ports(macro['depth'], macro['width'])}\n);"
        blackboxes.append('(* blackbox *) ' + signature + '\nendmodule')
        models.append(signature + f'''
    reg [{macro['width'] - 1}:0] memory [0:{macro['depth'] - 1}];
    always @(posedge clk) begin
        if (ce_in && !we_in) rd_out <= memory[addr_in];
        else rd_out <= 'x;
        if (ce_in && we_in) begin
            for (integer bit_index = 0; bit_index < {macro['width']}; bit_index = bit_index + 1)
                if (w_mask_in[bit_index]) memory[addr_in][bit_index] <= wd_in[bit_index];
        end
    end
endmodule''')
    (output / 'blackboxes.sv').write_text('\n\n'.join(blackboxes) + '\n')
    (output / 'models.sv').write_text('\n\n'.join(models) + '\n')
    result = {'kind': 'BSG Fakeram-only logic timing; functional RTL retains the project interfaces',
              'bindings': bindings, 'libraries': used}
    (output / 'bindings.json').write_text(json.dumps(result, indent=4) + '\n')
    return result
