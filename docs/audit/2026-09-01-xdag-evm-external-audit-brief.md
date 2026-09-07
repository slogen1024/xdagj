# XDAG EVM — External Security Audit Brief

**Date:** 2026-09-07 (revision 2; first issued 2026-09-01) · **Audited ref:** tag `evm-audit-freeze-2` = commit `a94d47e2`, branch `dev-evm`, repo `https://github.com/slogen1024/xdagj` (supersedes `evm-audit-freeze-1` = `3bbfc68f`; see §6 for the delta) · **Status of this document:** engagement entry point — self-contained; no other project document is required to scope the engagement. This file lives on `dev-evm` in a docs-only commit after the tag; the code under audit is exactly the tag.

## 1. Engagement summary

XDAGJ is the Java implementation of XDAG, a DAG-based PoW cryptocurrency (RandomX). The `dev-evm` branch embeds a **Hyperledger Besu-EVM execution layer** into the node: EVM transactions ride the existing DAG transport, execute deterministically at main-block confirmation, and interoperate with native XDAG through a two-way bridge. The work is feature-complete and has had two internal audit rounds (2026-08-30, 2026-09-04); **no shared network runs it yet** — on testnet/mainnet configuration every activation height is `Long.MAX_VALUE` and `evm.enabled = false`; only the throwaway devnet is live.

We are seeking a security audit of the EVM integration ahead of scheduling any activation on a public network.

- **Audited surface:** `io.xdag.evm.*` (26 files, ~5,200 lines) plus its integration points: four consensus hooks in `io.xdag.core.BlockchainImpl` (§2), the versioned block codec in `io.xdag.core.Block` / `EvmStateAnchor`, five P2P message codes in `io.xdag.net`, the `eth_*` JSON-RPC handlers in `io.xdag.rpc`, and the `evm.*` configuration surface in `io.xdag.config`. Full branch delta vs `develop`: 238 files, +37,598 / −361 lines (measured at the audited tag).
- **Verification baseline at the audited ref:** `mvn test` = 665 tests, 0 failures, 0 errors, 0 skipped; `mvn license:check` clean (JDK 21).
- **Engagement shape sought:** manual review of the priority surfaces in §3, findings report per §9, one fix-verification round.

## 2. Architecture primer

XDAG blocks form a DAG; consensus selects a **main chain** whose blocks each confirm a set of DAG blocks. The EVM piggybacks on this: EVM transactions are content-addressed blobs referenced from carrier blocks, and execution is totally ordered by main-block confirmation.

- **Four consensus hooks** (the only places EVM touches consensus): `applyBlock` collects EVM tx references in deterministic DFS order; `tryToConnect` (import) verifies the state-root anchor of a main-chain candidate whose confirm height is already settled and rejects a divergent one as `INVALID_BLOCK` (added after round 2, C4); `setMain` executes a confirmed height's references against persisted EVM world state and re-verifies the anchor; `unWindMain` reverses on reorg. EVM execution happens **only inside `setMain`**. Note one indirection: on a hard-reject network the import hook may first *settle* pending confirmations (`settleMainChainConfirmations` = the periodic `checkNewMain` run early), so `setMain` — and therefore execution — can be triggered from the import path; it never runs on orphan handling or fork evaluation.
- **δ-lagged execution:** confirming main height H executes matured height M = H − δ + 1 (δ = `evm.stateRootLag`; devnet 1, proposed shared-net 16). Execution inputs buffer in a maturity store until depth is reached. A shallow reorg (depth < δ) **re-opens** the heights the unwound blocks had matured: the consumed maturity entries are archived (EVM_META family `0x0C`) and restored by `rollbackForReorg`, execution artifacts are swept from `lowestUnwound − δ`, native journals from `lowestUnwound − 1`, and the fee already credited for a re-opened height is reversed, so the replacement blocks re-decide those heights under their own `daSkip` (round 2, C1/C2).
- **State commitment:** the EVM state is committed as a **chained delta root** (a running keccak chain over per-height execution digests), *not* an account MPT — there is no `eth_getProof`. The root is PoW-committed: main block N carries an `EvmStateAnchor` field `(height, rootLow, daSkip)` committing `root(N − δ − 1)` — the newest EVM height an honest miner has necessarily executed at template time (N − 1 is the still-unconfirmed pretop; confirming N − 2 executed N − δ − 1). The miner settles its own pending confirmations, predicts N from the block's chain position (`predictNextMainHeight`), and anchors; validators recompute the same height and return `MATCH` / `MISMATCH` / `ABSENT` (anchoring inactive) / `BEHIND` (this node lacks blobs, so it cannot judge). A `MISMATCH` is rejected at import and hard-rejected in `setMain` on mainnet configuration (`evm.stateRootHardReject`; warn-only on testnet/devnet). A malformed anchor payload parses as "no anchor" (never a parse failure), which the verdict logic treats exactly like an anchorless main candidate (round 2, U2).
- **Data availability:** if a miner packs a height whose blobs it cannot make available, it must set the block-committed `daSkip` bit; validators then deterministically skip that height's execution (folding a domain-separated sentinel into the chained root). Nodes missing blobs for an *include* height stall (freeze, request, re-sync) rather than diverge. A skipped height queues behind any earlier blob-deferred height so the root chain stays in height order.
- **Bridge (native ↔ EVM):** a depositor sends native XDAG to a fixed lock address with a structured remark; confirmation mints EVM wei 1:1 (1 nano = 10⁹ wei) before that height's txs execute. Withdrawals burn wei via a fixed system contract; burns recorded at height K release native from the lock at K + `bridgeWithdrawalDelay`, all-or-nothing per height, journaled so reorg reversal reverses exactly what was done.
- **Fee routing (transfer-from-lock):** the net EVM fee is native the payer already owns (their wei is a claim on locked native): at credit time the lock is debited, the debit is journaled, and the confirmed payload block's distributable amount gains the fee (the mining pool then distributes it). Nothing is minted; total native supply is conserved as an equality, and `getSupply()` counts the (bridge-gated) genesis-alloc premine seeded into the lock at chain start.
- **Rolling-upgrade compatibility (ungated paths):** two code paths run with `evm.enabled = false` and therefore on every node the moment the EVM-capable release is deployed: the native amount conversion (`BasicUtils.amount2xdagNew` ↔ `XAmount.ofXAmount`, 32.32 fixed point ↔ nano) is **consensus-frozen** to the algorithm the deployed network runs (an "exact" rewrite was reverted in round 2, U1), and the block parser accepts the versioned block format (transport byte 0 ≥ 1) leniently so a new node never drops a block a legacy node accepts (U2).

## 3. Scope, by priority

For each surface: the questions we most want answered, and where the code lives (class level, to survive line drift).

### P0-1 · Consensus hooks (DAG partial order → EVM total order)
Is `evmRefs` collection order strictly the `applyBlock` DFS order on every node? Does EVM execution occur **only** in `setMain` (including when import settles confirmations early)? Can any EVM exception escape into consensus and halt or corrupt the node? Are both `BI_APPLIED` settlement points collected?
→ `io.xdag.core.BlockchainImpl` (applyBlock / tryToConnect / setMain / unWindMain / settleMainChainConfirmations), `io.xdag.evm.EvmBlockProcessor` (processConfirmedBlock / processMainBlock / executeList / executeOne).

### P0-2 · Reorg symmetry & replay identity
Is the wipe-and-replay of `rollbackTo` / `rollbackForReorg` byte-identical to forward execution (replayed root == stored root)? Are the two sweep boundaries right — execution artifacts above `lowestUnwound − δ`, native journals (releases 0x08, maturity 0x09, fee debits 0x0B, archive 0x0C) above `lowestUnwound − 1` — and can a restored maturity entry ever be re-matured under a different `daSkip` than the canonical chain commits? Can the `0x0C` archive be pruned while still needed (retention = max(historyWindow, 64))? Do genesis markers, resets, and replay order interact correctly? Is the bridge unwind an exact reversal of journaled actions? Is an N-block unwind O(n), triggering one wipe?
→ `EvmBlockProcessor.rollbackTo / rollbackForReorg`, `io.xdag.evm.state.EvmMetaStore` (record families 0x00–0x0C, `removeAbove(exec, native)`), `BlockchainImpl` (unWindMain / reverseEvmFeeCreditOfReopenedHeight / reverseReleasedWithdrawals / reverseEvmFeeDebit).

### P0-3 · Bridge conservation (incl. transfer-from-lock fee routing)
Is total native conserved as an **equality** across deposit / gas fee / withdrawal / genesis alloc, under reorgs (including δ-lag re-open)? Deposit minting: lossless 1 nano = 10⁹ wei; malformed remarks deterministically routed to the recovery address on every node? Withdrawal release: all-or-nothing per height, never partial, never retried into inconsistency? Fee credit: debit-lock → journal → credit-block ordering; deterministic skip-ALL guards (lock shortfall, overflow) identical on every node? Journal hygiene: can `removeAbove` ever sweep a fee-debit journal whose debit was not reversed (we claim sweep ⊆ walk)? Async blob-drain crediting the same payload block as the sync path (a temporarily-behind node must converge on identical block amounts — and, per B1 in §6, must report a spend it rejected while behind)? `GenesisLockSeeder`: exactly-once semantics (ADDRESS-CF marker; ADD-not-SET to preserve pre-existing lock balance), and the documented snapshot-bootstrap caveat? `getSupply()` exactness?
→ `io.xdag.evm.bridge.*` (esp. `GenesisLockSeeder`, `BridgeConstants`), `BlockchainImpl` (creditEvmFee / reverseEvmFeeDebit / getSupply / onEvmBlobsAvailable), `io.xdag.config.AbstractConfig` (validateBridgeConfig), `io.xdag.db.AddressStore*`.

### P0-4 · State-root anchor & DA skip (PoW-committed)
Anchor encode/decode unambiguous (incl. the daSkip bit and the lenient-parse path)? Is `root(N − δ − 1)` really always available to an honest miner at template time, and does the miner's `predictNextMainHeight` (chain position after settling confirmations) equal the height every validator derives at import and at `setMain`, under concurrent block arrival and during sync? Is BEHIND (this node lacks data) cleanly distinguishable from MISMATCH (states disagree) — can a lagging node ever hard-reject an honest chain? Can the import-time rejection be weaponized (e.g. by racing a candidate's confirmation) to make honest nodes discard an honest block, or to keep a bad-anchor block alive on a hard-reject network? Can a stale or adversarial daSkip bit fork the network (we claim it only affects root(N − δ + 1), which the next block's anchor re-verifies)? Config split safety (`stateRootHardReject`: mainnet true, others false)?
→ `io.xdag.core.EvmStateAnchor` (parse / parseLenient), `io.xdag.core.Block` (codec v1), `BlockchainImpl` (anchoredEvmHeight / prepareStateRootAnchor / predictNextMainHeight / rejectDivergentAnchorAtImport / verifyStateRootAnchor / createMainBlock), `EvmBlockProcessor` (DA_SKIP_SENTINEL folding, maturedPayloadAvailable, skipMaturedHeight).

### P1-5 · Gas settlement
Prepay of the effective fee (gated by a maxFee affordability check) before the child-updater snapshot (else refunds resurrect deducted balances)? Revert/OOG still charges the net fee and bumps nonce? Type-2 fee semantics (effective = min(maxPriorityFee, maxFee) at baseFee ≡ 0) exact, type-0 byte-identical? EIP-3529 capped refund (min(refund, gasUsed/5)) fork-gated correctly?
→ `EvmBlockProcessor.executeOne`, `io.xdag.evm.tx.EvmTransaction`, `IntrinsicGas`.

### P1-6 · Transaction codec & crypto
EIP-155 (reject unprotected), EIP-2 low-s enforcement (single valid hash per tx), EIP-2718 envelope dispatch (accept 0x02, reject unknown types), intrinsic gas per EIP-2028/3860, cross-client hash/sender agreement (ethers-pinned vectors)?
→ `EvmTransaction` (decode / signingHash / getSender / getHash), `EvmAddress`.

### P1-7 · P2P DoS surface & blob classification
Is `isAwaitingBlob` / `isAwaitingBatch`-gated ingest the sole and sufficient defense against unsolicited-blob disk DoS? Classification is by **content** (`EvmTxStore.isBatchBody`: an RLP list of 1..3971 32-byte items is a batch body, anything else a tx blob) — can any byte string be both a valid signed transaction and a valid batch body, or be made to land in the wrong keyspace via either reply message? Does the 128 KiB size gate cover every entry point (P2P ingest AND pool admission `TOO_LARGE`, so a miner can never pack a blob peers refuse)? Do all five EVM message codes route (0x1B–0x1D, 0x1F–0x20; 0x1E is a retired hole — verify no residual routing)? Is the stall queue "behind", never "diverged"? Batch commitment (0x0F) consistency with per-tx semantics?
→ `io.xdag.net.XdagP2pHandler` (channelRead0 routing, ingestConsensusBytes / ingestEvmTxBlob / request handlers), `io.xdag.evm.tx.EvmTxStore` (decodeBatchBody), `io.xdag.evm.tx.EvmTxPool` (maxTxBytes), `io.xdag.net.message.p2p.Evm*Message`.

### P1-8 · Rolling-upgrade compatibility (paths that run with the EVM disabled)
With `evm.enabled = false` and every activation height at `Long.MAX_VALUE`, is a freeze-2 node byte-identical to a `develop` node on every consensus path? Specifically: `BasicUtils.amount2xdagNew` / `XAmount.ofXAmount` / `toXAmount` (32.32 fixed point ↔ nano, deliberately going through a `double` because that is what the deployed network computes — the pinned vectors in `BasicUtilsTest` / `XAmountTest` are develop outputs); `Block.parse` on a block whose transport byte 0 ≥ 1 with any 0x0A payload (must accept exactly what a legacy node accepts, same hash); `NewBlockMessage` / `SyncBlockMessage` decode. Are there any other ungated behavioural differences vs `develop` that could split a mixed-version network?
→ `io.xdag.utils.BasicUtils`, `io.xdag.core.XAmount`, `io.xdag.core.Block`, `io.xdag.core.EvmStateAnchor`, `io.xdag.net.message.consensus.*`.

### P2-9 · RPC surface
`eth_call` / `eth_estimateGas` can never commit state (simulation path fully separated from consensus path)? Bounded `eth_getLogs` (scan-range cap)? Auth constant-time, CORS split sane? (Loopback bind is the shipped default.) Oversized transactions rejected at `eth_sendRawTransaction` (−32602)?
→ `io.xdag.rpc.server.handler.EthRequestHandler`, `io.xdag.evm.XdagEvmExecutor` (simulateCall / simulateDeploy).

### P2-10 · Mempool fair eviction (node-local, non-consensus)
Full-pool priority eviction (price ASC → sender-load DESC → age ASC; tails only; incoming sender excluded): can it be weaponized for targeted eviction? Nonce-contiguity preserved?
→ `io.xdag.evm.tx.EvmTxPool`.

## 4. Out of scope

RandomX / PoW consensus itself; pre-existing native XDAG consensus except the four hooks and the two ungated paths in P1-8; mining-pool, wallet, telnet-admin subsystems; ecosystem tooling (`tools/hardhat-e2e`, `tools/local-explorer`); the future absolute-MPT state commitment (explicitly deferred, see §7).

## 5. Declared invariants (what the code claims — please try to break them)

1. EVM executes only inside `setMain`; never on orphan or fork-evaluation paths (import may *trigger* `setMain` by settling confirmations early, but executes nothing itself).
2. Execution order = `applyBlock` DFS order, identical on every node.
3. Replay (wipe + re-execute, for both `rollbackTo` and `rollbackForReorg`) is byte-identical: replayed chained root == stored root.
4. Receipts, reverse indexes, blooms, journals for reorged-out heights are swept with the reorg; heights re-opened by a shallow reorg are re-decided by the canonical chain, never by the orphaned block.
5. `eth_call` / `eth_estimateGas` never reach `commit()` on persistent state.
6. Only blobs a node is awaiting (pending ∪ buffered) are accepted and persisted from the network, and a blob's keyspace (tx vs batch body) is decided by its content, never by which message delivered it.
7. Reorg reversal reverses exactly what was journaled (releases 0x08, fee debits 0x0B) — no recomputation.
8. Sweep ⊆ walk: EVM_META truncation can never delete a journal whose native effect was not already reversed by the unwind walk.
9. All economic guards are deterministic skip-ALL (bridge release shortfall, fee-credit lock shortfall, fee overflow): every node takes the same branch given the same chain.
10. Total native supply is conserved as an equality under deposits, withdrawals, gas fees, and the genesis-alloc premine; `getSupply()` is exact.
11. Pre-activation identity: with every `evm.*ActivationHeight` at `Long.MAX_VALUE` and `evm.enabled=false`, node behaviour on every consensus path is identical to a `develop` build (all new paths at least double-gated; the ungated amount conversion and block parser are frozen/lenient to match it).
12. Prepaid gas is deducted before the child-updater snapshot; failed txs charge fees and bump nonces per Ethereum semantics.
13. Anchor satisfiability: block N commits `root(N − δ − 1)`, which an honest, fully-synced miner has always executed at template time; the miner's predicted height equals every validator's derived height.
14. A malformed anchor field is never a parse failure: the block parses (same hash, same links) with no anchor, and past activation the verdict logic rejects it as MISMATCH like any anchorless main candidate.

## 6. Prior findings disclosure (internal — not independent; please re-examine these areas)

### 6.1 Round 1 (2026-08-30) — all closed before freeze-1

A 4-way internal adversarial review (consensus / bridge / gas-economics / P2P·RPC·crypto). Conclusion then: no live remotely-exploitable vulnerability on the shipping configuration; several latent traps that arm at activation.

| ID | Severity | Finding | Disposition (fix SHA) |
|----|----------|---------|------------------------|
| A1 | High (latent) | Unguarded `longValueExact()` in the fee credit could throw into a half-applied `setMain` | Fixed: deterministic bitLength>62 skip (e2d2c41c) |
| A2 | — | Same shape on the deposit-mint path | No change: unreachable by construction (long-bounded amount) |
| A3 | Medium | Withdrawal-reversal underflow would abort a reorg | Diagnostic added; fail-loud kept deliberately (e2d2c41c) |
| A4 | High (design) | Fee routing minted native from unbacked genesis-alloc wei; `getSupply()` blind to it | Config fail-safe (e43b2cd7, 0741a8a2) then full transfer-from-lock model (3bbfc68f, 2026-09-01) |
| K1 | High | Async blob-drain discarded its fee → behind-node divergence on block amounts | Fixed: shared credit path, same payload block (b8c72862) |

### 6.2 Round 2 (2026-09-04, run against freeze-1) — the freeze-1 → freeze-2 delta

A 5-way adversarial review with orchestrator re-verification of every finding; C3 was additionally reproduced on a live devnet. All eight High findings are fixed at freeze-2 (each on its own branch, TDD, merged `--no-ff`):

| ID | Severity | Finding (at freeze-1) | Fix at freeze-2 (fix SHA / merge) |
|----|----------|------------------------|-----------------------------------|
| C3 | Critical (launch-blocking) | The anchor rule `root(N − δ)` was **unsatisfiable**: `checkNewMain` confirms a candidate only once another candidate sits above it, so every honestly-mined anchor was one height short and *every* block would MISMATCH → chain halt at activation on a hard-reject network | Rule is now `root(N − δ − 1)`; miner settles confirmations and predicts N from chain position (`prepareStateRootAnchor` / `predictNextMainHeight`); natural-confirmation test added (4b220b7a / 291a240f) |
| C4 | Critical | Hard-reject had no orphaning path: a bad-anchor block stayed `BI_MAIN_CHAIN` forever and froze every hard-reject node | Import-time verdict: `tryToConnect` rejects a settled MISMATCH as `INVALID_BLOCK` so a sibling can take the slot; `setMain` freeze kept as last resort (4b220b7a / 291a240f) |
| C1 | High (δ ≥ 2) | Shallow reorg (< δ) pinned the orphaned block's `daSkip` decision: matured height not re-opened → history-dependent divergence | Maturity archive `0x0C`, `rollbackForReorg` with two sweep boundaries, native fee-credit reversal for re-opened heights (89f671e0 / bb177b80) |
| C2 | High (δ ≥ 2) | `skipMaturedHeight` bypassed the pending-order gate and checkpointed ahead of an earlier blob-deferred height | Skip queues as an empty pending entry, drained in order (89f671e0 / bb177b80) |
| P1 | High (live on devnet) | A real batch body delivered via `EVM_TX_REPLY` was stored in the tx keyspace → treated as an undecodable single tx → status-0 receipt folded into the root; sticky and replayed | Content-based classification (`EvmTxStore.decodeBatchBody`), single ingest path `ingestConsensusBytes`, `expandRefs` defense in depth (2e15c63b / 54bc2281) |
| P2 | High | No admission size cap: RPC accepted a >128 KiB tx, the miner packed it, every peer refused the blob forever → network-wide EVM stall | `EvmTxPool.maxTxBytes` = `evm.maxP2pTxBytes`, `AddResult.TOO_LARGE`, RPC −32602 (2e15c63b / 54bc2281) |
| U1 | High (ungated, mixed-version) | `amount2xdagNew` had been rewritten as exact BigDecimal on a consensus path; differs from the deployed algorithm by ≥ 1 nano once the integer part reaches 2²¹ XDAG → balance split during the rolling upgrade | Legacy algorithm restored verbatim and marked CONSENSUS-FROZEN; develop-computed pins (d65a1b74 / a94d47e2) |
| U2 | Medium-High (ungated, mixed-version) | Strict anchor parse threw on reserved flag bits / negative height at message decode, so new nodes dropped a block legacy nodes accept and could never fetch it | `EvmStateAnchor.parseLenient` used by `Block.parse`; raw bytes/hash untouched; verdict logic unchanged (d65a1b74 / a94d47e2) |

**Still open at freeze-2 (acknowledged; please re-examine and challenge our severities).** None is gated off on shared networks *differently* from the rest of the EVM — all sit behind `evm.enabled=false` / `MAX_VALUE` heights.

- **P3 (Medium, miner griefing) — FIXED after freeze-2, on `dev-evm` (acafc3bd; will be in freeze-3):** at freeze-2 a *validation* failure (nonce mismatch, insufficient balance, …) is written as a status-0 / gasUsed-0 receipt and the tx hash is thereafter deduplicated away while the sender nonce is unchanged — any miner can "burn" any pending tx at zero cost by referencing it out of order. Fix: from `evm.invalidTxSkipActivationHeight` (devnet 0, shared nets MAX) a validation-failed ref is dropped from the block outright (no receipt, no digest entry, not in the tx list, reserved budget returned); deterministic, hash stays executable later; legacy behaviour byte-identical below the gate. Please review the gate and the determinism argument (validation reads only pre-state) on `dev-evm`.
- **B1 (Medium, δ ≥ 2) — FIXED after freeze-2, on `dev-evm` (cca699e0; will be in freeze-3):** at freeze-2, between `setMain(M + δ − 1)` and an async blob-drain a behind node has not yet credited block M's fee, so it rejects the pool's payout block spending that amount (insufficient balance → `BI_MAIN_REF` only) and never re-evaluates it — the fee path forked silently where the withdrawal path freezes CRITICAL. Fix (node-local, no consensus change; parity with the release path): `applyBlock` detects a rejected `XDAG_FIELD_IN` spend whose input block is the payload of a still-deferred height with fee routing active, records `evmFeeDivergenceHeight` and logs CRITICAL with re-sync guidance; deferral and late credit are logged; the K1 drain credit is kept (same journal height as a never-behind node). Please challenge whether a loud-and-re-sync policy is sufficient here or whether the fee credit itself should be made blob-independent.
- **B2 (Low) — FIXED after freeze-2 (196f8d3c, semantics-v2 pack):** the burn scan reads only `status == 1` receipts and a failed frame never surfaces logs.
- **E1–E6 — FIXED after freeze-2 (in freeze-3):** E1/E2/E4/E5 (with B2) form one fork pack behind `evm.semanticsV2ActivationHeight` (196f8d3c; devnet 0, shared nets MAX, byte-identical below): `SELFDESTRUCT` deletes the account + EIP-161 touched-empty cleanup, `BASEFEE` reads 0 (it exceptional-halted at freeze-2 — the freeze-1 brief wrongly said "reads 0"), `GASPRICE` reads the effective price, `BLOCKHASH` resolves canonical main-block hashes, EIP-170 / EIP-3541 creation rules, transaction-start "original" storage in nested frames. E3 (bd1e8013): simulation runs in the tagged block's context with the fork options in force there. E6 (9366265c): shared networks pin every consensus parameter and fork height in code (`EvmConsensusParams`) and refuse to start on a mismatch. Please review the gate placement and the E1 deletion epilogue (`XdagEvmExecutor.finishTransaction`) in particular.
- **R1–R7 — FIXED after freeze-2 (bd1e8013 / 3027b066, in freeze-3):** every eth_* block tag resolves against the EXECUTED EVM head (`Blockchain.getEvmExecutedHeight`); `blockHash` filter; pool-aware `"pending"` nonce; block-wide `logIndex` + real `cumulativeGasUsed`; bisected `eth_estimateGas` over a simulation that charges intrinsic gas; real `logsBloom`; JSON-RPC batches (HTTP + WS, cap 100); reverts as code 3 with the payload in `data`; `newHeads` real headers on the executed head; WS auth via `?token=`. Please probe the batch path for DoS (cap, per-entry isolation) and the executed-head semantics under δ ≥ 2.
- Lower / polish carried over from round 1: operator recovery runbook for corrupt EVM_META (fail-fast today); batch decode-loop budget bound; optional inbound rate-limit / Host-header allowlist (mitigated by loopback default).

Full internal reports in the repo: round 1 `docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`; round 2 `docs/audit/2026-09-04-evm-internal-audit-round2.md` (Chinese; §7 is a functional-completeness matrix, §8 the invariants re-verified as correct, §9 the fix order); fix plan `docs/superpowers/plans/2026-09-05-evm-anchor-c3-c4-fix.md` (with C1/C2, P1/P2, U1/U2 addenda).

## 7. Known limitations (accepted, documented — not findings)

- State commitment is a chained delta root, not an account MPT: no `eth_getProof`, no light clients; cross-node divergence is caught by the PoW anchor, not by state proofs.
- Opcode context: below `evm.semanticsV2ActivationHeight` (the audited tag) `GASPRICE` / `BLOCKHASH` read 0 and `BASEFEE` exceptional-halts; from that height `GASPRICE` is the effective price, `BLOCKHASH` resolves main-block hashes and `BASEFEE` reads 0. `PREVRANDAO` / `COINBASE` read 0 on both sides (deterministic, no randomness claim).
- `eth_getLogs` scan window ≤ 1024 heights (`evm.maxLogScanRange`); historical state queries cover the most recent 128 executed heights only (`evm.stateHistoryWindow`).
- No EIP-1559 base-fee market: `baseFee ≡ 0`; congestion control is a flat `minGasPrice`.
- Snapshot-bootstrap caveat: a node bootstrapping from a state snapshot must carry the genesis-lock-seed marker with the snapshotted balances (shared-net plan keeps `evm.alloc` empty, which sidesteps this).
- Consensus-frozen native amount conversion: the 32.32 ↔ nano conversion goes through a `double` and loses fraction bits above 2²¹ XDAG per link. This is the behaviour of every deployed node and is therefore kept bit-for-bit (P1-8); changing it is a hard fork.

## 8. Build & verify

Requirements: JDK 21 (Temurin tested), Maven 3.9.x.

```bash
git clone https://github.com/slogen1024/xdagj && cd xdagj
git checkout evm-audit-freeze-2
mvn clean package -DskipTests     # build
mvn test                          # expected: 665 tests, 0 failures, 0 errors, 0 skipped
mvn license:check                 # expected: clean
```

Two long-running tests are excluded from the default suite (`RandomXSyncTest`, `SyncTest`; a third legacy exclude pattern, `SnapshotJTest`, matches no existing file); run individually via `mvn test -Dtest=<Name>` if desired. To run a devnet node:

```bash
java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar target/xdagj-*-executable.jar -d
```

Devnet ships fee routing, the bridge, and a funded genesis alloc active from height 0 (`src/main/resources/xdag-devnet.conf`), so every audited mechanism is exercisable locally. Devnet runs δ = 1, so the δ ≥ 2 mechanisms (maturity buffer, DA skip, re-open on shallow reorg, BEHIND verdict) are exercised by the unit/integration tests (`EvmReorgReopenIntegrationTest`, `EvmBlockProcessorTest`, `StateRootAnchor*Test`) rather than by the live devnet; a δ ≥ 2 devnet run is available on request.

## 9. Expected deliverables & process

- Findings report with severity taxonomy (Critical / High / Medium / Low / Informational), each finding with reproduction steps or a reasoned exploit path, affected code, and a suggested fix direction.
- We will fix and tag `evm-audit-freeze-3`; one fix-verification round against that tag is in scope.
- Coordinated disclosure: findings remain private until fixes ship on the public branch; the final report will be archived in-repo (launch-plan step ④).
- Contact: repository owner via GitHub (`slogen1024/xdagj`) issues for logistics; a private channel will be established at engagement start for findings.

---

**Revision history.** r1 2026-09-01: issued against `evm-audit-freeze-1` (3bbfc68f, 633 tests). r2.2 2026-09-08: §6.2 notes E1–E6, B2 and R1–R7 as fixed on `dev-evm` after the tag (196f8d3c / 9366265c / bd1e8013 / 3027b066); §7 opcode limitation split by the semantics-v2 gate. r2.1 2026-09-08: §6.2 notes P3 and B1 as fixed on `dev-evm` after the tag (acafc3bd / cca699e0, suite 670). r2 2026-09-07: re-issued against `evm-audit-freeze-2` (a94d47e2, 665 tests) — anchor rule corrected to `root(N − δ − 1)`, import-time rejection, δ-lag re-open, content-classified ingest, admission size cap, consensus-frozen amount conversion, lenient anchor parse; §6.2 added with the round-2 delta and the open items; `BASEFEE` limitation corrected; priority surfaces P1-8 added, P2-8/P2-9 renumbered to P2-9/P2-10.
