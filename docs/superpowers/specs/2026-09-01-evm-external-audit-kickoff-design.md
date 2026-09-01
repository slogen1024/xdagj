# External Audit Track Kickoff — Design

**Status:** approved 2026-09-01 · documentation + release-engineering (no code change)
**Relates to:** launch plan T2 items B4/B5 (`.claude/docs/evm-mainnet-launch-and-ecosystem-plan.md`), audit entry doc (`.claude/docs/evm-mainnet-readiness-and-audit-scope.md`), internal findings (`docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`).

## 1. Problem

The third-party audit (launch-plan item B4) is supposed to start after code freeze, and the engineering precondition is now met: all §13.3 hard gates are closed (Gate 1, Gate 2, G3-T1/T2/T3), the internal 2026-08-30 adversarial audit's findings are fixed (A1/A3/K1/A4 including A4-full transfer-from-lock), and `dev-evm` = `origin/dev-evm` = `3bbfc68f` with 633 tests green and license-check clean. But nothing auditor-facing reflects this:

- The audit entry doc (`evm-mainnet-readiness-and-audit-scope.md`, written 2026-08-22) still lists the three hard gates as "工程待做", omits every surface built since (state-root anchor, DA skip-marker, transfer-from-lock fee routing, batch packing, type-2, mempool eviction), says the net fee is *burned*, and its §6 checklist and §7 merge stats are stale.
- There is no English-language engagement document, and audit firms work in English.
- There is no immutable ref that defines *what* is being audited.

## 2. Decision

Ship a three-part kickoff package (user-approved scope 2026-09-01; firm-shortlist research, bug-bounty draft, and the dev-evm→develop PR are explicitly deferred):

1. **Code freeze tag** — annotated tag `evm-audit-freeze-1` at `3bbfc68f`, pushed to origin. Both documents cite it as the audited ref. `dev-evm` keeps moving; audit-relevant fixes get `-2`, `-3`, ….
2. **Entry-doc refresh** — bring `evm-mainnet-readiness-and-audit-scope.md` to the as-built state (stays Chinese, per the `.claude/docs` series convention; the file is git-tracked).
3. **English auditor brief** — new self-contained `docs/audit/2026-09-01-xdag-evm-external-audit-brief.md` (git-tracked; `docs/` needs `git add -f`). Chosen over a thin English index into the Chinese docs (pushes translation onto the firm) and over a bilingual entry-doc rewrite (highest effort, no added value): one document is one front door.

## 3. Deliverable detail

### 3.1 Freeze tag

```bash
git tag -a evm-audit-freeze-1 3bbfc68f -m "EVM external-audit freeze #1: all hard gates + A4-full transfer-from-lock; 633 tests green"
git push origin evm-audit-freeze-1
```

### 3.2 Entry-doc refresh (`.claude/docs/evm-mainnet-readiness-and-audit-scope.md`)

Per-section edits (mechanism details keep pointing at the design docs; this doc stays an index):

- **Header/§0:** add the frozen ref (`evm-audit-freeze-1` = `3bbfc68f`) and a pointer to the English brief as the auditor-facing layer.
- **§1 table:** step ⑤ (hard gates) → **已关闭** with merge SHAs; step ② → 本次启动（入口 = 英文 brief + 冻结 tag）; step ① stays 待发起 (deferred by user decision).
- **§2 audit scope:** keep 2.1–2.7 (update line-number drift only if trivially checkable); add new priority surfaces as §2.8–§2.11:
  - **§2.8 PoW 状态根锚定 + 硬拒（Gate 1）** — `EvmStateAnchor` block field, `evm.stateRootLag` δ-lag, `AnchorVerdict` incl. BEHIND, hard-reject config split (mainnet true / testnet+devnet false); 0x1E gossip deleted. Merges 57e9ebb4 / f5582ee8 / b92993cb / 3428ccc1.
  - **§2.9 δ-滞后执行 + DA 跳过标记（Gate 2）** — maturity buffer 0x09, skip marker 0x0A + `DA_SKIP_SENTINEL`, miner sets daSkip / validator honors unconditionally, proactive δ-window blob fetch + backoff, bridge release invariant `bridgeWithdrawalDelay ≥ stateRootLag−1`. Merges 2506b6c4 / c090dc2f / 75dfdaaa / 5d9cfbb1 / 4557113a.
  - **§2.10 费用路由 transfer-from-lock（G3-T1 + K1 + A4-full）** — net fee = lock→miner TRANSFER (no mint): `creditEvmFee` debit + 0x0B journal + `reverseEvmFeeDebit` unwind reversal; lock-shortfall deterministic skip-ALL; async blob-drain credits the same payload block M (K1 convergence); `GenesisLockSeeder` genesis deposit (ADDRESS-CF marker 0x60, ADD-not-SET); `getSupply` bridge-gated premine; config whole-nano/ceiling fail-fasts. Merges 807a7079 / b8c72862 / 3bbfc68f. Audit questions: conservation equality under fee flow + reorg; journal sweep⊆walk; seed idempotence incl. snapshot-bootstrap caveat.
  - **§2.11 批量打包 + type-2 + mempool 公平驱逐** — 0x0F batch commitment / nonce chains / dual-lookup exec; EIP-2718 type-2 envelope + effective-fee split + 4-site activation gate; `EvmTxPool` priority eviction (tails-only, no self-orphan). Merges per program history; eviction is node-local (non-consensus) — mark as lower priority for auditors.
- **§3:** flip all three hard gates to ✅ 已关闭 with merge SHAs + one-line as-built summaries; note the three-gates-one-activationHeight packaging holds.
- **§4 known limitations:** delete the "净费燃烧" and "EIP-3529 毛收" items (superseded); keep chained-delta-root (no eth_getProof), opcode placeholders, scan windows, baseFee≡0; add the A4 snapshot-bootstrap marker caveat.
- **§5:** unchanged except a cross-check note that fee-routing now *requires* a scheduled bridge (config fail-fast).
- **§6 checklist:** tick 硬门槛-1/2/3; add rows "内部审计发现已修复归档 (2026-08-30 doc) ✅" and "冻结 tag evm-audit-freeze-1 已推送 ✅".
- **§7:** refresh ahead-count and changed-file stats vs `develop` (compute at implementation time); note merge still conflict-free or report otherwise.

### 3.3 English auditor brief (`docs/audit/2026-09-01-xdag-evm-external-audit-brief.md`)

Self-contained; a firm should be able to quote from this document alone. Sections:

1. **Engagement summary** — what XDAG EVM is (Besu-EVM execution layer embedded in a DAG-PoW chain), audited ref = tag `evm-audit-freeze-1` (`3bbfc68f`) on `dev-evm` of the public repo, ~size of the surface (io.xdag.evm.* + consensus hooks; give file/LOC counts computed at implementation time), deployment status (devnet-live only; every shared-net activation gated `Long.MAX_VALUE` + `evm.enabled=false`).
2. **Architecture primer** (~1 page) — DAG partial order → main-chain total order; the three consensus hooks (applyBlock collect / setMain execute / unWindMain reverse); δ-lagged execution E(M)=M−δ+1; chained delta root ≠ account MPT; PoW-committed `EvmStateAnchor`; the native↔EVM bridge (lock address, 1 nano = 10⁹ wei, mint-before-txs, burn→matured release); transfer-from-lock fee routing.
3. **Scope, by priority** — English rendering of entry-doc §2.1–§2.11 with concrete "audit questions" per surface and code pointers (class-level, not line-level, to survive drift).
4. **Out of scope** — RandomX/PoW itself, pre-existing native consensus except the three hooks, pool/wallet/telnet subsystems, ecosystem tooling (hardhat-e2e, local-explorer), future absolute-MPT work.
5. **Declared invariants** — English list of the §12-numbered invariants the code claims (execute-only-in-setMain, DFS order, replay byte-identity, conservation equality, eth_call never commits, awaited-gated blob ingest, journal reverse-what-you-did, sweep⊆walk, deterministic skip-ALL guards, pre-activation byte-identity).
6. **Prior findings disclosure** — the 2026-08-30 internal 4-way adversarial audit: A1/A2/A3/A4/K1 with severity, disposition, fix SHAs (e2d2c41c, b8c72862, e43b2cd7/0741a8a2, 3bbfc68f), and the lower-priority polish list; states plainly that internal review is not independent and these areas deserve re-examination.
7. **Known limitations** (accepted, documented) — as entry-doc §4 post-refresh.
8. **Build & verify** — JDK 21 + Maven, exact commands, expected 633/0/0, the three excluded long tests and how to run them, license check, how to run a devnet node.
9. **Expected deliverables & process** — report with severity taxonomy (Critical/High/Medium/Low/Info), PoC or reasoning per finding, a fix-verification round against a `evm-audit-freeze-2` tag, disclosure handling; contact = repo owner.

## 4. Non-goals

- Audit-firm shortlist research; bug-bounty program draft (T2 item, later).
- `dev-evm` → `develop` merge/PR (readiness-doc step ①) — separate release-manager decision.
- Translating the full Chinese design-doc series; the brief is self-contained instead.
- Any code or config change.

## 5. Acceptance

- Tag `evm-audit-freeze-1` exists at `3bbfc68f` and is on origin.
- Entry doc contains no stale "工程待做/未启动" claims about closed gates; every new surface has an audit-scope entry; checklist reflects reality.
- Brief is fully self-contained English (no Chinese-doc dependency to understand scope), every SHA cited exists, build instructions reproduce 633-green on the tag.
- Both docs + this spec committed to `dev-evm` (docs-only commits, direct-to-branch per project convention).
