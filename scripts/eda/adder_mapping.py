"""Default standard-cell mapping policy for BLevel parallel adders."""

from dataclasses import dataclass
from pathlib import Path
import re

DEFAULT_MODE = 'direct'
DEFAULT_DELAY_PS = 1000


def quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'


@dataclass(frozen=True)
class AdderMapping:
    modules: tuple
    mode: str = DEFAULT_MODE
    delay_ps: float | None = DEFAULT_DELAY_PS

    def __post_init__(self):
        if self.mode not in ('flat', 'isolated', 'direct'):
            raise ValueError('Mapping mode must be flat, isolated or direct')
        if self.delay_ps is not None and self.delay_ps <= 0:
            raise ValueError('Delay target must be positive')

    @classmethod
    def discover(cls, files, mode=DEFAULT_MODE, delay_ps=DEFAULT_DELAY_PS):
        modules = set()
        for path in files:
            modules.update(re.findall(r'\bmodule\s+(BLevelPAdder(?:32|33|64)(?:_\d+)?)\s*\(', Path(path).read_text()))
        return cls(tuple(sorted(modules)), mode, delay_ps)

    def preserve(self, additional_modules=()):
        modules = tuple(sorted(set(self.modules) | set(additional_modules)))
        if self.mode == 'flat' or not modules:
            return ''
        return 'setattr -mod -set keep_hierarchy 1 ' + ' '.join(modules)

    def flatten(self, additional_modules=()):
        modules = tuple(sorted(set(self.modules) | set(additional_modules)))
        if self.mode == 'flat' or not modules:
            return ''
        return 'setattr -mod -unset keep_hierarchy ' + ' '.join(modules) + '\nflatten\ndelete t:$scopeinfo'

    def commands(self, library, constraint, directory, additional_modules=()):
        delay = '' if self.delay_ps is None else f' -D {self.delay_ps:g}'
        command = f'abc -liberty {quote(library)} -constr {quote(constraint)}'
        result = []
        if self.mode == 'direct' and self.modules:
            script = Path(directory) / 'adder.abc'
            script.write_text(
                f'strash; &get -n; &nf{delay}; &put; buffer; upsize{delay}; dnsize{delay}; stime -p\n'
            )
            result.append(f'{command} -script {quote(script)} ' + ' '.join(self.modules))
        for module in additional_modules:
            result.append(command + delay + ' ' + module)
        result.append(command + delay)
        return '\n'.join(result)

    def manifest(self, additional_modules=()):
        return {'mode': self.mode, 'modules': list(self.modules),
                'hierarchical_modules': list(additional_modules), 'abc_delay_ps': self.delay_ps,
                'scope': 'Named BLevelPAdder modules use the direct flow; listed hierarchical modules are mapped '
                         'independently before the remaining logic.'}
