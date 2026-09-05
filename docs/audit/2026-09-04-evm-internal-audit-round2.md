# XDAG EVM 智能合约 — 内部安全审计（第二轮，2026-09-04）

**审计对象**：`dev-evm` @ `ae6f2716`（源码与冻结标签 `evm-audit-freeze-1` = `3bbfc68f` 完全一致，之后仅 4 个文档提交）。
**方法**：5 路并行对抗式审阅（EVM 核心/状态、桥+共识集成、交易编解码/交易池/P2P、RPC/WS、配置/DB/完整性），每个发现由主审按源码逐条复核，关键结论做数值/实证验证。
**基线**：`mvn clean test` = 633 tests, 0 failures, 0 errors, 0 skipped（JDK 21，2 分钟）。
**先前已处置、本轮不再重复**：A1/A2/A3/A4/K1（见 `docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`）。

## 0. 结论摘要

- **功能完整性**：EVM 执行、RocksDB 世界状态、EIP-2718 type-2、批次打包、双向桥、PoW 锚定的状态根 + δ 延迟执行 + DA-skip、EIP-3529、手续费入矿工奖励（transfer-from-lock）、交易池公平驱逐——**设计文档承诺的机制均已落地并有测试**。devnet（δ=1）可完整跑通部署/调用/桥接。
- **但尚不能在 testnet/mainnet 激活**：本轮发现 **4 个共识级 High**（其中 3 个只在 δ≥2 即共享网配置下触发，1 个与 δ 无关）、**2 个 P2P High**（1 个在 devnet 上即为 live）、**2 个未受 `evm.enabled` 门控、EVM 关闭的滚动升级期就会生效的 High/Medium**。
- **无远程可利用的鉴权绕过 / 注入 / 私钥泄露**（RPC/WS/P2P/配置/DB 面全部复核，见 §5）。
- 主网/测试网配置仍为 `evm.enabled=false`、全部激活高度 `Long.MAX_VALUE`，**当前线上无 live 漏洞**（U1/U2 例外：它们不受该门控）。

严重度约定：High = 可导致诚实节点分叉/停机/资金不守恒；Medium = 需特定条件或影响集成方正确性；Low = 规范偏差/纵深防御。

---

## 1. 共识 / δ 延迟执行（High）

### C1. ✅ 已修复（2026-09-05：成熟条目归档 0x0C + `rollbackForReorg` 回滚到 lowestUnwound−δ 并重开、`unWindMain` 反转重开高度手续费）— 浅重组（深度 < δ）后，被孤立区块的 `daSkip` 决策被"钉死"，看过孤块的节点永久分叉
`src/main/java/io/xdag/evm/EvmBlockProcessor.java:293-306`，`src/main/java/io/xdag/core/BlockchainImpl.java:1053`
* Severity: **High** · Category: consensus-divergence / reorg-asymmetry · 触发条件: `stateRootLag ≥ 2`（testnet/mainnet=16；devnet δ=1 免疫）
* 描述：成熟高度 M=N−δ+1 在 `setMain(N)` 时按块 N 的 `daSkip` 位**一次性**执行或跳过，并 `removeMaturityEntry(M)`。当 N 被深度 d<δ 的重组回滚时，`unWindMain` 只调 `rollbackTo(lowestUnwound−1)`，M ≤ N−1 的检查点、0x0A skip 标记、0x07 burn、0x0B 手续费日志全部保留，且没有任何代码重建 M 的 0x09 maturity entry。替代块 N′ 到来时 `getMaturityEntry(M).isEmpty() → return ZERO`，N′ 的 `daSkip` 被忽略。设计文档 `2026-08-27-evm-da-skip-marker-design.md` 声称"重组后 daSkip 从规范链重读"——实际 `rollbackTo` 只从 EVM_META 自身回放，从不读区块。
* 失效场景（δ=16 主网）：两个矿工争同一高度 N，A 缺 M 的 blob（daSkip=true），B 有（false）。先导入 N_A 的节点跳过 M（sentinel 根、无收据、无手续费、无 burn）；随后 N_B 胜出，这些节点回滚 N_A 但 M 保持"已跳过"；只见过 N_B 的节点则执行了 M。下一块锚定 root(M)：前者在主网 hard-reject 永久停机；warn-only 网络上则静默携带分歧的原生状态（lock 余额、块 M 的 amount、已释放的提现）。攻击者只需偶尔赢一个块并置 daSkip=true；诚实矿工间的 DA 差异也会自然触发。
* **修复与验证（2026-09-05）**：`EvmMetaStore` 新增 0x0C 归档族（成熟时 0x09→0x0C，保留 max(historyWindow,64) 个高度）与双边界 `removeAbove(exec, native)`（执行产物按 `lowestUnwound−δ` 清扫；0x08 释放日志、0x0B 手续费日志、0x09/0x0C 按 `lowestUnwound−1` 清扫）；`EvmBlockProcessor.rollbackForReorg(lowestUnwound, lag)` 回滚到 `lowestUnwound−δ` 后把 `(lowestUnwound−δ, lowestUnwound−1]` 的归档条目恢复为 0x09；`unWindMain` 在此之前对这些重开高度调用 `reverseEvmFeeCreditOfReopenedHeight`（lock 回补 + 区块 amount/fee 回退）。δ=1 时与旧 `rollbackTo(lowestUnwound−1)` 字节一致。测试：`EvmBlockProcessorTest.a_shallow_reorg_reopens_the_matured_height_for_the_replacement_block`（δ=2：块 3 daSkip=true 跳过高度 2 → 回滚块 3 → 替代块 3′ daSkip=false 执行高度 2，根与只见过替代链的节点一致）、`at_lag_one_the_reorg_rollback_is_the_legacy_rollbackTo`、`EvmMetaStoreTest` 归档/恢复/双边界清扫、`EvmReorgReopenIntegrationTest`（δ=2 真实链：unwind 决定块后高度 K 收据/记录消失、maturity entry 恢复、lock 与块 K 金额回退）。
* 建议（原文）：让 M 的执行成为规范链的纯函数——`unWindMain` 时回滚到 `lowestUnwound − δ`，并从仍规范的区块重建 `[lowest−δ+1, lowest−1]` 的 maturity entries（refs + 门控后的 deposits），同时对这些高度执行 `reverseEvmFeeDebit`/`reverseReleasedWithdrawals`；或改为 maturity entry 按"自身高度"而非"被消费"删除。补 δ=2 测试：N(daSkip=true) → unwind → N′(daSkip=false)，断言 root(M) 等于新同步节点。

### C2. ✅ 已修复（2026-09-05：有 pending 高度时 skip 以空 refs 入队，由 `onBlobsAvailable` 按序 drain）— `skipMaturedHeight` 绕过 pending 排序门，跳过高度先于更早的 blob 延迟高度做检查点，根链顺序被永久破坏
`src/main/java/io/xdag/evm/EvmBlockProcessor.java:318-326` vs `:245`，`:539`，`:636`
* Severity: **High** · Category: consensus-divergence / execution-ordering · 触发条件: δ ≥ 2 且存在一个 blob 延迟高度（正是 DA-skip 机制要容忍的状态）
* 描述：include 路径在 `pendingHeights()` 非空时会 `putPending` 而不执行；skip 路径无此门，直接 `executeAndCheckpoint(List.of(), …)`，而 `executeAndCheckpoint → executeList(…, latestRoot())` 取"最高检查点"。于是 H−1 pending 时，H 从 root(H−2) 链上；H−1 稍后 drain 时又从 root(H) 链上，且此时 H 的存款已先于 H−1 的交易被 mint。
* 失效场景：受害节点 V 缺 T 的 blob → `putPending(100)`；块 116 对 101 置 daSkip=true → V 立刻 checkpoint(101)=keccak(root(99)‖…)，网络其它节点为 keccak(root(100)‖…)。T 到达后 V 的 root(100) 也错。verdict 从 BEHIND 翻成 MISMATCH → 主网 hard-reject 永久停机。攻击者只需把 T 的 blob 只广播给部分节点，再等/挖一个 daSkip=true 的块。
* **修复与验证（2026-09-05）**：`skipMaturedHeight` 在 `pendingHeights()` 非空时写 skip 标记+deposits 后 `putPending(height, …, List.of())`，由 `onBlobsAvailable` 按序 drain，`executeAndCheckpoint` 按标记折叠 sentinel。测试 `EvmBlockProcessorTest.a_skipped_height_waits_behind_a_blob_deferred_height`（δ=2：B 节点高度 1 缺 blob、高度 2 被跳过 → 高度 2 不得先 checkpoint；blob 到达后两高度根与 A 节点一致）。
* 建议（原文）：`processConfirmedBlock` 中若 `daSkip && !pendingHeights().isEmpty()`，只持久化 skip 标记+deposits，并以空 refs `putPending(height, …)` 入队，由 `onBlobsAvailable` 按序 drain（`executeList` 已按标记折叠 sentinel）。补 lag=2 测试。

### C3. ✅ 已修复（2026-09-05，分支 fix/evm-anchor-c3-c4）— 矿工用 `nmain+1` 预测本块高度，与 `checkNewMain` 的确认时机差 1，锚点高度恒比验证方期望少 1
`src/main/java/io/xdag/core/BlockchainImpl.java:1808,1827-1829` vs `:1763-1768`，`:975-1000`
* Severity: **High**（激活 `stateRootActivationHeight` 后无条件触发；主网 = 链停机）· Category: miner-vs-validator asymmetry
* 描述：`checkNewMain` 只在候选块 p 之上**还有**候选块（`i > 1`）时才 `setMain(p)`，所以稳态下最顶端的主链候选总是未确认，`nmain` = 候选数 − 1。`XdagPow.onTimeout` 连接第 n 个 epoch 的块后（此时确认的是 n−1，`nmain=n−1`）立即 `newBlock()` 为 epoch n+1 出模板，模板算 `nextHeight = nmain+1 = n`，但该块最终在高度 n+1 被确认；验证方要求 `anchor.height == height − lag`，于是恒差 1 → MISMATCH。`daSkip` 同理评估错了一个高度。
* 测试为何没发现：`StateRootAnchorRejectTest` 用矿工自己的 `nmain+1` 做期望值（同义反复）；集成测试用 `generateExtraBlock`（不带锚）在 warn-only 的 devnet 上跑，surefire 日志里有 155 条 `anchor mismatch … committed=null … warn-only`，没有一条经自然 `checkNewMain` 路径得到的 MATCH。
* 实证：**已实证（2026-09-04，单节点 devnet，δ=1，`stateRootActivationHeight=0`，warn-only，`target/classes` + Maven classpath 直接跑 `io.xdag.Bootstrap -d`，运行 7 分钟）**：所有经 `createMainBlock` 出块、经 `checkNewMain` 自然确认的主块锚点全部 MISMATCH，零 MATCH。日志（`logs/xdag-info.log`，`BlockchainImpl:1376`）：

  | 确认高度 | committed anchor.height | 验证方期望 (height−lag) | verdict |
  |---|---|---|---|
  | 1 | null（首块无锚） | 0 | MISMATCH |
  | 2 | 0 | 1 | MISMATCH |
  | 3 | 0 | 2 | MISMATCH |
  | 4 | 1 | 3 | MISMATCH |
  | 5 | 2 | 4 | MISMATCH |

  稳态偏差为 2 而非 1：`onTimeout` 内 `tryToConnect` 的 `checkNewMain` 未确认新候选时，模板已用旧 `nmain` 生成，随后周期线程 `check-main-1` 才 `setMain`（日志中 23:33:20.018 出模板 vs 23:33:20.325 setMain(3)）。即偏差不仅存在，还随线程时序在 1–2 之间浮动——不同矿工的偏差可能不同，`nmain` 根本不是可用的高度预测源。主网配置（hard-reject）下第一个带锚区块即停机。
* **修复与复验（2026-09-05）**：规则改为块 N 承诺 `root(N−δ−1)`（`BlockchainImpl.anchoredEvmHeight`，矿工/setMain/导入期三处共用）；矿工先 `settleMainChainConfirmations()` 再按链位置 `predictNextMainHeight(pretop)` 预测高度（`prepareStateRootAnchor`）。新增测试：`StateRootAnchorMinerHeightTest`（预测高度 == 实际确认高度）、`StateRootAnchorNaturalConfirmTest`（hardReject=true 下 12 个矿工式锚点区块经 `tryToConnect → checkNewMain` 自然确认，nmain 持续推进）。devnet 复跑 8 分钟、确认至第 5 主块，`anchor mismatch` 日志 0 条（修复前每块 1 条）。
* 建议（原文）：以主链候选前沿而非 `nmain` 推导模板高度（`nmain + 未确认 BI_MAIN_CHAIN 候选数 + 1`，即 `checkNewMain` 走到的 `i`），锚定 `root(nextHeight−lag)`、对 `nextHeight−lag+1` 评估 daSkip；增加"createMainBlock 出块 → 再连两个候选让 checkNewMain 自然确认 → hardReject=true 下断言 MATCH"的测试。修复前不得在任何 hard-reject 网络上排期 `stateRootActivationHeight`。

### C4. ✅ 已修复（2026-09-05，导入期 INVALID_BLOCK）— hard-reject 没有孤立/替换路径：一个坏锚点的主块让所有 hard-reject 节点永久停机
`src/main/java/io/xdag/core/BlockchainImpl.java:1364-1375`
* Severity: **High**（仅主网 `stateRootHardReject=true`）· Category: crafted-block / permanent halt
* 描述：`verifyStateRootAnchor` 只在 `setMain` 调用（导入/`tryToConnect` 不校验）。锚错误的块被完整接受进 DAG、拿到 `BI_MAIN_CHAIN`、被诚实矿工当 pretop 引用；`setMain` 直接 `return`，不打标、不降难度、不允许兄弟块替代；`checkNewMain` 每个 tick 选到同一个 p。`createMainBlock` 注释中"过期锚点只会得到被丢弃的候选，不会分叉"所依赖的丢弃机制并不存在。
* 失效场景：攻击者赢得任一高度 N（任意算力份额，逐 epoch 重试），把 `rootLow`/`height` 写错（或省略锚点/发 v0 块），所有 hard-reject 节点冻结在 `nmain=N−1`，需人工介入。叠加 C3 则**自发**发生。
* **修复（2026-09-05）**：`tryToConnect` 在任何链接副作用之前调用 `rejectDivergentAnchorAtImport`：对带锚点或带 nonce（矿工候选）的块，按链位置算出确认高度，在该位置已 settle（下方未确认候选 ≤1；hard-reject 网先 settle）且块延伸主链块时计算 verdict，MISMATCH + hardReject → `INVALID_BLOCK`（不入库、不会成为 pretop），warn-only 只记日志，BEHIND/ABSENT/侧链/未 settle 放行由 setMain 复核（setMain 冻结保留为最后防线，日志给出 re-sync 指引）。测试 `StateRootAnchorImportRejectTest`：错根/错高度/无锚矿工块 → INVALID 且兄弟块继续推进链；BEHIND 放行；诚实锚点放行。
* 建议（原文）：在 `tryToConnect` 对将成为主链候选的块（高度 ≥ 激活）计算 verdict，MISMATCH 直接 `INVALID_BLOCK` 拒收（BEHIND 保持接受并延迟）；至少让 `setMain` 的 hard-reject 剥离该块的 `BI_MAIN_CHAIN` 并重跑主链选择。补"更重的坏锚块 + 兄弟块胜出"的测试。

---

## 2. P2P / 交易承载（High / Medium）

### P1. ✅ 已修复（2026-09-05：按内容分类 — `EvmTxStore.decodeBatchBody` 为唯一分类器，P2P 两个 reply 入口统一走 `ingestConsensusBytes`，`expandRefs` 纵深防御）— 批次体（batch body）经 `EVM_TX_REPLY` 注入到 tx 键空间，"毒化"不可逆，受害节点根链分叉
`src/main/java/io/xdag/net/XdagP2pHandler.java:529-552`，`src/main/java/io/xdag/evm/EvmBlockProcessor.java:366-386, 467-476`
* Severity: **High** · **devnet 上 live**（`batchActivationHeight=0`），共享网 latent
* 描述：一个 0x0F ref R 在 0x00（tx）和 0x01（batch）两个键空间都没有内容时为"unknown"。`ingestEvmTxBlob` 对**任何** keccak 命中 `isAwaitingBlob` 的字节直接 `putRaw(txHash, rawRlp)` 进 0x00，而 `isAwaitingBlob` 故意包含 `unknownRefs`。`expandRefs` 按"哪个 store 有"分类：先 `getBatch`（0x01，读时做类型检查），否则 `contains`（0x00，无检查）→ 视为单笔 legacy tx。因此 R 的解释取决于**哪条消息**送来了字节，而不是字节本身。注释"32 字节不可能同时是 keccak(tx) 和 keccak(batch body)"成立但无关：攻击者送的就是真实批次体 B。
* 失效场景：诚实矿工打包 R=keccak(B)。恶意对等体在诚实 batch reply 到达前发 `EvmTxReplyMessage(B)`（未请求的 reply 也被接受）→ 受害者 `putRaw(R,B)`。此后 `expandRefs` 认为 R 是完整的单笔 tx，`isAwaitingBatch(R)` 变 false，诚实 batch reply 被丢弃——毒化粘滞；`rollbackTo` 回放同一毒 blob，永久。执行时 B 解码失败 → `validationFailure("undecodable blob")` 状态 0 收据折入根链，而诚实节点执行了成员 → 主网 hard-reject 停机 / warn-only 下原生 amount、bridge burn 分歧。已毒化的诚实节点还会用 `EvmTxRequest(R)` 的回复把毒继续传播。成本：每个受害者一条消息。
* **修复与验证（2026-09-05）**：`EvmTxStore.decodeBatchBody/isBatchBody`（RLP 列表、1..MAX_BATCH_TXS 个 32 字节项；签名交易永不可能解析为批次体，两键空间按内容不相交）；`XdagP2pHandler.ingestConsensusBytes`：批次形字节仅在 `isAwaitingBatch` 时进 0x01，其它字节仅在 `isAwaitingBlob` 时进 0x00（不可解码字节仍可满足引用，保留"垃圾引用得到确定性失败收据"的活性语义）；`expandRefs` 对 0x00 中批次形字节按批次展开（可自愈修复前被毒化的存储）。测试 `XdagP2pHandlerEvmBatchTest`：批次体经 tx-reply 到达 → 存为批次且成员可展开；tx blob 经 batch-reply 到达 → 存为 tx；垃圾字节仍存为 tx blob。
* 建议（原文）：分类必须只依赖字节内容——ingest 时先解析：能解析为合法批次体（1..MAX_BATCH_TXS 个 32 字节项）的进 0x01，否则进 0x00（batch reply 同样镜像处理）；或在 `expandRefs` 中当 0x01 缺失而 0x00 内容可解析为批次体时按批次处理。补测试：unknown ref 先收 `EvmTxReplyMessage(batchBody)` 再收 `EvmBatchReplyMessage(batchBody)`，断言结果与相反顺序一致。

### P2. ✅ 已修复（2026-09-05：`EvmTxPool.add` 新增 `TOO_LARGE`，Kernel 传入 `evm.maxP2pTxBytes`，RPC 映射 -32602 "transaction too large"）— RPC 可接纳超过 `evm.maxP2pTxBytes` 的交易并被打包，但对等体永远拒收该 blob → 全网 EVM 停滞 + 锚点分裂
`src/main/java/io/xdag/evm/tx/EvmTxPool.java:104-209`（无大小检查），`src/main/java/io/xdag/rpc/server/handler/EthRequestHandler.java:198-237`，`src/main/java/io/xdag/net/XdagP2pHandler.java:533-535`
* Severity: **High**（前提：能向出块节点的 RPC 提交交易；诚实用户的大 calldata 交易也会触发）
* 描述：128 KiB 上限只在 P2P **接收**侧强制（ingest/batch reply）。`EvmTxPool.add` 和 `sendRawTransaction` 都不限大小（P2P 处注释"pool 自身有 size cap"不属实），HTTP 聚合器允许 1 MB，150 KB calldata 的 intrinsic gas ≈ 2.4M 远低于 30M。`processEvmTxRequest` 会把存储的任何 blob 发出去，但每个对等体都在 ingest 处丢弃，`BlobRetryBackoff` 永不放弃。
* 失效场景：向矿工 O 的 RPC 提交一笔有效的 150 KB 交易，O 在高度 H 打包并持有 blob。当 O 挖到成熟 H 的块 N=H+δ−1 时 `maturedPayloadAvailable=true → daSkip=false`；其它所有节点永远 defer H（`onBlobsAvailable` 在第一个不完整高度停止），后续高度全部排队，桥释放进入 CRITICAL 冻结；其它矿工锚定的是过期 floor 根，O 判为 MISMATCH 而 hard-reject 自停。无自动恢复（需全网改 `evm.maxP2pTxBytes`）。
* **修复与验证（2026-09-05）**：`EvmTxPool` 新增 8 参构造（`maxTxBytes`，7 参默认 131072），`add` 在解码前拒绝超限 blob；Kernel 传 `getEvmMaxP2pTxBytes()` 使准入与 P2P ingest 共用同一上限；`EthRequestHandler` 映射 `TOO_LARGE → invalidParams`。测试 `EvmTxPoolTest`（自定义上限与默认上限）、`EthRequestHandlerTest.eth_sendRawTransaction_oversized_tx_errors_with_invalid_params`。
* 建议（原文）：在 `EvmTxPool.add` 加 `rawRlp.size() <= evmMaxP2pTxBytes`（新增 `AddResult.TOO_LARGE`），`selectBatch/selectEvmBatch` 拒绝超限 blob 作纵深防御；`maturedPayloadAvailable` 也应要求每个成员 blob ≤ cap。

### P3. 校验失败收据永久消耗 tx hash：任何矿工可零成本"烧掉"任何待处理交易
`src/main/java/io/xdag/evm/EvmBlockProcessor.java:1050-1053, 781, 515-519`；`src/main/java/io/xdag/core/BlockchainImpl.java:1896-1907`
* Severity: **Medium** · Category: miner-griefing / censorship
* 描述：nonce 不匹配、余额不足等**校验**失败被写成状态 0、gasUsed 0 的收据并 `putReceipt`；dedup 对任何已有收据的 hash 永久跳过，而发送方 nonce 未变。以太坊里无效交易使整块无效，这里则是 hash 被消耗。
* 失效场景：受害者 pending N、N+1；矿工引用 `[N+1]`（或 `[N+1, N]`），N+1 以 nonce mismatch 得到收据，从此在所有节点不可执行；它仍停留在池中该发送方 run 的头部，`selectEvmBatch` 对带收据的 tx 标 `stopped` 跳过整个 run，直至 TTL 过期或用户 RBF。预签名/硬件签名的交易被直接销毁。成本：零 gas、一个块位。
* 建议：校验失败不消耗 hash——digest 仍折入 `(txHash,0,0)` 但记为独立的 "invalid-at-height" 标记且 dedup 忽略之；或直接从执行列表剔除校验失败的 ref（它们不耗 gas、不动状态）。两者跨节点均确定。

---

## 3. 未受 `evm.enabled` 门控、EVM 关闭的滚动升级期即生效（High / Medium）

### U1. `BasicUtils.amount2xdagNew` 从 double 改为精确 BigDecimal，改变了共识路径上的金额换算
`src/main/java/io/xdag/utils/BasicUtils.java:311-316`（commit `7987b414`，仅在 dev-evm，develop 上没有）
* Severity: **High（latent，混合版本网络）** · Category: consensus semantic change in shared utility
* 描述：旧实现 `new BigDecimal(first + tem)` 中 `first + tem` 是 double，`first ≥ 2^21`（≈209.7 万 XDAG）时丢失小数位；新实现精确。该函数经 `XAmount.ofXAmount` 用于**每个链接金额**（`Address.java:151`）、**每次余额读取**（`AddressStoreImpl.java:78,94`）、快照导入、`getSupply`。本人数值复现（每档 20000 个随机金额）：

  | first (XDAG) | 结果不同的比例 | 最大差值 |
  |---|---|---|
  | 1000 / 2^20 | 0 | 0 |
  | 2^21 | 11.2% | 1 nano |
  | 3,000,000 | 11.7% | 1 nano |
  | 10,000,000 | 46.3% | 1 nano |
  | 2^30 | 99.6% | 120 nano |

* 失效场景：滚动升级期间（计划先带 EVM-off 发布）任何持仓 ≥ 2.1M XDAG 的地址（交易所、大矿池）做接近全额的转账：旧节点算出的余额低 1+ nano 而拒绝应用，新节点应用 → 余额及后续可花性在两群节点间永久分歧。无需攻击者。
* 建议：恢复与旧版本位级一致的算法（精确版仅用于展示路径，或以分叉高度门控），加回归测试固定 `first ≥ 2^21` 的旧输出；把 `ofXAmount/toXAmount` 视为共识冻结。

### U2. `Block.parse` 对 transport 头字节 0 ≥ 1 的块把 0x0A 字段当锚点解析并在畸形时抛异常，新节点拒收旧节点接受的块
`src/main/java/io/xdag/core/Block.java:297, 312-318`；`src/main/java/io/xdag/core/EvmStateAnchor.java:79-86`
* Severity: **Medium-High（latent，混合版本网络）** · Category: cross-version split
* 描述：`blockFormatVersion = transportHeader & 0xFF`；≥1 时 `EvmStateAnchor.parse` 对未知 flag 位/负高度抛 `IllegalArgumentException`。`NewBlockMessage`/`SyncBlockMessage` 构造时即 `new Block(xdagBlock)` 解析，异常被 `MessageFactory` 包成 `MessageException`，块被丢弃。master 对未知字段类型 `default -> {}` 忽略。两个版本导入时都不校验字段类型。transport 头被哈希（不可被中继篡改）但由出块者自由选择。
* 失效场景：任何人广播一个自签 tx 块，transport 字节 0=0x01、含一个 flags=0x02 的 0x0A 字段。旧节点收入 DAG，旧矿工的主块引用它；新节点每次收到都抛异常，永远补不到该引用，卡在该主块之后（每次 BLOCK_REQUEST 同样失败）。
* 建议：激活前对 0x0A 字段宽松解析（未知 flag/负高度 → 视为"无锚"，交给 `stateRootActivationHeight` 之后的 verdict 逻辑判 MISMATCH），或把版本字节重解释门控在 `isEvmEnabled()` + 激活高度上；加混合版本解析测试。

---

## 4. EVM 语义 / 执行上下文（确定性偏差，所有节点一致，但会让合约行为错误）

### E1. `SELFDESTRUCT` 从不删除账户 — Medium
`src/main/java/io/xdag/evm/XdagEvmExecutor.java:233-254`；`EvmBlockProcessor.java:963-984`。执行器只驱动 Besu 的 message processor；账户删除属于 transaction processor 的职责（`initialFrame.getSelfDestructs() → deleteAccount`），本项目无任何调用（`grep getSelfDestructs src/main` 为空；`deleteAccount` 仅在 `RocksDbWorldUpdater` 定义）。结果：`selfdestruct` 后代码、存储、nonce 全部保留，余额清零，合约仍可调用，地址永不能被 CREATE2 重建。建议：成功路径 `parent.commit()` 前 `frame.getSelfDestructs().forEach(parent::deleteAccount)`（并按 EIP-161 清空 touched-empty 账户），分叉门控。

### E2. `BASEFEE` 异常停机；`GASPRICE`=0；`BLOCKHASH`=0 — Medium
`EvmBlockProcessor.java:937-940`（`SimpleBlockValues` 从不 `setBaseFee`，Besu `BaseFeeOperation` 在 `baseFee.isEmpty()` 时返回 `INVALID_OPERATION`，两路子任务均以 javap 验证）；`XdagEvmExecutor.java:217`（`.gasPrice(Wei.ZERO)`，而发送方实际按 `effectiveGasPrice` 付费）；`:227`（`blockHashLookup → Hash.ZERO`，注释"Sub-project B/C 提供真实查找"从未兑现）。审计简报 §7 写的"BASEFEE 读 0"**不正确**（是 revert 并耗尽 gas）。ERC-4337 EntryPoint（`getUserOpGasPrice` 用 `block.basefee`）、gas 返还中继等确定性失败。建议：`setBaseFee(Optional.of(Wei.ZERO))`、传入 `tx.getEffectiveGasPrice()`、按高度接入主块哈希；均改变收据/根，需分叉门控。

### E3. `eth_call` / `eth_estimateGas` 使用全零区块上下文 — Medium（功能）
`XdagEvmExecutor.java:118, 170-172`：`new SimpleBlockValues()` + `Address.ZERO` → `block.number=0`、`block.timestamp=0`。任何时间/高度相关逻辑的模拟结果与链上不一致（deadline 检查、估算 gas 错误）。建议：传入 head 的高度与时间戳。

### E4. 合约创建规则列表为空：EIP-170（24 KiB 代码上限）与 EIP-3541（`0xEF` 前缀）未强制 — Low
`XdagEvmExecutor.java:77` `new ContractCreationProcessor(evm, true, List.of(), 1L)`。以后补上是硬分叉。建议：`List.of(MaxCodeSizeRule.from(evm), PrefixCodeRule.of())`。

### E5. 嵌套帧的 `getOriginalStorageValue` 返回父帧**当前**值而非交易起始值 — Low
`src/main/java/io/xdag/evm/state/RocksDbAccount.java:127-129`：`return parent.getStorageValue(key)`。子调用中的 SSTORE 计价/退款偏离 EIP-2200/3529（受 gasUsed/5 上限约束，不可牟利）。建议：在交易级账户保留 original 快照或递归到根。

### E6. `evm.minGasPrice`、`evm.blockGasLimit`、`stateRootLag`、`bridgeWithdrawalDelay`、`alloc`、`bridgeRecoveryAddress` 等为节点本地 HOCON，却决定收据状态/预算/`GASLIMIT` 操作码/根链 — Low（设计）
`EvmBlockProcessor.java:916, 770, 940`；`AbstractConfig.java:554-585`。两个节点任一参数不同即根链分叉（如一方调高 minGasPrice 反垃圾）。且 `stateRootLag`、`blockGasLimit`、`chainId` 等未在加载时校验（lag=0 会在 `setMain` 内抛异常）。建议：作为不可覆盖的网络常量固化到 `EvmSpec`/`TestnetConfig`/`MainnetConfig`，或在握手中交换 genesis-params 摘要；启动时对覆盖值 fail-fast。

---

## 5. 桥 / 手续费（Medium / Low）

### B1. 异步 blob-drain 的延迟记账使落后节点拒绝其它节点接受的块 M 支出，且永不重评 — Medium
`BlockchainImpl.java:2725`（`onEvmBlobsAvailable → creditEvmFee`）、`:1149`（`applyBlock` 余额不足 → `return ZERO`，仅标 `BI_MAIN_REF`）。K1 让落后节点**最终**收敛到相同 `block.info.amount`，但在 `setMain(M+δ−1)` 与 drain 之间，矿池 `payPools` 按全额支出块 M 的 amount，落后节点以余额不足拒绝该支付块，之后不再重评。提现路径在同样情形下会 CRITICAL 冻结，手续费路径则静默分叉，且原生余额不被锚定。建议：(a) 经异步 drain 执行的高度不记手续费（同步路径在 defer 时也烧掉），或 (b) 与释放路径一致：CRITICAL + 要求重同步。

### B2. burn 扫描不检查收据状态 — Low（纵深防御）
`EvmBlockProcessor.java:783` 对每个收据无条件 `collectBridgeBurns(receipt.logs(), …)`；`receiptOf` 无论 success 都拷贝 logs。Besu 的 revert/exceptionalHalt 会 `clearLogs()`，唯一绕过路径是 `XdagEvmExecutor.runToHalt:243-252` 的 S-26 `catch (RuntimeException)` 直接置 `EXCEPTIONAL_HALT` 而不清 logs（子 updater 未提交，wei 未动，但 burn 会被记录并在 W 后从 lock 释放）。可达性未证明。建议：`collectBridgeBurns` 要求 `status==1`；S-26 catch 中 `clearLogs()`。

---

## 6. RPC / WS（对集成方返回错误数据；无鉴权/注入问题）

| # | 发现 | 位置 | 严重度 |
|---|---|---|---|
| R1 | `eth_blockNumber`/`latest`/`eth_getLogs`/`eth_getBlockByNumber` 用**原生** head，而收据/交易列表/日志在 δ−1 个块后才写入；状态读取却用 executed head。δ=16 时每次扫描最顶 15 个高度返回空且以后不再重扫 → 索引器/交易所永久漏掉存款日志；`eth_getBalance(addr,"0x<eth_blockNumber>")` 返回 -32000 | `EthRequestHandler.java:126,344,372,415-428`；`HistoricalStateReader.java:73` | Medium（latent δ≥2） |
| R2 | `eth_getLogs` 忽略 EIP-234 `blockHash` 过滤键，退化为 from=to=head 并成功返回 head 的日志 | `EthRequestHandler.java:416-418`；`LogFilter.java:69` | Medium |
| R3 | `eth_getTransactionCount(addr,"pending")` 不看交易池；池条目只在 admission 时按 executed nonce 修剪，`remove` 无调用方。ethers/viem 连续两笔同 nonce → 第二笔 REPLACED 静默丢第一笔（δ≥2 时第一笔已上链但未执行，第二笔上链后 nonce mismatch 并被 P3 烧掉） | `EthRequestHandler.java:134-138,372`；`EvmTxPool.java:181-188` | Medium |
| R4 | 收据 `logIndex` 从 0 按交易计数（`buildLogs(…,0)`），`eth_getLogs`/WS 按块计数；`cumulativeGasUsed = gasUsed` | `EthRequestHandler.java:327`；`EthObjects.java:112` | Medium |
| R5 | `eth_estimateGas` 单次模拟（gas=cap）返回 `intrinsic+gasUsed`，无 EIP-150 63/64 余量，含子调用的交易按估算值执行会 OOG | `EthRequestHandler.java:158-167` | Low |
| R6 | 区块/收据 `logsBloom` 恒为零（EVM_META 0x05 已存真实 bloom） | `EthObjects.java:117,133` | Low |
| R7 | 不支持 JSON-RPC 批量数组（ethers v6 默认批量）；revert 数据只在 `message` 无 `data` 字段（钱包无法解码自定义错误）；`newHeads` 推送占位头；WS 升级要求 `Authorization` 头（浏览器无法设置） | `JsonRpcHandler.java`；`RpcWebSocketFrameHandler.java:62`；`SubscriptionManager.java:100-102`；`RpcWebSocketServer.java:97` | 功能缺口 |

---

## 7. 功能完整性评估

### 7.1 已实现（与设计文档一致）
Besu-EVM Shanghai（PUSH0）、预编译 0x01–0x09、EIP-155/2/2718/1559(type-2)/3529/3860、RocksDB 世界状态 + 链式 delta 根 + 128 高度历史窗口、payload-by-reference + 0x0F 批次承诺 + 5 个 P2P 消息码、EVM 交易池（nonce 链、RBF、公平驱逐）、双向桥（remark 存款 / burn 日志提现 / 全有或全无释放 / 重组日志）、PoW 锚定的状态根 + δ 延迟执行 + DA-skip、手续费 transfer-from-lock、genesis alloc 种子、21 个 `eth_*/net_*/web3_*` 方法 + WS `newHeads`/`logs` 订阅。共 486 个 EVM 相关测试方法、20 个 evm 包测试类。

### 7.2 RPC 方法矩阵（钱包/浏览器视角）
- 完整：`eth_chainId` `net_version` `web3_clientVersion` `net_listening` `eth_getBalance` `eth_getCode` `eth_getStorageAt` `eth_gasPrice` `eth_maxPriorityFeePerGas` `eth_sendRawTransaction`（type-0/2）`eth_getBlockByNumber` `eth_getBlockByHash` `eth_accounts`(空)。
- 部分：`eth_blockNumber`(R1) `eth_getTransactionCount`(R3) `eth_feeHistory`(静态) `eth_estimateGas`(R5/E3) `eth_call`(E3/无 data) `eth_getTransactionByHash`(仅已上链) `eth_getTransactionReceipt`(R4/R6) `eth_getLogs`(R2) `eth_subscribe`(仅 newHeads/logs)。
- 缺失：`eth_syncing`、`eth_newFilter/getFilterChanges/uninstallFilter`、`eth_getBlockTransactionCountBy*`、`eth_getTransactionByBlock*AndIndex`、`eth_getBlockReceipts`、`txpool_*`、`debug_*/trace_*`、`net_peerCount`、`eth_getProof`（设计上不支持）。
- 客户端适配：MetaMask 可用；hardhat/ethers 需关闭批量；Blockscout 类浏览器被 `eth_syncing`/filters/`cumulativeGasUsed` 阻塞。

### 7.3 桥的功能缺口
- 无任何用户侧工具生成 remark（`tools/hardhat-e2e`、`tools/local-explorer` 均无桥代码），`xdag_sendTransaction` 不校验 remark，`Block` 静默截断 remark 到 32 字节 → 打错字的存款进 recovery 地址。
- devnet `bridgeRecoveryAddress = 0x7e5f…bdf` 是私钥=1 的公开测试账号（仅 devnet，但该配置模式不可复制到共享网）。
- 提现释放直接改 `AddressStore`，无载体块、无 TXHISTORY/MySQL 记录、`xdagStats.balance` 不更新 → 钱包/浏览器看到无来源的余额跳变。系统合约 ABI 只存在于测试资源 `src/test/resources/solidity/xdag_bridge.sol`。
- 共享网参数（δ=16、W=16、64 s epoch）下：首个收据 ≈17 分钟、桥出金端到端 ≈32 分钟，用户文档未说明。

### 7.4 文档与代码不一致
审计简报 §7 "BASEFEE 读 0"（实为异常停机）；§5 不变量 11"激活前字节一致"被 U1/U2 打破；简报未披露 EIP-170/3541 未强制、`eth_call` 零上下文；DA-skip 设计文档"重组后 daSkip 从规范链重读"不成立（C1）；`EvmBlockProcessor.java:67-69,372` 仍称手续费被 burn（已改为 transfer-from-lock）；`XdagEvmExecutor.java:261-263` 仍称 intrinsic gas 未扣（已在 `executeOne` 扣）。

### 7.5 测试覆盖缺口
`AuthHandler` 在 `src/test` 零引用（HTTP/WS bearer 鉴权未测）；无 δ≥2 下的重组 + daSkip 测试（C1/C2）；无经 `checkNewMain` 自然确认的锚点 MATCH 测试（C3）；无坏锚块被兄弟块替代的测试（C4）；无混合版本区块解析 / 旧金额算法回归测试（U1/U2）；无操作码语义测试（`BLOCKHASH/BASEFEE/COINBASE/PREVRANDAO/GASPRICE`）；无 JSON-RPC 批量、`eth_getBlockByHash`、WS 鉴权测试。`EvmConfig` `EvmReceipt` `EvmStateSchema` `EvmSubscriptionSink` `BridgeConstants` `EthObjects` 无专属测试。

### 7.6 分网络结论
- **devnet（单运营者，δ=1）**：功能完整可用；已知偏差 E1–E3、R2–R4、P1（live）。
- **testnet 激活**：**未就绪**。前置：修复 C1–C4、P1–P2、U1–U2（U1/U2 必须在**排期任何高度之前**随 EVM-off 版本发布到所有节点）；决定 E1/E2/E4（激活后再改是硬分叉）；把共识参数固化到网络配置（E6）；注册 chainId 51965/51964；发布桥 ABI + remark 编码器并给释放一条可见交易记录；R1–R4、批量 RPC、revert `data`；EVM_META/0x60 标记恢复与快照引导 runbook；外部审计一轮。
- **mainnet 激活**：以上全部 + 外部审计修复验证标签 + 赏金 + 真实 δ=16 多节点 testnet 浸泡（钱包/浏览器 UX 需按 17 分钟收据/32 分钟出金设计）+ 浏览器/索引器所需 RPC + 链式 delta 根（无证明/轻客户端）与固定费率市场的产品决策 + 7.5 的覆盖补齐。

---

## 8. 已复核为正确的关键不变量（摘录）
- 链式根与 state delta 摘要与迭代顺序无关（`Arrays.compareUnsigned` 排序、长度前缀、put/delete 域分隔）；无 HashMap 顺序、无墙钟进入任何哈希/收据/根。
- 手续费数学：affordability=`value+feeCap×gasLimit`，预付 `effective×gasLimit`，退款 `(gasLimit−netGasUsed)×effective`，EIP-3529 上限 `min(refund, gross/5)` 仅在激活后且 status==1 应用；revert/OOG 仍收费并递增 nonce。
- RLP（Besu 26.5.0 `shouldFitExactly=true`）：拒绝尾随字节、前导零、非规范长度；`to` 必须 20 字节；tx hash=keccak(精确接受的字节)。签名：`1≤r,s<n`、low-s（legacy+type-2）、`yParity∈{0,1}`、legacy `v<35` 拒绝、EIP-155 chainId 在 admission 与执行两处校验。
- 内容寻址：blob/batch 的 keccak 在存储**前**计算，仅 awaiting（pending∪buffered）的 ref 被存储；batch 体按精确字节服务/存储，成员顺序=矿工顺序。
- 存款检测：仅完整校验后的 OUTPUT 对 `LOCK_ADDRESS_20` 的信用、按确认高度门控、remark 校验和 `keccak(0x45‖addr)[0:2]` 绑定目标、0x06/0x09 随 `removeAbove` 清扫 → 重组/重启不会双重 mint。
- 提现释放：0x08 日志、lock 不足时全有或全无、逆序反转、`W ≥ δ−1` fail-fast、同一 `setMain` 内先成熟后释放。手续费：debit lock → 0x0B → credit；`reverseEvmFeeDebit` 在 `unSetMain` 之前；亚 nano 尘埃烧毁；A1 守卫与 lock 不足跳过均确定。
- `eth_call/estimateGas` 永不 commit（`simulate*` 传 `commit=false`，历史 overlay 的 put/delete 抛异常）；gas 上限 `requireGasWithinLimit`。
- HTTP 鉴权逐请求、位于 CORS 之后、`MessageDigest.isEqual` 常量时间；WS 升级 GET 经同一 `AuthHandler`；CORS 精确匹配才反射 Origin，`*` 不带凭证；无批量数组（不可绕过方法白名单）；Jackson 无默认多态类型；订阅按 Channel 隔离、id 为 16 字节 SecureRandom。
- `TransactionHistoryStoreImpl` 全部 PreparedStatement、不含 EVM 数据；钱包/CLI/Shell 改动不打印密钥；`kryo.setRegistrationRequired(true)` 覆盖全部序列化类型；testnet/mainnet 门控链（Kernel → RpcHandlers → BlockchainImpl → bridge 高度）完整，无默认凭证。
- `GenesisLockSeeder` 标记门控、ADD 而非 SET、整 nano 双重校验；`getSupply` 同门控加 premine；锁序 Blockchain monitor → EvmBlockProcessor monitor 在 `setMain/unWindMain/onEvmBlobsAvailable/createMainBlock` 一致，无反向边。
- RandomX 改动仅加 per-slot monitor，不影响输入/输出；握手改动仅加边界检查；消息长度字段为 ≤28 位 VLQ 且有 `require` 边界，无"接受但损坏"路径。

## 9. 建议的修复优先级
1. **激活前必须**：C3（锚点高度）→ C4（hard-reject 替换路径）→ C1/C2（δ-lag 重组与排序）→ P1（内容分类）→ P2（池大小上限）→ U1/U2（门控/回退，随 EVM-off 版本先发）。
2. **激活前应当**（否则日后为硬分叉）：E1 SELFDESTRUCT、E2 BASEFEE/GASPRICE/BLOCKHASH、E4 EIP-170/3541、E6 参数固化。
3. **集成质量**：R1–R4、E3、P3、B1、批量 RPC、revert data、桥工具与文档。
