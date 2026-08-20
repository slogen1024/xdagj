# XDAG 智能合约（EVM）设计与实施

> 适用版本：`dev-evm` 分支 @ `a3cacd36`（2026-07-31，JDK 21 全量测试 340/340 绿）
> 关注范围：**从"XDAG 的区块形成与存储逻辑"出发，完整推导"如何在 DAG 链上实现智能合约"的设计思考，以及当前分支上已落地的全部实现逻辑**。
> 定位：本文取代早期的 `evm-integration.md`（那篇写于 EVM 还是"孤岛"的 A+B1 阶段）；如今 A→B→C 全部完成，EVM 已接入共识、P2P、挖矿与 RPC。
> 交叉引用：[区块生成](block-generation.md)、[交易验证与上链](transaction-validation-and-import.md)、[共识与主链选择](consensus-and-mainchain-selection.md)、[存储](snapshot-and-storage.md)、[存储深剖](storage-deep-dive.md)、[P2P 同步](p2p-sync.md)、[RPC 与钱包](rpc-and-wallet.md)。

---

## 0. 总览：问题与最终形态

**问题**：XDAG 是 DAG 结构的 PoW 链——512 字节定长区块、无全局账户状态树、无 gas 市场、区块间只有偏序关系。以太坊式智能合约需要的东西它一样都没有：任意长度的交易载荷、全序执行、世界状态与状态承诺、gas 计费、兼容钱包的 RPC。

**最终形态**（一句话）：**内嵌 Hyperledger Besu Shanghai EVM；EVM 交易以"32 字节引用 + 旁路载荷"的方式上链（守住 512 字节墙）；执行顺序 = 主链确认序 × applyBlock DFS 序（复用 XDAG 既有的确定性）；世界状态落 RocksDB 平面 KV；状态承诺用"链式 delta 根"并经 P2P gossip 做跨节点分歧检测；gas 以 EVM wei 结算并燃烧；对外暴露 19 个 eth_* JSON-RPC 方法，MetaMask/Hardhat 可直接部署使用 USDT。**

```
 MetaMask/Hardhat
      │ eth_sendRawTransaction (EIP-155 RLP)
      ▼
 ┌─────────────┐   gossip 0x1B    ┌─────────────┐
 │  EvmTxPool  │◄────────────────►│  对等节点    │
 │ (mempool)   │                  └─────────────┘
 └──────┬──────┘
        │ selectEvmTxRef: 取 gasPrice 最高的未执行 tx 的 hash
        ▼
 ┌──────────────────────────┐
 │ 主块 (512B, PoW)          │  field 0x0F = EVM_TX_REF (32B tx hash)
 │  载荷本体存 EVM_TX 库     │  ← hash 进 calcHash，被 PoW 承诺
 └──────┬───────────────────┘
        │ tryToConnect → checkNewMain → setMain（主链确认 = 终局性）
        ▼
 ┌──────────────────────────┐
 │ applyBlock DFS 收集 refs  │ → EvmBlockProcessor.processMainBlock
 │  → executeList 逐笔执行   │ → 收据 + 链式状态根 + 检查点 (EVM_META)
 │  → 世界状态落 EVM_STATE   │ → 状态根 gossip 0x1E 跨节点比对
 └──────────────────────────┘
```

---

## 1. 地基一：区块形成逻辑给智能合约提供了什么

（详细机制见 [block-generation.md](block-generation.md)，此处只提取与智能合约直接相关的事实。）

### 1.1 512 字节墙与 4-bit 字段类型

区块物理形态固定 **512 字节 = 16 × 32 字节字段**（`XdagBlock.java:37`）。field[0] 是头（`transportHeader(8B) ‖ type(8B) ‖ timestamp(8B) ‖ fee(8B)`，小端，`Block.java:266-270`），其中 type 是 64-bit 类型掩码，**每 4 bit 描述一个字段的语义**：

```java
// XdagBlock.java:82
return (byte) (type >> (n << 2) & 0xf);   // 第 n 个 field 的 4-bit 类型码
```

字段类型码全表（`XdagField.java:70-126`）——**16 个码位已全部用尽**：

| 码 | 类型 | 码 | 类型 |
|----|------|----|------|
| 0x00 | NONCE | 0x08 | HEAD_TEST |
| 0x01 | HEAD | 0x09 | REMARK |
| 0x02 | IN（旧输入） | 0x0A | SNAPSHOT |
| 0x03 | OUT（旧输出） | 0x0B | COINBASE |
| 0x04 | SIGN_IN | 0x0C | INPUT（新输入） |
| 0x05 | SIGN_OUT | 0x0D | OUTPUT（新输出） |
| 0x06 | PUBLIC_KEY_0 | 0x0E | TRANSACTION_NONCE |
| 0x07 | PUBLIC_KEY_1 | **0x0F** | **EVM_TX_REF（本项目新增，占掉最后一个码位）** |

两条对智能合约生死攸关的推论：

1. **单块有效载荷只有几百字节**（16 字段减去头/签名/nonce/coinbase 等），任何真实 EVM 交易（USDT 部署 initcode 有数 KB）都塞不进——这直接决定了"载荷按引用"的上链方案（§3.2 D3）。
2. **4-bit 类型空间已耗尽**——0x0F 给了 EVM_TX_REF 后再无空位。这在后来堵死了"把 EVM 状态根作为原生字段锚进区块"的路（§6.3），是整个设计中最刚性的历史包袱。

### 1.2 XDAG 现成的确定性资产

智能合约需要"所有节点以相同顺序执行相同交易"。XDAG 虽是 DAG，但恰好已内建了三层可复用的确定性：

| 资产 | 来源 | 智能合约怎么用 |
|------|------|----------------|
| **主链全序** | 每 epoch（~64s，`XdagTime.java:62` `t>>16`）PoW 产出一个主块，`checkNewMain → setMain`（`BlockchainImpl.java:1302`）按高度单调确认 | EVM 执行的"区块高度"= 主块高度；**只在 setMain 时执行**，主链确认 = EVM 终局性 |
| **DFS 确定序** | `applyBlock`（`:1051`）对主块 link 的子图做递归深度优先结算，所有节点遍历顺序一致 | 一个主块确认的多笔 EVM 交易，按 DFS 访问序排定执行顺序（§5.3） |
| **PoW 承诺** | 任何字段都参与 `calcHash()`（doubleSha256，`Block.java:240-245`） | EVM_TX_REF 写进区块即被 PoW 承诺，改一个字节 PoW 作废 |
| **回滚通道** | 分叉重组走 `unWindMain → unApplyBlock`（`:994`） | EVM 状态回滚挂在同一通道上（§5.6） |

### 1.3 出块与导入路径上的 EVM 挂载点（先给全景，§5 展开）

- **出块**：`createMainBlock()`（`BlockchainImpl.java:1439-1472`）在装完 pretop/coinbase/orphans 后，若还有空字段，调 `selectEvmTxRef(16 - res - orphans.size())`（`:1469`）打包一笔 EVM 交易引用。
- **导入**：`tryToConnect()`（`:287-596`）两阶段校验/连接不感知 EVM（引用字段不是 link，不参与金额校验）；
- **确认**：`setMain()`（`:1302-1344`）里 `applyBlock` DFS 收集 refs（`:1318`），随后 `evmProcessor.processMainBlock(...)`（`:1332-1333`）——**这是共识进入 EVM 的唯一入口**；
- **回滚**：`unWindMain()`（`:994-1035`）算出最低被回退主块高度，**只调一次** `rollbackTo(lowestUnwound-1)`（`:1029-1033`）。

---

## 2. 地基二：存储逻辑给智能合约提供了什么

（详细机制见 [storage-deep-dive.md](storage-deep-dive.md)。）

XDAG 的存储范式是：**每个 `DatabaseName` 一个独立 RocksDB 实例（`RocksdbFactory.getDB`，`RocksdbFactory.java:42-55`），实例内用 1 字节前缀划分键空间**。原生已有 INDEX/BLOCK/TIME/ORPHANIND/SNAPSHOT/ADDRESS/TXHISTORY 七库。EVM 沿用同一范式新增三库（`DatabaseName.java:60/66/72`）：

| 库 | 内容 | 键布局 |
|----|------|--------|
| **EVM_STATE** | 世界状态（账户/代码/storage） | `0x00‖addr(20)→账户72B`；`0x01‖codeHash(32)→code`；`0x02‖addr(20)‖slot(32)→值(32)`；`0x03`→创世标记 |
| **EVM_TX** | 交易载荷（签名 RLP 原文，内容寻址） | `0x00‖txHash(32)→raw RLP`（key=keccak(value)，写入天然幂等） |
| **EVM_META** | 执行元数据 | `0x00‖height→检查点76B`；`0x01‖txHash→收据RLP`；`0x02‖height→有序txHash列表`；`0x03‖height→pending队列`；`0x04‖txHash→height(8)‖index(4)反向索引` |

关键原语：**`KVSource.batchWrite(puts, deletes)`**——接口默认方法（`KVSource.java:71-78`）顺序写，`RocksdbKVSource` 用真 `WriteBatch` 覆盖为原子写（`RocksdbKVSource.java:295-316`）。EVM 世界状态的一次 commit 就是一次 batchWrite，**整个状态 delta 要么全落盘要么全不落**（崩溃安全）。

另一关键原语：**`RocksdbKVSource.reset()`**（`:421-437`，写锁内 close→删目录→init）——reorg 回滚的"擦盘重放"依赖它（§5.6）。

---

## 3. 设计思考：DAG 上实现智能合约的五大难题与决策记录

### 3.1 五大难题

1. **512 字节墙**：EVM 交易任意长，区块定长且码位紧张。
2. **偏序 vs 全序**：DAG 区块间只有 link 偏序；EVM 状态机要求全序执行。
3. **世界状态与承诺**：XDAG 只有 UTXO 式区块结算与地址余额，没有账户状态树，更没有状态根让节点互证"世界状态一致"。
4. **费用与资源**：EVM 无 gas 结算就是免费计算 DoS 面；但 XDAG 原生余额（nano，10⁹）与 EVM wei（10¹⁸）是两个世界，谁来给 gas 付钱？
5. **数据可用性（DA）**：载荷不在块内，节点可能拿到块却拿不到载荷——执行会分叉还是停摆？

### 3.2 决策记录（每条含被否决的备选）

| # | 决策 | 备选与否决理由 |
|---|------|----------------|
| **D1** | **内嵌 Besu EVM 26.5.0**（`besu-evm`+`besu-datatypes`，Shanghai，JDK21 安全的最新配对；仓库 hyperledger.jfrog.io 非 Maven Central） | 复活 `feature/new-evm` 自研解释器：落后 develop ~4 年/943 commits、只到 Constantinople、无 PUSH0、从未合并——维护成本与正确性风险双输 |
| **D2** | **锁 Shanghai 分叉**：启用 PUSH0（现代 Solidity 必需），保留 legacy gas 语义，无 blob/KZG | 更新分叉（Cancun+）带来 blob 机制包袱；更老的跑不了新 solc 产物 |
| **D3** | **R1 载荷按引用**：签名 RLP 存 EVM_TX 库（内容寻址），区块只携带 32 字节 hash（新字段 0x0F），载荷走 P2P 旁路 | R2 内联限长 calldata：几百字节部署不了真合约，为 USDT 否决；R3 独立 EVM 排序层（mempool→"EVM 块"锚定主块）：要第二套确定性共识，最复杂最险 |
| **D4** | **执行序 = (主块高度, applyBlock DFS 序)**；只在 `setMain` 执行（终局性对齐），不做投机执行 | 导入即执行（tryToConnect）：孤块/分叉块会执行又回滚，状态管理爆炸；独立排序层见 R3 |
| **D5** | **世界状态 = RocksDB 平面 KV**（EvmStateSchema 三前缀），非 MPT | 引 Besu 的 Bonsai/Forest trie 需 `besu-ethereum` 大依赖（JDK21 兼容风险），且 v1 不需要 per-account proof |
| **D6** | **状态承诺 = 链式 delta 根**：`root_h = keccak(root_prev ‖ height ‖ per-tx(txHash,status,gasUsed)… ‖ stateDelta_h)`，origin = genesisRoot；跨节点用 **P2P gossip（0x1E）做分歧检测**（v1 只检测不硬拒） | 绝对状态 MPT 根：同 D5；**原生区块字段锚定**（把 (height,root) 打进新字段类型）：设计已批准但被 §1.1 的 4-bit 码位耗尽堵死——需要块格式硬分叉才有空位，留作后路 |
| **D7** | **gas 用 EVM wei 结算（Path α）**：准入查 `balance ≥ value + gasLimit×gasPrice`；执行先全额扣 maxFee、跑完退未用、净费**燃烧**（不给 coinbase，P2 再路由） | ADR-007"计量不结算"（v1 曾用）：免费 30M gas/块 + 零成本 Sybil 塞池，审计定为 HIGH 经济漏洞后废除；原生 XDAG 付 gas：要先打通 10⁹↔10¹⁸ 桥，改动面大 |
| **D8** | **资金上桥 = 配置化创世分配** `evm.alloc`（HOCON，仅 devnet 预注资） | 原生↔EVM 双向桥：主网前不需要，工程量与攻击面大得多 |
| **D9** | **回滚 = Option-A 擦盘重放**：`removeAbove` + `stateStore.reset()` + 重播 EVM_META 的逐高度 tx 列表，并自检重放根==存根 | 逐笔 undo 日志：EVM 状态 delta 复杂（SELFDESTRUCT/clearStorage/嵌套调用），undo 正确性难证；重放以存储换正确性，且 reorg 罕见 |
| **D10** | **DA 缺失 = 停摆等待（I4 stall-and-defer）**：缺载荷的主块进持久化 pending 队列，后续高度排队，blob 到齐按高度升序 drain；15s 定时补拉 | 跳过不执行（v1 曾用）：两个节点对"blob 是否在手"看法不同就永久状态分叉——缺载荷节点应当是"落后"而非"分歧" |
| **D11** | **兼容层 = 原样 eth_* JSON-RPC**（19 方法），地址=keccak(pubkey)[12:]，txHash=keccak(rawRlp)，EIP-155 chainId（devnet 51966/0xCAFE，mainnet 51964、testnet 51965 预留） | 自定义 xdag_evm_* API：MetaMask/Hardhat/ethers 全都不认，兼容是硬需求 |

### 3.3 一条流水线看全部决策

```
EIP-155 tx (D11) ──► EvmTxPool 准入 (D7 余额含费) ──► 载荷入 EVM_TX + hash 上块 (D3, 0x0F)
      ──► PoW 承诺 ──► setMain 终局 (D4) ──► DFS 序执行 (D4) ──► Besu Shanghai (D1/D2)
      ──► wei 扣费/退款/燃烧 (D7, 资金来自 D8 创世注资) ──► 状态落 EVM_STATE (D5)
      ──► 链式根 + gossip 比对 (D6) ──► reorg 时擦盘重放 (D9) ──► 缺 blob 停摆等待 (D10)
```

---

## 4. 实现：交易层（`io.xdag.evm.tx`）

### 4.1 `EvmTransaction` — EIP-155 编解码与恢复

字段：`nonce, gasPrice, gasLimit, to(Optional), value, payload, chainId, signature, rawRlp`（`EvmTransaction.java:57-81`）。

- **decode**（`:96-127`）：解 `[nonce,gasPrice,gasLimit,to,value,data,v,r,s]`；**拒绝无保护交易**（v<35，`:112-114`）；**强制 EIP-2 low-s**（`s > halfCurveOrder` 拒绝，`:115-120`，防延展性——同一笔交易只有一个合法 hash）；从 v 恢复 chainId+recId。
- **signingHash**（`:129-139`）：keccak(`[…, chainId, 0, 0]`)，标准 EIP-155。
- **getSender**（`:175`）：`ecrecover` 后 `Address.extract(pubKey)`，volatile 缓存。
- **getHash**（`:187`）：`keccak256(rawRlp)`——**保留进线原始字节作为规范编码**，与以太坊 txHash 完全一致，重广播不变。

`EvmAddress.fromPublicKey`（`EvmAddress.java:50-57`）：`keccak256(pubkey去0x04前缀的64字节)[12:32]`——真 Keccak（Besu `Hash.keccak256`），与 MetaMask 显示的地址一致；**别与 XDAG 区块的 doubleSha256 混淆**。

### 4.2 `IntrinsicGas` — EIP-2028/3860

`compute(payload, isCreate)`（`IntrinsicGas.java:54-68`）：21000 基础 + 零字节×4 + 非零×16；创建再加 32000 + 2×⌈initcode/32⌉；initcode > 49152 直接抛（EIP-3860 上限）。

### 4.3 `EvmTxPool` — mempool 准入与排序

`add(rawRlp)`（`EvmTxPool.java:89-166`，synchronized）按序闸门，对应 `AddResult` 12 个值（`:53-57`）：

```
逐出过期(TTL 1h) → RLP 解码 → chainId → 恢复 sender → gasLimit ≤ blockGasLimit
→ intrinsic gas 校验 → gasPrice ≥ minGasPrice
→ 开只读 RocksDbWorldUpdater 查账户:
     nonce 必须 == 账户 nonce（严格相等，无排队）
     balance ≥ value + gasLimit×gasPrice          ← D7 关键：费也要付得起
→ 重复 hash → DUPLICATE
→ 同 sender 已有: 同 nonce 需更高 gasPrice 才可替换(replace-by-fee)，否则 UNDERPRICED；
                  nonce 已过时则逐出旧的
→ 池满(4096) 且新 sender → POOL_FULL
→ 入池 + 载荷落 EVM_TX
```

选择 API：`selectTransactions(maxCount)`（`:169-177`）按 **gasPrice 降序**（插入序破平）——矿工打包用（§5.2）。每 sender 只挂一笔（v1 策略）。

---

## 5. 实现：载体块与共识接入

### 5.1 `XDAG_FIELD_EVM_TX_REF`（0x0F）——引用如何长在区块上

- **不是 link**：不装 Address/hashlow，装**原样大端 32 字节 keccak tx hash**；构造（`Block.java` 11 参构造器 `:112-211`，evmTxRef 在 links 之后、remark 之前 setType `:167-170`）、编码（`getEncodedBody :387-389`，**不做 reverse**，与 link 的大小端翻转不同！）、解析（`parse :277-279`）三处对称。
- **PoW 承诺**：参与 `toBytes()` → `calcHash()` → 也进 `signOut` 摘要——引用被算力与签名双重钉死。
- **导入无感**：`tryToConnect` 的引用校验只遍历 links，0x0F 字段不进金额/时间校验——EVM 引用坏了也不会拖垮原生导入。

### 5.2 矿工打包：`selectEvmTxRef`

`createMainBlock`（`BlockchainImpl.java:1439-1472`）装完常规字段后：

```java
// BlockchainImpl.java:1480-1497（整体套 try/catch——EVM 出任何错，挖矿照常，只是不带 ref）
selectEvmTxRef(freeFields):
    if freeFields < 1 || evmTxPool == null: return null
    for tx in evmTxPool.selectTransactions(8):        // gasPrice 降序前 8
        if metaStore.getReceipt(tx.hash).isPresent(): continue   // 已执行过的不重复打包
        return tx.hash
    return null
```

每主块自动打包**一笔**（v1 吞吐取舍）；但注意：**任何区块**（含手工构造的交易块）都可携带 0x0F 字段，一个主块确认的子图里可能收集到多笔 ref。

### 5.3 共识三挂载点（`BlockchainImpl`）

```java
// ① 收集：applyBlock(flag, block, evmRefs)  :1051
//    两处 BI_APPLIED 落点（:1061 无引用块 / :1180 结算完输入输出后）都调
//    collectEvmRef(:1199-1203)——把 block.evmTxRef 追加进 evmRefs。
//    DFS 递归序在所有节点一致 ⇒ evmRefs 顺序全网一致。

// ② 执行：setMain(block)  :1302-1344
evmRefs = new ArrayList<>()
applyBlock(true, block, evmRefs)                       // :1318
if (evmProcessor != null && !evmRefs.isEmpty())        // :1329-1334
    evmProcessor.processMainBlock(evmRefs, mainNumber,
        xdagTimestampToMs(block.timestamp)/1000,       // 秒级时间戳持久化，重放确定
        block.hash)

// ③ 回滚：unWindMain(block)  :994-1035
//    记录 lowestUnwoundMainHeight（:1009-1014），循环结束后
//    只调一次 evmProcessor.rollbackTo(lowest-1)（:1029-1033）
//    ——N 个主块被回退也只有一次擦盘重放，O(n) 而非 O(n²)。
```

### 5.4 `EvmBlockProcessor` — 执行主流程

（`io.xdag.evm.EvmBlockProcessor`，全部关键方法 synchronized。）

**入口 `processMainBlock`（`:139-156`）**：

```
txRefs 空 → 直接返回
height < activationHeight → 忽略（硬分叉门，:144-149）
已有 pending 高度 或 任一 blob 不在 EVM_TX → metaStore.putPending(...) 停摆挂队（D10）
否则 → executeAndCheckpoint(...)
```

**`executeAndCheckpoint`（`:227-266`）**：

```
seedGenesisIfAbsent()                       // 创世注资，标记幂等（§9）
候选过滤: 同块内去重；已在主链执行过的 ref 跳过（防收据被覆盖）；blob 缺失兜底跳过
outcome = executeList(candidates, height, timestampSeconds, latestRoot())
无一执行 → 不落任何记录
否则 → putHeightRecord(height, root, blockHash, count, ts) + putTxList(height, executed)
        （putTxList 同步写 0x04 txHash→(height,index) 反向索引——它是该索引唯一写者）
```

**`executeList`（`:364-417`）——链式根在这里折叠**：

```
root = new RocksDbWorldUpdater(stateStore)            // 每主块一个根 updater
digest = [previousRoot, height(8B)]                   // Phase 2：高度也进承诺
budget = blockGasLimit                                // 每主块聚合 gas 预算
for txHash in txHashes:
    if tx.gasLimit > budget: skip（不出收据，留给后面的主块）  // DoS 上界，全网一致
    budget -= tx.gasLimit
    receipt = executeOne(root, rawRlp, height, ts)
    putReceipt(txHash, receipt)
    digest += txHash ‖ status ‖ gasUsed
stateDelta = root.commitAndDigest()                   // 世界状态 delta 摘要（§6.2）
digest += stateDelta
chainedRoot = keccak256(concat(digest))
```

**`executeOne`（`:420-525`）——单笔执行与 gas 结算（D7 落地）**：

```
解码/chainId/恢复 sender/gasLimit≤blockGasLimit/intrinsic gas    失败→ validationFailure
gasPrice == 0 或 < minGasPrice → 拒绝（:453-456，池外手工载体块也逃不过执行期闸门）
nonce == 账户 nonce？ balance ≥ value + maxFee？                  失败→ status-0 收据，不动状态
── 以下整体套 try/catch（C1 防线：EVM 任何异常都不能逃进 setMain 砸共识）──
adjustBalance(root, sender, -maxFee)      // 全额预扣 gasLimit×gasPrice（revert/OOG 也收费）
messageGas = gasLimit - intrinsicGas
创建:  messageGas ≤ 0 → 记 nonce++，失败收据
       否则 executor.deploy(root.updater(), ...)      // 部署内部自增 nonce
调用:  先在 root 上 nonce++（revert 也要涨——以太坊语义）
       messageGas ≤ 0 → zeroGasCall（对无码地址纯转账成功；有码则 OOG 失败）:568-582
       否则 executor.call(root.updater(), ...)
adjustBalance(root, sender, +(gasLimit - gasUsed)×gasPrice)   // 退未用
// 净费 gasUsed×gasPrice 无人收取 = 燃烧（v1；coinbase 路由是 P2）
catch RuntimeException → status-0 收据（adjustBalance 下溢也在此被拦，:534-542）
```

预扣款为何在子 updater 分层下仍正确：executor 的 child updater 会快照**已被扣款的** root 余额，成功 commit 时把含扣款的绝对值写回——扣款嵌在快照里，不会被覆盖丢失（审计已逐路径验证）。

### 5.5 停摆队列（I4/D10）

- `putPending`：`0x03‖height → blockHash(32)‖ts(8)‖refs…` 持久化（重启不丢）。
- `onBlobsAvailable()`（`:164-176`）：按高度**升序** drain，遇到仍缺 blob 的高度即停（保序）。
- `pendingMissingBlobHashes()`（`:185`）喂给 P2P 15 秒补拉（§7.2）。
- 语义：缺载荷节点是**落后**（behind），blob 传到即收敛；不是**分歧**（diverged）。测试钉死：停摆后 drain 的节点与从未停摆的节点链式根逐字节相同。

### 5.6 reorg 回滚：`rollbackTo`（`:273-295`）

```
metaStore.removeAbove(height)   // 删 height 之上的检查点/tx列表/收据/反向索引/pending
stateStore.reset()              // EVM_STATE 整库擦除（连创世标记一起没了）
seedGenesisIfAbsent()           // 先恢复创世注资，否则重放交易付不起 gas
for h in txListHeights() 升序:  // EVM_META 里存的就是"重放脚本"
    outcome = executeList(getTxList(h), h, 存的时间戳, 链式前根)
    if outcome.root != 存的根: log.error(...)   // 重放自检：抓非确定性/损坏
```

正确性的两根支柱：EVM_TX 内容寻址（载荷永不丢/不被污染）+ EVM_META 的逐高度有序 tx 列表与时间戳（TIMESTAMP 操作码重放确定）。

---

## 6. 实现：世界状态与链式状态根

### 6.1 嵌套 `RocksDbWorldUpdater` / `RocksDbAccount`

镜像 Besu SimpleWorld/SimpleAccount 的嵌套 updater 模式（child 失败 revert 只丢本层、成功 commit 上浮、root commit 才落盘——这就是 EVM 嵌套调用回滚语义）：

- root `getAccount`（`RocksDbWorldUpdater.java:92-123`）：缓存未命中从 EVM_STATE 懒加载 72 字节账户记录 + 按 codeHash 懒加载 code；child 从 parent 拷贝。
- `createAccount`（`:126-145`）内置 **S-28 复活防护**：地址曾在本 updater 被删、或库里还残留旧 storage 槽 → 先 `clearStorage()`，防 SELFDESTRUCT 后重建的合约"继承"旧存储。
- `RocksDbAccount.getOriginalStorageValue`（`RocksDbAccount.java:127-136`）：child 委托 parent、root 读库——original 值决定 warm/cold gas 与退款，读错即 gas 记账错。
- child `commit()`（`:205-220`）合并 nonce/balance/**code**/storage 到 parent（注意：Besu 自家 `SimpleAccount.commit` 不合并 code，所以测试里 `setCode` 必须给 root——本实现无此坑）。

### 6.2 `commitAndDigest` — 状态 delta 摘要（`:185-244`）

root 提交时构造 `puts: Map` 与 `deletes: TreeSet(compareUnsigned)`（**内容寻址**，防同一 slot 既被清零又被 clearStorage 扫到而重复计入——根的单射性修复 f632d3f0）：

```
删除的账户 → 账户键 + 其全部 storage 槽入 deletes
存活账户   → 账户 72B、code（非空时）、每个改动槽（零值→delete，非零→put）
clearStorage → 扫库里持久化槽，未被本次重写的全部入 deletes
digest = keccak( 0x01 ‖ sorted(puts, 无符号字典序) 每项 len(8BE)‖key ‖ len‖value
               ‖ 0x00 ‖ sorted(deletes) 每项 len‖key )        // 域分离 + 长度定界，无歧义
store.batchWrite(puts, deletes)                                 // 摘要覆盖的恰是落盘的
return digest        // child 永远返回 Bytes32.ZERO——只有 root 摘要
```

### 6.3 链式状态根：Phase 1 → Phase 2

演进三步（每步修一个盲区）：

1. **最初**（B2b）：根只链 `(txHash,status,gasUsed)`——两个节点余额分叉但执行日志相同 ⇒ 根相同，**抓不到纯状态分叉**（审计定 HIGH）。
2. **Phase 1**（e9a41d08）：折叠 `stateDelta`（§6.2）——余额/storage 分叉即根分叉。
3. **Phase 2**（ebc97ac3 + 7829e906）：
   - **origin = `genesisRoot()`**（`EvmBlockProcessor.java:312`）= keccak(排序后的 `addr(20)‖balance(32)`…)——创世配置错的节点从第 0 步就分叉（此前 alloc 错误在账户被触碰前不可见）；空 alloc = keccak(空) 固定常数。
   - **height 折入**每级根（跨高度错位可检）。
   - **跨节点比对**：原计划把 (height,root) 锚进原生区块字段（PoW 承诺、进 DAG 不可抹），但 §1.1 的 4-bit 码位耗尽使其必须硬分叉——退而选 **P2P gossip 0x1E**（§7.3）。取舍明记：gossip 是链下的、靠诚实节点，弱于字段锚定；v1 只**检测**分歧（响亮告警，节点仍跟随 PoW 最重链——不拿原生活性给 EVM 正确性陪葬），升级为硬共识规则留作后路。

### 6.4 `EvmMetaStore` — 检查点、收据、重放脚本、反向索引

五个前缀的分工（键布局见 §2 表）：

- `0x00` 检查点：`stateRoot(32)‖blockHash(32)‖txCount(4BE)‖ts(8BE)` 共 76 字节（`EvmMetaStore.java:100-127`）。
- `0x02` tx 列表 = **重放脚本**：`putTxList`（`:145-155`）写列表的同时逐笔写 `0x04` 反向索引（唯一写者，锁步一致）。
- `0x04` 反向索引：`findTxLocation` O(1)（`:177-187`）——取代 RPC 曾经的全史线性扫（DoS 修复）。
- `0x03` pending 队列（§5.5）。
- `removeAbove(height)`（`:271-291`）：**连收据和反向索引一起删**——否则被 reorg 掉的交易还在对外宣称成功/合约地址。

---

## 7. 实现：P2P 旁路（载荷 gossip 与状态根比对）

### 7.1 消息面（4 个新码，`MessageCode.java:90-95`）

| 码 | 消息 | 体 |
|----|------|-----|
| 0x1B | EVM_TX_BROADCAST | raw RLP |
| 0x1C | EVM_TX_REQUEST | txHash(32) |
| 0x1D | EVM_TX_REPLY | raw RLP |
| 0x1E | EVM_STATE_ROOT | height(long)‖root(32) |

路由注意：`XdagP2pHandler.channelRead0:221` 显式把这四个码转 `onXdag`——**这行是修复**：EVM 三个码原先没进路由表，掉进 default 被 fireChannelRead 吞掉，整条 EVM gossip 路径曾是死的（只有直调 handler 的单测在跑）。新增消息码务必检查 channelRead0 路由。

### 7.2 载荷流转

- `processEvmTxBroadcast`（`:478-487`）：`ingestEvmTxBlob`，**首见入池才转发**（relay-on-first-sight，天然去重防回声风暴）。
- `ingestEvmTxBlob`（`:509-533`）双路径：**共识路径**——`evmProcessor.isAwaitingBlob(hash)` 为真才落 EVM_TX 并触发 `onBlobsAvailable()` drain（**只存正在等的 blob**，unsolicited 垃圾进不了磁盘）；**mempool 路径**——`evmTxPool.add`（池自己会持久化合法交易）。前置 `maxP2pTxBytes`（128KB）尺寸闸。
- `requestMissingEvmBlob`（`:404-413`）：NEW_BLOCK / 同步块两个入口，看到块携带 ref 而本地无 blob，即向宣布该块的 peer 发 0x1C。
- `requestPendingEvmBlobs`（`:423-435`）：15 秒定时兜底，把 `pendingMissingBlobHashes()` 逐个补拉。

### 7.3 状态根 gossip（Phase 2 stage 2）

- `gossipEvmStateRoot`（`:443-454`）：搭 15s tick 顺风车，把 `latestExecutedStateRoot()`（最高检查点的 (height,root)）发给 peer。
- `processEvmStateRoot`（`:461-476`）：`compareStateRoot` 三态——**AGREE**（trace 级）；**DIVERGE**（**ERROR 级响亮持续告警**："EVM world states have forked"）；**UNKNOWN**（本节点该高度无检查点 = 落后或无交易，**绝不误报**）。v1 检测不拒块（D6 取舍）。

---

## 8. 实现：eth_* RPC 兼容层

`JsonRpcServer`（`JsonRpcServer.java:75-98`）在 `evm.enabled && evmStateStore != null` 时把 `EthRequestHandler`（9 参构造：状态库/EvmConfig/minGasPrice/blockchain/池/广播器/EvmTxStore/EvmMetaStore/maxLogScanRange）挂进既有 supportsMethod 分发，与 `xdag_*` 的 JsonRequestHandler 并存（端口 10001）。

19 个方法（`EthRequestHandler.java:67-72`）分四组：

| 组 | 方法 | 要点 |
|----|------|------|
| 链信息 | eth_chainId / net_version / web3_clientVersion / eth_gasPrice / eth_blockNumber / eth_accounts / net_listening | gasPrice 返回 minGasPrice |
| 状态读 | eth_getBalance / getTransactionCount / getCode / getStorageAt | 只服务 "latest"/头高度（`:309-319`）；显式历史高度报错——无归档状态，诚实拒绝 |
| 模拟 | **eth_call / eth_estimateGas** | `simulate()`（`:467-472`）开**一次性 root updater** + `executor.simulateCall/simulateDeploy`（commit=false 共享路径）——**永不落盘**（ADR-005）。⚠️ 曾是 HIGH 漏洞：旧版把 root updater 直接给 `call()`，成功即 `parent.commit()` 真持久化——未认证 eth_call 可绕过共识改盘上状态（devnet CORS `*`）。修复即 simulate* 双入口，回归测试钉死 |
| 写+查 | **eth_sendRawTransaction**（`:193-226`）/ getTransactionByHash / getTransactionReceipt / getBlockByNumber / getBlockByHash / **eth_getLogs**（`:353-391`） | send：pool.add → 12 值 AddResult 映射 JSON-RPC 错误；ADDED/REPLACED 才广播 0x1B；DUPLICATE 幂等回 hash 不重播。查：`locate()`=O(1) 反向索引；blockHash 一律出自 `getBlockByHeight(h).hash`（单一事实源，null 安全回零 hash 不 500）；getLogs 地址集 + topic[0] 过滤、范围 ≤ maxLogScanRange(1024) |

eth "区块" 是**合成的**：任意主块高度 → 用 EVM_META 的 tx 列表拼 eth block JSON（空交易高度回空数组而非 null——MetaMask 轮询 latest 不能拿到 null）。

---

## 9. 资金上桥：创世分配

- 配置：`evm.alloc = [{address, balance(wei 十进制串)}]`；`AbstractConfig.parseEvmAlloc`（`AbstractConfig.java:245-264`）启动即 fail-fast：重复地址 / 非正 / >2²⁵⁶-1 全拒。
- 落地：`seedGenesisIfAbsent()`（`EvmBlockProcessor.java:117-127`）——`0x03` 单字节标记守卫，**每链一生只播一次**；重启见标记跳过（交易改过的余额不被冲掉）；reorg 擦盘把标记一起擦掉 → 重放前恢复注资（否则重放交易付不起 gas）。调用点：Kernel 启动（RPC 先见余额）+ executeAndCheckpoint 顶部 + rollbackTo 内。
- devnet 预注资 `0x7e5f…395bdf` 10²⁴ wei（`xdag-devnet.conf:34-49`）；testnet/mainnet `evm.enabled=false` 且 alloc 空。
- 设计选择（已批准）：创世是**纯状态种子**，不进状态根链本身——但 Phase 2 的 `genesisRoot()` origin 已把 alloc 折进根链原点，misconfig 即刻可见（§6.3）。

Kernel 接线全景（`Kernel.java:188-215`）：`evm.enabled` 才开三库 → 建 EvmConfig（Shanghai+chainId+blockGasLimit+minGasPrice）→ EvmTxStore/EvmTxPool → 6 参 EvmBlockProcessor（含 activationHeight+genesisAlloc）→ `seedGenesisIfAbsent()`。配置键全表：`evm.{enabled, activationHeight, chainId, blockGasLimit, txPoolTtlSeconds, maxP2pTxBytes, minGasPrice, maxLogScanRange, alloc}`（解析 `AbstractConfig.java:372-387`）。

---

## 10. 端到端时序：一笔 USDT 部署的完整旅程

```
MetaMask(chainId 51966) ──eth_sendRawTransaction──► EthRequestHandler
  └ EvmTxPool.add: EIP-155/EIP-2/intrinsic/nonce/balance(含费) 全过 → ADDED
  └ 载荷落 EVM_TX；broadcaster → 全 peer EVM_TX_BROADCAST(0x1B)
                                        │ peer 首见入池 → 继续转发
下一 epoch，本节点(或任一矿工节点)出块:
  └ createMainBlock → selectEvmTxRef: 池顶 gasPrice、无收据 → hash 进 field 0x0F
  └ XdagPow 整 epoch 收 share 刷 minHash → onTimeout 用最优 nonce 封块
  └ tryToConnect(自己先上链) + 广播 NewBlockMessage
       └ 收块 peer 若无 blob → EVM_TX_REQUEST(0x1C) 向宣布者补拉
主链推进，载体块被 checkNewMain 确认:
  └ setMain: applyBlock DFS → evmRefs=[deployTxHash]
  └ processMainBlock(refs, height, ts, blockHash)
       └ (若缺 blob: putPending 停摆，0x1D 补到后 onBlobsAvailable 升序 drain)
       └ executeAndCheckpoint → executeList:
            预扣 maxFee → executor.deploy(Besu Shanghai, CREATE 地址=f(sender,nonce))
            → USDT runtime code 落 EVM_STATE(0x01 codeHash 寻址) → 退未用 gas，净费燃烧
            → 收据(status=1, contractAddress, logs) 入 EVM_META 0x01
            → chainedRoot = keccak(prev‖height‖(tx,status,gas)‖stateDelta) 入检查点
  └ 15s tick: EVM_STATE_ROOT(0x1E) gossip → peer compareStateRoot → AGREE(trace)
MetaMask 轮询:
  └ eth_getTransactionReceipt: findTxLocation O(1) → 收据 + 合约地址
  └ 之后 eth_call balanceOf(...)：simulate 一次性 updater，读最新世界状态，永不落盘
分叉重组（如发生）:
  └ unWindMain → rollbackTo(lowest-1): removeAbove + reset + 创世重播 + 逐高度重放
     → 重放根 == 存根自检；载体块若在新链重现，交易在新高度重新执行
```

---

## 11. 实施历程与验证

实施按"可独立验证的子项目"推进，每步带 TDD 测试与独立代码评审/安全审计：

| 阶段 | 内容 | 关键产出/提交 |
|------|------|--------------|
| **A** | Besu 内嵌执行器 + USDT 内存证明 | `EvmConfig/XdagEvmExecutor/XdagExecutionResult`；主网 Tether 原文 usdt.sol 全生命周期测试 |
| **B1** | RocksDB 世界状态 + EVM 地址 | `EvmStateSchema/RocksDbAccount/RocksDbWorldUpdater/EvmAddress`、`batchWrite` |
| **B2a** | 共识之下的交易层 | `EvmTransaction/EvmTxStore/EvmTxPool/IntrinsicGas/EvmMetaStore/EvmReceipt`、字段 0x0F（a22f6282..2256c6c5） |
| **B2b** | 共识接线 | `EvmBlockProcessor`、applyBlock/setMain/unWindMain 三挂载、P2P 0x1B-0x1D、Kernel（e9c7e657..f7723c3f）；E2E：签名部署→载体块→主链确认→code+收据落库 |
| **B2 评审修复** | 1 critical + 4 real bugs | 零 gas 转账逃逸异常砸共识(C1)、重复 ref 覆盖收据、每块 gas 预算、池上限/逐出、activationHeight、reorg 删收据（1b0fe3ac）；缺 blob 改停摆等待（809fd7be） |
| **C1** | 只读 eth_* 13 方法 | `EthHex/EthRequestHandler`、simulate 不落盘 |
| **C2** | 写路径 | eth_sendRawTransaction + 矿工自动打包（28a630b7） |
| **C3** | 查询面 | getTransactionByHash/Receipt、getBlockBy*、getLogs、合成 eth 块（17acb28b）——**A→B→C 终验达成** |
| **安全审计与加固** | 2026-07-28/29 两轮多评审员对抗审计 | eth_call 持久化 HIGH 修复（simulate* 双入口）、EIP-2 low-s（04974f33）、O(1) 反向索引、delete 集内容寻址（f632d3f0） |
| **状态根 P1/P2** | delta 折根 → 创世原点+高度+gossip | e9a41d08、ebc97ac3、7829e906（原生字段锚定被 4-bit 耗尽阻断，改 gossip） |
| **gas 结算 + 上桥** | Path α + 创世分配 | 21f49d04、125d0210、加固 cee99a05 |
| **★ 终验** | JDK 21 全量构建 | **340 tests, 0 failures**（a3cacd36）；devnet MetaMask/Hardhat USDT 端到端可用 |

集成测试锚点：`EvmConsensusIntegrationTest`（签名部署随载体块确认而执行）、`MainBlockEvmPackingTest`（打包/空池/已执行跳过）、`MinerPackingSeamTest`（矿工打包→处理器执行→收据可查闭环）、`EvmBlockProcessorTest`（链式根抓纯状态分叉、创世幂等/重组恢复、停摆-drain 确定性、gas 预算、回滚删收据、根比对三态）。

---

## 12. 关键不变量与易错点

1. **EVM 只在 `setMain` 执行**：导入/孤块/分叉块阶段绝不碰 EVM 状态。任何想"提前执行"的优化都会破坏终局性对齐。
2. **evmRefs 顺序 = applyBlock DFS 序**：两处 BI_APPLIED 落点（`:1061/:1180`）都必须 collect；改 applyBlock 遍历顺序 = 改 EVM 共识。
3. **EVM 异常永不逃逸进共识**：executeOne 的 C1 try/catch、runToHalt 的异常闸、selectEvmTxRef 的 try/catch——三道防线，EVM 再烂挖矿与 setMain 照常。破坏任何一道都可能让一笔畸形交易砸停全节点。
4. **0x0F 不是 link、不 reverse**：编码/解析/构造三处对称，别按 Address 的大小端翻转套它。
5. **只有 root updater 摘要**：child `commitAndDigest` 返回 `Bytes32.ZERO`；digest 覆盖的恰是 batchWrite 落盘的 (puts,deletes)——两者由同一数据构造，改一处必须同改。
6. **deletes 必须内容寻址**（TreeSet compareUnsigned）：引用相等的 HashSet 会让"清零 + clearStorage 双路径删除同一槽"计入两次，根失去单射性。
7. **`putTxList` 是 0x04 反向索引唯一写者，`removeAbove` 是唯一删者**：收据与反向索引必须随 reorg 一起删，否则被重组掉的交易永远宣称成功。
8. **创世标记与擦盘的顺序**：`rollbackTo` 必须 reset **之后**、重放**之前** `seedGenesisIfAbsent`——顺序错则重放交易付不起 gas，全链重放失败。
9. **预扣-退款依赖"扣款先于子 updater 快照"**：在 `root.updater()` 派生之前完成 debit，子层 commit 写回的绝对余额才含扣款。
10. **eth_call/estimateGas 永不落盘**：只能走 `simulateCall/simulateDeploy`；把 root updater 交给会 `parent.commit()` 的 `call()/deploy()` 就是重现那个 HIGH 漏洞。
11. **只存"正在等"的 blob**：`ingestEvmTxBlob` 的 `isAwaitingBlob` 闸是 unsolicited-blob 磁盘 DoS 的唯一防线。
12. **新 P2P 消息码必须进 `channelRead0` 路由**：EVM 三码曾整体漏路由而静默死亡——单测直调 handler 测不出这个。
13. **UNKNOWN ≠ DIVERGE**：状态根比对里"本节点无该高度检查点"是常态（落后/无交易），当成分歧告警会天天误报。
14. **nonce 语义**：验证失败（chainId/余额/nonce 不符）不涨 nonce；进入执行后（含 revert/OOG）必涨——与以太坊一致，钱包重发依赖它。
15. **码位耗尽是硬约束**：想给区块加任何新字段类型都需要块格式硬分叉；设计新特性前先看 §1.1 的表。

## 13. 已知限制与主网前路线图

### 13.1 五大主网阻塞缺陷与解决方向（2026-08-14 对照代码逐条核实）

严格说这五条是 v1 **有意的范围裁剪**而非疏漏（代码注释里的 "v1"、"provisional" 即证据），但每一条都阻塞主网。按致命程度排序：缺陷 2、3 不解决则主网 EVM 是一条无资产、无吞吐的空链；缺陷 1、4 相对好补；缺陷 5 是流程门槛。

**缺陷 1：仅支持 legacy EIP-155（type-0），无 EIP-1559**

- 现状：`EvmTransaction.java:42` 明确 type-0 是 v1 唯一交易类型；解析只认 9 元素 legacy RLP，`v < 35` 的未保护交易直接拒（`:112-113`）；无 EIP-2718 typed envelope；费用模型纯 gasPrice + `evm.minGasPrice`，`BASEFEE` 操作码读 0。
- 影响：默认发 type-2 的工具链（MetaMask、viem、Hardhat）需显式退回 legacy 才能用；无费用市场，拥堵时只能靠 minGasPrice 一刀切。
- 解决方向：分两步，均走 `activationHeight` 式硬分叉门。**第一步**支持 EIP-2718 envelope + type-2 解析，在没有 base fee 市场前把 `maxFeePerGas` 视作 effective gasPrice、`eth_getBlockByNumber` 返回 `baseFeePerGas: 0x0`，钱包即插即用；**第二步**（可选）实现每主块 base fee 调整（EIP-1559 公式，target = blockGasLimit/2），才有真费用市场。解码入口集中在 `EvmTransaction.decode`，第一步改动面小。
- **已实现（2026-08-18，dev-evm）**：第一步落地——0x02 信封分派集中在 `EvmTransaction.decode`
  （type-1 明确拒绝；ethers v6 离线签名固定向量钉住跨客户端 hash/sender）；以太坊精确费用语义
  （baseFee≡0 下 effective = min(maxPriorityFee, maxFee)，准入/执行余额校验按 feeCap，扣退费按
  effective，type-0 逐字节不变）；EIP-2930 accessList 收 intrinsic gas 不预热；
  `evm.type2ActivationHeight`（devnet=0，testnet/mainnet 缺省不排期）三重门控——执行侧（激活前
  status-0 收据与未升级节点逐字节一致，预算记账同样镜像）、矿工侧（激活前不打包，防哈希永久烧毁）、
  RPC 侧（sendRaw 拒收）；RPC 面 type/maxFee/accessList/yParity 字段 + `eth_feeHistory`（全部 geth
  block tag）+ `eth_maxPriorityFeePerGas`；存储/P2P/批次/重放零变化。第二步（base fee 市场）仍为
  可选未实现。

**缺陷 2：每主块最多打包 1 笔 EVM tx（吞吐上限 ≈ 1 tx / 64s）**

- 现状：`Block.evmTxRef` 是单个 `Bytes32`（`Block.java:88`），矿工打包 `selectEvmTxRef` 只挑一笔（`BlockchainImpl.java:1495`，见 §5.2）；coinbase + orphans 占满 16 槽时甚至打包 0 笔。30M 的 `blockGasLimit` 执行容量被单笔浪费。
- 解决方向：**批次引用**——0x0F 字段语义从"单 tx hash"升级为"批次承诺"（tx hash 有序列表的承诺哈希），EVM_TX 存储侧存列表本体，P2P blob 请求按批次拉取，矿工按 sender-nonce 顺序装批直到 blockGasLimit。复用 0x0F 码位、语义随新 activationHeight 硬分叉切换，是 §12.15 码位耗尽约束下唯一不占新码位的扩容路径；执行侧 `evmRefs` 收集（`BlockchainImpl.java:1208`）本就是列表结构，改动集中在打包与 blob 流转。
- **已实现（2026-08-17，dev-evm）**：按"批次承诺"方向落地——0x0F 装 keccak256(RLP tx hash 列表)，
  批次体内容寻址存 EVM_TX（0x01 前缀），矿工 `selectEvmBatch` 按 sender 间 gasPrice 降序、
  sender 内 nonce 升序装批至 blockGasLimit；mempool 升级为每 sender nonce 链（窗口 16，累计余额准入）；
  执行侧对 ref 始终 dual-lookup 展开（激活高度只门控矿工侧，边界无活性陷阱）；
  P2P 新增 EVM_BATCH_REQUEST/REPLY(0x1F/0x20) 按需拉体，歧义 ref 双请求；
  重放脚本/收据/反向索引/bloom/回滚格式零变化。`evm.batchActivationHeight` devnet=0 已激活；
  testnet/mainnet 待分叉排期。吞吐上限从 1 tx/主块提升至整批装满；devnet 依用户裁定**不设 gas
  预算钳制**（`evm.blockGasLimit = 1e12`，预算机制保留为共享网安全阀——EVM 在 setMain 同步执行，
  正式网启用前必须定真实共识值），实际边界 = `MAX_BATCH_TXS`(3971 笔/主块，即 128KB
  `evm.maxP2pTxBytes` 消息能装下的最大哈希数：33B/哈希 + 4B 列表头) 与 64s 出块节奏（≈62 tx/s）。

**缺陷 3：无原生 XDAG ↔ EVM 自动 bridge（资金入口仅创世分配）**

- 现状：EVM 余额唯一来源是 `evm.alloc` 创世预分配（§9）；devnet 预注资一个测试地址，`xdag-devnet.conf:50` 注释直说 "Testnet/mainnet fund nothing"——正式网即使激活 EVM 也是一条没有原生资产的空链。没有存取款交易类型、没有 precompile、没有双向锚定。
- 解决方向：共识层双向锚定。**入金**：原生交易输出到系统锁定地址、remark 携带 EVM 目标地址 → `setMain` 确认后在 `EvmBlockProcessor` 执行序内等额 mint（挂在执行序即天然复用 `rollbackTo` 的 reorg 对称回滚）；**出金**：EVM 侧调用系统 precompile burn → 生成待解锁记录，共识层 N 主块确认后释放原生输出（延迟窗口吸收 reorg）。关键难点是两侧原子性与回滚对称。备选是 1:1 总量映射（主账本余额直接映射 EVM 账户），需统一账户模型，工程面远大于锚定方案。
- 决策前置：锁定地址与出金确认深度 N 属于共识规则（硬分叉内容），须先于实现定案。
- **已实现（Phase 3a 入金，2026-08-19，dev-evm）**：锁定地址协议常量 0x3109ff8cf0be958a428c12d86c0abf64f529f7db（keccak256("XDAG-EVM-BRIDGE-LOCK-v1") 末 20 字节，无私钥）；remark 编码 base58(0x45‖addr20‖keccak 前 2 字节) 恒 32 字符（标准 Base58Check 33 字符放不下的勘误已入 spec）；applyBlock 的 OUTPUT 记账分支按 DFS 序收集（金额=扣费后实际入账值，collectBridgeDeposit 助手），setMain 按确认高度门控（evm.bridgeActivationHeight devnet=0，且配置校验强制 ≥ evm.activationHeight）；EVM_META 0x06 进重放脚本（负数金额 fail-fast），executeList 先 mint 后执行交易（同高度可花、同一次 commit 入链式根），deposit-only 高度照常 checkpoint 并参与重放，rollbackTo 对称免费；非法 remark → evm.bridgeRecoveryAddress（排期即必填 fail-fast，devnet=测试地址）；1 nano = 10⁹ wei 无损。出金见下方 Phase 3b 条目（已实现）。
- **已实现（Phase 3b 出金，2026-08-20，dev-evm）**：系统合约固定字节码（solc 0.8.26 无优化去 metadata，634 字节，codeHash 0x80d4…2c78）播种于 0x97d38b2e…41e3ea（marker 0x04，genesis 同款、链式根外）；executeList 收据日志扫描（bloom 同生命周期，重放再生）→ EVM_META 0x07；setMain(H') 释放 H'−N 成熟 burn（AddressStore 直接记账、锁减目标加），实际释放写 0x08 日志，unWindMain 逐高度读日志反转后删除——反转的是实际做过的事；锁不足→整高度 all-or-nothing 跳过（先对该高度 burn 总额与桥锁余额整体预检，不足则一笔不释放、不写 0x08、永不重试）、EVM 停摆滞后→确定性跳过，均打 CRITICAL（共享网前提 = §13.3-2 DA 硬门槛）；evm.bridgeWithdrawalDelay devnet=2 建议 16；evm.enabled 交叉校验 + alloc 无背书启动告警。**缺陷 3 双向 bridge 至此完整**。

**缺陷 4：testnet/mainnet 的 chainId 与激活高度未定**

- 现状：chainId 默认 `0xCAFE`（51966），`AbstractConfig.java:166` 自注 "provisional devnet id"；仅 `xdag-devnet.conf` 显式配置；testnet/mainnet 两份 conf 只有 `evm.enabled = false` + `stateHistoryWindow`，无 chainId、无 activationHeight。
- 解决方向：向 ethereum-lists/chains 注册正式 chainId（testnet/mainnet 各一，提交前查重防碰撞），写入两份 conf；activationHeight 随硬分叉发布节奏定——先 testnet 试跑完整窗口再定 mainnet 高度。分叉门代码已就位（`getEvmActivationHeight()`；`EvmBlockProcessor.java:167` 激活前忽略 EVM_TX_REF 的共识含义），这条是纯配置 + 流程动作，代码零改动。

**缺陷 5：主网未开，上线审计闭环未建立**

- 现状：mainnet/testnet conf 皆 `evm.enabled = false`，EVM 栈只在 devnet 生效；代码停在 dev-evm 分支未合入；repo 内无外部审计产物。内部 review-gate（逐 commit 审查 + holistic review，§11）已跑通，但不等于面向主网的第三方审计闭环。
- 解决方向（按序）：① dev-evm 合入 develop，JDK 21 全量测试进 CI 门禁；② 第三方安全审计，重点面：共识三挂载点（§5.3）、reorg 回滚对称性（§5.6）、P2P blob DoS 面（§7）、模拟路径不落盘（§12.10）、bridge（缺陷 3 落地后）；③ testnet 公测周期 + bug bounty；④ 审计报告与修复清单归档进 repo；⑤ 连同 §13.3 三项硬门槛全部关闭后，再定 mainnet 激活高度。

### 13.2 其他 v1 限制（devnet 可用、诚实记录）

- 状态根是**链式 delta 承诺**，非绝对状态 MPT：无 eth_getProof/轻客户端；跨节点一致性靠 gossip **检测**（链下、不 PoW 承诺、不硬拒）。
- gas 净费**燃烧**（不给 coinbase，供应缓缩）；`GASPRICE` 操作码读 0；`BLOCKHASH/PREVRANDAO/BASEFEE/COINBASE` 皆 0（确定性占位，不分叉但无随机性）。
- getLogs 扫描范围 ≤ `maxLogScanRange`（1024）；历史状态查询仅覆盖最近 `stateHistoryWindow`（128）个高度，更早的 archive 不可得（C4）；EIP-3529 退款按毛 gasUsed 收（略高于主网、永不少收）；mempool 每 sender 一笔、无 per-sender Sybil 配额；blob 真不可得时 EVM 停摆（诚实 DA 行为，活性风险）。

（2026-08 更新：C5 已交付 bloom 过滤 + 全 topic 匹配，C4 已交付窗口内 block-tag 历史状态查询——本节早期版本"无 bloom、RPC 只服务 latest"的记录已随之删除。）

### 13.3 主网启用（`evm.enabled=true` 于共享网络）前的硬门槛

1. **PoW 承诺的状态根**：块格式修订腾出字段码位，把 (height,root) 锚回原生区块（设计已批准，被码位耗尽推迟）；或引入绝对状态根（MPT/SMT）。
2. **DA 强制**：导入期载荷可用性检查，或共识层带超时跳过标记——消除"blob 永不可得"的停摆。
3. 费用路由（燃烧→coinbase）、mempool per-sender 配额、EIP-3529 精确退款；chainId 注册与激活高度见 §13.1 缺陷 4。

## 14. 源码索引

| 主题 | 位置 |
|------|------|
| 字段类型枚举（0x0F） | `core/XdagField.java:70-126` |
| 类型掩码取码 | `core/XdagBlock.java:82` |
| 11 参构造 / 编码 / 解析 evmTxRef | `core/Block.java:112-211 / 387-389 / 277-279` |
| createMainBlock / selectEvmTxRef | `core/BlockchainImpl.java:1439-1472 / 1480-1497` |
| applyBlock 收集 / setMain 执行 / unWindMain 回滚 | `core/BlockchainImpl.java:1051-1203 / 1302-1344 / 994-1035` |
| 执行器（deploy/call/simulate*/runToHalt） | `evm/XdagEvmExecutor.java:90-185 / 228-249` |
| 块处理器（入口/执行/回滚/根） | `evm/EvmBlockProcessor.java:139 / 227 / 364 / 420 / 273 / 298-351` |
| 交易 / 池 / 内在 gas | `evm/tx/EvmTransaction.java:96-189`、`EvmTxPool.java:89-177`、`IntrinsicGas.java:54-68` |
| 世界状态（更新器/账户/键布局） | `evm/state/RocksDbWorldUpdater.java:185-276`、`RocksDbAccount.java:114-220`、`EvmStateSchema.java:36-115` |
| 元存储（检查点/收据/重放/反向索引） | `evm/state/EvmMetaStore.java:100-300` |
| P2P（码/路由/blob/根 gossip） | `net/message/MessageCode.java:90-95`、`net/XdagP2pHandler.java:221 / 404-533` |
| RPC（注册/处理器/模拟） | `rpc/server/core/JsonRpcServer.java:75-98`、`rpc/server/handler/EthRequestHandler.java:67-472` |
| 配置（EvmSpec/解析/alloc） | `config/spec/EvmSpec.java:34-60`、`config/AbstractConfig.java:245-264 / 372-387`、`resources/xdag-devnet.conf:34-49` |
| Kernel 接线 | `Kernel.java:188-215` |
