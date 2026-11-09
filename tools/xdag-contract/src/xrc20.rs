use crate::abi::{event, selector, u64_le};
use crate::host::Host;

/// XRC-20 balances under this host. `transfer(address,u64)` and `balanceOf(address)`.
pub struct Token;

impl Token {
    pub const TRANSFER: &'static str = "transfer(address,u64)";
    pub const BALANCE_OF: &'static str = "balanceOf(address)";
    pub const TRANSFER_EVENT: &'static str = "Transfer(address,address,u64)";

    pub fn mint(host: &mut Host, to: [u8; 20], amount: u64) {
        let next = Self::balance(host, &to).saturating_add(amount);
        host.storage_set(&key(&to), &u64_le(next));
    }

    pub fn balance(host: &Host, account: &[u8; 20]) -> u64 {
        host.storage_get(&key(account))
            .map(read_u64)
            .unwrap_or(0)
    }

    /// Dispatches on `host.selector`. Returns false when the selector is unknown or the
    /// caller cannot cover the transfer.
    pub fn call(host: &mut Host) -> bool {
        if host.selector == selector(Self::BALANCE_OF) {
            if host.input.len() != 20 {
                return false;
            }
            let mut account = [0u8; 20];
            account.copy_from_slice(&host.input);
            host.returns = u64_le(Self::balance(host, &account)).to_vec();
            return true;
        }
        if host.selector != selector(Self::TRANSFER) || host.input.len() != 28 {
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
        host.storage_set(&key(&from), &u64_le(have - amount));
        let dest = Self::balance(host, &to).saturating_add(amount);
        host.storage_set(&key(&to), &u64_le(dest));
        host.emit(event(Self::TRANSFER_EVENT, &u64_le(amount)));
        true
    }
}

fn key(account: &[u8; 20]) -> Vec<u8> {
    let mut raw = Vec::with_capacity(21);
    raw.push(1);
    raw.extend_from_slice(account);
    raw
}

fn read_u64(raw: &[u8]) -> u64 {
    let mut buf = [0u8; 8];
    buf.copy_from_slice(&raw[..8]);
    u64::from_le_bytes(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

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
    }
}
