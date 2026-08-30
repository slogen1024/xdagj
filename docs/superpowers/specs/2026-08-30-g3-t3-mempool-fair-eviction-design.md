# G3-T3: Mempool Per-Sender Fair Eviction — Design

**Status:** approved 2026-08-30 · node-local (NOT consensus, NOT a hard fork) · no ADR required
**Task:** §13.3 G3-T3 — "EvmTxPool per-sender quota + fair eviction; block cheap pool-filling." The last §13.3 hard-gate item.

## 1. Problem

`EvmTxPool` (`src/main/java/io/xdag/evm/tx/EvmTxPool.java`) already bounds memory with a global cap (`MAX_POOL_SIZE = 4096`), a per-sender nonce-window count cap (`MAX_PER_SENDER = 16`), an affordability check, replace-by-fee, and a 1-hour TTL. The remaining Sybil gap is the **full-pool behavior**: `add()` returns `POOL_FULL` as a hard first-come-first-served wall (`:190-195`). A funded attacker spreading transactions across many cheap keys can fill all 4096 slots and **starve honest new senders** until entries expire (TTL) or are mined.

**Honest scope.** No mempool policy can fully stop a same-price flood across many distinct *funded* keys — that is a fee-market / network-rate-limit problem, not a pool problem. What fair eviction delivers is: (a) a fresh or higher-paying sender can never be **locked out** of a full pool, and (b) no single or small set of senders can **dominate** it. Absolute Sybil immunity is explicitly out of scope.

## 2. Decision

Replace the hard `POOL_FULL` reject with **priority eviction**: when the pool is full and the incoming tx is not a same-slot replacement, evict the pool's least-deserving evictable entry to admit a strictly-more-deserving newcomer, ordered by price first and per-sender fairness second. Chosen 2026-08-30: price + per-sender-count fairness (over price-only); **no** per-sender byte quota (the count cap + per-tx gas-limit-bounded size + global cap already bound memory — YAGNI).

## 3. Mechanism

All changes are inside `EvmTxPool.add()` at the current `else if (byHash.size() >= MAX_POOL_SIZE)` branch (`:190`), plus one private helper. Everything before that branch (decode, chain-id, signature, gas-limit, intrinsic-gas, underpriced, nonce-window, cumulative-affordability, duplicate, same-slot replace-by-fee) is unchanged.

### 3.1 Victim candidates — tails of other senders only

The eviction candidates are the **tail entry (highest nonce) of every sender other than the incoming tx's sender**:
- **Tails only** — evicting a middle nonce would leave a gap in that sender's contiguous chain `[accountNonce, tail]`, orphaning the higher nonces (unminable). Evicting only the highest-nonce tail keeps every chain contiguous.
- **Other senders only** — the incoming sender is excluded so a sender extending its own chain (adding a higher nonce when full) never evicts its own lower-nonce tail (which would orphan the very tx being added or create a gap).

`bySender` is `Map<Address, NavigableMap<Long, PoolEntry>>`, so a sender's tail is `queue.lastEntry().getValue()` and its load is `queue.size()`.

### 3.2 Victim = the worst candidate

Order candidate tails by a total order and pick the worst (the one most deserving of eviction):
1. **effective gas price ASC** — the lowest-paying tail is evicted first (fee priority).
2. **sender entry-count DESC** — among equal prices, a tail belonging to the most-loaded sender is evicted first (per-sender fairness: the biggest occupier yields first).
3. **age ASC (oldest `addedAtSeconds` first)** — a deterministic final tiebreak.

If there are no other-sender candidates (unreachable at a full pool, but defensively), return `POOL_FULL`.

### 3.3 Admission rule

Admit the newcomer iff it is **strictly better** than the victim under the price+fairness order:
- `newcomer.effectiveGasPrice > victim.effectiveGasPrice`, **OR**
- `newcomer.effectiveGasPrice == victim.effectiveGasPrice` **AND** `incomingSenderLoad < victimSenderLoad`,

where `incomingSenderLoad` is the incoming sender's current entry count (0 for a brand-new sender; its current queue size for a sender extending its chain). Age is deliberately **not** part of the newcomer-vs-victim test (only of victim *selection*), so a newcomer never wins purely by being newer — this prevents thrash on a balanced pool.

On admit: remove the victim from `byHash` and from its sender's queue (removing the sender from `bySender` if its queue becomes empty), then insert the newcomer exactly as the normal path does (`byHash.put`, `queue.put`, `txStore.put`). Return `ADDED`.

Otherwise (newcomer not strictly better than any evictable victim — e.g. its sender is already the most-loaded at the same price): return `POOL_FULL`.

### 3.4 Why it meets the goal

- **No lock-out of fresh senders.** A brand-new sender (load 0) is strictly less-loaded than any sender with ≥1 entry, so at equal price it always displaces a flood tail → it always gets a slot. It cannot be starved.
- **Fee competition.** A higher-fee tx displaces any lower-fee tail regardless of load.
- **Per-sender fairness.** At equal price, the most-loaded sender's tail is evicted first, so capacity spreads across senders rather than concentrating.
- **Cap preserved.** Exactly one victim is evicted per admitted newcomer, so `byHash.size()` never exceeds `MAX_POOL_SIZE`.
- **Cheap flood no longer locks out.** A same-price flood still churns the pool, but every eviction it forces is one a fresh/higher-paying honest sender could equally force — the flood cannot hold honest senders out.

## 4. Scope & non-goals

**Touch points:** `EvmTxPool.add()` (the `:190` full-pool branch) + a private `selectEvictionVictim(Address incomingSender)` helper (returns the worst other-sender tail, or empty). No change to `selectTransactions`, `selectBatch`, the nonce window, replace-by-fee, TTL/`evictExpired`, `cost()`, or the AddResult enum. `MAX_POOL_SIZE`/`MAX_PER_SENDER` stay `public static final` constants (no new config).

**Non-goals:** per-sender byte quota (YAGNI); making the caps configurable (YAGNI); any change to mining selection order; absolute Sybil immunity against a same-price funded flood (fundamentally a fee-market/network concern).

## 5. Test impact & plan

`EvmTxPoolTest.pool_rejects_when_full_but_accepts_after_expiry_frees_a_slot` (`:217`) fills the pool with distinct 1-entry senders all at price `2e9`, then asserts a fresh same-price sender gets `POOL_FULL`. That asserts the **old** hard-wall behavior. Under fair eviction the fresh sender (load 0) displaces a 1-entry flood tail → **ADDED**. Update that test to the new semantics (fresh sender admitted via eviction; pool size stays `MAX_POOL_SIZE`; one prior entry evicted).

New tests:
1. **Higher fee displaces lower fee:** fill at price P; a newcomer at price > P is ADDED and a P-priced tail is evicted (pool stays full).
2. **Equal-price fresh sender displaces a loaded sender:** fill so some sender holds multiple entries and others hold one, all at price P; a fresh sender at P evicts the most-loaded sender's tail (fairness), not a 1-entry sender.
3. **Genuine POOL_FULL:** when the newcomer's sender is already the most-loaded and it does not outbid, `add()` returns `POOL_FULL` (no eviction, no thrash).
4. **Contiguity:** a multi-nonce sender whose tail is evicted keeps `[accountNonce, tail-1]` contiguous and still selectable; no middle nonce is ever evicted.
5. **No self-eviction:** a sender extending its own chain when full never evicts its own entry (it evicts another sender's tail, or gets POOL_FULL).
6. **Cap invariant:** after any admit-by-eviction, `size() == MAX_POOL_SIZE`.
