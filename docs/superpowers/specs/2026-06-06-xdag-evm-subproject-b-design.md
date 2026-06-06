# XDAG EVM — Sub-project B Spec (On-chain Integration)

- **Date:** 2026-06-06
- **Branch:** `dev-evm`
- **Status:** Draft for review
- **Depends on:** Sub-project A (executor over Besu, committed). See `2026-06-06-xdag-evm-subproject-a-design.md`.

## 1. Context (grounded in current `develop` code)

A deep read-only map of the current core/import/db/crypto model established:

- **Block model:** fixed **512-byte blocks = 16 × 32-byte fields**; field type = 4-bit code in header field 0 (`XdagField.FieldType`: `IN/OUT/SIGN_IN/SIGN_OUT/PUBLIC_KEY/INPUT(0xC)/OUTPUT(0xD)/TRANSACTION_NONCE(0xE)/...`). Address field = amount[0:8] LE + hash/addr[8:32]. Signatures secp256k1 (r,s). Block hash = `doubleSha256(512 bytes).reverse()`. **Hard wall: arbitrary EVM calldata cannot fit in a block (~416 bytes max payload).**
- **XDAG already has an account model:** `XDAG_FIELD_INPUT` + `TRANSACTION_NONCE` + `AddressStore` (per-20-byte-address balance + executed nonce). EVM accounts can align with this address space.
- **Import/consensus:** `BlockchainImpl.tryToConnect()` → `applyBlock()` is a **recursive, deterministic depth-first traversal of the DAG links** — the natural, node-consistent hook for EVM execution and ordering. Forks roll back via `unWindMain()` → `unApplyBlock()`.
- **DB:** one RocksDB instance per `DatabaseName`; `KVSource<K,V>` interface; **no `WriteBatch`/atomicity primitive today**. `XAmount` = `long` nano (1 XDAG = 10⁹ nano); EVM Wei = 10¹⁸ → conversion factor 10⁹.
- **Kernel wiring:** stores init at `Kernel.testStart()` ~L139–159; blockchain L169; register listeners L247; RPC L240. EVM store/executor slot in after stores/blockchain.

## 2. B is decomposed into B1 and B2

B is large and consensus-critical, so it splits:

| | Scope | Decision-risk |
|---|---|---|
| **B1 — State & crypto foundation** *(this session)* | Persistent Besu `WorldUpdater` backed by a new RocksDB `EVM_STATE` store; atomic `batchWrite`; EVM 20-byte address derivation. Makes the EVM **stateful & persistent**; provable by a persistence round-trip test. | Low — bounded, templated from Besu `SimpleWorld`/`SimpleAccount`. |
| **B2 — Consensus integration** *(next cycle)* | An on-chain EVM transaction representation in the DAG, EIP-155 secp256k1 signing/recovery, the `applyBlock()` execution hook with deterministic ordering, per-block state commitment, fork rollback, fee model, Kernel wiring. | **High — needs a protocol-level decision (below) + a build env.** |

## 3. The pivotal B2 decision (surfaced, not yet decided)

**How are EVM transactions (with potentially-KB calldata) carried and ordered on a DAG of fixed 512-byte blocks?** Three approaches:

- **(R1) Payload-by-reference (recommended):** the EVM transaction (EIP-155 RLP blob, incl. calldata) is stored in a new `EVM_TX` store keyed by its hash; the XDAG block carries only a **32-byte commitment** to that hash in a field, marked by a dedicated field type. Execution order = the existing deterministic `applyBlock()` DAG traversal. *Pros:* respects the 512-byte wall, reuses XDAG's deterministic ordering, minimal block-format change. *Cons:* tx payload propagation is a side-channel that must be gossiped/synced alongside blocks.
- **(R2) Calldata size-capped inline:** restrict EVM tx calldata to fit the free field budget (~hundreds of bytes) and pack RLP across fields. *Pros:* fully on-chain, no side-channel. *Cons:* cripplingly small calldata — cannot deploy real contracts (USDT initcode alone is multi-KB). **Rejected for USDT.**
- **(R3) Separate EVM ordering layer:** EVM txs gossiped in a mempool, batched into "EVM blocks" anchored to XDAG main blocks. *Pros:* clean EVM semantics. *Cons:* a second consensus/ordering layer to make deterministic — most complex, highest risk.

**Recommendation: R1.** It is the only approach that both fits the 512-byte block and supports real (multi-KB) contract deploys, while reusing XDAG's existing deterministic main-chain application order. B2 will be specced in full once R1 (or an alternative) is confirmed, plus the fee model and chainId reservation — these are protocol/governance decisions.

## 4. Sub-project B1 — detailed design (this session)

### 4.1 Cross-cutting decisions (B1)
- **EVM state store:** a single new `DatabaseName.EVM_STATE` RocksDB instance with **prefixed keys** — `0x00‖addr(20)` → account record, `0x01‖codeHash(32)` → code, `0x02‖addr(20)‖slot(32)` → storage value. One store ⇒ a single `batchWrite` is atomic for the whole EVM state delta. (Avoids a column-family refactor; cross-store atomicity with XDAG block writes is a B2 concern, and current XDAG writes are already non-atomic across stores.)
- **Atomicity primitive:** add `batchWrite(Map<K,V> puts, Set<K> deletes)` to `KVSource` as a **default method** (loops put/delete) so no existing implementor breaks; a true `org.rocksdb.WriteBatch` override in `RocksdbKVSource` is a gate-time optimization.
- **EVM balance** lives in EVM world-state, **separate from XDAG native balance** (bridging native↔EVM deferred to B2). USDT issuance needs no native value transfer (token balances live in contract storage), so B1 is unblocked.
- **Address derivation:** `EvmAddress.fromUncompressedPublicKey(pub64) = keccak256(pub64)[12:]` (true Keccak-256 via Besu `Hash.keccak256`). The glue from `io.xdag.crypto.keys.ECKeyPair` → 64-byte pubkey is a thin adapter deferred to B2 (depends on the external lib's API); B1 keeps the recipe pure and unit-tested with a known vector.

### 4.2 Files
```
src/main/java/io/xdag/db/rocksdb/DatabaseName.java   (modify: add EVM_STATE)
src/main/java/io/xdag/db/rocksdb/KVSource.java        (modify: default batchWrite)
src/main/java/io/xdag/evm/EvmAddress.java             (create: keccak address derivation)
src/main/java/io/xdag/evm/state/RocksDbWorldUpdater.java (create: WorldUpdater over EVM_STATE; nested like SimpleWorld, root flushes via batchWrite)
src/main/java/io/xdag/evm/state/RocksDbAccount.java    (create: MutableAccount, mirrors SimpleAccount + lazy storage load at root)
src/main/java/io/xdag/evm/state/EvmStateSchema.java    (create: key prefixes + account (de)serialization)

src/test/java/io/xdag/evm/EvmAddressTest.java          (create)
src/test/java/io/xdag/evm/state/InMemoryKVSource.java  (create: hermetic byte[] KVSource for tests)
src/test/java/io/xdag/evm/state/RocksDbWorldStateTest.java (create: persistence round-trip)
```

### 4.3 Persistent WorldUpdater
Mirrors Besu's `SimpleWorld`/`SimpleAccount` (Apache-2.0 template): a nested updater with `parent` + an `Optional`-cache of accounts. The **root** (`parent == null`) is backed by the `EVM_STATE` `KVSource`: `getAccount`/`get` lazily load+deserialize from the store; `commit()` serializes every touched account (record + code + changed storage slots) and persists them in one `batchWrite` (deleted accounts → deletes). Children are in-memory and `commit()` up to their parent exactly as `SimpleWorld` does. `RocksDbAccount.getOriginalStorageValue` reads the slot from the store at the root (or delegates to its parent account in a child), giving correct warm/cold + refund semantics.

The executor from A is unchanged — `XdagEvmExecutor.deploy/call(WorldUpdater, …)` now works against `new RocksDbWorldUpdater(evmStateStore)` exactly as it did against `SimpleWorld`.

### 4.4 Test (proves persistence)
`RocksDbWorldStateTest`: with an `InMemoryKVSource` (hermetic, no native RocksDB), (1) `new RocksDbWorldUpdater(store)`, deploy tiny SSTORE bytecode via `XdagEvmExecutor` and commit; (2) construct a **fresh** `RocksDbWorldUpdater(store)` and assert the account code + the stored slot + nonce survive — proving the serialize→store→reload round-trip. Plus an `EvmAddressTest` with a known pubkey→address vector.

## 5. Verification (DEFERRED → gate)
Same gate as A (JDK 21 + Maven). Add: `mvn -Dtest='EvmAddressTest,RocksDbWorldStateTest' test` green. The `RocksDbWorldUpdater` is templated from the verbatim Besu 26.5.0 `SimpleWorld`/`SimpleAccount`; the few `// CONFIRM` spots (Wei/UInt256 byte API) are the expected first-compile fixups.

## 6. Out of scope (→ B2, needs §3 decision + build env)
On-chain EVM tx representation (R1), EIP-155 RLP encode/sign/recover, `applyBlock()` execution hook + deterministic ordering, per-block state root (MPT), fork rollback of EVM state, fee model, `ECKeyPair`→EVM-address glue, Kernel wiring, snapshot integration, chainId reservation.
