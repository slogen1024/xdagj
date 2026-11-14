use xdag_contract::block::{
    sign_anchor, sign_challenge, sign_claim, sign_deploy_into, sign_deploy_new, sign_call, Anchor, Call, Challenge,
    Claim, DeployInto, DeployNew, Signed,
};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        usage();
    }
    let signed = match args[0].as_str() {
        "call" => sign_call(&call(&args[1..])),
        "deploy-new" => sign_deploy_new(&deploy_new(&args[1..])),
        "deploy-into" => sign_deploy_into(&deploy_into(&args[1..])),
        "anchor" => sign_anchor(&anchor(&args[1..])),
        "claim" => sign_claim(&claim(&args[1..])),
        "challenge" => sign_challenge(&challenge(&args[1..])),
        _ => usage(),
    };
    let signed = signed.unwrap_or_else(|e| {
        eprintln!("{e}");
        std::process::exit(1);
    });
    print_signed(&signed);
}

fn usage() -> ! {
    eprintln!("sign-ext call|deploy-new|deploy-into|anchor|claim|challenge ...");
    std::process::exit(2);
}

fn call(args: &[String]) -> Call {
    need(args, 11);
    Call {
        private_key: hex32(&args[0]),
        timestamp: num(&args[1]),
        nonce: num(&args[2]),
        chain: hex20(&args[3]),
        contract: hex20(&args[4]),
        selector: args[5].parse().unwrap(),
        gas_limit: args[6].parse().unwrap(),
        value: num(&args[7]),
        fee: num(&args[8]),
        header: args[9].parse().unwrap(),
        args: blob(&args[10]),
    }
}

fn deploy_new(args: &[String]) -> DeployNew {
    need(args, 11);
    DeployNew {
        private_key: hex32(&args[0]),
        timestamp: num(&args[1]),
        nonce: num(&args[2]),
        fee: num(&args[3]),
        gas_limit: num(&args[4]),
        gas_price: num(&args[5]),
        delivery_delay: num(&args[6]),
        max_call_gas: num(&args[7]),
        header: args[8].parse().unwrap(),
        wasm: blob(&args[9]),
        init_args: blob(&args[10]),
    }
}

fn deploy_into(args: &[String]) -> DeployInto {
    need(args, 11);
    let wasm = optional_blob(&args[9]);
    DeployInto {
        private_key: hex32(&args[0]),
        timestamp: num(&args[1]),
        nonce: num(&args[2]),
        fee: num(&args[3]),
        value: num(&args[4]),
        gas_limit: num(&args[5]),
        chain: hex20(&args[6]),
        code_hash: if wasm.is_some() { None } else { Some(hex32(&args[7])) },
        header: args[8].parse().unwrap(),
        wasm,
        init_args: blob(&args[10]),
    }
}

fn anchor(args: &[String]) -> Anchor {
    need(args, 15);
    Anchor {
        private_key: hex32(&args[0]),
        timestamp: num(&args[1]),
        nonce: num(&args[2]),
        fee: num(&args[3]),
        chain: hex20(&args[4]),
        seq: num(&args[5]),
        state_root: hex32(&args[6]),
        outbox_root: hex32(&args[7]),
        prev_seq: num(&args[8]),
        input_count: num(&args[9]),
        segment_count: num(&args[10]),
        prev_anchor: hex32(&args[11]),
        main_block: hex32(&args[12]),
        header: args[13].parse().unwrap(),
        commitment: blob(&args[14]),
    }
}

fn claim(args: &[String]) -> Claim {
    need(args, 13);
    Claim {
        private_key: hex32(&args[0]),
        timestamp: num(&args[1]),
        nonce: num(&args[2]),
        fee: num(&args[3]),
        to_chain_vault: args[4] == "1",
        src_chain: hex20(&args[5]),
        seq: num(&args[6]),
        index: num(&args[7]),
        amount: num(&args[8]),
        recipient: hex20(&args[9]),
        anchor: hex32(&args[10]),
        header: args[11].parse().unwrap(),
        proof: blob(&args[12]),
    }
}

fn challenge(args: &[String]) -> Challenge {
    need(args, 9);
    Challenge {
        private_key: hex32(&args[0]),
        timestamp: num(&args[1]),
        nonce: num(&args[2]),
        fee: num(&args[3]),
        input_index: num(&args[4]),
        deposit: num(&args[5]),
        anchor: hex32(&args[6]),
        header: args[7].parse().unwrap(),
        witness: blob(&args[8]),
    }
}

fn print_signed(signed: &Signed) {
    println!("{}", hex(&signed.block));
    for chunk in &signed.chunks {
        println!("{}", hex(chunk));
    }
}

fn need(args: &[String], n: usize) {
    if args.len() != n {
        usage();
    }
}

fn num(raw: &str) -> u64 {
    raw.parse().unwrap()
}

fn blob(raw: &str) -> Vec<u8> {
    if raw == "-" {
        Vec::new()
    } else {
        hex_bytes(raw)
    }
}

fn optional_blob(raw: &str) -> Option<Vec<u8>> {
    if raw == "-" {
        None
    } else {
        Some(hex_bytes(raw))
    }
}

fn hex(block: &[u8]) -> String {
    block.iter().map(|b| format!("{b:02x}")).collect()
}

fn hex_bytes(raw: &str) -> Vec<u8> {
    let s = raw.trim_start_matches("0x");
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn hex20(raw: &str) -> [u8; 20] {
    hex_bytes(raw).try_into().unwrap()
}

fn hex32(raw: &str) -> [u8; 32] {
    hex_bytes(raw).try_into().unwrap()
}
