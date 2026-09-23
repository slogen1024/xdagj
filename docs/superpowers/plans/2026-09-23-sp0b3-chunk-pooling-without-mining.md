# SP0b-3 追加修复实施计划：不挖矿的节点也要留得住分片块

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让不挖矿的节点也能持有分片块（Task 11 的回归），并停止在无可引用时建造会被自己拒绝的 link 块。

**Architecture:** 两处独立缺陷、同一个不对称——分片块在池里但不在任何消费者视野里。P1 在 `dealOrphan` 内按类别拆挖矿闸门；P2 在挖矿路径下游收口（可服务计数、不伪造时间戳、无引用不建块），**不动 `nnoref` 的含义**。

**Tech Stack:** Java 21、JUnit 4、既有 `ChainOrphanPool` / `ChunkOrphanTestBase` / `ChainL1TestBase`。

**上位文档：** 规格 `docs/superpowers/specs/2026-09-23-sp0b3-chunk-pooling-without-mining-design.md`（提交 `cdd0632b`）。

---

## 构建陷阱（对所有任务有效）

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
```

- 裸 PATH 的 `java` 是 17，会给出 class file 65.0 vs 61.0。
- **Maven 增量测试编译会对着陈旧 class 报成功。** 改公共签名后不要拿 `test-compile` 的通过当证据；跑真测试，或先 `touch` 受影响文件。Task 15 就是被这个咬过一次（变异脚本还原文件时 mtime 更旧，`target/` 里留着被变异的主类）。
- **不要在另一个 `mvn test` 还活着时跑 `mvn clean`**，会产生假的 `NoClassDefFound`。
- 新 `.java` 需要 MIT 头，`mvn license:check` 会查。
- 不得给任何 `.conf` 新增 `chain.*` 键。
- `docs/` 全局 gitignore，提交需 `git add -f`。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `src/main/java/io/xdag/core/BlockchainImpl.java` | 改。`dealOrphan` 按类别拆闸门；`createLinkBlock` 无引用不建块；`checkOrphan` 处理 null |
| `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java` | 改。新增 `selectableSize()` |
| `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java` | 改。`getOrphanLocked` 用可服务计数；空选择不伪造 `sendtime[1]`；`getOrphanSize` 补观测契约 javadoc |
| `src/test/java/io/xdag/chain/orphan/ChunkWithoutMiningTest.java` | 新建。默认配置（生成开启）、不装 PoW：PoW 那一半 + 配额/TTL 仍生效 |
| `src/test/java/io/xdag/chain/orphan/ChunkWithGenerationDisabledTest.java` | 新建。`newConfig()` 关掉生成 + 装上 PoW：配置那一半 |
| `src/test/java/io/xdag/chain/orphan/IdleLinkMintingTest.java` | 新建。P2 的直接断言（无引用不建块、不伪造时间戳） |
| `src/test/java/io/xdag/chain/orphan/ChunkFloodAdversarialTest.java` | 改。Step 3b 改为断言新行为 |
| `docs/superpowers/plans/2026-09-19-xdag-chain-sp0b3-orphan-pool.md` | 改。Step 3b 待办改为已修复 |

---

### Task 1: 不挖矿的节点必须留得住分片块

**Files:**
- Create: `src/test/java/io/xdag/chain/orphan/ChunkWithoutMiningTest.java`
- Create: `src/test/java/io/xdag/chain/orphan/ChunkWithGenerationDisabledTest.java`
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`dealOrphan`）

- [ ] **Step 1: 写失败测试**

新建 `ChunkWithoutMiningTest extends ChunkOrphanTestBase`（MIT 头照抄任一现有文件）。

**两个测试各隔离闸门的一半，这一点必须做对。** 闸门是 `getEnableGenerateBlock() && getPow() != null`。devnet 配置本来就把 `node.generate.block.enable` 设为 `true`，而 `ChunkOrphanTestBase.armTheOrphanPool()` 做的正是 `kernel.setPow(mock)`。所以：

- **配置那一半**：调用 `armTheOrphanPool()`（装上 PoW），再用 `newConfig()` 把生成关掉 —— 此时唯一关着闸门的是配置。
- **PoW 那一半**：用默认配置（生成开启），**不**调 `armTheOrphanPool()` —— 此时唯一关着闸门的是 `getPow() == null`。这也正是夹具的默认状态。

不这样分，两个测试测的是同一件事。

**因此这是两个类，不是一个类里的两个方法。** `enableGenerateBlock` 在 `AbstractConfig` 上是 `protected` 字段且**没有 setter**，而 `newConfig()` 是按类生效的，所以没有办法在同一个类里让一个测试看到生成开启、另一个看到关闭。

- `ChunkWithoutMiningTest` —— 不覆写生成配置（默认开启），不调 `armTheOrphanPool()`。承载 PoW 那一半，以及 Task 2 的配额/TTL 测试（它们本来就该在非挖矿形态下跑）。
- `ChunkWithGenerationDisabledTest` —— 覆写 `newConfig()` 关掉生成，并在测试里调 `armTheOrphanPool()`。只承载配置那一半，一个测试。

配置那一半要用覆写 getter 的子类（`ChainL1ProcessorTest` 用的就是这个写法）：

```java
    @Override
    protected Config newConfig() {
        return new DevnetConfig() {
            @Override public boolean getEnableGenerateBlock() { return false; }
        };
    }
```

**先确认 `armTheOrphanPool()` 在生成关闭时仍然可用**（它内部调 `mineMain(List.of())`）。若不可用，停下来报告并说明你退而采用的隔离方式，不要默默把两个测试写成同一个形态。

```java
/**
 * Task 11 made a chunk's only home the orphan pool, and the pool's entrance sits behind the
 * mining gate, so a node that does not mine stored an arriving chunk in neither place and every
 * paying block naming it was NO_PARENT for ever. These two tests are the two shapes that reaches:
 * block generation turned off in config, and a node whose PoW instance does not exist yet.
 */
@Test
public void aNodeWithBlockGenerationOffStillKeepsAnArrivingChunk() {
    armTheOrphanPool();   // installs the PoW mock, so config is the only half holding the gate
    Block chunk = lightChunk(701);
    assertImported(deliver(chunk));
    assertNotNull("a node that does not mine must still hold the chunk it was sent",
            pool().chunkBody(chunk.getHashLow()));

    Block paying = linkTo(chunk, 702);
    ImportResult r = deliver(paying);
    assertTrue("the block that pays for the chunk must import, not hang on NO_PARENT: "
            + r + " " + r.getErrorInfo(),
            r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
}

@Test
public void aNodeWhosePowDoesNotExistYetStillKeepsAnArrivingChunk() {
    // Deliberately no armTheOrphanPool(): generation is on in config, and getPow() == null is the
    // only half holding the gate. This is also the fixture's default state.
    Block chunk = lightChunk(711);
    assertImported(deliver(chunk));
    assertNotNull("the startup window before the PoW instance exists must not lose chunks",
            pool().chunkBody(chunk.getHashLow()));
}
```

`newConfig()` 覆写把 `node.generate.block.enable` 关掉——按 `ChunkPersistBudgetBoundaryTest` / `OrphanRefusalAccountingTest` 的既有做法写一个 `DevnetConfig` 子类覆写 `getEnableGenerateBlock()` 返回 `false`。**不要给字段赋值**（Task 13 移除了所有那种写法，构造函数里赋值会静默丢失）。

第二个测试用默认配置（生成开启）但 `kernel.setPow(null)`，覆盖闸门的另一半。

`pool().chunkBody(...)` 若不存在，用 `ChainOrphanPool` 现有的 chunk-body 读取方法（`getChunkBody` 一类，见 `OrphanBlockStore.getChunkBody` 的委托目标）。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -o test -Dtest=ChunkWithoutMiningTest
```
预期：两个测试都失败。第一个应当在 `assertNotNull` 处失败（池里没有），若断言顺序不同则在付费块的 `NO_PARENT` 处失败。**把实际失败文本记下来**——它是这个缺陷的现场证据。

- [ ] **Step 3: 拆闸门**

`BlockchainImpl.dealOrphan(Block, String, Classified)` 改为：

```java
    public boolean dealOrphan(Block block, String peerKey, Classified classified) {
        // A chunk's only home is this pool: tryToConnect skips saveBlock for the CHUNK category,
        // so the mining gate below must not be what decides whether this node keeps it. Every
        // other category is already on disk by the time we get here -- for them the pool is only
        // the mining work queue, and a node that does not mine buys nothing by filling it.
        boolean chunk = orphanCategoryOf(block, classified) == OrphanCategory.CHUNK;
        if (!chunk && !(kernel.getConfig().getEnableGenerateBlock() && kernel.getPow() != null)) {
            return false;
        }
        UInt64 nonce = UInt64.ZERO;
        XAmount fee = getTxFee(block);
        byte[] address = orphanAddressOf(block);
        if (address != null) {
            nonce = block.getTxNonceField().getTransactionNonce();
        }
        return getOrphanBlockStore().addOrphan(block, isTxBlock(block), nonce, fee, address,
                peerKey, classified) == OrphanAdmission.ADMITTED;
    }
```

同时更新该方法 javadoc 最后一段：它今天说「a node that pools nothing at all — no PoW, or block generation turned off — because nothing went in there either」，这句话现在只对非分片类别成立，要改写并说明为什么分片块是例外。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -o test -Dtest=ChunkWithoutMiningTest
```
预期：`Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 5: 变异验证**

把 `boolean chunk = ...` 改成 `boolean chunk = false;`，重跑，确认两个测试都变红并记录失败文本。还原。

- [ ] **Step 6: 跑全量，确认没有别的东西依赖「不挖矿就不入池」**

```bash
mvn -o test
```
预期：682 + 2 = 684 绿。**若有既存测试变红，先停下来报告**——那意味着有测试把这个洞当成了约定，需要判断是它错还是本改动错，不要直接改那个测试的断言。

- [ ] **Step 7: 提交**

```bash
git add src/test/java/io/xdag/chain/orphan/ChunkWithoutMiningTest.java \
        src/test/java/io/xdag/chain/orphan/ChunkWithGenerationDisabledTest.java \
        src/main/java/io/xdag/core/BlockchainImpl.java
git commit
```
主题行英文祈使句，正文说明 Task 11 造成的不对称与为何只对 CHUNK 开口，结尾 `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`。**提交信息全英文**。

---

### Task 2: 不挖矿的节点持有分片块，仍受三层配额与 TTL 约束

**Files:**
- Modify: `src/test/java/io/xdag/chain/orphan/ChunkWithoutMiningTest.java`

这是 §7 风险表那一行的证据：新增的内存持有由既有机制约束，不需要新机制。没有它，Task 1 就是一个「不挖矿的节点现在可以被灌爆」的未经检验的声明。

- [ ] **Step 1: 写失败测试**

在 `ChunkWithoutMiningTest` 里加两个常量、一个 `newConfig()` 覆写和两个测试。`newConfig()` **只调配额，不碰生成开关**（生成保持开启，非挖矿形态由「不调 `armTheOrphanPool()`」提供）：

```java
    /** Small enough that a flood hits it, large enough that one chunk does not. */
    private static final int BUDGET = 3;
    private static final int CHUNKS_SENT = 10;
    private static final String FLOODER_IP = "198.51.100.42";

    @Override
    protected Config newConfig() {
        AbstractConfig cfg = (AbstractConfig) super.newConfig();
        cfg.setChainOrphanChunkPerPeer(BUDGET);
        cfg.setChainOrphanChunkPerChain(BUDGET);
        return cfg;
    }
```

```java
/**
 * The holding Task 1 restored is bounded by the machinery that already exists for it: the CHUNK
 * tier cap, the per-peer budget, and the two-epoch TTL. A non-mining node is not a node with no
 * limits -- it is the same pool with the same three bounds.
 */
@Test
public void aNonMiningNodeStillChargesAFloodToItsSenderBudget() {
    for (int i = 0; i < CHUNKS_SENT; i++) {
        deliver(lightChunk(720 + i), FLOODER_IP);
    }
    assertEquals("the per-peer budget binds whether or not this node mines",
            BUDGET, pool().size(OrphanCategory.CHUNK));
}

@Test
public void aNonMiningNodeStillAgesChunksOut() {
    Block chunk = lightChunk(740);
    assertImported(deliver(chunk));
    assertNotNull("the chunk is held before its TTL runs out",
            pool().chunkBody(chunk.getHashLow()));

    // evictExpired(nowMillis, currentEpoch) -- the memory half of the cleaner tick, driven the way
    // ChunkFloodAdversarialTest drives it. Two epochs past the chunk's own header is what the age
    // rule makes the cut-off.
    long epoch = XdagTime.getEpoch(chunk.getTimestamp());
    assertEquals("the TTL must actually fire, or this is not the state the test says it is",
            1, pool().evictExpired(0L, epoch + 2).size());

    assertNull("the two-epoch TTL is what bounds a non-mining node's holding over time",
            pool().chunkBody(chunk.getHashLow()));
}
```

`setChainOrphanChunkPerPeer` / `setChainOrphanChunkPerChain` 的存在已在 `ChunkFloodQuotaPipelineTest` 的 `newConfig()` 里确认。`evictExpired` 返回被逐出的条目列表，所以那句 `assertEquals(1, ...size())` 同时把前提自证了——没有它，最后的 `assertNull` 可以被一个「TTL 根本没跑、但块也从来没进去过」的状态满足。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -o test -Dtest=ChunkWithoutMiningTest
```
预期：两个新测试失败（配额与 TTL 尚未在这个形态下被证明）。**注意**：它们也可能直接通过——若如此，说明既有机制确实已经覆盖这个形态，这依然是有价值的结论，但必须靠变异验证（Step 4）证明断言不是摆设，而不是靠「它一开始就绿」。

- [ ] **Step 3: 不需要生产改动**

本任务只加证据。若测试需要生产改动才能通过，**停下来报告**——那意味着 Task 1 打开了一个配额没盖住的口子，属于设计缺口而不是测试问题。

- [ ] **Step 4: 变异验证**

- 把 `chunkPerPeer` 的检查从池的准入里拿掉 → 第一个测试必须红。
- 让 `evictExpired` 对分片块不做任何事 → 第二个测试必须红。

记录两段失败文本，还原。

- [ ] **Step 5: 跑本类 + 提交**

```bash
mvn -o test -Dtest=ChunkWithoutMiningTest
git add src/test/java/io/xdag/chain/orphan/ChunkWithoutMiningTest.java
git commit
```

---

### Task 3: 预算改用可服务计数

**Files:**
- Modify: `src/main/java/io/xdag/chain/orphan/ChainOrphanPool.java`
- Modify: `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java`
- Create: `src/test/java/io/xdag/chain/orphan/IdleLinkMintingTest.java`

- [ ] **Step 1: 写失败测试**

新建 `IdleLinkMintingTest extends ChunkOrphanTestBase`（MIT 头）。本任务只写第一个测试，Task 4 再补其余。

```java
/**
 * The budget a link block is built against must describe what the packing walk can actually hand
 * out. totalSize() counts all four categories; selectBlocks never offers a CHUNK, because a chunk
 * is held for the block that will pay for it rather than for this node to reference.
 */
@Test
public void theSelectableCountExcludesChunks() {
    armTheOrphanPool();
    int linksBefore = pool().size(OrphanCategory.LINK);
    for (int i = 0; i < 5; i++) {
        assertImported(deliver(lightChunk(760 + i)));
    }
    assertEquals("five chunks arrived", 5, pool().size(OrphanCategory.CHUNK));
    assertEquals("and none of them is selectable", linksBefore, pool().selectableSize());
    assertEquals("while the total counts them", linksBefore + 5, pool().totalSize());
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -o test -Dtest=IdleLinkMintingTest
```
预期：编译失败，`cannot find symbol: method selectableSize()`。

- [ ] **Step 3: 加 `selectableSize()`**

在 `ChainOrphanPool` 的 `totalSize()` 旁边：

```java
    /**
     * How many pooled orphans the packing walk can actually hand out.
     *
     * <p>{@link #totalSize} counts all four categories, but {@code selectBlocks} never offers a
     * {@link OrphanCategory#CHUNK} entry: a chunk is held for the block that will pay for it, not
     * for this node to reference. A budget taken from the total therefore describes work the
     * selection cannot do — which is how a node holding nothing but chunks came to build link
     * blocks with no references in them.
     */
    public int selectableSize() {
        return total - counts[OrphanCategory.CHUNK.ordinal()];
    }
```

- [ ] **Step 4: 改 `getOrphanLocked` 的预算**

`OrphanBlockStoreImpl.getOrphanLocked`：

```java
        long addNum;
        if (!isMain) {
            addNum = Math.min(pool.selectableSize(), num);
        } else {
            addNum = Math.min(pool.selectableSize() + pool.mainRefSize(), num);
        }
```

- [ ] **Step 5: 给 `getOrphanSize` 补观测契约 javadoc**

改完之后 `getOrphanSize()` 在生产路径上**不再有读者**，但它是 `BlockchainTest` 中二十余处断言精确数值的断言面。在它的 javadoc 里写明这一点，否则下一个人会把它当死代码删掉：

```java
    /**
     * How many orphans this node holds — all four categories, {@code mainRef} excluded exactly as
     * it always was.
     *
     * <p><b>This is an observation contract, not a budget.</b> Since the packing budget moved to
     * {@link ChainOrphanPool#selectableSize} it has no production reader left, but a large body of
     * existing tests asserts exact values from it. Its meaning must not be narrowed to match the
     * budget: doing so would silently change what those assertions assert.
     */
```

- [ ] **Step 6: 跑测试确认通过 + 全量**

```bash
mvn -o test -Dtest=IdleLinkMintingTest
mvn -o test
```
预期：新测试绿；全量仍绿。**`BlockchainTest` 中依赖 `getOrphanSize()` 的断言必须一个不动**——若有变红，说明 Step 4 改错了地方（改到了 `getOrphanSize()` 自身而不是它的调用点）。

- [ ] **Step 7: 变异验证**

`selectableSize()` 改成 `return total;`，确认新测试变红，记录文本，还原。

- [ ] **Step 8: 提交**

---

### Task 4: 无可引用就不建块，且不伪造时间戳

**Files:**
- Modify: `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java`（`getOrphanLocked`）
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`createLinkBlock`、`checkOrphan`）
- Modify: `src/test/java/io/xdag/chain/orphan/IdleLinkMintingTest.java`

两半必须一起落地：只改时间戳而仍然建块，会得到时间戳 0 的块，比今天更糟。

- [ ] **Step 1: 写失败测试**

```java
/**
 * The block this node used to build when the pool could hand it nothing: no references, and a
 * timestamp of 1 because the empty selection still ran the "newest reference plus one" line. Its
 * own import refused it as "Block's time is illegal" -- eight of them per flood in the Task 15
 * measurement, built and signed for nothing.
 */
@Test
public void aPoolHoldingOnlyChunksBuildsNoLinkBlockAtAll() {
    armTheOrphanPool();
    drainSelectableEntries();          // 见下
    for (int i = 0; i < 5; i++) {
        assertImported(deliver(lightChunk(770 + i)));
    }
    assertEquals("the pool holds only chunks", 0, pool().selectableSize());

    assertNull("with nothing to reference there is no link block to build",
            blockchain.createLinkBlock(null, false));
}

/** The latent half: an empty selection must not fabricate a timestamp for its caller. */
@Test
public void anEmptySelectionLeavesTheCallersTimestampAlone() {
    armTheOrphanPool();
    drainSelectableEntries();
    long[] sendTime = new long[2];
    sendTime[0] = XdagTime.getCurrentTimestamp();

    List<Address> refs = blockchain.getBlockFromOrphanPool(13, sendTime, false);

    assertTrue("nothing was selected", refs.isEmpty());
    assertEquals("so nothing may be claimed about when the newest reference was", 0, sendTime[1]);
}
```

`drainSelectableEntries()` 是本类的私有辅助：把 `armTheOrphanPool()` 之后池里残留的可服务条目取走，让 `selectableSize()` 归零。最直接的做法是反复调用 `blockchain.getBlockFromOrphanPool(16, new long[]{XdagTime.getCurrentTimestamp(), 0}, false)` 直到 `pool().selectableSize() == 0`，并对循环次数设上限后 `fail`，避免夹具变化时变成死循环。**先断言 `selectableSize() == 0` 再继续**，让前提自证。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -o test -Dtest=IdleLinkMintingTest
```
预期：第一个测试在 `assertNull` 处失败（今天会拿到一个块）；第二个在 `assertEquals(0, sendTime[1])` 处失败，**实际值为 1**——把这个 1 记下来，它就是缺陷本身。

- [ ] **Step 3: 空选择不再推进时间戳**

`OrphanBlockStoreImpl.getOrphanLocked`，把无条件的那一行改为有条件：

```java
        if (!selected.isEmpty()) {
            // Only meaningful when something was selected: this is "one past the newest reference".
            // Run unconditionally, an empty selection produced min(0 + 1, now) == 1, a timestamp
            // before the era, which this node's own import refuses as "Block's time is illegal".
            sendtime[1] = Math.min(sendtime[1] + 1, sendtime[0]);
        }
```

- [ ] **Step 4: 无引用不建 link 块**

`BlockchainImpl.createLinkBlock` 的 `else` 分支：

```java
        } else {
            List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime, false);
            if (CollectionUtils.isEmpty(orphans)) {
                // Nothing the packing walk will hand out -- an empty pool, or one holding only
                // chunks, which selectBlocks never offers. Building anyway produced a block with no
                // references and sendTime[1] == 1, refused by this node's own import.
                return null;
            }
            refs.addAll(orphans);
        }
```

- [ ] **Step 5: 调用方处理 null**

`checkOrphan` 的循环里 `createNewBlock(...)` 之后紧接 `linkBlock.signOut(...)`，null 会 NPE：

```java
        while (nblk-- > 0) {
            Block linkBlock = createNewBlock(null, null, false,
                    kernel.getConfig().getNodeSpec().getNodeTag(), XAmount.ZERO, null);
            if (linkBlock == null) {
                // Nothing to link. End the round rather than continue, on cost rather than on
                // impossibility: checkOrphan is not synchronized while tryToConnect(PreValidated)
                // is, so a net thread can pool a selectable orphan between two iterations.
                // Breaking defers at most one link block to the next checkState tick; continuing
                // would take the blockchain monitor once per iteration for up to 61 futile
                // selections.
                break;
            }
            linkBlock.signOut(kernel.getWallet().getDefKey());
            ...
        }
```

**并核对其它调用方**：

```bash
grep -rn "createLinkBlock\|createNewBlock(null, null, false" --include="*.java" src/main src/test
```

逐一判断谁会收到 null。特别注意两点，判断结论写进注释或报告：
1. `createNewBlock(pairs=null, to=null, mining=false, ...)` 是否只有 `checkOrphan` 会走到。
2. `createLinkBlock(remark, true)`（isRoll）走的是 `rollTxList` 而非孤块池——`rollTxList` 为空时 refs 也为空，新的 null 返回会不会波及回滚路径。若会，要么把 null 返回限定在 `!isRoll` 分支内，要么让回滚调用方也处理 null，**选一条并说明**。

- [ ] **Step 6: 跑测试确认通过 + 全量**

```bash
mvn -o test -Dtest=IdleLinkMintingTest
mvn -o test
```

- [ ] **Step 7: 变异验证**

- 去掉 `createLinkBlock` 的空检查 → 第一个测试红。
- 把 `if (!selected.isEmpty())` 改回无条件 → 第二个测试红，实际值应为 1。
- 去掉 `checkOrphan` 的 null 检查 → 应当有测试以 NPE 红（若没有，说明覆盖不足，补一个）。

三段失败文本都记录，逐一还原。

- [ ] **Step 8: 提交**

---

### Task 5: 更新 Task 15 的测量、文档，并确认基准复活

**Files:**
- Modify: `src/test/java/io/xdag/chain/orphan/ChunkFloodAdversarialTest.java`
- Modify: `docs/superpowers/plans/2026-09-19-xdag-chain-sp0b3-orphan-pool.md`

- [ ] **Step 1: 更新 Step 3b 的断言与措辞**

`aChunkFloodMintsLinkBlocksThatCanReferenceNothing` 今天断言的是**修复前的浪费**，其 javadoc 已预告这次变红的含义（「那不是测试坏了——那说明待办被处理了，文档应当跟上」）。现在照它说的做。

这一条的断言文字必须改，因为**理由变了**：

```java
        assertEquals("nor can any of it be imported -- with nothing to reference, what the node"
                + " mines is refused by its own import path: " + chunks, 0, chunks.minted);
```

改为表达「什么都没有被建造」，而不是「建造了但被拒绝」。对照臂（`links.minted > 0` 等三条）**保持不变**——它仍然是「节点在非分片洪泛下确实在挖矿」的证据，没有它，分片臂的零可以被一个干脆停止挖矿的节点满足。

同时把类 javadoc 和方法 javadoc 里描述「建好签好被自己拒掉」的段落改写为现在的事实，并指向本次的设计文档。

**另有一段不在上述措辞之列，容易漏掉，必须一并改**：`ChunkFloodAdversarialTest` 里标题为「Where the mismatch actually lives」的那一段，引用了 `Math.min(getOrphanSize(), num)` 并说「`getOrphanSize()` 是 `ChainOrphanPool.totalSize()`，四类全算」。**追加 Task 3 之后这个表达式已经不存在**（预算现在是 `Math.min(pool.selectableSize(), num)`），而该段结尾的「That is the defect's address, and it is where a fix would go」现在读起来像一个尚未关闭的待办，实际上它已经被关闭了。改写为过去式并指向 `ChainOrphanPool#selectableSize`。

- [ ] **Step 2: 跑这个类确认通过**

```bash
mvn -o test -Dtest=ChunkFloodAdversarialTest
```

- [ ] **Step 3: 确认基准复活——这是验收判据 2**

```bash
mvn -o -Dxdag.bench=true -Dxdag.bench.blocks=2000 -Dtest='io.xdag.chain.bench.ChainL1ImportBenchmarkTest' test
```

预期：通过。它今天在 `syncPath` 腿上以 `NO_PARENT` 失败。

**不得为了让它通过而修改夹具的 PoW 设置**——Task 1 的基线文档警告过，那会让全部历史基线失去可比性。若它仍然失败，**停下来报告**，不要动夹具。

（20000 块的完整 run A 属于 Task 16，本任务只需 2000 块这一次确认缺陷已消。）

- [ ] **Step 4: 更新计划的 Step 3b 待办**

`docs/superpowers/plans/2026-09-19-xdag-chain-sp0b3-orphan-pool.md` 的 Task 15 Step 3b：把「显著 → 交 Task 16 立待办」改为已修复，指向 `docs/superpowers/specs/2026-09-23-sp0b3-chunk-pooling-without-mining-design.md`，并保留原始测量数字（它们是修复前的事实，Task 16 的文档要用）。

**引符号不引行号**——计划既有的行号引用已经漂了约 47 行，而收尾文档的寿命比工作计划长。

- [ ] **Step 5: 全量 + 许可证**

```bash
mvn -o test
mvn -o -q license:check
```

- [ ] **Step 6: 提交**（`docs/` 需 `git add -f`）

---

## 验收标准

1. 不挖矿的节点（配置关闭、PoW 为 null 两种形态）能接收分片链，付费块正常导入。
2. `ChainL1ImportBenchmarkTest` 在 2000 块下恢复通过，且**未修改夹具的 PoW 设置**。
3. 纯分片洪泛下建造的 link 块数为 0；非分片洪泛的对照臂行为与修复前一致。
4. 不挖矿节点的分片块持有仍受三层配额与两纪元 TTL 约束，各有测试。
5. `getOrphanSize()` 的含义未被改动，`BlockchainTest` 中依赖它的断言一个未改。
6. **`nnoref` 的含义与它的 `/11` 消费者均未改动**（规格 D3）。核对方式：`git diff` 里 `checkOrphan` 的 `long nblk = xdagStats.nnoref / 11;` 一行不变，`isHeldByThisNode` 不变。
6. 全量套件绿，`license:check` 通过，`.conf` 未新增任何键。
7. 每条新断言均有变异验证记录（失败文本照录）。
