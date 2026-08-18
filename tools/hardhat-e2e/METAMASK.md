# XDAG devnet — MetaMask runbook

Manual verification that MetaMask can talk to the XDAG devnet EVM. Requires a node running under JDK 21
(see `README.md`) with `rpc.http.enabled = true` and `rpc.http.corsOrigins` allowing MetaMask's origin
(`*` on devnet is fine).

## 1. Add the network
Settings → Networks → **Add a network manually**:
- Network name: `XDAG Devnet`
- New RPC URL: `http://127.0.0.1:10001`
- Chain ID: `51966`
- Currency symbol: `XDAG`

## 2. Import the funded account
Account menu → Import account → Private key:
- Key: `0x0000000000000000000000000000000000000000000000000000000000000001`
- Address: `0x7e5f4552091a69125d5dfcb7b8c2659029395bdf` (pre-funded ~1e24 wei on devnet)

Confirm the balance shows on the `XDAG Devnet` network.

## 3. Send a transaction
Create/import a second account, send a small amount to it, and confirm it lands. (The node must be
mining — the pool→carrier-block path packs the tx into a main block.)

## 4. Add the DemoToken
Deploy `DemoToken` via the Hardhat runbook (`README.md`), then in MetaMask: Import tokens → Custom token
→ paste the contract address → the `DEMO` balance for the deployer should appear (this exercises
`eth_call` `balanceOf`).

## 5. (Optional) WebSocket subscriptions
MetaMask itself uses HTTP. Tools/dapps that use `eth_subscribe` connect to `ws://127.0.0.1:10002`
(e.g. an ethers `WebSocketProvider`). `newHeads` and `logs` are supported.

## Troubleshooting
- **Fees:** both legacy and EIP-1559 (type-2) transactions are supported. The base fee is always 0 and
  the effective price is `min(maxPriorityFeePerGas, maxFeePerGas)`; `eth_feeHistory` and
  `eth_maxPriorityFeePerGas` are served, so MetaMask's default (type-2) fee flow works as-is.
- **Chain ID mismatch:** the network chain id MUST be `51966`, else MetaMask refuses to sign.
- **CORS:** if MetaMask/RPC calls are blocked, ensure the node's `rpc.http.corsOrigins` allows the origin.
- **Nothing confirms:** the node must be mining (a carrier block must include the tx and become main).
