# EVM Data-Availability Enforcement — In-Block Skip-Marker (Gate 2) Design

**Status:** Accepted (engineering design for ADR-015 / G2-D1)
**Date:** 2026-08-27
**Depends on:** ADR-013 (native anchor field), ADR-014 (lagged `root(H−δ)` + hard-reject), G1-T1..T4 (delivered: block format `EvmStateAnchor` incl. inert `daSkip` bit, miner writes anchor, `setMain` hard-rejects divergence, 0x1E gossip retired)
**Supersedes/bounds:** D10 stall-and-defer (as the *enforcement* mechanism); precondition for the bridge CRITICAL-skip downgrade (§2.5)
**Hard fork:** yes — bundled into the shared `evm.stateRootActivationHeight` activation with Gate 1

---

## 0. Problem & one-line shape

**Problem.** After Gate 1, a confirmed main block whose EVM blob is genuinely unavailable makes EVM execution **stall indefinitely** (D10, `EvmBlockProcessor.processMainBlock` defer path). Worse, Gate 1 introduced a **latent mainnet trap**: once a blob-stalled node falls δ behind, its `chainedRootAt(mainNumber − δ)` returns a stale root → the G1-T3 anchor check yields MISMATCH → with `evm.stateRootHardReject=true` (mainnet) `setMain` returns *before mutation* → **the node's native chain stalls.** Gate 2 is therefore not optional polish; it is required before `evm.enabled` on any shared network, and it is the same fix.

**Shape (one line).** Relocate EVM execution to **finality depth δ**: when a block confirms at height N, `setMain(N)` executes (or skips) the **matured EVM height `N − δ + 1`**, governed by that block's PoW-committed `daSkip` bit; a missing blob for a committed-*include* height leaves the node **behind, never stalled or forked**; a committed-*skip* height runs no transactions network-wide and folds a canonical "skipped" delta so every node's chained root advances identically.

---

## 1. Why δ-lagged execution (the load-bearing derivation)

Two facts in the delivered code are individually correct but jointly force this design:

1. **The anchor is δ-lagged** (ADR-014, delivered): block-N commits `root(N − δ)`.
2. **EVM execution is immediate** (delivered): `setMain(N)` executes height N the moment N confirms.

A *skip* cannot be divergence-free under immediate execution. If a fast node (has the blob) executes height K while a slow node (lacks it) skips K, their chained roots fork forever. Divergence-freedom **requires** that no node executes K until the network's committed decision for K is visible to all — i.e. the skip decision must itself be PoW-committed and δ-lagged. Hence execution must move to finality depth.

**The index is forced, not chosen.** Keep G1's delivered anchor semantics ("block-N commits `root(N−δ)`") untouched and ask only: *where must execution sit so that `root(N−δ)` exists at mine-time?* Let `E(M)` = the EVM height executed at `setMain(M)`. Mining block-N happens at tip N−1, just after `setMain(N−1)` ran; the miner must be able to read `root(N−δ)`, so it needs `E(N−1) ≥ N−δ`. With `E(M) = M − δ + 1`: `E(N−1) = N − δ` ✓ (exactly available). With `E(M) = M − δ`: `E(N−1) = N − δ − 1 < N − δ` ✗ (root not yet computed at mine-time). **`E(M) = M − δ + 1` is the unique choice** that preserves G1's anchor index while relocating execution.

Consequences of `E(M) = M − δ + 1`:
- `setMain(N)` executes/skips the **matured height `N − δ + 1`**.
- Block-N commits `root(N − δ) = root(maturedHeight − 1)` — unchanged from G1; the anchor verify still compares against `chainedRootAt(N − δ)`.
- Block-N's `daSkip` bit governs the matured height `N − δ + 1` (the height `setMain(N)` acts on).
- EVM only ever executes heights that are **δ − 1 confirmations deep** ⇒ any reorg shallower than δ−1 never touches EVM state (a real bonus; D9's EVM replay becomes safety-only near-dead-code).
- **δ = 1 degenerates to today's behavior** (`E(N) = N`, immediate, skip inert). Real DA enforcement needs **δ ≥ 2**; shared nets use δ = 16. Devnet keeps `stateRootLag = 1` for existing G1 tests, so G2 execution/skip integration tests construct a config with **δ ≥ 2** explicitly (the pure verifiers already take δ as a parameter; the processor gains one — see §4).

---

## 2. Architecture

### 2.1 The δ-maturity buffer (unifies with the existing pending queue)

`setMain(N)` has block-N's refs/deposits in hand (DFS-collected) but must now execute a *different*, older height `N − δ + 1`, whose refs came from that older block. So every confirmed height enqueues its execution inputs, and `setMain` matures the height that is now δ-final:

```
setMain(N):
  1. enqueue(height=N, refs=block-N.evmRefs, deposits=block-N.deposits,
             blockHash, ts, daSkip=block-N.anchor.daSkip)       // persist, unconditional
  2. matured = N - δ + 1
  3. if matured >= activationHeight:  mature(matured)            // execute-or-skip + checkpoint
  4. verify block-N.anchor == chainedRootAt(N - δ)              // reworked G1-T3 (§3.3)
```

`mature(K)` reads K's enqueued inputs and:
- **committed-skip** (`daSkip=true`): write a checkpoint for K that folds the **canonical skipped delta** (§3.2); execute no txs; nonces untouched.
- **committed-include** (`daSkip=false`): if all of K's blobs are present → execute normally (existing `executeAndCheckpoint`); else → **remain deferred** (BEHIND), exactly today's defer path, and drain via `onBlobsAvailable()` when blobs arrive. Deposits for K still persist and mint when K matures (unchanged exactly-once semantics, timing shifts by δ−1).

This is the existing EVM_META pending queue generalized from "only stalled heights" to "every height passes through a δ-deep buffer." The drain/order invariants (ascending, stop at first incomplete) carry over verbatim.

### 2.2 Component map

| Unit | Responsibility | Change |
|--|--|--|
| `EvmStateAnchor` | 32-byte payload incl. `daSkip` bit0 | **none** (delivered, currently inert) |
| `BlockchainImpl.setMain` | enqueue block-N inputs; mature `N−δ+1`; verify anchor after maturing | **reworked** (execution relocation + verdict handling) |
| `BlockchainImpl.computeStateRootAnchor` (miner) | set `daSkip` from pack-time blob availability of `N−δ+1` | **T1c** (today hardcodes `daSkip=false`) |
| `BlockchainImpl.verifyStateRootAnchor` | add **BEHIND** verdict (executed-height not yet reached) | **T1b** (today MATCH/MISMATCH/ABSENT) |
| `EvmBlockProcessor` | δ-maturity buffer; canonical skipped-delta checkpoint; `chainedRootAt` unchanged | **T1a/T1b** |
| `EvmMetaStore` | buffer/queue storage (reuse 0x03 pending or a sibling prefix) | **T1a** |
| bridge deposit persist/mint, withdrawal burn-scan | ride `executeList`; timing shifts by δ−1 | **co-moves T1a**; release reconcile = **T3** |

### 2.3 What does NOT change

- Block binary format, `EvmStateAnchor` codec, `blockFormatVersion` — all delivered by G1-T1.
- The anchor index "block-N commits `root(N−δ)`" and `chainedRootAt` semantics (G1-T2).
- Native consensus / PoW / main-chain selection — EVM execution point moves but native `setMain` mutation order, `nmain++`, rewards, reorg `unWindMain` structure are untouched.
- P2P message codes (tx 0x1B–0x1D, batch 0x1F–0x20). **No new P2P** — the miner's include/skip decision is local (does it have the blob at pack-time), committed in the block; validators read the committed bit. Blob fetch reuses existing gossip/retry.

---

## 3. Detailed semantics

### 3.1 Miner include/skip decision (T1c)

When building the block that will confirm at N (tip N−1), the matured height it governs is `K = N − δ + 1`. K's block sits at depth `(N−1) − K = δ − 2` below the tip, so the miner has had ≈ δ−2 confirmations of fetch time (the "bounded window ≈ δ" of ADR-015). Decision:

- K's blob(s) locally available (present in `EvmTxStore`, fully expandable) ⇒ `daSkip = false` (**include**). The miner is obliged to have published the blob (it built on K, and gossips on first-sight); a miner that commits include for a blob it withholds produces a block honest validators cannot reconstruct → they stay BEHIND and it never becomes canonical if a competing skip/valid block exists. Withholding is self-defeating.
- K's blob(s) missing/unexpandable at pack-time ⇒ `daSkip = true` (**skip**). Honest response to DA pressure.
- Pre-activation or `K < activationHeight` (incl. the first δ−1 heights, and δ=1) ⇒ anchor absent / `daSkip=false`, no skip semantics (mirrors ADR-014 "heights < δ anchor genesis").

Only the PoW-winning block-N's bit is canonical; competing miners may choose differently, but exactly one block-N wins.

### 3.2 Canonical skipped-delta (T1b)

A skipped height K must advance every node's chained root **identically and without any txs**. Define the skipped checkpoint deterministically:

```
root(K) = keccak(root(K-1) ‖ K ‖ SKIP_SENTINEL)      // SKIP_SENTINEL = fixed domain-separated tag
```

folded through the same `executeList` root-chaining machinery (§6.2 of the design doc), with an empty executed-tx set, an empty receipts/bloom set, and `putTxList(K, [])` so replay reproduces it. Bridge deposits at K, if any, **still mint** (a skip withholds only *EVM tx execution*, not confirmed native-side deposit credits — deposits are consensus facts of block K, not blob-dependent). Withdrawal burns cannot occur at a skipped height (no tx executed). Nonces are untouched: skipped txs never ran, senders resubmit.

> **Design note.** Whether deposits mint at a skipped height is a genuine choice. We mint (deposits are block-committed native facts independent of the EVM blob), which keeps the deposit conservation equality intact regardless of skip. The skipped-delta tag is folded *before* any deposit mint so the tag position in the root is fixed.

### 3.3 Reworked anchor verify — the BEHIND verdict (T1b)

`setMain(N)` matures `K = N−δ+1` first, then verifies block-N's anchor (commits `root(N−δ)`). New third outcome:

| Verdict | Condition | Action |
|--|--|--|
| **MATCH** | this node has executed/skipped through `N−δ` and its `chainedRootAt(N−δ)` equals the committed rootLow | proceed |
| **MISMATCH** | this node has executed/skipped through `N−δ` but the root differs | mainnet: hard-reject (stall pre-mutation, as G1-T3); testnet: warn |
| **BEHIND** *(new)* | this node has **not** yet executed height `N−δ` (an earlier committed-include height is deferred awaiting a blob) | **defer, do NOT hard-reject** — the node is legitimately behind, converges via `onBlobsAvailable()` |
| **ABSENT** | `N < activationHeight` or `N−δ < 0` | proceed (no anchor) |

BEHIND is what disarms the latent trap: a blob-lagging node no longer mistakes "I haven't caught up" for "the chain forked." MISMATCH still means a genuine executed-root divergence. The verifier stays pure/static; the "have I executed/skipped through height h?" predicate is supplied by the caller from the highest checkpoint (`EvmMetaStore.highestHeight().orElse(genesis-1) >= h`, where a checkpoint exists for every executed *or* skipped height).

### 3.4 Reorg

EVM now executes only δ−1-deep heights, so a reorg of depth < δ−1 rewrites only native blocks whose matured EVM heights were never executed → **no EVM rollback needed**. A reorg ≥ δ−1 deep (should be practically impossible on a healthy chain; δ=16) still triggers the existing D9 wipe-and-replay via `unWindMain`/`rollbackTo`; the maturity buffer is rebuilt from the canonical chain during replay. `removeAbove(h)` continues to drop checkpoints/receipts/pending/buffer entries above the unwound height. The `daSkip` decisions are re-read from the replayed canonical blocks (they are block-committed, not node-local), so replay reproduces the same skip/execute pattern deterministically.

### 3.5 Bridge co-movement (release reconcile deferred to T3)

- **Deposits:** collected & persisted per confirming block N (unchanged), but **mint** rides `executeList(N)`, which now runs at `setMain(N+δ−1)` — deposits credit δ−1 blocks later. Bounded, deterministic, conservation equality preserved (mint still exactly-once).
- **Withdrawal burns:** detected during `executeList` (receipt-log scan), so burn at height K is detected at `setMain(K+δ−1)`.
- **Withdrawal release** reads burns at `mainNumber − W` (W = `bridgeWithdrawalDelay` = 16). For a burn to be detected before its release: `K + δ − 1 ≤ K + W` ⇒ **`W ≥ δ − 1`** (16 ≥ 15 ✓). This becomes a **config-validation invariant** (`validateBridgeConfig`). The actual release-timing reconciliation, the CRITICAL-skip → normal-path downgrade (§2.5), and end-to-end conservation re-verification are **G2-T3**, not this design's first plan.

---

## 4. Config

- Reuse **`evm.stateRootLag`** (δ) — no new parameter. Devnet `1` (execution stays immediate, skip inert — existing G1 tests unaffected); testnet/mainnet `16`.
- Gate all G2 behavior under the shared **`evm.stateRootActivationHeight`** (Gate 1+2 one hard fork).
- New config-validation invariant: **`bridgeWithdrawalDelay ≥ stateRootLag − 1`** when the bridge is scheduled (§3.5).
- The `EvmBlockProcessor` gains a δ (`stateRootLag`) constructor parameter so it can compute maturity internally (today it executes whatever height it is handed; the relocation could alternatively live entirely in `setMain` — the plan will choose the seam that keeps `processMainBlock` height-agnostic and puts maturity arithmetic in `BlockchainImpl`, preferred for testability).

---

## 5. Decomposition (this design → tasks)

| Task | Scope | Consensus? |
|--|--|--|
| **G2-T1a** | **Relocate execution to depth δ via the maturity buffer.** `daSkip` stays `false`; missing blob still defers (BEHIND path may be stubbed as "defer" without the new verdict name). Reworks the G1-T2/T3 execution index; fixes the latent mainnet trap; makes EVM execute only δ-final heights. **First TDD plan.** | HF |
| **G2-T1b** | **Validator consumes `daSkip`.** Canonical skipped-delta checkpoint (§3.2) + the BEHIND verdict (§3.3). | HF |
| **G2-T1c** | **Miner sets `daSkip`.** Pack-time blob-availability decision for the matured height (§3.1). | HF |
| **G2-T2** | P2P retry / bounded fetch-window hardening (existing task; backoff/caps). | node-local |
| **G2-T3** | Bridge withdrawal release reconciliation under δ-lag + CRITICAL-skip downgrade (§2.5) + conservation re-verify + the `W ≥ δ−1` config invariant. | HF |

Dependency: **T1a → T1b → T1c** (execution must relocate before a skip bit means anything; the bit must be consumed before a miner should set it). T2 parallel; T3 after T1a..c.

---

## 6. Testing strategy (spec-level; the plans carry executable tests)

- **T1a index algebra** (pure/unit): `maturedHeight(N, δ) = N − δ + 1`; boundary heights `< δ` mature nothing; δ=1 ⇒ immediate (byte-identical to today).
- **T1a relocation** (integration, δ≥2): confirming blocks N..N+k executes heights `N−δ+1..`; a height is executed exactly when its confirming-block-plus-(δ−1) arrives; missing blob defers and drains in order; **byte-identical chained roots** vs a δ=1 immediate node once both reach the same executed height.
- **Latent-trap repair** (integration): a node that lacks a blob for a committed-include height stays *behind* and its native chain **advances** (no pre-mutation stall), then converges on blob arrival — contrasted with the pre-G2 hard-reject stall.
- **T1b skip** (integration): a committed-skip height folds `SKIP_SENTINEL`; a node-with-blob and a node-without both reach identical roots for that height; nonces untouched; a deposit at a skipped height still mints; reorg re-reads the committed skip deterministically.
- **T1b verify** (pure): MATCH/MISMATCH/BEHIND/ABSENT truth table.
- **Reorg** (integration): reorg depth < δ−1 touches no EVM state; ≥ δ−1 replays deterministically incl. skip pattern.
- **Conservation** (T3): deferred — re-verify deposit/withdrawal equality under δ-lagged mint/release.

---

## 7. Risks & mitigations

- **Blast radius on the `setMain` mount.** Mitigation: keep native mutation order untouched; only relocate the EVM hand-off; reuse the proven pending-queue drain; land as T1a in isolation (daSkip inert) so the relocation is verified before skip semantics.
- **Migration at activation.** The first δ−1 heights after activation mature nothing (pure buffer fill), mirroring ADR-014's genesis-lag edge; no special-case code beyond the `matured ≥ activationHeight` guard.
- **δ=1 devnet no-op.** Explicitly documented; G2 integration tests use δ≥2 configs; existing G1 devnet tests (δ=1) stay byte-identical.
- **RPC latest-executed height lags by δ.** Accepted (state itself doesn't lag, only its executed checkpoint); `eth_*` "latest" already tracks `highestExecutedHeight`, which simply trails the native head by δ−1.

---

## 8. Open items intentionally deferred

- Exact buffer storage (reuse EVM_META 0x03 pending vs. a sibling prefix) — a plan-level choice for T1a.
- `SKIP_SENTINEL` byte value and its position relative to deposit mint — pinned in T1b's plan (§3.2 fixes the ordering: sentinel folds before deposits).
- Bridge release timing, CRITICAL-skip downgrade, `W ≥ δ−1` enforcement — G2-T3.
