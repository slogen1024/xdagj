# SP0a：扩展字段、分片链与 L1 钩子（通道合约的共识层地基）

> 日期：2026-09-13
> 分支：`dev-dag-contract`
> 上级文档：`2026-09-13-xdag-lane-contracts-design.md`（总规格，下称"总规格"）
> 状态：待评审 → 评审通过后进入 writing-plans
> 同级：SP0b（导入流水线与孤块池，节点本地性能，独立 spec）

---

## 0. 一句话

在不改变任何 L1 区块有效性规则的前提下，让 xdagj 能够**识别、承载、索引**通道合约的七类扩展块，并在主块确认时按激活高度把 DEPLOY/CALL 登记为通道输入，为 SP1（执行引擎）提供确定性的输入流与代码库。

---

## 1. 范围

### 1.1 包含

| # | 交付 | 说明 |
|---|------|------|
| 1 | `XDAG_FIELD_EXT` 与 Block 模型扩展 | 字段码 0x0F 更名；`Block` 持有 `extFields`；解析/编码/签名覆盖；`getBlockLinks()` |
| 2 | 七种 ext 记录的字节级编解码 | CALL / DEPLOY / CHUNK / ANCHOR / BOND / CHALLENGE / CLAIM，严格按总规格 §5.2（CHUNK 按本文 §3.3 修正） |
| 3 | 分片链装配与构造 | `ChunkChain.assemble` / `ChunkChainBuilder.split` |
| 4 | 扩展块分类器 | `LaneBlockClassifier`：Block → kind + record + 结构错误 |
| 5 | `LaneBlockBuilder` | 构造 CALL / DEPLOY / CHUNK 块（字段预算、内联参数上限） |
| 6 | `LANE_L1` 存储 | 新 RocksDB 实例；`KVSource.batchWrite`；前缀布局 §5 |
| 7 | L1 钩子 | `LaneL1Hooks` 接口 + `LaneL1Processor` 实现 + `BlockchainImpl` 五处调用 |
| 8 | DEPLOY / CALL 的 L1 语义 | 注册表、合约表、代码库（引用计数）、输入索引、每高度计数；对称 unapply |
| 9 | 其余 kind 的分派桩 | BOND / ANCHOR / CHALLENGE / CLAIM：调用到 handler 接口，SP0a 实现为 no-op |
| 10 | 激活高度与配置 | `LaneSpec`；三网默认；conf 覆盖；`LaneActivation` |
| 11 | 快照扩展 | `SNAPSHOT/LANE_L1` 目录 + `laneStateHash` |
| 12 | 测试 | §9 全表 |
| 13 | 总规格同步 | §10 四处 |

### 1.2 不包含

- `tryToConnect` 的任何共识改动（原则 P1）；导入期反垃圾策略、孤块池分队列、并行预验证、基准 → **SP0b**。
- WASM 字节验证、插桩、执行、SMT → **SP1**（SP0a 只存代码字节并检查长度上限）。
- BOND/UNBOND/ANCHOR/CHALLENGE/CLAIM 的语义 → **SP2/SP3**。
- RPC、钱包、CLI → **SP5**（SP0a 提供 `LaneActivation` 供其门控）。

---

## 2. 原则

| # | 原则 | 后果 |
|---|------|------|
| **P1** | EXT 不影响 L1 有效性 | `tryToConnect` 不读 EXT；旧节点与新节点对同一块的值结算逐字节相同；格式错误的扩展块仍是合法 L1 块 |
| **P2** | 通道语义只在 `setMain` 内按主块高度激活 | `mainNumber ≥ activationHeight` 才建立上下文；激活前五个钩子全部 no-op |
| **P3** | 每个写有成对的反写 | `apply → unwind → apply` 的 `LANE_L1` 逐字节等于直接 `apply`；属性测试锁死 |
| **P4** | 解码永不抛异常 | 所有 `decode` 返回 `Result`；错误进入输入记录的 `status`，不进入日志以外的控制流 |
| **P5** | 存储写入原子 | 一个块的全部 `LANE_L1` 变更用一次 `batchWrite` 提交 |

---

## 3. Block 模型与扩展字段

### 3.1 `XdagField.FieldType`

`XDAG_FIELD_RESERVE6(0x0F)` 更名为 `XDAG_FIELD_EXT(0x0F)`。值不变，`fromByte` 行为不变。

### 3.2 `Block`

- 新字段 `private List<Bytes32> extFields = new CopyOnWriteArrayList<>()`（与 inputs/outputs 同风格）。
- `parse()`：`switch` 新增 `case XDAG_FIELD_EXT -> extFields.add(Bytes32.wrap(field.getData()))`。**EXT 字段不做 `reverse()`**：块内字节即逻辑字节（Address / nonce 字段有历史遗留的反转，EXT 不继承）。
- 主构造器新增重载：`Block(config, timestamp, links, pendings, mining, keys, remark, defKeyIndex, fee, txNonce, List<Bytes32> extFields)`；旧签名委托新签名并传 `null`。类型掩码写入位置：**remark 之后、公钥之前**，每个 ext 字段 `setType(XDAG_FIELD_EXT, len++)`。
- `getEncodedBody()`：在 remark 之后、公钥之前依次 `encoder.writeField(ext.toArray())`。
- 签名覆盖无需改动：`getSubRawData` 已包含所有非 SIGN 字段；`verifiedKeys` / `checkMineAndAdd` 不变。
- 新方法 `List<Address> getBlockLinks()`：按 `outputs` 顺序返回 `!isAddress && type == XDAG_FIELD_OUT` 的引用（link[0..n]）。
- 新方法 `List<Bytes32> getExtFields()`。
- `Block.clone()` 已是浅拷贝语义，`extFields` 随之；无需改。

**哈希与旧节点**：EXT 字段参与 `calcHash` 与签名摘要（本来就参与，因为它们是块字节的一部分）；旧节点解析走 `default -> {}`，块哈希、签名验证结果一致。

### 3.3 字段预算（16 字段）

| 块 | 固定 | 可变 | 备注 |
|----|------|------|------|
| CALL | header 1 + nonce 1 + INPUT 1 + OUTPUT 1 + pubkey 1 + SIGN_OUT 2 + ext 头 1 = 8 | 内联参数 ≤ 8 字段（256B），或 1 个 link + ≤ 7 空 | 无 remark |
| DEPLOY | 同上 8 + payload[0] codeHash 1 = 9 | 新建通道再 + payload[1] 配置 1；link[0] 代码链、link[1] 参数链、内联 init 参数占余下字段 | 无 remark |
| CHUNK | header 1 + link 1 + ext 头 1 + **SIGN_OUT 2（全零）** = 5 | 载荷 **11 字段 = 352B** | 无 INPUT、无 pubkey、fee = 0 |
| ANCHOR / BOND / CHALLENGE / CLAIM | 见总规格 §5.2，SP0a 只做编解码 | | |

**CHUNK 必须带 2 个全零 `SIGN_OUT`**：`checkMineAndAdd` 对 `getOutsig()` 直接验签，null 会抛异常使 `tryToConnect` 返回 ERROR；`parse()` 把 r = s = 0 视为"矿工伪块"替换为 (1,1)，验签必然失败但不抛异常。这是既有行为，SP0a 不改它。

### 3.4 编码约定（全部 ext 记录通用）

- 多字节整数**小端**（与 header 一致）。
- 哈希 32B、地址 20B 原样。
- ext 头 byte0 = kind；byte1..31 按 kind 布局；未使用字节必须为 0，解码时非 0 → `ExtError.RESERVED_NONZERO`。
- `decode(Bytes32 header, List<Bytes32> payload, List<Address> links)` 返回 `Result<T, ExtError>`。
- `ExtError` 枚举：`NO_EXT, UNKNOWN_KIND, RESERVED_NONZERO, BAD_LENGTH, MISSING_LINK, EXTRA_LINK, INLINE_ARGS_TOO_LONG, PAYLOAD_COUNT_MISMATCH, CHUNK_SEQ_GAP, CHUNK_TOTAL_MISMATCH, CHUNK_TOO_MANY, CHUNK_TAIL_HAS_LINK, CHUNK_CYCLE, CODE_TOO_LARGE`。

### 3.5 `ExtKind` 与记录

```
enum ExtKind { CALL(1), DEPLOY(2), CHUNK(3), ANCHOR(4), BOND(5), CHALLENGE(6), CLAIM(7) }

record CallExt(byte flags, Bytes contract20, int selector, long gasLimitU32, int argsLen, Bytes inlineArgs, Address argsChainHead /*nullable*/)
record DeployExt(byte flags, Bytes laneId20 /*newLane 时忽略*/, long gasLimitU32, int argsLen, Bytes32 codeHash,
                 LaneConfigExt config /*newLane 时非空*/, Bytes inlineArgs, Address codeChainHead, Address argsChainHead)
record LaneConfigExt(long gasPriceNano, long deliveryDelayD, long maxCallGas)
record ChunkExt(long seq, long totalLen, int dataLen, Address next /*nullable*/, Bytes data)
record AnchorExt(...)  record BondExt(...)  record ChallengeExt(...)  record ClaimExt(...)   // 按总规格 §5.2，SP0a 只编解码
```

CALL/DEPLOY 的 link 角色：CALL link[0] = 参数链（仅 flags.bit0）；DEPLOY link[0] = 代码链（flags.bit1）、link[1] = 参数链（flags.bit2；若无代码链则参数链是 link[0]）。多余 link → `EXTRA_LINK`；缺失 → `MISSING_LINK`。

### 3.6 分片链

```
ChunkChain.assemble(Address head, Function<Bytes32, Block> lookupRaw, int maxChunks) -> Result<Bytes, ExtError>
  visited = {}; expectSeq = 0; total = null; out = []
  cur = head
  while cur != null:
    if visited.contains(cur) -> CHUNK_CYCLE
    b = lookupRaw(cur.hash); c = ChunkExt.decode(b) (kind 必须为 CHUNK)
    if c.seq != expectSeq -> CHUNK_SEQ_GAP
    if total == null: total = c.totalLen  else if c.totalLen != total -> CHUNK_TOTAL_MISMATCH
    out += c.data[0..dataLen]; expectSeq++
    if expectSeq > maxChunks -> CHUNK_TOO_MANY
    cur = c.next
  if out.length != total -> CHUNK_TOTAL_MISMATCH
  return out
ChunkChain.count(head, lookupRaw, maxChunks) -> 片数或错误（费用复核用，不拼接）
ChunkChainBuilder.split(Bytes payload, long baseTimestamp, ECKeyPair unusedOrNull) -> List<Block>（尾片 baseTimestamp，逐片 +1 tick，头片最晚）
```

`lookupRaw` 由 `BlockchainImpl.getBlockByHash(hash, true)` 提供；分片块在 DEPLOY/CALL 导入前必然已落盘（`NO_PARENT` 规则）。

---

## 4. 分类与归属判定

### 4.1 `LaneBlockClassifier.classify(Block) -> Classified`

```
record Classified(ExtKind kind /*NONE 表示无 EXT*/, Object record /*解码结果或 null*/, ExtError error /*null 表示成功*/)
```

- `extFields` 为空 → `NONE`。
- 取 `extFields[0]` 为 ext 头，`extFields[1..]` 为 payload，`getBlockLinks()` 为 links，分派到对应 `decode`。
- 分类器**纯函数**、不访问存储、不抛异常。

### 4.2 归属判定（在 `LaneL1Processor.onBlockApplied` 内，需要注册表）

```
vaultOutputs = [o for o in block.outputs if o.isAddress && registry.hasLane(o.address20)]
c = classify(block)
consumed = ∅

case c.kind == DEPLOY && c.flags.newLane:
    laneId = LaneIds.laneIdOf(block.hash);  contract = LaneIds.contractIdOf(block.hash)
    status = checkDeploy(c)   // 代码链装配 / 长度 / 费用
    record(laneId, DEPLOY, status, contract)
    if status == OK: registry.create(laneId, ...); contracts.put(contract, laneId, codeHash); code.incRef(codeHash, bytes)
case c.kind == DEPLOY && !c.flags.newLane:
    if vaultOutputs.size() == 1 && vaultOutputs[0] == c.laneId: consumed = {vaultOutputs[0]}; 同上但 laneId = c.laneId
    else: 落到兜底规则
case c.kind == CALL:
    if vaultOutputs.size() == 1 && contracts.laneOf(c.contract) == vaultOutputs[0]:
        consumed = {vaultOutputs[0]}; status = checkFee(block) ; record(lane, CALL, status, c.contract)
    else: 落到兜底规则
case c.kind ∈ {BOND, ANCHOR, CHALLENGE, CLAIM}:
    handlers[kind].onApplied(block, c, ctx)     // SP0a：no-op 桩
兜底：for v in vaultOutputs \ consumed: record(v, NONE, INVALID_FORMAT, ∅)
```

`LaneIds.laneIdOf(h) = sha256("xdag-lane" ‖ h)[0..20]`，`contractIdOf(h) = sha256("xdag-contract" ‖ h)[0..20]`。

`checkDeploy`：`flags.bit1` 时装配代码链，长度 > `maxWasmBytes` → `CODE_TOO_LARGE`；否则 `codeHash` 必须已在代码库，否则 `INVALID_FORMAT`；装配结果的 `sha256` 必须等于 `payload[0].codeHash`，否则 `INVALID_FORMAT`；再做 `checkFee`。
`checkFee`：`header.fee ≥ chunkFee × (该块直接 link 的所有分片链片数之和)`，否则 `INVALID_FEE`。

`InputStatus { OK, INVALID_FORMAT, INVALID_FEE, CODE_TOO_LARGE }`。所有状态都产生输入记录（value 的通道内退款由 SP1 按状态处理）。

注册表查询用 DFS 当前时刻的状态：同一高度先 DEPLOY 后 CALL 可见，顺序全网一致。向未注册地址的转账不产生记录。

---

## 5. `LANE_L1` 存储

新增 `DatabaseName.LANE_L1`；`RocksdbFactory` 按需创建；`Kernel` 在 `addressStore` 之后构造 `LaneL1Store` 并 `start()`。

### 5.1 `KVSource.batchWrite`

```java
default void batchWrite(List<Pair<K,V>> puts, List<K> deletes) { puts.forEach(put); deletes.forEach(delete); }   // 接口默认：顺序写
// RocksdbKVSource 覆盖：WriteBatch + db.write(writeOptions, batch)，持读锁
```

### 5.2 前缀布局（值定长手工编码，小端）

| 前缀 | key | value | SP0a |
|------|-----|-------|------|
| 0x00 | `0x00` | schemaVersion u32 = 1 | ✓ |
| 0x01 | laneId(20) | createdHeight u64 ‖ createBlockHash 32 ‖ gasPriceNano u64 ‖ D u32 ‖ maxCallGas u32 ‖ contractCount u32 | ✓ |
| 0x02 | contract(20) | laneId 20 ‖ codeHash 32 ‖ deployHeight u64 ‖ deployBlockHash 32 | ✓ |
| 0x03 | codeHash(32) | refCount u32 ‖ len u32 ‖ bytes | ✓（refCount 到 0 删除） |
| 0x07 | laneId(20) ‖ height u64 | callCount u32 | ✓ |
| 0x0C | laneId(20) ‖ height u64 ‖ index u32 | blockHash 32 ‖ kind u8 ‖ status u8 ‖ contract 20 | ✓ |
| 0x0E | blockHash(32) | laneId 20 ‖ height u64 ‖ index u32 | ✓ |
| 0x04 0x05 0x06 0x08 0x09 0x0A 0x0B | 保证金 / 锚定头 / 锚定索引 / 已认领 / 高度触发器 / 挑战 / 段游标 | 常量预留 | SP2/SP3 |

`LaneL1Store` API（全部同步、无缓存，除 `codeBytes` 外都是点查）：
`hasLane / getLane / putLane / deleteLane`，`getContract / putContract / deleteContract`，`codeIncRef(hash, bytes) / codeDecRef(hash) / hasCode / getCode`，`getCallCount / putCallCount`，`putInput / getInput / deleteInput`，`getReverse / putReverse / deleteReverse`，`batch(LaneL1Batch)`（收集 puts/deletes 后一次提交），`exportAll(KVSource) / importAll(KVSource) / stateHash()`。

---

## 6. L1 钩子

### 6.1 接口

```java
public interface LaneL1Hooks {
    void onSetMainBegin(long height, Block mainBlock);
    void onBlockApplied(Block block);
    void onSetMainEnd(long height, Block mainBlock);
    void onBlockUnapplied(Block block);
    void onUnsetMain(long height, Block mainBlock);
    LaneL1Hooks NOOP = new LaneL1Hooks() { /* 全空 */ };
}
```

### 6.2 调用点（`BlockchainImpl`）

| 钩子 | 位置 |
|------|------|
| `onSetMainBegin(mainNumber, block)` | `setMain`：`updateBlockFlag(block, BI_MAIN, true)` 之后、`applyBlock(true, block)` 之前 |
| `onBlockApplied(block)` | `applyBlock`：`updateBlockFlag(block, BI_APPLIED, true)` 紧随其后（无论 `flag` 真假；主块自身也会到达此处，处理器按 kind 判定，主块无 EXT 直接返回） |
| `onSetMainEnd(mainNumber, block)` | `setMain`：方法末尾；**`mainBlockFee < 0` 的提前 `return` 分支之前也必须调用** |
| `onBlockUnapplied(block)` | `unApplyBlock`：`updateBlockFlag(block, BI_APPLIED, false)` 之前（此时 `BI_APPLIED` 仍为真，处理器据此判断是否有记录） |
| `onUnsetMain(height, block)` | `unSetMain`：`unApplyBlock(block, true)` 之前调用（先关上下文语义，再逆序反写），高度用 `block.getInfo().getHeight()` |

### 6.3 `LaneL1Processor` 行为

- `onSetMainBegin`：`if height < activationHeight: ctx = null; return`；否则 `ctx = new ApplyContext(height, mainBlock.hash, dfsIndex = 0, batch = new LaneL1Batch())`。
- `onBlockApplied`：`if ctx == null: return`；按 §4.2 判定；每条 `record(...)` 写 0x0C（index = ctx.dfsIndex++）、0x0E、0x07 递增；DEPLOY 成功再写 0x01/0x02/0x03；全部进入 `ctx.batch`。**每个块结束时立即 `store.batch(ctx.batch)` 并清空**（一个块一批，而非一个主块一批：`applyBlock` 递归中后续块的注册表查询要看到前面块的写入）。
- `onSetMainEnd`：SP0a 无高度触发器；`ctx = null`。
- `onBlockUnapplied`：`if height(block.ref) < activationHeight: return`（用反向索引是否存在判断更简单：`rev = store.getReverse(block.hash)`，为空直接返回）；删除 0x0C/0x0E，`callCount--`；若记录 kind == DEPLOY 且 status == OK：`contracts.delete`、`code.decRef`、新建通道则 `deleteLane` 否则 `contractCount--`；一次 `batch`。**unApplyBlock 已对 links 逆序，处理器不再排序**。
- `onUnsetMain`：`ctx = null`（防御性）。

### 6.4 DFS 索引的确定性

`applyBlock` 的递归顺序 = link 顺序 = 全网一致；`onBlockApplied` 只在 `BI_APPLIED` 成功路径被调用，`syncTxStatus` 导致的拒绝在所有节点一致（既有共识）。因此 `(laneId, height, index)` 全网一致。

### 6.5 Reorg 备注

`unWindMain` 沿 `maxDiffLink` 脊柱 `rollTx`，脊柱上若有 CALL 块，其 OUT 引用（分片）会被扔回孤块池并被下一个主块当普通孤块再 link 一次。分片内容寻址、`BI_MAIN_REF` 已置时 `applyBlock` 跳过，无害。

---

## 7. 激活高度与配置

`config/spec/LaneSpec`（仿 `SnapshotSpec`）：

| 键 | 类型 | devnet | testnet | mainnet | conf 键 |
|----|------|--------|---------|---------|---------|
| activationHeight | long | 0 | `Long.MAX_VALUE` | `Long.MAX_VALUE` | `lane.activation.height` |
| maxChunksPerChain | int | 4096 | 4096 | 4096 | `lane.chunk.maxPerChain` |
| maxWasmBytes | int | 1,048,576 | 同 | 同 | `lane.wasm.maxBytes` |
| maxInlineArgs | int | 256 | 同 | 同 | `lane.args.maxInline` |
| chunkFee | XAmount | 0.01 XDAG | 同 | 同 | `lane.chunk.fee` |

`AbstractConfig.getSetting()` 读取 conf 覆盖；`Config` 接口暴露 `getLaneSpec()`。
`LaneActivation.isActive(long height)`，`isActiveAt(XdagStats)`（用 `nmain`）供 SP5 门控。

---

## 8. 快照扩展

- `SnapshotStoreImpl.makeSnapshot`：新增 `SNAPSHOT/LANE_L1`（`RocksdbKVSource("LANE_L1")` 于快照目录下）：`laneStore.exportAll(target)` + 写 `0xFF → laneStateHash`。
- `laneStateHash = sha256( concat over keys in lexicographic order of len(key) u32 ‖ key ‖ len(val) u32 ‖ val )`，不含 `0xFF` 自身。
- `BlockchainImpl.initSnapshotJ`：若 `snapshotHeight ≥ activationHeight`：目录缺失 → `IllegalStateException("LANE_L1 snapshot required at height …")`；存在 → `importAll` 后重算哈希与 `0xFF` 比对，不等 → 异常。若 `snapshotHeight < activationHeight`：忽略。
- 幂等闸门沿用 `isSnapshotBoot`。

---

## 9. 测试策略（TDD，JUnit 4，`BlockBuilder` / `BlockchainTest` 模式）

| 层 | 用例 |
|----|------|
| 编解码 | 七种记录 encode→decode 往返；每字段边界（u16/u32 极值、argsLen 256/257）；保留字节非 0；随机 32B decode 只返回错误不抛 |
| Block | 带 EXT 的块 `toBytes()`→`new Block(new XdagBlock(bytes))` 往返：`extFields` 顺序、类型掩码位置、hash 稳定、`signOut`+`verifiedKeys` 通过；`getBlockLinks()` 顺序；旧构造器签名行为不变 |
| 分片链 | 1 片；352B 整除；非整除；4096 片；4097 → TOO_MANY；seq 断裂；totalLen 不符；末片带 link；环（A→B→A）；`count` 与 `assemble` 一致 |
| 分类器 | 每 kind 一正例；`NONE`；EXTRA_LINK / MISSING_LINK；内联参数超长 |
| Builder | CALL 内联 256B 恰好装下、257B 拒绝；DEPLOY 新建/加入两种；字段预算超 16 返回错误 |
| 存储 | CRUD；`batchWrite` 原子（用注入异常的 KVSource 验证无半写）；codeIncRef/DecRef 到 0 删除；`exportAll/importAll/stateHash` 往返 |
| 钩子端到端 | 真实 `Kernel` + RocksDB（`TemporaryFolder`）：DEPLOY(新通道，代码 300 片) → CALL×3（含 1 笔格式错、1 笔分片费不足）→ 出主块 → 确认；断言 0x01/0x02/0x03/0x07/0x0C/0x0E 内容与 DFS 顺序；再一笔向金库的普通转账 → INVALID_FORMAT 记录 |
| 属性 | 随机 3–8 个高度的块序列，随机 unwind 深度：`apply→unwind→apply` 后 `LANE_L1` 全 KV 逐字节等于直接 apply；`AddressStore` 中涉及地址的余额与 nonce 相等（复用 `BlockchainTest` 的分叉制造方式；不比较 `AddressStore` 全 KV，避免被既有 L1 回滚的已知不对称项干扰） |
| 激活门控 | `activationHeight = MAX`：同一序列后 `LANE_L1` 为空且 `BlockInfo`/余额/nonce/fee 逐字节等于用普通转账替换 CALL 的对照序列 |
| 快照 | 导出→导入往返；篡改一个 value 后哈希不符拒绝；`snapshotHeight ≥ activation` 且目录缺失拒绝；`< activation` 忽略 |
| 回归 | 现有 50 个测试类全绿（JDK 21 + toolchains，见 build env 记忆） |

---

## 10. 总规格同步（与本文同一提交）

1. §5.2 CHUNK：13 字段/416B → **11 字段/352B**，须带 2 个全零 `SIGN_OUT`；100KB WASM ≈ 291 片。
2. §5.1 新增原则"EXT 不影响 L1 有效性"；§5.4 费用规则改为"导入期节点策略（SP0b）+ `applyBlock` 共识复核（不足 → `INVALID_FEE` 输入）"；§5.5 硬分叉点收窄为系统划账与快照。
3. §6.1 增加 0x0C/0x0E，代码库加引用计数；§6.2 钩子表改为五钩子。
4. §18 SP0 拆为 SP0a / SP0b。

---

## 11. 文件清单

**改动**：`core/XdagField.java`、`core/Block.java`、`core/BlockchainImpl.java`（五处钩子 + `initSnapshotJ`）、`db/rocksdb/{DatabaseName, KVSource, RocksdbKVSource, SnapshotStoreImpl}.java`、`config/{Config, AbstractConfig, DevnetConfig, TestnetConfig, MainnetConfig}.java`、`Kernel.java`、`src/main/resources/xdag-*.conf`。
**新增**：`lane/ext/{ExtKind, ExtError, ExtCodec, CallExt, DeployExt, LaneConfigExt, ChunkExt, AnchorExt, BondExt, ChallengeExt, ClaimExt, ChunkChain, ChunkChainBuilder, LaneBlockBuilder, LaneBlockClassifier, Classified}.java`、`lane/l1/{LaneL1Hooks, LaneL1Processor, LaneL1Store, LaneL1Batch, LaneIds, InputStatus, ApplyContext, LaneKindHandler}.java`、`lane/LaneActivation.java`、`config/spec/LaneSpec.java`；测试镜像目录。

---

## 12. 开放问题

| # | 问题 | 处理 |
|---|------|------|
| Q1 | `addAmount` 对不存在的 OUTPUT 地址是否自动建档 | 计划首个任务确认；若否，DEPLOY(新通道) 时建档并在 unwind 时删除"由本 DEPLOY 创建"的档 |
| Q2 | `Block` 主构造器参数已有 10 个 | 新增重载即可；不引入 builder 以免扩大改动面 |
| Q3 | 主块自身经过 `onBlockApplied` | 主块无 EXT → `NONE` → 若 coinbase 输出恰好是某金库（概率可忽略）会记 INVALID_FORMAT；接受 |
| Q4 | 既有 L1 回滚存在已知不对称（`unApplyBlock` 中 `allBalance` 回滚 try/catch） | 属性测试只比较通道相关地址的余额/nonce；若 SP0a 期间发现影响通道语义的 L1 不对称，单独记 issue，不在本 SP 修 |
