# XDAG EVM — External Security Audit Brief

**Date:** 2026-09-01 · **Audited ref:** tag `evm-audit-freeze-1` = commit `3bbfc68f`, branch `dev-evm`, repo `https://github.com/slogen1024/xdagj` · **Status of this document:** engagement entry point — self-contained; no other project document is required to scope the engagement.

## 1. Engagement summary

XDAGJ is the Java implementation of XDAG, a DAG-based PoW cryptocurrency (RandomX). The `dev-evm` branch embeds a **Hyperledger Besu-EVM execution layer** into the node: EVM transactions ride the existing DAG transport, execute deterministically at main-block confirmation, and interoperate with native XDAG through a two-way bridge. The work is feature-complete and internally audited; **no shared network runs it yet** — on testnet/mainnet configuration every activation height is `Long.MAX_VALUE` and `evm.enabled = false`; only the throwaway devnet is live.

We are seeking a security audit of the EVM integration ahead of scheduling any activation on a public network.

- **Audited surface:** `io.xdag.evm.*` (26 files, ~5,000 lines) plus its integration points: three consensus hooks in `io.xdag.core.BlockchainImpl`, five P2P message codes in `io.xdag.net`, the `eth_*` JSON-RPC handlers in `io.xdag.rpc`, and the `evm.*` configuration surface in `io.xdag.config`. Full branch delta vs `develop`: 226 files, +34,615 lines (measured at the audited tag).
- **Verification baseline at the audited ref:** `mvn test` = 633 tests, 0 failures, 0 errors, 0 skipped; `mvn license:check` clean (JDK 21).
- **Engagement shape sought:** manual review of the priority surfaces in §3, findings report per §9, one fix-verification round.

## 2. Architecture primer

XDAG blocks form a DAG; consensus selects a **main chain** whose blocks each confirm a set of DAG blocks. The EVM piggybacks on this: EVM transactions are content-addressed blobs referenced from carrier blocks, and execution is totally ordered by main-block confirmation.

- **Three consensus hooks** (the only places EVM touches consensus): `applyBlock` collects EVM tx references in deterministic DFS order; `setMain` executes a confirmed height's references against persisted EVM world state; `unWindMain` reverses on reorg. EVM code never executes during import, orphan handling, or fork evaluation.
- **δ-lagged execution:** confirming main height H executes matured height M = H − δ + 1 (δ = `evm.stateRootLag`; devnet 1, proposed shared-net 16). Execution inputs buffer in a maturity store until depth is reached.
- **State commitment:** the EVM state is committed as a **chained delta root** (a running keccak chain over per-height execution digests), *not* an account MPT — there is no `eth_getProof`. The root is PoW-committed: each main block carries an `EvmStateAnchor` field `(height, root(H−δ), daSkip)`; a mismatching anchor is hard-rejected on mainnet configuration (warn-only on testnet/devnet).
- **Data availability:** if a miner packs a height whose blobs it cannot make available, it must set the block-committed `daSkip` bit; validators then deterministically skip that height's execution (folding a domain-separated sentinel into the chained root). Nodes missing blobs for an *include* height stall (freeze, request, re-sync) rather than diverge.
- **Bridge (native ↔ EVM):** a depositor sends native XDAG to a fixed lock address with a structured remark; confirmation mints EVM wei 1:1 (1 nano = 10⁹ wei) before that height's txs execute. Withdrawals burn wei via a fixed system contract; burns recorded at height K release native from the lock at K + `bridgeWithdrawalDelay`, all-or-nothing per height, journaled so reorg reversal reverses exactly what was done.
- **Fee routing (transfer-from-lock):** the net EVM fee is native the payer already owns (their wei is a claim on locked native): at credit time the lock is debited, the debit is journaled, and the confirmed payload block's distributable amount gains the fee (the mining pool then distributes it). Nothing is minted; total native supply is conserved as an equality, and `getSupply()` counts the (bridge-gated) genesis-alloc premine seeded into the lock at chain start.

## 3. Scope, by priority

For each surface: the questions we most want answered, and where the code lives (class level, to survive line drift).

### P0-1 · Consensus hooks (DAG partial order → EVM total order)
Is `evmRefs` collection order strictly the `applyBlock` DFS order on every node? Does EVM execution occur **only** in `setMain`? Can any EVM exception escape into consensus and halt or corrupt the node? Are both `BI_APPLIED` settlement points collected?
→ `io.xdag.core.BlockchainImpl` (applyBlock / setMain / unWindMain), `io.xdag.evm.EvmBlockProcessor` (processMainBlock / executeList / executeOne).

### P0-2 · Reorg symmetry & replay identity
Is `rollbackTo`'s wipe-and-replay byte-identical to forward execution (replayed root == stored root)? Do genesis markers, resets, and replay order interact correctly? Is the bridge unwind an exact reversal of journaled actions (release journal 0x08, fee-debit journal 0x0B)? Is an N-block unwind O(n), triggering one wipe?
→ `EvmBlockProcessor.rollbackTo`, `io.xdag.evm.state.EvmMetaStore` (record families 0x00–0x0B), `BlockchainImpl` (reverseReleasedWithdrawals / reverseEvmFeeDebit).

### P0-3 · Bridge conservation (incl. transfer-from-lock fee routing)
Is total native conserved as an **equality** across deposit / gas fee / withdrawal / genesis alloc, under reorgs? Deposit minting: lossless 1 nano = 10⁹ wei; malformed remarks deterministically routed to the recovery address on every node? Withdrawal release: all-or-nothing per height, never partial, never retried into inconsistency? Fee credit: debit-lock → journal → credit-block ordering; deterministic skip-ALL guards (lock shortfall, overflow) identical on every node? Journal hygiene: can `removeAbove` ever sweep a fee-debit journal whose debit was not reversed (we claim sweep ⊆ walk)? Async blob-drain crediting the same payload block as the sync path (a temporarily-behind node must converge on identical block amounts)? `GenesisLockSeeder`: exactly-once semantics (ADDRESS-CF marker; ADD-not-SET to preserve pre-existing lock balance), and the documented snapshot-bootstrap caveat? `getSupply()` exactness?
→ `io.xdag.evm.bridge.*` (esp. `GenesisLockSeeder`, `BridgeConstants`), `BlockchainImpl` (creditEvmFee / reverseEvmFeeDebit / getSupply / onEvmBlobsAvailable), `io.xdag.config.AbstractConfig` (validateBridgeConfig), `io.xdag.db.AddressStore*`.

### P0-4 · State-root anchor & DA skip (PoW-committed)
Anchor encode/decode unambiguous (incl. the daSkip bit)? Miner-write vs validator-check symmetry? Is BEHIND (this node lacks data) cleanly distinguishable from DIVERGE (states disagree) — can a lagging node ever hard-reject an honest chain? Can a stale or adversarial daSkip bit fork the network (we claim it only affects root(H−δ+1), which the next block's anchor re-verifies)? Config split safety (`stateRootHardReject`: mainnet true, others false)?
→ block codec (EvmStateAnchor field), `io.xdag.consensus` (createMainBlock anchor computation), `BlockchainImpl` (anchor verdict handling), `EvmBlockProcessor` (DA_SKIP_SENTINEL folding, maturedPayloadAvailable).

### P1-5 · Gas settlement
Prepay of the effective fee (gated by a maxFee affordability check) before the child-updater snapshot (else refunds resurrect deducted balances)? Revert/OOG still charges the net fee and bumps nonce? Type-2 fee semantics (effective = min(maxPriorityFee, maxFee) at baseFee ≡ 0) exact, type-0 byte-identical? EIP-3529 capped refund (min(refund, gasUsed/5)) fork-gated correctly?
→ `EvmBlockProcessor.executeOne`, `io.xdag.evm.tx.EvmTransaction`, `IntrinsicGas`.

### P1-6 · Transaction codec & crypto
EIP-155 (reject unprotected), EIP-2 low-s enforcement (single valid hash per tx), EIP-2718 envelope dispatch (accept 0x02, reject unknown types), intrinsic gas per EIP-2028/3860, cross-client hash/sender agreement (ethers-pinned vectors)?
→ `EvmTransaction` (decode / signingHash / getSender / getHash), `EvmAddress`.

### P1-7 · P2P DoS surface
Is `isAwaitingBlob`-gated ingest the sole and sufficient defense against unsolicited-blob disk DoS? Does the 128 KiB size gate cover every entry point? Do all five EVM message codes route (0x1B–0x1D, 0x1F–0x20; 0x1E is a retired hole — verify no residual routing)? Is the stall queue "behind", never "diverged"? Batch commitment (0x0F) consistency with per-tx semantics?
→ `io.xdag.net.XdagP2pHandler` (channelRead0 routing, ingest/request handlers), `io.xdag.net.message.p2p.Evm*Message`.

### P2-8 · RPC surface
`eth_call` / `eth_estimateGas` can never commit state (simulation path fully separated from consensus path)? Bounded `eth_getLogs` (scan-range cap)? Auth constant-time, CORS split sane? (Loopback bind is the shipped default.)
→ `io.xdag.rpc.server.handler.EthRequestHandler`, `io.xdag.evm.XdagEvmExecutor` (simulateCall / simulateDeploy).

### P2-9 · Mempool fair eviction (node-local, non-consensus)
Full-pool priority eviction (price ASC → sender-load DESC → age ASC; tails only; incoming sender excluded): can it be weaponized for targeted eviction? Nonce-contiguity preserved?
→ `io.xdag.evm.tx.EvmTxPool`.

## 4. Out of scope

RandomX / PoW consensus itself; pre-existing native XDAG consensus except the three hooks; mining-pool, wallet, telnet-admin subsystems; ecosystem tooling (`tools/hardhat-e2e`, `tools/local-explorer`); the future absolute-MPT state commitment (explicitly deferred, see §7).

## 5. Declared invariants (what the code claims — please try to break them)

1. EVM executes only in `setMain`; never on import/orphan/fork paths.
2. Execution order = `applyBlock` DFS order, identical on every node.
3. Replay (wipe + re-execute) is byte-identical: replayed chained root == stored root.
4. Receipts, reverse indexes, blooms, journals for reorged-out heights are swept with the reorg.
5. `eth_call` / `eth_estimateGas` never reach `commit()` on persistent state.
6. Only blobs a node is awaiting (pending ∪ buffered) are accepted and persisted from the network.
7. Reorg reversal reverses exactly what was journaled (releases 0x08, fee debits 0x0B) — no recomputation.
8. Sweep ⊆ walk: EVM_META truncation can never delete a journal whose native effect was not already reversed by the unwind walk.
9. All economic guards are deterministic skip-ALL (bridge release shortfall, fee-credit lock shortfall, fee overflow): every node takes the same branch given the same chain.
10. Total native supply is conserved as an equality under deposits, withdrawals, gas fees, and the genesis-alloc premine; `getSupply()` is exact.
11. Pre-activation byte-identity: with every `evm.*ActivationHeight` at `Long.MAX_VALUE` and `evm.enabled=false`, node behavior is byte-identical to a non-EVM build (all new paths at least double-gated).
12. Prepaid gas is deducted before the child-updater snapshot; failed txs charge fees and bump nonces per Ethereum semantics.

## 6. Prior findings disclosure (internal — not independent; please re-examine these areas)

A 4-way internal adversarial review (consensus / bridge / gas-economics / P2P·RPC·crypto) ran on 2026-08-30. Conclusion then: no live remotely-exploitable vulnerability on the shipping configuration; several latent traps that arm at activation. All are now closed:

| ID | Severity | Finding | Disposition (fix SHA) |
|----|----------|---------|------------------------|
| A1 | High (latent) | Unguarded `longValueExact()` in the fee credit could throw into a half-applied `setMain` | Fixed: deterministic bitLength>62 skip (e2d2c41c) |
| A2 | — | Same shape on the deposit-mint path | No change: unreachable by construction (long-bounded amount) |
| A3 | Medium | Withdrawal-reversal underflow would abort a reorg | Diagnostic added; fail-loud kept deliberately (e2d2c41c) |
| A4 | High (design) | Fee routing minted native from unbacked genesis-alloc wei; `getSupply()` blind to it | Config fail-safe (e43b2cd7, 0741a8a2) then full transfer-from-lock model (3bbfc68f, 2026-09-01) |
| K1 | High | Async blob-drain discarded its fee → behind-node divergence on block amounts | Fixed: shared credit path, same payload block (b8c72862) |

Lower-priority items acknowledged and open (non-blocking, documented): operator recovery runbook for corrupt EVM_META (fail-fast today); batch decode-loop budget bound; an RPC error-code mapping polish; optional inbound rate-limit / Host-header allowlist (mitigated by loopback default). Full internal report: `docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md` in the repo.

## 7. Known limitations (accepted, documented — not findings)

- State commitment is a chained delta root, not an account MPT: no `eth_getProof`, no light clients; cross-node divergence is caught by the PoW anchor, not by state proofs.
- Opcode placeholders: `GASPRICE` reads 0; `BLOCKHASH` / `PREVRANDAO` / `BASEFEE` / `COINBASE` read 0 (deterministic, no randomness claim).
- `eth_getLogs` scan window ≤ 1024 heights; historical state queries cover the most recent 128 heights only.
- No EIP-1559 base-fee market: `baseFee ≡ 0`; congestion control is a flat `minGasPrice`.
- Snapshot-bootstrap caveat: a node bootstrapping from a state snapshot must carry the genesis-lock-seed marker with the snapshotted balances (shared-net plan keeps `evm.alloc` empty, which sidesteps this).

## 8. Build & verify

Requirements: JDK 21 (Temurin tested), Maven 3.9.x.

```bash
git clone https://github.com/slogen1024/xdagj && cd xdagj
git checkout evm-audit-freeze-1
mvn clean package -DskipTests     # build
mvn test                          # expected: 633 tests, 0 failures, 0 errors, 0 skipped
mvn license:check                 # expected: clean
```

Two long-running tests are excluded from the default suite (`RandomXSyncTest`, `SyncTest`; a third legacy exclude pattern, `SnapshotJTest`, matches no existing file); run individually via `mvn test -Dtest=<Name>` if desired. To run a devnet node:

```bash
java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar target/xdagj-*-executable.jar -d
```

Devnet ships fee routing, the bridge, and a funded genesis alloc active from height 0 (`src/main/resources/xdag-devnet.conf`), so every audited mechanism is exercisable locally.

## 9. Expected deliverables & process

- Findings report with severity taxonomy (Critical / High / Medium / Low / Informational), each finding with reproduction steps or a reasoned exploit path, affected code, and a suggested fix direction.
- We will fix and tag `evm-audit-freeze-2`; one fix-verification round against that tag is in scope.
- Coordinated disclosure: findings remain private until fixes ship on the public branch; the final report will be archived in-repo (launch-plan step ④).
- Contact: repository owner via GitHub (`slogen1024/xdagj`) issues for logistics; a private channel will be established at engagement start for findings.
