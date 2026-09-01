# A4: Fee-Routing Supply Conservation — Design (fail-safe now, model deferred)

**Status:** approved 2026-08-31 · consensus-relevant (supply) · fail-safe shipped 2026-08-31; **full transfer-from-lock model IMPLEMENTED 2026-09-01** (plan `docs/superpowers/plans/2026-09-01-a4-full-transfer-from-lock.md`)
**Relates to:** G3-T1 (net fee → reward pool), ADR-016, the 2026-08-30 audit (finding A4).

## 1. Problem

`getSupply(nmain)` is a pure formula — the mining-reward schedule plus the Apollo-fork adjustment (`BlockchainImpl.java`). It sums neither EVM balances nor the block-`info.amount` deltas that `acceptAmount` applies. Three things sit outside it:

- **Genesis alloc** (`EvmBlockProcessor.seedGenesisIfAbsent`) mints EVM wei with **no native counterpart** — unbacked, and invisible to `getSupply`.
- **A bridge deposit** moves the depositor's native to the lock address (`applyBlock` → `addAmount(LOCK, credited)`) and mints EVM 1:1 — the locked native *backs* the wei; `getSupply` is unchanged (a transfer).
- **G3-T1/K1 fee routing** (`creditEvmFee`) `acceptAmount`s fresh native to the miner, derived from the spent EVM wei — a cross-domain **mint** that `getSupply` does not track.

The mint over-issues even for *deposit-backed* wei: when a user spends deposit-backed wei as gas, the wei's backing native stays locked **and** new native is minted to the miner — the fee is effectively created twice, and `getSupply` under-reports the true native in circulation. For *unbacked* genesis-alloc wei it is pure inflation.

**Reachability today:** none in production — every EVM fork is `Long.MAX_VALUE` and `evm.enabled = false` on shared nets. It is live only on **devnet** (`feeRewardActivationHeight = 0`, `evm.alloc` funded, bridge scheduled), which is throwaway. A4 is a **pre-activation blocker**: it must be resolved before scheduling fee-routing on any non-throwaway network.

## 2. Decision

**Ship a config fail-safe now; document the target supply model; defer the full consensus change** (chosen 2026-08-31). The full model is large and consensus-critical and only matters at activation, which is itself gated behind the external audit track — so the immediate deliverable is a fail-fast that prevents the clearly-unsafe misconfig plus this spec, which fixes the target model so a future implementer can build it deterministically.

**Target model (the eventual fix): transfer-from-lock.** EVM wei is a *claim* on native XDAG held in the deposit lock. The net EVM fee is native the payer already owns (their locked deposit) moving to the miner — a **transfer from the lock**, not a mint:
- `creditEvmFee` **debits the lock address** by the credited nano and credits the miner — total native unchanged, `getSupply` stays exact, and the bridge invariant `lock == redeemable-EVM` is preserved (both drop by the fee).
- **Genesis alloc becomes a "genesis deposit":** `seedGenesisIfAbsent` also credits the lock with the alloc's native equivalent, so every EVM wei (deposit or alloc) is lock-backed and the fee always has backing to transfer.
- **`getSupply` adds the one-time genesis-alloc total** (the premined native seeded into the lock), keeping it an accurate closed-form.

Rejected alternative — *mint-and-unify* (keep the mint; redefine total supply as native+EVM; track an EVM-supply running counter): diverges from the bridged-claim model natural to a native coin and adds a reorg-safe EVM-supply counter. Transfer-from-lock is the chosen target.

## 3. Config fail-safe (ship now)

Added at config load (`AbstractConfig`), running **before** `validateBridgeConfig`'s `!scheduled` early-return so it fires even when the bridge is unscheduled:

- **Fatal:** if `evm.feeRewardActivationHeight != Long.MAX_VALUE` (fee-routing scheduled) then `evm.bridgeActivationHeight != Long.MAX_VALUE` (bridge scheduled) — else throw. The transfer-from-lock model sources the fee from the deposit lock, which exists only when the bridge is scheduled; fee-routing without the lock has nothing to transfer from. Message names both keys and the reason.
- **Warn (startup, non-fatal):** if fee-routing is scheduled **and** `evm.alloc` is non-empty, log a WARN that the alloc wei is currently **unbacked**, so the fee mint inflates native until the transfer-from-lock model (this spec, deferred) lands — acceptable only on a throwaway network.

**Satisfiable by every current config:** devnet (`feeReward=0`, `bridge=0`) passes the fatal check and trips only the (accepted) warn; testnet/mainnet (`feeReward=MAX`) make both vacuous. No current test or conf breaks.

## 4. Scope

- **Now (this task):** the fatal + warn checks in `AbstractConfig` (place the fatal in `validateBridgeConfig` before its early-return, or a sibling `validateFeeRewardConfig` invoked from the same load point); a unit test in `EvmConfigSectionTest` (fee-scheduled + bridge-unscheduled throws; fee-scheduled + bridge-scheduled + alloc passes; the shared-net/devnet confs load). This spec + the pre-activation checklist updated to mark A4's fail-safe done and the model chosen.
- **Deferred (activation-prep, before scheduling fee-routing on a real net):**
  1. `creditEvmFee` debits the lock address by the credited nano (transfer, not mint); reorg reversal of the debit paired with the existing fee reversal. — **DONE 2026-09-01**
  2. `seedGenesisIfAbsent` seeds the lock with the alloc's native equivalent (genesis deposit); reorg-safe re-seed on `rollbackTo` (mirror the existing genesis-marker discipline). — **DONE 2026-09-01**
  3. `getSupply` adds the genesis-alloc total. — **DONE 2026-09-01**
  4. Conservation tests: total native constant across deposit / gas-fee / withdrawal / genesis-alloc; `getSupply` == actual native; bridge `lock == redeemable-EVM` under fee-routing. — **DONE 2026-09-01**
  5. Re-scope the config guard: once alloc is lock-backed, downgrade the alloc warn (backed alloc is fine). — **DONE 2026-09-01**

## 5. Non-goals

- The full transfer-from-lock / alloc-seeds-lock / getSupply implementation (deferred, §4).
- Devnet supply accuracy (throwaway; its unbacked-alloc inflation is accepted and only warned).
- The mint-and-unify alternative model (rejected).
