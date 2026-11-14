//! Paying blocks. Layout matches `ChainBlockBuilder.finish` + `Block.signOut` and
//! `ChunkChainBuilder.split`. Header nibbles include both SIGN_OUT fields. The hashed
//! preimage does not contain the signature. Digest is `preimage || compressed pubkey`.
//! Hash is SHA-256 twice. RFC6979, low-s. CALL args past 256 bytes, DEPLOY code and
//! init args, a CLAIM proof, an ANCHOR commitment, and a CHALLENGE witness become
//! CHUNK chains. An empty ANCHOR commitment is a zero head and no chunks.

use std::collections::HashSet;

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
const FLAG_NEW_CHAIN: u8 = 0x01;
const FLAG_CODE_CHAIN: u8 = 0x02;
const FLAG_DEPLOY_ARGS: u8 = 0x04;
const DEPLOY_NEW_INLINE: usize = 160;
const DEPLOY_INTO_INLINE: usize = 224;
const DEPLOY_INTO_CODE_INLINE: usize = 192;
const MIN_GAS_NANO: u64 = 100_000_000;

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
    let chained = head.is_some();
    let inline: &[u8] = if chained { &[] } else { call.args.as_slice() };
    let sender = sender_address(&call.private_key)?;
    let mut links = vec![
        Link::Pay { kind: INPUT, address: sender, nano: call.value },
        Link::Pay { kind: OUTPUT, address: call.chain, nano: call.value },
    ];
    if let Some(head) = head {
        links.push(Link::Head(head));
    }
    let mut ext = vec![ext_header(call, inline.len(), chained)];
    ext.extend(pad32(inline));
    sign_tx(call.header, call.timestamp, call.nonce, call.fee, &call.private_key, &links, &ext, chunks)
}

pub struct DeployNew {
    pub header: u8,
    pub timestamp: u64,
    pub nonce: u64,
    pub fee: u64,
    pub gas_limit: u64,
    pub gas_price: u64,
    pub delivery_delay: u64,
    pub max_call_gas: u64,
    pub private_key: [u8; 32],
    pub wasm: Vec<u8>,
    pub init_args: Vec<u8>,
}

pub fn sign_deploy_new(deploy: &DeployNew) -> Result<Signed, String> {
    let code = split_chunks(deploy.header, deploy.timestamp, &deploy.wasm)?;
    let code_head = hash_low(&code[0]);
    let mut flags = FLAG_NEW_CHAIN | FLAG_CODE_CHAIN;
    let mut chunks = code;
    let (inline, args_head) = if deploy.init_args.len() > DEPLOY_NEW_INLINE {
        let args = split_chunks(deploy.header, deploy.timestamp, &deploy.init_args)?;
        let head = hash_low(&args[0]);
        chunks.extend(args);
        flags |= FLAG_DEPLOY_ARGS;
        (Vec::new(), Some(head))
    } else {
        (deploy.init_args.clone(), None)
    };
    let links_n = if args_head.is_some() { 2 } else { 1 };
    let paid = required_value(deploy.fee, links_n)?;
    let sender = sender_address(&deploy.private_key)?;
    let mut links = vec![
        Link::Pay { kind: INPUT, address: sender, nano: paid },
        Link::Pay { kind: OUTPUT, address: sender, nano: paid },
        Link::Head(code_head),
    ];
    if let Some(head) = args_head {
        links.push(Link::Head(head));
    }
    let mut ext = vec![
        deploy_header(flags, &[0u8; 20], deploy.gas_limit, inline.len())?,
        sha256(&deploy.wasm),
        chain_config(deploy.gas_price, deploy.delivery_delay, deploy.max_call_gas)?,
    ];
    ext.extend(pad32(&inline));
    sign_tx(deploy.header, deploy.timestamp, deploy.nonce, deploy.fee, &deploy.private_key, &links, &ext, dedupe(chunks))
}

pub struct DeployInto {
    pub header: u8,
    pub timestamp: u64,
    pub nonce: u64,
    pub chain: [u8; 20],
    pub value: u64,
    pub fee: u64,
    pub gas_limit: u64,
    pub code_hash: Option<[u8; 32]>,
    pub private_key: [u8; 32],
    pub wasm: Option<Vec<u8>>,
    pub init_args: Vec<u8>,
}

pub fn sign_deploy_into(deploy: &DeployInto) -> Result<Signed, String> {
    if deploy.wasm.is_none() && deploy.code_hash.is_none() {
        return Err("need wasm or code hash".into());
    }
    let mut flags = 0u8;
    let mut chunks = Vec::new();
    let code_head;
    let hash;
    if let Some(wasm) = &deploy.wasm {
        let code = split_chunks(deploy.header, deploy.timestamp, wasm)?;
        code_head = Some(hash_low(&code[0]));
        chunks.extend(code);
        flags |= FLAG_CODE_CHAIN;
        hash = sha256(wasm);
    } else {
        code_head = None;
        hash = deploy.code_hash.unwrap();
    }
    let cap = if code_head.is_some() { DEPLOY_INTO_CODE_INLINE } else { DEPLOY_INTO_INLINE };
    let (inline, args_head) = if deploy.init_args.len() > cap {
        let args = split_chunks(deploy.header, deploy.timestamp, &deploy.init_args)?;
        let head = hash_low(&args[0]);
        chunks.extend(args);
        flags |= FLAG_DEPLOY_ARGS;
        (Vec::new(), Some(head))
    } else {
        (deploy.init_args.clone(), None)
    };
    let sender = sender_address(&deploy.private_key)?;
    let mut links = vec![
        Link::Pay { kind: INPUT, address: sender, nano: deploy.value },
        Link::Pay { kind: OUTPUT, address: deploy.chain, nano: deploy.value },
    ];
    if let Some(head) = code_head {
        links.push(Link::Head(head));
    }
    if let Some(head) = args_head {
        links.push(Link::Head(head));
    }
    let mut ext = vec![deploy_header(flags, &deploy.chain, deploy.gas_limit, inline.len())?, hash];
    ext.extend(pad32(&inline));
    sign_tx(deploy.header, deploy.timestamp, deploy.nonce, deploy.fee, &deploy.private_key, &links, &ext, dedupe(chunks))
}

pub struct Anchor {
    pub header: u8,
    pub timestamp: u64,
    pub nonce: u64,
    pub fee: u64,
    pub chain: [u8; 20],
    pub seq: u64,
    pub state_root: [u8; 32],
    pub outbox_root: [u8; 32],
    pub prev_seq: u64,
    pub input_count: u64,
    pub segment_count: u64,
    pub prev_anchor: [u8; 32],
    pub main_block: [u8; 32],
    pub private_key: [u8; 32],
    pub commitment: Vec<u8>,
}

pub fn sign_anchor(anchor: &Anchor) -> Result<Signed, String> {
    let (chunks, head) = if anchor.commitment.is_empty() {
        (Vec::new(), [0u8; 32])
    } else {
        let chunks = split_chunks(anchor.header, anchor.timestamp, &anchor.commitment)?;
        let head = hash_low(&chunks[0]);
        (chunks, head)
    };
    let paid = required_value(anchor.fee, 3)?;
    let sender = sender_address(&anchor.private_key)?;
    let links = vec![
        Link::Pay { kind: INPUT, address: sender, nano: paid },
        Link::Pay { kind: OUTPUT, address: sender, nano: paid },
        Link::Head(anchor.prev_anchor),
        Link::Head(anchor.main_block),
        Link::Head(head),
    ];
    if anchor.input_count > u32::MAX as u64 || anchor.segment_count > u32::MAX as u64 {
        return Err("anchor count".into());
    }
    let mut hdr = [0u8; 32];
    hdr[0] = 4;
    hdr[2..22].copy_from_slice(&anchor.chain);
    hdr[22..30].copy_from_slice(&anchor.seq.to_le_bytes());
    let mut counts = [0u8; 32];
    counts[0..8].copy_from_slice(&anchor.prev_seq.to_le_bytes());
    counts[8..12].copy_from_slice(&(anchor.input_count as u32).to_le_bytes());
    counts[12..16].copy_from_slice(&(anchor.segment_count as u32).to_le_bytes());
    let ext = [hdr, anchor.state_root, anchor.outbox_root, counts];
    sign_tx(anchor.header, anchor.timestamp, anchor.nonce, anchor.fee, &anchor.private_key, &links, &ext, chunks)
}

pub struct Claim {
    pub header: u8,
    pub timestamp: u64,
    pub nonce: u64,
    pub fee: u64,
    pub to_chain_vault: bool,
    pub src_chain: [u8; 20],
    pub seq: u64,
    pub index: u64,
    pub amount: u64,
    pub recipient: [u8; 20],
    pub anchor: [u8; 32],
    pub private_key: [u8; 32],
    pub proof: Vec<u8>,
}

pub fn sign_claim(claim: &Claim) -> Result<Signed, String> {
    let chunks = split_chunks(claim.header, claim.timestamp, &claim.proof)?;
    let head = hash_low(&chunks[0]);
    let paid = required_value(claim.fee, 2)?;
    let sender = sender_address(&claim.private_key)?;
    let links = vec![
        Link::Pay { kind: INPUT, address: sender, nano: paid },
        Link::Pay { kind: OUTPUT, address: sender, nano: paid },
        Link::Head(claim.anchor),
        Link::Head(head),
    ];
    let mut hdr = [0u8; 32];
    hdr[0] = 7;
    if claim.to_chain_vault {
        hdr[1] = 1;
    }
    hdr[2..22].copy_from_slice(&claim.src_chain);
    hdr[22..30].copy_from_slice(&claim.seq.to_le_bytes());
    let mut p0 = [0u8; 32];
    p0[0..8].copy_from_slice(&claim.index.to_le_bytes());
    p0[8..16].copy_from_slice(&claim.amount.to_le_bytes());
    let mut p1 = [0u8; 32];
    p1[..20].copy_from_slice(&claim.recipient);
    sign_tx(claim.header, claim.timestamp, claim.nonce, claim.fee, &claim.private_key, &links, &[hdr, p0, p1], chunks)
}

pub struct Challenge {
    pub header: u8,
    pub timestamp: u64,
    pub nonce: u64,
    pub fee: u64,
    pub input_index: u64,
    pub deposit: u64,
    pub anchor: [u8; 32],
    pub private_key: [u8; 32],
    pub witness: Vec<u8>,
}

pub fn sign_challenge(challenge: &Challenge) -> Result<Signed, String> {
    let chunks = split_chunks(challenge.header, challenge.timestamp, &challenge.witness)?;
    let head = hash_low(&chunks[0]);
    let fee_back = required_value(challenge.fee, 2)?;
    let paid = fee_back.checked_add(challenge.deposit).ok_or("amount overflow")?;
    if challenge.input_index > u32::MAX as u64 {
        return Err("input index".into());
    }
    let sender = sender_address(&challenge.private_key)?;
    let links = vec![
        Link::Pay { kind: INPUT, address: sender, nano: paid },
        Link::Pay { kind: OUTPUT, address: bond_vault(), nano: challenge.deposit },
        Link::Pay { kind: OUTPUT, address: sender, nano: fee_back },
        Link::Head(challenge.anchor),
        Link::Head(head),
    ];
    let mut hdr = [0u8; 32];
    hdr[0] = 6;
    hdr[2..6].copy_from_slice(&(challenge.input_index as u32).to_le_bytes());
    hdr[6..14].copy_from_slice(&challenge.deposit.to_le_bytes());
    sign_tx(
        challenge.header,
        challenge.timestamp,
        challenge.nonce,
        challenge.fee,
        &challenge.private_key,
        &links,
        &[hdr],
        chunks,
    )
}

enum Link {
    Pay { kind: u8, address: [u8; 20], nano: u64 },
    Head([u8; 32]),
}

fn sign_tx(
    header_type: u8,
    timestamp: u64,
    nonce: u64,
    fee: u64,
    private_key: &[u8; 32],
    links: &[Link],
    ext: &[[u8; 32]],
    chunks: Vec<[u8; 512]>,
) -> Result<Signed, String> {
    if 2 + links.len() + 3 + ext.len() > 16 {
        return Err("bad length".into());
    }
    let secret = SigningKey::from_slice(private_key).map_err(|e| e.to_string())?;
    let point = secret.verifying_key().to_encoded_point(true);
    let compressed = point.as_bytes();
    if compressed.len() != 33 {
        return Err("compressed pubkey".into());
    }
    let pubkey_type = if compressed[0] == 0x03 { PUB_ODD } else { PUB_EVEN };
    let mut x = [0u8; 32];
    x.copy_from_slice(&compressed[1..33]);

    let mut type_bits = 0u64;
    let mut n = 0u32;
    set_type(&mut type_bits, header_type, &mut n);
    set_type(&mut type_bits, NONCE, &mut n);
    for link in links {
        let kind = match link {
            Link::Pay { kind, .. } => *kind,
            Link::Head(_) => OUT,
        };
        set_type(&mut type_bits, kind, &mut n);
    }
    for _ in ext {
        set_type(&mut type_bits, EXT, &mut n);
    }
    set_type(&mut type_bits, pubkey_type, &mut n);
    set_type(&mut type_bits, SIGN_OUT, &mut n);
    set_type(&mut type_bits, SIGN_OUT, &mut n);

    let mut body = Vec::with_capacity(320);
    body.extend_from_slice(&header(type_bits, timestamp, fee));
    body.extend_from_slice(&reversed(&be_pad(nonce)));
    for link in links {
        match link {
            Link::Pay { address, nano, .. } => body.extend_from_slice(&amount_field(xdag_amount(*nano), address)),
            Link::Head(low) => body.extend_from_slice(&reversed(low)),
        }
    }
    for field in ext {
        body.extend_from_slice(field);
    }
    body.extend_from_slice(&x);

    let mut preimage = [0u8; 512];
    preimage[..body.len()].copy_from_slice(&body);
    let mut digest = Vec::with_capacity(512 + 33);
    digest.extend_from_slice(&preimage);
    digest.extend_from_slice(compressed);
    let hash = dsha256(&digest);
    let (sig, _recid) = secret.sign_prehash_recoverable(&hash).map_err(|e| e.to_string())?;
    let mut block = [0u8; 512];
    block[..body.len()].copy_from_slice(&body);
    block[body.len()..body.len() + 64].copy_from_slice(&sig.to_bytes());
    Ok(Signed { block, chunks })
}

fn sender_address(private_key: &[u8; 32]) -> Result<[u8; 20], String> {
    let secret = SigningKey::from_slice(private_key).map_err(|e| e.to_string())?;
    let point = secret.verifying_key().to_encoded_point(true);
    let compressed = point.as_bytes();
    if compressed.len() != 33 {
        return Err("compressed pubkey".into());
    }
    Ok(hash160(compressed))
}

fn required_value(fee: u64, chain_links: u64) -> Result<u64, String> {
    MIN_GAS_NANO
        .checked_mul(1 + chain_links)
        .and_then(|gas| fee.checked_add(gas))
        .ok_or_else(|| "amount overflow".into())
}

fn pad32(data: &[u8]) -> Vec<[u8; 32]> {
    let mut out = Vec::new();
    let mut off = 0;
    while off < data.len() {
        let mut field = [0u8; 32];
        let n = (data.len() - off).min(32);
        field[..n].copy_from_slice(&data[off..off + n]);
        out.push(field);
        off += 32;
    }
    out
}

fn dedupe(chunks: Vec<[u8; 512]>) -> Vec<[u8; 512]> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for chunk in chunks {
        if seen.insert(hash_low(&chunk)) {
            out.push(chunk);
        }
    }
    out
}

fn deploy_header(flags: u8, chain: &[u8; 20], gas: u64, args_len: usize) -> Result<[u8; 32], String> {
    if gas > u32::MAX as u64 || args_len > u16::MAX as usize {
        return Err("deploy header".into());
    }
    let mut raw = [0u8; 32];
    raw[0] = 2;
    raw[1] = flags;
    raw[2..22].copy_from_slice(chain);
    raw[22..26].copy_from_slice(&(gas as u32).to_le_bytes());
    raw[26..28].copy_from_slice(&(args_len as u16).to_le_bytes());
    Ok(raw)
}

fn chain_config(price: u64, delay: u64, max_gas: u64) -> Result<[u8; 32], String> {
    if delay > u32::MAX as u64 || max_gas > u32::MAX as u64 {
        return Err("chain config".into());
    }
    let mut raw = [0u8; 32];
    raw[..8].copy_from_slice(&price.to_le_bytes());
    raw[8..12].copy_from_slice(&(delay as u32).to_le_bytes());
    raw[12..16].copy_from_slice(&(max_gas as u32).to_le_bytes());
    Ok(raw)
}

fn bond_vault() -> [u8; 20] {
    let hash = Sha256::digest(b"xdag-chain-bond");
    let mut address = [0u8; 20];
    address.copy_from_slice(&hash[..20]);
    address
}

fn sha256(data: &[u8]) -> [u8; 32] {
    Sha256::digest(data).into()
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
        assert_eq!(signed.block[8], NONCE << 4 | DEVNET_HEADER);
    }
}
