# XDAG devnet — Hardhat E2E

End-to-end check of the XDAG devnet EVM JSON-RPC (HTTP `:10001`) with Hardhat + ethers.

## Prerequisites
- Node 18+ and npm.
- A running xdagj **devnet** node built + run under **JDK 21**, with `evm.enabled = true` and
  `rpc.http.enabled = true` (both default on devnet). Start it (from the repo root):
  ```
  java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
       -jar target/xdagj-*-executable.jar -d
  ```

## Steps
```
cd tools/hardhat-e2e
npm install
npx hardhat compile
npx hardhat run scripts/deploy.js --network xdagDevnet      # prints the token address
TOKEN=<address> npx hardhat run scripts/interact.js --network xdagDevnet
npx hardhat test --network xdagDevnet
```

## Notes
- **EIP-1559 supported.** The devnet accepts both legacy (type-0) and type-2 transactions: the base fee
  is always 0 and the effective price is `min(maxPriorityFeePerGas, maxFeePerGas)`. `eth_feeHistory` and
  `eth_maxPriorityFeePerGas` are served, so Hardhat/ethers default fee estimation works out of the box.
  The `gasPrice: 1` line in `hardhat.config.js` is now optional — kept as the simplest deterministic
  choice (forces legacy txs); removing it switches to the type-2 fee flow, and both modes work.
- **Chain id** is `51966` (0xCAFE).
- **Funded account:** private key `0x00…01` → `0x7e5f4552091a69125d5dfcb7b8c2659029395bdf`, pre-funded on devnet.
- **Mining:** a tx confirms once the node's miner packs it into a carrier block (batched with other pending txs into one main block),
  so confirmations may take a few blocks.
- For `eth_subscribe`, point an ethers `WebSocketProvider` at `ws://127.0.0.1:10002`.
