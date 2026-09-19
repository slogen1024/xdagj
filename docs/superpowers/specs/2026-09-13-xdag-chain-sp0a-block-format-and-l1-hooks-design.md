# SP0a：扩展字段、分片链与 L1 钩子（链合约的共识层地基）

> 日期：2026-09-13
> 分支：`dev-dag-contract`
> 上级文档：`2026-09-13-xdag-chain-contracts-design.md`（总规格，下称"总规格"）
> 状态：待评审 → 评审通过后进入 writing-plans
> 同级：SP0b（导入流水线与孤块池，节点本地性能，独立 spec）
> 术语：2026-09-17 起 lane/通道 统一改称 chain/链（应用链）；主链、分片链的用法不变。

---

## 0. 一句话

在不改变任何 L1 区块有效性规则的前提下，让 xdagj 能够**识别、承载、索引**链合约的七类扩展块，并在主块确认时按激活高度把 DEPLOY/CALL 登记为链输入，为 SP1（执行引擎）提供确定性的输入流与代码库。

---

## 1. 范围

### 1.1 包含

| # | 交付 | 说明 |
|---|------|------|
| 1 | `XDAG_FIELD_EXT` 与 Block 模型扩展 | 字段码 0x0F 更名；`Block` 持有 `extFields`；解析/编码/签名覆盖；`getBlockLinks()` |
| 2 | 七种 ext 记录的字节级编解码 | CALL / DEPLOY / CHUNK / ANCHOR / BOND / CHALLENGE / CLAIM，严格按总规格 §5.2（CHUNK 按本文 §3.3 修正） |
| 3 | 分片链装配与构造 | `ChunkChain.assemble` / `ChunkChainBuilder.split` |
| 4 | 扩展块分类器 | `ChainBlockClassifier`：Block → kind + record + 结构错误 |
| 5 | `ChainBlockBuilder` | 构造 CALL / DEPLOY / CHUNK 块（字段预算、内联参数上限） |
| 6 | `CHAIN_L1` 存储 | 新 RocksDB 实例；`KVSource.batchWrite`；前缀布局 §5 |
| 7 | L1 钩子 | `ChainL1Hooks` 接口 + `ChainL1Processor` 实现 + `BlockchainImpl` 五处调用 |
| 8 | DEPLOY / CALL 的 L1 语义 | 注册表、合约表、代码库（引用计数）、输入索引、每高度计数；对称 unapply |
| 9 | 其余 kind 的分派桩 | BOND / ANCHOR / CHALLENGE / CLAIM：调用到 handler 接口，SP0a 实现为 no-op |
| 10 | 激活高度与配置 | `ChainSpec`；三网默认；conf 覆盖；`ChainActivation` |
| 11 | 快照扩展 | `SNAPSHOT/CHAIN_L1` 目录 + `chainStateHash` |
| 12 | 测试 | §9 全表 |
| 13 | 总规格同步 | §10 四处 |

### 1.2 不包含

- `tryToConnect` 的任何共识改动（原则 P1）；导入期反垃圾策略、孤块池分队列、并行预验证、基准 → **SP0b**。
- WASM 字节验证、插桩、执行、SMT → **SP1**（SP0a 只存代码字节并检查长度上限）。
- BOND/UNBOND/ANCHOR/CHALLENGE/CLAIM 的语义 → **SP2/SP3**。
- RPC、钱包、CLI → **SP5**（SP0a 提供 `ChainActivation` 供其门控）。

---

## 2. 原则

| # | 原则 | 后果 |
|---|------|------|
| **P1** | EXT 不影响 L1 有效性 | `tryToConnect` 不读 EXT；旧节点与新节点对同一块的值结算逐字节相同；格式错误的扩展块仍是合法 L1 块 |
| **P2** | 链语义只在 `setMain` 内按主块高度激活 | `mainNumber ≥ activationHeight` 才建立上下文；激活前五个钩子全部 no-op |
| **P3** | 每个写有成对的反写 | `apply → unwind → apply` 的 `CHAIN_L1` 逐字节等于直接 `apply`；属性测试锁死 |
| **P4** | 解码永不抛异常 | 所有 `decode` 返回 `ExtResult`；错误进入输入记录的 `status`，不进入日志以外的控制流。例外是**构造期的编程错误**：记录的紧凑构造器对越界/长度不符抛 `IllegalArgumentException`，`Classified.as(Class)` 在分类失败时抛 `IllegalStateException`（调用方必须先 `isOk()`），二者都不在解码路径上 |
| **P5** | 存储写入原子 | 一个块的全部 `CHAIN_L1` 变更用一次 `batchWrite` 提交（apply 一次、unapply 一次；空批次不落盘） |
| **P6** | 钩子只吃 raw 块 | `onBlockApplied` / `onBlockUnapplied` 与分片链 `lookup` 必须拿到从 512 字节解析出来的块（`new Block(XdagBlock)` 或 `getBlockByHash(hash, true)`）。仅由 `BlockInfo` 构造的块没有 ext 字段、没有 link、没有 output，会被静默判为"什么都不是"——这是共识分歧而不是可见错误。这是**调用点前置条件**，当前 `BlockchainImpl` 的五处调用都满足 |

---

## 3. Block 模型与扩展字段

### 3.1 `XdagField.FieldType`

`XDAG_FIELD_RESERVE6(0x0F)` 更名为 `XDAG_FIELD_EXT(0x0F)`。值不变，`fromByte` 行为不变。

> **码点冲突（合入 `develop` 之前必须解决）**：`dev-evm` 分支把同一个 `0x0F` 用作 `XDAG_FIELD_EVM_TX_REF`（原始 32B EVM 交易哈希，字段写在 remark **之前**；本分支的 EXT 字段写在 remark **之后**）。两条分支都还没合进 `develop`，先合的那条占住 0x0F，另一条必须改码点或改语义。按总规格 D3 的决定 dev-evm 归档，但这件事必须在**任一分支合入 `develop` 之前**显式定夺，不能靠"先到先得"。

### 3.2 `Block`

- 新字段 `private List<Bytes32> extFields = new CopyOnWriteArrayList<>()`（与 inputs/outputs 同风格）。**只有 raw 块才有内容**：由 `BlockInfo` 构造的块（`getBlockByHash(hash, false)`）这里恒为空，`getBlockLinks()` 同理（见原则 P6）。
- `parse()`：`switch` 新增 `case XDAG_FIELD_EXT -> extFields.add(Bytes32.wrap(field.getData()))`。**EXT 字段不做 `reverse()`**：块内字节即逻辑字节（Address / nonce 字段有历史遗留的反转，EXT 不继承）。
- 主构造器新增重载：`Block(config, timestamp, links, pendings, mining, keys, remark, defKeyIndex, fee, txNonce, List<Bytes32> extFields)`；旧签名委托新签名并传 `null`。类型掩码写入位置：**remark 之后、公钥之前**，每个 ext 字段 `setType(XDAG_FIELD_EXT, len++)`。
- `getEncodedBody()`：在 remark 之后、公钥之前依次 `encoder.writeField(ext.toArray())`。
- 签名覆盖无需改动：`getSubRawData` 已包含所有非 SIGN 字段；`verifiedKeys` / `checkMineAndAdd` 不变。
- 新方法 `List<Address> getBlockLinks()`：按 `outputs` 顺序返回 `!isAddress && type == XDAG_FIELD_OUT` 的引用（link[0..n]）。不校验 link 上的 amount——OUT link 的金额规则仍然只在 `tryToConnect` 里。
- 新方法 `List<Bytes32> getExtFields()`。
- `setType(type, n)` 增加字段预算断言：`n >= XdagBlock.XDAG_BLOCK_FIELDS` 抛 `IllegalArgumentException("block field budget exceeded: field index n")`。原先超出 16 个字段会把类型 nibble 静默移出 64 位掩码（悄悄覆盖低位字段的类型），现在在构造期直接失败。
- `Block.clone()` 已是浅拷贝语义，`extFields` 随之；无需改。

**哈希与旧节点**：EXT 字段参与 `calcHash` 与签名摘要（本来就参与，因为它们是块字节的一部分）；旧节点解析走 `default -> {}`，块哈希、签名验证结果一致。

### 3.3 字段预算（16 字段）

| 块 | 固定 | 可变 | 备注 |
|----|------|------|------|
| CALL | header 1 + nonce 1 + INPUT 1 + OUTPUT 1 + pubkey 1 + SIGN_OUT 2 + ext 头 1 = 8 | 内联参数 ≤ 8 字段（256B），或 1 个 link + ≤ 7 空 | 无 remark |
| DEPLOY | 同上 8 + payload[0] codeHash 1 = 9 | 新建链再 + payload[1] 配置 1；link[0] 代码链、link[1] 参数链、内联 init 参数占余下字段 | 无 remark |
| CHUNK | header 1 + link 1 + ext 头 1 + **SIGN_OUT 2（全零）** = 5 | 载荷 **11 字段 = 352B** | 无 INPUT、无 pubkey、fee = 0 |
| ANCHOR / BOND / CHALLENGE / CLAIM | 见总规格 §5.2，SP0a 只做编解码 | | |

**CHUNK 必须带 2 个全零 `SIGN_OUT`**：`checkMineAndAdd` 对 `getOutsig()` 直接验签，null 会抛异常使 `tryToConnect` 返回 ERROR；`parse()` 把 r = s = 0 视为"矿工伪块"替换为 (1,1)，验签必然失败但不抛异常。这是既有行为，SP0a 不改它。

### 3.4 编码约定（全部 ext 记录通用）

- 多字节整数**小端**（与 header 一致）。
- 哈希 32B、地址 20B 原样。
- ext 头 byte0 = kind；byte1..31 按 kind 布局；未使用字节必须为 0，解码时非 0 → `ExtError.RESERVED_NONZERO`。
- `decode(Bytes32 header, List<Bytes32> payload, List<Address> links)` 返回 `Result<T, ExtError>`。
- `ExtError` 枚举（**只追加、不重排**）：`NO_EXT, UNKNOWN_KIND, RESERVED_NONZERO, BAD_LENGTH, MISSING_LINK, EXTRA_LINK, INLINE_ARGS_TOO_LONG, PAYLOAD_COUNT_MISMATCH, CHUNK_SEQ_GAP, CHUNK_TOTAL_MISMATCH, CHUNK_TOO_MANY, CHUNK_TAIL_HAS_LINK, CHUNK_CYCLE, NOT_A_CHUNK, CODE_TOO_LARGE, CODE_HASH_MISMATCH, CODE_UNKNOWN, CHUNK_TOO_OLD`。其中 `NOT_A_CHUNK` = 分片链走到一个"存在但不是 CHUNK"的块；`CODE_TOO_LARGE` / `CODE_HASH_MISMATCH` / `CODE_UNKNOWN` 三个都为 SP1 代码库预留，SP0a 的编解码器不产生（§4.2 的 `CODE_TOO_LARGE` 是 `InputStatus` 的同名常量，不是 `ExtError`）；`CHUNK_TOO_OLD` 是 §3.6 的年龄规则，追加在最后一位。
- `ExtKind.fromCode(int)` 按**无符号**比较 kind 字节（`(k.code & 0xff) == code`）；未分配的码返回 `null` → `UNKNOWN_KIND`，不抛异常。

### 3.5 `ExtKind` 与记录

```
enum ExtKind { CALL(1), DEPLOY(2), CHUNK(3), ANCHOR(4), BOND(5), CHALLENGE(6), CLAIM(7) }

record CallExt(int flags, Bytes contract /*20B*/, int selector, long gasLimit /*u32*/, int argsLen,
               Bytes inlineArgs, Bytes32 argsChainHead /*nullable*/)
record DeployExt(int flags, Bytes chainId /*20B；newChain 时线上必须全零*/, long gasLimit /*u32*/, int argsLen,
                 Bytes32 codeHash, ChainConfigExt config /*newChain 时非空，否则必须为 null*/, Bytes inlineArgs,
                 Bytes32 codeChainHead /*nullable*/, Bytes32 argsChainHead /*nullable*/)
record ChainConfigExt(long gasPriceNano, long deliveryDelayD, long maxCallGas)
record ChunkExt(long seq, long totalLen, int dataLen, Bytes32 next /*nullable = 尾片*/, Bytes data)
record AnchorExt(Bytes chainId, long seq, Bytes32 stateRoot, Bytes32 outboxMapRoot, long prevSeq, long inputCount,
                 long segmentCount, Bytes32 prevAnchor, Bytes32 mainBlock, Bytes32 commitmentHead)
record BondExt(boolean unbond, Bytes chainId, long amount)
record ChallengeExt(long inputIndex, long deposit, Bytes32 anchor, Bytes32 witnessHead)
record ClaimExt(boolean toChainVault, Bytes srcChain, long seq, long index, long amount, Bytes recipient,
                Bytes32 anchor, Bytes32 proofHead)
```

记录里的链接一律是 `Bytes32`（hashLow）而不是 `Address`：`decode` 收 `List<Address>`，但只取其 `getAddress()`。

**紧凑构造器只保证"能按线格式往返"**（非空、20B/32B 长度、u16/u32 范围、flag 与可空字段一致），**不做协议级范围检查**（例如 `0 < dataLen ≤ 352`、`argsLen ≤ 256`）——那些由 `decode` 负责，好让负例测试能构造越界记录再喂给 `decode`。所有 `Bytes` / `Bytes32` 成员在构造器里防御性复制。

CALL/DEPLOY 的 link 角色：CALL link[0] = 参数链（仅 flags.bit0）；DEPLOY link[0] = 代码链（flags.bit1）、link[1] = 参数链（flags.bit2；若无代码链则参数链是 link[0]）。多余 link → `EXTRA_LINK`；缺失 → `MISSING_LINK`。

### 3.6 分片链

```
ChunkChain.assemble(Bytes32 head, RawBlockLookup lookup, int maxChunks, long minEpoch) -> ExtResult<Bytes>
  visited = {}; expectSeq = 0; total = -1; out = []
  cur = head                              // head == null 立即 MISSING_LINK
  while cur != null:
    if !visited.add(cur)                     -> CHUNK_CYCLE
    if visited.size() > maxChunks            -> CHUNK_TOO_MANY
    b = lookup.get(cur);  if b == null       -> MISSING_LINK
    if epoch(b.timestamp) < minEpoch         -> CHUNK_TOO_OLD        // 年龄规则，见下
    c = classify(b);  if c.kind != CHUNK     -> NOT_A_CHUNK
    if !c.isOk()                             -> c.error              // 编解码错误原样透传，不折叠成 NOT_A_CHUNK
    if c.seq != expectSeq                    -> CHUNK_SEQ_GAP
    if total < 0:
        total = c.totalLen
        if total > maxChunks * 352           -> CHUNK_TOO_MANY       // 伪造头片的快速失败
    else if c.totalLen != total              -> CHUNK_TOTAL_MISMATCH
    out += c.data
    if out.size > total                      -> CHUNK_TOTAL_MISMATCH
    if out.size == total && c.next != null   -> CHUNK_TAIL_HAS_LINK
    expectSeq++;  cur = c.next
  if out.size != total                       -> CHUNK_TOTAL_MISMATCH
  return out

ChunkChain.countLenient(Bytes32 head, RawBlockLookup lookup, int maxChunks, long minEpoch) -> int
  只统计"查得到 + 够新 + 能解码成 CHUNK"的块，遇到第一个缺失/过旧/非 CHUNK 就停；永不失败、永不抛异常、环安全、上限 maxChunks。

ChunkChainBuilder.split(Config config, Bytes payload, long headTimestamp) -> List<Block>   // 按 seq 升序返回，index 0 = 头片
```

`assemble` / `countLenient` 各有一个 3 参重载，等价于 `minEpoch = Long.MIN_VALUE`（无年龄下界），**共识路径一律不得使用**。`total > maxChunks × 352` 这条在头片解出来的当下就触发，早于任何后续跳转与缓冲：否则一个伪造头片声明一个天文数字的 `totalLen`，就能逼调用方一路找一条永远拼不完的链，或者去开一个荒谬大的重组缓冲区。

**年龄规则（共识）**：链上每一片都必须满足 `epoch(chunk) ≥ epoch(payingBlock) − 1`，即"与付费块同 epoch，或紧邻的前一个 epoch"，否则 `CHUNK_TOO_OLD`。理由：`tryToConnect` 的 `NO_PARENT` 只要求父块的 `BlockInfo` 存在，而**快照启动的节点没有快照时间之前的块的原始字节**（`getBlockByHash(h, true)` 恰好在原始字节缺失时返回 `null`）；没有年龄下界时，同一条内容寻址的老分片链会在全量节点上装配成功、在快照节点上 `MISSING_LINK`——同一个块在两类节点上得到两种裁决。年龄规则让两类节点都只需要保留最近两个 epoch 的原始字节，裁决就必然一致。`minEpoch` 取自**付费块自己的时间戳**，不是主块的（决定论：只依赖被 apply 的那个块的字节）。

**`assemble` 与 `countLenient` 的费用契约**：`assemble` 在一条 N 片链上成功时，同参数的 `countLenient` 恰好返回 N；`assemble` 拒绝的链上 `countLenient` 可能多数（它会数到第一个问题之前的每一个合法 CHUNK，包括那个让 `assemble` 失败的片本身），但绝不会少数。因此凡是 L1 自己会装配的链（DEPLOY 的代码链），费用基数必须在 `assemble` 通过**之后**才取；参数链 L1 从不装配，见 §4.2。

**`split` 的 epoch 吸附**：按 `ChunkExt.MAX_DATA_LEN = 352` 字节切片，尾片先建（好让每片的 `link[0]` 指向已建好的下一片），返回时头片在前。头片时间戳默认就是 `headTimestamp`；若这条链会跨 epoch（`(headTimestamp & 0xffff) < n − 1`），整条链吸附进前一个 epoch：`head = (headTimestamp & ~0xffff) − 2`。用 `−2` 而非 `−1`，是因为低 16 位为 `0xffff` 的时间戳被 `XdagTime.isEndOfEpoch` 认作 epoch 末，会让这个无 INPUT 的块走 `calculateCurrentBlockDiff` 的 RandomX 难度分支；65535 个 tick 的容量足够协议允许的任何链（`maxPerChain = 4096`）。**一条链不跨 epoch**本身就是年龄规则的前提：尾片最老、也是年龄规则的约束点，跨 epoch 的链只能在头片自己那个 epoch 内被付费，窗口可短到 0 tick。

**导入顺序**：头片最新、尾片最老，而 XDAG 要求块的时间戳不得晚于它引用的任何块，所以**分片必须尾片先导入、头片最后，付费块再最后**。分片块以 `keys == null, defKeyIndex == -1, mining == false` 构造，因此天然带两个全零 `SIGN_OUT`、`getNonce()` 恒为 `null`（永远不会被标成 `BI_EXTRA`，即使时间戳落在 epoch 末），正常落盘。

`lookup` 由 `BlockchainImpl.getBlockByHash(hash, true)` 提供；付费块可导入 ⇔ 整条链已在本地（`NO_PARENT` 规则）。

### 3.7 付费块的 L1 金额与两层费用（`ChainBlockBuilder`）

`BlockchainImpl.outPutNum` / `getTxFee` 把 `Block.getOutputs()` 里的每一项都算作 "output"，**不区分**真正的支付（`XDAG_FIELD_OUTPUT`）和分片链头引用（`XDAG_FIELD_OUT`）；L1 既有的费用规则是 `input ≥ headerFee + MIN_GAS × outputs`。因此每个 CALL / DEPLOY 块的 INPUT 金额必须满足

```
requiredValue(headerFee, chainLinks) = headerFee + MIN_GAS × (1 + chainLinks)
```

其中 `1` 是那一笔真正的 OUTPUT（CALL 打给金库，新建链的 DEPLOY 自转），`chainLinks` 是这个块实际携带的分片链头 link 数（CALL 0 或 1；DEPLOY 1 或 2）。`deployNewChain` 自己按这个式子算自转金额（**在决定有没有参数链之后**算，因为代码链总是存在）；`call` / `deployIntoChain` 把 `value` 留给调用方，调用方必须用同一个式子定，否则块在 `tryToConnect` 就被拒。

这与 §4.2 的**分片费**是两回事，两层都要满足：`headerFee` 还必须至少是 `minHeaderFee(chunkFee, chunks) = chunkFee × chunks`，其中 `chunks` 是该块 link 的**所有链的片数之和**（不是链数）。前者是既有的 L1 有效性规则（不满足 → 块无效），后者是激活之后的链语义（不满足 → 输入记为 `INVALID_FEE`，块仍然有效）。

**link 字段顺序**：`Block` 构造器按 `links` 列表顺序写类型 nibble，而 `getEncodedBody()` 总是先写全部 `INPUT`/`IN`、再写全部 `OUTPUT`/`OUT`/`COINBASE`。要让 nibble 序列与实际字段字节一致，builder 里每个 refs 列表必须**先 INPUT、再 OUTPUT、最后 OUT（分片链头）**，不能是别的顺序。

**分片链头的时间戳**：每条链都根植于 `timestamp − 1`（若那一 tick 的低 16 位恰是 `0xffff` 则再退一 tick，理由同 §3.6），代码链与参数链**各自独立**根植，不把另一条链的长度叠上去——叠上去可能把第二条链的头推早一个 epoch，违反年龄规则。两条链可以共享同一个头时间戳；内容也恰好相同时切出来的分片块逐字节相同，`finish` 按 hashLow 去重（保留第一次出现、保持顺序），否则导入方会把第二份看成 `EXIST`。

---

## 4. 分类与归属判定

### 4.1 `ChainBlockClassifier.classify(Block) -> Classified`

```
record Classified(ExtKind kind /*null = 无 EXT，或 kind 字节未分配*/, Object value /*解码结果*/, ExtError error)
  Classified.NONE = (null, null, ExtError.NO_EXT)     // 块根本没有 ext 字段
  紧凑构造器要求 value / error 恰好一个非空
  <T> T as(Class<T>)  在 error != null 时抛 IllegalStateException（不是返回 null 的强转）
```

- `extFields` 为空 → `Classified.NONE`（kind = `null`，error = `NO_EXT`）。
- 取 `extFields[0]` 为 ext 头，`extFields[1..]` 为 payload，`getBlockLinks()` 为 links，按头字节 0 经 `ExtKind.fromCode` 分派到对应 `decode`；未分配的 kind 字节 → `(null, null, UNKNOWN_KIND)`。
- 分类器**纯函数**、不访问存储、不抛异常（`block == null` 除外：`NullPointerException`，编程错误）。调用方必须先 `isOk()` 再 `as(...)`。
- 传进来的块必须是 raw 块（原则 P6）：`isRaw = false` 的块既无 ext 字段也无 link，会被判成 `NONE`。

### 4.2 归属判定（在 `ChainL1Processor.onBlockApplied` 内，需要注册表）

```
vaultOutputs = [o for o in block.getOutputs()
                if o.isAddress && o.type == XDAG_FIELD_OUTPUT && registry.hasChain(addr20(o))]   // 按字段顺序，可重复
c = classify(block)
minEpoch = XdagTime.getEpoch(block.timestamp) - 1        // 付费块自己的 epoch（§3.6）
vaultConsumed = false

case c.kind == DEPLOY && c.isOk() && c.flags.newChain:
    chainId = ChainIds.chainIdOf(block.hash);  contract = ChainIds.contractIdOf(block.hash)
    applyDeploy(chainId, newChain = true)          // 不消费 vaultOutputs：新链此刻还不在注册表里
case c.kind == DEPLOY && c.isOk() && !c.flags.newChain:
    if vaultOutputs.size() == 1 && vaultOutputs[0] == c.chainId:
        vaultConsumed = true;  applyDeploy(c.chainId, newChain = false)
case c.kind == CALL && c.isOk():
    if vaultOutputs.size() == 1 && contracts.get(c.contract)?.chainId == vaultOutputs[0]:
        vaultConsumed = true
        chunks = countLenient(c.argsChainHead, minEpoch)
        record(vaultOutputs[0], CALL, feeCovers(block, chunks) ? OK : INVALID_FEE, c.contract)
case c.kind ∈ {ANCHOR, BOND, CHALLENGE, CLAIM} && c.isOk():
    handlers[kind]?.onApplied(block, c, ctx, batch)      // SP0a 不注册任何 handler
兜底：if !vaultConsumed: for v in vaultOutputs: record(v, kind = null, INVALID_FORMAT, 全零地址)
```

`ChainIds.chainIdOf(h) = sha256("xdag-chain" ‖ h)[0..20]`，`contractIdOf(h) = sha256("xdag-contract" ‖ h)[0..20]`；两个 tag 与截断长度都是协议常量，改动即硬分叉。

**只有 `XDAG_FIELD_OUTPUT` 地址链接算金库支付。** `Block.parse` 也会把 `XDAG_FIELD_COINBASE` 放进 `getOutputs()`，但 `applyBlock` 从不结算 COINBASE 字段的值，所以它绝不能被记成对金库的支付。这取代了原开放问题 Q3（"主块 coinbase 恰好指向某金库时会误记 `INVALID_FORMAT`，接受"）：现在不会。

**每一个被归属的金库 OUTPUT 产生一条输入记录**：同一个块向同一个金库出两笔 OUTPUT，就是两条 `INVALID_FORMAT` 记录（`vaultOutputs.size() != 1`，不会被 CALL/DEPLOY 消费），进金库的每一分钱都有账可对。

`applyDeploy` 的检查顺序（**先便宜后昂贵，且顺序决定最终 status**）：

1. **新建链的配置边界**：`1 ≤ maxCallGas ≤ 10_000_000`（`ChainL1Processor.MAX_CALL_GAS_CAP`，总规格 §17 的 `maxCallGas` 初值，只能下调），否则 `INVALID_FORMAT`。`0` 的链永远跑不了一次调用，超上限的链会让欺诈证明仲裁无界。`gasPriceNano` 与 `D` 不设限。此检查**先于**代码链检查：只看块自己的字节就能定，注定失败的链不值得去装配一条代码链。
2. **代码**：`flags.bit1` 时按年龄规则装配代码链，装配失败 → `INVALID_FORMAT`；长度 > `maxWasmBytes` → `CODE_TOO_LARGE`；`sha256(装配结果) != payload[0].codeHash` → `INVALID_FORMAT`。无代码链时 `codeHash` 必须已在代码库（`hasCode`），否则 `INVALID_FORMAT`。
3. **费用**：`chunks` = 代码链片数（**仅在 `assemble` 已通过之后**再用 `countLenient` 数一遍）+ 参数链的 `countLenient`；`headerFee < chunkFee × chunks` → `INVALID_FEE`。

因此 `INVALID_FORMAT` / `CODE_TOO_LARGE` 优先于 `INVALID_FEE`；`INVALID_FEE` 只可能出现在其余都合法的块上。

**费用规则（共识复核）**：`headerFee ≥ chunkFee × 该块直接 link 的所有分片链片数之和`；`chunks == 0` 时直接通过。`headerFee` 必须从**原始 512 字节的 header 字段**读（`BlockInfo.fee` 在 apply 期间会被改写成"收到的手续费"，`Block.getFee()` 答的不是"这个块声明了多少"）。

**参数链的费用基数就是 `countLenient` 本身，这是有意为之**：L1 从不装配参数链（CALL 的参数、DEPLOY 的 init 参数），SP1 的执行引擎才装配，装不出来就在链内失败并退款。分片费收的是"全网确实要存的分片块"，与这条链将来能不能装配无关。只有 DEPLOY 的代码链是 L1 自己装配的，所以它的片数必须在 `assemble` 通过之后才数（§3.6 的费用契约）。

`InputStatus { OK, INVALID_FORMAT, INVALID_FEE, CODE_TOO_LARGE }`，编码为 u8 `0..3`。所有状态都产生输入记录（value 的链内退款由 SP1 按状态处理）。

DEPLOY 的 `status == OK` 时额外写：新建链 → `putChain(chainId, ChainRecord(height, blockHash, gasPriceNano, D, maxCallGas, contractCount = 1))`；加入已有链 → `putChain(chainId, chain.withContractCount(+1))`；总是 `putContract(contract, ContractRecord(chainId, codeHash, height, blockHash))`；代码库按 `getCodeRefCount(codeHash)` 决定是 `putCodeRef(+1)`（已存在，**绝不重写 blob**——内容寻址已经保证存的就是这份代码）还是 `putCode(refCount = 1, bytes)`。

注册表查询用 DFS 当前时刻的状态：同一高度先 DEPLOY 后 CALL 可见，顺序全网一致。向未注册地址的转账不产生记录。

---

## 5. `CHAIN_L1` 存储

新增 `DatabaseName.CHAIN_L1`；`RocksdbFactory` 按需创建；`Kernel` 在 `orphanBlockStore.start()` 之后、`new BlockchainImpl(this)` 之前构造 `ChainL1Store` 并 `start()`。

### 5.1 `KVSource.batchWrite`

```java
default void batchWrite(List<Pair<K,V>> puts, List<K> deletes) { puts.forEach(put); deletes.forEach(delete); }   // 接口默认：顺序写
// RocksdbKVSource 覆盖：WriteBatch + db.write(writeOptions, batch)，持读锁
```

### 5.2 前缀布局（值定长手工编码，小端）

每个 key 都是"1 字节前缀 + 定长后缀"，没有分隔符，因此共享前缀的 key 不可能互相碰撞。

| 前缀 | 名字 | key | value | SP0a |
|------|------|-----|-------|------|
| 0x00 | `META` | `0x00` | schemaVersion u32 = 1 | ✓ |
| 0x01 | `CHAIN` | chainId(20) | createdHeight u64 ‖ createBlockHash 32 ‖ gasPriceNano u64 ‖ D u32 ‖ maxCallGas u32 ‖ contractCount u32（60B） | ✓ |
| 0x02 | `CONTRACT` | contract(20) | chainId 20 ‖ codeHash 32 ‖ deployHeight u64 ‖ deployBlockHash 32（92B） | ✓ |
| 0x03 | `CODE` | codeHash(32) | **原始代码字节**（只有 blob，不含计数） | ✓ |
| 0x07 | `CALL_COUNT` | chainId(20) ‖ height u64 | callCount u32 | ✓ |
| 0x0C | `INPUT` | chainId(20) ‖ height u64 ‖ index u32 | blockHash 32 ‖ kind u8（0 = 未分类）‖ status u8 ‖ contract 20（54B） | ✓ |
| 0x0D | `CODE_REF` | codeHash(32) | refCount u32 | ✓ |
| 0x0E | `REVERSE` | blockHash(32) | `InputRef`（chainId 20 ‖ height u64 ‖ index u32 = 32B）的定长数组 | ✓ |
| 0x04 0x05 0x06 0x08 0x09 0x0A 0x0B | 保证金 / 锚定头 / 锚定索引 / 已认领 / 高度触发器 / 挑战 / 段游标 | | 常量预留 | SP2/SP3 |
| 0xFF | `SNAPSHOT_HASH` | `0xFF` | 状态哈希 32 | 快照库里是导出的哈希；本地库里是"已从快照导入过"的一次性标记（§8）。**不计入 `stateHash()`** |

**`CODE` 与 `CODE_REF` 拆开**（原设计是 `refCount ‖ len ‖ bytes` 一条 value）：共享同一份代码的第二个 DEPLOY 只需要重写 4 字节的计数，而不是整份（最多 1 MB）blob。由此确定：

- `hasCode(codeHash)` ⇔ **`CODE_REF` 存在**（一次点查 4 字节，从不读 blob）；`getCodeRefCount` 对不存在的 key 返回 0，且**refCount 为 0 时永远不写这条 key**（删除而不是写 0），这样"代码库里有没有这份代码"的判定全网一致，unwind 之后也一致。
- 引用计数增减永不重写 blob；降到 0 时 `CODE` 与 `CODE_REF` 一起删。
- `getCallCount` 同理：不存在即 0，归 0 时删 key 而不是写 0。

`ChainL1Store` API：

- **写只有一条路径**：调用方构造 `ChainL1Batch`（`putChain / deleteChain`、`putContract / deleteContract`、`putCode / putCodeRef / deleteCode`、`putCallCount / deleteCallCount`、`putInput / deleteInput`、`putReverse / deleteReverse`），`store.commit(batch)` 一次 `batchWrite` 原子落盘；空批次不触底层。批次对同一个 key 只保留最后一次写。
- **读全是点查**：`hasChain / getChain`、`getContract`、`hasCode / getCodeRefCount / getCode`、`getCallCount`、`getInput`、`getReverse`；读 API 里没有范围扫描。
- **快照边界操作（O(N) 时间与堆，每块从不调用）**：`sortedKeys()`、`stateHash()`、`exportSnapshot(KVSource)`、`importSnapshot(KVSource)`。
- `stateHash()` = 按 key 的无符号字典序遍历，每对序列化为 `u32(len(key)) ‖ key ‖ u32(len(value)) ‖ value`（小端 u32）喂给 SHA-256；**`META` 计入，`0xFF` 自身排除**。它是内容的纯函数：RocksDB 与内存实现算出同一个值。
- 线程安全只到"共识锁"这一层：调用方（`BlockchainImpl` 的钩子）必须像串行化块应用一样串行化访问。

---

## 6. L1 钩子

### 6.1 接口

```java
public interface ChainL1Hooks {
    void onSetMainBegin(long height, Block mainBlock);
    void onBlockApplied(Block block);
    void onSetMainEnd(long height, Block mainBlock);
    void onBlockUnapplied(Block block);
    void onUnsetMain(long height, Block mainBlock);
    ChainL1Hooks NOOP = new ChainL1Hooks() { /* 全空 */ };
}
```

### 6.2 调用点（`BlockchainImpl`）

| 钩子 | 位置 |
|------|------|
| `onSetMainBegin(mainNumber, block)` | `setMain`：`updateBlockFlag(block, BI_MAIN, true)` 之后、包住整个 apply 过程的 `try` 之前 |
| `onBlockApplied(block)` | `applyBlock`：紧跟 `updateBlockFlag(block, BI_APPLIED, true)`，**两处调用点**——无 link 的提前 `return` 分支，以及正常出口（无论 `flag` 真假）。主块自身也会到达此处，处理器按 kind 判定，主块无 EXT 直接返回 |
| `onSetMainEnd(mainNumber, block)` | `setMain`：包住整个 apply 过程的 **`finally` 块**里——正常结束、`mainBlockFee < 0` 的提前 `return`、以及从 `applyBlock` DFS 里抛出来，三条出口都走它。（原设计只要求"方法末尾 + 提前 return 之前"，`finally` 把第三条出口也覆盖了） |
| `onBlockUnapplied(block)` | `unApplyBlock`：`updateBlockFlag(block, BI_APPLIED, false)` 之前（此时 `BI_APPLIED` 仍为真） |
| `onUnsetMain(height, block)` | `unSetMain`：方法开头、`unApplyBlock(block, true)` 之前（先关上下文语义，再逆序反写），高度用 `block.getInfo().getHeight()`（此时还没被清零） |

**处理器的安装位置是一条接线不变量**：`ChainL1Processor` 在 **`BlockchainImpl` 构造器里**用 `kernel.getChainL1Store()` 建好并装上，且**在 `startCheckMain` 之前**。checkMain 循环的初始延迟是 0，构造器的调用方拿回控制权之前它的第一次 `checkNewMain → setMain` 就可能已经跑完；从外面（像 `Kernel` 原先那样）接线，会让那个主块在钩子还是 `NOOP` 时被确认，它的链输入就永久丢在 `CHAIN_L1` 之外了。`kernel.getChainL1Store()` 为 `null`（不带 chain 存储的测试）时保持 `NOOP`。构造器紧接着把 `kernel.getChainKindHandlers()` 里的每一项 `registerHandler` 进去（见 §6.3），然后才 `startCheckMain`。`BlockchainImpl.setChainHooks` 已删除（没有任何调用方；测试读 `getChainHooks()` 拿到构造器装的那个处理器）。

### 6.3 `ChainL1Processor` 行为

- `onSetMainBegin`：`ctx = ChainActivation.isActive(height) ? new ApplyContext(height, mainBlock.hash) : null`。`ApplyContext` **只有 height 与主块哈希**，没有 DFS 计数器、也不持有批次。进来时 `ctx` 非空说明调用方 begin/end 不配对，记 `warn` 并覆盖。
- `onBlockApplied`：`if ctx == null: return`；按 §4.2 判定；每条 `record(...)` 写 0x0C（**index = 该 (chain, height) 的当前 callCount，即每链每高度从 0 递增，执行者按 `0..callCount−1` 枚举**）、把 `InputRef` 追加到本块的 0x0E 列表、并把 0x07 置为 `index + 1`；DEPLOY 成功再写 0x01/0x02 与 0x03/0x0D。**同一块的所有写入组成一个 batch，块结束时立即 `store.commit(batch)`**（一个块一批，而不是一个主块一批：`applyBlock` 递归中后续块的注册表查询要看到前面块的写入）。一个块可以有多条记录（每个被归属的金库 OUTPUT 一条），块内 index 在内存 map 里递增，同一个 (chain, height) 的多次 `putCallCount` 靠批次"同 key 只留最后一次写"收敛到最终值。什么都没归属、handler 也没写东西时**不提交空批次**。若该块的 0x0E 已存在（被 apply 了两次而中间没有 unapply），记 `error` 但仍然写入——在这里让块应用失败更糟。
- `onSetMainEnd`：SP0a 无高度触发器；`ctx = null`。`ctx.height != height` 时记 `warn`（调用方 begin/end 不配对）。
- `onBlockUnapplied`：**完全不看 `ctx`**（unwind 跑在任何 `setMain` 之外），高度从记录下来的 `InputRef` 里取。先分派非内建 kind 的 handler，再读 0x0E：为空就只剩 handler 的写入。有记录时**按 refs 逆序**逐条删 0x0C、`callCount--`；记录 kind == DEPLOY 且 status == OK 时 `undoDeploy`（删 0x02；`refCount ≤ 1` 则删 0x03+0x0D，否则 `putCodeRef(−1)`；创建块自己被回滚则 `deleteChain`，否则 `contractCount--`）；最后删 0x0E，一次 `commit`。`callCount` 归 0 时**删 key 而不是写 0**。记录损坏（`InputRecord.decode` / `InputStatus.fromCode` 抛）是**故意的 fail-stop**：不 catch——连记录都解析不了就无法正确回滚，静默跳过比暴露 DB 损坏更糟。**`unApplyBlock` 已对 links 逆序，处理器不再排序**。
- `onUnsetMain`：`ctx = null`（防御性）。与 `onSetMainEnd` 不同，它不与某一次 `onSetMainBegin` 配对，因此不做高度一致性检查。
- **`registerHandler` 必须在第一个钩子跑之前**：任何一个钩子方法进入时都会把 `started` 置真，之后 `registerHandler` 抛 `IllegalStateException`（否则同 kind 的已处理块就漏掉了它的分派）；`CALL / DEPLOY / CHUNK` 是内建的，注册它们抛 `IllegalArgumentException`。由于处理器是在 `BlockchainImpl` 构造器里建的，**注册点只有一个：`Kernel.getChainKindHandlers()`**——SP2/SP3 把 handler 放进这张 `EnumMap`，**在 `new BlockchainImpl(kernel)` 之前**；构造器建完处理器就把整张表 `forEach(processor::registerHandler)` 灌进去，再 `startCheckMain`。**不存在"构造之后立刻注册"这个窗口**：checkMain 的初始延迟是 0，构造器返回之前第一个 `setMain` 就可能已经跑完，那时 `started` 已为真，事后注册只会拿到 `IllegalStateException`。这张表构造之后不再被读，往里放东西没有任何效果。
- `ChainKindHandler` 的签名是 **4 参 / 3 参**：`onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch)` 与 `onUnapplied(Block block, Classified classified, ChainL1Batch batch)`。handler **写进处理器递给它的那个批次**，不自己提交（P5：一个块一批一次提交）。`onUnapplied` **可能为一个从未 apply 过的块触发**（unwind 路径没有上下文，无从知道那个高度当时是否已激活），因此 handler 必须**撤销幂等**：只撤销"自己存下来的记录能证明它做过"的事，不能假设与 `onApplied` 严格配对。
- **unapply 必须是 apply 的严格逆序**：`callCount` 的算术只有在"被 unapply 的块占据它碰过的每个 (chain, height) 的最高那几个 index"时才正确。`BlockchainImpl` 保证了这一点（`unApplyBlock` 先本块、再按逆序遍历 links）。真的被违反时，处理器用**金丝雀 + 钳制**兜底而不是抛异常：`callCount` 变负 → 记 `error` 并删 key；创建块被回滚时 `contractCount != 1` → 记 `error`；`contractCount` 会变负 → 记 `error` 并钳到 0（否则 `ChainRecord` 的 u32 范围检查会从 `unSetMain` 里抛出来）。

### 6.4 输入索引的确定性

`index` 是**每 (chain, height) 内从 0 递增的序号**，不是主块级的全局 DFS 序号：执行者读到 0x07 的 `callCount` 就能按 `0..callCount−1` 枚举该链该高度的全部输入。它的确定性来自 `applyBlock` 的递归顺序 = link 顺序 = 全网一致；`onBlockApplied` 只在 `BI_APPLIED` 成功路径被调用，`syncTxStatus` 导致的拒绝在所有节点一致（既有共识）。因此 `(chainId, height, index)` 全网一致。

处理器的每一个判定都只依赖：被 apply 的那个块的原始字节、DFS 走到此刻的 `CHAIN_L1` 状态、`ChainSpec` 参数、以及块查找。没有任何一处读墙上时钟（年龄规则用的是块自己的时间戳），也不依赖无关块到达网络的先后。

### 6.5 Reorg 备注

`unWindMain` 沿 `maxDiffLink` 脊柱 `rollTx`，脊柱上若有 CALL 块，其 OUT 引用（分片）会被扔回孤块池并被下一个主块当普通孤块再 link 一次。分片内容寻址、`BI_MAIN_REF` 已置时 `applyBlock` 跳过，无害。

---

## 7. 激活高度与配置

`config/spec/ChainSpec`（仿 `SnapshotSpec`）：

| 键 | 类型 | devnet | testnet | mainnet | conf 键 |
|----|------|--------|---------|---------|---------|
| activationHeight | long | 0 | `Long.MAX_VALUE` | `Long.MAX_VALUE` | `chain.activation.height` |
| maxChunksPerChain | int | 4096 | 同 | 同 | `chain.chunk.maxPerChain` |
| maxWasmBytes | int | 1,048,576 | 同 | 同 | `chain.wasm.maxBytes` |
| chunkFee | XAmount | 0.01 XDAG | 同 | 同 | `chain.chunk.feeMilliXdag`（milli-XDAG 整数） |
| maxInlineArgs | int | 256 | 同 | 同 | **无**——线格式常量 |

`maxInlineArgs` **没有 conf 键**（原设计的 `chain.args.maxInline` 已删）：它是 `ChainSpec.CHAIN_MAX_INLINE_ARGS = 256`，由 512 字节块布局推导（`(16 − 8) × 32`），与 `CallExt.MAX_INLINE_ARGS` 同源。可配置化意味着"同一个块在一个节点上装得下、在另一个节点上装不下"。同理 `maxChunksPerChain` 的协议默认值 `ChainSpec.DEFAULT_MAX_CHUNKS_PER_CHAIN = 4096` 与 `ChainBlockBuilder.MAX_CHUNKS_PER_CHAIN` 同源，且 **builder 按 config 里实际配置的值**限制切片数（超出 → `CHUNK_TOO_MANY`），而不是按常量。4096 × 352 B ≈ 1,441,792 B ≈ 1.44 MB。

`AbstractConfig.getSetting()` 读 conf 覆盖，并在**启动时 fail-fast**（这些值不一致就是静默分叉）：

- `chain.activation.height ≥ 0`；给了覆盖值就打一条 `warn`（网络默认被压过）。
- `chain.chunk.maxPerChain > 0`、`chain.wasm.maxBytes > 0`、`chain.chunk.feeMilliXdag ≥ 0`（负值与 `XAmount` 溢出都直接拒）。
- `maxWasmBytes ≤ maxChunksPerChain × 352`——超过这个天花板的 WASM 上限，任何分片链都够不着。
- `chunkFee × 2 × maxChunksPerChain` 必须不溢出 long：共识复核会把片数乘上去，DEPLOY 最坏情况是代码链 + 参数链两条；溢出会从 `setMain` 里抛出来，所以在启动时就拒。

**`chain.chunk.maxPerChain`、`chain.wasm.maxBytes`、`chain.chunk.feeMilliXdag` 这三个键与 `chain.activation.height` 都是共识参数，绝不要在共享网络（testnet / mainnet）上设置。** 它们不是节点本地的调优旋钮：一个节点用了非默认值，就会对同一批块算出与全网不同的 CHAIN_L1 裁决——**静默分叉 CHAIN_L1**，本地没有任何报错可看。它们的存在只为 devnet 与测试。因此 `AbstractConfig.getSetting()` 对这四个键里被 conf 覆盖的每一个都打一条 `warn`（`<key> overridden by configuration to <value> (protocol default <default>); this is a consensus parameter, never set it on testnet/mainnet`），`ChainSpec` 的类注释里记了同一条。

`Config` 接口暴露 `getChainSpec()`；`setChainActivationHeight(long)` 会清掉 conf 来源的覆盖值，所以测试/工具设的值一定生效。
`ChainActivation.isActive(long height)`（`height >= activationHeight`）；`isActiveNow(XdagStats)` 用 `nmain` 供 SP5/RPC/钱包按当前链顶门控，**不得用于 apply/unwind 路径**——那里必须传正在被确认的块的高度（`setMain` 里是 `nmain + 1`）。

**绝不往任何 conf 文件里加 `chain.*` 键**：`src/test/resources/xdag-devnet.conf` 是主配置的影子副本且优先级更高，两份文件一旦不同步就会出现"测试通过、生产分叉"。一律用代码默认值 + `setChainActivationHeight`。

---

## 8. 快照扩展

**导出**（`XdagCli.makeSnapshot`）：快照从此有**三个目录**——`SNAPSHOT/BLOCKS`、`SNAPSHOT/ADDRESS`、`SNAPSHOT/CHAIN_L1`（节点 store 目录下的独立 `RocksdbKVSource`）。`CHAIN_L1` **无条件导出**（没有 `shouldExport` 之类的开关）：`store.exportSnapshot(target)` 复制全部 key，再写 `0xFF → stateHash()`；`CHAIN_L1` 为空时导出的就是"`META` + 哈希"，激活高度之前的节点导入时会忽略它。目标目录非空 → 抛（二次导出会与上一次合并，记录的哈希就对不上内容了）。导出失败被 `makeSnapshot` 捕获并打印，**不影响它继续打印高度/下一帧摘要**。快照目录结构本身记在 `docs/XDAGJ_SNAPSHOT_zh.md` 与 `.claude/docs/snapshot-and-storage.md`。

`chainStateHash` 的定义见 §5：按 key 无符号字典序的 `u32(len(k)) ‖ k ‖ u32(len(v)) ‖ v` 的 SHA-256，含 `META`、不含 `0xFF` 自身。

**导入**（`ChainL1SnapshotGate.checkAndImport(config, snapshotHeight, store)`）：调用点是 **`BlockchainImpl` 构造器里快照启动分支的第一条语句**——即 `isSnapshotEnabled() && snapshotHeight > 0 && !blockStore.isSnapshotBoot()` 这一支的最前面，**在块/地址导入之前，且与 `snapshot.isSnapshotJ()` 开关无关**。这样每一次快照启动都会过这道门，并且是 fail-fast：缺 `CHAIN_L1` 的节点在动任何其它状态之前就停下来。

判定顺序：

1. `snapshotHeight < activationHeight` → **跳过**（no-op，什么都不碰：目录在不在都忽略，`store` 为 `null` 也不管）。
2. `store == null` → **抛**：到了激活高度，内核必须在 `BlockchainImpl` 之前就把 `CHAIN_L1` 接好，没接好不是"降级运行"而是"这个节点算不出正确的链状态"。
3. 本地 `CHAIN_L1` 已带 `0xFF` 标记（`importedSnapshotHash().isPresent()`）→ **只有在本次启动的 `SNAPSHOT/CHAIN_L1` 目录存在、它记录的 `0xFF` 哈希正好等于标记、且本地 `stateHash()` 仍等于标记**这三条同时成立时才幂等返回（同一次重新灌库的崩溃窗口重试）；任何一条不成立都**抛**，并提示删掉本地 `CHAIN_L1` 目录重启（目录缺失、快照未记录哈希、快照哈希 ≠ 标记 = "本次要用另一份快照灌库"、本地哈希 ≠ 标记 = "本地已经越过了导入时的状态"，各自一条消息）。`0xFF` 是**一次性的持久标记**：一次成功的导入会把快照里那份哈希写进本地库的 `ChainL1Keys.SNAPSHOT_HASH_KEY`，**与导入的全部 key 在同一个 `batchWrite` 里**原子落盘；它不计入 `stateHash()`，所以不影响状态哈希语义。它**不是**"跳过校验"的通行证：这道门只在块存储即将被快照重新灌库时才会到达（快照分支要求 `!blockStore.isSnapshotBoot()`，而 `Kernel` 构造完成后立即 `setSnapshotBoot()`），普通重启根本走不到这里；因此标记存在只可能意味着"上一次对**这同一份**快照的灌库已经导入过 chain 状态"，两半都要被证明。运维在节点成功启动之前**不要**删掉 `SNAPSHOT/CHAIN_L1`。
4. 没有标记，但本地 `CHAIN_L1` 已有状态（`hasState()`：`META` 与标记之外还有 key）→ **抛**，并给出补救办法（删掉本地 `CHAIN_L1` 目录重启，或换用配套的快照）。这份状态的来源无从证明（另一条链遗留、写了一半的导入、手工拷贝），静默合并或静默信任都可能直接分叉。
5. 快照目录 `SNAPSHOT/CHAIN_L1` 缺失 → **抛**，并指明补救办法："从发布 `SNAPSHOT/BLOCKS` 的同一来源取 `SNAPSHOT/CHAIN_L1`，放在它旁边再重启"。
6. 其余情况（没有标记、本地只有 `start()` 写的 `META`、目录也在）→ **导入**：校验快照带了 `0xFF`（且是 32 字节）、schema 版本等于 1，**在写任何东西之前**先用快照内容重算哈希与 `0xFF` 比对（不等则抛，store 保持原样），一致则把全部 key 连同标记在**一个原子批次**里提交——被拒绝或被中断的导入不会留下半填状态。

**§A（Task 17）：快照里不带任何原始分片字节。** 年龄规则允许快照之后的付费块往回够两个 epoch，可能够到快照时间之前；那些 CHUNK 块不在快照里，也不需要在，理由三条：

1. `SnapshotStoreImpl.makeSnapshot` 只保留"有公钥（`snapshotInfo != null`）或余额非零"的块，CHUNK 块金额为 0、无公钥，因此**在快照节点上整块都不存在**——连 `BlockInfo` 都没有，不是"只有 `BlockInfo` 没有原始字节"。
2. `tryToConnect` 只因"时间戳超前 `now + MAIN_CHAIN_PERIOD/4` / 早于 `xdagEra`"拒块，**没有任何以快照高度为界的拒绝规则**。指向一个连 `BlockInfo` 都没有的块的 link 得到 `NO_PARENT`，`SyncManager` 对 `NO_PARENT` 的回应就是向所有活跃 channel `sendGetBlock`，按需递归拉取父块；拉回来的块走正常导入路径，`saveBlock` 会写原始字节、`BlockInfo` 与时间索引。
3. 因此付费块在整条分片链被拉齐并落盘之前根本连不上；等到某个主块确认它时，快照节点上的 `getBlockByHash(h, true)` 对每一片都成功，与全量节点的裁决完全一致。

**残余暴露（接受；纯节点本地活性，永不产生状态分歧）**：如果没有任何 peer 能提供那些老分片，付费块在这个节点上就一直连不上，link 它的主块也一直 `NO_PARENT`。`SyncManager` 的重请求还带节流（同一个 hash 64 s 内不重复请求）并在 pending 集合到 `MAX_SIZE` 时随机淘汰条目，所以"最终会拉到"是尽力而为而非保证——但失败的后果只是这个节点跟不上，不是两个节点对同一个块给出不同裁决。这与"快照节点遇到任何一个引用了被丢弃的快照前块的新块"是同一种活性依赖。

幂等闸门沿用 `isSnapshotBoot`（构造器只在它还是 false 时进入快照分支）+ 上面那条 `0xFF` 标记。

---

## 9. 测试策略（TDD，JUnit 4，`BlockBuilder` / `BlockchainTest` 模式）

| 层 | 用例 |
|----|------|
| 编解码 | 七种记录 encode→decode 往返；每字段边界（u16/u32 极值、argsLen 256/257）；保留字节非 0；随机 32B decode 只返回错误不抛 |
| Block | 带 EXT 的块 `toBytes()`→`new Block(new XdagBlock(bytes))` 往返：`extFields` 顺序、类型掩码位置、hash 稳定、`signOut`+`verifiedKeys` 通过；`getBlockLinks()` 顺序；旧构造器签名行为不变 |
| 分片链 | 1 片；352B 整除；非整除；4096 片；4097 → TOO_MANY；seq 断裂；totalLen 不符；末片带 link；环（A→B→A）；非 CHUNK 块 → `NOT_A_CHUNK`；过旧分片 → `CHUNK_TOO_OLD`；`countLenient` 与 `assemble` 的费用契约 |
| 分类器 | 每 kind 一正例；`NONE`；EXTRA_LINK / MISSING_LINK；内联参数超长 |
| Builder | CALL 内联 256B 恰好装下、257B 拒绝；DEPLOY 新建/加入两种；字段预算超 16 返回错误 |
| 存储 | 点查 CRUD；`batchWrite` 原子（用注入异常的 KVSource 验证无半写）；`CODE`/`CODE_REF` 拆分后引用计数到 0 时两条 key 一起删、计数增减不重写 blob；`exportSnapshot` / `importSnapshot` / `stateHash` 往返 |
| 钩子端到端 | 真实 `Kernel` + RocksDB（`TemporaryFolder`）：DEPLOY(新链) → CALL×N（含格式错、分片费不足）→ 出主块 → 确认；断言 0x01/0x02/0x03/0x07/0x0C/0x0D/0x0E 的内容与**每 (chain, height) 从 0 递增的 index**；再一笔向金库的普通转账 → INVALID_FORMAT 记录 |
| 回滚对称（P3） | **一个确定性的分叉场景**（`ChainL1UnwindTest.reorgRemovesChainStateAndReapplyRestoresIt`）：主链上确认 DEPLOY + CALL，再从第 8 个主块处分出一条 24 块的更重分支把它们全部 unwind → `CHAIN_L1` 只剩 `META` 这一个 key、反向索引为空、**金库余额回到 0**；同样两个块在新分支上重新被 link/确认 → 在新高度上产生等价记录，key 数与回滚前逐个对上。外加 `ChainL1ProcessorTest.unapplyRestoresAnEmptyStore` 在处理器层直接验证 apply→unapply 之后 store 为空。**没有随机高度/深度的 reorg 属性测试**——原设计里那条（随机 3–8 个高度的块序列 + 随机 unwind 深度，逐字节比对全 KV）没有实现，记为 §12.2 G10 |
| 激活门控 | `activationHeight = MAX`：同一序列后 `CHAIN_L1` 为空且 `BlockInfo`/余额/nonce/fee 逐字节等于用普通转账替换 CALL 的对照序列 |
| 快照 | 导出→导入往返；篡改一个 value 后哈希不符拒绝；`snapshotHeight ≥ activation` 且目录缺失拒绝、`store == null` 拒绝；本地已有状态但无 `0xFF` 标记 → 拒绝；已有 `0xFF` 标记：同一快照 + 本地未漂移 → 幂等返回（`retryOfTheSameReseedIsIdempotentButDriftIsRefused`），本地已漂移 → 拒绝，换了一份快照 → 拒绝（`rebootstrapFromNewerSnapshotIsRefused`），目录缺失 → 拒绝（`markerPathRequiresTheSnapshotDirectory`）；`< activation` 忽略 |
| 回归 | 现有 50 个测试类全绿（JDK 21 + toolchains，见 build env 记忆） |

---

## 10. 总规格同步（与本文同一提交）

1. §5.2 CHUNK：13 字段/416B → **11 字段/352B**，须带 2 个全零 `SIGN_OUT`；100KB WASM ≈ 291 片。
2. §5.1 新增原则"EXT 不影响 L1 有效性"；§5.3 补分片链的**年龄规则**，并把 `maxChunksPerChain` 的容量更正为 ≈ 1.44 MB；§5.4 费用规则改为"导入期节点策略（SP0b）+ `applyBlock` 共识复核（不足 → `INVALID_FEE` 输入）"，并补"OUT 分片链 link 在 `getTxFee` 里算 output"导致的 `requiredValue` 规则；§5.5 硬分叉点收窄为系统划账与快照。
3. §6.1 增加 0x0C/0x0D/0x0E，代码库拆成 blob + 引用计数两条 key；§6.2 钩子表改为五钩子。
4. §12 与 §17：`maxCallGas` 补上 L1 DEPLOY 处强制的 `[1, 10_000_000]` 边界；§17 `maxChunksPerChain` 的容量数字更正为 ≈ 1.44 MB。
5. §18 SP0 拆为 SP0a / SP0b。
6. §20 新增三条：dev-evm 的 0x0F 码点冲突；快照节点按需拉取老分片的活性依赖；P3 的已知缺口（`unApplyBlock` 跳过 `ref == null` 的块、`BI_APPLIED` 与 CHAIN_L1 提交之间的崩溃窗口）。

---

## 11. 文件清单

**改动**：`core/XdagField.java`、`core/Block.java`（ext 字段、`getBlockLinks`、`setType` 预算断言）、`core/BlockchainImpl.java`（构造器装处理器 + 五处钩子 + 快照门）、`db/rocksdb/{DatabaseName, KVSource, RocksdbKVSource}.java`（`batchWrite`）、`config/{Config, AbstractConfig, DevnetConfig, TestnetConfig, MainnetConfig}.java`、`Kernel.java`（`ChainL1Store` 生命周期 + `chainKindHandlers` 注册表，二者都**必须在 `new BlockchainImpl(this)` 之前**就位）、`cli/XdagCli.java`（`makeSnapshot` 导出 `SNAPSHOT/CHAIN_L1`）。**不改任何 `src/main/resources/xdag-*.conf`**（§7 末尾）。
**新增**：`chain/ext/{ExtKind, ExtError, ExtResult, ExtCodec, Classified, CallExt, DeployExt, ChainConfigExt, ChunkExt, AnchorExt, BondExt, ChallengeExt, ClaimExt, ChunkChain, ChunkChainBuilder, ChainBlockBuilder, ChainBlockClassifier}.java`、`chain/l1/{ChainL1Hooks, ChainL1Processor, ChainL1Store, ChainL1Batch, ChainL1Keys, ChainIds, ChainKindHandler, ApplyContext, InputStatus, InputRecord, InputRef, ChainRecord, ContractRecord, ChainL1SnapshotGate}.java`、`chain/ChainActivation.java`、`config/spec/ChainSpec.java`；测试镜像目录（含 `chain/l1/ChainL1TestBase.java` 真实 `BlockchainImpl` + RocksDB 基座）。

---

## 12. 开放问题与已知缺口

### 12.1 开放问题

| # | 问题 | 处理 |
|---|------|------|
| Q1 | `addAmount` 对不存在的 OUTPUT 地址是否自动建档 | 计划首个任务确认；若否，DEPLOY(新链) 时建档并在 unwind 时删除"由本 DEPLOY 创建"的档 |
| Q2 | `Block` 主构造器参数已有 10 个 | 新增重载即可；不引入 builder 以免扩大改动面 |
| Q3 | 主块自身经过 `onBlockApplied` | **已关闭**：金库支付只认 `XDAG_FIELD_OUTPUT`，`COINBASE` 字段永远不算（§4.2）。主块的 coinbase 即使恰好指向某金库也不会产生记录 |
| Q4 | 既有 L1 回滚存在已知不对称（`unApplyBlock` 中 `allBalance` 回滚 try/catch） | 属性测试只比较链相关地址的余额/nonce；SP0a 期间确实发现了一处影响 P3 的不对称，见 12.2 的 G1，不在本 SP 修 |

### 12.2 已知缺口与后续工单（SP0a 不修）

| # | 缺口 | 影响与现状 |
|---|------|-----------|
| G1 | `BlockchainImpl.unApplyBlock` 会**跳过** `BI_MAIN_REF` 已置而 `ref == null` 的块（`setMain` 在 DFS 里抛异常、或走了 `mainBlockFee < 0` 的提前 `return` 时会留下这种块） | 该主块子块已提交的 `CHAIN_L1` 记录**永远不会被撤销**，滞留在一个不再确认它们的高度上。这是既有的**值结算不对称**（同样这些块的余额也保留着），不是 chain 层引入的；后果是 **SP1 不能无条件假设 P3 成立**。**SP0a 放大了它的触发面**：`CHAIN_L1` 是第二个 RocksDB，`applyBlock` 的 DFS 里现在会调 `store.commit`，而记录损坏是**故意的 fail-stop**（`InputRecord.decode` / `InputStatus.fromCode` 不 catch，见 §6.3），于是 DFS 里多了一类新的抛出点；`checkMain` 把这个异常吞掉，留下的主块已经是 `BI_MAIN` + 高度 + 奖励 + `nmain++` 全做完、但 `updateBlockRef` 没走到 → `ref == null` → **永远 unwind 不了**。SP0b 负责两件事：启动时的 `CHAIN_L1` vs `BLOCK` 一致性检查（见 G2），以及把 `ref == null` 这个跳过改成可修复的。**已关闭（SP0b-1）**：`BlockchainImpl.setMain` 现在在 `applyBlock` 的 DFS **之前**就 `updateBlockRef(block, self)`，`applyBlock` 对每个子块也在递归之前写 `ref = M`、子块被拒绝（−1）时还原为 null——DFS 中途抛异常留下的主块 `ref == self`，`unApplyBlock` 不再跳过它（`MainCompletionMarkerTest.failedSetMainLeavesRefOnSelfAndNoMarkerAdvance` / `childRefIsSetBeforeApplyAndRestoredByUnwind`，提交 5a82825a、63bca8e2）；旧库遗留的无 ref 主块由 `ChainRepairTool.patchStuckRefs` 补自引用后回滚（`ChainRepairToolTest.legacyStuckBlockIsPatchedAndUnwound`，提交 9e1c52b9）。接受的表面缺口：子块 ref 写入与其 `BI_MAIN_REF` 标志之间崩溃会留下一个非 null 的陈旧 ref，直到被覆盖；`unApplyBlock` 以 `BI_MAIN_REF` 为准，不受影响 |
| G2 | 崩溃一致性：`BI_APPLIED` 在 `CHAIN_L1` 提交**之前**就已急切落盘，两个库之间没有原子性，也没有启动时重放 | 两者之间崩溃会丢掉那个块的 chain 记录，而块仍被标为已应用，除非深度 reorg 把它 unapply 再 apply，否则不会重新触发 `onBlockApplied`。这与 `ADDRESS` / `BLOCK` 两个库之间本来就不原子是同一档次的问题，**接受**；SP0b 或快照工具可以在启动时加一道 `CHAIN_L1` vs `BLOCK` 的一致性检查。**已关闭（SP0b-1）**：不做 `CHAIN_L1` vs `BLOCK` 的逐块比对，而是给主链一个节点本地的完成标记——`BlockStore.LAST_COMPLETED_MAIN`（INDEX 键 `0xb0`，大端 long）只在 `setMain` / `unSetMain` 正常结束时写，`MAIN_IN_FLIGHT`（`0xc0`，op 字节 1 = setMain / 2 = unSetMain + 大端高度）在两者入口作为第一条 INDEX 写、正常结束时清，且每次写标记之前先 `saveXdagStatus`（标记永不跑到已持久化的 nmain 前面）；`ChainConsistencyCheck`（`io.xdag.chain.repair`）在 `BlockchainImpl` 构造器里按四条规则（in-flight、标记落后、统计之上有主块、窗口内无 ref）只读检查，命中即抛 `IllegalStateException`（`Kernel.enterRepairMode()` 下只记录），`--repairchain` 离线回滚到最后一个完整高度（`MainCompletionMarkerTest`、`ChainConsistencyCheckTest`、`ConsistencyGateTest`、`ChainRepairToolTest`、`RepairChainCommandTest`；提交 5a82825a…e19e4cb7，见 SP0b-1 规格 §3.2–§3.4）。两库之间不原子这一点本身仍然成立：标记是 INDEX 内的进度标记，不是跨库提交记录（`BlockStore#getLastCompletedMain` / `#getMainInFlight` 的 Javadoc 写明了它证明什么、不证明什么） |
| G3 | `Kernel.testStart` 没有 try/catch | `CHAIN_L1` 打开之后如果构造失败会泄漏这个列族——与其它每一个存储一样，既有问题。**已关闭（SP0b-2 T4，提交 73fb50ec / f3dc00f7 / 298d5e28）**：`testStart` 里从"打开数据库"起的全部装配包进 `try { … } catch (RuntimeException | Error e)`，失败时按**正常关机顺序**先 `stopServices()`（先停线程再动库：在还跑着 P2P/RPC/check-main 的情况下关 RocksDB 比泄漏更糟），再 `blockStore.flushSums()`（sums 是批量写回的，不然创世导入已经算出来的那些会白丢）、`chainL1Store.stop()`、`dbFactory.close()`（`WriteBehindFactory.close()` 会先 flush 再停那条**非 daemon** 的写入线程——SP0b-2 之后这已经不只是"泄漏一个列族"，而是泄漏一条让 JVM 退不出去的线程），沿途异常挂成 suppressed，最后原样重抛。同时 `isRunning.set(false)`：否则之后的 `testStop()` 会越过它的守卫在半成品上 NPE，而重试的 `testStart()` 又会被当成"已在运行"直接返回。**残留**：没有任何测试驱动 `Kernel.testStart` / `testStop`，这条失败路径只由代码与注释保证（SP0b-2 规格 §4.2 第 1 条） |
| G4 | `Kernel` 以 `(INDEX, BLOCK, TIME, TXHISTORY)` 调 `BlockStoreImpl(index, time, block, txHistory)`——`time` 形参拿到的是名为 `BLOCK` 的库，`block` 形参拿到的是名为 `TIME` 的库 | **这不只是“目录名会误导”，而就是每一个已运行节点的磁盘布局**：原始区块字节落在名为 `TIME` 的目录，时间索引落在名为 `BLOCK` 的目录（`RocksdbFactory` 按库名挂的 9 字节定长前缀器也因此挂在了原始区块库上）。改正参数顺序等于抛弃所有现存节点的数据，重命名目录需要一次数据迁移，两者都不在 SP0a/SP0b 范围内。**现已将这一布局收拢为唯一入口 `BlockStoreImpl.forNode(DatabaseFactory)`**：`Kernel.testStart`、`XdagCli.repairChain` 与所有测试基座（`ChainL1TestBase` 等）均经此开库，**任何离线工具必须用它而不是构造器**（按签名顺序开库会两个库互换，`getBlockByHash(…, true)` 找不到原始字节，SP0b-1 的 `--repairchain` 曾因此全量报 `INCOMPLETE_BLOCK_DATA_REASON`）。`XdagCli.makeSnapshot` 本来就把名为 `TIME` 的库当作 `blockSource`，与节点布局一致；`BlockStoreWiringTest` 钉住该布局，防止有人静默“修正”参数顺序。**已知不对称（SP0b-1 记录，不改）**：`XdagCli.makeSnapshot` 用 `new RocksdbKVSource(DatabaseName.TIME.toString())` 直接开这个库，前缀提取器长度为 0，而 `RocksdbFactory` 给同名库挂的是 9 字节定长前缀器——只有**目录选择**与 `forNode` 一致；快照扫描只做点查（`get`），不受前缀器影响，故不修（`makeSnapshot` 里的注释写明了这一点） |
| G5 | `BlockchainImpl` 在构造器里起了非 daemon 的 `rollBackLoop` / cleaner 线程，`stopCheckMain()` 从不停它 | 既有问题；测试基座每个测试泄漏一个线程。**已关闭（SP0b-1 T5）**：`stopCheckMain()` 现经 `stopCleaner()` 停掉 cleaner；`--repairchain` 依赖它退出（提交 3877c1b5）。**SP0b-2 补上了另一半（I4，提交 f3dc00f7 / 298d5e28）**：`OrphanBlockStoreImpl` 也有一个自己的非 daemon 调度线程（孤块清理器，每 300 s 一拍，会写 ORPHANIND 与统计），以前从来没人停它。现在 `OrphanBlockStore.stop()` 先 `cleaner.shutdownNow()` 再等 5 s（`shutdownNow` 只发中断，正在写的那一拍必须等它出来）然后关自己的 source，并且在 `Kernel.stopServices()` 里**紧挨着 `blockchain.stopCheckMain()`** 调用——库关掉之后再来一拍会写进关掉的 RocksDB 并在出门时毒掉写后队列，记一次根本没发生过的落盘失败。清理器的主体同时被放进 `synchronized (blockchain)`（C1） |
| G6 | `onBlockUnapplied` 即使在完全没有 chain 活动的网络上，也会对每个被 unapply 的块做一次 classify + 反向索引点查 | 纯噪声（unwind 本来就罕见），不修 |
| G7 | `XdagCli.copyFile` 把 `IOException` 吞成 `printStackTrace()`（既有） | `copyDir` 里一个文件拷贝失败/截断是静默的，运维可能分发一份不完整的 `SNAPSHOT/*`；`CHAIN_L1` 会在导入时被哈希校验拦住，但那已经是分发之后。应改为抛 `IllegalStateException`。**已关闭（SP0b-1）**：`XdagCli.copyFile` 捕获 `IOException` 后改抛 `IllegalStateException("snapshot copy failed: <src> -> <dst>", e)`，`copyDir` 传播；`makeSnapshot` 把 `SNAPSHOT/ADDRESS` 的拷贝失败报成 `address snapshot NOT written: …`、继续导出 `CHAIN_L1`、照常打印高度与 next start frame，返回 `false` → `--makesnapshot` 退出 1，末行指明删除**整个** `SNAPSHOT` 目录后重跑（`CopyDirFailureTest`、`MakeSnapshotEndToEndTest.anAddressCopyFailureIsReportedAndMakesTheSnapshotNotBootable`；提交 224f1c40、de60672b）。**残留**：`SnapshotStoreImpl.makeSnapshot` 在扫描内部仍吞异常，CLI 只能以“高度为 0”判定不可启动（`block snapshot NOT written`） |
| G8 | `RocksdbKVSource.init()` 打开失败时泄漏 native `ReadOptions`（既有） | `ChainL1SnapshotGate` 已把 `init()` 放进 `try`，但泄漏本身在 `RocksdbKVSource` 里。**已关闭（SP0b-1）**：`RocksdbKVSource.init()` 的 `ReadOptions` 现在在 `try` 内创建、失败路径 `finally` 释放，`alive` 保持 false 且对象可再次 `init()`（`RocksdbInitFailureTest.failedOpenLeavesSourceDeadAndReusable`；提交 224f1c40、de60672b）。**残留**：`init()` 里 `new LRUCache(32 MiB)` 与 `new BloomFilter(10, false)` 这两个 native 对象从未释放（成功与失败路径皆然），不在本 SP 范围 |
| G9 | `XdagCli.makeSnapshot` 的 `CHAIN_L1` 导出接线没有端到端测试 | 只有 `ChainL1SnapshotTest` 直接测 `ChainL1SnapshotGate.export`；`makeSnapshot` 整体（含 BLOCKS/ADDRESS）无测试。**已关闭（SP0b-1）**：`MakeSnapshotEndToEndTest.makeSnapshotWritesThreeDirectoriesThatBootAnotherNode` 在基座上跑真实的 `XdagCli.makeSnapshot(false)`，断言三目录存在、输出含高度与 next start frame，且**内容按启动路径实际读取的方式核验**：`SNAPSHOT/BLOCKS` 用启动同款 `SnapshotStoreImpl.saveSnapshotToIndex` 灌进第二个节点的块库后顶主块可按高度读到（启动就是不加保护地解引用这一读）、`SNAPSHOT/ADDRESS` 经 `AddressStoreImpl` 打开后矿工余额相等、`SNAPSHOT/CHAIN_L1` 记录的哈希等于源库 `stateHash()` 且第二个节点 `checkAndImport` 后相等；`makeSnapshot` 现返回 boolean（ADDRESS / BLOCKS / CHAIN_L1 任一不可启动 → `--makesnapshot` 退出 1），并在 `finally` 关闭 INDEX / TIME / SNAPSHOT/BLOCKS 三个句柄（提交 ace94fb2、3709e43e、de60672b）。**残留（既有，只记录）**：`-f <dir>` 只移动 store，`setDir()` 不派生 `walletFilePath` |
| G10 | reorg 只有 `ChainL1UnwindTest` 那一个确定性场景（24 块分叉）覆盖，**没有随机高度/深度的 reorg 属性测试** | 原设计的属性测试（随机 3–8 个高度的块序列 + 随机 unwind 深度，`apply→unwind→apply` 后 `CHAIN_L1` 全 KV 逐字节等于直接 apply，并比对涉及地址的余额与 nonce）留给 SP0b：确定性场景只能证明"这一条路径对称"，证不了"任意深度都对称"，而 P3 正是 SP1 要依赖的前提（另见 G1：P3 本身已知有缺口）。**已关闭（SP0b-1）**：`ChainL1ReorgPropertyTest`（场景由 `ReorgScenario.generate(seed)` 生成，纯数据）——**形态与原设计不同**：原设计“分叉后 `CHAIN_L1` 只剩 META、再在新分支上重新链接”不可行（`confirm()` 在非最佳分支上断言 BEST/APPLIED 会失败，且高度整体偏移），改为**同高度分支重放**：竞争分支在相同高度重新 link 同一批付费块、每高度主块数也相同；性质 = reorg 后 `CHAIN_L1.stateHash()` == 分叉前 == 新基座直接 apply，外加发送方余额/nonce、金库余额、挖矿密钥余额与 `BI_MAIN` / `BI_APPLIED` 标志；默认跑种子 1–2，`-Dxdag.reorg.full=true` 跑 1–8，`-Dxdag.reorg.seeds=a,b` 追加，每条断言消息带场景（提交 ba91968a、5a698579）。**该性质的余额半边找出了一处既有的 unwind 费用不对称，见 G11** |
| G11 | **既有** `BlockchainImpl.unApplyBlock` 反向划账的费用除数错误（由 G10 属性测试的余额比对发现）：`applyBlock` 给每个 `XDAG_FIELD_OUTPUT` 地址 link 记 `amount − L`（`L = outPutLimit(block) = max(MIN_GAS, getTxFee / outputs)`，其中 `outputs` 把 `XDAG_FIELD_OUT` 块 link 也算进去——§3.5 与本地文档早已记录 `outPutNum` 的这一计数）并持久化 `info.fee = k·L`（k = OUTPUT 地址 link 数）；`unApplyBlock` 却按 `fee / outPutNum(block) = k·L/(k+m)` 扣回（m = OUT 块 link 数） | 任何同时带 OUT 块 link 与 OUTPUT 地址 link 的块——每个带代码链的 chain DEPLOY、每个带参数链的 CALL（k = m = 1）——在 unwind 时每个 OUTPUT 被多扣 `L·m/(k+m)`（即 `L/2`）；金库被扣到负数时 `subtractAmount` 吞掉异常、静默跳过。传统钱包/矿池转账（m = 0）不受影响。**已关闭（SP0b-1，提交 79705de6）**：unwind 改为按持久化费用扣回 `amount − info.fee / k`（nano 精确；m = 0 时与旧算法逐字节相同；`outPutLimit` 在 unwind 时也可用，但持久化费用是“实际收了多少”的真相，将来费用规则改动也不会把新规则套到旧块上）；回归 `ChainL1UnwindFeeTest`（chain 块回到精确的交易前余额与空金库、普通转账逐字节不变），`ChainL1ReorgPropertyTest` 种子 1–8 全绿。这是一个**与共识相关的节点本地修复**（reorg 后的余额）：不同代码的节点在一次涉及 chain 块的 reorg 之后会分歧。**升级须知（三条）**：(i) 余额存在 `AddressStore`、不进任何块哈希——修复前所有节点是“错得一致”的确定性结果，不分叉 DAG，故本修复随分支无条件生效，不设激活高度；(ii) **不修复既有存储**——在旧代码上 unwind 过 chain 块的节点留着一个静默偏低的发送方余额（或偏高的金库），余额又是 `applyBlock` 余额不足判定的输入，这样的节点以后可能拒绝一笔同行接受的交易 → `BI_APPLIED` 标志与 `CHAIN_L1` stateHash / 快照哈希分歧；处置 = 见过这种 reorg 的 devnet/testnet 存储重新同步或从快照重灌；(iii) **不只影响 chain 合约**——xdagj 的 `createNewBlock` 从不发 OUT link，但 `tryToConnect` 也不禁止外部客户端把 `XDAG_FIELD_OUT` 与 `XDAG_FIELD_OUTPUT` 合在一个块里，这类外来块的 unwind 同样改变。**残留（不改）**：主块自身若是传统交易块，`setMain` 会把 `info.fee` 持久化为 `gasCollected`（子块费用之和）而不是 `k·L`，新旧除数都不能精确反向；当前没有代码路径会造出这种块（`createMainBlock` 不发 `XDAG_FIELD_IN` / `XDAG_FIELD_OUTPUT`）。另：`ChainRepairTool.finishInterruptedUnwind` 在 `unSetMain` 之前先把 `info.fee` 从库里恢复（`Block.parse()` 会用头部费用覆盖它），与 `unWindMain` 一致（提交 5a698579） |
