# L1 导入流水线基线（SP0b-2）

- 日期 / 机器：2026-09-19（10:58–11:36，两次全量运行背靠背）/ Apple M1 Pro，8 核，16 GiB（`hw.memsize` = 17179869184），macOS 15.7.4（24G517，Darwin 24.6.0）/ JDK `openjdk version "21.0.12" 2026-07-21 LTS`（Temurin-21.0.12+8-LTS）
- 代码：`dev-dag-contract`，SP0b-2 的 T1–T7 已全部落地（HEAD `65cd100b`），本次运行的工作树在其上多两处本任务的改动：`OrphanBlockStoreImpl` 两条每次导入都打的 INFO 降为 DEBUG，`ChainL1ImportBenchmarkTest` 加 `wrapFactory` 覆盖与 `pipeline.rN` 行。两者与本文档在同一个提交里。
- 负载：与 SP0b-1 基线**逐字节相同**——senders=64, blocks=20000（PLAIN 12075 / CALL_INLINE 4975 / CALL_CHAIN 1943 / DEPLOY 1007）, chunks=5829, mix=60/25/10/5, seed=20260917。`confirmedMainsPerRound=2001`。
- `TransactionHistoryStore`：`null`（`node.transaction.history.enable` 关闭时 CLI 节点的生产路径）。
- 原始输出：`target/bench/l1-import-20260919-111701.json`（A）、`target/bench/l1-import-20260919-113608.json`（B）

## 两条命令

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"

# A：默认参数 = 节点的真实形态（写后落盘开，流水线开）
mvn -q -Dxdag.bench=true -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test

# B：关掉写后落盘层，做前后对比
mvn -q -Dxdag.bench=true -Dxdag.bench.writeBehind=false -Dchain.ingest.threads=0 -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- A：10:58:10 → 11:17:01，墙钟 **18 min 51 s**；`uptime` 开始 `7.18 8.82 9.46`，结束 `6.36 7.07 8.09`。
- B：11:17:01 → 11:36:08，墙钟 **19 min 7 s**；`uptime` 开始 `6.36 7.07 8.09`，结束 `8.09 8.74 8.25`。
- 两次运行期间 `pgrep -fl "surefire|maven"` 只有本次运行自己的 JVM，没有别的 Maven / surefire。
- `-Dchain.ingest.threads=0` 对本基准**无效果**（见"注意事项"第 6 条）：基准从不 `start()` `SyncManager`，`pipeline.rN` 用的是基准自己构造的 `IngestPipeline`。留在命令里只为与计划的记录一致。

## 运行 A：写后落盘开 + 流水线（节点默认）

控制台输出（逐字粘贴）：

```
L1 import benchmark: senders=64 blocks=20000 chunks=5829 mix=[60, 25, 10, 5] kinds=[PLAIN 12075, CALL_INLINE 4975, CALL_CHAIN 1943, DEPLOY 1007] seed=20260917 confirmedMainsPerRound=2001 txHistoryStore=null writeBehind=true ingestThreads=8
```

| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |
|---|---|---|---|---|---|---|
| direct.r0 | 20000 | 4555 | 250.4 | 234.8 | 287.2 | 5670 |
| syncPath.r0 | 20000 | 3879 | 293.6 | 266.6 | 365.8 | 6659 |
| syncPath.r1 | 20000 | 4168 | 270.9 | 253.8 | 306.8 | 6197 |
| direct.r1 | 20000 | 4631 | 247.6 | 232.8 | 276.6 | 5577 |
| direct.r2 | 20000 | 4698 | 244.4 | 232.0 | 269.2 | 5498 |
| syncPath.r2 | 20000 | 4287 | 266.7 | 253.2 | 296.9 | 6025 |
| pipeline.r0 | 20000 | 7157 | 140.9 | 128.0 | 159.7 | 3609 |
| pipeline.r1 | 20000 | 7319 | 137.8 | 127.3 | 156.3 | 3529 |
| pipeline.r2 | 20000 | 7137 | 144.1 | 127.4 | 161.0 | 3619 |
| confirmed.r0 | 2001 | 83 | 153243.9 | 108205.4 | 450274.0 | 312484 |
| confirmed.r1 | 2001 | 83 | 153576.8 | 107597.1 | 456837.3 | 313024 |
| confirmed.r2 | 2001 | 83 | 153485.5 | 108018.3 | 452152.5 | 312870 |
| calib.emptyMain | 100 | 10 | 97227.4 | 64319.1 | 305650.5 | 9722 |
| phase.parse | 20000 | 64662 | 15.5 | - | - | 309 |
| phase.signature | 20000 | 9466 | 105.6 | - | - | 2112 |
| phase.refLookup | 1943 | 221285 | 4.5 | - | - | 8 |
| phase.persist.first | 20000 | 45185 | 22.1 | - | - | 442 |
| phase.persist | 20000 | 45185 | 22.1 | - | - | 442 |

```
paired direct/syncPath (same round, fresh fixtures, back to back):
```

| round | order | syncPath-direct mean µs | p50 µs | total ms |
|---|---|---|---|---|
| r0 | direct>syncPath | +43.2 | +31.8 | +989 |
| r1 | syncPath>direct | +23.3 | +21.0 | +620 |
| r2 | direct>syncPath | +22.3 | +21.2 | +527 |
| median | | +23.3 | +21.2 | +620 |

```
acceptance: pipeline median = 7157 blocks/s over 3 rounds, target 10000 -> NOT MET
json: target/bench/l1-import-20260919-111701.json
```

## 运行 B：写后落盘关

控制台输出（逐字粘贴）：

```
L1 import benchmark: senders=64 blocks=20000 chunks=5829 mix=[60, 25, 10, 5] kinds=[PLAIN 12075, CALL_INLINE 4975, CALL_CHAIN 1943, DEPLOY 1007] seed=20260917 confirmedMainsPerRound=2001 txHistoryStore=null writeBehind=false ingestThreads=8
```

| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |
|---|---|---|---|---|---|---|
| direct.r0 | 20000 | 4054 | 277.0 | 254.0 | 327.7 | 6371 |
| syncPath.r0 | 20000 | 3523 | 318.7 | 285.3 | 418.6 | 7332 |
| syncPath.r1 | 20000 | 3734 | 302.5 | 273.7 | 346.4 | 6917 |
| direct.r1 | 20000 | 4165 | 270.2 | 251.5 | 310.7 | 6201 |
| direct.r2 | 20000 | 4152 | 271.9 | 251.9 | 308.8 | 6221 |
| syncPath.r2 | 20000 | 3822 | 295.2 | 274.0 | 340.9 | 6758 |
| pipeline.r0 | 20000 | 6096 | 163.6 | 146.2 | 195.0 | 4237 |
| pipeline.r1 | 20000 | 6201 | 162.0 | 146.0 | 189.0 | 4165 |
| pipeline.r2 | 20000 | 6036 | 166.4 | 147.8 | 199.0 | 4279 |
| confirmed.r0 | 2001 | 82 | 154035.6 | 108551.1 | 456889.7 | 315055 |
| confirmed.r1 | 2001 | 82 | 153753.5 | 108866.1 | 454714.7 | 314394 |
| confirmed.r2 | 2001 | 81 | 155098.5 | 108997.3 | 457788.5 | 317255 |
| calib.emptyMain | 100 | 10 | 100560.9 | 65191.3 | 307211.7 | 10056 |
| phase.parse | 20000 | 64530 | 15.5 | - | - | 309 |
| phase.signature | 20000 | 9499 | 105.3 | - | - | 2105 |
| phase.refLookup | 1943 | 148006 | 6.8 | - | - | 13 |
| phase.persist.first | 20000 | 47574 | 21.0 | - | - | 420 |
| phase.persist | 20000 | 47574 | 21.0 | - | - | 420 |

```
paired direct/syncPath (same round, fresh fixtures, back to back):
```

| round | order | syncPath-direct mean µs | p50 µs | total ms |
|---|---|---|---|---|
| r0 | direct>syncPath | +41.7 | +31.3 | +961 |
| r1 | syncPath>direct | +32.3 | +22.1 | +716 |
| r2 | direct>syncPath | +23.3 | +22.1 | +537 |
| median | | +32.3 | +22.1 | +716 |

```
acceptance: pipeline median = 6096 blocks/s over 3 rounds, target 10000 -> NOT MET
json: target/bench/l1-import-20260919-113608.json
```

## 对比表

各列取三轮**中位**。"SP0b-1" 一列来自 `docs/benchmarks/2026-09-18-l1-import-baseline.md`（代码 0500e106，2026-09-18 08:27–08:47）；**那次运行的机器比今天吵得多**（`uptime` 由 5.64 涨到 40.19，今天全程 6–9），所以跨天的差值是代码收益的**上界**，不是干净的测量。干净的比较只有两处：同一次运行里的 `pipeline` vs `direct`（隔离流水线），以及今天背靠背的 A vs B（隔离写后落盘层）。

### 导入路径

| 行 | 统计量 | SP0b-1（跨天，含机器噪声） | B：写后关 | A：写后开（节点默认） |
|---|---|---|---|---|
| `direct` | blocks/s | 2323 | 4152 | **4631** |
| `direct` | mean µs | 460.2 | 271.9 | **247.6** |
| `direct` | p50 µs | 363.0 | 251.9 | **232.8** |
| `direct` | total ms | 11119 | 6221 | **5577** |
| `syncPath` | blocks/s | 2230 | 3734 | **4168** |
| `syncPath` | mean µs | 480.3 | 302.5 | **270.9** |
| `pipeline` | blocks/s（端到端，含 `flushSync`） | — | 6096 | **7157** |
| `pipeline` | 锁内 mean µs | — | 163.6 | **140.9** |
| `pipeline` | 锁内 p50 µs | — | 146.2 | **127.4** |
| `pipeline` | 锁内 p95 µs | — | 195.0 | **159.7** |
| `pipeline` | total ms | — | 4237 | **3609** |

- **流水线的净收益（同一次运行内，A）**：`pipeline` 7157 vs `direct` 4631 blocks/s = **1.55×**；每块锁内耗时由 `direct` 的 247.6 µs 降到 140.9 µs = **−43.1%**，正好是 `phase.signature`（105.6 µs）搬出锁的量。8 个预验证线程把 parse（15.5 µs）+ 验签（105.6 µs）完全藏在了提交线程后面。
- **写后落盘层的净收益（A vs B，同代码、同机器、相邻运行）**：`direct` +11.5%（4152 → 4631），`pipeline` +17.4%（6096 → 7157），`pipeline` 锁内 mean −13.9%（163.6 → 140.9 µs）。`phase.refLookup` 由 6.8 µs 降到 4.5 µs 是 INDEX 的 65536 条读缓存。
- 跨天看，`direct` 的 mean 由 460.2 降到 247.6 µs（−46.2%），blocks/s 由 2323 涨到 4631（1.99×）；其中有多少是机器变安静带来的无法从本次数据里拆开。可以拆开的是 `phase.persist`：217.4 → 22.1 µs（−90%），这是 T2 的 sums 内存写回，**在 B 里同样是 21.0 µs**（`phase.persist` 用的是没包写后层的 scratch 库），所以它是纯代码收益，与机器负载无关。

### 确认路径

| 行 | 统计量 | SP0b-1 | B | A |
|---|---|---|---|---|
| `confirmed` | blocks/s | 80 | 82 | 83 |
| `confirmed` | mean µs/主块 | 155192.8 | 154035.6 | 153485.5 |
| `confirmed` | p50 µs/主块 | 108822.3 | 108866.1 | 108018.3 |
| `calib.emptyMain` | mean µs/主块 | 98344.1 | 100560.9 | 97227.4 |

确认路径**基本没动**（80 → 83 blocks/s），符合预期：它被基座的伪 PoW 主导（`calib.emptyMain` 97.2 ms/主块，约占 `confirmed` 总耗时的 62%），而 SP0b-2 动的是 `tryToConnect`，不是 `setMain`/`applyBlock`。扣掉空主块后 apply ≈ 153.5 − 97.2 = 56.3 ms/主块 ≈ 5.63 ms/付费块，与 SP0b-1 的 5.68 ms 一致。

## 验收判定

**目标：`pipeline` 三轮 blocks/s 的中位 ≥ 10,000 块/s。**

**未达成。** 节点默认形态（运行 A，写后落盘开、8 个预验证线程）下三轮为 7157 / 7319 / 7137，**中位 = 7157 块/s**，是目标的 **71.6%**，差 2843 块/s。写后落盘关掉（运行 B）中位 = 6096 块/s。控制台原文：`acceptance: pipeline median = 7157 blocks/s over 3 rounds, target 10000 -> NOT MET`。

（`pipeline` 的 blocks/s 是端到端：从第一个 `submit` 到最后一个提交返回，**并且包含** `persist().flushSync()` 把写后队列排空，所以这个吞吐里落盘是算进去的。）

### 剩下的时间在哪

**瓶颈是单线程的锁内提交，而且这条线程已经 100% 饱和。** 运行 A 每轮 3609 ms 提交 25829 个块 = **端到端 139.7 µs/块**；同一轮的锁内 mean 是 140.9 µs/付费块。两者相等说明流水线的墙钟就等于提交线程的忙时间——预验证（parse + 验签）已经完全藏住，再加预验证线程一块钱都不会省。（按 20000 个付费块 × 140.9 µs = 2818 ms 反推，5829 个 chunk 的提交是 791 ms ≈ 135.7 µs/个，chunk 在锁里几乎和付费块一样贵。）

拿 `phase.*` 拆运行 A 的 140.9 µs：

| 锁内分段 | µs/付费块 | 占锁内时间 |
|---|---|---|
| 验签（`verifiedKeys()` 的 ECDSA） | 0（已移到池线程，`phase.signature` = 105.6 µs 在锁外） | 0% |
| persist（`BlockStore.saveBlock`） | ≤ 22.1（`phase.persist`；写后层下锁内只是入队，实际更低） | ≤ 15.7% |
| refLookup（块链接查询） | 1943 × 4.5 µs ÷ 20000 = 0.4 | 0.3% |
| **其余（锁内校验 / 难度 / 孤块池 / 统计 / `checkNewMain`）** | **≥ 118.4** | **≥ 84.0%** |

运行 B（库是全同步的，`phase.persist` = 21.0 µs 可以直接和锁内成本对齐）给出同样的结论：163.6 = 21.0（persist，12.8%）+ 0.7（refLookup）+ **141.9（其余，86.7%）**。

结论很直接：**SP0b-2 该搬的都搬完了，锁内剩下的 85% 是"其余"那一坨，它和 SP0b-1 时相比几乎没变（132.3 → 118.4 µs，−10%）。** 两个推论：

1. **把 persist 打到 0 也到不了目标**：140.9 − 22.1 = 118.4 µs → 8446 块/s，仍然短 15%。
2. **要到 10,000 块/s，平均每块的锁内提交必须 ≤ 100 µs**（目前 139.7 µs），也就是"其余"要从 118.4 µs 砍到 ≤ 78 µs（−34%）。这部分是 `tryToConnect` 锁内的时间戳/孤块池上限检查、`isExist`/`isExistInMem`、链接校验（地址链接查 AddressStore 的余额/费用与 nonce）、`removeOrphan`、`checkNewMain()`、难度与 top/统计更新、孤块池写入。下一轮要提速，靶子在这里，不在验签也不在落盘。

## 注意事项

1. **机器负载**。两次运行期间没有别的 Maven / surefire，但机器不是空的：常驻的 AiCoin、IntelliJ、Telegram 等让 `uptime` 全程在 6–9 之间（8 核）。基准 JVM 的提交线程占住一个核。同一张表内的比较（轮间、配对差值、`pipeline` vs `direct`）可信；**跨天和 SP0b-1 比要打折扣**——那次运行结束时 load 已到 40.19，今天最高 8.74。
2. **`txHistoryStore == null`**。与 SP0b-1 一致，走的是 `node.transaction.history.enable` 关闭时的生产路径。
3. **残留日志**。运行窗口（10:58–11:36，两次运行共 38 min）`logs/xdag-info.log` 写了 **144864 行 / 29.6 MB**。`OrphanBlockStoreImpl` 的队列统计 INFO **已经降成 DEBUG，窗口内 0 行**（SP0b-1 的单次运行里是 118603 行）。剩下的全是 apply 路径的 "Balance checker" WARN：`BlockchainImpl:2682`（地址级）120026 行 + `BlockchainImpl:2700`（块级）24724 行，合计约 72400 行/次运行，与 SP0b-1 的 72510 行一致——**它们只落在 `confirmed` 行里，导入路径（`direct`/`syncPath`/`pipeline`）现在基本不写日志**。`OrphanBlockStoreImpl` 里第三条同样的 INFO 在 `getOrphan()`（出块路径，每个主块一次，不是每次导入），按计划未动。
4. **`tryToConnect(Block)` 的语义变化（T7 的副作用）**。它现在是个不带 `synchronized` 的一行，转发给 `tryToConnect(PreValidator.inline(block))`；`inline()` 在**拿锁之前**就把 `parse()`、`getHashLow()` 和 `verifiedKeys()` 的 ECDSA 算掉。对一个会被接受的块，总工作量不变（只是挪出了锁）；但对一个**会被早早拒掉**的块（EXIST、类型/时间不合法、孤块池满），它现在要付一次它以前不付的 ECDSA。本基准的负载每个块都成功导入（每轮都断言了），所以 `direct` 行与 `pipeline` 行做的是同样的总功，两行可比；真实节点上重复的 gossip 会让这部分变成投机开销。缓解因素：`inline()` 只对**有输入**的块算公钥，chunk、链接块、主块（本负载里 5829 个 chunk）一分钱不付。
5. **写后落盘下 `direct`/`syncPath` 的 total 不含最后一次排空**。只有 `pipeline` 行调了 `flushSync()`。`WriteBehindQueue` 的 `maxPending` 是 4096 条，一轮大约写 20 万条，所以运行 A 的 `direct`/`syncPath` blocks/s 最多乐观约 2%；`pipeline` 行没有这个问题。
6. **`-Dchain.ingest.threads=0` 对本基准是空操作**。那个键只在 `SyncManager.doStart()` 里读，而基准从不启动 `SyncManager`（`syncManager()` 只 `new` 一个并接好 kernel）；`pipeline.rN` 用的是基准自己构造的 `IngestPipeline`（线程数 `-Dxdag.bench.ingestThreads`，默认 `availableProcessors()` = 8），`syncPath.rN` 走的是 `validateAndAddNewBlock`，它本来就不经流水线。所以运行 B 的 `pipeline` 行量的是"流水线 + 同步库"，不是"关掉流水线"。
7. **`phase.persist` 是下界**。它写的是一个空的 scratch 库（`RocksdbFactory`，**不包写后层**，A/B 两次都一样），三遍取最好，且重放时 `BlockInfo` 直接来自 `parse()`、比真实路径序列化出来小。真实节点库越大越贵。
8. **投机预验证的占比本次为 0**，因为负载里没有重复块，每个 `submit` 的块都被成功导入。真实节点上重复 gossip 会让池线程白算 parse + ECDSA；如果这一项变大，下一步就是在池线程上并发做一次 `isExist` 预筛。本次没有测量这一项。
9. **`pipeline` 行的 mean/p50/p95 是锁内提交耗时**（`SyncManager.importPreValidated` 的墙钟，含 `tryToConnect` 与中继簿记），**不是**端到端延迟，也不含块在队列里的排队时间；blocks/s 才是端到端。

## 实现说明

- `wrapFactory(DatabaseFactory)` 覆盖：`-Dxdag.bench.writeBehind` 默认 `true`，建 `new WriteBehindQueue(4096, 256, 20, true)` 并 `start()`，包成 `new WriteBehindFactory(raw, q, 65536)`。`ChainL1TestBase.setUpChain()` 见到 `WriteBehindFactory` 会把队列装成 kernel 的 `PersistControl`（`BlockchainImpl` 在构造器里读一次），`tearDownChain()` 的 `dbFactory.close()` 会先 flush 再停写入线程——所以每个新基座一条新队列，一次运行建 13 个基座不会泄漏非守护线程。
- `WriteBehindQueue` 只有四参构造器 `(maxPending, flushEntries, flushMs, threaded)`；计划里的三参写法不存在，实际用的是 `(4096, 256, 20, true)`。
- `pipeline.rN` 的提交器里不能用 `assertTrue`：它跑在 `IngestPipeline` 的提交线程上，那里的 `AssertionError` 会被 `commit()` 的 `catch (Throwable)` 吞掉只打日志，测试会假绿。改为记下第一个坏 `ImportResult`，`awaitIdle` 之后在主线程 `fail()`。
- 锁内样本用"块有输入"来筛付费块（chunk 由 `ChunkChainBuilder` 用 `links == null` 构造，没有输入）。筛完断言 `k[0] == w.items().size()`，万一这个前提变了，行会显式红而不是悄悄量错块。
- `persist()` 返回 `dbFactory instanceof WriteBehindFactory wb ? wb.queue() : PersistControl.NONE`，所以运行 B 里 `flushSync()` 是空操作，两次运行的 `pipeline` 行定义一致。
- 控制台首行加了 `writeBehind=<bool> ingestThreads=<n>`，末尾加了一行 `acceptance: pipeline median = … -> MET/NOT MET`；JSON 里多了 `writeBehind`、`ingestThreads`、`pipelineMedianPerSec`。其余行与配对差值表一字未动。
- 不带 `-Dxdag.bench=true` 时整个类仍在 `@BeforeClass` 就跳过，不建基座：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.031 s`。

## 修订记录（相对 SP0b-1 基线 `2026-09-18-l1-import-baseline.md`）

- **新增 `pipeline.rN` 行（三轮）**：同一份负载经 `IngestPipeline`（8 个预验证线程并行 parse/哈希/验签，单线程按到达序提交 `SyncManager.importPreValidated`）。mean/p50/p95 是**锁内提交耗时**，blocks/s 是**端到端并含 `flushSync()` 落盘**。这是 SP0b-2 的验收行。
- **新增 `wrapFactory` 覆盖**：基准默认跑在节点真实的写后落盘层上（INDEX/BLOCK/TIME/ORPHANIND 走一条有序队列 + 65536 条 INDEX 读缓存），`-Dxdag.bench.writeBehind=false` 回到同步库。SP0b-1 的基线只有同步库这一种形态。
- **日志降级**：`OrphanBlockStoreImpl` 里 `deleteFromQueue` 与 `addOrphan` 两处每次导入都打的队列统计 INFO 降为 DEBUG。SP0b-1 基线的"注意事项"记的 118603 行/次已经归零。
- **`direct` 行的含义变了**：`tryToConnect(Block)` 现在先 `PreValidator.inline(block)` 再进锁，验签在锁外但仍在调用线程上。对本负载（全部成功导入）总功不变，但不再是 SP0b-1 那个"验签在锁内"的 `direct`。见"注意事项"第 4 条。
- **跨天数字不可直接相减**：SP0b-1 那次运行的机器负载远高于今天（结束时 load 40.19 vs 今天 8.74）。干净的对照是本文档里的 A vs B 与同轮的 `pipeline` vs `direct`。
- **`phase.persist` 由 217.4 µs 降到 22.1 µs**，这是 SP0b-2 的 T2（sums 内存写回）；它在关掉写后落盘的运行 B 里同样是 21.0 µs，所以是纯代码收益。
