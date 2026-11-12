import { createHash } from "node:crypto";
import { secp256k1 } from "@noble/curves/secp256k1";
import { ripemd160 } from "@noble/hashes/ripemd160";
import { sha256 } from "@noble/hashes/sha256";

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

/**
 * CALL, same layout as ChainBlockBuilder. Args longer than 256 bytes are one CHUNK chain.
 * header 8 is devnet/testnet (XDAG_FIELD_HEAD_TEST). fee and value are nano.
 * `block` is the paying block. `chunks` is head-first.
 */
export function signCall(call: {
  header: number;
  timestamp: bigint;
  nonce: bigint;
  chain: Uint8Array;
  contract: Uint8Array;
  selector: number;
  gasLimit: number;
  value: bigint;
  fee: bigint;
  privateKey: Uint8Array;
  args: Uint8Array;
}): { block: Uint8Array; chunks: Uint8Array[] } {
  const chunks = call.args.length > 256 ? splitChunks(call.header, call.timestamp, call.args) : [];
  const head = chunks.length === 0 ? null : hashLow(chunks[0]);
  const inline = head === null ? call.args : Buffer.alloc(0);
  const compressed = secp256k1.getPublicKey(call.privateKey, true);
  const sender = hash160(compressed);
  const pubkeyType = compressed[0] === 0x03 ? 0x07 : 0x06;
  let typeBits = 0n;
  let n = 0n;
  const put = (field: number) => {
    typeBits |= BigInt(field) << (n * 4n);
    n += 1n;
  };
  put(call.header);
  put(0x0e);
  put(0x0c);
  put(0x0d);
  if (head !== null) put(0x03);
  put(0x0f);
  const payloadFields = Math.ceil(inline.length / 32);
  for (let i = 0; i < payloadFields; i++) put(0x0f);
  put(pubkeyType);
  put(0x05);
  put(0x05);
  const parts = [
    header(typeBits, call.timestamp, call.fee),
    reversed(bePad(call.nonce)),
    amountField(xdagAmount(call.value), sender),
    amountField(xdagAmount(call.value), call.chain),
  ];
  if (head !== null) parts.push(reversed(Buffer.from(head)));
  parts.push(extHeader(call.contract, call.selector, call.gasLimit, inline.length, head !== null));
  parts.push(...payload(inline));
  parts.push(Buffer.from(compressed.subarray(1, 33)));
  const body = Buffer.concat(parts);
  const preimage = Buffer.alloc(512);
  body.copy(preimage);
  const hash = sha256(sha256(Buffer.concat([preimage, Buffer.from(compressed)])));
  const sig = secp256k1.sign(hash, call.privateKey);
  const block = Buffer.alloc(512);
  body.copy(block);
  Buffer.from(sig.toCompactRawBytes()).copy(block, body.length);
  return { block, chunks };
}

function header(typeBits: bigint, timestamp: bigint, fee: bigint): Buffer {
  const raw = Buffer.alloc(32);
  raw.writeBigUInt64LE(typeBits, 8);
  raw.writeBigUInt64LE(timestamp, 16);
  raw.writeBigUInt64LE(fee, 24);
  return raw;
}

function extHeader(contract: Uint8Array, selector: number, gasLimit: number, argsLen: number, chained: boolean): Buffer {
  const raw = Buffer.alloc(32);
  raw[0] = 1;
  if (chained) raw[1] = 1;
  Buffer.from(contract).copy(raw, 2);
  raw.writeUInt32LE(selector >>> 0, 22);
  raw.writeUInt32LE(gasLimit >>> 0, 26);
  raw.writeUInt16LE(argsLen, 30);
  return raw;
}

function splitChunks(headerType: number, timestamp: bigint, args: Uint8Array): Buffer[] {
  const n = Math.ceil(args.length / 352);
  let head = timestamp - 1n;
  if ((head & 0xffffn) === 0xffffn) head -= 1n;
  if ((head & 0xffffn) < BigInt(n - 1)) head = (head & ~0xffffn) - 2n;
  let next: Buffer | null = null;
  const tailFirst: Buffer[] = [];
  for (let i = n - 1; i >= 0; i--) {
    const off = i * 352;
    const data = args.subarray(off, Math.min(off + 352, args.length));
    const block = chunkBlock(headerType, head - BigInt(i), i, args.length, data, next);
    next = hashLow(block);
    tailFirst.push(block);
  }
  tailFirst.reverse();
  return tailFirst;
}

function chunkBlock(headerType: number, timestamp: bigint, seq: number, total: number, data: Uint8Array, next: Buffer | null): Buffer {
  let typeBits = 0n;
  let n = 0n;
  const put = (field: number) => {
    typeBits |= BigInt(field) << (n * 4n);
    n += 1n;
  };
  put(headerType);
  if (next !== null) put(0x03);
  put(0x0f);
  const fields = Math.ceil(data.length / 32);
  for (let i = 0; i < fields; i++) put(0x0f);
  put(0x05);
  put(0x05);
  const ext = Buffer.alloc(32);
  ext[0] = 3;
  ext.writeUInt32LE(seq, 1);
  ext.writeUInt32LE(total, 5);
  ext.writeUInt16LE(data.length, 9);
  const body = Buffer.concat([
    header(typeBits, timestamp, 0n),
    ...(next === null ? [] : [reversed(next)]),
    ext,
    ...payload(data),
  ]);
  const block = Buffer.alloc(512);
  body.copy(block);
  return block;
}

function hashLow(block: Uint8Array): Buffer {
  const digest = Buffer.from(sha256(sha256(block)));
  digest.reverse();
  const low = Buffer.alloc(32);
  digest.copy(low, 8, 8, 32);
  return low;
}

function amountField(amount: bigint, address: Uint8Array): Buffer {
  const raw = Buffer.alloc(32);
  raw.writeBigUInt64BE(amount, 0);
  Buffer.from(address).copy(raw, 8);
  return reversed(raw);
}

function bePad(value: bigint): Buffer {
  const raw = Buffer.alloc(32);
  raw.writeBigUInt64BE(value, 0);
  return raw;
}

function reversed(raw: Buffer): Buffer {
  return Buffer.from(raw).reverse();
}

function payload(args: Uint8Array): Buffer[] {
  const out: Buffer[] = [];
  for (let off = 0; off < args.length; off += 32) {
    const field = Buffer.alloc(32);
    Buffer.from(args.subarray(off, off + 32)).copy(field);
    out.push(field);
  }
  return out;
}

function xdagAmount(nano: bigint): bigint {
  const whole = nano / 1000000000n;
  const frac = nano % 1000000000n;
  const product = frac << 32n;
  let low = product / 1000000000n;
  if (product % 1000000000n !== 0n) low += 1n;
  return (whole << 32n) + low;
}

function hash160(data: Uint8Array): Buffer {
  return Buffer.from(ripemd160(sha256(data)));
}
