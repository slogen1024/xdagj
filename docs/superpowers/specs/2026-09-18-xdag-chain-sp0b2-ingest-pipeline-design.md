# SP0b-2：锁外预验证流水线与写后落盘 设计规格

> 状态：设计稿 v1（2026-09-18，用户已逐节确认 §1–§5 的设计口径）。
> 上级文档：总体设计与路线图 `2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md` §5.2；SP0b-1 竣工规格 `2026-09-17-xdag-chain-sp0b1-benchmark-and-hardening-design.md`；基线 `docs/benchmarks/2026-09-18-l1-import-baseline.md`。
> 与路线图 §5.2 的偏差（用户 2026-09-18 决定）：(a) 验收目标从"做完四项"提高到 **≥ 10,000 块/s**（M3 的导入指标提前到本子项目）；(b) 落盘不是"每块一个 WriteBatch"而是**有序写后（write-behind）写入线程 + 读穿透待写缓存**；(c) 保序不是"按发送方/chainId 多队列可乱序"而是**并行预验证、按到达顺序单队列提交**。原因见 §2 与 §7。

---

## 0. 一句话

把 `tryToConnect` 锁内每块约 458 µs 的工作压到约 75 µs：ECDSA 验签移到锁外线程池并行做；块、`BlockInfo`、统计、孤块索引的写入改为追加到一条有序写流、由单个写入线程合成 `WriteBatch` 落盘、所有读先查待写缓存；`checkNewMain()` 的回走增量化；sums 改为内存写回。判定逻辑一字不改，崩溃后磁盘永远是写流的一个前缀，SP0b-1 的启动门与修复命令不需要任何改动。

## 1. 范围

### 1.1 包含

1. `io.xdag.db.rocksdb.WriteBehindKVSource`：包住 INDEX / TIME / BLOCK / ORPHANIND 四个 `RocksdbKVSource` 的有序写后层（§3.2）；INDEX 上的读缓存；sums 内存写回（§3.4）。
2. `io.xdag.chain.ingest.PreValidator` / `PreValidated` / `IngestPipeline`：并行预验证 + 按到达顺序的单线程提交（§3.3）。
3. `BlockchainImpl.tryToConnect(PreValidated)` 重载与 `canUseInput` 的结果注入；`checkNewMain()` 增量缓存（§3.4）。
4. `SyncManager.validateAndAddNewBlock` 改为投递到流水线（去掉重复解析）；`chain.ingest.threads = 0` 时回退到今天的同步路径（§3.5）。
5. 配置键、`Kernel` 装配与关机顺序、`Kernel.testStart` 的 try/catch（SP0a 规格 §12.2 G3）（§3.5）。
6. 等价性、保序、写后、崩溃前缀、增量回走、sums 六组测试；基准新增 `pipeline.rN` 行与验收；文档与记忆同步（§4、§5）。

### 1.2 不包含

- 任何影响 `CHAIN_L1` 状态哈希或全网判定的改动（P1：预验证只能加速通过，不能提前拒绝）。
- 锁内并行（按发送方分片的多把锁）——它要重写 `tryToConnect` 的共享状态，超出"节点本地、判定不变"的边界，留待将来。
- apply 路径（`setMain → applyBlock`）的优化：基线结论 4 说它约 5.7 ms/付费块，但它每 64 s 一次、且与共识状态转移绑定，属于另一个子项目；本子项目只保证它在**直写模式**下与今天逐字节相同。
- 孤块池分队列、配额、TTL、导入期费率策略（SP0b-3）；"Balance checker" WARN 日志（apply 路径）。
- `AddressStore`、`CHAIN_L1`、`TXHISTORY` 的写后化：它们只在 apply 路径写，而 apply 路径按 §3.2 直写。

## 2. 原则

- **P1 判定不变**：任何块经流水线与经今天的 `tryToConnect(Block)` 得到相同的 `ImportResult`、相同的库内容；预验证结果只是"已算出的事实"，拒绝逻辑仍在锁内。
- **P2 前缀一致**：磁盘上的四个库永远等于导入写流的一个前缀（组粒度）；共识状态转移（`setMain`/`unSetMain`/`unWindMain`/修复/快照导入）在排空之后以直写模式执行，崩溃形态与 SP0b-1 竣工时逐字节相同。
- **P3 不丢块、不静默失败**：队列满即背压（阻塞投递方），写失败即停机；没有任何"丢掉最旧的块"的路径。
- **P4 节点本地**：所有新配置键都是节点本地的，不进任何 `.conf`；关闭流水线（`chain.ingest.threads = 0`）的节点与开启的节点对同一块序列得到同一结果。
- **P5 顺序不变**：提交进锁的顺序等于块到达 `validateAndAddNewBlock` 的顺序。

## 3. 设计

### 3.1 数据流

```
netty 线程 ──NewBlockMessage / SyncBlockMessage（解码时已 new Block(new XdagBlock(bytes))）──►
   XdagP2pHandler.processNewBlock / processSyncBlock（isSyncOld 判定不变）
   ──BlockWrapper──► SyncManager.validateAndAddNewBlock(bw)
        │  chain.ingest.threads == 0：今天的同步路径（importBlock → tryToConnect(Block)），不再重解析
        │  否则：IngestPipeline.submit(bw)  ——有界接收队列 chain.ingest.queue（默认 4096），满则阻塞
        ▼
   PreValidator 线程池（chain.ingest.threads，默认 = 可用核数）
        每块：block.getHash()、block.verifiedKeys()（ECDSA）、ChainBlockClassifier.classify（RAW 块）
        → PreValidated{ seq, bw, block, hashLow, keys, classified, error }
        │  按 seq（到达序号）重排：完成顺序 ≠ 到达顺序时后完成的等前面的
        ▼
   提交线程（单线程 "xdag-ingest-commit"）：SyncManager.importPreValidated(pv)
        → blockchain.tryToConnect(pv)                      [全局锁]
        → 原有 syncPopBlock / syncPushBlock / distributeBlock 逻辑不变
        │  写：TIME/BLOCK/INDEX（block、BlockInfo、高度键、XdagStats、top）、ORPHANIND（dealOrphan）
        ▼
   WriteBehindKVSource（一条全局有序写流，chain.persist.maxPending 默认 4096 条）
        写入线程 "xdag-persist"：按库分组合成 batchWrite；≥ chain.persist.flushEntries（256）或
        ≥ chain.persist.flushMs（20）落一次；sums 脏键随每次落盘各写一次
```

- `tryToConnect(Block)` 保留：本地出块（`createNewBlock`/`checkMineAndAdd`）、测试基座 `mineMain`、修复工具、快照导入继续走它；实现为 `tryToConnect(PreValidator.inline(block))`，即在调用线程上算同样的事实再进锁。
- `syncPopBlock` 递归重导 `syncMap` 里等父块的子块时调用 `importBlock(bw)`：这些块进 `tryToConnect(Block)` 路径（在提交线程上、锁内），不再二次投递；它们的验签因此在锁内做——这与今天相同，且只发生在补父块的少数路径上。

### 3.2 `WriteBehindKVSource`（`io.xdag.db.rocksdb`）

包住一个 `RocksdbKVSource`，实现 `KVSource<byte[], byte[]>`。四个实例共享一个 `WriteBehindQueue`（全局有序队列 + 写入线程），保证跨库顺序。

- **写**：`put/delete/batchWrite` 把 `(source, key, value|tombstone)` 追加到全局队列，同时写进本库的待写 map（`ConcurrentHashMap<ByteKey, byte[]|TOMBSTONE>`）。`batchWrite` 的多条作为一个连续段追加。队列长度 ≥ `maxPending` 时追加阻塞（背压）。
- **读**：`get` 先查待写 map（命中墓碑返回 null，命中值直接返回），未命中再查 RocksDB。INDEX 实例上另有 LRU 读缓存（`chain.persist.readCache` 条，默认 65536）：`get` 未命中待写 map 且命中 LRU 时直接返回；所有写路径（含 bypass 直写）同步更新 LRU，所以缓存永不脏。`prefixKeyLookup`/`fetchPrefix`/`keys()`/迭代类方法先 `flushSync()` 再委托——它们不在导入热路径上（`getBlockByTime`、`listMainBlocksByHeight`、`loadSum`、孤块队列启动重建）。
- **写入线程**：取队列头部一段（直到队列空或达到 `flushEntries`），按库分组，每库一次 `batchWrite`（RocksDB 按库原子），按库顺序 INDEX → TIME → BLOCK → ORPHANIND 之外还要保证**段内跨库顺序**：实现为按队列顺序切分成"同库连续段"逐段 `batchWrite`，段与段之间不重排。落盘成功后从待写 map 移除（只移除仍指向同一版本的条目：写后又被覆盖的键保留新值）。
- **bypass 直写模式**：`flushSync()` 排空队列后，调用线程设置 `bypass` 标记；此后该线程的 `put/delete` 直接写 RocksDB（并更新读缓存），不进队列。`setMain`、`unSetMain`、`unWindMain`、`repairUnwindTo`、`initSnapshotJ` 的快照导入、`ChainRepairTool` 入口、`Kernel.testStop`/`stop`、`--makesnapshot` 都在 `flushSync()` 之后以 bypass 执行；锁在手、队列为空，没有交错。**这就是 SP0b-1 的 `saveXdagStatus → 完成标记`、G2 的 BI_APPLIED 与 CHAIN_L1 先后、四条一致性规则、`--repairchain` 全部不需要改动的原因。**
- **失败**：`batchWrite` 抛出即把队列置为 `failed`（记录异常），此后 `submit`/`put`/`flushSync()` 抛 `IllegalStateException(cause)`，`IngestPipeline` 停止取块，节点按今天 put 抛出的方式停机。不允许静默丢写。
- **崩溃语义（P2）**：磁盘 = 写流的一个前缀（组粒度），最多丢 `maxPending` 条写或 `flushMs`。丢掉的块只是"不在"（同步会重新拿，`NO_PARENT` 逻辑不变）；`XdagStats`、top、`BlockInfo` 都在同一条流里且在块之后写，所以磁盘上的 top 一定指向磁盘上存在的块，`nblocks` 等计数与前缀一致。共识状态转移直写，因此 `LAST_COMPLETED_MAIN`/`MAIN_IN_FLIGHT` 的写入时机与 SP0b-1 相同。

### 3.3 `PreValidator` / `PreValidated` / `IngestPipeline`（`io.xdag.chain.ingest`）

```java
public record PreValidated(long seq, BlockWrapper wrapper, Block block, Bytes32 hashLow,
                           List<PublicKey> keys, Classified classified, Throwable error) {
    public boolean hasKeys() { return error == null && keys != null; }
}
```

- `PreValidator.inline(Block)`：在调用线程上算一遍（`tryToConnect(Block)` 用）。`PreValidator.submit(seq, bw)`：线程池任务。做的事只有**纯函数**：`getHash()`（缓存原始字节的哈希）、`verifiedKeys()`（块内公钥 × 签名的 ECDSA，与 `AddressStore` 无关）、`ChainBlockClassifier.classify`（只看本块）。任何异常存进 `error`，`keys = null`。
- `IngestPipeline`：`submit(bw)` 分配 `seq` 并入接收队列；预验证完成后进 `PriorityBlockingQueue<PreValidated>`（按 `seq`），提交线程只取 `seq == nextSeq` 的那一个（否则等待），保证 P5。线程名 `xdag-ingest-<n>`、`xdag-ingest-commit`；`start()`/`stop()` 由 `SyncManager.start()/stop()` 调用；`stop()` 先停接收、等提交线程把已接收的全部提交完、再 `flushSync()`。
- `chain.ingest.threads = 0`：`IngestPipeline` 不创建，`validateAndAddNewBlock` 走 `importBlock(bw)`，但 `importBlock` 不再 `new Block(new XdagBlock(bytes))`（netty 解码已解析；`BlockWrapper.getBlock()` 就是那个 `Block`）。这也是基线结论 3 里那 4–6% 的来源。

### 3.4 锁内路径：`tryToConnect(PreValidated)` 与"其余"的压缩（`BlockchainImpl`）

- **判定不变，只替换计算**：`canUseInput(Block, List<PublicKey> keysOrNull)`——`keys == null` 时调用 `block.verifiedKeys()`（今天的行为），否则直接用。`verifyBlockSignature` 的公钥哈希比对、非地址输入的 `verifySignature`（读输入块，必须在锁内）、费用/链接/时间/孤块池上限/存在性检查全部原样。
- **`checkNewMain()` 增量化**：今天每次导入都从 top 沿 `getMaxDiffLink` 回走到上一个 BI_MAIN，步数随该主块之后的最佳块数增长，10k 块/s 下每个 epoch 会 O(n²)。改为缓存 `(topHash, p, i, chainVersion)`：
  - 新 top 的最大难度链接就是缓存的 `topHash` 且 `chainVersion` 未变 → `i' = i + (新 top 带 BI_MAIN_CHAIN ? 1 : 0)`，`p' = (p != null) ? p : (新 top 带 BI_MAIN_CHAIN ? 新 top : null)`，O(1)；
  - 否则全走一遍并重建缓存；
  - `chainVersion` 在 `setMain`、`unSetMain`、`unWindMain`、`updateNewChain`、任何写 BI_MAIN_CHAIN/BI_MAIN 标志的地方 +1。
  - 调用点、时间条件（`ct ≥ p.ts + 2·1024`、`i > 1`、BI_REF）一字不改；`CheckNewMainIncrementalTest` 断言每次调用的 `(p, i)` 与全走相同。
- **sums 内存写回**：`BlockStoreImpl.saveBlockSums` 改为更新内存里的 4 KB 数组（按文件名键，`ConcurrentHashMap<String, MutableBytes>`，首次从 RocksDB 懒加载）并记脏键；写入线程每次落盘把脏键各 `putSums` 一次（进同一批）。`loadSum`/`getSums` 读缓存（缓存缺失时读库）。每块省 4 次 get + 4 次 put。
- **每次导入的 `saveXdagStatus`/`saveXdagTopStatus`**：调用不改，自动进写流。
- **日志**：`OrphanBlockStoreImpl` 每次导入一条队列统计 INFO（基线窗口 118,603 行）降为 DEBUG。
- **不动**：`processExtraBlock`、孤块池上限判定、`dealOrphan`、`onNewTxHistory`。

预算（每付费块，锁内，基线 M1 Pro）：验签 109 → 0；persist 217 → ≈15（队列追加 + sums 内存更新）；其余 132 → ≈60（回走 O(1)、读命中、日志降级）；合计 ≈75 µs → 上限约 13k 块/s。预验证每块约 125 µs（解析已在 netty 侧；哈希 + ECDSA），8 线程约 60k/s，不是瓶颈。

### 3.5 配置、生命周期与错误处理

| 键 | 默认 | 校验 | 含义 |
|---|---|---|---|
| `chain.ingest.threads` | 可用核数 | ≥ 0 | 预验证线程数；0 = 关闭流水线（回退开关） |
| `chain.ingest.queue` | 4096 | ≥ 1 | 接收队列上限（背压） |
| `chain.persist.maxPending` | 4096 | ≥ 1 | 写流待写上限（背压） |
| `chain.persist.flushMs` | 20 | ≥ 1 | 落盘最大延迟 |
| `chain.persist.flushEntries` | 256 | ≥ 1 | 落盘批大小 |
| `chain.persist.readCache` | 65536 | ≥ 0 | INDEX 读缓存条数；0 = 关闭 |

全部节点本地，只在 `AbstractConfig.getSetting()` 读，`ChainSpec` 上加 getter，不进任何 `.conf`；非法取值拒绝启动（与 `chain.consistency.window` 同一套写法）。

- **装配**：`Kernel.testStart` 在 `new RocksdbFactory(config)` 之后、`BlockStoreImpl.forNode(dbFactory)` 之前，把四个库包成写后实例（`WriteBehindKVSource.wrapNode(dbFactory, queue)` 返回一个仍实现 `DatabaseFactory` 的包装工厂，`forNode` 不改）。`--repairchain`、`--makesnapshot`、测试里的离线打开不包（直写）。`Kernel.testStart` 补 try/catch：任一步失败关闭已打开的库再抛（G3）。
- **关机顺序**：`SyncManager.stop()`：停接收 → 等提交线程排空 → `flushSync()`；然后 `Kernel` 关库。`Kernel.testStop` 的 `saveXdagStatus` 在 `flushSync()` 之后直写。
- **预验证异常**：不丢块——`PreValidated.error` 非空时提交线程走 `tryToConnect(Block)`，锁内原逻辑给出与今天相同的结果（畸形块今天也是在 `tryToConnect` 里被拒）。
- **写入失败**：见 §3.2；致命。
- **背压**：接收队列满时 netty 线程阻塞在 `submit`（今天 `validateAndAddNewBlock` 是同步阻塞，等价）；写流满时提交线程阻塞在 `put`。

### 3.6 与 SP0b-1 的关系

- 标记 `0xb0/0xc0`、`ChainConsistencyCheck`、`ChainRepairTool`、`--repairchain`、`MakeSnapshotEndToEndTest` 全部不改：共识状态转移直写（§3.2）。
- 启动一致性检查在 `BlockchainImpl` 构造器里读库：此时写流为空（还没导入），读到的就是磁盘前缀。
- `ChainL1ReorgPropertyTest`、`ChainL1UnwindFeeTest` 等 SP0b-1 测试不改、必须全绿：它们的 apply/unwind 路径在直写模式下与今天相同。
- 基准 harness 加一行 `pipeline.rN`（§4），`direct`/`syncPath`/`confirmed`/`phase.*` 行保留。

## 4. 测试与验收

| 测试 | 断言 |
|---|---|
| `IngestEquivalenceTest`（基座；`io.xdag.chain.ingest`） | 同一随机块序列（复用 `BenchWorkload`，固定 seed，含 chunk、NO_PARENT 乱序注入），经流水线与经 `chain.ingest.threads=0` 同步路径：逐块 `ImportResult` 相同；排空后 INDEX/TIME/BLOCK/ORPHANIND 键值集逐字节相同；`XdagStats`、top 相同 |
| `IngestOrderingTest` | 注入慢预验证线程使完成顺序乱序，提交顺序仍等于到达顺序；`stop()` 后已接收的块全部提交 |
| `WriteBehindKVSourceTest` | put 未落盘即可 get；delete 墓碑；覆盖写保留新值；前缀遍历前排空；bypass 直写不进队列且更新读缓存；写失败后 put/flush 抛 `IllegalStateException`；`flushSync` 幂等；背压阻塞与唤醒 |
| `WriteBehindPrefixTest` | 在写流任意位置切断（不落盘直接关库，用可控写入线程），重开后：库内容是写流前缀；top 指向的块存在；`nblocks` 等于前缀里的块数；`ChainConsistencyCheck` 为 clean（导入路径不产生主块） |
| `CheckNewMainIncrementalTest` | 随机块序列 + 分叉 + `setMain`/unwind 混合下，增量缓存与全走对每次调用给出相同 `(p, i)`；`chainVersion` 变化后回退全走 |
| `SumsCacheTest` | 写回后 `loadSum`/`getSums` 与旧实现逐字节相同；脏键只在落盘时写 |
| SP0b-1 全部测试、`ChainL1ReorgPropertyTest` 8 个种子 | 不改，全绿 |
| `ChainL1ImportBenchmarkTest` | 新增 `pipeline.rN`（经 `IngestPipeline`，含线程池；blocks/s 分母付费块 + chunk，与基线同口径）；**验收：三轮中位 ≥ 10,000 块/s**，同一台 M1 Pro、安静条件；`direct`/`syncPath` 行保留做前后对比；结果写 `docs/benchmarks/<date>-l1-import-pipeline.md` |

## 5. 文件清单

**新增（main）**：`db/rocksdb/WriteBehindKVSource.java`、`db/rocksdb/WriteBehindQueue.java`、`chain/ingest/PreValidator.java`、`chain/ingest/PreValidated.java`、`chain/ingest/IngestPipeline.java`。
**改动（main）**：`BlockchainImpl`（`tryToConnect(PreValidated)`、`canUseInput` 注入、`checkNewMain` 缓存、`chainVersion`、共识转移处的 `flushSync()`+bypass）、`BlockStoreImpl`（sums 写回）、`SyncManager`（投递、不再重解析、start/stop）、`Kernel`（装配、G3、关机顺序）、`ChainSpec`/`AbstractConfig`（六个键）、`OrphanBlockStoreImpl`（日志级别）、`XdagCli`（修复/快照入口直写，无功能改动）。
**新增（test）**：上表六个测试类；`ChainL1ImportBenchmarkTest` 的 `pipeline` 行。
**文档**：`docs/benchmarks/<date>-l1-import-pipeline.md`；路线图 §5.2 同步；SP0a 规格 §12.2 G3 关闭；`.claude/docs/chain-l1-foundation.md` §9.2。

## 6. 风险与开放问题

- **R1 读穿透覆盖面**：`KVSource` 的所有读方法都必须走包装（含迭代类）；`WriteBehindKVSourceTest` 用接口反射枚举方法逐个断言，漏一个就是脏读。
- **R2 待写 map 的内存**：`maxPending = 4096` 条 × 最大 512 B 值，上限 ≈ 2 MB，可忽略；sums 缓存按文件名键增长（每 epoch 常数个），可忽略。
- **R3 `syncPopBlock` 递归在锁内验签**：补父块场景下子块仍在锁内做 ECDSA；只影响该场景的吞吐，不影响正确性。若基准显示占比可观，后续把 `syncMap` 里的子块也预验证。
- **R4 增量回走的缓存失效**：任何遗漏的 BI_MAIN_CHAIN 写点都会让缓存给出错误的 `(p, i)`；`CheckNewMainIncrementalTest` 的随机场景 + `chainVersion` 在 `updateBlockFlag` 里统一 +1（而不是在各调用点）来封住。
- **R5 验收数字的机器条件**：与基线同一台机器、`pgrep` 无其他 Maven；若达不到 10k，报告实测值与锁内分段（基准的 `phase.*` 行）后再决定是否进入锁内并行（§1.2）。

## 7. 与路线图 §5.2 的偏差汇总

| 路线图 | 本规格 | 原因 |
|---|---|---|
| 每块一个 WriteBatch | 有序写后 + 读穿透 | 10k 需要 persist 离开锁的临界路径；前缀语义保住 SP0b-1 |
| 按发送方/chainId 多队列、队列间乱序 | 并行预验证、按到达顺序单队列提交 | 锁是单把，乱序无吞吐收益，只引入跨节点顺序差异 |
| 目标"四项做完" | ≥ 10k 块/s | 用户决定，M3 指标提前 |
| `saveBlockInfo` 保持独立同步写 | 进写流；apply 期间处于直写模式 | 统一一条流，apply 路径语义不变 |
