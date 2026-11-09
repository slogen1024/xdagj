use crate::host::Host;

/// Prefix map. The stored key is `prefix ‖ key`.
pub struct Map {
    prefix: Vec<u8>,
}

impl Map {
    pub fn new(prefix: &[u8]) -> Self {
        Self { prefix: prefix.to_vec() }
    }

    pub fn get<'a>(&self, host: &'a Host, key: &[u8]) -> Option<&'a [u8]> {
        host.storage_get(&self.full(key))
    }

    pub fn set(&self, host: &mut Host, key: &[u8], value: &[u8]) {
        let full = self.full(key);
        host.storage_set(&full, value);
    }

    pub fn remove(&self, host: &mut Host, key: &[u8]) {
        let full = self.full(key);
        host.storage_remove(&full);
    }

    fn full(&self, key: &[u8]) -> Vec<u8> {
        let mut raw = self.prefix.clone();
        raw.extend_from_slice(key);
        raw
    }
}

/// Length lives at `prefix`. Element `i` lives at `prefix ‖ 0xff ‖ u32-le i`.
pub struct VecStore {
    prefix: Vec<u8>,
}

impl VecStore {
    pub fn new(prefix: &[u8]) -> Self {
        Self { prefix: prefix.to_vec() }
    }

    pub fn len(&self, host: &Host) -> u32 {
        host.storage_get(&self.prefix).map(read_u32).unwrap_or(0)
    }

    pub fn push(&self, host: &mut Host, value: &[u8]) {
        let index = self.len(host);
        let key = self.elem(index);
        host.storage_set(&key, value);
        host.storage_set(&self.prefix, &index.wrapping_add(1).to_le_bytes());
    }

    pub fn get<'a>(&self, host: &'a Host, index: u32) -> Option<&'a [u8]> {
        if index >= self.len(host) {
            return None;
        }
        host.storage_get(&self.elem(index))
    }

    fn elem(&self, index: u32) -> Vec<u8> {
        let mut raw = self.prefix.clone();
        raw.push(0xff);
        raw.extend_from_slice(&index.to_le_bytes());
        raw
    }
}

/// Membership is a present key. The value is empty.
pub struct Set {
    prefix: Vec<u8>,
}

impl Set {
    pub fn new(prefix: &[u8]) -> Self {
        Self { prefix: prefix.to_vec() }
    }

    pub fn insert(&self, host: &mut Host, key: &[u8]) {
        let full = self.full(key);
        host.storage_set(&full, &[]);
    }

    pub fn contains(&self, host: &Host, key: &[u8]) -> bool {
        host.storage_get(&self.full(key)).is_some()
    }

    pub fn remove(&self, host: &mut Host, key: &[u8]) {
        let full = self.full(key);
        host.storage_remove(&full);
    }

    fn full(&self, key: &[u8]) -> Vec<u8> {
        let mut raw = self.prefix.clone();
        raw.extend_from_slice(key);
        raw
    }
}

fn read_u32(raw: &[u8]) -> u32 {
    let mut buf = [0u8; 4];
    buf.copy_from_slice(&raw[..4]);
    u32::from_le_bytes(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn map_vec_and_set_share_the_host() {
        let mut host = Host::default();
        let map = Map::new(&[1]);
        map.set(&mut host, &[9], &[7, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(map.get(&host, &[9]), Some(&[7, 0, 0, 0, 0, 0, 0, 0][..]));
        map.remove(&mut host, &[9]);
        assert_eq!(map.get(&host, &[9]), None);

        let list = VecStore::new(b"v");
        list.push(&mut host, &[1]);
        list.push(&mut host, &[2, 3]);
        assert_eq!(list.len(&host), 2);
        assert_eq!(list.get(&host, 1), Some(&[2, 3][..]));
        assert_eq!(list.get(&host, 2), None);

        let set = Set::new(b"s");
        set.insert(&mut host, b"a");
        assert!(set.contains(&host, b"a"));
        set.remove(&mut host, b"a");
        assert!(!set.contains(&host, b"a"));
    }
}
