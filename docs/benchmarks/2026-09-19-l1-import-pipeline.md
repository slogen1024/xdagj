# L1 导入流水线基线（SP0b-2）

- 日期 / 机器：2026-09-19（10:58–11:36，两次全量运行背靠背）/ Apple M1 Pro，8 核，16 GiB（`hw.memsize` = 17179869184），macOS 15.7.4（24G517，Darwin 24.6.0）/ JDK `openjdk version "21.0.12" 2026-07-21 LTS`（Temurin-21.0.12+8-LTS）
- 代码：`dev-dag-contract`。**被测的树就是提交 `5784047e`（"Benchmark the ingest pipeline and record the SP0b-2 baseline"）**——它等于 T1–T7 的末端 `65cd100b` 加上本任务的两处改动（`OrphanBlockStoreImpl` 两条每次导入都打的 INFO 降为 DEBUG；`ChainL1ImportBenchmarkTest` 加 `wrapFactory` 覆盖与 `pipeline.rN` 行），两者与本文档首版在同一个提交里。
  **跑完之后又有一处修复不在被测树里**：`324c81af`（"Refuse ingest submits once the commit loop is gone"）给每个 `submit` 加了一次取信号量之前的死提交线程判定，也就是每块**多一次无竞争的 `ReentrantLock` 往返**。远在本基准的噪声底（轮间散布 2.6%）之下，不重跑；但本文的数字严格地说是 `5784047e` 的，不是 `HEAD` 的。
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

各列取三轮**中位**。"SP0b-1" 一列来自 `docs/benchmarks/2026-09-18-l1-import-baseline.md`（代码 0500e106，2026-09-18 08:27–08:47）；**那次运行的机器比今天吵得多**（`uptime` 由 5.64 涨到 40.19，今天全程 6–9），所以跨天的差值是代码收益的**上界**，不是干净的测量。

**三种对比的干净程度不一样，必须分开说：**

1. **`pipeline` vs `direct`（同一次运行内）——次干净，不是交错的。** `direct`/`syncPath` 两条腿按 SP0b-1 的做法逐轮交错、奇偶轮换先后，但 **`pipeline` 三轮是在六轮 direct/syncPath 全部跑完之后才跑的**，没有和它们交错。这正是 SP0b-1 基线被推翻重写的那个毛病（C2），本文档不回避：运行 A 里 `direct` 随轮次单调变快（r0 4555 → r1 4631 → r2 4698，**r0→r2 +3.1%**），所以排在后面的 `pipeline` 也吃到了同一份预热红利，1.55× 被抬高最多约 3%；反方向的偏差是"注意事项"第 5 条的排空豁免（`direct` 的 total 不含最后一次排空，让 `direct` 显得更快，把比值**压低**）。两者部分抵消，**1.55× 的可信区间大致是 ±3%**。运行 B 里 `direct` 没有单调趋势（4054 / 4165 / 4152，r1 > r2），所以这份漂移不是每次都有。
2. **A vs B（写后落盘开/关）——最不干净。** 两次运行**也不是交错的**，而且负载不同：A 全程在降（`uptime` 7.18 → 6.36），B 全程在升（6.36 → 8.74）。B 跑在更重的负载下，所以 +11.5% / +17.4% 里含一份**无法量化的共模成分**，只能当方向性结论，不能当精确幅度。
3. **跨天和 SP0b-1 比——最脏**，只有 `phase.persist` 那一项（见下）可以拆干净。

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

- **流水线的净收益（同一次运行内，A；非交错，见上）**：`pipeline` 7157 vs `direct` 4631 blocks/s = **1.55×（±3%）**。注意两个窗口量的不是一回事：`direct` 的 247.6 µs 是**每块导入耗时（调用线程墙钟）**——T7 之后 `tryToConnect(Block)` 先跑 `PreValidator.inline`，验签在**锁外**但仍在同一条线程上，所以这 247.6 µs 里含 ECDSA；`pipeline` 的 140.9 µs 才是纯**锁内**提交。两者之差 **106.7 µs**，和 `phase.signature`（105.6 µs）差 1%——不必说"正好"，但量级对得上，而且**这一项正是省下来的那一项**：`direct` 本来就不付 parse（递给 `tryToConnect` 的块已经解析过），所以"省下来的"≈ 验签一项。
- **被藏起来的池上工作 ≠ 省下来的工作**：池线程每轮做的是 20000 × (parse 15.5 + 验签 105.6) + 5829 × parse 15.5 ≈ **2.51 s CPU**，摊在 8 条线程 × 3.609 s 的窗口上只有 **≈ 9% 利用率**；此外池还要为每块多付一次 512 字节拷贝 + 重新解析（T7 的私有副本），这是 `direct` 从不付的开销。所以"藏住"的是 2.51 s，"相对 `direct` 省下"的只有验签。
- **写后落盘层的净收益（A vs B）**：`direct` +11.5%（4152 → 4631），`pipeline` +17.4%（6096 → 7157），`pipeline` 锁内 mean −13.9%（163.6 → 140.9 µs）。`phase.refLookup` 由 6.8 µs 降到 4.5 µs 是 INDEX 的 65536 条读缓存。**两次运行不交错且负载不同（A 降、B 升），这三个百分比含共模成分**，方向可信、幅度不精确。方向上还有一点要留意：排空豁免只影响 A（B 没有队列可排），所以它让 A 的 `direct` 显得更快，**+11.5% 是被高估而不是被低估的**；A、B 两条腿都调了 `flushSync()` 的 `pipeline` 行（+17.4%）没有这个问题，是 A/B 之间唯一定义对齐的比较。
- 跨天看，`direct` 的 mean 由 460.2 降到 247.6 µs（−46.2%），blocks/s 由 2323 涨到 4631（1.99×）；其中有多少是机器变安静带来的无法从本次数据里拆开。可以拆开的是 `phase.persist`：217.4 → 22.1 µs（−90%），这是 T2 的 sums 内存写回，**在 B 里同样是 21.0 µs**（`phase.persist` 用的是没包写后层的 scratch 库），所以它是纯代码收益，与机器负载无关。

### 确认路径

| 行 | 统计量 | SP0b-1 | B | A |
|---|---|---|---|---|
| `confirmed` | blocks/s | 80 | 82 | 83 |
| `confirmed` | mean µs/主块 | 155192.8 | 154035.6 | 153485.5 |
| `confirmed` | p50 µs/主块 | 108822.3 | 108866.1 | 108018.3 |
| `calib.emptyMain` | mean µs/主块 | 98344.1 | 100560.9 | 97227.4 |

确认路径**基本没动**（80 → 83 blocks/s），符合预期：它被基座的伪 PoW 主导，而 SP0b-2 动的是 `tryToConnect`，不是 `setMain`/`applyBlock`。那个 62% 是用 **mean 列乘主块数再除 total 列**算的：`calib.emptyMain` 的 mean 97227.4 µs × 2001 个主块 = 194.6 s，除以 `confirmed.r2` 的 total 312870 ms = **62.2%**（找 nonce 是几何分布、尾巴重，分解总量只能用均值，不能用 p50）。扣掉空主块后 apply ≈ 153.5 − 97.2 = 56.3 ms/主块 ≈ 5.63 ms/付费块，与 SP0b-1 的 5.68 ms 一致。

## 验收判定

**目标：`pipeline` 三轮 blocks/s 的中位 ≥ 10,000 块/s。**

**未达成。** 节点默认形态（运行 A，写后落盘开、8 个预验证线程）下三轮为 7157 / 7319 / 7137，**中位 = 7157 块/s**，是目标的 **71.6%**，差 2843 块/s。写后落盘关掉（运行 B）中位 = 6096 块/s。控制台原文：`acceptance: pipeline median = 7157 blocks/s over 3 rounds, target 10000 -> NOT MET`。

（`pipeline` 的 blocks/s 是端到端：从第一个 `submit` 到最后一个提交返回，**并且包含** `persist().flushSync()` 把写后队列排空，所以这个吞吐里落盘是算进去的。）

**和设计里"安静机器"这个验收前提的关系。** SP0b-2 的验收本来假定在一台安静机器上量，本次机器并不安静（`uptime` 全程 6–9，8 核）。这一条**对代码有利而不是不利**——更安静只会让数字更好看，所以它不能用来解释未达成，只能缩小差距。能拿到的上界是运行 A 自己的轮间散布：`pipeline` 三轮 7137 / 7157 / 7319，极差 **2.6%**；`direct` 三轮的单调漂移 **3.1%**。也就是说，机器噪声在本次运行里最多值几个百分点，而缺口是 **28.4%**（7157 vs 10000）。**负载解释不了这个缺口。**

### 剩下的时间在哪

**瓶颈是单线程的锁内提交，而且再多的预验证线程也到不了目标——这一点可以不依赖"线程 100% 忙"的假设直接证出来。**

只看一个能直接测到的量：运行 A 的 `pipeline.r0` 里，**付费块的锁内提交合计 20000 × 140.919 µs = 2818 ms，占整轮 3609 ms 的 78.1%**。提交是严格串行的，所以这 2818 ms 是整轮无论如何都绕不过去的下限。于是**即便预验证被完美藏住、提交线程零空转、chunk 提交零成本**，这一轮也不可能快过 2818 ms，对应的**硬上限是 25829 / 2.818 s = 9164 块/s**——仍然比目标低 8.4%。**加线程、加队列都翻不过这道墙，唯一的出路是把锁内每块的时间压下去。**

其余 3609 − 2818 = **790 ms** 是 chunk 提交 + 空转 + `flushSync()` 三者之和，本基准没有分别计时。由此只能得到一个**上界**：chunk 的锁内提交 **≤ 790.6 ms / 5829 = 135.6 µs/个**（等号只在空转与排空都为零时成立）。上一版把它写成"≈135.7 µs 的测量值"是不对的——那是一个以零空转为前提的推断，这里改成上界。

旁证（独立于上面那条不等式）：池线程每轮的工作量约 **2.51 s CPU**（20000 × (parse 15.5 + 验签 105.6) + 5829 × parse 15.5），摊在 8 条线程 × 3.609 s = 28.9 线程秒上只有 **≈ 9% 利用率**。池远没有饱和，瓶颈确实在提交侧。

拿 `phase.*` 拆运行 A 的 140.9 µs：

| 锁内分段 | µs/付费块 | 占锁内时间 |
|---|---|---|
| 验签（`verifiedKeys()` 的 ECDSA） | 0（已移到池线程，`phase.signature` = 105.6 µs 在锁外） | 0% |
| persist（`BlockStore.saveBlock`） | ≤ 22.1（`phase.persist`；写后层下锁内只是入队，实际更低） | ≤ 15.7% |
| refLookup（块链接查询） | 1943 × 4.519 µs ÷ 20000 = 0.4 | 0.3% |
| **其余（见下"其余里最可能是什么"）** | **≥ 118.3**（= 140.919 − 22.131 − 0.439） | **≥ 84.0%** |

运行 B 用同样的 ≤ / ≥ 口径（`phase.persist` 在两次运行里都是一个**下界**，见"注意事项"第 7 条，不能说它"直接等于"锁内成本）：163.6 = ≤ 21.0（persist，≤ 12.8%）+ 0.7（refLookup）+ **≥ 141.9（其余，≥ 86.7%）**。两次运行的结论一致。

结论很直接：**SP0b-2 该搬的都搬完了，锁内剩下的 ≥ 84% 是"其余"那一坨，它和 SP0b-1 时相比几乎没变（132.3 → ≥ 118.3 µs，约 −10%）。** 两个推论：

1. **把 persist 打到 0 也到不了目标。** 要用行自己的口径（blocks/s 的分母是 25829 个单位、分子是整轮墙钟），不能拿"每付费块的速率"去比。从 3609 ms 里扣掉 persist 的两种扣法给出一个区间：只扣付费块（20000 × 22.131 µs = 442 ms）→ 3166 ms → **8157 块/s**；连 chunk 一起扣（25829 × 22.131 µs = 572 ms）→ 3037 ms → **8504 块/s**。即 **8157–8504 块/s，仍短 15–18%**。（上一版写的"140.9 − 22.1 = 118.4 µs → 8446 块/s"两处都错：减法结果是 118.8，0.4 µs 的 refLookup 被悄悄吞掉了；而且 8446 是"每付费块"的速率，和"每单位"的目标不同口径。）
2. **要到 10,000 块/s，平均每单位的锁内提交必须 ≤ 100 µs**（目前 3609 ms / 25829 = 139.7 µs），也就是"其余"要从 ≥ 118.3 µs 砍到 ≲ 78 µs（约 −34%）。

#### "其余"里最可能是什么（按本树的已知事实排序，不是猜）

上一版只是照抄了 SP0b-1 的嫌疑人名单，没有排序。这棵树本身已经排除掉了其中两个大项：

- **`checkNewMain()` 基本可以划掉**：T5 已经把它改成 `(top, p, i, chainVersion)` 缓存的 **O(1) 增量**，不再每次导入都从 top 回走到上一个 `BI_MAIN`。SP0b-1 时它是"其余"里明确的一块，现在不是了。
- **ORPHANIND / INDEX / BLOCK / TIME 的写入基本可以划掉**：T1 之后它们在锁内只是**入队**（`WriteBehindKVSource` 追加到有序队列），真正的 `batchWrite` 在 `xdag-persist` 线程上。孤块池写入、`saveXdagStatus`、`saveBlockInfo` 的磁盘代价都不在锁里了。

按排除法，那 ≥ 118 µs 落在**没有被延后、也没有被移出锁**的工作上，其中最值得先查的是：

1. **链接校验里的 AddressStore 读**（每个地址链接查余额 / 费用 / nonce）。这是头号嫌疑：`WriteBehindFactory` 的 `WRAPPED` 只有 INDEX / BLOCK / TIME / ORPHANIND，**ADDRESS 是故意不包的**（它由 apply 路径写，那条路径本来就跑在直写模式下）；而且 `readCacheEntries` 只加在 `DatabaseName.INDEX` 上，**ADDRESS 连读缓存都没有**。本负载每个付费块有 2 个带金额的链接，全是地址链接，所以每块至少两次未加缓存的 RocksDB 读。
2. 锁内的时间戳区间与孤块池上限检查、`isExist` / `isExistInMem`。
3. 难度计算与 top / 统计更新、`removeOrphan` 的内存队列维护（`deleteFromQueue` 里对四个队列做 `contains` + `remove`，是线性扫描）。

**但这只是排序，不是测量。** 本基准现在没有任何一行能把这 118 µs 再拆开——`phase.*` 只覆盖 parse / signature / refLookup / persist。**下一个任务在动手优化之前，必须先给这几个子步骤补上 `phase.*` 行**（至少：AddressStore 读、孤块池增删、难度与统计更新），否则就是在没有基线的情况下改共识路径。

## 注意事项

1. **机器负载 / 交错**。两次运行期间没有别的 Maven / surefire，但机器不是空的：常驻的 AiCoin、IntelliJ、Telegram 等让 `uptime` 全程在 6–9 之间（8 核），**没有满足设计里"安静机器"的验收前提**；这一条对代码有利，而且被运行 A 自己的 2.6% 轮间散布框住，解释不了 28.4% 的缺口（见"验收判定"）。交错情况见"对比表"开头：`direct`/`syncPath` 交错，**`pipeline` 不与它们交错**（跑在六轮之后），**A 与 B 之间也不交错且负载走向相反**。同一次运行、同一张表内的轮间比较与配对差值最可信。**跨天和 SP0b-1 比要打折扣**——那次运行结束时 load 已到 40.19，今天最高 8.74。
2. **`txHistoryStore == null`**。与 SP0b-1 一致，走的是 `node.transaction.history.enable` 关闭时的生产路径。
3. **残留日志**。运行窗口（10:58–11:36，两次运行共 38 min）`logs/xdag-info.log` 写了 **144864 行 / 29.6 MB**。`OrphanBlockStoreImpl` 的队列统计 INFO **已经降成 DEBUG，窗口内 0 行**（SP0b-1 的单次运行里是 118603 行）。剩下的全是 apply 路径的 "Balance checker" WARN：`BlockchainImpl:2682`（地址级）120026 行 + `BlockchainImpl:2700`（块级）24724 行，合计约 72400 行/次运行，与 SP0b-1 的 72510 行一致——**它们只落在 `confirmed` 行里，导入路径（`direct`/`syncPath`/`pipeline`）现在基本不写日志**。账能对平：**144864 = 120026 + 24724 + 114**，最后那 114 行是零星的启动/配置/`WriteBehindQueue` 起停日志（26 + 26 + 26 + 13 + 13 + 4 + 2 + 2 + 2）。`OrphanBlockStoreImpl` 里第三条同样的 INFO 在 `getOrphan()`，按计划未动——它在**生产节点上每出一个主块打一次**，但在本基座里**一次都没触发**（基座的 `mineMain` 自己拼链接、不走 `getOrphan()` 选块），这正是上面那个等式能对平、且窗口内 `OrphanBlockStoreImpl` 为 0 行的原因。换句话说，这条 INFO 在真实节点上的成本本基准**没有覆盖到**。
4. **`tryToConnect(Block)` 的语义变化（T7 的副作用）**。它现在是个不带 `synchronized` 的一行，转发给 `tryToConnect(PreValidator.inline(block))`；`inline()` 在**拿锁之前**就把 `parse()`、`getHashLow()` 和 `verifiedKeys()` 的 ECDSA 算掉。对一个会被接受的块，总工作量不变（只是挪出了锁）；但对一个**会被早早拒掉**的块（EXIST、类型/时间不合法、孤块池满），它现在要付一次它以前不付的 ECDSA。本基准的负载每个块都成功导入（每轮都断言了），所以 `direct` 行与 `pipeline` 行做的是同样的总功，两行可比；真实节点上重复的 gossip 会让这部分变成投机开销。缓解因素：`inline()` 只对**有输入**的块算公钥，chunk、链接块、主块（本负载里 5829 个 chunk）一分钱不付。
5. **写后落盘下 `direct`/`syncPath` 的 total 不含最后一次排空**。只有 `pipeline` 行调了 `flushSync()`。`WriteBehindQueue` 的 `maxPending` 是 4096 条，一轮大约写 20 万条，所以运行 A 的 `direct`/`syncPath` blocks/s 最多乐观约 2%；`pipeline` 行没有这个问题。**方向要分清**：这让 `direct` 显得偏快，于是同一次运行里的 **1.55×（`pipeline`/`direct`）是保守的**（真实比值只会更高）；但对 A vs B 的 `direct` 比较是反过来的——B 没有队列可排，豁免只惠及 A，所以 **+11.5% 是偏高而非保守**。A/B 之间定义真正对齐的只有 `pipeline` 行（两边都 `flushSync()`），即 +17.4%。
6. **`-Dchain.ingest.threads=0` 对本基准是空操作**。那个键只在 `SyncManager.doStart()` 里读，而基准从不启动 `SyncManager`（`syncManager()` 只 `new` 一个并接好 kernel）；`pipeline.rN` 用的是基准自己构造的 `IngestPipeline`（线程数 `-Dxdag.bench.ingestThreads`，默认 `availableProcessors()` = 8），`syncPath.rN` 走的是 `validateAndAddNewBlock`，它本来就不经流水线。所以运行 B 的 `pipeline` 行量的是"流水线 + 同步库"，不是"关掉流水线"。
7. **`phase.persist` 是下界**（A、B 两次都是，所以上面所有用到它的分段都写成 ≤ / ≥）。它写的是一个空的 scratch 库（`RocksdbFactory`，**不包写后层**，A/B 两次都一样），三遍取最好，且重放时 `BlockInfo` 直接来自 `parse()`、比真实路径序列化出来小。真实节点库越大越贵。另外本次 **`phase.persist.first` 与 `phase.persist` 在两次运行里完全相等**（A 22.131 / B 21.020），而 SP0b-1 是 246.8 vs 217.4——冷热两遍的差价没有了，因为 T2 的 sums 内存写回去掉了每块 4 个 4 KB sums 数组的读改写，第一遍不再有"所有 sums 键都是新键"的额外代价。
8. **投机预验证的占比本次为 0**，因为负载里没有重复块，每个 `submit` 的块都被成功导入。真实节点上重复 gossip 会让池线程白算 parse + ECDSA；如果这一项变大，下一步就是在池线程上并发做一次 `isExist` 预筛。本次没有测量这一项。
9. **`pipeline` 行的 mean/p50/p95 是锁内提交耗时**（`SyncManager.importPreValidated` 的墙钟，含 `tryToConnect` 与中继簿记），**不是**端到端延迟，也不含块在队列里的排队时间；blocks/s 才是端到端。

## 实现说明

- `wrapFactory(DatabaseFactory)` 覆盖：`-Dxdag.bench.writeBehind` 默认 `true`，建 `new WriteBehindQueue(4096, 256, 20, true)` 并 `start()`，包成 `new WriteBehindFactory(raw, q, 65536)`。`ChainL1TestBase.setUpChain()` 见到 `WriteBehindFactory` 会把队列装成 kernel 的 `PersistControl`（`BlockchainImpl` 在构造器里读一次），`tearDownChain()` 的 `dbFactory.close()` 会先 flush 再停写入线程——所以每个新基座一条新队列，一次运行建 13 个基座不会泄漏非守护线程。
- `WriteBehindQueue` 只有四参构造器 `(maxPending, flushEntries, flushMs, threaded)`；计划里的三参写法不存在，实际用的是 `(4096, 256, 20, true)`。**这四个数加上 65536 正是 `ChainSpec` 的默认值**——`DEFAULT_PERSIST_MAX_PENDING=4096`、`DEFAULT_PERSIST_FLUSH_ENTRIES=256`、`DEFAULT_PERSIST_FLUSH_MS=20`、`DEFAULT_PERSIST_READ_CACHE=65536`——也正是 `Kernel` 自己传的那几个参数（`new WriteBehindQueue(getChainPersistMaxPending(), getChainPersistFlushEntries(), getChainPersistFlushMs(), true)` + `new WriteBehindFactory(dbFactory, persistQueue, getChainPersistReadCache())`）。基准的 `ingestThreads=8` 同样等于 `DEFAULT_INGEST_THREADS`（`availableProcessors()`）、队列容量 4096 等于 `DEFAULT_INGEST_QUEUE`。这是"运行 A = 节点默认形态"这个说法的依据。
- `pipeline.rN` 的提交器里不能用 `assertTrue`：它跑在 `IngestPipeline` 的提交线程上，那里的 `AssertionError` 会被 `commit()` 的 `catch (Throwable)` 吞掉只打日志，测试会假绿。改为记下第一个坏 `ImportResult`，`awaitIdle` 之后在主线程 `fail()`。
- 锁内样本用"块有输入"来筛付费块（chunk 由 `ChunkChainBuilder` 用 `links == null` 构造，没有输入）。筛完断言 `k[0] == w.items().size()`，万一这个前提变了，行会显式红而不是悄悄量错块。
- `persist()` 返回 `dbFactory instanceof WriteBehindFactory wb ? wb.queue() : PersistControl.NONE`，所以运行 B 里 `flushSync()` 是空操作，两次运行的 `pipeline` 行定义一致。
- 控制台首行加了 `writeBehind=<bool> ingestThreads=<n>`，末尾加了一行 `acceptance: pipeline median = … -> MET/NOT MET`；JSON 里多了 `writeBehind`、`ingestThreads`、`pipelineMedianPerSec`。其余行与配对差值表一字未动。
- 不带 `-Dxdag.bench=true` 时整个类仍在 `@BeforeClass` 就跳过，不建基座：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.031 s`。

## 修订记录

### 本文档自身的修订（2026-09-19，评审后，**数字未重跑**）

评审复核了每一个表格单元（对着 `target/bench/*.json`）与每一条日志计数，验收结论 7157 块/s / 71.6% / 未达成**原样成立**。改的全是措辞与推导，不是数据：

- **交错问题说清楚了**：`pipeline` 三轮并没有和 `direct`/`syncPath` 交错（跑在六轮之后），A 与 B 之间也不交错且负载走向相反。原文把这两处都当成"干净的比较"，现在量化了轮内漂移（A 的 `direct` r0→r2 +3.1%；B 无单调趋势）并给出 1.55× 的 ±3% 区间。
- **"线程 100% 饱和"的循环论证换掉了**：原文用"端到端 139.7 ≈ 锁内 140.9 ⇒ 饱和"，这是同一个量绕了一圈。改成不依赖该假设的硬上限：付费块提交合计 2818 ms = 整轮的 78.1%，故**即使零空转、chunk 零成本，上限也只有 9164 块/s**；另加池利用率 ≈ 9% 的旁证。135.7 µs 的 chunk 提交由"测量值"降级为**上界**。
- **"把 persist 打到 0"重算**：原文 `140.9 − 22.1 = 118.4` 算错（应为 118.8，且吞掉了 0.4 µs 的 refLookup），并把"每付费块"的 8446 块/s 拿去和"每单位"的目标比。改为行自身口径的区间 **8157–8504 块/s，短 15–18%**。
- **运行 B 的分段补上 ≤ / ≥**，并删掉"`phase.persist` 可以直接和锁内成本对齐"——它和"注意事项"第 7 条（`phase.persist` 是下界）自相矛盾。
- **"其余"那一坨给出排序与依据**：`checkNewMain`（T5 已 O(1)）与四个库的写入（T1 已改为入队）基本可以划掉，按排除法头号嫌疑是**链接校验里的 AddressStore 读**（`WriteBehindFactory` 故意不包 ADDRESS，读缓存又只加在 INDEX 上）。并明确要求**下一个任务先补 `phase.*` 子步骤行再动手优化**。
- 补充：与设计中"安静机器"验收前提的关系（该项对代码有利，且被 2.6% 的轮间散布框住，解释不了 28.4% 的缺口）；排空豁免对 1.55×（保守）与 +11.5%（偏高）方向相反；62% 的算法（mean 列 × 主块数 ÷ total 列）；日志等式 144864 = 120026 + 24724 + 114 与 `getOrphan()` 那条 INFO 在本基座触发 0 次；`(4096, 256, 20, true)` + 65536 就是 `ChainSpec.DEFAULT_PERSIST_*` 与 `Kernel` 所传的参数；池额外承担 T7 的 512 字节拷贝 + 重解析；`phase.persist.first == phase.persist`（T2 抹平了冷热两遍的差价）。

### 相对 SP0b-1 基线 `2026-09-18-l1-import-baseline.md`

- **新增 `pipeline.rN` 行（三轮）**：同一份负载经 `IngestPipeline`（8 个预验证线程并行 parse/哈希/验签，单线程按到达序提交 `SyncManager.importPreValidated`）。mean/p50/p95 是**锁内提交耗时**，blocks/s 是**端到端并含 `flushSync()` 落盘**。这是 SP0b-2 的验收行。
- **新增 `wrapFactory` 覆盖**：基准默认跑在节点真实的写后落盘层上（INDEX/BLOCK/TIME/ORPHANIND 走一条有序队列 + 65536 条 INDEX 读缓存），`-Dxdag.bench.writeBehind=false` 回到同步库。SP0b-1 的基线只有同步库这一种形态。
- **日志降级**：`OrphanBlockStoreImpl` 里 `deleteFromQueue` 与 `addOrphan` 两处每次导入都打的队列统计 INFO 降为 DEBUG。SP0b-1 基线的"注意事项"记的 118603 行/次已经归零。
- **`direct` 行的含义变了**：`tryToConnect(Block)` 现在先 `PreValidator.inline(block)` 再进锁，验签在锁外但仍在调用线程上。对本负载（全部成功导入）总功不变，但不再是 SP0b-1 那个"验签在锁内"的 `direct`。见"注意事项"第 4 条。
- **跨天数字不可直接相减**：SP0b-1 那次运行的机器负载远高于今天（结束时 load 40.19 vs 今天 8.74）。干净的对照是本文档里的 A vs B 与同轮的 `pipeline` vs `direct`。
- **`phase.persist` 由 217.4 µs 降到 22.1 µs**，这是 SP0b-2 的 T2（sums 内存写回）；它在关掉写后落盘的运行 B 里同样是 21.0 µs，所以是纯代码收益。
