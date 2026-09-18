# L1 导入基准基线（SP0b-1）

- 日期 / 机器：2026-09-18（第二次运行，08:27–08:47）/ Apple M1 Pro，8 核，16 GiB（`hw.memsize` = 17179869184），macOS 15.7.4（Darwin 24.6.0）/ JDK `openjdk version "21.0.12" 2026-07-21 LTS`（Temurin-21.0.12+8-LTS）
- 代码：d12181f0 + 本次基准修正（`BenchWorkload`、`ChainL1ImportBenchmarkTest`、`ChainL1TestBase.tearDownChain`；见文末"修订记录"）。`src/main` 未动。
- 命令：`mvn -q -Dxdag.bench=true -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test`（全部默认参数，未缩减 `blocks`）
- 负载：senders=64, blocks=20000（PLAIN 12075 / CALL_INLINE 4975 / CALL_CHAIN 1943 / DEPLOY 1007）, chunks=5829, mix=60/25/10/5, seed=20260917；sender 密钥由 seed 派生（sha256("bench-sender"‖seed‖i)），所以同 seed/senders/blocks/mix 下付费块与 chunk 跨轮、跨次逐字节相同（RFC 6979）。`confirmed` 每个主块链接 10 个付费块 → 每轮 2000 个链接主块 + 1 个空主块（最后一个付费块要再多一个主块才 `BI_APPLIED`），`confirmedMainsPerRound=2001`。
- 耗时：mvn 墙钟 19 min 45 s（08:27:34 → 08:47:19；surefire `Time elapsed: 1182 s`）
- 原始输出：`target/bench/l1-import-20260918-084718.json`
- 运行条件：`pgrep -fl "surefire|maven"` 确认期间没有其他 Maven / surefire JVM。机器仍**不安静**：`uptime` load average 开始 5.64 / 7.65 / 7.88，结束 40.19 / 27.51 / 17.94（8 核；后台 AiCoin、IntelliJ、Telegram 等常驻）。基准 JVM 单线程，全程占住约 99% 的一个核；绝对数字偏保守，轮间比较看中位数，direct/syncPath 的比较看**配对差值**（同一轮背靠背跑）。
- `TransactionHistoryStore`：本次为 `null`（`kernel.setTxHistoryStore(null)`，在 `BlockchainImpl` 构造之前设置），即 `node.transaction.history.enable` 关闭时 CLI 节点的生产路径，`onNewTxHistory` 直接返回。

控制台输出（逐字粘贴）：

```
L1 import benchmark: senders=64 blocks=20000 chunks=5829 mix=[60, 25, 10, 5] kinds=[PLAIN 12075, CALL_INLINE 4975, CALL_CHAIN 1943, DEPLOY 1007] seed=20260917 confirmedMainsPerRound=2001 txHistoryStore=null
```

| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |
|---|---|---|---|---|---|---|
| direct.r0 | 20000 | 2150 | 486.6 | 375.8 | 691.2 | 12013 |
| syncPath.r0 | 20000 | 2127 | 500.8 | 395.3 | 787.6 | 12142 |
| syncPath.r1 | 20000 | 2230 | 480.3 | 384.8 | 603.3 | 11585 |
| direct.r1 | 20000 | 2337 | 451.0 | 363.0 | 593.8 | 11053 |
| direct.r2 | 20000 | 2323 | 460.2 | 362.7 | 584.3 | 11119 |
| syncPath.r2 | 20000 | 2233 | 478.1 | 386.4 | 613.0 | 11565 |
| confirmed.r0 | 2001 | 81 | 153758.9 | 108124.8 | 457699.9 | 320142 |
| confirmed.r1 | 2001 | 78 | 158091.1 | 110221.0 | 467341.2 | 329384 |
| confirmed.r2 | 2001 | 80 | 155192.8 | 108822.3 | 458769.3 | 323011 |
| calib.emptyMain | 100 | 10 | 98344.1 | 64831.8 | 301260.5 | 9834 |
| phase.parse | 20000 | 64821 | 15.4 | - | - | 308 |
| phase.signature | 20000 | 9161 | 109.2 | - | - | 2183 |
| phase.refLookup | 1943 | 72224 | 13.8 | - | - | 26 |
| phase.persist.first | 20000 | 4052 | 246.8 | - | - | 4935 |
| phase.persist | 20000 | 4600 | 217.4 | - | - | 4347 |

```
paired direct/syncPath (same round, fresh fixtures, back to back):
```

| round | order | syncPath-direct mean µs | p50 µs | total ms |
|---|---|---|---|---|
| r0 | direct>syncPath | +14.2 | +19.5 | +129 |
| r1 | syncPath>direct | +29.3 | +21.8 | +532 |
| r2 | direct>syncPath | +17.9 | +23.7 | +446 |
| median | | +17.9 | +21.8 | +446 |

```
json: target/bench/l1-import-20260918-084718.json
```

列的含义：

- `n`：mean/p50/p95 背后的样本数（付费块、主块，或阶段单位——refLookup 是查询次数）。
- `blocks/s`：该行的吞吐单位每秒——导入行是付费块 + chunk（不含主块），`calib.emptyMain` 是主块/秒，`phase.*` 是阶段单位/秒（refLookup 为查询/秒）。
- `mean µs`：`n` 个样本的算术平均（先求和再排序）；`p50/p95`：分位数。`phase.*` 的 mean 是三次重放取最好的墙钟 ÷ `n`，本身就是均值，p50/p95 无定义填 `-`。
- `direct.*`：网络形态的块（从原始 512 字节解析出来、带 `XdagBlock`）直接进 `BlockchainImpl.tryToConnect`；p50/p95 只统计付费块。表中行的顺序就是运行顺序：第 r 轮先后跑 direct 与 syncPath 两条腿，偶数轮 direct 先、奇数轮 syncPath 先，每条腿一个全新基座。
- `syncPath.*`：走 `SyncManager.validateAndAddNewBlock(new BlockWrapper(b, 0))`。注意重解析发生在 `SyncManager.importBlock`（`tryToConnect(new Block(new XdagBlock(wrapper.getBlock().getXdagBlock().getData().toArray())))`），不是 `validateAndAddNewBlock` 开头的 `parse()`——对已解析的块它是空操作。
- `confirmed.*`：导入 + 每 10 个付费块挖一个主块（基座伪 PoW）+ `checkMain`，最后再挖空主块直到最后一个付费块 `BI_APPLIED`，含 `setMain`/`applyBlock`。**此行与 `calib.emptyMain` 的 mean/p50/p95 是每个主块的耗时（`mineMain` 含 `checkMain`），不是每块；blocks/s 的分母是付费块 + chunk = 25829，不含 2001 个主块。**
- `calib.emptyMain`：同一基座连挖 100 个**空**主块（找 nonce + 导入主块 + `checkMain` 但没有东西可 apply），用来从 `confirmed` 里扣掉伪 PoW 的开销。
- `phase.*`：在最后一份负载上单独重放某一阶段，只做归因，不是共识路径。`persist.first` 是往空的 scratch 库里写的第一遍（所有键都是新键，sums 数组首次创建）；`persist` 是三遍取最好（第 2、3 遍覆盖同样的键）。

## 阶段占比（均值 ÷ 均值：`direct` 每付费块均值 = 100%）

`direct` 三轮 mean 的中位数 = 460.2 µs（`direct.r2`；三轮 486.6 / 451.0 / 460.2）。阶段行本身就是均值，所以占比一律用均值除均值——分布右偏（p50 363 vs p95 594），用 p50 做分母会把每个占比抬高约四分之一。

| 阶段 | 均值 µs/付费块 | 占比 |
|------|--------------|------|
| parse（`new Block(new XdagBlock(bytes))`，含算哈希） | 15.4 | 3.4% |
| signature（`canUseInput` → `verifiedKeys()` 的 ECDSA 验签） | 109.2 | 23.7% |
| refLookup（对块链接做 `getBlockInfoByHash`）：1943 次 × 13.8 µs/次 = 26.8 ms ÷ 20000 块 | 1.3 | 0.3% |
| persist（`BlockStore.saveBlock`，三遍取最好） | 217.4 | 47.2% |
| 其余（锁内校验/难度/孤块池/统计） | 差值 116.9 | 25.4% |

说明：

- refLookup 对本负载**可忽略**：只有 CALL_CHAIN（1943 块，9.7%）的付费块带块链接（chunk 链头，每块一个），PLAIN/CALL_INLINE/DEPLOY 的输入输出都是地址链接，不查块库；1943 次查询共 26.8 ms，**每次 13.8 µs**（热的、命中的键：重放前已把负载导入过一次，查的不是布隆过滤器直接挡掉的未命中）。链接密集的负载要按每次查询 13.8 µs 重新算。
- persist 是**下界**：scratch 库是空的、三遍取最好；第一遍 246.8 µs（占 53.6%，此时"其余"降到 87.5 µs / 19.0%）。`saveBlock` 每块做 **8 次 put + 4 次 get**：`timeSource.put`（TIME 索引）+ `blockSource.put`（512 字节）+ `saveBlockSums`（4 个 sums 键，每个 get + put 一个 Java 序列化的 4096 字节数组，读改写）+ `saveBlockInfo`（`HASH_BLOCK_INFO` put + 高度键 put，后者无条件执行）——每导入一个 512 字节的块约写 16 KB。
- "其余"里的东西（都在 `synchronized tryToConnect` 内）：时间戳区间与孤块池上限检查、`isExist`/`isExistInMem`、链接校验（地址链接查 AddressStore 的余额/费用与 nonce）、`removeOrphan`、每次导入都调一次 `checkNewMain()`（从 top 往回走到上一个 `BI_MAIN`）、难度计算与 top/统计更新、孤块池写入，以及 `removeOrphan` → `OrphanBlockStoreImpl.deleteFromQueue` 每次的一条队列统计 INFO 日志（见"注意事项"）。本次 `txHistoryStore == null`，"其余"里不再含交易历史回退。

## direct 与 syncPath：配对差值

每轮两条腿背靠背、各自全新基座、奇偶轮交替先后，差值按轮配对（`syncPath.rN − direct.rN`）：

| 轮 | 顺序 | Δmean µs | Δp50 µs | Δtotal ms |
|---|---|---|---|---|
| r0 | direct 先 | +14.2 | +19.5 | +129 |
| r1 | syncPath 先 | +29.3 | +21.8 | +532 |
| r2 | direct 先 | +17.9 | +23.7 | +446 |
| **中位** | | **+17.9（+3.9%）** | **+21.8（+6.0%）** | **+446（+4.0%）** |

百分比相对 `direct` 的对应中位数（mean 460.2 µs、p50 363.0 µs、total 11119 ms）。三轮无论谁先跑，两个统计量的差值都为正，所以符号不是顺序漂移；幅度 Δmean 14–29 µs、Δp50 19.5–23.7 µs。`phase.parse` = 15.4 µs 能解释其中大部分，余下 2–14 µs 是 `importBlock` 里的 512 字节拷贝（`getData().toArray()`）、`BlockWrapper`、`syncMap`/`syncPopBlock` 簿记与 debug 日志参数求值；轮间散布（Δmean ±8 µs）与这部分同量级，本次不再细分。

## `confirmed` 路径的拆分

各列分别取三轮中位：总耗时 323011 ms（`confirmed.r2`），80 blocks/s（分母 25829，不含主块）；每个带 10 个付费块的主块 mean = 155.2 ms，p50 = 108.8 ms，p95 = 458.8 ms。空主块 mean = 98.3 ms（9834 / 100），p50 = 64.8 ms，p95 = 301.3 ms。找 nonce 是几何分布、尾巴重，分解总量要用**均值**。

- 伪 PoW + 空主块本身：2001 × 98.3 ms ≈ 196.8 s（≈ 61%）
- 直接导入（取 `direct` total 中位）：11.1 s（≈ 3%）
- 剩余 ≈ 323.0 − 196.8 − 11.1 ≈ 115.1 s（≈ 36%），即 `setMain`/`applyBlock` 把 20000 个付费块 + 5829 个 chunk 应用掉的代价：≈ 57.5 ms/主块，≈ 5.76 ms/付费块（按 25829 块算 ≈ 4.46 ms/块）。
- 另一种估法：满载主块均值 − 空主块均值 = 155.2 − 98.3 = 56.8 ms/主块 ≈ 5.68 ms/付费块，与上面一致（p50 之差 44.0 ms/主块 ≈ 4.4 ms/付费块，偏低是因为 p50 把 PoW 的重尾去掉了）。

**一个块被应用（setMain → applyBlock → ChainL1 处理 + 地址库 + 统计）的代价约 5.7 ms/付费块，是它被导入代价（均值 0.46 ms）的约 12 倍**（若把 chunk 也算作块，4.46 / 0.46 ≈ 10 倍）。注意基座每个主块只能直接链接 10 个块（16 个字段减去头/topRef/coinbase/两个 sign_out/nonce），真实网络主块靠传递引用覆盖成千上万块，所以每主块的固定开销（统计保存、完成标记、`updateBlockRef` 等）被摊到了只有 10 个块上，"每付费块"的数字含这部分摊销，偏高。

## 结论（给 SP0b-2 的输入）

1. **最大的一段是 persist（47.2%，且是下界；第一遍 53.6%）**，其次是"其余"锁内逻辑（25.4%）和验签（23.7%）；解析 3.4%，链接查询 0.3%（每次 13.8 µs，本负载链接太少）。想提高单节点导入吞吐，先动 `saveBlock`：它对每块做 8 次 put + 4 次 get（其中 `saveBlockSums` 是 4 个 4 KB 数组的读改写），合并成一个 WriteBatch / 去掉每块的 sums 读改写是最直接的目标；其次才是"其余"里每次导入都做的 `checkNewMain()` 回走与孤块池日志。
2. **锁外预验证能覆盖 parse + signature = 124.6 µs = 27.1%。** 本负载所有输入都是地址输入，`canUseInput` 走 `verifyBlockSignature`（只比公钥哈希），真正的开销在 `verifiedKeys()` 的 ECDSA，完全无状态，可以在拿 `tryToConnect` 锁之前做完；非地址输入的 `verifySignature` 要读输入块，不能全部外移。persist 与"其余"都要在锁内改状态，外移不了。按 Amdahl，锁持有时间减 27.1% 对应单锁吞吐上限约 1.37×；要更多就得改 persist。
3. **syncPath 比 direct 每块贵得很少：配对中位 Δmean = +17.9 µs（+3.9%），Δp50 = +21.8 µs（+6.0%），总耗时 +446 ms（+4.0%）**，三轮两种先后顺序下符号一致，不是噪声；幅度与一次重解析（15.4 µs）加 512 字节拷贝与簿记相符。把已解析的 `Block` 直接递给 `tryToConnect`（省掉 `importBlock` 里的 `new Block(new XdagBlock(bytes))`）最多省 4–6%，`SyncManager` 这条路径没有"解析之外的大头"——第一次运行里的 +78.8 µs 是顺序漂移（见修订记录）。
4. **确认路径：80 blocks/s 由基座伪 PoW 主导（≈ 61%），扣掉后 apply ≈ 5.7 ms/付费块，是导入的约 12 倍。** SP0b-2 若要看确认吞吐，应剖析 `setMain → applyBlock`（ChainL1 每应用块一个 batch、AddressStore 更新、每应用一个付费块一条地址级 "Balance checker" WARN 日志（3 轮共 60000 条，另有块级 12510 条）、统计保存），而不是 `tryToConnect`。
5. 复现：同 seed、senders、blocks、mix 下负载**逐字节可重现**（sender 密钥由 seed 派生，RFC 6979 签名，时间戳固定）；主块的 nonce 搜索也从同一时间线出发。`ROUNDS` 固定为 3，`blocks`/`senders`/`seed`/`mix` 可用 `-Dxdag.bench.*` 覆盖（`blocks` 必须 < 60000：每个块的时间戳是 `txTime()+n`，都得落在下一个主块封掉的那个 epoch 里）；不带 `-Dxdag.bench=true` 时整个类在 `@BeforeClass` 就跳过，不建基座（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 1`）。

## 注意事项

- 机器负载见"运行条件"；本次与第一次一样不是安静机器。同一表内的比较（轮间、配对差值）可信，绝对吞吐偏保守。
- 计时路径内仍有日志：运行窗口（08:27–08:47）`logs/xdag-info.log` 写了 191149 行 / 37.3 MB——`OrphanBlockStoreImpl` 的队列统计 INFO（`vipTxCount … linkQueue …`，118603 行，导入路径，落在"其余"里）和 `BlockchainImpl` 的 "Balance checker" WARN（72510 行，apply 路径），同步 `RollingFile`。这是 `src/main` 的行为，本次没有动；交易历史回退的两条日志（WARN + INFO）与 RocksDB put 已因 `txHistoryStore == null` 消失（窗口内 0 行）。
- `phase.persist` 是空库最好一遍，真实节点的库越大 sums 读改写与 compaction 越贵，只能当下界。
- DEPLOY 项（`wasm=null`，10 字节 initArgs ≤ 内联容量）携带 0 个 chunk，导入时与 CALL_INLINE 形态相同；只有 CALL_CHAIN 项带 chunk 链（每项 3 个，5829 = 1943 × 3）。
- `direct.r0` 的 p95 明显高（JIT 预热），三轮都列出，比较看中位与配对差值。

## 实现说明

- 基座方法名是 `setUpChain()`/`tearDownChain()`；轮间重建基座沿用 `ChainL1ReorgPropertyTest.freshFixture()` 的做法（删掉 `node` 目录、重置 `generateTime`）。`tearDownChain()` 现在先 `blockchain.stopCheckMain()`，停掉 `BlockchainImpl` 构造器启动的 `rollBackLoop` 清理线程（非守护、每个实例一个；本基准一次跑建 10 个基座）。
- `-Dxdag.bench=true` 的门在 `@BeforeClass`，参数（`senders/blocks/seed/mix`）也在那里解析并校验。
- `TransactionHistoryStore` 通过覆盖 `beforeBlockchain(kernel)` 置 `null`：`BlockchainImpl` 在构造器里拷贝一次 `kernel.getTxHistoryStore()`，之后再设无效。
- `BlockWrapper` 只有 `(Block, int ttl)` 与 `(Block, int, Peer, boolean)` 两个构造器，用 `new BlockWrapper(b, 0)`。
- 同步路径要给 kernel 三样东西：mock 的 `PeerClient.getNode()`（`importBlock` 在 IMPORTED_* 时先取 `kernel.getClient().getNode()` 再看 ttl，否则 NPE）、mock 的 `ChannelManager`，以及 `kernel.setBlockchain(blockchain)`——`BlockchainImpl` 构造器不会把自己注册到 kernel，`SyncManager` 却从 `kernel.getBlockchain()` 取。
- 负载里每个块（chunk 与付费块）都由 `BenchWorkload` 统一从**原始字节**重建：`new Block(new XdagBlock(b.getXdagBlock().getData().toArray()))`。不能用 `b.toBytes()`：对已解析的块它按 `getEncodedBody` 的规范顺序重新编码，字节可能与原布局不同，哈希随之改变，引用它的块会 `NO_PARENT`（冒烟跑时抓到）。`ChainBlockBuilder.finish` 与 `ChunkChainBuilder` 产出的块本来就已经是这种形态，PLAIN 块来自 `BlockBuilder` 需要显式转换。
- 每轮计时结束后（计时循环之外）断言 `getXdagTopStatus().getTop() == topRef`，一个悄悄劫持 top 的付费块/chunk 不会被记成成功。
- persist 阶段用 `BlockStoreImpl.forNode(new RocksdbFactory(scratchConfig))`，`scratchConfig` 是 rootDir 指向 `root.newFolder("scratch")` 的 `DevnetConfig`（RocksDB 每目录单写者），`scratch.close()` 在 `finally` 里。
- 基座的 `nextNonce()` 计数器不随 `setUpChain()` 重置，第二个新基座上的 deploy 会拿到 nonce 2 而地址库已执行 nonce 是 0，首轮之后 `confirm` 失败；改为在基准里自己用 `UInt64.ONE` 构造 deploy。
- 计划里"每 2000 块出一个主块"不可行：主块最多链接 10 个块（`Block.setType` 超过 16 个字段就抛 `block field budget exceeded`），改为每 10 个付费块一个主块；`confirm` 阶段的空主块也走 `mineTimed` 并计入 `confirmedMainsPerRound`。
- 阶段重放前把负载先导入一次（不计时），让 refLookup 查的是存在的键；重放用的原始字节取自 `getXdagBlock().getData()`，不是重新编码。
- 输出：`String.format(Locale.ROOT, …)`；JSON 文件名带秒；JSON 里 `paired`/`pairedMedian` 记录配对差值。

## 修订记录（相对 2026-09-18 04:44 的第一次运行，代码 d12181f0）

第一次运行的数字**不再有效**，本文档全部数字来自 08:27–08:47 的第二次运行。改动与原因：

- **C1 均值 ÷ 中位数**：阶段行是均值（best/count），第一次却除以 `direct` 的 p50；右偏分布下每个占比被抬高 21–56%。现在 `timeImport` 先求和再排序，表里加 `mean µs` 列，占比表改为均值 ÷ 均值。
- **C2 direct/syncPath 没有交错**：第一次先跑完 3 轮 direct 再跑 3 轮 syncPath，单调漂移全记在 syncPath 头上（+78.8 µs / +17.8%）。现在每轮两条腿背靠背、奇偶轮交替先后，报告配对差值：中位 +17.9 µs（mean）/ +21.8 µs（p50）。重解析的位置也改正为 `SyncManager.importBlock`（`validateAndAddNewBlock` 的 `parse()` 对已解析块是空操作）。
- **C3 计时循环里的 mock `TransactionHistoryStore`**：基座的 Mockito mock 让 `saveTxHistory` 返回 false，每个带金额的链接（每块 2 个）走"MySQL 失败"回退——一条 WARN + 一条 INFO + 一次 RocksDB put，同步 `RollingFile`，第一次运行写了 >300 MB 日志。现在基准在 `beforeBlockchain` 里 `kernel.setTxHistoryStore(null)`（生产路径）；direct p50 从 443 µs 降到 363 µs，与审阅时单独量到的 −14.6% 一致。
- **I1 泄漏的清理线程**：`BlockchainImpl` 构造器启动的 `rollBackLoop` 调度器（非守护）在 `tearDownChain()` 里从不停止，一次基准泄 10 个；现在 `tearDownChain()` 先 `stopCheckMain()`。基座家族（`io.xdag.chain.**`、`RepairChainCommandTest`、`MakeSnapshotEndToEndTest`）24 个类 / 190 个测试全绿（1 个跳过 = 本基准未带开关）。
- **I4 块的字节往返**：`BenchWorkload` 现在把每个块（chunk 与付费块）统一从原始字节重建成网络形态。核对 d12181f0 时发现 CALL/DEPLOY 的付费块与 chunk 本来就已经由 `ChainBlockBuilder.finish` / `ChunkChainBuilder` 往返过（`parsed=true` 且带 `XdagBlock`），只有 PLAIN 需要转换且原来就有——所以这一项对数字没有可测的影响；但用 `toBytes()` 做往返会改哈希（见实现说明），改成取原始字节。
- **I2 persist 计数**：`saveBlock` 是 8 次 put + 4 次 get（不是 4 次写），每块约 16 KB；同时报告第一遍（`persist.first`）与三遍最好。
- **I3 refLookup**：报告查询次数（n=1943）与每次 13.8 µs；对本负载可忽略（每付费块 1.3 µs）。
- **M1–M11**：门移到 `@BeforeClass`（不建基座即跳过，`Skipped: 1` 语义不变）；`confirm` 的空主块计入 `confirmedMainsPerRound`（2001）并说明 `confirmed` blocks/s 不含主块；`Locale.ROOT`；JSON 文件名带秒；`scratch.close()` 进 `finally`；`blocks < 60000` 守卫；每轮计时外断言 top；去掉不可达的 `default`、`items()/senders()` 只读、本地 `payload` 不再依赖 `ChunkChainTest`；DEPLOY 项 0 chunk 的说明；sender 密钥由 seed 派生后"逐字节可重现"成立；日志说明。
