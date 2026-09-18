# SP0b-2 锁外预验证流水线与写后落盘 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `tryToConnect` 锁内每块约 458 µs 压到约 75 µs，使 L1 导入吞吐达到 ≥ 10,000 块/s：ECDSA 验签移到锁外线程池；块/`BlockInfo`/统计/孤块索引的写入改为有序写后落盘（读穿透待写缓存）；`checkNewMain()` 回走增量化；sums 内存写回。判定逻辑一字不改，崩溃后磁盘永远是写流前缀，SP0b-1 的启动门与 `--repairchain` 不改。

**Architecture:** 三段：(1) `IngestPipeline` 线程池并行做 `PreValidator`（哈希 + `verifiedKeys()` + 分类），按到达序号单线程提交 `tryToConnect(PreValidated)`；(2) `BlockchainImpl` 锁内用预验证的公钥替代 ECDSA，`checkNewMain` 用 `(top, p, i, chainVersion)` 缓存做 O(1) 增量；(3) `WriteBehindKVSource` 包住 INDEX/TIME/BLOCK/ORPHANIND，所有写追加到一条全局有序队列由单个写入线程合成 `batchWrite`，所有读先查待写 map；共识状态转移（`setMain`/`unSetMain`/`unWindMain`/修复/快照）先排空再以直写模式执行。规格：`docs/superpowers/specs/2026-09-18-xdag-chain-sp0b2-ingest-pipeline-design.md`。

**Tech Stack:** Java 21、Maven、JUnit 4、Mockito、RocksDB（`KVSource.batchWrite` 已有）、`ChainL1TestBase` 基座、`BenchWorkload`/`ChainL1ImportBenchmarkTest`（SP0b-1）。

---

## 0. 约定（每个任务都适用）

### 0.1 构建与测试命令

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
mvn -q -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test     # 单类；-q 隐藏汇总，看 target/surefire-reports/<Class>.txt
mvn -q license:check                                                     # 每次提交前
```

不要 `mvn clean`。基座测试每个约 5–20 s。surefire 的 `*` 不进子包：跑一个包树用 `io.xdag.chain.**.*Test`。

### 0.2 分支与提交

分支 `dev-dag-contract`（已检出，不切换）。提交信息**只用英文**，结尾一行尾注：

```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

`docs/` 全局被 gitignore：`git add -f docs/...`。`.claude/docs/` 不进 git。只按文件名 `git add`。

### 0.3 许可证头

每个新 Java 文件顶部必须有 MIT 头（复制 `src/main/java/io/xdag/chain/l1/ChainIds.java` 前 23 行）。

### 0.4 既成规则（不得违反）

- P1：预验证只能加速通过，不能提前拒绝；任何块经流水线与经 `tryToConnect(Block)` 得到相同 `ImportResult` 与库内容。
- 不往任何 `.conf` 加 `chain.*` 键；六个新键都是节点本地键，只在 `AbstractConfig.getSetting()` 里读。
- 共识状态转移（`setMain`/`unSetMain`/`unWindMain`/`repairUnwindTo`/`reconcileTipTo`/`initSnapshotJ`）在直写模式下与今天逐字节相同；SP0b-1 的全部测试不改、必须全绿。
- 不改 `ChainL1Processor`、`CHAIN_L1`、`AddressStore` 的语义；ADDRESS/CHAIN_L1/TXHISTORY 不包写后层。
- `KVSource` 接口在 `io.xdag.db.rocksdb.KVSource`（不是 `io.xdag.db`）。

### 0.5 基座与代码事实

- `ChainL1TestBase.setUpChain()`：`dbFactory = new RocksdbFactory(config)` → `BlockStoreImpl.forNode(dbFactory)`；`kernel.setTxHistoryStore(mock)`；`beforeBlockchain(kernel)` 钩子在 `new MockBlockchain(kernel)` 之前。`MockBlockchain.startCheckMain` 是空操作、`checkMain()` = 纯 `checkNewMain()`。`mineMain(refs)` 挖伪 PoW 主块（每次推进 `generateTime += 64000`），`mineMain(refs, false)` 允许非最佳。`confirm(block)` 挖空主块直到 BI_APPLIED。`releaseStores()`/`tearDownChain()` 已幂等。
- `SyncManager extends AbstractXdagLifecycle`：`doStart()/doStop()`；`validateAndAddNewBlock(bw)` 是 `synchronized`，先 `parse()`（已解析块上是空操作）再 `importBlock(bw)`，`importBlock` 做 `tryToConnect(new Block(new XdagBlock(bytes)))`（重解析的目的是给每次尝试一个干净的 `BlockInfo`——`tryToConnect` 在判 NO_PARENT 之前就会写 BI_EXTRA 标志）。`syncPopBlock` 用 `importBlock` 递归重导等父块的子块。`importBlock` 在 IMPORTED_* 且 `!isOld` 时先取 `kernel.getClient().getNode()` 再看 ttl。
- `BlockchainImpl.tryToConnect(Block)` 是 `synchronized`；锁内 `canUseInput(block)` 调 `block.verifiedKeys()`（ECDSA，纯函数）；分叉时在锁内调 `unWindMain(blockRef)` 与 `updateNewChain`；每次导入末尾 `blockStore.saveXdagStatus(xdagStats)`；`updateBlockFlag` 在 `block.isSaved` 时 `saveBlockInfo`。BI_MAIN_CHAIN 的写点只有 `updateBlockFlag`（`updateNewChain` 两处 `true`、`unWindMain` 一处 `false`）；BI_MAIN 在 `setMain`/`unSetMain`。
- `checkNewMain()`：从 top（`getBlockByHash(top,false)`）沿 `getMaxDiffLink(getBlockByHash(hashLow,true),true)` 回走到第一个 BI_MAIN；`p` = 最后一个（最深的）BI_MAIN_CHAIN 块，`i` = 候选数；条件 `p != null && (p.flags & BI_REF) != 0 && i > 1 && now ≥ p.ts + 2·1024` 则 `setMain(p)`。`getMaxDiffLink` 只读 `info.maxDiffLink`。
- `BlockStoreImpl.saveBlock`：`timeSource.put` + `blockSource.put` + `saveBlockSums`（4 个键，各 `getSums`（get + Java 反序列化 4 KB）+ `putSums`（序列化 + put））+ `saveBlockInfo`（`HASH_BLOCK_INFO` put + 高度键 put）。`saveXdagStatus` / `saveXdagTopStatus` 各一次 INDEX put（键 `SETTING_STATS` / `SETTING_TOP_STATUS`）。`loadSum` 只读 `getSums`。
- `RocksdbKVSource.batchWrite(puts, deletes)`：一个 WriteBatch，**先所有 put 后所有 delete**。`RocksdbFactory.getDB(name)` 缓存实例；`DatabaseFactory` 只有 `getDB(DatabaseName)` 与 `close()`。
- `Kernel` 类级 Lombok `@Getter @Setter`；`Kernel.testStart` 顺序：`dbFactory = new RocksdbFactory(config)` → `blockStore = BlockStoreImpl.forNode(dbFactory)` → address/orphan/chainL1 stores → `new BlockchainImpl(this)` → … → `syncMgr = new SyncManager(this); syncMgr.start()`。`testStop`：`syncMgr.stop()` → … → `blockchain.stopCheckMain()` → `blockStore.saveXdagStatus` → `chainL1Store.stop()` → `dbFactory.close()`。
- `ChainSpec` 是接口，`AbstractConfig implements … ChainSpec`；节点本地键的既有写法见 `chain.consistency.window`（`ChainSpec.DEFAULT_CONSISTENCY_WINDOW`、`AbstractConfig.chainConsistencyWindow`、`getSetting()` 里 `config.hasPath` + 校验抛 `IllegalArgumentException`）；`ChainSpecTest.withProperty(key, value, body)` 用系统属性覆盖。
- `Block.getHash()`/`getHashLow()` 缓存在 `info` 里；`Block.parse()` 已解析即返回；`verifiedKeys()` 返回验证通过的公钥列表，不缓存。`ChainBlockClassifier.classify(Block)` 返回 `Classified(kind, value, error)`。
- 基准 `ChainL1ImportBenchmarkTest`：`Measure(name, n, perSec, meanMicros, p50Micros, p95Micros, totalMillis)`，`timeImport(name, w, importer)`，`syncManager()` 给 kernel 装 mock 的 `ChannelManager`/`PeerClient` 并 `setBlockchain`；`baseline()` 里 direct/syncPath 交错三轮，之后 confirmed 三轮、calib、phase 重放，`report(...)` 输出 Markdown 与 JSON。

### 0.6 任务顺序与依赖

T1 写后存储层 → T2 sums 写回 → T3 配置键 → T4 直写模式接入（BlockchainImpl/Kernel）+ 崩溃前缀测试 → T5 `checkNewMain` 增量 → T6 `PreValidator`/`IngestPipeline` → T7 `tryToConnect(PreValidated)`/`SyncManager`/`Kernel` 接入 + 等价性测试 → T8 日志降级 + 基准 `pipeline` 行 + 基线文档 → T9 规格同步、全量回归、记忆与汇报。

---

### Task 1: `WriteBehindQueue` + `WriteBehindKVSource` + `WriteBehindFactory`

**Files:**
- Create: `src/main/java/io/xdag/db/PersistControl.java`
- Create: `src/main/java/io/xdag/db/rocksdb/WriteBehindQueue.java`
- Create: `src/main/java/io/xdag/db/rocksdb/WriteBehindKVSource.java`
- Create: `src/main/java/io/xdag/db/rocksdb/WriteBehindFactory.java`
- Test: `src/test/java/io/xdag/db/rocksdb/WriteBehindKVSourceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.db.rocksdb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WriteBehindKVSourceTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private Config config;
    private RocksdbKVSource raw;
    private WriteBehindQueue queue;
    private WriteBehindKVSource source;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Before
    public void setUp() throws Exception {
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder("store").getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder("backup").getAbsolutePath());
        raw = new RocksdbKVSource("WB");
        raw.setConfig(config);
        raw.init();
        queue = new WriteBehindQueue(8, 4, 1_000_000L); // manual: never started, flushed by drainOnce()/flushSync()
        source = new WriteBehindKVSource(raw, queue, 16);
    }

    @After
    public void tearDown() {
        queue.abandon();
        raw.close();
    }

    @Test
    public void putIsVisibleBeforeItIsWrittenAndAfter() {
        source.put(b("k"), b("v"));
        assertArrayEquals(b("v"), source.get(b("k")));
        assertNull("not on disk yet", raw.get(b("k")));
        assertTrue(queue.drainOnce());
        assertArrayEquals(b("v"), raw.get(b("k")));
        assertArrayEquals(b("v"), source.get(b("k")));
        assertEquals(0, queue.pending());
    }

    @Test
    public void deleteIsATombstoneUntilWritten() {
        raw.put(b("k"), b("old"));
        source.delete(b("k"));
        assertNull(source.get(b("k")));
        assertArrayEquals("still on disk until the writer runs", b("old"), raw.get(b("k")));
        queue.flushSync();
        assertNull(raw.get(b("k")));
        assertNull(source.get(b("k")));
    }

    @Test
    public void laterPutWinsOverEarlierDeleteOfTheSameKeyInOneGroup() {
        source.put(b("k"), b("v1"));
        source.delete(b("k"));
        source.put(b("k"), b("v2"));
        assertArrayEquals(b("v2"), source.get(b("k")));
        queue.flushSync();
        assertArrayEquals("batchWrite applies deletes after puts; the queue must split the group", b("v2"), raw.get(b("k")));
    }

    @Test
    public void overwriteWhileAGroupIsInFlightKeepsTheNewestValue() {
        source.put(b("k"), b("v1"));
        // Simulate the writer taking the group but a newer put landing before completion is recorded.
        source.put(b("k"), b("v2"));
        assertTrue(queue.drainOnce());
        assertArrayEquals(b("v2"), source.get(b("k")));
        assertArrayEquals(b("v2"), raw.get(b("k")));
    }

    @Test
    public void iterationFlushesFirst() {
        source.put(b("p1"), b("a"));
        source.put(b("p2"), b("b"));
        List<byte[]> keys = source.prefixKeyLookup(b("p"));
        assertEquals(2, keys.size());
        assertEquals(0, queue.pending());
        assertArrayEquals(b("a"), raw.get(b("p1")));
    }

    @Test
    public void bypassWritesStraightThroughAndUpdatesTheReadCache() {
        source.put(b("k"), b("queued"));
        queue.flushSync();
        queue.direct(() -> {
            source.put(b("k"), b("direct"));
            source.put(b("d"), b("x"));
            source.delete(b("d"));
        });
        assertEquals("nothing queued", 0, queue.pending());
        assertArrayEquals(b("direct"), raw.get(b("k")));
        assertArrayEquals(b("direct"), source.get(b("k")));
        assertNull(source.get(b("d")));
    }

    @Test
    public void directDrainsPendingWritesFirst() {
        source.put(b("k"), b("queued"));
        assertEquals(1, queue.pending());
        queue.direct(() -> assertArrayEquals("drained before the body runs", b("queued"), raw.get(b("k"))));
        assertEquals(0, queue.pending());
    }

    @Test
    public void directIsReentrant() {
        queue.direct(() -> queue.direct(() -> source.put(b("k"), b("v"))));
        assertArrayEquals(b("v"), raw.get(b("k")));
        assertFalse(queue.isBypass());
    }

    @Test
    public void batchWriteQueuesPutsThenDeletesInOrder() {
        raw.put(b("gone"), b("1"));
        source.batchWrite(List.of(Pair.of(b("a"), b("1")), Pair.of(b("b"), b("2"))), List.of(b("gone")));
        assertArrayEquals(b("1"), source.get(b("a")));
        assertNull(source.get(b("gone")));
        queue.flushSync();
        assertArrayEquals(b("2"), raw.get(b("b")));
        assertNull(raw.get(b("gone")));
    }

    @Test
    public void backpressureBlocksAtMaxPendingAndWakesAfterADrain() throws Exception {
        for (int i = 0; i < 8; i++) {
            source.put(b("k" + i), b("v"));
        }
        AtomicBoolean unblocked = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            source.put(b("k8"), b("v"));
            unblocked.set(true);
        });
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse("put must block while pending >= maxPending", unblocked.get());
        assertTrue(queue.drainOnce());
        t.join(5_000);
        assertTrue(unblocked.get());
        queue.flushSync();
        assertArrayEquals(b("v"), raw.get(b("k8")));
    }

    @Test
    public void aFailedWriteIsFatalAndNeverSilent() {
        raw.close(); // the delegate is dead: the next group write must fail
        source.put(b("k"), b("v"));
        assertFalse(queue.drainOnce());
        assertNotNull(queue.failure());
        assertThrows(IllegalStateException.class, () -> source.put(b("k2"), b("v")));
        assertThrows(IllegalStateException.class, queue::flushSync);
    }

    @Test
    public void flushSyncIsIdempotentAndCheapWhenEmpty() {
        queue.flushSync();
        queue.flushSync();
        assertEquals(0, queue.pending());
    }

    @Test
    public void readCacheServesRepeatedGetsWithoutTheDelegate() {
        raw.put(b("k"), b("disk"));
        assertArrayEquals(b("disk"), source.get(b("k")));
        raw.delete(b("k")); // behind the wrapper's back: only a cache hit can still return it
        assertArrayEquals("second get is served by the read cache", b("disk"), source.get(b("k")));
        source.delete(b("k"));
        assertNull("a delete evicts the cache entry", source.get(b("k")));
    }

    @Test
    public void everyReadMethodOfTheInterfaceIsCoveredByTheWrapper() {
        List<String> missing = new ArrayList<>();
        for (Method m : KVSource.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                Method impl = WriteBehindKVSource.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (Modifier.isAbstract(impl.getModifiers())) {
                    missing.add(m.getName());
                }
            } catch (NoSuchMethodException e) {
                missing.add(m.getName());
            }
        }
        assertTrue("KVSource methods not overridden by WriteBehindKVSource (would bypass the pending map): " + missing,
                missing.isEmpty());
    }

    @Test
    public void factoryWrapsOnlyTheFourImportDatabases() {
        RocksdbFactory rawFactory = new RocksdbFactory(config);
        WriteBehindQueue q = new WriteBehindQueue(8, 4, 1_000_000L);
        WriteBehindFactory factory = new WriteBehindFactory(rawFactory, q, 16);
        try {
            assertTrue(factory.getDB(DatabaseName.INDEX) instanceof WriteBehindKVSource);
            assertTrue(factory.getDB(DatabaseName.BLOCK) instanceof WriteBehindKVSource);
            assertTrue(factory.getDB(DatabaseName.TIME) instanceof WriteBehindKVSource);
            assertTrue(factory.getDB(DatabaseName.ORPHANIND) instanceof WriteBehindKVSource);
            assertFalse(factory.getDB(DatabaseName.ADDRESS) instanceof WriteBehindKVSource);
            assertFalse(factory.getDB(DatabaseName.CHAIN_L1) instanceof WriteBehindKVSource);
            assertFalse(factory.getDB(DatabaseName.TXHISTORY) instanceof WriteBehindKVSource);
            assertTrue("same instance on repeated getDB", factory.getDB(DatabaseName.INDEX) == factory.getDB(DatabaseName.INDEX));
        } finally {
            q.abandon();
            factory.close();
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.db.rocksdb.WriteBehindKVSourceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（三个类不存在）。

- [ ] **Step 3: 实现 `PersistControl`**

```java
package io.xdag.db;

import java.util.function.Supplier;

/**
 * What the blockchain needs from the persistence layer at a consensus state transition: drain
 * every queued write, then run a body whose writes go straight to the database. With no
 * write-behind layer installed ({@link #NONE}) both are the identity, which is the behaviour of
 * every node before SP0b-2 and of the offline tools that open the databases directly.
 */
public interface PersistControl {

    /** Blocks until every write queued before the call is on disk. Throws if the writer has failed. */
    void flushSync();

    /**
     * {@link #flushSync()} then runs {@code body} on the calling thread in direct-write mode:
     * its writes bypass the queue (and are therefore on disk when it returns, in program
     * order). Re-entrant: a body already in direct mode runs the nested body as is.
     */
    <T> T direct(Supplier<T> body);

    default void direct(Runnable body) {
        direct(() -> {
            body.run();
            return null;
        });
    }

    /** True while the calling thread is inside {@link #direct}. */
    boolean isBypass();

    PersistControl NONE = new PersistControl() {
        @Override
        public void flushSync() {
        }

        @Override
        public <T> T direct(Supplier<T> body) {
            return body.get();
        }

        @Override
        public boolean isBypass() {
            return true;
        }
    };
}
```

- [ ] **Step 4: 实现 `WriteBehindQueue`**

```java
package io.xdag.db.rocksdb;

import io.xdag.db.PersistControl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * One ordered write stream shared by every {@link WriteBehindKVSource} of a node, drained by a
 * single writer thread into per-database {@code batchWrite} calls.
 *
 * <p>Ordering: entries are written in enqueue order. A group taken by the writer is cut into runs
 * of consecutive entries of the same source; a run is also cut before a put whose key was deleted
 * earlier in the same run, because {@code batchWrite} applies its deletes after its puts. Runs are
 * written in order, so the databases always hold a prefix of the stream: a crash loses at most the
 * entries the writer has not yet written (bounded by {@code maxPending} plus one group).
 *
 * <p>Failure: any throwable out of a write marks the queue failed; every later put or flush throws
 * {@link IllegalStateException} with that cause. Nothing is dropped silently.
 *
 * <p>Manual mode: a queue that was never {@link #start()}ed is drained by {@link #flushSync()} on the
 * calling thread and by tests through {@link #drainOnce()}.
 */
@Slf4j
public final class WriteBehindQueue implements PersistControl {

    static final class Entry {
        final WriteBehindKVSource source; // null for a barrier
        final byte[] key;
        final byte[] value;               // null = delete
        final long version;
        final CountDownLatch barrier;

        Entry(WriteBehindKVSource source, byte[] key, byte[] value, long version) {
            this.source = source;
            this.key = key;
            this.value = value;
            this.version = version;
            this.barrier = null;
        }

        Entry(CountDownLatch barrier) {
            this.source = null;
            this.key = null;
            this.value = null;
            this.version = -1;
            this.barrier = barrier;
        }
    }

    private final int maxPending;
    private final int flushEntries;
    private final long flushNanos;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final AtomicLong versions = new AtomicLong();
    private final ThreadLocal<Integer> bypassDepth = ThreadLocal.withInitial(() -> 0);
    private volatile Throwable failure;
    private volatile boolean running;
    private Thread writer;
    private long enqueued;
    private long written;
    private long firstEnqueuedNanos;

    public WriteBehindQueue(int maxPending, int flushEntries, long flushMs) {
        if (maxPending < 1 || flushEntries < 1 || flushMs < 1) {
            throw new IllegalArgumentException("maxPending, flushEntries and flushMs must be positive");
        }
        this.maxPending = maxPending;
        this.flushEntries = flushEntries;
        this.flushNanos = TimeUnit.MILLISECONDS.toNanos(flushMs);
    }

    /** Starts the writer thread. Not a daemon: a JVM must not exit with queued writes. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        writer = new Thread(this::loop, "xdag-persist");
        writer.setDaemon(false);
        writer.start();
    }

    /** Flushes everything and stops the writer thread. Idempotent. */
    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running) {
                if (failure == null) {
                    drainAll();
                }
                return;
            }
            t = writer;
        }
        try {
            flushSync();
        } finally {
            lock.lock();
            try {
                running = false;
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
            try {
                t.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Test hook: drop every queued write without writing it (a simulated crash). */
    public void abandon() {
        lock.lock();
        try {
            for (Entry e : queue) {
                if (e.barrier != null) {
                    e.barrier.countDown();
                }
            }
            queue.clear();
            running = false;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    long nextVersion() {
        return versions.incrementAndGet();
    }

    public Throwable failure() {
        return failure;
    }

    public int pending() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Number of entries ever enqueued (barriers excluded). */
    public long enqueuedCount() {
        lock.lock();
        try {
            return enqueued;
        } finally {
            lock.unlock();
        }
    }

    /** Number of entries written to the databases so far (barriers excluded). */
    public long writtenCount() {
        lock.lock();
        try {
            return written;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isBypass() {
        return bypassDepth.get() > 0;
    }

    @Override
    public <T> T direct(Supplier<T> body) {
        if (isBypass()) {
            return body.get();
        }
        flushSync();
        bypassDepth.set(1);
        try {
            return body.get();
        } finally {
            bypassDepth.set(0);
        }
    }

    private void checkFailed() {
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("write-behind persistence failed; the node must stop", f);
        }
    }

    void enqueue(Entry e) {
        checkFailed();
        lock.lock();
        try {
            while (queue.size() >= maxPending && failure == null) {
                notFull.await();
            }
            checkFailed();
            if (queue.isEmpty()) {
                firstEnqueuedNanos = System.nanoTime();
            }
            queue.addLast(e);
            enqueued++;
            notEmpty.signalAll();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while queuing a write", ie);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flushSync() {
        checkFailed();
        if (isBypass()) {
            return;
        }
        CountDownLatch barrier = new CountDownLatch(1);
        boolean threaded;
        lock.lock();
        try {
            if (queue.isEmpty()) {
                return;
            }
            queue.addLast(new Entry(barrier));
            threaded = running;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        if (!threaded) {
            drainAll();
            checkFailed();
            return;
        }
        try {
            barrier.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the writer", ie);
        }
        checkFailed();
    }

    private void drainAll() {
        while (drainOnce()) {
            // until empty
        }
    }

    private void loop() {
        while (true) {
            lock.lock();
            try {
                while (queue.isEmpty() && running) {
                    notEmpty.await();
                }
                if (queue.isEmpty() && !running) {
                    return;
                }
                if (queue.size() < flushEntries && queue.peekLast().barrier == null) {
                    long waited = System.nanoTime() - firstEnqueuedNanos;
                    if (waited < flushNanos) {
                        notEmpty.awaitNanos(flushNanos - waited);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            if (!drainOnce() && failure != null) {
                return;
            }
        }
    }

    /**
     * Writes one group (up to {@code flushEntries} entries, or up to and including the first
     * barrier). Returns false when there was nothing to write or the write failed.
     */
    public boolean drainOnce() {
        List<Entry> group = new ArrayList<>();
        lock.lock();
        try {
            while (!queue.isEmpty() && group.size() < flushEntries) {
                Entry e = queue.pollFirst();
                group.add(e);
                if (e.barrier != null) {
                    break;
                }
            }
            if (!queue.isEmpty()) {
                firstEnqueuedNanos = System.nanoTime();
            }
        } finally {
            lock.unlock();
        }
        if (group.isEmpty()) {
            return false;
        }
        try {
            writeRuns(group);
        } catch (Throwable t) {
            failure = t;
            log.error("write-behind persistence failed after {} entries; the node must stop", written, t);
            lock.lock();
            try {
                running = false;
                notFull.signalAll();
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
            for (Entry e : group) {
                if (e.barrier != null) {
                    e.barrier.countDown();
                }
            }
            return false;
        }
        lock.lock();
        try {
            for (Entry e : group) {
                if (e.barrier == null) {
                    written++;
                }
            }
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
        for (Entry e : group) {
            if (e.barrier != null) {
                e.barrier.countDown();
            } else {
                e.source.completed(e.key, e.version);
            }
        }
        return true;
    }

    private static void writeRuns(List<Entry> group) {
        WriteBehindKVSource runSource = null;
        List<Pair<byte[], byte[]>> puts = new ArrayList<>();
        List<byte[]> deletes = new ArrayList<>();
        Set<Bytes> deletedInRun = new HashSet<>();
        for (Entry e : group) {
            if (e.barrier != null) {
                continue;
            }
            boolean sourceChanges = runSource != null && runSource != e.source;
            boolean putAfterDelete = e.value != null && deletedInRun.contains(Bytes.wrap(e.key));
            if (sourceChanges || putAfterDelete) {
                flushRun(runSource, puts, deletes);
                puts = new ArrayList<>();
                deletes = new ArrayList<>();
                deletedInRun = new HashSet<>();
            }
            runSource = e.source;
            if (e.value == null) {
                deletes.add(e.key);
                deletedInRun.add(Bytes.wrap(e.key));
            } else {
                puts.add(Pair.of(e.key, e.value));
            }
        }
        flushRun(runSource, puts, deletes);
    }

    private static void flushRun(WriteBehindKVSource source, List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        if (source == null || (puts.isEmpty() && deletes.isEmpty())) {
            return;
        }
        source.delegate().batchWrite(puts, deletes);
    }
}
```

- [ ] **Step 5: 实现 `WriteBehindKVSource`**

```java
package io.xdag.db.rocksdb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * A {@link KVSource} whose writes are queued on a shared {@link WriteBehindQueue} and whose reads
 * see them immediately through a pending map. Iteration-style reads flush the queue first; they
 * are not on the import hot path. An optional LRU read cache (INDEX only in practice) serves
 * repeated point reads; every write path, queued or direct, updates it, so it is never stale.
 */
public final class WriteBehindKVSource implements KVSource<byte[], byte[]> {

    private static final byte[] TOMBSTONE = new byte[0];

    private record Pending(byte[] value, long version) {
        boolean isDelete() {
            return value == TOMBSTONE;
        }
    }

    private final KVSource<byte[], byte[]> delegate;
    private final WriteBehindQueue queue;
    private final ConcurrentHashMap<Bytes, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Bytes, byte[]> readCache; // null when disabled

    public WriteBehindKVSource(KVSource<byte[], byte[]> delegate, WriteBehindQueue queue, int readCacheEntries) {
        this.delegate = delegate;
        this.queue = queue;
        this.readCache = readCacheEntries <= 0 ? null : Collections.synchronizedMap(
                new LinkedHashMap<>(readCacheEntries * 4 / 3 + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Bytes, byte[]> eldest) {
                        return size() > readCacheEntries;
                    }
                });
    }

    KVSource<byte[], byte[]> delegate() {
        return delegate;
    }

    private void cachePut(byte[] key, byte[] value) {
        if (readCache != null) {
            readCache.put(Bytes.wrap(key), value);
        }
    }

    private void cacheRemove(byte[] key) {
        if (readCache != null) {
            readCache.remove(Bytes.wrap(key));
        }
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (queue.isBypass()) {
            delegate.put(key, val);
            cachePut(key, val);
            return;
        }
        long version = queue.nextVersion();
        pending.put(Bytes.wrap(key), new Pending(val, version));
        cachePut(key, val);
        queue.enqueue(new WriteBehindQueue.Entry(this, key, val, version));
    }

    @Override
    public void delete(byte[] key) {
        if (queue.isBypass()) {
            delegate.delete(key);
            cacheRemove(key);
            return;
        }
        long version = queue.nextVersion();
        pending.put(Bytes.wrap(key), new Pending(TOMBSTONE, version));
        cacheRemove(key);
        queue.enqueue(new WriteBehindQueue.Entry(this, key, null, version));
    }

    @Override
    public byte[] get(byte[] key) {
        Bytes k = Bytes.wrap(key);
        Pending p = pending.get(k);
        if (p != null) {
            return p.isDelete() ? null : p.value();
        }
        if (readCache != null) {
            byte[] cached = readCache.get(k);
            if (cached != null) {
                return cached;
            }
        }
        byte[] value = delegate.get(key);
        if (value != null) {
            cachePut(key, value);
        }
        return value;
    }

    @Override
    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        if (queue.isBypass()) {
            delegate.batchWrite(puts, deletes);
            for (Pair<byte[], byte[]> p : puts) {
                cachePut(p.getKey(), p.getValue());
            }
            for (byte[] d : deletes) {
                cacheRemove(d);
            }
            return;
        }
        for (Pair<byte[], byte[]> p : puts) {
            put(p.getKey(), p.getValue());
        }
        for (byte[] d : deletes) {
            delete(d);
        }
    }

    /** Called by the queue once the write with this version is on disk. */
    void completed(byte[] key, long version) {
        pending.computeIfPresent(Bytes.wrap(key), (k, p) -> p.version() == version ? null : p);
    }

    @Override
    public Set<byte[]> keys() throws RuntimeException {
        queue.flushSync();
        return delegate.keys();
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] key) {
        queue.flushSync();
        return delegate.prefixKeyLookup(key);
    }

    @Override
    public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
        queue.flushSync();
        delegate.fetchPrefix(key, func);
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] key) {
        queue.flushSync();
        return delegate.prefixValueLookup(key);
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
        queue.flushSync();
        return delegate.prefixKeyAndValueLookup(key);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void setName(String name) {
        delegate.setName(name);
    }

    @Override
    public boolean isAlive() {
        return delegate.isAlive();
    }

    @Override
    public void init() {
        delegate.init();
    }

    @Override
    public void close() {
        if (queue.failure() == null) {
            queue.flushSync();
        }
        delegate.close();
    }

    @Override
    public void reset() {
        queue.flushSync();
        pending.clear();
        if (readCache != null) {
            readCache.clear();
        }
        delegate.reset();
    }
}
```

- [ ] **Step 6: 实现 `WriteBehindFactory`**

```java
package io.xdag.db.rocksdb;

import java.util.EnumMap;
import java.util.EnumSet;

/**
 * Wraps a node's {@link DatabaseFactory} so that the four databases the import path writes —
 * INDEX, BLOCK, TIME, ORPHANIND — go through one {@link WriteBehindQueue}. ADDRESS, CHAIN_L1 and
 * TXHISTORY are handed out untouched: they are written only on the apply path, which runs in
 * direct mode anyway. {@code BlockStoreImpl.forNode} and the orphan store need no change.
 */
public final class WriteBehindFactory implements DatabaseFactory {

    private static final EnumSet<DatabaseName> WRAPPED =
            EnumSet.of(DatabaseName.INDEX, DatabaseName.BLOCK, DatabaseName.TIME, DatabaseName.ORPHANIND);

    private final DatabaseFactory delegate;
    private final WriteBehindQueue queue;
    private final int readCacheEntries;
    private final EnumMap<DatabaseName, WriteBehindKVSource> wrapped = new EnumMap<>(DatabaseName.class);

    public WriteBehindFactory(DatabaseFactory delegate, WriteBehindQueue queue, int readCacheEntries) {
        this.delegate = delegate;
        this.queue = queue;
        this.readCacheEntries = readCacheEntries;
    }

    public WriteBehindQueue queue() {
        return queue;
    }

    @Override
    public synchronized KVSource<byte[], byte[]> getDB(DatabaseName name) {
        if (!WRAPPED.contains(name)) {
            return delegate.getDB(name);
        }
        return wrapped.computeIfAbsent(name, n -> new WriteBehindKVSource(delegate.getDB(n), queue,
                n == DatabaseName.INDEX ? readCacheEntries : 0));
    }

    @Override
    public void close() {
        queue.stop();
        delegate.close();
    }
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.db.rocksdb.WriteBehindKVSourceTest,io.xdag.db.rocksdb.BatchWriteTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `WriteBehindKVSourceTest` 15 个全绿（`aFailedWriteIsFatalAndNeverSilent` 依赖 `RocksdbKVSource.batchWrite` 在已关闭的库上抛出——若它只记日志不抛，改用一个在 `batchWrite` 里 `throw new RuntimeException("boom")` 的匿名 `KVSource` 委托来制造失败，并在报告里注明）。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/io/xdag/db/PersistControl.java src/main/java/io/xdag/db/rocksdb/WriteBehindQueue.java src/main/java/io/xdag/db/rocksdb/WriteBehindKVSource.java src/main/java/io/xdag/db/rocksdb/WriteBehindFactory.java src/test/java/io/xdag/db/rocksdb/WriteBehindKVSourceTest.java
git commit -m "Add an ordered write-behind layer over the import databases

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: sums 内存写回（`BlockStoreImpl`）

**Files:**
- Modify: `src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`（`getSums`/`putSums`/`saveBlockSums`/`saveXdagStatus`/`stop`，新增 `flushSums`）
- Test: `src/test/java/io/xdag/db/store/SumsCacheTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.db.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.xdag.BlockBuilder;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.FileUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SumsCacheTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private Config config;
    private RocksdbFactory factory;
    private BlockStoreImpl store;
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    @Before
    public void setUp() throws Exception {
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        factory = new RocksdbFactory(config);
        store = BlockStoreImpl.forNode(factory);
        store.start();
    }

    private List<Block> blocks(int n, long t0) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Block b = BlockBuilder.generateAddressBlock(config, key, t0 + i * 65536L * 3);
            out.add(new Block(new XdagBlock(b.toBytes())));
        }
        return out;
    }

    /** The definition: per block, per file key, sum += block sum and size += 512 at the byte index of that level. */
    private static Map<String, MutableBytes> expected(List<Block> blocks) {
        Map<String, MutableBytes> m = new HashMap<>();
        for (Block b : blocks) {
            long time = b.getTimestamp();
            List<String> names = FileUtils.getFileName(time);
            for (int i = 0; i < names.size(); i++) {
                MutableBytes sums = m.computeIfAbsent(names.get(i), k -> MutableBytes.create(4096));
                int index = (int) ((time >> (40 - 8 * i)) & 0xff);
                long sum = sums.getLong(16 * index, java.nio.ByteOrder.LITTLE_ENDIAN) + b.getXdagBlock().getSum();
                long size = sums.getLong(16 * index + 8, java.nio.ByteOrder.LITTLE_ENDIAN) + 512;
                sums.set(16 * index, Bytes.wrap(BytesUtils.longToBytes(sum, true)));
                sums.set(16 * index + 8, Bytes.wrap(BytesUtils.longToBytes(size, true)));
            }
        }
        return m;
    }

    private byte[] rawSums(String name) {
        KVSource<byte[], byte[]> index = factory.getDB(DatabaseName.INDEX);
        return index.get(BytesUtils.merge(BlockStore.SUMS_BLOCK_INFO, name.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sumsAreUpdatedInMemoryAndWrittenOnlyOnFlush() {
        List<Block> bs = blocks(5, 1_700_000_000_000L);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        Map<String, MutableBytes> want = expected(bs);
        for (Map.Entry<String, MutableBytes> e : want.entrySet()) {
            assertArrayEquals("cache reflects every update", e.getValue().toArray(), store.getSums(e.getKey()).toArray());
            assertNull("nothing written before the flush", rawSums(e.getKey()));
        }
        store.flushSums();
        for (Map.Entry<String, MutableBytes> e : want.entrySet()) {
            assertArrayEquals("flushed value equals the cache", e.getValue().toArray(), store.getSums(e.getKey()).toArray());
            assertNotNull("the index now holds the key", rawSums(e.getKey()));
            BlockStoreImpl fresh = BlockStoreImpl.forNode(factory);
            assertArrayEquals("a fresh store reads the flushed array back", e.getValue().toArray(), fresh.getSums(e.getKey()).toArray());
        }
    }

    @Test
    public void loadSumIsIdenticalBeforeAndAfterAReopen() {
        List<Block> bs = blocks(12, 1_700_000_000_000L);
        for (Block b : bs) {
            store.saveBlock(b);
        }
        long start = bs.get(0).getTimestamp() & 0xffffff000000L;
        MutableBytes fromCache = MutableBytes.create(4096);
        int rc1 = store.loadSum(start, start + (1L << 24), fromCache);
        store.saveXdagStatus(new io.xdag.core.XdagStats()); // flushes the dirty sums first
        BlockStoreImpl reopened = BlockStoreImpl.forNode(factory);
        reopened.start();
        MutableBytes fromDisk = MutableBytes.create(4096);
        int rc2 = reopened.loadSum(start, start + (1L << 24), fromDisk);
        assertEquals(rc1, rc2);
        assertArrayEquals(fromCache.toArray(), fromDisk.toArray());
    }

    @Test
    public void everyTwoHundredFiftySixBlocksFlushOnTheirOwn() {
        List<Block> bs = blocks(BlockStoreImpl.SUMS_FLUSH_EVERY, 1_700_000_000_000L);
        for (int i = 0; i < bs.size() - 1; i++) {
            store.saveBlock(bs.get(i));
        }
        String any = FileUtils.getFileName(bs.get(0).getTimestamp()).get(0);
        assertNull(rawSums(any));
        store.saveBlock(bs.get(bs.size() - 1));
        assertNotNull("the 256th block flushed", rawSums(any));
    }
}
```

（`BlockStoreImpl` 的 Kryo 序列化是私有方法，所以测试不反序列化原始字节，只断言键存在并用一个新的 `BlockStoreImpl` 实例读回。）

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.db.store.SumsCacheTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（`flushSums`、`SUMS_FLUSH_EVERY` 不存在）。

- [ ] **Step 3: 实现**

`BlockStoreImpl` 新增字段与方法（放在 `saveBlockSums` 附近）：

```java
    /** Sums are rewritten in memory per block and written back every this many saved blocks (and at every stats save). */
    public static final int SUMS_FLUSH_EVERY = 256;
    private final ConcurrentHashMap<String, MutableBytes> sumsCache = new ConcurrentHashMap<>();
    private final Set<String> dirtySums = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sumsSinceFlush = new AtomicInteger();

    /**
     * Writes every dirty sums array to the index. Called every {@link #SUMS_FLUSH_EVERY} saved
     * blocks, before every stats save and at stop, so a crash loses at most that many blocks'
     * contributions — the same window as the write-behind stream, and sums are advisory (they only
     * steer the legacy sync protocol's range requests).
     */
    public void flushSums() {
        for (String key : dirtySums) {
            MutableBytes sums = sumsCache.get(key);
            if (sums != null) {
                writeSums(key, sums);
            }
            dirtySums.remove(key);
        }
        sumsSinceFlush.set(0);
    }

    private void writeSums(String key, Bytes sums) {
        byte[] value = null;
        try {
            value = serialize(sums.toArray());
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(BytesUtils.merge(SUMS_BLOCK_INFO, key.getBytes(StandardCharsets.UTF_8)), value);
    }
```

改写三个既有方法：

```java
    @Override
    public void saveBlockSums(Block block) {
        long size = 512;
        long sum = block.getXdagBlock().getSum();
        long time = block.getTimestamp();
        List<String> filename = FileUtils.getFileName(time);
        for (int i = 0; i < filename.size(); i++) {
            updateSum(filename.get(i), sum, size, (time >> (40 - 8 * i)) & 0xff);
        }
        if (sumsSinceFlush.incrementAndGet() >= SUMS_FLUSH_EVERY) {
            flushSums();
        }
    }

    @Override
    public MutableBytes getSums(String key) {
        MutableBytes cached = sumsCache.get(key);
        if (cached != null) {
            return cached.mutableCopy();
        }
        byte[] value = indexSource.get(BytesUtils.merge(SUMS_BLOCK_INFO, key.getBytes(StandardCharsets.UTF_8)));
        if (value == null) {
            return null;
        }
        try {
            MutableBytes sums = MutableBytes.wrap((byte[]) deserialize(value, byte[].class));
            sumsCache.putIfAbsent(key, sums.mutableCopy());
            return sums;
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void putSums(String key, Bytes sums) {
        sumsCache.put(key, sums.mutableCopy());
        dirtySums.add(key);
    }
```

`saveXdagStatus` 开头加一行 `flushSums();`；`stop()` 开头加 `flushSums();`（`stop` 关库前落盘）。`updateSum` 与 `loadSum` 不改（它们只经 `getSums`/`putSums`）。需要的 import：`java.util.Set`、`java.util.concurrent.ConcurrentHashMap`、`java.util.concurrent.atomic.AtomicInteger`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.db.store.SumsCacheTest,io.xdag.db.store.BlockStoreImplTest,io.xdag.db.SnapshotStoreTest,io.xdag.consensus.SyncTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（`SyncTest` 走 `loadSum`）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java src/test/java/io/xdag/db/store/SumsCacheTest.java
git commit -m "Keep block sums in memory and write them back in batches

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: 六个节点本地配置键

**Files:**
- Modify: `src/main/java/io/xdag/config/spec/ChainSpec.java`
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java`
- Modify: `src/test/java/io/xdag/chain/l1/ChainL1ProcessorTest.java`（`TestSpec` 补六个方法）
- Test: `src/test/java/io/xdag/config/ChainSpecTest.java`

- [ ] **Step 1: 写失败测试（追加到 `ChainSpecTest`）**

```java
    @Test
    public void ingestAndPersistKeysHaveNodeLocalDefaults() {
        ChainSpec spec = new DevnetConfig().getChainSpec();
        assertEquals(Runtime.getRuntime().availableProcessors(), spec.getChainIngestThreads());
        assertEquals(4096, spec.getChainIngestQueue());
        assertEquals(4096, spec.getChainPersistMaxPending());
        assertEquals(20, spec.getChainPersistFlushMs());
        assertEquals(256, spec.getChainPersistFlushEntries());
        assertEquals(65536, spec.getChainPersistReadCache());
    }

    @Test
    public void ingestAndPersistKeysAreOverridableAndValidated() {
        assertEquals(0, (int) withProperty("chain.ingest.threads", "0",
                () -> new DevnetConfig().getChainSpec().getChainIngestThreads()));
        assertEquals(0, (int) withProperty("chain.persist.maxPending", "0",
                () -> new DevnetConfig().getChainSpec().getChainPersistMaxPending()));
        assertEquals(7, (int) withProperty("chain.persist.flushMs", "7",
                () -> new DevnetConfig().getChainSpec().getChainPersistFlushMs()));
        for (String[] bad : new String[][] {
                {"chain.ingest.threads", "-1"}, {"chain.ingest.queue", "0"}, {"chain.persist.maxPending", "-1"},
                {"chain.persist.flushMs", "0"}, {"chain.persist.flushEntries", "0"}, {"chain.persist.readCache", "-1"}}) {
            IllegalArgumentException e = assertThrows(bad[0], IllegalArgumentException.class,
                    () -> withProperty(bad[0], bad[1], DevnetConfig::new));
            assertTrue(e.getMessage(), e.getMessage().contains(bad[0]));
        }
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.config.ChainSpecTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（六个 getter 不存在）。

- [ ] **Step 3: 实现**

`ChainSpec` 在 `DEFAULT_CONSISTENCY_WINDOW` 之后加常量，在 `getChainConsistencyWindow()` 之后加 getter：

```java
    /** Node-local defaults for the SP0b-2 ingest pipeline and write-behind persistence. */
    int DEFAULT_INGEST_THREADS = Runtime.getRuntime().availableProcessors();
    int DEFAULT_INGEST_QUEUE = 4096;
    int DEFAULT_PERSIST_MAX_PENDING = 4096;
    int DEFAULT_PERSIST_FLUSH_MS = 20;
    int DEFAULT_PERSIST_FLUSH_ENTRIES = 256;
    int DEFAULT_PERSIST_READ_CACHE = 65536;

    /** Pre-validation threads ({@code chain.ingest.threads}); 0 disables the pipeline. Node-local. */
    int getChainIngestThreads();

    /** Bound of the pipeline's receive queue ({@code chain.ingest.queue}); positive. Node-local. */
    int getChainIngestQueue();

    /** Bound of the write-behind stream ({@code chain.persist.maxPending}); 0 disables write-behind. Node-local. */
    int getChainPersistMaxPending();

    /** Maximum delay before a queued write reaches the database ({@code chain.persist.flushMs}); positive. Node-local. */
    int getChainPersistFlushMs();

    /** Writes per group ({@code chain.persist.flushEntries}); positive. Node-local. */
    int getChainPersistFlushEntries();

    /** INDEX read cache entries ({@code chain.persist.readCache}); 0 disables it. Node-local. */
    int getChainPersistReadCache();
```

`AbstractConfig`：字段（`chainConsistencyWindow` 旁）：

```java
    protected int chainIngestThreads = ChainSpec.DEFAULT_INGEST_THREADS;
    protected int chainIngestQueue = ChainSpec.DEFAULT_INGEST_QUEUE;
    protected int chainPersistMaxPending = ChainSpec.DEFAULT_PERSIST_MAX_PENDING;
    protected int chainPersistFlushMs = ChainSpec.DEFAULT_PERSIST_FLUSH_MS;
    protected int chainPersistFlushEntries = ChainSpec.DEFAULT_PERSIST_FLUSH_ENTRIES;
    protected int chainPersistReadCache = ChainSpec.DEFAULT_PERSIST_READ_CACHE;
```

getter（`getChainConsistencyWindow()` 旁，六个同形）：

```java
    @Override
    public int getChainIngestThreads() {
        return chainIngestThreads;
    }

    @Override
    public int getChainIngestQueue() {
        return chainIngestQueue;
    }

    @Override
    public int getChainPersistMaxPending() {
        return chainPersistMaxPending;
    }

    @Override
    public int getChainPersistFlushMs() {
        return chainPersistFlushMs;
    }

    @Override
    public int getChainPersistFlushEntries() {
        return chainPersistFlushEntries;
    }

    @Override
    public int getChainPersistReadCache() {
        return chainPersistReadCache;
    }
```

`getSetting()` 里 `chain.consistency.window` 校验之后：

```java
        // Node-local (SP0b-2): ingest pipeline and write-behind persistence sizing. Never consensus.
        chainIngestThreads = readNodeLocalInt(config, "chain.ingest.threads", chainIngestThreads, 0);
        chainIngestQueue = readNodeLocalInt(config, "chain.ingest.queue", chainIngestQueue, 1);
        chainPersistMaxPending = readNodeLocalInt(config, "chain.persist.maxPending", chainPersistMaxPending, 0);
        chainPersistFlushMs = readNodeLocalInt(config, "chain.persist.flushMs", chainPersistFlushMs, 1);
        chainPersistFlushEntries = readNodeLocalInt(config, "chain.persist.flushEntries", chainPersistFlushEntries, 1);
        chainPersistReadCache = readNodeLocalInt(config, "chain.persist.readCache", chainPersistReadCache, 0);
```

以及一个私有助手（放在 `getSetting()` 之后）：

```java
    private static int readNodeLocalInt(com.typesafe.config.Config config, String key, int current, int min) {
        int value = config.hasPath(key) ? config.getInt(key) : current;
        if (value < min) {
            throw new IllegalArgumentException("Invalid " + key + ": " + value + " (must be >= " + min + ")");
        }
        return value;
    }
```

（`config` 变量的类型以 `getSetting()` 里已有的为准。）`ChainL1ProcessorTest.TestSpec` 补六个 `@Override`，各返回对应 `ChainSpec.DEFAULT_*`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.config.ChainSpecTest,io.xdag.chain.l1.ChainL1ProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `ChainSpecTest` 19、`ChainL1ProcessorTest` 17 全绿。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/config/spec/ChainSpec.java src/main/java/io/xdag/config/AbstractConfig.java src/test/java/io/xdag/config/ChainSpecTest.java src/test/java/io/xdag/chain/l1/ChainL1ProcessorTest.java
git commit -m "Add the node-local ingest and persistence settings

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: 直写模式接入（`BlockchainImpl` / `Kernel` / 基座）+ 崩溃前缀测试

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`persist` 字段；`setMain`/`unSetMain`/`unWindMain`/`repairUnwindTo`/`reconcileTipTo`/`initSnapshotJ` 包成 `persist.direct`）
- Modify: `src/main/java/io/xdag/Kernel.java`（`persist` 字段；`testStart` 装配写后工厂 + G3 try/catch）
- Modify: `src/test/java/io/xdag/chain/l1/ChainL1TestBase.java`（`wrapFactory` 钩子）
- Test: `src/test/java/io/xdag/db/store/WriteBehindPrefixTest.java`

- [ ] **Step 1: 基座钩子**

`ChainL1TestBase.setUpChain()` 里把 `dbFactory = new RocksdbFactory(config);` 改为：

```java
        dbFactory = wrapFactory(new RocksdbFactory(config));
        if (dbFactory instanceof WriteBehindFactory wb) {
            kernel.setPersist(wb.queue()); // BlockchainImpl reads it once, in its constructor
        }
```

并加钩子（放在 `beforeBlockchain` 旁）：

```java
    /**
     * Lets a subclass put the node's write-behind layer (SP0b-2) under the block, time, index and
     * orphan databases. The default is the raw factory: every SP0a/SP0b-1 test keeps running on
     * synchronous writes, which is also what {@code Kernel} does when {@code chain.persist.maxPending}
     * is 0.
     */
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        return raw;
    }
```

（`Kernel.setPersist` 由 Task 4 Step 3 的 Lombok 字段提供；先写基座会编译失败，按 Step 2 的顺序一起编译。）

- [ ] **Step 2: 写失败测试**

```java
package io.xdag.db.store;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.chain.repair.ChainConsistencyCheck;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.core.XdagStats;
import io.xdag.core.XdagTopStatus;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.db.rocksdb.WriteBehindQueue;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * P2 (SP0b-2): whatever point the write stream is cut at, the databases hold a prefix of it. The
 * queue is never started, so the test drains groups by hand, then "crashes" by abandoning the rest.
 */
public class WriteBehindPrefixTest extends ChainL1TestBase {

    private static final int BLOCKS = 120;
    private WriteBehindQueue queue;

    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        queue = new WriteBehindQueue(1_000_000, 16, 1_000_000L); // manual mode: 16 entries per drainOnce()
        return new WriteBehindFactory(raw, queue, 1024);
    }

    private List<Block> plainTransfers(int n) {
        ECKeyPair senderKey = ECKeyPair.generate();
        Bytes sender = senderKey.toAddress();
        addressStore.updateBalance(sender.toArray(), XAmount.of(10_000, XUnit.XDAG));
        Address from = new Address(BytesUtils.arrayToByte32(sender.toArray()), XDAG_FIELD_INPUT, true);
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Address to = new Address(BytesUtils.arrayToByte32(ECKeyPair.generate().toAddress().toArray()), XDAG_FIELD_OUTPUT, true);
            out.add(new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                    config, senderKey, txTime() + i, from, to, ONE_XDAG, UInt64.valueOf(i + 1)).toBytes())));
        }
        return out;
    }

    @Test
    public void theDatabasesAlwaysHoldAPrefixOfTheWriteStream() throws Exception {
        queue.flushSync(); // the fixture's own setup (address block, stats) is on disk
        long baselineBlocks = blockchain.getXdagStats().nblocks;
        List<Block> blocks = plainTransfers(BLOCKS);
        long[] stampAfter = new long[BLOCKS];
        long[] nblocksAfter = new long[BLOCKS];
        for (int i = 0; i < BLOCKS; i++) {
            assertImported(blocks.get(i));
            stampAfter[i] = queue.enqueuedCount();
            nblocksAfter[i] = blockchain.getXdagStats().nblocks;
        }
        assertTrue("the stream grew", stampAfter[BLOCKS - 1] > 0);

        // Drain a random number of groups, then crash.
        Random r = new Random(20260918L);
        int groups = r.nextInt((int) (stampAfter[BLOCKS - 1] / 16) + 1);
        for (int g = 0; g < groups; g++) {
            queue.drainOnce();
        }
        long written = queue.writtenCount();
        queue.abandon();
        releaseStores();

        RocksdbFactory raw = new RocksdbFactory(config);
        BlockStore store = BlockStoreImpl.forNode(raw);
        store.start();
        try {
            int full = 0;
            while (full < BLOCKS && stampAfter[full] <= written) {
                full++;
            }
            XdagStats stats = store.getXdagStatus();
            assertEquals("stats on disk equal the last fully written import (" + written + " writes, " + full + " imports)",
                    full == 0 ? baselineBlocks : nblocksAfter[full - 1], stats.nblocks);
            for (int i = 0; i < full; i++) {
                assertNotNull("import " + i + " is fully on disk", store.getBlockByHash(blocks.get(i).getHashLow(), false));
                assertNotNull(store.getBlockByHash(blocks.get(i).getHashLow(), true));
            }
            // Later imports may be partial: a raw block without its info is invisible and harmless.
            for (int i = full + 1; i < BLOCKS; i++) {
                assertNull("import " + i + " is not on disk", store.getBlockByHash(blocks.get(i).getHashLow(), false));
            }
            XdagTopStatus top = store.getXdagTopStatus();
            assertNotNull(top);
            assertNotNull("the persisted top points at a persisted block",
                    store.getBlockByHash(Bytes32.wrap(top.getTop()), false));
            ChainConsistencyCheck.Report report = ChainConsistencyCheck.run(store, stats, config.getChainSpec(),
                    config.getChainSpec().getChainConsistencyWindow());
            assertTrue(report.describe(), report.clean());
        } finally {
            raw.close();
        }
    }

    @Test
    public void aConsensusTransitionDrainsThenWritesDirectly() {
        List<Block> blocks = plainTransfers(3);
        for (Block b : blocks) {
            assertImported(b);
        }
        assertTrue("imports are queued", queue.pending() > 0);
        mineMain(List.of(hashLow(blocks.get(0)), hashLow(blocks.get(1)), hashLow(blocks.get(2))));
        confirm(blocks.get(2)); // setMain runs inside: drains first, then writes directly
        assertEquals("nothing left queued after a setMain (its own writes bypassed the queue)", 0, queue.pending());
        assertTrue(store().getLastCompletedMain() >= 1);
    }

    private BlockStore store() {
        return kernel.getBlockStore();
    }
}
```

（`ChainConsistencyCheck.run` 的签名与 `Report.describe()/clean()`、`BlockStore.getLastCompletedMain()` 按 SP0b-1 as-built 用；不一致处以代码为准并记入报告。）

- [ ] **Step 3: 实现 `Kernel`**

字段（Lombok 生成 `getPersist/setPersist`）：

```java
    /** Consensus-transition hook of the write-behind layer; NONE when write-behind is off. */
    protected PersistControl persist = PersistControl.NONE;
```

`testStart()`：`dbFactory = new RocksdbFactory(this.config);` 之后紧接着：

```java
        ChainSpec chainSpec = config.getChainSpec();
        if (chainSpec.getChainPersistMaxPending() > 0) {
            WriteBehindQueue persistQueue = new WriteBehindQueue(chainSpec.getChainPersistMaxPending(),
                    chainSpec.getChainPersistFlushEntries(), chainSpec.getChainPersistFlushMs());
            persistQueue.start();
            dbFactory = new WriteBehindFactory(dbFactory, persistQueue, chainSpec.getChainPersistReadCache());
            persist = persistQueue;
            log.info("Write-behind persistence on: maxPending={}, flushEntries={}, flushMs={}, readCache={}",
                    chainSpec.getChainPersistMaxPending(), chainSpec.getChainPersistFlushEntries(),
                    chainSpec.getChainPersistFlushMs(), chainSpec.getChainPersistReadCache());
        }
        try {
```

方法末尾（`isRunning.set(true)` 之后、方法结束前）：

```java
        } catch (RuntimeException | Error e) {
            // G3 (SP0a §12.2): a failure after the databases opened used to leak every store.
            log.error("kernel start failed; closing the stores", e);
            if (chainL1Store != null) {
                try { chainL1Store.stop(); } catch (RuntimeException ignored) { }
            }
            if (dbFactory != null) {
                try { dbFactory.close(); } catch (RuntimeException ignored) { }
            }
            throw e;
        }
```

`testStop()` 不改：`dbFactory.close()` → `WriteBehindFactory.close()` 先 `queue.stop()`（排空）再关库。

- [ ] **Step 4: 实现 `BlockchainImpl`**

字段与构造器（`this.orphanBlockStore = kernel.getOrphanBlockStore();` 之后）：

```java
    /** Consensus transitions drain the write-behind stream and then write directly (SP0b-2 §3.2). */
    private final PersistControl persist;
    …
        PersistControl control = kernel.getPersist();
        this.persist = control == null ? PersistControl.NONE : control;
```

六个转移各改成"同步包装 + `Direct` 本体"，本体是原方法体逐字不动（只改名）：

```java
    public synchronized void setMain(Block block) {
        persist.direct(() -> setMainDirect(block));
    }

    private void setMainDirect(Block block) {
        // ← 原 setMain 的整个方法体（含 synchronized (this) { … }）
    }

    public synchronized void unSetMain(Block block) {
        persist.direct(() -> unSetMainDirect(block));
    }

    public synchronized void unWindMain(Block block) {
        persist.direct(() -> unWindMainDirect(block));
    }

    public synchronized void repairUnwindTo(long height) {
        persist.direct(() -> repairUnwindToDirect(height));
    }

    public synchronized void reconcileTipTo(long height, Bytes32 hashLow) {
        persist.direct(() -> reconcileTipToDirect(height, hashLow));
    }

    public synchronized void initSnapshotJ() {
        persist.direct(this::initSnapshotJDirect);
    }
```

要点：包装方法必须 `synchronized`——排空要在持锁时做，否则排空与进入本体之间另一次导入可能又排了写，直写就会插到它们前面。`direct` 可重入（`checkNewMain → setMain`、`unWindMain → unSetMain`、`tryToConnect → unWindMain` 都在锁内嵌套）。原方法上的 `@Override`/Javadoc 跟着包装方法走。

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.db.store.WriteBehindPrefixTest,io.xdag.chain.**.*Test,io.xdag.cli.RepairChainCommandTest,io.xdag.cli.MakeSnapshotEndToEndTest,io.xdag.core.BlockchainTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿；SP0b-1 的全部基座测试在默认（不包）基座上不变；`WriteBehindPrefixTest` 2 个绿。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/core/BlockchainImpl.java src/main/java/io/xdag/Kernel.java src/test/java/io/xdag/chain/l1/ChainL1TestBase.java src/test/java/io/xdag/db/store/WriteBehindPrefixTest.java
git commit -m "Run consensus transitions in direct-write mode over the write-behind layer

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `checkNewMain()` 增量化

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`chainVersion`、候选缓存、`updateBlockFlag`、`checkNewMain`）
- Test: `src/test/java/io/xdag/core/CheckNewMainIncrementalTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.core;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;

import io.xdag.BlockBuilder;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * The incremental candidate cache of {@code checkNewMain()} must give exactly the (p, i) the full
 * walk gives, after every kind of step: a best block extending the top, an empty main block, a
 * setMain, a fork that overtakes and unwinds, a non-best block.
 */
public class CheckNewMainIncrementalTest extends ChainL1TestBase {

    private ECKeyPair senderKey;
    private Address from;
    private long nonce = 1;

    private Block transfer() {
        Address to = new Address(BytesUtils.arrayToByte32(ECKeyPair.generate().toAddress().toArray()), XDAG_FIELD_OUTPUT, true);
        return new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, senderKey, txTime() + nonce, from, to, ONE_XDAG, UInt64.valueOf(nonce++)).toBytes()));
    }

    private void assertSameCandidate(String step) {
        BlockchainImpl.MainCandidate full = blockchain.walkCandidate();
        BlockchainImpl.MainCandidate cached = blockchain.cachedCandidate();
        assertEquals(step + ": count", full.count(), cached.count());
        assertEquals(step + ": candidate", full.hashLow(), cached.hashLow());
    }

    @Test
    public void cachedCandidateEqualsTheFullWalkAfterEveryStep() {
        senderKey = ECKeyPair.generate();
        Bytes sender = senderKey.toAddress();
        addressStore.updateBalance(sender.toArray(), XAmount.of(10_000, XUnit.XDAG));
        from = new Address(BytesUtils.arrayToByte32(sender.toArray()), XDAG_FIELD_INPUT, true);
        Random r = new Random(7L);
        List<Block> mains = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mains.add(mineMain(List.of()));
            assertSameCandidate("warm-up main " + i);
        }
        for (int step = 0; step < 60; step++) {
            int pick = r.nextInt(10);
            if (pick < 5) {
                Block t = transfer();
                assertImported(t);                       // extends the top: the O(1) path
                assertSameCandidate("transfer " + step);
            } else if (pick < 8) {
                mains.add(mineMain(List.of()));          // may setMain inside checkMain(): version bump
                assertSameCandidate("main " + step);
            } else {
                // Fork below the last two mains and let the branch overtake: unwind + re-flag.
                Block forkPoint = mains.get(Math.max(0, mains.size() - 3));
                long t = generateTime - 64000L * 2;
                rewindTo(forkPoint, t);
                for (int k = 0; k < 6; k++) {
                    mineMain(List.of(), false);
                    assertSameCandidate("fork main " + step + "." + k);
                }
                mains.add(blockchain.getBlockByHash(org.apache.tuweni.bytes.Bytes32.wrap(
                        blockchain.getXdagTopStatus().getTop()), false));
            }
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.core.CheckNewMainIncrementalTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（`MainCandidate`、`walkCandidate`、`cachedCandidate` 不存在）。

- [ ] **Step 3: 实现**

`BlockchainImpl` 新增（放在 `checkNewMain` 之前）：

```java
    /** What checkNewMain decides on: the deepest BI_MAIN_CHAIN block above the last main, and how many there are. */
    public record MainCandidate(Bytes32 hashLow, int count) {
        static final MainCandidate NONE = new MainCandidate(null, 0);
    }

    /** Bumped on every BI_MAIN / BI_MAIN_CHAIN flag change and every top reconciliation; invalidates the candidate cache. */
    private long chainVersion;
    private byte[] candidateTop;
    private MainCandidate candidate = MainCandidate.NONE;
    private long candidateVersion = -1;

    /** The full walk from the top to the last main block, exactly as checkNewMain always did. Read-only. */
    MainCandidate walkCandidate() {
        if (xdagTopStatus.getTop() == null) {
            return MainCandidate.NONE;
        }
        Bytes32 p = null;
        int i = 0;
        for (Block block = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false); block != null
                && ((block.getInfo().flags & BI_MAIN) == 0);
             block = getMaxDiffLink(getBlockByHash(block.getHashLow(), true), true)) {
            if ((block.getInfo().flags & BI_MAIN_CHAIN) != 0) {
                p = Bytes32.wrap(block.getHashLow());
                ++i;
            }
        }
        return new MainCandidate(p, i);
    }

    /**
     * The same answer in O(1) when the top merely grew by one block since the last call: the walk
     * from the new top is the new top followed by the walk from the old top, so the count grows by
     * one if the new top is BI_MAIN_CHAIN and the deepest candidate is unchanged unless there was
     * none. Anything else — a fork, a flag change (chainVersion), a main block at the top — falls
     * back to the full walk. Updates the cache.
     */
    MainCandidate cachedCandidate() {
        byte[] top = xdagTopStatus.getTop();
        if (top == null) {
            candidateTop = null;
            candidate = MainCandidate.NONE;
            candidateVersion = chainVersion;
            return candidate;
        }
        MainCandidate result;
        if (candidateVersion == chainVersion && candidateTop != null && Arrays.equals(top, candidateTop)) {
            result = candidate;
        } else {
            Block topBlock = getBlockByHash(Bytes32.wrap(top), false);
            byte[] link = topBlock == null ? null : topBlock.getInfo().getMaxDiffLink();
            if (candidateVersion == chainVersion && candidateTop != null && topBlock != null
                    && (topBlock.getInfo().flags & BI_MAIN) == 0 && link != null && Arrays.equals(link, candidateTop)) {
                boolean onChain = (topBlock.getInfo().flags & BI_MAIN_CHAIN) != 0;
                result = new MainCandidate(candidate.hashLow() != null ? candidate.hashLow()
                        : (onChain ? Bytes32.wrap(topBlock.getHashLow()) : null), candidate.count() + (onChain ? 1 : 0));
            } else {
                result = walkCandidate();
            }
        }
        candidateTop = top.clone();
        candidate = result;
        candidateVersion = chainVersion;
        return result;
    }
```

`checkNewMain()` 改为：

```java
    public synchronized void checkNewMain() {
        MainCandidate c = cachedCandidate();
        if (c.hashLow() == null || c.count() <= 1) {
            return;
        }
        // Fresh flags: BI_REF may have been set on the candidate by a later block.
        Block p = getBlockByHash(c.hashLow(), true);
        long ct = XdagTime.getCurrentTimestamp();
        if (p != null && ((p.getInfo().flags & BI_REF) != 0) && ct >= p.getTimestamp() + 2 * 1024) {
            setMain(p);
        }
    }
```

`updateBlockFlag` 开头（`if (block == null) return;` 之后）加：

```java
        if (flag == BI_MAIN || flag == BI_MAIN_CHAIN) {
            chainVersion++;
        }
```

需要 `import java.util.Arrays;`。`reconcileTipToDirect` 与 `repairUnwindToDirect` 里 `xdagTopStatus.setTop(...)` 之后各加 `chainVersion++;`（修复路径不经 `updateBlockFlag` 也可能改 top）。`checkNewMain` 原来对 `p` 用的是回走时加载的对象；现在改为决策前重新按哈希加载（raw），只会更新，不会更旧。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.core.CheckNewMainIncrementalTest,io.xdag.chain.l1.ChainL1ReorgPropertyTest,io.xdag.chain.l1.ChainL1UnwindTest,io.xdag.chain.repair.*Test,io.xdag.core.BlockchainTest,io.xdag.core.RewardTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（属性测试默认 2 个种子；再手动跑一次 `-Dxdag.reorg.full=true`）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/core/CheckNewMainIncrementalTest.java
git commit -m "Make the main-block candidate walk incremental

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: `PreValidated` / `PreValidator` / `IngestPipeline`

**Files:**
- Create: `src/main/java/io/xdag/chain/ingest/PreValidated.java`
- Create: `src/main/java/io/xdag/chain/ingest/PreValidator.java`
- Create: `src/main/java/io/xdag/chain/ingest/IngestPipeline.java`
- Test: `src/test/java/io/xdag/chain/ingest/IngestOrderingTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class IngestOrderingTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    private Block block(int i) {
        Block b = BlockBuilder.generateAddressBlock(config, key, 1_700_000_000_000L + i * 65536L);
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void commitOrderIsArrivalOrderEvenWhenPreValidationFinishesOutOfOrder() throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        AtomicInteger slow = new AtomicInteger();
        IngestPipeline pipeline = new IngestPipeline(4, 64, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        }, pv -> {
            // every third block pre-validates slowly, so completion order != arrival order
            if (pv.seq() % 3 == 0 && slow.getAndIncrement() < 20) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) { }
            }
        });
        pipeline.start();
        try {
            for (int i = 0; i < 60; i++) {
                pipeline.submit(new BlockWrapper(block(i), 0));
            }
            assertTrue(pipeline.awaitIdle(30, TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < 60; i++) {
            expected.add(i);
        }
        assertEquals(expected, committed);
    }

    @Test
    public void preValidationCarriesKeysAndNeverRejects() {
        PreValidated pv = PreValidator.inline(block(1));
        assertNull(pv.error());
        assertNotNull(pv.hashLow());
        assertEquals("an address block is signed by its own key", 1, pv.keys().size());
        assertTrue(pv.hasKeys());
    }

    @Test
    public void aThrowingPreValidationIsCarriedNotDropped() throws Exception {
        List<PreValidated> committed = Collections.synchronizedList(new ArrayList<>());
        IngestPipeline pipeline = new IngestPipeline(2, 8, pv -> {
            committed.add(pv);
            return ImportResult.INVALID_BLOCK;
        }, pv -> {
            if (pv.seq() == 1) {
                throw new IllegalStateException("boom");
            }
        });
        pipeline.start();
        try {
            for (int i = 0; i < 3; i++) {
                pipeline.submit(new BlockWrapper(block(i), 0));
            }
            assertTrue(pipeline.awaitIdle(10, TimeUnit.SECONDS));
        } finally {
            pipeline.stop();
        }
        assertEquals(3, committed.size());
        assertNotNull("the failure travels with the block", committed.get(1).error());
        assertNull(committed.get(1).keys());
        assertNull(committed.get(0).error());
    }

    @Test
    public void stopCommitsEverythingAlreadySubmitted() throws Exception {
        List<Long> committed = new CopyOnWriteArrayList<>();
        IngestPipeline pipeline = new IngestPipeline(2, 8, pv -> {
            committed.add(pv.seq());
            return ImportResult.IMPORTED_BEST;
        }, pv -> { });
        pipeline.start();
        for (int i = 0; i < 20; i++) {
            pipeline.submit(new BlockWrapper(block(i), 0));
        }
        pipeline.stop();
        assertEquals(20, committed.size());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ingest.IngestOrderingTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败。

- [ ] **Step 3: 实现 `PreValidated`**

```java
package io.xdag.chain.ingest;

import io.xdag.chain.ext.Classified;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.crypto.keys.PublicKey;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Facts about a block computed outside the blockchain lock: its hash, the public keys that verify
 * its signatures ({@code Block.verifiedKeys()}, a pure function of the bytes), and its chain ext
 * classification. Never a verdict: a block whose pre-validation threw travels with {@code error}
 * set and {@code keys == null}, and the lock recomputes what it needs. {@code seq} is the arrival
 * order the pipeline commits in; -1 for an inline computation.
 */
public record PreValidated(long seq, BlockWrapper wrapper, Block block, Bytes32 hashLow,
                           List<PublicKey> keys, Classified classified, Throwable error) {

    public boolean hasKeys() {
        return error == null && keys != null;
    }
}
```

- [ ] **Step 4: 实现 `PreValidator`**

```java
package io.xdag.chain.ingest;

import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.Classified;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.crypto.keys.PublicKey;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/** The pure part of import: hash, signature keys, ext classification. No store, no lock. */
public final class PreValidator {

    private PreValidator() {
    }

    /** For callers that hand a block straight to the lock: same facts, computed on the calling thread; no classification. */
    public static PreValidated inline(Block block) {
        return compute(-1, new BlockWrapper(block, 0), false);
    }

    static PreValidated compute(long seq, BlockWrapper wrapper, boolean classify) {
        Block block = wrapper.getBlock();
        try {
            block.parse();
            Bytes32 hashLow = Bytes32.wrap(block.getHashLow());
            List<PublicKey> keys = List.copyOf(block.verifiedKeys());
            Classified classified = classify ? ChainBlockClassifier.classify(block) : null;
            return new PreValidated(seq, wrapper, block, hashLow, keys, classified, null);
        } catch (RuntimeException e) {
            return new PreValidated(seq, wrapper, block, null, null, null, e);
        }
    }
}
```

- [ ] **Step 5: 实现 `IngestPipeline`**

```java
package io.xdag.chain.ingest;

import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Parallel pre-validation, in-order commit. {@link #submit} assigns the next sequence number and
 * hands the block to the pool; results are re-ordered by sequence and one commit thread calls the
 * committer strictly in arrival order (P5). Backpressure: {@code queueCapacity} blocks may be in
 * flight; a further {@link #submit} blocks the caller, which is what the network thread did before
 * SP0b-2 when it called the synchronized import directly.
 */
@Slf4j
public final class IngestPipeline {

    /** The lock-side import; must not throw for a block that would merely be rejected. */
    public interface Committer {
        ImportResult commit(PreValidated pv);
    }

    private final ExecutorService pool;
    private final Thread commitThread;
    private final Semaphore slots;
    private final Committer committer;
    private final Consumer<PreValidated> beforeReady; // test hook, may throw
    private final AtomicLong nextSeq = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition headReady = lock.newCondition();
    private final PriorityQueue<PreValidated> ready = new PriorityQueue<>((a, b) -> Long.compare(a.seq(), b.seq()));
    private long nextToCommit;
    private volatile boolean running;

    public IngestPipeline(int threads, int queueCapacity, Committer committer) {
        this(threads, queueCapacity, committer, pv -> { });
    }

    IngestPipeline(int threads, int queueCapacity, Committer committer, Consumer<PreValidated> beforeReady) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("threads and queueCapacity must be positive");
        }
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "xdag-ingest");
            t.setDaemon(true);
            return t;
        });
        this.slots = new Semaphore(queueCapacity);
        this.committer = committer;
        this.beforeReady = beforeReady;
        this.commitThread = new Thread(this::commitLoop, "xdag-ingest-commit");
        this.commitThread.setDaemon(false);
    }

    public void start() {
        running = true;
        commitThread.start();
    }

    /** Queues a block; blocks the caller while {@code queueCapacity} blocks are in flight. */
    public void submit(BlockWrapper wrapper) {
        if (!running) {
            throw new IllegalStateException("ingest pipeline is not running");
        }
        slots.acquireUninterruptibly();
        long seq = nextSeq.getAndIncrement();
        pool.execute(() -> {
            PreValidated pv = PreValidator.compute(seq, wrapper, true);
            try {
                beforeReady.accept(pv);
            } catch (RuntimeException e) {
                pv = new PreValidated(seq, wrapper, wrapper.getBlock(), null, null, null, e);
            }
            lock.lock();
            try {
                ready.add(pv);
                headReady.signalAll();
            } finally {
                lock.unlock();
            }
        });
    }

    private void commitLoop() {
        while (true) {
            PreValidated head;
            lock.lock();
            try {
                while (ready.isEmpty() || ready.peek().seq() != nextToCommit) {
                    if (!running && nextToCommit >= nextSeq.get()) {
                        return;
                    }
                    try {
                        headReady.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                head = ready.poll();
                nextToCommit++;
            } finally {
                lock.unlock();
            }
            try {
                committer.commit(head);
            } catch (Throwable t) {
                log.error("ingest commit failed for block {}", head.hashLow(), t);
            } finally {
                slots.release();
                lock.lock();
                try {
                    headReady.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /** True once every submitted block has been committed (or after the timeout, false). */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        lock.lock();
        try {
            while (nextToCommit < nextSeq.get()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                headReady.awaitNanos(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)));
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public long submitted() {
        return nextSeq.get();
    }

    public long committed() {
        lock.lock();
        try {
            return nextToCommit;
        } finally {
            lock.unlock();
        }
    }

    /** Stops accepting, commits everything already submitted, then stops the threads. */
    public void stop() {
        running = false;
        try {
            awaitIdle(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lock.lock();
        try {
            headReady.signalAll();
        } finally {
            lock.unlock();
        }
        pool.shutdown();
        try {
            commitThread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ingest.IngestOrderingTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 4 个绿。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/io/xdag/chain/ingest/PreValidated.java src/main/java/io/xdag/chain/ingest/PreValidator.java src/main/java/io/xdag/chain/ingest/IngestPipeline.java src/test/java/io/xdag/chain/ingest/IngestOrderingTest.java
git commit -m "Add the parallel pre-validation pipeline with in-order commit

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: `tryToConnect(PreValidated)`、`SyncManager` 与 `Kernel` 接入 + 等价性测试

**Files:**
- Modify: `src/main/java/io/xdag/core/Blockchain.java`（默认方法 `tryToConnect(PreValidated)`）
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（重载；`canUseInput(Block, List<PublicKey>)`）
- Modify: `src/main/java/io/xdag/consensus/SyncManager.java`（`pipeline`、`submitBlock`、`importPreValidated`、`finishImport`、start/stop）
- Modify: `src/main/java/io/xdag/net/XdagP2pHandler.java`（两处 `validateAndAddNewBlock` → `submitBlock`）
- Test: `src/test/java/io/xdag/chain/ingest/IngestEquivalenceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.bench.BenchWorkload;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.WriteBehindFactory;
import io.xdag.db.rocksdb.WriteBehindQueue;
import io.xdag.net.ChannelManager;
import io.xdag.net.PeerClient;
import io.xdag.net.node.Node;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * P1 (SP0b-2): the same block sequence through the pipeline and through the synchronous path
 * gives the same ImportResult per block and byte-identical INDEX/TIME/BLOCK/ORPHANIND, stats and
 * top. Both runs use the write-behind layer (started, real thread) and drain before comparing.
 */
public class IngestEquivalenceTest extends ChainL1TestBase {

    private static final int BLOCKS = 400;
    private WriteBehindQueue queue;

    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        queue = new WriteBehindQueue(4096, 64, 20);
        queue.start();
        return new WriteBehindFactory(raw, queue, 4096);
    }

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.setTxHistoryStore(null);
    }

    private record Prepared(Bytes chainId, Bytes contract, Bytes32 codeHash) {
    }

    private Prepared prepareChain() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        Bytes wasm = BenchWorkload.payload(600, 7);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm, BenchWorkload.payload(10, 8));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        return new Prepared(ChainIds.chainIdOf(deploy.block().getHash()),
                ChainIds.contractIdOf(deploy.block().getHash()), HashUtils.sha256(wasm));
    }

    /** Import order: chunks tail-first then the paying block, with one deliberate NO_PARENT pair. */
    private List<Block> order(BenchWorkload w) {
        List<Block> out = new ArrayList<>();
        boolean swapped = false;
        for (BenchWorkload.Item item : w.items()) {
            List<Block> chunks = item.chunks();
            if (!swapped && chunks.size() > 1) {
                out.add(item.block());            // paying block before its chain: NO_PARENT, then re-import
                for (int c = chunks.size() - 1; c >= 0; c--) {
                    out.add(chunks.get(c));
                }
                swapped = true;
                continue;
            }
            for (int c = chunks.size() - 1; c >= 0; c--) {
                out.add(chunks.get(c));
            }
            out.add(item.block());
        }
        return out;
    }

    private BenchWorkload workload(Prepared p) {
        BenchWorkload w = new BenchWorkload(config, 20260918L, 8, BLOCKS, new int[] {60, 25, 10, 5}, txTime(),
                p.chainId(), p.contract(), p.codeHash());
        for (ECKeyPair k : w.senders()) {
            addressStore.updateBalance(k.toAddress().toArray(), XAmount.of(10_000, XUnit.XDAG));
        }
        return w;
    }

    private SyncManager syncManager() {
        ChannelManager channels = mock(ChannelManager.class);
        when(channels.getActiveChannels()).thenReturn(List.of());
        kernel.setChannelMgr(channels);
        PeerClient client = mock(PeerClient.class);
        when(client.getNode()).thenReturn(new Node("127.0.0.1", 8001));
        kernel.setClient(client);
        kernel.setBlockchain(blockchain);
        return new SyncManager(kernel);
    }

    private TreeMap<Bytes, Bytes> dump(DatabaseName name) {
        KVSource<byte[], byte[]> db = dbFactory.getDB(name);
        TreeMap<Bytes, Bytes> m = new TreeMap<>();
        for (byte[] k : db.keys()) {
            m.put(Bytes.wrap(k), Bytes.wrap(db.get(k)));
        }
        return m;
    }

    private record Outcome(List<ImportResult> results, TreeMap<Bytes, Bytes> index, TreeMap<Bytes, Bytes> time,
                           TreeMap<Bytes, Bytes> block, TreeMap<Bytes, Bytes> orphan, long nblocks, Bytes top) {
    }

    private Outcome snapshot(List<ImportResult> results) {
        queue.flushSync();
        return new Outcome(results, dump(DatabaseName.INDEX), dump(DatabaseName.TIME), dump(DatabaseName.BLOCK),
                dump(DatabaseName.ORPHANIND), blockchain.getXdagStats().nblocks,
                Bytes.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    private void freshFixture(long fixtureStart) throws Exception {
        tearDownChain();
        FileUtils.deleteDirectory(new File(root.getRoot(), "node"));
        generateTime = fixtureStart;
        setUpChain();
    }

    @Test
    public void pipelineAndSynchronousPathAgreeByteForByte() throws Exception {
        long fixtureStart = generateTime;
        // Run A: pipeline (parallel pre-validation, in-order commit)
        Prepared p = prepareChain();
        BenchWorkload w = workload(p);
        List<Block> blocks = order(w);
        SyncManager sync = syncManager();
        List<ImportResult> resultsA = Collections.synchronizedList(new ArrayList<>());
        IngestPipeline pipeline = new IngestPipeline(4, 256, pv -> {
            ImportResult r = sync.importPreValidated(pv);
            resultsA.add(r);
            return r;
        });
        pipeline.start();
        for (Block b : blocks) {
            pipeline.submit(new BlockWrapper(b, 0));
        }
        assertTrue(pipeline.awaitIdle(120, TimeUnit.SECONDS));
        pipeline.stop();
        Outcome a = snapshot(resultsA);

        // Run B: the synchronous path on a fresh fixture with the same bytes
        freshFixture(fixtureStart);
        Prepared p2 = prepareChain();
        assertEquals("same chain, same bytes", p.chainId(), p2.chainId());
        BenchWorkload w2 = workload(p2);
        List<Block> blocks2 = order(w2);
        SyncManager sync2 = syncManager();
        List<ImportResult> resultsB = new ArrayList<>();
        for (Block b : blocks2) {
            resultsB.add(sync2.validateAndAddNewBlock(new BlockWrapper(b, 0)));
        }
        Outcome b = snapshot(resultsB);

        assertEquals("ImportResult sequence", b.results(), a.results());
        assertTrue("the NO_PARENT pair was exercised", a.results().contains(ImportResult.NO_PARENT));
        assertEquals("INDEX", b.index(), a.index());
        assertEquals("TIME", b.time(), a.time());
        assertEquals("BLOCK", b.block(), a.block());
        assertEquals("ORPHANIND", b.orphan(), a.orphan());
        assertEquals(b.nblocks(), a.nblocks());
        assertEquals(b.top(), a.top());
    }
}
```

（`BenchWorkload` 的构造签名与 `payload` 按 SP0b-1 as-built：`(Config, long seed, int senderCount, int blocks, int[] mix, long txTime, Bytes chainId, Bytes contract, Bytes32 sharedCodeHash)`；不一致处以代码为准。若 INDEX 里含无法逐字节比较的键（例如带墙钟的统计），把它从比较里剔除并在报告与规格里写明是哪一个。）

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ingest.IngestEquivalenceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（`importPreValidated` 不存在）。

- [ ] **Step 3: 实现 `Blockchain` 与 `BlockchainImpl`**

`Blockchain` 接口在 `tryToConnect(Block)` 之后加：

```java
    /** Import with facts computed outside the lock (SP0b-2); the default recomputes them inside. */
    default ImportResult tryToConnect(io.xdag.chain.ingest.PreValidated pv) {
        return tryToConnect(pv.block());
    }
```

`BlockchainImpl`：原 `tryToConnect(Block)` 的方法体整个搬进重载，`block` 局部变量从 `pv.block()` 取：

```java
    @Override
    public ImportResult tryToConnect(Block block) {
        return tryToConnect(PreValidator.inline(block));
    }

    @Override
    public synchronized ImportResult tryToConnect(PreValidated pv) {
        Block block = pv.block();
        // ← 原方法体，唯一改动：canUseInput(block) → canUseInput(block, pv.hasKeys() ? pv.keys() : null)
    }
```

`canUseInput`：

```java
    public boolean canUseInput(Block block) {
        return canUseInput(block, null);
    }

    /** {@code keys == null}: compute {@code block.verifiedKeys()} here (the pre-SP0b-2 behaviour). */
    public boolean canUseInput(Block block, List<PublicKey> keys) {
        List<PublicKey> verified = keys != null ? keys : block.verifiedKeys();
        List<Address> inputs = block.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return true;
        }
        for (Address in : inputs) {
            if (!in.isAddress) {
                if (!verifySignature(in, verified)) {
                    return false;
                }
            } else {
                if (!verifyBlockSignature(in, verified)) {
                    return false;
                }
            }
        }
        return true;
    }
```

`inline` 在锁外的调用线程上算 ECDSA——对本地出块、测试、修复工具与 `syncPopBlock` 的重导都等价于今天（同一线程、同一份工作），只是搬到了 `synchronized` 之外。

- [ ] **Step 4: 实现 `SyncManager`**

字段：`private IngestPipeline pipeline;`。`doStart()` 末尾：

```java
        int threads = kernel.getConfig().getChainSpec().getChainIngestThreads();
        if (threads > 0) {
            pipeline = new IngestPipeline(threads, kernel.getConfig().getChainSpec().getChainIngestQueue(),
                    this::importPreValidated);
            pipeline.start();
            log.info("Ingest pipeline on: threads={}, queue={}", threads, kernel.getConfig().getChainSpec().getChainIngestQueue());
        }
```

`doStop()` 开头：`if (pipeline != null) { pipeline.stop(); }`。新方法：

```java
    /** The network entry point: the pipeline when it is on, else the synchronous import. */
    public void submitBlock(BlockWrapper blockWrapper) {
        if (pipeline != null) {
            pipeline.submit(blockWrapper);
        } else {
            validateAndAddNewBlock(blockWrapper);
        }
    }

    /** The pipeline's commit step: the pre-validated first attempt, then the same bookkeeping as validateAndAddNewBlock. */
    public synchronized ImportResult importPreValidated(PreValidated pv) {
        ImportResult result = finishImport(pv.wrapper(), blockchain.tryToConnect(pv));
        afterImport(pv.wrapper(), result);
        return result;
    }
```

重构（行为不变）：`importBlock(bw)` 的 `tryToConnect(new Block(new XdagBlock(...)))` 之后的部分抽成 `private ImportResult finishImport(BlockWrapper bw, ImportResult importResult)`（EXIST 日志 + 分发逻辑 + `return importResult`），`importBlock` = `finishImport(bw, blockchain.tryToConnect(new Block(new XdagBlock(bw.getBlock().getXdagBlock().getData().toArray()))))`——**重导路径保留重解析**（`syncMap` 里的块可能带着上次尝试写下的标志）；`validateAndAddNewBlock` 里 `switch (result)` 抽成 `private void afterImport(BlockWrapper bw, ImportResult result)`，`validateAndAddNewBlock` = `parse(); result = importBlock(bw); log; afterImport(bw, result); return result;`。

- [ ] **Step 5: `XdagP2pHandler`**

`processNewBlock` 与 `processSyncBlock` 里 `syncMgr.validateAndAddNewBlock(bw)` → `syncMgr.submitBlock(bw)`。

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.chain.ingest.*Test,io.xdag.chain.**.*Test,io.xdag.core.BlockchainTest,io.xdag.consensus.SyncTest,io.xdag.cli.RepairChainCommandTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿；`IngestEquivalenceTest` 1 个绿（约 30–60 s）。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/io/xdag/core/Blockchain.java src/main/java/io/xdag/core/BlockchainImpl.java src/main/java/io/xdag/consensus/SyncManager.java src/main/java/io/xdag/net/XdagP2pHandler.java src/test/java/io/xdag/chain/ingest/IngestEquivalenceTest.java
git commit -m "Import network blocks through the pipeline with pre-validated keys

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: 日志降级、基准 `pipeline` 行与基线文档

**Files:**
- Modify: `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java`（两处 `log.info` → `log.debug`）
- Modify: `src/test/java/io/xdag/chain/bench/ChainL1ImportBenchmarkTest.java`（`wrapFactory` 覆盖；`pipeline.rN` 三轮；`report` 新行）
- Create: `docs/benchmarks/<yyyy-mm-dd>-l1-import-pipeline.md`

- [ ] **Step 1: 日志降级**

`OrphanBlockStoreImpl` 里两条 `log.info("vipTxCount: {}, …")` 改为 `log.debug`。

- [ ] **Step 2: 基准**

`ChainL1ImportBenchmarkTest`：

```java
    /** -Dxdag.bench.writeBehind=false runs the old synchronous stores; default is the node's write-behind layer. */
    @Override
    protected DatabaseFactory wrapFactory(DatabaseFactory raw) {
        if (!Boolean.parseBoolean(System.getProperty("xdag.bench.writeBehind", "true"))) {
            return raw;
        }
        WriteBehindQueue q = new WriteBehindQueue(4096, 256, 20);
        q.start();
        return new WriteBehindFactory(raw, q, 65536);
    }

    private PersistControl persist() {
        return dbFactory instanceof WriteBehindFactory wb ? wb.queue() : PersistControl.NONE;
    }
```

`baseline()` 在 confirmed 三轮之前加三轮 `pipeline.rN`：

```java
        Measure[] pipelineRows = new Measure[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            freshFixture();
            prepareChain();
            BenchWorkload w = workload();
            SyncManager sync = syncManager();
            long[] inLock = new long[w.items().size()];
            int[] k = {0};
            IngestPipeline pipeline = new IngestPipeline(
                    Integer.getInteger("xdag.bench.ingestThreads", Runtime.getRuntime().availableProcessors()), 4096, pv -> {
                long a = System.nanoTime();
                ImportResult r = sync.importPreValidated(pv);
                if (pv.block().getInputs() != null && !pv.block().getInputs().isEmpty() && k[0] < inLock.length) {
                    inLock[k[0]++] = System.nanoTime() - a;
                }
                assertTrue(String.valueOf(r), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
                return r;
            });
            pipeline.start();
            long t0 = System.nanoTime();
            for (BenchWorkload.Item item : w.items()) {
                for (int c = item.chunks().size() - 1; c >= 0; c--) {
                    pipeline.submit(new BlockWrapper(item.chunks().get(c), 0));
                }
                pipeline.submit(new BlockWrapper(item.block(), 0));
            }
            assertTrue(pipeline.awaitIdle(600, TimeUnit.SECONDS));
            persist().flushSync();
            long total = (System.nanoTime() - t0) / 1_000_000;
            pipeline.stop();
            assertTop();
            pipelineRows[round] = measure("pipeline.r" + round, inLock, k[0], w.items().size() + w.chunkCount(), total);
            results.add(pipelineRows[round]);
        }
```

（`pipeline.*` 行的 mean/p50/p95 是**锁内提交耗时**（`importPreValidated`），blocks/s 是端到端（首个 submit 到全部提交并排空）。`persist().flushSync()` 计入总时间，所以吞吐含落盘。）`report(...)` 的 JSON 与 Markdown 自动包含新行；控制台首行加 `writeBehind=<bool> ingestThreads=<n>`；配对差值表不变。

- [ ] **Step 3: 跑**

冒烟：`mvn -q -Dxdag.bench=true -Dxdag.bench.blocks=1000 -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test`。
全量（安静机器，`pgrep -fl "surefire|maven"` 无其他）：默认参数一次；`-Dxdag.bench.writeBehind=false` 再跑一次 direct/syncPath 做前后对比（可用 `-Dxdag.bench.blocks=20000` 全量）。记录 `uptime`。

- [ ] **Step 4: 基线文档**

`docs/benchmarks/<yyyy-mm-dd>-l1-import-pipeline.md`（中文）：机器/JDK/提交；两条命令；两张表（写后关 vs 开；`pipeline.rN` 三轮）；对比表：`direct` 均值前后、`pipeline` 三轮 blocks/s 与中位、锁内提交 mean/p50/p95；验收判定：**`pipeline` 三轮中位 ≥ 10,000 块/s** 达成与否；若未达成，给出锁内分段（`phase.*` 与 `pipeline` 的锁内 mean）与结论。注意事项：负载、`txHistoryStore=null`、残留日志。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java src/test/java/io/xdag/chain/bench/ChainL1ImportBenchmarkTest.java
git add -f docs/benchmarks/<yyyy-mm-dd>-l1-import-pipeline.md
git commit -m "Benchmark the ingest pipeline and record the SP0b-2 baseline

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: 规格同步、全量回归、记忆与汇报

**Files:**
- Modify: `docs/superpowers/specs/2026-09-18-xdag-chain-sp0b2-ingest-pipeline-design.md`（as-built 偏差：`chain.persist.maxPending=0` 关闭写后；sums 在 `BlockStoreImpl` 内每 256 块/每次 stats 保存时写回，不是写入线程写；重导路径保留重解析；`inline` 不做分类；基准 `pipeline` 行的口径；实测数字只引用基线文档）
- Modify: `docs/superpowers/specs/2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md`（§5.2 标为已实施并指向本规格；§13.2 M3 导入指标的状态）
- Modify: `docs/superpowers/specs/2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md`（§12.2 G3 关闭）
- Modify: `docs/XDAGJ_SNAPSHOT_zh.md`（写后落盘的崩溃窗口、`chain.persist.*` 与 `chain.ingest.*` 的含义、关机顺序）
- Modify: `.claude/docs/chain-l1-foundation.md`（§9.2 导入流水线与写后层；§10 索引）—— 不进 git
- Modify: 本计划头部"执行记录"

- [ ] **Step 1: 全量回归与许可证**

Run: `mvn -q license:check` → 退出 0；`mvn -q test`（安静机器）→ 汇总 0 失败 0 错误（基准类 Skipped 1）；`-Dxdag.reorg.full=true -Dtest=io.xdag.chain.l1.ChainL1ReorgPropertyTest` 8 个种子全绿。

- [ ] **Step 2: 规格与文档同步**

按上面的文件清单逐项改；每条陈述以代码为准；数字只引用基线文档。提交（`git add -f` 每个 docs 文件；`.claude/docs` 不提交）。

- [ ] **Step 3: 记忆与汇报**

更新记忆文件（SP0b-2 完成状态、提交链、测试总数、基准结论、验收结果、偏差）；向用户汇报：分支、提交列表、测试总数、基准表（前后对比 + `pipeline` 行）、验收是否达成、与规格的偏差、给 SP0b-3 的输入。
