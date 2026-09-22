# 孤块池 add/remove 基线（SP0b-3 改造前）

**本次测量在 `1c2e2d94` 之后、任何 SP0b-3 行为改动之前。** 被测树 = `08e72a97`（"Plan SP0b-3 as sixteen TDD tasks"）
的生产代码，加上本任务对 `ChainL1ImportBenchmarkTest` 的唯一改动。**生产代码一行未动**：
`OrphanBlockStoreImpl` 与 `BlockchainImpl` 在两次运行里就是 `08e72a97` 的样子。

两次运行期间分支上另落了三个提交（`58418f95` / `086b13da` / `63919aba`），
**三个都只改 `docs/superpowers/plans/2026-09-19-xdag-chain-sp0b3-orphan-pool.md` 一个文件**，
不碰生产代码也不碰基准代码，因此对本文数字没有影响；本文的基准改动提交在 `63919aba` 之上。
SP0b-3 后续每一个任务都应当和本文的表做对比。

## 四行新数字，各自的用途

| 行 | 每次调用 mean | 它是干什么的 |
|---|---|---|
| `phase.orphan.add` | **38.2 µs** | 守护快乐路径：改造后不得变慢 |
| `phase.orphan.remove` | **2.9 µs** | 守护快乐路径：改造后不得变慢（**这是最好情况**，见 §3） |
| `phase.orphan.removeWorst.d125` | **5.4 µs** | ↓ |
| `phase.orphan.removeWorst.d500` | **10.0 µs** | **SP0b-3 要移动的就是这两个数**，以及它们之间的斜率 |

**两个 `removeWorst` 之间的比值才是结论**：桶深 ×4（125 → 500），单次移除 ×1.85（5.4 → 10.0 µs）。
拆成"固定开销 + 与深度成正比的扫描"两项，可解出 **固定 ≈ 3.9 µs，线性项 ≈ 1.5 µs / 125 深**
（≈ 12 ns 每个队列元素）。移除代价确实随池深上升——这正是规格 §7.2「移除复杂度：池内条目数放大后，
单次移除的代价不随之线性上升」要消灭的东西，Task 15 的复杂度断言就该对着这条斜率判。

对照组：`orphan.remove` 在桶深 31（2000 块）与桶深 312（20000 块）下分别是 3.0 与 2.9 µs——
**深度 ×10，代价不变**。同一个 `deleteFromQueue`，换一个移除顺序就从"与深度无关"变成"随深度上升"，
差别只在下面 §3 说的那件事。

## 环境与两次运行

- 机器：Apple M1 Pro（`MacBookPro18,3`），8 核，16 GiB（`hw.memsize` = 17179869184），
  macOS 15.7.4（24G517，Darwin 24.6.0）/ JDK `openjdk version "21.0.12" 2026-07-21 LTS`（Temurin-21.0.12+8-LTS）
- 分支：`dev-dag-contract`，HEAD `08e72a97`
- `TransactionHistoryStore`：`null`；`writeBehind=true`（节点默认形态）；`ingestThreads=8`；seed=20260917

本文的数字来自**同一台机器、同一晚、同一状态**下的两次运行：

| | 运行 A | 运行 B |
|---|---|---|
| 时间 | 2026-09-22 20:32–20:56（23 min 57 s） | 2026-09-22 21:32–21:35（2 min 39 s） |
| `blocks` | 20000（chunks 5829） | 2000（chunks 579） |
| 原始输出 | `target/bench/l1-import-20260922-205641.json` | `target/bench/l1-import-20260922-213511.json` |
| 本文取用 | **除 `removeWorst.*` 外的全部行** | **只取 `removeWorst.d125` / `d500` 两行** |

为什么分两次：`removeWorst` 把负载压到 4 个地址上，每次 `contains()` + `remove()` 都要走一趟桶，
**代价随桶深的平方增长**。按 20000 块的规模做，一趟计时就是 10⁸ 量级的比较，三趟加三次重建跑不完也放不下
（一次尝试已被系统以内存不足杀掉）。这行要回答的是"移除代价是否随深度上升"，**靠的是形态和深度，不是样本数**,
所以它用自己的小负载、自己的 `n`。运行 B 的负载是运行 A 负载的**前 2000 项，逐字节相同**
（`BenchWorkload` 单遍生成，同 seed 同 sender 数 ⇒ 前 N 项与总量无关）。

**注意 `removeWorst.*` 的 `n` 是它自己的移除次数（626 / 2579），不是 20000。**
不要拿它的每次代价去和 20000 样本的行直接比绝对值；它只跟另一个深度的自己比。

## 命令

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"

# 运行 A
mvn -o -Dxdag.bench=true -Dtest='io.xdag.chain.bench.ChainL1ImportBenchmarkTest' test
# 运行 B
mvn -o -Dxdag.bench=true -Dxdag.bench.blocks=2000 -Dtest='io.xdag.chain.bench.ChainL1ImportBenchmarkTest' test
```

不带 `-Dxdag.bench=true` 时该类照旧整体 skip（`@BeforeClass` 的 `assumeTrue`），本次改动没有动这个门，已验证。

## 运行 A 完整表（20000 块）

```
L1 import benchmark: senders=64 blocks=20000 chunks=5829 mix=[60, 25, 10, 5] kinds=[PLAIN 12075, CALL_INLINE 4975, CALL_CHAIN 1943, DEPLOY 1007] seed=20260917 confirmedMainsPerRound=2001 txHistoryStore=null writeBehind=true ingestThreads=8
```

| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |
|---|---|---|---|---|---|---|
| direct.r0 | 20000 | 3412 | 333.7 | 294.3 | 373.6 | 7570 |
| syncPath.r0 | 20000 | 2937 | 386.4 | 330.3 | 583.2 | 8795 |
| syncPath.r1 | 20000 | 3394 | 338.5 | 319.1 | 370.0 | 7610 |
| direct.r1 | 20000 | 3667 | 313.3 | 290.6 | 345.3 | 7044 |
| direct.r2 | 20000 | 3619 | 316.1 | 291.5 | 353.3 | 7137 |
| syncPath.r2 | 20000 | 3302 | 345.9 | 320.2 | 383.5 | 7823 |
| pipeline.r0 | 20000 | 3503 | 200.7 | 160.8 | 259.4 | 7374 |
| pipeline.r1 | 20000 | 5380 | 189.5 | 159.6 | 214.7 | 4801 |
| pipeline.r2 | 20000 | 5267 | 192.8 | 159.3 | 227.3 | 4904 |
| confirmed.r0 | 2001 | 65 | 194470.6 | 135645.5 | 580258.5 | 396431 |
| confirmed.r1 | 2001 | 66 | 192459.7 | 136388.1 | 558899.8 | 392205 |
| confirmed.r2 | 2001 | 66 | 192573.1 | 135214.8 | 563728.0 | 392499 |
| calib.emptyMain | 100 | 8 | 121720.6 | 79464.4 | 396932.9 | 12172 |
| phase.parse | 20000 | 51006 | 19.6 | - | - | 392 |
| phase.signature | 20000 | 7642 | 130.9 | - | - | 2617 |
| phase.refLookup | 1943 | 199970 | 5.0 | - | - | 9 |
| **phase.orphan.add** | **20000** | **26163** | **38.2** | - | - | **764** |
| **phase.orphan.remove** | **20000** | **345521** | **2.9** | - | - | **57** |
| phase.persist.first | 20000 | 24164 | 41.4 | - | - | 827 |
| phase.persist | 20000 | 24164 | 41.4 | - | - | 827 |

| round | order | syncPath-direct mean µs | p50 µs | total ms |
|---|---|---|---|---|
| r0 | direct>syncPath | +52.7 | +36.0 | +1225 |
| r1 | syncPath>direct | +25.2 | +28.5 | +566 |
| r2 | direct>syncPath | +29.8 | +28.7 | +686 |
| median | | +29.8 | +28.7 | +686 |

```
acceptance: pipeline median = 5267 blocks/s over 3 rounds, target 10000 -> NOT MET
```

运行 A 跑在加断言之前的版本上（见 §4）。计时体逐字完全相同，断言只活在**未计时的恢复**里。

## 运行 B 完整表（2000 块，本文只取 `removeWorst` 两行）

```
L1 import benchmark: senders=64 blocks=2000 chunks=579 mix=[60, 25, 10, 5] kinds=[PLAIN 1228, CALL_INLINE 490, CALL_CHAIN 193, DEPLOY 89] seed=20260917 confirmedMainsPerRound=201 txHistoryStore=null writeBehind=true ingestThreads=8
```

| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |
|---|---|---|---|---|---|---|
| direct.r0 | 2000 | 3208 | 346.7 | 320.3 | 450.3 | 804 |
| syncPath.r0 | 2000 | 2007 | 561.1 | 404.7 | 1097.6 | 1285 |
| syncPath.r1 | 2000 | 3256 | 350.0 | 335.3 | 379.4 | 792 |
| direct.r1 | 2000 | 3679 | 310.5 | 294.0 | 341.4 | 701 |
| direct.r2 | 2000 | 3804 | 301.2 | 289.2 | 328.2 | 678 |
| syncPath.r2 | 2000 | 3411 | 332.7 | 321.6 | 373.0 | 756 |
| pipeline.r0 | 2000 | 5179 | 183.6 | 156.4 | 191.2 | 498 |
| pipeline.r1 | 2000 | 4113 | 241.8 | 163.8 | 378.5 | 627 |
| pipeline.r2 | 2000 | 5148 | 181.9 | 154.3 | 196.0 | 501 |
| confirmed.r0 | 201 | 75 | 168222.2 | 112285.9 | 473629.3 | 34530 |
| confirmed.r1 | 201 | 75 | 167225.1 | 110254.2 | 482251.4 | 34320 |
| confirmed.r2 | 201 | 76 | 166318.3 | 111209.9 | 481337.6 | 34145 |
| calib.emptyMain | 100 | 8 | 127723.3 | 81153.3 | 472366.3 | 12772 |
| phase.parse | 2000 | 51233 | 19.5 | - | - | 39 |
| phase.signature | 2000 | 7780 | 128.5 | - | - | 257 |
| phase.refLookup | 193 | 213762 | 4.7 | - | - | 0 |
| phase.orphan.add | 2000 | 32342 | 30.9 | - | - | 61 |
| phase.orphan.remove | 2000 | 331082 | 3.0 | - | - | 6 |
| **phase.orphan.removeWorst.d125** | **626** | **184269** | **5.4** | - | - | **3** |
| **phase.orphan.removeWorst.d500** | **2579** | **100304** | **10.0** | - | - | **25** |
| phase.persist.first | 2000 | 35749 | 28.0 | - | - | 55 |
| phase.persist | 2000 | 40481 | 24.7 | - | - | 49 |

运行 B 的 `orphan.add` / `orphan.remove`（30.9 / 3.0）在噪声内复现了运行 A 的 38.2 / 2.9，
且是**带着全部断言**跑出来的——这同时也是运行 A 那两行没有测到退化状态的旁证。

---

## 1. 这些数字里装了什么（不要误读）

**`orphan.add` 里有数据库，`orphan.remove` 与 `removeWorst` 里没有。**

- `addOrphan` = 内存入队 + **1 次 AddressStore 同步读**（`getExecutedNonceNum`；ADDRESS 库不在 write-behind
  的包装名单里，是真的 RocksDB get）+ **2 次 ORPHANIND 读 + 2 次 ORPHANIND 写**（条目本身与 `ORPHAN_SIZE`
  计数器）经过节点真实的 write-behind 队列。**所以 38.2 µs 的大头不是队列逻辑，是存储。**
- `deleteFromQueue` **完全不碰数据库**。`removeOrphan` 的数据库那一半在 `deleteByKey` 里，
  两个 remove 行都**故意不含它**，所以它们是纯粹的"移除遍历"成本。

内存部分可以估出来并与实测对上：64 sender、20000 条 ⇒ 每桶 ~312 条，`addOrphan` 的 `contains()` 是
**整桶扫描**（条目不存在，扫到底），Σ ≈ 312²/2 × 64 ≈ 3.1M 次 `Arrays.equals(32B)` ≈ 78 ms，
约占 `orphan.add` 全趟 764 ms 的 10%。**剩下九成落在 ADDRESS 同步读 + write-behind 入队（含队列满时的背压）。**
每次计时前的未计时恢复都调了 `persist().flushSync()`，计时窗口开始时队列是空的；窗口内 40000 次写
产生的背压仍然计入本行。这 35 µs 没有再拆开——**SP0b-3 若要动存储布局，应先补一次拆解**，
否则容易把存储的改善记到队列头上。

## 2. `orphan.add` 与 `orphan.remove` 与池深无关（实测）

| 行 | 2000 块（桶深 ~31） | 20000 块（桶深 ~312） | 变化 |
|---|---|---|---|
| `orphan.add` | 30.9 µs | 38.2 µs | 深度 ×10，代价基本不变（差值是机器噪声） |
| `orphan.remove` | 3.0 µs | 2.9 µs | 深度 ×10，代价不变 |

`add` 与深度无关，因为它被存储主导（§1）。`remove` 与深度无关的原因见下一节——那是本文最重要的一句话。

## 3. `orphan.remove` 是**最好情况**，不是最坏情况

基准按负载顺序移除，而负载顺序对每个 sender 恰好是 **nonce 升序**，
这正是 `accountTxMap` 比较器建堆的顺序。于是每次 `contains()` 与 `remove()` 都命中**下标 0**，
整行是 O(log n) 的下沉，**不是 O(n) 的扫描**。

这恰好也是快乐路径的形状（`selectBlocks` 同样从队头取），所以它是
「SP0b-3 不得让正常路径变慢」的正确 BEFORE 数字——**但它对洪泛一无所知**。
如果 BEFORE 只有这一个数，改造后它会从 2.9 变成差不多的数值，什么也证明不了。
`removeWorst.d*` 就是为此存在的。

## 4. `removeWorst.d*` 的形态（按规格 §7.2「垃圾分片洪泛」/ §3「移除复杂度」搭的）

三件事让它成为最坏情况：

1. **少 sender、深队列。** 付费块被改派到 `WORST_SENDERS = 4` 个地址上，每桶 125 / 500 条，
   而不是 20000/64 ≈ 312。**只有 address 参数是合成的**——池收到的正是"几个地址洪泛"会呈现的样子，
   而且用的是负载自己的真实 sender 地址（AddressStore 里有它们），
   所以 `getExecutedNonceNum` 的行为与生产一致，而不是对着一个从未注资的 key。
2. **`linkQueue` 与 `mainRef` 都算上，不只是账户桶。** 所有分片块一并入池——分片块不是交易块，
   落在 `linkQueue`（规格 §1：「分片块就住在里面」）；再由一次 `getOrphan(isMain = true)` 给 `mainRef` 播种。
   `mainRef` 是 `ConcurrentLinkedDeque`，`deleteFromQueue` **对每一次移除都要走它一趟**，无论条目属于哪一类。
   两者都是 O(n) 且没有堆结构可借力（规格 §3 表）。
3. **乱序移除。** 固定 seed 洗牌，`contains()` / `remove()` 落在队列中部，而不是像 `orphan.remove` 那样落在下标 0。

**`dNNN` 是账户桶的深度**；随着它从 125 到 500，`linkQueue`（126 → 579）与 `mainRef`（126 → 256）
也一起变深——真实洪泛本来就是三者同时变深，所以这一行量的是"池深"而非"单一队列深度"的标度。

**仍然低估了上限**：`chain.orphan.chunkLimit` 是 60000（规格 §2），本负载只有 579 个分片块可用来灌 `linkQueue`。
按实测斜率（≈ 12 ns / 元素）外推，队列堆到 60000 时单次移除将是**百微秒量级**——但这是外推，不是实测。

## 5. 每次重复都从同一个池状态开始（并且有断言证明）

`bestOf3(Runnable)` 对"会留下痕迹"的 body 是不诚实的：`addOrphan` 第二、三遍会全部命中幂等护栏
（`orphanSource.get(key) != null` 跳过写、`contains()` 命中后不 offer），`deleteFromQueue` 第二、三遍会在空队列上遍历
——两行都会量到错的东西，而 `min` 恰好会挑中最便宜的那一遍。

为此加了 `bestOf3(restore, body)` 重载：每次计时前跑一遍**未计时**的恢复。

- `orphan.add` 的恢复 = 对每个条目 `deleteFromQueue` + `deleteByKey` 再 `flushSync()`（内存与磁盘都清空）。
- `orphan.remove` / `removeWorst` 的恢复 = 先清空、再全量 `addOrphan`、再 `flushSync()`；
  `removeWorst` 另加一次 `getOrphan(isMain = true)` 播种 `mainRef`。

**六处状态断言**把"每一趟做的是同样的活"变成了构建门禁，而不是读者的信任：
计时前池必须为空（add）或满（remove），计时后必须为满（add）或空（remove），
`ORPHAN_SIZE` 落盘计数器同样检查（`getOrphanSize()` 只数内存，而 `addOrphan` 的幂等护栏在**数据库**上，
只查内存会漏掉"写被跳过"这一种退化）。`removeWorst` 另外断言形态本身：
分片块数 == `linkQueue` 深度、账户桶恰好 `WORST_SENDERS` 个、`mainRef` 播种量 == `getOrphan` 实际交出的数量。

**变异测试证明这些断言不是摆设**：把恢复改成少清 1 条（20000 中的 1 条），构建失败并给出
`the restore left entries in the pool before a timed add pass expected:<0> but was:<1>`。已还原。

`mainRef` 的断言刻意**不写死数量**：`selectBlocks` 在 `isMain` 时会先走一段上限 6 条的 VIP 分支再取
`linkQueue`，而 SP0b-3 要改的正是这个取用顺序（规格 §3 第 168 行要删掉 `:462` 的无条件提前返回）。
写死数量会让 Task 3 因为错误的理由而失败，所以断言对的是 `getOrphan` 实际返回的条数。

## 6. 其它前提

- **这些行不是 `direct` 路径的回放。** 夹具从不 `kernel.setPow(...)`，`kernel.getPow() == null`，
  所以 `BlockchainImpl.dealOrphan` 在本文件**每一个其他行里都是空操作**——`direct` / `syncPath` /
  `pipeline` / `confirmed` 的数字里**完全不含孤块池成本**。换句话说：**SP0b-2 的 7157 blocks/s 里，
  孤块池一分钱都没算进去。** 这几行是"一个真在挖矿的节点会额外付出多少"的估计。
- **没有持有 blockchain 监视器。** 生产路径上 `addOrphan` / `deleteFromQueue` 都在 `synchronized` 的
  `tryToConnect` 里跑；本行直接调用，省掉一次无竞争的可重入锁往返（~20 ns 量级，可忽略）。
  夹具里没有并发写者（`MockBlockchain.startCheckMain` 是空的，没有 pow，
  `OrphanBlockStoreImpl.start()` 从未被调用所以清理线程没起来），不加锁不影响确定性。
- **`orphan.add` / `orphan.remove` 全部走 `accountTxMap`。** 20000 个付费块每个都带 `XDAG_FIELD_INPUT`，
  `isAccountTx` 恒真；这两行里 `mtxQueue`、`linkQueue`、`mainRef` 始终为空，分片块也不在其中。
  分片块与 `linkQueue` / `mainRef` 只出现在 `removeWorst.d*` 里。

## 7. 本机当前比 SP0b-2 基线那次慢，跨文档比较会凭空造出改进或掩盖回归

两次运行期间本机有别的负载（`codegraph` 索引进程稳定占满一个核，另有 IDEA / Android Studio /
若干 Electron 应用；运行 A 起始 15 分钟负载均值 18.46）。结果是全表比 2026-09-19 那次慢约 20–25%：

| | 本文（2026-09-22） | `2026-09-19-l1-import-pipeline.md` |
|---|---|---|
| `direct` 最好一轮 | 3667 blocks/s | 4698 blocks/s |
| `pipeline` 中位 | 5267 blocks/s | 7157 blocks/s |
| `phase.persist` | 41.4 µs | 22.1 µs |

**因此：SP0b-3 的 AFTER 必须对着本文这张表比，绝不能对着 `2026-09-19-l1-import-pipeline.md` 比。**
跨机器／跨状态比较会凭空造出一个改进，或掩盖一次回归。跨运行只有同一次运行内部的比值可用；
本文 `orphan.*` 与同表的 `parse` / `signature` / `persist` / `direct` 是同一次运行同一时段测的，比值可用。
AFTER 必须**重跑整张表**（A 与 B 两条命令都跑），不能只跑几行去对本文的绝对微秒数。
