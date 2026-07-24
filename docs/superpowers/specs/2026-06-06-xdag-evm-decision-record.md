# XDAG EVM — Architecture Decision Record (ADR)

- **Date:** 2026-07-01
- **Branch:** `dev-evm`
- **Status:** Accepted (devnet defaults); mainnet/testnet values TBD before launch
- **Related:** `2026-06-06-xdag-evm-subproject-b2-r1-protocol.md`, `2026-06-06-xdag-evm-subproject-a-design.md`

This document locks cross-cutting decisions for Sub-projects B2 and C. Items marked **TBD** require governance sign-off before testnet/mainnet.

---

## ADR-001: EVM engine — Besu embed over legacy interpreter

| | |
|--|--|
| **Decision** | Embed `besu-evm:26.5.0` (Shanghai) on `dev-evm`; **do not** rebase `feature/evm` self-handrolled interpreter |
| **Status** | **Accepted** |
| **Rationale** | Legacy interpreter lacks PUSH0 / modern Solidity; 67 files to maintain; Besu gives Shanghai + precompiles + active security patches |
| **Consequences** | `feature/evm` code is reference-only; all new work in `io.xdag.evm` Besu wrapper |

---

## ADR-002: EVM transaction carrier — R1 payload-by-reference

| | |
|--|--|
| **Decision** | Full EIP-155 RLP in `EVM_TX` store; 512 B block carries `XDAG_FIELD_EVM_TX_REF` (32 B hash) |
| **Status** | **Accepted** |
| **Alternatives rejected** | R2 inline calldata (~416 B max — cannot deploy USDT); R3 separate EVM consensus layer |
| **Consequences** | New P2P messages; blob must exist before block import; side store sync is consensus-critical |

Spec: `2026-06-06-xdag-evm-subproject-b2-r1-protocol.md`

---

## ADR-003: Transaction ordering — reuse `applyBlock()` DFS

| | |
|--|--|
| **Decision** | Deterministic execution order = existing `BlockchainImpl.applyBlock()` recursive link traversal + field order within block |
| **Status** | **Accepted** |
| **Alternatives deferred** | Conflux GHAST + epoch topology sort + deferred execution (5 epoch) — optional P2 enhancement if reorg rate is high |
| **Rationale** | Native XDAG already establishes canonical order this way; separate GHAST module is unnecessary for v1 |
| **Consequences** | P0 item "DAG→全序算法" for EVM = **B2 hook**, not new consensus module |

---

## ADR-004: EVM execution commit timing — only at `setMain`

| | |
|--|--|
| **Decision** | Execute and commit `RocksDbWorldUpdater` only inside `setMain` → `applyBlock(true, …)` path |
| **Status** | **Accepted** |
| **Alternatives rejected** | Execute at `tryToConnect` (speculative — double execution on reorg); execute at every link apply |
| **Rationale** | Aligns EVM finality with native main-block confirmation (~2048 s window in `checkNewMain`) |
| **Consequences** | `eth_call` / `eth_estimateGas` use ephemeral journal (no commit); chain head lag for MetaMask block number |

---

## ADR-005: Reorg strategy — checkpoint + replay

| | |
|--|--|
| **Decision** | Store `stateRoot` per main height in `EVM_META`; on reorg revert to ancestor root and replay canonical main blocks |
| **Status** | **Accepted** (v1 devnet: full replay; production may add periodic snapshots — P1) |
| **Alternatives rejected** | Per-tx inverse in `unApplyBlock` (too complex); delay EVM commit by N epochs (deferred to P2) |
| **Consequences** | Reorg cost = O(blocks × txs); acceptable for devnet; monitor on testnet |

---

## ADR-006: chainId

| | |
|--|--|
| **Decision** | Per-network `evm.chainId` in HOCON; register via ethereum-lists/chains before public launch |
| **Status** | **Provisional values below** |

| Network | chainId (decimal) | Hex | Notes |
|---------|-------------------|-----|-------|
| Devnet | 51966 | `0xCAFE` | Placeholder in `EvmConfig.DEVNET_CHAIN_ID` |
| Testnet | **TBD** | **TBD** | Reserve before testnet EVM activation |
| Mainnet | **TBD** | **TBD** | Reserve before mainnet EVM activation |

**Rule:** `chainId` MUST NOT change after EVM activation height on a network.

---

## ADR-007: Fee model — native XDAG pays gas (v1)

| | |
|--|--|
| **Decision** | v1: user submits **account tx block** with native `INPUT` fee (XAmount) covering EVM gas; EVM tx `gasPrice` may be `1` wei placeholder; executor meters gas but settlement is native |
| **Status** | **Accepted for v1 / USDT path** |
| **Future (P1)** | EVM Wei balance on `RocksDbWorldUpdater` pays gas (`tx.gasLimit * tx.gasPrice` deducted from sender EVM account) |
| **Rationale** | USDT lifecycle needs no EVM native balance; avoids native↔EVM bridge in B2 |
| **Consequences** | `eth_gasPrice` returns configured minimum; MetaMask gas estimation maps to native fee field in wallet (B2 wallet work) |

Conversion reference: 1 XDAG = 10⁹ nano (`XAmount`); 1 ETH = 10¹⁸ wei — factor 10⁹ if bridging later.

---

## ADR-008: Nonce — separate EVM account nonce

| | |
|--|--|
| **Decision** | EIP-155 tx uses **EVM account nonce** from `RocksDbWorldUpdater` / `EvmStateSchema`; independent from `AddressStore.executedNonce` for native transfers |
| **Status** | **Accepted** |
| **Rationale** | Standard Ethereum tx semantics for MetaMask / Hardhat |
| **Consequences** | Same secp256k1 key → two address spaces (Base58 XDAG vs Keccak EVM) with **two nonce counters**; wallet must show both |

Address derivation: [`EvmAddress.fromUncompressedPublicKey`](src/main/java/io/xdag/evm/EvmAddress.java) = `keccak256(pub64)[12:]`.

---

## ADR-009: EVM vs native balance — separate ledgers (v1)

| | |
|--|--|
| **Decision** | `EVM_STATE` Wei balances independent from `AddressStore` XDAG balances; no automatic bridge in B2 |
| **Status** | **Accepted** |
| **Rationale** | B1 design; USDT balances live in contract storage |
| **Future (P1)** | Deposit/withdraw precompile or explicit bridge tx type |

---

## ADR-010: Cross-store atomicity — best-effort (v1)

| | |
|--|--|
| **Decision** | Block store write + `EVM_STATE` commit are **not** atomic in v1; crash may require replay from last good `EVM_META` checkpoint |
| **Status** | **Accepted** (devnet) |
| **Future (P1)** | Single RocksDB WriteBatch spanning stores or unified DB instance |

---

## ADR-011: Supported tx types — legacy EIP-155 only (v1)

| | |
|--|--|
| **Decision** | Type-0 legacy txs only; no EIP-1559 / 2930 / 4844 |
| **Status** | **Accepted** |
| **Rationale** | Matches Sub-project A/C legacy gas decision; no `baseFeePerGas` in block header |
| **Consequences** | MetaMask sends type-0 on XDAG network config |

---

## ADR-012: State commitment — MPT state root per main block

| | |
|--|--|
| **Decision** | Persist Ethereum-compatible state root in `EVM_META` after each main block EVM execution |
| **Status** | **Accepted** |
| **Implementation** | Use Besu `StateRoot` / `WorldState` hasher (B2 task); enables future `eth_getProof` |

---

## Salvage guide — `feature/evm` branch

**Do not merge code.** Reference only:

| Artifact | Path (feature/evm) | Reuse |
|----------|---------------------|-------|
| Tx type enum | `io.xdag.core.TransactionType` | CREATE/CALL naming for docs & receipts |
| Tx gas fields | `io.xdag.core.XdagTransaction` | Metadata shape reference |
| eth RPC method list | `io.xdag.rpc.modules.web3.Web3EthModuleImpl` | Sub-project C checklist |
| Account interface | `io.xdag.state.AccountState` | Compare with Besu `MutableAccount` |
| Wei conversion | `io.xdag.utils.EVMUtils` | Fee bridge math (P1) |
| Receipt shape | `io.xdag.evm.client.TransactionReceipt` | C storage design |

**Do not reuse:** `EVM.java`, `OpCode.java`, `Program.java`, `FeeSchedule.java`, Constantinople specs, `RocksdbProxy` (superseded by `RocksDbWorldUpdater`).

---

## P0 gap checklist (dev-evm vs production)

Track against branch `dev-evm`:

| ID | Task | Phase | Status |
|----|------|-------|--------|
| P0-01 | Besu executor + USDT JUnit (A) | A | Code done; `.bin` gate pending |
| P0-02 | Persistent WorldUpdater (B1) | B1 | Code done; Kernel unwired |
| P0-03 | R1 protocol spec | B2 | **Done** (this ADR + B2 R1 spec) |
| P0-04 | `EVM_TX` / `EVM_META` stores + pool | B2 | Not started |
| P0-05 | `EVM_TX_REF` field + block codec | B2 | Not started |
| P0-06 | P2P `0x1B–0x1F` messages | B2 | Not started |
| P0-07 | `applyBlock` EVM hook + receipts | B2 | Not started |
| P0-08 | Reorg checkpoint + replay | B2 | Not started |
| P0-09 | Kernel wiring | B2 | Not started |
| P0-10 | `eth_sendRawTransaction` + core `eth_*` | C | Not started |
| P0-11 | Devnet E2E (deploy USDT → transfer) | C | Not started |
| P0-12 | ~~feature/evm rebase~~ | — | **Cancelled** → Besu route (ADR-001) |
| P0-13 | DAG ordering module | — | **N/A** → ADR-003 (`applyBlock` DFS) |

### P1 (post-MVP)

- XRC token standard
- Event log Bloom index
- `evm.blockGasLimit` / storage quotas in config
- Native↔EVM balance bridge
- Full MetaMask subscriptions (`eth_subscribe`)
- Periodic EVM state snapshots for fast reorg

### P2

- `debug_traceTransaction` / `trace_*` RPC
- Contract verification tool
- EVM fork upgrades (Berlin/London/Cancun)
- XDAG-specific precompiles
- Conflux-style deferred execution (optional)

---

## Open items before testnet

1. Final **chainId** for testnet/mainnet (ADR-006)
2. **activationHeight** per network
3. **Native fee schedule** mapping to EVM gas units (wallet UX)
4. Legal / governance review of hard-fork block format change (`0x0F` repurposing)
5. Security audit of B2 consensus path + P2P blob handling

---

## Document index

| Document | Purpose |
|----------|---------|
| `2026-06-06-xdag-evm-subproject-a-design.md` | A + program overview |
| `2026-06-06-xdag-evm-subproject-b-design.md` | B1/B2 split (B1 done) |
| `2026-06-06-xdag-evm-subproject-b2-r1-protocol.md` | R1 wire + consensus spec |
| `2026-06-06-xdag-evm-decision-record.md` | This ADR |
| `.claude/devs/xdag-smart-contract-research.md` | Strategic research (updated 2026-07-01) |
