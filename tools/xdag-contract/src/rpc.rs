//! JSON-RPC bodies for every `chain_*` method on `ChainRequestHandler`.
//! Param order matches that handler. Signing stays in `ChainCli`.

pub const VIEWS: [&str; 4] = ["pending", "confirmed", "anchored", "final"];

pub fn get_balance(chain: &str, account: &str, view: &str) -> String {
    request("chain_getBalance", &format!("[\"{chain}\",\"{account}\",\"{view}\"]"))
}

pub fn get_state(chain: &str, contract: &str, key_hex: &str, view: &str) -> String {
    request("chain_getState", &format!("[\"{chain}\",\"{contract}\",\"{key_hex}\",\"{view}\"]"))
}

pub fn get_proof(chain: &str, contract: &str, key_hex: &str, view: &str) -> String {
    request("chain_getProof", &format!("[\"{chain}\",\"{contract}\",\"{key_hex}\",\"{view}\"]"))
}

pub fn estimate_gas(chain: &str, contract: &str, selector: i32, args_hex: &str) -> String {
    request(
        "chain_estimateGas",
        &format!("[\"{chain}\",\"{contract}\",{selector},\"{args_hex}\"]"),
    )
}

pub fn get_anchor(chain: &str, seq: u64) -> String {
    request("chain_getAnchor", &format!("[\"{chain}\",{seq}]"))
}

pub fn get_head(chain: &str) -> String {
    request("chain_getHead", &format!("[\"{chain}\"]"))
}

pub fn get_receipt(block_hash: &str) -> String {
    request("chain_getReceipt", &format!("[\"{block_hash}\"]"))
}

pub fn build_claim(chain: &str, seq: u64, index: u64, dst: Option<&str>) -> String {
    let params = match dst {
        Some(dst) => format!("[\"{chain}\",{seq},{index},\"{dst}\"]"),
        None => format!("[\"{chain}\",{seq},{index}]"),
    };
    request("chain_buildClaim", &params)
}

pub fn subscribe(kind: &str) -> String {
    request("chain_subscribe", &format!("[\"{kind}\"]"))
}

/// One `chain_getBalance` per view, in `pending` / `confirmed` / `anchored` / `final` order.
pub fn poll_balance(chain: &str, account: &str) -> Vec<String> {
    VIEWS.iter().map(|view| get_balance(chain, account, view)).collect()
}

/// Words after the `chain` verb: `deploy <chain> <wasm hex> <gas>`.
pub fn deploy_words(chain_hex: &str, wasm: &[u8], gas: u64) -> Vec<String> {
    vec![
        "deploy".to_string(),
        chain_hex.to_string(),
        hex(wasm),
        gas.to_string(),
    ]
}

fn hex(raw: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(raw.len() * 2);
    for byte in raw {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

pub fn query(chain: &str, contract: &str, selector: i32, args_hex: &str, view: &str) -> String {
    request(
        "chain_query",
        &format!("[\"{chain}\",\"{contract}\",{selector},\"{args_hex}\",\"{view}\"]"),
    )
}

pub fn send_call(raw_blocks: &[&str]) -> String {
    let mut params = String::from("[[");
    for (i, block) in raw_blocks.iter().enumerate() {
        if i > 0 {
            params.push(',');
        }
        params.push('"');
        params.push_str(block);
        params.push('"');
    }
    params.push_str("]]");
    request("chain_sendCall", &params)
}

fn request(method: &str, params: &str) -> String {
    format!(r#"{{"jsonrpc":"2.0","id":1,"method":"{method}","params":{params}}}"#)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bodies_match_the_java_param_order() {
        assert_eq!(
            get_balance("aa", "bb", "confirmed"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_getBalance","params":["aa","bb","confirmed"]}"#
        );
        assert_eq!(
            query("aa", "cc", 1531377910, "0102", "final"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_query","params":["aa","cc",1531377910,"0102","final"]}"#
        );
        assert_eq!(
            send_call(&["abcd"]),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_sendCall","params":[["abcd"]]}"#
        );
        assert_eq!(
            get_state("aa", "cc", "01", "anchored"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_getState","params":["aa","cc","01","anchored"]}"#
        );
        assert_eq!(
            estimate_gas("aa", "cc", 7, "ff"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_estimateGas","params":["aa","cc",7,"ff"]}"#
        );
        assert_eq!(
            build_claim("aa", 4, 1, None),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_buildClaim","params":["aa",4,1]}"#
        );
        assert_eq!(
            build_claim("aa", 4, 1, Some("dd")),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_buildClaim","params":["aa",4,1,"dd"]}"#
        );
        assert_eq!(poll_balance("aa", "bb").len(), 4);
        assert!(poll_balance("aa", "bb")[0].contains("pending"));
        assert!(poll_balance("aa", "bb")[3].contains("final"));
        assert_eq!(deploy_words("aa", &[0x0a, 0x0b], 500_000), ["deploy", "aa", "0a0b", "500000"]);
        assert_eq!(
            get_head("aa"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_getHead","params":["aa"]}"#
        );
        assert_eq!(
            get_anchor("aa", 9),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_getAnchor","params":["aa",9]}"#
        );
        assert_eq!(
            get_receipt("abcd"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_getReceipt","params":["abcd"]}"#
        );
        assert_eq!(
            get_proof("aa", "cc", "01", "final"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_getProof","params":["aa","cc","01","final"]}"#
        );
        assert_eq!(
            subscribe("anchors"),
            r#"{"jsonrpc":"2.0","id":1,"method":"chain_subscribe","params":["anchors"]}"#
        );
    }
}
