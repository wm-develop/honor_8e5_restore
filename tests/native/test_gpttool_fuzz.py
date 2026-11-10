"""Host-only native GPT snapshot fuzzing; no devices or block writes."""
from __future__ import annotations

import json
import os
from pathlib import Path
import random
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCE = ROOT / 'app/src/main/cpp/gpttool.c'
CC = os.environ.get('CC', 'clang')


def main() -> None:
    clean = (ROOT / 'ublbackup_20261029_233101/gpt_sda.raw').read_bytes()
    modified = (ROOT / 'ublbackup_20261030_131440/gpt_sda.raw').read_bytes()
    randomizer = random.Random(0x475054)
    with tempfile.TemporaryDirectory(prefix='gpt-native-fuzz-', dir=HERE) as temporary:
        folder = Path(temporary)
        driver = folder / 'snapshot-fuzz-driver.c'
        include = json.dumps(SOURCE.as_posix())
        driver.write_text(r'''
#define main gpttool_cli_main
#include INCLUDE_SOURCE
#undef main
int main(int argc, char **argv) {
    if (argc != 4) return 125;
    int count = atoi(argv[2]), valid = 0, invalid = 0, unsafe = 0;
    struct snapshot before = {0};
    if (!load_snapshot(argv[3], &before)) return 123;
    for (int index = 0; index < count; index++) {
        char path[1024];
        int length = snprintf(path, sizeof(path), "%s/fuzz-%04d.bin", argv[1], index);
        if (length < 0 || (size_t)length >= sizeof(path)) return 124;
        struct snapshot snapshot = {0};
        if (load_snapshot(path, &snapshot)) {
            valid++;
            if (index >= 200 && index < 600 && restore_transition(&before, &snapshot)) unsafe++;
            free_snapshot(&snapshot);
        } else {
            invalid++;
        }
    }
    free_snapshot(&before);
    printf("PROCESSED=%d\nVALID=%d\nINVALID=%d\nUNSAFE_TRANSITIONS=%d\nRESULT=success\n", count, valid, invalid, unsafe);
    return 0;
}
'''.replace('INCLUDE_SOURCE', include), encoding='utf-8', newline='\n')
        executable = folder / ('snapshot-fuzz.exe' if os.name == 'nt' else 'snapshot-fuzz')
        compiled = subprocess.run([CC, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                                   str(driver), '-o', str(executable)], capture_output=True)
        if compiled.returncode:
            raise AssertionError(compiled.stdout.decode(errors='replace') + compiled.stderr.decode(errors='replace'))
        total = 1002
        for index in range(total):
            if index < 200:
                raw = bytearray(randomizer.randbytes(randomizer.randrange(0, 65537)))
                if len(raw) >= 8 and index % 2:
                    raw[:8] = b'HUBLGT22'
            elif index < 600:
                raw = bytearray(modified)
                for _ in range(1 + index % 16):
                    at = randomizer.randrange(len(raw))
                    raw[at] ^= 1 << randomizer.randrange(8)
            elif index < 1000:
                raw = bytearray(clean)
                for _ in range(12):
                    at = randomizer.randrange(min(len(raw), 512 + 2 * 4096))
                    raw[at] = randomizer.randrange(256)
                if index % 3 == 0:
                    raw = raw[:randomizer.randrange(len(raw) + 1)]
            elif index == 1000:
                raw = bytearray(clean)
            else:
                raw = bytearray(modified)
            (folder / f'fuzz-{index:04d}.bin').write_bytes(raw)
        environment = os.environ.copy()
        if 'asan' in Path(CC).name.lower():
            environment['ASAN_OPTIONS'] = 'detect_leaks=0:halt_on_error=1:abort_on_error=1'
        result = subprocess.run([str(executable), str(folder), str(total), str(ROOT / 'ublbackup_20261030_131440/gpt_sda.raw')],
                                capture_output=True, env=environment, timeout=180)
        output = result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace')
        if result.returncode or b'PROCESSED=1002' not in result.stdout or b'UNSAFE_TRANSITIONS=0' not in result.stdout or b'RESULT=success' not in result.stdout:
            raise AssertionError(output)
        values = dict(line.split('=', 1) for line in result.stdout.decode().splitlines() if '=' in line)
        assert int(values['VALID']) >= 2, output
        assert int(values['VALID']) + int(values['INVALID']) == total, output
        assert 'AddressSanitizer' not in output and 'runtime error' not in output, output
        print(f"PASS: {total} native GPT snapshot fuzz cases; {values['VALID']} structurally accepted, {values['INVALID']} rejected")


if __name__ == '__main__':
    main()
