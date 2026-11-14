#![no_std]
#![no_main]

use xdag_wasm::host;

const MINT: i32 = -617361918;
const TRANSFER: i32 = 1433710159;
const BALANCE_OF: i32 = 1531377910;
/// `sha256("Transfer(address,address,u64)")`. The amount follows as a separate emit data field.
const TRANSFER_TOPIC: [u8; 32] = [
    0x87, 0x7b, 0x0b, 0x23, 0xdf, 0x5c, 0xa2, 0x5a, 0x26, 0xca, 0xe5, 0x0d, 0x71, 0x6d, 0xb3, 0x08, 0x35, 0x3f, 0x7f,
    0xfb, 0xc0, 0x76, 0x4c, 0x84, 0xab, 0x7a, 0x25, 0xa0, 0xe6, 0x0d, 0x35, 0x73,
];

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
    } else if sel == BALANCE_OF {
        balance_of();
    }
}

unsafe fn balance_of() {
    if host::input_len() != 20 {
        return;
    }
    host::input_read(host::at(0));
    host::set_byte(64, 1);
    host::copy(65, 0, 20);
    host::write_u64(128, 0);
    host::storage_get(host::at(64), 21, host::at(128), 8);
    host::return_data(host::at(128), 8);
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
    host::write_u64(160, 0);
    host::storage_get(host::at(64), 21, host::at(160), 8);
    let have = host::read_u64(160);
    let amount = host::read_u64(128);
    if have < amount {
        return;
    }
    host::write_u64(176, have - amount);
    host::storage_set(host::at(64), 21, host::at(176), 8);
    host::write_u64(192, 0);
    host::storage_get(host::at(96), 21, host::at(192), 8);
    host::write_u64(200, host::read_u64(192).saturating_add(amount));
    host::storage_set(host::at(96), 21, host::at(200), 8);
    host::write_bytes(208, &TRANSFER_TOPIC);
    host::emit(host::at(208), 32, host::at(128), 8);
}
