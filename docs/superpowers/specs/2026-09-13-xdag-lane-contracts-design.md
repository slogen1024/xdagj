# XDAG 通道式 DAG 原生智能合约总设计（Lane Contracts）

> 日期：2026-09-13
> 分支：`dev-dag-contract`（自 `develop` @ `5a0f1078` 新开）
> 状态：**总设计规格（master spec），待评审**
> 定位：本文是"放弃 EVM 兼容、DAG 原生合约、分片执行一步到位"这一新方向的**总纲**。它给出全部协议级决策、字节级格式、算法与不变量，并把工程拆成 SP0–SP5 六个子项目；每个子项目再各自出详细 spec 与实施计划。
> 阅读前提：`.claude/docs/` 下的十篇架构文档（出块、共识、交易验证、存储、P2P 等；本地文件，不在 git）。dev-evm 分支的 EVM 方案作为对照，本文不复用其代码。

---

## 0. 一页摘要

**目标**：在不改变 XDAG PoW 主链安全模型的前提下，让 XDAG 具备通用智能合约能力，并让执行与状态的吞吐**在协议层面没有上限**。

**方法**：把"通道（Lane）"作为 DAG 原生的状态分区。

- **L1（现有 XDAG DAG）只做四件事**：排序（PoW 主链 × `applyBlock` DFS）、数据可用性（所有通道块都是 512B DAG 块，`NO_PARENT` 导入规则天然强制 DA）、原生账本与金库、仲裁（单步 WASM 重执行）。
- **通道**承载 1..N 个 WASM 合约，状态 = L1 输入流的纯函数（based 排序，执行者无法重排或审查）。通道内同步原子可组合；通道间异步消息，消息就是通道 outbox 里的条目，交付延迟 D 由目标通道自配。
- **执行者**只跑自己订阅的通道。**认证者**锁定 XDAG 保证金后发布锚定块（状态根 + 逐输入承诺）。**任何人**都可用"单步重执行 + 状态见证"的欺诈证明挑战错误锚定并获得罚没奖励。
- **合约 VM = WASM**，运行时 = Chicory（零原生依赖、纯 Java）；部署期字节码插桩做确定性 gas 计量。
- **区块格式**只用剩下的唯一字段码 `0x0F` 作为扩展字段，现网节点对它静默忽略，值结算路径完全复用。

**吞吐边界（诚实版）**：执行与状态无上限；排序 + DA 受单节点导入带宽限制（硬件上限而非协议上限），v1 内做导入流水线并行化，v2 以 DA 采样解除。

---

## 1. 目标、非目标与决策记录

### 1.1 目标

| # | 目标 | 可验证标准 |
|---|------|-----------|
| G1 | DAG 原生 | 调用、载荷、锚定、挑战、认领全部是普通 512B DAG 块；不新增任何块传播协议 |
| G2 | 放弃 EVM 兼容 | 无 Besu 依赖、无 eth_* RPC、无 keccak 地址空间；用户在通道内与 L1 使用同一地址 |
| G3 | 执行/状态吞吐无上限 | 节点只执行/存储订阅通道；新增通道与执行者线性扩容；核数扩展曲线近线性 |
| G4 | 安全不弱于 L1 | 排序与 DA 由 PoW 保证；通道正确性由"1 个诚实观察者 + 挑战窗口"保证；金库无无背书流出 |
| G5 | 通用 DApp 基础 | 同通道内 EVM 级同步可组合；跨通道异步可组合；参考 DApp：代币、DEX、跨通道演示 |
| G6 | 对现有生态零破坏 | `AddressStore`、钱包、交易所、`xdag_*` RPC 不变；激活前后旧节点不崩溃 |

### 1.2 非目标（v1）

- 不做 EVM/Solidity 兼容层，不做 MetaMask 接入。
- 不做 DA 采样/纠删码（v2）。
- 不做编织式原子跨通道步（v2，格式已预留）。
- 不做 zk 证明（乐观欺诈证明足够，且 Java 生态无成熟 zk WASM 证明器）。
- 不改 PoW 算法、主块节奏（64s）与确认规则。

### 1.3 决策记录（用户于 2026-09-13 确认）

| # | 决策 | 选项与理由 |
|---|------|-----------|
| D1 | **分片执行一步到位** | 备选"先全复制并行、后分片"被否。v1 即包含通道订阅、保证金认证者与欺诈证明 |
| D2 | **WASM + Chicory** | 备选 JVM 字节码沙箱（AVM 先例已消亡）、自研字节码（工具链周期过长）。WASM 是 NEAR/Polkadot/Cosmos/Stylus 主流；Chicory 纯 Java 与 xdagj 零原生依赖策略一致 |
| D3 | **从 develop 新开分支** | 只借鉴 dev-evm 已验证的机制思想（PoW 承诺状态根、成熟滞后执行、reorg 对称回滚），不复用 Besu 相关代码；dev-evm 归档 |
| D4 | **开放保证金认证者 + 无状态欺诈证明** | 备选"运营方指定执行者"（去中心化弱）、"通道级合并挖矿"（冷门通道算力不足）。based 排序使认证者只是"结果担保人"，任何人可接管 |
| D5 | **L1 保留原生账本 + 通道金库** | 备选"原生账本迁入系统通道"（需迁移全部存量余额与钱包/交易所集成，风险最大） |
| D6 | **`subscribeAll` 过渡模式纳入 v1** | 节点可配置订阅全部通道，退化为全复制并行执行，降低一步到位的交付风险（§20.3 D2） |

---

## 2. 先例调研与结论

| 项目 | 与本设计的关系 | 采纳 | 不采纳 |
|------|---------------|------|--------|
| **Kaspa**（唯一同类 PoW BlockDAG）| L1 极简（covenants + zk 验证），执行交给 based rollup；L1 只做排序与 DA | based 排序、L1 不执行通用逻辑 | zk（Java 生态不可行）；应用各自搭 L2（我们把 VM 与仲裁"内置"，任何节点可跟任何通道） |
| **Linera**（微链）| 同一验证者集并行执行百万条链；链内同步可组合、链间异步消息 | 通道 = 微链的 XDAG 化；链内同步 + 链间异步 | 需要验证者身份（我们用保证金 + 欺诈证明替代） |
| **NEAR Nightshade** | 异步分片、收据式跨分片调用、无状态验证（状态见证） | 收据即输入、状态见证做单步验证 | 出块者/验证者分配（PoS） |
| **Sui / Aptos** | 对象所有权快路径、Block-STM 乐观并行 | "通道内独立即并行"的思想 | 单一世界状态的块内并行（不解决横向扩展） |
| **Vite**（XDAG 最近亲：DAG + 快照链 + 异步合约）| 2019 上线后式微：**纯异步编程模型对开发者过于痛苦** | 快照链 = 主链的排序作用 | 纯异步（我们保留通道内同步） |
| **CosmWasm** | actor 模型结构性防重入、submessage reply | 默认禁止重入 | 完全禁止同步调用（DEX 需要同通道原子撮合） |
| **Kadena Chainweb** | PoW 编织平行链、SPV 跨链 | 编织式跨通道原子步（v2） | 固定链数 |
| **Radix Cerberus** | 按需编织分片共识实现原子跨分片 | v2 编织步的理论依据 | BFT 验证者 |
| **Chicory** | 纯 Java WASM 运行时，有解释器与运行时/构建期编译两种模式 | 解释器作共识参考、编译模式提速 | 无内建 gas 计量 → 我们在部署期做字节码插桩（NEAR/Polkadot 同法） |

**结论**：XDAG 已有的"主链确认序 × DFS 序"是天然的 based 排序器；512B 块与 `NO_PARENT` 规则是天然的 DA 层；缺的只是（a）分区的执行/状态模型，（b）不依赖验证者身份的结果担保机制。本设计用"通道 + 保证金认证者 + 单步欺诈证明"补上这两块。

---

## 3. XDAG 现状约束（设计边界）

| 约束 | 事实 | 对设计的影响 |
|------|------|-------------|
| 定长块 | 512B = 16 × 32B 字段；field[0] 是 header（transport 8B 零 + 4-bit 类型掩码 8B + time 8B + fee 8B） | 大载荷必须以"分片链"承载 |
| 字段码 | 4-bit，`0x00–0x0E` 已用，**仅 `0x0F` 空闲**；`Block.parse()` 对它走 `default -> {}` 静默忽略，但它仍参与哈希与签名 | 全部新语义走 `0x0F` 扩展字段；旧节点不崩溃、值结算不变 |
| 排序 | 主块每 64s（epoch）一个；确认 ≈ 2 epoch；`setMain` → `applyBlock` 递归 DFS，顺序 = link 顺序 | 通道输入流 = 确认序 × DFS 序；只消费已确认输入 |
| 结算 | 账户模型：INPUT 至多 1 个，nonce = executedNonce + 1，`sumIn == sumOut`，OUTPUT 到账 = amount − outPutLimit；交易块 fee 必须 > 0 | 系统类块（锚定/挑战/认领）以"自转 MIN_GAS"付费；CALL 的 value 走现有结算 |
| 锁 | `tryToConnect` 全局 `synchronized`，验签与递归结算都在锁内 | L1 导入流水线必须并行化（SP0） |
| 孤块池 | `MAX_ORPHAN_SIZE = 3750`，满则拒绝账户交易块 | 需改为按通道分队列/提高上限（SP0） |
| 原生账本 | `AddressStore`：20B 地址 → 余额/nonce | 通道金库、保证金库都是普通 20B 地址（无私钥）；用户在通道内沿用同一地址 |
| P2P | `MessageCode` 最大 `0x1A`；新块经 `NEW_BLOCK` gossip 与 sum 树同步 | 新消息码自 `0x1B` 起；不新增块传播协议 |
| 存储 | 8 个 RocksDB 实例，1 字节前缀分区，只有 `batchWrite` 原子 | 新增 `LANE_L1`（全网小状态）与 `LANE_EXEC`（订阅通道状态）两个实例 |

---

## 4. 总体架构

### 4.1 分层与角色

| 层 / 角色 | 职责 | 谁运行 |
|-----------|------|--------|
| **L1 DAG** | 排序、DA、原生账本、通道注册表、保证金账本、金库、锚定规范链、欺诈证明仲裁 | 全部节点 |
| **通道 Lane** | 状态分区 + 执行单元，1..N 个 WASM 合约；通道内同步原子调用；状态 = L1 输入流的纯函数 | 订阅该通道的节点 |
| **执行者** | 订阅若干通道，计算其状态，提供 RPC/证明服务 | 任意节点 |
| **认证者 Attester** | 锁定保证金，发布锚定块 | 任意执行者 |
| **挑战者** | 发现错误锚定，提交挑战块 | 任意执行者 |
| **认领者** | 为终局提现/跨通道转值提交 CLAIM（可由机器人代劳） | 任意人 |

### 4.2 数据流

```
 用户钱包/SDK
   │ 构造 CALL 块（+分片链）并签名
   ▼
 L1 DAG ── NEW_BLOCK gossip ── 主块 link ── setMain 确认 ── applyBlock DFS
   │  值结算：INPUT 用户 → OUTPUT 通道金库（现有路径）
   │  L1 钩子：记录通道输入 / 注册表 / 保证金 / 锚定规范链 / 挑战仲裁 / 认领划账
   ▼
 通道执行者（只订阅通道 L）
   │  输入流(L, h) = 已交付消息 ++ 已确认 CALL/DEPLOY（DFS 序）
   │  逐输入执行 → C_k 承诺 → SMT 状态根 / outbox / 事件
   ▼
 认证者：ANCHOR 块（stateRoot, outboxMapRoot, 承诺链）──► L1 规范锚定链 ──W──► 终局
   ▲                                                   │
   └── 挑战者：CHALLENGE 块（callIndex + 见证）──► L1 单步重执行仲裁 ──► 罚没/作废
                                                       │
 认领者：CLAIM 块（终局锚定 + MMR 证明）──► L1 金库 → 收款人 / 目标通道金库
```

### 4.3 "DAG 原生"的五条含义

1. **一切皆块**：调用、载荷分片、锚定、挑战、认领都是 512B DAG 块，走同一套 gossip/同步/存储。
2. **DA 由 DAG 结构强制**：块只能在其全部 link 存在时导入（`NO_PARENT`），分片链的每一片都被 link，因此付费块可导入 ⇔ 其载荷完整可得。
3. **排序由 DAG 给出**：主链确认序 × DFS 序，不需要独立排序层或 mempool 共识。
4. **锚定 link 主块**：锚定块直接 link 它消费到的主块，主块被 unwind 则锚定自然失效，reorg 语义免费获得。
5. **状态分区沿 DAG 边界**：通道之间只通过消息（outbox 条目）耦合，没有共享世界状态。

---

## 5. 区块格式：扩展字段 `XDAG_FIELD_EXT (0x0F)`

### 5.1 通用规则

- 将 `XDAG_FIELD_RESERVE6 (0x0F)` 更名为 `XDAG_FIELD_EXT`。
- 块内**第一个** `0x0F` 字段是**扩展头**：byte0 = `kind`，byte1..31 为 kind 参数。
- 扩展头之后所有 `0x0F` 字段是**原始 32B 载荷**，顺序即语义顺序。
- **块链接的角色按位置分配**：块内所有 `XDAG_FIELD_OUT`（指向块、amount = 0）按字段顺序编号 link[0], link[1]…，各 kind 规定每个位置的含义。
- 扩展块的值结算部分（INPUT/OUTPUT/nonce/签名/fee）**完全沿用现有规则**，旧节点把它们当普通账户交易处理。
- 激活高度 `lane.activationHeight` 之前，所有节点忽略扩展语义（与旧节点行为一致）。
- **EXT 不影响 L1 有效性（SP0a 决策 2026-09-13）**：`tryToConnect` 不读扩展字段，格式错误的扩展块仍是合法 L1 块并正常结算值；通道语义只在 `setMain`/`applyBlock` 按主块高度激活后解释，格式错误的调用记为 `INVALID_FORMAT` 输入（value 退回通道内余额）。导入期的分片费率与配额检查是节点本地反垃圾策略（SP0b），不是共识。

### 5.2 各 kind 的字节级布局

所有多字节整数为**小端**（与 XDAG header 一致）。`u16/u32/u64` 表示无符号整数。

#### kind = 0x01 CALL（调用块）

扩展头：

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x01 |
| 1 | 1 | flags | bit0 = 参数走分片链；其余保留为 0 |
| 2 | 20 | contract | 通道内合约地址 |
| 22 | 4 | selector | 方法选择子 = `sha256(方法签名字符串)[0..4]` |
| 26 | 4 | gasLimit | u32，单位 gas |
| 30 | 2 | argsLen | u16，内联参数字节数（≤ 256）；走分片链时为 0 |

其他字段：INPUT（调用者地址 + value + fee）、OUTPUT（通道金库地址 = laneId，amount = value）、TRANSACTION_NONCE、公钥、签名 ×2。内联参数放在后续 `0x0F` 载荷字段（≤ 8 个 = 256B）。分片链时 link[0] = 载荷链首片。

字段预算：header 1 + nonce 1 + INPUT 1 + OUTPUT 1 + pubkey 1 + sig 2 + 扩展头 1 = 8，剩 8 个字段给内联参数（或 1 个 link + 7 个空）。

value 的 L1 目标是**通道金库**而非合约；目标合约只出现在扩展头。

#### kind = 0x02 DEPLOY（部署块）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x02 |
| 1 | 1 | flags | bit0 = 新建通道；bit1 = 代码走分片链（否则 codeHash 必须已在代码库）；bit2 = init 参数走分片链 |
| 2 | 20 | laneId | 加入已有通道时的通道 id；新建时忽略 |
| 22 | 4 | gasLimit | init 调用 gas 上限 |
| 26 | 2 | argsLen | 内联 init 参数长度 |
| 28 | 4 | 保留 | 0 |

载荷字段：payload[0] = codeHash（32B，原始 WASM 字节的 sha256）；payload[1] = 通道配置（仅新建通道时有效）：`gasPrice u64（nano/gas） ‖ deliveryDelayD u32 ‖ maxCallGas u32 ‖ 保留 16B`；其后为内联 init 参数。link[0] = 代码分片链首片（flags.bit1）；link[1] = init 参数分片链首片（flags.bit2）。

派生：新通道 `laneId = sha256("xdag-lane" ‖ blockHash)[0..20]`；新合约地址 `contract = sha256("xdag-contract" ‖ blockHash)[0..20]`。新通道的金库地址就是 laneId，在 `setMain` 时以 0 余额创建于 `AddressStore`。

#### kind = 0x03 CHUNK（数据分片块）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x03 |
| 1 | 4 | seq | u32，链内序号，首片为 0 |
| 5 | 4 | totalLen | u32，整条链的载荷总字节数 |
| 9 | 2 | dataLen | u16，本片有效字节数（≤ 352） |
| 11 | 21 | 保留 | 0 |

link[0] = 下一片（末片无）。分片块必须带 **2 个全零 `SIGN_OUT` 字段**（`checkMineAndAdd` 对缺失的 outsig 会抛异常导致导入 ERROR；全零签名被解析为矿工伪块 (1,1)，不需要真正签名），因此载荷字段最多 **11 个**（header 1 + link 1 + 扩展头 1 + 签名 2 + 11 = 16），每片 **352B**。分片块**无 INPUT、无公钥、fee = 0**（与现有 link block 同类）。分片链的费用由 link 它的付费块承担（§5.4）。100KB WASM ≈ 291 片。

链的完整性校验：`totalLen == Σ dataLen`，`seq` 连续，末片无 link；载荷哈希 `sha256(拼接后的字节)` 由使用方（CALL/DEPLOY/ANCHOR…）的语义决定是否需要匹配某个承诺。

#### kind = 0x04 ANCHOR（锚定块）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x04 |
| 1 | 1 | flags | 保留（bit0 预留给 v2 编织多通道锚定） |
| 2 | 20 | laneId | |
| 22 | 8 | seq | u64 = 消费到的主块高度 h |
| 30 | 2 | 保留 | 0 |

载荷：payload[0] = stateRoot（SMT 根）；payload[1] = outboxMapRoot（§7.6）；payload[2] = `prevSeq u64 ‖ inputCount u32 ‖ segmentCount u32 ‖ eventsRootAgg 16B（保留）`。
link[0] = 上一规范锚定（创世锚定为通道的 DEPLOY 块）；link[1] = 高度 h 的主块；link[2] = 承诺链首片（§9.2，内容 = 已消费段列表 ++ 逐输入承诺 C_1..C_n）。
值结算：INPUT 认证者 → OUTPUT 认证者自身，amount = MIN_GAS（自转付费）。字段：header + nonce + INPUT + OUTPUT + pubkey + sig×2 + 扩展头 + 3 载荷 + 3 link = 14。

#### kind = 0x05 BOND（保证金块）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x05 |
| 1 | 1 | flags | bit0 = 解锁（UNBOND） |
| 2 | 20 | laneId | 全零 = 全局保证金（可为任意通道锚定） |
| 22 | 8 | amount | UNBOND 时的解锁额；BOND 时忽略（以 OUTPUT 金额为准） |
| 30 | 2 | 保留 | 0 |

BOND：INPUT 认证者 → OUTPUT 保证金库地址 `LANE_BOND_VAULT = sha256("xdag-lane-bond")[0..20]`，amount = 保证金。UNBOND：自转 MIN_GAS 付费；到期（确认高度 + W）在 `setMain` 时由系统从保证金库划回 `min(申请额, 剩余保证金)`（期间被罚没则相应减少），无需额外块。

#### kind = 0x06 CHALLENGE（挑战块）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x06 |
| 1 | 1 | flags | 保留 |
| 2 | 4 | inputIndex | u32，被挑战输入在锚定承诺链中的位置 k |
| 6 | 8 | deposit | u64，挑战押金（须等于 OUTPUT 到保证金库的金额） |
| 14 | 18 | 保留 | 0 |

link[0] = 被挑战锚定；link[1] = 见证分片链首片（§10.2）。值结算：INPUT 挑战者 → OUTPUT 保证金库（押金）。

#### kind = 0x07 CLAIM（认领块）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 1 | kind | 0x07 |
| 1 | 1 | flags | bit0 = 收款方是通道金库（跨通道转值对账）；否则收款方是 L1 用户地址 |
| 2 | 20 | srcLane | 源通道 |
| 22 | 8 | seq | 所引用终局锚定的 seq |
| 30 | 2 | 保留 | 0 |

payload[0] = `index u64 ‖ amount u64 ‖ 保留 16B`；payload[1] = `recipient 20B ‖ 保留 12B`。link[0] = 终局锚定；link[1] = 证明分片链首片（对 outboxMapRoot 的 SMT 证明 + 对目标 MMR 的成员证明，§7.6）。值结算：自转 MIN_GAS 付费。字段：header + nonce + INPUT + OUTPUT + pubkey + sig×2 + 扩展头 + 2 载荷 + 2 link = 12。系统动作：金库(srcLane) → recipient 划账 `amount`。

### 5.3 分片链的引用与 DA

- 付费块 link 首片，首片 link 次片，……链尾无 link。`tryToConnect` 的 `NO_PARENT` 规则保证：付费块可导入 ⇔ 整条链已在本地。
- 分片块本身是 EXTRA/孤块，未被任何付费块引用时可被孤块池按现有规则淘汰；被主块直接 link 的"裸分片"只是无意义的 link block，不进入任何通道语义。
- 单条链上限 `lane.maxChunksPerChain`（初始 4096 片 × 352 B ≈ 1,441,792 B ≈ 1.44 MB）。
- **年龄规则（共识，SP0a 决策 2026-09-14）**：付费块引用的分片链上每一片都必须满足 `epoch(chunk) ≥ epoch(payingBlock) − 1`，即"与付费块同 epoch，或紧邻的前一个 epoch"，否则该输入被记为格式错（`ExtError.CHUNK_TOO_OLD`）。理由：`NO_PARENT` 只要求父块的 `BlockInfo` 存在，而**快照启动的节点没有快照时间之前的块的原始字节**；没有年龄下界时，同一条内容寻址的老分片链会在全量节点上装配成功、在快照节点上失败——同一个块在两类节点上得到两种裁决。年龄规则让两类节点都只需保留最近两个 epoch 的原始字节。客户端切链工具因此保证一条链不跨 epoch（跨 epoch 的链只能在头片自己那个 epoch 内被付费，窗口可短到 0 tick）。

### 5.4 费用规则（L1 层）

扩展块的 header fee 字段必须满足：`header.fee ≥ chunkFee × 该块直接 link 的所有分片链总片数`。`chunkFee` 为协议参数（初始 0.01 XDAG/片）。两层执行：导入期作为节点本地策略拒绝（SP0b，可配置）；`applyBlock` 时作为共识复核，不足者记为 `INVALID_FEE` 输入（value 退回通道内余额，分片链不被使用）。这笔费用与现有交易费一样归 PoW 主块矿工，覆盖排序与 DA 成本。复核时 `header.fee` 必须从**原始 512 字节的 header 字段**读：`BlockInfo.fee` 在 apply 期间会被改写成"这个块收到的手续费"，不再是"这个块声明了多少"。

**分片链头 link 也算 output（SP0a 决策 2026-09-14）**：`outPutNum` / `getTxFee` 不区分真正的支付（`XDAG_FIELD_OUTPUT`）与分片链头引用（`XDAG_FIELD_OUT`），两者都计入 `input ≥ headerFee + MIN_GAS × outputs`。因此构造 CALL / DEPLOY 块时，INPUT 金额必须是

```
requiredValue(headerFee, chainLinks) = headerFee + MIN_GAS × (1 + chainLinks)
```

（`1` = 那一笔真正的 OUTPUT，`chainLinks` = 该块实际携带的分片链头数：CALL 0 或 1，DEPLOY 1 或 2）。这是既有的 L1 有效性规则，与上面的分片费是**两层独立约束**：金额不够块直接无效，分片费不够块仍然有效、只是输入记为 `INVALID_FEE`。

**参数链的费用基数是宽松计数（SP0a 决策 2026-09-14）**：L1 只装配 DEPLOY 的代码链，它的片数必须在装配通过之后再数；CALL 的参数链与 DEPLOY 的 init 参数链 L1 **从不装配**（SP1 的执行引擎才装配，装不出来就在通道内失败并退款），它们的费用基数就是"查得到 + 够新 + 能解码成 CHUNK 的片数"这个宽松计数。分片费收的是全网确实要存的分片块，与这条链将来能不能装配无关。

### 5.5 兼容性与硬分叉点

- **软兼容部分**：旧节点解析 `0x0F` 走 `default`，把 CALL/DEPLOY/ANCHOR/BOND/CHALLENGE/CLAIM 当普通账户交易结算，哈希/签名不受影响。
- **硬分叉部分**（激活高度后行为分歧）：(a) CLAIM 与 UNBOND 到期的**系统划账**改变 L1 余额；(b) 快照必须携带 `LANE_L1`；(c) 孤块池、导入流水线与导入期费率策略均为节点本地，不影响共识。因此 `lane.activationHeight` 必须作为硬分叉高度统一升级，与 dev-evm 的 `*ActivationHeight` 治理方式相同。
- **激活前的资金陷阱**：旧节点把 CALL 当普通转账，value 进金库却没有通道语义，激活前发出的调用资金无法 CLAIM。因此钱包、SDK 与 `lane_*` RPC 在激活高度前必须拒绝构造/提交扩展块；协议侧只从激活高度起记录通道输入，激活前进入金库的余额视为捐赠，不做补救。

---

## 6. L1 状态扩展与钩子

### 6.1 全网小状态（`LANE_L1` RocksDB 实例）

| 前缀 | key | value | 用途 |
|------|-----|-------|------|
| 0x01 | laneId | 配置（gasPrice, D, maxCallGas）、创建高度、创建块 hash | 通道注册表 |
| 0x02 | contract(20) | laneId ‖ codeHash ‖ 部署高度 | 合约 → 通道 |
| 0x03 | codeHash | 原始 WASM 字节（只有 blob） | 代码库（全网保存，仲裁需要） |
| 0x04 | attester(20) ‖ laneId | 保证金额 ‖ 解锁申请高度 | 保证金账本 |
| 0x05 | laneId | 规范头 seq ‖ 锚定块 hash | 锚定链头 |
| 0x06 | laneId ‖ seq | 锚定记录：hash、stateRoot、outboxMapRoot、inputCount、确认高度、状态（canonical/final/voided） | 锚定索引 |
| 0x07 | laneId ‖ height | `callCount u32` | 每高度输入计数（DFS 时顺手统计） |
| 0x08 | srcLane ‖ dstKey ‖ index | 1 | 已认领防重放 |
| 0x09 | height | 待到期列表（UNBOND、锚定终局） | 高度触发器 |
| 0x0A | challengeHash | 仲裁结果 | 挑战记录 |
| 0x0B | dstLane ‖ srcLane | 已消费游标 `to`、已消费到的 srcAnchorSeq | 段消费游标（§7.5） |
| 0x0C | laneId ‖ height ‖ index | blockHash ‖ kind ‖ status ‖ contract | 输入索引（执行者按此重建输入流） |
| 0x0D | codeHash | refCount u32 | 代码引用计数（与 blob 分开：共享代码的第二次部署只重写 4 字节，不重写最大 1 MB 的 blob）。"代码库里有没有这份代码"以**本条 key 是否存在**为准；计数到 0 时 0x03 与 0x0D 一起删（从不写 0），保证 unwind 后判定全网一致 |
| 0x0E | blockHash | laneId ‖ height ‖ index 的定长数组 | unapply 反向索引（一个块可以有多条输入记录） |

`index` 是**每 (lane, height) 内从 0 递增的序号**（0x07 的 `callCount` 就是下一个 index），不是主块级的全局 DFS 序号：执行者按 `0..callCount−1` 枚举该通道该高度的输入流。

### 6.2 钩子位置

| 时机 | 动作 | 对称回滚 |
|------|------|---------|
| `tryToConnect` | **不读 EXT**（原则：EXT 不影响 L1 有效性）；SP0b 的导入期策略在锁外做分片费率/配额过滤 | 无状态 |
| `onSetMainBegin(h)` | `setMain` 中 `BI_MAIN` 置位后、`applyBlock` 前：`h ≥ activationHeight` 才建立 DFS 上下文 | `onUnsetMain` 清上下文 |
| `onBlockApplied`（`applyBlock` 置 `BI_APPLIED` 后，DFS 序） | CALL/DEPLOY：登记通道输入（0x0C/0x0E/0x07；DEPLOY 更新注册表/合约表/代码库 0x01/0x02/0x03/0x0D）；BOND/UNBOND：更新保证金账本；ANCHOR：有效性检查 → 规范链；CHALLENGE：仲裁（§10）；CLAIM：证明校验 → 系统划账 | `onBlockUnapplied`（`unApplyBlock` 逆序）逐项反向 |
| `onSetMainEnd(h)` | 按固定顺序处理高度 h 的到期项：① 仲裁本高度确认的 CHALLENGE ② 锚定终局 ③ UNBOND 归还 ④ 消息段变为可交付 ⑤ 本高度成为规范的锚定产生本高度的"锚定奖励"系统输入（§7.1） | `onUnsetMain` 反向 |

**规则**：每个新增写操作必须有成对的反操作，并在 SP0 中用"随机 unwind/replay 后状态相等"的属性测试锁死。

### 6.3 快照扩展（SP0 范围）

XDAG 主网节点通常从快照启动。`LANE_L1` 的全部内容（注册表、合约→通道、代码库、保证金账本、锚定链头与索引、每高度输入计数、已认领集合、高度触发器、段游标）必须随快照一起导出/导入，并在快照中附带一个 `laneStateHash = sha256(LANE_L1 全部 KV 的规范序列化)` 供校验；快照启动的节点若在激活高度之后缺少该段则拒绝启动。理由：dev-evm 曾因 alloc 标记未进快照导致快照节点与全量节点状态分歧，本设计的 L1 小状态直接影响锚定有效性判定与系统划账，缺失即分叉。

SP0a 的落地形态（细节见 SP0a 规格 §8）：快照从此有**三个目录** `SNAPSHOT/BLOCKS`、`SNAPSHOT/ADDRESS`、`SNAPSHOT/LANE_L1`，导出无条件进行（空的 `LANE_L1` 导出的就是"元数据 + 哈希"）；导入闸门是快照启动分支的第一条语句，与 SnapshotJ 开关无关，fail-fast；一次成功的导入会在本地库里留下一个**一次性持久标记**（`0xFF`，不计入状态哈希）；这道门只在块存储被快照重新灌库时到达（普通重启走不到），标记存在时还必须同时证明"本次用的是同一份快照"（快照记录的哈希 = 标记）与"本地未漂移"（`stateHash()` = 标记）才幂等返回，否则拒绝并提示删本地 `LANE_L1` 目录；本地有状态却没有标记同样拒绝启动。**快照里不携带任何原始分片字节**：年龄规则允许付费块往回够两个 epoch，可能够到快照之前，那些分片块由 `NO_PARENT` 触发的按需拉取补齐（见 §20.2 E12）。

---

## 7. 通道执行模型

### 7.1 输入流定义

通道 L 在主块高度 h 的输入序列：

```
inputs(L, h) = systemInputs(L, h)  ++  deliveredMessages(L, h)  ++  confirmedBlocks(L, h)

systemInputs(L, h):      高度 h 成为规范的 L 的锚定各产生一条"锚定奖励"输入 {attester, prevSeq, seq}，
                         执行效果 = 把 (prevSeq, seq] 的费用池划给 attester 的通道内余额；inputHash = sha256("reward" ‖ anchorHash)
deliveredMessages(L, h): 在高度 h 变为可交付的所有源段（§7.5）按 (源锚定的 L1 确认序, 段内 index) 排序展开为消息
confirmedBlocks(L, h):   高度 h 的主块 applyBlock DFS 中，目标为 L 且 L1 值结算 BI_APPLIED 的 CALL/DEPLOY，按 DFS 序
```

通道状态 `S(L, h) = exec(S(L, h−1), inputs(L, h))`。`seq` 即 h。三类输入的**数量与身份 L1 都能确定**（规范锚定、可交付段、已结算块都是 L1 状态），只有段内消息的内容需要源通道数据。由于 inputs 只依赖 L1 已确认历史与其他通道的**已确认锚定**，`S(L, h)` 是 L1 历史的纯函数：任何两个诚实执行者必然得到相同结果。

### 7.2 单笔 CALL 的事务语义

1. 前置：value 已由 L1 结算进金库；通道内账本 `bal[sender] += value`。
2. 预扣 gas：`bal[sender] −= gasLimit × gasPrice`；不足则该输入标记 `status = INSUFFICIENT_GAS`，不执行，value 留在 `bal[sender]`。
3. 执行 `call(contract, selector, args)`；合约可用 `xdag.value()` 读取本次 value 并用 `transfer_from_caller(amount ≤ value)` 领取。
4. 结束：成功 → 提交状态；失败（trap / abort / 超 gas / 超深度）→ 回滚到调用前，`status = FAILED`。两种情况都退还未用 gas：`bal[sender] += (gasLimit − gasUsed) × gasPrice`；已用 gas 计入通道费用池。
5. 产出承诺 `C_k = sha256(inputHash ‖ postStateRoot ‖ outboxMapRoot ‖ eventsRoot ‖ status(1B) ‖ gasUsed(u64))`。

`inputHash`：CALL/DEPLOY 为块 hash；消息为其 MMR 叶哈希（§7.6）。

### 7.3 通道内调用与重入

- `call(contract, selector, args, value)` 同步执行，嵌套帧，最大深度 `maxCallDepth`（初始 16）；被调失败时调用方收到错误码，可选择回滚或继续；帧内状态变更在帧成功返回时并入上层，失败时丢弃（嵌套 updater，与 dev-evm 的 `RocksDbWorldUpdater` 思想相同）。
- **默认禁止重入**：若调用栈中已存在目标合约则 trap。方法可在导出元数据中标记 `reentrant`（SDK 宏），仅该方法允许被重入。

### 7.4 跨通道消息

- `send(dstLane, contract, selector, args, value)`：向本通道 outbox 追加条目 `{srcLane, dstLane, index, sender, contract, selector, args, value, srcHeight}`（`sender` = 发起 send 的合约地址或用户地址）；`bal[sender] −= value` 进入"出站在途"。
- 目标通道收到消息作为输入时：先 `bal[sender] += value`（在目标通道内以原发地址记账），再执行 `recv`；`recv` 内用 `transfer_from_caller` 领取。`recv` 失败则状态回滚，value 留在目标通道内的 `bal[sender]`，原发者可提现回 L1 或再次 `send`。
- 消息**不做回执**（无 reply）；需要回执的应用自行反向 `send`。

### 7.5 交付规则与 D

- 源通道 S 的每个规范锚定 A（seq = h_S）在其 outboxMap 中给出每个目标通道 X 的 MMR 大小 `size_A(X)`；段 `[size_{prev}(X), size_A(X))` 是 A 新增的发往 X 的消息。L1 只看得到 outboxMapRoot，看不到 `size_A(X)`，因此段的上界 `to` 由目标通道的认证者**声明**，其真实性由 E3 挑战保证（§10.1）；下界 `from` 必须等于 L1 记录的该 (X, S) 游标（上一次消费的 `to`），可被 L1 精确核对。
- 段对 X **可交付**的高度 `h_deliver = confirmHeight(A) + D_X`，其中 `confirmHeight(A)` 是 A 所在主块的确认高度，`D_X` 是 X 的配置。
- **消费是强制且确定的**：X 在高度 h 必须消费所有 `h_deliver ≤ h` 且尚未消费的段，按 (A 的 L1 确认序) 排序。锚定中"已消费段列表"（§9.2）的 (srcLane, srcAnchorSeq) 序列与各段 `from` 必须与 L1 计算结果完全一致，否则锚定结构无效；`to` 可挑战。
- 若 A 后来被欺诈证明否决，所有消费了 A 的段的通道从消费点回滚（级联）。`D_X = W` 时永不级联；`D_X = 0` 时即时交付但暴露于级联，适合同一运营方的通道群。

### 7.6 状态与承诺结构

| 结构 | 定义 |
|------|------|
| 状态 KV | key = `sha256(contract ‖ userKey)`，value = 任意字节（≤ `maxValueBytes`，初始 64KB） |
| 通道内账本 | 视为系统合约 `0x00…00` 的 KV：`bal[addr]`、费用池 |
| stateRoot | 二叉**稀疏 Merkle 树**（256 层，空子树哈希预计算），哈希 = SHA-256（xdagj 已有；blake3 作为备选，若基准显示瓶颈可在激活前更换，激活后不可变） |
| outbox | 每个目标（dstLane，或 `L1 = 全零`）一棵 append-only **MMR**；叶 = `sha256(条目编码)` |
| outboxMapRoot | 一棵小 SMT：key = dstLane，value = `mmrRoot ‖ size u64` |
| eventsRoot | 本输入产生的事件列表的 Merkle 根（供浏览器/轻客户端） |

### 7.7 变更日志、回滚与快照

- 每个 seq 记录 KV 变更集（旧值/新值）与 outbox/账本增量；保留最近 `K` 个 seq（初始 128）。
- 回滚触发：L1 `unWindMain` 到 h0 → 回滚到 seq ≤ h0；源锚定被否决 → 回滚到消费该段之前的 seq；本地锚定被否决 → 无需回滚（状态本就是对的，是锚定错），但要重新按规范链核对。
- 每 `snapshotInterval`（初始 64 seq）落一份全量 KV 快照 + 根，供新订阅者引导与本地快速恢复。

### 7.8 软预执行

执行者可对未确认输入（当前 DAG 中已导入但未 `setMain` 的 CALL）按临时 DFS 序预执行，只服务 RPC 的 `pending` 视图，永不锚定。

---

## 8. WASM 合约 ABI v1

### 8.1 模块要求

- WebAssembly 1.0 核心 + `sign-ext` + `bulk-memory`（可选）+ `mutable-globals`；**禁止**：浮点指令（f32/f64 全部）、SIMD、线程/原子、异常处理、多内存、引用类型、尾调用。
- 恰好一个线性内存，初始 ≤ `maxMemoryPages`（初始 64 页 = 4MB），必须声明 `max`。
- 导入只能来自 `xdag` 命名空间（§8.3）。
- 原始字节 ≤ `maxWasmBytes`（初始 1MB）。

### 8.2 导出

| 导出 | 签名 | 说明 |
|------|------|------|
| `init` | `() -> ()` | 部署时执行一次，参数经 `xdag.input` 读取 |
| `call` | `() -> ()` | 处理 CALL；selector 经 `xdag.selector` 读取，由 SDK 生成分发 |
| `recv` | `() -> ()` | 处理入站跨通道消息 |
| `query` | `() -> ()` | 只读调用，链下，由 RPC 触发，写操作 trap |
| `memory` | memory | 必须导出 |

### 8.3 宿主导入 `xdag.*`

| 函数 | 签名（i32 = 指针/长度） | 语义 | gas（初始） |
|------|------------------------|------|-------------|
| `input_len` / `input_read(ptr)` | `()->i32` / `(i32)->()` | 读取本次参数 | 10 / 10 + 1/32B |
| `selector` | `()->i32` | 本次方法选择子 | 5 |
| `return_data(ptr,len)` | `(i32,i32)->()` | 设置返回数据 | 10 + 1/32B |
| `caller(ptr)` / `self_address(ptr)` / `lane_id(ptr)` | `(i32)->()` | 20B 写入 | 10 |
| `value()` | `()->i64` | 本次 value（nano） | 5 |
| `height()` / `timestamp()` | `()->i64` | 当前 seq / 该主块时间戳 | 5 |
| `storage_get(kptr,klen,vptr,vcap)` | `(i32,i32,i32,i32)->i32` | 返回长度或 −1 | 200 + `gasPerWitnessByte × witnessBytes(key)`（同 key 重复读只计一次见证） |
| `storage_set(kptr,klen,vptr,vlen)` | `(i32,i32,i32,i32)->()` | 写 | 1000 + 100/32B（新 key 额外 2000）+ `gasPerWitnessByte × witnessBytes(key)`（同 key 已读过则不重复计） |
| `storage_remove(kptr,klen)` | `(i32,i32)->()` | 删（退 500） | 500 + `gasPerWitnessByte × witnessBytes(key)` |
| `storage_scan(pptr,plen,max,out)` | `(i32,i32,i32,i32)->i32` | 有界前缀扫描（≤ 256 条） | 500 + 200/条 + `gasPerWitnessByte × Σ witnessBytes` |
| `balance(aptr)` | `(i32)->i64` | 通道内余额 | 100 |
| `transfer(aptr,amount)` | `(i32,i64)->i32` | 通道内转账 | 500 |
| `transfer_from_caller(amount)` | `(i64)->i32` | 领取本次 value | 200 |
| `withdraw(aptr,amount)` | `(i32,i64)->i32` | 向 L1 地址提现（outbox L1 条目） | 2000 |
| `call(cptr,sel,aptr,alen,value,rptr,rcap)` | `(i32,i32,i32,i32,i64,i32,i32)->i32` | 同通道同步调用 | 500 + 被调 gas |
| `send(lptr,cptr,sel,aptr,alen,value)` | `(i32,i32,i32,i32,i32,i64)->i32` | 跨通道消息 | 2000 + 50/32B |
| `emit(tptr,tlen,dptr,dlen)` | `(i32,i32,i32,i32)->()` | 事件 | 100 + 8/32B |
| `sha256(p,l,o)` / `keccak256(p,l,o)` | `(i32,i32,i32)->()` | 哈希 | 60 + 12/32B |
| `secp256k1_verify(h,sig,pub)` / `_recover(h,sig,v,o)` | | 验签/恢复 | 3000 |
| `gas_left()` | `()->i64` | | 5 |
| `abort(mptr,mlen)` | `(i32,i32)->()` | 带消息回滚 | 0 |

费用表为初始值，SP1 用微基准校准后固化为协议参数；激活后只能通过硬分叉调整。

**见证比例计费（硬约束）**：`witnessBytes(key) = 64 + 32 × nonEmptySiblings(key) + valueLen(key)`，其中 `nonEmptySiblings` 是该 key 在当前 SMT 路径上非空子树兄弟的数量（状态的确定性函数，执行者与仲裁者算得一致），`valueLen` 是读到/写前的值长度。`gasPerWitnessByte`（初始 10）与 `maxCallGas`、`maxWitnessBytes` 必须满足

```
maxCallGas / gasPerWitnessByte + 固定开销(≤ 32KB) ≤ maxWitnessBytes
```

这保证**任何在 gas 上限内完成的调用，其欺诈证明见证都装得下**，认证者无法通过构造"读集巨大"的调用制造不可挑战的错误锚定。SP1 必须用属性测试锁死：随机调用的实际见证字节数 ≤ 由 gasUsed 推出的上界。

### 8.4 确定性规则

1. 部署期验证（§8.1）失败 → DEPLOY 输入 `status = INVALID_CODE`。
2. **部署期插桩**（确定性协议函数，输入 codeHash 唯一确定输出）：
   - 在每个基本块入口注入 `xdag.gas(cost)`（或全局计数器减法 + 溢出 trap），cost = 块内指令按类别计费之和；
   - `memory.grow` 前注入按页计费（初始 8192 gas/页）；
   - 栈高度计量：每个函数入口/出口维护全局栈计数，超过 `maxStackHeight`（初始 65536）trap；
   - 插桩后的模块缓存于本地，codeHash 仍为原始字节哈希。
3. 运行期：内存越界、整数除零、未定义 `unreachable`、导入返回错误码等一律为 trap，语义由 WASM 规范唯一确定。
4. 宿主函数不得引入非确定性（无时钟、无随机、无 I/O）。
5. 见证比例计费（§8.3）是共识规则：`nonEmptySiblings` 的计算方式随 SMT 定义一起固化，改变它等于硬分叉。

### 8.5 运行时模式

- **共识参考实现 = Chicory 解释器**：L1 欺诈证明仲裁只使用它。
- 执行者可启用 Chicory 运行时编译（JVM 字节码）提速；若两种模式结果不一致，得到的是一个可被挑战的锚定，仲裁以解释器为准。SP1 的确定性测试要求两种模式对同一输入流产出相同根。
- 每次调用一个新 `Instance`（内存清零），实例池只复用已编译模块，不复用内存。

### 8.6 费用与激励

- **L1 块费**（现有）→ PoW 矿工：排序 + DA。
- **执行 gas** = `gasUsed × gasPrice(lane)`，从 sender 通道余额扣，进入通道费用池；在锚定 seq = h 成为规范时，`(prevSeq, h]` 内的费用池归该锚定的认证者（通道内余额，可提现）。
- 通道 `gasPrice` 由部署者在创建时设定，协议下限 `minGasPrice`（初始 1 nano/gas）。

---

## 9. 认证者、锚定与终局

### 9.1 保证金

- BOND 后即刻生效；`bond(attester, lane) + bond(attester, 全局) ≥ minBond`（初始 10,000 XDAG，待经济分析）才可为该通道锚定。
- UNBOND 申请后保证金仍可被罚没；确认高度 + W 到期时系统划回。

### 9.2 锚定块的承诺链内容

承诺链（link[2] 指向的分片链）按序包含：

1. **已消费段列表**：`segmentCount` 条，每条 `srcLane 20B ‖ srcAnchorSeq u64 ‖ from u64 ‖ to u64`（44B，紧凑拼接后按 32B 字段切分）。
2. **逐输入承诺** `C_1..C_n`（n = inputCount），顺序与 §7.1 的输入序完全一致。

### 9.3 有效性检查（所有节点，无需执行）

| 检查 | 依据 |
|------|------|
| 签名者保证金足额 | `LANE_L1` 0x04 |
| link[0] 是当前规范头且 `prevSeq` 匹配，`seq > prevSeq` | 0x05 |
| link[1] 是高度 `seq` 的规范主块 | `BLOCK_HEIGHT` 索引 |
| 已消费段列表的 (srcLane, srcAnchorSeq) 序列与各段 `from` == L1 按 §7.5 计算的结果 | 0x06 + 0x0B 游标 + 各源通道配置 |
| `inputCount == Σ 规范锚定奖励数 + Σ 段 (to − from) + Σ_{h∈(prevSeq,seq]} callCount(L,h)` | 前后两项 L1 精确可知；中间项的 `to` 是认证者声明值，真实性由 E3 挑战保证（§10） |
| 承诺链长度 == segmentCount × 44B + inputCount × 32B（按 32B 字段向上取整） | 分片链 totalLen |

通过即成为 `(lane, seq)` 的**规范锚定**（同 seq 后来者忽略）。

### 9.4 终局

锚定所在主块确认高度 + W（初始 32）到达且状态仍为 canonical → `final`。CLAIM 只认 final 锚定；`D_X = W` 的通道只消费 final 段。

### 9.5 否决与回退

挑战成功 → 该锚定及其所有后代置 `voided`，规范头回退到其父；消费了其段的通道级联回滚（§7.5）；新的锚定可以从父继续。认证者的保证金按 §10.4 罚没。

---

## 10. 欺诈证明（单步、无状态）

### 10.1 可挑战的错误类型

| 类型 | 断言 | 见证 |
|------|------|------|
| E1 输入错位 | 位置 k 的 `inputHash` ≠ 正确输入 | 正确输入的身份：CALL 块 hash（L1 已有）或消息的 MMR 成员证明 + 源锚定 outboxMap SMT 证明 |
| E2 执行错误 | 给定正确前态与输入，重执行得到的 `C'_k ≠ C_k` | 前态根来源 + 读集 SMT 证明 + 写集兄弟路径 + 消息内容 + `C_k` 开解 |
| E3 段上界错误 | 声明的 `to` ≠ 源锚定 A 的 outboxMap 中 `size_A(dst)` | 源锚定 outboxMap 对 key = dst 的一个 SMT 证明（存在或不存在） |

### 10.2 见证格式（分片链载荷）

```
WitnessV1 {
  type u8                          // E1 / E2 / E3
  preState {                       // E2
    prevCommitment C_{k-1} 开解     // 或 k = 1 时上一锚定的 stateRoot
  }
  input {                          // E1 / E2
    kind u8, blockHash 32B | message { srcLane, index, 条目编码, MMR 证明, outboxMap SMT 证明 }
  }
  reads  [ (key, value|absent, SMT 证明) ... ]
  writes [ (key, 兄弟路径) ... ]
  claimed C_k 开解 { inputHash, postStateRoot, outboxMapRoot, eventsRoot, status, gasUsed }
}
```

见证总长 ≤ `maxWitnessBytes`（初始 1MB）。

### 10.3 仲裁算法（`CHALLENGE` 块 `setMain` 时全网执行）

```
verify(challenge):
  A = link[0]; require A.status == canonical            // final 锚定不可挑战；同一高度内先仲裁后终局
  require k < A.inputCount
  W = parse(witness chain)
  // 1. 承诺定位
  C_k     = A.commitments[k]; C_{k-1} = k>0 ? A.commitments[k-1] : null
  R_prev  = k>0 ? open(C_{k-1}).postStateRoot : A.parent.stateRoot
  require sha256(open(C_k)) == C_k
  // 2. 输入正确性 (E1)
  expected = expectedInputAt(A.lane, A.prevSeq, A.seq, k)  // L1 可算：段列表 + callCount；消息身份用见证证明
  if expected != open(C_k).inputHash: return CHALLENGE_SUCCEEDS
  // 2b. 段上界 (E3)
  if W.type == E3: return declaredTo(A, seg) != provenSize(srcAnchor, dst) ? CHALLENGE_SUCCEEDS : CHALLENGE_FAILS
  // 3. 重执行 (E2)
  store = WitnessBackedStore(R_prev, W.reads, W.writes)     // 未证明的读 → trap → 挑战失败
  result = ChicoryInterpreter.execute(code(contract), input, store, gasLimit ≤ maxCallGas)
  C'_k = commit(result)
  return C'_k != C_k ? CHALLENGE_SUCCEEDS : CHALLENGE_FAILS
```

**有界性**：一次仲裁 ≤ `maxCallGas`（初始 10,000,000）的 WASM 解释执行 + ≤ `maxWitnessBytes` 的证明校验；主块 `setMain` 内最多处理 `maxChallengesPerHeight`（初始 4）个挑战，超出的顺延到下一高度（确定性队列，按 DFS 序）。

**锁外预计算**：CHALLENGE 块在 `tryToConnect` 导入时（尚未确认）即在锁外线程池计算裁决并缓存 `(challengeHash → verdict)`；`setMain` 时只应用缓存结果，缓存缺失（如节点重启）才在锁内重算。裁决只依赖 L1 已确认状态与见证内容，因此预计算与锁内重算结果相同；SP3 用"缓存与重算一致"的测试锁死。这样正常情况下主锁内的仲裁开销接近零，恶意挑战最多让锁外线程池忙碌。

### 10.4 经济

| 结果 | 认证者保证金 | 挑战者押金 |
|------|-------------|-----------|
| 挑战成功 | 罚没：50% → 挑战者，50% 销毁 | 退还 |
| 挑战失败 | 不变 | 没收 → 认证者 |

挑战押金下限 `minChallengeDeposit`（初始 minBond / 10）。

### 10.5 为什么认证者无法逃避

被挑战所需的一切都在 L1 DA：调用块、源锚定、承诺链；见证由挑战者自带。认证者扣留 receipts/状态只会让自己失去费用收入，不会阻止挑战。

---

## 11. 资产流与守恒

| 流向 | 机制 | L1 动作 |
|------|------|--------|
| 存入 | CALL 的 value | INPUT 用户 → OUTPUT 金库（现有结算） |
| 提现 | `withdraw` → outbox(L1) 条目 → 终局 → CLAIM | 金库 → 用户（系统划账，记 0x08 防重放） |
| 跨通道转值 | `send(value)` → outbox(dst) 条目 → 目标通道消费即入账 → 任意人 CLAIM(flags.bit0) | 金库(src) → 金库(dst) |
| gas | 通道内扣，归认证者 | 无 |

**不变量 I1**（每通道）：`Σ 通道内余额 + 出站在途 − 入站在途 == 金库 L1 余额`，其中"在途"以已终局锚定为界。
**不变量 I2**（L1）：金库余额只能因 CALL 结算增加、因带证明的 CLAIM 减少。
**不变量 I3**：CLAIM 只认 final 锚定 → 错误锚定造成的任何提现都在 W 内可被挑战化解。

---

## 12. 安全模型与威胁分析

| 威胁 | 缓解 |
|------|------|
| 认证者提交错误状态根 / 伪造提现 | 单步欺诈证明；CLAIM 只认 final；W 内 1 个诚实观察者即可 |
| 认证者审查/重排调用 | 不可能：排序在 L1 PoW，输入流强制且确定 |
| 认证者扣留数据 | 挑战不依赖认证者数据（§10.5） |
| 垃圾挑战 | 押金没收；每高度仲裁数上限 |
| 冷门通道无人观察 | 用户可自行运行执行者；`maxWithdrawPerWindow ≤ f(总保证金)`（可选参数）限制单窗口最大损失 |
| 分片链垃圾块 | 分片块无费用但只有被付费块 link 才有语义；付费块按片数付费；孤块池按现有规则淘汰 |
| 级联回滚攻击（源通道故意锚错让下游回滚） | 下游自选 D；D = W 免疫；源认证者被罚没 |
| L1 深 reorg | 通道按变更日志回滚；锚定 link 主块自然失效 |
| Chicory 解释器与编译器分歧 | 仲裁只用解释器；执行者分歧只产生可挑战锚定 |
| WASM 非确定性 | §8.1 白名单 + 插桩；部署期拒绝 |
| 资源耗尽（内存/栈/gas） | 硬上限 + trap；仲裁有界 |
| 部署者把通道 `maxCallGas` 设得过大（或为 0），使仲裁无界 / 通道永远跑不了调用 | L1 在 DEPLOY（新建通道）处强制 `1 ≤ maxCallGas ≤ 10,000,000`（= §17 的 `maxCallGas` 初值，只能下调；实现为 `LaneL1Processor.MAX_CALL_GAS_CAP`），越界记 `INVALID_FORMAT`，通道不注册。该检查先于代码链装配。`gasPriceNano` 与 `D` 由部署者自定，不设上下界 |

**信任假设汇总**：PoW 主链诚实多数（不变）；每条通道在任意 W 窗口内至少一个诚实观察者在线；出主块的矿池是事实上的排序者，其抢跑与短期审查能力与今天 XDAG 交易的情况相同，本设计不消除它（§20.1 S3）。

---

## 13. Reorg 与一致性不变量

1. L1 `unWindMain(h0)` → `unApplyBlock` 逐项反向 §6.2 的写操作（注册表、保证金、锚定链头、计数、已认领、系统划账）。
2. 执行者收到 `onUnwind(h0)` → 每个订阅通道回滚到 seq ≤ h0。
3. 重新 `setMain` 后按新输入流重放。
4. **属性测试**：对任意块序列与任意 unwind 点，`apply → unwind → apply` 的 L1 状态与通道状态等于直接 `apply`。

---

## 14. P2P 与订阅

| 消息码 | 名称 | 方向 | 内容 |
|--------|------|------|------|
| 0x1B | LANE_SUBSCRIPTIONS | 握手后互发 | 本节点订阅的 laneId 列表（路由提示） |
| 0x1C / 0x1D | LANE_SNAPSHOT_REQUEST / REPLY | 请求 (lane, seq, page) | 快照分页（KV 批 + 页哈希）；请求方核对根 == 该 seq 规范锚定 stateRoot |
| 0x1E / 0x1F | LANE_PROOF_REQUEST / REPLY | 请求 (lane, seq, kind, key) | outbox 段内容 + MMR 证明、outboxMap SMT 证明、C_k 开解、事件 |

- 通道块传播零新增：CALL/CHUNK/ANCHOR/… 走 `NEW_BLOCK` gossip 与 sum 树同步。
- 新订阅者引导：找最近 final 锚定 → 拉快照 → 校验 → 从 seq 重放本地 L1 输入流；无锚定通道从 DEPLOY 块重放。
- 执行者只对订阅通道保存 `LANE_EXEC` 状态；未订阅通道仅保存 `LANE_L1` 中的锚定索引。

---

## 15. 吞吐与性能

### 15.1 边界分析

| 层 | 上限 | 备注 |
|----|------|------|
| 执行 | 无 | 通道独立；每个执行者的吞吐 ≈ 核数 × 单核 WASM 吞吐；加通道加执行者即扩容 |
| 状态 | 无 | 仅存订阅通道 |
| 排序 + DA | 单节点导入带宽 | 每笔调用 = 512B 块 + 32B 承诺 ≈ 544B；10k 块/s ≈ 5.4MB/s |

### 15.2 L1 导入流水线（SP0）

- **锁外预验证池**：解析、哈希、EXT 校验、分片链结构、签名验证（`verifiedKeys` 缓存）在线程池完成；按 `laneId`（或 sender）分片的工作队列保证同通道顺序。
- **锁内最小化**：`tryToConnect` 只做存在性、link 校验、难度计算、DAG 插入、孤块池更新。
- **批量落盘**：RocksDB `WriteBatch`。
- **孤块池**：从单一 `MAX_ORPHAN_SIZE = 3750` 改为按通道/发送方的分队列与全局上限（初始 100k）。
- 基准目标：≥ 10k 块/s/节点（当前基线在 SP0 首先测量并写入文档）。

### 15.3 密度与 DA 的后续路线

- v1 可选 **BATCH 块**：一个付费块 link 一条分片链，链内打包多笔紧凑签名调用（≈110B/笔 → 3.7 笔/片），密度 ≈ 4×；批内顺序由打包者决定（软排序，用户仍可自发单笔块）。
- v2 **DA 采样**：分片链由部分节点全量存储 + 纠删码 + 采样，解除"所有节点存所有字节"。

---

## 16. 开发者面

### 16.1 RPC `lane_*`

| 方法 | 说明 |
|------|------|
| `lane_sendCall(rawBlocks[])` | 提交客户端签好的 CALL 块与分片链 |
| `lane_query(lane, contract, selector, args, view)` | 只读执行 `query`，`view ∈ {pending, confirmed, anchored, final}` |
| `lane_getReceipt(blockHash)` | 状态、gasUsed、返回数据、事件、所在 seq、四级确认状态 |
| `lane_getAnchor(lane, seq)` / `lane_getHead(lane)` | 锚定索引 |
| `lane_getState(lane, contract, key, view)` / `lane_getProof(...)` | 状态与 SMT 证明 |
| `lane_getBalance(lane, address, view)` | 通道内余额 |
| `lane_estimateGas(...)` | 在 `pending` 视图试执行 |
| `lane_subscribe(receipts | events | anchors)` | WebSocket 推送 |
| `lane_buildClaim(srcLane, seq, index)` | 组装 CLAIM 所需证明 |

原生 `xdag_*` 全部不变。

### 16.2 SDK 与工具

- Rust crate `xdag-contract`：`#[xdag::contract]` / `#[xdag::method(reentrant)]` 宏生成 selector 分发与参数编解码（编码 = 紧凑 borsh 风格，定义在 ABI 附录）；存储集合（Map/Vec/Set）；消息类型；单元测试用的内存宿主。
- 客户端库 `xdag-client`（TypeScript + Rust）：构造 CALL/DEPLOY 块与分片链、签名、CLAIM 证明组装、四级状态轮询。
- CLI：`xdag lane create|deploy|call|query|bond|unbond|anchor|challenge|claim|subscribe`。
- 参考 DApp：XRC-20 代币；单通道 DEX（池 + 代币同通道 → 原子撮合）；跨通道消息演示（D = 0 与 D = W 对照）。

---

## 17. 协议参数表（初始值，标注"待校准"者在 SP1/SP3 基准与经济分析后固化）

| 参数 | 初始值 | 说明 |
|------|--------|------|
| `lane.activationHeight` | devnet 0 / testnet, mainnet MAX | 硬分叉高度 |
| `W` | 32 主块（≈ 34 min） | 挑战窗口 = 终局延迟 = UNBOND 延迟 |
| `minBond` | 10,000 XDAG（待校准） | |
| `minChallengeDeposit` | minBond / 10 | |
| `slashSplit` | 50% 挑战者 / 50% 销毁 | |
| `D`（通道默认） | W | 部署时可设 0..W |
| `maxCallGas` | 10,000,000（待校准，只能下调） | 单次调用与单次仲裁上限。同时是**每通道配置的硬上界**：L1 在新建通道的 DEPLOY 处强制 `1 ≤ maxCallGas ≤ 10,000,000`，越界 → `INVALID_FORMAT`（§12） |
| `gasPerWitnessByte` | 10 | 见证比例计费系数，须满足 §8.3 不等式 |
| `maxWitnessBytes` | 1 MB（1,048,576 B） | 与 maxCallGas / gasPerWitnessByte 满足 §8.3 不等式 |
| `maxChallengesPerHeight` | 4 | |
| `maxInlineArgs` | 256 B | 由 512 B 块布局推导（`(16 − 8) × 32`），**不可配置**——没有对应的 conf 键 |
| `maxChunksPerChain` | 4096 | 4096 × 352 B ≈ 1.44 MB。与 `maxWasmBytes`、`chunkFee`、`activationHeight` 一样是**共识参数**：SP0a 给了 conf 键（`lane.chunk.maxPerChain` / `lane.wasm.maxBytes` / `lane.chunk.feeMilliXdag` / `lane.activation.height`）只为 devnet 与测试，**共享网络上绝不设置**——非默认值不会报错，只会静默分叉 `LANE_L1`；被覆盖时启动打 `warn` |
| `chunkFee` | 0.01 XDAG/片 | 归 PoW 矿工 |
| `maxWasmBytes` | 1 MB | |
| `maxMemoryPages` | 64（4 MB） | |
| `maxStackHeight` | 65,536 | |
| `maxCallDepth` | 16 | |
| `maxValueBytes` | 64 KB | 单个存储值 |
| `minGasPrice` | 1 nano/gas | |
| `K`（变更日志窗口） | 128 seq | |
| `snapshotInterval` | 64 seq | |
| `maxWithdrawPerWindow` | 开启，公式由 SP3 定（初始建议 ≤ 该通道总保证金 × 2 / W 窗口） | 纵深防御（§20.1 S2） |
| `orphanPoolLimit` | 100,000 | 替代 MAX_ORPHAN_SIZE |

---

## 18. 子项目分解与顺序

| # | 子项目 | 核心交付 | 依赖 |
|---|--------|---------|------|
| **SP0a** | 块格式与 L1 钩子（共识层） | `XDAG_FIELD_EXT` 编解码；7 种 kind 解析与校验；分片链；`LANE_L1` 存储 + `batchWrite`；注册表/合约表/代码库（引用计数）/输入索引；五个 setMain/applyBlock 钩子与对称 unapply；激活高度与 `LaneSpec`；`LaneActivation` 供钱包/RPC 门控；**快照扩展与 laneStateHash（§6.3）**；属性测试。详见 `2026-09-13-xdag-lane-sp0a-block-format-and-l1-hooks-design.md` | — |
| **SP0b** | 导入流水线与孤块池（节点本地） | `tryToConnect` 锁外并行预验证（签名、解析）；孤块池按通道/来源分队列、分片块配额与 TTL 淘汰、`orphanPoolLimit`；导入期分片费率策略；L1 导入基准（块/s 前后对比） | SP0a（可与 SP1 并行） |
| **SP1** | 通道执行引擎 | Chicory 集成；部署验证 + 插桩（含栈高度计量与保守 `-Xss` 无关上限）；宿主 ABI；SMT/MMR；**见证比例计费与 §8.3 不等式的属性测试**；输入流构建；确定性执行；变更日志/回滚/快照；软预执行；两模式一致性测试；gas 微基准 | SP0a |
| **SP2** | 资产流 | 金库记账；withdraw/outbox(L1)；CLAIM 校验与系统划账；跨通道转值对账；不变量 I1–I3 测试 | SP1 |
| **SP3** | 认证与仲裁 | BOND/UNBOND；锚定有效性与规范链；承诺链；终局 W；见证格式；E1/E2/E3 仲裁；**锁外预计算裁决缓存**；罚没；级联回滚 | SP1（与 SP2 并行） |
| **SP4** | 订阅与 P2P | 订阅集合（含 `lane.subscribeAll` 过渡模式，D6）；`0x1B–0x1F` 消息；快照同步；证明拉取；交付规则 D；执行者只跑订阅通道；多节点 devnet | SP2, SP3 |
| **SP5** | 开发者面 | `lane_*` RPC；Rust SDK；客户端库；CLI；三个参考 DApp；文档 | SP4 |
| SP6（v2） | 扩展 | 编织原子跨通道锚定（ANCHOR flags.bit0）；BATCH 密度块；DA 采样 | v1 上线后 |

顺序：SP0a → SP1 → SP2 / SP3（SP1 后可并行）→ SP4 → SP5；SP0b 与 SP1 并行。

**实施级总体设计与路线图**（各子项目的接口、数据结构、算法、测试、切片与里程碑）见 `2026-09-17-xdag-lane-contracts-program-design-and-roadmap.md`；SP0b 拆为 SP0b-1（基准 + 加固工单）→ SP0b-2（锁外预验证流水线）→ SP0b-3（孤块池分队列/配额/TTL + 导入期费率策略）。

每个子项目：brainstorming（细化 spec）→ writing-plans → TDD 实施 → 代码评审 → 合入 `dev-dag-contract`。

---

## 19. 测试策略

| 类别 | 内容 |
|------|------|
| 单元 / TDD | 每个子项目全程 TDD；编解码往返；SMT/MMR 证明正反例 |
| 确定性 | 同一输入流在解释器与编译模式得同根；插桩输出对 codeHash 唯一 |
| 属性 | apply/unwind/replay 相等；不变量 I1–I3 在随机操作序列下恒成立 |
| 对抗 | 错误锚定（E1/E2/E3 各类）必被挑战成功；正确锚定挑战必失败且押金没收；扣留数据不影响挑战；重复 CLAIM 被拒 |
| Reorg | 随机深度 unwind 后通道状态与金库守恒；锚定 link 主块失效 |
| 基准 | L1 导入块/s（并行化前后）；多通道执行核数扩展曲线；SMT 更新吞吐；单次仲裁耗时上限 |
| 端到端 | 3 节点 devnet、2 条通道、节点订阅不同通道；跨通道 DEX 演示；一次人为错误锚定的完整挑战-罚没流程 |

---

## 20. 风险与开放问题

分三档：**结构性**（由 XDAG 节奏与乐观模型决定，工程无法消除，只能缓解或接受）、**工程性**（可在子项目内解决，但必须现在设计进去）、**生态与交付**。

### 20.1 结构性风险

| # | 风险 | 影响 | 缓解 / 现状 |
|---|------|------|------------|
| S1 | **延迟**：主块 64s、确认约 2 epoch，调用要 2–3 分钟才进入通道输入流；跨通道再加 D，提现再加 W（≈ 34 min） | DeFi 体验明显弱于秒级链 | 软预执行只改善展示；终局时间不可缩短，除非改主链节奏（非目标）。诚实地把它写进产品预期 |
| S2 | **冷门通道无人看守**：安全前提是每条通道在 W 内有诚实观察者；金库 ≫ 保证金时理性认证者有作恶动机；终局锚定不可挑战，一旦得逞永久损失 | 长尾通道资金风险 | `maxWithdrawPerWindow ≤ f(总保证金)` 默认**开启**（改自"可选"，SP3 定公式）；钱包对无独立观察者的通道给出风险提示；社区运行公共观察者 |
| S3 | **矿池即排序者**：DFS 顺序由出主块的矿池决定，矿池天然拥有 DEX 抢跑权，也能拖延 CHALLENGE 块确认；XDAG 矿池集中度高，矿池很可能同时成为主要认证者 | MEV 与中心化压力 | 通道可选"epoch 内按块 hash 排序"的反 MEV 模式（v2 研究项）；W 取 32 而非更小，使拖延挑战需要长时间多数算力；把风险写入 §12 信任假设 |
| S4 | **可组合性碎片化与引力效应**：没有原子跨通道，代币与 DEX 会挤进同一条大通道，实际吞吐退化为单执行者上限，"无上限"被引力抵消 | 实践中的 TPS 远低于理论 | v2 编织原子跨通道锚定（ANCHOR flags.bit0 已预留）；SDK 提供跨通道代币/流动性模式；单通道执行引擎本身做多核并行（同通道内互不冲突的合约可并行，v2） |
| S5 | **级联回滚**：D=0 通道群在上游锚定被否决时连锁回滚，开发者要理解"已确认但可撤销"的状态语义 | DApp 难写对（Vite 教训） | 通道内同步作为主编程模型；SDK 只暴露 `confirmed/anchored/final` 三级明确语义；D=0 需部署者显式声明 |
| S6 | **经济参数无市场**：gasPrice 由部署者定死、L1 块费固定、保证金与罚没比例靠估计，激活后只能硬分叉调整 | 定价失真、参数僵化 | v2 引入通道级 gasPrice 治理（部署者可更新）与 L1 费率市场；激活前用 testnet 数据校准 |

### 20.2 工程性风险

| # | 风险 | 处理（已合入规格） |
|---|------|------------------|
| E1 | 见证体积超上限使某些调用不可挑战 | §8.3 见证比例计费 + `maxCallGas / gasPerWitnessByte ≤ maxWitnessBytes` 硬不等式，SP1 属性测试锁死 |
| E2 | 仲裁在主锁内跑 WASM，恶意挑战让节点卡顿 | `maxCallGas` 降为 10M、每高度 4 个；§10.3 锁外预计算裁决缓存 |
| E3 | 快照启动节点缺 `LANE_L1` 导致分叉 | §6.3 快照扩展 + `laneStateHash`，纳入 SP0 |
| E4 | Chicory 成熟度：1.x、无内建燃料、解释器 Java 递归受 `-Xss` 影响、编译模式遇 JVM 64KB 方法上限退回解释器 | 栈高度插桩与保守上限（SP1）；仲裁只用解释器；性能基准决定 `maxCallGas` 是否再下调；保留更换运行时的接口边界（执行器只依赖一个 `WasmEngine` 接口） |
| E5 | 激活前资金陷阱 | §5.5 钱包/RPC 门控；协议只从激活起记录输入 |
| E6 | 免费分片块垃圾攻击填满孤块池 | 孤块池按来源 IP/地址配额 + 分片块 TTL 淘汰（SP0）；被付费块引用后才落盘 |
| E7 | L1 存储无界增长：每笔调用 512B 永久全网保存，1k tx/s ≈ 44 GB/天，XDAG 无剪枝 | v1 内：已终局锚定之前的分片链载荷允许本地剪枝为 hash（保留块 header 以维持 DAG 结构与 sum 同步）；v2 DA 采样。这是运营上的硬伤，必须在 testnet 期间测出增长曲线 |
| E8 | L1 导入带宽是"无上限"的最后瓶颈 | §15.2 流水线；BATCH 密度块；v2 DA 采样 |
| E9 | 保证金经济：`minBond` 与通道可提现额的关系 | SP3 经济分析；S2 的提现限速默认开启 |
| E10 | SMT 哈希 SHA-256 vs blake3（开放问题 O1） | SP1 基准后在激活前定死 |
| E11 | **字段码 0x0F 与 dev-evm 冲突**：`dev-evm` 分支把 `0x0F` 用作 `XDAG_FIELD_EVM_TX_REF`（原始 32B EVM 交易哈希，字段写在 remark **之前**），本方向把同一个码点用作 `XDAG_FIELD_EXT`（写在 remark **之后**）。两条分支都还没合进 `develop` | **必须在任一分支合入 `develop` 之前显式定夺**，不能靠"先到先得"：先合的那条占住 0x0F，另一条要么改码点、要么改语义。按 D3 的决定 dev-evm 归档，但归档动作本身必须落实并记录 |
| E12 | 快照节点缺少激活前后的老分片原始字节，付费块引用的分片链可能在本地拿不到 | 年龄规则（§5.3）把窗口限制在两个 epoch；缺失的分片块在快照节点上**连 `BlockInfo` 都不存在**（快照只保留有公钥或余额非零的块），因此 link 它的付费块得到 `NO_PARENT`，由 `SyncManager` 按需递归拉取补齐。`tryToConnect` 没有任何以快照高度为界的拒绝规则，所以两类节点的裁决必然一致。**残余风险是纯活性**：`SyncManager` 对同一个 hash 有 64 s 的重请求节流，pending 集合满时随机淘汰，"最终会拉到"是尽力而为；拉不到的后果只是这个节点跟不上，不是分叉 |
| E13 | **P3（写必有成对反写）存在两处已知缺口**，SP1 不能无条件假设它成立 | (a) `BlockchainImpl.unApplyBlock` 会跳过 `BI_MAIN_REF` 已置而 `ref == null` 的块（`setMain` 在 DFS 里抛异常、或走 `mainBlockFee < 0` 的提前 `return` 时会留下这种块），其子块已提交的 `LANE_L1` 记录永不撤销——这是既有的值结算不对称（同样这些块的余额也保留着），不是通道层引入的，但 SP0a **放大了它的触发面**：`LANE_L1` 是第二个 RocksDB，`applyBlock` 的 DFS 里会 `commit` 它，且记录损坏是故意的 fail-stop，DFS 里因此多了一类抛出点；`checkMain` 吞掉异常，留下的主块 `BI_MAIN`/高度/奖励/`nmain++` 都已做完而 `updateBlockRef` 没走到，永远 unwind 不了。SP0b 负责启动一致性检查与让这个跳过可修复；(b) `BI_APPLIED` 在 `LANE_L1` 提交之前就已落盘，两个库之间没有原子性也没有启动重放，崩在中间会丢掉那个块的通道记录。(b) 与 `ADDRESS`/`BLOCK` 两库本来就不原子是同一档次，**接受**；SP0b 或快照工具可加一道启动时的 `LANE_L1` vs `BLOCK` 一致性检查 |

### 20.3 生态与交付风险

| # | 风险 | 处理 |
|---|------|------|
| D1 | **冷启动**：无 Hardhat/MetaMask/Etherscan，只有 Rust SDK；NEAR、Polkadot 最终都补回了 EVM 层 | 通道抽象与 VM 无关：v2 可以引入"EVM 通道"（把 dev-evm 的执行器装进一条通道），不违背 L1 设计；v1 先做 Rust SDK + AssemblyScript |
| D2 | **交付风险**："一步到位分片"让 v1 覆盖 SP0–SP5，任一子项目卡住整个方向无法上线 | **已决定（2026-09-13）纳入 v1**：SP4 提供 `lane.subscribeAll` 配置，节点订阅全部通道时行为退化为"全复制并行执行"，可作为过渡模式先上 testnet；分片订阅、认证与仲裁在同一代码路径上逐步启用 |
| D3 | 硬分叉治理与 dev-evm 归档；若主网仍有非 xdagj 实现需同步实现 EXT 语义 | 与社区沟通；确认主网节点实现构成。**归档必须在合入 `develop` 之前落实**：两条分支占用同一个字段码 0x0F，见 §20.2 E11 |
| D4 | 参数编码规范（borsh 风格）细节（O2）；事件索引与浏览器（O3） | SP5 ABI 附录；后续生态项目 |


## 21. 术语表

| 术语 | 含义 |
|------|------|
| 通道 Lane | 状态分区 + 执行单元；有唯一 20B laneId，同时是其金库的 L1 地址 |
| 输入流 | 通道在某高度必须消费的有序输入（消息 ++ CALL/DEPLOY） |
| seq | 通道步号 = 主块高度 |
| 承诺 C_k | 第 k 个输入执行后的 32B 承诺 |
| 锚定 Anchor | 认证者对某 seq 状态的签名声明 |
| 规范锚定 | 每 (lane, seq) 中 L1 确认序第一个有效锚定 |
| 终局 final | 锚定确认后经过 W 且未被否决 |
| 段 | 源锚定新增的发往某目标通道的消息区间 `[from, to)` |
| D | 目标通道对入站段的交付延迟 |
| W | 挑战窗口 |
| 见证 | 单步重执行所需的全部证明 |
| 金库 | 通道的 L1 地址（无私钥）及其余额 |
