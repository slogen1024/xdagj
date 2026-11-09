import { createHash } from "node:crypto";

/** sha256(ascii)[0..4] as a signed big-endian int. Same as AbiCodec.selector. */
export function selector(signature) {
  const hash = createHash("sha256").update(signature, "ascii").digest();
  return hash.readInt32BE(0);
}

export function u32(value) {
  const raw = Buffer.alloc(4);
  raw.writeUInt32LE(value);
  return raw;
}

export function u64(value) {
  const raw = Buffer.alloc(8);
  raw.writeBigUInt64LE(BigInt(value));
  return raw;
}

export function bytes(raw) {
  const body = Buffer.from(raw);
  return Buffer.concat([u32(body.length), body]);
}

export function event(signature, body) {
  const topic = createHash("sha256").update(signature, "ascii").digest();
  return Buffer.concat([topic, Buffer.from(body)]);
}

export function request(method, params) {
  return { jsonrpc: "2.0", id: 1, method, params };
}

/** chain_getBalance(chain, account, view). Both addresses are hex. */
export function getBalance(chain, account, view) {
  return request("chain_getBalance", [chain, account, view]);
}

/** chain_query(chain, contract, selector, args, view). */
export function query(chain, contract, selectorValue, args, view) {
  return request("chain_query", [chain, contract, selectorValue, Buffer.from(args).toString("hex"), view]);
}

/** chain_sendCall(rawBlocks). */
export function sendCall(rawBlocks) {
  return request("chain_sendCall", [rawBlocks]);
}

export const VIEWS = ["pending", "confirmed", "anchored", "final"];

export function getState(chain, contract, keyHex, view) {
  return request("chain_getState", [chain, contract, keyHex, view]);
}

export function getProof(chain, contract, keyHex, view) {
  return request("chain_getProof", [chain, contract, keyHex, view]);
}

/** chain_estimateGas has no view. The node tries the pending copy. */
export function estimateGas(chain, contract, selectorValue, args) {
  return request("chain_estimateGas", [chain, contract, selectorValue, Buffer.from(args).toString("hex")]);
}

export function getAnchor(chain, seq) {
  return request("chain_getAnchor", [chain, seq]);
}

export function getHead(chain) {
  return request("chain_getHead", [chain]);
}

export function getReceipt(blockHash) {
  return request("chain_getReceipt", [blockHash]);
}

/** Omit dst and the node builds the claim without a destination. */
export function buildClaim(chain, seq, index, dst) {
  const params = dst == null ? [chain, seq, index] : [chain, seq, index, dst];
  return request("chain_buildClaim", params);
}

export function subscribe(kind) {
  return request("chain_subscribe", [kind]);
}

export function pollBalance(chain, account) {
  return VIEWS.map((view) => getBalance(chain, account, view));
}

/** Words after the chain verb: deploy, chain hex, wasm hex, gas. */
export function deployWords(chainHex, wasm, gas) {
  return ["deploy", chainHex, Buffer.from(wasm).toString("hex"), String(gas)];
}
