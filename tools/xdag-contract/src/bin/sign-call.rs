use xdag_contract::block::{sign_call, Call};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() != 11 {
        eprintln!("sign-call <priv> <ts> <nonce> <chain> <contract> <selector> <gas> <value> <fee> <header> <args>");
        std::process::exit(2);
    }
    let call = Call {
        private_key: hex32(&args[0]),
        timestamp: args[1].parse().unwrap(),
        nonce: args[2].parse().unwrap(),
        chain: hex20(&args[3]),
        contract: hex20(&args[4]),
        selector: args[5].parse().unwrap(),
        gas_limit: args[6].parse().unwrap(),
        value: args[7].parse().unwrap(),
        fee: args[8].parse().unwrap(),
        header: args[9].parse::<u8>().unwrap(),
        args: hex_bytes(&args[10]),
    };
    let block = sign_call(&call).unwrap_or_else(|e| {
        eprintln!("{e}");
        std::process::exit(1);
    });
    println!("{}", block.iter().map(|b| format!("{b:02x}")).collect::<String>());
}

fn hex_bytes(raw: &str) -> Vec<u8> {
    let s = raw.trim_start_matches("0x");
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn hex20(raw: &str) -> [u8; 20] {
    let bytes = hex_bytes(raw);
    bytes.try_into().unwrap()
}

fn hex32(raw: &str) -> [u8; 32] {
    let bytes = hex_bytes(raw);
    bytes.try_into().unwrap()
}
