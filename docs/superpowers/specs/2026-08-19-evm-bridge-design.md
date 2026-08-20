# 缺陷 3：XDAG ↔ EVM 共识记账桥（设计 spec，两期实施）

日期：2026-08-19
分支：dev-evm
状态：已批准（brainstorming 定案；路线图方案 A 的第 3 个工程子项目，
前置为缺陷 1 EIP-2718/type-2，已于 2026-08-18 交付并全绿 460）；
Phase 3a（入金）已于 2026-08-19 交付（dev-evm，a691a29e..HEAD）；
Phase 3b（出金）已于 2026-08-20 交付

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

现有钱包 `xfer` 打款到锁定地址，remark 携带编码后的 20 字节 EVM 目标地址。
**编码格式（计划期勘误定案）**：`base58( 0x45 ‖ addr20 ‖ keccak256(0x45‖addr20)[0:2] )`
——23 字节载荷（版本字节 'E' + 地址 + 2 字节校验），版本字节非零保证无前导零，
编码后**恒为 32 个 ASCII 字符**，正好填满 32 字节 remark。勘误：brainstorming 时
估的标准 Base58Check（24 字节载荷）实为 33 字符放不下（devnet conf 的 fund 地址
即 33 字符实证）；hex 42 字符更放不下。版本字节还能确定性拒绝误贴的原生地址串
（结构/长度不符 → 解码失败 → 回收地址）。外部向量（ethers base58 独立实现）：
`0x7e5f4552091a69125d5dfcb7b8c2659029395bdf ↔ "2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1"`。

### 2.2 检测与入账

1. **检测**：`setMain` 的 applyBlock 遍历中，凡 `XDAG_FIELD_OUTPUT`(0x0D) 打给
   锁定地址且高度 ≥ 激活门，按既有 DFS 确定序收集 `(EVM 目标, 金额 nano)`——与
   evmRefs 收集（`BlockchainImpl:1087-1190`）同一套顺序保证。旧式 `XDAG_FIELD_OUT`
   指向 32 字节块哈希、锁定地址是 20 字节钱包地址，故检测路径唯一。
2. **remark 解析**：去尾部零填充 → ASCII → base58 解码 → 23 字节校验
   （版本 0x45 + keccak 前 2 字节校验和）→ 20 字节 EVM 地址；任何失败
   （缺失/坏校验/坏版本/长度不符）→ 目标 = 回收地址。**所有入金必 mint**——
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
   （`AddressStore` 直接更新，reward 先例）。

   **交付期修订（防御语义裁定为整高度 all-or-nothing，取代本条原文的
   "确定性跳过全部剩余释放"前缀语义）**：
   1. setMain(H') 释放前先对 H'−N 高度的 burn 总额与桥锁地址余额做**整体预检**
      （whole-height total pre-check）。
   2. 锁余额不足以覆盖该高度全部 burn 时，**该高度一笔也不释放，也不写 0x08 日志**
      （打 CRITICAL 日志后整体跳过；0x08 释放日志见附录 A 交付期修订）。
   3. 被跳过的高度**永不重试**——资金保持锁定，等待 recovery-address 人工干预。
   4. 采用 all-or-nothing 而非前缀释放的理由：顺序无关（order-invariance，
      释放结果不依赖同高度内 burn 的排列）、日志状态二元化（0x08 要么完整
      要么不存在，反转无部分态）、以及"冻结而非花费"（freeze-don't-spend，
      异常时保守持锁）。

   （锁不足正常不可达，见 §4；确定性不变：全网同数据同跳过。）
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
消失）；非法 remark（缺失/坏校验和/坏版本/截断/原生地址串误贴）→ 回收地址 mint；
remark 编码往返与外部向量；激活门前后（门前打款不 mint）；换算精确
（1 nano ↔ 10⁹ wei 无损）；
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

## 附录 A：系统合约工件（Phase 3b 固化）

出金系统合约的全部共识工件在此固化。合约字节码是共识数据，任何字节改动都是硬分叉；
以下 hex 与 `io.xdag.evm.bridge.BridgeContract` 中的常量逐字节一致（634 字节，已核对）。

### A.1 Solidity 源

`src/test/resources/solidity/xdag_bridge.sol`（逐字）：

```solidity
// SPDX-License-Identifier: MIT
pragma solidity 0.8.26;

/// XDAG bridge withdrawal entry (spec 2026-08-19 section 3.1). No admin, no upgrade, no selfdestruct.
/// msg.value stays in the contract forever: its balance is the cumulative burned wei (audit figure).
contract XdagBridge {
    event Withdrawal(bytes20 indexed nativeTarget, uint256 amount);

    function withdraw(bytes20 nativeTarget) external payable {
        require(msg.value > 0 && msg.value % 1e9 == 0, "bad amount");
        emit Withdrawal(nativeTarget, msg.value);
    }
}
```

### A.2 编译设置（可复现）

- 编译器：`solc 0.8.26+commit.8a97fa7a`（solc-js，版本串 `0.8.26+commit.8a97fa7a.Emscripten.clang`）。
- 优化器：**关闭**（`optimizer.enabled = false`）。
- metadata：`bytecodeHash: "none"`（不追加 metadata 哈希，字节码尾部无 CBOR metadata 变动源）。
- evmVersion：`shanghai`（内嵌 EVM 跑的正是 Shanghai fork）。**注**：solc 0.8.26 的默认
  evmVersion（cancun）对本源码产出**逐字节相同**的字节码（已核对，同一 keccak）——因此
  shanghai 的固定只是文档意义上的显式声明，不是分叉风险点，也不发 Cancun-only 操作码
  （MCOPY/TLOAD）。

标准 JSON 设置：

```json
{
  "optimizer": { "enabled": false },
  "evmVersion": "shanghai",
  "metadata": { "bytecodeHash": "none" }
}
```

固定的常量取自 `evm.deployedBytecode.object`（**runtime** 字节码，634 字节；非 creation 字节码）。
`src/test/resources/solidity/README.md` 记有 solc-js 复现步骤；工件离线编译一次后嵌入 Java，
构建不跑 `solc`（保持 hermetic），由 `BridgeContractTest` 以长度/keccak/topic0/selector 钉死。

### A.3 地址推导

合约地址 = `keccak256("XDAG-EVM-BRIDGE-CONTRACT-v1")` 的**末 20 字节** =
`0x97d38b2e167709f0ddb4880d197ce2920241e3ea`
（完整 keccak `0x04022c2feaf7ac263ccc018497d38b2e167709f0ddb4880d197ce2920241e3ea`）。
nothing-up-my-sleeve 地址，无私钥、无 CREATE/CREATE2 部署——直接以固定字节码播种
（marker `0x04`，genesis 同款，落在链式根之外）。

### A.4 Runtime 字节码（634 字节，唯一被播种的代码）

```
0x60806040526004361061001d575f3560e01c8063dce0f64e14610021575b5f80fd5b61003b6004803603810190610036919061013c565b61003d565b005b5f3411801561005a57505f633b9aca0034610058919061019d565b145b610099576040517f08c379a000000000000000000000000000000000000000000000000000000000815260040161009090610227565b60405180910390fd5b806bffffffffffffffffffffffff19167fcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42346040516100d89190610254565b60405180910390a250565b5f80fd5b5f7fffffffffffffffffffffffffffffffffffffffff00000000000000000000000082169050919050565b61011b816100e7565b8114610125575f80fd5b50565b5f8135905061013681610112565b92915050565b5f60208284031215610151576101506100e3565b5b5f61015e84828501610128565b91505092915050565b5f819050919050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52601260045260245ffd5b5f6101a782610167565b91506101b283610167565b9250826101c2576101c1610170565b5b828206905092915050565b5f82825260208201905092915050565b7f62616420616d6f756e74000000000000000000000000000000000000000000005f82015250565b5f610211600a836101cd565b915061021c826101dd565b602082019050919050565b5f6020820190508181035f83015261023e81610205565b9050919050565b61024e81610167565b82525050565b5f6020820190506102675f830184610245565b9291505056fea164736f6c634300081a000a
```

### A.5 派生工件

- **codeHash** = `0x80d42d27751c0527c5efcaddfa9fb802ffda21137cf695c78cf5c114c73d2c78`（runtime 字节码的 keccak）。
- **Withdrawal topic0** = `keccak256("Withdrawal(bytes20,uint256)")` =
  `0xcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42`
  （自校验：此 hex 出现在上面的 runtime 字节码内；selector 亦然）。
- **withdraw selector** = `0xdce0f64e`；`withdraw(target)` 的 calldata =
  `0xdce0f64e ‖ target20 ‖ 12 个零字节`（bytes20 在其 ABI 槽内左对齐）。
- **事件形状**：`nativeTarget` 为 indexed bytes20 → topic1 = target20 右补零至 32 字节
  （解码取 topic1 的 [0,20) 字节）；`amount` 非 indexed → data = 32 字节大端 uint256 wei。

### A.6 确定性 caveat（释放的活性前提）

`setMain(H')` 的释放要求本节点 EVM 已**执行到**烧毁高度 `H = H'−N`。因缺失 blob 数据而
在该高度以下停摆滞后的节点**无法**确定性释放——它会**确定性跳过**该高度的释放并打
CRITICAL 日志。devnet 上 blob 恒为本地，此路径不可达；共享网络下这正是 §13.3-2 的
DA 硬门槛（主块必须携带其引用的全部 EVM 交易数据方可导入）必须在 `evm.enabled = true`
之前关闭的原因。这是诚实的 v1 语义；`0x08` 释放日志使反转"反转的是实际做过的事"，
因此即便某次跳过造成不对称，也无法污染反转记账。

### A.7 交付期修订：反转实现为 0x08 释放日志

§3.2.3 的反转实现为 **`0x08` 释放日志**（`setMain` 记账实际释放、unwind 读日志反转后删除）
——比 spec 原文"读 `0x07` 镜像反转"更强：即使某高度因停摆/锁不足跳过了部分释放，
反转也永远精确等于实际所为。
