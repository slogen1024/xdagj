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
  signCall,
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
  assert.equal(Buffer.from(block).toString("hex"), rust);
});

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
