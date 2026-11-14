//! Old fixture bytes, selector 0, not the on-chain ABI. Selector 0 mints 50 or seeds 100/100,
//! `transfer` moves a balance, `swap` takes 25 of asset 0, `send` appends one outbox row with
//! value 0. This is an emitter, not a Rust-to-wasm compiler. The chain reference is `xdag-wasm`.

const TRANSFER: i32 = 1433710159;
const SWAP: i32 = -488097224;
const PING: i32 = -150857028;

pub fn token() -> Vec<u8> {
    let mut op = Op::default();
    op.call(0);
    op.i32(TRANSFER);
    op.eq32();
    op.if_void();
    op.i32(0);
    op.call(1);
    op.i32(96);
    op.i32(1);
    op.store8();
    for i in 0..20 {
        op.i32(97 + i);
        op.i32(i);
        op.load8u();
        op.store8();
    }
    op.i32(64);
    op.i32(1);
    op.store8();
    op.i32(65);
    op.call(2);
    op.i32(128);
    op.i32(20);
    op.load64();
    op.store64();
    op.i32(64);
    op.i32(21);
    op.i32(160);
    op.i32(8);
    op.call(3);
    op.drop();
    op.i32(160);
    op.load64();
    op.i32(128);
    op.load64();
    op.lt_u64();
    op.if_void();
    op.otherwise();
    op.i32(176);
    op.i32(160);
    op.load64();
    op.i32(128);
    op.load64();
    op.sub64();
    op.store64();
    op.i32(64);
    op.i32(21);
    op.i32(176);
    op.i32(8);
    op.call(4);
    op.i32(96);
    op.i32(21);
    op.i32(192);
    op.i32(8);
    op.call(3);
    op.drop();
    op.i32(200);
    op.i32(192);
    op.load64();
    op.i32(128);
    op.load64();
    op.add64();
    op.store64();
    op.i32(96);
    op.i32(21);
    op.i32(200);
    op.i32(8);
    op.call(4);
    op.end();
    op.otherwise();
    op.i32(64);
    op.i32(1);
    op.store8();
    op.i32(65);
    op.call(2);
    op.i32(128);
    op.i64(50);
    op.store64();
    op.i32(64);
    op.i32(21);
    op.i32(128);
    op.i32(8);
    op.call(4);
    op.end();
    op.end();
    module(
        &[
            ty(&[], &[I32]),
            ty(&[I32], &[]),
            ty(&[I32, I32, I32, I32], &[I32]),
            ty(&[I32, I32, I32, I32], &[]),
            ty(&[], &[]),
        ],
        &[
            ("selector", 0),
            ("input_read", 1),
            ("caller", 1),
            ("storage_get", 2),
            ("storage_set", 3),
        ],
        4,
        &op.0,
    )
}

pub fn pool() -> Vec<u8> {
    let mut op = Op::default();
    write_key(&mut op, 0, b'r', b'0');
    write_key(&mut op, 4, b'r', b'1');
    op.call(0);
    op.i32(SWAP);
    op.eq32();
    op.if_void();
    op.i32(0);
    op.i32(2);
    op.i32(16);
    op.i32(8);
    op.call(1);
    op.drop();
    op.i32(4);
    op.i32(2);
    op.i32(24);
    op.i32(8);
    op.call(1);
    op.drop();
    op.i32(32);
    op.i32(16);
    op.load64();
    op.i32(24);
    op.load64();
    op.mul64();
    op.i32(16);
    op.load64();
    op.i64(25);
    op.add64();
    op.div_u64();
    op.store64();
    op.i32(40);
    op.i32(16);
    op.load64();
    op.i64(25);
    op.add64();
    op.store64();
    op.i32(0);
    op.i32(2);
    op.i32(40);
    op.i32(8);
    op.call(2);
    op.i32(4);
    op.i32(2);
    op.i32(32);
    op.i32(8);
    op.call(2);
    op.otherwise();
    op.i32(16);
    op.i64(100);
    op.store64();
    op.i32(24);
    op.i64(100);
    op.store64();
    op.i32(0);
    op.i32(2);
    op.i32(16);
    op.i32(8);
    op.call(2);
    op.i32(4);
    op.i32(2);
    op.i32(24);
    op.i32(8);
    op.call(2);
    op.end();
    op.end();
    module(
        &[
            ty(&[], &[I32]),
            ty(&[I32, I32, I32, I32], &[I32]),
            ty(&[I32, I32, I32, I32], &[]),
            ty(&[], &[]),
        ],
        &[("selector", 0), ("storage_get", 1), ("storage_set", 2)],
        3,
        &op.0,
    )
}

pub fn inbox() -> Vec<u8> {
    let mut op = Op::default();
    fill(&mut op, 0, 20, 3);
    fill(&mut op, 20, 20, 4);
    op.i32(40);
    op.i32(9);
    op.store8();
    op.i32(41);
    op.i32(8);
    op.store8();
    op.i32(0);
    op.i32(20);
    op.i32(PING);
    op.i32(40);
    op.i32(2);
    op.i64(0);
    op.call(0);
    op.drop();
    op.end();
    module(
        &[ty(&[I32, I32, I32, I32, I32, I64], &[I32]), ty(&[], &[])],
        &[("send", 0)],
        1,
        &op.0,
    )
}

const I32: u8 = 0x7f;
const I64: u8 = 0x7e;

fn write_key(op: &mut Op, at: i32, a: u8, b: u8) {
    op.i32(at);
    op.i32(a as i32);
    op.store8();
    op.i32(at + 1);
    op.i32(b as i32);
    op.store8();
}

fn fill(op: &mut Op, at: i32, n: i32, value: i32) {
    for i in 0..n {
        op.i32(at + i);
        op.i32(value);
        op.store8();
    }
}

fn ty(params: &[u8], results: &[u8]) -> Vec<u8> {
    let mut raw = vec![0x60];
    uleb(&mut raw, params.len() as u32);
    raw.extend_from_slice(params);
    uleb(&mut raw, results.len() as u32);
    raw.extend_from_slice(results);
    raw
}

fn module(types: &[Vec<u8>], imports: &[(&str, u32)], empty: u32, call: &[u8]) -> Vec<u8> {
    let mut out = vec![0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00];
    let mut type_sec = Vec::new();
    uleb(&mut type_sec, types.len() as u32);
    for t in types {
        type_sec.extend_from_slice(t);
    }
    section(&mut out, 1, &type_sec);
    let mut import_sec = Vec::new();
    uleb(&mut import_sec, imports.len() as u32);
    for (name, ty) in imports {
        name_bytes(&mut import_sec, "xdag");
        name_bytes(&mut import_sec, name);
        import_sec.push(0x00);
        uleb(&mut import_sec, *ty);
    }
    section(&mut out, 2, &import_sec);
    let mut func_sec = Vec::new();
    uleb(&mut func_sec, 4);
    for _ in 0..4 {
        uleb(&mut func_sec, empty);
    }
    section(&mut out, 3, &func_sec);
    section(&mut out, 5, &[0x01, 0x01, 0x01, 0x01]);
    let mut export_sec = Vec::new();
    uleb(&mut export_sec, 5);
    let base = imports.len() as u32;
    for (i, name) in ["init", "call", "recv", "query"].iter().enumerate() {
        name_bytes(&mut export_sec, name);
        export_sec.push(0x00);
        uleb(&mut export_sec, base + i as u32);
    }
    name_bytes(&mut export_sec, "memory");
    export_sec.push(0x02);
    uleb(&mut export_sec, 0);
    section(&mut out, 7, &export_sec);
    let mut code = Vec::new();
    uleb(&mut code, 4);
    func(&mut code, &[0x0b]);
    func(&mut code, call);
    func(&mut code, &[0x0b]);
    func(&mut code, &[0x0b]);
    section(&mut out, 10, &code);
    out
}

fn func(out: &mut Vec<u8>, body: &[u8]) {
    let mut raw = vec![0x00];
    raw.extend_from_slice(body);
    uleb(out, raw.len() as u32);
    out.extend_from_slice(&raw);
}

fn section(out: &mut Vec<u8>, id: u8, payload: &[u8]) {
    out.push(id);
    uleb(out, payload.len() as u32);
    out.extend_from_slice(payload);
}

fn name_bytes(out: &mut Vec<u8>, name: &str) {
    uleb(out, name.len() as u32);
    out.extend_from_slice(name.as_bytes());
}

fn uleb(out: &mut Vec<u8>, mut value: u32) {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        out.push(byte);
        if value == 0 {
            return;
        }
    }
}

fn sleb(out: &mut Vec<u8>, mut value: i64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        let end = (value == 0 && byte & 0x40 == 0) || (value == -1 && byte & 0x40 != 0);
        if end {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

#[derive(Default)]
struct Op(Vec<u8>);

impl Op {
    fn i32(&mut self, value: i32) {
        self.0.push(0x41);
        sleb(&mut self.0, value as i64);
    }

    fn i64(&mut self, value: i64) {
        self.0.push(0x42);
        sleb(&mut self.0, value);
    }

    fn store8(&mut self) {
        self.0.extend_from_slice(&[0x3a, 0x00, 0x00]);
    }

    fn load8u(&mut self) {
        self.0.extend_from_slice(&[0x2d, 0x00, 0x00]);
    }

    fn store64(&mut self) {
        self.0.extend_from_slice(&[0x37, 0x00, 0x00]);
    }

    fn load64(&mut self) {
        self.0.extend_from_slice(&[0x29, 0x00, 0x00]);
    }

    fn call(&mut self, func: u32) {
        self.0.push(0x10);
        uleb(&mut self.0, func);
    }

    fn drop(&mut self) {
        self.0.push(0x1a);
    }

    fn eq32(&mut self) {
        self.0.push(0x46);
    }

    fn lt_u64(&mut self) {
        self.0.push(0x58);
    }

    fn add64(&mut self) {
        self.0.push(0x7c);
    }

    fn sub64(&mut self) {
        self.0.push(0x7d);
    }

    fn mul64(&mut self) {
        self.0.push(0x7e);
    }

    fn div_u64(&mut self) {
        self.0.push(0x80);
    }

    fn if_void(&mut self) {
        self.0.extend_from_slice(&[0x04, 0x40]);
    }

    fn otherwise(&mut self) {
        self.0.push(0x05);
    }

    fn end(&mut self) {
        self.0.push(0x0b);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn modules_export_call_and_match_the_fixtures() {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../src/test/resources/chain-sdk");
        for (name, bytes, pinned) in [
            ("token.wasm", token(), &include_bytes!("../../../src/test/resources/chain-sdk/token.wasm")[..]),
            ("pool.wasm", pool(), &include_bytes!("../../../src/test/resources/chain-sdk/pool.wasm")[..]),
            ("inbox.wasm", inbox(), &include_bytes!("../../../src/test/resources/chain-sdk/inbox.wasm")[..]),
        ] {
            assert_eq!(&bytes[..4], b"\0asm");
            assert!(bytes.windows(4).any(|w| w == b"call"));
            assert_eq!(bytes, pinned, "{name}");
            assert_eq!(std::fs::read(dir.join(name)).unwrap(), pinned);
        }
    }
}
