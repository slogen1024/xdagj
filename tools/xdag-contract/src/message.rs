use crate::abi::{bytes, selector};
use crate::host::Host;

/// Cross-chain ping. `ping(bytes)` appends one outbox row. It does not wait for D.
pub struct Inbox;

impl Inbox {
    pub const PING: &'static str = "ping(bytes)";

    pub fn call(host: &mut Host, dst_chain: [u8; 20], contract: [u8; 20]) -> bool {
        if host.selector != selector(Self::PING) {
            return false;
        }
        let args = bytes(&host.input);
        let value = host.value;
        host.send(dst_chain, contract, host.selector, args, value);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ping_is_one_outbox_row() {
        let mut host = Host::default();
        host.selector = selector(Inbox::PING);
        host.input = vec![9, 8];
        host.value = 50;
        let dst = [3u8; 20];
        let contract = [4u8; 20];
        assert!(Inbox::call(&mut host, dst, contract));
        assert_eq!(host.outbox.len(), 1);
        assert_eq!(host.outbox[0].dst_chain, dst);
        assert_eq!(host.outbox[0].value, 50);
        assert_eq!(host.outbox[0].args, vec![2, 0, 0, 0, 9, 8]);
    }
}
