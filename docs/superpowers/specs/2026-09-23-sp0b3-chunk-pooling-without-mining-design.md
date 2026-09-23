# SP0b-3 追加修复：不挖矿的节点也要留得住分片块 — 设计

**日期**：2026-09-23  
**分支**：`dev-dag-contract`，基线 `125193a4`（Task 15 收口后）  
**上位文档**：`docs/superpowers/specs/2026-09-19-xdag-chain-sp0b3-orphan-pool-design.md`、计划 `docs/superpowers/plans/2026-09-19-xdag-chain-sp0b3-orphan-pool.md`

本文处理两个缺陷。它们不是 SP0b-3 计划预期的工作：一个是 Task 11 的回归，另一个是 Task 15 测量出来的既有浪费。两者插在 Task 15 与 Task 16 之间，因为 **Task 16 的基准在缺陷一未修时根本跑不完**，AFTER 表产不出来。

---

## 1. 缺陷一（P1）：不挖矿的节点把分片块存在了「哪儿都不是」

### 1.1 证据链

三处代码相遇：

| 位置（按符号） | 事实 |
|---|---|
| `BlockchainImpl.tryToConnect` 的提交段 | 类别为 `CHUNK` 时**跳过** `saveBlock`（Task 11 的延迟落盘） |
| `BlockchainImpl.dealOrphan(Block, String, Classified)` | 整个方法体被 `getEnableGenerateBlock() && kernel.getPow() != null` 把关 |
| `ChainOrphanPool.chunkBodies` | 只在 `ChainOrphanPool.add(...)` 里写入，而那只能经 `addOrphan` 到达 |

于是：**一个不挖矿的节点收到一个已分类的分片块，块存储没有、池子也没有**，此后每一个引用它的付费块永远得到 `NO_PARENT`。

Task 11 之前不存在这个洞：那时分片块无论池子收不收都会 `saveBlock`，所以这类节点留得住。是延迟落盘把「留得住」这件事**整个托付给了池子**，而池子的入口挂在挖矿闸门后面。

`dealOrphan` 的 javadoc 其实已经把这个状态写了下来（「a node that pools nothing at all — no PoW, or block generation turned off」），但它当时论述的是 `nnoref` 的配对，没有意识到 Task 11 之后这句话意味着分片块彻底丢失。

### 1.2 受影响的部署形态

- 浏览器节点、纯 RPC 节点（`node.generate.block.enable = false`）
- **任何节点在它的 PoW 实例存在之前的启动窗口**（`getPow() == null`），与配置无关

三个随发行的 `.conf` 都把 `node.generate.block.enable` 设为 `true`，所以配置那一半默认成立；开口的是 `getPow() != null` 那一半。

### 1.3 它是怎么被发现的

两条独立路径指向同一件事：

1. Task 13 实施期自查时作为「Task 11 遗留、超出本任务范围」记录过一次。
2. Task 15 收口时运行 `ChainL1ImportBenchmarkTest`（平时被 `assumeTrue` 跳过，Task 8–14 之间无人触达），其 `syncPath` 腿以 `NO_PARENT` 失败。把 Task 15 全部改动 stash 掉，未修改的树**失败方式完全相同**——所以不是 Task 15 造成的。临时为基准夹具装配 PoW 会让整轮通过，移除又失败，因果由实验确定。

**不采用「给基准夹具加 pow」这条路**：Task 1 的基线文档明确警告，那会让全部历史基线失去可比性，而可比性是这套基准唯一的价值。

---

## 2. 缺陷二（P2）：挖矿路径为它取不到的东西建块

### 2.1 证据链

`OrphanBlockStoreImpl.getOrphanLocked` 的预算与时间戳：

```java
addNum = Math.min(getOrphanSize(), num);                  // 非主块
List<OrphanEntry> selected = selectBlocks(addNum, sendtime[0], isMain);
for (OrphanEntry e : selected) { sendtime[1] = Math.max(sendtime[1], e.meta().getTime()); }
sendtime[1] = Math.min(sendtime[1] + 1, sendtime[0]);
```

- `getOrphanSize()` 是 `ChainOrphanPool.totalSize()`，**四个类别全算，分片块在内**（其自身 javadoc 明言）。
- `selectBlocks` **从不交出 `CHUNK` 条目**。
- 因此预算与可服务量之间的差额，恰好是池中分片块的数量。
- `selected` 为空时，`sendtime[1]` 停在 0，随后 `min(0 + 1, sendtime[0])` = **1**。

### 2.2 实测症状（Task 15 Step 3b）

670 个块的洪泛、8 个 tick，对照组为同等规模的非分片洪泛：

| | 非分片洪泛（对照） | 纯分片洪泛 |
|---|---|---|
| 每个挖出的块携带引用 | 12 | **0** |
| 退役的孤块 | 12/块 | **0** |
| `nnoref` | 671 → 583 | 671 → **671** |
| 成功导入的 link 块 | 8 | **0** |

零引用 + 时间戳 1 的块被**节点自己的导入路径**以 `Block's time is illegal` 拒绝（`timestamp < getXdagEra()`）。八个块建好、签好，一个没进去。而且不会停：`nnoref` 动不了，`nblk` 每个 tick 都是同一个值，直到 TTL 把计数还回来。

### 2.3 为什么现在修

P1 的修复**扩大了 P2 的暴露面**：修复后不挖矿的节点会把分片块计入 `nnoref`，于是启动窗口（`enableGenerateBlock = true` 且 `pow == null`，而 `checkState` 只检查配置与节点状态、不检查 pow）成为 P2 的新触发点。引入一个变化、使一个已记录的缺陷更易发生、又把它留给下一个人重新建立上下文，是不诚实的。

---

## 3. 决策

### D1：解耦只限 `CHUNK`

非分片类别的块在 `tryToConnect` 里已经 `saveBlock`，它们在磁盘上；池子对它们而言只是**挖矿的工作队列**。一个不挖矿的节点池住它们什么也买不到，只是白付内存、配额记账和 `nnoref`。分片块是唯一「池子是它唯一的家」的类别。

这与 Task 13 确立的 `isHeldByThisNode`（「本节点是否在某处持有它」）规则完全一致：非分片块在磁盘上所以照数，分片块只有进了池才算。

### D2：闸门在 `dealOrphan` 内按类别拆，签名不变

`dealOrphan` 从它已经收到的 `classified` 算出类别，`CHUNK` 绕过挖矿闸门，其余三类照旧。

不选「把闸门上提到 `tryToConnect`」：那样「是否落盘」与「是否入池」两个决定能并排可见（今天相隔一百余行，而它们合起来才决定块存在哪），可见性收益是真的——但要动 `dealOrphan` 的公共契约，而 Task 13 刚把单参重载改回 `void`。为一次可见性改善再动一次公共签名不划算。

不选「把闸门做成池/存储的属性」：那要把「本节点挖不挖矿」这个知识推进存储层，那里现在完全不知道这回事。为一个类别的例外引入这种耦合不划算。

单参 `dealOrphan(Block)`（roll-back 的 re-orphan）传 `null` classified，算出的类别永远不是 `CHUNK`，与其 javadoc 既有声明（「a transaction block is never a chunk anyway」）一致。

### D3：`nnoref / 11` 不动，收口放在下游

`checkOrphan` 继续用 `xdagStats.nnoref / 11` 决定挖多少 link 块。

理由：`nnoref` 是会落盘、被多处读取的统计量，Task 13 刚刚**刻意**把它定义成「本节点是否在某处持有它」。让挖矿决策去重新定义它，或者把一个持久计数器与一个活内存计数混进同一个表达式，都比问题本身更脏。

正确的收口在下游：节点仍然每个 tick 决定「该挖了」，然后发现无可引用而**不建块**。剩下的代价只有那个决定本身，是廉价的；实测代价（8 个块建好签好被自己拒掉）归零。

### D4：无可引用就不建块，并且不伪造时间戳

两处，分工明确：

- `getOrphanLocked`：`selected` 为空时不再把 `sendtime[1]` 推成 1。这是与分片块无关的**潜在 bug**——任何让选择为空的路径都会撞上它。
- `createLinkBlock`：孤块池没交出任何引用就不建块。

**这是本次修复唯一能被外部观察到的出块行为变化**：一个今天在「无可引用」状态下仍会发出 link 块的节点，之后不发。该块今天必然被自己拒绝，所以没有损失，但它是出块路径的改动，评审应当按此对待。

### D5：`addNum` 用可服务计数

`getOrphanLocked` 的预算改用「不含 `CHUNK` 的计数」，在池上作为具名方法 **`ChainOrphanPool.selectableSize()`** 提供（实现即 `total - counts[CHUNK.ordinal()]`）。

它本身不造成损害——预算偏大时选择只是交回更少——但它让预算**描述它实际能服务的东西**，并让 D4 的第二条更少被触发。

> **实施期更正（2026-09-23，规格评审确认）：上面这句「预算偏大时选择只是交回更少」不完整，它假设多出来的余量什么也买不到。**
>
> 实际上 `selectBlocks` 开头的 `mainRef` 循环**跑在最前面**并消费到预算上限（`if (!pool.mainRefIsEmpty() && (isMain || pool.mainRefSize() >= 9))`，非主块路径 `it.remove()`）。而 `mainRef` 不在 `!isMain` 的预算里——改动前后都不在。所以分片块的计数一直在**顺带为 link 路径提供可以花在 `mainRef` 上的余量**，那份余量是**被花掉了**，不是被丢弃。
>
> 改用可服务计数之后，这份顺带的抽干消失了。幅度被死死限住：`num` 是 `createLinkBlock` 里的 `16 - res`，而 `res` 是同方法的 `int res = 1 + hasRemark + 2`，所以是 12 或 13——池中非分片孤块 ≥13 时收窄为零，否则每个 link 块最多 13 条。
>
> **定性：时机变化，不是泄漏**，因此不在本次修复里改。`mainRef` 有一条一等排空路径——`tryToConnect` 的引用循环（`for (Address ref : all)`）→ `removeOrphan` → `deleteFromQueue` → `pool.mainRefRemove`——任何引用了停泊条目的块被导入都会把它排掉；`isMain` 预算也仍然整份加上 `mainRefSize()` 且只提供不移除，所以每次主块构建都会把整个积压重新引用一遍。
>
> **与 D4 不叠加**：`mainRef` 只由 `getOrphanLocked` 的 `isMain` 分支填充（那一行 `pool.mainRefAdd(e)`），所以**不挖矿的节点 `mainRef` 恒为空**，而那正是 D4「无引用不建块」发力最猛的地方——在那里这处收窄是空操作。
>
> 诚实的修法（link 分支也加 `mainRefSize()`）会改变**完全没有分片块的节点**的行为，即所有既有部署，远超 D5 的影响范围，属于 `mainRef` 不对称的归属者（见 `selectBlocks` 自身 javadoc 的末段，那里把这个不对称标注为刻意保留）。

**`getOrphanSize()` 的含义必须保持不动**，理由在实施前核实过并且与直觉相反：它在生产代码里**没有其它读者**（`OrphanBlockStore` 接口声明，加上 `getOrphanLocked` 里那两个 `addNum` 调用点，仅此而已），但它是 `BlockchainTest` 中**二十余处断言精确数值**的断言面。所以它不是一个「可以顺手收紧的内部计数」，而是一大批既有测试的观测契约——改它的含义会静默改变那些断言所断言的东西，其中包含更早任务的回归测试。

推论：新的可服务计数作为池上的具名方法提供，由 `getOrphanLocked` 直接使用；`getOrphanSize()` 因此在生产路径上不再有读者，但作为观测接口保留。这一点要在实施时写进它的 javadoc，否则下一个人会把它当成死代码删掉。

---

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `BlockchainImpl.dealOrphan(Block, String, Classified)` | 按类别拆闸门（D2） |
| `ChainOrphanPool` | 新增可服务计数方法（D5） |
| `OrphanBlockStoreImpl.getOrphanLocked` | 预算改用可服务计数；选择为空时不伪造 `sendtime[1]`（D4、D5） |
| `BlockchainImpl.createLinkBlock` | 无引用不建块（D4） |
| `ChunkFloodAdversarialTest` 的 Step 3b 小节 | 改为断言新行为，保留对照臂 |
| 计划 Task 15 Step 3b | 待办改为已修复，指向本文档 |

---

## 5. 明确不做

- **不改 `nnoref` 的含义或它的 `/11` 消费者**（D3）。
- **不给基准夹具装配 PoW**（§1.3）。
- **不动其余三个类别的入池闸门**（D1）。
- 不引入新配置键。不碰任何 `.conf`。

---

## 6. 测试策略

| 断言 | 形式 |
|---|---|
| 不挖矿节点（配置关闭）收到分片块后，付费块能导入 | 新测试，两种不挖矿形态各一 |
| 不挖矿节点（`pow == null`）同上 | 同上 |
| 分片块在不挖矿节点上仍受三层配额与 TTL 约束 | 新测试 |
| 纯分片洪泛下**不再建造** link 块（从 8 建造/0 导入 变为 0 建造） | 改写 Task 15 Step 3b，保留非分片对照臂 |
| 选择为空时不产生时间戳为 1 的块 | 新测试，直接针对 `getOrphanLocked` |
| 非分片洪泛下的出块行为不变 | Step 3b 的对照臂即是 |

**端到端证人**：`ChainL1ImportBenchmarkTest` 的 `syncPath` 腿应当自行恢复绿。这是一个我们没有为它编写、原本就存在的见证者，比任何新写的测试更有说服力——它也是 Task 16 能够开始的前提。

每条断言做变异验证并记录失败文本，与 Task 12–15 同一标准。

---

## 7. 风险

| 风险 | 评估 |
|---|---|
| 不挖矿节点新增内存持有 | 由既有的 `chunkLimit` / `chunkPerPeer` / `chunkPerChain` 三层配额与两纪元 TTL 约束。**不需要新机制**——这些配额本来就是为约束分片块持有而存在的 |
| 出块行为变化（D4） | 唯一外部可观察的变化。被跳过的块今天必然被自己拒绝，所以无损失；但属于出块路径，评审应单独关注 |
| `nnoref` 在不挖矿节点上升 | 符合 Task 13 规则。无消费者误动：`checkState` 对 `enableGenerateBlock = false` 根本不调 `checkOrphan` |
| 与 Task 16 的关系 | 本修复是 Task 16 的前置。AFTER 表必须在本修复之后、在同一棵树上测量 |

---

## 8. 验收判据

1. 不挖矿的节点（两种形态）能接收分片链，付费块正常导入。
2. `ChainL1ImportBenchmarkTest` 恢复通过，且**未为此修改夹具的 PoW 设置**。
3. 纯分片洪泛下建造的 link 块数为 0；非分片洪泛的出块行为与修复前一致。
4. 全量套件绿，`license:check` 通过，`.conf` 未新增任何键。
5. 每条新断言均有变异验证记录。
