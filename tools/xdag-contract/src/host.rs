use std::collections::BTreeMap;

/// In-memory stand-in for the `xdag.*` imports a unit test needs.
#[derive(Default)]
pub struct Host {
    storage: BTreeMap<Vec<u8>, Vec<u8>>,
    pub caller: [u8; 20],
    pub dst_chain: [u8; 20],
    pub dst_contract: [u8; 20],
    pub selector: i32,
    pub input: Vec<u8>,
    pub value: u64,
    pub returns: Vec<u8>,
    pub events: Vec<Vec<u8>>,
    pub outbox: Vec<Outbox>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Outbox {
    pub dst_chain: [u8; 20],
    pub contract: [u8; 20],
    pub selector: i32,
    pub args: Vec<u8>,
    pub value: u64,
}

impl Host {
    pub fn storage_get(&self, key: &[u8]) -> Option<&[u8]> {
        self.storage.get(key).map(Vec::as_slice)
    }

    pub fn storage_set(&mut self, key: &[u8], value: &[u8]) {
        self.storage.insert(key.to_vec(), value.to_vec());
    }

    pub fn storage_remove(&mut self, key: &[u8]) {
        self.storage.remove(key);
    }

    pub fn emit(&mut self, body: Vec<u8>) {
        self.events.push(body);
    }

    pub fn send(&mut self, dst_chain: [u8; 20], contract: [u8; 20], selector: i32, args: Vec<u8>, value: u64) {
        self.outbox.push(Outbox { dst_chain, contract, selector, args, value });
    }
}
