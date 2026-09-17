"""Bind the project's synchronous masked RAM interface to pinned Nangate45 estimate macros."""
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MEMORY = ROOT / 'eda/platforms/nangate45/memory'
PATTERN = re.compile(r'SinglePortMaskedRam_(\d+)_(\d+)_(\d+)')


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
        lines = [f'''module {path.stem} (
    input clock,
    input enable,
    input write,
    input [{address - 1}:0] address,
    input [{width - 1}:0] dataIn,
    input [{lanes - 1}:0] mask,
    output [{width - 1}:0] dataOut
);''']
        mask_parts = [f'{{{lane_bits}{{mask[{lane}]}}}}' for lane in reversed(range(lanes))]
        lines.append(f"    wire [{width - 1}:0] bitMask = {{{', '.join(mask_parts)}}};")
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
            lines += [f"    wire [{macro['width'] - 1}:0] data_{i};",
                      f"    {macro['name']} memory_{i} (",
                      f"        .clk(clock), .ce_in(enable), .we_in(write),",
                      f"        .addr_in({padded('address', addr_padding)}),",
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
    (output / 'wrappers.sv').write_text('\n\n'.join(wrappers) + '\n')
    blackboxes, models = [], []
    for name, macro in sorted(used.items()):
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
    result = {'kind': 'pinned architectural SRAM estimates; functional models implement the project interface',
              'bindings': bindings, 'libraries': used}
    (output / 'bindings.json').write_text(json.dumps(result, indent=4) + '\n')
    return result
