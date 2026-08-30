# K1: Async-Drain Fee-Credit Convergence — Design

**Status:** approved 2026-08-30 · consensus-critical (fork risk) · resolves the G3-T1 §3.6 pre-activation blocker
**Depends on / revises:** G3-T1 (net EVM fee → reward pool). Changes the credited block from the *confirming* block N to the *payload* block M.

## 1. Problem

Under δ-lagged execution + DA-defer, a matured EVM height M executes in one of two places:
- **synchronously** in `setMain(N)` when the blob is present at maturity (`N = M+δ−1`), or
- **asynchronously** in `EvmBlockProcessor.onBlobsAvailable()` when a temporarily-behind node's blob arrives late (called from `XdagP2pHandler` on the P2P thread).

G3-T1 credits the net fee only on the synchronous path (`onBlobsAvailable` discards the fee its `executeAndCheckpoint` returns). So a node that **deferred** M credits `block.info.amount` differently than a never-behind node. Because `PoolAwardManager`'s reward-distribution spend is validated against `block.info.amount` by every node, a caught-up miner's *correct* distribution would be rejected by a behind node as an over-spend — a **consensus fork**, not just lost revenue. This is the documented G3-T1 blocker (§3.6).

## 2. Decision

**Credit the payload block M (the matured height's own block), identically on both paths**, via a shared helper. The fee for executing height M is folded into block M's `info.amount` + `info.fee`, whether M executed sync or async. The async drain is routed through a new `BlockchainImpl.onEvmBlobsAvailable()` so native crediting stays in `BlockchainImpl` and respects the Blockchain→EvmProcessor lock order.

**Why payload M, not confirming N (chosen 2026-08-30):** crediting confirming block N is not reorg-safe — a shallow reorg that unwinds N while keeping M's execution reverses N's credit, and the new block at that height won't re-execute the already-executed M, so the credit is silently lost. Crediting M pairs the credit with the execution: both are keyed to M and always unwind (and re-credit) together. It also makes sync and async credit the *same* block deterministically. **At δ=1 (devnet, the only currently-activated config) M=N, so this is byte-identical**; it changes only δ≥2 attribution, which is unscheduled. The alternative (keep N + a per-height fee ledger for reorg re-credit) was rejected as more code and a larger consensus surface.

## 3. Mechanism

### 3.1 Shared credit helper

Extract the G3-T1 fee credit (with its A1 overflow guard) into one method on `BlockchainImpl`:

```
private void creditEvmFee(Block block, long height, BigInteger feeWei) {
    if (block == null || height < feeRewardActivationHeight || feeWei.signum() <= 0) return;
    BigInteger nano = feeWei.divide(WEI_PER_NANO);            // floor; dust burned
    if (nano.bitLength() > 62) { log CRITICAL "…skip…"; return; }   // A1 guard
    if (nano.signum() <= 0) return;
    XAmount fee = XAmount.of(nano.longValueExact());
    acceptAmount(block, fee);                                 // -> block.info.amount (PoolAwardManager distributes)
    block.getInfo().setFee(block.getInfo().getFee().add(fee)); // -> reversed by unSetMain's block.getFee()
    blockStore.saveBlockInfo(block.getInfo());
}
```

Gating on `height` (the executed/credited height M) — determinism unchanged (byte-identical at δ=1 where M==N).

### 3.2 Synchronous path (`setMain`)

`processConfirmedBlock(...)` returns the matured height M's net fee. In `setMain(N)` compute `M = maturedEvmHeight(N, lag) = N − lag + 1` and credit block M:

```
Block target = (M == mainNumber) ? block : getBlockByHeight(M);   // δ=1: the in-scope confirming block
creditEvmFee(target, M, evmFeeWei);
```

At δ=1, `M == mainNumber`, so `target` is the in-scope `block` (no stale-load) and the credit is byte-identical to G3-T1's current behavior. At δ≥2, M<N is a past block loaded canonically by height.

### 3.3 Asynchronous path (drain)

- `EvmBlockProcessor.onBlobsAvailable()` changes return type `void → List<DrainedHeight>` where `DrainedHeight(long height, BigInteger netFeeWei)` (a new public record). The pending queue is keyed by the **payload height M**, so each drained entry's `height` is M and its `netFeeWei` is `executeAndCheckpoint`'s return.
- New `BlockchainImpl.onEvmBlobsAvailable()` (on the `Blockchain` interface, `synchronized`): calls `evmProcessor.onBlobsAvailable()` and credits each drained height's fee to its own block — symmetric with the sync path, no lag arithmetic needed (the drained height *is* M):

```
public synchronized void onEvmBlobsAvailable() {
    if (evmProcessor == null) return;
    for (DrainedHeight d : evmProcessor.onBlobsAvailable()) {
        creditEvmFee(getBlockByHeight(d.height()), d.height(), d.netFeeWei());
    }
}
```

- `XdagP2pHandler`'s two call sites (`ingestEvmTxBlob`, `processEvmBatchReply`) change `evmProcessor.onBlobsAvailable()` → `chain.onEvmBlobsAvailable()`.

### 3.4 Why this converges and is reorg-safe

- **Convergence:** block M's amount = `reward(M) + nativeFee(M) + evmFee(M)`, all deterministic. A node that executes M sync and one that executes M async both fold the *same* `evmFee(M)` into block M — identical `block.info.amount` once both have executed M. The reward-distribution validation therefore agrees.
- **Executes/credits exactly once per canonical chain:** on any node M executes either sync (blob present) or async (deferred → drained), never both, so the credit is applied once.
- **Reorg symmetry:** the credit is folded into block M's `getFee()`, which `unSetMain`/`unWindMain` reverses. The drain runs under the Blockchain monitor (via the wrapper), so it never races `setMain`/`unWindMain`. A reorg that removes M sweeps its pending entry (`removeAbove`) and, on the new chain, re-executes+re-credits M once; a shallow reorg above M leaves M's execution and credit both intact.

## 4. Scope & touch points

- `EvmBlockProcessor`: `onBlobsAvailable()` → `List<DrainedHeight>` (collect each drained height + `executeAndCheckpoint` fee); add `public record DrainedHeight(long height, java.math.BigInteger netFeeWei)`.
- `BlockchainImpl`: extract `creditEvmFee(Block, long, BigInteger)`; `setMain` credits block M (`getBlockByHeight(M)` or the in-scope block at δ=1); new `onEvmBlobsAvailable()`.
- `Blockchain` interface: add `onEvmBlobsAvailable()`.
- `XdagP2pHandler`: two call sites route through `chain.onEvmBlobsAvailable()`.
- **G3-T1 revision:** the sync credit targets M not N. The δ=2 attribution test `under_lag_2_the_evm_fee_credits_the_block_that_executed_the_payload` flips to assert block **M=K** credited (block K+1 not). δ=1 tests unchanged (byte-identical).

**Non-goals:** unbounded pending-queue depth (a separate DA/liveness concern); the A4 supply-conservation question; making the async credit itself trigger `PoolAwardManager` (it rides `block.info.amount` as the sync path does).

## 5. Testing

1. **Async credit at δ=1 (the core proof):** ingest a carrier whose blob is withheld → `setMain(N)` defers M=N (credits 0); then store the blob + call `blockchain.onEvmBlobsAvailable()` → drains M and credits block M=N. Assert block N's `info.fee` == `floor(gasUsed×gasPrice/1e9)`. Fails today (async discards the fee).
2. **Sync/async convergence:** the same high-gasPrice tx yields the identical block-M fee whether the blob is present at maturity (sync) or withheld-then-drained (async).
3. **Reorg reverses the async credit:** after an async credit to block M, `unSetMain`/unwind reverts block M's `info.fee` (and amount) to its pre-credit value.
4. **δ=2 attribution (revise G3-T1 test):** at δ=2 the fee credits the payload block **K** (not the confirming K+1).
5. **Overflow guard still holds on both paths** (the A1 test remains green; the async path uses the same `creditEvmFee` guard).
6. **δ=1 byte-identical:** existing G3-T1 fee tests (`confirming_block_is_credited_the_floored_evm_fee`, dust, aggregation, conservation, below-activation) stay green — at δ=1 crediting block M is the same block, same amount.
