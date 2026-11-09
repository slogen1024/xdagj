#![no_std]
#![no_main]

use xdag_wasm::host;

const PING: i32 = -150857028;

#[no_mangle]
pub unsafe extern "C" fn init() {}

#[no_mangle]
pub unsafe extern "C" fn recv() {}

#[no_mangle]
pub unsafe extern "C" fn query() {}

#[no_mangle]
pub unsafe extern "C" fn call() {
    let len = host::input_len();
    if len < 0 || len > 64 {
        return;
    }
    host::fill(0, 20, 3);
    host::fill(20, 20, 4);
    if len > 0 {
        host::input_read(host::at(40));
    }
    host::send(host::at(0), host::at(20), PING, host::at(40), len, 0);
}
