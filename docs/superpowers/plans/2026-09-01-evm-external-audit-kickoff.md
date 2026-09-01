# External Audit Kickoff Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the three-part external-audit kickoff package (spec `docs/superpowers/specs/2026-09-01-evm-external-audit-kickoff-design.md`): pushed freeze tag `evm-audit-freeze-1`, as-built refresh of the Chinese audit entry doc, and a self-contained English auditor brief.

**Architecture:** Documentation + release engineering only — zero code changes. Work happens DIRECTLY on `dev-evm` in the main checkout `/Users/tron/IDEAProject/xdagj` (docs-only commits go straight to the branch per project convention; no worktree). The user has explicitly approved pushing the tag; do NOT push the branch itself.

**Tech Stack:** git (annotated tag), Markdown. Verification: JDK 21 + Maven for the final build reproduction.

**Build env (Task 4 only):**
```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home"
MVN="$HOME/tools/apache-maven-3.9.9/bin/mvn"
```

**Verified facts to cite (all SHAs confirmed to exist in the repo, 2026-09-01):**
| Ref | Subject |
|-----|---------|
| 57e9ebb4 | Merge G1-T1 EVM state-root anchor block format |
| f5582ee8 | Merge G1-T2 miner writes the EVM state-root anchor |
| b92993cb | Merge G1-T3: setMain hard-rejects divergent state-root anchor |
| 3428ccc1 | Merge G1-T4: retire the 0x1E EVM state-root gossip |
| 2506b6c4 | Merge G2-T1a: relocate EVM execution to finality depth (maturity buffer) |
| c090dc2f | Merge G2-T1b: validator consumes the DA skip-marker |
| 75dfdaaa | Merge G2-T1c: miner sets the DA skip-marker at pack time |
| 5d9cfbb1 | Merge G2-T2: proactive delta-window blob fetch + bounded retry |
| 4557113a | Merge G2-T3: bridge withdrawal release under delta-lag |
| 807a7079 | Merge G3-T1: net EVM fee to miner reward pool (ADR-016) |
| dc30a3b7 | Merge G3-T2 EIP-3529 precise gas refund |
| aaa38a7b | Merge G3-T3: mempool per-sender fair eviction |
| b8c72862 | Merge K1: async-drain fee-credit convergence |
| e2d2c41c | fix(evm): A1 overflow guard + A3 reversal diagnostic |
| e43b2cd7 | feat(evm): A4 fail-safe (fee-routing requires bridge) |
| 0741a8a2 | Merge A4: fee-routing supply-conservation fail-safe |
| 3bbfc68f | Merge A4-full: transfer-from-lock fee routing — **the freeze commit** |

Other verified facts: full suite at `3bbfc68f` = **633 tests / 0 failures / 0 errors / 0 skipped** + `license:check` clean (verified twice on 2026-09-01: in the task worktree and on the merged `dev-evm`). `dev-evm` vs `develop`: **262 commits ahead, 227 files changed, +34,689/−361** (recompute in Task 2 if `develop` moved). Repo: `https://github.com/slogen1024/xdagj`. Excluded long tests (surefire): `RandomXSyncTest`, `SyncTest`, `SnapshotJTest`.

---

### Task 1: Freeze tag `evm-audit-freeze-1`

**Files:** none (git ref only)

- [ ] **Step 1: Verify the target commit is what we think it is**

```bash
git -C /Users/tron/IDEAProject/xdagj log -1 --format='%h %s' 3bbfc68f
```
Expected: `3bbfc68f Merge A4-full: transfer-from-lock fee routing (supply conservation)`

- [ ] **Step 2: Create the annotated tag**

```bash
git -C /Users/tron/IDEAProject/xdagj tag -a evm-audit-freeze-1 3bbfc68f -m "EVM external-audit freeze #1

Audited surface: XDAG EVM execution layer (io.xdag.evm.*) + consensus hooks,
all §13.3 hard gates closed (Gate 1 state-root anchor, Gate 2 DA skip-marker,
G3 fee routing/refund/mempool) + A4-full transfer-from-lock supply conservation.
Full suite: 633 tests, 0 failures. Entry docs: docs/audit/ (English brief),
.claude/docs/evm-mainnet-readiness-and-audit-scope.md (Chinese index)."
```

- [ ] **Step 3: Push the tag (user-approved outward action; push ONLY the tag, not the branch)**

```bash
git -C /Users/tron/IDEAProject/xdagj push origin evm-audit-freeze-1
```

- [ ] **Step 4: Verify**

```bash
git -C /Users/tron/IDEAProject/xdagj describe --tags 3bbfc68f
git -C /Users/tron/IDEAProject/xdagj ls-remote --tags origin evm-audit-freeze-1
```
Expected: first prints `evm-audit-freeze-1`; second lists the tag ref on origin (two lines: the tag object and `^{}` peeled to `3bbfc68f...`).

No commit for this task (tags are refs, not commits).

---

### Task 2: Refresh the Chinese audit entry doc

**Files:**
- Modify: `.claude/docs/evm-mainnet-readiness-and-audit-scope.md`

Read the whole file first (141 lines). Apply the following section edits. Keep everything not mentioned below byte-identical. The doc's voice is a compact Chinese index that points to design docs for mechanism detail — match it.

- [ ] **Step 1: Header block — add the frozen ref + English brief pointer**

After the existing `> 交叉引用：...` line in the header quote block, add one more quoted line:

```markdown
> **审计基线（code freeze）：tag `evm-audit-freeze-1` = commit `3bbfc68f`（2026-09-01，全量 633 测试绿 + license:check 通过）。英文审计入口（面向审计机构，自包含）：[`docs/audit/2026-09-01-xdag-evm-external-audit-brief.md`](../../docs/audit/2026-09-01-xdag-evm-external-audit-brief.md)。**
```

- [ ] **Step 2: §1 table — flip steps ② and ⑤**

Replace the two rows:

```markdown
| ② | 第三方安全审计（范围见 §2） | 外部 | 待启动——本文为其入口 |
```
with
```markdown
| ② | 第三方安全审计（范围见 §2） | 外部 | **本次启动（2026-09-01）**：冻结 tag `evm-audit-freeze-1` 已推送，英文 brief 已就绪（`docs/audit/`）；待选定审计机构 |
```
and
```markdown
| ⑤ | §13.3 三项硬门槛全部关闭 | 仓库内（需硬分叉） | **未关闭**（§3）——最大工程阻塞 |
```
with
```markdown
| ⑤ | §13.3 三项硬门槛全部关闭 | 仓库内（需硬分叉） | **✅ 已关闭（2026-08-26/28/30）**：Gate 1（57e9ebb4/f5582ee8/b92993cb/3428ccc1）、Gate 2（2506b6c4/c090dc2f/75dfdaaa/5d9cfbb1/4557113a）、G3-T1/T2/T3（807a7079/dc30a3b7/aaa38a7b）；另 A4-full transfer-from-lock（3bbfc68f，2026-09-01）关闭费用守恒 |
```

Also replace the `**放行顺序**` paragraph with:

```markdown
**放行顺序**：⑤ 已关闭；② 本次启动（代码已冻结于 `evm-audit-freeze-1`）；① 待发起（发布负责人决策，见 §7）；⑥ 是终点。
```

- [ ] **Step 3: §2 — append four new audit surfaces after §2.7**

Append verbatim after the §2.7 block:

```markdown
### 2.8 PoW 承诺的状态根 + 硬拒（Gate 1，2026-08-25/26 落地）

- 审计问题：`EvmStateAnchor` 块字段的编解码是否无歧义（含 daSkip 位）？矿工写锚（`(height, root(H−δ))`，δ=`evm.stateRootLag`）与 `setMain` 校验是否对称？`AnchorVerdict.BEHIND`（本节点落后）与 DIVERGE（分歧）的判定是否不可混淆——落后节点绝不硬拒诚实链？`evm.stateRootHardReject`（mainnet true / testnet+devnet false）的分裂配置是否安全？0x1E 链下 gossip 已删除，码位复用是否有残留路由？
- 代码：`core/Block*`（EvmStateAnchor 字段编解码）、`consensus/`（createMainBlock 写锚）、`core/BlockchainImpl`（setMain 锚校验/硬拒）。
- 合并：57e9ebb4（块格式）/ f5582ee8（矿工写锚）/ b92993cb（硬拒）/ 3428ccc1（退役 0x1E）。

### 2.9 δ-滞后执行 + DA 跳过标记（Gate 2，2026-08-27/28 落地）

- 审计问题：执行迁移到成熟深度（E(M)=M−δ+1，maturity buffer EVM_META 0x09）后，成熟/回滚/重放是否与 δ=1 逐字节一致？跳过标记（EVM_META 0x0A + `DA_SKIP_SENTINEL` 折叠进链式根）是否全网确定性——矿工置位 daSkip、验证方无条件遵从，落后/竞态的 daSkip 能否造成分叉？主动 δ-窗口 blob 拉取 + 退避是否只是活性优化（无共识面）？bridge 释放不变量 `bridgeWithdrawalDelay ≥ stateRootLag−1` 的 fail-fast 是否完备（低于它 → 每次释放 CRITICAL-skip → 桥永不释放）？
- 代码：`evm/EvmBlockProcessor`（processConfirmedBlock/maturedPayloadAvailable/executeList 折叠 sentinel）、`evm/state/EvmMetaStore`（0x09/0x0A）、`net/XdagP2pHandler.requestMissingEvmBlobs`、`config/AbstractConfig`（W≥δ−1 校验）。
- 合并：2506b6c4 / c090dc2f / 75dfdaaa / 5d9cfbb1 / 4557113a。

### 2.10 费用路由 transfer-from-lock（G3-T1 + K1 + A4-full，2026-08-29 → 09-01 落地）— **最高优先级（新共识面）**

- 审计问题：净费 = **锁→矿工转移**（非铸造）：`creditEvmFee` 的 debit 锁 → 0x0B 日志 → credit 块 的顺序与全跳过守卫（锁不足/A1 溢出 = 确定性 skip-ALL、费燃烧）是否任何输入下全网一致？`reverseEvmFeeDebit` 在 unWindMain 中"精确反转做过的事"——0x0B 日志的 sweep⊆walk（removeAbove 绝不能扫掉未反转的日志）是否成立？异步 blob-drain 与同步 setMain 是否 credit 同一载荷块 M（K1 收敛，落后节点金额一致）？`GenesisLockSeeder` 创世入金（ADDRESS-CF 标记 0x60，ADD 非 SET——保留升级/预激活滞留余额；EVM_STATE 擦盘不得重播种）幂等性？快照引导节点必须携带 0x60 标记（或共享网 alloc 留空）——见就绪 caveat？`getSupply` 加 bridge 门控的 alloc premine 后是否仍为闭式精确值、共享网逐字节不变？配置整-nano/上限 fail-fast + `allocTotalNano` 运行时尘额后备是否双层完备？
- 代码：`core/BlockchainImpl`（creditEvmFee/reverseEvmFeeDebit/getSupply/onEvmBlobsAvailable）、`evm/bridge/GenesisLockSeeder`、`evm/state/EvmMetaStore`（0x0B）、`db/AddressStore*`（0x60 标记）、`config/AbstractConfig`（alloc 校验）。
- 合并：807a7079（G3-T1）/ b8c72862（K1）/ 0741a8a2（A4 fail-safe）/ 3bbfc68f（A4-full）。守恒断言：capstone 测试断言锁-debit 等式（同步+异步）。

### 2.11 批量打包 + type-2 信封 + mempool 公平驱逐（2026-08-17/18/30 落地）

- 审计问题：0x0F 批承诺 + 池内 nonce 链 + 双查找执行是否与逐笔路径语义一致（MAX_BATCH_TXS=3971 传输上限）？EIP-2718 type-2（0x02 信封、effective/feeCap 费拆分、ethers 向量钉住）的 4 处激活门（含预激活逐字节收据）是否完备？`EvmTxPool` 满池优先驱逐（price ASC → sender-load DESC → age ASC，仅尾部、不自孤、一进一出）是否不可被用作定向逐出攻击——注意此面为**节点本地非共识**，优先级低于 2.8–2.10。
- 代码：`evm/tx/EvmTxPool`、`evm/tx/EvmTransaction`（type-2）、`net/`（0x1F/0x20）、批承诺执行路径。
```

- [ ] **Step 4: §3 — flip the three hard gates to closed**

Replace the three numbered items' trailing `**工程待做**（…）` clauses and the intro. New §3 intro sentence (replace the `> **决策门已全关…` block's last sentence "下列三门槛的**方案已定**，但**工程实现仍未完成**……"):

```markdown
> **决策门已全关（2026-08-24）**：ADR-013…016 已签署。**三门槛工程已全部关闭（2026-08-25 → 08-30）**，打包同一 `activationHeight` 的键石（G1-T1 块格式）已交付；另 A4-full（费用守恒 transfer-from-lock）于 2026-09-01 关闭。以下保留原编号，标注关闭状态：
```

Item 1: replace `**工程待做**（G1-T1…T4）。` with `**✅ 已关闭**（G1-T1…T4：57e9ebb4 / f5582ee8 / b92993cb / 3428ccc1）。`
Item 2: replace `**工程待做**（G2-T1…T3）。` with `**✅ 已关闭**（G2-T1a/b/c + T2 + T3：2506b6c4 / c090dc2f / 75dfdaaa / 5d9cfbb1 / 4557113a）；bridge CRITICAL-skip 降级已转为确定性有界路径（freeze→re-sync 语义）。`
Item 3: replace the whole item text (it says 净费燃烧/工程待做) with:

```markdown
3. **费用路由与经济参数**：**✅ 已关闭**——净费路由到矿工奖励池（G3-T1，807a7079）并升级为 **transfer-from-lock**（A4-full，3bbfc68f：锁→矿工转移非铸造，getSupply 精确）；异步 drain 收敛（K1，b8c72862）；EIP-3529 精确退款（G3-T2，dc30a3b7）；mempool per-sender 公平驱逐（G3-T3，aaa38a7b，节点本地）。经济参数本身（blockGasLimit/minGasPrice 真实共识值）仍属 §5 启用前必改项。
```

- [ ] **Step 5: §4 — drop superseded limitations, add the snapshot caveat**

Delete the line about type-2 第二步 ONLY IF it mentions 净费燃烧 — it does not; keep it. Instead: §4 has no fee-burn line (that lives in old §3), so the §4 edit is only an ADDITION. Append to §4's bullet list:

```markdown
- A4-full 快照引导 caveat：快照引导的节点必须随快照携带 ADDRESS-CF 创世播种标记（0x60），否则会在快照余额之上重复播种锁（共享网当前计划 alloc 留空，天然规避）；写入运维/恢复文档前审计需知情。
```

- [ ] **Step 6: §5 — add the fee⇒bridge cross-check note**

Append one bullet at the end of the §5 启用前必改项 numbered list:

```markdown
  6. 费用路由与桥耦合：`feeRewardActivationHeight` 已被配置校验强制要求同时排期 bridge（transfer-from-lock 的锁来源）；排期时两者一并规划。
```

- [ ] **Step 7: §6 checklist — tick the closed gates, add two rows**

Flip these three to checked and annotate:

```markdown
- [x] §3 硬门槛-1（状态根 PoW 承诺）已关闭（2026-08-26）
- [x] §3 硬门槛-2（DA 强制）已关闭（2026-08-28）
- [x] §3 硬门槛-3（费用路由/配额/退款）已关闭（2026-08-30；A4-full 守恒 2026-09-01）
```

Add two new rows after them:

```markdown
- [x] 内部对抗性审计（2026-08-30，4 路）发现已修复归档：`docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`
- [x] 审计冻结基线已推送：tag `evm-audit-freeze-1` = `3bbfc68f`（全量 633 测试绿）
```

- [ ] **Step 8: §7 — refresh the merge stats**

Recompute first (develop may have moved):

```bash
git -C /Users/tron/IDEAProject/xdagj rev-list --count develop..dev-evm
git -C /Users/tron/IDEAProject/xdagj diff --stat develop...dev-evm | tail -1
git -C /Users/tron/IDEAProject/xdagj merge-tree $(git -C /Users/tron/IDEAProject/xdagj merge-base develop dev-evm) develop dev-evm | grep -c '^<<<<<<<' || echo 0
```

Then replace §7's 领先/改动面 bullets with the recomputed numbers (as of plan-writing: 262 commits ahead / 227 files / +34,689 −361; conflict count expected 0 — if NOT 0, report it instead of claiming conflict-free). Keep the 合并动作 bullet unchanged.

- [ ] **Step 9: Commit**

```bash
git -C /Users/tron/IDEAProject/xdagj add .claude/docs/evm-mainnet-readiness-and-audit-scope.md
git -C /Users/tron/IDEAProject/xdagj commit -m "docs(evm): refresh audit entry doc to as-built state; cite freeze tag (audit kickoff)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: English auditor brief

**Files:**
- Create: `docs/audit/2026-09-01-xdag-evm-external-audit-brief.md`

- [ ] **Step 1: Compute the surface-size stats to substitute**

```bash
cd /Users/tron/IDEAProject/xdagj
echo "evm pkg: $(git ls-files 'src/main/java/io/xdag/evm/**' | wc -l) files, $(git ls-files 'src/main/java/io/xdag/evm/**' | xargs wc -l | tail -1 | awk '{print $1}') lines"
echo "delta vs develop: $(git rev-list --count develop..dev-evm) commits, $(git diff --stat develop...dev-evm | tail -1)"
```

(At plan time: 26 files / 5,042 lines in `io.xdag.evm.*`; 227 files / +34,689 across the whole EVM delta. Substitute fresh numbers into §1 below where marked `«…»`.)

- [ ] **Step 2: Write the brief**

Create `docs/audit/2026-09-01-xdag-evm-external-audit-brief.md` with EXACTLY this content (substituting the two `«…»` stats):

````markdown
# XDAG EVM — External Security Audit Brief

**Date:** 2026-09-01 · **Audited ref:** tag `evm-audit-freeze-1` = commit `3bbfc68f`, branch `dev-evm`, repo `https://github.com/slogen1024/xdagj` · **Status of this document:** engagement entry point — self-contained; no other project document is required to scope the engagement.

## 1. Engagement summary

XDAGJ is the Java implementation of XDAG, a DAG-based PoW cryptocurrency (RandomX). The `dev-evm` branch embeds a **Hyperledger Besu-EVM execution layer** into the node: EVM transactions ride the existing DAG transport, execute deterministically at main-block confirmation, and interoperate with native XDAG through a two-way bridge. The work is feature-complete and internally audited; **no shared network runs it yet** — on testnet/mainnet configuration every activation height is `Long.MAX_VALUE` and `evm.enabled = false`; only the throwaway devnet is live.

We are seeking a security audit of the EVM integration ahead of scheduling any activation on a public network.

- **Audited surface:** `io.xdag.evm.*` («26 files, ~5,000 lines») plus its integration points: three consensus hooks in `io.xdag.core.BlockchainImpl`, six P2P message codes in `io.xdag.net`, the `eth_*` JSON-RPC handlers in `io.xdag.rpc`, and the `evm.*` configuration surface in `io.xdag.config`. Full branch delta vs `develop`: «227 files, +34,689 lines».
- **Verification baseline at the audited ref:** `mvn test` = 633 tests, 0 failures, 0 errors, 0 skipped; `mvn license:check` clean (JDK 21).
- **Engagement shape sought:** manual review of the priority surfaces in §3, findings report per §9, one fix-verification round.

## 2. Architecture primer

XDAG blocks form a DAG; consensus selects a **main chain** whose blocks each confirm a set of DAG blocks. The EVM piggybacks on this: EVM transactions are content-addressed blobs referenced from carrier blocks, and execution is totally ordered by main-block confirmation.

- **Three consensus hooks** (the only places EVM touches consensus): `applyBlock` collects EVM tx references in deterministic DFS order; `setMain` executes a confirmed height's references against persisted EVM world state; `unWindMain` reverses on reorg. EVM code never executes during import, orphan handling, or fork evaluation.
- **δ-lagged execution:** confirming main height H executes matured height M = H − δ + 1 (δ = `evm.stateRootLag`; devnet 1, proposed shared-net 16). Execution inputs buffer in a maturity store until depth is reached.
- **State commitment:** the EVM state is committed as a **chained delta root** (a running keccak chain over per-height execution digests), *not* an account MPT — there is no `eth_getProof`. The root is PoW-committed: each main block carries an `EvmStateAnchor` field `(height, root(H−δ), daSkip)`; a mismatching anchor is hard-rejected on mainnet configuration (warn-only on testnet/devnet).
- **Data availability:** if a miner packs a height whose blobs it cannot make available, it must set the block-committed `daSkip` bit; validators then deterministically skip that height's execution (folding a domain-separated sentinel into the chained root). Nodes missing blobs for an *include* height stall (freeze, request, re-sync) rather than diverge.
- **Bridge (native ↔ EVM):** a depositor sends native XDAG to a fixed lock address with a structured remark; confirmation mints EVM wei 1:1 (1 nano = 10⁹ wei) before that height's txs execute. Withdrawals burn wei via a fixed system contract; burns recorded at height K release native from the lock at K + `bridgeWithdrawalDelay`, all-or-nothing per height, journaled so reorg reversal reverses exactly what was done.
- **Fee routing (transfer-from-lock):** the net EVM fee is native the payer already owns (their wei is a claim on locked native): at credit time the lock is debited, the debit is journaled, and the confirmed payload block's distributable amount gains the fee (the mining pool then distributes it). Nothing is minted; total native supply is conserved as an equality, and `getSupply()` counts the (bridge-gated) genesis-alloc premine seeded into the lock at chain start.

## 3. Scope, by priority

For each surface: the questions we most want answered, and where the code lives (class level, to survive line drift).

### P0-1 · Consensus hooks (DAG partial order → EVM total order)
Is `evmRefs` collection order strictly the `applyBlock` DFS order on every node? Does EVM execution occur **only** in `setMain`? Can any EVM exception escape into consensus and halt or corrupt the node? Are both `BI_APPLIED` settlement points collected?
→ `io.xdag.core.BlockchainImpl` (applyBlock / setMain / unWindMain), `io.xdag.evm.EvmBlockProcessor` (processMainBlock / executeList / executeOne).

### P0-2 · Reorg symmetry & replay identity
Is `rollbackTo`'s wipe-and-replay byte-identical to forward execution (replayed root == stored root)? Do genesis markers, resets, and replay order interact correctly? Is the bridge unwind an exact reversal of journaled actions (release journal 0x08, fee-debit journal 0x0B)? Is an N-block unwind O(n), triggering one wipe?
→ `EvmBlockProcessor.rollbackTo`, `io.xdag.evm.state.EvmMetaStore` (record families 0x00–0x0B), `BlockchainImpl` (reverseReleasedWithdrawals / reverseEvmFeeDebit).

### P0-3 · Bridge conservation (incl. transfer-from-lock fee routing)
Is total native conserved as an **equality** across deposit / gas fee / withdrawal / genesis alloc, under reorgs? Deposit minting: lossless 1 nano = 10⁹ wei; malformed remarks deterministically routed to the recovery address on every node? Withdrawal release: all-or-nothing per height, never partial, never retried into inconsistency? Fee credit: debit-lock → journal → credit-block ordering; deterministic skip-ALL guards (lock shortfall, overflow) identical on every node? Journal hygiene: can `removeAbove` ever sweep a fee-debit journal whose debit was not reversed (we claim sweep ⊆ walk)? Async blob-drain crediting the same payload block as the sync path (a temporarily-behind node must converge on identical block amounts)? `GenesisLockSeeder`: exactly-once semantics (ADDRESS-CF marker; ADD-not-SET to preserve pre-existing lock balance), and the documented snapshot-bootstrap caveat? `getSupply()` exactness?
→ `io.xdag.evm.bridge.*` (esp. `GenesisLockSeeder`, `BridgeConstants`), `BlockchainImpl` (creditEvmFee / reverseEvmFeeDebit / getSupply / onEvmBlobsAvailable), `io.xdag.config.AbstractConfig` (validateBridgeConfig), `io.xdag.db.AddressStore*`.

### P0-4 · State-root anchor & DA skip (PoW-committed)
Anchor encode/decode unambiguous (incl. the daSkip bit)? Miner-write vs validator-check symmetry? Is BEHIND (this node lacks data) cleanly distinguishable from DIVERGE (states disagree) — can a lagging node ever hard-reject an honest chain? Can a stale or adversarial daSkip bit fork the network (we claim it only affects root(H−δ+1), which the next block's anchor re-verifies)? Config split safety (`stateRootHardReject`: mainnet true, others false)?
→ block codec (EvmStateAnchor field), `io.xdag.consensus` (createMainBlock anchor computation), `BlockchainImpl` (anchor verdict handling), `EvmBlockProcessor` (DA_SKIP_SENTINEL folding, maturedPayloadAvailable).

### P1-5 · Gas settlement
Prepay of maxFee before the child-updater snapshot (else refunds resurrect deducted balances)? Revert/OOG still charges the net fee and bumps nonce? Type-2 fee semantics (effective = min(maxPriorityFee, maxFee) at baseFee ≡ 0) exact, type-0 byte-identical? EIP-3529 capped refund (min(refund, gasUsed/5)) fork-gated correctly?
→ `EvmBlockProcessor.executeOne`, `io.xdag.evm.tx.EvmTransaction`, `IntrinsicGas`.

### P1-6 · Transaction codec & crypto
EIP-155 (reject unprotected), EIP-2 low-s enforcement (single valid hash per tx), EIP-2718 envelope dispatch (accept 0x02, reject unknown types), intrinsic gas per EIP-2028/3860, cross-client hash/sender agreement (ethers-pinned vectors)?
→ `EvmTransaction` (decode / signingHash / getSender / getHash), `EvmAddress`.

### P1-7 · P2P DoS surface
Is `isAwaitingBlob`-gated ingest the sole and sufficient defense against unsolicited-blob disk DoS? Does the 128 KiB size gate cover every entry point? Do all six message codes route (0x1B–0x20)? Is the stall queue "behind", never "diverged"? Batch commitment (0x0F) consistency with per-tx semantics?
→ `io.xdag.net.XdagP2pHandler` (channelRead0 routing, ingest/request handlers), `io.xdag.net.message.p2p.Evm*Message`.

### P2-8 · RPC surface
`eth_call` / `eth_estimateGas` can never commit state (simulation path fully separated from consensus path)? Bounded `eth_getLogs` (scan-range cap)? Auth constant-time, CORS split sane? (Loopback bind is the shipped default.)
→ `io.xdag.rpc.server.handler.EthRequestHandler`, `io.xdag.evm.XdagEvmExecutor` (simulateCall / simulateDeploy).

### P2-9 · Mempool fair eviction (node-local, non-consensus)
Full-pool priority eviction (price ASC → sender-load DESC → age ASC; tails only; incoming sender excluded): can it be weaponized for targeted eviction? Nonce-contiguity preserved?
→ `io.xdag.evm.tx.EvmTxPool`.

## 4. Out of scope

RandomX / PoW consensus itself; pre-existing native XDAG consensus except the three hooks; mining-pool, wallet, telnet-admin subsystems; ecosystem tooling (`tools/hardhat-e2e`, `tools/local-explorer`); the future absolute-MPT state commitment (explicitly deferred, see §7).

## 5. Declared invariants (what the code claims — please try to break them)

1. EVM executes only in `setMain`; never on import/orphan/fork paths.
2. Execution order = `applyBlock` DFS order, identical on every node.
3. Replay (wipe + re-execute) is byte-identical: replayed chained root == stored root.
4. Receipts, reverse indexes, blooms, journals for reorged-out heights are swept with the reorg.
5. `eth_call` / `eth_estimateGas` never reach `commit()` on persistent state.
6. Only blobs a node is awaiting (pending ∪ buffered) are accepted and persisted from the network.
7. Reorg reversal reverses exactly what was journaled (releases 0x08, fee debits 0x0B) — no recomputation.
8. Sweep ⊆ walk: EVM_META truncation can never delete a journal whose native effect was not already reversed by the unwind walk.
9. All economic guards are deterministic skip-ALL (bridge release shortfall, fee-credit lock shortfall, fee overflow): every node takes the same branch given the same chain.
10. Total native supply is conserved as an equality under deposits, withdrawals, gas fees, and the genesis-alloc premine; `getSupply()` is exact.
11. Pre-activation byte-identity: with every `evm.*ActivationHeight` at `Long.MAX_VALUE` and `evm.enabled=false`, node behavior is byte-identical to a non-EVM build (all new paths at least double-gated).
12. Prepaid gas is deducted before the child-updater snapshot; failed txs charge fees and bump nonces per Ethereum semantics.

## 6. Prior findings disclosure (internal — not independent; please re-examine these areas)

A 4-way internal adversarial review (consensus / bridge / gas-economics / P2P·RPC·crypto) ran on 2026-08-30. Conclusion then: no live remotely-exploitable vulnerability on the shipping configuration; several latent traps that arm at activation. All are now closed:

| ID | Severity | Finding | Disposition (fix SHA) |
|----|----------|---------|------------------------|
| A1 | High (latent) | Unguarded `longValueExact()` in the fee credit could throw into a half-applied `setMain` | Fixed: deterministic bitLength>62 skip (e2d2c41c) |
| A2 | — | Same shape on the deposit-mint path | No change: unreachable by construction (long-bounded amount) |
| A3 | Medium | Withdrawal-reversal underflow would abort a reorg | Diagnostic added; fail-loud kept deliberately (e2d2c41c) |
| A4 | High (design) | Fee routing minted native from unbacked genesis-alloc wei; `getSupply()` blind to it | Config fail-safe (e43b2cd7, 0741a8a2) then full transfer-from-lock model (3bbfc68f, 2026-09-01) |
| K1 | High | Async blob-drain discarded its fee → behind-node divergence on block amounts | Fixed: shared credit path, same payload block (b8c72862) |

Lower-priority items acknowledged and open (non-blocking, documented): operator recovery runbook for corrupt EVM_META (fail-fast today); batch decode-loop budget bound; an RPC error-code mapping polish; optional inbound rate-limit / Host-header allowlist (mitigated by loopback default). Full internal report: `docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md` in the repo.

## 7. Known limitations (accepted, documented — not findings)

- State commitment is a chained delta root, not an account MPT: no `eth_getProof`, no light clients; cross-node divergence is caught by the PoW anchor, not by state proofs.
- Opcode placeholders: `GASPRICE` reads 0; `BLOCKHASH` / `PREVRANDAO` / `BASEFEE` / `COINBASE` read 0 (deterministic, no randomness claim).
- `eth_getLogs` scan window ≤ 1024 heights; historical state queries cover the most recent 128 heights only.
- No EIP-1559 base-fee market: `baseFee ≡ 0`; congestion control is a flat `minGasPrice`.
- Snapshot-bootstrap caveat: a node bootstrapping from a state snapshot must carry the genesis-lock-seed marker with the snapshotted balances (shared-net plan keeps `evm.alloc` empty, which sidesteps this).

## 8. Build & verify

Requirements: JDK 21 (Temurin tested), Maven 3.9.x.

```bash
git clone https://github.com/slogen1024/xdagj && cd xdagj
git checkout evm-audit-freeze-1
mvn clean package -DskipTests     # build
mvn test                          # expected: 633 tests, 0 failures, 0 errors, 0 skipped
mvn license:check                 # expected: clean
```

Three long-running tests are excluded from the default suite (`RandomXSyncTest`, `SyncTest`, `SnapshotJTest`); run individually via `mvn test -Dtest=<Name>` if desired. To run a devnet node:

```bash
java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar target/xdagj-*-executable.jar -d
```

Devnet ships fee routing, the bridge, and a funded genesis alloc active from height 0 (`src/main/resources/xdag-devnet.conf`), so every audited mechanism is exercisable locally.

## 9. Expected deliverables & process

- Findings report with severity taxonomy (Critical / High / Medium / Low / Informational), each finding with reproduction steps or a reasoned exploit path, affected code, and a suggested fix direction.
- We will fix and tag `evm-audit-freeze-2`; one fix-verification round against that tag is in scope.
- Coordinated disclosure: findings remain private until fixes ship on the public branch; the final report will be archived in-repo (launch-plan step ④).
- Contact: repository owner via GitHub (`slogen1024/xdagj`) issues for logistics; a private channel will be established at engagement start for findings.
````

- [ ] **Step 3: Verify every SHA cited in the brief exists**

```bash
cd /Users/tron/IDEAProject/xdagj
for sha in 3bbfc68f e2d2c41c e43b2cd7 0741a8a2 b8c72862; do git cat-file -t $sha || echo "MISSING $sha"; done
```
Expected: five lines of `commit`, no MISSING.

- [ ] **Step 4: Commit (docs/ needs -f per the repo's gitignore)**

```bash
git -C /Users/tron/IDEAProject/xdagj add -f docs/audit/2026-09-01-xdag-evm-external-audit-brief.md
git -C /Users/tron/IDEAProject/xdagj commit -m "docs(evm): English external-audit engagement brief (audit kickoff)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Verification pass

- [ ] **Step 1: Stale-claims sweep on the entry doc**

```bash
grep -n "工程待做\|待启动——本文为其入口\|未关闭" /Users/tron/IDEAProject/xdagj/.claude/docs/evm-mainnet-readiness-and-audit-scope.md
```
Expected: NO hits describing gates ②/⑤/§3 as open (any remaining hit must be about genuinely-open items ①/③/④/⑥ — read each hit and justify or fix).

- [ ] **Step 2: Reproduce the build claim at the tag**

The tag commit `3bbfc68f` already has two full-suite verifications from 2026-09-01 (worktree + merged branch, both 633/0/0/0). Re-run once against the tag ref for the brief's reproducibility claim:

```bash
cd /Users/tron/IDEAProject/xdagj
git stash list | head -1   # ensure nothing to lose; working tree should be clean apart from committed docs
git worktree add /tmp/evm-audit-verify evm-audit-freeze-1
cd /tmp/evm-audit-verify
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home"
"$HOME/tools/apache-maven-3.9.9/bin/mvn" -q test 2>&1 | tail -5
python3 -c "
import glob, re
t=f=e=s=0
for p in glob.glob('/tmp/evm-audit-verify/target/surefire-reports/*.xml'):
    m=re.search(r'tests=\"(\d+)\".*?errors=\"(\d+)\".*?skipped=\"(\d+)\".*?failures=\"(\d+)\"', open(p).read(600))
    if m: t+=int(m.group(1)); e+=int(m.group(2)); s+=int(m.group(3)); f+=int(m.group(4))
print(f'tests={t} failures={f} errors={e} skipped={s}')"
cd /Users/tron/IDEAProject/xdagj
git worktree remove --force /tmp/evm-audit-verify
```
Expected: `tests=633 failures=0 errors=0 skipped=0`. If it differs, STOP — the brief's §1/§8 claims are wrong; investigate before shipping.

- [ ] **Step 3: Final state check**

```bash
git -C /Users/tron/IDEAProject/xdagj status -sb
git -C /Users/tron/IDEAProject/xdagj log --oneline origin/dev-evm..dev-evm
```
Expected: clean tree; local commits = the spec commit (f2067b91) + Task 2 commit + Task 3 commit. Branch NOT pushed (only the tag was pushed — pushing `dev-evm` is a separate user decision).

---

## Self-review notes

- **Spec coverage:** §3.1 tag → Task 1; §3.2 entry-doc per-section edits → Task 2 steps 1–8 (every bullet in the spec's §3.2 list maps to a step); §3.3 brief with all nine sections → Task 3 (full text embedded); spec §5 acceptance → Task 4 (stale-claims grep, SHA existence, build reproduction, no-branch-push check).
- **Deliberate judgment calls:** the brief avoids embedding a personal email (contact = GitHub); code pointers are class-level by design; the entry doc's §2.1–2.7 line-number references are left as-is (spec: "update only if trivially checkable" — they are historical anchors, and the English brief supersedes them for auditors).
- **The user has pre-approved exactly one outward action: pushing the tag.** Nothing else leaves the machine.
