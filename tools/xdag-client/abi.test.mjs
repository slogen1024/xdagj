import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import test from "node:test";
import {
  buildClaim,
  bytes,
  deployWords,
  estimateGas,
  event,
  getBalance,
  getHead,
  pollBalance,
  query,
  selector,
  sendCall,
  signAnchor,
  signCall,
  signChallenge,
  signClaim,
  signDeployInto,
  signDeployNew,
  subscribe,
  u32,
  u64,
} from "./abi.js";

test("selector and lengths match AbiCodec", () => {
  assert.equal(selector("balanceOf(address)"), 1531377910);
  assert.equal(selector("transfer(address,u64)"), 1433710159);
  assert.equal(selector("Transfer(address,address,u64)"), -2021979357);
  assert.deepEqual([...u32(2)], [2, 0, 0, 0]);
  assert.deepEqual([...u64(1)], [1, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual([...bytes([9, 8])], [2, 0, 0, 0, 9, 8]);
  assert.equal(event("Transfer(address,address,u64)", u64(7)).length, 40);
});

test("typescript call matches the rust block", () => {
  const priv = "a392604efc2fad9c0b3da43b5f698a2e3f270f170d859912be0d54742275c5f6";
  const chain = "0102030405060708090a0b0c0d0e0f1011121314";
  const contract = "14131211100f0e0d0c0b0a090807060504030201";
  const rust = execFileSync(
    "cargo",
    ["run", "--quiet", "--offline", "--manifest-path", "../xdag-contract/Cargo.toml", "--bin", "sign-call", "--",
      priv, "1556925644800", "1", chain, contract, "7", "0", "100", "100000000", "8", "0102"],
    { cwd: new URL(".", import.meta.url), encoding: "utf8" },
  ).trim();
  const block = signCall({
    header: 8,
    timestamp: 1556925644800n,
    nonce: 1n,
    chain: Buffer.from(chain, "hex"),
    contract: Buffer.from(contract, "hex"),
    selector: 7,
    gasLimit: 0,
    value: 100n,
    fee: 100000000n,
    privateKey: Buffer.from(priv, "hex"),
    args: Buffer.from("0102", "hex"),
  });
  assert.equal(Buffer.from(block.block).toString("hex"), rust);
  assert.equal(block.chunks.length, 0);
});

test("typescript chunk chain matches rust", () => {
  const priv = "a392604efc2fad9c0b3da43b5f698a2e3f270f170d859912be0d54742275c5f6";
  const chain = "0102030405060708090a0b0c0d0e0f1011121314";
  const contract = "14131211100f0e0d0c0b0a090807060504030201";
  const args = Buffer.alloc(353, 0);
  for (let i = 0; i < args.length; i++) args[i] = (i + 1) & 0xff;
  const rust = execFileSync(
    "cargo",
    ["run", "--quiet", "--offline", "--manifest-path", "../xdag-contract/Cargo.toml", "--bin", "sign-call", "--",
      priv, "1556925644800", "1", chain, contract, "7", "0", "100", "100000000", "8", args.toString("hex")],
    { cwd: new URL(".", import.meta.url), encoding: "utf8" },
  ).trim().split("\n");
  const signed = signCall({
    header: 8,
    timestamp: 1556925644800n,
    nonce: 1n,
    chain: Buffer.from(chain, "hex"),
    contract: Buffer.from(contract, "hex"),
    selector: 7,
    gasLimit: 0,
    value: 100n,
    fee: 100000000n,
    privateKey: Buffer.from(priv, "hex"),
    args,
  });
  assert.equal(Buffer.from(signed.block).toString("hex"), rust[0]);
  assert.deepEqual(signed.chunks.map((c) => Buffer.from(c).toString("hex")), rust.slice(1));
});

test("typescript deploy anchor claim and challenge match rust", () => {
  const priv = "a392604efc2fad9c0b3da43b5f698a2e3f270f170d859912be0d54742275c5f6";
  const chain = Buffer.from("0102030405060708090a0b0c0d0e0f1011121314", "hex");
  const recipient = Buffer.from("14131211100f0e0d0c0b0a090807060504030201", "hex");
  const wasm = fill(40, 3);
  const init = fill(20, 4);
  const base = { header: 8, timestamp: 1556925644800n, nonce: 1n, fee: 100000000n, privateKey: Buffer.from(priv, "hex") };
  same(signDeployNew({ ...base, gasLimit: 1000n, gasPrice: 7n, deliveryDelay: 8n, maxCallGas: 9n, wasm, initArgs: init }),
    rust(["deploy-new", priv, "1556925644800", "1", "100000000", "1000", "7", "8", "9", "8", wasm.toString("hex"), init.toString("hex")]));
  const hash = Buffer.alloc(32, 0);
  hash[0] = 9;
  const longArgs = fill(225, 2);
  same(signDeployInto({ ...base, chain, value: 250n, gasLimit: 1000n, codeHash: hash, wasm: null, initArgs: longArgs }),
    rust(["deploy-into", priv, "1556925644800", "1", "100000000", "250", "1000", chain.toString("hex"), hash.toString("hex"), "8", "-", longArgs.toString("hex")]));
  const state = word(1);
  const outbox = word(2);
  const prev = word(3);
  const main = word(4);
  same(signAnchor({ ...base, chain, seq: 11n, stateRoot: state, outboxRoot: outbox, prevSeq: 4n, inputCount: 5n, segmentCount: 6n, prevAnchor: prev, mainBlock: main, commitment: Buffer.alloc(0) }),
    rust(["anchor", priv, "1556925644800", "1", "100000000", chain.toString("hex"), "11", state.toString("hex"), outbox.toString("hex"), "4", "5", "6", prev.toString("hex"), main.toString("hex"), "8", "-"]));
  const anchor = word(7);
  const proof = fill(353, 9);
  same(signClaim({ ...base, toChainVault: true, srcChain: chain, seq: 11n, index: 3n, amount: 9n, recipient, anchor, proof }),
    rust(["claim", priv, "1556925644800", "1", "100000000", "1", chain.toString("hex"), "11", "3", "9", recipient.toString("hex"), anchor.toString("hex"), "8", proof.toString("hex")]));
  const witness = fill(10, 1);
  same(signChallenge({ ...base, inputIndex: 6n, deposit: 50n, anchor, witness }),
    rust(["challenge", priv, "1556925644800", "1", "100000000", "6", "50", anchor.toString("hex"), "8", witness.toString("hex")]));
});

function rust(args) {
  return execFileSync(
    "cargo",
    ["run", "--quiet", "--offline", "--manifest-path", "../xdag-contract/Cargo.toml", "--bin", "sign-ext", "--", ...args],
    { cwd: new URL(".", import.meta.url), encoding: "utf8" },
  ).trim().split("\n");
}

function same(signed, lines) {
  assert.equal(Buffer.from(signed.block).toString("hex"), lines[0]);
  assert.deepEqual(signed.chunks.map((c) => Buffer.from(c).toString("hex")), lines.slice(1));
}

function fill(len, seed) {
  const raw = Buffer.alloc(len);
  for (let i = 0; i < len; i++) raw[i] = (seed + i) & 0xff;
  return raw;
}

function word(b) {
  const raw = Buffer.alloc(32);
  for (let i = 0; i < raw.length; i++) raw[i] = (b + i) & 0xff;
  return raw;
}

test("rpc bodies use the chain_* names", () => {
  assert.equal(getBalance("aa", "bb", "final").method, "chain_getBalance");
  assert.deepEqual(getBalance("aa", "bb", "final").params, ["aa", "bb", "final"]);
  const q = query("c", "k", selector("balanceOf(address)"), [1, 2], "pending");
  assert.equal(q.method, "chain_query");
  assert.equal(q.params[2], 1531377910);
  assert.equal(q.params[3], "0102");
  assert.equal(q.params[4], "pending");
  assert.deepEqual(sendCall(["dead"]).params, [["dead"]]);
  assert.deepEqual(estimateGas("c", "k", 7, [255]).params, ["c", "k", 7, "ff"]);
  assert.deepEqual(buildClaim("c", 4, 1).params, ["c", 4, 1]);
  assert.deepEqual(buildClaim("c", 4, 1, "dd").params, ["c", 4, 1, "dd"]);
  assert.deepEqual(getHead("c").params, ["c"]);
  assert.equal(subscribe("events").method, "chain_subscribe");
  assert.deepEqual(
    pollBalance("c", "a").map((row) => row.params[2]),
    ["pending", "confirmed", "anchored", "final"],
  );
  assert.deepEqual(deployWords("aa", [0x0a, 0x0b], 500000), ["deploy", "aa", "0a0b", "500000"]);
});
