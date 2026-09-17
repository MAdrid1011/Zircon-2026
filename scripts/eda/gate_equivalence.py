"""Check a mapped design at its actual register and memory boundaries."""

import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re

from eda.adder_mapping import quote


def boundaries(module):
    registers = {}
    memories = {}
    for name, cell in module['cells'].items():
        kind = cell['type']
        if kind == '$_DFF_P_' or kind.startswith('DFF'):
            if len(cell['connections']['Q']) != 1 or len(cell['connections']['D']) != 1:
                raise ValueError(f'Unsupported register: {name}')
            clock = cell['connections'].get('C', cell['connections'].get('CK'))
            if clock != module['ports']['clock']['bits']:
                raise ValueError('Expected one ungated positive-edge clock')
            registers[cell['connections']['Q'][0]] = (name, cell['connections']['D'][0])
        elif kind.startswith('fakeram45_'):
            memories[name] = cell
        elif 'DFF' in kind or 'LATCH' in kind:
            raise ValueError(f'Unsupported state: {name} {kind}')
    return registers, memories


def aliases(module):
    result = {}
    for name, net in module['netnames'].items():
        if name.startswith('$') or name.startswith('_') or net.get('hide_name'):
            continue
        for index, bit in enumerate(net['bits']):
            result[(name, index)] = bit
    return result


def make_cuts(reference, mapped, top, output):
    gold = json.loads(Path(reference).read_text())['modules'][top]
    gate = json.loads(Path(mapped).read_text())['modules'][top]
    gr, gm = boundaries(gold)
    cr, cm = boundaries(gate)
    if len(gr) != len(cr) or set(gm) != set(cm):
        raise ValueError('Register or memory count changed')
    ga, ca = aliases(gold), aliases(gate)
    buffers = {cell['connections']['Z'][0]: cell['connections']['A'][0]
               for cell in gate['cells'].values() if cell['type'].startswith('BUF_X')}

    def state_source(bit):
        visited = set()
        while bit in buffers:
            if bit in visited:
                raise ValueError('Buffer cycle in state correspondence')
            visited.add(bit)
            bit = buffers[bit]
        return bit

    matches = {}
    for label, bit in sorted(ga.items()):
        # Output buffering can leave the public state alias after a pure buffer.
        candidate = state_source(ca[label]) if label in ca else None
        if bit in gr and candidate in cr:
            if bit in matches and matches[bit] != candidate:
                raise ValueError('Ambiguous state alias')
            matches[bit] = candidate
    if set(matches) != set(gr) or set(matches.values()) != set(cr):
        raise ValueError('Every state bit must have an unchanged named correspondence')
    pairs = sorted(matches.items())
    memory_inputs, memory_outputs = {}, {}
    for name in sorted(gm):
        a, b = gm[name], cm[name]
        if a['type'] != b['type'] or a['port_directions'] != b['port_directions']:
            raise ValueError('Memory interface changed')
        for pin in sorted(a['port_directions']):
            if len(a['connections'][pin]) != len(b['connections'][pin]):
                raise ValueError('Memory width changed')
            target = memory_inputs if a['port_directions'][pin] == 'input' else memory_outputs
            target[(name, pin)] = (a['connections'][pin], b['connections'][pin])
    if {p: (v['direction'], len(v['bits'])) for p, v in gold['ports'].items()} != \
            {p: (v['direction'], len(v['bits'])) for p, v in gate['ports'].items()}:
        raise ValueError('Top-level interface changed')
    for index, (module, registers, memories, name) in enumerate(((gold, gr, gm, 'gold'), (gate, cr, cm, 'gate'))):
        removed = {value[0] for value in registers.values()} | set(memories)
        ports = copy.deepcopy(module['ports'])
        if pairs:
            states = [pair[index] for pair in pairs]
            ports['proof_state'] = {'direction': 'input', 'bits': states}
            ports['proof_next'] = {'direction': 'output', 'bits': [registers[q][1] for q in states]}
        for i, bits in enumerate(memory_outputs.values()):
            ports[f'proof_memory_read_{i}'] = {'direction': 'input', 'bits': bits[index]}
        for i, bits in enumerate(memory_inputs.values()):
            ports[f'proof_memory_command_{i}'] = {'direction': 'output', 'bits': bits[index]}
        cut = {'attributes': {}, 'ports': ports, 'netnames': {},
               'cells': {n: c for n, c in module['cells'].items() if n not in removed}}
        for register, _ in registers.values():
            connections = module['cells'][register]['connections']
            if connections.get('QN'):
                if module['cells'][register]['type'] != 'DFF_X1':
                    raise ValueError('Only Nangate DFF_X1 complemented output is modeled')
                cut['cells']['proof_inverse_' + register] = {
                    'hide_name': 0, 'type': '$_NOT_', 'parameters': {}, 'attributes': {},
                    'port_directions': {'A': 'input', 'Y': 'output'},
                    'connections': {'A': connections['Q'], 'Y': connections['QN']},
                }
        (Path(output) / f'{name}-cut.json').write_text(json.dumps({'modules': {name: cut}}))
    return {'register_bits': len(pairs), 'memory_instances': len(gm),
            'memory_input_groups': len(memory_inputs), 'memory_output_groups': len(memory_outputs)}


def prove_cut(directory, library, run, yosys):
    script = f'''read_liberty -ignore_miss_func {quote(library)}
read_json {quote(directory / 'gold-cut.json')}
read_json {quote(directory / 'gate-cut.json')}
miter -equiv -ignore_gold_x -flatten gold gate miter
prep -top miter
sat -verify -prove trigger 0 -set-def-inputs -timeout 120
'''
    path = directory / 'equivalence.ys'
    path.write_text(script)
    hashes = {name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
              for name in ('gold-cut.json', 'gate-cut.json', 'equivalence.ys')}
    hashes['library'] = hashlib.sha256(Path(library).read_bytes()).hexdigest()
    cache = directory / 'proof-cache.json'
    log_path = directory / 'equivalence.log'
    if cache.exists() and log_path.exists():
        saved = json.loads(cache.read_text())
        if saved['inputs'] == hashes and saved['log_sha256'] == hashlib.sha256(log_path.read_bytes()).hexdigest():
            if 'SAT proof finished - no model found: SUCCESS!' in log_path.read_text():
                return
    log = run([yosys, '-Q', '-T', '-s', str(path)], directory / 'equivalence.log')
    if 'SAT proof finished - no model found: SUCCESS!' not in log:
        raise RuntimeError(f'Equivalence did not complete: {directory}')
    cache.write_text(json.dumps({'inputs': hashes, 'log_sha256': hashlib.sha256(log_path.read_bytes()).hexdigest()},
                               indent=4) + '\n')


def partition(directory, width=512):
    modules = [json.loads((directory / f'{name}-cut.json').read_text())['modules'][name]
               for name in ('gold', 'gate')]
    outputs = [(name, index) for name, port in modules[0]['ports'].items()
               if port['direction'] == 'output' for index in range(len(port['bits']))]
    inputs = [name for name, port in modules[0]['ports'].items() if port['direction'] == 'input']
    drivers = []
    for module in modules:
        lookup = {}
        for name, cell in module['cells'].items():
            for pin, bits in cell['connections'].items():
                if cell['port_directions'][pin] == 'output':
                    for bit in bits:
                        if isinstance(bit, int):
                            if bit in lookup and lookup[bit] != name:
                                raise ValueError('Multiple drivers in proof cone')
                            lookup[bit] = name
        drivers.append(lookup)
    groups = []
    for start in range(0, len(outputs), width):
        selected = outputs[start:start + width]
        cones, used = [], []
        for module, lookup in zip(modules, drivers):
            pending = [module['ports'][name]['bits'][index] for name, index in selected]
            bits, cells = set(), set()
            while pending:
                bit = pending.pop()
                if not isinstance(bit, int) or bit in bits:
                    continue
                bits.add(bit)
                name = lookup.get(bit)
                if name is not None and name not in cells:
                    cells.add(name)
                    cell = module['cells'][name]
                    pending.extend(b for pin, vector in cell['connections'].items()
                                   if cell['port_directions'][pin] == 'input' for b in vector)
            cones.append(cells)
            used.append(bits)
        input_indices = {name: [i for i in range(len(modules[0]['ports'][name]['bits']))
                               if any(module['ports'][name]['bits'][i] in bits
                                      for module, bits in zip(modules, used))] for name in inputs}
        out = directory / f'part-{len(groups):03}'
        out.mkdir(exist_ok=True)
        for index, (name, module, cells) in enumerate(zip(('gold', 'gate'), modules, cones)):
            ports = {pin: {'direction': 'input', 'bits': [module['ports'][pin]['bits'][i] for i in indices]}
                     for pin, indices in input_indices.items() if indices}
            ports['proof_outputs'] = {'direction': 'output',
                                      'bits': [module['ports'][pin]['bits'][i] for pin, i in selected]}
            cut = {'attributes': {}, 'ports': ports, 'netnames': {},
                   'cells': {key: module['cells'][key] for key in sorted(cells)}}
            (out / f'{name}-cut.json').write_text(json.dumps({'modules': {name: cut}}))
        groups.append({'directory': out.name, 'outputs': selected,
                       'cell_counts': [len(cells) for cells in cones]})
    if sum(len(group['outputs']) for group in groups) != len(outputs):
        raise ValueError('Incomplete output partition coverage')
    (directory / 'partitions.json').write_text(json.dumps(groups, indent=4) + '\n')
    return groups


def prove(reference, mapped, top, library, directory, run, yosys, partition_width=None):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    cut = make_cuts(reference, mapped, top, directory)
    if partition_width is not None and partition_width <= 0:
        raise ValueError('Proof partition width must be positive')
    if cut['register_bits'] > 4096 or partition_width is not None:
        groups = partition(directory, partition_width or 512)
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(prove_cut, directory / group['directory'], library, run, yosys) for group in groups]
            for future in futures:
                future.result()
        lines = []
        for group in groups:
            log = directory / group['directory'] / 'equivalence.log'
            lines.append(f"{group['directory']}: PASS {len(group['outputs'])} output bits "
                         f"sha256={hashlib.sha256(log.read_bytes()).hexdigest()}")
        lines.append('PASS: every output, next-state bit and memory input is covered by a successful partition.')
        (directory / 'equivalence.log').write_text('\n'.join(lines) + '\n')
        cut['partitions'] = len(groups)
        cut['proven_output_bits'] = sum(len(group['outputs']) for group in groups)
    else:
        prove_cut(directory, library, run, yosys)
    return {**cut, 'status': 'PASS',
            'scope': 'All defined primary inputs, named register states and memory output bits; '
                     'compare outputs, next states and every memory input. Undefined reference outputs ignored.'}
