# EVM 交易批次打包 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每主块打包多笔 EVM 交易直到 blockGasLimit：0x0F 载体字段升级为批次承诺，池支持每 sender nonce 链，执行侧 dual-lookup 展开，重放/收据/bloom/回滚格式零变化。

**Architecture:** 批次体 = RLP 有序 tx hash 列表，内容寻址存 EVM_TX（`0x01‖keccak(body)`）；矿工 `selectEvmBatch` 装批落库后把承诺放入 0x0F；执行侧对每个 ref 先查批次体、miss 则按 legacy 单笔解释（dual-lookup，任何高度都安全）；缺体/缺 blob 沿用停摆-drain；两个新 P2P 码按需拉体。`evm.batchActivationHeight` 只门控矿工侧。

**Tech Stack:** Java 21（Temurin，toolchains 必需）、Besu RLP（`besu-ethereum-rlp`，`BytesValueRLPOutput`/`RLPInput`）、Besu datatypes（`Hash`/`Wei`/`Address`）、JUnit 4 + Mockito、既有 SimpleEncoder/SimpleDecoder 消息编码。

**Spec:** `docs/superpowers/specs/2026-08-17-evm-batch-packing-design.md`（含两处计划阶段修正：批次体键前缀 `0x01`；执行侧始终 dual-lookup）

---

## 全局事实（执行前须知）

- 构建环境（2026-08-14 验证门确认）：每个跑测试的命令块都要带
  ```bash
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
  ```
  PATH 裸 `java`/`mvn` 是 JDK17/3.6.3，会失败。shell 状态不跨 Bash 调用持久。
- 新 Java 文件必须带 MIT license header（从任意现有源文件复制,
  "The MIT License (MIT) ... 2020-2030 The XdagJ Developers"），`mvn verify` 强制。
- 4 空格缩进、120 列、无通配 import、JUnit 4（`org.junit.Test`/`Assert`），不是 JUnit 5。
- 提交信息全英文，每个逻辑变更独立 commit，结尾加
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 关键现有 API（已核实）：`EvmTransaction`：`getHash()→Hash`、`getRawRlp()→Bytes`、
  `getNonce()/getGasLimit()→long`、`getGasPrice()/getValue()→Wei`、`getSender()→Address`；
  `Hash.hash(Bytes)` = keccak256；`EvmTxStore` 现有键 `0x00‖txHash`；
  `EvmBlockProcessor` 8 参构造器（config, stateStore, txStore, metaStore, activationHeight,
  genesisAlloc, journal, historyWindow），4/5/6 参委托重载。
- 消息三处接线缺一不可（历史教训 §12.12）：`MessageCode` 枚举、`MessageFactory` case、
  `XdagP2pHandler.channelRead0` 路由 + `onXdag` 分发。
- 测试基础设施：`io.xdag.evm.state.InMemoryKVSource` 是现成的内存 KVSource；
  `EvmBlockProcessorTest`/`EvmTxPoolTest`/`MainBlockEvmPackingTest`/`MinerPackingSeamTest`/
  `RpcTransportE2ETest` 是要扩展的既有锚点，动它们前先读原文件的构造模式。

## 文件结构（新建/修改总览）

| 文件 | 动作 | 职责 |
|------|------|------|
| `evm/tx/EvmTxStore.java` | 修改 | +批次体记录（0x01 前缀、编解码、形状校验） |
| `evm/tx/EvmTxPool.java` | 重写内部 | 每 sender nonce 链 + `selectBatch` |
| `net/message/MessageCode.java` | 修改 | +`EVM_BATCH_REQUEST(0x1F)`/`EVM_BATCH_REPLY(0x20)` |
| `net/message/p2p/EvmBatchRequestMessage.java` | 新建 | 按承诺哈希请求批次体 |
| `net/message/p2p/EvmBatchReplyMessage.java` | 新建 | 批次体回包 |
| `net/message/MessageFactory.java` | 修改 | 两个新 case |
| `evm/EvmBlockProcessor.java` | 修改 | ref 展开（dual-lookup）+ 停摆条件扩展 + 缺失清单 |
| `net/XdagP2pHandler.java` | 修改 | 新码路由/处理 + 补拉 tick 扩展 + 落库闸 |
| `core/BlockchainImpl.java` | 修改 | `selectEvmBatch` + 激活高度分支 |
| `config/spec/EvmSpec.java` + `config/AbstractConfig.java` + 4 份 conf | 修改 | `evm.batchActivationHeight` |
| 测试：`EvmTxStoreBatchTest`(新)、`EvmTxPoolTest`(扩)、`EvmBatchMessageTest`(新)、`EvmBlockProcessorTest`(扩)、`MainBlockEvmPackingTest`(扩)、`MinerPackingSeamTest`(扩)、`RpcTransportE2ETest`(扩) | | |

---

### Task 1: EvmTxStore 批次体记录

**Files:**
- Modify: `src/main/java/io/xdag/evm/tx/EvmTxStore.java`
- Test: `src/test/java/io/xdag/evm/tx/EvmTxStoreBatchTest.java`（新建，MIT header）

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.evm.tx;

import static org.junit.Assert.*;

import io.xdag.evm.state.InMemoryKVSource;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.Before;
import org.junit.Test;

public class EvmTxStoreBatchTest {

    private EvmTxStore store;

    @Before
    public void setUp() {
        store = new EvmTxStore(new InMemoryKVSource());
    }

    private static Bytes32 h(int i) {
        return Bytes32.leftPad(Bytes.ofUnsignedInt(i));
    }

    @Test
    public void batch_round_trip_preserves_order_and_is_content_addressed() {
        List<Bytes32> hashes = List.of(h(3), h(1), h(2)); // 非排序，顺序必须原样保留
        Bytes body = EvmTxStore.encodeBatch(hashes);
        Hash batchHash = store.putBatch(body);
        assertEquals(Hash.hash(body), batchHash);
        assertTrue(store.containsBatch(batchHash));
        assertEquals(hashes, store.getBatch(batchHash).orElseThrow());
        assertEquals(body, store.getBatchRaw(batchHash).orElseThrow());
    }

    @Test
    public void batch_key_space_does_not_collide_with_tx_blobs() {
        Bytes body = EvmTxStore.encodeBatch(List.of(h(1)));
        Hash batchHash = store.putBatch(body);
        // 同一个 32 字节值按 tx 键查必须 miss（0x00 vs 0x01 前缀域隔离）
        assertFalse(store.contains(batchHash));
        assertTrue(store.containsBatch(batchHash));
    }

    @Test
    public void malformed_or_oversized_body_reads_as_miss() {
        // 直接塞一个坏形状 value（非 RLP 列表）
        Bytes bad = Bytes.fromHexString("0xdeadbeef");
        Hash badHash = store.putBatch(bad);
        assertTrue(store.getBatch(badHash).isEmpty());
        // 超过 MAX_BATCH_TXS 条目按 miss
        List<Bytes32> tooMany = IntStream.rangeClosed(1, EvmTxStore.MAX_BATCH_TXS + 1)
                .mapToObj(EvmTxStoreBatchTest::h).toList();
        Hash bigHash = store.putBatch(EvmTxStore.encodeBatch(tooMany));
        assertTrue(store.getBatch(bigHash).isEmpty());
        // 空列表也按 miss（空批不该存在）
        Hash emptyHash = store.putBatch(EvmTxStore.encodeBatch(List.of()));
        assertTrue(store.getBatch(emptyHash).isEmpty());
    }

    @Test
    public void get_batch_for_unknown_hash_is_empty() {
        assertTrue(store.getBatch(Hash.hash(Bytes.of(1))).isEmpty());
        assertFalse(store.containsBatch(Hash.hash(Bytes.of(1))));
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败（方法不存在）**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest=EvmTxStoreBatchTest`
Expected: COMPILATION ERROR（`encodeBatch`/`putBatch`/`getBatch`/`getBatchRaw`/`containsBatch`/`MAX_BATCH_TXS` 未定义）

- [ ] **Step 3: 实现（EvmTxStore 追加，现有代码不动）**

在 `EvmTxStore.java` 追加（imports 补 `java.util.ArrayList`、`java.util.List`、
`org.apache.tuweni.bytes.Bytes32`、`org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput`、
`org.hyperledger.besu.ethereum.rlp.RLP`、`org.hyperledger.besu.ethereum.rlp.RLPInput`、
`lombok.extern.slf4j.Slf4j`——类上加 `@Slf4j`）：

```java
    /** Batch bodies (spec §5): 0x01 | keccak256(body) -> RLP list of 32-byte tx hashes. */
    private static final byte PREFIX_BATCH = 0x01;

    /** Hard cap on txs per batch (spec §2); an over-sized crafted body reads as a miss. */
    public static final int MAX_BATCH_TXS = 1024;

    private static byte[] batchKey(Hash batchHash) {
        return Bytes.concatenate(Bytes.of(PREFIX_BATCH), batchHash.getBytes()).toArray();
    }

    /** Canonical batch-body encoding: an RLP list of the ordered 32-byte tx hashes. */
    public static Bytes encodeBatch(List<Bytes32> txHashes) {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        txHashes.forEach(out::writeBytes);
        out.endList();
        return out.encoded();
    }

    /** Stores a batch body content-addressed; returns its commitment hash. Idempotent. */
    public Hash putBatch(Bytes body) {
        Hash batchHash = Hash.hash(body);
        store.put(batchKey(batchHash), body.toArray());
        return batchHash;
    }

    public Optional<Bytes> getBatchRaw(Hash batchHash) {
        byte[] raw = store.get(batchKey(batchHash));
        return raw == null ? Optional.empty() : Optional.of(Bytes.wrap(raw));
    }

    public boolean containsBatch(Hash batchHash) {
        return store.get(batchKey(batchHash)) != null;
    }

    /**
     * Decodes a stored batch body into its ordered tx hashes. A malformed, empty, or over-sized
     * body reads as a miss (with a warning) — this is the execution-side clamp on crafted
     * batches (spec §5/§9).
     */
    public Optional<List<Bytes32>> getBatch(Hash batchHash) {
        Optional<Bytes> raw = getBatchRaw(batchHash);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            RLPInput in = RLP.input(raw.get());
            List<Bytes32> hashes = in.readList(RLPInput::readBytes32);
            if (hashes.isEmpty() || hashes.size() > MAX_BATCH_TXS) {
                log.warn("Batch {} has {} entries (allowed 1..{}); treating as miss",
                        batchHash, hashes.size(), MAX_BATCH_TXS);
                return Optional.empty();
            }
            return Optional.of(hashes);
        } catch (RuntimeException e) {
            log.warn("Batch {} body is malformed ({}); treating as miss", batchHash, e.toString());
            return Optional.empty();
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/evm/tx/EvmTxStore.java src/test/java/io/xdag/evm/tx/EvmTxStoreBatchTest.java
git commit -m "feat(evm): content-addressed batch-body records in EvmTxStore (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: EvmTxPool nonce 链——数据结构与准入

**Files:**
- Modify: `src/main/java/io/xdag/evm/tx/EvmTxPool.java`
- Test: `src/test/java/io/xdag/evm/tx/EvmTxPoolTest.java`（扩展；先读原文件学它怎么造签名交易与注资账户）

- [ ] **Step 1: 写失败测试（追加到 EvmTxPoolTest，沿用其既有的造 tx/注资 helper）**

先读 `EvmTxPoolTest.java` 全文，弄清它构造 `EvmTxPool`、签名交易（sender 私钥、nonce、
gasPrice 参数化）与预注资账户的 helper 名字，然后按同样模式追加（下面的
`fundedTx(...)/pool/fund(...)` 用原文件的实际 helper 替换——语义按注释）：

```java
    @Test
    public void nonce_window_accepts_future_nonces_within_16_and_rejects_beyond() {
        // 账户 nonce = 0；nonce 0..15 全部 ADDED，nonce 16 = NONCE_MISMATCH
        for (int n = 0; n <= 15; n++) {
            assertEquals(EvmTxPool.AddResult.ADDED, pool.add(fundedTx(n, /*gasPrice*/ 10)));
        }
        assertEquals(EvmTxPool.AddResult.NONCE_MISMATCH, pool.add(fundedTx(16, 10)));
        // 过去的 nonce 一样拒
        assertEquals(EvmTxPool.AddResult.NONCE_MISMATCH, pool.add(txWithNonceBelowAccount()));
        assertEquals(16, pool.size());
    }

    @Test
    public void replace_by_fee_is_per_sender_nonce_slot() {
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(fundedTx(0, 10)));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(fundedTx(1, 10)));
        // 同 (sender, nonce=1) 更高价替换；等价/低价拒
        assertEquals(EvmTxPool.AddResult.UNDERPRICED, pool.add(fundedTx(1, 10)));
        assertEquals(EvmTxPool.AddResult.REPLACED, pool.add(fundedTx(1, 11)));
        assertEquals(2, pool.size()); // nonce 0 不受影响
    }

    @Test
    public void cumulative_balance_rejects_a_queue_the_sender_cannot_afford() {
        // 账户注资恰好只够 2 笔 (value + gasLimit*gasPrice)；第 3 笔 INSUFFICIENT_BALANCE
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(exactBudgetTx(0)));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(exactBudgetTx(1)));
        assertEquals(EvmTxPool.AddResult.INSUFFICIENT_BALANCE, pool.add(exactBudgetTx(2)));
    }

    @Test
    public void stale_entries_are_pruned_when_the_account_nonce_advances() {
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(fundedTx(0, 10)));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(fundedTx(1, 10)));
        advanceAccountNonceTo(1); // 模拟 nonce0 已上链（按原文件模式直接改世界状态）
        // 下一次 add 触发对该 sender 的 stale 剪枝：nonce0 条目被清
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(fundedTx(2, 10)));
        assertTrue(pool.get(hashOf(fundedTx(0, 10))).isEmpty());
    }
```

（`advanceAccountNonceTo`/`exactBudgetTx`/`txWithNonceBelowAccount`/`hashOf` 若原文件没有
现成等价物就在测试里新写小 helper，用 `RocksDbWorldUpdater` 直接 set nonce/balance。）

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest=EvmTxPoolTest`
Expected: 新增 4 个测试 FAIL（窗口拒收 nonce 1+ 为 NONCE_MISMATCH——现行为是严格相等）

- [ ] **Step 3: 重构 EvmTxPool 内部**

核心改动（保持类名/构造器/`AddResult` 枚举值/`selectTransactions`/`get`/`remove`/
`evictExpired`/`size` 的对外签名不变）：

```java
    /** Per-sender pending window: nonces in [accountNonce, accountNonce + MAX_PER_SENDER - 1]. */
    public static final int MAX_PER_SENDER = 16;

    /** sender -> (nonce -> entry), nonce-ascending. */
    private final Map<Address, NavigableMap<Long, PoolEntry>> bySender = new LinkedHashMap<>();
```

`add(Bytes rawRlp)` 重写帐户段之后的逻辑（前段解码/chainId/签名/gasLimit/intrinsic/
minGasPrice 闸原样保留）：

```java
        Account account = new RocksDbWorldUpdater(evmStateStore).getAccount(sender);
        long accountNonce = account == null ? 0L : account.getNonce();
        Wei balance = account == null ? Wei.ZERO : account.getBalance();

        NavigableMap<Long, PoolEntry> queue = bySender.computeIfAbsent(sender, s -> new TreeMap<>());
        pruneStale(sender, queue, accountNonce); // 剪掉 nonce < accountNonce 的过时条目

        if (tx.getNonce() < accountNonce || tx.getNonce() >= accountNonce + MAX_PER_SENDER) {
            if (queue.isEmpty()) {
                bySender.remove(sender);
            }
            return AddResult.NONCE_MISMATCH;
        }

        // 累计余额：sender 队列内全部条目（替换场景刨去被替换者）+ 新条目
        BigInteger cumulative = cost(tx);
        for (PoolEntry e : queue.values()) {
            if (e.tx().getNonce() != tx.getNonce()) {
                cumulative = cumulative.add(cost(e.tx()));
            }
        }
        if (balance.getAsBigInteger().compareTo(cumulative) < 0) {
            if (queue.isEmpty()) {
                bySender.remove(sender);
            }
            return AddResult.INSUFFICIENT_BALANCE;
        }

        Hash hash = tx.getHash();
        if (byHash.containsKey(hash)) {
            return AddResult.DUPLICATE;
        }
        PoolEntry existing = queue.get(tx.getNonce());
        boolean replaced = false;
        if (existing != null) {
            if (tx.getGasPrice().compareTo(existing.tx().getGasPrice()) <= 0) {
                return AddResult.UNDERPRICED;
            }
            byHash.remove(existing.tx().getHash());
            replaced = true;
        } else if (byHash.size() >= MAX_POOL_SIZE) {
            return AddResult.POOL_FULL;
        }
        PoolEntry entry = new PoolEntry(tx, sender, clockSeconds.getAsLong());
        byHash.put(hash, entry);
        queue.put(tx.getNonce(), entry);
        txStore.put(tx);
        return replaced ? AddResult.REPLACED : AddResult.ADDED;
```

新增私有 helpers：

```java
    private static BigInteger cost(EvmTransaction tx) {
        return tx.getValue().getAsBigInteger()
                .add(tx.getGasPrice().getAsBigInteger().multiply(BigInteger.valueOf(tx.getGasLimit())));
    }

    /** Drops entries whose nonce the chain has already passed. */
    private void pruneStale(Address sender, NavigableMap<Long, PoolEntry> queue, long accountNonce) {
        Iterator<Map.Entry<Long, PoolEntry>> it = queue.headMap(accountNonce, false).entrySet().iterator();
        while (it.hasNext()) {
            byHash.remove(it.next().getValue().tx().getHash());
            it.remove();
        }
    }
```

`removeInternal` 同步改为从 `bySender.get(sender)` 的 NavigableMap 按 nonce 删并在
队列空时移除 sender 键；`evictExpired` 逻辑不变（走 `removeInternal`）。
类顶部 javadoc 的 "one pending tx per sender" 段落改写为 nonce-window 描述。
imports 增 `java.util.NavigableMap`、`java.util.TreeMap`、`java.util.Iterator`。

- [ ] **Step 4: 跑池全部测试确认通过（含既有测试不回退）**

Run: 同 Step 2 命令
Expected: EvmTxPoolTest 全绿。注意：既有测试
`insufficient_balance_for_the_gas_fee_is_rejected` 等依赖单笔语义的仍应通过
（单笔时累计=单笔）；若有测试断言"不同 nonce 逐出旧条目"（旧 stale 语义），
按新语义改断言并在 commit message 里说明。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/evm/tx/EvmTxPool.java src/test/java/io/xdag/evm/tx/EvmTxPoolTest.java
git commit -m "feat(evm): per-sender nonce-chain mempool with cumulative balance admission (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: EvmTxPool.selectBatch——gas 预算装批

**Files:**
- Modify: `src/main/java/io/xdag/evm/tx/EvmTxPool.java`
- Test: `src/test/java/io/xdag/evm/tx/EvmTxPoolTest.java`（继续追加）

- [ ] **Step 1: 写失败测试**

```java
    @Test
    public void select_batch_orders_senders_by_price_and_nonces_ascending_within_sender() {
        // senderA gasPrice 20（nonce 0,1）、senderB gasPrice 30（nonce 0）
        // 期望顺序：B0, A0, A1（sender 间按下一笔价格降序，sender 内 nonce 升序）
        pool.add(txFrom(senderA, 0, 20));
        pool.add(txFrom(senderA, 1, 20));
        pool.add(txFrom(senderB, 0, 30));
        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals(3, batch.size());
        assertEquals(senderB, batch.get(0).getSender());
        assertEquals(0, batch.get(1).getNonce());
        assertEquals(senderA, batch.get(1).getSender());
        assertEquals(1, batch.get(2).getNonce());
    }

    @Test
    public void select_batch_stops_a_sender_at_the_budget_without_leaving_nonce_holes() {
        // senderA 两笔各 gasLimit=21000；预算只够 1 笔 → 只选 nonce 0，绝不跳过 0 选 1
        pool.add(txFrom(senderA, 0, 20));
        pool.add(txFrom(senderA, 1, 20));
        List<EvmTransaction> batch = pool.selectBatch(21000);
        assertEquals(1, batch.size());
        assertEquals(0, batch.get(0).getNonce());
    }

    @Test
    public void select_batch_skips_a_sender_with_a_gap_at_the_head() {
        // senderA 队列只有 nonce 1（账户 nonce=0，nonce 0 从未提交）→ 无法执行，整个 sender 跳过
        pool.add(txFrom(senderA, 1, 20));
        pool.add(txFrom(senderB, 0, 10));
        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals(1, batch.size());
        assertEquals(senderB, batch.get(0).getSender());
    }

    @Test
    public void select_batch_respects_the_max_batch_size_cap() {
        // 65 个不同 sender 各 1 笔，cap 传 64 → 恰好 64 笔
        for (int i = 0; i < 65; i++) {
            pool.add(txFromDistinctSender(i));
        }
        assertEquals(64, pool.selectBatch(Long.MAX_VALUE, 64).size());
    }
```

（`txFrom(sender, nonce, gasPrice)`/`txFromDistinctSender` 按原文件签名 helper 组装；
注意 gap 测试要求 add 允许 nonce 1 在窗口内入池——窗口是 [0,15]，允许。）

- [ ] **Step 2: 跑测试确认编译失败（selectBatch 不存在）**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest=EvmTxPoolTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 selectBatch**

```java
    /** {@code selectBatch(gasBudget, EvmTxStore.MAX_BATCH_TXS)}. */
    public synchronized List<EvmTransaction> selectBatch(long gasBudget) {
        return selectBatch(gasBudget, EvmTxStore.MAX_BATCH_TXS);
    }

    /**
     * Greedy batch fill (spec §3): senders ordered by the gas price of their next unselected tx
     * (descending, insertion order breaking ties), nonces strictly ascending within a sender
     * starting at the account nonce (a head gap disqualifies the sender), a tx that exceeds the
     * remaining budget stops its sender (no nonce holes), until the budget, the cap, or the pool
     * is exhausted. Expired entries are skipped.
     */
    public synchronized List<EvmTransaction> selectBatch(long gasBudget, int maxTxs) {
        long now = clockSeconds.getAsLong();
        List<EvmTransaction> selected = new ArrayList<>();
        long remaining = gasBudget;
        // sender -> 待选队列快照（过滤过期 + 头部 gap 校验）
        List<Deque<EvmTransaction>> runs = new ArrayList<>();
        for (Map.Entry<Address, NavigableMap<Long, PoolEntry>> e : bySender.entrySet()) {
            long accountNonce = currentNonce(e.getKey());
            Deque<EvmTransaction> run = new ArrayDeque<>();
            long expected = accountNonce;
            for (PoolEntry entry : e.getValue().tailMap(accountNonce, true).values()) {
                if (now - entry.addedAtSeconds() > ttlSeconds || entry.tx().getNonce() != expected) {
                    break; // 过期或出现 gap：该 sender 的 run 到此为止
                }
                run.add(entry.tx());
                expected++;
            }
            if (!run.isEmpty()) {
                runs.add(run);
            }
        }
        while (selected.size() < maxTxs) {
            Deque<EvmTransaction> best = null;
            for (Deque<EvmTransaction> run : runs) { // runs 数 ≤ 池 sender 数，线性扫足够
                if (run.isEmpty()) {
                    continue;
                }
                if (best == null
                        || run.peek().getGasPrice().compareTo(best.peek().getGasPrice()) > 0) {
                    best = run;
                }
            }
            if (best == null) {
                break;
            }
            EvmTransaction tx = best.peek();
            if (tx.getGasLimit() > remaining) {
                best.clear(); // 装不下：该 sender 停止（不留 nonce 空洞），换下一个 sender
                continue;
            }
            selected.add(best.poll());
            remaining -= tx.getGasLimit();
        }
        return selected;
    }

    private long currentNonce(Address sender) {
        Account account = new RocksDbWorldUpdater(evmStateStore).getAccount(sender);
        return account == null ? 0L : account.getNonce();
    }
```

imports 增 `java.util.ArrayDeque`、`java.util.Deque`。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: EvmTxPoolTest 全绿

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/evm/tx/EvmTxPool.java src/test/java/io/xdag/evm/tx/EvmTxPoolTest.java
git commit -m "feat(evm): gas-budgeted selectBatch over per-sender nonce runs (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 两个新 P2P 消息

**Files:**
- Modify: `src/main/java/io/xdag/net/message/MessageCode.java`
- Create: `src/main/java/io/xdag/net/message/p2p/EvmBatchRequestMessage.java`（MIT header）
- Create: `src/main/java/io/xdag/net/message/p2p/EvmBatchReplyMessage.java`（MIT header）
- Modify: `src/main/java/io/xdag/net/message/MessageFactory.java`
- Test: `src/test/java/io/xdag/net/message/p2p/EvmBatchMessageTest.java`（新建；先看同目录
  既有 EVM 消息测试的模式并沿用）

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.net.message.p2p;

import static org.junit.Assert.*;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class EvmBatchMessageTest {

    @Test
    public void request_round_trips_through_its_body() {
        Bytes32 hash = Bytes32.random(new java.util.Random(42));
        EvmBatchRequestMessage msg = new EvmBatchRequestMessage(hash);
        EvmBatchRequestMessage decoded = new EvmBatchRequestMessage(msg.getBody());
        assertEquals(hash, decoded.getBatchHash());
    }

    @Test
    public void reply_round_trips_through_its_body() {
        Bytes body = Bytes.fromHexString("0xc281aa"); // 任意字节；语义校验在落库层
        EvmBatchReplyMessage msg = new EvmBatchReplyMessage(body);
        EvmBatchReplyMessage decoded = new EvmBatchReplyMessage(msg.getBody());
        assertEquals(body, decoded.getBatchBody());
    }
}
```

（`Bytes32.random(Random)` 若签名不符就换 `Bytes32.wrap(new byte[32])` 填固定值——
不许用无参 random。`getBody()` 是 `Message` 基类既有 getter，先确认名字。）

- [ ] **Step 2: 跑测试确认编译失败**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest=EvmBatchMessageTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

`MessageCode.java`：在 `EVM_STATE_ROOT(0x1E);` 处改为：

```java
    EVM_STATE_ROOT(0x1E),
    // EVM batch bodies (batch D2): fetch the ordered tx-hash list behind a 0x0F batch commitment.
    EVM_BATCH_REQUEST(0x1F),
    EVM_BATCH_REPLY(0x20);
```

`EvmBatchRequestMessage.java`（完全镜像 `EvmTxRequestMessage` 的结构）：

```java
package io.xdag.net.message.p2p;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import org.apache.tuweni.bytes.Bytes32;

/** Request for one EVM batch body by commitment hash (batch D2); answered by {@link EvmBatchReplyMessage}. */
public class EvmBatchRequestMessage extends Message {

    private final Bytes32 batchHash;

    public EvmBatchRequestMessage(Bytes32 batchHash) {
        super(MessageCode.EVM_BATCH_REQUEST, EvmBatchReplyMessage.class);
        this.batchHash = batchHash;
        SimpleEncoder enc = new SimpleEncoder();
        enc.writeBytes(batchHash.toArray());
        this.body = enc.toBytes();
    }

    public EvmBatchRequestMessage(byte[] body) {
        super(MessageCode.EVM_BATCH_REQUEST, EvmBatchReplyMessage.class);
        SimpleDecoder dec = new SimpleDecoder(body);
        this.batchHash = Bytes32.wrap(dec.readBytes());
        this.body = body;
    }

    public Bytes32 getBatchHash() {
        return batchHash;
    }

    @Override
    public String toString() {
        return "EvmBatchRequestMessage [" + batchHash.toHexString() + "]";
    }
}
```

`EvmBatchReplyMessage.java`（镜像 `EvmTxReplyMessage`——先读它，reply 消息的
`super(...)` 第二参用它同款；载荷字段）：

```java
package io.xdag.net.message.p2p;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import org.apache.tuweni.bytes.Bytes;

/** Carries one EVM batch body (RLP tx-hash list); integrity is keccak-checked at ingest. */
public class EvmBatchReplyMessage extends Message {

    private final Bytes batchBody;

    public EvmBatchReplyMessage(Bytes batchBody) {
        super(MessageCode.EVM_BATCH_REPLY, null);
        this.batchBody = batchBody;
        SimpleEncoder enc = new SimpleEncoder();
        enc.writeBytes(batchBody.toArray());
        this.body = enc.toBytes();
    }

    public EvmBatchReplyMessage(byte[] body) {
        super(MessageCode.EVM_BATCH_REPLY, null);
        SimpleDecoder dec = new SimpleDecoder(body);
        this.batchBody = Bytes.wrap(dec.readBytes());
        this.body = body;
    }

    public Bytes getBatchBody() {
        return batchBody;
    }

    @Override
    public String toString() {
        return "EvmBatchReplyMessage [" + batchBody.size() + " bytes]";
    }
}
```

`MessageFactory.java` 在 EVM case 组后追加：

```java
                case EVM_BATCH_REQUEST -> new EvmBatchRequestMessage(body);
                case EVM_BATCH_REPLY -> new EvmBatchReplyMessage(body);
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/net/message/MessageCode.java src/main/java/io/xdag/net/message/MessageFactory.java src/main/java/io/xdag/net/message/p2p/EvmBatchRequestMessage.java src/main/java/io/xdag/net/message/p2p/EvmBatchReplyMessage.java src/test/java/io/xdag/net/message/p2p/EvmBatchMessageTest.java
git commit -m "feat(evm): EVM_BATCH_REQUEST/REPLY message codes 0x1F/0x20 (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: EvmBlockProcessor——dual-lookup 展开与停摆扩展

**Files:**
- Modify: `src/main/java/io/xdag/evm/EvmBlockProcessor.java`
- Test: `src/test/java/io/xdag/evm/EvmBlockProcessorTest.java`（扩展；先读它的
  setUp/注资/签名交易 helper 与既有停摆测试的写法）

- [ ] **Step 1: 写失败测试**

```java
    @Test
    public void a_batch_ref_expands_to_its_ordered_txs_in_one_block() {
        // 两笔已入 EVM_TX 的签名交易（同 sender nonce 0,1，按原文件 helper 构造+注资）
        Bytes body = EvmTxStore.encodeBatch(List.of(hashOf(tx0), hashOf(tx1)));
        Hash batchHash = txStore.putBatch(body);
        proc.processMainBlock(List.of(Bytes32.wrap(batchHash.getBytes())), 1L, 1000L, BLOCK_HASH_1);
        // 两笔都执行：收据齐、txList 平铺有序
        assertTrue(metaStore.getReceipt(hashOf(tx0)).isPresent());
        assertTrue(metaStore.getReceipt(hashOf(tx1)).isPresent());
        assertEquals(List.of(b32(hashOf(tx0)), b32(hashOf(tx1))), metaStore.getTxList(1L));
    }

    @Test
    public void a_legacy_single_tx_ref_still_executes_via_dual_lookup() {
        proc.processMainBlock(List.of(b32(hashOf(tx0))), 1L, 1000L, BLOCK_HASH_1);
        assertTrue(metaStore.getReceipt(hashOf(tx0)).isPresent());
    }

    @Test
    public void a_missing_batch_body_defers_and_reports_both_kinds_of_missing_hashes() {
        Bytes body = EvmTxStore.encodeBatch(List.of(b32(hashOf(tx0))));
        Hash batchHash = Hash.hash(body); // 故意不 putBatch
        Bytes32 ref = Bytes32.wrap(batchHash.getBytes());
        proc.processMainBlock(List.of(ref), 1L, 1000L, BLOCK_HASH_1);
        assertTrue(metaStore.getReceipt(hashOf(tx0)).isEmpty()); // 未执行 = 停摆
        // 双 miss 的 ref 同时出现在两个缺失清单（补拉 tick 会两种请求都发）
        assertTrue(proc.pendingMissingBatchHashes().contains(ref));
        assertTrue(proc.pendingMissingBlobHashes().contains(ref));
        assertTrue(proc.isAwaitingBatch(batchHash));
        // 体到 → drain → 执行
        txStore.putBatch(body);
        proc.onBlobsAvailable();
        assertTrue(metaStore.getReceipt(hashOf(tx0)).isPresent());
        assertTrue(proc.pendingMissingBatchHashes().isEmpty());
    }

    @Test
    public void a_batch_with_a_missing_member_blob_defers_until_the_blob_arrives() {
        // 体在库，但成员 tx1 的 blob 不在（构造后从 txStore remove）
        Bytes body = EvmTxStore.encodeBatch(List.of(b32(hashOf(tx0)), b32(hashOf(tx1))));
        Hash batchHash = txStore.putBatch(body);
        txStore.remove(hashOf(tx1));
        proc.processMainBlock(List.of(Bytes32.wrap(batchHash.getBytes())), 1L, 1000L, BLOCK_HASH_1);
        assertTrue(metaStore.getReceipt(hashOf(tx0)).isEmpty()); // 整高度停摆（保序）
        assertTrue(proc.pendingMissingBlobHashes().contains(b32(hashOf(tx1))));
        assertFalse(proc.pendingMissingBatchHashes().contains(b32(hashOf(tx1)))); // 已知是 blob
        assertTrue(proc.isAwaitingBlob(hashOf(tx1)));
        txStore.putRaw(hashOf(tx1), tx1.getRawRlp());
        proc.onBlobsAvailable();
        assertTrue(metaStore.getReceipt(hashOf(tx1)).isPresent());
    }
```

（`b32(Hash)`/`hashOf` 是 `Bytes32.wrap(h.getBytes())` 小 helper；`BLOCK_HASH_1`、
`metaStore.getTxList` 名字以原文件为准。）

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest=EvmBlockProcessorTest`
Expected: 新测试 FAIL/编译错（`pendingMissingBatchHashes`/`isAwaitingBatch` 不存在；
批次 ref 被当作 tx blob 缺失而停摆但永不 drain）

- [ ] **Step 3: 实现展开层**

`EvmBlockProcessor.java` 新增展开 record 与方法：

```java
    /** Dual-lookup expansion of raw 0x0F refs (spec §7). */
    private record Expansion(List<Bytes32> flat, List<Bytes32> unknownRefs,
                             List<Bytes32> missingTxBlobs) {
        boolean complete() {
            return unknownRefs.isEmpty() && missingTxBlobs.isEmpty();
        }
    }

    /**
     * Interprets each ref by content (spec §2): a stored batch body expands to its ordered tx
     * hashes; otherwise the ref is a legacy single-tx hash. A ref matching neither store is
     * "unknown" — it may be either kind, so the P2P retry asks for both. Deterministic because a
     * 32-byte value cannot be both keccak(tx RLP) and keccak(batch body).
     */
    private Expansion expandRefs(List<Bytes32> txRefs) {
        List<Bytes32> flat = new ArrayList<>();
        List<Bytes32> unknown = new ArrayList<>();
        List<Bytes32> missingBlobs = new ArrayList<>();
        for (Bytes32 ref : txRefs) {
            Hash asHash = Hash.wrap(ref);
            Optional<List<Bytes32>> batch = txStore.getBatch(asHash);
            if (batch.isPresent()) {
                for (Bytes32 member : batch.get()) {
                    flat.add(member);
                    if (!txStore.contains(Hash.wrap(member))) {
                        missingBlobs.add(member);
                    }
                }
            } else if (txStore.contains(asHash)) {
                flat.add(ref); // legacy single-tx ref
            } else {
                unknown.add(ref);
            }
        }
        return new Expansion(flat, unknown, missingBlobs);
    }
```

`processMainBlock` 的停摆判断从 `!allBlobsPresent(txRefs)` 改为：

```java
        if (!metaStore.pendingHeights().isEmpty() || !expandRefs(txRefs).complete()) {
            metaStore.putPending(height, blockHash, timestampSeconds, txRefs); // 存原始 refs
            ...
```

`onBlobsAvailable` 的判断同样改为 `expandRefs(pending.refs()).complete()`；
`executeAndCheckpoint` 开头把 `txRefs` 换成 `expandRefs(txRefs).flat()` 再走原有
去重/收据跳过/候选循环（循环体不变）。`allBlobsPresent` 删除（被 expandRefs 取代）。

缺失清单与 awaiting 闸：

```java
    /** Expanded tx hashes (and ambiguous refs) that deferred heights still lack, for EVM_TX_REQUEST retry. */
    public synchronized List<Bytes32> pendingMissingBlobHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        forEachPendingExpansion(exp -> {
            exp.missingTxBlobs().forEach(h -> { if (seen.add(h)) missing.add(h); });
            exp.unknownRefs().forEach(h -> { if (seen.add(h)) missing.add(h); }); // 可能是 legacy 单笔
        });
        return missing;
    }

    /** Ambiguous refs that may be batch commitments, for EVM_BATCH_REQUEST retry. */
    public synchronized List<Bytes32> pendingMissingBatchHashes() {
        List<Bytes32> missing = new ArrayList<>();
        Set<Bytes32> seen = new HashSet<>();
        forEachPendingExpansion(exp ->
                exp.unknownRefs().forEach(h -> { if (seen.add(h)) missing.add(h); }));
        return missing;
    }

    private void forEachPendingExpansion(java.util.function.Consumer<Expansion> fn) {
        for (long height : metaStore.pendingHeights()) {
            metaStore.getPending(height).ifPresent(p -> fn.accept(expandRefs(p.refs())));
        }
    }

    /** True if some deferred height's expansion still lacks this tx blob (or has it as an unknown ref). */
    public synchronized boolean isAwaitingBlob(Hash txHash) {
        Bytes32 target = Bytes32.wrap(txHash.getBytes());
        return pendingMissingBlobHashes().contains(target);
    }

    /** True if some deferred height has this hash as an ambiguous (possibly-batch) ref. */
    public synchronized boolean isAwaitingBatch(Hash batchHash) {
        return pendingMissingBatchHashes().contains(Bytes32.wrap(batchHash.getBytes()));
    }
```

（旧 `isAwaitingBlob` 的手写循环删除，统一走展开清单——语义变化：已存 blob 时
`pendingMissingBlobHashes` 不含它，与旧的 `txStore.contains` 前置检查等价。）

- [ ] **Step 4: 跑 EvmBlockProcessorTest 全部确认通过（既有停摆/重放/根测试不回退）**

Run: 同 Step 2 命令
Expected: 全绿。既有 `stall_then_drain` 确定性测试自动覆盖"batch 也保序"路径。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/evm/EvmBlockProcessor.java src/test/java/io/xdag/evm/EvmBlockProcessorTest.java
git commit -m "feat(evm): dual-lookup batch expansion with extended stall semantics (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: P2P 接线（路由、处理、补拉、落库闸）

**Files:**
- Modify: `src/main/java/io/xdag/net/XdagP2pHandler.java`
- Test: 先找既有直调 handler 的 EVM P2P 测试（`grep -rn "processEvmTxRequest\|EvmTxRequestMessage" src/test/`），在同一个测试类里追加；若无直调测试类则新建 `src/test/java/io/xdag/net/XdagP2pHandlerEvmBatchTest.java` 仿既有模式（mock Kernel/msgQueue）。

- [ ] **Step 1: 写失败测试（按找到的既有测试模式改写下述语义）**

```java
    @Test
    public void batch_request_replies_with_the_stored_body() {
        // txStore 里 putBatch 一个体；构造 EvmBatchRequestMessage(batchHash) 直调 handler
        // 断言 msgQueue.sendMessage 收到 EvmBatchReplyMessage 且 getBatchBody() == body
    }

    @Test
    public void batch_reply_is_ingested_only_when_awaited_and_keccak_matches() {
        // isAwaitingBatch=true + body keccak == 请求过的承诺 → putBatch 落库 + onBlobsAvailable 被触发
        // isAwaitingBatch=false（unsolicited）→ 不落库
        // body 被篡改（keccak 不符任何 awaited 承诺）→ 不落库
    }

    @Test
    public void the_retry_tick_requests_both_kinds_for_ambiguous_refs() {
        // evmProcessor.pendingMissingBatchHashes() 返回 [X]、pendingMissingBlobHashes() 返回 [X, Y]
        // 断言 tick 后 msgQueue 收到 EvmBatchRequestMessage(X)、EvmTxRequestMessage(X)、EvmTxRequestMessage(Y)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest=<该测试类>`
Expected: FAIL/编译错

- [ ] **Step 3: 实现**

`channelRead0` 的 EVM 路由 case（现 `:221`）追加两个码：

```java
            case EVM_TX_BROADCAST, EVM_TX_REQUEST, EVM_TX_REPLY, EVM_STATE_ROOT,
                 EVM_BATCH_REQUEST, EVM_BATCH_REPLY -> onXdag(msg);
```

`onXdag` 分发（现 `:342-345` 组）追加：

```java
            case EVM_BATCH_REQUEST -> processEvmBatchRequest((EvmBatchRequestMessage) msg);
            case EVM_BATCH_REPLY -> processEvmBatchReply((EvmBatchReplyMessage) msg);
```

处理方法（放在 `processEvmTxReply` 之后）：

```java
    private void processEvmBatchRequest(EvmBatchRequestMessage msg) {
        EvmTxStore evmTxStore = kernel.getEvmTxStore();
        if (evmTxStore == null) {
            return;
        }
        evmTxStore.getBatchRaw(Hash.wrap(msg.getBatchHash()))
                .ifPresent(body -> msgQueue.sendMessage(new EvmBatchReplyMessage(body)));
    }

    /**
     * Stores a batch body only when (a) it is within the P2P size cap, (b) its keccak matches a
     * commitment some deferred height is actually awaiting (the sole ingest gate — unsolicited
     * bodies never touch the disk), then resumes deferred execution. Member blobs the body reveals
     * as missing are fetched by the next retry tick via pendingMissingBlobHashes().
     */
    private void processEvmBatchReply(EvmBatchReplyMessage msg) {
        EvmTxStore evmTxStore = kernel.getEvmTxStore();
        EvmBlockProcessor evmProcessor = kernel.getEvmBlockProcessor();
        if (evmTxStore == null || evmProcessor == null
                || msg.getBatchBody().size() > config.getEvmSpec().getEvmMaxP2pTxBytes()) {
            return;
        }
        Hash batchHash;
        try {
            batchHash = Hash.hash(msg.getBatchBody());
        } catch (RuntimeException e) {
            return;
        }
        if (evmProcessor.isAwaitingBatch(batchHash)) {
            evmTxStore.putBatch(msg.getBatchBody());
            evmProcessor.onBlobsAvailable();
        }
    }
```

`requestPendingEvmBlobs()`（现 `:423`）的循环后追加批次补拉：

```java
            for (Bytes32 ref : evmProcessor.pendingMissingBatchHashes()) {
                msgQueue.sendMessage(new EvmBatchRequestMessage(ref));
            }
```

`requestMissingEvmBlob(Block)`（现 `:404`）的 miss 分支改为双请求（ref 类别不可预知）：

```java
        if (!evmTxStore.contains(txHash) && !evmTxStore.containsBatch(txHash)) {
            msgQueue.sendMessage(new EvmTxRequestMessage(block.getEvmTxRef()));
            msgQueue.sendMessage(new EvmBatchRequestMessage(block.getEvmTxRef()));
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: 全绿

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/net/XdagP2pHandler.java src/test/java/io/xdag/net/<测试文件>
git commit -m "feat(evm): wire batch-body fetch into P2P routing and the retry tick (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 配置 evm.batchActivationHeight + 矿工 selectEvmBatch

**Files:**
- Modify: `src/main/java/io/xdag/config/spec/EvmSpec.java`（+`getEvmBatchActivationHeight()`）
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java`（字段默认 `Long.MAX_VALUE`、getter、
  `evm.batchActivationHeight` 解析——照抄 `:384-385` 的 activationHeight 模式）
- Modify: `src/main/resources/xdag-devnet.conf`（`evm.batchActivationHeight = 0`，带注释）；
  testnet/mainnet conf **不加**（缺省=不激活）
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`createMainBlock` 分支 + 新
  `selectEvmBatch`）
- Test: `src/test/java/io/xdag/config/EvmConfigSectionTest.java`（扩:devnet=0、未配置=MAX）
  + `src/test/java/io/xdag/core/MainBlockEvmPackingTest.java`（扩）

- [ ] **Step 1: 写失败测试**

`EvmConfigSectionTest` 追加（仿它既有的 conf 断言写法）：

```java
    @Test
    public void devnet_activates_batch_packing_at_genesis_and_shared_nets_do_not() {
        assertEquals(0L, new DevnetConfig().getEvmSpec().getEvmBatchActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getEvmSpec().getEvmBatchActivationHeight());
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getEvmSpec().getEvmBatchActivationHeight());
    }
```

`MainBlockEvmPackingTest` 追加（仿它既有的"打包→断言 evmTxRef"模式）：

```java
    @Test
    public void a_mined_main_block_carries_a_batch_commitment_covering_the_pool() {
        // 池：senderA nonce0/1 + senderB nonce0（既有 helper 造 tx 并 add）
        // 触发 createMainBlock（既有模式）
        // 断言 block.getEvmTxRef() != null 且 txStore.getBatch(Hash.wrap(ref)) 展开
        // == [B0, A0, A1]（价格序）或按 helper 的 gasPrice 设置断言正确顺序
    }

    @Test
    public void a_single_pool_tx_is_still_packed_as_a_batch_commitment() {
        // 池只有 1 笔；断言 ref 是批次承诺（getBatch 命中、体大小 1），不是裸 tx hash
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest='EvmConfigSectionTest,MainBlockEvmPackingTest'`
Expected: FAIL/编译错

- [ ] **Step 3: 实现**

config 三件套照 activationHeight 模式（字段 `protected long evmBatchActivationHeight = Long.MAX_VALUE;`）。
devnet conf 在 `evm.activationHeight = 0` 旁加：

```hocon
# Batch packing (defect-2 fork): height at which mined main blocks switch the 0x0F field from a
# single tx hash to a batch commitment. Devnet activates at genesis; testnet/mainnet omit it
# (= Long.MAX_VALUE, not activated) until the fork is scheduled.
evm.batchActivationHeight = 0
```

`BlockchainImpl.createMainBlock`（现 `:1484`）：

```java
        long nextHeight = xdagStats.nmain + 1;
        boolean batchFork = nextHeight >= kernel.getConfig().getEvmSpec().getEvmBatchActivationHeight();
        Bytes32 evmTxRef = batchFork ? selectEvmBatch(16 - res - orphans.size())
                : selectEvmTxRef(16 - res - orphans.size());
```

新方法（放 `selectEvmTxRef` 后；同样的 never-throw 纪律）：

```java
    /**
     * Builds a gas-budgeted batch from the pool, persists its body content-addressed, and returns
     * the commitment for the 0x0F field (batch D2) — or null when the EVM is disabled, no field
     * slot is free, or the pool yields nothing. A tx that already has a receipt (executed on the
     * canonical chain) drops itself and its sender's later txs (no nonce holes). Never throws into
     * mining.
     */
    private Bytes32 selectEvmBatch(int freeFields) {
        try {
            if (freeFields < 1 || kernel.getEvmTxPool() == null || kernel.getEvmTxStore() == null) {
                return null;
            }
            long gasBudget = kernel.getConfig().getEvmSpec().getEvmBlockGasLimit();
            EvmMetaStore metaStore = kernel.getEvmMetaStore();
            List<io.xdag.evm.tx.EvmTransaction> picked = kernel.getEvmTxPool().selectBatch(gasBudget);
            List<Bytes32> hashes = new ArrayList<>(picked.size());
            Set<org.hyperledger.besu.datatypes.Address> stopped = new HashSet<>();
            for (io.xdag.evm.tx.EvmTransaction tx : picked) {
                if (stopped.contains(tx.getSender())) {
                    continue; // an earlier tx of this sender was dropped — no nonce holes
                }
                if (metaStore != null && metaStore.getReceipt(tx.getHash()).isPresent()) {
                    stopped.add(tx.getSender());
                    continue;
                }
                hashes.add(Bytes32.wrap(tx.getHash().getBytes()));
            }
            if (hashes.isEmpty()) {
                return null;
            }
            Bytes body = io.xdag.evm.tx.EvmTxStore.encodeBatch(hashes);
            return Bytes32.wrap(kernel.getEvmTxStore().putBatch(body).getBytes());
        } catch (RuntimeException e) {
            log.warn("EVM batch selection failed while building a main block; packing none", e);
            return null;
        }
    }
```

（`getEvmBlockGasLimit` 的确切 getter 名先在 EvmSpec 里核实——若叫别名照实际用。）

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: 全绿（既有 pre-fork 打包测试因 devnet=0 会走批次分支——若有测试断言
"ref == 裸 tx hash"，按新语义改为断言批次展开单元素，commit message 说明）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/config src/main/resources/xdag-devnet.conf src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/config/EvmConfigSectionTest.java src/test/java/io/xdag/core/MainBlockEvmPackingTest.java
git commit -m "feat(evm): miner-side batch packing behind evm.batchActivationHeight (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: 端到端集成 + 全量回归

**Files:**
- Test: `src/test/java/io/xdag/core/MinerPackingSeamTest.java`（扩：打包→执行→逐笔收据闭环）
- Test: `src/test/java/io/xdag/rpc/e2e/RpcTransportE2ETest.java`（扩：HTTP 多笔场景)
- Modify: `.claude/docs/smart-contract-design-and-implementation.md`（§13.1 缺陷 2 状态更新）

- [ ] **Step 1: 写失败/新集成测试**

`MinerPackingSeamTest` 追加：

```java
    @Test
    public void a_batched_main_block_confirms_multiple_txs_end_to_end() {
        // 单 sender 连续 nonce 0..2 三笔 + 另一 sender 一笔 → pool
        // createMainBlock 打包（devnet batch fork @0）→ 断言 evmTxRef 展开为 4 笔
        // 真 EvmBlockProcessor.processMainBlock(该 ref) → 4 张收据齐全、
        // metaStore.getTxList(height) 平铺 4 笔、sender nonce 推进到 3
    }
```

`RpcTransportE2ETest` 追加（仿它既有 http 测试：提交后由测试直接调 proc.processMainBlock）：

```java
    @Test
    public void http_multiple_txs_confirm_in_one_main_block() {
        // eth_sendRawTransaction 提交同 sender nonce 0,1 两笔（第二笔现在 ADDED 而非 NONCE_MISMATCH）
        // 组批（EvmTxStore.encodeBatch + putBatch）→ proc.processMainBlock 一次
        // 断言两笔 eth_getTransactionReceipt 均 status 0x1，eth_getBlockByNumber 显示 2 笔
    }
```

- [ ] **Step 2: 跑两个测试类确认新测试通过**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn -q test -Dtest='MinerPackingSeamTest,RpcTransportE2ETest'`
Expected: 全绿（这两个是组装既有部件的集成测试，TDD 意义在钉死端到端行为）

- [ ] **Step 3: 全量回归（后台跑，10-30 分钟）**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"; cd /Users/tron/IDEAProject/xdagj && mvn clean package 2>&1 | tee /tmp/xdagj-batch-build.log`
Expected: `Tests run: ≥400, Failures: 0, Errors: 0` + `BUILD SUCCESS`（基线 394 + 本期新增）。
失败按验证门先例分类：测试工件直接修（独立 commit）；产品缺陷 = 停下报告。

- [ ] **Step 4: 更新设计文档 §13.1 缺陷 2 状态**

`.claude/docs/smart-contract-design-and-implementation.md` 缺陷 2 小节末尾追加一段：
已于本期实现（批次承诺方案、`evm.batchActivationHeight` 门控、池 nonce 链窗口 16、
dual-lookup 展开、两个 P2P 码），devnet 已激活；testnet/mainnet 待分叉排期。
（保持该文档的行文风格；不删原缺陷描述，标注"已实现"。）

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/xdag/core/MinerPackingSeamTest.java src/test/java/io/xdag/rpc/e2e/RpcTransportE2ETest.java .claude/docs/smart-contract-design-and-implementation.md
git commit -m "test(evm): end-to-end batched main block + docs status for defect 2 (batch D2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Spec 覆盖对照（自检）

| Spec 要求 | 计划落点 |
|-----------|----------|
| §2 承诺语义 + dual-lookup 任意高度 + MAX_BATCH_TXS | Task 1（存储/校验）、Task 5（展开） |
| §3 池 nonce 链（窗口/累计余额/RBF/剪枝/selectBatch） | Task 2、Task 3 |
| §4 矿工打包 + try/catch + 单字段 | Task 7 |
| §5 存储 0x01 前缀 + 永不删 | Task 1 |
| §6 P2P 两码 + 三处接线 + 双请求 + 落库闸 | Task 4、Task 6 |
| §7 展开 + pending 存原始 refs + 格式零变化 | Task 5 |
| §8 配置（devnet 0 / 共享网缺省 MAX） | Task 7 |
| §9 不变量（乱序批兜底=既有 executeOne；闸；对称性） | Task 5/6 实现 + 既有测试 |
| §10 测试矩阵 | Task 1-8 各 Step 1 + Task 8 |
| §1 成功标准③ 激活前逐字节一致 | Task 7（分支保留 legacy 路径）+ 全量回归 |

计划外说明：§10 的"缺体停摆 drain 与不停摆节点根逐字节一致"由 Task 5 Step 1 第 3/4 个
测试 + 既有 `stall_then_drain` 确定性测试共同覆盖；"含批次高度的 reorg 重放"由既有
rollbackTo 重放测试自动覆盖（重放脚本是平铺 txList，与 ref 形态无关——若评审认为需要
显式测试，在 Task 5 追加一个批次高度 rollbackTo 用例）。
