# L1 导入基准基线（SP0b-1）

- 日期 / 机器：2026-09-18 / Apple M1 Pro，8 核，16 GiB（`hw.memsize` = 17179869184），macOS Darwin 24.6.0 / JDK `openjdk version "21.0.12" 2026-07-21 LTS`（Temurin-21.0.12+8-LTS）
- 命令：`mvn -q -Dxdag.bench=true -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test`（全部默认参数，未缩减 `blocks`）
- 负载：senders=64, blocks=20000, chunks=5829, mix=60/25/10/5, seed=20260917；`confirmed` 每个主块链接 10 个付费块 → 每轮 2000 个主块
- 耗时：mvn 墙钟 20 min 15.7 s（surefire `Time elapsed: 1213 s`，04:23:48 → 04:44:04）
- 原始输出：`target/bench/l1-import-20260918-0444.json`
- 运行条件：期间没有其他 Maven 构建；但机器**不安静**——启动时 load average 31.9 / 22.8 / 21.5，运行中升到 42.2（8 核），后台常驻约 300% CPU（IntelliJ ≈ 39%、系统缓存清理守护 `deleted` ≈ 23%、AiCoin ≈ 37%、`mds` ≈ 18%）。基准 JVM 单线程，全程占住约 89% 的一个核；绝对数字偏保守，轮间波动（如 `direct.r0` 的 JIT 预热）以中位数为准。

控制台表（逐字粘贴）：

```
L1 import benchmark: senders=64 blocks=20000 chunks=5829 mix=[60, 25, 10, 5] seed=20260917 confirmedMainsPerRound=2000
```

| measure | blocks/s | p50 µs | p95 µs | total ms |
|---|---|---|---|---|
| direct.r0 | 1840 | 487.8 | 861.2 | 14036 |
| direct.r1 | 2025 | 442.7 | 742.1 | 12758 |
| direct.r2 | 1866 | 443.5 | 826.8 | 13839 |
| syncPath.r0 | 1712 | 487.3 | 891.7 | 15088 |
| syncPath.r1 | 1681 | 522.3 | 971.0 | 15363 |
| syncPath.r2 | 1702 | 527.0 | 959.8 | 15175 |
| confirmed.r0 | 79 | 110447.6 | 465181.8 | 325045 |
| confirmed.r1 | 79 | 106435.8 | 471897.6 | 326328 |
| confirmed.r2 | 77 | 108841.3 | 470932.4 | 333298 |
| calib.emptyMain | 10 | 63471.2 | 295828.1 | 9707 |
| phase.parse | 65245 | 15.3 | 0.0 | 306 |
| phase.signature | 9275 | 107.8 | 0.0 | 2156 |
| phase.refLookup | 1170932 | 0.9 | 0.0 | 17 |
| phase.persist | 4981 | 200.8 | 0.0 | 4015 |

列的含义：

- `direct.*`：预解析好的块直接进 `BlockchainImpl.tryToConnect`（块与 chunk 都计入 blocks/s；p50/p95 只统计付费块）。
- `syncPath.*`：走 `SyncManager.validateAndAddNewBlock(new BlockWrapper(b, 0))`，即网络入口（它会从原始字节重新解析一次再调 `tryToConnect`）。
- `confirmed.*`：导入 + 每 10 个付费块挖一个主块（基座伪 PoW）+ `checkMain` 直到最后一个付费块 `BI_APPLIED`，含 `setMain`/`applyBlock`。**此行与 `calib.emptyMain` 的 p50/p95 是每个主块的耗时（`mineMain` 含 `checkMain`），不是每块。**
- `calib.emptyMain`：同一基座连挖 100 个**空**主块（找 nonce + 导入主块 + `checkMain` 但没有东西可 apply），用来从 `confirmed` 里扣掉伪 PoW 的开销；blocks/s 一列是主块/秒。
- `phase.*`：在最后一份负载上单独重放某一阶段（三次取最好），只做归因，不是共识路径；p50 一列是 `best / count`（平均），p95 无意义填 0。

## 阶段占比（`direct` 单块中位耗时 = 100%）

`direct` 三轮 p50 的中位数 = 443.5 µs（`direct.r2`）。

| 阶段 | 中位 µs/块 | 占比 |
|------|-----------|------|
| parse（`new Block(new XdagBlock(bytes))`，含算哈希） | 15.3 | 3.5% |
| signature（`canUseInput` → `verifiedKeys()` 的 ECDSA 验签） | 107.8 | 24.3% |
| refLookup（对块链接做 `getBlockInfoByHash`） | 0.9 | 0.2% |
| persist（`BlockStore.saveBlock`：TIME 索引 put + BLOCK put + `saveBlockSums` 读改写 + `saveBlockInfo`） | 200.8 | 45.3% |
| 其余（锁内校验/难度/孤块池/统计） | 差值 118.8 | 26.8% |

说明：

- refLookup 在本负载里几乎为零，因为只有 CALL_CHAIN（10%）的付费块带块链接（chunk 链头），PLAIN/CALL_INLINE/DEPLOY 的输入输出都是地址链接，不查块库。重放前已把负载导入过一次，所以查的是命中的键，不是布隆过滤器直接挡掉的未命中。
- "其余"里的东西（都在 `synchronized tryToConnect` 内）：时间戳区间与孤块池上限检查、`isExist`/`isExistInMem`（对自身哈希各查一次）、链接校验（地址链接查 AddressStore 的余额/费用与 nonce）、`removeOrphan`、每个带金额的链接一次 `onNewTxHistory`、每次导入都调一次 `checkNewMain()`（从 top 往回走到上一个 `BI_MAIN`）、难度计算与 top/统计更新、孤块池写入。
- 基座陷阱：基座把 `TransactionHistoryStore` 换成 Mockito mock，`saveTxHistory` 返回 false，于是每个带金额的链接都走"MySQL 写失败"回退——一条 WARN 日志 + 一次 `saveTxHistoryToRocksdb`（本负载每块 2 个）。这部分在 direct/syncPath/confirmed 三个数字里都有，落在"其余"里；`txHistoryStore == null` 的节点没有这段。本次没有单独量化。

## `confirmed` 路径的拆分

三轮取中位：总耗时 326328 ms（`confirmed.r1`），79 blocks/s；每个带 10 个付费块的主块 p50 = 108.8 ms，p95 = 470.9 ms。空主块 p50 = 63.5 ms，p95 = 295.8 ms，均值 = 9707 / 100 = 97.1 ms（找 nonce 是几何分布，尾巴重，分解总量要用均值）。

- 伪 PoW + 空主块本身：2000 × 97.1 ms ≈ 194 s（≈ 59%）
- 直接导入（取 `direct` 中位 total）：13.8 s（≈ 4%）
- 剩余 ≈ 326.3 − 194.1 − 13.8 ≈ 118.3 s，即 `setMain`/`applyBlock` 把 20000 个付费块 + 5829 个 chunk 应用掉的代价：≈ 59 ms/主块，≈ 4.6 ms/块（按 25829 块算）或 ≈ 5.9 ms/付费块。
- 另一种估法：满载主块 p50 − 空主块 p50 = 108.8 − 63.5 = 45.4 ms/主块 ≈ 4.5 ms/付费块，量级一致。

两种估法都说明：**一个块被应用（setMain → applyBlock → ChainL1 处理 + 地址库 + 历史回退 + 统计）的代价是它被导入代价（0.44 ms）的约 10 倍。** 注意基座每个主块只能直接链接 10 个块（16 个字段减去头/topRef/coinbase/两个 sign_out/nonce），真实网络主块靠传递引用覆盖成千上万块，所以这里每主块的固定开销（统计保存、完成标记、`updateBlockRef` 等）被摊到了只有 10 个块上，"每付费块"的数字含这部分摊销，偏高。

## 结论（给 SP0b-2 的输入）

1. **最大的一段是 persist（45.3%）**，其次是"其余"锁内逻辑（26.8%）和验签（24.3%）；解析只有 3.5%，链接查询可忽略。想提高单节点导入吞吐，先动 `saveBlock`：它对每块做四次 RocksDB 写（其中 `saveBlockSums` 还是读改写），合并成一个 WriteBatch / 去掉每块的 sums 读改写是最直接的目标；其次才是"其余"里每次导入都做的 `checkNewMain()` 回走和历史回退。
2. **锁外预验证能覆盖 parse + signature = 123.1 µs = 27.8%。** 本负载所有输入都是地址输入，`canUseInput` 走 `verifyBlockSignature`（只比公钥哈希），真正的开销在 `verifiedKeys()` 的 ECDSA，完全无状态，可以在拿 `tryToConnect` 锁之前做完；非地址输入的 `verifySignature` 要读输入块，不能全部外移。persist 与"其余"都要在锁内改状态，外移不了。按 Amdahl，锁持有时间减 27.8% 对应单锁吞吐上限约 1.39×；要更多就得改 persist。
3. **syncPath 比 direct 每块中位多 78.8 µs（+17.8%），总耗时多 1336 ms（+9.7%），吞吐 1702 vs 1866 blocks/s。** 重解析本身只值 15.3 µs（约 1/5），其余来自 `SyncManager` 的包装与簿记（`getData().toArray()` 拷贝、`syncPopBlock`、debug 日志参数等），本次没有再细分。把已解析的 `Block` 直接递给 `tryToConnect` 只能省 3.5%，收益有限；这条路径的开销大头不在解析。
4. **确认路径：79 blocks/s 由基座伪 PoW 主导（≈ 59%），扣掉后 apply ≈ 4.6 ms/块，是导入的约 10 倍。** SP0b-2 若要看确认吞吐，应剖析 `setMain → applyBlock`（ChainL1 每应用块一个 batch、AddressStore 更新、历史回退、统计保存），而不是 `tryToConnect`。建议补一次 `txHistoryStore == null` 的对照，把历史回退在导入与应用两边的份额量出来。
5. 复现：同 seed 与 sender 数下负载逐字节可重现（RFC 6979），但每轮的 sender 密钥是 `ECKeyPair.generate()` 随机生成的，跨轮/跨次不同；比较时看中位数。`ROUNDS` 与 `blocks` 可用 `-Dxdag.bench.blocks/senders/seed/mix` 覆盖，不带 `-Dxdag.bench=true` 时该类被跳过（`Tests run: 1, Skipped: 1`）。

## 实现说明（相对计划正文的修正）

- 基座方法名是 `setUpChain()`/`tearDownChain()`；轮间重建基座沿用 `ChainL1ReorgPropertyTest.freshFixture()` 的做法（删掉 `node` 目录、重置 `generateTime`）。
- `BlockWrapper` 只有 `(Block, int ttl)` 与 `(Block, int, Peer, boolean)` 两个构造器，用 `new BlockWrapper(b, 0)`。
- 同步路径要给 kernel 三样东西：mock 的 `PeerClient.getNode()`（`importBlock` 在 IMPORTED_* 时先取 `kernel.getClient().getNode()` 再看 ttl，否则 NPE）、mock 的 `ChannelManager`，以及 `kernel.setBlockchain(blockchain)`——`BlockchainImpl` 构造器不会把自己注册到 kernel，`SyncManager` 却从 `kernel.getBlockchain()` 取。
- persist 阶段用 `BlockStoreImpl.forNode(new RocksdbFactory(scratchConfig))`，`scratchConfig` 是 rootDir 指向 `root.newFolder("scratch")` 的 `DevnetConfig`（RocksDB 每目录单写者）。
- 基座的 `nextNonce()` 计数器不随 `setUpChain()` 重置，第二个新基座上的 deploy 会拿到 nonce 2 而地址库已执行 nonce 是 0，首轮之后 `confirm` 失败（日志 `tx nonce error, tx nonce: 2, executed nonce: 0`）；改为在基准里自己用 `UInt64.ONE` 构造 deploy。
- 计划里"每 2000 块出一个主块"不可行：主块最多链接 10 个块（`Block.setType` 超过 16 个字段就抛 `block field budget exceeded`），改为每 10 个付费块一个主块，因此每轮 2000 个主块，并加了 `calib.emptyMain` 校准行与 `confirmed` 的每主块 p50/p95。
- 阶段重放前把负载先导入一次（不计时），让 refLookup 查的是存在的键。
