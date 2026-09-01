# XDAG EVM 主网上线就绪与安全审计范围

> 适用版本：`dev-evm` 分支。本文是**缺陷 5**（"主网未开、上线审计闭环未建立"）的落地产物，
> 作为第三方安全审计的入口文档与 `evm.enabled = true` 于共享网络前的门槛清单。
> 交叉引用：[智能合约设计与实施](smart-contract-design-and-implementation.md)（下称"设计文档"，本文 §x.y 均指其章节）。
> **审计基线（code freeze）：tag `evm-audit-freeze-1` = commit `3bbfc68f`（2026-09-01，全量 633 测试绿 + license:check 通过）。英文审计入口（面向审计机构，自包含）：[`docs/audit/2026-09-01-xdag-evm-external-audit-brief.md`](../../docs/audit/2026-09-01-xdag-evm-external-audit-brief.md)。**
> **本文只覆盖"工程硬门槛 + 审计范围"（上线的必要条件）。完整的主网上线排期 + 生态配套（钱包/浏览器/桥 UI/RPC/dApp demo/文档/运营）见 [主网上线与生态建设详细计划](evm-mainnet-launch-and-ecosystem-plan.md)；本文的 §3 硬门槛即该计划的 Track A 关键路径。**

---

## 0. 用途与读者

- **第三方审计方**：从 §2 的审计范围切入——每个重点面给出"关注问题 + 关键代码位置 + 已声明不变量"。
- **发布负责人**：按 §1 的六步流程推进,以 §3 硬门槛与 §6 自检清单作为放行 gate。
- **运维/配置**：§5 是缺陷 4 的配置就绪现状与启用前必改项。

本文只做"就绪与审计范围"的组织；机制细节一律回指设计文档,不重复正文。

---

## 1. 上线流程门槛（缺陷 5 的六步闭环）

严格说缺陷 5 的主体是**流程门槛**而非代码：其中 ②③④ 依赖外部方（审计机构、公测参与者），无法在仓库内闭合。

| # | 步骤 | 性质 | 当前状态 |
|---|------|------|----------|
| ① | `dev-evm` 合入 `develop`,JDK 21 全量测试进 CI 门禁 | 仓库内 | **就绪**：合并无冲突（§7）；CI 已加全量测试 job（§4）。待发起合并 |
| ② | 第三方安全审计（范围见 §2） | 外部 | **本次启动（2026-09-01）**：冻结 tag `evm-audit-freeze-1` 已推送，英文 brief 已就绪（`docs/audit/`）；待选定审计机构 |
| ③ | testnet 公测周期 + bug bounty | 外部 | 待启动——testnet 配置已 scaffold（§5） |
| ④ | 审计报告与修复清单归档进 repo | 外部产物入库 | 待 ② 完成 |
| ⑤ | §13.3 三项硬门槛全部关闭 | 仓库内（需硬分叉） | **✅ 已关闭（2026-08-26/28/30）**：Gate 1（57e9ebb4/f5582ee8/b92993cb/3428ccc1）、Gate 2（2506b6c4/c090dc2f/75dfdaaa/5d9cfbb1/4557113a）、G3-T1/T2/T3（807a7079/dc30a3b7/aaa38a7b）；另 A4-full transfer-from-lock（3bbfc68f，2026-09-01）关闭费用守恒 |
| ⑥ | 定 mainnet `activationHeight` + `evm.enabled = true` | 配置 | 待 ①–⑤ 全绿 |

**放行顺序**：⑤ 已关闭；② 本次启动（代码已冻结于 `evm-audit-freeze-1`）；① 待发起（发布负责人决策，见 §7）；⑥ 是终点。

---

## 2. 第三方审计范围（重点面）

优先级从高到低。每个面给"审计问题清单 → 关键代码 → 已声明不变量（设计文档 §12 编号）"。

### 2.1 共识三挂载点（设计文档 §5.3）— **最高优先级**

EVM 通过 `applyBlock`（收集）/ `setMain`（执行）/ `unWindMain`（回滚）三点接入 XDAG 共识,是"DAG 偏序 → EVM 全序"的唯一桥。

- 审计问题：`evmRefs` 顺序是否严格 = `applyBlock` DFS 序,全网一致？两处 `BI_APPLIED` 落点是否都 collect？EVM 是否**只**在 `setMain` 执行(绝不在导入/孤块/分叉块)？EVM 异常能否逃逸进共识砸停节点？
- 代码：`core/BlockchainImpl.java`（applyBlock `:1051-1203` / setMain `:1302-1344` / unWindMain `:994-1035`）、`evm/EvmBlockProcessor.java`（processMainBlock/executeList/executeOne）。
- 不变量：§12.1（只在 setMain 执行）、§12.2（DFS 序）、§12.3（异常三道防线：executeOne C1、runToHalt 闸、selectEvmTxRef try/catch）、§12.14（nonce 语义）。

### 2.2 reorg 回滚对称性（设计文档 §5.6）+ bridge unwind — **最高优先级**

- 审计问题：`rollbackTo` 的"擦盘重放"是否与正向执行逐字节一致(重放根 == 存根)？创世标记与 reset/重放的顺序是否正确？bridge 出金的 `unWindMain` 逐高度反转是否精确反转"实际做过的事"(0x08 日志)、入金 mint 是否随 reorg 对称回滚？N 个主块回退是否只触发一次擦盘(O(n) 非 O(n²))？
- 代码：`EvmBlockProcessor.rollbackTo`、EVM_META 0x06(入金)/0x07(出金记录)/0x08(释放日志)、`RocksdbKVSource.reset()`。
- 不变量：§12.7（收据/反向索引随 reorg 同删）、§12.8（创世标记与擦盘顺序）、§12.5（只有 root updater 摘要）。

### 2.3 P2P blob / 批次 DoS 面（设计文档 §7）

- 审计问题：`ingestEvmTxBlob` 的 `isAwaitingBlob` 闸是否是 unsolicited-blob 磁盘 DoS 的唯一防线且无绕过？`maxP2pTxBytes`(128KB) 尺寸闸是否覆盖全部入口？新消息码(0x1B–0x20)是否都进 `channelRead0` 路由？停摆队列(I4)是否是"落后"而非"分歧"？
- 代码：`net/XdagP2pHandler.java`（channelRead0 路由 `:221`、ingest/request `:404-533`）、`net/message/p2p/Evm*Message.java`。
- 不变量：§12.11（只存正在等的 blob）、§12.12（新码必进路由）、§12.13（UNKNOWN ≠ DIVERGE）。

### 2.4 模拟路径不落盘（设计文档 §8 / §12.10）

- 审计问题：`eth_call` / `eth_estimateGas` 是否**永不**触达 `parent.commit()`？（旧版曾是 HIGH：未认证 eth_call 绕过共识改盘上状态。）simulate 双入口是否是唯一路径？
- 代码：`rpc/server/handler/EthRequestHandler.java`（simulate `:467-472`）、`evm/XdagEvmExecutor.java`（simulateCall/simulateDeploy）。
- 不变量：§12.10（eth_call/estimateGas 永不落盘）。

### 2.5 bridge 双向原子性与守恒（缺陷 3）

- 审计问题：入金 1 nano = 10⁹ wei 是否无损、非法 remark 是否确定性路由到 recovery 地址(全网一致)？出金锁不足时"all-or-nothing 整高度跳过"是否不可被部分释放、不重试？成熟延迟 N 是否足以吸收 reorg？桥锁地址无私钥不可花？系统合约字节码 codeHash 固定(0x80d4…2c78)？总量守恒是否 EQUALITY(非 ≤)？
- 代码：`evm/bridge/*`、`EvmBlockProcessor`（mint-before-txs、burn 扫描、成熟释放）、`config/AbstractConfig.validateBridgeConfig`。
- 关注：出金锁不足/EVM 停摆滞后均打 CRITICAL——审计需确认这是共享网前提(§13.3-2 DA 硬门槛)下的正确降级,而非静默吞没。

### 2.6 gas 结算：预扣/退款/燃烧（设计文档 §5.4）

- 审计问题：预扣 `maxFee` 是否先于子 updater 快照(否则退款写回丢扣款)？revert/OOG 是否照收净费、涨 nonce？`adjustBalance` 下溢是否被 C1 拦截？type-2 费用语义(effective = min(maxPriorityFee, maxFee) at baseFee≡0)是否精确、type-0 逐字节不变？
- 代码：`EvmBlockProcessor.executeOne`（`:420-525`）、`evm/tx/EvmTransaction`（type-2 费用分解）。
- 不变量：§12.9（预扣先于子 updater 快照）、§12.14（nonce 语义）。

### 2.7 交易层编解码（设计文档 §4）

- 审计问题：EIP-155 拒无保护交易(v<35)、EIP-2 low-s 强制(防延展性,同一笔只有一个合法 hash)、EIP-2718 type-2 信封分派(拒 type-1)、intrinsic gas(EIP-2028/3860 上限)是否与以太坊一致、跨客户端 hash/sender 一致(ethers 固定向量已钉)？
- 代码：`evm/tx/EvmTransaction.java`（decode/signingHash/getSender/getHash）、`evm/tx/IntrinsicGas.java`、`evm/EvmAddress.java`。

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

---

## 3. §13.3 主网硬门槛（阻塞清单）— 关闭前 `evm.enabled` 不得于共享网络置 true

> **决策门已全关（2026-08-24）**：ADR-013…016 已签署。**三门槛工程已全部关闭（2026-08-25 → 08-30）**，打包同一 `activationHeight` 的键石（G1-T1 块格式）已交付；另 A4-full（费用守恒 transfer-from-lock）于 2026-09-01 关闭。以下保留原编号，标注关闭状态：

1. **PoW 承诺的状态根**：当前链式 delta 根仅靠**链下 gossip(0x1E)检测**分歧,不进 PoW 承诺、不硬拒块（4-bit 字段码位已耗尽,设计文档 §1.1/§6.3）。**已裁定（ADR-013/014）**：块格式硬分叉腾码位,新增 `EVM_STATE_ROOT` 字段锚定链式 delta 根 `(height, root(H−δ))`；δ=`evm.stateRootLag`（建议 16）；分歧 mainnet 硬拒 / testnet 先告警；绝对状态根 MPT 排到主网后 fork。**✅ 已关闭**（G1-T1…T4：57e9ebb4 / f5582ee8 / b92993cb / 3428ccc1）。
2. **DA 强制**：blob 真不可得时 EVM **停摆等待**(诚实但有活性风险,设计文档 §5.5/D10)。**已裁定（ADR-015，方案 B）**：矿工在块内提交 EVM include/skip 标记（与 G1-T1 共用块格式），include 则必须已让 blob 可得否则块无效,skip 全网确定性跳过。**这是 bridge 出金 CRITICAL-skip 降级(§2.5)的前提门槛**——B 落地后该降级变为确定性有界路径。**✅ 已关闭**（G2-T1a/b/c + T2 + T3：2506b6c4 / c090dc2f / 75dfdaaa / 5d9cfbb1 / 4557113a）；bridge CRITICAL-skip 降级已转为确定性有界路径（freeze→re-sync 语义）。
3. **费用路由与经济参数**：**✅ 已关闭**——净费路由到矿工奖励池（G3-T1，807a7079）并升级为 **transfer-from-lock**（A4-full，3bbfc68f：锁→矿工转移非铸造，getSupply 精确）；异步 drain 收敛（K1，b8c72862）；EIP-3529 精确退款（G3-T2，dc30a3b7）；mempool per-sender 公平驱逐（G3-T3，aaa38a7b，节点本地）。经济参数本身（blockGasLimit/minGasPrice 真实共识值）仍属 §5 启用前必改项。

> chainId 注册与激活高度见 §5 与设计文档 §13.1 缺陷 4——属配置/流程,非本节工程门槛。

---

## 4. §13.2 已知限制（审计需知情，devnet 可用、非 devnet 阻塞）

- 状态根是链式 delta 承诺而非绝对状态 MPT：无 `eth_getProof`/轻客户端；跨节点一致性靠 gossip **检测**。
- `GASPRICE` 读 0；`BLOCKHASH/PREVRANDAO/BASEFEE/COINBASE` 皆 0（确定性占位,无随机性）。
- `getLogs` 扫描范围 ≤ `maxLogScanRange`(1024)；历史状态查询仅覆盖最近 `stateHistoryWindow`(128) 个高度。
- type-2 第二步(真 EIP-1559 base fee 市场)未实现：`baseFee≡0`,拥堵靠 `minGasPrice` 一刀切（设计文档 §13.1 缺陷 1）。
- A4-full 快照引导 caveat：快照引导的节点必须随快照携带 ADDRESS-CF 创世播种标记（0x60），否则会在快照余额之上重复播种锁（共享网当前计划 alloc 留空，天然规避）；写入运维/恢复文档前审计需知情。

---

## 5. 配置就绪（缺陷 4 现状）

- **chainId 已定**（保留值,启用前须在 ethereum-lists/chains 查重注册）：mainnet **51964**(0xCAFC)、testnet **51965**(0xCAFD)、devnet 51966(0xCAFE)。运行时以各网 HOCON `evm.chainId` 为准；`EvmConfig` 常量为测试/工具默认。
- **testnet / mainnet 已 scaffold 但保持关闭**：`evm.enabled = false`；四个 fork 门(`activationHeight`/`batchActivationHeight`/`type2ActivationHeight`/`bridgeActivationHeight`)全 = `Long.MAX_VALUE`（未排期）。`activationHeight` 显式钉 MAX_VALUE(而非默认 0),防未来 `enabled=true` 静默于高度 0 激活。
- **启用前必改项(共享网)**：
  1. 在 ethereum-lists/chains 注册 chainId 确认无碰撞；
  2. 定 `evm.blockGasLimit` **真实共识值**（EVM 在 `setMain` 同步执行,当前 30M 为 framework 占位）；
  3. 复核 `evm.minGasPrice`（当前 1 gwei 占位）；
  4. 排期各 `*ActivationHeight`；
  5. 若启用 bridge：设 `evm.bridgeActivationHeight`(≥ `evm.activationHeight`)、`evm.bridgeRecoveryAddress`、`evm.bridgeWithdrawalDelay`(建议 16)——配置校验会 fail-fast。
  6. 费用路由与桥耦合：`feeRewardActivationHeight` 已被配置校验强制要求同时排期 bridge（transfer-from-lock 的锁来源）；排期时两者一并规划。

---

## 6. 审计前就绪自检清单

- [x] 合并无冲突,可 fast-forward（§7）
- [x] CI 快门禁（JDK 21 `mvn clean package`,PR→develop/master）
- [x] CI 全量测试 job（含被排除的长测,nightly + 手动触发）
- [x] 缺陷 4 配置 scaffold + chainId 保留值 + 测试钉住
- [ ] `dev-evm` → `develop` 已合并
- [x] §3 硬门槛-1（状态根 PoW 承诺）已关闭（2026-08-26）
- [x] §3 硬门槛-2（DA 强制）已关闭（2026-08-28）
- [x] §3 硬门槛-3（费用路由/配额/退款）已关闭（2026-08-30；A4-full 守恒 2026-09-01）
- [x] 内部对抗性审计（2026-08-30，4 路）发现已修复归档：`docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`
- [x] 审计冻结基线已推送：tag `evm-audit-freeze-1` = `3bbfc68f`（全量 633 测试绿）
- [ ] 第三方审计报告归档
- [ ] testnet 公测周期完成
- [ ] chainId 已在 ethereum-lists/chains 注册

---

## 7. 合并信息（dev-evm → develop）

- **领先**：`dev-evm` 领先 `develop` 263 个提交,落后 0；本地 `develop` 与 `upstream/develop` 一致。
- **冲突**：`git merge-tree` 无冲突标记——可 fast-forward；按 gitflow 建议 `--no-ff` 保留特性分支合并点。
- **改动面**：228 文件 +35134/−361（EVM 栈 `io.xdag.evm.*` + 共识三挂载 + P2P 6 码 + RPC eth_* + 配置 + 测试 + 文档 + local-explorer 工具）。
- **合并动作**：由发布负责人确认后执行（本仓库 gitflow：`develop` 无直接提交,走 PR)。
