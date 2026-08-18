# 缺陷 1：EIP-2718 typed envelope + type-2 交易（设计 spec，第一步）

日期：2026-08-18
分支：dev-evm
状态：已批准（brainstorming 定案；路线图方案 A 的第 2 个工程子项目，
前置为缺陷 2 批次打包，已于 2026-08-17 交付并全绿 430）

对应缺陷：`.claude/docs/smart-contract-design-and-implementation.md` §13.1 缺陷 1
（仅支持 legacy EIP-155 type-0，默认发 type-2 的 MetaMask/viem/Hardhat 需显式退回 legacy）。
实现路径：§13.1 既定两步走的**第一步**——EIP-2718 信封 + type-2 解析执行，无 base fee
市场（baseFee ≡ 0）；第二步（可选、不在本 spec 内）为每主块 base fee 调整的真费用市场。

## 0. 已裁定的决策（brainstorming 问答记录）

1. **类型范围**：type-2 全支持——accessList 非空也接受，按 EIP-2930 收 intrinsic gas
   但**不做预热**（确定性多收、永不少收，与既有 EIP-3529 毛退款同一诚实取向）；
   type-1 (0x01) 明确拒绝并报清晰错误。
2. **费用语义**：以太坊精确公式 `effective = baseFee + min(maxPriorityFee, maxFee − baseFee)`，
   第一步 baseFee 硬编码 0（即 min(maxPriorityFee, maxFee)）；余额准入按以太坊规则
   `value + maxFee × gasLimit`。第二步引入 base fee 市场时公式不变、只是 baseFee 非 0。
3. **分叉门**：新增独立 `evm.type2ActivationHeight`，完全镜像缺陷 2 的
   batchActivationHeight 模式（devnet=0，testnet/mainnet 缺省 Long.MAX_VALUE）。
4. **RPC 配套**：`eth_feeHistory` 与 `eth_maxPriorityFeePerGas` 一并补齐
   （钱包估费链路闭环，"即插即用"目标才成立）。
5. **建模**：方案 A 单类多型——`EvmTransaction` 一个类加 type 字段与 type-2 专属字段
   （Besu 自身 Transaction 类同款设计），不拆 sealed 子类；不引入 besu-ethereum 依赖
   （既有裁定：pom 只保留 besu-evm/datatypes/crypto/rlp 四件套）。

## 1. 目标与成功标准

MetaMask/viem/Hardhat 以默认设置（type-2）对 XDAG devnet 发交易即可用，无须
显式退回 legacy。成功标准：

1. 外部客户端（ethers v6）离线签出的 type-2 raw 交易，节点解码后 txHash 与 sender
   与以太坊逐位一致（固定向量测试钉住）；
2. type-2 经 sendRaw → 池 → 批次打包 → 执行 → 收据/查询全链路绿，type-0 与 type-2
   可混合同批；
3. **激活前兼容性质**：`evm.type2ActivationHeight` 之前，升级节点对 type-2 blob 产出的
   status-0 收据与未升级节点（解码失败路径）**逐字节相同** → 链式根一致，激活前不分叉；
4. 现有 430 项测试零回退；type-0 行为与费用逐字节不变。

## 2. 编解码（EvmTransaction 单类多型）

- `decode(Bytes raw)` 按首字节分派：
  - `>= 0xc0` → 现有 legacy 路径**逐字节不变**（EIP-155 强制、EIP-2 low-s 拒绝照旧）；
  - `== 0x02` → type-2：`0x02 ‖ rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas,
    gasLimit, to, value, data, accessList, yParity, r, s])`；yParity 必须 ∈ {0,1}；
    EIP-2 low-s 同样强制；`maxPriorityFee > maxFee` 拒绝（以太坊同款校验）；
  - `== 0x01` → 拒绝 "type-1 (EIP-2930) transactions not supported"；
  - `0x03..0x7f` → 拒绝 "unsupported transaction type"（EIP-2718 类型码空间）；
  - `0x80..0xbf` → 本就是非法 RLP 首字节，自然抛 RLPException。
- type-2 signingHash = `keccak256(0x02 ‖ rlp(前 9 项))`；签名用 yParity 直接构造
  （无 EIP-155 v 算术）。
- `rawRlp` = 完整信封字节（含 0x02 前缀）；**txHash = keccak256(信封) 与以太坊逐位一致**
  ——内容寻址（存储键、批次体成员、P2P 请求）天然兼容，零迁移。
- 新增字段：`type`(int, 0|2)、`maxPriorityFeePerGas`、`maxFeePerGas`（type-0 时为 null）、
  `accessList`（type-0 时为空表）。新 record `AccessListEntry(Address address,
  List<Bytes32> storageKeys)`（io.xdag.evm.tx），RLP 形如 `[[address, [key…]]…]`，
  解析后保留（RPC 回显 + intrinsic gas 计数）。
- `getChainId()` 统一返回（type-2 是显式字段），池/执行双重 chainId 校验代码不变。
- 测试/工具面：`unsignedType2(...)` 工厂 + `sign` 支持 type-2（产出信封字节）；
  legacy 的 `unsigned/sign/encodeWithV` 不动。

## 3. 费用语义（baseFee ≡ 0 的以太坊精确公式）

新增两个访问器，收敛现有 8 个 `getGasPrice()` 消费点；`getGasPrice()` 保留给
type-0 语义与 RPC 回显：

- `getEffectiveGasPrice()`：type-0 → gasPrice；type-2 → `min(maxPriorityFee, maxFee)`
  （完整公式 `baseFee + min(maxPriorityFee, maxFee − baseFee)` 写进注释，第二步只改 baseFee）；
- `getFeeCapPerGas()`：type-0 → gasPrice；type-2 → maxFeePerGas。

消费点映射（全部换访问器，机制不变）：

| 消费点 | 现状 | 改为 |
|---|---|---|
| 池 minGasPrice 准入（EvmTxPool:133） | gasPrice | effective |
| 执行期 floor 复查（EvmBlockProcessor:601） | gasPrice | effective |
| RBF 比价（EvmTxPool:183） | gasPrice | effective |
| legacy 选择排序（EvmTxPool:208） | gasPrice | effective |
| selectBatch 贪心排序（EvmTxPool:291） | gasPrice | effective |
| 累计余额准入（EvmTxPool:314） | gasPrice×gasLimit | feeCap×gasLimit |
| 执行期余额检查 + 预扣（EvmBlockProcessor:615） | gasPrice×gasLimit | 检查 feeCap×gasLimit；预扣 effective×gasLimit、退未用、烧净额 |
| 收据 effectiveGasPrice（EthObjects:87） | gasPrice | effective |

注意执行期的不对称（以太坊同款）：余额**校验**against `value + feeCap×gasLimit`
（保护第二步 baseFee 上浮），实际**预扣** `effective×gasLimit`（退款基数）。
type-0 两者相等，行为逐字节不变。

## 4. 分叉门（evm.type2ActivationHeight）

- 配置：`EvmSpec.getEvmType2ActivationHeight()` + AbstractConfig 字段（默认
  `Long.MAX_VALUE`）+ HOCON 解析 `evm.type2ActivationHeight`；devnet **主 conf 与
  test 影子 conf 都写 0**（影子 conf per-key 覆盖的坑已记录在案）。
- 执行侧（共识）：`executeOne` 在 chainId 检查之前加
  `height < gate && tx.getType() == 2 → validationFailure("type-2 before activation")`。
- **激活前兼容性质（本设计的关键安全性质，测试必须钉住）**：`EvmReceipt` 只有
  (status, gasUsed, contractAddress, logs)，不含 reason 字符串——门控拒绝（升级节点）
  与 "undecodable blob"（未升级节点的解码失败路径）产出**逐字节相同**的
  status-0/零 gas/无 logs 收据 → 链式根一致，激活前新旧节点不分叉。
- RPC `eth_sendRawTransaction`：激活前拒收 type-2（清晰错误，节点本地 UX；用当前
  已执行高度或 nmain 判断即可，无须精确——共识正确性由执行侧兜底）。
- 池/P2P 入口**不设门**：devnet 门=0 实际不可达；极端情况（手工打包）由执行侧
  确定性 status-0 兜底，只浪费攻击者自己的批内槽位。

## 5. intrinsic gas（EIP-2930 计数，不预热）

`IntrinsicGas.compute` 增加 accessList 参数：每地址 +2400（ACCESS_LIST_ADDRESS_COST）、
每存储槽 +1900（ACCESS_LIST_STORAGE_KEY_COST）。**不做执行期预热**——SLOAD/账户访问
仍按冷计价，比以太坊主网多收、永不少收，全网确定性一致（所有节点同一代码），
与 EIP-3529 毛退款同一诚实取向，文档记录为已知偏差。

## 6. RPC 面

- `EthObjects.tx`（type-2 时）：`type: "0x2"`、`maxFeePerGas`、`maxPriorityFeePerGas`、
  `accessList`（回显解析结构）、`yParity` 与 `v`（同值输出，geth 兼容——ethers 等
  工具读 v）、`gasPrice` = effective（geth 对已上链 type-2 的惯例）、`chainId`；
  type-0 输出逐字节不变。
- `receipt`：`type` 按实际类型（"0x0"/"0x2"），`effectiveGasPrice` = effective。
- `block`：`baseFeePerGas: "0x0"` 已就位（EthObjects:115），不动。
- 新方法（EthRequestHandler 注册 + 分派）：
  - `eth_maxPriorityFeePerGas` → minGasPrice（hex quantity）；
  - `eth_feeHistory(blockCount, newestBlock, rewardPercentiles)` → 诚实静态应答：
    `oldestBlock` 按区间解析；`baseFeePerGas` 全 "0x0"（长度 blockCount+1）；
    `gasUsedRatio` 全 0.0（devnet 预算 1e12 下真实比率亦 ≈0，注释注明）；
    `reward` 每百分位统一 minGasPrice（仅当请求了 percentiles）；blockCount 钳到 1024，
    newestBlock 支持 "latest" 与显式高度。
- `tools/hardhat-e2e` 运行手册更新：README/METAMASK.md 的 "legacy-gas-only" 告诫改为
  type-2 已支持；hardhat.config.js 保留 legacy 写法但注明两种均可。

## 7. 零变化面

- 存储/P2P/批次把 blob 当不透明字节：EvmTxStore 键（0x00/0x01 前缀）、批次体
  （tx hash 列表）、EVM_TX_BROADCAST/REQUEST/REPLY + EVM_BATCH_REQUEST/REPLY 消息、
  `evm.maxP2pTxBytes` 检查——**零改动**。
- 链式根算法、收据 RLP、0x04 反向索引、0x05 bloom、journal/C4 历史窗口、
  rollbackTo 重放——**零改动**（重放脚本仍是扁平已执行 txHash 列表，按内容重解码，
  type 信息在信封字节里自含）。
- `Block.java` 字节不动；GASPRICE 操作码仍读 0（§13.2 既有限制，本次不动，
  避免改变既有 type-0 语义）。

## 8. 测试面

- **编解码**：type-2 round-trip（decode(信封) 各字段正确、rawRlp 保真）；签名恢复；
  **至少一条外部客户端固定向量**（ethers v6 离线签出的 raw hex，断言 txHash 与
  sender 逐位一致——钉跨客户端兼容）；负例：type-1、未知类型（0x03）、yParity>1、
  high-s、截断信封、priority>maxFee、非列表体。
- **费用**：effective 计算（min 两侧各一例）；feeCap 余额准入（够 effective 不够
  feeCap → 拒）；跨类型 RBF（type-0 ↔ type-2 同 (sender,nonce) 按 effective 比价）；
  混合类型 selectBatch 排序；扣退费按 effective；floor 按 effective。
- **intrinsic**：accessList 地址/槽计数；空列表 = 现行值不变。
- **分叉门**：激活前 type-2 → status-0 且收据与 "undecodable blob" 收据**逐字节相等**
  （兼容性质）；激活后正常执行；RPC 激活前拒收。
- **RPC**：type-2 tx/receipt JSON 字段齐全；type-0 JSON 逐字节不变；
  feeHistory/maxPriorityFeePerGas 应答形状。
- **端到端**：`RpcTransportE2ETest` 加 type-2 全链路（HTTP sendRaw → 打包确认 →
  收据 type "0x2"）；混合批次（type-0 与 type-2 同一主块确认）。
- **回归**：现有 430 项零回退（type-0 路径逐字节不变的实证）。

## 9. 攻击面小结

信封解码在既有 keccak 内容寻址闸之内（伪造体不可行）；未知类型/type-1 在池准入与
执行侧双双确定性拒绝；激活前兼容性质消除升级窗口内的根分叉；accessList 只影响
intrinsic gas 计数（多收方向），不开新执行语义；feeCap 准入上界防第二步 baseFee
上浮时的余额透支。无新增 P2P 消息、无新增存储格式 → 无新增 DoS 面。
