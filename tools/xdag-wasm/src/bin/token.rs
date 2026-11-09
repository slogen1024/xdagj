#![no_std]
#![no_main]

use xdag_wasm::host;

const MINT: i32 = -617361918;
const TRANSFER: i32 = 1433710159;

#[no_mangle]
pub unsafe extern "C" fn init() {}

#[no_mangle]
pub unsafe extern "C" fn recv() {}

#[no_mangle]
pub unsafe extern "C" fn query() {}

#[no_mangle]
pub unsafe extern "C" fn call() {
    let sel = host::selector();
    if sel == MINT {
        mint();
    } else if sel == TRANSFER {
        transfer();
    }
}

unsafe fn mint() {
    if host::input_len() != 28 {
        return;
    }
    host::input_read(host::at(0));
    host::set_byte(64, 1);
    host::copy(65, 0, 20);
    host::copy(128, 20, 8);
    host::storage_set(host::at(64), 21, host::at(128), 8);
}

unsafe fn transfer() {
    if host::input_len() != 28 {
        return;
    }
    host::input_read(host::at(0));
    host::set_byte(96, 1);
    host::copy(97, 0, 20);
    host::set_byte(64, 1);
    host::caller(host::at(65));
    host::copy(128, 20, 8);
    host::storage_get(host::at(64), 21, host::at(160), 8);
    let have = host::read_u64(160);
    let amount = host::read_u64(128);
    if have < amount {
        return;
    }
    host::write_u64(176, have - amount);
    host::storage_set(host::at(64), 21, host::at(176), 8);
    host::storage_get(host::at(96), 21, host::at(192), 8);
    host::write_u64(200, host::read_u64(192).saturating_add(amount));
    host::storage_set(host::at(96), 21, host::at(200), 8);
}
