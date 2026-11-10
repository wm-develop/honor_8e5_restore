"""Host-only partition image write and fault-injection fixtures; never use ADB or block devices."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCE = ROOT / 'app/src/main/cpp/partition_worker.c'
CC = os.environ.get('CC', 'clang')
CHUNK = 65536


def main() -> None:
    checks = []

    def check(name: str, condition: bool) -> None:
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    with tempfile.TemporaryDirectory(prefix='partition-restore-fixtures-', dir=HERE) as temporary:
        folder = Path(temporary)
        harness = folder / 'partition-write-harness.c'
        include = json.dumps(SOURCE.as_posix())
        harness.write_text(r'''
#include <io.h>
#include <fcntl.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
static const char *target_path;
static int target_fd = -1, write_calls, read_calls;
static int fail_write, fail_read, corrupt_read, fail_commit;
static int fault_open(const char *path, int flags, ...) {
    int mode = 0;
    va_list args;
    va_start(args, flags);
    if (flags & _O_CREAT) mode = va_arg(args, int);
    va_end(args);
    int fd = _open(path, flags, mode);
    if (!strcmp(path, target_path)) target_fd = fd;
    return fd;
}
static int fault_read(int fd, void *buffer, unsigned size) {
    read_calls++;
    if (fail_read == read_calls) { errno = EIO; return -1; }
    int result = _read(fd, buffer, size);
    if (result > 0 && corrupt_read && fd == target_fd) ((unsigned char *)buffer)[0] ^= 1;
    return result;
}
static int fault_write(int fd, const void *buffer, unsigned size) {
    write_calls++;
    if (fail_write == write_calls) { errno = EIO; return -1; }
    return _write(fd, buffer, size);
}
static int fault_commit(int fd) {
    if (fail_commit) { errno = EIO; return -1; }
    return _commit(fd);
}
#define _open fault_open
#define _read fault_read
#define _write fault_write
#define _commit fault_commit
#define main partition_worker_cli_main
#include INCLUDE_SOURCE
#undef main
#undef _open
#undef _read
#undef _write
#undef _commit
int main(int argc, char **argv) {
    uint64_t offset, capacity;
    if (argc != 10 || !parse64(argv[2], &offset) || !parse64(argv[3], &capacity)) return 125;
    target_path = argv[1];
    fail_write = atoi(argv[6]); fail_read = atoi(argv[7]);
    corrupt_read = atoi(argv[8]); fail_commit = atoi(argv[9]);
    return write_range(argv[1], offset, capacity, argv[4], atoi(argv[5]) != 0, true);
}
'''.replace('INCLUDE_SOURCE', include), encoding='utf-8', newline='\n')
        suffix = '.exe' if os.name == 'nt' else ''
        executable = folder / ('partition-write-harness' + suffix)
        worker = folder / ('partition-worker' + suffix)
        for source, output in ((harness, executable), (SOURCE, worker)):
            compiled = subprocess.run([CC, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                                       str(source), '-o', str(output)], capture_output=True)
            check('compile ' + output.name, compiled.returncode == 0)
            if compiled.returncode:
                raise AssertionError(compiled.stdout.decode(errors='replace') + compiled.stderr.decode(errors='replace'))
        self_test = subprocess.run([str(worker), '--self-test'], capture_output=True)
        check('native worker self-test', self_test.returncode == 0 and b'RESULT=success' in self_test.stdout)

        original = bytes((index * 37 + 11) % 251 for index in range(512 * 1024))
        image = bytes((index * 13 + 7) % 251 for index in range(256 * 1024))
        target = folder / 'target.img'
        source = folder / 'source.img'
        offset = 4096

        def reset(source_bytes: bytes = image) -> None:
            target.write_bytes(original)
            source.write_bytes(source_bytes)

        def run(execute: int = 1, capacity: int | None = None, fail_write: int = 0,
                fail_read: int = 0, corrupt_read: int = 0, fail_commit: int = 0,
                source_path: Path = source, target_path: Path = target, at: int = offset) -> subprocess.CompletedProcess:
            return subprocess.run([str(executable), str(target_path), str(at), str(len(image) if capacity is None else capacity),
                                   str(source_path), str(execute), str(fail_write), str(fail_read), str(corrupt_read), str(fail_commit)],
                                  capture_output=True, timeout=30)

        def successful_write(name: str) -> None:
            expected = original[:offset] + image + original[offset + len(image):]
            check(name + ' exact target bytes', target.read_bytes() == expected)
            check(name + ' prefix preserved', target.read_bytes()[:offset] == original[:offset])
            check(name + ' suffix preserved', target.read_bytes()[offset + len(image):] == original[offset + len(image):])

        reset()
        result = run()
        check('full write exits successfully', result.returncode == 0)
        check('full write reports direct-style verified readback', b'READBACK=match' in result.stdout and b'RESULT=success' in result.stdout)
        successful_write('full write')

        reset()
        result = run(execute=0)
        check('dry run reports planned', result.returncode == 0 and b'RESULT=planned' in result.stdout)
        check('dry run leaves every byte unchanged', target.read_bytes() == original)

        for name, capacity, at in (
            ('image exceeds capacity', len(image) - 512, offset),
            ('capacity exceeds target', len(original), offset),
            ('unaligned offset', len(image), 1),
            ('zero capacity', 0, offset),
            ('unaligned capacity', 513, offset),
            ('overflowing range', len(image), (1 << 64) - 512),
        ):
            reset()
            result = run(capacity=capacity, at=at)
            check(name + ' rejected', result.returncode != 0 and b'RESULT=success' not in result.stdout)
            check(name + ' rejected before modification', target.read_bytes() == original)

        reset()
        result = run(capacity=len(original), source_path=target, at=0)
        check('image alias target rejected', result.returncode != 0 and target.read_bytes() == original)
        reset(b'')
        result = run(capacity=512)
        check('empty image rejected', result.returncode != 0 and target.read_bytes() == original)
        reset()
        result = run(source_path=folder / 'missing.img')
        check('missing image rejected before modification', result.returncode != 0 and target.read_bytes() == original)

        reset()
        result = run(fail_write=1)
        check('first write failure does not modify target', result.returncode != 0 and target.read_bytes() == original)
        check('first write failure never reports success', b'RESULT=success' not in result.stdout and b'READBACK=match' not in result.stdout)

        reset()
        result = run(fail_write=2)
        partial = original[:offset] + image[:CHUNK] + original[offset + CHUNK:]
        check('mid-write failure is reported', result.returncode != 0 and b'RESULT=success' not in result.stdout)
        check('mid-write failure exposes bounded partial state', target.read_bytes() == partial)

        reset()
        result = run(fail_commit=1)
        check('flush failure is reported without success', result.returncode != 0 and b'RESULT=success' not in result.stdout)
        check('flush failure cannot use planned dry-run result', b'RESULT=planned' not in result.stdout)

        reset()
        result = run(corrupt_read=1)
        check('readback corruption is rejected', result.returncode != 0 and b'READBACK=match' not in result.stdout)
        check('readback corruption never reports success', b'RESULT=success' not in result.stdout)

        reset()
        result = run(fail_read=3)
        check('readback I/O failure is rejected', result.returncode != 0 and b'RESULT=success' not in result.stdout)

        short = image[:1000]
        reset(short)
        result = subprocess.run([str(executable), str(target), '0', '4096', str(source), '1', '0', '0', '0', '0'],
                                capture_output=True, timeout=30)
        check('unaligned short image tail succeeds safely', result.returncode == 0 and b'READBACK=match' in result.stdout)
        check('last sector bytes beyond short image are preserved', target.read_bytes() == short + original[len(short):])

        target.write_bytes(original)
        readback = subprocess.run([str(worker), 'read-range', str(target), '0', str(len(original)), '--fixture'], capture_output=True)
        check('read-range returns exact bytes', readback.returncode == 0 and readback.stdout == original)
        check('read-range does not modify target', target.read_bytes() == original)

    print(f'PASS: {len(checks)} partition worker write, bounds and fault-injection checks')


if __name__ == '__main__':
    main()
