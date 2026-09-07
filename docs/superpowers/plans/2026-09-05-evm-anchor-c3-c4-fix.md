# C3/C4 fix — anchor height rule + import-time hard-reject (2026-09-05)

**Source:** audit round 2 (`docs/audit/2026-09-04-evm-internal-audit-round2.md`) findings C3 and C4.

## Root cause (C3)
`createMainBlock` predicts the confirm height as `nmain + 1`, but `checkNewMain` only confirms a
candidate once ANOTHER candidate sits above it, so at template time the pretop (N−1) is always
unconfirmed and — because `tryToConnect` runs `checkNewMain` BEFORE the top update — N−2 is usually
still unconfirmed too (the periodic `check-main` thread confirms it ~300 ms later). Observed on
devnet: block confirmed at height h carried `anchor.height = h−3`; the validator expects `h−1`.

Deeper: even with the right N, the rule "block N commits root(N−δ)" is unsatisfiable by an honest
miner — root(N−δ) is produced by confirming N−1, which happens only when N itself connects. The
Gate-2 design premise "mining N happens just after setMain(N−1)" is false.

## Decisions
- **D1 rule change (consensus, pre-activation only):** block N commits `root(N−δ−1)` = the newest EVM
  height an honest miner has necessarily executed when templating N (N−2 is the newest confirmed
  height; confirming it executed (N−2)−δ+1). Validator: `expectedHeight = height − lag − 1`; `< 0 → ABSENT`.
- **D2 miner height = chain position:** `nmain + (# unconfirmed BI_MAIN_CHAIN candidates on the
  max-diff path from the pretop down to the last BI_MAIN) + 1`, mirroring `checkNewMain`'s walk.
  Before computing, the miner settles pending confirmations (`checkNewMain()` loop) so
  `root(N−δ−1)` exists locally. EVM-gated (no behavior change with EVM off).
- **D3 daSkip:** semantics unchanged (block N decides matured N−δ+1), now with the correct N.
- **D4 import-time verdict (C4):** in `tryToConnect`, after difficulty/maxDiffLink and before the
  top update: if EVM is on, the block's predicted height ≥ activation, and the node is settled for
  that position (≤1 unconfirmed candidate below it), verify the anchor. `MISMATCH` + hard-reject →
  `INVALID_BLOCK` (never stored, never a pretop); warn-only → log. Applies to blocks that carry an
  anchor, or that carry a nonce (mined candidates) without one. `BEHIND`/`ABSENT`/unsettled → accept
  (setMain re-verifies; its freeze stays as the last resort for a block accepted while behind).
- **D5 byte-identity:** all new paths gated on `evmProcessor != null`; below activation unchanged.

## Tests (TDD order)
1. `StateRootAnchorSelectionTest`/`VerifyTest`: new index N−lag−1 (update pinned N−lag cases).
2. `predictNextMainHeight` on a naturally-built chain equals the next confirmed height.
3. Anchored chain built with the production miner helper confirms through `tryToConnect` →
   `checkNewMain` under `stateRootHardReject=true` (nmain advances; no freeze) — the C3 regression.
4. `createMainBlock` output passes its own import-time check (hard-reject config).
5. C4: wrong-root anchor / anchorless mined block → `INVALID_BLOCK` (hard-reject); accepted under
   warn-only; accepted when BEHIND; sibling continues the chain.

## Docs to update
DA-skip design (root index), ADR-014 line, audit brief §2/§5, Chinese design doc, audit round-2 report.

---

# Addendum — C1/C2 fix (2026-09-05)

## C2 (skip path bypasses the pending-order gate)
`skipMaturedHeight` checkpointed immediately from `latestRoot()`, so with an earlier blob-deferred
height pending it chained ahead of it. Fix: when `pendingHeights()` is non-empty, persist the skip
marker + deposits and `putPending(height, blockHash, ts, List.of())`; `onBlobsAvailable` drains it
in order and `executeAndCheckpoint` folds the sentinel from the marker (same path replay uses).

## C1 (shallow reorg pins the orphaned block's daSkip decision)
Matured height M = N−δ+1 is decided by block N. If N is unwound (depth < δ), M's outcome must be
undone and re-decided by the replacement block. Decisions:
- **Archive, don't delete, the consumed maturity entry**: new EVM_META family `0x0C` (same layout as
  `0x09`), written when M matures, pruned at `matured − historyWindow`.
- **Two sweep boundaries** in `EvmMetaStore.removeAbove(executionBoundary, nativeBoundary)`:
  execution artifacts (0x00–0x07, 0x0A, 0x0B) above `lowestUnwound − δ`; native-height journals
  (0x08 releases, 0x09 maturity, 0x0C archive) above `lowestUnwound − 1`. Releases at re-opened
  heights stem from burns ≤ target (config invariant W ≥ δ−1) and stay valid; releases of re-opened
  burns land at ≥ lowestUnwound and were reversed by the walk.
- **`EvmBlockProcessor.rollbackForReorg(lowestUnwound, lag)`**: `rollbackTo`-style wipe+replay to
  `target = lowestUnwound − lag`, then restore the archived entries of `(target, lowestUnwound−1]`
  into `0x09` so the new chain's blocks re-mature them under their own `daSkip`. At lag=1 this is
  byte-identical to the previous `rollbackTo(lowestUnwound − 1)`.
- **Native side** (`unWindMain`, before the EVM sweep): for each re-opened height, reverse its 0x0B
  fee debit (lock re-credit) AND un-credit block h's amount/fee (it stays main, so `unSetMain` will
  not do it). Pending heights above target are swept; deposits/burns/skip markers are re-derived.

---

# Addendum — P1/P2 fix (2026-09-05)

## P1 (batch body injected as a tx blob)
`ingestEvmTxBlob` stored ANY awaited bytes into the tx keyspace (0x00) and `expandRefs` classified a
ref by which keyspace held it, so the real batch body delivered inside `EVM_TX_REPLY` became an
"undecodable single tx" (sticky, replayed, root-forking). Fix: classification by content —
`EvmTxStore.decodeBatchBody/isBatchBody` is THE classifier; both P2P reply paths go through
`XdagP2pHandler.ingestConsensusBytes` (batch-shaped → 0x01 iff awaited as a batch; anything else →
0x00 iff awaited as a blob; undecodable bytes still satisfy a ref so a garbage-referencing miner gets a
deterministic failed receipt instead of a stall); `expandRefs` also reads batch-shaped bytes in 0x00
as the batch they are (defense in depth, self-heals a pre-fix poisoned store).

## P2 (no admission size cap)
`EvmTxPool.add` rejects `rawRlp.size() > maxTxBytes` with `AddResult.TOO_LARGE` (RPC → -32602
"transaction too large"); Kernel passes `evm.maxP2pTxBytes` so admission and P2P ingest share one bound;
the 7-arg constructor keeps the 128 KiB default.

---

# Addendum — U1/U2 fix (2026-09-07)

Both items are UNGATED (they bite with `evm.enabled=false`), so they had to be closed before the EVM-off
release that precedes any activation height.

## U1 (amount conversion drifted from the deployed network)
Commit `7987b414` rewrote `BasicUtils.amount2xdagNew` as an exact BigDecimal. That function feeds
`XAmount.ofXAmount` on every consensus path (link amounts, stored balances, snapshot import, supply). The
legacy algorithm (`new BigDecimal(first + temp / 2^32)` through a double) drops fraction bits once the
integer part reaches 2^21 XDAG, so the two implementations disagree by ≥1 nano on large amounts and a
mixed-version network splits on a near-full spend from a ≥2.1M XDAG balance. Decision: **the deployed
behaviour is the consensus**; restore the legacy body verbatim, mark it CONSENSUS-FROZEN (any change is a
height-gated hard fork), keep the exact variants only on the display-only `amount2xdag` overloads, and pin
develop-computed outputs in `BasicUtilsTest` / `XAmountTest`.

## U2 (strict anchor parse drops blocks legacy nodes accept)
A block with transport byte 0 ≥ 1 reinterprets nibble 0x0A as `EvmStateAnchor`; `EvmStateAnchor.parse`
threw on reserved flag bits / negative height, and NEW_BLOCK/SYNC_BLOCK construct the Block at decode
time, so new nodes dropped a block develop nodes accept (and could never fetch it → stall behind the main
block referencing it). Decision: **parse leniently, judge at verdict time** — `EvmStateAnchor.parseLenient`
returns `null` for a malformed payload, `Block.parse` uses it (debug log only), the raw bytes/hash are
untouched, and past activation `verifyStateRootAnchor(null, …)` already returns MISMATCH for a main
candidate, so no consensus check is weakened. Tests: `EvmStateAnchorTest`, `BlockEvmStateRootTest`,
`NewBlockMessageMalformedAnchorTest`.

---

# Addendum — P3/B1 fix (2026-09-08)

## P3 (validation failure consumed the tx hash)
Decision: **drop, don't receipt** — from `evm.invalidTxSkipActivationHeight` a ref that fails validation
in `executeOne` is skipped in `executeList` (no receipt, no digest entry, not in the tx list, reserved gas
budget returned). Validation reads only the pre-state and charges nothing, so the verdict is identical on
every node and replay (which re-runs the stored tx list) is unaffected. The alternative "invalid-at-height
marker" would have put the hash in two heights' tx lists (invalid at H1, executed at H2) and broken the
tx→height index; rejected. Gate default active for tooling (`EvmConfig`), devnet 0, shared nets MAX.

## B1 (silent native fork on a blob-behind node)
Decision: **observability parity with the bridge-release BEHIND path, keep the K1 drain credit.** Option
(a) "burn on drain" is only self-consistent under a "deferral ⇒ re-sync" framing anyway (never-behind
nodes still credit) and would make the common no-intermediate-spend case diverge permanently. So: keep
crediting on drain (same journal height M as a never-behind node) and make the actual fork event loud and
precise — `applyBlock` recognises a rejected `XDAG_FIELD_IN` spend whose input block is the payload of a
still-pending EVM height with fee routing active, records `evmFeeDivergenceHeight` (first occurrence) and
logs CRITICAL with re-sync guidance; `setMain` logs CRITICAL at deferral; the drain logs a late-credit WARN.
No consensus change. Follow-up: surface `getEvmFeeDivergenceHeight()` via RPC/telnet status.
