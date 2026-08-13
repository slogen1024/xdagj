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
- **Legacy gas only.** The devnet has no EIP-1559; `hardhat.config.js` sets `gasPrice` so Hardhat/ethers
  never call `eth_feeHistory` / `eth_maxPriorityFeePerGas` (unimplemented). Do not remove that line.
- **Chain id** is `51966` (0xCAFE).
- **Funded account:** private key `0x00…01` → `0x7e5f4552091a69125d5dfcb7b8c2659029395bdf`, pre-funded on devnet.
- **Mining:** a tx confirms once the node's miner packs it into a carrier block (one tx per block on v1),
  so confirmations may take a few blocks.
- For `eth_subscribe`, point an ethers `WebSocketProvider` at `ws://127.0.0.1:10002`.
