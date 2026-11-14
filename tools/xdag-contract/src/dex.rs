use crate::abi::u64_le;
use crate::host::Host;
/// Constant-product pool. Reserves live in this host. Fee is zero.
pub struct Pool;

#[xdag::contract]
impl Pool {
    pub const SWAP: &'static str = "swap(u64,u64)";

    pub fn seed(host: &mut Host, reserve_in: u64, reserve_out: u64) {
        host.storage_set(b"r0", &u64_le(reserve_in));
        host.storage_set(b"r1", &u64_le(reserve_out));
    }

    /// `input` is `amount_in ‖ min_out`, both u64 LE. Output amount is written to `returns`.
    #[xdag::method("swap(u64,u64)")]
    pub fn swap(host: &mut Host) -> bool {
        if host.input.len() != 16 {
            return false;
        }
        let amount_in = read(&host.input[..8]);
        let min_out = read(&host.input[8..]);
        let r0 = reserve(host, b"r0");
        let r1 = reserve(host, b"r1");
        let Some(prod) = r0.checked_mul(r1) else {
            return false;
        };
        let Some(denom) = r0.checked_add(amount_in) else {
            return false;
        };
        if amount_in == 0 || denom == 0 || r1 == 0 {
            return false;
        }
        let new_r1 = prod / denom;
        if new_r1 > r1 {
            return false;
        }
        let out = r1 - new_r1;
        if out < min_out {
            return false;
        }
        host.storage_set(b"r0", &u64_le(r0 + amount_in));
        host.storage_set(b"r1", &u64_le(r1 - out));
        host.returns = u64_le(out).to_vec();
        true
    }
}

fn reserve(host: &Host, key: &[u8]) -> u64 {
    host.storage_get(key).map(read).unwrap_or(0)
}

fn read(raw: &[u8]) -> u64 {
    let mut buf = [0u8; 8];
    buf.copy_from_slice(&raw[..8]);
    u64::from_le_bytes(buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::abi::selector;

    #[test]
    fn swap_keeps_the_product() {
        let mut host = Host::default();
        Pool::seed(&mut host, 100, 100);
        host.selector = selector(Pool::SWAP);
        host.input = {
            let mut raw = u64_le(25).to_vec();
            raw.extend_from_slice(&u64_le(1));
            raw
        };
        assert!(Pool::call(&mut host));
        let out = read(&host.returns);
        assert_eq!(out, 20);
        assert_eq!(reserve(&host, b"r0"), 125);
        assert_eq!(reserve(&host, b"r1"), 80);
    }

    #[test]
    fn swap_rejects_before_it_writes() {
        let mut host = Host::default();
        host.selector = selector(Pool::SWAP);
        host.input = {
            let mut raw = u64_le(25).to_vec();
            raw.extend_from_slice(&u64_le(1));
            raw
        };
        assert!(!Pool::call(&mut host));

        Pool::seed(&mut host, 100, 100);
        host.input = vec![1];
        assert!(!Pool::call(&mut host));
        assert_eq!(reserve(&host, b"r0"), 100);
        assert_eq!(reserve(&host, b"r1"), 100);

        host.input = {
            let mut raw = u64_le(0).to_vec();
            raw.extend_from_slice(&u64_le(1));
            raw
        };
        assert!(!Pool::call(&mut host));

        host.input = {
            let mut raw = u64_le(25).to_vec();
            raw.extend_from_slice(&u64_le(21));
            raw
        };
        assert!(!Pool::call(&mut host));
        assert_eq!(reserve(&host, b"r0"), 100);
        assert_eq!(reserve(&host, b"r1"), 100);

        host.selector = 1;
        assert!(!Pool::call(&mut host));
        assert_eq!(reserve(&host, b"r0"), 100);
        assert_eq!(reserve(&host, b"r1"), 100);
    }

    #[test]
    fn swap_rejects_an_overflowing_product() {
        let mut host = Host::default();
        Pool::seed(&mut host, u64::MAX, 2);
        host.selector = selector(Pool::SWAP);
        host.input = {
            let mut raw = u64_le(1).to_vec();
            raw.extend_from_slice(&u64_le(0));
            raw
        };
        assert!(!Pool::call(&mut host));
        assert_eq!(reserve(&host, b"r0"), u64::MAX);
        assert_eq!(reserve(&host, b"r1"), 2);
    }
}
