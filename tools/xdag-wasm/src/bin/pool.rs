#![no_std]
#![no_main]

use xdag_wasm::host;

const SEED: i32 = 671297210;
const SWAP: i32 = -488097224;

#[no_mangle]
pub unsafe extern "C" fn init() {}

#[no_mangle]
pub unsafe extern "C" fn recv() {}

#[no_mangle]
pub unsafe extern "C" fn query() {}

#[no_mangle]
pub unsafe extern "C" fn call() {
    let sel = host::selector();
    if sel == SEED {
        seed();
    } else if sel == SWAP {
        swap();
    }
}

unsafe fn seed() {
    if host::input_len() != 16 {
        return;
    }
    host::input_read(host::at(16));
    write_key(0, b'r', b'0');
    write_key(4, b'r', b'1');
    host::storage_set(host::at(0), 2, host::at(16), 8);
    host::storage_set(host::at(4), 2, host::at(24), 8);
}

unsafe fn swap() {
    if host::input_len() != 16 {
        return;
    }
    write_key(0, b'r', b'0');
    write_key(4, b'r', b'1');
    host::input_read(host::at(48));
    let amount = host::read_u64(48);
    let min_out = host::read_u64(56);
    if amount == 0 {
        return;
    }
    host::storage_get(host::at(0), 2, host::at(16), 8);
    host::storage_get(host::at(4), 2, host::at(24), 8);
    let r0 = host::read_u64(16);
    let r1 = host::read_u64(24);
    let Some(prod) = r0.checked_mul(r1) else {
        return;
    };
    let Some(denom) = r0.checked_add(amount) else {
        return;
    };
    if denom == 0 || r1 == 0 {
        return;
    }
    let new_r1 = prod / denom;
    if new_r1 > r1 {
        return;
    }
    let out = r1 - new_r1;
    if out < min_out {
        return;
    }
    host::write_u64(32, new_r1);
    host::write_u64(40, denom);
    host::storage_set(host::at(0), 2, host::at(40), 8);
    host::storage_set(host::at(4), 2, host::at(32), 8);
}

unsafe fn write_key(at: isize, a: u8, b: u8) {
    host::set_byte(at, a);
    host::set_byte(at + 1, b);
}
