use crate::abi::bytes;
use crate::host::Host;
/// Cross-chain ping. `ping(bytes)` appends one outbox row. It does not wait for D.
pub struct Inbox;

#[xdag::contract]
impl Inbox {
    pub const PING: &'static str = "ping(bytes)";

    /// Destination is `host.dst_chain` / `host.dst_contract`. Args are length-prefixed.
    #[xdag::method("ping(bytes)", reentrant)]
    pub fn ping(host: &mut Host) -> bool {
        let args = bytes(&host.input);
        let value = host.value;
        let dst = host.dst_chain;
        let contract = host.dst_contract;
        let selector = host.selector;
        host.send(dst, contract, selector, args, value);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::abi::selector;

    #[test]
    fn ping_is_one_outbox_row() {
        let mut host = Host::default();
        host.selector = selector(Inbox::PING);
        host.input = vec![9, 8];
        host.value = 50;
        host.dst_chain = [3u8; 20];
        host.dst_contract = [4u8; 20];
        assert!(Inbox::call(&mut host));
        assert_eq!(host.outbox.len(), 1);
        assert_eq!(host.outbox[0].dst_chain, [3u8; 20]);
        assert_eq!(host.outbox[0].value, 50);
        assert_eq!(host.outbox[0].args, vec![2, 0, 0, 0, 9, 8]);
        assert_eq!(Inbox::reentrant_selectors(), &[selector(Inbox::PING)]);
    }

    #[test]
    fn unknown_selector_sends_nothing() {
        let mut host = Host::default();
        host.selector = 1;
        host.input = vec![9];
        assert!(!Inbox::call(&mut host));
        assert!(host.outbox.is_empty());
    }

    #[test]
    fn empty_ping_still_prefixes_the_length() {
        let mut host = Host::default();
        host.selector = selector(Inbox::PING);
        host.dst_chain = [3u8; 20];
        host.dst_contract = [4u8; 20];
        assert!(Inbox::call(&mut host));
        assert_eq!(host.outbox.len(), 1);
        assert_eq!(host.outbox[0].args, vec![0, 0, 0, 0]);
        assert_eq!(host.outbox[0].value, 0);
    }
}
