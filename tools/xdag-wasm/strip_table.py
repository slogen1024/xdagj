#!/usr/bin/env python3
"""Drop wasm-ld's unused funcref table.

rustc 1.96 emits a one-entry table even when the module never uses
call_indirect. Chicory decodes that 0x70 element type as RefNull, and
ModuleValidator rejects it. Section 4 is removed only when the code
section has no call_indirect (0x11) and there is no element section.
"""

import pathlib
import sys


def uleb(buf, i):
    n = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        if b < 128:
            return n, i
        shift += 7


def encode_uleb(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def sections(data):
    if data[:4] != b"\0asm":
        raise SystemExit("not wasm")
    i = 8
    out = []
    while i < len(data):
        sid = data[i]
        i += 1
        size, start = uleb(data, i)
        out.append((sid, data[start : start + size]))
        i = start + size
    return out


def uses_call_indirect(code):
    i = 0
    count, i = uleb(code, i)
    for _ in range(count):
        size, i = uleb(code, i)
        if b"\x11" in code[i : i + size]:
            return True
        i += size
    return False


def pack(secs):
    body = bytearray(b"\0asm\x01\x00\x00\x00")
    for sid, payload in secs:
        body.append(sid)
        body += encode_uleb(len(payload))
        body += payload
    return bytes(body)


def main(paths):
    for raw in paths:
        path = pathlib.Path(raw)
        data = path.read_bytes()
        secs = sections(data)
        ids = [sid for sid, _ in secs]
        if 9 in ids:
            raise SystemExit(f"{path} has an element section")
        code = dict(secs).get(10)
        if code is not None and uses_call_indirect(code):
            raise SystemExit(f"{path} uses call_indirect")
        if 4 not in ids:
            continue
        path.write_bytes(pack([(sid, payload) for sid, payload in secs if sid != 4]))


if __name__ == "__main__":
    main(sys.argv[1:])
