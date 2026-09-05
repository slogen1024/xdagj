# XDAG EVM — Mainnet Hard-Fork Decision Records (ADR-013…016)

- **Date:** 2026-08-22 (proposed) · **2026-08-24 adjudicated**
- **Branch:** `dev-evm`
- **Status:** **Accepted** — the four decision gates are closed. Each ADR is signed off for the single mainnet-enabling hard fork; engineering (G1-T*/G2-T*/G3-T*) may now expand into per-gate TDD plans.
- **Related:** `2026-06-06-xdag-evm-decision-record.md` (ADR-001…012), `.claude/docs/evm-mainnet-readiness-and-audit-scope.md` §3, `.claude/docs/evm-mainnet-hard-gates-tasklist.md`, `.claude/docs/evm-mainnet-launch-and-ecosystem-plan.md`, design doc §13.3 / D6 / D7 / D10

These four ADRs close the **four decision gates** blocking the three §13.3 mainnet hard gates. Each keeps its options comparison for the record and states the **accepted decision**. Numbering continues the canonical ADR series. All four target a **single coordinated hard fork** (one `activationHeight`), not four separate ones.

Mapping to the task list: ADR-013 = G1-D1, ADR-014 = G1-D2, ADR-015 = G2-D1, ADR-016 = G3-D1.

**Adjudication note (2026-08-24):** two gates were decided *against* the original 2026-08-22 proposal — **G2-D1 chose option B (in-block skip-marker)**, not the proposed import-time gate; **G3-D1 routes the net fee through the miner reward pool (`PoolAwardManager`)**, not a direct coinbase credit. G1-D2 keeps the proposed lagged-anchor + hard-reject but replaces the "reuse `bridgeWithdrawalDelay`" idea with a **dedicated `evm.stateRootLag`** parameter and adds a **testnet-warn → mainnet-reject** rollout. Each section below records the accepted decision and, where it diverges, why.

---

## ADR-013: Mainnet state commitment — what root goes into PoW (G1-D1)

| | |
|--|--|
| **Decision (accepted)** | Anchor the **existing chained delta root** into a native block field for the mainnet fork; defer an absolute MPT state root to a later scheduled fork |
| **Status** | **Accepted** — hard fork |
| **Supersedes / extends** | Resolves D6's "native field anchoring (后路)"; **revisits ADR-012** (MPT-per-block was proposed but never shipped — flat KV + chained delta root shipped instead, per D5/D6) |
| **Context** | Today the chained delta root (`EvmBlockProcessor.java:498-509`) is committed **only off-chain via gossip 0x1E — detection, no PoW commitment, no hard reject**. Mainnet requires the root to have consensus effect. The prior question is *what* to commit, since the 4-bit field-type space is exhausted (`XdagField.java:70-103`) and any anchoring needs a block-format change regardless. |

**Options**

| | A. Anchor chained delta root | B. Absolute MPT/SMT state root | C. A now, B later |
|--|--|--|--|
| EVM-state rewrite | none (reuse shipped root) | large (replace flat KV with trie; touches `RocksDbWorldUpdater`, `EvmStateSchema`, reorg replay) | none for launch |
| `eth_getProof` / light client | ✗ | ✓ | ✗ at launch, ✓ later |
| Per-block hashing cost | unchanged | higher (trie hashing) | unchanged at launch |
| Audit surface of the launch fork | small (root already audited) | large | small |
| Ethereum tooling parity | partial | full | grows over time |
| Consensus-safety of the goal (全网状态一致 enforcement) | **met** | met | **met** |

**Decision: option C — anchor the chained delta root for the launch fork, schedule MPT (option B) as a later fork.** The chained delta root is already a cryptographic commitment to the exact `(txHash, status, gasUsed, stateDelta)` sequence; anchoring it in PoW + hard-reject (ADR-014) delivers the *safety* goal of §13.3-1 (cross-node state agreement enforced by consensus) with **zero new EVM-state code** and minimal added audit surface. `eth_getProof`/light-client is a *capability* enhancement, not a mainnet-safety blocker — bundling a full trie rewrite into the launch fork inflates timeline and audit risk. This keeps ADR-012's MPT goal alive as scheduled future work rather than killing it.

**Consequences**
- The committed root is **history-dependent** (chained), not a pure function of current state. A node bootstrapping from snapshot must replay from, or trust, a checkpoint to reconstruct the chain — acceptable given XDAG's existing snapshot mechanism, but note it in the snapshot/sync path.
- No `eth_getProof` / light clients until the later MPT fork; document as a known limitation for the mainnet launch (extends §13.2).
- The block-format work (freeing a field code, task G1-T1) is required either way and is the project critical path. Because ADR-015 also chose an in-block marker (option B), G1-T1's block-format revision must reserve room for **both** the `EVM_STATE_ROOT` payload **and** the DA skip-marker — co-design them (see ADR-015 consequences).

---

## ADR-014: State-root commitment timing — lagged anchoring + hard reject (G1-D2)

| | |
|--|--|
| **Decision (accepted)** | Block H anchors `root(H−δ−1)` (**corrected 2026-09-05**, audit C3: originally `root(H−δ)`, which an honest miner cannot know at template time because H−1 is confirmed only when H connects; a **lagged**, already-final root) where δ = the new `evm.stateRootLag` parameter; a block whose anchored root mismatches the verifier's recomputed root is **hard-rejected on mainnet**, **warn-only on testnet** during a shakedown window |
| **Status** | **Accepted** — hard fork |
| **Depends on** | ADR-013 (defines *what* root) |
| **Context** | The load-bearing problem: EVM executes at `setMain`, i.e. **after** a main block is confirmed (ADR-004 / §12.1). So when block H is mined/packed, H's post-execution root **does not exist yet**. "Commit this block's own root" is impossible under XDAG's execution timing. |

**Options**

| | (a) Lagged anchoring `root(H−δ)` | (b) Pack-time pre-execution → `root(H)` |
|--|--|--|
| Breaks §12.1 / ADR-004 (execute only at `setMain`)? | **no** | **yes** (speculative execution) |
| Reorg behavior | root(H−δ) already on stable history if δ ≥ reorg depth | double-execution + rollback on non-canonical blocks (the exact case ADR-004 rejected) |
| Consensus-mount blast radius | small (read a historical root at pack time) | large (rewires the three consensus mounts) |
| Commitment freshness | lags by δ heights | current |
| Risk | low | high |

Plus a **divergence policy** sub-choice, independent of a/b: **hard-reject** the block on root mismatch (gives the root consensus effect) vs **soft-warn** (today's gossip).

**Decision: (a) lagged anchoring + hard-reject, phased by network.** Option (b) resurrects the speculative-execution / double-rollback problem ADR-004 explicitly rejected and would touch the most safety-critical code (the three consensus mounts) — not worth it to shave δ heights of freshness. Reuse the existing replay-root three-state compare (`EvmBlockProcessor.java:450`) as the verifier, feeding it the anchored root instead of a gossip sample.

**Divergence rollout (phased):**
- **testnet: warn-only first.** During the public-beta window the verifier logs an anchored-vs-recomputed mismatch as a loud telemetry event but does **not** reject the block — a shakedown period to surface any implementation divergence without splitting the test network.
- **mainnet: hard-reject from the activation height.** Only hard-reject gives the state root real consensus effect; without it we are back to gossip detection. The switch from warn to reject is itself governed by the activation height, not a config toggle a single node can flip.

**δ parameter — dedicated `evm.stateRootLag` (diverges from the 2026-08-22 proposal):** the original proposal reused `evm.bridgeWithdrawalDelay` as one unified finality constant. **Decision: a dedicated `evm.stateRootLag` config parameter instead**, so state-root finality depth and bridge-release finality depth are tuned independently:
- `devnet`: small (e.g. `2`) for fast test cycles.
- `testnet` / `mainnet`: suggested `16`, confirmed against measured reorg depth during the testnet window before locking.
- δ must be ≥ the deepest plausible reorg so `root(H−δ)` is effectively final when anchored (else a reorg retroactively invalidates an honestly-mined block). It is a consensus parameter: it must be validated (positive, bounded) and identical across a network, and changing it is a hard-fork-level action.

**Consequences**
- MetaMask/`eth_*` state-root-derived views lag by δ (the state itself does not lag — only its PoW-anchored proof).
- Genesis edge: heights < δ anchor the genesis origin root (no prior root); handle explicitly.
- unWindMain must keep anchored roots consistent under reorg — already symmetric via the replay path (D9), but add a test.
- `evm.stateRootLag` joins the config-validation surface (`AbstractConfig`); document it alongside the other `evm.*` fork/finality knobs.

---

## ADR-015: Data-availability enforcement — in-block skip-marker (G2-D1)

| | |
|--|--|
| **Decision (accepted)** | The miner commits, **in the block**, whether the height's EVM payload is **included** or **skipped**. `include` (a 0x0F batch commitment) is only valid if the miner has made the blob available — verifiers that cannot obtain it treat the block as invalid; `skip` is honoured deterministically network-wide with no blob required. Replaces unbounded stall-and-defer as the *enforcement* mechanism |
| **Status** | **Accepted** — hard fork (consensus rule: EVM inclusion/skip is block-committed) |
| **Supersedes / extends** | Bounds D10 (stall-and-defer); precondition for downgrading the bridge-withdrawal CRITICAL-skip (§2.5) |
| **Context** | Today a confirmed block with a missing blob makes EVM **stall indefinitely** (D10 / `EvmBlockProcessor.java:194-249`) — honest but an unbounded liveness risk, and the reason bridge withdrawals fall back to CRITICAL-skip. |

**Options**

| | A. Import-time availability gate | B. Consensus skip-marker (in-block) |
|--|--|--|
| Depends on ADR-013 block-format work? | no (no new field) | **yes** (needs an in-block marker) — but G1-T1 is already on the critical path |
| Missing-payload semantics | block is "not yet selectable" = *behind* | height is **skipped** (committed) = those EVM txs never execute |
| Global-agreement hazard | low (each node gates on what it locally has) | **low once the skip is block-committed** — the block, not a per-node timeout, decides |
| Liveness of native chain | **coupled** to EVM DA when `evm.enabled` (backbone may stall) | **native chain advances**; EVM holes are explicit and consensus-agreed |
| Withholding-miner attack | deprioritizes withholders (their block isn't selected) | `include` obliges the miner to publish the blob or produce an invalid block; else it commits `skip` |
| Composes with ADR-014 hard-reject? | **tension** — an honest node lacking the blob would hard-reject a valid chain within δ | **clean** — the anchored `root(H−δ)` reflects exactly what the block committed (executed or skipped) |

**Decision: option B — in-block skip-marker.** This **overrides the 2026-08-22 proposal (option A)**. The two G1 decisions changed the trade-off: G1-D1 already pays for a block-format hard fork (so B's "needs a marker" cost is marginal), and G1-D2 hard-reject makes A's import-gate hazardous — an honest node that is merely *behind* on a blob would, under A + hard-reject, be unable to recompute `root(H−δ)` and would reject an otherwise valid chain. B avoids that by making DA a **miner-enforced, block-committed** property: the block itself carries the include/skip decision, the δ-lagged anchored root enforces it, and native-chain backbone liveness is never coupled to EVM data availability.

**Mitigations of the two concerns the original proposal raised against B:**
1. *"Global timeout agreement is hard (clock/propagation variance)."* — Resolved: the skip is **not** derived from each node's wall-clock timeout. It is a value the **miner commits in the block**. Every verifier reads the same committed bit; there is no per-node timeout to disagree on. The miner's own bounded fetch/produce window (≈ δ) is where the include-vs-skip choice is made, but the *consensus* artifact is the committed marker, not a timeout.
2. *"Skip = data loss."* — Accepted as an explicit, bounded trade to preserve backbone liveness: the skipped height's EVM txs deterministically do not execute (senders resubmit; nonces are untouched because nothing executed). This is strictly better than today's unbounded stall, and it is network-uniform (everyone skips the same height) because it is block-committed and folded into the anchored root.

**Consequences / open questions**
- **Marker encoding — co-design with G1-T1.** The skip-marker should minimize codepoint pressure: prefer a **sentinel/flag** (e.g. a reserved value of the batch-commitment field, or a bit alongside the `EVM_STATE_ROOT` field from ADR-013) over a whole new 4-bit type code, since the type space is already exhausted (`XdagField.java`). G1-T1 must reserve room for **both** the state-root payload and this marker in one block-format revision.
- The rule applies **only** when `evm.enabled` and past `activationHeight`.
- `include` obligation: a miner referencing a blob it never publishes produces a block verifiers reject (they cannot reconstruct `root(H−δ)`), so withholding is self-defeating; the honest choice under DA pressure is to commit `skip`. Confirm the miner's bounded fetch/produce window and document it.
- After this lands, revisit bridge withdrawal (task G2-T3): the CRITICAL-skip degradation (§2.5) becomes a **normal, bounded, deterministic** path — DA no longer lags without bound.

---

## ADR-016: Fee routing — net fee to the miner reward pool (G3-D1)

| | |
|--|--|
| **Decision (accepted)** | Route the net EVM fee (`gasUsed × effectiveGasPrice`) into the **miner reward pool via `PoolAwardManager`**, instead of burning it. Convert `wei → nano` rounding down; burn the sub-nano dust |
| **Status** | **Accepted** — hard fork (consensus: supply / reward) |
| **Supersedes / extends** | Closes the deferred routing item of **ADR-007** (fee model); the code marks this as "P2" at `EvmBlockProcessor.java:752-755` |
| **Context** | Net fee is currently **burned** (deflationary, but miners earn nothing for the `setMain` execution work they perform on EVM txs → zero incentive to include EVM txs). With `baseFee ≡ 0` (defect-1 step-2 not built), the entire net fee is effectively a tip. |

**Options**

| | A. Burn (status quo) | B. Direct coinbase credit | C. Reward pool (`PoolAwardManager`) |
|--|--|--|--|
| Miner incentive to include EVM txs | none | aligned | **aligned** |
| Fits XDAG pool-mining reward model | n/a | bypasses pool distribution | **native** (fees flow through the same distribution) |
| Requires base-fee market? | no | no | no |
| wei→nano conversion needed | no | yes (10⁹ factor, floor) | yes (10⁹ factor, floor) |
| Accounting boundary | isolated (§2.6) | isolated | **entangled** with the reward state machine |
| Reorg accounting | none | reverse credit in `unWindMain` | reverse the pool credit in `unWindMain` (harder) |

**Decision: option C — route the net fee through `PoolAwardManager`.** This **overrides the 2026-08-22 proposal (option B, direct coinbase credit).** Rationale: XDAG is a pool-mining network; routing EVM fees through the same reward-distribution path the native block reward already uses is the model-consistent choice, so EVM fees are shared by the pool's reward policy rather than landing on a single coinbase address. C's cost is a tighter coupling between EVM fee accounting and the reward state machine — accepted, and flagged below as the primary audit concern. Option A is rejected (no miner incentive); the EIP-1559 burn/tip split is deferred because it depends on the unbuilt base-fee market (defect-1 step-2 / §13.1) — a `baseFee > 0` burn hook can be added at that later step without re-opening this ADR.

**Conversion & dust:** the net fee is computed in wei; credit `floor(netFeeWei / 10⁹)` nano to the reward pool and **burn** the sub-nano remainder (< 1 nano per tx) — deterministic, network-consistent, negligible, and avoids maintaining a carry ledger.

**Consequences — primary design risk (audit focus):**
- **Boundary entanglement (extends §2.6).** EVM fee settlement now touches `PoolAwardManager`, previously isolated. The auditor must confirm EVM fee credit and the native block reward do **not** double-count, and that the fee enters the pool at a well-defined accounting time point consistent with EVM's `setMain` execution vs the pool's distribution cadence.
- **Reorg symmetry.** `unWindMain` must reverse the pool credit exactly and symmetrically (same discipline as bridge release, §2.5) — this is harder than reversing a plain coinbase balance because the credit passes through the reward state machine; add explicit reorg tests that assert the pool ledger returns byte-identical after a reverted height.
- **Supply conservation.** The invariant changes from "net burn" to "burn dust + credit reward pool"; update the conservation statement and its test to include the pool credit.
- Touches `EvmBlockProcessor.executeOne` (`:749-771`), the `PoolAwardManager` boundary, and reward/`AddressStore` accounting.
- **Sibling G3 items (tracked separately, not this ADR):** per-sender mempool Sybil quota (G3-T3) is **node-local, not consensus** — no ADR needed, ship anytime. EIP-3529 precise refund (G3-T2) — **✅ SHIPPED 2026-08-22** (branch `feature/eip3529-refund`, plan `docs/superpowers/plans/2026-08-22-eip3529-precise-refund.md`): consensus but spec-following (no design choice), gated behind its own `evm.eip3529ActivationHeight` (devnet=0, testnet/mainnet unscheduled).

---

## Cross-ADR summary & sequencing

| ADR | Gate | Accepted decision | Block-format dependency |
|-----|------|-------------------|-------------------------|
| 013 | State root: what | Chained delta root now, MPT later | **keystone** — G1-T1 block-format revision |
| 014 | State root: when | Lagged `root(H−δ)`, δ = `evm.stateRootLag` (16), mainnet hard-reject / testnet warn-first | needs 013 |
| 015 | DA enforcement | **In-block skip-marker (option B)** | **needs 013's G1-T1** (shares the block-format revision; marker as a sentinel/flag) |
| 016 | Fee routing | **Net fee → `PoolAwardManager` reward pool** | **parallel** (no block-format) |

**Bundle all four behind one `activationHeight`.** 013 → {014, 015} is the long pole: G1-T1's block-format revision must carve out room for **both** the `EVM_STATE_ROOT` field (013/014) **and** the DA skip-marker (015) in a single revision, so 015 no longer parallelizes away from the block-format work (it did under the rejected option A). 016 (reward-pool routing) has no block-format dependency and can be built in parallel and merged into the same fork.

**New consensus parameter introduced:** `evm.stateRootLag` (ADR-014) — validated, network-uniform, devnet small / testnet-mainnet 16.

**Next step after sign-off:** for each accepted ADR, run `superpowers:writing-plans` to expand its tasks (G1-T*/G2-T*/G3-T*) into a bite-sized TDD plan. Recommended order: G1-T1 block-format revision first (unblocks 013/014/015), 016 in parallel. G3-T3 needs no ADR and can start immediately (G3-T2 shipped 2026-08-22).
