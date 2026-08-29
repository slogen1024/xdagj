# G3-T1: Net EVM Fee → Miner Reward Pool (ADR-016) — Design

**Status:** approved 2026-08-29 · hard fork (consensus: supply / reward) · no block-format dependency (parallel to Gate 1/2)
**Supersedes:** the "P2 / burned, no coinbase" deferral at `EvmBlockProcessor.java:912` (ADR-007 fee model)
**Decision source:** ADR-016 (`docs/superpowers/specs/2026-08-22-xdag-evm-mainnet-hardfork-adr.md`)

## 1. Problem

The net EVM fee (`netGasUsed × effectiveGasPrice`, in wei) is currently **burned** — the sender is debited `gasLimit × effectiveGasPrice` upfront and refunded the unused gas (`EvmBlockProcessor.executeOne`, `:934–984`), and the net remainder is simply never credited to anyone. Miners therefore earn nothing for the `setMain` execution work they perform on EVM txs, so there is no incentive to include them. ADR-016 routes that net fee into the **miner reward pool** instead of burning it.

## 2. Decision (accepted, ADR-016 + 2026-08-29 brainstorm)

Route the net EVM fee into the reward pool by **folding it into the confirming main block's native amount**, the amount `PoolAwardManager` already distributes. Convert `wei → nano` by flooring; burn the sub-nano dust. Consensus-gated behind a new `evm.feeRewardActivationHeight`.

Two mechanism choices were settled during brainstorm:

- **Routing = fold into `block.info.amount`** (not a new `PoolAwardManager` API). `PoolAwardManager.payPools` distributes `block.getInfo().getAmount()` (`PoolAwardManagerImpl.java:201`) split fund/node/pool. Folding the EVM fee into that amount routes it through the *existing* distribution with **zero `PoolAwardManager` change**, and subjects it to the same reward policy ("shared by the pool's reward policy" — ADR-016). This deliberately avoids a parallel reward ledger, the entanglement the ADR flagged as the primary risk.
- **Storage = the block's existing fee field.** `unSetMain` already reverses `block.getFee()` (`BlockchainImpl.java:1567`). Folding the EVM-fee-nano into `block.getInfo().setFee(...)` makes reorg reversal free and provably symmetric via the *same* path native fees already use — no `BlockInfo` serialization change.

## 3. Mechanism

### 3.1 Where the fee is settled and surfaced

The surfaced net fee is defined as **the wei actually removed from the sender** = the upfront gas debit (`gasLimit × effectiveGasPrice`) minus the refund actually applied. This is the conservation-exact quantity (what the sender lost == what is routed + dust) and is correct on every `executeOne` path:
- normal success/revert/OOG: `upfront − (gasLimit − netGasUsed) × effectiveGasPrice = netGasUsed × effectiveGasPrice`;
- pre-debit `validationFailure` (`:897`, `:903`, `:915`, …) — returns a receipt **before the debit at `:935`**: net = **0** (nothing was charged);
- defensive `catch` (`:986`, executor threw after the debit, before the refund ran): net = the full `gasLimit × effectiveGasPrice` (no refund applied).

It must be **surfaced from `executeOne`**, the only place that knows the actual debit and refund — it cannot be reconstructed downstream from `receipt.gasUsed()` alone (that would over-count validation failures and under-count the catch path).

`executeOne` returns the settled net fee (wei) alongside its receipt (a small `ExecutedTx(receipt, netFeeWei)` value, or an equivalent accumulator). The fee is a **native-side side-channel only** — it is NOT folded into the receipt and NOT part of the chained state root, so the EVM world state and root are byte-identical whether or not fee routing is active.

### 3.2 Aggregation and conversion

`executeList` sums `netFeeWei` across the matured height's txs (a skipped or empty or deposit-only height contributes 0). The per-height sum bubbles up through `executeAndCheckpoint` → `processMainBlock` / `skipMaturedHeight` → `processConfirmedBlock`, which returns the matured height's total net fee wei to `setMain`.

Conversion is done **once per block** (minimising dust): `evmFeeNano = floor(sumWei / 10⁹)`, dust `= sumWei mod 10⁹` is burned. `10⁹` is `BridgeConstants.WEI_PER_NANO`.

### 3.3 Crediting (in `setMain`)

The fee is credited to the **confirming block N** (`mainNumber`), whose `setMain(N)` performed the execution — not the matured payload's block `N−δ+1`. This is the block whose native reward amount is being set anyway, and it makes reorg symmetric (see §3.4).

In `BlockchainImpl.setMain`, after `processConfirmedBlock(...)` returns the matured height's `sumWei` (currently `:1424`):

```
if (evmProcessor != null && mainNumber >= evmSpec.getEvmFeeRewardActivationHeight()) {
    XAmount evmFeeNano = XAmount.of(sumWei / WEI_PER_NANO);   // floor; dust burned
    if (evmFeeNano.greaterThan(XAmount.ZERO)) {
        acceptAmount(block, evmFeeNano);                                  // -> block.info.amount (+ xdagStats if BI_OURS)
        block.getInfo().setFee(block.getInfo().getFee().add(evmFeeNano)); // -> reversed by unSetMain
        blockStore.saveBlockInfo(block.getInfo());
    }
}
```

`acceptAmount` (`BlockchainImpl.java` ~`:1470`) adds to `block.info.amount` and, for `BI_OURS` blocks, to `xdagStats.balance`. `PoolAwardManager` then distributes the enlarged amount 16 award-epochs later with no code change.

The gate is on `mainNumber` (the confirming/crediting height), matching the height whose miner did the work. It is a consensus parameter — network-uniform, not a per-node toggle.

### 3.4 Reorg symmetry

`unSetMain(N)` reverses `block.getFee()` via `acceptAmount(block, ZERO.subtract(block.getFee()))` (`:1567`). Because the EVM-fee-nano is folded into `block.getFee()`, the credit is reversed together with the native fee — byte-identical ledger after a reverted height. The paired EVM state rollback (`rollbackTo`) wipes the matured payload's execution; on a reorg *back*, forward `setMain` re-executes and re-credits deterministically. The fee-credit lifecycle lives entirely in `setMain`/`unSetMain` (native), never in the EVM replay path.

### 3.5 Activation gating (HF)

New `EvmSpec.getEvmFeeRewardActivationHeight()` + `AbstractConfig` field/getter + three confs, following the exact pattern of `type2ActivationHeight` / `eip3529ActivationHeight` / `stateRootActivationHeight`:
- **devnet = 0** (feature on for dev/testing)
- **testnet = MAX_VALUE**, **mainnet = MAX_VALUE** (unscheduled until the launch fork)

Below activation, `sumWei` is still computed but **not credited** — the fee is burned exactly as today, so native accounting is byte-identical to a non-upgraded node. Computing the sum is a read-only side-channel and never touches consensus state.

### 3.6 Async-drain (deferred-blob) crediting — out of scope for v1, **mainnet-activation blocker**

Under δ-lag + DA-defer, a matured height's EVM txs can execute in two places: **synchronously** in `setMain(N)` (blob present at maturity — the normal path this design credits), or **asynchronously** in `EvmBlockProcessor.onBlobsAvailable()` when a temporarily-behind node's blob arrives late (called from `XdagP2pHandler` on the P2P thread, with no native-accounting access). A node that *deferred* height M would execute it async and thus never credit block N in `setMain(N)`, so its `block N` amount would diverge from a never-behind node's — and since `block.info.amount` gates the reward-distribution spend that all nodes validate, that is a consensus divergence.

**v1 scope decision (2026-08-30):** implement **sync-path crediting only**. This is fully correct for everything currently activated: devnet is δ=1 single-node (matured height == confirming block; no defer path), and testnet/mainnet are unscheduled (MAX_VALUE). The divergence is only reachable on a multi-node δ≥2 network with real blob lag — i.e. exactly at mainnet activation.

**This is therefore a hard, explicit blocker for scheduling `feeRewardActivationHeight` on testnet/mainnet.** The pre-mainnet fix (a follow-up task): route `onBlobsAvailable` through a `BlockchainImpl.onEvmBlobsAvailable()` wrapper (the P2P call sites already hold a `Blockchain` reference), persist each executed height's net fee in EVM_META, and have **both** the sync and async paths credit block `M+δ−1` by height lookup so a behind node converges. Recorded in §7 and the hard-gates tasklist.

## 4. Conservation

The supply invariant changes from **"net fee burned"** to **"dust burned + `floor(netFee/10⁹)` nano credited to block N"**. Concretely, per activated block: the EVM-side supply decreases by `sumWei` (sender debits, uncredited on the EVM side), and the native-side supply increases by `floor(sumWei/10⁹)` nano (block N's amount), with `sumWei mod 10⁹` net-destroyed as dust. This is a deterministic wei→nano cross-domain transfer; it does not touch the bridge lock address, so bridge lock↔EVM conservation (§2.5) is unaffected.

## 5. Scope & touch points

- `EvmBlockProcessor.executeOne` (`:749–989`) — surface settled `netFeeWei` (0 for pre-debit validation failures).
- `EvmBlockProcessor.executeList` / `executeAndCheckpoint` / `processMainBlock` / `skipMaturedHeight` / `processConfirmedBlock` — thread the per-height net-fee sum up to the caller (skip/empty/deposit-only → 0).
- `BlockchainImpl.setMain` (~`:1424`) — convert + credit the matured height's fee to block N under the activation gate.
- `EvmSpec` + `AbstractConfig` + `xdag-{devnet,testnet,mainnet}.conf` — `evm.feeRewardActivationHeight` (0 / MAX / MAX).
- `PoolAwardManager` — **no change** (distributes the enlarged `block.info.amount`).

## 6. Testing

1. **Fee surfaced correctly (unit, `EvmBlockProcessorTest`):** a successful tx surfaces `netGasUsed × effectiveGasPrice`; a validation-failed tx surfaces 0 (no debit); a reverted/OOG tx surfaces `grossGasUsed × effectiveGasPrice`.
2. **Credit at/above activation (integration):** a main block confirming a gas-paying EVM tx has `info.amount` increased by exactly `floor(sumWei/10⁹)` nano and `info.fee` increased by the same; below activation the amount/fee are unchanged (byte-identical burn).
3. **Dust burned:** a fee with a sub-nano remainder credits `floor` and the dust is not credited anywhere (amount delta == floor, not ceil).
4. **wei→nano aggregation:** two txs in one matured height sum in wei, then floor once (credit == `floor((f1+f2)/10⁹)`, not `floor(f1/10⁹)+floor(f2/10⁹)`).
5. **Reorg symmetry (integration):** confirm a fee-crediting block, then `unSetMain` it → block `info.amount` and `info.fee` return byte-identical to pre-credit; re-`setMain` re-credits identically.
6. **No double-count / policy split:** the credited fee flows through `PoolAwardManager`'s fund/node/pool split exactly like the block reward (assert the distributable amount includes the fee once, not twice).
7. **δ-lag interaction:** at δ=2, the fee from height K's payload (executed at `setMain(K+1)`) is credited to block K+1, and reorg of K+1 reverses it (aligns with G2-T3's δ-lag maturation).
8. **EVM root unchanged:** the chained root at a height is identical with fee routing on vs off (the fee is native-side only).

## 7. Non-goals (out of scope)

- EIP-1559 base-fee burn/tip split — deferred to the type-2 base-fee-market step (§13.1); a `baseFee > 0` burn hook can be added there without re-opening this design.
- Per-sender mempool Sybil quota (G3-T3) — node-local, separate task.
- Any `PoolAwardManager` distribution-policy change — the fee simply rides the existing policy.
- **Async-drain (deferred-blob) fee-crediting (§3.6) — deferred; a documented hard blocker for testnet/mainnet activation, with a follow-up task.** v1 credits only the synchronous `setMain` path (correct for devnet δ=1 and the δ≥2 sync case).
