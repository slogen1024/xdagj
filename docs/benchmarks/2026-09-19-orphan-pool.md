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

---

# 孤块池 add/remove AFTER（SP0b-3 十五个任务 + 2026-09-23 追加修复之后）

**本次测量在 `75e5a5c3`**，工作树干净（只有未跟踪的 `.codex/`）。
全量套件 **690 tests / 0 failures / 0 errors / 1 skipped**（skip 的正是本基准类自己的
`@BeforeClass` `assumeTrue` 门），`mvn -o -q license:check` 通过。

本节与上面的 BEFORE 并列，**不替换它**。所有对比一律对着上面那张表，
不对 `docs/benchmarks/2026-09-19-l1-import-pipeline.md`（理由见 BEFORE §7）。

## A0. 先说结论

| 行 | BEFORE（运行 B，2000 块） | AFTER（运行 B，2000 块） | 判读 |
|---|---|---|---|
| `phase.orphan.add` | 30.9 µs | **26.9 µs** | 没有退步（−13%） |
| `phase.orphan.remove` | 3.0 µs | **1.2 µs** | 没有退步（−60%）；**这仍然是最好情况**，什么也不证明 |
| `phase.orphan.removeWorst.d125` | 5.4 µs | **12.0 µs** | **变贵了（+122%），原因未查明**，见 §A7 |
| `phase.orphan.removeWorst.d500` | 10.0 µs | **8.2 µs** | 变便宜了（−18%） |

**斜率**：BEFORE 桶深 ×4 → 单次移除 ×1.85（5.4 → 10.0）。AFTER 桶深 ×4 → 单次移除 **×0.68**（12.0 → 8.2）。
**规格 §7.2「移除复杂度」这条判据：达成**——但**证据是 Task 15 的确定性探针
（`OrphanRemovalComplexityTest`：池 ×100 → 每次移除只多 14 次探测），不是这两行墙钟**；
墙钟这一侧只能说"拟合不出正的线性项，且绝对量级可负担"。完整论证与它的边界见 §A6。

**还有一件必须先读的事：运行 A（20000 块）现在跑不完了**，原因是 SP0b-3 自己的类别上限，
详见 §A1。本节的 AFTER 表因此只有 2000 块那一次。

BEFORE 运行 A 的 `orphan.add` = 38.2 µs 与 `orphan.remove` = 2.9 µs（20000 块）**没有 AFTER 对应物**，
因为运行 A 现在跑不完（§A1）。BEFORE §2 已实测这两行与池深无关（深度 ×10 代价不变），
所以 2000 块这一对承载的是同一个信息。

## A1. 运行 A（20000 块）跑不完了，而且原因是 SP0b-3 自己

命令按原样跑，23 分 04 秒后 **BUILD FAILURE**：

```
java.lang.AssertionError: a timed add pass did not fill the pool expected:<20000> but was:<3750>
  at io.xdag.chain.bench.ChainL1ImportBenchmarkTest.lambda$baseline$10(ChainL1ImportBenchmarkTest.java:727)
```

（行号是原样抄下来的栈，会漂；它指的是 `baseline` 里 `orphan.add` 那一行在**第二趟计时之前**
检查"上一趟是否把池填满"的那条断言，`addPass` 守卫的那两句。）

**3750 就是 `ChainSpec.DEFAULT_ORPHAN_ACCOUNT_TX_LIMIT`（`chain.orphan.accountTxLimit`）。**
基准的 20000 个付费块每一个都是账户交易（BEFORE §6），于是第 3751 个开始被池子拒收。

**这不是回归，是本子项目做的事在基准上现形。** 改造前 3750 这个数只在导入路径上被检查一次
（`BlockchainImpl` 里 `isAccountTx(block) && getOrphanSize() >= MAX_ORPHAN_SIZE`），
而基准**直接调 `pool.addOrphan`**，绕过了那道门，所以 20000 条全进得去。SP0b-3 把上限按类别
搬进了池子本身：`ChainOrphanPool` 的准入在类别满时返回非 ADMITTED，`addOrphan` 随即跳过写入。
导入路径仍然先问一次类别是否已满，池子的拒收是**赛跑余量的兜底**（见 `BlockchainImpl.tryToConnect`
准入段的注释与 `OrphanBlockStoreImpl.addOrphan` 的拒收分支）。所以这正是"三层配额各自生效"。

**没有动那条断言，也没有给夹具加 pow。** 计划 Task 16 与 BEFORE §7 都写明了理由：
断言失败是真发现，夹具一旦加 pow 全部历史基线就失去可比性。

**后果与待办（本子项目不修，明确交出去）**：

- 本文的 AFTER 表只能取 2000 块那一次。20000 块那一列在本 HEAD 上**拿不到**，
  因为夹具"每个付费块一个池内条目"的前提已经被生产行为作废。
- `ChainL1ImportBenchmarkTest` 在**默认块数下现在是红的**。追加修复期只重跑过
  `-Dxdag.bench.blocks=2000`（2000 < 3750，碰不到上限），所以没人撞到。
- 修法属于夹具而不属于断言：要么把负载压在类别上限之下，要么让 `orphan.add` 这一行
  按池子实际接纳的条数计数。两者都会让 20000 块这一列与 BEFORE 不再等价，**要在做之前想清楚**。

## A2. 环境与两次运行

同一台机器：Apple M1 Pro（`MacBookPro18,3`），8 核，16 GiB，macOS 15.7.4（Darwin 24.6.0），
Temurin 21.0.12+8-LTS。`TransactionHistoryStore` = `null`；`writeBehind=true`；`ingestThreads=8`；seed=20260917。

| | 运行 A | 运行 B |
|---|---|---|
| 时间 | 2026-09-24 08:19:12–08:42:26（23 min 04 s） | 2026-09-24 08:44:22–08:47:03（2 min 39 s） |
| `blocks` | 20000 | 2000（chunks 579） |
| 结果 | **BUILD FAILURE**（§A1） | BUILD SUCCESS |
| 原始输出 | 无（表在测试末尾才打印，失败时一行都没产出） | `target/bench/l1-import-20260924-084703.json` |

**本机当前比 BEFORE 那一晚空闲得多，这一点必须记下来。** BEFORE 记录的是"运行 A 起始 15 分钟
负载均值 18.46"，且 `codegraph` 索引进程稳定占满一个核。本次：

- 运行 A 窗口内每分钟采样 24 次，1 分钟负载 **2.40–5.04**，15 分钟负载 **3.44–3.97**；
  运行 B 窗口 1 分钟负载 **2.06–4.05**，15 分钟负载 **3.23–3.36**。
- `codegraph` 进程在跑但空闲（0.0% CPU），与 BEFORE 那次占满一个核不同。
- 同时在跑：AiCoin（Electron，12 个 helper，渲染与 GPU 进程各占 20–30% CPU）、IntelliJ IDEA、
  Telegram、Slack、EasyConnect、Cloudflare WARP。**没有 Android Studio**。

**所以不能把整表的变化记到 SP0b-3 头上。** §A4 用同表的标定行把"机器状态买了多少"量出来。

## A3. 运行 B 完整表（2000 块）

```
L1 import benchmark: senders=64 blocks=2000 chunks=579 mix=[60, 25, 10, 5] kinds=[PLAIN 1228, CALL_INLINE 490, CALL_CHAIN 193, DEPLOY 89] seed=20260917 confirmedMainsPerRound=201 txHistoryStore=null writeBehind=true ingestThreads=8
```

| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |
|---|---|---|---|---|---|---|
| direct.r0 | 2000 | 3236 | 346.1 | 319.1 | 430.8 | 797 |
| syncPath.r0 | 2000 | 1955 | 586.4 | 497.5 | 1133.9 | 1319 |
| syncPath.r1 | 2000 | 3041 | 375.8 | 337.6 | 535.9 | 848 |
| direct.r1 | 2000 | 3798 | 302.4 | 290.0 | 329.0 | 679 |
| direct.r2 | 2000 | 3872 | 296.8 | 286.9 | 321.8 | 666 |
| syncPath.r2 | 2000 | 3367 | 341.6 | 320.9 | 470.3 | 766 |
| pipeline.r0 | 2000 | 5668 | 178.8 | 157.0 | 270.1 | 455 |
| pipeline.r1 | 2000 | 5631 | 183.3 | 155.0 | 264.0 | 458 |
| pipeline.r2 | 2000 | 5263 | 192.7 | 156.3 | 290.7 | 490 |
| confirmed.r0 | 201 | 75 | 167219.2 | 110052.2 | 471363.8 | 34300 |
| confirmed.r1 | 201 | 76 | 166427.2 | 108842.1 | 490114.7 | 34135 |
| confirmed.r2 | 201 | 76 | 166187.3 | 109998.3 | 470452.7 | 34089 |
| calib.emptyMain | 100 | 8 | 124583.5 | 81216.8 | 383322.3 | 12458 |
| phase.parse | 2000 | 51025 | 19.6 | - | - | 39 |
| phase.signature | 2000 | 7780 | 128.5 | - | - | 257 |
| phase.refLookup | 193 | 227987 | 4.4 | - | - | 0 |
| **phase.orphan.add** | **2000** | **37210** | **26.9** | - | - | **53** |
| **phase.orphan.remove** | **2000** | **809539** | **1.2** | - | - | **2** |
| **phase.orphan.removeWorst.d125** | **626** | **83628** | **12.0** | - | - | **7** |
| **phase.orphan.removeWorst.d500** | **2579** | **122060** | **8.2** | - | - | **21** |
| phase.persist.first | 2000 | 40519 | 24.7 | - | - | 49 |
| phase.persist | 2000 | 44803 | 22.3 | - | - | 44 |

| round | order | syncPath-direct mean µs | p50 µs | total ms |
|---|---|---|---|---|
| r0 | direct>syncPath | +240.4 | +178.4 | +522 |
| r1 | syncPath>direct | +73.4 | +47.7 | +169 |
| r2 | direct>syncPath | +44.8 | +34.0 | +100 |
| median | | +73.4 | +47.7 | +169 |

```
acceptance: pipeline median = 5631 blocks/s over 3 rounds, target 10000 -> NOT MET
```

**夹具的形态断言全部通过**——BEFORE §5 列的那六处状态断言（计时前池空/池满、计时后池满/池空、
两处 `ORPHAN_SIZE` 落盘计数），加上 `removeWorst` 自己那几条形态断言（分片块数 == LINK 集合深度、
账户桶恰好 `WORST_SENDERS` 个、`mainRef` 播种量 == `getOrphan` 实际交出的条数且不低于下界）。
2000 块碰不到 §A1 那道类别上限，所以 add/remove 两行的"一块一条目"前提在这里仍然成立。
`removeWorst` 两行的 `n`（626 / 2579）与 BEFORE 逐字相同，说明被测形态没有变，变的只是代价。

### A3.1 复现性：还有第二次独立运行，结论一致

`target/bench/l1-import-20260923-233737.json`（2026-09-23 23:37，追加修复 Task 5 的那次重跑）
是一次**独立的 2000 块运行**，树落在 `7fc56d4a`（23:23）与 `f585cfb8`（23:45）之间。
其后只有两个提交：`f585cfb8` 不碰 `src/main`，它对基准超类 `ChainL1TestBase` 的唯一改动是
把 `new MockBlockchain(kernel)` 提成可覆写的 `newBlockchain(Kernel)` 接缝，默认行为逐字不变；
`75e5a5c3` 对 `src/main` 的改动**只有注释**（已逐行核对）。
所以两次运行的生产代码与夹具行为实质相同，而机器状态、时段都不同：

| 行 | 09-23 23:37 | 09-24 08:47 | 差 |
|---|---|---|---|
| `orphan.add` | 25.344 µs | 26.874 µs | +6% |
| `orphan.remove` | 1.121 µs | 1.235 µs | +10% |
| `removeWorst.d125` | 12.070 µs | 11.958 µs | −1% |
| `removeWorst.d500` | 8.220 µs | 8.193 µs | −0.3% |

**两个 `removeWorst` 行在 1% 以内复现。** §A7 那个"变贵了"不是一次抖动。

## A4. 机器状态买了多少：用同表的标定行量出来

BEFORE §7 说得对——跨运行只有同一次运行内部的比值可用。但本次与 BEFORE 运行 B
**是同一个块数、同一个形态、同一台机器**，所以可以先看那些 SP0b-3 一行代码都没碰过的行
到底动了多少，再决定 `orphan.*` 的差值能不能当真：

| 行 | BEFORE 运行 B | AFTER 运行 B | 差 | SP0b-3 碰过吗 |
|---|---|---|---|---|
| `phase.parse` | 19.5 µs | 19.6 µs | +0.5% | 没有 |
| `phase.signature` | 128.5 µs | 128.5 µs | **0%** | 没有 |
| `phase.refLookup` | 4.7 µs | 4.4 µs | −6% | 没有 |
| `phase.persist` | 24.7 µs | 22.3 µs | −10% | 没有 |
| `phase.persist.first` | 28.0 µs | 24.7 µs | −12% | 没有 |
| `calib.emptyMain` | 127723 µs | 124584 µs | −2.5% | 没有 |
| `pipeline` 中位 | 5148 blocks/s | 5631 blocks/s | **+9.4%** | 没有（§A5） |

**`phase.signature` 逐字不变、`phase.parse` 只差 0.5%。** 这两行是纯 CPU、单线程、不碰存储的解析与验签，
机器空闲 5 倍对它们几乎什么也没买。所以**同为单线程纯内存的 `orphan.remove` / `removeWorst` 两行的差值
可以当真**——它们不是被机器状态推动的。

反过来，`persist`（−10%）、`pipeline`（+9.4%）这些要么走 write-behind 队列、要么开 8 个 ingest 线程，
**它们的改善大概率是买来的，不是改出来的**，不要记到 SP0b-3 头上。`orphan.add` 里有一次
ADDRESS 同步读加四次 ORPHANIND 队列往返（BEFORE §1），所以它的 −13% **至少有一部分**
和 `persist` 的 −10% 是同一件事，不能整份算作队列逻辑变快。

## A5. 四行分开讲

### `phase.orphan.add`：30.9 → 26.9 µs，没有退步

BEFORE §1 已经拆过：这一行的大头不是队列逻辑，是存储（1 次 ADDRESS 同步读 + 2 读 2 写
ORPHANIND 经 write-behind）。SP0b-3 在这条路径上**加了活**——按类别的准入判定、三层配额记账
（`chain.orphan.accountTxLimit` / `chunkPerPeer` / `chunkPerChain`）、hashlow 索引维护——
而这一行没有变慢。考虑到 §A4 里 `persist` 同期 −10%，**诚实的说法是"新增的配额记账没有在这一行上留下可见代价"，
而不是"入池变快了 13%"**。

### `phase.orphan.remove`：3.0 → 1.2 µs，没有退步——**并且它仍然什么也不证明**

**这一行是最好情况，BEFORE 与 AFTER 都是，但理由已经不是同一个，别照抄 BEFORE §3。**

- BEFORE 的理由是排序：基准按负载顺序移除，对每个 sender 就是 nonce 升序，恰好是比较器的堆序，
  于是 `contains()` / `remove()` 每次命中下标 0，走 O(log n) 下沉而不是 O(n) 扫描。
- AFTER 的移除按 hashlow 走索引，**移除顺序已经不影响代价**，那条理由随之失效。
  它现在是最好情况是因为**形态**：这一行的 `mainRef` 与 LINK 集合自始至终是空的、
  账户桶只有 ~31 深、64 个 sender 摊开（BEFORE §6 的前提在 AFTER 依然成立，已复核）。

所以 3.0 → 1.2 仍然只是"没有退步"的证据，**不是"线性移除问题被解决"的证据**——
无论它快多少，这一行对洪泛一无所知。后面两行才是依据。

### `phase.orphan.removeWorst.d125`：5.4 → 12.0 µs，**变贵了，原因未查明**

见 §A7。它是本文唯一一个**动了而归因不出来**的数。

### `phase.orphan.removeWorst.d500`：10.0 → 8.2 µs

这是四行里**唯一一个 SP0b-3 打算移动的数**，它按预期方向动了：更深的池、更贵的 BEFORE、
更便宜的 AFTER。但**它单独一个数不是结论**，结论是它与 d125 之间的斜率（§A6）。

## A6. 验收判据：单次移除的代价不随池内条目数线性上升

（规格 §7.2「移除复杂度」那一行；下面按它判。）

| | 桶深 125 | 桶深 500 | 深度 ×4 → 代价 × |
|---|---|---|---|
| BEFORE | 5.4 µs | 10.0 µs | **×1.85** |
| AFTER | 12.0 µs | 8.2 µs | **×0.68** |

**判定：达成。但要说清楚证据是谁给的——是确定性探针，不是这两行墙钟。**

**墙钟这一侧能说的话，以及说不了的话。**

BEFORE 那条斜率可以拆成"固定 ≈ 3.9 µs + 线性 ≈ 12 ns/元素"，**正号**，
两个深度上的实测与这个两参数模型自洽，拆得出来。

AFTER **拆不出正的线性项**。一个"每元素代价随池深单调上升"的模型必须预测 d500 ≥ d125，
因为**一次移除里每一个与深度有关的分量在 d500 上都不比 d125 浅**：

- 账户桶 125 → 500（`ChainOrphanPool` 的 per-address `TreeSet`）；
- LINK 集合 126 → 579；
- `mainRef` 的播种预算两边都是 256（`OrphanBlockStoreImpl.getOrphanLocked` 的
  `addNum = min(selectableSize + mainRefSize, num)`，`num` = 夹具的 `MAIN_REF_DEPTH` = 256，
  而两个深度的池都装着 ≥ 256 条），**这一项两边相等**。

而实测 d500 < d125。

**但这不足以单独结案，必须把话说全**：§A7 那个未查明的量如果真是"每趟固定开销"，
它按 1/n 摊到两行上（d125 摊 626、d500 摊 2579），**会把实测斜率系统性地压低**。
也就是说 ×0.68 是**被低估过的**斜率，真实斜率的上界这套仪器给不出来。
拿它当"线性项已消失"的证明是不成立的。

**真正钉住这条判据的是 Task 15 的确定性探针**——这也正是那个任务不用墙钟的理由
（"墙钟答不了这个分辨率的问题"，见 `OrphanRemovalComplexityTest` 的类文档）：
池 1000 → 149 次探测，池 100000 → 163 次（**池子 ×100，每次移除只多 14 次探测**）；
把线性走查放回去，同一对测量变成 254 → 11927（**多 11673 次**）。
那是复杂度判据的正式证据。**墙钟这两行的作用只有一个：与它不矛盾，且在绝对量级上可负担。**

那 149 次探测里还有一个与 §A7 相关的分解，探针自己记着：**128 次是 `mainRef` 那条定长队列，
只有约 21 次是树**。所以"随池深上升"的那一项确实没了，而**与 `mainRef` 长度成正比的那一段还在**
——它由出块节奏而不是洪泛决定，探针刻意把 `mainRef` 在两次测量里保持等长，
由同一个类里的 `whatIsStillLinearIsTheHandedOutDequeAndOnlyItsOwnLength` 单独钉住。

**顺带一句**：两个深度上的绝对代价（8.2 与 12.0 µs）都远小于一次锁内提交——
**用本表自己的数**：AFTER 运行 B 的 `pipeline.r1` 每块 183.3 µs（该行的 mean 就是锁内提交，
口径见 `2026-09-19-l1-import-pipeline.md` §"拆 140.9 µs"，那里的绝对值属于另一台机器状态，不引来比较）。
本子项目的验收是"公平与可负担"，这两个数落在可负担这一侧。

## A7. 动了但归因不出来：`removeWorst.d125` 5.4 → 12.0 µs

**先说清楚它不是什么。** 不是抖动（§A3.1，两次独立运行差 1%）；不是机器状态
（§A4，同为单线程纯内存的标定行 `phase.signature` 逐字不变）；不是形态变了
（`n` 仍是 626，形态断言全绿）。

**排除掉的两条具体猜想，都是查过代码之后排除的：**

1. **不是 `mainRef` 变深。** 两个深度的播种预算都是 256（§A6 的算式），所以
   `mainRef` 这一项在 d125 与 d500 上是同一个量。
2. **不是 `mainRef` 的比较变贵了。** AFTER 的 `ChainOrphanPool.mainRefFind` 逐元素比的是
   `Bytes32.equals`，不分配；BEFORE 那次单趟 `ConcurrentLinkedDeque.remove(Object)`
   逐元素走的是 `OrphanMeta.equals`，而它每次比较都要 `hashlow.toArray()` **两次**
   （见 `OrphanMeta.equals` 至今仍是 `Arrays.equals(hashlow.toArray(), m.hashlow.toArray())`）。
   新扫描是两者里便宜的那个，不是贵的那个。
   （附带记一笔，不是本条的成因：`mainRefRemove` 在找到之后还要再 `mainRef.remove(found)`
   走第二趟，而它自己的 javadoc 写的是"这趟扫描和老的 `ConcurrentLinkedDeque.remove(Object)` 一样"——
   命中时其实是两趟。这是一处**廉价的可改项**，不是这次的解释：未命中时只走一趟，
   而 d500 的未命中比例比 d125 高得多，双趟更解释不了 d125 比 d500 贵。）

**唯一与数据自洽的形状是"每趟固定开销"，而不是"每元素开销"。** 按实测反解：
若每次移除的真实代价 c 两边都是约 8.0 µs，则 d125 每趟多出
7000 − 626×8.0 ≈ **2 ms**，d500 每趟多出 21000 − 2579×8.0 ≈ **0.4 ms**。
`bestOf3` 取三趟最小值，`d125` 每趟只有 626 次移除、7 ms，**一次 JIT 去优化/重编译或一次 GC
就能占掉它近三成**；`d500` 的 2579 次移除、21 ms 把同样的固定开销摊薄到约 2%。
d125 又恰好是**第一个**进入非空 `mainRef` 与非空 LINK 集合的计时行——它前面的
`orphan.remove` 整行跑在 `mainRef` 与 `linkQueue` 皆空的形状上。

**但这是一个说得通的形状，不是一次测量。** 本次没有跑 `-XX:+PrintCompilation`，
也没有隔离 GC，所以**本文不把它记为成因**。据实记录：
`removeWorst.d125` 在 AFTER 上稳定地贵了 2.2 倍，**原因未查明**。

**它对 §A6 的影响已经写在那里，不要绕过**：若它确是每趟固定开销，
它会把实测斜率压低，于是墙钟这一侧的 ×0.68 不能单独证明线性项消失——
那条判据的证据是确定性探针。反过来，若它是每元素开销，
那它必须同时解释"每一个与深度有关的分量在 d500 上都不更浅、而 d500 却更便宜"，
本文没有找到这样的解释。两种可能都不改变判定，因为判定不靠这两行。

想往下查的人，最小的下一步是：给 `WORST_DEPTHS` 加第三个点（比如 250），
看三点是否共线——若 d125 是固定开销被 626 次摊薄的结果，d250 应当落在 d125 与 d500 之间偏 d500 一侧，
而不是落在一条正斜率直线上。**那要改夹具，超出 Task 16 的范围。**

## A8. 没有下降的东西，以及为什么它本来就不会下降

**锁内时间没有下降。** `confirmed` 中位 167225 → 166427 µs（**−0.5%**），噪声之内；
`calib.emptyMain` 127723 → 124584（−2.5%）。**SP0b-3 的验收是公平性与可负担性，不是吞吐**；
吞吐目标属于 SP0b-2b，本文不拿这两行邀功，也不拿它们认错。

**`pipeline.rN` 改善了 9.4%（5148 → 5631 blocks/s），但它不是验收信号，一行都不是。**
理由是 BEFORE §6 那条，AFTER 依然成立并已复核：夹具从不 `kernel.setPow(...)`，
`kernel.getPow() == null`，而 `BlockchainImpl.dealOrphan` 对非 CHUNK 类别仍然被
`getEnableGenerateBlock() && getPow() != null` 把关——**所以 `direct` / `syncPath` /
`pipeline` / `confirmed` 每一个数字里孤块池成本都是零**。9.4% 是 §A4 说的那件事买来的。

两个推论照旧成立，值得在 AFTER 这一侧再说一遍：

1. **路线图 §5.2.1 把孤块池增删列为锁内 ≥84% 的头号嫌疑，这个前提是错的**——
   产生那个 84% 的基准里孤块池根本没运行过。
2. **真实挖矿节点上它会运行**：`addOrphan` 单独 26.9 µs，对照本表自己的锁内提交
   （AFTER 运行 B `pipeline.r1` mean = 183.3 µs/块），**基准低估了真实节点的锁内开销**，不是高估。
   （计划 Task 16 引的是 140.9 µs，那个数出自 `2026-09-19-l1-import-pipeline.md`，
   属于更快的机器状态，此处只用本表自己的数。）

**不要为此给夹具加 pow。** 那会让全部历史基线失去可比性，而可比性是这套基准唯一的价值。

## A9. AFTER 与 BEFORE 之间还发生了什么：归因边界

AFTER = SP0b-3 的十五个任务 **加上** Task 15 之后插入的五任务追加修复
（`docs/superpowers/specs/2026-09-23-sp0b3-chunk-pooling-without-mining-design.md`）。
**不要把整份差值记到孤块池改造头上。** 三条边界，都核实过：

- **Task 15 的复杂度探针是纯测试件，不扰动本表任何一行。** 它是
  `OrphanRemovalComplexityTest` 里的 `CountingMeta extends OrphanMeta` 子类，
  该任务对 `src/main` 的 `git diff --stat` 是空的。
- **追加修复的出块改动（D4：无可引用就不建块）动不了本表任何一行。**
  基准**从不到达 `checkOrphan`**：`ChainL1TestBase.MockBlockchain.startCheckMain` 是空实现，
  夹具也不设 pow。**所以如果本表某一行动了，D4 不是原因。**
- **追加修复的 D5 确实改了本表会走到的生产代码**——`ChainOrphanPool.selectableSize()`
  与 `OrphanBlockStoreImpl.getOrphanLocked` 的预算——**但在本夹具里它是数值上的空操作**：
  基准用的是五参 `addOrphan` 重载，`classified` 传 `null`，于是
  `OrphanCategory.of(isTx, address, kind = null)` 对非交易块恒为 `LINK`，
  **池中 CHUNK 计数恒为 0，`selectableSize() == totalSize()`**。
  它**可能**影响的是 `removeWorst` 恢复段里那次 `getOrphan(isMain = true)` 的播种预算；
  在这里 `min(total + 0, 256)` 与 `min(selectable + 0, 256)` 是同一个数，所以没有影响。
  它**不可能**影响计时窗口本身：窗口里只有 `deleteFromQueue`，不经过任何预算。
  顺带：本夹具因此也**没有**覆盖 CHUNK 类别的准入与配额路径——那是功能测试的事，不是本表的事。

## A10. Task 15 Step 3b 的那笔账在别处结清

Task 15 量的"分片洪泛下 `checkOrphan` 空转"以及追加修复后的复测，
**BEFORE 与 AFTER 都记在计划的 Task 15 Step 3b 小节里**
（`docs/superpowers/plans/2026-09-19-xdag-chain-sp0b3-orphan-pool.md`）。
本文只引用，**不把那些 tick 计数搬进上面的表**：那是另一套仪器
（`ChunkFloodAdversarialTest`）在另一套夹具上的测量，与 `phase.orphan.*` 和 blocks/s 不同量纲，
并排会造成"同一张表里的数可以互相比"的错觉。
