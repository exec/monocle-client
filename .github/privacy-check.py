#!/usr/bin/env python3
"""Publication guard for obvious private runtime files, credentials and locations.

This is a heuristic, not a secret scanner or a way to erase published Git history.
"""

import pathlib
import re
import subprocess
import sys

NUMBER = r"[-+]?\d[\d_]*(?:\.\d+)?(?:[eE][-+]?\d+)?"
COORDINATES = re.compile(
    r"(?<![\w.])" +
    r"(?:[\"']?x[\"']?\s*[:=]\s*)?(" + NUMBER + r")\s*,\s*" +
    r"(?:[\"']?y[\"']?\s*[:=]\s*)?(" + NUMBER + r")\s*,\s*" +
    r"(?:[\"']?z[\"']?\s*[:=]\s*)?(" + NUMBER + r")(?![\w.])",
    re.IGNORECASE,
)
SECRET = re.compile(
    r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|"
    r"xox[baprs]-[A-Za-z0-9-]{20,})\b|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
)
PRIVATE_FILES = {"host-config.json", "host-tasks.json", "bot-worker-tasks.json"}


def findings(path, content):
    name = pathlib.PurePosixPath(path).name
    if name in PRIVATE_FILES or re.search(r"\.(?:sqlite|db)(?:-wal|-shm|\.initialized)?$", name) or name == '.env' or name.startswith('.env.') and name != '.env.example':
        return [(0, "private runtime/configuration file")]
    if '/' not in path and name.startswith('BUILDING_'):
        return [(0, "personal inventory report")]
    try:
        lines = content.decode('utf-8').splitlines()
    except UnicodeDecodeError:
        return []
    result = []
    for index, line in enumerate(lines):
        if SECRET.search(line):
            result.append((index + 1, "credential-like value"))
        # Fixture locations must be intentional, not copied from live telemetry.
        if not (path.startswith('docs/') or '/src/test/' in '/' + path):
            continue
        context = '\n'.join(lines[max(0, index - 4):index + 1])
        synthetic = re.search(r"synthetic[^\n]*(?:coordinates|world-edge)|synthetic example", context, re.IGNORECASE)
        for match in COORDINATES.finditer(line):
            x, y, z = (float(value.replace('_', '')) for value in match.groups())
            if -2048 <= y <= 2048 and min(abs(x), abs(z)) > 4096 and not synthetic:
                result.append((index + 1, "location-like literal; redact it or use explicitly labeled synthetic coordinates"))
    return result


def self_test():
    point = f'{9_000_000},64,{-9_000_000}'
    assert findings('docs/incident.md', point.encode())
    assert findings('src/test/Example.java', ('new BlockPos(' + point + ')').encode())
    assert findings('docs/example.md', f'x: {9_000_000}, y: 64, z: {-9_000_000}'.encode())
    assert not findings('src/test/Example.java', ('// Synthetic coordinates for precision\nnew BlockPos(' + point + ')').encode())
    assert not findings('docs/example.md', b'128,64,-96')
    assert findings('data/stash.sqlite-wal', b'')
    assert findings('data/tasks.sqlite.initialized', b'{}')
    assert findings('host-config.json', b'{}')
    assert findings('BUILDING_RESOURCES.txt', b'')
    assert findings('example.txt', ('ghp_' + 'A' * 36).encode())
    assert not findings('.env.example', b'TOKEN=YOUR_TOKEN_HERE')
    assert not findings('src/test/Example.java', b'10000000,65536,1728000')


if __name__ == '__main__':
    self_test()
    if '--self-test' in sys.argv:
        print('Publication privacy guard self-tests passed.')
        sys.exit(0)
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
    failures = []
    for path in sorted(set(paths) - {''}):
        file = pathlib.Path(path)
        if file.is_file():
            failures.extend((path, line, reason) for line, reason in findings(path, file.read_bytes()))
    for path, line, reason in failures:
        print(f'{path}:{line}: {reason}')  # Do not echo credentials or private coordinates.
    print(f'Publication privacy guard: {len(failures)} finding(s).')
    sys.exit(bool(failures))
