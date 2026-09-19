# SP0b-2：锁外预验证流水线与写后落盘 设计规格

> 状态：设计稿 v1（2026-09-18，用户已逐节确认 §1–§5 的设计口径）→ **竣工同步 v2（2026-09-19）**：§1、§3、§4、§5、§6、§7 已按 `dev-dag-contract` 上的实现（提交 `404c1c7c`…`324c81af`，21 个，清单见实施计划头部的"执行记录"）改写；与 v1 的偏差在各节开头或条目内以"**偏差**"标出。**本文与代码不一致时以代码为准。** 实测数字只在 `docs/benchmarks/2026-09-19-l1-import-pipeline.md`，本文除验收结论（§4.3）外不引用任何数字。
> 上级文档：总体设计与路线图 `2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md` §5.2；SP0b-1 竣工规格 `2026-09-17-xdag-chain-sp0b1-benchmark-and-hardening-design.md`；基线 `docs/benchmarks/2026-09-18-l1-import-baseline.md`。
> 与路线图 §5.2 的偏差（用户 2026-09-18 决定）：(a) 验收目标从"做完四项"提高到 **≥ 10,000 块/s**（M3 的导入指标提前到本子项目）；(b) 落盘不是"每块一个 WriteBatch"而是**有序写后（write-behind）写入线程 + 读穿透待写缓存**；(c) 保序不是"按发送方/chainId 多队列可乱序"而是**并行预验证、按到达顺序单队列提交**。原因见 §2 与 §7。
> **验收结论（先说）：目标未达成**——`pipeline` 三轮中位 7157 块/s，是 10,000 的 71.6%。已交付的收益、不达标的非循环论证上界、以及下一步的目标见 §4.3。

---

## 0. 一句话

把 `tryToConnect` 锁内每块的工作压下去：ECDSA 验签移到锁外线程池并行做；块、`BlockInfo`、统计、孤块索引的写入改为追加到一条有序写流、由单个写入线程合成 `batchWrite` 落盘、所有读先查待写缓存；`checkNewMain()` 的回走增量化；sums 改为内存写回。判定逻辑一字不改，崩溃后磁盘永远是写流的一个前缀，SP0b-1 的启动门与修复命令不需要任何改动。

## 1. 范围

### 1.1 包含（as-built）

1. `io.xdag.db.PersistControl`（接口，**v1 没有单列**）+ `io.xdag.db.rocksdb.WriteBehindQueue` / `WriteBehindKVSource` / `WriteBehindFactory`：包住 INDEX / BLOCK / TIME / ORPHANIND 四个库的有序写后层（§3.2）；INDEX 上的读缓存；共识状态转移的排空 + 直写模式。
2. `BlockStoreImpl` 的 sums 内存写回与 `BlockStore.flushSums()`（§3.4）。
3. `io.xdag.chain.ingest.PreValidator` / `PreValidated` / `IngestPipeline`：并行预验证 + 按到达顺序的单线程提交（§3.3）。
4. `BlockchainImpl.tryToConnect(PreValidated)`（并提到 `Blockchain` 接口上，带默认实现）、`canUseInput` 的结果注入与**空输入短路重排**、`checkNewMain()` 的候选缓存与 `chainVersion`（§3.4）。
5. `SyncManager.submitBlock` / `importPreValidated` 与 `XdagP2pHandler` 的投递接线；六个节点本地配置键；`Kernel` 装配、关机顺序与 `testStart` 的 try/catch（SP0a 规格 §12.2 G3）（§3.3、§3.5）。
6. 写后、崩溃前缀、sums、配置、增量回走、保序/等价性/块隔离九组测试；基准新增 `pipeline.rN` 行与 `-Dxdag.bench.writeBehind` 开关；`OrphanBlockStoreImpl` 两条每次导入都打的 INFO 降为 DEBUG；文档与记忆同步（§4、§5）。

### 1.2 不包含

- 任何影响 `CHAIN_L1` 状态哈希或全网判定的改动（P1：预验证只能加速通过，不能提前拒绝）。
- 锁内并行（按发送方分片的多把锁）——它要重写 `tryToConnect` 的共享状态，超出"节点本地、判定不变"的边界，留待将来。
- apply 路径（`setMain → applyBlock`）的优化：它每 64 s 一次、且与共识状态转移绑定，属于另一个子项目；本子项目只保证它在**直写模式**下与今天逐字节相同。
- 孤块池分队列、配额、TTL、导入期费率策略（SP0b-3）；"Balance checker" WARN 日志（apply 路径）。
- `AddressStore`、`CHAIN_L1`、`TXHISTORY` 的写后化：它们只在 apply 路径写，而 apply 路径按 §3.2 直写。**这一条在验收之后成了下一个任务的头号线索**：ADDRESS 既没有包写后层、也没有读缓存，而链接校验每块要读它两次（§4.3）。
- **锁内"其余"那一坨**（校验里的 AddressStore 读、孤块池增删、难度与统计更新）：本 SP 没有动它，它正是没到 10k 的原因（§4.3）。

## 2. 原则

- **P1 判定不变**：任何块经流水线与经今天的 `tryToConnect(Block)` 得到相同的 `ImportResult`、相同的库内容；预验证结果只是"已算出的事实"，拒绝逻辑仍在锁内。
- **P2 前缀一致**：磁盘上的四个库永远等于导入写流的一个前缀（组粒度）；共识状态转移（`setMain`/`unSetMain`/`unWindMain`/修复/快照导入）在排空之后以直写模式执行，崩溃形态与 SP0b-1 竣工时逐字节相同。
- **P3 不丢块、不静默失败**：队列满即背压（阻塞投递方），写失败即停机；没有任何"丢掉最旧的块"的路径。唯一的例外被显式建模并上报：提交线程本身死掉（§3.3 的 `SubmitRejectedException`）。
- **P4 节点本地**：所有新配置键都是节点本地的，不进任何 `.conf`；关闭流水线（`chain.ingest.threads = 0`）或关闭写后层（`chain.persist.maxPending = 0`）的节点与开启的节点对同一块序列得到同一结果。
- **P5 顺序不变**：提交进锁的顺序等于块到达投递入口的顺序。
- **C1（as-built 新增）单写者**：写后层包住的四个库，其**所有**写路径都必须持在同一把锁——区块链监视器（`BlockchainImpl` 的 `this`）——之下。直写模式的正确性完全建立在这上面（§3.2）。为此本 SP 把两处历史上不持锁的写搬进了监视器：`BlockchainImpl.checkMain()` 整个方法改成 `synchronized`（原来只有里面的 `checkNewMain()` 是），孤块清理器 `cleanExpiredOrphans` 整个方法体放进 `synchronized (blockchain)`。

## 3. 设计

### 3.1 数据流（as-built）

```
netty 线程 ──NewBlockMessage / SyncBlockMessage（解码时已 new Block(new XdagBlock(bytes))）──►
   XdagP2pHandler.processNewBlock / processSyncBlock（isSyncOld 判定不变）
   ──BlockWrapper──► XdagP2pHandler.submitBlock(bw) ──► SyncManager.submitBlock(bw)   [不 synchronized]
        │  pipeline == null（chain.ingest.threads == 0）：今天的同步路径
        │      → validateAndAddNewBlock(bw) → importBlock(bw) → tryToConnect(重解析出的 Block)
        │  否则：IngestPipeline.submit(bw) ——信号量 chain.ingest.queue（默认 4096）个在飞槽位，满则阻塞
        ▼
   PreValidator 线程池（chain.ingest.threads，默认 = 可用核数，线程名 xdag-ingest-<n>，daemon）
        每块：从 wrapper 的 512 字节**重新解析出一份私有副本**，再算 parse / getHashLow().copy()
              / verifiedKeys()（仅当块有输入）/ ChainBlockClassifier.classify
        → PreValidated{ seq, wrapper, block(私有副本), hashLow, keys, classified, error }
        │  按 seq（到达序号）在 PriorityQueue 里重排
        ▼
   提交线程（单线程 "xdag-ingest-commit"，daemon）：SyncManager.importPreValidated(pv)  [synchronized]
        → blockchain.tryToConnect(pv)                      [区块链监视器]
        → 原有 relay（distributeBlock）与 syncPopBlock / syncPushBlock 逻辑不变
        │  写：TIME/BLOCK/INDEX（block、BlockInfo、高度键、XdagStats、top）、ORPHANIND（dealOrphan）
        ▼
   WriteBehindKVSource ×4（一条全局有序写流）
        写入线程 "xdag-persist"（非 daemon）：按"同库连续段"切分成 run，逐段 batchWrite；
        一组 ≥ chain.persist.flushEntries（256）、遇到 barrier、或最老条目满
        chain.persist.flushMs（20）时落一次
        sums 不走这条流：由 BlockStoreImpl 自己按 §3.4 的触发点写回（写回本身会进这条流）
```

- `tryToConnect(Block)` 保留：本地出块（`createNewBlock`/`checkOrphan`/`checkMineAndAdd`）、测试基座 `mineMain`、修复工具、快照导入继续走它；实现为 `tryToConnect(PreValidator.inline(block))`——在调用线程上算同样的事实（**不复制块**）再进锁。
- **偏差（v1 §3.1 第二条）**：`tryToConnect(Block)` 现在**不再是 `synchronized` 方法**，而是一行转发；`synchronized` 挪到 `tryToConnect(PreValidated)` 上。副作用：`inline()` 在拿锁之前就把 `parse()`、`getHashLow()` 与 ECDSA 算掉，因此**一个会被早早拒掉的块（EXIST、类型/时间非法、孤块池满）现在要付一次它以前不付的验签**。缓解：`inline()` 只对**有输入**的块算公钥，chunk / 链接块 / 主块一分钱不付。
- `syncPopBlock` 递归重导 `syncMap` 里等父块的子块时仍调用 `importBlock(bw)`：这些块在提交线程上、锁内验签——与今天相同，且只发生在补父块的少数路径上。

### 3.2 写后落盘层（`io.xdag.db.PersistControl` + `db/rocksdb/WriteBehind*`）

> **偏差（v1 §3.2 整节）**：类的切分多了一层（`PersistControl` 接口 + `WriteBehindFactory` 包装工厂）；"bypass 直写"改名并改语义为 `PersistControl.direct(body)` 且**排空是惰性的**（见下）；跨库顺序不是"按固定库序 + 段内保序"而是**严格按队列顺序切 run**，并且 run 还要在"同一 run 内 put 撞上先前 delete"处切开；失败处理多了一个**失败处理器 → 节点停机**的闭环；多了**手动模式**（无写入线程）供测试与"线程未启动"场景使用。

**`PersistControl`（`io.xdag.db`）** —— 区块链只依赖这三个方法，`NONE` 是三者皆恒等（没装写后层的节点、离线工具、绝大多数测试就是这个）：

| 方法 | 语义 |
|---|---|
| `flushSync()` | 阻塞到调用之前排进流里的每一条写都已经交给数据库（RocksDB WAL，不 fsync；耐久性与 SP0b-2 之前一致）。已失败则抛 `IllegalStateException` |
| `direct(body)` | 在调用线程上以直写模式跑 `body`：它的写绕开队列（返回时已在库里、且按程序序），前面排着的队列内容先被排空。可重入 |
| `isBypass()` | 当前线程是否处于直写模式（`NONE` 恒 `true`） |

**`WriteBehindQueue(maxPending, flushEntries, flushMs, threaded)`** —— 一条全局有序队列 + 一个写入线程，被一个节点的四个 `WriteBehindKVSource` 共享，**跨库顺序由这条队列保证**。

- **写（`enqueue`）**：在队列锁的**同一个临界区**里做三件事——分配版本号 `version`、把 `(value, version)` 记进本库的待写 map 并同步更新读缓存（`recordPending`）、把条目追加到队列尾。三者同锁，所以待写 map 与写流永远不会对"两次写谁在前"有分歧。队列长度 ≥ `maxPending` 时生产者阻塞（背压）；**没有写入线程时（手动模式，或线程尚未 `start()`）生产者自己排掉一组**，而不是永远停在那里。
- **写入线程（`loop` → `drainOnce`）**：一次取一组（最多 `flushEntries` 条，或到第一个 barrier 为止），把这一组切成 **run**——同库的连续条目为一段，**并且**在"本 run 内已删过的键又被 put"处强制切开（`RocksdbKVSource.batchWrite` 先做所有 put 再做所有 delete，不切开就会把后到的 put 删掉）；run 按顺序逐个 `batchWrite`。**同一时刻只有一组在飞**（`inFlight` 0/1），所以数据库看到的永远是写流的一个前缀。**条目永不按库或按键合并**——合并就破坏前缀性质。导入的写模式是每块在几个库之间来回切（TIME → BLOCK → INDEX → …），所以 run 普遍很短：**这一层挣的是异步，不是批量**，调 `flushEntries` 之前先测。
- **读（`get`）**：先采样本库的写世代 `writes`（**必须在所有查找之前采样**），再查待写 map（命中墓碑返回 null、命中值直接返回），再查读缓存（只有 INDEX 实例有，`chain.persist.readCache` 条 LRU），最后查 RocksDB。**未命中回填读缓存时要在队列锁里再核一次世代**：世代变了或这个键已经有待写条目，就不回填——否则可能把一份比刚写下去的更旧的值钉进缓存。迭代类方法（`keys` / `prefixKeyLookup` / `fetchPrefix` / `prefixValueLookup` / `prefixKeyAndValueLookup`）先 `flushSync()` 再委托；它们不在导入热路径上。
- **直写模式（`direct`）**：`bypass` 与 `drained` 都是 **ThreadLocal**。进入时只把 `bypass` 立起来，**排空推迟到 body 的第一次写**（或第一次迭代读）——那一次写里的 `flushSync()` 把流排干并把 `drained` 记上，于是同一 scope 内后续的 `flushSync()` 是空操作。顺序语义不变（直写永远越不过排在它前面的队列内容），变的是**一个什么都不写的 body 不再排空**。这不是优化洁癖：`unWindMain` 对绝大多数导入的块都会进入而几乎从不写，急切排空会让写流每导入一个块就被清空一次，这一层就白做了（代码里标为 **I1**）。
  - **前提（写在 `PersistControl.direct` 的 Javadoc 里）**：调用者必须持有排除掉这四个库其它一切写者的锁——在节点上就是区块链监视器。`direct` 自己**不**阻塞并发生产者：body 执行期间别的线程排进来的写排在排空之后，可能覆盖 body 的直写。这就是 §2 C1 那条"所有写都要在监视器下"的由来。
- **共识状态转移**：`BlockchainImpl` 里六个方法各自包一层 `persist.direct(…)`，里层 `…Direct` 方法体与 SP0b-2 之前**逐字节相同**：`initSnapshotJ`、`unWindMain`、`reconcileTipTo`、`repairUnwindTo`、`setMain`、`unSetMain`。**这就是 SP0b-1 的 `saveXdagStatus → 完成标记`、四条一致性规则、`--repairchain`、快照门全部不需要改动的原因。** 离线工具（`--repairchain`、`--makesnapshot`）与测试基座默认开的是裸 `RocksdbFactory`，`PersistControl` 是 `NONE`，本来就直写。
- **失败**：任何一次写抛出（以及写入线程被中断）都会把队列置为 `failed`：记下第一个原因、唤醒**每一个**等待者（背压中的生产者、等 barrier 的 flusher、等在飞组的人），此后 `put`/`delete`/`flushSync`/`start` 一律抛 `IllegalStateException(cause)`，`drainOnce()` 返回 false。**失败的队列永不恢复。** 队列在锁外、唤醒完所有人之后调一次失败处理器；`Kernel` 装的处理器（`onPersistFailure`，代码里标为 **I2**）另起一条非 daemon 线程 `xdag-persist-failure` 跑 `testStop()` 再 `System.exit(1)`——**写后落盘失败现在会让节点停机**，而不是像以前那样每次 put 记一行错误继续跑。
- **手动模式（`threaded == false`）**：没有写入线程；`flushSync()` 在调用线程上排空，撞到 `maxPending` 的生产者自己排一组，测试用 `drainOnce()` 单步推进。一条 threaded 队列在 `start()` 之前与 `stop()` 之后表现相同（并打一次 warn）。
- **崩溃语义（P2）**：磁盘 = 写流的一个前缀（组粒度），最多丢 `maxPending` 条写加一组，或 `flushMs` 的时间窗。丢掉的块只是"不在"（同步会重新拿，`NO_PARENT` 逻辑不变）；`XdagStats`、top、`BlockInfo` 都在同一条流里且在块之后写，所以磁盘上的 top 一定指向磁盘上存在的块。共识状态转移直写，因此 `LAST_COMPLETED_MAIN`/`MAIN_IN_FLIGHT` 的写入时机与 SP0b-1 相同。
- **`WriteBehindFactory`**：只包 `INDEX / BLOCK / TIME / ORPHANIND` 四个库（`WRAPPED`），读缓存只加在 `INDEX` 上；其余（SNAPSHOT / ADDRESS / TXHISTORY / CHAIN_L1）原样透出。`close()` 先 `queue.stop()`（flush + join 写入线程），再清掉包装缓存、关委托工厂——队列失败也照关，只记一行错误。`BlockStoreImpl.forNode` 与孤块存储**一行不改**。

### 3.3 `PreValidator` / `PreValidated` / `IngestPipeline`（`io.xdag.chain.ingest`）

```java
public record PreValidated(long seq, BlockWrapper wrapper, Block block, Bytes32 hashLow,
                           List<PublicKey> keys, Classified classified, Throwable error) {
    public boolean hasKeys() { return keys != null; }
}
```

- **只算纯函数**：`parse()`、`getHashLow().copy()`、`verifiedKeys()`（块内公钥 × 签名的 ECDSA，与 `AddressStore` 无关）、`ChainBlockClassifier.classify`。任何异常存进 `error`、`keys = null`，块照样往下走（P1：预验证不做任何拒绝）。
- **偏差（v1 §3.3 与 §3.5 的"去掉重复解析"）——两处都要改**：
  1. **`chain.ingest.threads = 0` 的同步路径仍然重解析。** v1 说这条路径应该去掉 `new Block(new XdagBlock(bytes))`（并把基线结论 3 的 4–6% 算作收益）。实现**故意保留**它：一个躺在 `syncMap` 里等父块的块带着上一次尝试写下的 `BI_EXTRA` 标志（`tryToConnect` 在判 NO_PARENT 之前就会打这个标），每次尝试必须从干净的 `BlockInfo` 开始。`importBlock` 的注释写明了这一点。
  2. **那次重解析原本担着两件事，不是一件。** 除了"干净的 `BlockInfo`"，它还把**链上那份块与中继线程手里的 wrapper 块隔离开**：`tryToConnect` 会改它导入的那个块（BI_EXTRA 导入时 `setFee(ZERO)`，之后 `setMain`/`unSetMain`/`unApplyBlock` 改 fee/height，而块还活在 `memOrphanPool` 里），而中继线程同时在对 `wrapper.getBlock()` 调 `toBytes()` 重新序列化。共用一个实例就会广播出"不是收到的那串字节"（fee 在被哈希、被签名的头部里），还会在非 final 字段上竞争。所以**流水线在池线程上重新解析出一份私有副本**（`PreValidator.compute`）：同一份工作，从锁里挪到了并行的池上。`PreValidated.block()` 永远是链要导入的那一个；只有 `PreValidated.failed(seq, wrapper, error)` 这一个工厂会别名回 `wrapper.getBlock()`（副本还没做出来就炸了），所以提交方对带 `error` 的 `pv` 一律回落到 `importBlock`（它自己会按老办法造一份）。`PreValidator.inline` 则**故意不复制**：调用者自己造的块、没有别的线程在看。
- **`IngestPipeline`**：`submit(bw)` 先判拒绝、再取信号量槽位（容量 `chain.ingest.queue`）、再在锁内分配 `seq`，然后丢给线程池；预验证完成后进 `PriorityQueue<PreValidated>`（按 `seq`），提交线程只取 `seq == nextToCommit` 的那一个。`start()`/`stop()` 由 `SyncManager.doStart()/doStop()` 调；`stop()` 先停接收、等在飞的全部提交完（上限 15 s）、`pool.shutdown()`、join 提交线程（上限 5 s），超时就中断它。**单次使用**：`start()` 一次、`stop()` 一次，停过的不可重启。
- **`submit` 的两条调用方约束（写在 Javadoc 里，违反即死锁或静默损坏）**：
  1. **不得持有提交方需要的任何监视器。** 槽位从 `submit` 一直持到那个块提交返回，所以在容量处阻塞的调用者若同时持着 L，而提交方也要 L，就死锁。具体到本树：`SyncManager.validateAndAddNewBlock` 与 `importPreValidated` 都是 `synchronized`，因此**入口 `submitBlock` 绝不能是 `synchronized`**。
  2. **交出去之后不得再碰这个块。** `Block.parse()` / `getHashLow()` 是用一个普通 `boolean` 守着的惰性变更器；调用线程上的一次 `parse()` 撞上池线程的那一次，会把块的 inputs/outputs 复制一遍——是静默的、共识可见的损坏，不是崩溃。`XdagP2pHandler.submitBlock` 的注释也复述了这一条。
- **拒绝与失败模型（as-built 新增，v1 没有）**：
  - `SubmitRejectedException extends IllegalStateException`，带 `isCommitterDead()`：**启动前/`stop()` 之后**的拒绝是例行的（调用方只记 debug），**提交线程已死**的拒绝意味着这个节点从此什么都不导入，`XdagP2pHandler` 按 10 分钟节流打 WARN。判定在**取信号量之前**做：不然一条已饱和的流水线会把每一条 netty I/O 线程永久停在一个再也不会被释放的信号量上，而那条 WARN 永远到不了。
  - 提交循环退出时（`finally`）把还没提交的块的槽位**全数还回去**并置 `committerDead`：已经停在 acquire 上的投递方醒来、看到标志、被拒绝。
  - **`Error` 不会结束提交循环**：`commit()` catch 的是 `Throwable` 并只记日志，所以委托方抛出的 `Error`（包括测试里的 `AssertionError`）不终止循环。**可达的死亡路径只有中断**（`takeHead()` 里 `await` 被中断 → 返回 null → 循环结束），而中断也正是 `stop()` 超时后的最后手段。基准的提交器因此不能用 `assertTrue`（会被吞成一行日志、测试假绿），必须记下坏结果、回主线程 `fail()`。
  - 线程池拒绝任务（`pool.execute` 抛出）时**不丢块**：直接把一个带 `error` 的 `PreValidated` 发布到提交队列，避免整条流水线卡在这个 seq 后面；`Error` 仍然向上抛。
- **线程属性**：池线程与提交线程都是 **daemon**（`stop()` 负责排空；非 daemon 会让每一次漏掉的 `stop()`——启动失败、测试中断、surefire fork——变成永不退出的进程，而突然退出与 `kill -9` 等价，存储本来就容忍）。写入线程 `xdag-persist` 相反，是**非 daemon**：JVM 不能带着没落盘的写退出。

### 3.4 锁内路径的压缩（`BlockchainImpl` / `BlockStoreImpl`）

- **判定不变，只替换计算**：`canUseInput(Block, List<PublicKey> keysOrNull)`——`keys == null` 时调用 `block.verifiedKeys()`（今天的行为），否则直接用。`verifyBlockSignature` 的公钥哈希比对、非地址输入的 `verifySignature`（读输入块，必须在锁内）、费用/链接/时间/孤块池上限/存在性检查全部原样。
  - **偏差（新增收益，v1 未提）**：`canUseInput` 原来**先**算 `verifiedKeys()`、**后**判 `inputs.isEmpty()`，于是链接块与主块（流量的大头）白付一次从来没人读的 ECDSA。as-built 把空输入的短路提到验签之前。`PreValidator` 同样只对有输入的块算公钥（`keys` 保持 null，锁内那条路一字不变）。这改变了"验签在总开销里占多少"的口径，基准文档里也记了这一条。
- **`checkNewMain()` 增量化**：缓存 `(candidateTop, candidate=(p,i), candidateVersion)` + 一个 `lastFlagged`。
  - **偏差（v1 §3.4 的规则写错了，不可能命中）**：v1 写的是"新 top 的 `maxDiffLink` 就是缓存的 `topHash` **且 `chainVersion` 未变** → O(1)"。这条规则**永远不会触发**：`tryToConnect` 在把 top 挪过去之前，先给进来的块打上 `BI_MAIN_CHAIN`（`updateNewChain`），那一下就把版本加了 1。as-built 的规则是：**"…且自缓存建立以来恰好发生一次标志变更，而且就发生在如今这个 top 上"**——即 `candidateVersion + 1 == chainVersion && lastFlagged == top`。这正是每一次 best 导入的形状。
  - 完整规则：`top == candidateTop && 版本未变` → 直接用缓存；否则若上面那条"恰好一次、就在新 top 上"成立，取出 top 块，要求它**不是** `BI_MAIN` 且它的 `maxDiffLink` 等于 `candidateTop`，则 `i' = i + (新 top 带 BI_MAIN_CHAIN ? 1 : 0)`，`p' = p != null ? p : (带 BI_MAIN_CHAIN ? 新 top : null)`；其余一切（真分叉——`unWindMain` 清多个标志、`updateNewChain` 设多个标志，即 ≥ 2 次 bump；`setMain`——1 次 bump 但不在新 top 上；top 自己是主块；修复/快照 bump；冷缓存）回落到全走一遍。正确性依据：一个链接必然指向**严格更早**的时间戳（`tryToConnect` 强制），所以新 top 不可能出现在旧 top 的回走路径上，给它打标志不改变那条路径。
  - **`chainVersion` 的 bump 点**：`updateBlockFlag` 里只要 `flag` 是 `BI_MAIN` 或 `BI_MAIN_CHAIN` 就 +1 并记下 `lastFlagged = 该块`；另有 `bumpChainVersion()`（把 `lastFlagged` 清成 null，表示"这次变更不是某个块的标志变了"）用在三处非标志输入上：`initSnapshotJDirect`（快照导入直接重写标志与链接）、`reconcileTipToDirect` 与 `repairUnwindToDirect`（修复路径直接挪 top）。
  - **偏差（as-built 新增的第四个 bump 点，v1 完全没有）**：回走依赖"走过的块还读得出来"，而不只依赖标志。`removeOrphan(..., ORPHAN_REMOVE_REUSE)` 会把一个块从 `memOrphanPool` 里踢掉**而不落盘**——它就此不可加载，却没有任何标志变更记录这件事。所以 `processExtraBlock` 的这条 REUSE 分支也要 `bumpChainVersion()`。
  - `maxDiffLink` 本身不需要 bump：它在块被连接时只写一次（`calculateBlockDiff` 对已有难度的块提前返回）。
  - 调用点与判定条件一字不改：`count() <= 1` 返回；用 `c.hashLow()` **重新按哈希取一次 `BlockInfo`**（`BI_REF` 不在缓存键里，后来的块可能刚置上）；`(flags & BI_REF) != 0 && ct >= p.ts + 2·1024` 才 `setMain`。只有 `setMain` 需要 raw 块（它要走链接），所以 raw 到那一步才加载；健康库里它不可能为 null，真为 null 就跳过、下次重试（旧代码会在回走里 NPE，这里不新增这个抛出点）。
- **sums 内存写回（`BlockStoreImpl`）**：`saveBlockSums` 改为更新内存里的 4 KB 数组（`ConcurrentHashMap<String, MutableBytes>` + 脏键集合），`getSums` 先读缓存、未命中再读库（**读不回填缓存**，所以历史桶的 `loadSum` 流量撑不大它）。
  - **偏差（v1 §3.4 的触发点写错了）**：v1 说"写入线程每次落盘把脏键各写一次"。as-built 的触发点是四个，都在 `BlockStoreImpl` 自己身上：**① 每 `SUMS_FLUSH_EVERY = 256` 个保存过的块；② 保存的块换了最深那一档桶（2^24 个 1/1024 s 的 tick ≈ 4.55 h）时，先把离开的那个桶写掉；③ `BlockStore.stop()`；④ `Kernel.testStop()` 显式调 `BlockStore.flushSums()`**（以及 `Kernel.testStart` 的 G3 失败路径，免得创世导入写下的 sums 白丢）。**`saveXdagStatus(...)` 不是触发点**——它每次导入都调一次，拿它当触发点等于把批量彻底废掉。
  - **崩溃损失界**：一次没走到任何触发点的停止，会丢掉上次 flush 以来保存的那些块的贡献——少于 256 个，且全在当时正在长的那个最深桶里（换桶会先 flush）。这个损失是**永久的**（没有任何代码会给已经在盘上的块重新计 sums），后果是那个桶从此少报、传统同步协议比对 sums 时会反复钻进这个区间重新请求：**是带宽问题，不是正确性问题**（sums 从不进共识）。
  - **缓存有界**：每次 flush 之后只留下仍然脏的键 + 最后一个保存块的四个键（下一个块几乎总会复用它们）。

### 3.5 配置、生命周期与错误处理

| 键 | 默认 | 校验 | 含义 |
|---|---|---|---|
| `chain.ingest.threads` | 可用核数 | ≥ 0 | 预验证线程数；**0 = 关闭流水线**（回退开关，`SyncManager` 不建 `IngestPipeline`，走同步路径） |
| `chain.ingest.queue` | 4096 | ≥ 1 | 在飞块数上限（背压） |
| `chain.persist.maxPending` | 4096 | ≥ 0 | 写流待写上限（背压）；**0 = 关闭写后层**（`Kernel` 不包 `WriteBehindFactory`，`PersistControl` 保持 `NONE`，写法与 SP0b-2 之前逐字节相同） |
| `chain.persist.flushMs` | 20 | ≥ 1 | 一组的最大落盘延迟 |
| `chain.persist.flushEntries` | 256 | ≥ 1，且 **≤ `maxPending`**（仅当 `maxPending > 0`） | 一组的最大条目数 |
| `chain.persist.readCache` | 65536 | ≥ 0 | INDEX 读缓存条数；0 = 关闭 |

- **偏差（v1 的表写的是 `maxPending ≥ 1`）**：`maxPending = 0` 是**关闭写后层**的开关，与 `chain.ingest.threads = 0` 对称——这是运维回退到"纯同步存储"的唯一方式，规格 v1 漏了。另新增一条跨键校验：`flushEntries > maxPending` 时拒绝启动（写流最多攒到 `maxPending` 就被写入线程追上，条目阈值永远够不着，于是每一组都得白等一个 `flushMs`）。
- 六个键全部节点本地，只在 `AbstractConfig.getSetting()` 用 `readNodeLocalInt(config, key, current, min)` 读，`ChainSpec` 上加 getter 与 `DEFAULT_*` 常量，**不进任何 `.conf`**；非法取值抛 `IllegalArgumentException` 拒绝启动（与 `chain.consistency.window` 同一套写法，且**不打**共识参数那条覆盖警告——它们不是共识参数）。
- **装配（`Kernel.testStart`）**：`dbFactory = new RocksdbFactory(config)` 之后、任何 store 建起来之前，若 `chain.persist.maxPending > 0` 则 `new WriteBehindQueue(maxPending, flushEntries, flushMs, true)` 并 `dbFactory = new WriteBehindFactory(dbFactory, queue, readCache)`，`persist = queue`。**包装在 try 之外、启线程在 try 之内**：没有任何失败路径能留下一条没人管的非 daemon 写入线程。`BlockchainImpl` 在**构造器里读一次** `kernel.getPersist()`（必须在这里：快照分支会调 `initSnapshotJ()`，构造器末尾起的 check-main 循环在构造器返回之前就可能跑到 `setMain`）。
- **`Kernel.testStart` 的 try/catch（SP0a §12.2 G3，本 SP 关闭）**：任一步失败 → `isRunning.set(false)` → 按正常顺序 `stopServices()`（先停线程，再动库：在还跑着 P2P/RPC/check-main 的情况下关 RocksDB 比泄漏更糟）→ `blockStore.flushSums()` → `chainL1Store.stop()` → `dbFactory.close()`（`WriteBehindFactory.close()` 会先 flush 再停写入线程）→ 原样重抛（沿途异常挂成 suppressed）。`isRunning` 那一下是必须的：不然之后的 `testStop()` 会越过它的守卫在半成品上 NPE，重试的 `testStart()` 又会被当成"已在运行"直接返回。
- **关机顺序（as-built，v1 只写了一句）**：
  1. `stopServices()`：api → sync → **syncMgr（`IngestPipeline.stop()`：停接收、排空、join）** → pow → channelMgr → nodeMgr → p2p → client → `blockchain.stopCheckMain()` → **`orphanBlockStore.stop()`（I4：先停孤块清理器，它和 check-main 一样是会写 ORPHANIND 与统计的非 daemon 调度线程；库关掉之后再来一拍会写进关掉的 RocksDB 并在出门时毒掉写后队列）** → webSocketServer → poolAwardManager。`stopServices()` **自己不写任何东西**（它也被 G3 的失败路径调，那时半数组件还是 null，逐个判空）。
  2. `MessageQueue.timer.shutdown()`（JVM 级，不属于任何一个 kernel，所以不在 `stopServices` 里：一次失败的 `testStart` 不能让同 JVM 的下一次尝试拿到一个死掉的消息队列）。
  3. **持着区块链监视器**写最后两笔：`blockStore.flushSums()` 与 `blockStore.saveXdagStatus(...)`（`stopCheckMain()` 只等 check-main 5 秒，熬过这个等待的转移不能和这里排进队列的写撞上；sums 是批量写回的，`saveXdagStatus` 故意不触发它，这里是最后一次机会；统计那一笔是 SP0b-1 的 I3）。队列已中毒会抛，记一行错误继续——这两笔要么是建议性的、要么可再推导。
  4. `chainL1Store.stop()`；最后 **`dbFactory.close()`**——**偏差（v1 没写）**：过去是逐个 `DatabaseName` 关库，现在必须走工厂，因为只有 `WriteBehindFactory.close()` 会停掉那条非 daemon 的写入线程（顺带也不用再为了关而打开这个节点从没用过的库）。
- **预验证异常**：不丢块——`PreValidated.error` 非空时提交线程走 `importBlock`（重解析后的 `tryToConnect(Block)`），锁内原逻辑给出与今天相同的结果。
- **写入失败**：见 §3.2，致命，节点停机。
- **背压**：在飞槽位用尽时 netty 线程阻塞在 `submit`（今天 `validateAndAddNewBlock` 就是同步阻塞的，等价）；写流满时提交线程阻塞在 `put`。

### 3.6 与 SP0b-1 的关系

- 标记 `0xb0/0xc0`、`ChainConsistencyCheck`、`ChainRepairTool`、`--repairchain`、`MakeSnapshotEndToEndTest` 全部不改：共识状态转移直写（§3.2）。
- 启动一致性检查在 `BlockchainImpl` 构造器里读库：此时写流为空（还没导入），读到的就是磁盘前缀。
- `ChainL1ReorgPropertyTest`、`ChainL1UnwindFeeTest` 等 SP0b-1 测试不改、全绿：它们的 apply/unwind 路径在直写模式下与今天相同。
- **基座的一处改动（as-built）**：`ChainL1TestBase.MockBlockchain.checkMain()` 从"只调 `checkNewMain()`"改回"`synchronized` + `checkNewMain()` + `saveXdagStatus`"，即生产形状。原因是 C1 这个 bug**只因为那笔统计保存跑在 `checkNewMain` 之后**才存在（写后层下它是队列写，不能落在某次转移的直写之后），一个把它去掉的 override 会把这件事对所有基座测试藏起来。另新增一个 `protected DatabaseFactory wrapFactory(DatabaseFactory raw)` 钩子（默认恒等），供基准把写后层装到基座底下。

## 4. 测试与验收

### 4.1 新增测试（as-built 类名与 `@Test` 数）

| 测试类 | `@Test` | 断言要点 |
|---|---|---|
| `io.xdag.db.rocksdb.WriteBehindKVSourceTest` | 25 | 未落盘即可 get；delete 墓碑；同组内 put 覆盖先前的 delete；在飞组期间覆盖写留最新值；迭代前排空；直写穿透并更新读缓存；`direct` 在**第一次写**时排空、什么都不写则不排空、迭代读也触发、可重入；`batchWrite` 两条路径上的 null 值当 delete；手动模式背压在调用方排一组；写失败致命且不静默；`flushSync` 幂等；读缓存命中不碰委托；**读未命中撞上排队写 / 撞上直写删除都不会毒化读缓存**；用接口反射枚举**每一个读方法**都经过包装（R1）；两个 source 共享一条队列时按流序分成两个 run 写；`reset` 先排空再清待写与缓存；`close` 先排空再关委托、失败后也照关；工厂只包四个库、队列失败也关委托 |
| `io.xdag.db.rocksdb.WriteBehindQueueThreadedTest` | 11 | 定时器落盘；`flushSync` 阻塞到组进库；`direct` 看得见每一条排队写且没人和它竞争、不写则不动流、第一次写才排空；写失败唤醒每一个等待者；背压把生产者停住直到写入线程排空；`stop` 排空并 join；**threaded 但从未 `start()` 的队列在调用方排空**；并发读者不会把陈旧值钉进读缓存；并发写者下待写 map 与磁盘一致 |
| `io.xdag.db.store.SumsCacheTest` | 6 | 内存更新、只在 flush 时写；重开库后 `loadSum` 逐字节相同；**每次导入的 `saveXdagStatus` 不会在第 256 块之前触发 flush**；换最深桶会把离开的那个写掉；回到已被逐出的桶时在磁盘值之上累加；`reset` 清缓存/脏集/计数 |
| `io.xdag.db.store.WriteBehindPrefixTest` | 2 | 在写流任意位置切断（`abandon()` 模拟崩溃）后重开：库内容是写流的一个前缀；共识状态转移先排空再直写 |
| `io.xdag.core.CheckNewMainIncrementalTest` | 2 | 随机块序列 + 分叉 + `setMain`/unwind 混合下，`cachedCandidate()` 与 `walkCandidate()` 对每一步给出相同的 `(p, i)`；连续导入不会每次都付一次全走（`candidateWalks` 计数） |
| `io.xdag.chain.ingest.IngestOrderingTest` | 12 | 预验证完成乱序时提交仍按到达序；预验证带上公钥且从不拒绝；抛异常的预验证被带走而不是丢掉；`stop` 提交完已接收的全部；`awaitIdle` 等到委托返回；抛异常的提交器不卡住流水线；容量 1 下全部预验证失败也全部提交；每个槽位都在飞时继续投递；并发投递仍得到一段连续序列；**提交线程死掉时 `submit` 被拒绝而不是停在信号量上**；停过的流水线不可重启 |
| `io.xdag.chain.ingest.IngestEquivalenceTest` | 1 | 同一随机块序列（复用 `BenchWorkload`，固定 seed）经流水线与经同步路径：逐块 `ImportResult` 相同；排空后四个库的键值集逐字节相同；`XdagStats`、top 相同 |
| `io.xdag.chain.ingest.IngestBlockIsolationTest` | 1 | 链导入的**永远不是** wrapper 自己那个 `Block` 实例（私有副本，§3.3） |
| `io.xdag.config.ChainSpecTest` | +3（类内共 20） | 六个键的默认值；可覆盖 + 六种非法值各自拒绝启动；`flushEntries > maxPending` 被拒 |
| `io.xdag.chain.bench.ChainL1ImportBenchmarkTest` | 1（默认 Skipped） | `pipeline.rN` 行 + `-Dxdag.bench.writeBehind` 开关 + 末行 `acceptance: … -> MET/NOT MET` |

辅助（无 `@Test`）：`io.xdag.db.rocksdb.ForwardingKVSource`（可注入失败/计数的委托）。

SP0a / SP0b-1 的全部测试一行未改、必须全绿，`ChainL1ReorgPropertyTest` 的 8 个种子亦然。

### 4.2 已知测试缺口（明确记录，不掩盖）

1. **没有任何测试驱动 `Kernel.testStart()` / `testStop()`**，所以 threaded 队列的节点级接线（装配顺序、失败处理器、G3 的失败路径、关机时那两笔持锁写、`dbFactory.close()` 停写入线程）只被间接覆盖：队列与工厂本身有单测，`Kernel` 里把它们串起来的那几十行没有。
2. **`SyncManager.doStart()` / `doStop()` 的流水线接线没有自己的测试**：`IngestEquivalenceTest` 自己 `new` 了一条流水线，没有走 `SyncManager` 的生命周期。因此"`chain.ingest.threads = 0` 时不建流水线"、"`doStop` 先停流水线再让 kernel 关库"这两条只有代码和注释保证。
3. **运维可见性**：`IngestPipeline.submitted() / committed() / inFlight()` 只有测试在读，没有任何日志、telnet 或 RPC 面把它们露出来。提交线程死掉这一条**已经**有面向运维的出口（`XdagP2pHandler` 的节流 WARN），但"流水线积压多少"目前看不见。
4. **投机预验证的占比没有测量**：真实节点上重复的 gossip 会让池线程白算 parse + ECDSA，本基准的负载里没有重复块（见基准文档"注意事项"第 8 条）。

### 4.3 验收：**未达成**

> 数字与全部推导见 `docs/benchmarks/2026-09-19-l1-import-pipeline.md`；本节不引用其它任何数字。

**目标：`pipeline` 三轮 blocks/s 的中位 ≥ 10,000 块/s（同一台 M1 Pro、安静条件）。实测中位 7157 块/s，是目标的 71.6%——未达成。**

**已经交付的**（同一次运行内）：`pipeline` 对 `direct` 是 **1.55×**（7157 vs 4631）；打开写后层比关掉它 **+17.4%**；`phase.persist` 由 SP0b-1 的 217.4 µs 降到 **22.1 µs**（sums 内存写回，纯代码收益——关掉写后层的那一轮同样是 21.0 µs）；验签整项移出锁。

**为什么加线程也到不了**（不依赖"线程 100% 饱和"这类循环论证的硬上界）：付费块的锁内提交合计占整轮的 **78.1%**，而提交是严格串行的，所以**即便预验证被完美藏住、提交线程零空转、chunk 提交零成本，单提交线程的硬上限也只有约 9164 块/s**，仍比目标低 8.4%。旁证：池利用率只有约 **9%**。**唯一的出路是把锁内每块的时间压下去。**

**瓶颈搬到哪里去了**：锁内剩下的"其余"（校验里的 AddressStore 读、孤块池写、难度与统计更新）占锁内时间的 **≥ 84%**，与 SP0b-1 时相比几乎没变。要到 10,000 块/s，平均每单位的锁内提交必须 **≤ 100 µs**，即"其余"要再砍约 **34%**。按本树的已知事实排序，头号嫌疑是**链接校验里的 `AddressStore` 读**（`WriteBehindFactory` 故意不包 ADDRESS，读缓存又只加在 INDEX 上，本负载每个付费块至少两次没有任何缓存的 RocksDB 读）；`checkNewMain`（T5 已 O(1)）与四个库的写入（T1 已改为入队）基本可以划掉。

**下一步（给 SP0b-2b / SP0b-3 的输入）**：在动手优化共识路径之前，**必须先给这几个子步骤补上 `phase.*` 行**（至少：AddressStore 读、孤块池增删、难度与统计更新），否则就是在没有基线的情况下改共识路径。加预验证线程、加队列都翻不过上面那道墙。

## 5. 文件清单（as-built）

**新增（main）**：`db/PersistControl.java`、`db/rocksdb/WriteBehindQueue.java`、`db/rocksdb/WriteBehindKVSource.java`、`db/rocksdb/WriteBehindFactory.java`、`chain/ingest/PreValidated.java`、`chain/ingest/PreValidator.java`、`chain/ingest/IngestPipeline.java`。
**改动（main）**：`BlockchainImpl`（`tryToConnect(PreValidated)`、`canUseInput` 注入与空输入短路、候选缓存与 `chainVersion`、六个 `persist.direct` 包装、`checkMain` 改 `synchronized`）、`Blockchain`（`tryToConnect(PreValidated)` 默认方法）、`BlockStore` / `BlockStoreImpl`（`flushSums()` + sums 写回）、`SyncManager`（`submitBlock`/`importPreValidated`、`volatile pipeline`、`doStart`/`doStop`）、`XdagP2pHandler`（投递与死提交线程告警）、`Kernel`（装配、G3、失败处理器、关机顺序）、`ChainSpec`/`AbstractConfig`（六个键）、`OrphanBlockStoreImpl`（清理器上监视器、`stop()` 停清理器、两条 INFO 降 DEBUG）。
**新增（test）**：§4.1 的九个类 + `ForwardingKVSource`；`ChainL1TestBase` 加 `wrapFactory` 钩子并把 `checkMain` 改回生产形状；`ChainL1ImportBenchmarkTest` 的 `pipeline` 行与 `writeBehind` 开关。
**未改动（明确）**：`ChainL1Processor`、`CHAIN_L1`、`AddressStore` 的语义；`BlockStoreImpl.forNode`；`XdagCli`（修复/快照入口本来就是裸工厂 = 直写）；任何 `.conf`。

## 6. 风险与开放问题（竣工复核）

- **R1 读穿透覆盖面** —— 已由 `WriteBehindKVSourceTest` 的接口反射枚举封住（每一个读方法逐个断言）。
- **R2 待写 map 的内存** —— `maxPending = 4096` 条 × 最大 512 B 值，几 MB；sums 缓存每次 flush 后只留脏键 + 最后一块的四个键，有界（类注释给出了"无界会是什么量级"的估算）。
- **R3 `syncPopBlock` 递归在锁内验签** —— 仍然如此，只影响补父块场景，未测量。
- **R4 增量回走的缓存失效** —— `chainVersion` 在 `updateBlockFlag` 里统一 +1（不是在各调用点），另加三处修复/快照 bump 与一处 REUSE 逐出 bump；`CheckNewMainIncrementalTest` 逐步比对全走。**残留风险**：将来任何新的"块不再可加载"或"绕过 `updateBlockFlag` 改 `BI_MAIN*`"的路径都必须记得 bump。
- **R5 验收数字的机器条件** —— 本次机器并不安静（基准文档"注意事项"第 1 条），但这一条**对代码有利**，且被运行内 2.6% 的轮间散布框住，解释不了 28.4% 的缺口。
- **R6（新）单写者前提是口头约定** —— `PersistControl.direct` 的前提（调用者持有排除其它写者的锁）没有任何运行时断言，只有 Javadoc 与 C1 的两处改动。新增一条不持监视器的写路径就会静默破坏直写语义。
- **R7（新）`Kernel` / `SyncManager` 的接线没有测试** —— 见 §4.2 第 1、2 条。

## 7. 与路线图 §5.2 的偏差汇总（竣工版）

| 路线图 §5.2 | 本规格 as-built | 原因 |
|---|---|---|
| 每块一个 `WriteBatch` | 有序写后 + 读穿透 + 直写模式 | 10k 需要 persist 离开锁的临界路径；前缀语义保住 SP0b-1 |
| 按发送方/chainId 多队列、队列间乱序 | 并行预验证、按到达顺序单队列提交 | 锁是单把，乱序无吞吐收益，只引入跨节点顺序差异 |
| 目标"四项做完" | ≥ 10k 块/s（**未达成，7157**） | 用户决定，M3 指标提前；实测与原因见 §4.3 |
| `saveBlockInfo` 保持独立同步写 | 进写流；apply 期间处于直写模式 | 统一一条流，apply 路径语义不变 |
| `SignatureCheck.verify` + `(hashLow) → boolean` LRU 64k 缓存 | 没有这个类，也没有这个缓存：`PreValidator` 直接调 `Block.verifiedKeys()`，结果以 `List<PublicKey>` 随 `PreValidated` 传给 `canUseInput` | 验签只对**有输入**的块做，按块一次性算完即用，没有跨块复用的余地；加一层哈希键缓存只会多一次哈希与一份内存 |
| `SyncManager.validateAndAddNewBlock` 去掉重复解析 | 重解析**保留**（同步路径），流水线则在池线程上做**私有副本**解析 | 见 §3.3 的两条偏差：`syncMap` 重导需要干净 `BlockInfo`，中继线程需要与链隔离的实例 |
| `PreValidated` 带 `sigOk` / `extStructureOk` / `chunkStructure` | 只带 `keys` / `classified` / `error` | 预验证不做任何判定（P1）；结构性校验仍在锁内原样执行 |
