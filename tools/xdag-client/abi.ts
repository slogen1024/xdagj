import { createHash } from "node:crypto";

export type View = "pending" | "confirmed" | "anchored" | "final";

export interface RpcRequest {
  jsonrpc: "2.0";
  id: 1;
  method: string;
  params: unknown[];
}

/** sha256(ascii)[0..4] as a signed big-endian int. Same as AbiCodec.selector. */
export function selector(signature: string): number {
  const hash = createHash("sha256").update(signature, "ascii").digest();
  return hash.readInt32BE(0);
}

export function u32(value: number): Buffer {
  const raw = Buffer.alloc(4);
  raw.writeUInt32LE(value);
  return raw;
}

export function u64(value: number | bigint): Buffer {
  const raw = Buffer.alloc(8);
  raw.writeBigUInt64LE(BigInt(value));
  return raw;
}

export function bytes(raw: Uint8Array | readonly number[]): Buffer {
  const body = Buffer.from(raw);
  return Buffer.concat([u32(body.length), body]);
}

export function event(signature: string, body: Uint8Array): Buffer {
  const topic = createHash("sha256").update(signature, "ascii").digest();
  return Buffer.concat([topic, Buffer.from(body)]);
}

export function request(method: string, params: unknown[]): RpcRequest {
  return { jsonrpc: "2.0", id: 1, method, params };
}

/** chain_getBalance(chain, account, view). Both addresses are hex. */
export function getBalance(chain: string, account: string, view: View): RpcRequest {
  return request("chain_getBalance", [chain, account, view]);
}

/** chain_query(chain, contract, selector, args, view). */
export function query(
  chain: string,
  contract: string,
  selectorValue: number,
  args: Uint8Array | readonly number[],
  view: View,
): RpcRequest {
  return request("chain_query", [chain, contract, selectorValue, Buffer.from(args).toString("hex"), view]);
}

/** chain_sendCall(rawBlocks). */
export function sendCall(rawBlocks: readonly string[]): RpcRequest {
  return request("chain_sendCall", [rawBlocks]);
}

export const VIEWS: readonly View[] = ["pending", "confirmed", "anchored", "final"];

export function getState(chain: string, contract: string, keyHex: string, view: View): RpcRequest {
  return request("chain_getState", [chain, contract, keyHex, view]);
}

export function getProof(chain: string, contract: string, keyHex: string, view: View): RpcRequest {
  return request("chain_getProof", [chain, contract, keyHex, view]);
}

/** chain_estimateGas has no view. The node tries the pending copy. */
export function estimateGas(
  chain: string,
  contract: string,
  selectorValue: number,
  args: Uint8Array | readonly number[],
): RpcRequest {
  return request("chain_estimateGas", [chain, contract, selectorValue, Buffer.from(args).toString("hex")]);
}

export function getAnchor(chain: string, seq: number): RpcRequest {
  return request("chain_getAnchor", [chain, seq]);
}

export function getHead(chain: string): RpcRequest {
  return request("chain_getHead", [chain]);
}

export function getReceipt(blockHash: string): RpcRequest {
  return request("chain_getReceipt", [blockHash]);
}

/** Omit dst and the node builds the claim without a destination. */
export function buildClaim(chain: string, seq: number, index: number, dst?: string): RpcRequest {
  const params = dst == null ? [chain, seq, index] : [chain, seq, index, dst];
  return request("chain_buildClaim", params);
}

export function subscribe(kind: string): RpcRequest {
  return request("chain_subscribe", [kind]);
}

export function pollBalance(chain: string, account: string): RpcRequest[] {
  return VIEWS.map((view) => getBalance(chain, account, view));
}

/** Words after the chain verb: deploy, chain hex, wasm hex, gas. */
export function deployWords(chainHex: string, wasm: Uint8Array | readonly number[], gas: number): string[] {
  return ["deploy", chainHex, Buffer.from(wasm).toString("hex"), String(gas)];
}
