# SP0b-3 孤块池实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让垃圾分片洪泛无法饿死账户交易，让孤块移除的代价不随池子变大而线性上升，且不引入共识分歧。

**Architecture:** 新增纯内存的 `ChainOrphanPool`（分类有序集合 + hashlow 索引 + 三层配额 + 双制 TTL + 未落盘分片块体），`OrphanBlockStoreImpl` 退为 ORPHANIND 持久化与启动重建的外壳并把内存决策全部委托给它。分片块改为内存独有（不进块存储、不进 ORPHANIND），由两个纪元的 TTL 兜底。导入期费率策略作为一道闸门挡在两条摄入路径之前，被本节点请求回来的块一律豁免。

**Tech Stack:** Java 21、JUnit 4、RocksDB（仅 ORPHANIND 外壳）、既有 `ChunkChain` / `ChainBlockClassifier` / `IngestPipeline`。

**上位文档：** 规格 `docs/superpowers/specs/2026-09-19-xdag-chain-sp0b3-orphan-pool-design.md`。与规格的一处细化见下。

---

## 构建陷阱（Task 7 实测，对所有后续任务有效）

**Maven 的增量测试编译会对着陈旧的 class 文件报成功。** Task 7 删掉了几个 getter，而 `mvn -o -q test-compile` 仍然通过——直到 touch 了引用它们的测试文件，错误才暴露出来。

所以：**删除或改签名之后，不要拿 `test-compile` 的通过当证据。** 跑真正的测试（surefire 会强制重编需要的部分），或者先 touch 受影响的文件。这条在每个会改公共签名的任务里都适用。

---

## 规格细化（实施期决定）

规格 §4.3 只说分片块延迟落盘，未说 ORPHANIND 的去留。本计划确定：**分片块在块存储与 ORPHANIND 两处都不写，只在内存**。

理由三条。其一，若仍写 ORPHANIND，重启后行指向已不存在的块体。其二，重建时无法从 34 字节键 / 36 字节值里分辨分片块与其它 link 块，除非改行格式。其三，TTL 只有两个纪元（128 秒），重启丢失无害，需要时走既有拉取路径。

推论：从 ORPHANIND 重建出来的条目按构造全是非分片块，类别判定不需要新增字节，行格式不变。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `src/main/java/io/xdag/chain/orphan/OrphanCategory.java` | 新建。四类枚举 + 由 `isTx`/地址/`ExtKind` 归类的静态方法 |
| `src/main/java/io/xdag/chain/orphan/OrphanEntry.java` | 新建。`OrphanMeta` + 类别 + 来源 peer + 分片链头 + 块体（仅分片块） |
| `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java` | 新建。分类有序集合、hashlow 索引、三层配额、双制 TTL、分片块体存储 |
| `src/main/java/io/xdag/chain/orphan/OrphanAdmission.java` | 新建。准入结果枚举（`ADMITTED` / `CATEGORY_FULL` / `PEER_FULL` / `CHAIN_FULL` / `POOL_FULL` / `DUPLICATE`） |
| `src/main/java/io/xdag/chain/ingest/ChunkFeePolicy.java` | 新建。摄入边界的费率闸门 |
| `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java` | 改。内存集合全部移走，保留 ORPHANIND 与重建，委托给池 |
| `src/main/java/io/xdag/core/BlockchainImpl.java` | 改。准入闸门按类别、peer/kind 管线、分片块跳过落盘、`getBlockByHash` 合并查找 |
| `src/main/java/io/xdag/core/ImportResult.java` | 改。新增 `CHAIN_FEE_POLICY` |
| `src/main/java/io/xdag/consensus/SyncManager.java` | 改。费率闸门接入，被请求块豁免 |
| `src/main/java/io/xdag/config/spec/ChainSpec.java` | 改。九个默认值 |
| `src/main/java/io/xdag/config/AbstractConfig.java` | 改。九个键的解析与校验 |

测试新增 `ChainOrphanPoolTest`、`OrphanQuotaTest`、`OrphanTtlTest`、`OrphanPackingFairnessTest`、`ChunkDeferredPersistTest`、`BlockLookupEquivalenceTest`、`ChunkFeePolicyTest`、`OrphanFloodTest`。

---

### Task 1: 基准 phase 行（硬性前置，先于任何行为改动）

**Files:**
- Modify: `src/test/java/io/xdag/chain/bench/ChainL1ImportBenchmarkTest.java`
- Create: `docs/benchmarks/2026-09-19-orphan-pool.md`

路线图 §5.2.1 第 4 条把孤块池那部分的 `phase.*` 行判给先动手的子项目。现有四行（`parse`/`signature`/`refLookup`/`persist`）都拆不开锁内那 ≥84%。

- [ ] **Step 1: 加两行 phase 测量**

在 `refLookup` 那段之后、`persist` 之前插入。`parsed` 与 `kernel` 在作用域内。

```java
// Orphan-pool add/remove, the two halves SP0b-2b named as a prime suspect for the in-lock
// rest. Measured on the same parsed blocks, against a pool already holding the workload so
// the removal walk is not measured against an empty queue.
OrphanBlockStore pool = kernel.getOrphanBlockStore();
results.add(phaseRow("orphan.add", parsed.size(), bestOf3(() -> {
    for (Block b : parsed) {
        pool.addOrphan(b, blockchain.isAccountTx(b), UInt64.ZERO, XAmount.ZERO, null);
    }
})));
results.add(phaseRow("orphan.remove", parsed.size(), bestOf3(() -> {
    for (Block b : parsed) {
        pool.deleteFromQueue(b, blockchain.isAccountTx(b), UInt64.ZERO, XAmount.ZERO, null);
    }
})));
```

若 `addOrphan`/`deleteFromQueue` 不在 `OrphanBlockStore` 接口上，用 `(OrphanBlockStoreImpl)` 强转并在注释里写明这是基准专用。

- [ ] **Step 2: 跑基准，记录改造前数字**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
mvn -o -Dxdag.bench=true -Dtest='io.xdag.chain.bench.ChainL1ImportBenchmarkTest' test
```

Expected: 输出表里出现 `phase.orphan.add` 与 `phase.orphan.remove` 两行。

- [ ] **Step 3: 写 BEFORE 文档**

`docs/benchmarks/2026-09-19-orphan-pool.md`：机器信息、完整表、以及一句「本次测量在 `1c2e2d94` 之后、任何 SP0b-3 行为改动之前」。

- [ ] **Step 4: 提交**

```bash
git add src/test/java/io/xdag/chain/bench/ChainL1ImportBenchmarkTest.java
git add -f docs/benchmarks/2026-09-19-orphan-pool.md
git commit
```

提交信息说明：这两行是 SP0b-2b 判给本子项目的测量债，数字是改造前基线。

---

### Task 2: 九个配置键与校验

**Files:**
- Modify: `src/main/java/io/xdag/config/spec/ChainSpec.java`
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java`
- Test: `src/test/java/io/xdag/config/ChainOrphanConfigTest.java`

- [ ] **Step 1: 写失败测试**

```java
public class ChainOrphanConfigTest {

    /** 低于 2 会让本节点拒绝其它节点接受的链，是共识分歧，必须启动即失败。 */
    @Test
    public void chunkTtlEpochsBelowTwoIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.orphan.chunkTtlEpochs", "1", DevnetConfig::new));
        assertTrue("the message must name the key and the floor: " + e.getMessage(),
                e.getMessage().contains("chain.orphan.chunkTtlEpochs") && e.getMessage().contains("2"));
    }

    @Test
    public void perPeerQuotaAboveTheChunkLimitIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.orphan.chunkPerPeer", "999999", DevnetConfig::new));
    }

    @Test
    public void defaultsMatchTheSpec() {
        ChainSpec c = new DevnetConfig().getChainSpec();
        assertEquals(100000, c.getChainOrphanPoolLimit());
        assertEquals(3750, c.getChainOrphanAccountTxLimit());
        assertEquals(3750, c.getChainOrphanMtxLimit());
        assertEquals(60000, c.getChainOrphanChunkLimit());
        assertEquals(30000, c.getChainOrphanLinkLimit());
        assertEquals(5000, c.getChainOrphanChunkPerPeer());
        assertEquals(20000, c.getChainOrphanChunkPerChain());
        assertEquals(2, c.getChainOrphanChunkTtlEpochs());
        assertTrue(c.isChainIngestFeePolicy());
    }
}
```

**覆盖机制用既有的 `ChainSpecTest.withProperty`**（`src/test/java/io/xdag/config/ChainSpecTest.java:51`）：设置 JVM 系统属性 → `ConfigFactory.invalidateCaches()` → 跑 body → `finally` 清理并再次失效缓存。`AbstractConfig.getSetting()`（`:329`）走的是 `ConfigFactory.load(getConfigName())`，系统属性会叠加在资源文件之上；而 `getSetting()` 由构造路径（`:180`）调用，所以校验失败会从构造器抛出。测试写成：

```java
assertThrows(IllegalArgumentException.class,
        () -> withProperty("chain.orphan.chunkTtlEpochs", "1", DevnetConfig::new));
```

把 `withProperty` 抽成一个测试工具或在新测试类里复制一份，**不要**去找 `ConfigFactory.parseString`——仓库里没有那种写法。

- [ ] **Step 2: 跑测试确认失败**

Expected: 编译失败，`getChainOrphanPoolLimit` 等方法不存在。

- [ ] **Step 3: 加默认值**

`ChainSpec.java`，接在 `DEFAULT_PERSIST_READ_CACHE` 之后：

```java
/** Node-local defaults for the SP0b-3 orphan pool. */
int DEFAULT_ORPHAN_POOL_LIMIT = 100000;
int DEFAULT_ORPHAN_ACCOUNT_TX_LIMIT = 3750;
int DEFAULT_ORPHAN_MTX_LIMIT = 3750;
int DEFAULT_ORPHAN_CHUNK_LIMIT = 60000;
int DEFAULT_ORPHAN_LINK_LIMIT = 30000;
int DEFAULT_ORPHAN_CHUNK_PER_PEER = 5000;
int DEFAULT_ORPHAN_CHUNK_PER_CHAIN = 20000;

/**
 * Minimum retention for a CHUNK block, in epochs. NOT a free tunable: the chunk age rule
 * accepts a chunk only from the paying block's epoch or the one before it, so two epochs is
 * the floor at which eviction can no longer change a consensus outcome. Below it this node
 * would reject chains other nodes accept.
 */
int DEFAULT_ORPHAN_CHUNK_TTL_EPOCHS = 2;
int MIN_ORPHAN_CHUNK_TTL_EPOCHS = 2;

boolean DEFAULT_INGEST_FEE_POLICY = true;
```

同文件加九个 getter 声明。**九个 getter 都声明在 `ChainSpec` 接口上**，与既有的 `getChainIngestThreads()`（`ChainSpec.java:199`）到 `getChainPersistReadCache()`（`:214`）并列——`AbstractConfig implements ... ChainSpec`，所以测试要走 `config.getChainSpec().getXxx()`，不能直接在 `Config` 上调。

- [ ] **Step 4: 加解析与校验**

`AbstractConfig.java`，字段接在 `chainPersistReadCache` 之后，解析接在 `:458` 之后：

```java
// Node-local (SP0b-3): orphan pool tiers and the ingest fee policy. Never consensus, with one
// exception noted below.
chainOrphanPoolLimit = readNodeLocalInt(config, "chain.orphan.poolLimit", chainOrphanPoolLimit, 1);
chainOrphanAccountTxLimit = readNodeLocalInt(config, "chain.orphan.accountTxLimit", chainOrphanAccountTxLimit, 1);
chainOrphanMtxLimit = readNodeLocalInt(config, "chain.orphan.mtxLimit", chainOrphanMtxLimit, 1);
chainOrphanChunkLimit = readNodeLocalInt(config, "chain.orphan.chunkLimit", chainOrphanChunkLimit, 1);
chainOrphanLinkLimit = readNodeLocalInt(config, "chain.orphan.linkLimit", chainOrphanLinkLimit, 1);
chainOrphanChunkPerPeer = readNodeLocalInt(config, "chain.orphan.chunkPerPeer", chainOrphanChunkPerPeer, 1);
chainOrphanChunkPerChain = readNodeLocalInt(config, "chain.orphan.chunkPerChain", chainOrphanChunkPerChain, 1);
// The one that is not merely node-local: the chunk age rule makes two epochs the point below
// which eviction starts changing consensus outcomes, so a lower value is refused outright.
chainOrphanChunkTtlEpochs = readNodeLocalInt(config, "chain.orphan.chunkTtlEpochs",
        chainOrphanChunkTtlEpochs, ChainSpec.MIN_ORPHAN_CHUNK_TTL_EPOCHS);
chainIngestFeePolicy = config.hasPath("chain.ingest.feePolicy")
        ? config.getBoolean("chain.ingest.feePolicy") : chainIngestFeePolicy;

long categorySum = (long) chainOrphanAccountTxLimit + chainOrphanMtxLimit
        + chainOrphanChunkLimit + chainOrphanLinkLimit;
if (categorySum > chainOrphanPoolLimit) {
    throw new IllegalArgumentException("Invalid chain.orphan.poolLimit: " + chainOrphanPoolLimit
            + " (must be >= the sum of the four category limits: " + categorySum + ")");
}
if (chainOrphanChunkPerPeer > chainOrphanChunkLimit) {
    throw new IllegalArgumentException("Invalid chain.orphan.chunkPerPeer: " + chainOrphanChunkPerPeer
            + " (must be <= chain.orphan.chunkLimit: " + chainOrphanChunkLimit + ")");
}
if (chainOrphanChunkPerChain > chainOrphanChunkLimit) {
    throw new IllegalArgumentException("Invalid chain.orphan.chunkPerChain: " + chainOrphanChunkPerChain
            + " (must be <= chain.orphan.chunkLimit: " + chainOrphanChunkLimit + ")");
}
```

注意 `readNodeLocalInt` 的 `min` 参数正好给了 TTL 下界，错误信息会自带键名与下界，满足测试断言。

- [ ] **Step 5: 跑测试，确认通过；不改任何 `.conf`**

```bash
mvn -o -Dtest='io.xdag.config.*Test' test
grep -rn "chain.orphan\|feePolicy" src/main/resources/*.conf   # 必须无输出
```

- [ ] **Step 6: 提交**

---

### Task 3: `OrphanCategory` 与 `ChainOrphanPool` 骨架

**Files:**
- Create: `src/main/java/io/xdag/chain/orphan/OrphanCategory.java`
- Create: `src/main/java/io/xdag/chain/orphan/OrphanEntry.java`
- Create: `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java`
- Test: `src/test/java/io/xdag/chain/orphan/ChainOrphanPoolTest.java`

本任务只做结构与顺序，不做配额与 TTL，不接线。

- [ ] **Step 1: 写失败测试**

```java
public class ChainOrphanPoolTest {

    /** 排序规则与今天逐字节相同：linkQueue 按时间升序再 hashlow 字典序。 */
    @Test
    public void linkEntriesComeOutByTimeThenHashlow() {
        ChainOrphanPool pool = newPool();
        OrphanEntry a = link(hash(0x02), 100L);
        OrphanEntry b = link(hash(0x01), 100L);   // 同时间，hashlow 更小
        OrphanEntry c = link(hash(0x03), 50L);    // 更早
        pool.add(b); pool.add(a); pool.add(c);
        assertEquals(List.of(c, b, a), pool.peekAll(OrphanCategory.LINK));
    }

    /** 移除按 hashlow 走索引，不依赖队头，也不重建序列化形式。 */
    @Test
    public void removalByHashlowTakesTheRightEntryWhateverItsPosition() {
        ChainOrphanPool pool = newPool();
        OrphanEntry first = link(hash(0x01), 10L);
        OrphanEntry middle = link(hash(0x02), 20L);
        OrphanEntry last = link(hash(0x03), 30L);
        pool.add(first); pool.add(middle); pool.add(last);
        assertSame(middle, pool.remove(middle.hashlow()));
        assertEquals(List.of(first, last), pool.peekAll(OrphanCategory.LINK));
        assertEquals(2, pool.size(OrphanCategory.LINK));
        assertNull("removing twice must not take a different entry", pool.remove(middle.hashlow()));
    }

    /** size() 必须精确：准入闸门读的就是它。 */
    @Test
    public void sizeIsExactAcrossAddAndRemove() {
        ChainOrphanPool pool = newPool();
        for (int i = 1; i <= 100; i++) pool.add(link(hash(i), i));
        assertEquals(100, pool.size(OrphanCategory.LINK));
        for (int i = 1; i <= 50; i++) pool.remove(hash(i));
        assertEquals(50, pool.size(OrphanCategory.LINK));
        assertEquals(50, pool.totalSize());
    }

    @Test
    public void duplicateHashlowIsRejectedNotDoubleCounted() {
        ChainOrphanPool pool = newPool();
        OrphanEntry e = link(hash(0x01), 10L);
        assertEquals(OrphanAdmission.ADMITTED, pool.add(e));
        assertEquals(OrphanAdmission.DUPLICATE, pool.add(link(hash(0x01), 99L)));
        assertEquals(1, pool.size(OrphanCategory.LINK));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Expected: 编译失败，三个类都不存在。

- [ ] **Step 3: 实现三个类**

`OrphanCategory.java`：

```java
public enum OrphanCategory {
    ACCOUNT_TX, MTX, CHUNK, LINK;

    /**
     * The routing decision, in one place. {@code kind} may be null on paths that carry no
     * classification; a null kind can never be CHUNK, so it falls through to LINK exactly as
     * today's {@code addOrphanToMemory} does.
     */
    public static OrphanCategory of(boolean isTx, byte[] address, ExtKind kind) {
        if (isTx) {
            return BytesUtils.isFullZero(address) ? MTX : ACCOUNT_TX;
        }
        return kind == ExtKind.CHUNK ? CHUNK : LINK;
    }
}
```

`OrphanEntry.java`：`OrphanMeta meta`、`OrphanCategory category`、`String peerKey`（null = 无归属）、`Bytes32 chainHead`（仅 CHUNK，null = 无主）、`Block body`（仅 CHUNK，其余 null）。`hashlow()` 委托给 meta。`equals`/`hashCode` 仍以 hashlow 为准，与 `OrphanMeta` 一致。

`ChainOrphanPool.java`：**一开始就按真实形状建，不要先做单集合再由 Task 7 拆**。账户交易在代码里本就是按地址分桶的两层结构，先做单集合等于让 Task 7 推倒重来。

| 类别 | 结构 |
|---|---|
| `LINK` | 一个 `TreeSet<OrphanEntry>` |
| `MTX` | 一个 `TreeSet<OrphanEntry>` |
| `CHUNK` | 一个 `TreeSet<OrphanEntry>`（新增，今天不存在） |
| `ACCOUNT_TX` | `Map<String 十六进制地址, TreeSet<OrphanEntry>>` **两份**：普通桶与 VIP 快车道，对应今天的 `accountTxMap`（`:65`）与 `vipTxMap`（`:69`） |

外加一张 `Map<Bytes, OrphanEntry>` 索引。**索引项必须能定位到持有它的那个集合**（在 `OrphanEntry` 上记类别 + 地址键，或索引直接存集合引用），否则 `remove(Bytes32)` 查到条目后还要遍历四类去找它在哪，移除又退回线性。

VIP 准入判定（`nonce == executedNonce + 1` 且 `fee > averageFee`）与 `accountNonce` 的推进逻辑在 Task 7 接线时搬入，本任务只需把两层桶的容器建好，行为搬迁留到 Task 7。

比较器逐条抄自 `OrphanBlockStoreImpl:56/60/342` 现有定义，一字不改（`linkQueue` 与 `mtxQueue` 在 `:56`/`:60`，每地址桶在 `:342`；`CHUNK` 沿用 `linkQueue` 的时间升序加 hashlow）。`add` 返回 `OrphanAdmission`，`remove(Bytes32)` 返回被移除的条目或 null，`peekAll` 仅供测试。

不加 `synchronized`：池由调用方（区块链监视器）保护，这一点写在类注释里。

**硬陷阱：移除必须摘掉「存进去的那个实例」。** `remove(Bytes32)` 先查索引拿到原始 `OrphanEntry`，再用**那个实例**去 `treeSet.remove(...)`。绝不可以从 hashlow 或调用方参数重新构造一个 entry 再去摘。

原因：`PriorityBlockingQueue.remove(Object)` 走 `equals`，而 `OrphanMeta.equals` 只比 hashlow，所以今天传进来的 fee 与存入时不一致也照删不误；`TreeSet.remove(Object)` 走的却是**比较器**。两侧的值确实会不一致——`dealOrphan` 用的是 `tryToConnect` 收到的块实例，而 `removeOrphan`（`BlockchainImpl.java:2308`）先 `b = getBlockByHash(b.getHashLow(), true)` 重新取块再算 `getTxFee(b)`，链在这之间改过 `info.fee`。而 `mtxQueue` 正是按费用降序排的。重构的 entry 会被比较器排到别处、永远够不到目标，于是静默失败：条目留在集合里，配额名额永远还不回来。

索引里存的就是原始实例，用它去摘保证命中。**Step 5 的变异验证要额外加一条**：把 `remove` 改成「用调用方的 fee 重构 entry 再摘」，在 `mtxQueue` 上构造一个 fee 变过的条目，确认它变红。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: 变异验证**

把 `remove` 改成 `first()` 再移除，确认 `removalByHashlowTakesTheRightEntryWhateverItsPosition` 变红并记下失败文本。恢复。

- [ ] **Step 6: 提交**

---

### Task 4: 类别上限与全局上限

**Files:**
- Modify: `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java`
- Test: `src/test/java/io/xdag/chain/orphan/OrphanQuotaTest.java`

- [ ] **Step 1: 写失败测试**

```java
/** 任一类别打满只拒绝该类别，不影响其它类别——这正是今天活着的饿死。 */
@Test
public void aFullCategoryDoesNotBlockAnother() {
    ChainOrphanPool pool = newPool(limits().chunk(2).accountTx(10).build());
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(1))));
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(2))));
    assertEquals(OrphanAdmission.CATEGORY_FULL, pool.add(chunk(hash(3))));
    assertEquals("a full chunk category must not touch account transactions",
            OrphanAdmission.ADMITTED, pool.add(accountTx(hash(4), addr(1), 1L)));
}

@Test
public void theGlobalLimitBacksStopsEverything() {
    ChainOrphanPool pool = newPool(limits().poolLimit(3).chunk(100).link(100).build());
    pool.add(chunk(hash(1))); pool.add(link(hash(2), 1L)); pool.add(chunk(hash(3)));
    assertEquals(OrphanAdmission.POOL_FULL, pool.add(link(hash(4), 2L)));
}

/** 移除后名额必须真的还回来。 */
@Test
public void removingFreesTheSlot() {
    ChainOrphanPool pool = newPool(limits().chunk(1).build());
    pool.add(chunk(hash(1)));
    assertEquals(OrphanAdmission.CATEGORY_FULL, pool.add(chunk(hash(2))));
    pool.remove(hash(1));
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunk(hash(2))));
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**：`add` 先查全局再查类别，计数在 `add`/`remove` 两侧同步维护，绝不由集合 `size()` 现算（那会把 TTL 淘汰和移除的顺序错误掩盖掉）。
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 变异验证**：把类别检查删掉，确认第一个测试变红。
- [ ] **Step 6: 提交**

---

### Task 5: 按 peer 与按分片链的二级配额

**Files:**
- Modify: `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java`
- Test: `src/test/java/io/xdag/chain/orphan/OrphanQuotaTest.java`（追加）

- [ ] **Step 1: 写失败测试**

```java
@Test
public void onePeerCannotUseTheWholeChunkBudget() {
    ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(2).build());
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(1), head(1))));
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(2), head(1))));
    assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(3), head(1))));
    assertEquals("another peer must be unaffected",
            OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerB", hash(4), head(2))));
}

@Test
public void oneChunkChainCannotUseTheWholeChunkBudget() {
    ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerChain(2).build());
    pool.add(chunkFrom("peerA", hash(1), head(1)));
    pool.add(chunkFrom("peerA", hash(2), head(1)));
    assertEquals(OrphanAdmission.CHAIN_FULL, pool.add(chunkFrom("peerB", hash(3), head(1))));
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerB", hash(4), head(2))));
}

/** 本地产生的块没有来源 peer，不计入任何 peer 配额。 */
@Test
public void locallyOriginatedChunksAreUnattributed() {
    ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).build());
    pool.add(chunkFrom(null, hash(1), head(1)));
    assertEquals("an unattributed chunk must not consume a peer's budget",
            OrphanAdmission.ADMITTED, pool.add(chunkFrom(null, hash(2), head(1))));
}

/** 无法归组到链头的分片块进「无主」桶，仍受全局与 peer 约束。 */
@Test
public void anUngroupedChunkStillCountsAgainstPeerAndGlobal() {
    ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).build());
    pool.add(chunkFrom("peerA", hash(1), null));
    assertEquals(OrphanAdmission.PEER_FULL, pool.add(chunkFrom("peerA", hash(2), null)));
}
```

- [ ] **Step 2: 跑测试确认失败**

Expected: 编译失败，`chunkFrom` 与 `head` 辅助方法以及 peer/链头两层计数都不存在。

**配额键必须用 `Peer.getIp()`，不能用 `getPeerId()`。** 这一条决定该配额有没有意义。

`peerId` 是对端在握手里自报的，虽然经过密码学绑定（`HandshakeMessage.java:170-172` 校验它等于公钥的 Base58 地址并验签），但生成一个新密钥对的成本近乎为零——**攻击者每次重连换一个身份就把配额清零了**，于是这层防护形同虚设。

`ip` 则不是自报的：`msg.getPeer(channel.getRemoteIp())`（`XdagP2pHandler.java:256`、`:285`）传入的是 `Channel.getRemoteAddress().getAddress().getHostAddress()`，真实套接字地址。这也是路线图原文说的「来源 peer IP」。

代价要知道：同一 IP 背后可能有多个合法节点（NAT、同机多实例），按 IP 限额会牵连它们。这是刻意取舍——配额的目的是让洪泛有成本，而按可任意再生的身份限额没有任何成本。把这个取舍写进代码注释。

- [ ] **Step 3: 实现两层计数**

池内加 `Map<String, Integer> perPeer` 与 `Map<Bytes32, Integer> perChain`，**只对 `CHUNK` 类别维护**。`peerKey` 为 null 或 `chainHead` 为 null 时跳过对应那一层（不建桶、不计数）。`add` 在通过全局与类别检查之后依次查这两层；`remove` 与淘汰两条路径都递减，**归零时必须删键**，否则一轮洪泛过后 map 会留下无界数量的空桶。

- [ ] **Step 4: 写空桶回收的测试**

```java
/** 归零即删键：否则一轮洪泛过后 map 会留下无界数量的空桶。 */
@Test
public void emptyQuotaBucketsAreReclaimed() {
    ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(10).build());
    for (int i = 0; i < 10_000; i++) {
        pool.add(chunkFrom("peer" + i, hash(i), head(i)));
        pool.remove(hash(i));
    }
    assertEquals("a per-peer bucket must be dropped when it reaches zero", 0, pool.peerBucketCount());
    assertEquals("a per-chain bucket must be dropped when it reaches zero", 0, pool.chainBucketCount());
}
```

- [ ] **Step 5: 跑测试确认全部通过**

- [ ] **Step 6: 变异验证**

把 `remove` 的归零删键改成只递减不删键，确认 `emptyQuotaBucketsAreReclaimed` 变红并记下失败文本；再把 peer 层检查整个删掉，确认 `onePeerCannotUseTheWholeChunkBudget` 变红。恢复。

- [ ] **Step 7: 提交**

---

### Task 6: 双制 TTL

**Files:**
- Modify: `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java`
- Test: `src/test/java/io/xdag/chain/orphan/OrphanTtlTest.java`

- [ ] **Step 1: 写失败测试**

```java
/**
 * 纪元 N 的分片块能被纪元 N 或 N+1 的付费块合法引用，最后一刻是 N+1 结束。
 * 所以 N+1 之内不得淘汰，N+2 起可以。
 */
@Test
public void aChunkSurvivesTheEpochAfterItsOwn() {
    ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
    pool.add(chunkAtEpoch(hash(1), 100L));
    pool.evictExpired(0L, 100L);
    assertEquals("same epoch", 1, pool.size(OrphanCategory.CHUNK));
    pool.evictExpired(0L, 101L);
    assertEquals("the epoch a paying block may still reference it from", 1, pool.size(OrphanCategory.CHUNK));
    pool.evictExpired(0L, 102L);
    assertEquals("past the age rule's reach", 0, pool.size(OrphanCategory.CHUNK));
}

/** 计时量是块自己的时间戳，不是本节点收到的时刻——重启后结论必须不变。 */
@Test
public void chunkTtlIsBlockTimestampNotLocalReceipt() {
    ChainOrphanPool pool = newPool(limits().chunkTtlEpochs(2).build());
    pool.add(chunkAtEpoch(hash(1), 100L));
    ChainOrphanPool restarted = newPool(limits().chunkTtlEpochs(2).build());
    restarted.add(chunkAtEpoch(hash(1), 100L));   // 重建：同一个块，新的本地时刻
    pool.evictExpired(0L, 102L);
    restarted.evictExpired(0L, 102L);
    assertEquals(pool.size(OrphanCategory.CHUNK), restarted.size(OrphanCategory.CHUNK));
    assertEquals(0, restarted.size(OrphanCategory.CHUNK));
}

/** 非分片块没有协议年龄界，保持今天的十五分钟本地时钟。 */
@Test
public void nonChunkOrphansKeepTheLocalFifteenMinuteTtl() {
    ChainOrphanPool pool = newPool(limits().build());
    pool.add(linkReceivedAt(hash(1), 0L));
    pool.evictExpired(14 * 60_000L, 0L);
    assertEquals(1, pool.size(OrphanCategory.LINK));
    pool.evictExpired(16 * 60_000L, 0L);
    assertEquals(0, pool.size(OrphanCategory.LINK));
}

/** 淘汰必须把三层配额计数都还回来。 */
@Test
public void evictionReleasesEveryQuotaCounter() {
    ChainOrphanPool pool = newPool(limits().chunk(100).chunkPerPeer(1).chunkTtlEpochs(2).build());
    pool.add(chunkFromAtEpoch("peerA", hash(1), head(1), 100L));
    pool.evictExpired(0L, 102L);
    assertEquals(OrphanAdmission.ADMITTED, pool.add(chunkFrom("peerA", hash(2), head(1))));
}
```

- [ ] **Step 2–6**: 实现 `evictExpired(long nowMillis, long currentEpoch)`——一个方法收两个时钟，分片块只看 `currentEpoch`、其余只看 `nowMillis`，调用方一次性传入两者；淘汰路径必须走与 `remove` 相同的计数释放代码，不得复制一份。变异验证：让淘汰跳过 peer 计数释放，确认最后一个测试变红。

---

### Task 7: `OrphanBlockStoreImpl` 委托给池

**Files:**
- Modify: `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java`
- Test: `src/test/java/io/xdag/db/rocksdb/OrphanBlockStoreConcurrencyTest.java`（必须继续通过，断言不得改写）

这是最大的一次接线。**`OrphanBlockStore` 接口签名不变**，`getOrphan` 的监视器语义与 `811deec0` 建立的锁序原样保留。

- [ ] **Step 1: 先跑既有测试，记录绿的基线**

```bash
mvn -o -Dtest='io.xdag.db.rocksdb.OrphanBlockStoreConcurrencyTest,io.xdag.core.BlockchainTest' test
```

- [ ] **Step 2a: 先把 `OrphanMeta` 提升到 `io.xdag.chain.orphan`（机械移动）**

Task 3 现在从 `io.xdag.chain.orphan` 导入 `io.xdag.db.rocksdb.OrphanBlockStoreImpl.OrphanMeta`（`OrphanEntry.java:27`）。本任务会让 `db.rocksdb` 反过来依赖 `chain.orphan`，于是两个包互指——包循环。

这一步是断开它的唯一自然时机：存储类本来就要被掏空。把这个嵌套类原样移出成 `io.xdag.chain.orphan.OrphanMeta`，**只改包名与引用点，不改字段、不改 `equals`/`hashCode`、不改 `parse`**。已知的跨包引用点：`OrphanEntry`、`BlockchainTest`（以 `OrphanBlockStoreImpl.OrphanMeta` 命名）。移动完先跑一次全量编译确认没有漏网的引用，再进 Step 2b。

如果移动过程中发现它与存储格式耦合到无法干净移出，**停下来说明**，不要强搬——那意味着分层判断需要重议。

- [ ] **Step 2b: 把七件内存集合搬进池**

`linkQueue`/`mtxQueue`/`accountTxMap`/`vipTxMap`/`orphanInsertTimeMap`/`mainRef`/`accountNonce` 全部从 `OrphanBlockStoreImpl` 删除，改为持有一个 `ChainOrphanPool`。`addOrphanToMemory` 的路由逻辑搬进 `OrphanCategory.of` 与池的 `add`；VIP 判定（`nonce == executedNonce + 1` 且 `fee > averageFee`）连同 `accountNonce` 的推进逻辑一并搬入，行为一字不改。

`deleteFromQueue` 不再拼键值字节再 `OrphanMeta.parse`：直接 `pool.remove(block.getHashLow())`。DB 行删除与 `ORPHAN_SIZE` 递减保持原样。

集合改为非并发实现（`TreeSet`/`HashMap`），理由写进类注释：`811deec0` 之后每个访问点都持有区块链监视器。

- [ ] **Step 3: 分片块不写 ORPHANIND**

`addOrphan` 中，类别为 `CHUNK` 时只入池，不写 DB 行、不动 `ORPHAN_SIZE`。`deleteFromQueue` 对称处理。`rebuildMemoryFromDb` 因此按构造只会重建出非分片块，不需要类别字节。

- [ ] **Step 3b: 断言每一个上限都被接线命名（Task 4 留下的债）**

`OrphanLimits` 里未命名的上限是 `UNLIMITED`（`Integer.MAX_VALUE`），不是保守默认值。所以**接线时漏掉任何一个上限，那个类别就变成无界的**——SP0b-3 要加的防护静默失效，而且没有任何征兆。

写一个测试：用真实 `ChainSpec` 构造生产用的 `OrphanLimits`，断言四个类别上限与全局上限**没有一个等于 `UNLIMITED`**，并断言它们等于 Task 2 的九个键的当前值。将来新增类别时这个测试会立刻响。

```java
@Test
public void everyCapIsNamedByTheProductionWiring() {
    OrphanLimits limits = OrphanBlockStoreImpl.limitsFrom(new DevnetConfig().getChainSpec());
    for (OrphanCategory c : OrphanCategory.values()) {
        assertNotEquals("category " + c + " was left unbounded by the wiring",
                OrphanLimits.UNLIMITED, limits.capFor(c));
    }
    assertNotEquals(OrphanLimits.UNLIMITED, limits.poolLimit());
}
```

- [ ] **Step 3c: 清理线程传给 `evictExpired` 的纪元必须跟着链走，不能用墙上时钟（共识安全，非选项）**

Task 6 发现规格 §4.1 的安全论证有一个前提没写出来：「当前纪元」必须跟着**正在处理的块**走，而不是跟着墙上时钟走。

同步积压时两者会分开。节点还在处理纪元 N+1 的付费块，墙钟已经到了 N+2，于是纪元 N 的分片块被提前淘汰——可那个付费块引用它完全合法（年龄规则允许 N 或 N+1）。结果是淘汰了的节点判 INVALID、保留着的节点判 OK，**两个诚实节点对同一条链得出不同结论**，正是年龄规则本身要防的那种分歧。

所以清理线程传入的 `currentEpoch` 取**本节点主链顶端的纪元**（`XdagTime.getEpoch(顶端主块时间戳)`），不取 `XdagTime.getCurrentEpoch()`。落后的节点因此淘汰得同样落后，追上之后自然对齐。Task 6 刻意把 `currentEpoch` 做成参数而不在池内读时钟，就是为了让这个选择留在接线层。

写一个测试钉住：构造「链顶端停在纪元 N+1、墙钟已到 N+3」的场景，断言纪元 N 的分片块**仍在池中**。变异验证：把传入值换成墙钟纪元，确认它变红。

- [ ] **Step 4: 跑既有测试，必须全绿且断言未改**
- [ ] **Step 5: 提交**

---

### Task 8: 准入闸门按类别

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`:471` 附近）
- Test: `src/test/java/io/xdag/chain/orphan/OrphanFloodTest.java`

- [ ] **Step 1: 写失败测试**

```java
/**
 * 今天活着的饿死形态二：3750 是四队列全局计数却只在账户交易进来时检查，
 * 于是 link/chunk/mtx 灌满池子后账户交易开始被拒。
 */
@Test
public void aChunkFloodDoesNotStarveAccountTransactions() throws Exception {
    for (int i = 0; i < 5000; i++) {
        assertImported(chunkBlock(i));
    }
    ImportResult r = blockchain.tryToConnect(accountTxBlock(aliceKey, 1));
    assertTrue("a chunk flood must not push account transactions out of the pool: " + r,
            r == IMPORTED_BEST || r == IMPORTED_NOT_BEST);
}
```

- [ ] **Step 2: 跑测试确认失败**，记录失败文本（应为 `INVALID_BLOCK` 且信息含 "Orphan block pool is full"）。
- [ ] **Step 3: 实现**：`:471` 的 `isAccountTx(block) && getOrphanSize() >= MAX_ORPHAN_SIZE` 改为按该块的类别查对应上限。`MAX_ORPHAN_SIZE` 常量删除，四个上限来自配置。
- [ ] **Step 4–6**: 跑测试、变异验证、提交。

---

### Task 9: 打包顺序反转优先级

**Files:**
- Modify: `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java`（`selectBlocks`）
- Test: `src/test/java/io/xdag/chain/orphan/OrphanPackingFairnessTest.java`

- [ ] **Step 1: 写失败测试**

```java
/** 今天活着的饿死形态一：linkQueue 非空时无条件提前返回，账户交易轮不到。 */
@Test
public void aMainBlockTakesAccountTransactionsBeforeChunks() {
    for (int i = 0; i < 50; i++) pool.add(chunk(hash(100 + i)));
    pool.add(accountTx(hash(1), addr(1), 1L));
    List<OrphanMeta> picked = store.selectBlocks(16, Long.MAX_VALUE, true);
    assertTrue("an account transaction must reach a main block even with chunks queued",
            picked.stream().anyMatch(m -> m.hashlow().equals(hash(1))));
}

/** 但 linkQueue 仍要被消费，否则安静节点的 nnoref 永远追不平。 */
@Test
public void aMainBlockStillFillsRemainingSlotsFromTheLinkQueue() {
    pool.add(accountTx(hash(1), addr(1), 1L));
    for (int i = 0; i < 50; i++) pool.add(link(hash(100 + i), i));
    List<OrphanMeta> picked = store.selectBlocks(16, Long.MAX_VALUE, true);
    assertEquals("the main block must still be filled to its budget", 16, picked.size());
}

@Test
public void aLinkBlockStillDrainsTheLinkQueue() {
    for (int i = 0; i < 50; i++) pool.add(link(hash(100 + i), i));
    assertFalse(store.selectBlocks(16, Long.MAX_VALUE, false).isEmpty());
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**：删掉 `:462` 的无条件 `return`；主块顺序改为 `mainRef` → VIP（≤6）→ 账户交易 + mtx 合并 → `linkQueue` 填满剩余名额；link 块顺序为 `mainRef`（消费式）→ `linkQueue`。`mainRef` 的 `isMain` 差异保留。
- [ ] **Step 4–6**: 跑测试、变异验证（恢复提前返回，确认第一个测试变红）、提交。

---

### Task 10: peer 与 kind 管线

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`dealOrphan`、两个 `tryToConnect`）
- Modify: `src/main/java/io/xdag/db/OrphanBlockStore.java`（`addOrphan` 增参）
- Test: `src/test/java/io/xdag/chain/orphan/OrphanQuotaTest.java`（追加端到端一例）

- [ ] **Step 1: 写失败测试**：经 `IngestPipeline` 投递两个不同 peer 的分片块，断言池内两条的 `peerKey` 不同且各计各的配额。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**：`tryToConnect(PreValidated pv)` 从 `pv.wrapper().getRemotePeer()` 取来源、从 `pv.classified().kind()` 取分类，一路传到 `dealOrphan(block, peerKey, kind)` 与 `addOrphan(..., peerKey, kind)`。`tryToConnect(Block)` 传 null/null。

**`peerKey` 取 `Peer.getIp()`，不是 `getPeerId()`**——理由见 Task 5 前置说明，那里有完整论证，这里只重申结论：`peerId` 可零成本再生，拿它当配额键等于没有配额。

**链头归组的质量直接决定按链配额的强度。** Task 5 查明：归不到链头（head 为 null）的分片块完全绕过按链那一层，只受按 peer 与全局约束。所以归组越弱，攻击者越容易把自己的分片块做成「无主」来只付按 IP 的 5000 而不碰按链的 20000。这仍然是有界的，可以接受，但**不要为了省事而让归组轻易返回 null**：沿 `next` 能走到的就走到，走不到再记为无主。在实现里注明这层关系，免得后来者以为 null 只是个无所谓的缺省。
- [ ] **Step 4–6**: 跑测试、变异验证、提交。

---

### Task 11: 分片块体存储与延迟落盘

**Files:**
- Modify: `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java`
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`:737`）
- Test: `src/test/java/io/xdag/chain/orphan/ChunkDeferredPersistTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
public void anUnreferencedChunkIsNotOnDisk() throws Exception {
    Block chunk = chunkBlock(1);
    assertImported(chunk);
    assertNull("an unreferenced chunk must not reach the block store",
            kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
    assertNotNull("but it must still be servable from memory",
            blockchain.getBlockByHash(chunk.getHashLow(), true));
}

@Test
public void aReferencedChunkIsPersisted() throws Exception {
    Block chunk = chunkBlock(1);
    assertImported(chunk);
    assertImported(payingBlockReferencing(chunk));
    assertNotNull("a chunk referenced by an imported paying block must be persisted",
            kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**：`:737` 的 `saveBlock(block)` 在类别为 `CHUNK` 时跳过，块体存入池；付费块 `IMPORTED_*` 时把它引用到的分片链上每个块 `saveBlock` 并从池的块体存储移出。块体存储的容量由分片配额封顶，无需另设上限。
- [ ] **Step 4–6**: 跑测试、变异验证、提交。

---

### Task 12: 合并 `getBlockByHash` 查找与等价性属性测试

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`getBlockByHash`，`:2258` 附近）
- Test: `src/test/java/io/xdag/core/BlockLookupEquivalenceTest.java`

**这是本计划风险最高的一处改动**：该查找在共识路径上（`ChainL1Processor` 经 `BlockchainImpl:324` 使用），同时被 netty 线程锁外调用。

- [ ] **Step 1: 写失败测试**

```java
/**
 * 合并后的查找对任意块序列必须与改造前逐字节相等。属性测试，固定种子可重放。
 */
@Test
public void theMergedLookupIsByteIdenticalToTheOldOne() {
    for (long seed : SEEDS) {
        List<Block> blocks = randomBlocks(seed, 500);
        for (Block b : blocks) assertImported(b);
        for (Block b : blocks) {
            Block viaLookup = blockchain.getBlockByHash(b.getHashLow(), true);
            assertNotNull("seed " + seed + " lost " + b.getHashLow(), viaLookup);
            assertArrayEquals("seed " + seed + " served different bytes for " + b.getHashLow(),
                    b.getXdagBlock().getData().toArray(),
                    viaLookup.getXdagBlock().getData().toArray());
        }
    }
}

/** 块体存储被 netty 线程锁外读，必须安全。 */
@Test(timeout = 30_000)
public void concurrentLookupsDuringImportNeverThrowOrLoseABlock() throws Exception {
    // 一个线程持续导入分片块，另一个线程持续 getBlockByHash，断言无异常且已导入的块始终可见
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**：`getBlockByHash` 在读池 A 之后、读块存储之前，插入对池的分片块体查询。块体存储必须独立保证并发读安全（不依赖只有监视器持有者才成立的不变式）。对外服务沿用 `a51e09c5` 的结论：发从原始字节重新解析的副本。
- [ ] **Step 4–6**: 跑测试、变异验证、提交。

---

### Task 13: `ImportResult.CHAIN_FEE_POLICY` 与既有分支核对

**Files:**
- Modify: `src/main/java/io/xdag/core/ImportResult.java`
- Test: `src/test/java/io/xdag/consensus/ImportResultBranchTest.java`

**Task 8 实测发现的真实代价，这是诚实结果码的主要论据：** `SyncManager` 对 `INVALID_BLOCK` 的处理**不调用 `syncPopBlock`**。所以每一个在 `syncMap` 里等待这个块的子块，会一直停在那里直到被淘汰——一句「我这儿满了」会**搁浅整棵子树**。

相比之下惩罚性动作反而不是问题：`releaseWaiters` 的 `case INVALID_BLOCK` 是空的（日志都注释掉了），今天没有任何对端会因此被计分或封禁。

所以 `CHAIN_FEE_POLICY` 的价值不在「别冤枉发送方」，而在**让等待的子块能继续推进**。核对分支时重点看的是 `syncPopBlock` 这一侧，不是封禁那一侧。

- [ ] **Step 1: 列出所有需要核对的分支**

```bash
grep -rn "INVALID_BLOCK" --include="*.java" src/main/java
```

逐一判断：新变体表达「本节点不想要」，不是「这个块坏了」。任何做惩罚性动作（断连、计分、黑名单）的分支都不得把它算进去。

- [ ] **Step 1b: 顺手关掉 Task 11 指出的 `nnoref` 泄漏**

Task 11 发现：一个通过了类别闸门、却被**按 peer 或按链配额**拒绝的分片块，仍然会执行 `nnoref++`，而它现在既不在池里也不在磁盘上——这个计数永远还不回来。

这个泄漏本身早于本子项目（任何被拒且无人引用的块都一样漏），但 Task 11 把它变宽了：以前那个块在磁盘上，后来的引用会修复计数；现在不会。

两条可行路线，选一条并说明：让 `addOrphan` 把准入判决向上返回（Task 10 已经把来源与类别送下去了，这是同一条管线的回程），或者把二级配额也提到闸门上查（但 `ChainOrphanPool.isFull` 的文档论证过不该这么做——闸门只知道类别，把「这个 peer 花完了预算」变成「池子满了」会误伤其它来源）。

前者更对。`nnoref` 应当与「真的进了池」配对，正如 Task 7 让它与「真的离开了池」配对。

- [ ] **Step 2: 写测试钉住**：断言 `CHAIN_FEE_POLICY` 不触发任何惩罚路径。
- [ ] **Step 3–6**: 加变体、修分支、跑测试、提交。

---

### Task 14: 费率策略闸门与被请求块豁免

**Files:**
- Create: `src/main/java/io/xdag/chain/ingest/ChunkFeePolicy.java`
- Modify: `src/main/java/io/xdag/consensus/SyncManager.java`
- Test: `src/test/java/io/xdag/chain/ingest/ChunkFeePolicyTest.java`

- [ ] **Step 1: 写失败测试**

```java
/**
 * 最要紧的一条：被拒的块在本节点主动请求回来时必须被接受。
 * 否则主块引用它时拉不回来，节点停在该高度永远同步不动。
 */
@Test
public void aRequestedBlockBypassesThePolicy() throws Exception {
    Block underpaid = underpaidPayingBlock();
    assertSame(ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid));
    ImportResult viaRequest = submitAsRequested(underpaid);
    assertTrue("a block this node asked for must never be refused by its own policy: " + viaRequest,
            viaRequest == IMPORTED_BEST || viaRequest == IMPORTED_NOT_BEST);
}

/** 端到端：策略开启下，主块引用被拒块，节点必须能继续同步。 */
@Test
public void aMainBlockReferencingARefusedBlockStillSyncs() throws Exception {
    Block underpaid = underpaidPayingBlock();
    assertSame(ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid));
    Block main = mainBlockReferencing(underpaid);
    assertImported(main);
    assertEquals("the node must have pulled the refused block back in",
            nmainBefore + 1, blockchain.getXdagStats().nmain);
}

/** 漏数导致放行，不是误拒：付费块先于分片块到达是正常顺序。 */
@Test
public void aPayingBlockArrivingBeforeItsChunksIsAdmitted() throws Exception {
    Block paying = payingBlockWithChainHead(head(1), /* chunks not yet sent */ 0);
    assertNotSame(ImportResult.CHAIN_FEE_POLICY, submitAsGossip(paying));
}

/** 策略开/关，最终 DAG 内容一致。 */
@Test
public void theDagIsTheSameWithThePolicyOnAndOff() throws Exception {
    assertEquals(runScenario(true), runScenario(false));
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现 `ChunkFeePolicy`**

```java
/**
 * A node-local gate in front of BOTH ingest paths. It is deliberately not inside
 * {@code PreValidator}: SP0b-2's P1 requires pre-validation to accelerate acceptance and never
 * reject early, and this is an early rejection based on off-lock reads. Sitting equally in
 * front of the pipeline and the synchronous path keeps the two paths in agreement, which is
 * what P1 actually asks for.
 *
 * <p>A block this node requested is NEVER refused here. The safety argument for refusing at
 * all is "the main block will arrive and we will pull it" -- and the pull goes through
 * tryToConnect too, so refusing a requested block would leave the node unable to ever admit it.
 */
public final class ChunkFeePolicy {
    public boolean refuse(Block payingBlock, boolean requested) {
        if (!enabled || requested) {
            return false;
        }
        int chunks = ChunkChain.countLenient(head(payingBlock), lookup, maxPerChain);
        return payingBlock.getFee().lessThan(chunkFee.multiply(chunks));
    }
}
```

- [ ] **Step 4: 接入 `SyncManager`**：在投递到 `IngestPipeline` 之前与同步路径之前各一处，`requested` 取自「本节点是否请求过这个块」，不得以 `isOld` 单独作准（它是同步标志，不是请求标志——两者的差异要在实现时确认并在注释里写明）。
- [ ] **Step 5–6**: 跑测试、变异验证（去掉豁免，确认第二个测试变红并给出「节点卡住」的失败文本）、提交。

---

### Task 15: 对抗测试

**Files:**
- Test: `src/test/java/io/xdag/chain/orphan/OrphanFloodTest.java`（补齐）

- [ ] **Step 1: TTL 淘汰后被引用，两个节点结论必须一致**

```java
/**
 * 分片块被 TTL 淘汰后其付费块才到达：链组装失败。关键断言是这个结论与
 * 「本节点从未收到过该分片块」完全一致——年龄规则保证了这一点，所以
 * 淘汰不会让两个诚实节点分歧。
 */
@Test
public void aChunkEvictedByTtlGivesTheSameVerdictAsOneNeverReceived() throws Exception {
    InputStatus evicted = runWithChunkThenEvict();
    InputStatus neverSeen = runWithoutChunkAtAll();
    assertEquals("eviction must not change the verdict the age rule already fixes",
            neverSeen, evicted);
}
```

- [ ] **Step 2: 移除复杂度不随池子变大而线性上升**

用确定性计数而非墙钟——墙钟在并行构建下会抖。在池上加一个仅测试可见的比较次数计数器，断言移除一条时的比较次数在池子放大一百倍后增长不超过对数量级。

```java
@Test
public void removalCostDoesNotGrowLinearlyWithPoolSize() {
    long small = comparisonsForOneRemoval(1_000);
    long large = comparisonsForOneRemoval(100_000);
    assertTrue("removal went linear: " + small + " -> " + large, large < small * 4);
}
```

**`comparisonsForOneRemoval` 必须造最坏情况，不是最好情况。** Task 1 发现按 nonce 升序移除会每次命中堆顶，走 O(log n)，那样即使不改任何代码这个测试也会通过。要少量发送方、深队列、与比较器不相关的移除顺序（固定种子打乱），并且要覆盖 `mainRef`——它是 `ConcurrentLinkedDeque`，`remove(Object)` 是纯线性走查，没有堆结构可借力，而 `deleteFromQueue` 每次移除都会碰它，不分类别。

- [ ] **Step 3: 锁序未被破坏**

断言 `getOrphan` 仍在区块链监视器下执行，且不存在「先持孤块池后取监视器」的反向路径。沿用 `OrphanBlockStoreConcurrencyTest` 既有的闩锁交错手法，不改写它的断言。

- [ ] **Step 3b: 量一量分片块离开 link 队列之后 `checkOrphan` 的空转（Task 10 留下的代价）**

Task 10 指出：分片块归入 CHUNK 类之后就不再进 link 队列，所以本节点挖的任何块都不会引用它们。但一个到达的分片块仍然会抬高 `nnoref`，而且要等两个纪元后清理线程才还回来。`checkOrphan` 用 `nblk = nnoref / 11` 决定挖多少 link 块——于是**洪泛期间节点会挖出一批根本无法引用任何东西的 link 块**。

计数本身是对的（Task 7 已把递减改成按条目而非按行），变的是时机。但「对的计数」不等于「没有浪费」。

测：在分片洪泛下记录 `checkOrphan` 触发的 link 块数量，与同等规模的非分片洪泛对比。如果空转显著，把数字写进 Task 16 的文档并作为待办列出——**不要在本子项目里顺手改 `checkOrphan` 的计数口径**，那会动到出块节奏，属于另一个变更的范围。若不显著，同样据实记录，这个疑虑就此关闭。

- [ ] **Step 4: 单 peer 与单链洪泛的端到端**

经真实 `IngestPipeline` 投递，而非直接调池：断言单一来源被 `chunkPerPeer` 截断后，其它来源的分片块与账户交易都不受影响。

- [ ] **Step 5: TTL 重启稳定的端到端**

重启节点后分片块的剩余寿命不被重置（与 Task 6 的单元测试互补，这一条走真实的 `rebuildMemoryFromDb`）。
- [ ] **Step 6: 每一条都做变异验证并记录失败文本**
- [ ] **Step 7: 提交**

---

### Task 16: 基准 AFTER 与收尾

**Files:**
- Modify: `docs/benchmarks/2026-09-19-orphan-pool.md`

- [ ] **Step 1: 跑全量套件与许可证检查**

```bash
mvn -o test
mvn -o -q license:check
```

- [ ] **Step 2: 重跑基准，把 AFTER 表写进同一份文档**，与 Task 1 的 BEFORE 并列。

三行各有各的用途，报告时必须分开讲，**不要把它们混成一个「孤块池变快了」的结论**：

| 行 | 它回答什么 |
|---|---|
| `phase.orphan.add` | 快乐路径有没有退步 |
| `phase.orphan.remove` | 快乐路径有没有退步 |
| `phase.orphan.removeWorst` | **SP0b-3 到底有没有修好它要修的东西** |

Task 1 查明 `orphan.remove` 测的是当前移除的**最好情况**：基准按工作负载顺序移除，对每个发送方就是 nonce 升序，而那正是堆序，于是 `contains()` 与 `remove()` 每次命中索引 0，走 O(log n) 下沉而非 O(n) 扫描。所以前两行前后持平只说明没退步，**不能拿来证明线性移除的问题被解决了**。`removeWorst`（少量发送方、深队列、乱序移除、覆盖 `mainRef`）才是验收标准第 3 条的依据。

**必须与 Task 1 的 BEFORE 表比，不能与 `docs/benchmarks/2026-09-19-l1-import-pipeline.md` 比**——那份是在更快的机器状态下记的，跨表对比会凭空造出改进或盖掉退步。

- [ ] **Step 3: 诚实结论**：若锁内时间没有下降，明说没有下降；本子项目的验收标准是公平性与可负担性，不是吞吐目标。吞吐目标属于 SP0b-2b。

**`pipeline.rN` 不会因为 SP0b-3 而改善，不要把它当成验收信号。** Task 1 查明基准夹具从不设置 pow，而 `dealOrphan` 被 `getEnableGenerateBlock() && getPow() != null` 把关（`BlockchainImpl.java:818`），所以**孤块池在基准的每一个导入行里都是空操作**。SP0b-2 的 7157 块/s 里孤块池开销为零。

两个推论：

其一，路线图 §5.2.1 把孤块池增删列为锁内 ≥84% 的头号嫌疑，这个前提是错的——产生那个 84% 的基准里它根本没运行。

其二，真实挖矿节点上它会运行，而 `addOrphan` 单独就是 38.2 µs，对照锁内提交的 140.9 µs，**真实节点的锁内时间比基准报告的高出一截**。基准低估了真实开销。

因此 SP0b-3 的收益只能从 `phase.orphan.*` 四行读出，不能从导入行读。**不要为此给夹具加上 pow**：那会让所有历史基线失去可比性，而可比性是这套基准唯一的价值。

- [ ] **Step 4: 提交**（`docs/` 需 `git add -f`）

---

## 验收标准

1. 垃圾分片洪泛下账户交易仍可导入（Task 8）。
2. 主块打包中账户交易优先于分片块，且 `linkQueue` 仍被消费（Task 9）。
3. 三层配额各自生效且互不影响；配额计数在移除与淘汰后都归还，空桶不残留（Task 4/5/6）。
4. 分片块 TTL 按纪元、重启稳定；`chunkTtlEpochs < 2` 启动即失败（Task 2/6）。
5. 合并后的 `getBlockByHash` 与改造前逐字节相等，且锁外并发读安全（Task 12）。
6. 费率策略开/关下最终 DAG 一致；被请求的块永不被策略拒绝（Task 14）。
7. `811deec0` 与 `a51e09c5` 的回归测试继续通过且断言未被改写。
8. 全量套件绿，`license:check` 通过，`.conf` 文件未新增任何 `chain.*` 键。
