# XDAG devnet EVM — validation runbook

Three independent ways to validate the EVM JSON-RPC surface, cheapest first. **A** needs no node
and no network; **B** and **C** need a running devnet node.

## Prerequisites
- **JDK 21** + **Maven 3.9.x** — to build and run the node. (The build does not work on JDK 17.)
- **Node 18+** and **npm** — for the Hardhat project (B).

---

## A. In-process JUnit E2E — deterministic, no node, no network

`RpcTransportE2ETest` boots the real HTTP + WebSocket RPC servers on ephemeral ports inside one JVM
and drives them over real sockets with the JDK's `java.net.http` client. This is the primary,
repeatable validation — it does **not** depend on mining or timing.

```bash
# from the repo root, with JAVA_HOME on JDK 21 and mvn on PATH:
mvn test -Dtest=RpcTransportE2ETest      # just the harness (fast)
mvn test                                 # full suite (baseline + C4/C5/C6/E2E)
```

It asserts, end to end: `eth_chainId` = `0xcafe`; genesis balance = 1e18 (`0xde0b6b3a7640000`);
contract deploy → receipt (`status 0x1` + `contractAddress`); `eth_getLogs` address- and
topic-filtering; a **C4** historical balance at an old block tag; and over WebSocket a `logs`
subscription delivering `removed:false` then `removed:true` across a reorg, plus `newHeads`.

---

## B. Live Hardhat E2E — needs a running devnet node

The devnet node **self-produces main blocks** (`node.generate.block.enable = true` in
`xdag-devnet.conf`), so submitted eth txs confirm on their own — no external miner required.

**Terminal 1 — build and start the node (JDK 21):**
```bash
mvn clean package -DskipTests
java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar target/xdagj-*-executable.jar -d
```
Leave it running. It exposes HTTP RPC on `:10001` and WebSocket RPC on `:10002`.

**Terminal 2 — run Hardhat against it (Node 18+):**
```bash
cd tools/hardhat-e2e
npm install
npx hardhat compile
npx hardhat run scripts/deploy.js --network xdagDevnet          # prints the token address
TOKEN=<address> npx hardhat run scripts/interact.js --network xdagDevnet
npx hardhat test --network xdagDevnet
```
A tx confirms once the node packs it into a carrier main block (one eth tx per block on v1), so
`waitForDeployment()` / `tx.wait()` may take a few blocks. See `README.md` for the network details.

---

## C. MetaMask — manual

With the node from **B** running, follow `METAMASK.md`: add the `XDAG Devnet` network
(`http://127.0.0.1:10001`, chain id `51966`), import the funded key, send a tx, and import the
`DemoToken` contract to exercise `eth_call balanceOf`. For `eth_subscribe`, point a tool's
`WebSocketProvider` at `ws://127.0.0.1:10002`.
