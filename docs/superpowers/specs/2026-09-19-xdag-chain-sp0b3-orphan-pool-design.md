# SP0b-3：孤块池分层、配额、TTL 与导入期费率策略 — 设计

> 定位：本文是 SP0b-3 的实施级设计。基线为 `dev-dag-contract` HEAD `0b0c513d`（SP0b-2 竣工 + 五路审计的十五个修复）。总规格 `2026-09-13-xdag-chain-contracts-design.md` 与路线图 `2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md` §5.3 是上位文档；**本文与路线图 §5.3 有六处实质偏离，逐条记在 §8**，以本文为准。

**一句话目标**：让垃圾分片洪泛无法饿死账户交易，让孤块移除的代价不再随池子变大而线性上升，并且不引入任何共识分歧。

---

## 1. 现状与路线图的六处不符

路线图 §5.3 写于 2026-09-17，SP0b-1、SP0b-2 与审计修复都在其后落地。下列事实经代码核对，与 §5.3 的描述不符，直接改变方案形状。

### 1.1 代码里有两个互不相交的孤块池

| | 池 A `BlockchainImpl.memOrphanPool` | 池 B `OrphanBlockStoreImpl` |
|---|---|---|
| 声明 | `BlockchainImpl.java:141` | `Kernel.java:224`，列族 `ORPHANIND` |
| 装什么 | 完整 `Block` 对象（活的、可变） | 只有 34 字节键 / 36 字节值的元数据 `OrphanMeta` |
| 装谁 | **恰好是 `BI_EXTRA` 挖矿块**（`BlockchainImpl.java:732-735`） | **其余全部**（`:736-739` → `dealOrphan` → `addOrphan`） |
| 落盘 | 否，被引用时才写；被淘汰时直接丢弃不写 | 是，ORPHANIND 行 + `ORPHAN_SIZE` 计数，启动重建 |
| 上限 | `MAX_ALLOWED_EXTRA = 65536`（`Constants.java:68`） | `MAX_ORPHAN_SIZE = 3750`（`BlockchainImpl.java:98`） |

一个块只会在其中一个里（`:732` 的 if/else）。

路线图说「`ChainOrphanPool` 替代 `memOrphanPool` 的单一 `LinkedHashMap`：按类别分队列——账户交易 / 分片块 / 其它 link 块」。但 `memOrphanPool` 里**一个账户交易、一个分片块都没有**。账户交易与分片块都在池 B，而池 B 已经是分队列的。替换池 A 不会碰到分片块。

路线图还把两个池的两个上限当成同一个（「`orphanPoolLimit` 100,000 替代 `MAX_ORPHAN_SIZE = 3750`」，而被替换的 `LinkedHashMap` 实际由 `MAX_ALLOWED_EXTRA = 65536` 约束）。

**本文的目标是池 B。** 池 A 不在 SP0b-3 范围内。

### 1.2 池 B 的既有队列与路线图给的三类不对应

| 路线图类别 | 现状 |
|---|---|
| 账户交易 | **有**，`accountTxMap`（`:65`）与 `vipTxMap`（`:69`），两层，都按地址分桶 |
| 分片块 | **没有**，池 B 里没有任何分片感知 |
| 其它 link 块 | `linkQueue`（`:56`）存在，但它不是「其它」——它是一切非交易块的兜底，分片块就住在里面 |

还有路线图未提及的第四条队列 `mtxQueue`（`:60`，主块可用的 `XDAG_FIELD_IN` 交易）与一条 `mainRef`（`:72`，已交出去的候选，供再次提供或被 link 块消费）。

完整的内存集合为七件：`linkQueue`、`mtxQueue`、`accountTxMap`、`vipTxMap`、`orphanInsertTimeMap`、`mainRef`、`accountNonce`。

### 1.3 饿死今天就是活的，而且有两种形态

**形态一，打包路径。** `selectBlocks`（`OrphanBlockStoreImpl.java:413`）在 `isMain == true` 时的取用顺序是 `mainRef` → `vipTxMap`（≤6 条）→ `linkQueue`，而 `linkQueue` 那一支在非空时**无条件 `return`**（`:462`），于是 `mtxQueue` 与 `accountTxMap` 的合并段只有在 `linkQueue` 为空时才走得到。分片块正住在 `linkQueue` 里，所以**今天分片块就排在普通账户交易前面进主块**。

**形态二，准入路径。** `MAX_ORPHAN_SIZE = 3750` 的唯一执行点是 `BlockchainImpl.java:471`：

```java
if (isAccountTx(block) && orphanBlockStore.getOrphanSize() >= MAX_ORPHAN_SIZE) {
```

`getOrphanSize()`（`:531`）是四条队列之和。所以这是**全局计数，却只在账户交易进来时检查**：link、分片、mtx 三类往里灌不受任何限制，一旦总数越过 3,750，账户交易开始收到「孤块池已满」，而灌满池子的那些块一个都没被拦。

路线图那句「账户交易队列保留原上限语义」没有对应物——今天不存在按队列的上限，3,750 也不是账户交易队列的上限。

### 1.4 移除是线性的，抬配额会让锁内更慢

| 集合 | 类型 | `remove(Object)` |
|---|---|---|
| `linkQueue` | `PriorityBlockingQueue` | O(n) |
| `mtxQueue` | `PriorityBlockingQueue` | O(n) |
| `mainRef` | `ConcurrentLinkedDeque` | O(n) |
| `vipTxMap` / `accountTxMap` 的桶 | `PriorityBlockingQueue` | `contains` + `remove`，两次 O(n) |

`deleteFromQueue`（`:238`）还要先把 `Block` 与若干字段拼成键值字节、再 `OrphanMeta.parse` 解回来，然后按身份在队列里线性查找。整段跑在区块链监视器内、单一提交线程上。

路线图 §5.2.1 已把孤块池增删列为锁内 ≥84% 时间的头号嫌疑之一。按现状把上限从 3,750 抬到 100,000，是把这条路径放慢约二十七倍。

### 1.5 分片块不携带 chainId，按 chainId 的配额在准入时刻不可计算

`ChunkExt` 的字段是 `(seq, totalLen, dataLen, next, data)`（`ChunkExt.java:57`）——没有任何目标链身份。chainId 在引用链头的那个付费块上。分片块进入孤块池时付费块通常尚未到达；等付费块到了，该分片块已经要离开池子，此时再限额没有意义。

### 1.6 分片块今天导入即落盘；TTL 是一视同仁的本地十五分钟

落盘发生在 `BlockchainImpl.java:737` 的非 `BI_EXTRA` 分支，分片块与其它 link 块走同一条路，没有任何费率检查（`tryToConnect` 按 P1 刻意与 chain 协议无关）。

TTL 是 `cleanExpiredOrphans(15 * 60 * 1000L)`（`:117-119`），对所有类别一视同仁，计时起点是**本节点收到的墙上时钟**（`:315`），且 `rebuildMemoryFromDb` 在重启时把每一条重新盖成「现在」，等于重启即重置。

---

## 2. 池的分层与准入配额

### 2.1 分层

新增 `io.xdag.chain.orphan.ChainOrphanPool`，纯内存，不认识 RocksDB：

- 分类队列（§3.1）
- 一张 `hashlow → 条目` 索引
- 配额计数（全局 / 按来源 peer / 按分片链）
- TTL 时钟（§4）
- 未落盘分片块的块体存储（§4.3）

`OrphanBlockStoreImpl` 保留 ORPHANIND 行读写、`ORPHAN_SIZE` 计数与启动重建，把全部内存决策委托给 `ChainOrphanPool`。`OrphanBlockStore` 接口不变。`getOrphan` 的监视器语义与 `811deec0` 建立的锁序（区块链 → 孤块队列）原样保留。

启动重建的顺序不变：清空可重建集合 → 重放每一条 `0x00` 前缀的 DB 行 → 灰入池。注意现状的 `accountNonce` 不在清空之列（`:97-102`），本次保持该行为，不在 SP0b-3 内改动。

### 2.2 配额三层

| 层级 | 键 | 准入时可算 | 配置键 | 默认 |
|---|---|---|---|---|
| 全局 | 无 | 是 | `chain.orphan.poolLimit` | 100000 |
| 按来源 peer | `BlockWrapper.remotePeer` | 是 | `chain.orphan.chunkPerPeer` | 5000 |
| 按分片链 | 链头 hashlow | 是 | `chain.orphan.chunkPerChain` | 20000 |

第三层由路线图的「付费块目标 chainId」改为「分片链链头」，理由见 §1.5。它拦的是同一个攻击面——单一来源用大量互不相关的分片链灌满池子——但只依赖分片块自身携带的信息。链头由沿 `next` 归组得到；无法归组到已知链头的分片块归入「无主」桶，与「无 peer」同等处理，受全局与 peer 两层约束。

按 peer 与按分片链的配额**只作用于分片块**。账户交易、mtx 与其它 link 块只受各自的类别上限（§2.3）与全局上限约束。

### 2.3 类别上限取代单一全局闸门

`BlockchainImpl.java:471` 的「全局计数 + 只查账户交易」改为每类各有上限：

| 类别 | 配置键 | 默认 | 说明 |
|---|---|---|---|
| 账户交易（含 VIP） | `chain.orphan.accountTxLimit` | 3750 | 保持今天账户交易的有效准入行为 |
| 主块交易 mtx | `chain.orphan.mtxLimit` | 3750 | |
| 分片块 | `chain.orphan.chunkLimit` | 60000 | 另受 peer / 分片链二级配额 |
| 其它 link 块 | `chain.orphan.linkLimit` | 30000 | |

任一类别打满只拒绝该类别的新块，不影响其它类别。全局 `poolLimit` 作为兜底，任何类别都不得让总数越过它。四项之和（97,500）刻意略低于全局上限，使全局兜底只在配置被调大后才生效。

### 2.4 peer 身份的管线

`BlockWrapper` 已持有 `remotePeer`（`BlockWrapper.java:45`），`PreValidated` 已持有 `BlockWrapper`（`PreValidated.java:51`），`tryToConnect(PreValidated)`（`BlockchainImpl.java:435`）收的就是它。缺的只是最后一跳：把来源与分类从 `tryToConnect` 传给 `dealOrphan` / `addOrphan`。

非管线路径 `tryToConnect(Block)`（`:408`）与本地产生的块（挖矿、RPC、命令行）没有来源 peer，按「无归属」处理，不计入任何 peer 配额。这是正确语义，不是降级。

分类（`ExtKind`）同样由 `PreValidated.classified` 提供；非管线路径沿用 `PreValidator.inline` 的分类结果。池不自己调用分类器。

---

## 3. 队列结构、移除复杂度与打包顺序

### 3.1 有序集合取代优先队列

今天那三组比较器都以 hashlow 字典序收尾，本身已是全序。因此队列改为 `TreeSet<OrphanMeta>` 加一张 `hashlow → OrphanMeta` 索引：

- 队头 = `first()`
- 按条目移除 = `remove(meta)`，O(log n)，精确
- `size()` 精确——这点要紧，准入闸门读的就是它

排序规则一字不改：`linkQueue` 按时间升序再 hashlow；`mtxQueue` 按费用降序、时间升序、再 hashlow；每地址桶按 nonce 升序、时间升序、再 hashlow。

**不采惰性墓碑。** 它把移除做成 O(1)，但 `size()` 变成近似值而准入判断依赖精确计数，且需要额外的压实触发器。以对数换精确，值得。

`deleteFromQueue` 不再绕序列化形式走一圈：由 hashlow 直接查索引拿到条目再摘除。

### 3.2 去掉并发集合

`811deec0` 之后，池 B 的每一个访问点都持有区块链监视器：`addOrphan`/`removeOrphan` 经由 `synchronized tryToConnect`，`getOrphan` 自己取，`cleanExpiredOrphans` 已被放入。池 B 只存元数据，没有锁外读取点（锁外读的是池 A 的块体，那是另一个池）。

因此内部集合全部改为非并发实现（`TreeSet` / `HashMap`），由监视器保护。省掉的 CAS 流量本身就是锁内时间。

**例外**：§4.3 的未落盘分片块块体存储**会**被锁外读（`getBlockByHash` 在 netty 线程上被调用），它必须独立地保证并发读安全，见 §4.3。

### 3.3 打包顺序：反转优先级，不是禁止

路线图 §5.3 第 5 条写「主块打包只从账户交易队列取」。若照字面执行，`linkQueue` 就只剩 `createLinkBlock` 消费，而后者由 `checkOrphan` 按 `nnoref / 11` 驱动；在没有账户交易的安静节点上主块将几乎不带引用，`nnoref` 要等 link 块慢慢追平。

改为反转优先级：

| 路径 | 取用顺序 |
|---|---|
| 主块（`isMain = true`） | `mainRef` → VIP（≤6）→ 账户交易 + mtx 合并 → `linkQueue` 填满剩余名额 |
| link 块（`isMain = false`） | `mainRef`（消费式移除）→ `linkQueue` |

`:462` 的无条件提前返回删除。账户交易与 mtx 永远优先，`linkQueue` 只填剩余名额：饿死消失，链接吞吐不减。

`mainRef` 的现有差异保留：`isMain` 时只提供不移除，`!isMain` 时消费式移除。

---

## 4. TTL、延迟落盘与共识安全边界

### 4.1 年龄规则是安全性的来源

`ChunkChain` 的年龄规则是 `minEpoch = XdagTime.getEpoch(付费块时间戳) - 1`，即「与付费块同纪元，或紧邻的前一纪元」（`ChunkChain.java:76-88`，判定在 `:145` 与 `:210`）。`ChainL1Processor` 在 `setMain` 的 `assemble`/`chainCount` 调用一律带该界，并明文禁止使用无年龄界的三参重载（`ChainL1Processor.java:80-89`）。

这条规则存在的理由正是分歧防护：快照启动的节点没有快照前区块的原始字节，而 `tryToConnect` 的无父检查只看 `BlockInfo` 即满足，没有年龄界时两个诚实节点会对同一条链能否组装得出不同结论。

**推论**：纪元 N 的分片块只能被纪元 N 或 N+1 的付费块合法引用，最后一刻是 N+1 结束。任何更晚的引用都会被年龄规则拒绝，**与本节点手上有没有那几个字节无关**。因此只要保留覆盖到 `chunk 纪元 + 1` 的末尾，淘汰就不可能改变任何共识结论。

### 4.2 分片块 TTL

- 计时量改为**块自身时间戳所在纪元**，不用本地墙上时钟。重启后结论不变（今天的 `rebuildMemoryFromDb` 会重置本地计时，换成共识相关的 TTL 后那将是错误）。
- 淘汰条件：`当前纪元 > chunk 纪元 + chunkTtlEpochs - 1`，判定取保守侧（宁可多留一个纪元）。
- `chain.orphan.chunkTtlEpochs` 默认 2，**配置校验强制下界 2**，只许往上调。低于 2 会让本节点拒绝其它节点接受的链，那是共识分歧，必须在启动时 fail-fast 而不是靠文档约定。

非分片块的孤块保持今天的十五分钟本地时钟 TTL：它们没有协议年龄界，不需要也不应该改。

### 4.3 延迟落盘与块体存储

分片块不再在 `BlockchainImpl.java:737` 无条件落盘；改为被付费块引用（付费块 `IMPORTED_*`）后才 `saveBlock`。未被引用的分片块只在内存。

**这需要一个路线图未计入的内存块体存储。** 池 B 只存元数据，装不下块体。块体存储由 `ChainOrphanPool` 持有，容量由 §2.2 / §2.3 的分片配额封顶，最坏约 51 MB（六万条 × 512 字节 + 索引开销）。

**查找路径必须合并，且这条路径在共识上。** `ChainL1Processor` 使用的查找是 `BlockchainImpl.java:324` 的 `hash -> getBlockByHash(hash, true)`，今天先读池 A 再读块存储。分片块不再进块存储后，`getBlockByHash` 必须也读块体存储。约束两条：

1. `getBlockByHash` 被 netty 线程调用（P2P 服务路径），块体存储必须安全地支持锁外读。`a51e09c5` 刚在这条路上修过并发读问题，同样的教训适用：读路径不得依赖只有监视器持有者才成立的不变式。
2. 合并后的查找必须与今天逐字节等价，用属性测试钉死（§7）。

顺带的好处：持有但未落盘的分片块仍可回应对端的块请求。按 `a51e09c5` 的结论，对外服务一律发从原始字节重新解析的副本，不发池内实例。

---

## 5. 导入期费率策略

节点本地策略，默认开启，`chain.ingest.feePolicy=false` 关闭。关闭后 §2–§4 全部独立生效。

### 5.1 判定

付费块在进入摄入之前，用 `ChunkChain.countLenient` 对其链头计数（锁外，读孤块池与块存储），`headerFee < chunkFee × count` 则拒绝，返回 `ImportResult.CHAIN_FEE_POLICY`，不落盘、不进 DAG、不转发。

`chunkFee` 取 `chain.chunk.feeMilliXdag`（已有键，默认 10 mXDAG）。计数上界取 `chain.chunk.maxPerChain`（已有键，默认 4096）。

### 5.2 被请求的块必须豁免（硬性）

路线图的安全论证是「被拒的块从未进入本节点 DAG，主块到达时会按需拉取」。但拉回来的块同样要过 `tryToConnect`：若策略照样生效，它会被再次拒绝，节点将永远拉不进那个块，停在该高度无法同步。

因此策略**只作用于未经请求的广播到达**。凡本节点主动请求回来的块一律放行：同步拉取与广播走不同入口，且 `BlockWrapper` 已有 `isOld` 标志，豁免有现成抓手。实现必须以「是否为本节点请求」为准，不得以块的内容或来源为准。

此条路线图未写，但它是该方案能否成立的前提。

### 5.3 放置位置：摄入边界，不进 `PreValidator`

SP0b-2 的 P1 要求预验证只能加速通过、不能提前拒绝，而本策略恰是基于锁外读的提前拒绝。P1 管的是「流水线与同步路径必须给出相同 `ImportResult`」；费率策略是节点本地的、故意改变结果的。

只要它作为一道闸门**平等地挡在两条摄入路径前面**，而不是塞进流水线内部，P1 仍然成立。路线图「付费块进入锁前」的表述在实现上必须落为摄入边界。

### 5.4 误差方向是良性的

付费块先于其分片块到达是正常顺序。彼时 `countLenient` 数不全，`chunkFee × count` 偏小，`headerFee < 偏小阈值` 更不易成立——**漏数导致放行，不是误拒**。

反向地，`countLenient` 的契约是「不会少数，可能多数」（`ChunkChain.java:90-99`），多数只发生在 `assemble` 会拒绝的畸形链上，那种链本来就要在 `setMain` 被判无效，提前拒绝没有损失。

### 5.5 新增 `ImportResult` 变体的既有分支

`CHAIN_FEE_POLICY` 表达「本节点不想要」，不是「这个块坏了」。必须逐一核对 `SyncManager` 对 `INVALID_BLOCK` 的既有处理与所有相等判断/switch，确保没有路径把它当成非法块去做惩罚性动作（断连、计分、加入黑名单等）。

---

## 6. 配置键

全部节点本地。按既有约定**不写入任何 `.conf` 文件**，只在 `ChainSpec` / `AbstractConfig` 给默认值。

| 键 | 默认 | 校验 |
|---|---|---|
| `chain.orphan.poolLimit` | 100000 | > 0；≥ 四项类别上限之和 |
| `chain.orphan.accountTxLimit` | 3750 | > 0 |
| `chain.orphan.mtxLimit` | 3750 | > 0 |
| `chain.orphan.chunkLimit` | 60000 | > 0 |
| `chain.orphan.linkLimit` | 30000 | > 0 |
| `chain.orphan.chunkPerPeer` | 5000 | > 0；≤ `chunkLimit` |
| `chain.orphan.chunkPerChain` | 20000 | > 0；≤ `chunkLimit` |
| `chain.orphan.chunkTtlEpochs` | 2 | **≥ 2，启动 fail-fast** |
| `chain.ingest.feePolicy` | true | — |

---

## 7. 测试策略

### 7.1 基准（硬性前置，先于任何行为改动）

路线图 §5.2.1 第 4 条约定孤块池部分的 `phase.*` 基准行归先动手的子项目负责。SP0b-2b 未开工，此债归 SP0b-3。

在动任何代码之前，给 `L1ImportBenchmarkTest` 增加 `phase.orphan.add` 与 `phase.orphan.remove` 两行，并在改造前后各跑一次写入 `docs/benchmarks/2026-09-19-orphan-pool.md`。现有基准只有 `phase.parse` / `phase.signature` / `phase.refLookup` / `phase.persist` 四行，没有任何一行能拆开锁内那 ≥84%。

### 7.2 功能与对抗

| 测试 | 断言 |
|---|---|
| 垃圾分片洪泛 | 分片块打满 `chunkLimit` 后，账户交易仍能导入；`INVALID_BLOCK "Orphan block pool is full"` 不再因非账户交易而触发 |
| 单 peer 洪泛 | 单一来源的分片块被 `chunkPerPeer` 截断，其它 peer 的分片块不受影响 |
| 单链洪泛 | 单一分片链被 `chunkPerChain` 截断，其它链不受影响 |
| 打包公平性 | 账户交易与分片块同时在池中时，主块先取账户交易；`linkQueue` 只填剩余名额；link 块仍能消费 `linkQueue` |
| TTL 淘汰后被引用 | 分片块被 TTL 淘汰后其付费块到达 → 链组装失败 → 与年龄规则的判定一致（两个节点结论相同） |
| TTL 下界 | `chunkTtlEpochs = 1` 启动即失败并给出原因 |
| TTL 重启稳定 | 重启后分片块的剩余寿命不被重置 |
| 查找等价 | 合并后的 `getBlockByHash` 对任意块序列与改造前逐字节相等 |
| 延迟落盘 | 未被引用的分片块不在块存储；被付费块引用后出现在块存储；P2P 仍能服务未落盘的分片块，且发的是副本 |
| 费率策略开/关 | 两种设置下最终 DAG 内容一致（被拒块经拉取进入） |
| 费率策略豁免 | 被拒的块在本节点主动请求回来时必须被接受；构造「策略开启 + 主块引用被拒块」场景，断言节点能继续同步 |
| 移除复杂度 | 池内条目数放大后，单次移除的代价不随之线性上升（确定性计数，非墙钟） |
| 锁序 | `getOrphan` 仍在区块链监视器下；无反向持有点 |

### 7.3 回归保护

`811deec0`（孤块选择串行化）与 `a51e09c5`（对外发副本）的回归测试必须继续通过且不得改写其断言。`OrphanBlockStoreConcurrencyTest` 与 `BlockServeTest` 是本次改造的护栏。

---

## 8. 与路线图 §5.3 的偏离清单

| # | 路线图 | 本文 | 理由 |
|---|---|---|---|
| 1 | 替换 `memOrphanPool` | 目标是池 B `OrphanBlockStoreImpl`，池 A 不动 | §1.1：`memOrphanPool` 里没有账户交易也没有分片块 |
| 2 | `orphanPoolLimit` 替代 `MAX_ORPHAN_SIZE`，账户交易队列保留原上限语义 | 四类各有上限 + 全局兜底 | §1.3：今天不存在按队列的上限，3,750 是全局计数只查账户交易 |
| 3 | 二级配额按「来源 peer + 付费块目标 chainId」 | 按「来源 peer + 分片链链头」 | §1.5：分片块不携带 chainId，准入时刻不可计算 |
| 4 | 主块打包只从账户交易队列取 | 反转优先级，`linkQueue` 只填剩余名额 | §3.3：照字面执行会让安静节点的 `nnoref` 失去主块这条消费路径 |
| 5 | 分片块延迟落盘（未展开） | 明确需要内存块体存储 + 合并 `getBlockByHash` + 锁外读安全 | §4.3：池 B 只存元数据，装不下块体；查找路径在共识上 |
| 6 | 费率策略（未提豁免） | 被请求的块必须豁免，否则节点永久卡死 | §5.2 |

---

## 9. 风险与未决

1. **`getBlockByHash` 在共识查找路径上。** §4.3 的合并是本次改动风险最高的一处。等价性属性测试是必须项，不是加分项。
2. **`chunkTtlEpochs` 的下界是共识约束伪装成的配置项。** 必须 fail-fast，不能只写文档。
3. **费率策略的豁免抓手依赖「是否为本节点请求」的判定准确。** 若该判定有漏，后果是节点永久无法同步某个高度。测试必须构造真实的「主块引用被拒块」场景，不能只测策略本身。
4. **本次不触碰池 A。** 路线图未来若仍要 `ChainOrphanPool` 接管 `BI_EXTRA` 块，那是另一个切片；`a51e09c5` 刚为池 A 建立的同步映射与副本语义届时需要重新评估。
5. **SP0b-2b 的其余部分仍未开工。** 本文只偿还孤块池那一行 `phase.*` 的债，`AddressStore` 读与难度/统计更新的测量仍空缺，锁内 ≥84% 的其余部分不在本文范围。
