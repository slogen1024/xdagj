import { createHash } from "node:crypto";
import { secp256k1 } from "@noble/curves/secp256k1";
import { ripemd160 } from "@noble/hashes/ripemd160";
import { sha256 } from "@noble/hashes/sha256";
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
/**
 * Inline CALL, 512 bytes, same layout as ChainBlockBuilder. Chunked args throw.
 * header 8 is devnet/testnet (XDAG_FIELD_HEAD_TEST). fee and value are nano.
 */
export function signCall(call) {
    if (call.args.length > 256) {
        throw new Error("args need a chunk chain");
    }
    const compressed = secp256k1.getPublicKey(call.privateKey, true);
    const sender = hash160(compressed);
    const pubkeyType = compressed[0] === 0x03 ? 0x07 : 0x06;
    let typeBits = 0n;
    let n = 0n;
    const put = (field) => {
        typeBits |= BigInt(field) << (n * 4n);
        n += 1n;
    };
    put(call.header);
    put(0x0e);
    put(0x0c);
    put(0x0d);
    put(0x0f);
    const payloadFields = Math.ceil(call.args.length / 32);
    for (let i = 0; i < payloadFields; i++)
        put(0x0f);
    put(pubkeyType);
    put(0x05);
    put(0x05);
    const body = Buffer.concat([
        header(typeBits, call.timestamp, call.fee),
        reversed(bePad(call.nonce)),
        amountField(xdagAmount(call.value), sender),
        amountField(xdagAmount(call.value), call.chain),
        extHeader(call.contract, call.selector, call.gasLimit, call.args.length),
        ...payload(call.args),
        Buffer.from(compressed.subarray(1, 33)),
    ]);
    const preimage = Buffer.alloc(512);
    body.copy(preimage);
    const hash = sha256(sha256(Buffer.concat([preimage, Buffer.from(compressed)])));
    const sig = secp256k1.sign(hash, call.privateKey);
    const block = Buffer.alloc(512);
    body.copy(block);
    Buffer.from(sig.toCompactRawBytes()).copy(block, body.length);
    return block;
}
function header(typeBits, timestamp, fee) {
    const raw = Buffer.alloc(32);
    raw.writeBigUInt64LE(typeBits, 8);
    raw.writeBigUInt64LE(timestamp, 16);
    raw.writeBigUInt64LE(fee, 24);
    return raw;
}
function extHeader(contract, selector, gasLimit, argsLen) {
    const raw = Buffer.alloc(32);
    raw[0] = 1;
    Buffer.from(contract).copy(raw, 2);
    raw.writeUInt32LE(selector >>> 0, 22);
    raw.writeUInt32LE(gasLimit >>> 0, 26);
    raw.writeUInt16LE(argsLen, 30);
    return raw;
}
function amountField(amount, address) {
    const raw = Buffer.alloc(32);
    raw.writeBigUInt64BE(amount, 0);
    Buffer.from(address).copy(raw, 8);
    return reversed(raw);
}
function bePad(value) {
    const raw = Buffer.alloc(32);
    raw.writeBigUInt64BE(value, 0);
    return raw;
}
function reversed(raw) {
    return Buffer.from(raw).reverse();
}
function payload(args) {
    const out = [];
    for (let off = 0; off < args.length; off += 32) {
        const field = Buffer.alloc(32);
        Buffer.from(args.subarray(off, off + 32)).copy(field);
        out.push(field);
    }
    return out;
}
function xdagAmount(nano) {
    const whole = nano / 1000000000n;
    const frac = nano % 1000000000n;
    const product = frac << 32n;
    let low = product / 1000000000n;
    if (product % 1000000000n !== 0n)
        low += 1n;
    return (whole << 32n) + low;
}
function hash160(data) {
    return Buffer.from(ripemd160(sha256(data)));
}
