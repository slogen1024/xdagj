# 缺陷 3：XDAG ↔ EVM 共识记账桥（设计 spec，两期实施）

日期：2026-08-19
分支：dev-evm
状态：已批准（brainstorming 定案；路线图方案 A 的第 3 个工程子项目，
前置为缺陷 1 EIP-2718/type-2，已于 2026-08-18 交付并全绿 460）

对应缺陷：`.claude/docs/smart-contract-design-and-implementation.md` §13.1 缺陷 3
（EVM 余额唯一来源是 evm.alloc 创世预分配——正式网即使激活 EVM 也是无资产空链）。
架构：已批准**方案 A 共识记账桥**（方案 B 统一账户模型工程面极大且推翻叠加层承诺；
方案 C 新增桥交易类型因 4-bit 字段码空间已耗尽不可行）。

## 0. 已裁定的决策（brainstorming 问答记录）

1. **范围**：一个 spec 定案双向，**两期实施**——Phase 3a 入金先行（devnet 立刻获得
   真实资金入口，取代 evm.alloc 的角色），Phase 3b 出金。各自独立 plan + 全量回归。
2. **锁定地址**：协议推导常量（nothing-up-my-sleeve），代码内置不进配置。
3. **出金确认深度**：`evm.bridgeWithdrawalDelay` 配置项，devnet=2，
   testnet/mainnet 分叉排期时定（spec 建议 16 = XDAG 传统确认数，~17 分钟）。
4. **出金机制**：系统合约 + 事件日志（非 precompile——收据/日志已在重放脚本内，
   执行器零改动，reorg 对称性免费继承；此为对 §13.1 原文 "系统 precompile" 方向的
   已裁定修订）。
5. **误存款政策**：兑到回收地址——remark 缺失/非法的入金 mint 到
   `evm.bridgeRecoveryAddress`（人工处理退款）。**用户明确选择此项**（高于"永久滞留"
   推荐项）：引入每网络共识配置 + 运营职责，换取用户资金可救回。

## 1. 共识参数

| 参数 | 形态 | 值 |
|------|------|-----|
| 锁定地址 | 代码常量 | 20 字节 = `keccak256("XDAG-EVM-BRIDGE-LOCK-v1")` 末 20 字节 |
| 桥合约地址 | 代码常量 | 20 字节 = `keccak256("XDAG-EVM-BRIDGE-CONTRACT-v1")` 末 20 字节 |
| 激活门 | `evm.bridgeActivationHeight` | devnet=0；其他缺省 `Long.MAX_VALUE`（batch/type2 门同模式） |
| 出金深度 N | `evm.bridgeWithdrawalDelay` | devnet=2；建议值 16 |
| 回收地址 | `evm.bridgeRecoveryAddress` | 20 字节 EVM 地址；**桥激活的网络必填，启动 fail-fast**（fund.address S-36 同款） |
| 单位换算 | 固定 | 1 nano = 10⁹ wei（1 XDAG = 10¹⁸ wei，钱包显示对齐 ETH 习惯） |

锁定地址无对应私钥（找到即破解 keccak/EC）：普通转账可打入、无人能签出；
出金释放 = 协议直接扣减其 `AddressStore` 余额（出块奖励 `acceptAmount`
的无签名记账先例，`BlockchainImpl:1323`）。

激活前打给锁定地址的转账只是普通转账：滞留、不 mint、**不追溯**（文档注明）。

## 2. 入金（Phase 3a：原生 → EVM mint）

### 2.1 用户侧

现有钱包 `xfer` 打款到锁定地址，remark 携带 **Base58Check 编码的 20 字节 EVM
目标地址**（约 28 个 ASCII 字符 + 4 字节校验和，恰好塞进 32 字节 ASCII remark；
hex 表示 42 字符塞不下——编码选 Base58Check 的硬原因）。

### 2.2 检测与入账

1. **检测**：`setMain` 的 applyBlock 遍历中，凡 `XDAG_FIELD_OUTPUT`(0x0D) 打给
   锁定地址且高度 ≥ 激活门，按既有 DFS 确定序收集 `(EVM 目标, 金额 nano)`——与
   evmRefs 收集（`BlockchainImpl:1087-1190`）同一套顺序保证。旧式 `XDAG_FIELD_OUT`
   指向 32 字节块哈希、锁定地址是 20 字节钱包地址，故检测路径唯一。
2. **remark 解析**：ASCII 去零填充 → Base58Check 解码 → 20 字节 EVM 地址；
   任何失败（缺失/坏校验和/长度不符）→ 目标 = 回收地址。**所有入金必 mint**——
   守恒不变量因此干净（§4）。
3. **入账**：deposit 列表随 `processMainBlock` 传入 EvmBlockProcessor：
   先持久化 EVM_META 新前缀 `0x06 | height → 有序 (address20, amountNano) 列表`
   （长度框定编码，进重放脚本），再在该高度**交易执行之前**按序 mint
   （`getOrCreate + setBalance(balance + amountNano×10⁹)`，genesis-seed 同款），
   与本高度交易同一次 root commit → 链式根经 state delta 自动覆盖 mint 效果。
4. **重组对称**：native 回退 → 既有 `rollbackTo` 擦 EVM_STATE + 重放；重放的
   `executeList` 读 `0x06` 记录先 mint 再执行交易；`removeAbove` 一并清除记录；
   新分支 setMain 传入新列表。**零新回滚机制**。

## 3. 出金（Phase 3b：EVM burn → 原生释放）

### 3.1 系统合约

桥激活高度首次执行时，协议把**固定 runtime 字节码**直接写入桥合约地址
（marker 防重复，genesis-seed 同款机制；字节码常量连同 solidity 源码在 Phase 3b
plan 中经 solc 固化后回填本 spec 附录，部署后 code hash 有测试断言）。合约唯一入口：

```solidity
event Withdrawal(bytes20 indexed nativeTarget, uint256 amount);
function withdraw(bytes20 nativeTarget) external payable {
    require(msg.value > 0 && msg.value % 1e9 == 0);  // 拒绝尘埃，不取整
    emit Withdrawal(nativeTarget, msg.value);
    // msg.value 留存合约 —— 合约余额 = 累计已烧 wei（审计量）
}
```

无管理员、无升级、无 selfdestruct、无其他函数。

### 3.2 检测、成熟与释放

1. **检测**：高度 H 的 `executeList` 完成后扫描本高度收据日志
   （logger == 桥合约地址 && topic0 == `Withdrawal` 事件签名），提取有序出金列表
   `(nativeTarget20, amountNano = wei/10⁹)` 存 EVM_META `0x07 | H`
   （进重放脚本、`removeAbove` 对称清除）。
2. **释放**：`setMain(H')` 在 EVM 执行之后查 `H = H' − N` 的 `0x07` 记录，
   逐笔协议记账：锁定地址减 amountNano、目标原生地址加 amountNano
   （`AddressStore` 直接更新，reward 先例）。防御：锁定余额不足则**确定性跳过
   全部剩余释放** + 响亮告警（正常不可达，见 §4；确定性：全网同数据同跳过）。
同一 `setMain` 内的确定性次序：原生记账（applyBlock：入金打入锁定地址）→ EVM
执行（deposit mint → 交易执行/burn → 日志检测）→ 成熟释放（H−N 记录）。
测试钉死此序。

3. **反转**：`unSetMain(H')` 镜像反转本高度已做的释放（目标减、锁定加），
   且必须发生在 `rollbackTo` 擦除 `0x07` 记录**之前**（实施时核实 unWindMain
   与 rollbackTo 的现有次序，缺陷 2 时确认过 unWindMain 只做一次
   rollbackTo(lowestUnwound−1)）。
4. N 深度让浅重组几乎触不到已释放资金；深于 N 的重组由 2+3 的对称性兜底。

## 4. 守恒不变量与安全

- **核心不变量**：`锁定地址余额 × 10⁹ ≥ EVM 内桥系 wei 流通量`，恒成立且只会更宽——
  gas 燃烧销毁 wei（缺口 2 路径 α 烧不给 coinbase）、误存款 mint 进回收地址，
  都只减不增流通侧。释放侧永远有足额背书。
- **⚠ evm.alloc 无背书警告（醒目）**：创世预分配的 wei 没有原生锁定背书——devnet
  的 10²⁴ wei 若走出金会掏空属于存款人的锁定池。**规则：启用出金（Phase 3b 激活）
  的网络 `evm.alloc` 必须为空**；实现加启动告警（alloc 非空 && bridge 激活 → WARN，
  devnet 玩具链自担）。
- 攻击面：伪造入金需伪造原生转账（原生共识挡）；伪造出金需伪造合约日志（须真实
  执行合约 = 真实烧 wei，收据在链式根内）；重放/双花由高度绑定记录 +
  setMain/unSetMain 对称性挡；remark 解析确定性（纯函数，坏输入 → 回收地址，
  不存在节点间分歧空间）；合约无特权入口。
- 回收地址是共识配置：各网络值不一致 = mint 目标分歧 = 根分叉，与激活高度同风险
  等级，随分叉排期同步定案（devnet 用已注资测试地址）。

## 5. 零变化面

原生交易/块格式零改动（remark 现成字段、锁定地址普通地址、无新字段码——4-bit
码空间耗尽的既有约束正是方案 C 的死因）；`Block.java` 字节不动；链式根算法不动
（mint/burn 走既有 state delta 与收据）；收据 RLP/bloom/0x04 反向索引/C4 历史窗口
不动；P2P 零新消息；EvmTxPool/执行器（XdagEvmExecutor）零改动。

## 6. 测试面

**Phase 3a**：E2E 原生转账→mint→`eth_getBalance`（真实 HTTP）；重放确定性
（rollback 后链式根逐字节复现，含 deposit 高度）；重组撤销 mint（回退分支后余额
消失）；非法 remark（缺失/坏校验和/截断）→ 回收地址 mint；Base58Check 往返与
边界；激活门前后（门前打款不 mint）；换算精确（1 nano ↔ 10⁹ wei 无损）；
同高度多笔入金排序确定性。

**Phase 3b**：burn→N 高度后释放→原生余额可查；成熟前重组（记录随 rollbackTo
消失，不释放）与成熟后重组（unSetMain 反转释放）各一例；尘埃拒绝
（`msg.value % 10⁹ != 0` revert）；锁定不足防御跳过（构造 alloc 场景）；
同高度入金+出金混合的记账顺序；合约 code hash 固定性断言；
`0x06`/`0x07` 记录的 removeAbove 对称性。

**回归**：两期各自全量绿（Phase 3a 基线 460）。

## 7. 分期交付物

- **Phase 3a**：锁定地址/桥合约地址常量类、setMain 检测收集、EVM_META `0x06`、
  mint + 重放集成、`evm.bridgeActivationHeight` + `evm.bridgeRecoveryAddress`
  配置（含 fail-fast）、remark Base58Check 编解码、E2E。
- **Phase 3b**：系统合约（solidity 源 + 固定字节码 + 播种）、日志扫描、EVM_META
  `0x07` 成熟队列、`setMain`/`unSetMain` 释放/反转钩子、
  `evm.bridgeWithdrawalDelay` 配置、alloc 非空告警、E2E。
