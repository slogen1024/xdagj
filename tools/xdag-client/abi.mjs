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
