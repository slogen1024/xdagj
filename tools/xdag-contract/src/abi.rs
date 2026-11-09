use sha2::{Digest, Sha256};

/// `sha256(ascii)[0..4]` as a signed big-endian i32. Same as `AbiCodec.selector`.
pub fn selector(signature: &str) -> i32 {
    let hash = Sha256::digest(signature.as_bytes());
    i32::from_be_bytes([hash[0], hash[1], hash[2], hash[3]])
}

pub fn event(signature: &str, body: &[u8]) -> Vec<u8> {
    let mut out = Sha256::digest(signature.as_bytes()).to_vec();
    out.extend_from_slice(body);
    out
}

pub fn u32_le(value: u32) -> [u8; 4] {
    value.to_le_bytes()
}

pub fn u64_le(value: u64) -> [u8; 8] {
    value.to_le_bytes()
}

pub fn address(raw: &[u8]) -> [u8; 20] {
    raw.try_into().expect("address is 20 bytes")
}

pub fn bytes(raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + raw.len());
    out.extend_from_slice(&u32_le(raw.len() as u32));
    out.extend_from_slice(raw);
    out
}

pub fn string(value: &str) -> Vec<u8> {
    bytes(value.as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pins_match_abi_codec() {
        assert_eq!(selector("balanceOf(address)"), 1_531_377_910);
        assert_eq!(selector("transfer(address,u64)"), 1_433_710_159);
        assert_eq!(selector("Transfer(address,address,u64)"), -2_021_979_357);
        assert_eq!(u32_le(2), [2, 0, 0, 0]);
        assert_eq!(u64_le(1), [1, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(bytes(&[9, 8]), vec![2, 0, 0, 0, 9, 8]);
        assert_eq!(event("Transfer(address,address,u64)", &u64_le(7)).len(), 40);
    }
}
