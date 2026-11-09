//! Encoding matches `io.xdag.chain.abi.AbiCodec`. [`xdag::contract`] dispatches the memory host.
//! [`wasm`] emits the three modules the node runs. [`rpc`] builds JSON-RPC bodies. Signing and
//! `chain deploy` stay in `ChainCli`. This crate does not compile Rust down to wasm.

pub extern crate xdag;

mod abi;
mod dex;
mod host;
mod message;
mod rpc;
mod storage;
mod wasm;
mod xrc20;

pub use abi::{address, bytes, event, selector, string, u32_le, u64_le};
pub use dex::Pool;
pub use host::Host;
pub use message::Inbox;
pub use rpc::{get_balance, query, send_call};
pub use storage::{Map, Set, VecStore};
pub use wasm::{inbox, pool, token};
pub use xrc20::Token;
