# 缺陷 2：EVM 交易批次打包（设计 spec）

日期：2026-08-17
分支：dev-evm
状态：已批准（brainstorming 定案；路线图方案 A 的第 1 个工程子项目，
前置验证门已于 2026-08-14 通过——JDK 21 全量 394 绿 + devnet 冒烟）

对应缺陷：`.claude/docs/smart-contract-design-and-implementation.md` §13.1 缺陷 2
（每主块最多 1 笔 EVM tx，吞吐 ≈ 1 tx/64s）。
实现路径：已批准方案 A——**批次承诺 + 内容寻址批次体**（相对方案 B 多 0x0F 字段
与方案 C 批次体上链，A 吞吐直达 gas 上限且对既有已验证代码扰动最小）。

## 1. 目标与成功标准

每主块可打包多笔 EVM 交易直到 `blockGasLimit`（交付后修订：devnet 依用户裁定把该配置提到
1e12 使预算不构成约束——实际边界为 `MAX_BATCH_TXS` 笔/主块（交付后二次修订：依用户指令由
1024 提至传输极限 3971；预算机制保留为共享网安全阀），
单 sender 连发（nonce 链）与多 sender 并发都能在一个主块内确认。

成功标准：
1. 集成测试证明：单 sender 连续 nonce 多笔 + 多 sender 并发，均可在一个主块内全部确认；
2. 停摆-drain 与 reorg 重放的链式根**逐字节**确定性保持（现有测试钉死的性质不回退）；
3. 激活高度之前的行为与现状逐字节一致（硬分叉门）。

## 2. 字段与承诺语义

- 激活后（待产主块高度 ≥ `evm.batchActivationHeight`）矿工产出的 0x0F 字段值
  = `keccak256(RLP([txHash0, txHash1, …]))`（批次承诺）。
- **激活高度只门控矿工侧产出**；执行侧对（EVM 激活后的）所有 ref **始终 dual-lookup**。
  这是确定性安全的：一个 32 字节值不可能既是 keccak(签名交易 RLP) 又是
  keccak(批次体)（等价于 keccak 碰撞），解释由数据唯一决定。好处是消除激活边界的
  活性陷阱——若解释按高度切换而矿工对确认高度预判偏差一格，承诺会被当作 tx hash
  等待一个永不存在的 blob，永久停摆。（计划阶段修正：spec 初稿的"激活后才 dual-lookup"
  收紧为本条。）
- 0x0F 的编码/解析/构造三处（`Block.java` 11 参构造器 / `getEncodedBody` / `parse`）
  **一概不动**——字段仍是原样 32 字节，只有语义随高度切换。
- 执行期展开用 **dual-lookup**：先按批次体查（§5），miss 则按 legacy 单 tx blob 解释。
  激活前后交界处被确认的旧式 crafted 载体块因此平滑过渡；两类记录均内容寻址，
  伪造需 keccak 原像，无歧义攻击空间。
- 空批不放字段；**单笔也用批次承诺**（激活后语义统一，矿工与执行侧无分支）。
- 批次上限：主约束 Σ gasLimit ≤ `blockGasLimit`；硬上限 `MAX_BATCH_TXS = 3971`
  （交付后修订，原 1024：现取传输极限——每哈希 RLP 33B + 4B 列表头，
  33×3971+4 = 131047 ≤ `evm.maxP2pTxBytes`=131072，再多一笔即超）。

## 3. EvmTxPool 重构：每 sender nonce 链

- `bySender` 从 `Map<Address, PoolEntry>` 改为
  `Map<Address, NavigableMap<Long /*nonce*/, PoolEntry>>`。
- 准入窗口：`nonce ∈ [账户nonce, 账户nonce + MAX_PER_SENDER - 1]`，
  常量 `MAX_PER_SENDER = 16`（不加配置项，YAGNI）。
- 余额准入改**累计**检查：该 sender 队列内所有条目（含新条目）的
  Σ(value + gasLimit×gasPrice) ≤ 账户余额（≤16 条遍历，拦明显死批；
  执行期 `executeOne` 逐笔复查仍是最终兜底）。
- replace-by-fee 升维到 (sender, nonce)：同 (sender,nonce) 需更高 gasPrice 才可替换。
- 账户 nonce 前进后剪除过时条目（现有 stale-nonce 逐出的推广）；TTL 过期逐条生效；
  池满（`MAX_POOL_SIZE=4096` 按总条目数）拒新，per-sender 上限 16 钳制单 sender 占用。
- 新增 `selectBatch(gasBudget)`：sender 间按「该 sender 最低未选 nonce 那笔的 gasPrice」
  降序贪心；sender 内严格 nonce 升序；某笔装不下预算则该 sender 立即停止
  （**批内不留 nonce 空洞**）；直到预算或池尽，且不超过 `MAX_BATCH_TXS`。
- 旧 `selectTransactions(maxCount)` 保留，供激活前路径与既有测试使用。

## 4. 矿工打包（BlockchainImpl）

`createMainBlock`：
- 待产块高度 < 激活高度 → 现有 `selectEvmTxRef` 路径，一字不动；
- ≥ 激活高度 → 新 `selectEvmBatch`：`pool.selectBatch(blockGasLimit)` → 滤掉已有收据的
  tx（同现状防重复打包）→ 组体、**先落库**（`putBatch`，矿工自己必有体）→
  0x0F 装承诺。整体 try/catch：EVM 任何异常，挖矿照常只是不带 ref（三道防线之一，不动）。
- 仍只占用 1 个空闲字段，freeFields 判定不变。

## 5. 存储（EvmTxStore 扩展）

- EVM_TX 列族新增批次体记录：`key = keccak256(body)`，`value = body`
  （body = RLP 编码的 32 字节 tx hash 平铺列表）。
- 键布局顺存储既有约定（现有 tx 记录 key = `0x00‖txHash`）：批次体 key = **`0x01‖batchHash`**
  （`PREFIX_BATCH = 0x01`）。内容寻址不受影响（batchHash = keccak256(body)）；
  前缀域隔离让两类记录永不同键。（计划阶段修正：spec 初稿写"不加前缀"，
  是因为当时未核实 EvmTxStore 已用前缀键。）
- 新 API：`putBatch(hash, body)` / `getBatch(hash) → Optional<List<Bytes32>>`
  （get 内部校验 RLP 形状且条目数 ≤ `MAX_BATCH_TXS`，坏形状或超限一律按 miss 处理并告警
  ——这就是执行侧对恶意超大 crafted 批的落点，§9 三重钳制之一）。
- 批次体与 blob 同样**永不删除**（重放脚本依赖内容寻址载荷永在，§5.6 支柱不变）。

## 6. P2P（消息 + XdagP2pHandler)

- 新消息码：`EVM_BATCH_REQUEST(0x1F)`（体 = 承诺 32 字节）、
  `EVM_BATCH_REPLY(0x20)`（体 = 批次体字节，收包校验长度 ≤ `evm.maxP2pTxBytes`）。
  消息码是 8-bit 空间（与块字段 4-bit 码位耗尽无关），0x1F/0x20 空闲已核实。
- 三处接线缺一不可（§12.12 历史教训）：`MessageFactory` case、`channelRead0` 路由进
  `onXdag`、handler 分发。
- 请求方：执行期发现缺体 → 停摆（§7）→ 缺失哈希并入既有 15 秒补拉 tick
  （`pendingMissingBlobHashes` 旁新增 `pendingMissingBatchHashes`）。
  **双 miss 的 ref 类别不可预知**（可能是批次承诺，也可能是激活交界处 legacy 单笔），
  补拉 tick 对它**同时**发 `EVM_BATCH_REQUEST` 与 `EVM_TX_REQUEST`，谁先到谁解决——
  两条落库路径各有内容寻址闸（batch 的 keccak 校验 / blob 的 hash 重算闸），互不污染。
- 落库入口唯一闸：`isAwaitingBatch`（防 unsolicited 磁盘 DoS，对齐 §12.11）
  + `keccak256(body) == 请求的承诺` 校验（防污染）→ `putBatch` → `onBlobsAvailable()` drain。
- 体到手后，对体内缺失的单笔 tx blob 走**既有** `EVM_TX_REQUEST` 基础设施。
- 不主动广播批次体（按需拉取，与 blob 策略一致；tx blob 本身仍走 EVM_TX_BROADCAST
  先行扩散，正常情况体到时 blob 已在库）。

## 7. 执行（EvmBlockProcessor）

- `processMainBlock(refs, height, ts, blockHash)`：激活后对每个 ref 做 dual-lookup 展开
  （batch hit → 有序 tx hash 列表；miss → 视为 legacy 单 tx ref），全部展开后按
  「evmRefs DFS 序 × 批内序」拼接为平铺列表，随后去重（同现状）。
- 停摆条件扩展：任一 ref 缺体（dual-lookup 双 miss 且不在 EVM_TX blob 中）
  或展开后任一 tx blob 缺失 → `putPending`。**pending 存原始 refs 不存展开结果**
  （drain 时重新展开——体到齐后展开确定，语义与从未停摆一致）。
- `executeList` / `putTxList` / 收据 / 0x04 反向索引 / 0x05 bloom / 重放脚本 /
  `rollbackTo` **格式零变化**：`putTxList` 存的平铺已执行列表就是重放脚本，
  rollbackTo 直接重放平铺列表、无需再展开。C4 历史状态、C5 bloom、C6 订阅
  全部自动继承（都挂在 executeList 之后，与 ref 形态无关）。
- `executeList` 既有聚合 gas 预算（budget = blockGasLimit，超预算跳过）保留，
  是对恶意 crafted 巨批的执行期钳制（矿工侧正常装批时永不触发）。

## 8. 配置与激活（硬分叉门）

- 新 `evm.batchActivationHeight`：`EvmSpec` + `AbstractConfig` 解析 + 4 份 conf。
  devnet = 0（新链直接批次语义；现有 devnet 数据重同步，root 格式变更已有两次先例）；
  testnet / mainnet 不配置 = `Long.MAX_VALUE`（不激活）。
- eth_* RPC 无需改动：`eth_getBlockByNumber` 等的 transactions 列表来自 EVM_META
  平铺 txList，天然正确显示一块多笔。

## 9. 错误处理与新增不变量

既有三道防线（selectEvmBatch try/catch、executeOne C1 闸、runToHalt 闸）不动。新增：

- (a) **执行侧不强制批内 nonce 序**：`executeOne` 逐笔 nonce/余额复查是最终裁决，
  乱序/亏空的恶意批 = 后续笔 status-0 收据，全网确定性一致，不停摆不砸共识；
- (b) `putBatch` 的 keccak 校验 + `isAwaitingBatch` 是 P2P 批次体落库的唯一入口闸；
- (c) pending 只存 refs（不存展开），drain 重新展开；
- (d) 0x0F 编码/解析/构造三处对称性保持原样（语义变、字节不变）；
- (e) 批内已执行 tx（收据已存在）在展开后的候选过滤中跳过（现状规则自动覆盖）。

攻击面小结：伪造体不可行（内容寻址）；unsolicited 体不落盘（awaiting 闸）；
巨批被 MAX_BATCH_TXS(3971) + 消息长度上限(128KB) + 执行期 gas 预算三重钳制
（前两重现已重合——常量即传输极限，有测试钉住该不变量）；
死批（亏空/乱序）只浪费自己批内槽位，出确定性 status-0 收据。

## 10. 测试

单元：
- 池 nonce 链：窗口准入（过低/过高拒）、累计余额拒死批、(sender,nonce) RBF、
  TTL 过期、stale 剪枝、池满、`selectBatch` 的 sender 间 gasPrice 序 + sender 内
  nonce 升序 + 预算截断不留空洞 + MAX_BATCH_TXS 上限；
- `EvmTxStore` 批次体存取 round-trip + 坏形状按 miss；
- 展开 dual-lookup：batch hit / legacy fallback / 双 miss；
- 两个新消息编解码 round-trip。

集成（扩展既有测试锚点）：
- 单 sender 连续 nonce N 笔 + 多 sender 并发，一个主块全部确认、收据/反向索引齐全
  （扩展 `MainBlockEvmPackingTest` / `MinerPackingSeamTest`）；
- 缺体停摆 → 体到 → drain，与从未停摆节点链式根逐字节一致
  （扩展既有停摆确定性测试）；
- 含批次高度的 reorg：rollbackTo 重放平铺脚本、根自检通过；
- 激活门：激活前主块走单 ref 语义逐字节不变，激活后走批次；
- `RpcTransportE2ETest` 增一个多笔场景（HTTP 提交多笔 → 一次 processMainBlock →
  逐笔收据可查）。

## 11. 范围外（YAGNI）

- 批次体主动广播 / gossip 扩散优化（按需拉取够用）；
- mempool per-sender 之外的 Sybil 配额（路线图独立项）；
- EIP-2718/1559（路线图缺陷 1，下一个子项目）；
- DA 强制 / 停摆超时跳过（§13.3 硬门槛，主网前另行处理）；
- 方案 C（批次体上链为专用载荷块）作为 v2 演进方向记录，不在本期。
