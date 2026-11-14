//! Host imports. Names and types match `HostAbi.TYPES`.

#[link(wasm_import_module = "xdag")]
extern "C" {
    pub fn input_len() -> i32;
    pub fn input_read(ptr: i32);
    pub fn selector() -> i32;
    pub fn caller(ptr: i32);
    pub fn storage_get(key: i32, key_len: i32, val: i32, val_cap: i32) -> i32;
    pub fn storage_set(key: i32, key_len: i32, val: i32, val_len: i32);
    pub fn send(dst: i32, contract: i32, selector: i32, args: i32, args_len: i32, value: i64) -> i32;
    pub fn emit(topic: i32, topic_len: i32, data: i32, data_len: i32);
    pub fn return_data(ptr: i32, len: i32);
}

static mut SCRATCH: [u8; 256] = [0; 256];

pub unsafe fn at(off: isize) -> i32 {
    core::ptr::addr_of_mut!(SCRATCH).cast::<u8>().offset(off) as i32
}

pub unsafe fn read_u64(off: isize) -> u64 {
    let p = core::ptr::addr_of!(SCRATCH).cast::<u8>().offset(off);
    let mut buf = [0u8; 8];
    core::ptr::copy_nonoverlapping(p, buf.as_mut_ptr(), 8);
    u64::from_le_bytes(buf)
}

pub unsafe fn write_u64(off: isize, value: u64) {
    let p = core::ptr::addr_of_mut!(SCRATCH).cast::<u8>().offset(off);
    core::ptr::copy_nonoverlapping(value.to_le_bytes().as_ptr(), p, 8);
}

pub unsafe fn copy(dst: isize, src: isize, n: usize) {
    let base = core::ptr::addr_of_mut!(SCRATCH).cast::<u8>();
    core::ptr::copy_nonoverlapping(base.offset(src), base.offset(dst), n);
}

pub unsafe fn set_byte(off: isize, value: u8) {
    *core::ptr::addr_of_mut!(SCRATCH).cast::<u8>().offset(off) = value;
}

pub unsafe fn write_bytes(off: isize, bytes: &[u8]) {
    let p = core::ptr::addr_of_mut!(SCRATCH).cast::<u8>().offset(off);
    core::ptr::copy_nonoverlapping(bytes.as_ptr(), p, bytes.len());
}

pub unsafe fn fill(off: isize, n: usize, value: u8) {
    let p = core::ptr::addr_of_mut!(SCRATCH).cast::<u8>().offset(off);
    for i in 0..n {
        *p.add(i) = value;
    }
}
