"""Repair standard-cell capacitance limits using function-preserving buffer trees."""

from collections import defaultdict
import json
from pathlib import Path

from eda.adder_mapping import quote
from eda.frontend_macro_buffers import standard_pins


def repair(directory, top_name, output_load, run, yosys):
    directory = Path(directory)
    path = directory / 'full-mapped.json'
    design = json.loads(path.read_text())
    top = design['modules'][top_name]
    pins = standard_pins()
    loads = defaultdict(list)
    for name, cell in top['cells'].items():
        for pin, bits in cell['connections'].items():
            if cell['port_directions'][pin] != 'input':
                continue
            cap = (25.0 if pin == 'clk' else 5.0) if cell['type'].startswith('fakeram45_') else pins[cell['type']][pin]['cap']
            for index, bit in enumerate(bits):
                if isinstance(bit, int):
                    loads[bit].append((bits, index, cap))
    for port in top['ports'].values():
        if port['direction'] == 'output':
            for index, bit in enumerate(port['bits']):
                if isinstance(bit, int):
                    loads[bit].append((port['bits'], index, output_load))
    next_bit = 1 + max(bit for cell in top['cells'].values() for bits in cell['connections'].values()
                       for bit in bits if isinstance(bit, int))
    changes = []
    inserted = 0

    def buffer(sinks):
        nonlocal next_bit, inserted
        bit = next_bit
        next_bit += 1
        name = f'capacitance_buffer_{inserted}'
        inserted += 1
        for bits, index, _ in sinks:
            bits[index] = bit
        connections = {'A': [0], 'Z': [bit]}
        top['cells'][name] = {'hide_name': 0, 'type': 'BUF_X4', 'parameters': {}, 'attributes': {},
                              'port_directions': {'A': 'input', 'Z': 'output'}, 'connections': connections}
        top['netnames'][name + '_out'] = {'hide_name': 0, 'bits': [bit], 'attributes': {}}
        return connections['A'], 0, pins['BUF_X4']['A']['cap']

    drivers = list(top['cells'].items())
    drivers += [('input:' + name, {'type': 'BUF_X1', 'port_directions': {'Z': 'output'},
                                   'connections': {'Z': port['bits']}})
                for name, port in top['ports'].items() if port['direction'] == 'input' and name != 'clock']
    for name, cell in drivers:
        if cell['type'].startswith('fakeram45_'):
            continue
        for pin, bits in cell['connections'].items():
            limit = pins[cell['type']][pin]['limit']
            if cell['port_directions'][pin] != 'output' or limit is None:
                continue
            for bit in bits:
                sinks = loads[bit]
                total = sum(sink[2] for sink in sinks)
                if total <= limit:
                    continue
                if pins['BUF_X4']['A']['cap'] > limit:
                    raise ValueError('Buffer input exceeds source capacitance limit')
                changes.append({'cell': name, 'pin': pin, 'before_ff': total, 'limit_ff': limit})
                while True:
                    groups, group, cap = [], [], 0.0
                    for sink in sinks:
                        if sink[2] > 20.0:
                            raise ValueError('Individual non-clock sink exceeds buffer leaf budget')
                        if group and cap + sink[2] > 20.0:
                            groups.append(group)
                            group, cap = [], 0.0
                        group.append(sink)
                        cap += sink[2]
                    if group:
                        groups.append(group)
                    sinks = [buffer(group) for group in groups]
                    if sum(sink[2] for sink in sinks) <= min(limit, 20.0):
                        break
                for connection, index, _ in sinks:
                    connection[index] = bit
    if inserted:
        for name, port in top['ports'].items():
            top['netnames'][name]['bits'] = list(port['bits'])
        path.write_text(json.dumps(design) + '\n')
        script = directory / 'capacitance-buffers.ys'
        script.write_text(f'read_json {quote(path)}\nhierarchy -top {top_name}\ncheck -assert\n'
                          f'write_verilog -noattr -noexpr {quote(directory / "full-mapped.v")}\n')
        run([yosys, '-Q', '-T', '-s', str(script)], directory / 'capacitance-buffers.log')
    result = {'buffer': 'BUF_X4', 'inserted': inserted, 'changes': changes,
              'scope': 'Same 20 fF leaf budget for every mapping; clock distribution remains ideal.'}
    (directory / 'capacitance-buffers.json').write_text(json.dumps(result, indent=4) + '\n')
    return result
