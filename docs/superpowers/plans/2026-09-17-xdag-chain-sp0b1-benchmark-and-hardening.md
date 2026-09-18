# SP0b-1 L1 导入基准与主链完整性加固 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 测出 L1 导入吞吐基线并分阶段归因；从根上消除"`setMain` 中途失败留下永远无法回滚的主块"（G1），给"上一次 `setMain` 是否完整结束"一个可核对的标记（G2），启动时检测即停机并提供离线修复命令 `--repairchain`；补上 `makeSnapshot` 端到端测试（G9）、随机 reorg 属性测试（G10）与 G7/G8 两处既有缺陷。

**Architecture:** 所有改动都是节点本地的：`setMain` 把 `updateBlockRef(self)` 提前到 `applyBlock` 之前；`BlockStore` 增加一个 INDEX 列的 `LAST_COMPLETED_MAIN` 标记，只在 `setMain` 正常结束时写；`ChainConsistencyCheck` 在 `BlockchainImpl` 构造器里（统计加载之后、装配处理器之前）只读检查，命中即抛（`repairMode` 下只记录报告）；`ChainRepairTool` 复用 `unWindMain + updateNewChain` 做受控回滚；基准是 JUnit 黑盒测试，不碰 `tryToConnect`。规格：`docs/superpowers/specs/2026-09-17-xdag-chain-sp0b1-benchmark-and-hardening-design.md`。

**Tech Stack:** Java 21、Maven、JUnit 4、Mockito、RocksDB（现有）、`ChainL1TestBase` 基座（真实 `BlockchainImpl` + RocksDB + 受控难度伪 PoW）。

---

## 执行记录（as executed，2026-09-18）

> 本块只记录执行结果与偏离；各任务正文保持计划原样，as-built 细节以 SP0b-1 规格 v2（`docs/superpowers/specs/2026-09-17-xdag-chain-sp0b1-benchmark-and-hardening-design.md`）为准。

**提交（`git log --oneline 4805774e..HEAD`，旧→新）**

| 任务 | 提交 |
|------|------|
| T1 | 5a82825a Point a main block at itself before applying it and record setMain completion；63bca8e2 Complete the setMain marker on every normal exit and set child refs before applying；f196557e Polish the setMain marker comments and test the unSetMain guard |
| T2 | 7f541c84 Add the node-local chain.consistency.window setting；dcc5e0b4 Mark the consistency window as node-local in config and Javadoc |
| T3 | d9fd5932 Add the read-only main chain consistency check；66cad1c1 Scan the whole main chain in the consistency check and record the in-flight operation |
| T4 | 0b7ae3e1 Refuse to start on an inconsistent main chain unless in repair mode；3877c1b5 Persist stats before the completion marker and harden the boot consistency gate |
| T5 | 9e1c52b9 Add the offline chain repair tool: patch stuck refs and unwind to the last complete height；d9c50cb9 Make the chain repair tool reconcile the tip, verify its unwind and report a status；e5f8eb3f Leave the unwound branch un-flagged after a repair and state the peer dependency honestly |
| T6 | e796aaff Add the --repairchain command；a5ced6fe Open block stores through one production wiring so offline tools read what the node wrote；f996e091 Open the block store through forNode in BlockStoreImplTest；e19e4cb7 Harden --repairchain: refuse in-flight unwind, split exit codes |
| T7 | 224f1c40 Fail loudly on a snapshot copy error and release read options on a failed RocksDB open |
| T8 | ace94fb2 Test makeSnapshot end to end across the three snapshot directories；3709e43e Verify snapshot BLOCKS/ADDRESS content and close makeSnapshot's handles；84b2d4aa Polish the repair and snapshot commands after review；de60672b Make the snapshot rerun hint and the bootable verdict honest |
| T9 | 79705de6 Reverse OUTPUT credits from the persisted fee per output address；ba91968a Add the seeded reorg property test for CHAIN_L1 symmetry；5a698579 Restore the stored fee before finishing an interrupted unwind |
| T10 | d12181f0 Add the L1 import benchmark and record the baseline（首版 harness）；基线重跑所用的 harness 修订与基线文档重写由基准代理另行提交（本记录写成时尚未提交） |
| T11 | 规格/文档同步提交（本块所在提交及其后的运维文档提交） |

**偏离计划之处**

- **标记键与 in-flight 记录（T1/T3）**：`LAST_COMPLETED_MAIN` 是 `0xb0`（不是规格 v1 的 `0x7E`），另加 `MAIN_IN_FLIGHT = 0xc0`（op 字节 1 = setMain / 2 = unSetMain + 大端高度）；每次写标记前先 `saveXdagStatus`，`Kernel.testStop` 关库前再存一次；快照重灌分支写 `xdagStats.nmain` 并清 in-flight。
- **一致性检查（T3）**：四条规则（in-flight、标记落后、统计之上有主块、窗口内无 ref），扫描 `[max(1, nmain − window), nmain + 64]`，不按激活高度钳制，跳过陈旧高度索引；`Report` 五个分量，`describe()` / `describeForBoot()` 分开。
- **修复命令（T5/T6）**：`--repairchain [dry-run|force|reinit-marker]`（位置参数；`reinit-marker` 为降级陷阱新增），退出码 0/1/2/3/4（Task 6 质量评审后从 0/1/2/3 拆开）；回滚前 `reconcileTipTo`；回滚后不重新打 `BI_MAIN_CHAIN`，节点需要同行的新块才能重新确认；半途 `unSetMain` 单独处理（块仍 `BI_MAIN` → 恢复库里的 `info.fee` 后续完，否则 UNREPAIRABLE）；`BlockStoreImpl.forNode` 成为所有开库的唯一入口（曾因按签名顺序开库而全量报 `INCOMPLETE_BLOCK_DATA_REASON`）。
- **G9（T8）**：`makeSnapshot` 改为返回 boolean，不可启动 → `--makesnapshot` 退出 1；三目录内容按启动路径实际读取的方式核验；重跑提示指向整个 `SNAPSHOT` 目录；`finally` 关闭句柄。
- **Task 9 形态变更**：计划里"分叉回滚后 `CHAIN_L1` 只剩 META、再在新分支重新链接"不可行（`confirm()` 在非最佳分支断言 BEST/APPLIED 会失败，高度整体偏移）→ 改为**同高度分支重放**（竞争分支在相同高度重新 link 同一批付费块、每高度主块数相同），性质 = reorg 后 `stateHash()` == 分叉前 == 新基座直接 apply，外加余额/nonce/金库与标志。基座事实：`checkNewMain` 确认的是**上一次** `mineMain` 的主块（`i > 1`），故 handler 故障注入发生在链接之后的下一次 `mineMain`。
- **G10 发现（T9）**：属性测试的余额半边暴露既有的 `unApplyBlock` 费用除数错误（`fee / outPutNum` 把 OUT 块 link 也算进除数），带代码链的 DEPLOY 与带参数链的 CALL 在 unwind 时每个 OUTPUT 多扣 `L/2`；修为 `amount − info.fee / k`（提交 79705de6，m = 0 逐字节不变；`ChainL1UnwindFeeTest`）。不修既有存储；不设激活高度；不只影响 chain 合约——见 SP0a 规格 §12.2 G11。
- **Task 10 API 与统计口径修正**：`syncPath` 用 `new BlockWrapper(b, 0)`（计划写的 `(b, ttl, null)` 不是现有构造器）；`confirmed` 每 `LINKS_PER_MAIN = 10` 个付费块出一个主块（不是 2000），其 mean/p50/p95 是**每个主块**的耗时；新增 `calib.emptyMain`（100 个空主块的基座成本）用于把 `confirmed` 拆成伪 PoW 与 setMain/apply；`direct`/`syncPath` 同轮交错并报配对差值（消单调漂移）；报表加 `mean` 列，**阶段占比 = 阶段均值 / direct 均值**（阶段行是 best-of-3 墙钟 / n，本身是均值；除以 direct 的 p50 会因右偏分布高估），不是计划的"中位数占比"；基座改为生产形状 `txHistoryStore == null`（默认 mock 会把每个金额 link 送进 MySQL 失败回退）。数字只在 `docs/benchmarks/2026-09-18-l1-import-baseline.md`。
- **顺手关闭 G5**：`stopCheckMain()` 经 `stopCleaner()` 停掉 cleaner（`--repairchain` 依赖它退出）。
- **Task 11**：Step 2 本提交完成；Step 1（全量 `mvn test` + `license:check`）与 Step 3（记忆）由控制方在基准重跑之后执行。

---

## 0. 约定（每个任务都适用）

### 0.1 构建与测试命令

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
mvn -q -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test     # 单类；-q 隐藏汇总，看 target/surefire-reports/<Class>.txt
mvn -q license:check                                                     # 每次提交前
```

不要 `mvn clean`（其他会话可能在同一目录构建）。基座测试每个约 5–15 s。

### 0.2 分支与提交

分支 `dev-dag-contract`（已检出，不切换）。提交信息**只用英文**，结尾两行尾注：

```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

`docs/` 全局被 gitignore：`git add -f docs/...`。`.claude/docs/` 不进 git。

### 0.3 许可证头

每个新 Java 文件顶部必须有 MIT 头（复制 `src/main/java/io/xdag/chain/l1/ChainIds.java` 前 23 行）。

### 0.4 既成规则（不得违反）

P1 `tryToConnect` 不读 EXT；P5 每个应用块一个 `CHAIN_L1` batch（本计划不新增 `CHAIN_L1` 写操作）；不往任何 `.conf` 加 `chain.*` 键；`chain.consistency.window` 是节点本地键，只在 `AbstractConfig.getSetting()` 里读；不改 `ChainL1Processor` 的语义。

### 0.5 测试陷阱

- 基座 `mineMain(refs)` 的 `refs` 必须按发送方 nonce 升序；`txTime()` 只对"下一次 `mineMain` 就链接它"的块有效；`confirm()` 会推进 `generateTime`。
- 基座的 `MockBlockchain.checkMain()` 直接调 `checkNewMain()`，**没有** `try/catch`：`setMain` 里抛出的异常会传到测试代码——制造"未完成的 `setMain`"就靠这一点。
- `blockchain.getBlockByHeight(h)` 对 `h > xdagStats.nmain` 返回 null；要看"统计之上"的主块必须用 `blockStore.getBlockByHeight(h)`。
- `Block.getHash()` 会缓存原始字节：签名前不要调用；对手工构造的块一律 `new Block(new XdagBlock(b.toBytes()))` 重新解析。
- 在同一 `kernel`/存储上再 `new MockBlockchain(kernel)` 就是"重启"：新实例从库里加载统计与顶状态。第一个实例不必关闭（它的 `startCheckMain` 是空操作）。

---

### Task 1: `BlockStore` 完成标记 + `setMain` 的 G1 根治与标记写入

**Files:**
- Modify: `src/main/java/io/xdag/db/BlockStore.java`
- Modify: `src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`setMain`、`unSetMain`、构造器快照分支）
- Test: `src/test/java/io/xdag/chain/repair/MainCompletionMarkerTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.xdag.chain.repair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import io.xdag.Kernel;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

public class MainCompletionMarkerTest extends ChainL1TestBase {

    private static final Bytes BOND_CHAIN = Bytes.repeat((byte) 0x22, 20);

    /** Throws on the first BOND it is handed, once armed; a plain no-op otherwise. */
    private static final class ExplodingHandler implements ChainKindHandler {
        volatile boolean armed;

        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("simulated failure inside the applyBlock DFS");
            }
        }

        @Override
        public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        }
    }

    private final ExplodingHandler handler = new ExplodingHandler();

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.getChainKindHandlers().put(ExtKind.BOND, handler);
    }

    private Block bondBlock() {
        Block b = new Block(config, txTime(), null, null, false, null, null, -1, XAmount.ZERO, null,
                List.of(new BondExt(false, BOND_CHAIN, 0L).encodeHeader()));
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void markerFollowsNormalConfirmationAndUnwind() {
        assertEquals("fresh store carries no marker", -1L, kernel.getBlockStore().getLastCompletedMain());
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        assertEquals(blockchain.getXdagStats().nmain, kernel.getBlockStore().getLastCompletedMain());
        long before = blockchain.getXdagStats().nmain;

        // unSetMain moves the marker down with the height it removes
        Block tip = blockchain.getBlockByHeight(before);
        blockchain.unSetMain(tip);
        assertEquals(before - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals(before - 1, blockchain.getXdagStats().nmain);
    }

    @Test
    public void failedSetMainLeavesRefOnSelfAndNoMarkerAdvance() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long completed = kernel.getBlockStore().getLastCompletedMain();

        Block bond = bondBlock();
        assertImported(bond);
        handler.armed = true;
        // the main block confirming the BOND is mined; MockBlockchain.checkMain has no try/catch,
        // so the handler's exception escapes setMain (after BI_MAIN, height, reward, nmain++)
        assertThrows(IllegalStateException.class, () -> mineMain(List.of(hashLow(bond))));

        long failedHeight = blockchain.getXdagStats().nmain;
        assertEquals(completed + 1, failedHeight);
        Block stuck = kernel.getBlockStore().getBlockByHeight(failedHeight);
        assertNotNull("the failed main block is stored with its height", stuck);
        // G1 root fix: the ref points at itself BEFORE the DFS, so unApplyBlock will not skip it
        assertNotNull("ref must be set before applyBlock runs", stuck.getInfo().getRef());
        assertEquals(stuck.getHashLow(), org.apache.tuweni.bytes.Bytes32.wrap(stuck.getInfo().getRef()));
        // G2 marker: a setMain that did not finish never advances the marker
        assertEquals(completed, kernel.getBlockStore().getLastCompletedMain());
    }
}
```

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.repair.MainCompletionMarkerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: method getLastCompletedMain()`。

- [x] **Step 3: `BlockStore` 接口与实现**

`src/main/java/io/xdag/db/BlockStore.java`，常量区（`TX_HISTORY = (byte) 0xa0` 之后）加：

```java
    /** Node-local: height of the last setMain that ran to completion (see BlockchainImpl.setMain). */
    byte LAST_COMPLETED_MAIN = (byte) 0xb0;
```

方法区（`setSnapshotBoot()` 之后）加：

```java
    /**
     * Height of the last main block whose {@code setMain} ran to completion, or {@code -1} if the
     * marker was never written (a store created before SP0b-1). Node-local bookkeeping: never part
     * of any hash, never exported with a snapshot.
     */
    long getLastCompletedMain();

    void saveLastCompletedMain(long height);
```

`src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`，`setSnapshotBoot()` 之后加：

```java
    @Override
    public long getLastCompletedMain() {
        byte[] data = indexSource.get(new byte[]{LAST_COMPLETED_MAIN});
        if (data == null || data.length != 8) {
            return -1L;
        }
        return BytesUtils.bytesToLong(data, 0, false);
    }

    @Override
    public void saveLastCompletedMain(long height) {
        indexSource.put(new byte[]{LAST_COMPLETED_MAIN}, BytesUtils.longToBytes(height, false));
    }
```

（`BytesUtils.longToBytes(long, boolean)` / `bytesToLong(byte[], int, boolean)` 已存在于 `io.xdag.utils.BytesUtils`；若签名不同，改用同文件里现有的 8 字节大端编解码。）

- [x] **Step 4: `setMain` / `unSetMain` / 构造器快照分支**

`BlockchainImpl.setMain`：把 `try {` 后的开头改为

```java
            try {
                // G1 root fix (SP0b-1): point the main block at itself BEFORE the DFS. A throw out of
                // applyBlock then leaves ref == self, so unApplyBlock treats this block normally
                // instead of skipping a BI_MAIN_REF block whose ref is null (which could never be
                // unwound). ref lives only in the local BlockInfo; it is not part of any hash.
                updateBlockRef(block, new Address(block));

                // Accept reward
                acceptAmount(block, reward);
```

并在 `if (randomx != null) { randomx.randomXSetForkTime(block); }` 之后、`} finally {` 之前加：

```java
                // G2 marker (SP0b-1): written only on a normal completion — deliberately NOT in the
                // finally block — so a boot can tell whether the previous setMain finished.
                blockStore.saveLastCompletedMain(mainNumber);
```

`unSetMain`：在方法开头 `chainHooks.onUnsetMain(...)` 之前记 `long height = block.getInfo().getHeight();`（若已有同名变量则复用），在最后一句 `updateBlockRef(block, null);` 之后加：

```java
            blockStore.saveLastCompletedMain(height - 1);
```

构造器快照分支：在 `blockStore.saveXdagStatus(xdagStats);`（"Save latest snapshot state"）之后加：

```java
            // SP0b-1: a freshly re-seeded block store is complete up to the snapshot height.
            blockStore.saveLastCompletedMain(snapshotHeight);
```

- [x] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.repair.MainCompletionMarkerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 2, Failures: 0`。再跑 `mvn -q -Dtest='io.xdag.chain.**.*Test,io.xdag.core.*Test' -Dsurefire.failIfNoSpecifiedTests=false test` 全绿（既有 reorg/激活/快照测试不受影响）。

- [x] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/db/BlockStore.java src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/chain/repair/MainCompletionMarkerTest.java
git commit -m "Point a main block at itself before applying it and record setMain completion

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `chain.consistency.window` 配置（节点本地）

**Files:**
- Modify: `src/main/java/io/xdag/config/spec/ChainSpec.java`
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java`
- Test: `src/test/java/io/xdag/config/ChainSpecTest.java`（追加两个测试）

- [x] **Step 1: 写失败测试**（追加到 `ChainSpecTest`，沿用文件里已有的 `withProperty` 助手）

```java
    @Test
    public void consistencyWindowDefaultsTo128() {
        assertEquals(128, new DevnetConfig().getChainSpec().getChainConsistencyWindow());
    }

    @Test
    public void consistencyWindowIsNodeLocalAndValidated() {
        assertEquals(64, (int) withProperty("chain.consistency.window", "64",
                () -> new DevnetConfig().getChainSpec().getChainConsistencyWindow()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withProperty("chain.consistency.window", "0", DevnetConfig::new));
        assertTrue(e.getMessage().contains("chain.consistency.window"));
    }
```

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.config.ChainSpecTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: method getChainConsistencyWindow()`。

- [x] **Step 3: 实现**

`ChainSpec.java` 末尾加：

```java
    /** Protocol-independent default for {@code chain.consistency.window}. */
    int DEFAULT_CONSISTENCY_WINDOW = 128;

    /**
     * How many main heights below the tip the startup consistency check scans for main blocks
     * left without a self reference. Node-local (not consensus): may be set in the conf as
     * {@code chain.consistency.window}; must be positive.
     */
    int getChainConsistencyWindow();
```

`AbstractConfig.java`：字段区加 `protected int chainConsistencyWindow = ChainSpec.DEFAULT_CONSISTENCY_WINDOW;`；`getSetting()` 的链参数块末尾加：

```java
        if (config.hasPath("chain.consistency.window")) {
            chainConsistencyWindow = config.getInt("chain.consistency.window");
        }
        if (chainConsistencyWindow <= 0) {
            throw new IllegalArgumentException(
                    "Invalid chain.consistency.window: " + chainConsistencyWindow + " (must be > 0)");
        }
```

getter：

```java
    @Override
    public int getChainConsistencyWindow() {
        return chainConsistencyWindow;
    }
```

- [x] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.config.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（`ChainSpecTest` 比之前多 2 个）。

- [x] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/config/spec/ChainSpec.java src/main/java/io/xdag/config/AbstractConfig.java src/test/java/io/xdag/config/ChainSpecTest.java
git commit -m "Add the node-local chain.consistency.window setting

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `ChainConsistencyCheck`（纯检查，不改状态）

**Files:**
- Create: `src/main/java/io/xdag/chain/repair/ChainConsistencyCheck.java`
- Test: `src/test/java/io/xdag/chain/repair/ChainConsistencyCheckTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.xdag.chain.repair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Block;
import io.xdag.db.BlockStore;
import java.util.List;
import org.junit.Test;

public class ChainConsistencyCheckTest extends ChainL1TestBase {

    private ChainConsistencyCheck.Report check() {
        return ChainConsistencyCheck.run(kernel.getBlockStore(), blockchain.getXdagStats(),
                config.getChainSpec(), config.getChainSpec().getChainConsistencyWindow());
    }

    @Test
    public void cleanChainIsClean() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        ChainConsistencyCheck.Report r = check();
        assertTrue(r.describe(), r.clean());
        assertFalse(r.markerInitialized());
        assertEquals(blockchain.getXdagStats().nmain, r.nmain());
        assertEquals(blockchain.getXdagStats().nmain, r.marker());
    }

    @Test
    public void missingMarkerIsInitializedNotFatal() {
        for (int i = 0; i < 3; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(-1L); // simulate a store from before SP0b-1
        // (getLastCompletedMain treats a stored -1 exactly like an absent marker: see Task 1)
        ChainConsistencyCheck.Report r = check();
        assertTrue(r.describe(), r.clean());
        assertTrue(r.markerInitialized());
        assertEquals(blockchain.getXdagStats().nmain, r.marker());
    }

    @Test
    public void markerBehindTipIsAnIncompleteSetMain() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long tip = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveLastCompletedMain(tip - 2);
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(2, r.stuck().size());
        assertEquals(tip - 1, r.stuck().get(0).height());
        assertEquals(tip, r.stuck().get(1).height());
        assertTrue(r.stuck().get(0).reason().contains("setMain incomplete"));
        assertTrue(r.describe().contains("--repairchain"));
    }

    @Test
    public void mainBlockWithoutRefIsStuck() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain - 1;
        Block m = blockchain.getBlockByHeight(h);
        blockchain.updateBlockRef(m, null); // legacy stuck shape (pre-SP0b-1 crash)
        ChainConsistencyCheck.Report r = check();
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(h, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("without ref"));
    }

    @Test
    public void mainBlockAbovePersistedStatsIsStuck() {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        // simulate a crash after the main block at nmain was stored but before stats were persisted
        io.xdag.core.XdagStats stale = new io.xdag.core.XdagStats();
        stale.nmain = blockchain.getXdagStats().nmain - 1;
        kernel.getBlockStore().saveLastCompletedMain(stale.nmain);
        ChainConsistencyCheck.Report r = ChainConsistencyCheck.run(kernel.getBlockStore(), stale,
                config.getChainSpec(), 128);
        assertFalse(r.clean());
        assertEquals(1, r.stuck().size());
        assertEquals(stale.nmain + 1, r.stuck().get(0).height());
        assertTrue(r.stuck().get(0).reason().contains("above persisted stats"));
    }

    @Test
    public void windowBoundsTheScan() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long old = 2;
        blockchain.updateBlockRef(blockchain.getBlockByHeight(old), null);
        BlockStore store = kernel.getBlockStore();
        ChainConsistencyCheck.Report narrow = ChainConsistencyCheck.run(store, blockchain.getXdagStats(),
                config.getChainSpec(), 2);
        assertTrue("outside the window the stuck block is not scanned", narrow.clean());
        ChainConsistencyCheck.Report wide = ChainConsistencyCheck.run(store, blockchain.getXdagStats(),
                config.getChainSpec(), 128);
        assertFalse(wide.clean());
    }
}
```

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.repair.ChainConsistencyCheckTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChainConsistencyCheck`。

- [x] **Step 3: 实现**

`src/main/java/io/xdag/chain/repair/ChainConsistencyCheck.java`：

```java
package io.xdag.chain.repair;

import static io.xdag.config.Constants.BI_MAIN;

import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Block;
import io.xdag.core.XdagStats;
import io.xdag.db.BlockStore;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Startup check that the main chain is in a state the chain hooks can trust: every confirmed main
 * block points at itself (so it can be unwound) and the last setMain ran to completion (so every
 * CHAIN_L1 record it should have produced exists). Read-only; the caller decides whether a
 * non-clean report is fatal (normal boot) or merely recorded (repair mode).
 *
 * <p>Two failure shapes are detected: (1) the {@code LAST_COMPLETED_MAIN} marker is behind the
 * persisted tip, or a main block is stored above the persisted tip — a setMain that did not
 * finish; (2) a main block inside the window carries {@code BI_MAIN} but no ref — the shape a
 * pre-SP0b-1 crash left behind (SP0b-1 sets the ref before the DFS, so new crashes no longer
 * produce it). A store without a marker (first boot after upgrading) is initialized to the tip
 * with a warning: its history cannot be verified.
 */
@Slf4j
public final class ChainConsistencyCheck {

    public static final String REPAIR_HINT =
            "; the node refuses to start. Run `xdag.sh --repairchain --dry-run` to see the repair plan and "
                    + "`xdag.sh --repairchain` to unwind to the last complete height";

    /** How far above the persisted tip stored main blocks are looked for. */
    static final int ABOVE_TIP_SCAN = 64;

    public record Stuck(long height, Bytes32 hash, String reason) {
    }

    public record Report(long nmain, long marker, List<Stuck> stuck, boolean markerInitialized) {
        public boolean clean() {
            return stuck.isEmpty();
        }

        /** Human-readable summary, one stuck block per line. */
        public String describe() {
            StringBuilder sb = new StringBuilder("main chain consistency: nmain=" + nmain + ", lastCompletedMain="
                    + marker + (markerInitialized ? " (initialized on this boot)" : ""));
            if (clean()) {
                return sb.append(", clean").toString();
            }
            sb.append(", ").append(stuck.size()).append(" stuck main block(s):");
            for (Stuck s : stuck) {
                sb.append("\n  height ").append(s.height()).append(" ")
                        .append(s.hash() == null ? "?" : s.hash().toHexString()).append(": ").append(s.reason());
            }
            return sb.append(REPAIR_HINT).toString();
        }

        /** Height to unwind to: everything above it is suspect. */
        public long repairTarget() {
            long earliestStuck = stuck.stream().mapToLong(Stuck::height).min().orElse(nmain + 1);
            return Math.max(0, Math.min(marker, earliestStuck - 1));
        }
    }

    private ChainConsistencyCheck() {
    }

    public static Report run(BlockStore blockStore, XdagStats stats, ChainSpec spec, int window) {
        long nmain = stats.nmain;
        long marker = blockStore.getLastCompletedMain();
        boolean initialized = false;
        if (marker < 0) {
            log.warn("LAST_COMPLETED_MAIN marker absent: initializing to nmain={}; history before this boot "
                    + "cannot be verified", nmain);
            marker = nmain;
            initialized = true;
        }
        List<Stuck> stuck = new ArrayList<>();

        // (1) the last setMain did not finish
        for (long h = marker + 1; h <= nmain; h++) {
            stuck.add(new Stuck(h, hashAt(blockStore, h), "setMain incomplete (completion marker " + marker
                    + " is behind persisted nmain " + nmain + ")"));
        }
        if (marker > nmain + ABOVE_TIP_SCAN) {
            log.warn("LAST_COMPLETED_MAIN marker {} is far above persisted nmain {}: stats lag suspected", marker,
                    nmain);
        }

        // (2) main blocks in the window without a self reference; (3) main blocks above the persisted tip
        long from = Math.max(1, Math.max(spec.getChainActivationHeight(), nmain - window));
        for (long h = from; h <= nmain + ABOVE_TIP_SCAN; h++) {
            Block b = blockStore.getBlockByHeight(h);
            if (b == null) {
                if (h > nmain) {
                    break; // no stored main block above this point
                }
                continue;
            }
            if ((b.getInfo().getFlags() & BI_MAIN) == 0) {
                continue;
            }
            if (h > nmain) {
                stuck.add(new Stuck(h, hashLow(b), "main block above persisted stats (setMain crashed before "
                        + "stats were saved)"));
            } else if (b.getInfo().getRef() == null) {
                stuck.add(new Stuck(h, hashLow(b), "main block without ref (unwindable only after repair)"));
            }
        }
        stuck.sort((a, c) -> Long.compare(a.height(), c.height()));
        return new Report(nmain, marker, List.copyOf(stuck), initialized);
    }

    private static Bytes32 hashAt(BlockStore store, long h) {
        Block b = store.getBlockByHeight(h);
        return b == null ? null : hashLow(b);
    }

    private static Bytes32 hashLow(Block b) {
        return Bytes32.wrap(b.getHashLow().toArray());
    }
}
```

注意：`markerBehindTipIsAnIncompleteSetMain` 里 `tip-1` 与 `tip` 各出现一次——规则 (1) 与规则 (2) 可能对同一高度各加一条；实现里对同一高度只保留一条（先加的那条），用 `LinkedHashMap<Long, Stuck>` 或在加入前 `stuck.stream().noneMatch(s -> s.height() == h)` 去重。

- [x] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.repair.ChainConsistencyCheckTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 6, Failures: 0`。

- [x] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/repair/ChainConsistencyCheck.java src/test/java/io/xdag/chain/repair/ChainConsistencyCheckTest.java
git commit -m "Add the read-only main chain consistency check

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: 把检查接进 `BlockchainImpl` 构造器（检测即停机；`repairMode` 只记录）

**Files:**
- Modify: `src/main/java/io/xdag/Kernel.java`（两个字段）
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（构造器）
- Test: `src/test/java/io/xdag/chain/repair/ConsistencyGateTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.xdag.chain.repair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.l1.ChainL1TestBase;
import java.util.List;
import org.junit.Test;

public class ConsistencyGateTest extends ChainL1TestBase {

    @Test
    public void cleanStoreConstructsAndRecordsAReport() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        MockBlockchain restarted = new MockBlockchain(kernel); // "restart" on the same stores
        assertNotNull(kernel.getConsistencyReport());
        assertTrue(kernel.getConsistencyReport().clean());
        assertEquals(blockchain.getXdagStats().nmain, restarted.getXdagStats().nmain);
    }

    @Test
    public void inconsistentStoreRefusesToConstruct() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(blockchain.getXdagStats().nmain - 1);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new MockBlockchain(kernel));
        assertTrue(e.getMessage(), e.getMessage().contains("setMain incomplete"));
        assertTrue(e.getMessage(), e.getMessage().contains("--repairchain"));
    }

    @Test
    public void repairModeConstructsWithoutStartingCheckMain() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(blockchain.getXdagStats().nmain - 1);
        kernel.setRepairMode(true);
        MockBlockchain repair = new MockBlockchain(kernel);
        assertNotNull(repair);
        assertFalse(kernel.getConsistencyReport().clean());
        assertEquals(blockchain.getXdagStats().nmain - 1, kernel.getConsistencyReport().repairTarget());
    }

    @Test
    public void absentMarkerIsInitializedOnFirstBoot() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(-1L);
        new MockBlockchain(kernel);
        assertTrue(kernel.getConsistencyReport().markerInitialized());
        assertEquals(blockchain.getXdagStats().nmain, kernel.getBlockStore().getLastCompletedMain());
    }
}
```

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.repair.ConsistencyGateTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: method getConsistencyReport()`。

- [x] **Step 3: `Kernel` 字段**

`Kernel.java` 字段区（`chainKindHandlers` 之后）加（类级 Lombok `@Getter @Setter` 生成访问器）：

```java
    /**
     * SP0b-1: when true, BlockchainImpl's constructor records a non-clean consistency report in
     * {@link #consistencyReport} instead of throwing, and does not start the check-main loop.
     * Set by the offline repair tool only.
     */
    protected boolean repairMode;

    /** SP0b-1: the startup consistency report of the most recent BlockchainImpl construction. */
    protected io.xdag.chain.repair.ChainConsistencyCheck.Report consistencyReport;
```

- [x] **Step 4: 构造器接线**

`BlockchainImpl` 构造器：在 `// Initialize RandomX` 块之后、`// Chain contracts (SP0a): install the hooks` 之前加：

```java
        // SP0b-1: refuse to run on a main chain the chain hooks cannot trust. Both boot paths reach
        // this point with xdagStats loaded (the snapshot branch wrote the marker itself).
        ChainConsistencyCheck.Report report = ChainConsistencyCheck.run(blockStore, xdagStats,
                kernel.getConfig().getChainSpec(), kernel.getConfig().getChainSpec().getChainConsistencyWindow());
        if (report.markerInitialized()) {
            blockStore.saveLastCompletedMain(xdagStats.nmain);
        }
        kernel.setConsistencyReport(report);
        if (!report.clean()) {
            if (kernel.isRepairMode()) {
                log.warn("repair mode: {}", report.describe());
            } else {
                throw new IllegalStateException(report.describe());
            }
        }
```

并把 `this.startCheckMain(1024);` 改为

```java
        if (!kernel.isRepairMode()) {
            this.startCheckMain(1024);
        }
```

import `io.xdag.chain.repair.ChainConsistencyCheck`。

- [x] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.chain.**.*Test,io.xdag.core.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（`ConsistencyGateTest` 4 个；既有 `BlockchainTest` 等构造 `BlockchainImpl` 的测试在无标记时走"初始化"路径，不受影响）。

- [x] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/Kernel.java src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/chain/repair/ConsistencyGateTest.java
git commit -m "Refuse to start on an inconsistent main chain unless in repair mode

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `ChainRepairTool` 核心（受控回滚）

**Files:**
- Create: `src/main/java/io/xdag/chain/repair/ChainRepairTool.java`
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（新增 `repairUnwindTo`）
- Test: `src/test/java/io/xdag/chain/repair/ChainRepairToolTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.xdag.chain.repair;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainKindHandler;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

public class ChainRepairToolTest extends ChainL1TestBase {

    private static final Bytes BOND_CHAIN = Bytes.repeat((byte) 0x33, 20);

    private static final class ExplodingHandler implements ChainKindHandler {
        volatile boolean armed;

        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("simulated failure inside the applyBlock DFS");
            }
        }

        @Override
        public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
        }
    }

    private final ExplodingHandler handler = new ExplodingHandler();

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.getChainKindHandlers().put(ExtKind.BOND, handler);
    }

    private Block bondBlock() {
        Block b = new Block(config, txTime(), null, null, false, null, null, -1, XAmount.ZERO, null,
                List.of(new BondExt(false, BOND_CHAIN, 0L).encodeHeader()));
        return new Block(new XdagBlock(b.toBytes()));
    }

    /** Snapshot of CHAIN_L1 (all keys except the 0xFF marker) as hex strings key=value. */
    private List<String> chainState() {
        List<String> out = new ArrayList<>();
        for (byte[] k : chainStore.sortedKeys()) {
            if ((k[0] & 0xff) == 0xff) {
                continue;
            }
            out.add(Bytes.wrap(k).toHexString() + "=" + Bytes.wrap(kernel.getChainL1Store().getRaw(k)).toHexString());
        }
        return out;
    }

    @Test
    public void incompleteSetMainIsUnwoundAndReconfirmedIdentically() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        // a DEPLOY confirmed before the failure must survive the repair untouched
        ChainBlockBuilder.Built deploy = deployNewChain(payload(600, 41), payload(10, 42));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertTrue(chainStore.hasChain(chainId));
        List<String> before = chainState();

        // the failing height: a CALL and a BOND in the same main block; the BOND handler explodes
        ChainBlockBuilder.Built call = call(chainId, ChainIds.contractIdOf(deploy.block().getHash()), payload(20, 43), FEE);
        importBuilt(call);
        Block bond = bondBlock();
        assertImported(bond);
        handler.armed = true;
        assertThrows(IllegalStateException.class, () -> mineMain(List.of(hashLow(call.block()), hashLow(bond))));
        long failed = blockchain.getXdagStats().nmain;
        kernel.getBlockStore().saveXdagStatus(blockchain.getXdagStats()); // as a later import would have

        // restart in repair mode
        kernel.setRepairMode(true);
        MockBlockchain repair = new MockBlockchain(kernel);
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertFalse(report.clean());
        assertEquals(failed - 1, report.repairTarget());

        ChainRepairTool.Outcome dry = ChainRepairTool.repair(kernel, repair, report, true, false);
        assertTrue(dry.dryRun());
        assertEquals(failed, repair.getXdagStats().nmain);      // nothing changed

        ChainRepairTool.Outcome done = ChainRepairTool.repair(kernel, repair, report, false, false);
        assertFalse(done.dryRun());
        assertTrue(done.after().describe(), done.after().clean());
        assertEquals(failed - 1, repair.getXdagStats().nmain);
        assertEquals(failed - 1, kernel.getBlockStore().getLastCompletedMain());
        assertEquals("records of the failed height are gone, earlier ones intact", before, chainState());

        // the node resumes: the same blocks are re-confirmed at the same height with the handler quiet
        kernel.setRepairMode(false);
        blockchain = repair;
        Block again = mineMain(List.of());
        assertEquals(failed, heightOf(again));
        confirm(call.block());
        assertEquals(1L, chainStore.getCallCount(chainId, failed));
        assertTrue(kernel.getConsistencyReport() == report || ChainConsistencyCheck.run(kernel.getBlockStore(),
                repair.getXdagStats(), config.getChainSpec(), 128).clean());
    }

    @Test
    public void legacyStuckBlockIsPatchedAndUnwound() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        long h = blockchain.getXdagStats().nmain - 1;
        blockchain.updateBlockRef(blockchain.getBlockByHeight(h), null);
        kernel.setRepairMode(true);
        MockBlockchain repair = new MockBlockchain(kernel);
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        assertEquals(h - 1, report.repairTarget());
        ChainRepairTool.Outcome done = ChainRepairTool.repair(kernel, repair, report, false, false);
        assertTrue(done.after().clean());
        assertEquals(h - 1, repair.getXdagStats().nmain);
    }

    @Test
    public void deepTargetNeedsForce() {
        for (int i = 0; i < 6; i++) {
            mineMain(List.of());
        }
        kernel.getBlockStore().saveLastCompletedMain(1L);
        kernel.setRepairMode(true);
        MockBlockchain repair = new MockBlockchain(kernel);
        ChainConsistencyCheck.Report report = kernel.getConsistencyReport();
        ChainRepairTool.Outcome refused = ChainRepairTool.repair(kernel, repair, report, false, false);
        assertTrue(refused.refused());
        ChainRepairTool.Outcome forced = ChainRepairTool.repair(kernel, repair, report, false, true);
        assertFalse(forced.refused());
        assertEquals(1L, repair.getXdagStats().nmain);
    }
}
```

`kernel.getChainL1Store().getRaw(k)`：若 `ChainL1Store` 没有原始读接口，测试改用 `chainStore.stateHash()` 比较（before/after 的哈希相等即可），并删掉 `chainState()`。

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.repair.ChainRepairToolTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChainRepairTool`。

- [x] **Step 3: `BlockchainImpl.repairUnwindTo`**

`BlockchainImpl` 里 `unWindMain` 之后加：

```java
    /**
     * SP0b-1 repair: unwinds every main block above {@code height} the same way a fork does
     * (unWindMain, then re-flagging the surviving branch with updateNewChain so checkNewMain can
     * re-confirm the unwound blocks later). Only meaningful in repair mode, where the check-main
     * loop is not running; callers persist stats afterwards.
     */
    public void repairUnwindTo(long height) {
        synchronized (this) {
            Block target = blockStore.getBlockByHeight(height);
            if (target == null) {
                throw new IllegalStateException("no main block stored at height " + height);
            }
            unWindMain(target);
            Block top = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), true);
            if (top != null) {
                updateNewChain(top, true);
            }
            blockStore.saveXdagStatus(xdagStats);
            blockStore.saveXdagTopStatus(xdagTopStatus);
        }
    }
```

- [x] **Step 4: `ChainRepairTool`**

`src/main/java/io/xdag/chain/repair/ChainRepairTool.java`：

```java
package io.xdag.chain.repair;

import io.xdag.Kernel;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Offline repair for a main chain the consistency check refused: give every stuck main block the
 * self reference setMain would have written, unwind to the last complete height with the ordinary
 * fork machinery, then re-check. Blocks stay in the store; the node re-confirms them on its own
 * once it starts normally.
 */
@Slf4j
public final class ChainRepairTool {

    public record Outcome(boolean dryRun, boolean refused, long target, ChainConsistencyCheck.Report after) {
    }

    private ChainRepairTool() {
    }

    public static Outcome repair(Kernel kernel, BlockchainImpl blockchain, ChainConsistencyCheck.Report report,
                                 boolean dryRun, boolean force) {
        long target = report.repairTarget();
        int window = kernel.getConfig().getChainSpec().getChainConsistencyWindow();
        System.out.println(report.describe());
        System.out.println("repair plan: unwind main chain from " + report.nmain() + " to " + target
                + " (" + (report.nmain() - target) + " main block(s)), then re-check");
        if (report.clean()) {
            System.out.println("nothing to repair");
            return new Outcome(dryRun, false, target, report);
        }
        if (dryRun) {
            return new Outcome(true, false, target, report);
        }
        if (target < report.nmain() - window && !force) {
            System.out.println("refusing: target is more than " + window + " heights below the tip; pass --force");
            return new Outcome(false, true, target, report);
        }
        for (ChainConsistencyCheck.Stuck s : report.stuck()) {
            if (s.hash() == null) {
                continue;
            }
            Block m = blockchain.getBlockByHash(s.hash(), false);
            if (m != null && m.getInfo().getRef() == null) {
                blockchain.updateBlockRef(m, new Address(m));
                log.info("repair: set self ref on stuck main block {} at height {}", s.hash(), s.height());
            }
        }
        blockchain.repairUnwindTo(target);
        kernel.getBlockStore().saveLastCompletedMain(target);
        ChainConsistencyCheck.Report after = ChainConsistencyCheck.run(kernel.getBlockStore(),
                blockchain.getXdagStats(), kernel.getConfig().getChainSpec(), window);
        kernel.setConsistencyReport(after);
        System.out.println(after.describe());
        return new Outcome(false, false, target, after);
    }
}
```

注意 `repairUnwindTo` 会经由 `unSetMain` 把标记写到 `height − 1`……最后 `saveLastCompletedMain(target)` 再统一写一次即可。`getBlockByHash(hash, false)` 得到的 `BlockInfo` 足够 `updateBlockRef`。

- [x] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.repair.ChainRepairToolTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 3, Failures: 0`。若第一个测试在 `confirm(call.block())` 处失败，检查 `repairUnwindTo` 后 `updateNewChain` 是否把 `BI_MAIN_CHAIN` 重新标到了被回滚的分支上（`checkNewMain` 只提升 `BI_MAIN_CHAIN` 块）；若 `updateNewChain(top, true)` 在 `top` 已带 `BI_MAIN_CHAIN` 时提前返回，改为从 `getMaxDiffLink(top, true)` 起调用。

- [x] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/chain/repair/ChainRepairTool.java src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/chain/repair/ChainRepairToolTest.java
git commit -m "Add the offline chain repair tool: patch stuck refs and unwind to the last complete height

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: CLI `--repairchain [--dry-run] [--force]`

**Files:**
- Modify: `src/main/java/io/xdag/cli/XdagOption.java`
- Modify: `src/main/java/io/xdag/cli/XdagCli.java`
- Test: `src/test/java/io/xdag/cli/XdagCliTest.java`（追加）

- [x] **Step 1: 写失败测试**（追加到 `XdagCliTest`）

```java
    @Test
    public void repairChainDispatchesToTheTool() throws Exception {
        XdagCli xdagCLI = spy(new XdagCli());
        xdagCLI.setConfig(new DevnetConfig());
        doReturn(0).when(xdagCLI).repairChain(anyBoolean(), anyBoolean());

        xdagCLI.start(new String[]{"--repairchain", "dry-run"});
        verify(xdagCLI).repairChain(true, false);

        xdagCLI.start(new String[]{"--repairchain", "force"});
        verify(xdagCLI).repairChain(false, true);

        xdagCLI.start(new String[]{"--repairchain"});
        verify(xdagCLI).repairChain(false, false);
    }
```

同时把 `testHelp` 的期望文本加一行（按字母序落在 `--makesnapshot` 之后）：

```
                    --repairchain <mode>              unwind to the last complete main height; mode = dry-run|force
```

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.cli.XdagCliTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: method repairChain(boolean,boolean)`。

- [x] **Step 3: 实现**

`XdagOption.java`：`MAKE_SNAPSHOT("makesnapshot")` 改为 `MAKE_SNAPSHOT("makesnapshot"),` 并追加 `REPAIR_CHAIN("repairchain");`。

`XdagCli` 选项区（`addOption(makeSnapshotOption);` 之后）：

```java
        Option repairChainOption = Option.builder()
                .longOpt(XdagOption.REPAIR_CHAIN.toString())
                .desc("unwind to the last complete main height; mode = dry-run|force")
                .hasArg(true).optionalArg(true).argName("mode").type(String.class)
                .build();
        addOption(repairChainOption);
```

分发（`MAKE_SNAPSHOT` 分支之后、`else {` 之前）：

```java
        } else if (cmd.hasOption(XdagOption.REPAIR_CHAIN.toString())) {
            String mode = cmd.getOptionValue(XdagOption.REPAIR_CHAIN.toString());
            boolean dryRun = mode != null && mode.trim().equals("dry-run");
            boolean force = mode != null && mode.trim().equals("force");
            int code = repairChain(dryRun, force);
            if (code != 0) {
                exit(code);
            }
```

方法（`makeSnapshot` 之后）：

```java
    /**
     * SP0b-1: offline main chain repair. Opens the stores exactly like a node would, constructs the
     * blockchain in repair mode (the consistency check reports instead of throwing and the check-main
     * loop stays off), then hands over to {@link ChainRepairTool}. Exit codes: 0 clean, 1 still
     * inconsistent after repair, 2 refused (deep target without force).
     */
    protected int repairChain(boolean dryRun, boolean force) {
        Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : null;
        if (wallet == null) {
            System.out.println("wallet not found or locked; --repairchain needs the node wallet");
            return 2;
        }
        Kernel kernel = new Kernel(getConfig(), wallet);
        kernel.setRepairMode(true);
        RocksdbFactory dbFactory = new RocksdbFactory(getConfig());
        try {
            BlockStore blockStore = new BlockStoreImpl(
                    dbFactory.getDB(DatabaseName.INDEX),
                    dbFactory.getDB(DatabaseName.BLOCK),
                    dbFactory.getDB(DatabaseName.TIME),
                    dbFactory.getDB(DatabaseName.TXHISTORY));
            blockStore.start();
            AddressStore addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
            addressStore.start();
            OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
            orphanBlockStore.start();
            ChainL1Store chainStore = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
            chainStore.start();
            kernel.setBlockStore(blockStore);
            kernel.setAddressStore(addressStore);
            kernel.setOrphanBlockStore(orphanBlockStore);
            kernel.setChainL1Store(chainStore);
            BlockchainImpl blockchain = new BlockchainImpl(kernel);
            ChainRepairTool.Outcome outcome = ChainRepairTool.repair(kernel, blockchain,
                    kernel.getConsistencyReport(), dryRun, force);
            blockchain.stopCheckMain();
            chainStore.stop();
            if (outcome.refused()) {
                return 2;
            }
            return outcome.after().clean() ? 0 : 1;
        } finally {
            dbFactory.close();
        }
    }
```

（`BlockStoreImpl` 的参数顺序沿用 `Kernel.testStart` 的写法；`blockchain.stopCheckMain()` 若不存在则省略——repair 模式下未启动。）

- [x] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.cli.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿。

- [x] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/cli/XdagOption.java src/main/java/io/xdag/cli/XdagCli.java src/test/java/io/xdag/cli/XdagCliTest.java
git commit -m "Add the --repairchain command

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: G7 `copyFile` 抛异常 + G8 `RocksdbKVSource.init()` 失败路径释放资源

**Files:**
- Modify: `src/main/java/io/xdag/cli/XdagCli.java`（`copyFile`）
- Modify: `src/main/java/io/xdag/db/rocksdb/RocksdbKVSource.java`（`init`）
- Test: `src/test/java/io/xdag/cli/CopyDirFailureTest.java`、`src/test/java/io/xdag/db/rocksdb/RocksdbInitFailureTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.xdag.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CopyDirFailureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void unreadableSourceFileFails() throws Exception {
        File src = tmp.newFolder("src");
        File f = new File(src, "a.sst");
        Files.writeString(f.toPath(), "x", StandardCharsets.UTF_8);
        File dstDir = tmp.newFolder("dst");
        // destination is a directory where a file is expected → FileOutputStream fails
        File clash = new File(dstDir, "a.sst");
        assertTrue(clash.mkdir());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> XdagCli.copyDir(src.getAbsolutePath(), dstDir.getAbsolutePath()));
        assertTrue(e.getMessage(), e.getMessage().contains("a.sst"));
    }

    @Test
    public void copiesNestedDirectories() throws Exception {
        File src = tmp.newFolder("s");
        File nested = new File(src, "n");
        assertTrue(nested.mkdir());
        Files.writeString(new File(nested, "f").toPath(), "hello", StandardCharsets.UTF_8);
        File dst = new File(tmp.getRoot(), "d/e");
        XdagCli.copyDir(src.getAbsolutePath(), dst.getAbsolutePath());
        assertEquals("hello", Files.readString(new File(dst, "n/f").toPath(), StandardCharsets.UTF_8));
    }
}
```

```java
package io.xdag.db.rocksdb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import io.xdag.config.DevnetConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RocksdbInitFailureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void failedOpenLeavesSourceDeadAndReusable() throws Exception {
        DevnetConfig config = new DevnetConfig();
        File storeDir = tmp.newFolder("store");
        config.getNodeSpec().setStoreDir(storeDir.getAbsolutePath());
        // a regular FILE where RocksDB expects the database directory → open fails
        Files.writeString(new File(storeDir, "BROKEN").toPath(), "not a db", StandardCharsets.UTF_8);
        RocksdbKVSource src = new RocksdbKVSource("BROKEN");
        src.setConfig(config);
        assertThrows(RuntimeException.class, src::init);
        assertFalse(src.isAlive());
        src.close(); // must be a no-op, not a crash, after a failed init
        assertFalse(src.isAlive());
    }
}
```

- [x] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest='io.xdag.cli.CopyDirFailureTest,io.xdag.db.rocksdb.RocksdbInitFailureTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `CopyDirFailureTest.unreadableSourceFileFails` 失败（当前 `copyFile` 只打印堆栈，不抛）；`RocksdbInitFailureTest` 可能已通过或因 `close()` 触碰已释放对象失败——记录实际结果。

- [x] **Step 3: 实现**

`XdagCli.copyFile` 的 `catch(IOException e)` 改为：

```java
        } catch (IOException e) {
            throw new IllegalStateException("snapshot copy failed: " + sourcePath + " -> " + newPath, e);
        }
```

`RocksdbKVSource.init()`：在外层 `try (Options options = new Options())` 里、`db = RocksDB.open(...)` 失败的 `catch (RocksDBException e)` 中，抛出前释放已建的 `readOpts`：

```java
                    } catch (RocksDBException e) {
                        log.error(e.getMessage(), e);
                        if (readOpts != null) {
                            readOpts.close();
                            readOpts = null;
                        }
                        throw new RuntimeException("Failed to initialize database", e);
                    }
```

同样在 `catch (IOException ioe)` 分支加同一段释放。（`Options` 本身由 try-with-resources 释放；`tableCfg`/`LRUCache`/`BloomFilter` 随 `options` 释放。）

- [x] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest='io.xdag.cli.CopyDirFailureTest,io.xdag.db.rocksdb.RocksdbInitFailureTest,io.xdag.cli.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿。

- [x] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/cli/XdagCli.java src/main/java/io/xdag/db/rocksdb/RocksdbKVSource.java src/test/java/io/xdag/cli/CopyDirFailureTest.java src/test/java/io/xdag/db/rocksdb/RocksdbInitFailureTest.java
git commit -m "Fail loudly on a snapshot copy error and release read options on a failed RocksDB open

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: G9 `makeSnapshot` 端到端测试

**Files:**
- Modify: `src/test/java/io/xdag/chain/l1/ChainL1TestBase.java`（`rootDir`/`storeDir` 对齐）
- Test: `src/test/java/io/xdag/cli/MakeSnapshotEndToEndTest.java`

- [x] **Step 1: 基座对齐 `rootDir`**

`ChainL1TestBase.setUpLane` 开头的两行改为：

```java
        String rootDir = root.newFolder("node").getAbsolutePath();
        config.setRootDir(rootDir);                                   // XdagCli.makeSnapshot derives paths from it
        config.getNodeSpec().setStoreDir(rootDir + "/rocksdb/xdagdb"); // RocksdbKVSource derives paths from this
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
```

跑 `mvn -q -Dtest='io.xdag.chain.**.*Test' ... test` 确认既有基座测试仍全绿。

- [x] **Step 2: 写失败测试**

```java
package io.xdag.cli;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1SnapshotGate;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class MakeSnapshotEndToEndTest extends ChainL1TestBase {

    @Test
    public void makeSnapshotWritesThreeDirectoriesThatBootAnotherNode() throws Exception {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        ChainBlockBuilder.Built deploy = deployNewChain(payload(700, 61), payload(10, 62));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertTrue(chainStore.hasChain(chainId));
        Bytes32 expectedHash = chainStore.stateHash();
        long height = blockchain.getXdagStats().nmain;

        // release the stores so the CLI can open them (same directories)
        chainStore.stop();
        dbFactory.close();
        dbFactory = null;
        chainStore = null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            XdagCli cli = spy(new XdagCli());
            cli.setConfig(config);
            cli.makeSnapshot(false);
        } finally {
            System.setOut(old);
        }
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed, printed.contains("snapshot height: " + height));
        assertTrue(printed, printed.contains("next start frame:"));
        assertTrue(printed, printed.contains("chain state snapshot written to"));

        Path snap = Paths.get(config.getNodeSpec().getStoreDir(), "SNAPSHOT");
        assertTrue(Files.isDirectory(snap.resolve("BLOCKS")));
        assertTrue(Files.isDirectory(snap.resolve("ADDRESS")));
        assertTrue(Files.isDirectory(snap.resolve("CHAIN_L1")));

        RocksdbKVSource snapChain = new RocksdbKVSource(ChainL1SnapshotGate.SNAPSHOT_DB_NAME);
        snapChain.setConfig(config);
        snapChain.init();
        try {
            assertEquals(expectedHash, Bytes32.wrap(snapChain.get(ChainL1Keys.SNAPSHOT_HASH_KEY)));
        } finally {
            snapChain.close();
        }

        // a second node bootstraps from the shipped directory
        String otherRoot = root.newFolder("other").getAbsolutePath();
        Config other = new DevnetConfig();
        other.setRootDir(otherRoot);
        other.getNodeSpec().setStoreDir(otherRoot + "/rocksdb/xdagdb");
        other.getChainSpec().setChainActivationHeight(0);
        Files.createDirectories(Paths.get(other.getNodeSpec().getStoreDir(), "SNAPSHOT"));
        XdagCli.copyDir(snap.resolve("CHAIN_L1").toString(),
                Paths.get(other.getNodeSpec().getStoreDir(), "SNAPSHOT", "CHAIN_L1").toString());
        RocksdbKVSource otherSrc = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        otherSrc.setConfig(other);
        ChainL1Store otherStore = new ChainL1Store(otherSrc);
        otherStore.start();
        try {
            ChainL1SnapshotGate.checkAndImport(other, height, otherStore);
            assertEquals(expectedHash, otherStore.stateHash());
            assertTrue(otherStore.hasChain(chainId));
        } finally {
            otherStore.stop();
        }
    }
}
```

- [x] **Step 3: 运行，确认失败或通过**

Run: `mvn -q -Dtest=io.xdag.cli.MakeSnapshotEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 首次运行可能因 `makeSnapshot` 用 `getRootDir() + "/rocksdb/xdagdb/..."` 路径而与基座目录不一致失败——Step 1 的对齐正是为此；若仍失败，把失败信息原样记入报告后修正测试的路径假设（不改 `makeSnapshot` 的路径推导）。`tearDownChain` 在 `dbFactory == null` 时应跳过关闭（基座已判空）。

- [x] **Step 4: 通过后提交**

```bash
git add src/test/java/io/xdag/chain/l1/ChainL1TestBase.java src/test/java/io/xdag/cli/MakeSnapshotEndToEndTest.java
git commit -m "Test makeSnapshot end to end across the three snapshot directories

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: G10 随机 reorg 属性测试

**Files:**
- Create: `src/test/java/io/xdag/chain/l1/ReorgScenario.java`
- Test: `src/test/java/io/xdag/chain/l1/ChainL1ReorgPropertyTest.java`

- [x] **Step 1: 场景生成器**

```java
package io.xdag.chain.l1;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure data: a seeded random sequence of chain operations grouped by main height, plus a fork plan. */
public final class ReorgScenario {

    public enum Op { DEPLOY_NEW, DEPLOY_JOIN, CALL_HIT, CALL_MISS, PLAIN, CALL_LOWFEE, DEPLOY_BADGAS }

    /** One operation: which sender key index and, for chain-targeting ops, which earlier chain (index into created chains). */
    public record Step(Op op, int sender, int chainRef, int payloadSeed) {
    }

    public final long seed;
    public final List<List<Step>> heights;   // ops per main height, in order
    public final int forkAt;                 // index into heights (fork point = main block confirming heights[forkAt])
    public final int branchLength;           // competing branch length

    private ReorgScenario(long seed, List<List<Step>> heights, int forkAt, int branchLength) {
        this.seed = seed;
        this.heights = heights;
        this.forkAt = forkAt;
        this.branchLength = branchLength;
    }

    private static final Op[] WEIGHTED = {
            Op.DEPLOY_NEW, Op.DEPLOY_NEW, Op.DEPLOY_JOIN, Op.DEPLOY_JOIN,
            Op.CALL_HIT, Op.CALL_HIT, Op.CALL_HIT, Op.CALL_HIT, Op.CALL_MISS, Op.CALL_MISS,
            Op.PLAIN, Op.PLAIN, Op.PLAIN, Op.CALL_LOWFEE, Op.DEPLOY_BADGAS };

    public static ReorgScenario generate(long seed) {
        Random r = new Random(seed);
        int n = 3 + r.nextInt(6);                 // 3..8 heights
        List<List<Step>> hs = new ArrayList<>();
        int chains = 0;
        int payload = 1;
        for (int h = 0; h < n; h++) {
            int ops = r.nextInt(7);               // 0..6
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i < ops; i++) {
                Op op = WEIGHTED[r.nextInt(WEIGHTED.length)];
                if (chains == 0 && (op == Op.DEPLOY_JOIN || op == Op.CALL_HIT || op == Op.CALL_MISS || op == Op.CALL_LOWFEE)) {
                    op = Op.DEPLOY_NEW;           // nothing to target yet
                }
                int ref = chains == 0 ? -1 : r.nextInt(chains);
                steps.add(new Step(op, r.nextInt(4), ref, payload++));
                if (op == Op.DEPLOY_NEW) {
                    chains++;
                }
            }
            hs.add(steps);
        }
        int forkAt = r.nextInt(Math.max(1, n / 2));
        int after = n - forkAt;                   // main blocks above the fork point (plus confirmations)
        int branch = 2 * (after + 6) + 2;         // strictly heavier than anything branch A can build
        return new ReorgScenario(seed, hs, forkAt, branch);
    }

    @Override
    public String toString() {
        return "ReorgScenario{seed=" + seed + ", heights=" + heights + ", forkAt=" + forkAt + ", branch=" + branchLength + "}";
    }
}
```

- [x] **Step 2: 属性测试**

```java
package io.xdag.chain.l1;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * P3 as a property: for a seeded random operation sequence, apply → fork/unwind → re-apply leaves
 * CHAIN_L1 byte-identical to applying the same sequence on a fresh node, and the balances/nonces of
 * every address involved agree. Runs two seeds by default; -Dxdag.reorg.full=true runs all eight,
 * -Dxdag.reorg.seeds=a,b,c appends seeds.
 */
public class ChainL1ReorgPropertyTest extends ChainL1TestBase {

    private static final long[] DEFAULT_SEEDS = {1, 2, 3, 4, 5, 6, 7, 8};

    private static long[] seeds() {
        List<Long> out = new ArrayList<>();
        int n = Boolean.getBoolean("xdag.reorg.full") ? DEFAULT_SEEDS.length : 2;
        for (int i = 0; i < n; i++) {
            out.add(DEFAULT_SEEDS[i]);
        }
        String extra = System.getProperty("xdag.reorg.seeds");
        if (extra != null && !extra.isBlank()) {
            for (String s : extra.split(",")) {
                out.add(Long.parseLong(s.trim()));
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    /** Everything a run of one scenario produced that the property compares. */
    private record Outcome(Map<String, String> chainState, Map<String, String> balances) {
    }

    private final ECKeyPair[] senders = new ECKeyPair[4];
    private final UInt64[] nonces = new UInt64[4];
    private final List<Bytes> chainIds = new ArrayList<>();
    private final List<Bytes> contracts = new ArrayList<>();
    private final List<Bytes> codeHashes = new ArrayList<>();

    private void fundSenders() {
        for (int i = 0; i < 4; i++) {
            senders[i] = ECKeyPair.generate();
            nonces[i] = UInt64.ONE;
            addressStore.updateBalance(senders[i].toAddress().toArray(), XAmount.of(500, XUnit.XDAG));
        }
    }

    private UInt64 nonce(int s) {
        UInt64 n = nonces[s];
        nonces[s] = n.add(UInt64.ONE);
        return n;
    }

    /** Builds the block(s) for one step; returns the paying block's hashLow to link, or null for a chunk-only failure. */
    private Block execute(ReorgScenario.Step step) {
        ECKeyPair key = senders[step.sender];
        Bytes args = payload(20, step.payloadSeed);
        switch (step.op) {
            case DEPLOY_NEW, DEPLOY_BADGAS -> {
                Bytes wasm = payload(600, step.payloadSeed);
                ChainConfigExt cfg = step.op == ReorgScenario.Op.DEPLOY_BADGAS
                        ? new ChainConfigExt(1L, 32L, 0L) : CFG;
                int chunks = ChainBlockBuilder.deployNewChainChunks(wasm.size(), args.size());
                XAmount fee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), chunks);
                ChainBlockBuilder.Built b = ChainBlockBuilder.deployNewChain(config, txTime(), key, nonce(step.sender),
                        fee, wasm, cfg, args, 1000L).value();
                importBuilt(b);
                if (step.op == ReorgScenario.Op.DEPLOY_NEW) {
                    chainIds.add(io.xdag.chain.l1.ChainIds.chainIdOf(b.block().getHash()));
                    contracts.add(io.xdag.chain.l1.ChainIds.contractIdOf(b.block().getHash()));
                    codeHashes.add(HashUtils.sha256(wasm));
                }
                return b.block();
            }
            case DEPLOY_JOIN -> {
                Bytes chainId = chainIds.get(step.chainRef);
                ChainBlockBuilder.Built b = ChainBlockBuilder.deployIntoChain(config, txTime(), key, nonce(step.sender),
                        chainId, ONE_XDAG, FEE, null, org.apache.tuweni.bytes.Bytes32.wrap(codeHashes.get(step.chainRef).toArray()),
                        args, 1L).value();
                importBuilt(b);
                return b.block();
            }
            case CALL_HIT, CALL_LOWFEE -> {
                Bytes chainId = chainIds.get(step.chainRef);
                Bytes bigArgs = step.op == ReorgScenario.Op.CALL_LOWFEE ? payload(1000, step.payloadSeed) : args;
                XAmount fee = step.op == ReorgScenario.Op.CALL_LOWFEE ? XAmount.of(20, XUnit.MILLI_XDAG) : FEE;
                ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, txTime(), key, nonce(step.sender), chainId,
                        contracts.get(step.chainRef), 1, 100L, ONE_XDAG, fee, bigArgs).value();
                importBuilt(b);
                return b.block();
            }
            case CALL_MISS -> {
                Bytes chainId = chainIds.get(step.chainRef);
                ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, txTime(), key, nonce(step.sender), chainId,
                        Bytes.repeat((byte) 0x5a, 20), 1, 100L, ONE_XDAG, FEE, args).value();
                importBuilt(b);
                return b.block();
            }
            case PLAIN -> {
                Address from = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()), XDAG_FIELD_INPUT, true);
                Bytes to = chainIds.isEmpty() ? Bytes.repeat((byte) 0x6b, 20) : chainIds.get(0);
                Address dst = new Address(BytesUtils.arrayToByte32(to.toArray()), XDAG_FIELD_OUTPUT, true);
                Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                        config, key, txTime(), from, dst, ONE_XDAG, nonce(step.sender)).toBytes()));
                assertImported(plain);
                return plain;
            }
            default -> throw new IllegalStateException(step.op.name());
        }
    }

    /** Applies the scenario's heights in order; returns the main blocks mined (one per height, plus confirmations). */
    private List<Block> apply(ReorgScenario sc) {
        List<Block> mains = new ArrayList<>();
        for (List<ReorgScenario.Step> steps : sc.heights) {
            List<org.apache.tuweni.bytes.Bytes32> refs = new ArrayList<>();
            // steps are sorted by sender so refs stay in ascending nonce order per sender
            List<ReorgScenario.Step> ordered = new ArrayList<>(steps);
            ordered.sort((a, b) -> Integer.compare(a.sender(), b.sender()));
            Block last = null;
            for (ReorgScenario.Step s : ordered) {
                last = execute(s);
                refs.add(hashLow(last));
            }
            Block main = mineMain(refs);
            mains.add(main);
            if (last != null) {
                confirm(last);
            } else {
                mineMain(List.of());
            }
        }
        return mains;
    }

    private Outcome snapshot() {
        Map<String, String> state = new LinkedHashMap<>();
        for (byte[] k : chainStore.sortedKeys()) {
            if ((k[0] & 0xff) >= 0xfe) {
                continue;
            }
            state.put(Bytes.wrap(k).toHexString(), Bytes.wrap(kernel.getChainL1Store().getRaw(k)).toHexString());
        }
        Map<String, String> balances = new LinkedHashMap<>();
        for (ECKeyPair s : senders) {
            byte[] a = s.toAddress().toArray();
            balances.put(Bytes.wrap(a).toHexString(), addressStore.getBalanceByAddress(a) + "/" + addressStore.getExecutedNonceNum(a));
        }
        for (Bytes c : chainIds) {
            balances.put(c.toHexString(), addressStore.getBalanceByAddress(c.toArray()).toString());
        }
        return new Outcome(state, balances);
    }

    @Test
    public void reorgThenReapplyEqualsDirectApply() {
        for (long seed : seeds()) {
            ReorgScenario sc = ReorgScenario.generate(seed);
            // --- run A: apply, fork, re-apply (this fixture)
            resetFixtureForScenario();
            fundSenders();
            for (int i = 0; i < 4; i++) {
                mineMain(List.of());
            }
            Block forkPoint = mineMain(List.of());
            long forkTime = generateTime;
            List<Block> mains = apply(sc);
            Outcome applied = snapshot();
            rewindTo(forkPoint, forkTime);
            for (int i = 0; i < sc.branchLength; i++) {
                mineMain(List.of(), false);
            }
            assertTrue(sc + ": fork branch did not overtake",
                    Arrays.equals(hashLow(blockchain.getBlockByHash(org.apache.tuweni.bytes.Bytes32.wrap(
                            blockchain.getXdagTopStatus().getTop()), false)).toArray(), blockchain.getXdagTopStatus().getTop()));
            assertEquals(sc + ": CHAIN_L1 must be empty after the reorg", 0, snapshot().chainState().size());
            // re-link every paying block in the original per-height order on the new branch
            // (the blocks are still in the store; the mains list only marks the heights)
            replay(sc);
            Outcome reapplied = snapshot();
            // --- run B: direct apply on a fresh fixture
            tearDownChain();
            setUpLane();
            resetFixtureForScenario();
            fundSendersSame(applied);
            for (int i = 0; i < 5; i++) {
                mineMain(List.of());
            }
            apply(sc);
            Outcome direct = snapshot();
            assertEquals(sc + ": CHAIN_L1 differs after reorg/re-apply", direct.chainState(), reapplied.chainState());
            assertEquals(sc + ": balances/nonces differ", direct.balances(), reapplied.balances());
        }
    }
}
```

实施者需要补齐三个助手（它们的具体写法取决于基座字段的可见性，规格允许在此定稿）：

- `resetFixtureForScenario()`：清空 `chainIds/contracts/codeHashes`，`nonces` 归 1。
- `replay(sc)`：在新分支上按原高度顺序重新 `mineMain(refs)` + `confirm`，`refs` 是 `apply` 时记录的每高度付费块 hashLow（把 `apply` 改为同时记录 `List<List<Bytes32>> refsPerHeight` 字段）。
- `fundSendersSame(applied)`：run B 必须用**同一组密钥与同一注资**才能得到相同的块字节——把 `senders` 改为按种子派生：`ECKeyPair.fromPrivateKey(Bytes32 of sha256("reorg-sender" ‖ seed ‖ i))`（用 `HashUtils.sha256` 与 `ECKeyPair.fromPrivateKey` 的既有签名），run A/B 各调一次同一个 `fundSenders(seed)`；于是 `fundSendersSame` 就是 `fundSenders(seed)`。

如果 `kernel.getChainL1Store().getRaw(k)` 不存在，比较改用 `chainStore.stateHash()`（相等即通过），并只在失败时打印键集差异。

- [x] **Step 3: 运行**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1ReorgPropertyTest -Dsurefire.failIfNoSpecifiedTests=false test`（默认 2 个种子，约 1–2 分钟）；再 `-Dxdag.reorg.full=true` 跑全部 8 个一次并记录用时。
Expected: 全部通过。失败时输出包含 `ReorgScenario{seed=…}` 全文；先用 `-Dxdag.reorg.seeds=<seed>` 复现，再判断是场景生成器的非法组合（例如同一发送方余额耗尽 → `importBuilt` 断言失败）还是真正的对称性缺陷；前者修生成器（例如把每个发送方每场景的操作数限制在 8 以内），后者停下并报告。

- [x] **Step 4: 提交**

```bash
git add src/test/java/io/xdag/chain/l1/ReorgScenario.java src/test/java/io/xdag/chain/l1/ChainL1ReorgPropertyTest.java
git commit -m "Add the seeded reorg property test for CHAIN_L1 symmetry

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: L1 导入基准与基线文档

**Files:**
- Create: `src/test/java/io/xdag/chain/bench/BenchWorkload.java`
- Create: `src/test/java/io/xdag/chain/bench/ChainL1ImportBenchmarkTest.java`
- Create: `docs/benchmarks/2026-09-xx-l1-import-baseline.md`（日期用实际运行日）

- [x] **Step 1: 工作负载生成器**

```java
package io.xdag.chain.bench;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.config.Config;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

/** Pre-built synthetic import workload: a list of (chunks…, paying block) groups in import order. */
public final class BenchWorkload {

    public enum Kind { PLAIN, CALL_INLINE, CALL_CHAIN, DEPLOY }

    public record Item(Kind kind, List<Block> chunks, Block block) {
    }

    public final List<Item> items = new ArrayList<>();
    public final ECKeyPair[] senders;

    public BenchWorkload(Config config, long seed, int senderCount, int blocks, int[] mixPercent,
                         long txTime, Bytes chainId, Bytes contract, Bytes32 sharedCodeHash, ChainConfigExt cfg) {
        Random r = new Random(seed);
        senders = new ECKeyPair[senderCount];
        UInt64[] nonces = new UInt64[senderCount];
        for (int i = 0; i < senderCount; i++) {
            senders[i] = ECKeyPair.generate();
            nonces[i] = UInt64.ONE;
        }
        XAmount one = XAmount.of(1, XUnit.XDAG);
        XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);
        for (int n = 0; n < blocks; n++) {
            int s = r.nextInt(senderCount);
            UInt64 nonce = nonces[s];
            nonces[s] = nonce.add(UInt64.ONE);
            ECKeyPair key = senders[s];
            long t = txTime + n;                          // strictly increasing ticks inside one epoch
            int pick = r.nextInt(100);
            Kind kind = pick < mixPercent[0] ? Kind.PLAIN
                    : pick < mixPercent[0] + mixPercent[1] ? Kind.CALL_INLINE
                    : pick < mixPercent[0] + mixPercent[1] + mixPercent[2] ? Kind.CALL_CHAIN : Kind.DEPLOY;
            switch (kind) {
                case PLAIN -> {
                    Address from = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()), XDAG_FIELD_INPUT, true);
                    Address to = new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, true);
                    Block b = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(config, key, t, from, to, one, nonce).toBytes()));
                    items.add(new Item(kind, List.of(), b));
                }
                case CALL_INLINE -> {
                    ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, t, key, nonce, chainId, contract, 1, 100L, one, fee, payload(20, n)).value();
                    items.add(new Item(kind, b.chunks(), b.block()));
                }
                case CALL_CHAIN -> {
                    ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, t, key, nonce, chainId, contract, 1, 100L, one, fee, payload(1000, n)).value();
                    items.add(new Item(kind, b.chunks(), b.block()));
                }
                case DEPLOY -> {
                    // joins the existing chain by code hash: no 285-chunk code chain per deploy
                    ChainBlockBuilder.Built b = ChainBlockBuilder.deployIntoChain(config, t, key, nonce, chainId, one, fee, null, sharedCodeHash, payload(10, n), 1L).value();
                    items.add(new Item(kind, b.chunks(), b.block()));
                }
                default -> throw new IllegalStateException();
            }
        }
    }

    public int chunkCount() {
        return items.stream().mapToInt(i -> i.chunks().size()).sum();
    }
}
```

- [x] **Step 2: 基准测试**

```java
package io.xdag.chain.bench;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.net.ChannelManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Before;
import org.junit.Test;

/**
 * Black-box L1 import baseline. Skipped unless -Dxdag.bench=true. Prints a Markdown table and
 * writes target/bench/l1-import-<timestamp>.json; the numbers go into docs/benchmarks by hand.
 */
public class ChainL1ImportBenchmarkTest extends ChainL1TestBase {

    private static final int ROUNDS = 3;

    private int senders;
    private int blocks;
    private long seed;
    private int[] mix;
    private Bytes chainId;
    private Bytes contract;
    private org.apache.tuweni.bytes.Bytes32 codeHash;

    @Before
    public void benchGate() {
        assumeTrue("set -Dxdag.bench=true to run the import benchmark", Boolean.getBoolean("xdag.bench"));
        senders = Integer.getInteger("xdag.bench.senders", 64);
        blocks = Integer.getInteger("xdag.bench.blocks", 20_000);
        seed = Long.getLong("xdag.bench.seed", 20260917L);
        mix = Arrays.stream(System.getProperty("xdag.bench.mix", "60,25,10,5").split(",")).mapToInt(Integer::parseInt).toArray();
    }

    /** One confirmed chain with a shared code blob so CALL/DEPLOY items have a target. */
    private void prepareChain() {
        for (int i = 0; i < 4; i++) {
            mineMain(List.of());
        }
        Bytes wasm = payload(600, 7);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm, payload(10, 8));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        chainId = ChainIds.chainIdOf(deploy.block().getHash());
        contract = ChainIds.contractIdOf(deploy.block().getHash());
        codeHash = HashUtils.sha256(wasm);
    }

    private BenchWorkload workload() {
        BenchWorkload w = new BenchWorkload(config, seed, senders, blocks, mix, txTime(), chainId, contract, codeHash, CFG);
        for (ECKeyPair k : w.senders) {
            addressStore.updateBalance(k.toAddress().toArray(), XAmount.of(10_000, XUnit.XDAG));
        }
        return w;
    }

    private record Measure(String name, double blocksPerSec, double p50Micros, double p95Micros, long totalMillis) {
    }

    private Measure timeImport(String name, BenchWorkload w, Consumer<Block> importer) {
        long[] perBlock = new long[w.items.size()];
        long t0 = System.nanoTime();
        int i = 0;
        for (BenchWorkload.Item item : w.items) {
            for (int c = item.chunks().size() - 1; c >= 0; c--) {
                importer.accept(item.chunks().get(c));
            }
            long a = System.nanoTime();
            importer.accept(item.block());
            perBlock[i++] = System.nanoTime() - a;
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        Arrays.sort(perBlock);
        int all = w.items.size() + w.chunkCount();
        return new Measure(name, all * 1000.0 / Math.max(1, total), perBlock[perBlock.length / 2] / 1000.0,
                perBlock[(int) (perBlock.length * 0.95)] / 1000.0, total);
    }

    private void expectImported(Block b) {
        ImportResult r = blockchain.tryToConnect(b);
        assertTrue("import failed: " + r + " " + r.getErrorInfo(), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
    }

    @Test
    public void baseline() throws IOException {
        List<Measure> results = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) {
                tearDownChain();
                setUpLane();
            }
            prepareChain();
            BenchWorkload w = workload();
            results.add(timeImport("direct.r" + round, w, this::expectImported));
        }
        for (int round = 0; round < ROUNDS; round++) {
            tearDownChain();
            setUpLane();
            prepareChain();
            BenchWorkload w = workload();
            ChannelManager channels = mock(ChannelManager.class);
            when(channels.getActiveChannels()).thenReturn(List.of());
            kernel.setChannelMgr(channels);
            SyncManager sync = new SyncManager(kernel);
            results.add(timeImport("syncPath.r" + round, w, b -> {
                ImportResult r = sync.validateAndAddNewBlock(new BlockWrapper(b, 0, null));
                assertTrue(String.valueOf(r), r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
            }));
        }
        for (int round = 0; round < ROUNDS; round++) {
            tearDownChain();
            setUpLane();
            prepareChain();
            BenchWorkload w = workload();
            List<org.apache.tuweni.bytes.Bytes32> refs = new ArrayList<>();
            long t0 = System.nanoTime();
            for (BenchWorkload.Item item : w.items) {
                for (int c = item.chunks().size() - 1; c >= 0; c--) {
                    expectImported(item.chunks().get(c));
                }
                expectImported(item.block());
                refs.add(hashLow(item.block()));
                if (refs.size() == 2000) {
                    mineMain(refs);
                    refs.clear();
                }
            }
            if (!refs.isEmpty()) {
                mineMain(refs);
            }
            confirm(w.items.get(w.items.size() - 1).block());
            long total = (System.nanoTime() - t0) / 1_000_000;
            results.add(new Measure("confirmed.r" + round, (w.items.size() + w.chunkCount()) * 1000.0 / Math.max(1, total), 0, 0, total));
        }
        // phase replay on the last workload's blocks (cost attribution, not a consensus path)
        tearDownChain();
        setUpLane();
        prepareChain();
        BenchWorkload w = workload();
        List<byte[]> raw = new ArrayList<>();
        for (BenchWorkload.Item item : w.items) {
            raw.add(item.block().toBytes());
        }
        results.add(phase("parse", raw.size(), () -> {
            for (byte[] b : raw) {
                new Block(new XdagBlock(b));
            }
        }));
        List<Block> parsed = new ArrayList<>();
        for (byte[] b : raw) {
            parsed.add(new Block(new XdagBlock(b)));
        }
        results.add(phase("signature", parsed.size(), () -> {
            for (Block b : parsed) {
                blockchain.canUseInput(b);
            }
        }));
        results.add(phase("refLookup", parsed.size(), () -> {
            for (Block b : parsed) {
                for (io.xdag.core.Address a : b.getLinks()) {
                    if (!a.getIsAddress()) {
                        kernel.getBlockStore().getBlockInfoByHash(a.getAddress());
                    }
                }
            }
        }));
        RocksdbFactory scratch = new RocksdbFactory(config);
        BlockStore scratchStore = new BlockStoreImpl(scratch.getDB(DatabaseName.INDEX), scratch.getDB(DatabaseName.TIME),
                scratch.getDB(DatabaseName.BLOCK), scratch.getDB(DatabaseName.TXHISTORY));
        // NOTE: uses a different config storeDir if the factory would collide with the fixture's; see Step 3
        results.add(phase("persist", parsed.size(), () -> {
            for (Block b : parsed) {
                scratchStore.saveBlock(b);
            }
        }));
        scratch.close();
        report(results, w);
    }

    private Measure phase(String name, int count, Runnable body) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            body.run();
            best = Math.min(best, System.nanoTime() - t0);
        }
        return new Measure("phase." + name, count * 1e9 / best, best / 1000.0 / count, 0, best / 1_000_000);
    }

    private void report(List<Measure> results, BenchWorkload w) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("| measure | blocks/s | p50 µs | p95 µs | total ms |\n|---|---|---|---|---|\n");
        for (Measure m : results) {
            md.append(String.format("| %s | %.0f | %.1f | %.1f | %d |%n", m.name(), m.blocksPerSec(), m.p50Micros(), m.p95Micros(), m.totalMillis()));
        }
        System.out.println("L1 import benchmark: senders=" + senders + " blocks=" + blocks + " chunks=" + w.chunkCount()
                + " mix=" + Arrays.toString(mix) + " seed=" + seed);
        System.out.println(md);
        Path dir = Paths.get("target", "bench");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        StringBuilder json = new StringBuilder("{\"jvm\":\"" + System.getProperty("java.version") + "\",\"cores\":"
                + Runtime.getRuntime().availableProcessors() + ",\"senders\":" + senders + ",\"blocks\":" + blocks
                + ",\"chunks\":" + w.chunkCount() + ",\"seed\":" + seed + ",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            Measure m = results.get(i);
            json.append(i > 0 ? "," : "").append("{\"name\":\"").append(m.name()).append("\",\"blocksPerSec\":")
                    .append(m.blocksPerSec()).append(",\"p50us\":").append(m.p50Micros()).append(",\"p95us\":")
                    .append(m.p95Micros()).append(",\"totalMs\":").append(m.totalMillis()).append("}");
        }
        json.append("]}");
        Files.writeString(dir.resolve("l1-import-" + stamp + ".json"), json.toString(), StandardCharsets.UTF_8);
    }
}
```

- [x] **Step 3: 首次运行与修正**

Run: `mvn -q -Dxdag.bench=true -Dxdag.bench.blocks=2000 -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test`（先小规模跑通）。
Expected: 通过并打印表格。常见修正：
- `persist` 阶段的 `RocksdbFactory(config)` 会与基座共用同一 `storeDir` → 用一份 `DevnetConfig` 副本把 `storeDir` 指向 `root.newFolder("scratch")`。
- `kernel.setChannelMgr(...)` 若 `Kernel` 没有该 setter（Lombok 类级 `@Setter` 应有），改用 `Mockito.spy(kernel)`。
- 同一纪元内 20,000 个递增 tick 不能越过纪元末尾：`txTime()` 距纪元末约 60,000 tick，够用；若不够，把 `blocks` 默认改小并注明。
- `confirmed` 测量里每 2000 块出一个主块：这些主块链接的付费块必须仍在同一纪元（`mineMain` 每次推进 64 s = 一个纪元；付费块在前一个纪元，允许）。

然后全量：`mvn -q -Dxdag.bench=true -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test`。默认不带 `-Dxdag.bench=true` 时确认该类被跳过（surefire 报告 `Skipped: 1`）。

- [x] **Step 4: 基线文档**

`docs/benchmarks/<yyyy-mm-dd>-l1-import-baseline.md`：

```markdown
# L1 导入基准基线（SP0b-1）

- 日期 / 机器：<date> / <CPU 型号, 核数, 内存> / JDK <version>
- 命令：`mvn -q -Dxdag.bench=true -Dtest=io.xdag.chain.bench.ChainL1ImportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 负载：senders=64, blocks=20000, chunks=<n>, mix=60/25/10/5, seed=20260917

<粘贴控制台 Markdown 表>

## 阶段占比（`direct` 单块中位耗时 = 100%）

| 阶段 | 中位 µs/块 | 占比 |
|------|-----------|------|
| parse | | |
| signature | | |
| refLookup | | |
| persist | | |
| 其余（锁内校验/难度/孤块池/统计） | 差值 | |

## 结论（给 SP0b-2 的输入）

<哪一段最大；锁外预验证能覆盖的比例；syncPath 相对 direct 的重解析开销>
```

- [x] **Step 5: 提交**

```bash
git add src/test/java/io/xdag/chain/bench/BenchWorkload.java src/test/java/io/xdag/chain/bench/ChainL1ImportBenchmarkTest.java
git add -f docs/benchmarks/<yyyy-mm-dd>-l1-import-baseline.md
git commit -m "Add the L1 import benchmark and record the baseline

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: 全量验证、文档与规格同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md`（§12.2）
- Modify: `docs/superpowers/specs/2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md`（§5.1 状态）
- Modify: `docs/XDAGJ_SNAPSHOT_zh.md`（一致性检查与 `--repairchain`）
- Modify: `.claude/docs/chain-l1-foundation.md`（§9 新增一致性/修复；不进 git）

- [x] **Step 1: 全量回归与许可证**（2026-09-18 @ 6bad0803：75 类 445 测试，0 失败 0 错误 1 跳过（基准类，设计如此）；`license:check` 通过）

Run: `mvn -q license:check` → 退出 0；`mvn -q test` → 读 `target/surefire-reports/*.txt` 汇总 0 失败 0 错误（基准类 Skipped 1）。

- [x] **Step 2: 规格同步**

SP0a 规格 §12.2：G1/G2/G7/G8/G9/G10 行的"影响与现状"末尾追加"**已关闭（SP0b-1）**：<一句话指向实现类/测试类>"。总体设计 §5.1 标题后加"（已实施，见 SP0b-1 规格与计划）"。`docs/XDAGJ_SNAPSHOT_zh.md` 新增一节"启动一致性检查与 `--repairchain`"：两种失败形态、报错示例、`--repairchain dry-run` / `--repairchain` / `--repairchain force` 的含义与退出码、修复后节点自行重新确认。`.claude/docs/chain-l1-foundation.md` 增补 §9"主链完整性：`LAST_COMPLETED_MAIN`、`ChainConsistencyCheck`、`ChainRepairTool`"。

```bash
git add -f docs/superpowers/specs/2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md docs/superpowers/specs/2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md docs/XDAGJ_SNAPSHOT_zh.md
git commit -m "Close the SP0a follow-up tickets in the specs and document the repair command

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

- [x] **Step 3: 记忆与汇报**（2026-09-18）

在 `/Users/tron/.claude/projects/-Users-tron-IDEAProject-xdagj/memory/xdag-chain-contracts.md` 追加：SP0b-1 完成的提交范围、测试数、基线数字（direct/syncPath/confirmed 块/s 与最大阶段占比）、G1–G10 状态、下一步 SP0b-2。最终向用户汇报：分支、提交列表、`mvn test` 结果、基线表、偏离计划之处。
