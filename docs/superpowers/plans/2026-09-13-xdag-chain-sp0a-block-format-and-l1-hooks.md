# SP0a 扩展字段、分片链与 L1 钩子 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 xdagj 能识别、承载、索引链合约的七类扩展块（字段码 0x0F），并在主块确认时按激活高度把 DEPLOY/CALL 登记进 `CHAIN_L1`，作为 SP1 执行引擎的确定性输入流与代码库；全程不改变任何 L1 区块有效性规则。

**Architecture:** 三条原则贯穿所有任务：(P1) `tryToConnect` 零改动，EXT 不影响 L1 有效性；(P2) 链语义只在 `setMain`/`applyBlock` 中按主块高度激活；(P3) 每个写有成对反写，`apply → unwind → apply` 与直接 `apply` 逐字节相等。新代码集中在新包 `io.xdag.chain`（`ext` 子包 = 纯函数编解码与分类，`l1` 子包 = 存储与钩子），对既有代码的改动限于 `Block`/`XdagField`（扩展字段）、`BlockchainImpl`（五处钩子 + 快照门）、`Kernel`（接线）、`KVSource`/`RocksdbKVSource`（`batchWrite`）、`DatabaseName`、配置类、`XdagCli`（快照导出）。

**Tech Stack:** Java 21、Maven 3.9.9 + toolchains、JUnit 4.13 + Mockito、RocksDB（`rocksdbjni`）、Tuweni `Bytes`/`Bytes32`、`io.xdag.crypto`（`HashUtils.sha256(Bytes) -> Bytes32`、`ECKeyPair`）。

**规格：** `docs/superpowers/specs/2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md`（下称 SP0a 规格）；总规格 `docs/superpowers/specs/2026-09-13-xdag-chain-contracts-design.md`。

---

## 0. 约定（每个任务都适用）

### 0.1 构建与测试命令

每个 shell 会话先执行（PATH 上的 `java` 仍是 JDK 17，必须显式指定 JDK 21）：

```bash
cd /Users/tron/IDEAProject/xdagj
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
```

跑单个测试类（`-Dsurefire.failIfNoSpecifiedTests=false` 避免多模块告警；首次跑前 `mvn -q compile` 以确认主代码可编译）：

```bash
mvn -q -Dtest=io.xdag.chain.ext.ChunkExtTest -Dsurefire.failIfNoSpecifiedTests=false test
```

期望输出末尾：`Tests run: N, Failures: 0, Errors: 0, Skipped: 0`（`-q` 下只在失败时打印详细日志；成功时命令退出码 0）。失败时去掉 `-q` 看完整输出。

全量回归：`mvn -q test`（约 5–8 分钟）。许可证头检查：`mvn -q license:check`。

### 0.2 分支与提交

工作分支 `dev-dag-contract`（已存在，自 `develop` 新建）。每个任务末尾提交；提交信息**英文**，末尾固定两行：

```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6
```

`docs/` 被全局 gitignore，文档提交要 `git add -f`。

### 0.3 许可证头

`license-maven-plugin` 检查 `src/main/**/*.java` 与 `src/test/**/*.java`。**每个新建 Java 文件第一行起必须是**下面这段（与 `src/main/java/io/xdag/core/XdagField.java` 前 23 行逐字相同）：

```java
/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
```

下文代码块为节省篇幅省略这段头，**写文件时必须加上**。

### 0.4 编码约定

- 多字节整数一律**小端**；哈希 32B、地址 20B 原样。
- 所有 `decode` 返回 `ExtResult`，**绝不抛异常**。
- `Block` 的 Address/nonce 字段在编码时做了 `reverse()`，EXT 字段**不做**反转：块内字节就是逻辑字节。
- 20 字节地址在 32 字节 `Address` 结构中位于偏移 8..28：`BytesUtils.arrayToByte32(byte[20])` 构造，`BasicUtils.hash2byte(MutableBytes32)` 取回。

### 0.5 测试陷阱（必读）

1. 测试里的伪主块（`BlockBuilder.generateExtraBlock`）和 CHUNK 块的难度都来自原始哈希（`calculateCurrentBlockDiff` 对无 INPUT 的块用 `getDiffByRawHash`），量级相同。Task 14 的测试基座用 nonce 循环把伪主块难度挖到 `[2^46, 2^47)` 区间，并在每次导入后断言链顶仍是预期主块。主网 PoW 难度高出几十个数量级，不受影响。
2. `Block.getHash()` 首次调用会缓存 `xdagBlock`；**先取哈希再签名会得到错误哈希**。构造器/测试统一在 `signOut` 之后用 `new Block(new XdagBlock(b.toBytes()))` 重新解析得到干净对象。
3. `src/test/resources/xdag-devnet.conf` 是主配置的影子副本，Typesafe Config 按 key 合并且测试副本优先。本计划**不**往任何 conf 里加 `chain.*` 键（用代码默认值），避免两份文件不同步。
4. 所有用 `DevnetConfig` 的测试类共享 CWD 相对的 `devnet/wallet/wallet.data`；`@After` 里删除钱包（照抄 `BlockchainTest.tearDown`）。

---

## 1. 文件结构

**改动（既有文件）**

| 文件 | 改动 |
|------|------|
| `src/main/java/io/xdag/core/XdagField.java` | `XDAG_FIELD_RESERVE6` → `XDAG_FIELD_EXT` |
| `src/main/java/io/xdag/core/Block.java` | `extFields`；11 参构造器；`parse()`/`getEncodedBody()`；`getBlockLinks()` |
| `src/main/java/io/xdag/db/rocksdb/DatabaseName.java` | 新增 `CHAIN_L1` |
| `src/main/java/io/xdag/db/rocksdb/KVSource.java` | `batchWrite` 默认方法 |
| `src/main/java/io/xdag/db/rocksdb/RocksdbKVSource.java` | `batchWrite` 用 `WriteBatch` 覆盖 |
| `src/main/java/io/xdag/config/Config.java` | `getChainSpec()` |
| `src/main/java/io/xdag/config/AbstractConfig.java` | 实现 `ChainSpec`；conf 键读取 |
| `src/main/java/io/xdag/config/{Devnet,Testnet,Mainnet}Config.java` | 激活高度默认值 |
| `src/main/java/io/xdag/core/BlockchainImpl.java` | `chainHooks` 字段与五处调用；`initSnapshotJ` 末尾快照门 |
| `src/main/java/io/xdag/Kernel.java` | `chainL1Store` 字段、构造与钩子接线 |
| `src/main/java/io/xdag/cli/XdagCli.java` | `makeSnapshot` 末尾导出 `SNAPSHOT/CHAIN_L1` |

**新增（主代码，包 `io.xdag.chain`）**

| 文件 | 职责 |
|------|------|
| `ext/ExtKind.java` | 七种 kind 枚举 |
| `ext/ExtError.java` | 结构错误枚举 |
| `ext/ExtResult.java` | 值/错误二选一的 record |
| `ext/ExtCodec.java` | 小端读写、载荷字段拼接/切分 |
| `ext/ChunkExt.java`、`ext/CallExt.java`、`ext/ChainConfigExt.java`、`ext/DeployExt.java`、`ext/AnchorExt.java`、`ext/BondExt.java`、`ext/ChallengeExt.java`、`ext/ClaimExt.java` | 各 kind 的 record + `decode`/`encode` |
| `ext/Classified.java`、`ext/ChainBlockClassifier.java` | Block → kind/record/error |
| `ext/ChunkChain.java`、`ext/ChunkChainBuilder.java` | 分片链装配/计数/构造 |
| `ext/ChainBlockBuilder.java` | 构造 CALL/DEPLOY 块（含分片链） |
| `l1/InputStatus.java`、`l1/ChainRecord.java`、`l1/ContractRecord.java`、`l1/InputRecord.java`、`l1/InputRef.java` | 存储记录 |
| `l1/ChainL1Keys.java`、`l1/ChainL1Batch.java`、`l1/ChainL1Store.java` | `CHAIN_L1` 键布局、批量写、存储 API、快照导出/导入/哈希 |
| `l1/ChainIds.java` | chainId / contractId 派生，地址工具 |
| `l1/ChainL1Hooks.java`、`l1/ChainKindHandler.java`、`l1/ApplyContext.java`、`l1/ChainL1Processor.java` | 钩子接口与处理器 |
| `l1/ChainL1SnapshotGate.java` | 快照启动时的 `CHAIN_L1` 门控 |
| `ChainActivation.java` | 激活判定 |
| `config/spec/ChainSpec.java`（包 `io.xdag.config.spec`） | 配置接口 |

**新增（测试，镜像目录 `src/test/java/io/xdag/...`）**：`core/BlockExtFieldTest`、`chain/ext/{ExtCodecTest, ChunkExtTest, CallExtTest, DeployExtTest, SystemExtTest, ChainBlockClassifierTest, ChunkChainTest, ChainBlockBuilderTest}`、`chain/InMemoryKVSource`、`db/rocksdb/BatchWriteTest`、`chain/l1/{ChainL1StoreTest, ChainL1ProcessorTest, ChainL1TestBase, ChainL1HooksIntegrationTest, ChainL1UnwindTest, ChainActivationGateTest, ChainL1SnapshotTest}`、`config/ChainSpecTest`。

---

## 2. 任务

### Task 1: `XDAG_FIELD_EXT` 与 `Block.extFields`

**Files:**
- Modify: `src/main/java/io/xdag/core/XdagField.java`
- Modify: `src/main/java/io/xdag/core/Block.java`
- Test: `src/test/java/io/xdag/core/BlockExtFieldTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.core;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_EXT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.junit.Test;

public class BlockExtFieldTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);

    private static Bytes32 extField(int kind) {
        byte[] b = new byte[32];
        b[0] = (byte) kind;
        b[31] = 0x7f;
        return Bytes32.wrap(b);
    }

    private static Bytes32 blockRef(int seed) {
        MutableBytes32 h = MutableBytes32.create();
        byte[] tail = new byte[24];
        tail[0] = (byte) seed;
        h.set(8, Bytes.wrap(tail));
        return h;
    }

    @Test
    public void extFieldsSurviveEncodeParseAndKeepSignatureValid() {
        List<Bytes32> ext = List.of(extField(0x01), extField(0x02));
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false,
                List.of(key), "remark", 0, XAmount.ZERO, null, ext);
        b.signOut(key);

        Block parsed = new Block(new XdagBlock(b.toBytes()));

        assertEquals(ext, parsed.getExtFields());
        // field order: header(0) remark(1) ext(2) ext(3) pubkey(4) sign(5) sign(6)
        assertEquals(XDAG_FIELD_EXT, parsed.getXdagBlock().getField(2).getType());
        assertEquals(XDAG_FIELD_EXT, parsed.getXdagBlock().getField(3).getType());
        assertEquals(1, parsed.verifiedKeys().size());
        assertEquals(b.recalcHash(), parsed.getHash());
    }

    @Test
    public void legacyConstructorHasNoExtFields() {
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false,
                List.of(key), null, 0, XAmount.ZERO, null);
        b.signOut(key);
        Block parsed = new Block(new XdagBlock(b.toBytes()));
        assertTrue(parsed.getExtFields().isEmpty());
    }

    @Test
    public void blockLinksReturnsOnlyBlockOutRefsInFieldOrder() {
        Bytes32 a = blockRef(1);
        Bytes32 c = blockRef(3);
        Address addrOut = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()),
                XDAG_FIELD_OUTPUT, XAmount.of(1, XUnit.XDAG), true);
        List<Address> pendings = List.of(
                new Address(a, XDAG_FIELD_OUT, false),
                addrOut,
                new Address(c, XDAG_FIELD_OUT, false));
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, pendings, false,
                null, null, -1, XAmount.ZERO, null, null);
        Block parsed = new Block(new XdagBlock(b.toBytes()));

        List<Address> links = parsed.getBlockLinks();
        assertEquals(2, links.size());
        assertEquals(a, links.get(0).getAddress());
        assertEquals(c, links.get(1).getAddress());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.core.BlockExtFieldTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: variable XDAG_FIELD_EXT` / 构造器参数不匹配。

- [ ] **Step 3: 改 `XdagField`**

在 `src/main/java/io/xdag/core/XdagField.java` 中把

```java
        // Reserved field 6
        XDAG_FIELD_RESERVE6(0x0F);
```

改为

```java
        // Extension field: first EXT field in a block is the extension header (byte0 = kind), later ones are raw payload
        XDAG_FIELD_EXT(0x0F);
```

- [ ] **Step 4: 改 `Block`**

(a) 字段区（`private Bytes32 nonce;` 之后）新增：

```java
    /**
     * Extension fields (type XDAG_FIELD_EXT), in field order. The first one is the extension header.
     */
    private List<Bytes32> extFields = new CopyOnWriteArrayList<>();
```

(b) 把原 10 参构造器改成委托 + 新 11 参构造器。原构造器签名保持，方法体替换为：

```java
    public Block(
            Config config,
            long timestamp,
            List<Address> links,
            List<Address> pendings,
            boolean mining,
            List<ECKeyPair> keys,
            String remark,
            int defKeyIndex,
            XAmount fee,
            UInt64 txNonce) {
        this(config, timestamp, links, pendings, mining, keys, remark, defKeyIndex, fee, txNonce, null);
    }

    public Block(
            Config config,
            long timestamp,
            List<Address> links,
            List<Address> pendings,
            boolean mining,
            List<ECKeyPair> keys,
            String remark,
            int defKeyIndex,
            XAmount fee,
            UInt64 txNonce,
            List<Bytes32> extFields) {
        // ... 原方法体逐行保留 ...
```

在原方法体里 remark 处理块（`if (StringUtils.isAsciiPrintable(remark)) { ... }`）之后、`if (CollectionUtils.isNotEmpty(keys)) {` 之前插入：

```java
        if (CollectionUtils.isNotEmpty(extFields)) {
            for (Bytes32 ext : extFields) {
                setType(XDAG_FIELD_EXT, lenghth++);
                this.extFields.add(ext);
            }
        }
```

(c) `parse()` 的 `switch` 中，在 `default -> {` 之前新增分支：

```java
                case XDAG_FIELD_EXT -> extFields.add(Bytes32.wrap(field.getData().toArray()));
```

(d) `getEncodedBody()` 中，`if (info.getRemark() != null) { encoder.write(info.getRemark()); }` 之后、公钥循环之前插入：

```java
        for (Bytes32 ext : extFields) {
            encoder.writeField(ext.toArray());
        }
```

(e) 在 `getLinks()` 之后新增：

```java
    /**
     * Block references (XDAG_FIELD_OUT pointing at a block, amount 0) in field order: link[0], link[1], ...
     * Extension kinds assign roles to links by position.
     */
    public List<Address> getBlockLinks() {
        List<Address> res = Lists.newArrayList();
        for (Address a : outputs) {
            if (!a.getIsAddress() && a.getType() == XDAG_FIELD_OUT) {
                res.add(a);
            }
        }
        return res;
    }
```

`getExtFields()` 由类级 `@Getter` 自动生成。

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.core.BlockExtFieldTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: 跑既有 Block/Blockchain 回归**

Run: `mvn -q -Dtest='io.xdag.core.BlockTest,io.xdag.core.BlockchainTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（构造器委托保证旧行为字节一致）。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/io/xdag/core/XdagField.java src/main/java/io/xdag/core/Block.java src/test/java/io/xdag/core/BlockExtFieldTest.java
git commit -m "Add XDAG_FIELD_EXT extension fields to Block

Rename the reserved field code 0x0F to XDAG_FIELD_EXT, carry extension
fields through construction, encoding and parsing (after the remark,
before public keys), and expose block-reference links by position.
Legacy constructor behaviour is unchanged.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 2: `ExtKind` / `ExtError` / `ExtResult` / `ExtCodec`

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/ExtKind.java`
- Create: `src/main/java/io/xdag/chain/ext/ExtError.java`
- Create: `src/main/java/io/xdag/chain/ext/ExtResult.java`
- Create: `src/main/java/io/xdag/chain/ext/ExtCodec.java`
- Test: `src/test/java/io/xdag/chain/ext/ExtCodecTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ExtCodecTest {

    @Test
    public void littleEndianRoundTrips() {
        byte[] a = new byte[32];
        ExtCodec.putU16(a, 0, 0xBEEF);
        ExtCodec.putU32(a, 2, 0xDEADBEEFL);
        ExtCodec.putU64(a, 6, 0x0102030405060708L);
        assertEquals(0xBEEF, ExtCodec.u16(a, 0));
        assertEquals(0xDEADBEEFL, ExtCodec.u32(a, 2));
        assertEquals(0x0102030405060708L, ExtCodec.u64(a, 6));
        assertEquals(0xEF, a[0] & 0xff); // least significant byte first
        assertEquals(0x08, a[6] & 0xff);
        assertTrue(ExtCodec.isZero(a, 14, 32));
        assertFalse(ExtCodec.isZero(a, 0, 32));
    }

    @Test
    public void payloadRoundTripAndPadding() {
        Bytes data = Bytes.random(100);
        List<Bytes32> fields = ExtCodec.writeBytes(data);
        assertEquals(4, fields.size());
        ExtResult<Bytes> back = ExtCodec.readBytes(fields, 100);
        assertTrue(back.isOk());
        assertEquals(data, back.value());
        assertEquals(0, ExtCodec.writeBytes(Bytes.EMPTY).size());
        assertEquals(1, ExtCodec.fieldsFor(1));
        assertEquals(1, ExtCodec.fieldsFor(32));
        assertEquals(2, ExtCodec.fieldsFor(33));
    }

    @Test
    public void payloadErrors() {
        Bytes data = Bytes.random(40);
        List<Bytes32> fields = ExtCodec.writeBytes(data);
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, ExtCodec.readBytes(fields, 70).error());
        byte[] dirty = fields.get(1).toArray();
        dirty[31] = 1; // trailing byte must be zero
        ExtResult<Bytes> r = ExtCodec.readBytes(List.of(fields.get(0), Bytes32.wrap(dirty)), 40);
        assertEquals(ExtError.RESERVED_NONZERO, r.error());
        assertNull(r.value());
    }

    @Test
    public void kindCodes() {
        assertEquals(ExtKind.CALL, ExtKind.fromCode(1));
        assertEquals(ExtKind.CLAIM, ExtKind.fromCode(7));
        assertNull(ExtKind.fromCode(0));
        assertNull(ExtKind.fromCode(8));
        assertEquals(3, ExtKind.CHUNK.code());
    }

    @Test
    public void resultHelpers() {
        assertTrue(ExtResult.ok(1).isOk());
        assertFalse(ExtResult.fail(ExtError.NO_EXT).isOk());
        assertArrayEquals(new byte[]{1, 2}, ExtResult.ok(new byte[]{1, 2}).value());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ExtCodecTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `package io.xdag.chain.ext does not exist`。

- [ ] **Step 3: 实现四个类**

`src/main/java/io/xdag/chain/ext/ExtKind.java`：

```java
package io.xdag.chain.ext;

/**
 * Kinds of extension blocks. The code is stored in byte 0 of the extension header field.
 */
public enum ExtKind {
    CALL(1), DEPLOY(2), CHUNK(3), ANCHOR(4), BOND(5), CHALLENGE(6), CLAIM(7);

    private final byte code;

    ExtKind(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    /** Returns the kind for a code, or null when the code is not assigned. */
    public static ExtKind fromCode(int code) {
        for (ExtKind k : values()) {
            if (k.code == code) {
                return k;
            }
        }
        return null;
    }
}
```

`src/main/java/io/xdag/chain/ext/ExtError.java`：

```java
package io.xdag.chain.ext;

/** Structural errors of extension blocks and chunk chains. Decoding never throws; it reports one of these. */
public enum ExtError {
    NO_EXT,
    UNKNOWN_KIND,
    RESERVED_NONZERO,
    BAD_LENGTH,
    MISSING_LINK,
    EXTRA_LINK,
    INLINE_ARGS_TOO_LONG,
    PAYLOAD_COUNT_MISMATCH,
    CHUNK_SEQ_GAP,
    CHUNK_TOTAL_MISMATCH,
    CHUNK_TOO_MANY,
    CHUNK_TAIL_HAS_LINK,
    CHUNK_CYCLE,
    NOT_A_CHUNK,
    CODE_TOO_LARGE,
    CODE_HASH_MISMATCH,
    CODE_UNKNOWN
}
```

`src/main/java/io/xdag/chain/ext/ExtResult.java`：

```java
package io.xdag.chain.ext;

/** Either a decoded value or an {@link ExtError}. */
public record ExtResult<T>(T value, ExtError error) {

    public static <T> ExtResult<T> ok(T value) {
        return new ExtResult<>(value, null);
    }

    public static <T> ExtResult<T> fail(ExtError error) {
        return new ExtResult<>(null, error);
    }

    public boolean isOk() {
        return error == null;
    }
}
```

`src/main/java/io/xdag/chain/ext/ExtCodec.java`：

```java
package io.xdag.chain.ext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Little-endian integer helpers and 32-byte payload field packing shared by all extension codecs. */
public final class ExtCodec {

    public static final int FIELD = 32;

    private ExtCodec() {
    }

    public static int u8(byte[] a, int off) {
        return a[off] & 0xff;
    }

    public static int u16(byte[] a, int off) {
        return (a[off] & 0xff) | ((a[off + 1] & 0xff) << 8);
    }

    public static long u32(byte[] a, int off) {
        return (u16(a, off) & 0xffffL) | ((long) u16(a, off + 2) << 16);
    }

    public static long u64(byte[] a, int off) {
        return (u32(a, off) & 0xffffffffL) | (u32(a, off + 4) << 32);
    }

    public static void putU16(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >>> 8);
    }

    public static void putU32(byte[] a, int off, long v) {
        for (int i = 0; i < 4; i++) {
            a[off + i] = (byte) (v >>> (8 * i));
        }
    }

    public static void putU64(byte[] a, int off, long v) {
        for (int i = 0; i < 8; i++) {
            a[off + i] = (byte) (v >>> (8 * i));
        }
    }

    public static boolean isZero(byte[] a, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Number of 32-byte fields needed to carry {@code len} bytes. */
    public static int fieldsFor(int len) {
        return (len + FIELD - 1) / FIELD;
    }

    /** Concatenates payload fields and returns the first {@code len} bytes; trailing bytes must be zero. */
    public static ExtResult<Bytes> readBytes(List<Bytes32> payload, int len) {
        if (payload.size() != fieldsFor(len)) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        byte[] all = new byte[payload.size() * FIELD];
        int p = 0;
        for (Bytes32 f : payload) {
            System.arraycopy(f.toArray(), 0, all, p, FIELD);
            p += FIELD;
        }
        if (!isZero(all, len, all.length)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(Bytes.wrap(Arrays.copyOf(all, len)));
    }

    /** Splits bytes into zero-padded 32-byte fields. */
    public static List<Bytes32> writeBytes(Bytes data) {
        List<Bytes32> out = new ArrayList<>();
        byte[] src = data.toArray();
        for (int off = 0; off < src.length; off += FIELD) {
            byte[] f = new byte[FIELD];
            System.arraycopy(src, off, f, 0, Math.min(FIELD, src.length - off));
            out.add(Bytes32.wrap(f));
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ExtCodecTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/ExtKind.java src/main/java/io/xdag/chain/ext/ExtError.java src/main/java/io/xdag/chain/ext/ExtResult.java src/main/java/io/xdag/chain/ext/ExtCodec.java src/test/java/io/xdag/chain/ext/ExtCodecTest.java
git commit -m "Add extension kind, error and codec primitives for chain blocks

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 3: `ChunkExt` 编解码

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/ChunkExt.java`
- Test: `src/test/java/io/xdag/chain/ext/ChunkExtTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Address;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.junit.Test;

public class ChunkExtTest {

    /** A block hashlow: first 8 bytes zero (that is where Address keeps the amount). */
    static Bytes32 hashLow(int seed) {
        MutableBytes32 h = MutableBytes32.create();
        byte[] tail = new byte[24];
        tail[0] = (byte) seed;
        tail[23] = (byte) (seed * 7);
        h.set(8, Bytes.wrap(tail));
        return Bytes32.wrap(h.toArray());
    }

    static Address link(Bytes32 hashLow) {
        return new Address(hashLow, XDAG_FIELD_OUT, false);
    }

    @Test
    public void roundTripFullChunkWithNext() {
        Bytes data = Bytes.random(ChunkExt.MAX_DATA_LEN);
        ChunkExt c = new ChunkExt(3, 1000, ChunkExt.MAX_DATA_LEN, hashLow(9), data);
        ExtResult<ChunkExt> r = ChunkExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(hashLow(9))));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(c, r.value());
        assertEquals(11, c.encodePayload().size());
    }

    @Test
    public void roundTripOneByteTailWithoutNext() {
        ChunkExt c = new ChunkExt(0, 1, 1, null, Bytes.of((byte) 0x42));
        ExtResult<ChunkExt> r = ChunkExt.decode(c.encodeHeader(), c.encodePayload(), List.of());
        assertTrue(r.isOk());
        assertNull(r.value().next());
        assertEquals(Bytes.of((byte) 0x42), r.value().data());
    }

    @Test
    public void rejectsBadLengths() {
        ChunkExt zero = new ChunkExt(0, 10, 0, null, Bytes.EMPTY);
        assertEquals(ExtError.BAD_LENGTH, ChunkExt.decode(zero.encodeHeader(), List.of(), List.of()).error());

        ChunkExt tooBig = new ChunkExt(0, 400, 353, null, Bytes.random(353));
        assertEquals(ExtError.BAD_LENGTH, ChunkExt.decode(tooBig.encodeHeader(), tooBig.encodePayload(), List.of()).error());

        ChunkExt moreThanTotal = new ChunkExt(0, 5, 10, null, Bytes.random(10));
        assertEquals(ExtError.BAD_LENGTH, ChunkExt.decode(moreThanTotal.encodeHeader(), moreThanTotal.encodePayload(), List.of()).error());
    }

    @Test
    public void rejectsStructuralErrors() {
        ChunkExt c = new ChunkExt(1, 100, 40, null, Bytes.random(40));
        assertEquals(ExtError.EXTRA_LINK,
                ChunkExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(hashLow(1)), link(hashLow(2)))).error());

        byte[] h = c.encodeHeader().toArray();
        h[20] = 1;
        assertEquals(ExtError.RESERVED_NONZERO, ChunkExt.decode(Bytes32.wrap(h), c.encodePayload(), List.of()).error());

        byte[] wrongKind = c.encodeHeader().toArray();
        wrongKind[0] = ExtKind.CALL.code();
        assertEquals(ExtError.UNKNOWN_KIND, ChunkExt.decode(Bytes32.wrap(wrongKind), c.encodePayload(), List.of()).error());

        List<Bytes32> dirty = new ArrayList<>(c.encodePayload());
        byte[] last = dirty.get(1).toArray();
        last[31] = 1;
        dirty.set(1, Bytes32.wrap(last));
        assertEquals(ExtError.RESERVED_NONZERO, ChunkExt.decode(c.encodeHeader(), dirty, List.of()).error());

        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, ChunkExt.decode(c.encodeHeader(), List.of(dirty.get(0)), List.of()).error());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChunkExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChunkExt`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/chain/ext/ChunkExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CHUNK (kind 3): one 352-byte slice of a larger payload. link[0] is the next chunk (absent on the tail).
 * Header: b0 kind, b1..4 seq u32, b5..8 totalLen u32, b9..10 dataLen u16, b11..31 zero.
 */
public record ChunkExt(long seq, long totalLen, int dataLen, Bytes32 next, Bytes data) {

    /** header + next link + ext header + two zero SIGN_OUT fields leave 11 payload fields. */
    public static final int MAX_PAYLOAD_FIELDS = 11;
    public static final int MAX_DATA_LEN = MAX_PAYLOAD_FIELDS * ExtCodec.FIELD;

    public static ExtResult<ChunkExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.CHUNK.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        long seq = ExtCodec.u32(h, 1);
        long totalLen = ExtCodec.u32(h, 5);
        int dataLen = ExtCodec.u16(h, 9);
        if (!ExtCodec.isZero(h, 11, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (dataLen == 0 || dataLen > MAX_DATA_LEN || dataLen > totalLen) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        if (links.size() > 1) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        ExtResult<Bytes> data = ExtCodec.readBytes(payload, dataLen);
        if (!data.isOk()) {
            return ExtResult.fail(data.error());
        }
        Bytes32 next = links.isEmpty() ? null : Bytes32.wrap(links.get(0).getAddress().toArray());
        return ExtResult.ok(new ChunkExt(seq, totalLen, dataLen, next, data.value()));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CHUNK.code();
        ExtCodec.putU32(h, 1, seq);
        ExtCodec.putU32(h, 5, totalLen);
        ExtCodec.putU16(h, 9, dataLen);
        return Bytes32.wrap(h);
    }

    public List<Bytes32> encodePayload() {
        return ExtCodec.writeBytes(data);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChunkExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/ChunkExt.java src/test/java/io/xdag/chain/ext/ChunkExtTest.java
git commit -m "Add CHUNK extension codec

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 4: `CallExt` 编解码

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/CallExt.java`
- Test: `src/test/java/io/xdag/chain/ext/CallExtTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.chain.ext.ChunkExtTest.hashLow;
import static io.xdag.chain.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class CallExtTest {

    private static final Bytes CONTRACT = Bytes.random(20);

    @Test
    public void inlineArgsRoundTrip() {
        Bytes args = Bytes.random(20);
        CallExt c = new CallExt(0, CONTRACT, 0x12345678, 5_000_000L, 20, args, null);
        ExtResult<CallExt> r = CallExt.decode(c.encodeHeader(), c.encodePayload(), List.of());
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(c, r.value());
        assertEquals(1, c.encodePayload().size());
    }

    @Test
    public void inlineArgsAtLimitAndBeyond() {
        Bytes max = Bytes.random(CallExt.MAX_INLINE_ARGS);
        CallExt ok = new CallExt(0, CONTRACT, 1, 1, CallExt.MAX_INLINE_ARGS, max, null);
        assertTrue(CallExt.decode(ok.encodeHeader(), ok.encodePayload(), List.of()).isOk());

        Bytes over = Bytes.random(CallExt.MAX_INLINE_ARGS + 1);
        CallExt bad = new CallExt(0, CONTRACT, 1, 1, CallExt.MAX_INLINE_ARGS + 1, over, null);
        assertEquals(ExtError.INLINE_ARGS_TOO_LONG,
                CallExt.decode(bad.encodeHeader(), bad.encodePayload(), List.of()).error());
    }

    @Test
    public void chainedArgs() {
        Bytes32 head = hashLow(5);
        CallExt c = new CallExt(CallExt.FLAG_ARGS_CHAIN, CONTRACT, 7, 10, 0, Bytes.EMPTY, head);
        ExtResult<CallExt> r = CallExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(head)));
        assertTrue(r.isOk());
        assertEquals(head, r.value().argsChainHead());
        assertTrue(r.value().argsByChain());
        assertEquals(0, c.encodePayload().size());

        assertEquals(ExtError.MISSING_LINK, CallExt.decode(c.encodeHeader(), List.of(), List.of()).error());
        assertEquals(ExtError.EXTRA_LINK,
                CallExt.decode(c.encodeHeader(), List.of(), List.of(link(head), link(hashLow(6)))).error());
    }

    @Test
    public void rejectsUnknownFlagsAndWrongKind() {
        CallExt c = new CallExt(0x02, CONTRACT, 1, 1, 0, Bytes.EMPTY, null);
        assertEquals(ExtError.RESERVED_NONZERO, CallExt.decode(c.encodeHeader(), List.of(), List.of()).error());

        byte[] h = new CallExt(0, CONTRACT, 1, 1, 0, Bytes.EMPTY, null).encodeHeader().toArray();
        h[0] = ExtKind.CHUNK.code();
        ExtResult<CallExt> r = CallExt.decode(Bytes32.wrap(h), List.of(), List.of());
        assertEquals(ExtError.UNKNOWN_KIND, r.error());
        assertNull(r.value());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.CallExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class CallExt`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/chain/ext/CallExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CALL (kind 1). Header: b0 kind, b1 flags, b2..21 contract, b22..25 selector u32, b26..29 gasLimit u32,
 * b30..31 argsLen u16. Inline args occupy the payload fields; with FLAG_ARGS_CHAIN link[0] is the chunk chain head.
 */
public record CallExt(int flags, Bytes contract, int selector, long gasLimit, int argsLen, Bytes inlineArgs,
                      Bytes32 argsChainHead) {

    public static final int FLAG_ARGS_CHAIN = 0x01;
    public static final int MAX_INLINE_ARGS = 256;

    public boolean argsByChain() {
        return (flags & FLAG_ARGS_CHAIN) != 0;
    }

    public static ExtResult<CallExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.CALL.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAG_ARGS_CHAIN) != 0) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        Bytes contract = Bytes.wrap(Arrays.copyOfRange(h, 2, 22));
        int selector = (int) ExtCodec.u32(h, 22);
        long gasLimit = ExtCodec.u32(h, 26);
        int argsLen = ExtCodec.u16(h, 30);

        if ((flags & FLAG_ARGS_CHAIN) != 0) {
            if (argsLen != 0) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            if (!payload.isEmpty()) {
                return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
            }
            if (links.isEmpty()) {
                return ExtResult.fail(ExtError.MISSING_LINK);
            }
            if (links.size() > 1) {
                return ExtResult.fail(ExtError.EXTRA_LINK);
            }
            Bytes32 head = Bytes32.wrap(links.get(0).getAddress().toArray());
            return ExtResult.ok(new CallExt(flags, contract, selector, gasLimit, 0, Bytes.EMPTY, head));
        }
        if (argsLen > MAX_INLINE_ARGS) {
            return ExtResult.fail(ExtError.INLINE_ARGS_TOO_LONG);
        }
        if (!links.isEmpty()) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        ExtResult<Bytes> args = ExtCodec.readBytes(payload, argsLen);
        if (!args.isOk()) {
            return ExtResult.fail(args.error());
        }
        return ExtResult.ok(new CallExt(flags, contract, selector, gasLimit, argsLen, args.value(), null));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CALL.code();
        h[1] = (byte) flags;
        System.arraycopy(contract.toArray(), 0, h, 2, 20);
        ExtCodec.putU32(h, 22, selector & 0xffffffffL);
        ExtCodec.putU32(h, 26, gasLimit);
        ExtCodec.putU16(h, 30, argsLen);
        return Bytes32.wrap(h);
    }

    public List<Bytes32> encodePayload() {
        return argsByChain() ? List.of() : ExtCodec.writeBytes(inlineArgs);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.CallExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/CallExt.java src/test/java/io/xdag/chain/ext/CallExtTest.java
git commit -m "Add CALL extension codec

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 5: `ChainConfigExt` 与 `DeployExt` 编解码

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/ChainConfigExt.java`
- Create: `src/main/java/io/xdag/chain/ext/DeployExt.java`
- Test: `src/test/java/io/xdag/chain/ext/DeployExtTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.chain.ext.ChunkExtTest.hashLow;
import static io.xdag.chain.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class DeployExtTest {

    private static final Bytes ZERO_CHAIN = Bytes.wrap(new byte[20]);
    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();
    private static final ChainConfigExt CONFIG = new ChainConfigExt(5L, 32L, 10_000_000L);

    @Test
    public void chainConfigRoundTrip() {
        ExtResult<ChainConfigExt> r = ChainConfigExt.decode(CONFIG.encode());
        assertTrue(r.isOk());
        assertEquals(CONFIG, r.value());
        byte[] dirty = CONFIG.encode().toArray();
        dirty[20] = 1;
        assertEquals(ExtError.RESERVED_NONZERO, ChainConfigExt.decode(Bytes32.wrap(dirty)).error());
    }

    @Test
    public void newChainWithCodeChainAndInlineArgs() {
        Bytes32 codeHead = hashLow(11);
        Bytes args = Bytes.random(50);
        DeployExt d = new DeployExt(DeployExt.FLAG_NEW_CHAIN | DeployExt.FLAG_CODE_CHAIN, ZERO_CHAIN, 1_000_000L, 50,
                CODE_HASH, CONFIG, args, codeHead, null);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(codeHead)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(d, r.value());
        assertTrue(r.value().newChain());
        assertTrue(r.value().codeByChain());
        // payload = codeHash + config + 2 args fields
        assertEquals(4, d.encodePayload().size());
    }

    @Test
    public void intoChainWithKnownCodeAndChainedArgs() {
        Bytes32 argsHead = hashLow(12);
        DeployExt d = new DeployExt(DeployExt.FLAG_ARGS_CHAIN, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, argsHead);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(argsHead)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(d, r.value());
        assertNull(r.value().config());
        assertEquals(1, d.encodePayload().size());
    }

    @Test
    public void intoChainWithBothChainsOrdersLinksCodeThenArgs() {
        Bytes32 codeHead = hashLow(13);
        Bytes32 argsHead = hashLow(14);
        DeployExt d = new DeployExt(DeployExt.FLAG_CODE_CHAIN | DeployExt.FLAG_ARGS_CHAIN, CHAIN, 1L, 0, CODE_HASH,
                null, Bytes.EMPTY, codeHead, argsHead);
        ExtResult<DeployExt> r = DeployExt.decode(d.encodeHeader(), d.encodePayload(),
                List.of(link(codeHead), link(argsHead)));
        assertTrue(r.isOk());
        assertEquals(codeHead, r.value().codeChainHead());
        assertEquals(argsHead, r.value().argsChainHead());
        assertEquals(ExtError.MISSING_LINK,
                DeployExt.decode(d.encodeHeader(), d.encodePayload(), List.of(link(codeHead))).error());
        assertEquals(ExtError.EXTRA_LINK, DeployExt.decode(d.encodeHeader(), d.encodePayload(),
                List.of(link(codeHead), link(argsHead), link(hashLow(15)))).error());
    }

    @Test
    public void rejectsBadShapes() {
        DeployExt nonZeroChain = new DeployExt(DeployExt.FLAG_NEW_CHAIN, CHAIN, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(nonZeroChain.encodeHeader(), nonZeroChain.encodePayload(), List.of()).error());

        DeployExt ok = new DeployExt(0, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);
        assertEquals(ExtError.BAD_LENGTH, DeployExt.decode(ok.encodeHeader(), List.of(), List.of()).error());

        DeployExt missingConfig = new DeployExt(DeployExt.FLAG_NEW_CHAIN, ZERO_CHAIN, 1L, 0, CODE_HASH, CONFIG, Bytes.EMPTY, null, null);
        assertEquals(ExtError.BAD_LENGTH,
                DeployExt.decode(missingConfig.encodeHeader(), List.of(CODE_HASH), List.of()).error());

        DeployExt badFlags = new DeployExt(0x08, CHAIN, 1L, 0, CODE_HASH, null, Bytes.EMPTY, null, null);
        assertEquals(ExtError.RESERVED_NONZERO,
                DeployExt.decode(badFlags.encodeHeader(), badFlags.encodePayload(), List.of()).error());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.DeployExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class DeployExt`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/chain/ext/ChainConfigExt.java`：

```java
package io.xdag.chain.ext;

import org.apache.tuweni.bytes.Bytes32;

/** Chain configuration carried by a new-chain DEPLOY: gasPrice u64 (nano/gas) | deliveryDelayD u32 | maxCallGas u32 | 16 zero. */
public record ChainConfigExt(long gasPriceNano, long deliveryDelayD, long maxCallGas) {

    public static ExtResult<ChainConfigExt> decode(Bytes32 field) {
        byte[] a = field.toArray();
        if (!ExtCodec.isZero(a, 16, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(new ChainConfigExt(ExtCodec.u64(a, 0), ExtCodec.u32(a, 8), ExtCodec.u32(a, 12)));
    }

    public Bytes32 encode() {
        byte[] a = new byte[32];
        ExtCodec.putU64(a, 0, gasPriceNano);
        ExtCodec.putU32(a, 8, deliveryDelayD);
        ExtCodec.putU32(a, 12, maxCallGas);
        return Bytes32.wrap(a);
    }
}
```

`src/main/java/io/xdag/chain/ext/DeployExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DEPLOY (kind 2). Header: b0 kind, b1 flags, b2..21 chainId (zero when creating a chain), b22..25 gasLimit u32,
 * b26..27 argsLen u16, b28..31 zero. Payload: [0] codeHash, [1] chain config (new chain only), then inline init args.
 * Links: code chain head (FLAG_CODE_CHAIN) first, then args chain head (FLAG_ARGS_CHAIN).
 */
public record DeployExt(int flags, Bytes chainId, long gasLimit, int argsLen, Bytes32 codeHash, ChainConfigExt config,
                        Bytes inlineArgs, Bytes32 codeChainHead, Bytes32 argsChainHead) {

    public static final int FLAG_NEW_CHAIN = 0x01;
    public static final int FLAG_CODE_CHAIN = 0x02;
    public static final int FLAG_ARGS_CHAIN = 0x04;
    private static final int FLAGS_MASK = FLAG_NEW_CHAIN | FLAG_CODE_CHAIN | FLAG_ARGS_CHAIN;

    public boolean newChain() {
        return (flags & FLAG_NEW_CHAIN) != 0;
    }

    public boolean codeByChain() {
        return (flags & FLAG_CODE_CHAIN) != 0;
    }

    public boolean argsByChain() {
        return (flags & FLAG_ARGS_CHAIN) != 0;
    }

    public static ExtResult<DeployExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.DEPLOY.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAGS_MASK) != 0 || !ExtCodec.isZero(h, 28, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        boolean newChain = (flags & FLAG_NEW_CHAIN) != 0;
        boolean codeChain = (flags & FLAG_CODE_CHAIN) != 0;
        boolean argsChain = (flags & FLAG_ARGS_CHAIN) != 0;
        if (newChain && !ExtCodec.isZero(h, 2, 22)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        Bytes chainId = Bytes.wrap(Arrays.copyOfRange(h, 2, 22));
        long gasLimit = ExtCodec.u32(h, 22);
        int argsLen = ExtCodec.u16(h, 26);

        if (payload.isEmpty()) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        Bytes32 codeHash = payload.get(0);
        int idx = 1;
        ChainConfigExt config = null;
        if (newChain) {
            if (payload.size() < 2) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            ExtResult<ChainConfigExt> c = ChainConfigExt.decode(payload.get(1));
            if (!c.isOk()) {
                return ExtResult.fail(c.error());
            }
            config = c.value();
            idx = 2;
        }
        List<Bytes32> argsFields = payload.subList(idx, payload.size());
        Bytes inline = Bytes.EMPTY;
        if (argsChain) {
            if (argsLen != 0) {
                return ExtResult.fail(ExtError.BAD_LENGTH);
            }
            if (!argsFields.isEmpty()) {
                return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
            }
        } else {
            if (argsLen > CallExt.MAX_INLINE_ARGS) {
                return ExtResult.fail(ExtError.INLINE_ARGS_TOO_LONG);
            }
            ExtResult<Bytes> a = ExtCodec.readBytes(argsFields, argsLen);
            if (!a.isOk()) {
                return ExtResult.fail(a.error());
            }
            inline = a.value();
        }
        int expectedLinks = (codeChain ? 1 : 0) + (argsChain ? 1 : 0);
        if (links.size() < expectedLinks) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > expectedLinks) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        Bytes32 codeHead = codeChain ? Bytes32.wrap(links.get(0).getAddress().toArray()) : null;
        Bytes32 argsHead = argsChain ? Bytes32.wrap(links.get(codeChain ? 1 : 0).getAddress().toArray()) : null;
        return ExtResult.ok(new DeployExt(flags, chainId, gasLimit, argsLen, codeHash, config, inline, codeHead, argsHead));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.DEPLOY.code();
        h[1] = (byte) flags;
        System.arraycopy(chainId.toArray(), 0, h, 2, 20);
        ExtCodec.putU32(h, 22, gasLimit);
        ExtCodec.putU16(h, 26, argsLen);
        return Bytes32.wrap(h);
    }

    public List<Bytes32> encodePayload() {
        List<Bytes32> out = new ArrayList<>();
        out.add(codeHash);
        if (newChain()) {
            out.add(config.encode());
        }
        if (!argsByChain()) {
            out.addAll(ExtCodec.writeBytes(inlineArgs));
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.DeployExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/ChainConfigExt.java src/main/java/io/xdag/chain/ext/DeployExt.java src/test/java/io/xdag/chain/ext/DeployExtTest.java
git commit -m "Add DEPLOY extension codec with chain configuration

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 6: `AnchorExt` / `BondExt` / `ChallengeExt` / `ClaimExt` 编解码

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/AnchorExt.java`
- Create: `src/main/java/io/xdag/chain/ext/BondExt.java`
- Create: `src/main/java/io/xdag/chain/ext/ChallengeExt.java`
- Create: `src/main/java/io/xdag/chain/ext/ClaimExt.java`
- Test: `src/test/java/io/xdag/chain/ext/SystemExtTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.chain.ext.ChunkExtTest.hashLow;
import static io.xdag.chain.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class SystemExtTest {

    private static final Bytes CHAIN = Bytes.random(20);

    @Test
    public void anchorRoundTripAndLinkCount() {
        Bytes32 prev = hashLow(1);
        Bytes32 main = hashLow(2);
        Bytes32 commit = hashLow(3);
        AnchorExt a = new AnchorExt(CHAIN, 120L, Bytes32.random(), Bytes32.random(), 100L, 42L, 2L, prev, main, commit);
        ExtResult<AnchorExt> r = AnchorExt.decode(a.encodeHeader(), a.encodePayload(), List.of(link(prev), link(main), link(commit)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(a, r.value());
        assertEquals(3, a.encodePayload().size());
        assertEquals(ExtError.MISSING_LINK, AnchorExt.decode(a.encodeHeader(), a.encodePayload(), List.of(link(prev), link(main))).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, AnchorExt.decode(a.encodeHeader(), a.encodePayload().subList(0, 2), List.of(link(prev), link(main), link(commit))).error());
        byte[] h = a.encodeHeader().toArray();
        h[1] = 1; // v1 forbids any anchor flag
        assertEquals(ExtError.RESERVED_NONZERO, AnchorExt.decode(Bytes32.wrap(h), a.encodePayload(), List.of(link(prev), link(main), link(commit))).error());
    }

    @Test
    public void bondRoundTrip() {
        BondExt bond = new BondExt(false, CHAIN, 0L);
        assertEquals(bond, BondExt.decode(bond.encodeHeader(), List.of(), List.of()).value());
        BondExt unbond = new BondExt(true, Bytes.wrap(new byte[20]), 5_000_000_000L);
        assertEquals(unbond, BondExt.decode(unbond.encodeHeader(), List.of(), List.of()).value());
        assertEquals(ExtError.EXTRA_LINK, BondExt.decode(bond.encodeHeader(), List.of(), List.of(link(hashLow(1)))).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, BondExt.decode(bond.encodeHeader(), List.of(Bytes32.ZERO), List.of()).error());
    }

    @Test
    public void challengeRoundTrip() {
        Bytes32 anchor = hashLow(4);
        Bytes32 witness = hashLow(5);
        ChallengeExt c = new ChallengeExt(17L, 123_456_789L, anchor, witness);
        ExtResult<ChallengeExt> r = ChallengeExt.decode(c.encodeHeader(), List.of(), List.of(link(anchor), link(witness)));
        assertTrue(r.isOk());
        assertEquals(c, r.value());
        assertEquals(ExtError.MISSING_LINK, ChallengeExt.decode(c.encodeHeader(), List.of(), List.of(link(anchor))).error());
    }

    @Test
    public void claimRoundTrip() {
        Bytes32 anchor = hashLow(6);
        Bytes32 proof = hashLow(7);
        Bytes recipient = Bytes.random(20);
        ClaimExt c = new ClaimExt(true, CHAIN, 99L, 3L, 1_000_000_000L, recipient, anchor, proof);
        ExtResult<ClaimExt> r = ClaimExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(anchor), link(proof)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(c, r.value());
        assertEquals(2, c.encodePayload().size());
        assertEquals(ExtError.EXTRA_LINK, ClaimExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(anchor), link(proof), link(hashLow(8)))).error());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.SystemExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class AnchorExt`。

- [ ] **Step 3: 实现四个 record**

`src/main/java/io/xdag/chain/ext/AnchorExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * ANCHOR (kind 4). Header: b0 kind, b1 flags (must be 0 in v1), b2..21 chainId, b22..29 seq u64, b30..31 zero.
 * Payload: [0] stateRoot, [1] outboxMapRoot, [2] prevSeq u64 | inputCount u32 | segmentCount u32 | 16 zero.
 * Links: [0] previous anchor, [1] main block at seq, [2] commitment chain head.
 */
public record AnchorExt(Bytes chainId, long seq, Bytes32 stateRoot, Bytes32 outboxMapRoot, long prevSeq,
                        long inputCount, long segmentCount, Bytes32 prevAnchor, Bytes32 mainBlock,
                        Bytes32 commitmentHead) {

    public static ExtResult<AnchorExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.ANCHOR.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        if (ExtCodec.u8(h, 1) != 0 || !ExtCodec.isZero(h, 30, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (payload.size() != 3) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (links.size() < 3) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > 3) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        byte[] p2 = payload.get(2).toArray();
        if (!ExtCodec.isZero(p2, 16, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(new AnchorExt(
                Bytes.wrap(Arrays.copyOfRange(h, 2, 22)),
                ExtCodec.u64(h, 22),
                payload.get(0),
                payload.get(1),
                ExtCodec.u64(p2, 0),
                ExtCodec.u32(p2, 8),
                ExtCodec.u32(p2, 12),
                Bytes32.wrap(links.get(0).getAddress().toArray()),
                Bytes32.wrap(links.get(1).getAddress().toArray()),
                Bytes32.wrap(links.get(2).getAddress().toArray())));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.ANCHOR.code();
        System.arraycopy(chainId.toArray(), 0, h, 2, 20);
        ExtCodec.putU64(h, 22, seq);
        return Bytes32.wrap(h);
    }

    public List<Bytes32> encodePayload() {
        byte[] p2 = new byte[32];
        ExtCodec.putU64(p2, 0, prevSeq);
        ExtCodec.putU32(p2, 8, inputCount);
        ExtCodec.putU32(p2, 12, segmentCount);
        return List.of(stateRoot, outboxMapRoot, Bytes32.wrap(p2));
    }
}
```

`src/main/java/io/xdag/chain/ext/BondExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** BOND (kind 5). Header: b0 kind, b1 flags (bit0 = unbond), b2..21 chainId (zero = global), b22..29 amount u64, b30..31 zero. */
public record BondExt(boolean unbond, Bytes chainId, long amount) {

    public static final int FLAG_UNBOND = 0x01;

    public static ExtResult<BondExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.BOND.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAG_UNBOND) != 0 || !ExtCodec.isZero(h, 30, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (!payload.isEmpty()) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (!links.isEmpty()) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        return ExtResult.ok(new BondExt((flags & FLAG_UNBOND) != 0,
                Bytes.wrap(Arrays.copyOfRange(h, 2, 22)), ExtCodec.u64(h, 22)));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.BOND.code();
        h[1] = (byte) (unbond ? FLAG_UNBOND : 0);
        System.arraycopy(chainId.toArray(), 0, h, 2, 20);
        ExtCodec.putU64(h, 22, amount);
        return Bytes32.wrap(h);
    }
}
```

`src/main/java/io/xdag/chain/ext/ChallengeExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/** CHALLENGE (kind 6). Header: b0 kind, b1 flags (0), b2..5 inputIndex u32, b6..13 deposit u64, b14..31 zero. Links: [0] anchor, [1] witness chain head. */
public record ChallengeExt(long inputIndex, long deposit, Bytes32 anchor, Bytes32 witnessHead) {

    public static ExtResult<ChallengeExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.CHALLENGE.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        if (ExtCodec.u8(h, 1) != 0 || !ExtCodec.isZero(h, 14, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (!payload.isEmpty()) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (links.size() < 2) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > 2) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        return ExtResult.ok(new ChallengeExt(ExtCodec.u32(h, 2), ExtCodec.u64(h, 6),
                Bytes32.wrap(links.get(0).getAddress().toArray()),
                Bytes32.wrap(links.get(1).getAddress().toArray())));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CHALLENGE.code();
        ExtCodec.putU32(h, 2, inputIndex);
        ExtCodec.putU64(h, 6, deposit);
        return Bytes32.wrap(h);
    }
}
```

`src/main/java/io/xdag/chain/ext/ClaimExt.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * CLAIM (kind 7). Header: b0 kind, b1 flags (bit0 = recipient is a chain vault), b2..21 srcChain, b22..29 seq u64,
 * b30..31 zero. Payload: [0] index u64 | amount u64 | 16 zero; [1] recipient 20 | 12 zero. Links: [0] anchor, [1] proof chain head.
 */
public record ClaimExt(boolean toChainVault, Bytes srcChain, long seq, long index, long amount, Bytes recipient,
                       Bytes32 anchor, Bytes32 proofHead) {

    public static final int FLAG_TO_CHAIN_VAULT = 0x01;

    public static ExtResult<ClaimExt> decode(Bytes32 header, List<Bytes32> payload, List<Address> links) {
        byte[] h = header.toArray();
        if (ExtCodec.u8(h, 0) != ExtKind.CLAIM.code()) {
            return ExtResult.fail(ExtError.UNKNOWN_KIND);
        }
        int flags = ExtCodec.u8(h, 1);
        if ((flags & ~FLAG_TO_CHAIN_VAULT) != 0 || !ExtCodec.isZero(h, 30, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        if (payload.size() != 2) {
            return ExtResult.fail(ExtError.PAYLOAD_COUNT_MISMATCH);
        }
        if (links.size() < 2) {
            return ExtResult.fail(ExtError.MISSING_LINK);
        }
        if (links.size() > 2) {
            return ExtResult.fail(ExtError.EXTRA_LINK);
        }
        byte[] p0 = payload.get(0).toArray();
        byte[] p1 = payload.get(1).toArray();
        if (!ExtCodec.isZero(p0, 16, 32) || !ExtCodec.isZero(p1, 20, 32)) {
            return ExtResult.fail(ExtError.RESERVED_NONZERO);
        }
        return ExtResult.ok(new ClaimExt((flags & FLAG_TO_CHAIN_VAULT) != 0,
                Bytes.wrap(Arrays.copyOfRange(h, 2, 22)), ExtCodec.u64(h, 22),
                ExtCodec.u64(p0, 0), ExtCodec.u64(p0, 8), Bytes.wrap(Arrays.copyOfRange(p1, 0, 20)),
                Bytes32.wrap(links.get(0).getAddress().toArray()),
                Bytes32.wrap(links.get(1).getAddress().toArray())));
    }

    public Bytes32 encodeHeader() {
        byte[] h = new byte[32];
        h[0] = ExtKind.CLAIM.code();
        h[1] = (byte) (toChainVault ? FLAG_TO_CHAIN_VAULT : 0);
        System.arraycopy(srcChain.toArray(), 0, h, 2, 20);
        ExtCodec.putU64(h, 22, seq);
        return Bytes32.wrap(h);
    }

    public List<Bytes32> encodePayload() {
        byte[] p0 = new byte[32];
        ExtCodec.putU64(p0, 0, index);
        ExtCodec.putU64(p0, 8, amount);
        byte[] p1 = new byte[32];
        System.arraycopy(recipient.toArray(), 0, p1, 0, 20);
        return List.of(Bytes32.wrap(p0), Bytes32.wrap(p1));
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.SystemExtTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/AnchorExt.java src/main/java/io/xdag/chain/ext/BondExt.java src/main/java/io/xdag/chain/ext/ChallengeExt.java src/main/java/io/xdag/chain/ext/ClaimExt.java src/test/java/io/xdag/chain/ext/SystemExtTest.java
git commit -m "Add ANCHOR, BOND, CHALLENGE and CLAIM extension codecs

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 7: `Classified` 与 `ChainBlockClassifier`

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/Classified.java`
- Create: `src/main/java/io/xdag/chain/ext/ChainBlockClassifier.java`
- Test: `src/test/java/io/xdag/chain/ext/ChainBlockClassifierTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.chain.ext.ChunkExtTest.hashLow;
import static io.xdag.chain.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChainBlockClassifierTest {

    private final Config config = new DevnetConfig();

    /** Builds an unsigned block carrying the given extension fields and block links, re-parsed from bytes. */
    static Block extBlock(Config config, List<Bytes32> ext, List<Address> links) {
        Block b = new Block(config, XdagTime.getCurrentTimestamp(), null, links.isEmpty() ? null : links, false,
                null, null, -1, XAmount.ZERO, null, ext);
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void blockWithoutExtIsNone() {
        Block b = extBlock(config, List.of(), List.of());
        Classified c = ChainBlockClassifier.classify(b);
        assertSame(Classified.NONE, c);
        assertNull(c.kind());
        assertEquals(ExtError.NO_EXT, c.error());
    }

    @Test
    public void classifiesCall() {
        CallExt call = new CallExt(0, Bytes.random(20), 9, 100L, 3, Bytes.of((byte) 1, (byte) 2, (byte) 3), null);
        List<Bytes32> ext = new ArrayList<>();
        ext.add(call.encodeHeader());
        ext.addAll(call.encodePayload());
        Classified c = ChainBlockClassifier.classify(extBlock(config, ext, List.of()));
        assertTrue(String.valueOf(c.error()), c.isOk());
        assertEquals(ExtKind.CALL, c.kind());
        assertEquals(call, c.as(CallExt.class));
    }

    @Test
    public void classifiesChunkWithNextLink() {
        Bytes32 next = hashLow(3);
        ChunkExt chunk = new ChunkExt(0, 40, 40, next, Bytes.random(40));
        List<Bytes32> ext = new ArrayList<>();
        ext.add(chunk.encodeHeader());
        ext.addAll(chunk.encodePayload());
        Classified c = ChainBlockClassifier.classify(extBlock(config, ext, List.of(link(next))));
        assertTrue(c.isOk());
        assertEquals(ExtKind.CHUNK, c.kind());
        assertEquals(next, c.as(ChunkExt.class).next());
    }

    @Test
    public void unknownKindAndDecodeErrorsAreReported() {
        byte[] h = new byte[32];
        h[0] = 9;
        Classified unknown = ChainBlockClassifier.classify(extBlock(config, List.of(Bytes32.wrap(h)), List.of()));
        assertEquals(ExtError.UNKNOWN_KIND, unknown.error());
        assertNull(unknown.kind());

        CallExt chained = new CallExt(CallExt.FLAG_ARGS_CHAIN, Bytes.random(20), 1, 1, 0, Bytes.EMPTY, hashLow(1));
        Classified missing = ChainBlockClassifier.classify(extBlock(config, List.of(chained.encodeHeader()), List.of()));
        assertEquals(ExtKind.CALL, missing.kind());
        assertEquals(ExtError.MISSING_LINK, missing.error());
        assertNull(missing.value());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChainBlockClassifierTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class Classified`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/chain/ext/Classified.java`：

```java
package io.xdag.chain.ext;

/** Result of classifying a block: its extension kind, the decoded record (one of the *Ext records) and a structural error. */
public record Classified(ExtKind kind, Object value, ExtError error) {

    public static final Classified NONE = new Classified(null, null, ExtError.NO_EXT);

    public boolean isOk() {
        return error == null;
    }

    public <T> T as(Class<T> type) {
        return type.cast(value);
    }
}
```

`src/main/java/io/xdag/chain/ext/ChainBlockClassifier.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Address;
import io.xdag.core.Block;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/** Pure function Block -> Classified. Never touches storage, never throws. */
public final class ChainBlockClassifier {

    private ChainBlockClassifier() {
    }

    public static Classified classify(Block block) {
        List<Bytes32> ext = block.getExtFields();
        if (ext == null || ext.isEmpty()) {
            return Classified.NONE;
        }
        Bytes32 header = ext.get(0);
        ExtKind kind = ExtKind.fromCode(header.get(0) & 0xff);
        if (kind == null) {
            return new Classified(null, null, ExtError.UNKNOWN_KIND);
        }
        List<Bytes32> payload = new ArrayList<>(ext.subList(1, ext.size()));
        List<Address> links = block.getBlockLinks();
        return switch (kind) {
            case CALL -> wrap(kind, CallExt.decode(header, payload, links));
            case DEPLOY -> wrap(kind, DeployExt.decode(header, payload, links));
            case CHUNK -> wrap(kind, ChunkExt.decode(header, payload, links));
            case ANCHOR -> wrap(kind, AnchorExt.decode(header, payload, links));
            case BOND -> wrap(kind, BondExt.decode(header, payload, links));
            case CHALLENGE -> wrap(kind, ChallengeExt.decode(header, payload, links));
            case CLAIM -> wrap(kind, ClaimExt.decode(header, payload, links));
        };
    }

    private static Classified wrap(ExtKind kind, ExtResult<?> result) {
        return result.isOk() ? new Classified(kind, result.value(), null) : new Classified(kind, null, result.error());
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChainBlockClassifierTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/Classified.java src/main/java/io/xdag/chain/ext/ChainBlockClassifier.java src/test/java/io/xdag/chain/ext/ChainBlockClassifierTest.java
git commit -m "Add chain block classifier

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 8: `ChunkChain` 装配与 `ChunkChainBuilder`

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/ChunkChain.java`
- Create: `src/main/java/io/xdag/chain/ext/ChunkChainBuilder.java`
- Test: `src/test/java/io/xdag/chain/ext/ChunkChainTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_SIGN_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class ChunkChainTest {

    private final Config config = new DevnetConfig();
    private static final long TS = 0x16a00000000L; // any timestamp after the devnet era

    static Bytes payload(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return Bytes.wrap(b);
    }

    static Map<Bytes32, Block> index(List<Block> blocks) {
        Map<Bytes32, Block> m = new HashMap<>();
        for (Block b : blocks) {
            m.put(Bytes32.wrap(b.getHashLow().toArray()), b);
        }
        return m;
    }

    static Bytes32 head(List<Block> chunks) {
        return Bytes32.wrap(chunks.get(0).getHashLow().toArray());
    }

    /** Builds one chunk block by hand so tests can produce malformed chains. */
    static Block rawChunk(Config config, long ts, ChunkExt ext) {
        List<Bytes32> fields = new ArrayList<>();
        fields.add(ext.encodeHeader());
        fields.addAll(ext.encodePayload());
        List<Address> links = ext.next() == null ? null : List.of(new Address(ext.next(), XDAG_FIELD_OUT, false));
        Block b = new Block(config, ts, null, links, false, null, null, -1, XAmount.ZERO, null, fields);
        return new Block(new XdagBlock(b.toBytes()));
    }

    @Test
    public void splitAndAssembleRoundTrip() {
        for (int len : new int[]{1, 351, 352, 353, 704, 1000}) {
            Bytes data = payload(len, len);
            List<Block> chunks = ChunkChainBuilder.split(config, data, TS);
            assertEquals("chunks for " + len, (len + 351) / 352, chunks.size());
            Map<Bytes32, Block> idx = index(chunks);
            ExtResult<Bytes> back = ChunkChain.assemble(head(chunks), h -> idx.get(h), 4096);
            assertTrue("len " + len + ": " + back.error(), back.isOk());
            assertEquals(data, back.value());
            assertEquals(chunks.size(), ChunkChain.countLenient(head(chunks), h -> idx.get(h), 4096));
        }
    }

    @Test
    public void chunkBlocksHaveDescendingTimestampsZeroSignaturesAndSeqFromHead() {
        List<Block> chunks = ChunkChainBuilder.split(config, payload(1000, 7), TS);
        for (int i = 0; i < chunks.size(); i++) {
            Block b = chunks.get(i);
            assertEquals(TS - i, b.getTimestamp());
            ChunkExt c = ChainBlockClassifier.classify(b).as(ChunkExt.class);
            assertEquals(i, c.seq());
            assertEquals(1000, c.totalLen());
            assertNotNull(b.getOutsig()); // zero signature parsed as the (1,1) pseudo signature
            int fields = 0;
            for (int f = 1; f < 16; f++) {
                if (b.getXdagBlock().getField(f).getType() == XDAG_FIELD_SIGN_OUT) {
                    fields++;
                }
            }
            assertEquals(2, fields);
        }
        assertNull(ChainBlockClassifier.classify(chunks.get(chunks.size() - 1)).as(ChunkExt.class).next());
        assertEquals(1, chunks.get(0).getBlockLinks().size());
    }

    @Test
    public void assembleReportsStructuralErrors() {
        List<Block> good = ChunkChainBuilder.split(config, payload(1000, 1), TS);
        Map<Bytes32, Block> idx = index(good);

        assertEquals(ExtError.CHUNK_TOO_MANY, ChunkChain.assemble(head(good), h -> idx.get(h), 2).error());
        assertEquals(ExtError.MISSING_LINK, ChunkChain.assemble(head(good), h -> null, 4096).error());

        // seq gap: middle chunk re-encoded with seq 5
        ChunkExt mid = ChainBlockClassifier.classify(good.get(1)).as(ChunkExt.class);
        Block badMid = rawChunk(config, TS - 1, new ChunkExt(5, mid.totalLen(), mid.dataLen(), mid.next(), mid.data()));
        Map<Bytes32, Block> gap = new HashMap<>(idx);
        gap.put(Bytes32.wrap(good.get(1).getHashLow().toArray()), badMid);
        assertEquals(ExtError.CHUNK_SEQ_GAP, ChunkChain.assemble(head(good), h -> gap.get(h), 4096).error());

        // total mismatch: tail claims a different total
        ChunkExt tail = ChainBlockClassifier.classify(good.get(2)).as(ChunkExt.class);
        Block badTail = rawChunk(config, TS - 2, new ChunkExt(2, 999, tail.dataLen(), null, tail.data()));
        Map<Bytes32, Block> mismatch = new HashMap<>(idx);
        mismatch.put(Bytes32.wrap(good.get(2).getHashLow().toArray()), badTail);
        assertEquals(ExtError.CHUNK_TOTAL_MISMATCH, ChunkChain.assemble(head(good), h -> mismatch.get(h), 4096).error());

        // not a chunk
        Map<Bytes32, Block> notChunk = new HashMap<>(idx);
        notChunk.put(head(good), ChainBlockClassifierTest.extBlock(config, List.of(), List.of()));
        assertEquals(ExtError.NOT_A_CHUNK, ChunkChain.assemble(head(good), h -> notChunk.get(h), 4096).error());
    }

    @Test
    public void tailWithLinkAndCycleAreRejected() {
        // chunk A: seq 0 already carries the whole total but still links B
        Bytes data = payload(10, 3);
        Block b = rawChunk(config, TS - 1, new ChunkExt(1, 10, 10, null, data));
        Block a = rawChunk(config, TS, new ChunkExt(0, 10, 10, Bytes32.wrap(b.getHashLow().toArray()), data));
        Map<Bytes32, Block> idx = index(List.of(a, b));
        assertEquals(ExtError.CHUNK_TAIL_HAS_LINK, ChunkChain.assemble(Bytes32.wrap(a.getHashLow().toArray()), h -> idx.get(h), 4096).error());

        // cycle: A(seq0) -> X, lookup(X) = B(seq1) -> A
        Bytes32 x = ChunkExtTest.hashLow(77);
        Block a2 = rawChunk(config, TS, new ChunkExt(0, 30, 10, x, data));
        Block b2 = rawChunk(config, TS - 1, new ChunkExt(1, 30, 10, Bytes32.wrap(a2.getHashLow().toArray()), data));
        Map<Bytes32, Block> cyc = new HashMap<>();
        cyc.put(Bytes32.wrap(a2.getHashLow().toArray()), a2);
        cyc.put(x, b2);
        assertEquals(ExtError.CHUNK_CYCLE, ChunkChain.assemble(Bytes32.wrap(a2.getHashLow().toArray()), h -> cyc.get(h), 4096).error());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChunkChainTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChunkChainBuilder`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/chain/ext/ChunkChain.java`：

```java
package io.xdag.chain.ext;

import io.xdag.core.Block;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Walks a chunk chain head -> tail via link[0] and reassembles the payload. */
public final class ChunkChain {

    @FunctionalInterface
    public interface RawBlockLookup {
        /** Returns the raw (parsed) block for a hashlow, or null when unknown. */
        Block get(Bytes32 hashLow);
    }

    private ChunkChain() {
    }

    public static ExtResult<Bytes> assemble(Bytes32 head, RawBlockLookup lookup, int maxChunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Set<Bytes32> visited = new HashSet<>();
        long expectSeq = 0;
        long total = -1;
        Bytes32 cur = head;
        while (cur != null) {
            if (!visited.add(cur)) {
                return ExtResult.fail(ExtError.CHUNK_CYCLE);
            }
            if (visited.size() > maxChunks) {
                return ExtResult.fail(ExtError.CHUNK_TOO_MANY);
            }
            Block b = lookup.get(cur);
            if (b == null) {
                return ExtResult.fail(ExtError.MISSING_LINK);
            }
            Classified c = ChainBlockClassifier.classify(b);
            if (c.kind() != ExtKind.CHUNK) {
                return ExtResult.fail(ExtError.NOT_A_CHUNK);
            }
            if (!c.isOk()) {
                return ExtResult.fail(c.error());
            }
            ChunkExt chunk = c.as(ChunkExt.class);
            if (chunk.seq() != expectSeq) {
                return ExtResult.fail(ExtError.CHUNK_SEQ_GAP);
            }
            if (total < 0) {
                total = chunk.totalLen();
            } else if (chunk.totalLen() != total) {
                return ExtResult.fail(ExtError.CHUNK_TOTAL_MISMATCH);
            }
            out.writeBytes(chunk.data().toArray());
            if (out.size() > total) {
                return ExtResult.fail(ExtError.CHUNK_TOTAL_MISMATCH);
            }
            if (out.size() == total && chunk.next() != null) {
                return ExtResult.fail(ExtError.CHUNK_TAIL_HAS_LINK);
            }
            expectSeq++;
            cur = chunk.next();
        }
        if (out.size() != total) {
            return ExtResult.fail(ExtError.CHUNK_TOTAL_MISMATCH);
        }
        return ExtResult.ok(Bytes.wrap(out.toByteArray()));
    }

    /** Number of chunk blocks reachable from head, stopping at the first structural problem or at maxChunks. Used for fee checks. */
    public static int countLenient(Bytes32 head, RawBlockLookup lookup, int maxChunks) {
        Set<Bytes32> visited = new HashSet<>();
        Bytes32 cur = head;
        while (cur != null && visited.size() < maxChunks && visited.add(cur)) {
            Block b = lookup.get(cur);
            if (b == null) {
                break;
            }
            Classified c = ChainBlockClassifier.classify(b);
            if (c.kind() != ExtKind.CHUNK || !c.isOk()) {
                break;
            }
            cur = c.as(ChunkExt.class).next();
        }
        return visited.size();
    }
}
```

`src/main/java/io/xdag/chain/ext/ChunkChainBuilder.java`：

```java
package io.xdag.chain.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;

import io.xdag.config.Config;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Client-side helper: splits a payload into CHUNK blocks. Result is head-first; chunk i has timestamp headTimestamp - i. */
public final class ChunkChainBuilder {

    private ChunkChainBuilder() {
    }

    public static List<Block> split(Config config, Bytes payload, long headTimestamp) {
        int total = payload.size();
        if (total == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        int n = (total + ChunkExt.MAX_DATA_LEN - 1) / ChunkExt.MAX_DATA_LEN;
        List<Block> tailFirst = new ArrayList<>(n);
        Bytes32 next = null;
        for (int i = n - 1; i >= 0; i--) {
            int off = i * ChunkExt.MAX_DATA_LEN;
            int len = Math.min(ChunkExt.MAX_DATA_LEN, total - off);
            ChunkExt ext = new ChunkExt(i, total, len, next, payload.slice(off, len));
            List<Bytes32> fields = new ArrayList<>();
            fields.add(ext.encodeHeader());
            fields.addAll(ext.encodePayload());
            List<Address> links = next == null ? null : List.of(new Address(next, XDAG_FIELD_OUT, false));
            Block raw = new Block(config, headTimestamp - i, null, links, false, null, null, -1, XAmount.ZERO, null, fields);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            next = Bytes32.wrap(parsed.getHashLow().toArray());
            tailFirst.add(parsed);
        }
        Collections.reverse(tailFirst);
        return tailFirst;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChunkChainTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/ChunkChain.java src/main/java/io/xdag/chain/ext/ChunkChainBuilder.java src/test/java/io/xdag/chain/ext/ChunkChainTest.java
git commit -m "Add chunk chain assembly and builder

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 9: `ChainBlockBuilder`（CALL / DEPLOY 块构造）

**Files:**
- Create: `src/main/java/io/xdag/chain/ext/ChainBlockBuilder.java`
- Test: `src/test/java/io/xdag/chain/ext/ChainBlockBuilderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.ext;

import static io.xdag.chain.ext.ChunkChainTest.index;
import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class ChainBlockBuilderTest {

    private final Config config = new DevnetConfig();
    private final ECKeyPair sender = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private static final long TS = 0x16a00000000L;
    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes CONTRACT = Bytes.random(20);
    private static final XAmount ONE = XAmount.of(1, XUnit.XDAG);
    private static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    private static final ChainConfigExt CFG = new ChainConfigExt(1L, 32L, 10_000_000L);

    @Test
    public void callWithInlineArgs() {
        ExtResult<ChainBlockBuilder.Built> r = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L, ONE, FEE, payload(200, 1));
        assertTrue(String.valueOf(r.error()), r.isOk());
        ChainBlockBuilder.Built built = r.value();
        assertTrue(built.chunks().isEmpty());
        Block b = built.block();
        Classified c = ChainBlockClassifier.classify(b);
        assertTrue(String.valueOf(c.error()), c.isOk());
        assertEquals(ExtKind.CALL, c.kind());
        assertEquals(payload(200, 1), c.as(CallExt.class).inlineArgs());
        assertEquals(1, b.getInputs().size());
        assertEquals(1, b.getOutputs().size());
        assertTrue(b.getBlockLinks().isEmpty());
        assertEquals(1, b.verifiedKeys().size());
        assertEquals(UInt64.ONE, b.getTxNonceField().getTransactionNonce());
        assertEquals(FEE, b.getFee());
    }

    @Test
    public void callWithChainedArgs() {
        Bytes args = payload(1000, 2);
        ChainBlockBuilder.Built built = ChainBlockBuilder.call(config, TS, sender, UInt64.ONE, CHAIN, CONTRACT, 7, 100L, ONE, FEE, args).value();
        assertEquals(3, built.chunks().size());
        Classified c = ChainBlockClassifier.classify(built.block());
        assertTrue(c.isOk());
        assertTrue(c.as(CallExt.class).argsByChain());
        assertEquals(1, built.block().getBlockLinks().size());
        assertTrue(built.chunks().get(0).getTimestamp() < built.block().getTimestamp());
        Map<Bytes32, Block> idx = index(built.chunks());
        assertEquals(args, ChunkChain.assemble(c.as(CallExt.class).argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void deployNewChainChainsCodeAndKeepsSmallArgsInline() {
        Bytes wasm = payload(10_000, 3);
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE, wasm, CFG, payload(160, 4), 1_000L).value();
        assertEquals(29, built.chunks().size());
        Classified c = ChainBlockClassifier.classify(built.block());
        assertTrue(String.valueOf(c.error()), c.isOk());
        DeployExt d = c.as(DeployExt.class);
        assertTrue(d.newChain());
        assertTrue(d.codeByChain());
        assertEquals(false, d.argsByChain());
        assertEquals(HashUtils.sha256(wasm), d.codeHash());
        assertEquals(CFG, d.config());
        assertEquals(160, d.inlineArgs().size());
        assertEquals(1, built.block().getBlockLinks().size());
        assertEquals(wasm, ChunkChain.assemble(d.codeChainHead(), index(built.chunks())::get, 4096).value());
    }

    @Test
    public void deployNewChainChainsArgsWhenTheyDoNotFit() {
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployNewChain(config, TS, sender, UInt64.ONE, FEE, payload(500, 5), CFG, payload(161, 6), 1L).value();
        DeployExt d = ChainBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertTrue(d.argsByChain());
        assertEquals(2, built.block().getBlockLinks().size());
        assertEquals(2 + 1, built.chunks().size()); // 500B code = 2 chunks, 161B args = 1 chunk
        Map<Bytes32, Block> idx = index(built.chunks());
        assertEquals(payload(161, 6), ChunkChain.assemble(d.argsChainHead(), idx::get, 4096).value());
    }

    @Test
    public void feeHelpers() {
        assertEquals(0, ChainBlockBuilder.chunksFor(0));
        assertEquals(1, ChainBlockBuilder.chunksFor(352));
        assertEquals(2, ChainBlockBuilder.chunksFor(353));
        assertEquals(XAmount.of(30, XUnit.MILLI_XDAG), ChainBlockBuilder.minHeaderFee(XAmount.of(10, XUnit.MILLI_XDAG), 3));
    }

    @Test
    public void deployIntoChainWithKnownCode() {
        Bytes32 codeHash = Bytes32.random();
        ChainBlockBuilder.Built built = ChainBlockBuilder.deployIntoChain(config, TS, sender, UInt64.ONE, CHAIN, ONE, FEE, null, codeHash, payload(100, 7), 1L).value();
        assertTrue(built.chunks().isEmpty());
        DeployExt d = ChainBlockClassifier.classify(built.block()).as(DeployExt.class);
        assertEquals(false, d.newChain());
        assertEquals(false, d.codeByChain());
        assertEquals(codeHash, d.codeHash());
        assertEquals(CHAIN, d.chainId());
        assertEquals(payload(100, 7), d.inlineArgs());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChainBlockBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChainBlockBuilder`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/chain/ext/ChainBlockBuilder.java`：

```java
package io.xdag.chain.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Builds signed CALL / DEPLOY account-transaction blocks plus their chunk chains.
 * Chunks are returned head-first and must be imported tail-first before the paying block.
 * Callers must pass value >= headerFee + MIN_GAS for call/deployIntoChain (L1 input rule); deployNewChain sizes its
 * self-transfer automatically. headerFee must be >= minHeaderFee(chunkFee, chunks) or the input is recorded INVALID_FEE.
 */
public final class ChainBlockBuilder {

    /** header, nonce, INPUT, OUTPUT, pubkey, SIGN_OUT x2, ext header */
    private static final int TX_FIXED_FIELDS = 8;

    public record Built(Block block, List<Block> chunks) {
    }

    private ChainBlockBuilder() {
    }

    public static ExtResult<Built> call(Config config, long timestamp, ECKeyPair sender, UInt64 nonce, Bytes chainId,
                                        Bytes contract, int selector, long gasLimit, XAmount value, XAmount headerFee,
                                        Bytes args) {
        int inlineCapacity = Math.min((XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS) * ExtCodec.FIELD, CallExt.MAX_INLINE_ARGS);
        List<Block> chunks = List.of();
        Bytes32 argsHead = null;
        int flags = 0;
        Bytes inline = args;
        if (args.size() > inlineCapacity) {
            chunks = ChunkChainBuilder.split(config, args, timestamp - 1);
            argsHead = Bytes32.wrap(chunks.get(0).getHashLow().toArray());
            flags = CallExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        CallExt ext = new CallExt(flags, contract, selector, gasLimit, inline.size(), inline, argsHead);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, value));
        refs.add(new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, value, true));
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(), chunks);
    }

    public static ExtResult<Built> deployNewChain(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                                 XAmount headerFee, Bytes wasm, ChainConfigExt chainConfig,
                                                 Bytes initArgs, long gasLimit) {
        List<Block> chunks = new ArrayList<>();
        long nextHeadTs = timestamp - 1;
        List<Block> code = ChunkChainBuilder.split(config, wasm, nextHeadTs);
        chunks.addAll(code);
        Bytes32 codeHead = Bytes32.wrap(code.get(0).getHashLow().toArray());
        nextHeadTs -= code.size();
        int flags = DeployExt.FLAG_NEW_CHAIN | DeployExt.FLAG_CODE_CHAIN;
        // fixed 8 + codeHash + config + code link = 11 fields used
        int inlineCapacity = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 3) * ExtCodec.FIELD;
        Bytes32 argsHead = null;
        Bytes inline = initArgs;
        if (initArgs.size() > Math.min(inlineCapacity, CallExt.MAX_INLINE_ARGS)) {
            List<Block> args = ChunkChainBuilder.split(config, initArgs, nextHeadTs);
            chunks.addAll(args);
            argsHead = Bytes32.wrap(args.get(0).getHashLow().toArray());
            flags |= DeployExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        DeployExt ext = new DeployExt(flags, Bytes.wrap(new byte[20]), gasLimit, inline.size(), HashUtils.sha256(wasm),
                chainConfig, inline, codeHead, argsHead);
        List<Address> refs = new ArrayList<>();
        // self transfer; tryToConnect requires input amount >= header fee + MIN_GAS x outputs
        XAmount self = headerFee.add(Constants.MIN_GAS);
        refs.add(input(sender, self));
        refs.add(new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_OUTPUT, self, true));
        refs.add(new Address(codeHead, XDAG_FIELD_OUT, false));
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(), chunks);
    }

    /** wasm may be null when the code hash is already known to the network; then codeHash is used as given. */
    public static ExtResult<Built> deployIntoChain(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                                  Bytes chainId, XAmount value, XAmount headerFee, Bytes wasm,
                                                  Bytes32 codeHash, Bytes initArgs, long gasLimit) {
        List<Block> chunks = new ArrayList<>();
        long nextHeadTs = timestamp - 1;
        int flags = 0;
        Bytes32 codeHead = null;
        Bytes32 hash = codeHash;
        if (wasm != null) {
            List<Block> code = ChunkChainBuilder.split(config, wasm, nextHeadTs);
            chunks.addAll(code);
            codeHead = Bytes32.wrap(code.get(0).getHashLow().toArray());
            nextHeadTs -= code.size();
            flags |= DeployExt.FLAG_CODE_CHAIN;
            hash = HashUtils.sha256(wasm);
        }
        // fixed 8 + codeHash + optional code link
        int inlineCapacity = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 1 - (codeHead != null ? 1 : 0)) * ExtCodec.FIELD;
        Bytes32 argsHead = null;
        Bytes inline = initArgs;
        if (initArgs.size() > Math.min(inlineCapacity, CallExt.MAX_INLINE_ARGS)) {
            List<Block> args = ChunkChainBuilder.split(config, initArgs, nextHeadTs);
            chunks.addAll(args);
            argsHead = Bytes32.wrap(args.get(0).getHashLow().toArray());
            flags |= DeployExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        DeployExt ext = new DeployExt(flags, chainId, gasLimit, inline.size(), hash, null, inline, codeHead, argsHead);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, value));
        refs.add(new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, value, true));
        if (codeHead != null) {
            refs.add(new Address(codeHead, XDAG_FIELD_OUT, false));
        }
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(), chunks);
    }

    private static Address input(ECKeyPair sender, XAmount amount) {
        return new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_INPUT, amount, true);
    }

    /** Number of CHUNK blocks a payload of {@code len} bytes needs (0 for an empty payload). */
    public static int chunksFor(int len) {
        return (len + ChunkExt.MAX_DATA_LEN - 1) / ChunkExt.MAX_DATA_LEN;
    }

    /** Smallest header fee that satisfies the consensus chunk-fee rule for {@code chunks} chunk blocks (chunkFee = ChainSpec.getChainChunkFee()). */
    public static XAmount minHeaderFee(XAmount chunkFee, int chunks) {
        return chunkFee.multiply(chunks);
    }

    private static ExtResult<Built> finish(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                           XAmount headerFee, List<Address> refs, Bytes32 extHeader,
                                           List<Bytes32> payload, List<Block> chunks) {
        List<Bytes32> fields = new ArrayList<>();
        fields.add(extHeader);
        fields.addAll(payload);
        int total = 1 + 1 + refs.size() + 3 + fields.size();
        if (total > XdagBlock.XDAG_BLOCK_FIELDS) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        Block raw = new Block(config, timestamp, refs, null, false, List.of(sender), null, 0, headerFee, nonce, fields);
        raw.signOut(sender);
        return ExtResult.ok(new Built(new Block(new XdagBlock(raw.toBytes())), chunks));
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.ext.ChainBlockBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 6, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/chain/ext/ChainBlockBuilder.java src/test/java/io/xdag/chain/ext/ChainBlockBuilderTest.java
git commit -m "Add builder for CALL and DEPLOY chain blocks

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 10: `DatabaseName.CHAIN_L1`、`KVSource.batchWrite` 与测试用 `InMemoryKVSource`

**Files:**
- Modify: `src/main/java/io/xdag/db/rocksdb/DatabaseName.java`
- Modify: `src/main/java/io/xdag/db/rocksdb/KVSource.java`
- Modify: `src/main/java/io/xdag/db/rocksdb/RocksdbKVSource.java`
- Create: `src/test/java/io/xdag/chain/InMemoryKVSource.java`
- Test: `src/test/java/io/xdag/db/rocksdb/BatchWriteTest.java`

- [ ] **Step 1: 写测试用 `InMemoryKVSource`（测试基础设施，先于测试）**

`src/test/java/io/xdag/chain/InMemoryKVSource.java`：

```java
package io.xdag.chain;

import io.xdag.db.rocksdb.KVSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/** Sorted in-memory KVSource for unit tests. Keys and values are copied on the way in and out. */
public class InMemoryKVSource implements KVSource<byte[], byte[]> {

    private final TreeMap<Bytes, byte[]> map = new TreeMap<>();
    private String name = "memory";
    private boolean alive;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void init() {
        alive = true;
    }

    @Override
    public void close() {
        alive = false;
    }

    @Override
    public void reset() {
        map.clear();
    }

    @Override
    public void put(byte[] key, byte[] val) {
        if (val == null) {
            map.remove(Bytes.wrap(key));
        } else {
            map.put(Bytes.wrap(Arrays.copyOf(key, key.length)), Arrays.copyOf(val, val.length));
        }
    }

    @Override
    public byte[] get(byte[] key) {
        byte[] v = map.get(Bytes.wrap(key));
        return v == null ? null : Arrays.copyOf(v, v.length);
    }

    @Override
    public void delete(byte[] key) {
        map.remove(Bytes.wrap(key));
    }

    @Override
    public Set<byte[]> keys() {
        Set<byte[]> out = new HashSet<>();
        for (Bytes k : map.keySet()) {
            out.add(k.toArray());
        }
        return out;
    }

    @Override
    public List<byte[]> prefixKeyLookup(byte[] prefix) {
        List<byte[]> out = new ArrayList<>();
        for (Bytes k : map.keySet()) {
            if (startsWith(k, prefix)) {
                out.add(k.toArray());
            }
        }
        return out;
    }

    @Override
    public void fetchPrefix(byte[] prefix, Function<Pair<byte[], byte[]>, Boolean> func) {
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix) && func.apply(Pair.of(e.getKey().toArray(), e.getValue().clone()))) {
                return;
            }
        }
    }

    @Override
    public List<byte[]> prefixValueLookup(byte[] prefix) {
        List<byte[]> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix)) {
                out.add(e.getValue().clone());
            }
        }
        return out;
    }

    @Override
    public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] prefix) {
        List<Pair<byte[], byte[]>> out = new ArrayList<>();
        for (Map.Entry<Bytes, byte[]> e : map.entrySet()) {
            if (startsWith(e.getKey(), prefix)) {
                out.add(Pair.of(e.getKey().toArray(), e.getValue().clone()));
            }
        }
        return out;
    }

    private static boolean startsWith(Bytes key, byte[] prefix) {
        return key.size() >= prefix.length && key.slice(0, prefix.length).equals(Bytes.wrap(prefix));
    }
}
```

- [ ] **Step 2: 写失败测试**

`src/test/java/io/xdag/db/rocksdb/BatchWriteTest.java`：

```java
package io.xdag.db.rocksdb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.chain.InMemoryKVSource;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BatchWriteTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    @Test
    public void defaultBatchWriteAppliesPutsThenDeletes() {
        InMemoryKVSource src = new InMemoryKVSource();
        src.init();
        src.put(b(9), b(9));
        src.batchWrite(List.of(Pair.of(b(1), b(11)), Pair.of(b(2), b(22)), Pair.of(b(3), null)), List.of(b(9), b(2)));
        assertArrayEquals(b(11), src.get(b(1)));
        assertNull(src.get(b(2)));
        assertNull(src.get(b(3)));
        assertNull(src.get(b(9)));
    }

    @Test
    public void rocksBatchWriteUsesWriteBatch() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource src = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        src.setConfig(config);
        src.init();
        try {
            src.put(b(9), b(9));
            src.batchWrite(List.of(Pair.of(b(1), b(11)), Pair.of(b(2), b(22)), Pair.of(b(3), null)), List.of(b(9)));
            assertArrayEquals(b(11), src.get(b(1)));
            assertArrayEquals(b(22), src.get(b(2)));
            assertNull(src.get(b(3)));
            assertNull(src.get(b(9)));
            assertEquals(2, src.keys().size());
        } finally {
            src.close();
        }
    }

    @Test
    public void factoryKnowsChainL1() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbFactory factory = new RocksdbFactory(config);
        KVSource<byte[], byte[]> db = factory.getDB(DatabaseName.CHAIN_L1);
        assertNotNull(db);
        assertEquals("CHAIN_L1", db.getName());
        factory.close();
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.db.rocksdb.BatchWriteTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误（`batchWrite` 不存在 / `CHAIN_L1` 不存在）。

- [ ] **Step 4: 实现**

`DatabaseName.java`：在 `TXHISTORY` 后追加：

```java
    TXHISTORY,
    /** Chain contracts: global L1 state (registry, code store, input index, bonds, anchors). */
    CHAIN_L1
```

`KVSource.java`：接口内追加默认方法（文件已 import `java.util.List` 与 `org.apache.commons.lang3.tuple.Pair`）：

```java
    /**
     * Applies all puts (a null value means delete) and then all deletes. The default applies them one by one;
     * transactional backends override it so the whole batch is atomic.
     */
    default void batchWrite(List<Pair<K, V>> puts, List<K> deletes) {
        for (Pair<K, V> p : puts) {
            put(p.getKey(), p.getValue());
        }
        for (K k : deletes) {
            delete(k);
        }
    }
```

`RocksdbKVSource.java`：新增 import `org.rocksdb.WriteBatch`、`org.rocksdb.WriteOptions`、`java.util.List`、`org.apache.commons.lang3.tuple.Pair`（后两者若已存在则跳过），在 `delete(...)` 之后新增：

```java
    @Override
    public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
        resetDbLock.readLock().lock();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            for (Pair<byte[], byte[]> p : puts) {
                if (p.getValue() == null) {
                    batch.delete(p.getKey());
                } else {
                    batch.put(p.getKey(), p.getValue());
                }
            }
            for (byte[] k : deletes) {
                batch.delete(k);
            }
            db.write(options, batch);
        } catch (RocksDBException e) {
            log.error("Failed to batch write into db '{}'", name, e);
            hintOnTooManyOpenFiles(e);
            throw new RuntimeException(e);
        } finally {
            resetDbLock.readLock().unlock();
        }
    }
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.db.rocksdb.BatchWriteTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/db/rocksdb/DatabaseName.java src/main/java/io/xdag/db/rocksdb/KVSource.java src/main/java/io/xdag/db/rocksdb/RocksdbKVSource.java src/test/java/io/xdag/chain/InMemoryKVSource.java src/test/java/io/xdag/db/rocksdb/BatchWriteTest.java
git commit -m "Add CHAIN_L1 database and atomic batchWrite to KVSource

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 11: `CHAIN_L1` 存储：记录、键、批量写、快照导出/导入/哈希

**Files:**
- Create: `src/main/java/io/xdag/chain/l1/InputStatus.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainRecord.java`
- Create: `src/main/java/io/xdag/chain/l1/ContractRecord.java`
- Create: `src/main/java/io/xdag/chain/l1/InputRecord.java`
- Create: `src/main/java/io/xdag/chain/l1/InputRef.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainL1Keys.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainL1Batch.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainL1Store.java`
- Test: `src/test/java/io/xdag/chain/l1/ChainL1StoreTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.l1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.ext.ExtKind;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChainL1StoreTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes CONTRACT = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();
    private static final Bytes32 BLOCK = Bytes32.random();

    private static ChainL1Store memStore() {
        ChainL1Store s = new ChainL1Store(new InMemoryKVSource());
        s.start();
        return s;
    }

    @Test
    public void startWritesSchemaVersionOnce() {
        InMemoryKVSource src = new InMemoryKVSource();
        ChainL1Store s = new ChainL1Store(src);
        s.start();
        assertEquals(1, src.keys().size());
        assertEquals(ChainL1Store.SCHEMA_VERSION, s.schemaVersion());
        s.start();
        assertEquals(1, src.keys().size());
    }

    @Test
    public void recordsRoundTrip() {
        ChainRecord chain = new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 2L);
        assertEquals(chain, ChainRecord.decode(chain.encode()));
        assertEquals(3L, chain.withContractCount(3L).contractCount());
        ContractRecord contract = new ContractRecord(CHAIN, CODE_HASH, 7L, BLOCK);
        assertEquals(contract, ContractRecord.decode(contract.encode()));
        InputRecord in = new InputRecord(BLOCK, ExtKind.CALL, InputStatus.INVALID_FEE, CONTRACT);
        assertEquals(in, InputRecord.decode(in.encode()));
        InputRecord none = new InputRecord(BLOCK, null, InputStatus.INVALID_FORMAT, Bytes.wrap(new byte[20]));
        assertEquals(none, InputRecord.decode(none.encode()));
        List<InputRef> refs = List.of(new InputRef(CHAIN, 7L, 0L), new InputRef(CHAIN, 7L, 1L));
        assertEquals(refs, InputRef.decodeList(InputRef.encodeList(refs)));
        assertEquals(InputStatus.CODE_TOO_LARGE, InputStatus.fromCode(3));
    }

    @Test
    public void batchCommitWritesEverythingInOneCall() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryKVSource src = new InMemoryKVSource() {
            @Override
            public void batchWrite(List<Pair<byte[], byte[]>> puts, List<byte[]> deletes) {
                calls.incrementAndGet();
                super.batchWrite(puts, deletes);
            }
        };
        ChainL1Store s = new ChainL1Store(src);
        s.start();

        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        batch.putContract(CONTRACT, new ContractRecord(CHAIN, CODE_HASH, 7L, BLOCK));
        batch.putCode(CODE_HASH, 1L, Bytes.of((byte) 1, (byte) 2, (byte) 3));
        batch.putCallCount(CHAIN, 7L, 1L);
        batch.putInput(CHAIN, 7L, 0L, new InputRecord(BLOCK, ExtKind.DEPLOY, InputStatus.OK, CONTRACT));
        batch.putReverse(BLOCK, List.of(new InputRef(CHAIN, 7L, 0L)));
        s.commit(batch);

        assertEquals(1, calls.get());
        assertTrue(s.hasChain(CHAIN));
        assertEquals(1L, s.getChain(CHAIN).contractCount());
        assertEquals(CHAIN, s.getContract(CONTRACT).chainId());
        assertTrue(s.hasCode(CODE_HASH));
        assertEquals(1L, s.getCodeRefCount(CODE_HASH));
        assertEquals(Bytes.of((byte) 1, (byte) 2, (byte) 3), s.getCode(CODE_HASH));
        assertEquals(1L, s.getCallCount(CHAIN, 7L));
        assertEquals(InputStatus.OK, s.getInput(CHAIN, 7L, 0L).status());
        assertEquals(1, s.getReverse(BLOCK).size());

        ChainL1Batch undo = new ChainL1Batch();
        undo.deleteChain(CHAIN);
        undo.deleteContract(CONTRACT);
        undo.deleteCode(CODE_HASH);
        undo.deleteCallCount(CHAIN, 7L);
        undo.deleteInput(CHAIN, 7L, 0L);
        undo.deleteReverse(BLOCK);
        s.commit(undo);
        assertEquals(2, calls.get());
        assertFalse(s.hasChain(CHAIN));
        assertNull(s.getContract(CONTRACT));
        assertFalse(s.hasCode(CODE_HASH));
        assertEquals(0L, s.getCodeRefCount(CODE_HASH));
        assertEquals(0L, s.getCallCount(CHAIN, 7L));
        assertNull(s.getInput(CHAIN, 7L, 0L));
        assertTrue(s.getReverse(BLOCK).isEmpty());
        assertEquals(1, src.keys().size()); // only META remains

        s.commit(new ChainL1Batch());
        assertEquals(2, calls.get()); // empty batch does not touch the store
    }

    @Test
    public void snapshotExportImportAndHash() {
        ChainL1Store a = memStore();
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
        batch.putCode(CODE_HASH, 2L, Bytes.random(1000));
        a.commit(batch);
        Bytes32 hashA = a.stateHash();

        InMemoryKVSource snap = new InMemoryKVSource();
        a.exportSnapshot(snap);
        assertEquals(hashA, Bytes32.wrap(snap.get(new byte[]{ChainL1Keys.SNAPSHOT_HASH})));

        ChainL1Store b = memStore();
        b.importSnapshot(snap);
        assertEquals(hashA, b.stateHash());
        assertTrue(b.hasChain(CHAIN));
        assertEquals(2L, b.getCodeRefCount(CODE_HASH));

        // tampering is detected
        snap.put(ChainL1Keys.code(CODE_HASH), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        ChainL1Store c = memStore();
        assertThrows(IllegalStateException.class, () -> c.importSnapshot(snap));

        // a snapshot without a hash is refused
        InMemoryKVSource noHash = new InMemoryKVSource();
        a.exportSnapshot(noHash);
        noHash.delete(new byte[]{ChainL1Keys.SNAPSHOT_HASH});
        assertThrows(IllegalStateException.class, () -> memStore().importSnapshot(noHash));

        // hash depends on content
        ChainL1Batch more = new ChainL1Batch();
        more.putCallCount(CHAIN, 8L, 1L);
        a.commit(more);
        assertNotEquals(hashA, a.stateHash());
    }

    @Test
    public void rocksAndMemoryProduceTheSameHashForTheSameContent() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        RocksdbKVSource rocks = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        rocks.setConfig(config);
        ChainL1Store onRocks = new ChainL1Store(rocks);
        onRocks.start();
        ChainL1Store inMem = memStore();
        try {
            for (ChainL1Store s : List.of(onRocks, inMem)) {
                ChainL1Batch batch = new ChainL1Batch();
                batch.putChain(CHAIN, new ChainRecord(7L, BLOCK, 5L, 32L, 10_000_000L, 1L));
                batch.putContract(CONTRACT, new ContractRecord(CHAIN, CODE_HASH, 7L, BLOCK));
                batch.putInput(CHAIN, 7L, 0L, new InputRecord(BLOCK, ExtKind.DEPLOY, InputStatus.OK, CONTRACT));
                s.commit(batch);
            }
            assertEquals(inMem.stateHash(), onRocks.stateHash());
            assertEquals(4, onRocks.sortedKeys().size());
        } finally {
            onRocks.stop();
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1StoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `package io.xdag.chain.l1 does not exist`。

- [ ] **Step 3: 实现记录类型**

`src/main/java/io/xdag/chain/l1/InputStatus.java`：

```java
package io.xdag.chain.l1;

/** Outcome of L1-level attribution of a chain input. All statuses produce an input record. */
public enum InputStatus {
    OK(0), INVALID_FORMAT(1), INVALID_FEE(2), CODE_TOO_LARGE(3);

    private final byte code;

    InputStatus(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    public static InputStatus fromCode(int code) {
        for (InputStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown input status " + code);
    }
}
```

`src/main/java/io/xdag/chain/l1/ChainRecord.java`：

```java
package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes32;

/** CHAIN_L1 0x01 value: createdHeight u64 | createBlockHash 32 | gasPriceNano u64 | D u32 | maxCallGas u32 | contractCount u32. */
public record ChainRecord(long createdHeight, Bytes32 createBlockHash, long gasPriceNano, long deliveryDelayD,
                         long maxCallGas, long contractCount) {

    public static final int SIZE = 8 + 32 + 8 + 4 + 4 + 4;

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        ExtCodec.putU64(a, 0, createdHeight);
        System.arraycopy(createBlockHash.toArray(), 0, a, 8, 32);
        ExtCodec.putU64(a, 40, gasPriceNano);
        ExtCodec.putU32(a, 48, deliveryDelayD);
        ExtCodec.putU32(a, 52, maxCallGas);
        ExtCodec.putU32(a, 56, contractCount);
        return a;
    }

    public static ChainRecord decode(byte[] a) {
        return new ChainRecord(ExtCodec.u64(a, 0), Bytes32.wrap(Arrays.copyOfRange(a, 8, 40)), ExtCodec.u64(a, 40),
                ExtCodec.u32(a, 48), ExtCodec.u32(a, 52), ExtCodec.u32(a, 56));
    }

    public ChainRecord withContractCount(long count) {
        return new ChainRecord(createdHeight, createBlockHash, gasPriceNano, deliveryDelayD, maxCallGas, count);
    }
}
```

`src/main/java/io/xdag/chain/l1/ContractRecord.java`：

```java
package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** CHAIN_L1 0x02 value: chainId 20 | codeHash 32 | deployHeight u64 | deployBlockHash 32. */
public record ContractRecord(Bytes chainId, Bytes32 codeHash, long deployHeight, Bytes32 deployBlockHash) {

    public static final int SIZE = 20 + 32 + 8 + 32;

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(chainId.toArray(), 0, a, 0, 20);
        System.arraycopy(codeHash.toArray(), 0, a, 20, 32);
        ExtCodec.putU64(a, 52, deployHeight);
        System.arraycopy(deployBlockHash.toArray(), 0, a, 60, 32);
        return a;
    }

    public static ContractRecord decode(byte[] a) {
        return new ContractRecord(Bytes.wrap(Arrays.copyOfRange(a, 0, 20)), Bytes32.wrap(Arrays.copyOfRange(a, 20, 52)),
                ExtCodec.u64(a, 52), Bytes32.wrap(Arrays.copyOfRange(a, 60, 92)));
    }
}
```

`src/main/java/io/xdag/chain/l1/InputRecord.java`：

```java
package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtKind;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** CHAIN_L1 0x0C value: blockHash 32 | kind u8 (0 = none) | status u8 | contract 20. */
public record InputRecord(Bytes32 blockHash, ExtKind kind, InputStatus status, Bytes contract) {

    public static final int SIZE = 32 + 1 + 1 + 20;

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(blockHash.toArray(), 0, a, 0, 32);
        a[32] = kind == null ? 0 : kind.code();
        a[33] = status.code();
        System.arraycopy(contract.toArray(), 0, a, 34, 20);
        return a;
    }

    public static InputRecord decode(byte[] a) {
        int kindCode = a[32] & 0xff;
        return new InputRecord(Bytes32.wrap(Arrays.copyOfRange(a, 0, 32)), kindCode == 0 ? null : ExtKind.fromCode(kindCode),
                InputStatus.fromCode(a[33] & 0xff), Bytes.wrap(Arrays.copyOfRange(a, 34, 54)));
    }
}
```

`src/main/java/io/xdag/chain/l1/InputRef.java`：

```java
package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;

/** Address of one input record: chainId 20 | height u64 | index u32. A block can own several (one per vault output). */
public record InputRef(Bytes chainId, long height, long index) {

    public static final int SIZE = 20 + 8 + 4;

    public byte[] encode() {
        byte[] a = new byte[SIZE];
        System.arraycopy(chainId.toArray(), 0, a, 0, 20);
        ExtCodec.putU64(a, 20, height);
        ExtCodec.putU32(a, 28, index);
        return a;
    }

    public static InputRef decode(byte[] a, int off) {
        return new InputRef(Bytes.wrap(Arrays.copyOfRange(a, off, off + 20)), ExtCodec.u64(a, off + 20), ExtCodec.u32(a, off + 28));
    }

    public static byte[] encodeList(List<InputRef> refs) {
        byte[] a = new byte[refs.size() * SIZE];
        for (int i = 0; i < refs.size(); i++) {
            System.arraycopy(refs.get(i).encode(), 0, a, i * SIZE, SIZE);
        }
        return a;
    }

    public static List<InputRef> decodeList(byte[] a) {
        List<InputRef> out = new ArrayList<>(a.length / SIZE);
        for (int off = 0; off + SIZE <= a.length; off += SIZE) {
            out.add(decode(a, off));
        }
        return out;
    }
}
```

- [ ] **Step 4: 实现键、批与存储**

`src/main/java/io/xdag/chain/l1/ChainL1Keys.java`：

```java
package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import io.xdag.utils.BytesUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Key layout of the CHAIN_L1 RocksDB instance (one-byte prefixes). Prefixes 0x04..0x0B are reserved for SP2/SP3. */
public final class ChainL1Keys {

    public static final byte META = 0x00;
    public static final byte CHAIN = 0x01;
    public static final byte CONTRACT = 0x02;
    public static final byte CODE = 0x03;
    public static final byte BOND = 0x04;
    public static final byte ANCHOR_HEAD = 0x05;
    public static final byte ANCHOR = 0x06;
    public static final byte CALL_COUNT = 0x07;
    public static final byte CLAIMED = 0x08;
    public static final byte HEIGHT_TRIGGER = 0x09;
    public static final byte CHALLENGE = 0x0A;
    public static final byte SEGMENT_CURSOR = 0x0B;
    public static final byte INPUT = 0x0C;
    public static final byte REVERSE = 0x0E;
    /** Only inside a snapshot database: the state hash of everything else. */
    public static final byte SNAPSHOT_HASH = (byte) 0xFF;

    public static final byte[] META_KEY = {META};
    public static final byte[] SNAPSHOT_HASH_KEY = {SNAPSHOT_HASH};

    private ChainL1Keys() {
    }

    public static byte[] chain(Bytes chainId) {
        return BytesUtils.merge(CHAIN, chainId.toArray());
    }

    public static byte[] contract(Bytes contract) {
        return BytesUtils.merge(CONTRACT, contract.toArray());
    }

    public static byte[] code(Bytes32 codeHash) {
        return BytesUtils.merge(CODE, codeHash.toArray());
    }

    public static byte[] callCount(Bytes chainId, long height) {
        byte[] h = new byte[8];
        ExtCodec.putU64(h, 0, height);
        return BytesUtils.merge(new byte[]{CALL_COUNT}, chainId.toArray(), h);
    }

    public static byte[] input(Bytes chainId, long height, long index) {
        byte[] tail = new byte[12];
        ExtCodec.putU64(tail, 0, height);
        ExtCodec.putU32(tail, 8, index);
        return BytesUtils.merge(new byte[]{INPUT}, chainId.toArray(), tail);
    }

    public static byte[] reverse(Bytes32 blockHash) {
        return BytesUtils.merge(REVERSE, blockHash.toArray());
    }
}
```

`src/main/java/io/xdag/chain/l1/ChainL1Batch.java`：

```java
package io.xdag.chain.l1;

import io.xdag.chain.ext.ExtCodec;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Collects the CHAIN_L1 writes of one block; committed atomically by {@link ChainL1Store#commit}. */
public final class ChainL1Batch {

    private final List<Pair<byte[], byte[]>> puts = new ArrayList<>();
    private final List<byte[]> deletes = new ArrayList<>();

    public List<Pair<byte[], byte[]>> puts() {
        return puts;
    }

    public List<byte[]> deletes() {
        return deletes;
    }

    public boolean isEmpty() {
        return puts.isEmpty() && deletes.isEmpty();
    }

    public void putChain(Bytes chainId, ChainRecord record) {
        puts.add(Pair.of(ChainL1Keys.chain(chainId), record.encode()));
    }

    public void deleteChain(Bytes chainId) {
        deletes.add(ChainL1Keys.chain(chainId));
    }

    public void putContract(Bytes contract, ContractRecord record) {
        puts.add(Pair.of(ChainL1Keys.contract(contract), record.encode()));
    }

    public void deleteContract(Bytes contract) {
        deletes.add(ChainL1Keys.contract(contract));
    }

    /** value: refCount u32 | len u32 | bytes */
    public void putCode(Bytes32 codeHash, long refCount, Bytes code) {
        byte[] v = new byte[8 + code.size()];
        ExtCodec.putU32(v, 0, refCount);
        ExtCodec.putU32(v, 4, code.size());
        System.arraycopy(code.toArray(), 0, v, 8, code.size());
        puts.add(Pair.of(ChainL1Keys.code(codeHash), v));
    }

    public void deleteCode(Bytes32 codeHash) {
        deletes.add(ChainL1Keys.code(codeHash));
    }

    public void putCallCount(Bytes chainId, long height, long count) {
        byte[] v = new byte[4];
        ExtCodec.putU32(v, 0, count);
        puts.add(Pair.of(ChainL1Keys.callCount(chainId, height), v));
    }

    public void deleteCallCount(Bytes chainId, long height) {
        deletes.add(ChainL1Keys.callCount(chainId, height));
    }

    public void putInput(Bytes chainId, long height, long index, InputRecord record) {
        puts.add(Pair.of(ChainL1Keys.input(chainId, height, index), record.encode()));
    }

    public void deleteInput(Bytes chainId, long height, long index) {
        deletes.add(ChainL1Keys.input(chainId, height, index));
    }

    public void putReverse(Bytes32 blockHash, List<InputRef> refs) {
        puts.add(Pair.of(ChainL1Keys.reverse(blockHash), InputRef.encodeList(refs)));
    }

    public void deleteReverse(Bytes32 blockHash) {
        deletes.add(ChainL1Keys.reverse(blockHash));
    }
}
```

`src/main/java/io/xdag/chain/l1/ChainL1Store.java`：

```java
package io.xdag.chain.l1;

import io.xdag.core.XdagLifecycle;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.chain.ext.ExtCodec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Global (every node) chain state: registry, contracts, code store, input index. All writes go through {@link ChainL1Batch}. */
public final class ChainL1Store implements XdagLifecycle {

    public static final int SCHEMA_VERSION = 1;

    private final KVSource<byte[], byte[]> source;
    private volatile boolean running;

    public ChainL1Store(KVSource<byte[], byte[]> source) {
        this.source = source;
    }

    @Override
    public void start() {
        source.init();
        ensureMeta();
        running = true;
    }

    @Override
    public void stop() {
        source.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void reset() {
        source.reset();
        ensureMeta();
    }

    private void ensureMeta() {
        if (source.get(ChainL1Keys.META_KEY) == null) {
            byte[] v = new byte[4];
            ExtCodec.putU32(v, 0, SCHEMA_VERSION);
            source.put(ChainL1Keys.META_KEY, v);
        }
    }

    public int schemaVersion() {
        byte[] v = source.get(ChainL1Keys.META_KEY);
        return v == null ? 0 : (int) ExtCodec.u32(v, 0);
    }

    public boolean hasChain(Bytes chainId) {
        return source.get(ChainL1Keys.chain(chainId)) != null;
    }

    public ChainRecord getChain(Bytes chainId) {
        byte[] v = source.get(ChainL1Keys.chain(chainId));
        return v == null ? null : ChainRecord.decode(v);
    }

    public ContractRecord getContract(Bytes contract) {
        byte[] v = source.get(ChainL1Keys.contract(contract));
        return v == null ? null : ContractRecord.decode(v);
    }

    public boolean hasCode(Bytes32 codeHash) {
        return source.get(ChainL1Keys.code(codeHash)) != null;
    }

    public long getCodeRefCount(Bytes32 codeHash) {
        byte[] v = source.get(ChainL1Keys.code(codeHash));
        return v == null ? 0 : ExtCodec.u32(v, 0);
    }

    public Bytes getCode(Bytes32 codeHash) {
        byte[] v = source.get(ChainL1Keys.code(codeHash));
        if (v == null) {
            return null;
        }
        int len = (int) ExtCodec.u32(v, 4);
        return Bytes.wrap(Arrays.copyOfRange(v, 8, 8 + len));
    }

    public long getCallCount(Bytes chainId, long height) {
        byte[] v = source.get(ChainL1Keys.callCount(chainId, height));
        return v == null ? 0 : ExtCodec.u32(v, 0);
    }

    public InputRecord getInput(Bytes chainId, long height, long index) {
        byte[] v = source.get(ChainL1Keys.input(chainId, height, index));
        return v == null ? null : InputRecord.decode(v);
    }

    public List<InputRef> getReverse(Bytes32 blockHash) {
        byte[] v = source.get(ChainL1Keys.reverse(blockHash));
        return v == null ? List.of() : InputRef.decodeList(v);
    }

    public void commit(ChainL1Batch batch) {
        if (!batch.isEmpty()) {
            source.batchWrite(batch.puts(), batch.deletes());
        }
    }

    // ---- snapshot support ----

    /** All keys in byte-wise lexicographic order. */
    public List<byte[]> sortedKeys() {
        return sortedKeysOf(source);
    }

    private static List<byte[]> sortedKeysOf(KVSource<byte[], byte[]> src) {
        List<byte[]> keys = new ArrayList<>(src.keys());
        keys.sort((a, b) -> Bytes.wrap(a).compareTo(Bytes.wrap(b)));
        return keys;
    }

    private static boolean isSnapshotHashKey(byte[] key) {
        return key.length == 1 && key[0] == ChainL1Keys.SNAPSHOT_HASH;
    }

    public Bytes32 stateHash() {
        return stateHashOf(source);
    }

    /** sha256 over sorted (len(key) u32 | key | len(value) u32 | value), skipping the snapshot hash key itself. */
    public static Bytes32 stateHashOf(KVSource<byte[], byte[]> src) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] len = new byte[4];
        for (byte[] k : sortedKeysOf(src)) {
            if (isSnapshotHashKey(k)) {
                continue;
            }
            byte[] v = src.get(k);
            ExtCodec.putU32(len, 0, k.length);
            md.update(len);
            md.update(k);
            ExtCodec.putU32(len, 0, v.length);
            md.update(len);
            md.update(v);
        }
        return Bytes32.wrap(md.digest());
    }

    public void exportSnapshot(KVSource<byte[], byte[]> target) {
        for (byte[] k : sortedKeys()) {
            target.put(k, source.get(k));
        }
        target.put(ChainL1Keys.SNAPSHOT_HASH_KEY, stateHash().toArray());
    }

    /** Copies every key from the snapshot and verifies the recorded state hash; throws on mismatch or missing hash. */
    public void importSnapshot(KVSource<byte[], byte[]> from) {
        byte[] expected = from.get(ChainL1Keys.SNAPSHOT_HASH_KEY);
        if (expected == null) {
            throw new IllegalStateException("CHAIN_L1 snapshot has no state hash");
        }
        for (byte[] k : sortedKeysOf(from)) {
            if (isSnapshotHashKey(k)) {
                continue;
            }
            source.put(k, from.get(k));
        }
        Bytes32 actual = stateHash();
        if (!actual.equals(Bytes32.wrap(expected))) {
            throw new IllegalStateException("CHAIN_L1 snapshot hash mismatch: expected " + Bytes32.wrap(expected)
                    + " actual " + actual);
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1StoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/chain/l1/ src/test/java/io/xdag/chain/l1/ChainL1StoreTest.java
git commit -m "Add CHAIN_L1 store with records, batched writes and snapshot support

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 12: `ChainSpec` 配置与 `ChainActivation`

**Files:**
- Create: `src/main/java/io/xdag/config/spec/ChainSpec.java`
- Modify: `src/main/java/io/xdag/config/Config.java`
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java`
- Modify: `src/main/java/io/xdag/config/DevnetConfig.java`
- Modify: `src/main/java/io/xdag/config/TestnetConfig.java`
- Modify: `src/main/java/io/xdag/config/MainnetConfig.java`
- Create: `src/main/java/io/xdag/chain/ChainActivation.java`
- Test: `src/test/java/io/xdag/config/ChainSpecTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.chain.ChainActivation;
import org.junit.Test;

public class ChainSpecTest {

    @Test
    public void devnetIsActiveFromGenesisWithProtocolDefaults() {
        Config config = new DevnetConfig();
        assertEquals(0L, config.getChainSpec().getChainActivationHeight());
        assertEquals(4096, config.getChainSpec().getChainMaxChunksPerChain());
        assertEquals(1024 * 1024, config.getChainSpec().getChainMaxWasmBytes());
        assertEquals(256, config.getChainSpec().getChainMaxInlineArgs());
        assertEquals(XAmount.of(10, XUnit.MILLI_XDAG), config.getChainSpec().getChainChunkFee());
    }

    @Test
    public void sharedNetworksAreNotScheduled() {
        assertEquals(Long.MAX_VALUE, new MainnetConfig().getChainSpec().getChainActivationHeight());
        assertEquals(Long.MAX_VALUE, new TestnetConfig().getChainSpec().getChainActivationHeight());
    }

    @Test
    public void activationCanBeOverriddenProgrammatically() {
        Config config = new DevnetConfig();
        config.getChainSpec().setChainActivationHeight(100L);
        ChainActivation activation = new ChainActivation(config.getChainSpec());
        assertFalse(activation.isActive(99L));
        assertTrue(activation.isActive(100L));
        assertTrue(activation.isActive(101L));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.config.ChainSpecTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: method getChainSpec()`。

- [ ] **Step 3: 实现**

`src/main/java/io/xdag/config/spec/ChainSpec.java`：

```java
package io.xdag.config.spec;

import io.xdag.core.XAmount;

/** Chain (DAG-native contract) protocol parameters. Activation height gates every chain hook in setMain/applyBlock. */
public interface ChainSpec {

    long getChainActivationHeight();

    void setChainActivationHeight(long height);

    int getChainMaxChunksPerChain();

    int getChainMaxWasmBytes();

    int getChainMaxInlineArgs();

    XAmount getChainChunkFee();
}
```

`Config.java`：在 `SnapshotSpec getSnapshotSpec();` 之后追加 `ChainSpec getChainSpec();`（并 import `io.xdag.config.spec.ChainSpec`；文件已 `import io.xdag.config.spec.*` 则无需）。

`AbstractConfig.java`：

(a) 类声明 `implements Config, AdminSpec, NodeSpec, WalletSpec, RPCSpec, SnapshotSpec, RandomxSpec, FundSpec` 末尾追加 `, ChainSpec`。

(b) 字段区（`// RandomX configuration` 之前）新增：

```java
    // Chain (DAG-native contracts) configuration
    protected long chainActivationHeight = Long.MAX_VALUE;
    protected Long chainActivationHeightOverride;
    protected int chainMaxChunksPerChain = 4096;
    protected int chainMaxWasmBytes = 1024 * 1024;
    protected int chainMaxInlineArgs = 256;
    protected XAmount chainChunkFee = XAmount.of(10, XUnit.MILLI_XDAG);
```

确认文件有 `import io.xdag.core.XUnit;`（没有则加）。

(c) `getSetting()` 末尾（`flag = ...` 之后）追加：

```java
        // Chain configuration overrides (defaults live in the per-network constructors)
        chainActivationHeightOverride = config.hasPath("chain.activation.height") ? config.getLong("chain.activation.height") : null;
        if (config.hasPath("chain.chunk.maxPerChain")) {
            chainMaxChunksPerChain = config.getInt("chain.chunk.maxPerChain");
        }
        if (config.hasPath("chain.wasm.maxBytes")) {
            chainMaxWasmBytes = config.getInt("chain.wasm.maxBytes");
        }
        if (config.hasPath("chain.args.maxInline")) {
            chainMaxInlineArgs = config.getInt("chain.args.maxInline");
        }
        if (config.hasPath("chain.chunk.feeMilliXdag")) {
            chainChunkFee = XAmount.of(config.getLong("chain.chunk.feeMilliXdag"), XUnit.MILLI_XDAG);
        }
```

`getSetting()` 在父构造器里先跑，子类构造器随后设默认值；用 `chainActivationHeightOverride` 保证 conf 覆盖不被子类默认值冲掉。

(d) 在 `getSnapshotSpec()` 之后新增：

```java
    @Override
    public ChainSpec getChainSpec() {
        return this;
    }

    @Override
    public long getChainActivationHeight() {
        return chainActivationHeightOverride != null ? chainActivationHeightOverride : chainActivationHeight;
    }

    @Override
    public void setChainActivationHeight(long height) {
        this.chainActivationHeightOverride = null;
        this.chainActivationHeight = height;
    }

    @Override
    public int getChainMaxChunksPerChain() {
        return chainMaxChunksPerChain;
    }

    @Override
    public int getChainMaxWasmBytes() {
        return chainMaxWasmBytes;
    }

    @Override
    public int getChainMaxInlineArgs() {
        return chainMaxInlineArgs;
    }

    @Override
    public XAmount getChainChunkFee() {
        return chainChunkFee;
    }
```

`DevnetConfig.java` 构造器末尾追加 `this.chainActivationHeight = 0;`；`TestnetConfig.java` 与 `MainnetConfig.java` 构造器末尾追加 `this.chainActivationHeight = Long.MAX_VALUE;`。

`src/main/java/io/xdag/chain/ChainActivation.java`：

```java
package io.xdag.chain;

import io.xdag.config.spec.ChainSpec;
import io.xdag.core.XdagStats;

/** Single place that answers "is the chain protocol active at this main height". Used by hooks now, by RPC/wallet gating later. */
public final class ChainActivation {

    private final ChainSpec spec;

    public ChainActivation(ChainSpec spec) {
        this.spec = spec;
    }

    public boolean isActive(long mainHeight) {
        return mainHeight >= spec.getChainActivationHeight();
    }

    public boolean isActiveNow(XdagStats stats) {
        return isActive(stats.nmain);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.config.ChainSpecTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 3, Failures: 0`。若 `MainnetConfig`/`TestnetConfig` 在测试类路径下因缺少必填 conf 键（如 `admin.telnet.password`）抛异常，把第二个测试改为只断言 `new DevnetConfig()` 用 `setChainActivationHeight(Long.MAX_VALUE)` 后的值，并在提交信息里注明。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/xdag/config/spec/ChainSpec.java src/main/java/io/xdag/config/Config.java src/main/java/io/xdag/config/AbstractConfig.java src/main/java/io/xdag/config/DevnetConfig.java src/main/java/io/xdag/config/TestnetConfig.java src/main/java/io/xdag/config/MainnetConfig.java src/main/java/io/xdag/chain/ChainActivation.java src/test/java/io/xdag/config/ChainSpecTest.java
git commit -m "Add ChainSpec configuration and activation height per network

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 13: `ChainIds`、钩子接口与 `ChainL1Processor`

**Files:**
- Create: `src/main/java/io/xdag/chain/l1/ChainIds.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainL1Hooks.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainKindHandler.java`
- Create: `src/main/java/io/xdag/chain/l1/ApplyContext.java`
- Create: `src/main/java/io/xdag/chain/l1/ChainL1Processor.java`
- Test: `src/test/java/io/xdag/chain/l1/ChainL1ProcessorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.l1;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainBlockClassifierTest;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

public class ChainL1ProcessorTest {

    /** A ChainSpec whose limits tests can shrink. */
    static final class TestSpec implements ChainSpec {
        long activation = 0;
        int maxChunks = 4096;
        int maxWasm = 1024 * 1024;

        @Override public long getChainActivationHeight() { return activation; }
        @Override public void setChainActivationHeight(long height) { activation = height; }
        @Override public int getChainMaxChunksPerChain() { return maxChunks; }
        @Override public int getChainMaxWasmBytes() { return maxWasm; }
        @Override public int getChainMaxInlineArgs() { return 256; }
        @Override public XAmount getChainChunkFee() { return XAmount.of(10, XUnit.MILLI_XDAG); }
    }

    private static final long TS = 0x16a00000000L;
    private static final XAmount ONE = XAmount.of(1, XUnit.XDAG);
    private static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    private static final ChainConfigExt CFG = new ChainConfigExt(1L, 32L, 10_000_000L);

    private final Config config = new DevnetConfig();
    private final ECKeyPair sender = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private final Map<Bytes32, Block> dag = new HashMap<>();
    private InMemoryKVSource src;
    private ChainL1Store store;
    private TestSpec spec;
    private ChainL1Processor proc;
    private Block mainBlock;
    private long nonce = 1;
    private final List<Block> appliedOrder = new ArrayList<>();

    @Before
    public void setUp() {
        src = new InMemoryKVSource();
        store = new ChainL1Store(src);
        store.start();
        spec = new TestSpec();
        proc = new ChainL1Processor(store, spec, h -> dag.get(Bytes32.wrap(h.toArray())));
        mainBlock = ChainBlockClassifierTest.extBlock(config, List.of(), List.of());
    }

    private UInt64 nextNonce() {
        return UInt64.valueOf(nonce++);
    }

    /** Registers chunks and the block in the fake DAG and feeds them to the processor in import order (tail first). */
    private void apply(ChainBlockBuilder.Built built) {
        for (int i = built.chunks().size() - 1; i >= 0; i--) {
            apply(built.chunks().get(i));
        }
        apply(built.block());
    }

    private void apply(Block b) {
        dag.put(Bytes32.wrap(b.getHashLow().toArray()), b);
        proc.onBlockApplied(b);
        appliedOrder.add(b);
    }

    private void unapplyAll() {
        for (int i = appliedOrder.size() - 1; i >= 0; i--) {
            proc.onBlockUnapplied(appliedOrder.get(i));
        }
        appliedOrder.clear();
    }

    private ChainBlockBuilder.Built deployNewChain(Bytes wasm) {
        int chunks = ChainBlockBuilder.chunksFor(wasm.size());
        XAmount fee = ChainBlockBuilder.minHeaderFee(spec.getChainChunkFee(), chunks);
        return ChainBlockBuilder.deployNewChain(config, TS, sender, nextNonce(), fee, wasm, CFG, payload(20, 9), 1000L).value();
    }

    private ChainBlockBuilder.Built call(Bytes chainId, Bytes contract, Bytes args, XAmount headerFee) {
        return ChainBlockBuilder.call(config, TS, sender, nextNonce(), chainId, contract, 1, 100L, ONE, headerFee, args).value();
    }

    @Test
    public void deployCreatesChainContractCodeAndInputRecord() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(1000, 1);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm);
        apply(deploy);

        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());
        assertTrue(store.hasChain(chainId));
        ChainRecord chain = store.getChain(chainId);
        assertEquals(10L, chain.createdHeight());
        assertEquals(deploy.block().getHash(), chain.createBlockHash());
        assertEquals(1L, chain.contractCount());
        assertEquals(CFG.gasPriceNano(), chain.gasPriceNano());
        ContractRecord cr = store.getContract(contract);
        assertEquals(chainId, cr.chainId());
        assertEquals(HashUtils.sha256(wasm), cr.codeHash());
        assertEquals(wasm, store.getCode(cr.codeHash()));
        assertEquals(1L, store.getCodeRefCount(cr.codeHash()));
        assertEquals(1L, store.getCallCount(chainId, 10));
        InputRecord in = store.getInput(chainId, 10, 0);
        assertEquals(ExtKind.DEPLOY, in.kind());
        assertEquals(InputStatus.OK, in.status());
        assertEquals(contract, in.contract());
        assertEquals(List.of(new InputRef(chainId, 10, 0)), store.getReverse(deploy.block().getHash()));
        // chunk blocks produce no records
        assertTrue(store.getReverse(deploy.chunks().get(0).getHash()).isEmpty());
    }

    @Test
    public void callsAreRecordedWithStatusesInOrder() {
        proc.onSetMainBegin(10, mainBlock);
        ChainBlockBuilder.Built deploy = deployNewChain(payload(600, 2));
        apply(deploy);
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());

        ChainBlockBuilder.Built ok = call(chainId, contract, payload(20, 3), FEE);
        ChainBlockBuilder.Built unknownContract = call(chainId, Bytes.random(20), payload(20, 4), FEE);
        ChainBlockBuilder.Built lowFee = call(chainId, contract, payload(1000, 5), XAmount.of(20, XUnit.MILLI_XDAG));
        ChainBlockBuilder.Built exactFee = call(chainId, contract, payload(1000, 6), XAmount.of(30, XUnit.MILLI_XDAG));
        apply(ok);
        apply(unknownContract);
        apply(lowFee);
        apply(exactFee);

        // plain transfer into the vault: no EXT at all
        Address from = new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = BlockBuilder.generateNewTransactionBlock(config, sender, TS, from, to, ONE, nextNonce());
        apply(new Block(new XdagBlock(plain.toBytes())));

        assertEquals(6L, store.getCallCount(chainId, 10));
        assertEquals(InputStatus.OK, store.getInput(chainId, 10, 1).status());
        assertEquals(ExtKind.CALL, store.getInput(chainId, 10, 1).kind());
        assertEquals(contract, store.getInput(chainId, 10, 1).contract());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(chainId, 10, 2).status());
        assertNull(store.getInput(chainId, 10, 2).kind());
        assertEquals(InputStatus.INVALID_FEE, store.getInput(chainId, 10, 3).status());
        assertEquals(InputStatus.OK, store.getInput(chainId, 10, 4).status());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(chainId, 10, 5).status());
        assertEquals(ok.block().getHash(), store.getInput(chainId, 10, 1).blockHash());
    }

    @Test
    public void deployIntoChainAndCodeReferenceCounting() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(600, 7);
        ChainBlockBuilder.Built first = deployNewChain(wasm);
        apply(first);
        Bytes chainId = ChainIds.chainIdOf(first.block().getHash());
        Bytes32 codeHash = HashUtils.sha256(wasm);

        ChainBlockBuilder.Built reuse = ChainBlockBuilder.deployIntoChain(config, TS, sender, nextNonce(), chainId, ONE, FEE, null, codeHash, payload(10, 8), 1L).value();
        apply(reuse);
        assertEquals(2L, store.getChain(chainId).contractCount());
        assertEquals(2L, store.getCodeRefCount(codeHash));
        assertEquals(chainId, store.getContract(ChainIds.contractIdOf(reuse.block().getHash())).chainId());

        ChainBlockBuilder.Built unknown = ChainBlockBuilder.deployIntoChain(config, TS, sender, nextNonce(), chainId, ONE, FEE, null, Bytes32.random(), payload(10, 8), 1L).value();
        apply(unknown);
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(chainId, 10, 2).status());
        assertEquals(ExtKind.DEPLOY, store.getInput(chainId, 10, 2).kind());
        assertEquals(2L, store.getChain(chainId).contractCount());
        assertNull(store.getContract(ChainIds.contractIdOf(unknown.block().getHash())));
    }

    @Test
    public void oversizedCodeIsRecordedButNotStored() {
        spec.maxWasm = 500;
        proc.onSetMainBegin(10, mainBlock);
        ChainBlockBuilder.Built deploy = deployNewChain(payload(600, 10));
        apply(deploy);
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertEquals(InputStatus.CODE_TOO_LARGE, store.getInput(chainId, 10, 0).status());
        assertFalse(store.hasChain(chainId));
        assertFalse(store.hasCode(HashUtils.sha256(payload(600, 10))));
        assertEquals(1L, store.getCallCount(chainId, 10));
    }

    @Test
    public void nothingHappensBelowActivationOrOutsideSetMain() {
        spec.activation = 100;
        proc.onSetMainBegin(99, mainBlock);
        apply(deployNewChain(payload(600, 11)));
        assertEquals(1, src.keys().size());
        proc.onSetMainEnd(99, mainBlock);

        spec.activation = 0;
        // no onSetMainBegin: context is null, hook must be inert
        apply(deployNewChain(payload(600, 12)));
        assertEquals(1, src.keys().size());
    }

    @Test
    public void handlersAreDispatchedForOtherKinds() {
        List<Classified> seen = new ArrayList<>();
        proc.registerHandler(ExtKind.BOND, new ChainKindHandler() {
            @Override
            public void onApplied(Block block, Classified classified, ApplyContext ctx) {
                assertEquals(10L, ctx.height());
                seen.add(classified);
            }

            @Override
            public void onUnapplied(Block block, Classified classified) {
                seen.remove(classified);
            }
        });
        proc.onSetMainBegin(10, mainBlock);
        BondExt bond = new BondExt(false, Bytes.random(20), 0L);
        Block bondBlock = ChainBlockClassifierTest.extBlock(config, List.of(bond.encodeHeader()), List.of());
        apply(bondBlock);
        assertEquals(1, seen.size());
        assertEquals(bond, seen.get(0).as(BondExt.class));
        assertEquals(1, src.keys().size()); // stub handler writes nothing
        proc.onBlockUnapplied(bondBlock);
        assertTrue(seen.isEmpty());
    }

    @Test
    public void unapplyRestoresAnEmptyStore() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(900, 13);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm);
        apply(deploy);
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());
        apply(call(chainId, contract, payload(20, 14), FEE));
        apply(ChainBlockBuilder.deployIntoChain(config, TS, sender, nextNonce(), chainId, ONE, FEE, null, HashUtils.sha256(wasm), Bytes.EMPTY, 1L).value());
        proc.onSetMainEnd(10, mainBlock);
        assertTrue(src.keys().size() > 1);

        proc.onSetMainBegin(11, mainBlock);
        apply(call(chainId, contract, payload(20, 15), FEE));
        proc.onSetMainEnd(11, mainBlock);
        assertEquals(1L, store.getCallCount(chainId, 11));

        unapplyAll();
        assertEquals(1, src.keys().size());
        assertNotNull(src.get(ChainL1Keys.META_KEY));
        assertSame(null, store.getChain(chainId));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1ProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChainL1Processor`。

- [ ] **Step 3: 实现接口与工具**

`src/main/java/io/xdag/chain/l1/ChainIds.java`：

```java
package io.xdag.chain.l1;

import io.xdag.core.Address;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.utils.BasicUtils;
import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Deterministic identifiers: chainId / contractId are derived from the creating block hash; chainId is also the vault address. */
public final class ChainIds {

    private static final Bytes CHAIN_TAG = Bytes.wrap("xdag-chain".getBytes(StandardCharsets.US_ASCII));
    private static final Bytes CONTRACT_TAG = Bytes.wrap("xdag-contract".getBytes(StandardCharsets.US_ASCII));
    public static final Bytes ZERO_ADDRESS = Bytes.wrap(new byte[20]);

    private ChainIds() {
    }

    public static Bytes chainIdOf(Bytes32 blockHash) {
        return Bytes.wrap(HashUtils.sha256(Bytes.concatenate(CHAIN_TAG, blockHash)).slice(0, 20).toArray());
    }

    public static Bytes contractIdOf(Bytes32 blockHash) {
        return Bytes.wrap(HashUtils.sha256(Bytes.concatenate(CONTRACT_TAG, blockHash)).slice(0, 20).toArray());
    }

    /** The 20-byte address carried by an address-type link (INPUT/OUTPUT/COINBASE). */
    public static Bytes address20(Address address) {
        return Bytes.wrap(BasicUtils.hash2byte(address.getAddress()).toArray());
    }
}
```

`src/main/java/io/xdag/chain/l1/ChainL1Hooks.java`：

```java
package io.xdag.chain.l1;

import io.xdag.core.Block;

/** The five points where BlockchainImpl hands control to the chain layer. Every implementation must be reorg-symmetric. */
public interface ChainL1Hooks {

    void onSetMainBegin(long height, Block mainBlock);

    void onBlockApplied(Block block);

    void onSetMainEnd(long height, Block mainBlock);

    void onBlockUnapplied(Block block);

    void onUnsetMain(long height, Block mainBlock);

    ChainL1Hooks NOOP = new ChainL1Hooks() {
        @Override
        public void onSetMainBegin(long height, Block mainBlock) {
        }

        @Override
        public void onBlockApplied(Block block) {
        }

        @Override
        public void onSetMainEnd(long height, Block mainBlock) {
        }

        @Override
        public void onBlockUnapplied(Block block) {
        }

        @Override
        public void onUnsetMain(long height, Block mainBlock) {
        }
    };
}
```

`src/main/java/io/xdag/chain/l1/ChainKindHandler.java`：

```java
package io.xdag.chain.l1;

import io.xdag.core.Block;
import io.xdag.chain.ext.Classified;

/** Per-kind L1 semantics for BOND / ANCHOR / CHALLENGE / CLAIM. SP0a ships no implementations; SP2/SP3 register theirs. */
public interface ChainKindHandler {

    void onApplied(Block block, Classified classified, ApplyContext ctx);

    void onUnapplied(Block block, Classified classified);
}
```

`src/main/java/io/xdag/chain/l1/ApplyContext.java`：

```java
package io.xdag.chain.l1;

import org.apache.tuweni.bytes.Bytes32;

/** Lives from onSetMainBegin to onSetMainEnd of one confirmed main block. */
public final class ApplyContext {

    private final long height;
    private final Bytes32 mainBlockHash;

    public ApplyContext(long height, Bytes32 mainBlockHash) {
        this.height = height;
        this.mainBlockHash = mainBlockHash;
    }

    public long height() {
        return height;
    }

    public Bytes32 mainBlockHash() {
        return mainBlockHash;
    }
}
```

- [ ] **Step 4: 实现处理器**

`src/main/java/io/xdag/chain/l1/ChainL1Processor.java`：

```java
package io.xdag.chain.l1;

import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.DeployExt;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ext.ExtResult;
import io.xdag.chain.ext.ChainBlockClassifier;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * L1 semantics of chain blocks, evaluated in applyBlock DFS order after value settlement.
 * Only active while a context exists (main height >= activation). One CHAIN_L1 batch per block.
 */
@Slf4j
public final class ChainL1Processor implements ChainL1Hooks {

    private final ChainL1Store store;
    private final ChainSpec spec;
    private final ChunkChain.RawBlockLookup lookup;
    private final Map<ExtKind, ChainKindHandler> handlers = new EnumMap<>(ExtKind.class);
    private ApplyContext ctx;

    public ChainL1Processor(ChainL1Store store, ChainSpec spec, ChunkChain.RawBlockLookup lookup) {
        this.store = store;
        this.spec = spec;
        this.lookup = lookup;
    }

    public void registerHandler(ExtKind kind, ChainKindHandler handler) {
        handlers.put(kind, handler);
    }

    /** Current apply context, null outside an activated setMain. Exposed for tests. */
    public ApplyContext context() {
        return ctx;
    }

    @Override
    public void onSetMainBegin(long height, Block mainBlock) {
        ctx = height >= spec.getChainActivationHeight() ? new ApplyContext(height, mainBlock.getHash()) : null;
    }

    @Override
    public void onSetMainEnd(long height, Block mainBlock) {
        ctx = null;
    }

    @Override
    public void onUnsetMain(long height, Block mainBlock) {
        ctx = null;
    }

    @Override
    public void onBlockApplied(Block block) {
        if (ctx == null) {
            return;
        }
        Classified c = ChainBlockClassifier.classify(block);
        List<Bytes> vaults = vaultOutputs(block);
        if (c.kind() == null && vaults.isEmpty()) {
            return;
        }
        Bytes32 blockHash = block.getHash();
        ChainL1Batch batch = new ChainL1Batch();
        List<InputRef> refs = new ArrayList<>();
        Map<Bytes, Long> counts = new HashMap<>();
        boolean vaultConsumed = false;

        if (c.kind() == ExtKind.DEPLOY && c.isOk()) {
            DeployExt d = c.as(DeployExt.class);
            if (d.newChain()) {
                applyDeploy(block, d, ChainIds.chainIdOf(blockHash), true, batch, refs, counts);
            } else if (vaults.size() == 1 && vaults.get(0).equals(d.chainId())) {
                vaultConsumed = true;
                applyDeploy(block, d, d.chainId(), false, batch, refs, counts);
            }
        } else if (c.kind() == ExtKind.CALL && c.isOk()) {
            CallExt call = c.as(CallExt.class);
            if (vaults.size() == 1) {
                ContractRecord target = store.getContract(call.contract());
                if (target != null && target.chainId().equals(vaults.get(0))) {
                    vaultConsumed = true;
                    int chunks = chainCount(call.argsChainHead());
                    InputStatus status = feeCovers(block, chunks) ? InputStatus.OK : InputStatus.INVALID_FEE;
                    record(batch, refs, counts, vaults.get(0), blockHash, ExtKind.CALL, status, call.contract());
                }
            }
        } else if (c.kind() != null && c.isOk()) {
            ChainKindHandler handler = handlers.get(c.kind());
            if (handler != null) {
                handler.onApplied(block, c, ctx);
            }
        }

        for (int i = 0; i < vaults.size(); i++) {
            if (i == 0 && vaultConsumed) {
                continue;
            }
            record(batch, refs, counts, vaults.get(i), blockHash, null, InputStatus.INVALID_FORMAT, ChainIds.ZERO_ADDRESS);
        }
        if (refs.isEmpty()) {
            return;
        }
        batch.putReverse(blockHash, refs);
        store.commit(batch);
        log.debug("chain inputs recorded: block={} height={} refs={}", blockHash, ctx.height(), refs.size());
    }

    @Override
    public void onBlockUnapplied(Block block) {
        Bytes32 blockHash = block.getHash();
        Classified c = ChainBlockClassifier.classify(block);
        if (c.kind() != null && c.isOk() && c.kind() != ExtKind.CALL && c.kind() != ExtKind.DEPLOY) {
            ChainKindHandler handler = handlers.get(c.kind());
            if (handler != null) {
                handler.onUnapplied(block, c);
            }
        }
        List<InputRef> refs = store.getReverse(blockHash);
        if (refs.isEmpty()) {
            return;
        }
        ChainL1Batch batch = new ChainL1Batch();
        Map<Bytes, Long> counts = new HashMap<>();
        long height = refs.get(0).height();
        for (int i = refs.size() - 1; i >= 0; i--) {
            InputRef r = refs.get(i);
            InputRecord in = store.getInput(r.chainId(), r.height(), r.index());
            batch.deleteInput(r.chainId(), r.height(), r.index());
            long remaining = counts.computeIfAbsent(r.chainId(), l -> store.getCallCount(l, r.height())) - 1;
            counts.put(r.chainId(), remaining);
            if (in != null && in.kind() == ExtKind.DEPLOY && in.status() == InputStatus.OK) {
                undoDeploy(blockHash, r.chainId(), in.contract(), batch);
            }
        }
        for (Map.Entry<Bytes, Long> e : counts.entrySet()) {
            if (e.getValue() <= 0) {
                batch.deleteCallCount(e.getKey(), height);
            } else {
                batch.putCallCount(e.getKey(), height, e.getValue());
            }
        }
        batch.deleteReverse(blockHash);
        store.commit(batch);
        log.debug("chain inputs removed: block={} height={} refs={}", blockHash, height, refs.size());
    }

    private void applyDeploy(Block block, DeployExt d, Bytes chainId, boolean newChain, ChainL1Batch batch,
                             List<InputRef> refs, Map<Bytes, Long> counts) {
        Bytes32 blockHash = block.getHash();
        Bytes contract = ChainIds.contractIdOf(blockHash);
        InputStatus status = InputStatus.OK;
        Bytes code = null;
        int chunks = 0;
        if (d.codeByChain()) {
            chunks += chainCount(d.codeChainHead());
            ExtResult<Bytes> assembled = ChunkChain.assemble(d.codeChainHead(), lookup, spec.getChainMaxChunksPerChain());
            if (!assembled.isOk()) {
                status = InputStatus.INVALID_FORMAT;
            } else if (assembled.value().size() > spec.getChainMaxWasmBytes()) {
                status = InputStatus.CODE_TOO_LARGE;
            } else if (!HashUtils.sha256(assembled.value()).equals(d.codeHash())) {
                status = InputStatus.INVALID_FORMAT;
            } else {
                code = assembled.value();
            }
        } else if (!store.hasCode(d.codeHash())) {
            status = InputStatus.INVALID_FORMAT;
        }
        if (d.argsByChain()) {
            chunks += chainCount(d.argsChainHead());
        }
        if (status == InputStatus.OK && !feeCovers(block, chunks)) {
            status = InputStatus.INVALID_FEE;
        }
        record(batch, refs, counts, chainId, blockHash, ExtKind.DEPLOY, status, contract);
        if (status != InputStatus.OK) {
            return;
        }
        if (newChain) {
            batch.putChain(chainId, new ChainRecord(ctx.height(), blockHash, d.config().gasPriceNano(),
                    d.config().deliveryDelayD(), d.config().maxCallGas(), 1L));
        } else {
            ChainRecord chain = store.getChain(chainId);
            batch.putChain(chainId, chain.withContractCount(chain.contractCount() + 1));
        }
        batch.putContract(contract, new ContractRecord(chainId, d.codeHash(), ctx.height(), blockHash));
        Bytes bytes = code != null ? code : store.getCode(d.codeHash());
        batch.putCode(d.codeHash(), store.getCodeRefCount(d.codeHash()) + 1, bytes);
    }

    private void undoDeploy(Bytes32 blockHash, Bytes chainId, Bytes contract, ChainL1Batch batch) {
        ContractRecord cr = store.getContract(contract);
        batch.deleteContract(contract);
        if (cr != null) {
            long refCount = store.getCodeRefCount(cr.codeHash());
            if (refCount <= 1) {
                batch.deleteCode(cr.codeHash());
            } else {
                batch.putCode(cr.codeHash(), refCount - 1, store.getCode(cr.codeHash()));
            }
        }
        ChainRecord chain = store.getChain(chainId);
        if (chain != null) {
            if (chain.createBlockHash().equals(blockHash)) {
                batch.deleteChain(chainId);
            } else {
                batch.putChain(chainId, chain.withContractCount(chain.contractCount() - 1));
            }
        }
    }

    private void record(ChainL1Batch batch, List<InputRef> refs, Map<Bytes, Long> counts, Bytes chainId,
                        Bytes32 blockHash, ExtKind kind, InputStatus status, Bytes contract) {
        long index = counts.computeIfAbsent(chainId, l -> store.getCallCount(l, ctx.height()));
        counts.put(chainId, index + 1);
        batch.putInput(chainId, ctx.height(), index, new InputRecord(blockHash, kind, status, contract));
        batch.putCallCount(chainId, ctx.height(), index + 1);
        refs.add(new InputRef(chainId, ctx.height(), index));
    }

    /** Address-type outputs whose address is a registered chain vault, in field order. */
    private List<Bytes> vaultOutputs(Block block) {
        List<Bytes> out = new ArrayList<>();
        for (Address o : block.getOutputs()) {
            if (o.getIsAddress()) {
                Bytes a = ChainIds.address20(o);
                if (store.hasChain(a)) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    private int chainCount(Bytes32 head) {
        return head == null ? 0 : ChunkChain.countLenient(head, lookup, spec.getChainMaxChunksPerChain());
    }

    /** Consensus re-check of the chunk fee rule: header fee field >= chunkFee x chunks. */
    private boolean feeCovers(Block block, int chunks) {
        if (chunks == 0) {
            return true;
        }
        return headerFee(block).compareTo(spec.getChainChunkFee().multiply(chunks)) >= 0;
    }

    /**
     * The fee field of the block header. BlockInfo.fee is overwritten with collected fees during apply,
     * so the value is read from the raw 512 bytes.
     */
    static XAmount headerFee(Block block) {
        XdagBlock raw = block.getXdagBlock();
        if (raw == null) {
            return XAmount.ZERO;
        }
        Bytes32 header = Bytes32.wrap(raw.getField(0).getData());
        return XAmount.of(header.getLong(24, ByteOrder.LITTLE_ENDIAN), XUnit.NANO_XDAG);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1ProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 7, Failures: 0`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/chain/l1/ChainIds.java src/main/java/io/xdag/chain/l1/ChainL1Hooks.java src/main/java/io/xdag/chain/l1/ChainKindHandler.java src/main/java/io/xdag/chain/l1/ApplyContext.java src/main/java/io/xdag/chain/l1/ChainL1Processor.java src/test/java/io/xdag/chain/l1/ChainL1ProcessorTest.java
git commit -m "Add chain L1 processor with DEPLOY/CALL attribution and symmetric unapply

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 14: 接入 `BlockchainImpl` / `Kernel`，测试基座与端到端确认流

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`
- Modify: `src/main/java/io/xdag/Kernel.java`
- Create: `src/test/java/io/xdag/chain/l1/ChainL1TestBase.java`
- Test: `src/test/java/io/xdag/chain/l1/ChainL1HooksIntegrationTest.java`

- [ ] **Step 1: 写测试基座**

`src/test/java/io/xdag/chain/l1/ChainL1TestBase.java`：

```java
package io.xdag.chain.l1;

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_COINBASE;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.BlockBuilder;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.XdagTime;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

/**
 * Real BlockchainImpl + RocksDB fixture for chain hook tests. Main blocks are "mined" by searching a nonce whose
 * raw-hash difficulty lies in [2^46, 2^47), so chain weight grows predictably and chunk blocks (difficulty ~2^33)
 * can never hijack the top (see plan §0.5).
 */
public abstract class ChainL1TestBase {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    protected static final XAmount ONE_XDAG = XAmount.of(1, XUnit.XDAG);
    protected static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    protected static final ChainConfigExt CFG = new ChainConfigExt(1L, 32L, 10_000_000L);
    private static final BigInteger DIFF_LO = BigInteger.ONE.shiftLeft(46);
    private static final BigInteger DIFF_HI = BigInteger.ONE.shiftLeft(47);

    protected Config config = new DevnetConfig();
    protected Wallet wallet;
    protected Kernel kernel;
    protected DatabaseFactory dbFactory;
    protected AddressStore addressStore;
    protected ChainL1Store chainStore;
    protected MockBlockchain blockchain;
    protected final ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    protected long generateTime = 1600616700000L;
    protected Bytes32 topRef;
    private long nonce = 1;
    /** Mixed into every mined nonce; bumped by rewindTo() so a competing branch never reproduces an existing block byte for byte. */
    private long forkSalt = 0;

    public static class MockBlockchain extends BlockchainImpl {
        public MockBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public void startCheckMain(long period) {
        }

        @Override
        public void addOurBlock(int keyIndex, Block block) {
        }

        public void checkMain() {
            checkNewMain();
        }
    }

    @Before
    public void setUpChain() throws Exception {
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        wallet = new Wallet(config);
        wallet.unlock("password");
        wallet.setAccounts(Collections.singletonList(poolKey));
        wallet.flush();

        kernel = new Kernel(config, poolKey);
        dbFactory = new RocksdbFactory(config);
        BlockStore blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TXHISTORY));
        blockStore.reset();
        OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
        orphanBlockStore.reset();
        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.reset();
        chainStore = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
        chainStore.start();
        chainStore.reset();

        kernel.setBlockStore(blockStore);
        kernel.setOrphanBlockStore(orphanBlockStore);
        kernel.setAddressStore(addressStore);
        kernel.setTxHistoryStore(Mockito.mock(TransactionHistoryStore.class));
        kernel.setWallet(wallet);
        kernel.setChainL1Store(chainStore);

        blockchain = new MockBlockchain(kernel);
        blockchain.setChainHooks(new ChainL1Processor(chainStore, config.getChainSpec(),
                hash -> blockchain.getBlockByHash(hash, true)));

        Block addressBlock = BlockBuilder.generateAddressBlock(config, poolKey, generateTime);
        addressStore.updateBalance(poolKey.toAddress().toArray(), XAmount.of(1000, XUnit.XDAG));
        assertSame(ImportResult.IMPORTED_BEST, blockchain.tryToConnect(new Block(new XdagBlock(addressBlock.toBytes()))));
        topRef = hashLow(addressBlock);
    }

    @After
    public void tearDownChain() throws IOException {
        if (wallet != null) {
            try {
                wallet.delete();
            } catch (NoSuchFileException e) {
                // already gone
            }
        }
        if (dbFactory != null) {
            dbFactory.close();
        }
    }

    protected UInt64 nextNonce() {
        return UInt64.valueOf(nonce++);
    }

    protected static Bytes32 hashLow(Block b) {
        return Bytes32.wrap(b.getHashLow().toArray());
    }

    /** Timestamp for a transaction or chunk block: a few seconds into the epoch the next mined main block closes. */
    protected long txTime() {
        return XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime + 64000L)) - 60000L;
    }

    protected ChainBlockBuilder.Built deployNewChain(Bytes wasm, Bytes initArgs) {
        int chunks = ChainBlockBuilder.chunksFor(wasm.size());
        XAmount fee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), chunks);
        return ChainBlockBuilder.deployNewChain(config, txTime(), poolKey, nextNonce(), fee, wasm, CFG, initArgs, 1000L).value();
    }

    protected ChainBlockBuilder.Built call(Bytes chainId, Bytes contract, Bytes args, XAmount headerFee) {
        return ChainBlockBuilder.call(config, txTime(), poolKey, nextNonce(), chainId, contract, 1, 100L, ONE_XDAG, headerFee, args).value();
    }

    /** Imports chunks tail-first, then the paying block. */
    protected void importBuilt(ChainBlockBuilder.Built built) {
        for (int i = built.chunks().size() - 1; i >= 0; i--) {
            assertImported(built.chunks().get(i));
        }
        assertImported(built.block());
    }

    protected void assertImported(Block b) {
        ImportResult r = blockchain.tryToConnect(b);
        assertTrue("import failed: " + r + " " + r.getErrorInfo(),
                r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
        assertArrayEquals("a non-main block hijacked the chain top; change the payload seed (plan §0.5)",
                topRef.toArray(), blockchain.getXdagTopStatus().getTop());
    }

    protected Block mineMain(List<Bytes32> extraRefs) {
        return mineMain(extraRefs, true);
    }

    /**
     * Fake PoW: a main block linking topRef, the coinbase and extraRefs, with nonce searched until the raw-hash
     * difficulty is in [2^46, 2^47). expectBest=false is for competing branches that have not overtaken yet.
     */
    protected Block mineMain(List<Bytes32> extraRefs, boolean expectBest) {
        generateTime += 64000L;
        List<Address> pending = new ArrayList<>();
        pending.add(new Address(topRef, XDAG_FIELD_OUT, false));
        pending.add(new Address(BasicUtils.keyPair2Hash(poolKey), XDAG_FIELD_COINBASE, true));
        for (Bytes32 r : extraRefs) {
            pending.add(new Address(r, XDAG_FIELD_OUT, false));
        }
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
        Block template = new Block(config, xdagTime, null, pending, true, null, null, -1, XAmount.ZERO, null);
        template.signOut(poolKey);
        for (long n = 1; ; n++) {
            byte[] nonceBytes = new byte[32];
            ExtCodec.putU64(nonceBytes, 0, n);
            ExtCodec.putU64(nonceBytes, 8, generateTime);
            ExtCodec.putU64(nonceBytes, 16, forkSalt);
            template.setNonce(Bytes32.wrap(nonceBytes));
            Block candidate = new Block(new XdagBlock(template.toBytes()));
            BigInteger diff = blockchain.calculateCurrentBlockDiff(candidate);
            if (diff.compareTo(DIFF_LO) >= 0 && diff.compareTo(DIFF_HI) < 0) {
                ImportResult r = blockchain.tryToConnect(candidate);
                if (expectBest) {
                    assertSame("main block must extend the best chain: " + r.getErrorInfo(), ImportResult.IMPORTED_BEST, r);
                } else {
                    assertTrue("fork block rejected: " + r.getErrorInfo(),
                            r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST);
                }
                topRef = hashLow(candidate);
                blockchain.checkMain();
                return candidate;
            }
        }
    }

    /** Points the mining cursor at an earlier main block so the next mineMain() calls build a competing branch. */
    protected void rewindTo(Block main, long timeWhenMined) {
        topRef = hashLow(main);
        generateTime = timeWhenMined;
        forkSalt++;
    }

    /** Mines empty main blocks until the block has been applied (settled by a confirmed main block). */
    protected void confirm(Block block) {
        for (int i = 0; i < 6; i++) {
            if ((blockchain.getBlockByHash(block.getHashLow(), false).getInfo().getFlags() & BI_APPLIED) != 0) {
                return;
            }
            mineMain(List.of());
        }
        fail("block not applied after 6 main blocks: " + block.getHashLow());
    }

    protected long heightOf(Block main) {
        return blockchain.getBlockByHash(main.getHashLow(), false).getInfo().getHeight();
    }

    protected XAmount balanceOf(Bytes address20) {
        return addressStore.getBalanceByAddress(address20.toArray());
    }
}
```

- [ ] **Step 2: 写失败的端到端测试**

`src/test/java/io/xdag/chain/l1/ChainL1HooksIntegrationTest.java`：

```java
package io.xdag.chain.l1;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class ChainL1HooksIntegrationTest extends ChainL1TestBase {

    @Test
    public void deployAndCallsAreRecordedInDfsOrderAfterConfirmation() {
        for (int i = 0; i < 10; i++) {
            mineMain(List.of());
        }
        assertTrue(blockchain.getXdagStats().nmain >= 8);

        Bytes wasm = payload(100_000, 42);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm, payload(50, 1));
        assertEquals(285, deploy.chunks().size());
        importBuilt(deploy);
        Block mDeploy = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());

        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());
        long hDeploy = heightOf(mDeploy);
        ChainRecord chain = chainStore.getChain(chainId);
        assertNotNull("chain registered", chain);
        assertEquals(hDeploy, chain.createdHeight());
        assertEquals(1L, chain.contractCount());
        assertEquals(wasm, chainStore.getCode(HashUtils.sha256(wasm)));
        assertEquals(1L, chainStore.getCodeRefCount(HashUtils.sha256(wasm)));
        assertEquals(List.of(new InputRef(chainId, hDeploy, 0)), chainStore.getReverse(deploy.block().getHash()));
        InputRecord deployInput = chainStore.getInput(chainId, hDeploy, 0);
        assertEquals(ExtKind.DEPLOY, deployInput.kind());
        assertEquals(InputStatus.OK, deployInput.status());
        assertEquals(contract, deployInput.contract());
        assertEquals(1L, chainStore.getCallCount(chainId, hDeploy));

        ChainBlockBuilder.Built ok = call(chainId, contract, payload(20, 2), FEE);
        ChainBlockBuilder.Built badContract = call(chainId, Bytes.random(20), payload(20, 3), FEE);
        ChainBlockBuilder.Built lowFee = call(chainId, contract, payload(1000, 4), XAmount.of(20, XUnit.MILLI_XDAG));
        ChainBlockBuilder.Built goodFee = call(chainId, contract, payload(1000, 5), XAmount.of(30, XUnit.MILLI_XDAG));
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address vault = new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, vault, ONE_XDAG, nextNonce()).toBytes()));
        importBuilt(ok);
        importBuilt(badContract);
        importBuilt(lowFee);
        importBuilt(goodFee);
        assertImported(plain);

        Block mCalls = mineMain(List.of(hashLow(ok.block()), hashLow(badContract.block()), hashLow(lowFee.block()),
                hashLow(goodFee.block()), hashLow(plain)));
        confirm(plain);
        long h = heightOf(mCalls);

        assertEquals(5L, chainStore.getCallCount(chainId, h));
        assertEquals(InputStatus.OK, chainStore.getInput(chainId, h, 0).status());
        assertEquals(ok.block().getHash(), chainStore.getInput(chainId, h, 0).blockHash());
        assertEquals(contract, chainStore.getInput(chainId, h, 0).contract());
        assertEquals(InputStatus.INVALID_FORMAT, chainStore.getInput(chainId, h, 1).status());
        assertEquals(InputStatus.INVALID_FEE, chainStore.getInput(chainId, h, 2).status());
        assertEquals(InputStatus.OK, chainStore.getInput(chainId, h, 3).status());
        assertEquals(InputStatus.INVALID_FORMAT, chainStore.getInput(chainId, h, 4).status());
        assertNull(chainStore.getInput(chainId, h, 5));

        // value settled by the unchanged L1 rules: five deposits of 1 XDAG minus their fee shares
        assertTrue(balanceOf(chainId).greaterThan(XAmount.of(4, XUnit.XDAG)));
        assertEquals(UInt64.valueOf(6), addressStore.getExecutedNonceNum(poolKey.toAddress().toArray()));
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1HooksIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: method setChainHooks` / `setChainL1Store`。

- [ ] **Step 4: 改 `BlockchainImpl`**

(a) import：`import io.xdag.chain.l1.ChainL1Hooks;`

(b) 字段区（`private byte[] preSeed;` 之后）新增：

```java
    // Chain contracts (SP0a): hooks invoked from setMain/applyBlock; NOOP until Kernel wires the processor
    private volatile ChainL1Hooks chainHooks = ChainL1Hooks.NOOP;

    public void setChainHooks(ChainL1Hooks hooks) {
        this.chainHooks = hooks == null ? ChainL1Hooks.NOOP : hooks;
    }
```

(c) `applyBlock`：两处。叶子早返回分支改为

```java
        if (links == null || links.isEmpty()) {
            updateBlockFlag(block, BI_APPLIED, true);
            chainHooks.onBlockApplied(block);
            return XAmount.ZERO;
        }
```

结算成功路径 `updateBlockFlag(block, BI_APPLIED, true);` 紧随其后加一行：

```java
        updateBlockFlag(block, BI_APPLIED, true);
        chainHooks.onBlockApplied(block);
```

(d) `unApplyBlock`：在 `if ((block.getInfo().flags & BI_APPLIED) != 0) {` 分支内、`updateBlockFlag(block, BI_APPLIED, false);` 之前加：

```java
            chainHooks.onBlockUnapplied(block);
            updateBlockFlag(block, BI_APPLIED, false);
```

(e) `setMain`：

```java
            updateBlockFlag(block, BI_MAIN, true);
            chainHooks.onSetMainBegin(mainNumber, block);
            ...
            XAmount mainBlockFee = applyBlock(true, block);
            if (mainBlockFee.compareTo(XAmount.ZERO) < 0) {
                chainHooks.onSetMainEnd(mainNumber, block);
                return;
            } else {
            ...
            if (randomx != null) {
                randomx.randomXSetForkTime(block);
            }
            chainHooks.onSetMainEnd(mainNumber, block);
        }
```

(f) `unSetMain`：`log.debug("UnSet main,...")` 之后加：

```java
            chainHooks.onUnsetMain(block.getInfo().getHeight(), block);
```

- [ ] **Step 5: 改 `Kernel`**

import：`io.xdag.core.BlockchainImpl`、`io.xdag.chain.l1.ChainL1Processor`、`io.xdag.chain.l1.ChainL1Store`。字段区新增 `protected ChainL1Store chainL1Store;`（类级 `@Getter @Setter` 生成访问器）。

在 `orphanBlockStore.start();` 之后追加：

```java
        chainL1Store = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
        chainL1Store.start();
```

把 `blockchain = new BlockchainImpl(this);` 改为：

```java
        BlockchainImpl chain = new BlockchainImpl(this);
        chain.setChainHooks(new ChainL1Processor(chainL1Store, config.getChainSpec(), hash -> chain.getBlockByHash(hash, true)));
        blockchain = chain;
```

`Kernel.stop()` 已遍历 `DatabaseName.values()` 关闭所有实例，`CHAIN_L1` 随之关闭，不要再调 `chainL1Store.stop()`（避免双重 close）。

- [ ] **Step 6: 运行端到端测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1HooksIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 1, Failures: 0`（约 10–30 s：285 片导入 + 若干次 nonce 搜索）。若 `assertImported` 报 "hijacked the chain top"，改 `payload(100_000, 42)` 的 seed 并记录在提交信息里。

- [ ] **Step 7: 跑核心回归**

Run: `mvn -q -Dtest='io.xdag.core.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（钩子默认 NOOP，激活前无行为改变）。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/io/xdag/core/BlockchainImpl.java src/main/java/io/xdag/Kernel.java src/test/java/io/xdag/chain/l1/ChainL1TestBase.java src/test/java/io/xdag/chain/l1/ChainL1HooksIntegrationTest.java
git commit -m "Wire chain L1 hooks into setMain/applyBlock and the kernel

Five hook call sites in BlockchainImpl (setMain begin/end, block applied,
block unapplied, unsetMain), CHAIN_L1 store construction in Kernel, and an
end-to-end fixture that confirms DEPLOY and CALL blocks through real main
block confirmation with fake PoW of bounded difficulty.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 15: Reorg 对称性测试

**Files:**
- Test: `src/test/java/io/xdag/chain/l1/ChainL1UnwindTest.java`

- [ ] **Step 1: 写测试**

```java
package io.xdag.chain.l1;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Block;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.chain.ext.ChainBlockBuilder;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

public class ChainL1UnwindTest extends ChainL1TestBase {

    @Test
    public void reorgRemovesChainStateAndReapplyRestoresIt() {
        Block forkPoint = null;
        long forkTime = 0;
        for (int i = 0; i < 12; i++) {
            Block m = mineMain(List.of());
            if (i == 7) {
                forkPoint = m;
                forkTime = generateTime;
            }
        }

        Bytes wasm = payload(5_000, 21);
        ChainBlockBuilder.Built deploy = deployNewChain(wasm, payload(10, 22));
        importBuilt(deploy);
        Block mDeploy = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        Bytes contract = ChainIds.contractIdOf(deploy.block().getHash());

        ChainBlockBuilder.Built call = call(chainId, contract, payload(20, 23), FEE);
        importBuilt(call);
        Block mCall = mineMain(List.of(hashLow(call.block())));
        confirm(call.block());

        assertTrue(chainStore.hasChain(chainId));
        assertEquals(1L, chainStore.getCallCount(chainId, heightOf(mDeploy)));
        assertEquals(1L, chainStore.getCallCount(chainId, heightOf(mCall)));
        int keysWhenApplied = chainStore.sortedKeys().size();
        assertTrue(keysWhenApplied > 1);

        // A competing branch from the fork point. Branch A above the fork point has at most 12 blocks of weight
        // < 2^47 each; 24 blocks of weight >= 2^46 each are strictly heavier, so the reorg is deterministic.
        rewindTo(forkPoint, forkTime);
        Block last = null;
        for (int i = 0; i < 24; i++) {
            last = mineMain(List.of(), false);
        }
        assertNotNull(last);
        assertArrayEquals("fork branch did not overtake", hashLow(last).toArray(), blockchain.getXdagTopStatus().getTop());

        // unwind of the confirmed main blocks removed every chain record symmetrically
        assertEquals(1, chainStore.sortedKeys().size());
        assertFalse(chainStore.hasChain(chainId));
        assertFalse(chainStore.hasCode(HashUtils.sha256(wasm)));
        assertTrue(chainStore.getReverse(deploy.block().getHash()).isEmpty());
        assertTrue(chainStore.getReverse(call.block().getHash()).isEmpty());

        // the same blocks re-linked on the new branch produce equivalent records at their new heights
        Block mDeploy2 = mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Block mCall2 = mineMain(List.of(hashLow(call.block())));
        confirm(call.block());
        assertTrue(chainStore.hasChain(chainId));
        assertEquals(heightOf(mDeploy2), chainStore.getChain(chainId).createdHeight());
        assertEquals(1L, chainStore.getCodeRefCount(HashUtils.sha256(wasm)));
        assertEquals(wasm, chainStore.getCode(HashUtils.sha256(wasm)));
        assertEquals(InputStatus.OK, chainStore.getInput(chainId, heightOf(mCall2), 0).status());
        assertEquals(contract, chainStore.getInput(chainId, heightOf(mCall2), 0).contract());
        assertEquals(keysWhenApplied, chainStore.sortedKeys().size());
    }
}
```

- [ ] **Step 2: 运行**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1UnwindTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 1, Failures: 0`。若失败于 "fork branch did not overtake"，把 24 提高到 32（分叉块权重下限 2^46 与 A 分支上限 2^47 之比决定所需数量）。若失败于 `sortedKeys().size()` 不为 1，说明某个反写缺失：对照 `ChainL1Processor.onBlockUnapplied` 与 `undoDeploy` 逐项核对。

- [ ] **Step 3: 提交**

```bash
git add src/test/java/io/xdag/chain/l1/ChainL1UnwindTest.java
git commit -m "Test chain L1 state symmetry across a main chain reorg

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 16: 激活门控与旧节点等价性测试

**Files:**
- Test: `src/test/java/io/xdag/chain/l1/ChainActivationGateTest.java`

- [ ] **Step 1: 写测试**

```java
package io.xdag.chain.l1;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.xdag.BlockBuilder;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.utils.BytesUtils;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

public class ChainActivationGateTest extends ChainL1TestBase {

    @Test
    public void belowActivationNothingIsRecordedAndValueSettlesLikeAPlainTransfer() {
        config.getChainSpec().setChainActivationHeight(Long.MAX_VALUE);
        for (int i = 0; i < 10; i++) {
            mineMain(List.of());
        }

        ChainBlockBuilder.Built deploy = deployNewChain(payload(2_000, 31), payload(10, 32));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertEquals(1, chainStore.sortedKeys().size());
        assertFalse(chainStore.hasChain(chainId));

        // a CALL-shaped block: 1 XDAG to the vault address, header fee 0.1 -> recipient gets 1 - (0.1 + MIN_GAS) = 0.8
        ChainBlockBuilder.Built callShaped = call(chainId, Bytes.random(20), payload(20, 33), FEE);
        importBuilt(callShaped);
        mineMain(List.of(hashLow(callShaped.block())));
        confirm(callShaped.block());
        assertEquals(XAmount.of(800, XUnit.MILLI_XDAG), balanceOf(chainId));

        // a plain transfer of the same shape moves exactly the same amount
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, to, ONE_XDAG, nextNonce()).toBytes()));
        assertImported(plain);
        mineMain(List.of(hashLow(plain)));
        confirm(plain);
        assertEquals(XAmount.of(1600, XUnit.MILLI_XDAG), balanceOf(chainId));

        assertEquals(1, chainStore.sortedKeys().size());
        assertEquals(UInt64.valueOf(3), addressStore.getExecutedNonceNum(poolKey.toAddress().toArray()));
    }
}
```

- [ ] **Step 2: 运行**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainActivationGateTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 1, Failures: 0`

- [ ] **Step 3: 提交**

```bash
git add src/test/java/io/xdag/chain/l1/ChainActivationGateTest.java
git commit -m "Test activation gating and legacy-equivalent settlement of chain blocks

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 17: 快照门控 `ChainL1SnapshotGate`、CLI 导出与 `initSnapshotJ` 接入

**Files:**
- Create: `src/main/java/io/xdag/chain/l1/ChainL1SnapshotGate.java`
- Modify: `src/main/java/io/xdag/cli/XdagCli.java`
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`（`initSnapshotJ` 末尾）
- Test: `src/test/java/io/xdag/chain/l1/ChainL1SnapshotTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.xdag.chain.l1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.cli.XdagCli;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChainL1SnapshotTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private static final Bytes CHAIN = Bytes.random(20);
    private static final Bytes32 CODE_HASH = Bytes32.random();

    private Config configIn(Path storeDir) {
        Config c = new DevnetConfig();
        c.getNodeSpec().setStoreDir(storeDir.toString());
        c.getNodeSpec().setStoreBackupDir(storeDir.resolve("backup").toString());
        return c;
    }

    private ChainL1Store openStore(Config config) {
        RocksdbKVSource src = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        src.setConfig(config);
        ChainL1Store store = new ChainL1Store(src);
        store.start();
        return store;
    }

    @Test
    public void exportThenImportOnAnotherNodeVerifiesTheHash() throws Exception {
        Path dirA = root.newFolder("a").toPath();
        Path dirB = root.newFolder("b").toPath();
        Config configA = configIn(dirA);
        Config configB = configIn(dirB);

        ChainL1Store a = openStore(configA);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, Bytes32.random(), 5L, 32L, 10_000_000L, 1L));
        batch.putCode(CODE_HASH, 1L, Bytes.random(700));
        a.commit(batch);
        Bytes32 expected = a.stateHash();
        ChainL1SnapshotGate.export(configA, a);
        a.stop();
        assertTrue(Files.isDirectory(dirA.resolve(ChainL1SnapshotGate.SNAPSHOT_DB_NAME)));

        // ship the snapshot directory to node B (what operators do with SNAPSHOT/BLOCKS today)
        XdagCli.copyDir(dirA.resolve(ChainL1SnapshotGate.SNAPSHOT_DB_NAME).toString(),
                dirB.resolve(ChainL1SnapshotGate.SNAPSHOT_DB_NAME).toString());

        ChainL1Store b = openStore(configB);
        ChainL1SnapshotGate.checkAndImport(configB, 100L, b);
        assertEquals(expected, b.stateHash());
        assertTrue(b.hasChain(CHAIN));
        b.stop();
    }

    @Test
    public void missingSnapshotIsFatalOnceActivated() throws Exception {
        Path dir = root.newFolder("c").toPath();
        Config config = configIn(dir);
        ChainL1Store store = openStore(config);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ChainL1SnapshotGate.checkAndImport(config, 100L, store));
            assertTrue(e.getMessage().contains("CHAIN_L1 snapshot required"));
        } finally {
            store.stop();
        }
    }

    @Test
    public void snapshotBelowActivationIsIgnored() throws Exception {
        Path dir = root.newFolder("d").toPath();
        Config config = configIn(dir);
        config.getChainSpec().setChainActivationHeight(1_000L);
        ChainL1Store store = openStore(config);
        try {
            ChainL1SnapshotGate.checkAndImport(config, 999L, store); // no directory, no exception
            assertFalse(Files.exists(dir.resolve(ChainL1SnapshotGate.SNAPSHOT_DB_NAME)));
        } finally {
            store.stop();
        }
    }

    @Test
    public void tamperedSnapshotIsRejected() throws Exception {
        Path dir = root.newFolder("e").toPath();
        Config config = configIn(dir);
        ChainL1Store a = openStore(config);
        ChainL1Batch batch = new ChainL1Batch();
        batch.putChain(CHAIN, new ChainRecord(7L, Bytes32.random(), 5L, 32L, 10_000_000L, 1L));
        a.commit(batch);
        ChainL1SnapshotGate.export(config, a);
        a.stop();

        RocksdbKVSource snap = new RocksdbKVSource(ChainL1SnapshotGate.SNAPSHOT_DB_NAME);
        snap.setConfig(config);
        snap.init();
        snap.put(ChainL1Keys.chain(CHAIN), new ChainRecord(8L, Bytes32.random(), 5L, 32L, 10_000_000L, 1L).encode());
        snap.close();

        Config fresh = configIn(root.newFolder("f").toPath());
        XdagCli.copyDir(dir.resolve(ChainL1SnapshotGate.SNAPSHOT_DB_NAME).toString(),
                Paths.get(fresh.getNodeSpec().getStoreDir(), ChainL1SnapshotGate.SNAPSHOT_DB_NAME).toString());
        ChainL1Store b = openStore(fresh);
        try {
            assertThrows(IllegalStateException.class, () -> ChainL1SnapshotGate.checkAndImport(fresh, 100L, b));
        } finally {
            b.stop();
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1SnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译错误 `cannot find symbol: class ChainL1SnapshotGate`。

- [ ] **Step 3: 实现 `ChainL1SnapshotGate`**

`src/main/java/io/xdag/chain/l1/ChainL1SnapshotGate.java`：

```java
package io.xdag.chain.l1;

import io.xdag.config.Config;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Snapshot bootstrap rule for CHAIN_L1: once the snapshot height is at or past the chain activation height, a node
 * booting from a snapshot must also import SNAPSHOT/CHAIN_L1 (hash-verified). Below activation the directory is ignored.
 */
public final class ChainL1SnapshotGate {

    public static final String SNAPSHOT_DB_NAME = "SNAPSHOT/CHAIN_L1";

    private ChainL1SnapshotGate() {
    }

    public static Path snapshotDir(Config config) {
        return Paths.get(config.getNodeSpec().getStoreDir(), SNAPSHOT_DB_NAME);
    }

    /** Writes the whole CHAIN_L1 state plus its hash into SNAPSHOT/CHAIN_L1 under the node's store directory. */
    public static void export(Config config, ChainL1Store store) {
        RocksdbKVSource target = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        target.setConfig(config);
        target.init();
        try {
            store.exportSnapshot(target);
        } finally {
            target.close();
        }
    }

    /** Called from snapshot bootstrap. Throws IllegalStateException when the snapshot is required but missing or corrupt. */
    public static void checkAndImport(Config config, long snapshotHeight, ChainL1Store store) {
        long activation = config.getChainSpec().getChainActivationHeight();
        if (snapshotHeight < activation || store == null) {
            return;
        }
        Path dir = snapshotDir(config);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("CHAIN_L1 snapshot required at height " + snapshotHeight
                    + " (chain activation height " + activation + ") but " + dir + " is missing");
        }
        RocksdbKVSource source = new RocksdbKVSource(SNAPSHOT_DB_NAME);
        source.setConfig(config);
        source.init();
        try {
            store.importSnapshot(source);
        } finally {
            source.close();
        }
    }
}
```

- [ ] **Step 4: 接入 `XdagCli.makeSnapshot` 与 `BlockchainImpl.initSnapshotJ`**

`XdagCli.makeSnapshot(boolean b)`：在 `copyDir(source.toString(),target.toString());` 之后追加（import `io.xdag.chain.l1.ChainL1Store`、`io.xdag.chain.l1.ChainL1SnapshotGate`）：

```java
        RocksdbKVSource chainSource = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        chainSource.setConfig(getConfig());
        ChainL1Store chainStore = new ChainL1Store(chainSource);
        chainStore.start();
        ChainL1SnapshotGate.export(getConfig(), chainStore);
        chainStore.stop();
        System.out.println("chain state snapshot written to " + ChainL1SnapshotGate.snapshotDir(getConfig()));
```

`BlockchainImpl.initSnapshotJ()`：在 `XAmount allBalance = ...` 之前追加（import `io.xdag.chain.l1.ChainL1SnapshotGate`）：

```java
        // Chain contracts: the CHAIN_L1 snapshot is mandatory once the snapshot height is past activation
        ChainL1SnapshotGate.checkAndImport(kernel.getConfig(), snapshotHeight, kernel.getChainL1Store());
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -Dtest=io.xdag.chain.l1.ChainL1SnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/xdag/chain/l1/ChainL1SnapshotGate.java src/main/java/io/xdag/cli/XdagCli.java src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/chain/l1/ChainL1SnapshotTest.java
git commit -m "Carry CHAIN_L1 state in snapshots with a verified state hash

Snapshot creation exports SNAPSHOT/CHAIN_L1 next to SNAPSHOT/BLOCKS and
SNAPSHOT/ADDRESS; snapshot bootstrap refuses to start past the chain
activation height without it and rejects a hash mismatch.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

---

### Task 18: 全量验证、文档与规格同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md`（§6.3 索引语义同步）
- Create: `.claude/docs/chain-l1-foundation.md`（本地文档，不进 git）

- [ ] **Step 1: 许可证头检查**

Run: `mvn -q license:check`
Expected: 退出码 0。若列出缺头文件，把 §0.3 的头加到该文件顶部后重跑。

- [ ] **Step 2: 全量回归**

Run: `mvn -q test 2>&1 | tail -40`
Expected: 末尾 `BUILD SUCCESS`；`Tests run: N, Failures: 0, Errors: 0`。先 `df -h .` 确认磁盘未满（历史上磁盘满会伪装成各种奇怪失败）。若 `ChainL1*` 之外的既有测试失败，先用 `git stash` 验证它在本分支起点是否已失败（既有不稳定测试不归本 SP 修）。

- [ ] **Step 3: 同步 SP0a 规格的索引语义**

实现中 `index` 是**每 (chain, height) 内从 0 递增的序号**（执行者可按 `0..callCount-1` 枚举），而不是主块级全局 DFS 序号。把 SP0a 规格 §6.3 中

```
- `onBlockApplied`：`if ctx == null: return`；按 §4.2 判定；每条 `record(...)` 写 0x0C（index = ctx.dfsIndex++）、0x0E、0x07 递增；DEPLOY 成功再写 0x01/0x02/0x03；全部进入 `ctx.batch`。
```

改为

```
- `onBlockApplied`：`if ctx == null: return`；按 §4.2 判定；每条 `record(...)` 写 0x0C（index = 该 (chain, height) 的当前 callCount，即每链每高度从 0 递增，执行者按 0..callCount−1 枚举）、0x0E、0x07 递增；DEPLOY 成功再写 0x01/0x02/0x03；同一块的所有写入组成一个 batch。
```

并把 §6.3 `onSetMainBegin` 一句中的 `dfsIndex = 0` 删掉（`ApplyContext` 只含 height 与主块哈希）。

```bash
git add -f docs/superpowers/specs/2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md
git commit -m "Sync SP0a spec: input index is per chain and height

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015vKPFi9TPWtHk12kmtvqX6"
```

- [ ] **Step 4: 写本地架构说明（不进 git）**

`.claude/docs/chain-l1-foundation.md`，沿用 `.claude/docs` 其他文档的格式（标题、适用版本、关注范围、分节、末尾"关键不变量与易错点"+"源码索引"表）。内容要点：EXT 字段规则与 `Block` 改动；七种 kind 的字节布局（引用总规格 §5.2，注明 CHUNK 11 字段）；分片链导入顺序与 `NO_PARENT` 的 DA 保证；`CHAIN_L1` 前缀表；五个钩子的调用点与 `ChainL1Processor` 的归属规则；激活高度；快照门；测试基座的"受控难度伪主块"技巧与 §0.5 的陷阱。

- [ ] **Step 5: 更新记忆并汇报**

在 `/Users/tron/.claude/projects/-Users-tron-IDEAProject-xdagj/memory/xdag-chain-contracts.md` 的 Status 段追加：SP0a 实施完成的提交范围、测试数、发现的既有 L1 不对称（若有）、下一步 SP1 或 SP0b。

最终汇报给用户：分支、提交列表、`mvn test` 结果、任何偏离计划之处。

---

## 3. 自审记录

**规格覆盖**：§3 Block/EXT → Task 1；§3.4–3.6 编解码与分片链 → Task 2–8；`ChainBlockBuilder` → Task 9；§4 分类与归属 → Task 7、13；§5 存储与 `batchWrite` → Task 10–11；§6 钩子与调用点 → Task 13–14；§7 配置与激活 → Task 12；§8 快照 → Task 17；§9 测试矩阵 → Task 1–17 各自测试 + Task 18 回归（属性测试以 Task 15 的"apply→unwind→re-apply"等价断言实现，随机序列版本留待 SP0b 基准基础设施就绪后补充）；§10 总规格同步已在 spec 提交中完成；§11 文件清单与 §1 一致。

**类型一致性**：`ExtResult<T>(value, error)`、`Classified(kind, value, error)`、`ChunkChain.RawBlockLookup.get(Bytes32)`、`ChainBlockBuilder.Built(block, chunks)`、`ChainL1Store` 的 `getCallCount/getInput/getReverse/getCodeRefCount/getCode/commit/exportSnapshot/importSnapshot/stateHash/sortedKeys/schemaVersion`、`ChainL1Batch` 的 `put*/delete*`、`ChainL1Hooks` 五方法、`ChainSpec` 六方法、`ChainL1SnapshotGate.export/checkAndImport/snapshotDir/SNAPSHOT_DB_NAME` 在所有任务中保持同名同签名。

**已知取舍**：(1) 测试伪主块的难度区间 `[2^46, 2^47)` 与分片块难度 `~2^33` 的分离依赖确定性内容，冲突时改 seed；(2) `TestnetConfig`/`MainnetConfig` 在测试类路径下若缺必填 conf 键，Task 12 的第二个用例按说明降级；(3) `unApplyBlock` 既有的 `allBalance` 回滚不对称不在本 SP 范围。
