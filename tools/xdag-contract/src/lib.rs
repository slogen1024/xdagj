//! Encoding matches `io.xdag.chain.abi.AbiCodec`. The three reference contracts run on
//! [`host::Host`]. The node runs the wasm twins in `ReferenceDappsTest`, not this crate.
//! [`rpc`] builds the JSON-RPC bodies. Signing stays in `ChainCli`.

mod abi;
mod dex;
mod host;
mod message;
mod rpc;
mod xrc20;

pub use abi::{address, bytes, event, selector, string, u32_le, u64_le};
pub use dex::Pool;
pub use host::Host;
pub use message::Inbox;
pub use rpc::{get_balance, query, send_call};
pub use xrc20::Token;
