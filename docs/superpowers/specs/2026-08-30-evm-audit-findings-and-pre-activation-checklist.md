# EVM Security Audit (2026-08-30) — Findings & Pre-Activation Checklist

**Context.** After all §13.3 hard gates completed (Gate 1/2, G3-T1/T2/T3), a 4-way parallel adversarial audit (consensus, bridge, gas/economics, P2P·RPC·crypto) was run on `dev-evm`. **No live, remotely-exploitable vulnerability exists on the shipping testnet/mainnet configuration** — the whole EVM is behind `Long.MAX_VALUE` activation gates and `evm.enabled=false` on shared nets. The network-facing surface (P2P blob ingest, RPC, tx crypto) is well-hardened, and all prior audit fixes re-validated. What the audit found is a set of **latent traps and design items that arm the moment a feature is activated** — this doc is the disposition + the checklist that MUST be cleared before scheduling any `evm.*ActivationHeight` on a real network.

## Findings & disposition

| # | Sev | Finding | Disposition |
|---|-----|---------|-------------|
| **A1** | High (latent) | Unguarded `longValueExact()` in the G3-T1 fee credit (`BlockchainImpl.java:1435-1436`) can throw `ArithmeticException` *into* `setMain` after native state is half-applied → node-local consensus corruption. `evmFeeWei` is an unbounded `BigInteger` sum, so `/10⁹` can exceed `Long.MAX` given a large `evm.alloc` + fee-routing. | **FIX NOW** (hardening task, this session). Deterministic overflow guard → skip the (optional, additive) credit + CRITICAL log instead of aborting. |
| **A2** | — | Same-shape `longValueExact()` on the deposit-mint path (`BlockchainImpl.java:1247-1248`). | **No code change — unreachable by construction.** `credited` is an `XAmount` whose nano magnitude is already a `long`; converting it back to `long` cannot overflow. A guard would be dead code (YAGNI). |
| **A3** | Medium | `reverseReleasedWithdrawals` subtracts from the target with no underflow guard (`BlockchainImpl.java:1554-1561`); a shortfall would abort a reorg mid-unwind. | **Diagnostic only — do NOT swallow.** Unreachable by construction: the top-down `unWindMain` re-credits the target (via `unApplyBlock`) for every higher-height spend *before* reversing height M's release, so the target always holds the released amount at reversal time. And the current fail-loud is *correct*: a swallow-and-continue would credit the lock without debiting the target → silent conservation break. Add a pre-subtract CRITICAL log for observability; keep fail-loud. |
| **A4** | High (design) | Fee routing (G3-T1) mints native XDAG from **unbacked EVM wei**: `evm.alloc` genesis wei has no native lock, so spending it as gas mints fresh native XDAG via the reward-pool credit. `getSupply()` doesn't account for this. This is ADR-016's explicit open item ("update the conservation statement + test"), unresolved in code. | **PRE-ACTIVATION DESIGN DECISION (deferred).** See §"A4 resolution options". Blocks scheduling `feeRewardActivationHeight` on any net with a non-lock-backed wei supply. |
| **K1** | High (known) | Async-drain fee-credit divergence: `onBlobsAvailable` executes a deferred matured height on the P2P thread and **discards** its fee, so a temporarily-behind node credits `block.info.amount` differently than a never-behind node → consensus divergence (the spend `PoolAwardManager` makes is validated by all nodes). | **PRE-ACTIVATION BLOCKER (already tracked, spec §3.6).** Route `onBlobsAvailable` through a `BlockchainImpl.onEvmBlobsAvailable()` wrapper and credit block `M+δ−1` by height (both sync+async credit the same block). Blocks scheduling `feeRewardActivationHeight` at δ≥2 on a multi-node net. |

**Lower / polish (non-blocking):** corrupt on-disk EVM_META throws fail-fast → node-local liveness halt (document a re-sync/wipe-EVM_STATE recovery path); over-budget batch members dropped-not-retried + decode loop runs past budget (bound the decode loop, define a retry/GC story); RPC block-tag `ArithmeticException` mis-mapped to `-32603` + ERROR log-spam (map to `-32602`); optional per-message inbound rate-limit and HTTP `Host`-header allow-list (mitigated today by the loopback bind default).

**A1 residual (informational, pre-existing, unreachable):** even after the A1 conversion guard, `acceptAmount`→`XAmount.add`'s `Math.addExact(block.amount, evmFee)` is a theoretical throw site if a *credited* fee plus the block amount exceeded `Long.MAX`. Identical exposure existed before A1 and is unreachable for any `bitLength()≤62` fee against realistic block amounts (a fee large enough to overflow the add would have `bitLength()>62` and already be skipped). Not a regression; noted for the audit trail.

## Post-note: A1 + A3 shipped (2026-08-30)

A1's overflow guard and A3's reversal diagnostic were implemented + reviewed + merged this session (`fix(evm): guard EVM fee-credit overflow + bridge-reversal shortfall diagnostic (audit A1/A3)`). A2 was left unchanged (unreachable-by-construction). **K1 (async-drain fee-credit convergence) is now ALSO done** (merge b8c72862): both the sync and async paths credit the payload block M via a shared `creditEvmFee`, so a blob-behind node converges — the fork is closed. The remaining checklist items (**A4** supply/conservation + `evm.alloc` cross-check, external audit track, shared-net gas params, EVM_META recovery-path doc) are still open and gate any activation.

## A4 resolution options (pick before scheduling `feeRewardActivationHeight`)

The core question: **is EVM wei a claim on native XDAG (1:1 lock-backed), or an independent balance?** Options:

1. **Lock-backed only (recommended for a bridged mainnet):** forbid a non-empty `evm.alloc` whenever the bridge OR fee-routing is scheduled, so all EVM wei originates from a native lock. Add a fail-fast in `AbstractConfig` (`evm.alloc` non-empty ⇒ `bridgeActivationHeight` and `feeRewardActivationHeight` both `MAX_VALUE`, or the lock is pre-seeded with matching native). Cheap, prevents the misconfig, keeps conservation an equality.
2. **Unified supply:** explicitly define native+EVM as one supply, and update `getSupply()` + the conservation statement/tests to include the fee-routing credit and the genesis alloc. More invasive; needed only if genesis alloc on a fee-routing net is a real requirement.

Either way, `validateBridgeConfig` should gain the `evm.alloc`-vs-scheduled-bridge/fee cross-check (currently absent).

## Pre-activation checklist (gate before flipping any `evm.*ActivationHeight` off `MAX_VALUE` on testnet/mainnet)

- [ ] **A1** overflow guard shipped (this session's hardening task).
- [x] **K1** async-drain fee crediting routed through `BlockchainImpl.onEvmBlobsAvailable` + credited to the payload block M — **DONE (merge b8c72862)**; design `docs/superpowers/specs/2026-08-30-k1-async-drain-fee-credit-design.md`. Both sync + async credit the identical block M via a shared `creditEvmFee`, closing the fork.
- [ ] **A4** supply/conservation model decided + `evm.alloc`-vs-bridge/fee config cross-check added; `getSupply()`/conservation tests updated accordingly.
- [ ] External **security audit + bug bounty + testnet shakedown** (the "external track").
- [ ] Shared-net `minGasPrice` / `blockGasLimit` reviewed as the consensus values (devnet's `minGasPrice=1` makes gas ~free there by design).
- [ ] Documented node-recovery path for a corrupt EVM_META (re-sync / wipe EVM_STATE) so a single bad record can't wedge a node with no operator guidance.

**What is NOT a blocker (confirmed solid by the audit):** chained-root determinism, reorg reversal symmetry, `executeOne`'s consensus-protecting guard, δ-lag boundaries + BEHIND verdict, crafted-block hard-reject safety, type-2 pre-activation byte-identity, full tx-crypto validation (EIP-2/155/2718 + negative-gaslimit backstop), non-committing `eth_call`, content-addressed/awaited-gated blob ingest, constant-time auth + CORS split, bounded `eth_getLogs`.
