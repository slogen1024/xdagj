//! JSON-RPC bodies for `chain_getBalance`, `chain_query` and `chain_sendCall`.
//! Signing stays in `ChainCli`.

pub fn get_balance(chain: &str, account: &str, view: &str) -> String {
    request("chain_getBalance", &format!("[\"{chain}\",\"{account}\",\"{view}\"]"))
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
    }
}
