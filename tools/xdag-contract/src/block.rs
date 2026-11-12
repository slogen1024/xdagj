//! CALL block. Layout matches `ChainBlockBuilder.finish` + `Block.signOut` and
//! `ChunkChainBuilder.split`. Header nibbles include both SIGN_OUT fields. The hashed
//! preimage does not contain the signature. Digest is `preimage || compressed pubkey`.
//! Hash is SHA-256 twice. RFC6979, low-s. Args longer than 256 bytes become one CHUNK chain.

use k256::ecdsa::SigningKey;
use ripemd::Ripemd160;
use sha2::{Digest, Sha256};

const HEAD_TEST: u8 = 0x08;
const NONCE: u8 = 0x0e;
const INPUT: u8 = 0x0c;
const OUTPUT: u8 = 0x0d;
const SIGN_OUT: u8 = 0x05;
const PUB_EVEN: u8 = 0x06;
const PUB_ODD: u8 = 0x07;
const EXT: u8 = 0x0f;
const OUT: u8 = 0x03;
const CHUNK: u8 = 0x03;
const CALL_INLINE: usize = 256;
const CHUNK_DATA: usize = 352;
const FLAG_ARGS_CHAIN: u8 = 0x01;

pub struct Call {
    pub header: u8,
    pub timestamp: u64,
    pub nonce: u64,
    pub chain: [u8; 20],
    pub contract: [u8; 20],
    pub selector: i32,
    pub gas_limit: u32,
    pub value: u64,
    pub fee: u64,
    pub private_key: [u8; 32],
    pub args: Vec<u8>,
}

pub struct Signed {
    pub block: [u8; 512],
    pub chunks: Vec<[u8; 512]>,
}

pub fn sign_call(call: &Call) -> Result<Signed, String> {
    let chunks = if call.args.len() > CALL_INLINE {
        split_chunks(call.header, call.timestamp, &call.args)?
    } else {
        Vec::new()
    };
    let head = chunks.first().map(hash_low);
    let secret = SigningKey::from_slice(&call.private_key).map_err(|e| e.to_string())?;
    let compressed = secret.verifying_key().to_encoded_point(true);
    let compressed = compressed.as_bytes();
    if compressed.len() != 33 {
        return Err("compressed pubkey".into());
    }
    let mut sender = [0u8; 20];
    sender.copy_from_slice(&hash160(compressed));
    let pubkey_type = if compressed[0] == 0x03 { PUB_ODD } else { PUB_EVEN };
    let mut x = [0u8; 32];
    x.copy_from_slice(&compressed[1..33]);

    let chained = head.is_some();
    let inline = if chained { &[][..] } else { call.args.as_slice() };
    let payload_fields = inline.len().div_ceil(32);
    let mut type_bits = 0u64;
    let mut n = 0u32;
    set_type(&mut type_bits, call.header, &mut n);
    set_type(&mut type_bits, NONCE, &mut n);
    set_type(&mut type_bits, INPUT, &mut n);
    set_type(&mut type_bits, OUTPUT, &mut n);
    if chained {
        set_type(&mut type_bits, OUT, &mut n);
    }
    set_type(&mut type_bits, EXT, &mut n);
    for _ in 0..payload_fields {
        set_type(&mut type_bits, EXT, &mut n);
    }
    set_type(&mut type_bits, pubkey_type, &mut n);
    set_type(&mut type_bits, SIGN_OUT, &mut n);
    set_type(&mut type_bits, SIGN_OUT, &mut n);

    let mut body = Vec::with_capacity(256);
    body.extend_from_slice(&header(type_bits, call.timestamp, call.fee));
    body.extend_from_slice(&reversed(&be_pad(call.nonce)));
    let wire = xdag_amount(call.value);
    body.extend_from_slice(&amount_field(wire, &sender));
    body.extend_from_slice(&amount_field(wire, &call.chain));
    if let Some(head) = head {
        body.extend_from_slice(&reversed(&head));
    }
    body.extend_from_slice(&ext_header(call, inline.len(), chained));
    let mut off = 0;
    while off < inline.len() {
        let mut field = [0u8; 32];
        let n = (inline.len() - off).min(32);
        field[..n].copy_from_slice(&inline[off..off + n]);
        body.extend_from_slice(&field);
        off += n;
    }
    body.extend_from_slice(&x);
    let body_fields = body.len() / 32;

    let mut preimage = [0u8; 512];
    preimage[..body.len()].copy_from_slice(&body);
    let mut digest = Vec::with_capacity(512 + 33);
    digest.extend_from_slice(&preimage);
    digest.extend_from_slice(compressed);
    let hash = dsha256(&digest);
    let (sig, _recid) = secret
        .sign_prehash_recoverable(&hash)
        .map_err(|e| e.to_string())?;
    let sig = sig.to_bytes();

    let mut block = [0u8; 512];
    block[..body.len()].copy_from_slice(&body);
    block[body.len()..body.len() + 64].copy_from_slice(&sig);
    let _ = body_fields;
    Ok(Signed { block, chunks })
}

fn split_chunks(header_type: u8, timestamp: u64, payload: &[u8]) -> Result<Vec<[u8; 512]>, String> {
    if payload.is_empty() {
        return Err("empty chunk payload".into());
    }
    let n = payload.len().div_ceil(CHUNK_DATA);
    if n > 4096 {
        return Err("too many chunks".into());
    }
    let mut head = chunk_head_timestamp(timestamp);
    if (head & 0xffff) < (n as u64 - 1) {
        head = (head & !0xffff) - 2;
    }
    let mut next: Option<[u8; 32]> = None;
    let mut tail_first = Vec::with_capacity(n);
    for i in (0..n).rev() {
        let off = i * CHUNK_DATA;
        let data = &payload[off..(off + CHUNK_DATA).min(payload.len())];
        let block = chunk_block(header_type, head - i as u64, i as u32, payload.len() as u32, data, next);
        next = Some(hash_low(&block));
        tail_first.push(block);
    }
    tail_first.reverse();
    Ok(tail_first)
}

fn chunk_head_timestamp(timestamp: u64) -> u64 {
    let mut t = timestamp - 1;
    if t & 0xffff == 0xffff {
        t -= 1;
    }
    t
}

fn chunk_block(header_type: u8, timestamp: u64, seq: u32, total: u32, data: &[u8], next: Option<[u8; 32]>) -> [u8; 512] {
    let mut type_bits = 0u64;
    let mut n = 0u32;
    set_type(&mut type_bits, header_type, &mut n);
    if next.is_some() {
        set_type(&mut type_bits, OUT, &mut n);
    }
    let fields = data.len().div_ceil(32);
    set_type(&mut type_bits, EXT, &mut n);
    for _ in 0..fields {
        set_type(&mut type_bits, EXT, &mut n);
    }
    set_type(&mut type_bits, SIGN_OUT, &mut n);
    set_type(&mut type_bits, SIGN_OUT, &mut n);
    let mut body = Vec::new();
    body.extend_from_slice(&header(type_bits, timestamp, 0));
    if let Some(link) = next {
        body.extend_from_slice(&reversed(&link));
    }
    let mut ext = [0u8; 32];
    ext[0] = CHUNK;
    ext[1..5].copy_from_slice(&seq.to_le_bytes());
    ext[5..9].copy_from_slice(&total.to_le_bytes());
    ext[9..11].copy_from_slice(&(data.len() as u16).to_le_bytes());
    body.extend_from_slice(&ext);
    let mut off = 0;
    while off < data.len() {
        let mut field = [0u8; 32];
        let n = (data.len() - off).min(32);
        field[..n].copy_from_slice(&data[off..off + n]);
        body.extend_from_slice(&field);
        off += n;
    }
    let mut block = [0u8; 512];
    block[..body.len()].copy_from_slice(&body);
    block
}

fn hash_low(block: &[u8; 512]) -> [u8; 32] {
    let mut digest = dsha256(block);
    digest.reverse();
    let mut low = [0u8; 32];
    low[8..32].copy_from_slice(&digest[8..32]);
    low
}

fn set_type(bits: &mut u64, field: u8, n: &mut u32) {
    *bits |= (field as u64) << (*n * 4);
    *n += 1;
}

fn header(type_bits: u64, timestamp: u64, fee: u64) -> [u8; 32] {
    let mut raw = [0u8; 32];
    raw[8..16].copy_from_slice(&type_bits.to_le_bytes());
    raw[16..24].copy_from_slice(&timestamp.to_le_bytes());
    // Header fee is nano. Link amounts go through xdag_amount.
    raw[24..32].copy_from_slice(&fee.to_le_bytes());
    raw
}

fn ext_header(call: &Call, args_len: usize, chained: bool) -> [u8; 32] {
    let mut raw = [0u8; 32];
    raw[0] = 1;
    if chained {
        raw[1] = FLAG_ARGS_CHAIN;
    }
    raw[2..22].copy_from_slice(&call.contract);
    raw[22..26].copy_from_slice(&(call.selector as u32).to_le_bytes());
    raw[26..30].copy_from_slice(&call.gas_limit.to_le_bytes());
    raw[30..32].copy_from_slice(&(args_len as u16).to_le_bytes());
    raw
}

/// `XAmount.toXAmount`: whole XDAG in the high 32 bits, ceil(fraction * 2^32) in the low 32.
fn xdag_amount(nano: u64) -> u64 {
    let whole = nano / 1_000_000_000;
    let frac = (nano % 1_000_000_000) as u128;
    let product = frac << 32;
    let mut low = (product / 1_000_000_000) as u64;
    if product % 1_000_000_000 != 0 {
        low += 1;
    }
    (whole << 32) + low
}

fn amount_field(amount: u64, address: &[u8; 20]) -> [u8; 32] {
    let mut raw = [0u8; 32];
    raw[..8].copy_from_slice(&amount.to_be_bytes());
    raw[8..28].copy_from_slice(address);
    reversed(&raw)
}

fn be_pad(value: u64) -> [u8; 32] {
    let mut raw = [0u8; 32];
    raw[..8].copy_from_slice(&value.to_be_bytes());
    raw
}

fn reversed(raw: &[u8; 32]) -> [u8; 32] {
    let mut out = *raw;
    out.reverse();
    out
}

fn hash160(data: &[u8]) -> [u8; 20] {
    let mut ripe = Ripemd160::new();
    ripe.update(Sha256::digest(data));
    ripe.finalize().into()
}

fn dsha256(data: &[u8]) -> [u8; 32] {
    Sha256::digest(Sha256::digest(data)).into()
}

pub const DEVNET_HEADER: u8 = HEAD_TEST;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inline_call_is_one_block() {
        let mut key = [0u8; 32];
        key[31] = 1;
        let signed = sign_call(&Call {
            header: DEVNET_HEADER,
            timestamp: 0x16a0_0000_000,
            nonce: 1,
            chain: [1u8; 20],
            contract: [2u8; 20],
            selector: 7,
            gas_limit: 0,
            value: 100,
            fee: 100_000_000,
            private_key: key,
            args: vec![1, 2],
        })
        .unwrap();
        assert_eq!(signed.block.len(), 512);
        assert!(signed.chunks.is_empty());
        assert_eq!(signed.block[8], DEVNET_HEADER);
    }
}
