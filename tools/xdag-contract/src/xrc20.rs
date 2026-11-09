use crate::abi::{event, u64_le};
use crate::host::Host;
use crate::storage::Map;
/// XRC-20 balances under this host. `transfer(address,u64)` and `balanceOf(address)`.
pub struct Token;

#[xdag::contract]
impl Token {
    pub const TRANSFER: &'static str = "transfer(address,u64)";
    pub const BALANCE_OF: &'static str = "balanceOf(address)";
    pub const TRANSFER_EVENT: &'static str = "Transfer(address,address,u64)";

    pub fn mint(host: &mut Host, to: [u8; 20], amount: u64) {
        let next = Self::balance(host, &to).saturating_add(amount);
        balances().set(host, &to, &u64_le(next));
    }

    pub fn balance(host: &Host, account: &[u8; 20]) -> u64 {
        balances().get(host, account).map(read_u64).unwrap_or(0)
    }

    #[xdag::method("balanceOf(address)")]
    pub fn balance_of(host: &mut Host) -> bool {
        if host.input.len() != 20 {
            return false;
        }
        let mut account = [0u8; 20];
        account.copy_from_slice(&host.input);
        host.returns = u64_le(Self::balance(host, &account)).to_vec();
        true
    }

    #[xdag::method("transfer(address,u64)")]
    pub fn transfer(host: &mut Host) -> bool {
        if host.input.len() != 28 {
            return false;
        }
        let mut to = [0u8; 20];
        to.copy_from_slice(&host.input[..20]);
        let amount = read_u64(&host.input[20..]);
        let from = host.caller;
        let have = Self::balance(host, &from);
        if have < amount {
            return false;
        }
        balances().set(host, &from, &u64_le(have - amount));
        let dest = Self::balance(host, &to).saturating_add(amount);
        balances().set(host, &to, &u64_le(dest));
        host.emit(event(Self::TRANSFER_EVENT, &u64_le(amount)));
        true
    }
}

fn balances() -> Map {
    Map::new(&[1])
}

fn read_u64(raw: &[u8]) -> u64 {
    let mut buf = [0u8; 8];
    buf.copy_from_slice(&raw[..8]);
    u64::from_le_bytes(buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::abi::selector;

    #[test]
    fn transfer_moves_balance_and_emits() {
        let mut host = Host::default();
        let alice = [1u8; 20];
        let bob = [2u8; 20];
        Token::mint(&mut host, alice, 50);
        host.caller = alice;
        host.selector = selector(Token::TRANSFER);
        host.input = {
            let mut raw = bob.to_vec();
            raw.extend_from_slice(&u64_le(20));
            raw
        };
        assert!(Token::call(&mut host));
        assert_eq!(Token::balance(&host, &alice), 30);
        assert_eq!(Token::balance(&host, &bob), 20);
        assert_eq!(host.events.len(), 1);
        host.selector = selector(Token::BALANCE_OF);
        host.input = bob.to_vec();
        assert!(Token::call(&mut host));
        assert_eq!(host.returns, u64_le(20));
        assert!(Token::reentrant_selectors().is_empty());
    }
}
