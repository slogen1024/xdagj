import assert from "node:assert/strict";
import test from "node:test";
import { bytes, event, getBalance, query, selector, sendCall, u32, u64 } from "./abi.mjs";

test("selector and lengths match AbiCodec", () => {
  assert.equal(selector("balanceOf(address)"), 1531377910);
  assert.equal(selector("transfer(address,u64)"), 1433710159);
  assert.equal(selector("Transfer(address,address,u64)"), -2021979357);
  assert.deepEqual([...u32(2)], [2, 0, 0, 0]);
  assert.deepEqual([...u64(1)], [1, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual([...bytes([9, 8])], [2, 0, 0, 0, 9, 8]);
  assert.equal(event("Transfer(address,address,u64)", u64(7)).length, 40);
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
});
