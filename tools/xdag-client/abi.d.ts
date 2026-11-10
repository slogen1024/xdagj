export type View = "pending" | "confirmed" | "anchored" | "final";
export interface RpcRequest {
    jsonrpc: "2.0";
    id: 1;
    method: string;
    params: unknown[];
}
/** sha256(ascii)[0..4] as a signed big-endian int. Same as AbiCodec.selector. */
export declare function selector(signature: string): number;
export declare function u32(value: number): Buffer;
export declare function u64(value: number | bigint): Buffer;
export declare function bytes(raw: Uint8Array | readonly number[]): Buffer;
export declare function event(signature: string, body: Uint8Array): Buffer;
export declare function request(method: string, params: unknown[]): RpcRequest;
/** chain_getBalance(chain, account, view). Both addresses are hex. */
export declare function getBalance(chain: string, account: string, view: View): RpcRequest;
/** chain_query(chain, contract, selector, args, view). */
export declare function query(chain: string, contract: string, selectorValue: number, args: Uint8Array | readonly number[], view: View): RpcRequest;
/** chain_sendCall(rawBlocks). */
export declare function sendCall(rawBlocks: readonly string[]): RpcRequest;
export declare const VIEWS: readonly View[];
export declare function getState(chain: string, contract: string, keyHex: string, view: View): RpcRequest;
export declare function getProof(chain: string, contract: string, keyHex: string, view: View): RpcRequest;
/** chain_estimateGas has no view. The node tries the pending copy. */
export declare function estimateGas(chain: string, contract: string, selectorValue: number, args: Uint8Array | readonly number[]): RpcRequest;
export declare function getAnchor(chain: string, seq: number): RpcRequest;
export declare function getHead(chain: string): RpcRequest;
export declare function getReceipt(blockHash: string): RpcRequest;
/** Omit dst and the node builds the claim without a destination. */
export declare function buildClaim(chain: string, seq: number, index: number, dst?: string): RpcRequest;
export declare function subscribe(kind: string): RpcRequest;
export declare function pollBalance(chain: string, account: string): RpcRequest[];
/** Words after the chain verb: deploy, chain hex, wasm hex, gas. */
export declare function deployWords(chainHex: string, wasm: Uint8Array | readonly number[], gas: number): string[];
/**
 * Inline CALL, 512 bytes, same layout as ChainBlockBuilder. Chunked args throw.
 * header 8 is devnet/testnet (XDAG_FIELD_HEAD_TEST). fee and value are nano.
 */
export declare function signCall(call: {
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
}): Uint8Array;
