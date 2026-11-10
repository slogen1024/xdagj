//! Inline CALL block. Layout matches `ChainBlockBuilder.finish` + `Block.signOut`:
//! header nibbles include both SIGN_OUT fields, the hashed preimage does not contain the
//! signature, the digest is `preimage || compressed pubkey`, and the hash is SHA-256 twice.
//! RFC6979, low-s. Chunk chains stay in `ChainBlockBuilder`.

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

pub fn sign_call(call: &Call) -> Result<[u8; 512], String> {
    if call.args.len() > 256 {
        return Err("args need a chunk chain".into());
    }
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

    let payload_fields = call.args.len().div_ceil(32);
    let mut type_bits = 0u64;
    let mut n = 0u32;
    set_type(&mut type_bits, call.header, &mut n);
    set_type(&mut type_bits, NONCE, &mut n);
    set_type(&mut type_bits, INPUT, &mut n);
    set_type(&mut type_bits, OUTPUT, &mut n);
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
    body.extend_from_slice(&ext_header(call));
    let mut off = 0;
    while off < call.args.len() {
        let mut field = [0u8; 32];
        let n = (call.args.len() - off).min(32);
        field[..n].copy_from_slice(&call.args[off..off + n]);
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
    Ok(block)
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

fn ext_header(call: &Call) -> [u8; 32] {
    let mut raw = [0u8; 32];
    raw[0] = 1;
    raw[2..22].copy_from_slice(&call.contract);
    raw[22..26].copy_from_slice(&(call.selector as u32).to_le_bytes());
    raw[26..30].copy_from_slice(&call.gas_limit.to_le_bytes());
    raw[30..32].copy_from_slice(&(call.args.len() as u16).to_le_bytes());
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
        let block = sign_call(&Call {
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
        assert_eq!(block.len(), 512);
        assert_eq!(block[8], DEVNET_HEADER);
    }
}
