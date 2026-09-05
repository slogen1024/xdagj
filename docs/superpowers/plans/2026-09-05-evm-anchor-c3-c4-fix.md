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
