# XDAG 原生智能合约（Chain 链）总体详细设计与实施规划

> 状态：设计稿 v1（2026-09-17）。
> 定位：本文是 **SP0b 至 SP5（含 v2 展望）的实施级总体设计与路线图**。它以已建成的 SP0a 为基线，把总规格 `2026-09-13-xdag-chain-contracts-design.md`（架构与协议规则的真相源）展开到接口、数据结构、算法、测试与任务分解的粒度，并给出里程碑与门禁。
> 真相源规则：协议规则以总规格为准；SP0a 已建成部分以 `2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md` 为准；本文与二者冲突时，本文让位并需修订。各子项目开工前仍要走 brainstorming → 子项目 spec → writing-plans 的流程，本文的设计是那一步的起点而非替代。
> 术语：2026-09-17 起 lane/通道 统一改称 chain/链（应用链）；主链、分片链的用法不变。

---

## 0. 阅读指引

| 读者 | 建议阅读 |
|------|---------|
| 想了解整体路线与排期 | §1、§3、§13、§14 |
| 要开工 SP0b | §2、§5 |
| 要开工 SP1（执行引擎） | §2、§3、§4、§6、§11 |
| 要开工 SP2 / SP3 | §6.4–§6.6、§7、§8、§11 |
| 要开工 SP4 / SP5 | §9、§10、§15 |
| 评审共识安全 | §3、§6.7、§8.4、§11.3、§14 |

约定：`SPx-y` 表示子项目 x 的第 y 个可独立交付的切片；"handler" 指 `io.xdag.chain.l1.ChainKindHandler` 的实现；"执行者"指订阅了某链并计算其状态的节点进程内组件；"共识路径"指 `BlockchainImpl.setMain/applyBlock/unApplyBlock/unSetMain` 及其钩子；"节点本地"指不影响 `CHAIN_L1` 状态哈希与任何全网可见判定的逻辑。

---

## 1. 目标、成功判据与退出标准

### 1.1 v1 上线定义（Definition of Done）

v1 = 链合约在 testnet 激活并稳定运行一个观察期后，主网以硬分叉高度激活。上线前必须同时满足：

1. **共识安全**：SP0a–SP3 的全部共识规则在两类节点（全量、快照启动）上判定一致；随机 reorg 属性测试（§12）在所有子项目通过；对抗测试证明三类错误锚定（E1/E2/E3）必被挑战成功、正确锚定不可被成功挑战。
2. **经济闭环**：存入 → 执行 → 提现 / 跨链转值 → CLAIM 的守恒不变量 I1–I3 在随机操作序列下恒成立；罚没与押金流转正确。
3. **可运营**：节点在 `chain.subscribeAll` 过渡模式下能跟上 testnet 负载；快照三目录流程有运维文档与端到端测试；启动一致性检查与修复命令可用。
4. **可开发**：`chain_*` RPC、Rust SDK、客户端库、CLI 与三个参考 DApp 可用；ABI 附录固化。
5. **性能门槛**：L1 导入 ≥ 10k 块/s/节点（合成负载，§5.1 基准定义）；单链执行 ≥ 2k 调用/s（简单转账合约）；单次仲裁 ≤ 2 s（`maxCallGas` 上限下）。数字在 SP1/SP3 基准后可以修订，但修订必须写进本文 §13 的门禁。

### 1.2 非目标（v1）

与总规格 §1.2 一致：不做原子跨链、不做 EVM 兼容、不做 DA 采样、不改主链节奏、不做链级治理。

### 1.3 退出标准与回退

- 任一子项目的对抗测试无法闭合（例如 E2 仲裁不能在 `maxCallGas` 内有界完成）→ 冻结该子项目，回到本文 §14 的决策项，不带着已知漏洞进入下一子项目。
- testnet 观察期内出现共识分歧 → 修复 + 快照重发 + 延后主网激活高度；不允许"带病激活"。

---

## 2. 已建成基线（SP0a 竣工，分支 `dev-dag-contract`）

### 2.1 交付物

| 包 | 内容 | 状态 |
|----|------|------|
| `io.xdag.core` | `XDAG_FIELD_EXT (0x0F)`、`Block.extFields/getBlockLinks/setType 预算断言`、`BlockchainImpl` 五个钩子 + 构造器内装配处理器 + 快照门 | 已合入 |
| `io.xdag.chain.ext` | 七种 kind 的编解码（`CallExt/DeployExt/ChunkExt/AnchorExt/BondExt/ChallengeExt/ClaimExt`）、`ChunkChain`（装配、宽松计数、年龄规则）、`ChunkChainBuilder`、`ChainBlockBuilder`、`ChainBlockClassifier` | 已合入 |
| `io.xdag.chain.l1` | `ChainL1Store/Batch/Keys`、记录类型、`ChainL1Processor`、`ChainL1Hooks`、`ChainKindHandler`、`ApplyContext`、`ChainIds`、`ChainL1SnapshotGate` | 已合入 |
| `io.xdag.chain` / `config` | `ChainActivation`、`ChainSpec`（四个共识参数 + 启动校验 + 覆盖告警） | 已合入 |
| `Kernel` | `chainL1Store` 生命周期、`chainKindHandlers` 注册表（构造 `BlockchainImpl` 之前放入） | 已合入 |
| 测试 | 22 个测试类，全量 383 绿；`ChainL1TestBase`（真实 `BlockchainImpl` + RocksDB + 受控难度伪 PoW + 分叉盐） | 已合入 |

### 2.2 后续子项目必须遵守的既成规则

1. **P1**：EXT 永不影响 L1 有效性；`tryToConnect` 不读 EXT。
2. **P2**：链语义只在 `mainNumber ≥ activationHeight` 的 `setMain` 内解释；处理器在 `BlockchainImpl` 构造器里从 `kernel.getChainL1Store()` 装配，先于 `startCheckMain`。
3. **P3**：每个写操作有成对反写；卸载顺序是应用顺序的严格逆序；已知缺口 G1/G2 由 SP0b-1 关闭。
4. **P4**：解码永不抛异常（`ExtResult`）；记录损坏是故意的 fail-stop。
5. **P5**：每个应用/卸载块恰好一个 `CHAIN_L1` batch；handler 写入处理器给的批次。
6. **P6**：钩子与 `RawBlockLookup` 只接受原始块（`getBlockByHash(h, true)`）。
7. 年龄规则 `epoch(chunk) ≥ epoch(paying) − 1`；代码链先装配后计数；参数链从不在 L1 装配，费用基数是宽松计数。
8. 金库支付只认 `XDAG_FIELD_OUTPUT`；一个块的每个命中金库的输出各产生一条输入记录；索引按 (chain, height) 递增。
9. 新链 DEPLOY 强制 `1 ≤ maxCallGas ≤ 10,000,000`，先于代码链检查。
10. `CODE (0x03)` 与 `CODE_REF (0x0D)` 分离；引用计数从不写 0。
11. `ChainKindHandler` 注册只能通过 `Kernel.getChainKindHandlers()` 在构造 `BlockchainImpl` 之前完成；`onUnapplied` 可能收到从未 `onApplied` 的块，实现必须以自己的记录为准做幂等撤销。
12. 快照三目录；`ChainL1SnapshotGate` 是每次快照启动的第一条语句；标记路径必须证明"同一快照 + 本地未漂移"。
13. `chain.activation.height`、`chain.chunk.maxPerChain`、`chain.wasm.maxBytes`、`chain.chunk.feeMilliXdag` 是共识参数，共享网络上绝不设置；不往任何 `.conf` 里加 `chain.*` 键。
14. 合入 `develop` 的前置：字段码 0x0F 与 dev-evm 的取舍（D3 归档必须落实）。

### 2.3 转交工单

| 工单 | 归属 |
|------|------|
| G1 `ref == null` 卡死主块可修复；G2 启动时 `CHAIN_L1` vs `BLOCK` 一致性检查 | SP0b-1 |
| G9 `makeSnapshot` 端到端测试；G10 随机 reorg 属性测试 | SP0b-1 |
| G7 `copyFile` 吞异常；G8 `RocksdbKVSource.init` 泄漏 | SP0b-1（顺手） |
| G3/G4/G5/G6 | 不阻塞任何子项目；SP0b-2 触碰 `Kernel`/`BlockchainImpl` 构造器时顺手处理 G3/G5 |

---

## 3. 全局架构与不变量（实施视角）

### 3.1 组件与所属子项目

```
                 ┌────────────── 共识路径（所有节点，SP0a/SP2/SP3） ──────────────┐
NEW_BLOCK ─► 导入流水线(SP0b) ─► tryToConnect ─► setMain/applyBlock ─► ChainL1Processor ─► ChainKindHandlers
                                                                        │  CALL/DEPLOY 归属   │  BOND/ANCHOR/CHALLENGE/CLAIM
                                                                        ▼                    ▼
                                                                   CHAIN_L1（全网小状态，进状态哈希）
                                                                        │ 输入索引 0x0C/0x07
                 ┌────────────── 执行路径（订阅节点，SP1/SP4） ──────────────┐
                 │  InputStreamBuilder(chain, h) ─► ChainExecutor ─► CHAIN_EXEC（本地大状态）
                 │        ▲ 已交付段（跨链）          │ C_k / stateRoot / outbox
                 │        └── 证明拉取 0x1E/0x1F        ▼
                 │                              AnchorPublisher（认证者，SP3） ─► ANCHOR 块 ─► L1
                 │                              Challenger（SP3） ─► CHALLENGE 块 ─► L1 仲裁
                 └── chain_* RPC / SDK / CLI（SP5）
```

### 3.2 三条分界线

| 分界 | 一侧 | 另一侧 | 判据 |
|------|------|--------|------|
| 共识 / 节点本地 | `ChainL1Processor`、handlers、`CHAIN_L1`、仲裁算法 | 导入流水线、孤块池、执行者、RPC | 是否影响 `CHAIN_L1.stateHash()` 或任何全网可见的判定 |
| L1 状态 / 链状态 | `CHAIN_L1`（小、全网、进快照哈希） | `CHAIN_EXEC`（大、只有订阅者、可重建） | 链状态是 L1 历史的纯函数，丢了可以重放 |
| 主锁内 / 主锁外 | `setMain` 内的钩子与仲裁应用 | 预验证、裁决预计算、执行、锚定发布 | 锁内只做 O(点查) 与已缓存结果的应用 |

### 3.3 全局不变量（每个子项目的属性测试都要覆盖自己涉及的部分）

- **N1 确定性**：`CHAIN_L1` 状态是主链历史的纯函数；执行路径 `S(L, h)` 是 L1 历史的纯函数。禁止：时钟、随机、孤块池内容、同步状态、非 `ChainSpec` 配置。
- **N2 对称**：`apply → unwind → apply` 与直接 `apply` 得到逐字节相同的 `CHAIN_L1` 与 `CHAIN_EXEC`。
- **N3 有界**：主锁内每高度的额外工作 ≤ `maxChallengesPerHeight × 仲裁上限` + O(输入数) 点查。
- **N4 守恒**：I1–I3（总规格 §11）。
- **N5 两类节点等价**：全量节点与快照启动节点对同一块得到相同裁决。

---

## 4. 模块与包结构规划

```
io.xdag.chain
├── ext/            已建成：编解码、分片链、构建器、分类器
├── l1/             已建成：CHAIN_L1、处理器、钩子、快照门
│   └── handlers/   SP2/SP3：BondHandler, AnchorHandler, ChallengeHandler, ClaimHandler, HeightTriggers
├── ingest/         SP0b-2/3：PreValidator, IngestPipeline, ChainOrphanPool, ChunkFeePolicy
├── repair/         SP0b-1：ChainConsistencyCheck, ChainRepairTool
├── exec/           SP1：ChainExecutor, InputStreamBuilder, CallFrame, ChainLedger, ChangeLog, ChainExecStore
│   ├── wasm/       SP1：WasmEngine(接口), ChicoryEngine, ModuleValidator, GasInstrumenter, HostAbi
│   └── state/      SP1：SparseMerkleTree, Mmr, OutboxMap, WitnessRecorder
├── anchor/         SP3：AnchorPublisher, CommitmentChain, CanonicalChainRules
├── fraud/          SP3：WitnessCodec, Arbiter, WitnessBackedStore, VerdictCache
├── asset/          SP2：VaultLedger, WithdrawOutbox, ClaimVerifier, SettlementJournal
├── p2p/            SP4：ChainSubscriptions, ChainSnapshotSync, ChainProofService, 消息 0x1B–0x1F
└── rpc/            SP5：ChainApi(chain_*), ChainApiImpl, 视图层 (pending/confirmed/anchored/final)
```

### 4.1 关键接口边界（草案，SP 开工时在子项目 spec 里定稿）

```java
// SP1 —— 执行器只依赖这个接口，Chicory 可替换（E4）
interface WasmEngine {
    PreparedModule prepare(Bytes32 codeHash, Bytes wasm) throws InvalidModuleException;   // 验证 + 插桩 + 缓存
    ExecResult execute(PreparedModule m, String export, HostContext ctx, long gasLimit);   // 每次新 Instance
    boolean isInterpreterMode();                                                          // 仲裁强制 true
}

// SP1 —— 链状态存取（SMT 之上）
interface ChainStateStore {
    Optional<Bytes> get(Bytes32 key);           // key = sha256(contract ‖ userKey)
    void put(Bytes32 key, Bytes value);
    void remove(Bytes32 key);
    List<Entry> scan(Bytes prefix, int max);    // 有界
    Bytes32 root();
    WitnessRecorder recorder();                 // 记录读集/写集与见证字节数
}

// SP1 —— 输入流
interface InputStreamBuilder {
    List<ChainInput> inputs(Bytes chainId, long height);   // systemInputs ++ delivered ++ confirmed，只读 CHAIN_L1
}

// SP2/SP3 —— 共识侧 handler（已建成的接口）
interface ChainKindHandler {
    void onApplied(Block block, Classified c, ApplyContext ctx, ChainL1Batch batch);
    void onUnapplied(Block block, Classified c, ChainL1Batch batch);
}

// SP3 —— 仲裁（共识；锁外预计算与锁内重算共用）
interface Arbiter {
    Verdict verify(Bytes32 challengeHash, ChallengeExt ch, WitnessV1 w, ChainL1View l1);   // 纯函数
}
```

---

## 5. SP0b：导入流水线、孤块池与加固（节点本地）

SP0b 切成三个可独立交付的切片，顺序固定：**SP0b-1 基准 + 加固 → SP0b-2 流水线 → SP0b-3 孤块池与费率策略**。SP0b-1 的基线数据决定 SP0b-2 的切分；SP0b-2 的结果决定 SP0b-3 是否需要更激进的配额。

### 5.1 SP0b-1：导入基准 + G1/G2/G9/G10（已确认方案）

**A. `L1ImportBenchmarkTest`（测试侧，黑盒）**

- 继承 `ChainL1TestBase`；`Assume.assumeTrue(Boolean.getBoolean("xdag.bench"))`，默认跳过；无新依赖。
- `BenchWorkload`：K 个 `ECKeyPair.generate()` 发送方，`addressStore.updateBalance` 注资；固定种子生成 N 个块：普通转账 / 内联 CALL / 3 片参数链 CALL / 长代码链 DEPLOY，默认比例 60/25/10/5，可配；nonce 递增，块时间落在同一纪元；先全部构建再计时。
- 测量：① `tryToConnect` 直连（全部 `IMPORTED_NOT_BEST`）；② `SyncManager.validateAndAddNewBlock`（含重解析）；③ 确认路径（每 2000 块出一个主块并 `confirm`）。三轮取中位数，报 p50/p95 与块/s。
- 阶段重演（同一批块，测试侧单独计时）：解析、`canUseInput` 验签、引用点查、`saveBlock`。给出占比估计。
- 输出：控制台表 + `target/bench/*.json`；基线写入 `docs/benchmarks/<date>-l1-import-baseline.md`（含机器信息）。不改 `BlockchainImpl`。

**B. 一致性检查（G2）与卡死主块（G1）**（设计已由用户于 2026-09-17 逐节确认，细节见 SP0b-1 规格 §3.2–§3.4）

- **G1 根治**：`setMain` 在 `applyBlock` 之前就 `updateBlockRef(block, self)`（结尾那句保留，幂等）。DFS 中途抛异常留下的主块因此 `ref == self`，`unApplyBlock` 能正常处理它；"永远 unwind 不了"的状态不再产生。`ref` 只在本地 `BlockInfo`，不进任何哈希。
- **G2 标记**：`BlockStore` 新增节点本地键 `LAST_COMPLETED_MAIN`（INDEX 列）：`setMain` **正常走到末尾**时写 `h`（异常路径不写，不在 `finally`）；`unSetMain` 末尾写 `h − 1`；快照导入后写 `snapshotHeight`。不放在 `CHAIN_L1`（免去状态哈希与快照门的排除逻辑）。
- `ChainConsistencyCheck.run(blockStore, stats, spec, window)`：放在 `BlockchainImpl` 构造器、快照分支与统计加载之后、装配处理器之前。规则：标记缺失 → warn 并初始化为 `nmain`；`标记 < nmain` → 未完成的 `setMain`；扫描 `[max(activation, nmain − window), nmain + 8]` 中 `BI_MAIN` 已置而 `ref == null` 的主块 → 旧版遗留卡死；命中即抛 `IllegalStateException`（`repairMode` 下只记录报告）。`window` 默认 128（`chain.consistency.window`，节点本地）。

**C. 修复命令 `XdagCli --repairchain [--dry-run]`**

- 离线打开 BLOCK/ADDRESS/CHAIN_L1，按 `ChainL1TestBase` 的方式组装无网络的 `Kernel + BlockchainImpl`（`startCheckMain` 不启动）。
- 对每个卡死主块 M：`updateBlockRef(M, new Address(M))`（补上 `setMain` 本应写的那一步，`fee` 保持 0）。
- 目标高度 `target = min(LAST_COMPLETED_MAIN, 最早卡死高度 − 1)`；`unWindMain(getBlockByHeight(target))`。回滚经由钩子成对撤销 `CHAIN_L1` 记录、撤销奖励；块仍在库里，节点启动后 `checkNewMain` 重新确认并重新应用。
- 结束时重跑 `ChainConsistencyCheck`；`--dry-run` 只打印计划。
- 测试：用基座人为制造 G1/G2 两种状态（在第 k 个块上抛异常的 handler 得到未完成的 `setMain`；人为把已确认主块的 `ref` 置 null 模拟旧版遗留），验证检查命中、`--dry-run` 不改库、修复后检查通过且 `CHAIN_L1` 与"干净重放"逐字节相等。

**D. G9 / G10**

- G9：`XdagCliTest` 风格 spy，在临时存储上跑 `makeSnapshot`，断言三目录存在、`SNAPSHOT/CHAIN_L1` 的 `0xFF` 哈希等于源库 `stateHash()`。
- G10：`ChainL1ReorgPropertyTest`：固定种子集合（默认 8 个，`-Dxdag.reorg.seeds` 可加），每个种子：随机 3–8 个高度、每高度随机 0–6 个块（DEPLOY 新建 / 加入、CALL 命中 / 未命中金库、普通转账、故意低费），随机分叉点与竞争分支长度（保证超越），断言 `sortedKeys` 与全部 value 逐字节等于同种子在新基座上直接 apply 的结果，并比对涉及地址的余额与 nonce；失败时打印种子。

**任务量估计**：8–10 个 TDD 任务。

### 5.2 SP0b-2：锁外预验证流水线与批量落盘

目标：把 `tryToConnect` 锁内工作压缩到"存在性 + link 校验 + 难度 + DAG 插入 + 孤块池更新"，其余在锁外并行完成；`saveBlock` 的三次 RocksDB 写合成一个 `WriteBatch`。

**设计要点**

1. `PreValidator`（线程池，`chain.ingest.threads` 默认 = 核数）：输入原始 512B；输出 `PreValidated{block, hashLow, classified, sigOk, extStructureOk, chunkStructure?}`。做：解析（`new Block(new XdagBlock(bytes))`）、`Block.getHash`、`ChainBlockClassifier.classify`、分片链结构（只看本块：seq/dataLen/link 数）、签名验证（`canUseInput` 的纯计算部分抽成静态方法 `SignatureCheck.verify(block, pubkeys)`，结果按 `(hashLow) → boolean` 缓存 `verifiedBlocks`，LRU 64k）。
2. **保序**：按 `sender 地址`（有 INPUT 的块）或 `chainId`（CALL/DEPLOY 的目标）哈希到 `chain.ingest.chains` 条队列；同一队列串行进入锁；不同队列可乱序。链头/分片块按其付费块的队列归队（分片块先到时进入等待映射，付费块到达时一起提交）。
3. **锁内**：`tryToConnect(PreValidated)` 重载：跳过已在锁外完成的解析与验签（`canUseInput` 改为"若 `preValidated.sigOk` 已知则直接用"）。**签名验证依赖的公钥集合来自块本身或 `AddressStore`**：锁外验签只对"公钥在块内"的账户交易有效；依赖存储的路径仍在锁内做，SP0b-2 先覆盖前者（占比由 SP0b-1 基准给出）。
4. **批量落盘**：`BlockStoreImpl.saveBlock` 三次 put → 一个 `WriteBatch`（`KVSource.batchWrite` 已在 SP0a 加入）；`saveBlockInfo` 保持独立（它在 apply 期间反复调用）。
5. `SyncManager.validateAndAddNewBlock` 去掉重复解析，改为投递到 `IngestPipeline`；`XdagP2pHandler` 不变。
6. **P1 不变**：预验证只是把锁内计算提前，判定不变；任何预验证失败的块仍走原路径得到同一个 `ImportResult`（预验证只能"加速通过"，不能"提前拒绝"，拒绝逻辑仍在锁内以保证结果一致）。

**测试**：等价性——同一随机块序列经 `IngestPipeline` 与经旧 `tryToConnect` 得到相同的 `ImportResult` 序列、相同的 `BLOCK/INDEX/ADDRESS` 内容；并发正确性——多队列并发投递后 DAG 内容与串行一致；基准前后对比写入 `docs/benchmarks`。

**任务量估计**：8–12 个任务。风险：`canUseInput` 与 `AddressStore` 的耦合；`processExtraBlock` 的孤块池语义。

### 5.3 SP0b-3：孤块池分队列、配额、TTL 与导入期费率策略

1. `ChainOrphanPool` 替代 `memOrphanPool` 的单一 `LinkedHashMap`：按类别分队列——账户交易 / 分片块 / 其它 link 块；分片块再按来源 peer IP 与付费块目标 chainId 二级配额（`chain.orphan.chunkPerPeer` 默认 5,000、`chain.orphan.chunkPerChain` 默认 20,000）；全局 `orphanPoolLimit` 100,000 替代 `MAX_ORPHAN_SIZE = 3750`（账户交易队列保留原上限语义）。
2. **TTL**：分片块进入池后 `chain.orphan.chunkTtlEpochs`（默认 2 个纪元 = 128 s）内未被付费块引用则淘汰；与年龄规则对齐（超过两个纪元的分片本来就不可能再被合法引用）。
3. **落盘时机**：分片块被付费块引用（付费块 `IMPORTED_*`）后才 `saveBlock`；未被引用的分片块只在内存池。这改变了现有"导入即落盘"的行为，仅对 CHUNK 类块生效，其它块不变。
4. **导入期费率策略**（节点本地、可关闭）：付费块进入锁前，用 `ChunkChain.countLenient` 对其链头计数（锁外，可能读孤块池），`headerFee < chunkFee × count` 则拒绝转发与导入（返回 `INVALID_BLOCK` 变体 `CHAIN_FEE_POLICY`，不落盘、不进 DAG）。因为是策略而非共识，两个节点可能对同一块有不同决定；这不影响 `CHAIN_L1`——被拒绝的块从未进入本节点的 DAG，本节点会因 `NO_PARENT`/缺块在主块到来时按需拉取（与快照节点的路径相同）。策略默认开启，`chain.ingest.feePolicy=false` 关闭。
5. `getOrphan` / `OrphanBlockStore` 的接口保持；主块打包只从账户交易队列取。

**测试**：垃圾分片洪泛下账户交易仍可导入；TTL 淘汰后被付费块引用 → `NO_PARENT` → 拉取路径；费率策略开/关下 DAG 内容一致性（被拒块最终经拉取进入）。

**任务量估计**：8–10 个任务。

---

## 6. SP1：链执行引擎

SP1 是最大的子项目，建议切为 **SP1-1 WASM 引擎与 ABI → SP1-2 状态与承诺 → SP1-3 输入流与执行循环 → SP1-4 日志/回滚/快照与软预执行 → SP1-5 确定性与基准**。SP1 全程不改共识路径（不注册 handler、不改 `CHAIN_L1` 写入），它只读 `CHAIN_L1` 与块存储。

### 6.1 SP1-1：`WasmEngine`、模块验证与插桩

- 依赖：Chicory（版本在子项目计划时钉死；要求同时具备解释器与运行时编译两种模式）。执行器只依赖 `WasmEngine`。
- `ModuleValidator`：按总规格 §8.1 白名单校验（拒绝浮点、SIMD、原子、异常、多内存、引用类型、尾调用；恰一个导出 `memory` 且声明 `max ≤ maxMemoryPages`；导入只来自 `xdag`；导出 `init/call/recv/query`）。失败 → `INVALID_CODE`。
- `GasInstrumenter`（确定性协议函数）：基本块入口注入 `xdag.gas(cost)`；`memory.grow` 前按页计费；函数入口/出口维护栈计数，超 `maxStackHeight` trap；输出模块的 sha256 记入测试向量（`codeHash → instrumentedHash` 固定）。插桩后模块本地缓存，`codeHash` 仍为原始字节哈希。
- `HostAbi`：实现总规格 §8.3 全部 `xdag.*` 导入，费用表来自 `GasSchedule`（协议常量类，激活后不可改）。
- 测试：白名单每条一个反例；插桩输出对固定 wasm 向量的哈希钉死；gas 计数在解释器与编译模式一致；`-Xss` 无关性（深递归模块在两种模式下同一 trap 点）。

### 6.2 SP1-2：状态、承诺与见证

- `SparseMerkleTree`：256 层、SHA-256、空子树预计算；`get/put/remove/root/proof/verify`；`nonEmptySiblings(key)` 作为一等 API（见证计费依赖）。
- `Mmr`：append-only，`root/size/proof/verify`。
- `OutboxMap`：小 SMT，key = dstChain，value = `mmrRoot ‖ size`。
- `WitnessRecorder`：记录一次调用的读集（key, value|absent, 证明）、写集（key, 兄弟路径）、累计 `witnessBytes`；提供 `estimateFromGas(gasUsed)` 上界。
- `ChainExecStore`（RocksDB 列 `CHAIN_EXEC`，节点本地）：前缀表——`0x01 chainId‖key → value`、`0x02 chainId → 最新 seq/根`、`0x03 chainId‖seq → 变更集`、`0x04 chainId‖seq → 快照页`、`0x05 chainId‖dst → MMR 节点`、`0x06 chainId‖seq‖k → C_k 开解`、`0x07 chainId‖blockHash → 回执`。
- 属性测试：随机调用的实际 `witnessBytes ≤ estimateFromGas(gasUsed)`（§8.3 不等式）；SMT/MMR 证明正反例；同一 KV 序列在两种插入顺序下根相同。

### 6.3 SP1-3：输入流与执行循环

- `InputStreamBuilder.inputs(chain, h)`：`systemInputs`（本高度成为规范的锚定 → 奖励输入；SP3 之前为空）++ `deliveredMessages`（本高度可交付的段展开；SP4 之前为空）++ `confirmedBlocks`（`CHAIN_L1` 0x0C 按 `0..callCount−1` 枚举，只取 `status == OK` 的 CALL/DEPLOY，`INVALID_*` 输入仍占位并产生 `status` 回执）。
- `ChainExecutor.step(chain, h)`：逐输入执行总规格 §7.2 的事务语义（预扣 gas、执行、提交/回滚、退还、承诺 `C_k`）；DEPLOY 输入执行 `init`；`INSUFFICIENT_GAS/FAILED/INVALID_*` 都产生承诺与回执。
- `CallFrame` 嵌套 updater：深度 ≤ `maxCallDepth`，默认禁重入（导出元数据 `reentrant` 标记放行）。
- `ChainLedger`：系统合约 `0x00…00` 的 KV：`bal[addr]`、费用池、出站在途。
- 与 L1 的同步：执行者监听 `onSetMainEnd(h)`（节点本地事件总线，非共识钩子）推进各订阅链到 seq = h；落后时批量追赶。
- 测试：固定输入流向量的根与承诺钉死；`INVALID_*` 输入退款路径；重入拒绝；深度上限。

### 6.4 SP1-4：变更日志、回滚、快照与软预执行

- 每 seq 变更集（旧值/新值 + outbox/账本增量），保留 `K = 128`；`rollbackTo(seq)`；每 `snapshotInterval = 64` 落全量 KV 快照页 + 根。
- 回滚触发：`onUnwind(h0)`（L1 事件）→ 所有订阅链回滚到 ≤ h0；源锚定否决（SP3）→ 回滚到消费该段之前。
- 软预执行：对未确认 CALL 按临时 DFS 序在影子 updater 上执行，只服务 `pending` 视图；永不写 `CHAIN_EXEC` 主状态。
- 测试：随机 apply/unwind/replay 的 N2 属性；快照页重建根一致。

### 6.5 SP1-5：确定性与基准

- 两模式一致性：同一输入流在解释器与编译模式得到相同根与承诺（若 Chicory 编译模式对某模块退回解释器，需在结果中可观测但根不变）。
- gas 微基准校准费用表，写入 `docs/benchmarks`；决定 blake3 是否替换 SHA-256（E10，激活前定死）。
- 单链执行吞吐基准（简单转账合约）。

**SP1 任务量估计**：25–30 个任务，建议拆成两份计划（SP1-1/2 与 SP1-3/4/5）。

### 6.6 SP1 对后续子项目暴露的接口

`ChainExecutor.stateRootAt(chain, seq)`、`commitmentsBetween(chain, prevSeq, seq)`、`outboxMapRootAt`、`proofFor(chain, seq, kind, key)`、`receipt(blockHash)`、`query(chain, contract, selector, args, view)`。

### 6.7 共识相关注意

SP1 本身不写共识，但 **仲裁（SP3）要复用 SP1 的解释器执行与见证格式**：`WasmEngine.isInterpreterMode()` 必须能被 SP3 强制；`WitnessBackedStore`（用见证代替真实存储）必须与 `ChainStateStore` 同接口。SP1 在设计 `ChainStateStore` 时就要让"读未证明的 key"能表达为 trap。

---

## 7. SP2：资产流与守恒

SP2 在 SP1-3 之后开工，与 SP3 并行。它引入第一批 **共识 handler**（`ClaimHandler`）与第一处 **系统划账**（硬分叉点 (a)）。

### 7.1 组件

| 组件 | 层 | 内容 |
|------|----|------|
| `VaultLedger` | 执行侧 | 链内账本视图 + 出站在途/入站在途统计，供 I1 校验与 RPC |
| `WithdrawOutbox` | 执行侧 | `withdraw(addr, amount)` → outbox(L1) 条目；`send(value)` → outbox(dst) 条目；MMR 追加 |
| `ClaimVerifier` | 共识 | 校验 CLAIM：终局锚定存在（0x06 状态 final）、`index` 未认领（0x08）、`amount/recipient` 与 MMR 叶一致、MMR 成员证明 + outboxMap SMT 证明对 `outboxMapRoot` 成立 |
| `ClaimHandler` | 共识 handler | 通过 → 写 0x08、系统划账 `金库(srcChain) → recipient`（flags.bit0 时 recipient 是目标金库）；`onUnapplied` 按 0x08 记录逆操作 |
| `SettlementJournal` | 共识 | `CHAIN_L1` 前缀 `0x10 blockHash → 划账明细`（进状态哈希）：每次系统划账记账，unwind 时精确反向（借鉴 dev-evm 的 journal 经验：只反向"确实做过的"） |

### 7.2 系统划账与 `AddressStore`

- 划账通过 `BlockchainImpl` 暴露的受控入口（新方法 `chainTransfer(from20, to20, amount, journalKey)`），内部走现有 `addAmount/subtractAmount`；金库余额不足 → CLAIM 记 `INVALID_CLAIM`，不划账（金库不足只可能在 I2 被破坏时发生，属于 fail-safe）。
- 反向：`onUnapplied` 读 journal 做逆向划账，然后删 journal 与 0x08。
- 硬分叉：激活前 CLAIM 块只是普通自转块；激活后才有划账。

### 7.3 测试

- 不变量 I1–I3 随机操作序列（存入/执行/withdraw/send/CLAIM/reorg）；重复 CLAIM 拒绝；错误证明拒绝；unwind 后金库与用户余额精确恢复；快照节点与全量节点对同一 CLAIM 判定一致。

**任务量估计**：10–12 个任务。

---

## 8. SP3：认证者、锚定、终局与欺诈证明

SP3 引入 `BondHandler / AnchorHandler / ChallengeHandler` 与高度触发器，是共识复杂度最高的子项目。切片：**SP3-1 保证金与锚定规范链 → SP3-2 承诺链与终局/触发器 → SP3-3 见证与仲裁 → SP3-4 罚没、级联回滚与认证者/挑战者进程**。

### 8.1 SP3-1：BOND/UNBOND 与锚定规范链

- `BondHandler`：BOND → 0x04 `attester‖chainId` 增额（OUTPUT 到 `CHAIN_BOND_VAULT`）；UNBOND → 记申请高度并向 0x09 高度触发器登记 `h + W`。反向对称。
- `AnchorHandler.onApplied`：总规格 §9.3 六项有效性检查（全部是 `CHAIN_L1` 点查 + `BLOCK_HEIGHT` 索引 + 分片链 `totalLen`），通过 → 0x06 锚定记录 `canonical`、0x05 链头前进、0x09 登记 `h + W` 终局触发；同 seq 后来者忽略（写一条 `ignored` 记录便于 RPC 解释）。反向：删记录、链头回退。
- 承诺链在 SP3-1 只校验长度与结构，不解析承诺内容。

### 8.2 SP3-2：承诺链、终局与高度触发器

- `CommitmentChain`：承诺链编解码（已消费段列表 + `C_1..C_n`）；`AnchorPublisher`（执行侧）从 SP1 的 `commitmentsBetween` 与 `InputStreamBuilder` 的段消费结果构造并签发 ANCHOR 块（含自转 MIN_GAS 付费）。
- `HeightTriggers`：`onSetMainEnd(h)` 固定顺序处理 0x09 的到期项：① 本高度确认的 CHALLENGE 仲裁应用 ② 锚定终局（`canonical → final`）③ UNBOND 归还（系统划账，走 §7.2 的 journal）④ 段可交付标记 ⑤ 锚定奖励系统输入（写入 0x0C 作为 `kind = SYSTEM` 输入，供 SP1 的 `systemInputs`）。`onUnsetMain` 逆序反向。
- 段游标 0x0B：消费段列表通过校验后推进 `to`；反向回退。

### 8.3 SP3-3：见证与仲裁

- `WitnessCodec`：`WitnessV1` 分片链载荷编解码（E1/E2/E3），总长 ≤ `maxWitnessBytes`。
- `Arbiter.verify`：总规格 §10.3 算法；E2 用 `WasmEngine`（强制解释器）+ `WitnessBackedStore`（读集来自见证，未证明读 → trap → 挑战失败）。纯函数：只读 `CHAIN_L1` 已确认状态 + 代码库 0x03 + 见证。
- `VerdictCache`：`tryToConnect` 导入 CHALLENGE 时（锁外线程池）预计算裁决；`ChallengeHandler.onApplied` 优先取缓存，缺失才锁内重算；每高度最多 `maxChallengesPerHeight = 4`，超出的进 0x09 顺延队列（确定性，按 DFS 序）。测试锁死"缓存 == 重算"。
- 仲裁结果 0x0A；成功 → 锚定及后代 `voided`、链头回退到父、罚没（§8.4）；失败 → 押金没收给认证者（journal 划账）。

### 8.4 SP3-4：罚没、级联回滚与进程

- 罚没：50% → 挑战者、50% 销毁（`CHAIN_BOND_VAULT` → 挑战者地址 + 销毁地址；journal 记录）。
- 级联：被否决锚定的段被谁消费过（0x0B 游标历史）→ 那些链的执行者收到 `onSourceVoided(srcChain, seq)` 事件回滚到消费前（执行侧）；L1 侧只改锚定状态与游标。
- `Challenger`（执行侧进程）：对本地订阅链的每个新规范锚定比对本地承诺，发现分歧即构造见证并签发 CHALLENGE。
- `maxWithdrawPerWindow`：SP3 定公式并实现在 `ClaimVerifier`（每链每 W 窗口累计提现 ≤ f(总保证金)）。

### 8.5 测试

- 对抗：E1/E2/E3 各类错误锚定必被挑战成功；正确锚定的挑战必失败且押金没收；见证超长/未证明读/篡改承诺开解全部判失败；`maxChallengesPerHeight` 顺延确定性。
- 有界性：`maxCallGas` 上限的 E2 仲裁耗时基准 ≤ 2 s。
- N2：BOND/ANCHOR/CHALLENGE/CLAIM 混合序列的随机 reorg 属性。
- 两类节点等价：快照节点对同一 CHALLENGE 裁决一致（代码库随快照）。

**任务量估计**：25–30 个任务（两份计划）。

---

## 9. SP4：订阅与 P2P

### 9.1 订阅集合与过渡模式

- `ChainSubscriptions`：配置 `chain.subscribe = [chainId...]` 与 `chain.subscribeAll = true|false`（D6，v1 默认 testnet 上 `true`）；执行者只对订阅链跑 SP1 引擎；未订阅链只保留 `CHAIN_L1` 锚定索引。
- 握手后互发 `0x1B CHAIN_SUBSCRIPTIONS`（路由提示，不影响 gossip）。

### 9.2 消息（码点 0x1B–0x1F，已核实 0x10–0x1A 在用、0x1B 起空闲）

| 码 | 名称 | 内容 | 服务方 |
|----|------|------|--------|
| 0x1B | CHAIN_SUBSCRIPTIONS | chainId 列表 | 所有节点 |
| 0x1C/0x1D | CHAIN_SNAPSHOT_REQUEST/REPLY | (chain, seq, page) → KV 页 + 页哈希 | 订阅者 |
| 0x1E/0x1F | CHAIN_PROOF_REQUEST/REPLY | (chain, seq, kind, key) → 段内容 + MMR 证明 / outboxMap SMT 证明 / C_k 开解 / 事件 | 订阅者 |

- 新订阅者引导：最近 final 锚定 → 拉快照页 → 校验根 == 锚定 `stateRoot` → 从 seq 重放本地 L1 输入流；无锚定链从 DEPLOY 重放。
- 跨链交付：目标链执行者在 `h_deliver` 前通过 0x1E 拉取源段内容与证明；拉不到 → 该链执行停在该高度（活性，不影响共识），并持续重试。

### 9.3 多节点 devnet

- 3 节点 docker-compose（复用现有 devnet 配置），2 条链，节点订阅不同链；跨链消息 D=0 与 D=W 对照；一次人为错误锚定的完整挑战-罚没流程脚本化。

**任务量估计**：15–18 个任务。

---

## 10. SP5：开发者面

- `chain_*` RPC（总规格 §16.1 全部方法）：实现层复用 SP1 的 `ChainExecutor` 视图接口；四级视图 `pending/confirmed/anchored/final` 由执行者状态 + `CHAIN_L1` 锚定索引合成；`chain_sendCall` 接受原始块与分片链，投递到 SP0b 的 `IngestPipeline`；激活前一律拒绝（E5）。
- ABI 附录：参数编码（borsh 风格紧凑编码）、selector 规则、事件编码、错误码表。
- Rust SDK `xdag-contract`（宏、存储集合、消息类型、内存宿主）；客户端库 `xdag-client`（TS + Rust）；CLI `xdag chain …`（复用 `Commands`/`Shell` 框架）。
- 参考 DApp：XRC-20；单链 DEX；跨链消息演示。
- 文档：开发者指南、运维指南（快照三目录、修复命令、订阅配置）、ABI 附录。

**任务量估计**：20–25 个任务（SDK/客户端库为独立仓库或 `tools/` 子目录）。

---

## 11. 跨子项目的统一设计

### 11.1 存储总表

| 库 | 层 | 进快照哈希 | 内容 |
|----|----|-----------|------|
| `CHAIN_L1` | 共识 | 是（0xFE/0xFF 除外） | 0x01–0x0E（SP0a）、0x10 划账 journal（SP2）、0x04/0x05/0x06/0x08/0x09/0x0A/0x0B 由 SP2/SP3 启用 |
| `CHAIN_EXEC` | 节点本地 | 否 | 链 KV、变更集、快照页、MMR、承诺开解、回执 |
| `BLOCK/INDEX/ADDRESS` | 共识 | 现有 | 不新增语义；系统划账只经 `chainTransfer` 入口 |

`CHAIN_L1` 新前缀由本文预留：`0x10 SETTLEMENT_JOURNAL`、`0xFE LOCAL_META`（节点本地、不进哈希；SP0b-1 最终把完成标记放在 `BlockStore`，此前缀暂未使用，保留给将来的节点本地元数据）。

### 11.2 配置与参数

- 共识参数只从 `ChainSpec` 读，默认值全网一致，共享网络不设置；新增共识参数（`W`、`minBond`、`gasPerWitnessByte`…）随子项目加入 `ChainSpec` 并沿用"启动校验 + 覆盖告警"模式。
- 节点本地参数（`chain.ingest.*`、`chain.orphan.*`、`chain.consistency.window`、`chain.subscribe*`）允许 conf 设置，仍不写进仓库 conf 文件。

### 11.3 激活与硬分叉流程

1. testnet：`chain.activation.height` 由治理定为未来高度 H；所有节点升级到含 SP0–SP5 的版本；H 前 `ChainActivation` 门控钱包/RPC。
2. 快照发布者在 H 前后各发布一次三目录快照。
3. 观察期（建议 ≥ 4 周）：一致性检查零命中、随机 reorg 属性测试在真实数据上抽样运行、至少一次演练挑战。
4. 主网重复上述流程。

### 11.4 快照

三目录已定；后续子项目新增的 `CHAIN_L1` 前缀自动进入导出/哈希；`CHAIN_EXEC` 不进快照（可重放）。

---

## 12. 测试与验证策略总表

| 类别 | SP0b | SP1 | SP2 | SP3 | SP4 | SP5 |
|------|------|-----|-----|-----|-----|-----|
| TDD 单元 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 属性（N2 随机 reorg） | G10 建立框架 | 执行状态 | 资产流 | 锚定/挑战混合 | — | — |
| 属性（N4 守恒 I1–I3） | — | 账本 | ✓ | 罚没/押金 | — | — |
| 确定性（N1） | 等价性（流水线 vs 旧路径） | 两模式一致；插桩钉死 | 快照 vs 全量节点 | 缓存 == 重算 | — | — |
| 对抗 | 分片洪泛 | 恶意模块 | 错误证明/重复 CLAIM | E1/E2/E3、超长见证 | 拉取拒绝服务 | 激活前拒绝 |
| 基准 | 导入块/s 前后 | gas 校准、执行吞吐、SMT | — | 仲裁耗时上限 | 引导时间 | — |
| 端到端 | 修复命令、makeSnapshot | — | — | — | 3 节点 devnet | DApp 演示 |

每个子项目的计划必须在"最后一个任务"包含：`mvn license:check`、全量 `mvn test`、规格同步、`.claude/docs` 本地文档、记忆更新。

---

## 13. 里程碑、依赖与门禁

### 13.1 依赖图

```
SP0a ✅ ─┬─► SP0b-1 ─► SP0b-2 ─► SP0b-3
         │
         └─► SP1-1 ─► SP1-2 ─► SP1-3 ─► SP1-4 ─► SP1-5
                                   │
                                   ├─► SP2 ─┐
                                   └─► SP3-1 ─► SP3-2 ─► SP3-3 ─► SP3-4 ─┤
                                                                         ├─► SP4 ─► SP5
SP0b-3 ──────────────────────────────────────────────────────────────────┘
```

SP0b 与 SP1 并行（不同包、不同人/会话）；SP2 与 SP3 并行（都依赖 SP1-3）；SP4 需要 SP2+SP3+SP0b-3。

### 13.2 里程碑

| 里程碑 | 内容 | 出口门禁 |
|--------|------|---------|
| **M1 基线** | SP0b-1 完成 | 基线文档；G1/G2/G9/G10 关闭；全量绿 |
| **M2 引擎** | SP1-1/2 完成 | 白名单/插桩测试向量钉死；§8.3 不等式属性测试通过 |
| **M3 执行** | SP1-3/4/5 + SP0b-2 完成 | 单链执行吞吐 ≥ 2k 调用/s；两模式一致；导入 ≥ 10k 块/s |
| **M4 经济与安全** | SP2 + SP3 完成 | I1–I3 属性；E1/E2/E3 对抗；仲裁 ≤ 2 s；N2 全场景 |
| **M5 网络** | SP0b-3 + SP4 完成 | 3 节点 devnet 跨链 + 挑战演练脚本通过 |
| **M6 开发者面** | SP5 完成 | RPC/SDK/CLI/DApp；文档 |
| **M7 testnet 激活** | 治理定高度、快照、观察期 | §11.3 |

### 13.3 每个子项目的固定流程与门禁

brainstorming（子项目 spec，以本文对应章节为起点）→ writing-plans（TDD 任务）→ subagent-driven 实施（每任务规格评审 + 质量评审）→ 整体终审 → 全量测试 + 许可证 → 规格同步 → 记忆。任何"Important 及以上"评审项未关闭不得进入下一子项目。

### 13.4 规模估计

| 子项目 | 任务数（估） | 计划数 |
|--------|-------------|--------|
| SP0b-1 / -2 / -3 | 9 / 10 / 9 | 3 |
| SP1 | 28 | 2 |
| SP2 | 11 | 1 |
| SP3 | 28 | 2 |
| SP4 | 16 | 1 |
| SP5 | 22 | 2（RPC/CLI；SDK/DApp） |

---

## 14. 风险矩阵与待决事项

| # | 事项 | 影响子项目 | 决策时点 | 当前倾向 |
|---|------|-----------|---------|---------|
| R1 | 字段码 0x0F 与 dev-evm 冲突（E11/D3） | 合入 develop | SP0b-1 前 | 归档 dev-evm 并记录；若需保留 dev-evm 则本方向改用 0x0E 之类需重编码——代价极大，故倾向归档 |
| R2 | Chicory 版本、编译模式退化、`-Xss`（E4） | SP1-1 | SP1 spec | 解释器为仲裁真相；`WasmEngine` 可替换 |
| R3 | SHA-256 vs blake3（E10） | SP1-2 | M3 前 | 基准决定；激活前定死 |
| R4 | 经济参数（`minBond`、罚没比例、`maxWithdrawPerWindow` 公式）（E9/S2） | SP3 | M4 前 | SP3 经济分析 + testnet 数据 |
| R5 | L1 存储无界增长（E7） | 运营 | testnet 观察期 | 已终局锚定前的分片载荷本地剪枝（v1 可选实现，SP0b-3 之后评估） |
| R6 | `canUseInput` 与 `AddressStore` 耦合限制锁外验签覆盖率 | SP0b-2 | SP0b-1 基线后 | 先覆盖块内公钥路径 |
| R7 | 矿池即排序者（S3）、可组合性引力（S4） | 产品 | v2 | 反 MEV 排序模式与编织锚定留 v2 |
| R8 | 过渡模式 `subscribeAll` 下单节点承担全部执行 | SP4/运营 | M5 | 多核并行执行不同链；testnet 负载测试 |

---

## 15. 附录

### 15.1 消息码与 RPC 现状（已核实）

- P2P 在用码点：0x00–0x05、0x10–0x1A；0x1B–0x1F 空闲，分配给 SP4。
- 原生 RPC `xdag_*`（`XdagApi`）保持不变；`chain_*` 作为独立接口 `ChainApi` 注册到同一 JSON-RPC 服务器。

### 15.2 `CHAIN_L1` 前缀总表（含预留）

| 前缀 | 用途 | 引入 |
|------|------|------|
| 0x00 META | schema | SP0a |
| 0x01–0x03, 0x07, 0x0C–0x0E | 注册表/合约/代码/计数/输入/引用计数/反向索引 | SP0a |
| 0x04 BOND、0x05 ANCHOR_HEAD、0x06 ANCHOR、0x09 HEIGHT_TRIGGER、0x0A CHALLENGE、0x0B SEGMENT_CURSOR | SP3 |
| 0x08 CLAIMED、0x10 SETTLEMENT_JOURNAL | SP2 |
| 0xFE LOCAL_META（不进哈希，预留） | 未使用 |
| 0xFF SNAPSHOT_HASH（不进哈希） | SP0a |

### 15.3 术语

同总规格 §21；新增：**切片 SPx-y**、**journal（划账日志）**、**预验证（PreValidated）**、**LAST_COMPLETED_MAIN（`BlockStore` 里的最后完整 `setMain` 高度标记）**。
