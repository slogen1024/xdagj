# XDAG 智能合约深度调研报告

> 调研日期：2026-06-03 · **更新：2026-07-01**
> 目标：深度分析 XDAG 如何实现类 ETH 智能合约，涵盖已有代码实现、行业对比、技术挑战与可行路径

> **2026-07-01 更新摘要**
> - **技术路线已切换**：`dev-evm` 分支嵌入 Besu EVM 26.5.0（Shanghai），**不再** rebase `feature/evm` 自研解释器
> - **Sub-project A + B1 代码已落地**（executor + `RocksDbWorldUpdater`）；A verification gate、B2、C 未完成
> - **EVM tx 承载**：R1 payload-by-reference 已确认 → 见 `docs/superpowers/specs/2026-06-06-xdag-evm-subproject-b2-r1-protocol.md`
> - **排序策略**：复用 `BlockchainImpl.applyBlock()` DFS，**非**独立 Conflux GHAST 模块 → 见 `docs/superpowers/specs/2026-06-06-xdag-evm-decision-record.md` ADR-003

---

## 目录

1. [当前状态总览](#1-当前状态总览)
2. [feature/evm 分支：完整 EVM 实现分析](#2-featureevm-分支完整-evm-实现分析)
   - 2.1 [架构总览](#21-架构总览)
   - 2.2 [EVM 执行引擎 (EVM.java)](#22-evm-执行引擎-evmjava)
   - 2.3 [操作码体系 (OpCode.java)](#23-操作码体系-opcodejava)
   - 2.4 [Gas 费用表 (FeeSchedule.java)](#24-gas-费用表-feeschedulejava)
   - 2.5 [交易执行器 (TransactionExecutor.java)](#25-交易执行器-transactionexecutorjava)
   - 2.6 [XDAG-EVM 交易适配器](#26-xdag-evm-交易适配器)
   - 2.7 [状态存储层 (Repository)](#27-状态存储层-repository)
   - 2.8 [程序运行时 (Program)](#28-程序运行时-program)
   - 2.9 [内存与栈](#29-内存与栈)
   - 2.10 [数据字 (DataWord)](#210-数据字-dataword)
   - 2.11 [链规范体系 (Spec)](#211-链规范体系-spec)
   - 2.12 [预编译合约](#212-预编译合约)
   - 2.13 [程序调用上下文 (ProgramInvoke)](#213-程序调用上下文-programinvoke)
   - 2.14 [zk-SNARK 密码学](#214-zk-snark-密码学)
   - 2.15 [异常体系](#215-异常体系)
   - 2.16 [交易类型扩展](#216-交易类型扩展)
   - 2.17 [账户状态模型](#217-账户状态模型)
3. [feature/new-evm 分支：增量改进分析](#3-featurenew-evm-分支增量改进分析)
4. [Master 分支现状与差距分析](#4-master-分支现状与差距分析)
5. [Mars 路线图定位](#5-mars-路线图定位)
6. [行业对比：DAG 项目智能合约方案](#6-行业对比dag-项目智能合约方案)
   - 6.1 [Conflux Tree-Graph 方案](#61-conflux-tree-graph-方案)
   - 6.2 [Kaspa BlockDAG 方案](#62-kaspa-blockdag-方案)
   - 6.3 [IOTA/Shimmer 方案](#63-iotashimmer-方案)
   - 6.4 [Vite 异步合约方案](#64-vite-异步合约方案)
   - 6.5 [Fantom/Sonic 方案](#65-fantomsonic-方案)
7. [学术研究前沿](#7-学术研究前沿)
8. [核心技术挑战深度分析](#8-核心技术挑战深度分析)
9. [XDAG 可行实现路径](#9-xdag-可行实现路径)
10. [结论与建议](#10-结论与建议)
11. [参考资料](#11-参考资料)

---

## 1. 当前状态总览

| 维度 | 状态 |
|------|------|
| **主网/Master 分支** | 不支持智能合约，仅支持 TRANSFER 交易 |
| **`dev-evm` 分支（当前 active）** | Besu Shanghai 嵌入 + B1 持久化 WorldUpdater；**未**接入共识/RPC/P2P |
| **feature/evm 分支** | 自研 Constantinople 解释器（67 文件）— **战略废弃**，仅作设计参考 |
| **feature/new-evm 分支** | feature/evm + develop 合并尝试，同样 stale，**不继续** |
| **Mars 路线图** | 拓展期（Phase 3）进行中 — A/B1 代码有，链上/RPC 无 |
| **技术选型** | 路径 A + Besu 嵌入 + R1 payload-by-reference |

**核心发现（2026-07-01）：** 智能合约执行层已在 `dev-evm` 用 Besu 证明（USDT JUnit，待 `.bin` gate）。距生产节点的缺口是 **B2 共识集成**（R1 协议、applyBlock hook、P2P blob、reorg）和 **C eth_* RPC**，而非 EVM 解释器本身。详见 `docs/superpowers/specs/` 下 A/B/B2-R1/ADR 文档。

---

## 2. feature/evm 分支：完整 EVM 实现分析

### 2.1 架构总览

feature/evm 分支在 master 基础上新增 **797 个文件变更，50,041 行插入**。EVM 实现采用 **Facade/Adapter 模式**，将 XDAG 原生类型桥接到 EVM 标准接口：

```
XDAG 核心层                         EVM 执行层
===========                         =========
XdagTransaction          --->      Transaction (接口)
  via XdagEvmTransaction             (Facade 适配器)

AccountState             --->      Repository (接口)
  via XdagEvmRepository              (Facade 适配器)

XAmount (XDAG 原生货币)   <--->     BigInteger (wei)
  via EVMUtils.xAmountToWei()        EVMUtils.weiToXAmount()

Block (XDAG DAG 块)      --->      Block (EVM 客户端接口)
  via XdagEvmBlock                   (Facade 适配器)
```

**完整文件结构：**

```
io.xdag.evm/
├── EVM.java                         # 主执行引擎（1,110 行）
├── OpCode.java                      # 完整 256 操作码枚举（951 行）
├── DataWord.java                    # 32 字节数据字（461 行）
├── FeeSchedule.java                 # Gas 费用表（236 行）
├── LogInfo.java                     # 事件日志
├── MessageCall.java                 # 内部调用表示
├── BytecodeCompiler.java            # 字节码编译/反编译工具
│
├── client/
│   ├── TransactionExecutor.java     # 交易执行器（214 行）
│   ├── XdagEvmTransaction.java      # XDAG→EVM 交易适配器
│   ├── XdagEvmRepository.java       # 状态存储适配器
│   ├── XdagEvmSpec.java             # XDAG 链规范
│   ├── XdagEvmPrecompiledContracts.java  # XDAG 预编译合约扩展点
│   ├── Repository.java              # 状态存储接口（16 个方法）
│   ├── Transaction.java             # 交易接口
│   ├── Block.java                   # EVM 块上下文接口
│   ├── BlockStore.java              # 块存储接口
│   ├── TransactionReceipt.java      # 交易回执
│   └── RocksdbProxy.java            # RocksDB EVM 持久化适配器
│
├── program/
│   ├── Program.java                 # 程序执行上下文（836 行）
│   ├── Memory.java                  # 线性内存管理
│   ├── Stack.java                   # EVM 栈（最大 1024）
│   ├── ProgramPreprocess.java       # 字节码预处理
│   ├── ProgramResult.java           # 执行结果
│   ├── InternalTransaction.java     # 内部交易记录
│   ├── invoke/
│   │   ├── ProgramInvoke.java       # 调用上下文接口
│   │   ├── ProgramInvokeFactory.java
│   │   ├── ProgramInvokeFactoryImpl.java
│   │   └── ProgramInvokeImpl.java
│   └── exception/                   # 13 个异常类
│       ├── BytecodeExecutionException.java
│       ├── OutOfGasException.java
│       ├── BadJumpDestinationException.java
│       ├── StackOverflowException.java
│       ├── StackUnderflowException.java
│       ├── IllegalOperationException.java
│       ├── CallTooDeepException.java
│       ├── CallDepthOverflowException.java
│       ├── InsufficientBalanceException.java
│       ├── PrecompiledFailureException.java
│       ├── ReturnDataCopyIllegalBoundsException.java
│       ├── StaticCallModificationException.java
│       └── ExceptionFactory.java
│
├── chainspec/
│   ├── Spec.java                    # 链规范接口
│   ├── BaseSpec.java                # 基础规范（Frontier/Homestead）
│   ├── ByzantiumSpec.java           # 拜占庭分叉
│   ├── ConstantinopleSpec.java      # 君士坦丁堡分叉
│   ├── PrecompiledContracts.java    # 预编译合约接口
│   ├── PrecompiledContract.java     # 单个预编译合约接口
│   ├── PrecompiledContractContext.java
│   ├── BasePrecompiledContracts.java      # 地址 1-5
│   ├── ByzantiumPrecompiledContracts.java # 地址 6-8 (BN128)
│   └── ConstantinoplePrecompiledContracts.java
│
├── state/
│   ├── Account.java                 # 账户模型
│   └── AccountStateImpl.java        # RocksDB 持久化
│
└── crypto/zksnark/                  # 14 个 zk-SNARK 文件
    ├── Params.java                  # 椭圆曲线常量
    ├── Field.java                   # 域运算接口
    ├── Fp.java                      # F_p 域（基础域）
    ├── Fp2.java                     # F_p2 域（二次扩展）
    ├── Fp6.java                     # F_p6 域
    ├── Fp12.java                    # F_p12 域
    ├── BN128.java                   # BN128 曲线（抽象）
    ├── BN128Fp.java                 # BN128 over F_p
    ├── BN128Fp2.java               # BN128 over F_p2
    ├── BN128G1.java                 # G1 子群
    ├── BN128G2.java                 # G2 子群
    └── PairingCheck.java            # 配对检查（Ate pairing）
```

### 2.2 EVM 执行引擎 (EVM.java)

**文件：** `src/main/java/io/xdag/evm/EVM.java`（1,110 行）

核心执行循环由两个方法组成：

- **`play(Program)`** — 主循环，反复调用 `step()` 直到程序停止或异常
- **`step(Program)`** — 单步执行，包含两个 switch 块：
  1. **Gas 计算** (行 133-319)：根据操作码计算并扣除 gas
  2. **操作执行** (行 324-1072)：实际执行操作码逻辑

**内存扩展 Gas 计算** 遵循以太坊黄皮书公式：

```
memory_cost = MEMORY * words + words² / 512
```

**EIP 特性门控：** 通过 `Spec` 接口按需启用：
- `eip145()` — 位移指令 (SHL/SHR/SAR)
- `eip1052()` — EXTCODEHASH 操作码
- `eip1014()` — CREATE2 确定性地址
- `eip1283()` — SSTORE 净 gas 计量

**异常处理：** 任何执行异常都会消耗全部剩余 gas 并重置未来退款。

**XDAG 适配：**
- 使用 Apache Tuweni `Bytes`/`MutableBytes` 替代原始 `byte[]`
- 使用 Tuweni 的 `Hash.keccak256()` 实现 SHA3 操作码
- `isDeadAccount()` 目前 stub 为始终返回 `false`（TODO 待完善）

### 2.3 操作码体系 (OpCode.java)

**文件：** `src/main/java/io/xdag/evm/OpCode.java`（951 行）

完整实现以太坊全部 256 个操作码槽位：

| 范围 | 类别 | 示例 |
|------|------|------|
| 0x00-0x0b | 算术运算 | STOP, ADD, MUL, SUB, DIV, SDIV, MOD, SMOD, ADDMOD, MULMOD, EXP, SIGNEXTEND |
| 0x10-0x1d | 比较与位运算 | LT, GT, SLT, SGT, EQ, ISZERO, AND, OR, XOR, NOT, BYTE, SHL, SHR, SAR |
| 0x20 | 哈希 | SHA3 (Keccak-256) |
| 0x30-0x3f | 环境信息 | ADDRESS, BALANCE, ORIGIN, CALLER, CALLVALUE, CALLDATALOAD/SIZE/COPY, CODESIZE/COPY, GASPRICE, EXTCODESIZE/COPY/HASH |
| 0x40-0x48 | 区块信息 | BLOCKHASH, COINBASE, TIMESTAMP, NUMBER, DIFFICULTY, GASLIMIT, CHAINID, SELFBALANCE, BASEFEE |
| 0x50-0x5b | 栈/内存/存储 | POP, MLOAD, MSTORE, MSTORE8, SLOAD, SSTORE, JUMP, JUMPI, PC, MSIZE, GAS, JUMPDEST |
| 0x60-0x7f | PUSH1-PUSH32 | 将 1-32 字节立即数压栈 |
| 0x80-0x8f | DUP1-DUP16 | 复制栈上第 1-16 个元素 |
| 0x90-0x9f | SWAP1-SWAP16 | 交换栈顶与第 2-17 个元素 |
| 0xa0-0xa4 | LOG0-LOG4 | 发射事件日志（0-4 个 topic） |
| 0xf0-0xff | 系统操作 | CREATE, CALL, CALLCODE, RETURN, DELEGATECALL, CREATE2, STATICCALL, REVERT, SUICIDE |

**CallFlags 枚举** 优雅地编码了 CALL 变体的语义差异：

```java
CALL:         Call + HasValue           // 标准调用，可传值
CALLCODE:     Call + HasValue + Stateless // 在调用者上下文执行被调用者代码
DELEGATECALL: Call + Stateless + Delegate // 保持原始 caller 和 value
STATICCALL:   Call + Static              // 只读调用，禁止状态修改
```

**Gas Tier 分级：** ZeroTier(0), BaseTier(2), VeryLowTier(3), LowTier(5), MidTier(8), HighTier(10), ExtTier(20), SpecialTier(1)

### 2.4 Gas 费用表 (FeeSchedule.java)

**文件：** `src/main/java/io/xdag/evm/FeeSchedule.java`（236 行）

关键 Gas 值（对标以太坊黄皮书 Appendix G）：

| 操作 | Gas 值 | 说明 |
|------|--------|------|
| TRANSACTION | 21,000 | 基础交易成本 |
| TRANSACTION_CREATE_CONTRACT | 53,000 | 合约创建交易成本 |
| SLOAD | 50 | 存储读取 |
| SET_SSTORE | 20,000 | 存储首次写入 |
| RESET_SSTORE | 5,000 | 存储重写 |
| REFUND_SSTORE | 15,000 | 存储清除退款 |
| REUSE_SSTORE | 200 | EIP-1283 净 gas 计量 |
| CALL | 40 | 内部调用基础 gas |
| VT_CALL | 9,000 | 带值转移的调用附加 gas |
| NEW_ACCT_CALL | 25,000 | 向新账户调用附加 gas |
| STIPEND_CALL | 2,300 | 调用时赠送的 gas 津贴 |
| CREATE | 32,000 | 内部合约创建 |
| CREATE_DATA | 200 | 每字节合约代码存储 |
| SHA3 | 30 | Keccak-256 基础 |
| SHA3_WORD | 6 | Keccak-256 每字 |
| EC_RECOVER | 3,000 | 签名恢复预编译 |
| EXP_GAS | 10 | 指数运算基础 |
| EXP_BYTE_GAS | 10 | 指数每字节 |
| BALANCE | 20 | 余额查询 |

**XDAG 适配：**
- `SUICIDE = 0` 和 `NEW_ACCT_SUICIDE = 0` — XDAG 使用零成本自毁，与以太坊的 5,000 gas 不同
- Gas 定价总体对标 EIP-150 (Tangerine Whistle) 级别，低于 Istanbul 之后的以太坊
- 类未标记为 `final`，允许 Spec 链覆盖特定网络的 gas 值

### 2.5 交易执行器 (TransactionExecutor.java)

**文件：** `src/main/java/io/xdag/evm/client/TransactionExecutor.java`（214 行）

完整的交易生命周期管理：

```
run() → prepare() → execute() → TransactionReceipt

prepare():
  ├── 检查 gas 是否超出区块 gas 上限
  ├── 检查 gas 是否满足基础成本
  ├── 检查 nonce 匹配
  └── 检查发送者余额充足

execute():
  ├── 1. 从发送者预扣 gas 费用
  ├── 2. 创建"幻影调用"(空 bytecode Program) 管理 gas 记账
  ├── 3. 扣除基础交易成本
  ├── 4a. CREATE 交易 → program.createContract(...)
  ├── 4b. CALL 交易 → 增加发送者 nonce → program.callContract(...)
  ├── 5. 提交已删除账户
  ├── 6. 计算退款: min(futureRefund + suicideRefund, gasUsed/2)
  └── 7. 将剩余 gas 退还发送者
```

**"幻影调用"模式** 是一个关键设计：创建一个空操作码的 Program 实例，纯粹作为 gas 记账的包装器。这样可以复用 Program 的 gas 管理基础设施来执行顶层交易。

退款计算严格遵循以太坊规范：`min(futureRefund + suicideRefund, gasUsed/2)`。

### 2.6 XDAG-EVM 交易适配器

**文件：** `src/main/java/io/xdag/evm/client/XdagEvmTransaction.java`（84 行）

Facade/Adapter 模式，将 XDAG 原生 `XdagTransaction` 包装为 EVM `Transaction` 接口：

```java
public class XdagEvmTransaction implements Transaction {
    private XdagTransaction transaction;

    @Override public boolean isCreate() {
        return transaction.getType() == TransactionType.CREATE;
    }
    @Override public BigInteger getValue() {
        return EVMUtils.xAmountToWei(transaction.getValue());  // XAmount → Wei
    }
    @Override public BigInteger getGasPrice() {
        return EVMUtils.xAmountToWei(transaction.getGasPrice()); // XAmount → Wei
    }
    // from, to, nonce, data, gas 直接委托
}
```

这是 **XDAG 原生交易格式与 EVM 执行层之间的关键桥梁**。`EVMUtils.xAmountToWei()` 负责货币单位转换，使 EVM 的 gas 计算（假设 18 位小数精度）能与 XDAG 原生货币单位兼容。

### 2.7 状态存储层 (Repository)

**接口：** `src/main/java/io/xdag/evm/client/Repository.java`（178 行，16 个方法）

```java
public interface Repository {
    // 账户生命周期
    boolean exists(Bytes address);
    void createAccount(Bytes address);
    void delete(Bytes address);

    // Nonce 管理
    long increaseNonce(Bytes address);
    void setNonce(Bytes address, long nonce);
    long getNonce(Bytes address);

    // 合约代码存储
    void saveCode(Bytes address, Bytes code);
    Bytes getCode(Bytes address);

    // 合约状态存储 (key-value)
    void putStorageRow(Bytes address, DataWord key, DataWord value);
    DataWord getStorageRow(Bytes address, DataWord key);

    // 余额
    BigInteger getBalance(Bytes address);
    void addBalance(Bytes address, BigInteger value);

    // 快照/事务
    Repository startTracking();  // 创建快照
    Repository clone();
    void commit();               // 提交变更到上层
    void rollback();             // 回滚变更
}
```

**实现：** `XdagEvmRepository`（140 行）包装 XDAG 的 `AccountState`：

- `getBalance()` / `addBalance()` 通过 `EVMUtils` 在 `XAmount` 和 `BigInteger` (wei) 之间转换
- `putStorageRow()` / `getStorageRow()` 将 `DataWord` 桥接为 `Bytes`
- `startTracking()` 调用 `accountState.track()` 创建快照层
- `delete()` 通过将代码设为 null 来标记账户删除

**底层 AccountState 实现** 使用分层快照架构：
- `ConcurrentHashMap<Bytes, Bytes> updates` 缓冲进行中的变更
- 链式 `prev` 引用实现快照分层：`track()` 创建新层，`commit()` 向上合并
- 根层直接写入 `KVSource<Bytes, Bytes>` (RocksDB)
- 三种数据类型前缀：`TYPE_ACCOUNT=0`, `TYPE_CODE=1`, `TYPE_STORAGE=2`
- 存储键为复合键：`[TYPE_STORAGE | address | key]`

### 2.8 程序运行时 (Program)

**文件：** `src/main/java/io/xdag/evm/program/Program.java`（836 行）

这是单个 EVM 执行帧的运行时状态持有者，约 60 个方法：

**常量：**
- `MAX_DEPTH = 1024` — 最大调用深度
- `MAX_STACKSIZE = 1024` — 最大栈大小

**合约创建流程 (CREATE/CREATE2)：**

```
createContractImpl():
  1. 扣除 gas
  2. 增加发送者 nonce
  3. startTracking() 创建快照
  4. 设置新合约 nonce
  5. 转移余额 (value)
  6. 记录内部交易
  7. 创建新 Program 实例执行构造函数字节码
  8. 保存合约代码（检查存储成本 + 最大大小限制）
  9. commit() 或 rollback()
  10. 退还剩余 gas
```

**合约调用流程 (CALL/CALLCODE/DELEGATECALL/STATICCALL)：**

```
callContractImpl():
  1. 检查调用深度
  2. 检查预编译合约 → 直接执行并返回
  3. startTracking() 创建快照
  4. 转移余额 (如果是 CALL 且有 value)
  5. 创建新 ProgramInvoke
  6. 创建新 Program 实例执行合约字节码
  7. EVM.play(program) 执行
  8. 处理结果: commit() 或 rollback()
  9. 处理返回数据
```

**DELEGATECALL 特殊处理：** 传播父作用域的 caller 和 value。
**STATICCALL 特殊处理：** 通过 `isStaticCall()` 标志传播到子调用，禁止任何状态修改。

### 2.9 内存与栈

**Memory.java (185 行)**

分块内存分配策略：
- `CHUNK_SIZE = 1024` 字节 — 每块 1KB
- `WORD_SIZE = 32` 字节 — 对齐到 32 字节边界
- `softSize` 追踪逻辑大小（始终为 32 的倍数）
- `LinkedList<byte[]> chunks` 存储物理块，避免大连续分配
- 跨块读写自动处理

**Stack.java (69 行)**

- 基于 `java.util.Stack<DataWord>` 的薄包装
- `pop()` 使用 synchronized 同步
- 1024 元素上限（在 Program 层检查）

### 2.10 数据字 (DataWord)

**文件：** `src/main/java/io/xdag/evm/DataWord.java`（461 行）

**不可变** 256 位字表示，基于 Tuweni `Bytes`：

- 所有算术运算返回新实例（函数式风格）
- 结果用 `MAX_VALUE (2^256 - 1)` 掩码，强制 256 位溢出语义
- `add()` 使用手工字节级加法（避免 BigInteger 性能开销）
- `exp()` 使用 `BigInteger.modPow(exponent, TWO_POW_256)`
- 安全转换方法 `intValueSafe()` / `longValueSafe()` 溢出时返回 MAX_VALUE
- 支持有符号操作：`sValue()`, `sDiv()`, `sMod()`, `signExtend()` 实现二进制补码

### 2.11 链规范体系 (Spec)

继承链：`Spec (接口) → BaseSpec → ByzantiumSpec → ConstantinopleSpec → XdagEvmSpec`

| 规范 | 特性 |
|------|------|
| **BaseSpec** (Frontier/Homestead) | 所有 EIP 标志返回 false，无 gas 上限，合约大小无限制 |
| **ByzantiumSpec** | 自定义 FeeSchedule (BALANCE=400, CALL=700, SLOAD=200)，EIP-150 63/64 规则，合约最大 24,576 字节 |
| **ConstantinopleSpec** | 启用 EIP-145(位移), EIP-1014(CREATE2), EIP-1052(EXTCODEHASH), EIP-1283(净gas计量) |
| **XdagEvmSpec** | 继承 Constantinople，覆盖预编译合约为 `XdagEvmPrecompiledContracts` |

**EIP-150 63/64 规则：** `getCallGas()` 将请求的 gas 上限为 `available - available/64`，防止通过深递归调用耗尽所有 gas。

**默认规范：** `Spec.DEFAULT = new ByzantiumSpec()`

### 2.12 预编译合约

继承链：`PrecompiledContracts → Base → Byzantium → Constantinople → XdagEvm`

| 地址 | 合约 | Gas 成本 | 来源 |
|------|------|---------|------|
| 0x01 | **ECRecover** — ECDSA 签名恢复 (secp256k1) | 3,000 固定 | Base |
| 0x02 | **SHA-256** 哈希 | 60 + 12×words | Base |
| 0x03 | **RIPEMD-160** 哈希 | 600 + 120×words | Base |
| 0x04 | **Identity** — 原样返回输入 | 15 + 3×words | Base |
| 0x05 | **ModExp** — 模幂运算 | 按输入长度计算 (GQUAD_DIVISOR=20) | Base |
| 0x06 | **BN128Addition** — EC 点加法 | 500 固定 | Byzantium |
| 0x07 | **BN128Multiplication** — EC 标量乘法 | 40,000 固定 | Byzantium |
| 0x08 | **BN128Pairing** — 配对检查 (zkSNARK) | 80,000×pairs + 100,000 | Byzantium |

**ECRecover 实现细节：** 使用 `Keys.signatureToAddress()` 配合自定义 `ContractSign`，验证 v=27/28，r 和 s 在 [1, SECP256K1N) 范围内。

**`XdagEvmPrecompiledContracts`** 目前为空，是 **XDAG 特有预编译合约的扩展点** — 可在此添加 DAG 状态查询等 XDAG 原生功能。

### 2.13 程序调用上下文 (ProgramInvoke)

**`ProgramInvoke` 接口** 定义运行中 EVM 程序所需的全部上下文：

```java
// 交易上下文
getOwnerAddress(), getOriginAddress(), getCallerAddress()
getGasLimit(), getGasPrice(), getValue()
getDataSize(), getDataValue(offset), getDataCopy(offset, length)

// 区块上下文
getBlockPrevHash(), getBlockCoinbase(), getBlockTimestamp()
getBlockNumber(), getBlockDifficulty(), getBlockGasLimit()

// 数据库上下文
getRepository(), getOriginalRepository(), getBlockStore()

// 其他
getCallDepth(), isStaticCall()
```

**ProgramInvokeFactory 两种创建模式：**
1. **顶层调用：** 从交易创建，`callDepth = -1`，克隆 repository 作为 originalRepository（EIP-1283 净 gas 计量需要）
2. **内部调用：** `callDepth + 1`，传播原始交易的 origin 和 gasPrice，复制父 Program 的所有区块上下文

### 2.14 zk-SNARK 密码学

实现以太坊 alt_bn128 预编译合约（EIP-196, EIP-197）所需的完整密码学原语：

**域塔结构：**
```
Fp (素数域, mod P)
 └── Fp2 (二次扩展, a·i + b)
      └── Fp6 (三个 Fp2 元素)
           └── Fp12 (两个 Fp6 元素)
```

**椭圆曲线：**
- `BN128<T>` — Barreto-Naehrig 曲线，Jacobian 坐标
- `BN128Fp` / `BN128G1` — 第一参数群 (over F_p)
- `BN128Fp2` / `BN128G2` — 第二参数群 (twisted curve over F_p2)

**配对检查 (PairingCheck.java)：** 实现 BN128 上的最优 Ate 配对
1. `addPair()` 累积配对对
2. Miller loop 迭代（线求值 + 倍加/加法）
3. 结果乘积
4. `finalExponentiation()` — 使用 Fp12 的 cyclotomic 指数
5. 返回 1（成功）或 0（失败）

### 2.15 异常体系

13 个异常类，全部继承 `BytecodeExecutionException (extends RuntimeException)`：

| 异常 | 触发条件 |
|------|---------|
| `OutOfGasException` | gas 不足 |
| `BadJumpDestinationException` | JUMP 目标不是 JUMPDEST |
| `StackOverflowException` | 栈超过 1024 |
| `StackUnderflowException` | 栈上操作数不足 |
| `IllegalOperationException` | 无效/未定义操作码 |
| `CallTooDeepException` | 调用深度超限 |
| `InsufficientBalanceException` | 余额不足以转账 |
| `PrecompiledFailureException` | 预编译合约执行失败 |
| `ReturnDataCopyIllegalBoundsException` | RETURNDATACOPY 越界 |
| `StaticCallModificationException` | STATICCALL 中的状态修改 |

`ExceptionFactory` 提供工厂方法：`notEnoughOpGas()`, `notEnoughSpendingGas()`, `gasOverflow()`, `invalidOpCode()`, `badJumpDestination()`, `tooSmallStack()`, `tooLargeStack()`。

### 2.16 交易类型扩展

**新增文件：** `src/main/java/io/xdag/core/TransactionType.java`

```java
public enum TransactionType {
    COINBASE(0x00),   // 出块奖励
    TRANSFER(0x01),   // 余额转账
    CREATE(0x05),     // 合约部署
    CALL(0x06);       // 合约调用
}
```

### 2.17 账户状态模型

**Account.java：** POJO，四个字段：
- `Bytes address` — 账户标识
- `XAmount available` — 可用余额
- `XAmount locked` — 锁定余额
- `long nonce` — 交易计数器

支持 `SimpleEncoder`/`SimpleDecoder` 二进制序列化。

---

## 3. feature/new-evm 分支：增量改进分析

| 指标 | feature/evm | feature/new-evm | master |
|------|-------------|-----------------|--------|
| 总提交数 | 438 | 665 (+227) | — |
| Java 版本 | 15 | 17 | 21 |
| 项目版本 | 0.4.3 | 0.4.7 | 0.8.3 |
| 与 master 差异 | 797 文件 | 744 文件 | — |

**feature/new-evm = feature/evm + 密码学迁移 + 快照系统**，而非新的 EVM 功能。

### 3.1 密码学库迁移（最大变更）

| 组件 | feature/evm | feature/new-evm |
|------|-------------|-----------------|
| 密钥类型 | Tuweni `SECP256K1.KeyPair` | Besu `KeyPair` / `SECPPrivateKey` / `SECPPublicKey` |
| 签名 | Tuweni `SECP256K1.Signature` | Besu `SECPSignature` |
| 哈希 | `org.apache.tuweni.crypto.Hash` | `org.hyperledger.besu.crypto.Hash` |
| 签名/验证 | Tuweni SECP256K1 | Besu `Sign.SECP256K1` |

新增文件：
- `io/xdag/crypto/Sign.java` — EC 曲线常量、密钥派生、Besu SECP256K1 实例
- `io/xdag/crypto/Hash.java` — SHA-256、Keccak-256、HMAC-SHA512、RIPEMD-160

### 3.2 快照系统（全新）

```
io.xdag.snapshot/
├── SnapshotJ.java                # RocksDB 快照读写器（238 行）
├── core/
│   ├── SnapshotUnit.java         # 快照数据单元（pubkey + balance + block data）
│   ├── BalanceData.java          # 余额数据（flags, timestamps, hashes）
│   └── StatsBlock.java           # 统计块（height, time, hash, difficulty）
├── config/
│   └── SnapShotKeys.java         # LMDB 键定义
└── db/
    ├── SnapshotChainStore.java      # 接口
    └── SnapshotChainStoreImpl.java  # 实现：读取 C 版本 LMDB 快照
```

- 兼容 C 版本 XDAG 的 LMDB 快照格式
- 支持 Snappy 压缩解压
- 双模式：`SnapshotJ` (RocksDB 原生) 和 LMDB (C 版本兼容)

### 3.3 其他改进

- **依赖更新：** 移除 hutool → Apache Commons；log4j 2.13.3 → 2.17.2（修复 Log4Shell）
- **网络状态扩展：** 新增 devnet 状态 (WDST, CDST, SDST)
- **配置重构：** hutool Setting → Apache Commons Configuration2
- **EVM 引擎变更极小：** 仅密码学调用点替换（Tuweni → Besu）

### 3.4 未完成事项

- EVM 引擎无新功能
- 未与 master 分支整合（master 已发展到 v0.8.3 / Java 21）
- 无 EVM-区块链桥接（EVM 仍是独立模块）
- BlockchainImpl 中多处 TODO 注释

---

## 4. Master 分支现状与差距分析

Master (v0.8.3, Java 21) 相比 EVM 分支已独立演进：

| 维度 | Master 现状 | EVM 分支 | 差距 |
|------|------------|---------|------|
| 密码学 | 自定义抽象层 (`ECKeyPair`) | Tuweni/Besu | 需统一 |
| RocksDB 列族 | 6 个 (INDEX, BLOCK, TIME, TXHISTORY, ADDRESS, ORPHANIND) | 基础列族 | 需扩展 |
| 交易类型 | 仅 TRANSFER | TRANSFER + CREATE + CALL | 需合并 |
| 账户模型 | Block-as-Address | Account (address, balance, nonce) | 需整合 |
| RPC | 基础 XDAG RPC | 无 EVM RPC | 需新增 |
| Java 版本 | 21 | 15/17 | 需升级 |
| 快照系统 | 独立实现 (SnapshotStore) | 独立实现 (SnapshotJ) | 需合并 |

**关键缺失（Besu 路线，2026-07-01 进度）：**

| 缺失模块 | 说明 | 优先级 | 进度 |
|----------|------|--------|------|
| A verification gate | 生成 `.bin` fixtures，`mvn test` 全绿 | P0 | 待做 |
| B2 R1 链上集成 | `EVM_TX` store、P2P、applyBlock hook、reorg | P0 | spec 已定，代码未做 |
| EVM RPC 接口 | `eth_call`, `eth_sendRawTransaction`, `eth_getCode` 等 | P0 | 未做（C） |
| MetaMask 兼容 Web3 RPC | JSON-RPC 2.0 兼容层 | P0/P1 | 未做（C） |
| P2P EVM tx 消息 | `EVM_TX_BROADCAST` / sync（R1 侧信道） | P0 | spec 已定 |
| ~~DAG→全序算法~~ | ~~独立 Conflux GHAST 模块~~ | — | **已由 ADR-003 替代**：`applyBlock()` DFS |
| ~~feature/evm rebase~~ | ~~合并自研引擎~~ | — | **已取消** → Besu ADR-001 |
| Kernel 接线 | `EVM_STATE` / executor 启动 | P0 | enum 有，未接线 |
| EVM 配置 | Block Gas Limit, activation height | P1 | HOCON 草案在 B2 spec |
| XRC 代币标准 | 类 ERC-20 链上规范 | P1 | 未做 |
| 事件日志/Bloom 过滤器 | receipts + 索引 | P1 | 未做 |
| 合约调试工具 | trace, debug_traceTransaction | P2 | 未做 |

---

## 5. Mars 路线图定位

XDAG "火星计划" 四阶段：

| 阶段 | 名称 | 状态 | 智能合约相关 |
|------|------|------|-------------|
| 1 | 探索期 | ✅ 完成 | 测试网、RandomX、Libp2p |
| 2 | 着陆期 | ✅ 完成 | 主网、RPC、Stratum、快照 |
| **3** | **拓展期** | **⏳ 部分完成** | **EVM+Solidity、MetaMask 兼容、以太坊账户模型、XRC 标准** |
| 4 | 繁荣期 | 未开始 | 跨链、预言机、DEX |

拓展期已完成项：地址块重构、手续费增加
拓展期未完成项：**EVM/Solidity 支持、XRC 标准、白名单开放**

难度评级：**极高**，预估 3-6 个月

---

## 6. 行业对比：DAG 项目智能合约方案

### 6.1 Conflux Tree-Graph 方案

**最成熟的 DAG 原生 EVM 方案，与 XDAG 最具参考价值（同为 PoW DAG）。**

#### 架构

Conflux 使用 **Tree-Graph** 账本结构 — 一棵有向树（parent 边）嵌入一个 DAG（parent + reference 边）。每个区块有且仅有一条 parent 边和零或多条 reference 边。

#### GHAST 主链选择算法

**Greedy Heaviest Adaptive Sub-Tree (GHAST)** 是从 DAG 提取线性排序的核心共识机制：

1. **从创世块开始**，检查父树中的所有子块
2. **在每个分叉处**，选择整个子树累计权重（PoW 难度）最大的子块
3. **贪婪前进** 直到叶子节点
4. **结果路径** 从创世块到叶子节点即为 **主链 (Pivot Chain)**

实现使用 **Link-Cut Trees** 数据结构实现 O(log n) 子树权重查询。

#### 自适应权重机制

在疑似活性攻击（主链不收敛）时，GHAST 切换模式：
- PoW 质量 ≥ `adaptive_heavy_block_ratio × 目标难度` 的块获得高权重
- 其他块获得 **零权重**
- 这有效减慢出块速度，将权重集中在稀少的重块上

#### 从 DAG 到全序的三步过程

```
步骤 1: GHAST 主链选择 → 有序主链块序列
步骤 2: 纪元 (Epoch) 定义 → 每个主链块定义一个 epoch，
        该主链块的"过去集"中所有尚未分配的块归入此 epoch
步骤 3: Epoch 内拓扑排序 → 按 parent/reference 因果关系排序，
        平局用区块哈希打破 → 确定性全序
```

**关键设计：** 块有效性与交易有效性独立 — 无效交易（重复、余额不足）成为空操作，不影响包含它的块的有效性。

#### 延迟执行

状态执行延迟 `DEFERRED_STATE_EPOCH_COUNT`（默认 5）个 epoch。`ConsensusWorker` 确定排序后，将 `ExecutionTask` 入队到独立的 `ConsensusExecutor` 线程。**问责机制** 允许诚实节点检测和纠正无效状态根。

#### 双空间架构

| 空间 | 特性 |
|------|------|
| **Core Space** | Conflux 原生交易格式、CIP-37 base32 地址、存储抵押模型 |
| **eSpace** | 完整 EVM 兼容、标准以太坊地址、gas 模型、MetaMask 支持 |

两个空间共享同一 Tree-Graph 账本。**CrossSpaceCall** 内部合约实现原子跨空间资产和数据转移。

**确认延迟：** 4.5-7.4 分钟（因延迟执行和 epoch 排序）
**吞吐量：** 最高 3,000 TPS

#### 对 XDAG 的参考意义

XDAG 已有的主链概念（最大难度链）与 Conflux 的 GHAST 类似，可以：
1. 复用主链选择来建立全局排序
2. 定义 epoch（按时间帧或主链块）
3. epoch 内拓扑排序
4. 延迟执行吸收重组

### 6.2 Kaspa BlockDAG 方案

#### GHOSTDAG 协议

Kaspa 使用 GHOSTDAG 提取全序：
1. 计算 DAG 中每个子树的累计 PoW 权重
2. 选择最重路径作为"蓝链"
3. 与主流诚实产出关联紧密的块为"蓝块"，关联弱的为"红块"
4. 所有块（蓝+红）都包含在全序中，红块的冲突交易被作废

当前 10 BPS（每秒 10 块），路线图目标 100 BPS。

#### Kasplex L2: Based Rollup

**最简 L2 架构：**

```
用户 → 提交含 EVM 字节码的 L1 交易
     → Kaspa GHOSTDAG 排序
     → L2 索引器扫描 L1，提取 EVM 字节码
     → 按规范顺序执行
     → 更新 L2 状态
```

- 无独立排序器，依赖 L1 排序
- **当前限制：** 索引器是受信任的，可以呈现错误状态
- 2025年8月上线 zkEVM 主网，增加零知识证明

#### vProgs: 原生 L1 可验证程序

- 每个 vProg 是独立应用，拥有确定性状态转换函数
- 链下执行 + 链上 ZK 证明验证
- **同步可组合性：** 合并多个 vProg 的 ZK 证明为单个 L1 可验证承诺
- Toccata 硬分叉（2026年6月目标）引入 **SilverScript** 编译器和 ZK 验证操作码

### 6.3 IOTA/Shimmer 方案

#### ISC L2 链架构

IOTA Smart Contracts 作为锚定到 IOTA Tangle 的 L2 链运行：
- **Wasp 节点** 组成 **委员会**，并行运行多条 L2 链
- BFT 共识（>2/3 一致）确定状态变更
- 通过 **锚定对象** 将状态哈希锚定到 L1

#### Magic Smart Contracts

特殊系统合约，桥接 EVM 与 ISC sandbox：
- 部署在已知地址，预注入 `__iscSandbox`, `__iscAccounts`, `__iscUtil` 引用
- 允许 Solidity 合约访问 ISC 特有功能：原生代币管理、L1 交互、跨链通信
- 支持将 L1 原生币包装为 ERC20

#### IOTA Rebased (2025)

根本性架构转型：
- UTXO → **对象模型** (Object-based)，由 **Move VM** 驱动
- **Mysticeti 共识**：委托 PoS，亚秒级终结性，50,000+ TPS
- **双 VM 架构：** Move + EVM (via rollup)

### 6.4 Vite 异步合约方案

#### Block-Lattice 架构

每个账户拥有自己的区块链，交易天然并行。

#### 请求-响应模型

每次合约交互拆分为两笔交易：

```
1. 请求交易 (S): 客户端发送合约调用 → 追加到调用者账户链
2. 响应交易 (R): 合约执行结果 → 追加到合约账户链，引用请求哈希
```

**示例流程（B 调用 A）：**
```
客户端 → B.test()     → 请求 S1
B 执行 → A.add()      → 请求 S2 (在响应 R1 内)
A 执行 → sender.sum() → 请求 S3 (在响应 R2 内)
B 接收 → sum() 回调   → 响应 R3
```

#### Solidity++ 异步编程

扩展 Solidity，增加异步原语：
- **无返回值**，结果通过回调传递
- `message` 关键字定义消息，`onMessage` 处理器
- 同步调用 (`synccall`) 作为中断处理

#### 快照链终结性

**Snapshot Chain** 独立区块链，每个快照块记录：
- 所有账户余额
- 每个账户链最新块的哈希

交易一旦出现在快照块中即获得终结性。

**权衡：**
- 高吞吐量（真并行）
- 开发者复杂度高（异步回调模型）
- 跨合约调用非原子性
- 后期提案 VEP-19 引入同步调用

### 6.5 Fantom/Sonic 方案

#### Lachesis aBFT 共识

无领导者、异步、基于 DAG 的 aBFT 协议：

```
1. 验证者将交易打包为事件块 → 引用 2-k 个父块
2. 构建 OPERA DAG
3. Root 选择: 能触达 >2/3 验证权重的块成为 Root
4. Clotho 选择: Root 经多轮投票选出 Clotho 候选
5. Atropos 选择: Clotho 候选确认为 Atropos 终结块
6. 拓扑排序: Atropos 下的事件按层次 → Lamport 时间戳 → 哈希排序
```

**终结性：** Sonic 720 毫秒（亚秒级）

#### Fantom Virtual Machine (FVM)

- **动态翻译：** 代码转换为更高效的指令格式
- **超级指令：** 常见指令模式合并为单个操作（自适应演化）
- **结果：** 比前代快 ~65x，4,500 TPS

#### Carmen 数据库

替换以太坊 Merkle Patricia Trie (MPT)：
- **扁平存储：** 使用序号代替原始地址（`0x0DE2...0f80` → 序号 `123`）
- **实时剪枝：** 验证者在线持续剪枝，磁盘使用减少 90%
- **SonicDB S6：** 最新版本使用 Rust 实现的 Verkle Trie，见证证明 <150 字节

### 6.6 方案对比总表

| 项目 | DAG 类型 | 共识 | VM | 全序方案 | 终结性 | TPS | 状态 |
|------|---------|------|-----|---------|--------|-----|------|
| **Conflux** | Tree-Graph | GHAST (PoW) | EVM (双空间) | 主链+Epoch+拓扑排序 | 4.5-7.4 min | 3,000 | 生产 |
| **Kaspa** | BlockDAG | GHOSTDAG (PoW) | EVM (L2 Rollup) | 蓝链排序 | ~10s (L1) | 10 BPS | 生产 |
| **IOTA** | Tangle | Mysticeti (DPoS) | Move + EVM | 委员会 BFT | <1s | 50,000+ | 生产 |
| **Vite** | Block-Lattice | HDPoS (3层) | Solidity++ | 每账户链+快照链 | ~1s | 理论无限 | 生产 |
| **Fantom/Sonic** | OPERA DAG | Lachesis (aBFT PoS) | FVM (超级指令) | Atropos+拓扑排序 | 720ms | 10,000+ | 生产 |
| **XDAG** | DAG | 主链 (PoW) | EVM (未上线) | 待定 | ~64s | — | 开发中 |

---

## 7. 学术研究前沿

### 7.1 Thunderbolt: DAG 分片并发合约执行

发表于 EDBT 2026，专门解决分片 DAG 上图灵完备智能合约的并发执行问题。

**核心创新 — 双执行模型：**
- **EOV (Execution-Order-Validation)** 用于单分片交易：乐观执行 → 共识 → 验证，支持高并发
- **OE (Order-Execution)** 用于跨分片交易：确定性排序 → 执行，确保跨分片一致性

**动态并发控制器：** 运行时调度交易，无需预声明读写集。通过 **Transaction Preplay** — 分片提议者预执行交易确定结果后再创建区块。

**结果：** 64 副本下比串行执行提升 50x 吞吐量。

### 7.2 Batch-Schedule-Execute (BSX) 范式

IEEE ICDE 2024，提出替代传统 Order-Execute 模型：

```
1. Batching: 交易分组为无序块
2. Scheduling: 从交易依赖（冲突图）创建确定性调度
3. Execution: 按调度并发执行
```

冲突图是 **无向图**（顶点=交易，边=读写冲突），调度器将其转换为 **DAG** 表示执行序。**MCGBR 算法** 将交易分为无冲突集（层级），添加层间有向边。

**关键发现：** 最优调度是 NP 难的（归约自图顶点着色）。

### 7.3 Block-STM (Aptos)

Aptos 生产中使用的并行执行引擎：
- **乐观并发：** 交易推测性并行执行，假设无冲突
- **多版本数据结构：** 每个数据项维护多版本，按交易序号索引
- **验证：** 执行后验证读集一致性，失败则重新执行
- **无需预计算：** 不需要提前知道读写集
- **性能：** 160,000+ TPS

### 7.4 MEV 在 DAG 上的影响

- **多并发提议者** 打破单构建者假设，引入新 MEV 渠道
- **架构不变性结论：** 对于确定性出块时间的区块链，套利 MEV 在排序机制和出块时间分布变化下**保持不变** — 即从线性链改为 DAG **不能消除** 基本套利 MEV
- **Fino 协议：** 将交易内容传播与排序元数据解耦，用 Shamir 秘密分享加密交易内容，先确定顺序再解密
- **ShimmerEVM：** 使用随机交易排序作为简单 MEV 缓解

---

## 8. 核心技术挑战深度分析

### 8.1 交易排序与确定性（最核心挑战）

**问题：** DAG 允许并行区块产出，但智能合约执行要求确定性全序。

**各项目的排序机制：**

| 项目 | 排序机制 | 确定性保证 |
|------|---------|-----------|
| Conflux | GHAST 主链 + epoch 内拓扑排序 | 所有节点从相同 DAG 视图推导相同顺序 |
| Kaspa | GHOSTDAG 蓝链选择 | 诚实节点间收敛一致 |
| Fantom/Sonic | Lachesis Atropos + Lamport 时间戳排序 | DAG 因果关系的确定性终结 |
| Vite | 每账户链排序 + 快照链确认 | 无需全局排序 |

**XDAG 的机会（2026-07-01）：** XDAG 已有主链概念（最大难度链选择）与 `applyBlock()` 确定性 DFS — 可直接作为 EVM 执行序，无需先实现 Conflux GHAST。B2 在 `setMain` 路径挂接 `XdagEvmExecutor` + R1 `EVM_TX` 侧存储即可。

### 8.2 全局状态管理

**问题：** DAG 中"当前状态"在哪个时间点确定？

**各项目方案：**

| 项目 | 状态存储方案 |
|------|------------|
| Conflux | 标准 MPT + 延迟执行（5 epoch 滞后）吸收重组 |
| Fantom/Sonic | Carmen 扁平存储 → SonicDB Verkle Trie |
| Vite | 每合约独立账户链 + 快照链全局快照 |
| Kaspa vProgs | 每 vProg 管理自身状态，ZK 证明提交到 L1 |

**XDAG feature/evm 方案：** `AccountStateImpl` 的分层快照架构（ConcurrentHashMap 缓冲 + RocksDB 持久化）已为此提供基础。但需要与 DAG 的 epoch/主链同步机制集成。

### 8.3 读写冲突处理

**问题：** 两个并发交易修改同一合约状态时，如何检测和解决？

三种冲突类型：Write-After-Read (WAR), Write-After-Write (WAW), Read-After-Write (RAW)。

**对于图灵完备合约**，预声明读写集不现实（访问模式是动态的）。

**可行方案：**
1. **先排序后执行 (OE)：** 建立全序后顺序执行（Conflux 当前方案，最安全但吞吐量受限）
2. **乐观并发 (Block-STM)：** 推测性并行执行 + 验证 + 重试（Aptos 方案，高性能但复杂）
3. **预执行 (Thunderbolt)：** 提议者预执行确定冲突关系，构建执行 DAG

### 8.4 跨合约调用原子性

**问题：** 以太坊的 A→B→C 调用链是原子的。DAG 的异步模型如何保证？

| 方案 | 原子性 | 权衡 |
|------|--------|------|
| L1 顺序执行 (Conflux) | ✅ 完全原子 | 吞吐量受限 |
| L2 Rollup (Kaspa) | ✅ L2 内原子 | 跨 L1/L2 需桥接 |
| 请求-响应 (Vite) | ❌ 天然非原子 | 需不同编程模式 |
| ZK 证明合并 (Kaspa vProgs) | ✅ 跨应用原子 | 复杂密码学基础设施 |

**XDAG feature/evm 选择：** L1 顺序执行（类 Conflux），保证完全原子性。

### 8.5 确定性重放

**问题：** 所有节点必须从 DAG 的偏序中推导出相同的全序。

关键要求：
- 排序算法必须完全确定性
- 平局打破规则必须一致（通常用区块哈希）
- 所有节点对相同 DAG 视图必须产生相同结果

### 8.6 Gas 计量考量

| 模式 | 特点 |
|------|------|
| 标准 EVM gas | 按指令计费，兼容所有以太坊工具 |
| 调整后 gas | 如 Conflux eSpace SSTORE 40000（vs ETH 20000） |
| 多维定价 | 带宽/计算/存储分别定价（Arbitrum 5 维度） |
| 零费用 | 配额制替代 gas（Vite） |
| 超级指令优化 | 合并常见模式降低 gas（Sonic FVM） |

**XDAG feature/evm 选择：** 标准 EVM gas 模型（EIP-150 级别），自毁零成本。

---

## 9. XDAG 可行实现路径

### 路径 A: 主链排序 + L1 EVM 执行（类 Conflux）⬅ **dev-evm / Besu 方向**

```
XDAG DAG → 主链选择（maxDiffLink + setMain）
→ applyBlock() DFS 确定性遍历
→ EVM_TX_REF(32B hash) → EVM_TX store → XdagEvmExecutor
→ RocksDbWorldUpdater commit + stateRoot[mainHeight]
```

| 维度 | 评估 |
|------|------|
| **优点** | Solidity 完全兼容、跨合约调用原子性、开发者生态可复用 |
| **缺点** | 512B 块需 R1 侧存储、主块确认延迟 ~2048s、串行执行吞吐 |
| **开发量** | 瓶颈在 B2+C — 非 Besu 本身 |
| **参考** | Conflux 宏观方向；实现简化为 applyBlock DFS |
| **代码基础** | `dev-evm`: Besu executor + B1 WorldUpdater（**非** feature/evm） |

**关键实施步骤（D → A → B → C，2026-07-01）：**

| 阶段 | 内容 | 状态 |
|------|------|------|
| **A** | Besu Shanghai executor + USDT JUnit | 代码完成，gate 待跑 |
| **B1** | `RocksDbWorldUpdater` + `EvmAddress` + `EVM_STATE` | 代码完成，Kernel 未接线 |
| **B2** | R1 协议、`applyBlock` hook、P2P、reorg、receipts | spec 已定，代码未做 |
| **C** | `eth_*` RPC → MetaMask / Hardhat E2E | 未开始 |

1. **排序（ADR-003）** — 复用 `applyBlock()` DFS，不新建 GHAST 模块；可选 P2 延迟执行
2. **EVM tx（ADR-002）** — R1：`EVM_TX` store + `XDAG_FIELD_EVM_TX_REF`（0x0F）
3. **账户** — EVM nonce/余额在 `EVM_STATE`，与 native `AddressStore` 分离（v1）
4. **RPC（C）** — `eth_*` 命名空间；保留 `xdag_*`
5. **引擎** — Besu 26.5.0 嵌入；**放弃** feature/evm rebase

### 路径 B: L2 Based Rollup（类 Kaspa）

```
用户 → 提交含 EVM 数据的 XDAG 交易 → DAG 排序
→ L2 索引器扫描 L1 → 提取 EVM 数据 → 执行 → L2 状态
```

| 维度 | 评估 |
|------|------|
| **优点** | 对 L1 协议改动最小、架构简单 |
| **缺点** | 增加信任假设（索引器）、跨层交互延迟、需额外基础设施 |
| **开发量** | 中 — L1 改动小，但需开发独立 L2 组件 |
| **参考** | Kaspa Kasplex |

### 路径 C: 异步合约模型（类 Vite）

```
调用者 → 请求交易 (S) → 追加到调用者账户链
合约 → 响应交易 (R) → 追加到合约账户链 → 引用 S
```

| 维度 | 评估 |
|------|------|
| **优点** | 最"原生 DAG"、天然高并发 |
| **缺点** | 与 Solidity 不兼容、跨合约非原子、开发者门槛高 |
| **开发量** | 极高 — 需全新 VM 和编程模型 |
| **参考** | Vite Solidity++ |

### 路径推荐

**推荐路径 A + Besu**，理由：
1. `dev-evm` 已用 Besu 证明 USDT 级合约（现代 Solidity / PUSH0）
2. 最大程度兼容以太坊生态（Solidity/MetaMask/开发者工具）
3. 与 Mars 路线图一致
4. XDAG 主链 + `applyBlock` DFS 提供确定性序，无需独立 GHAST 实现（ADR-003）
5. Conflux 已证明 PoW DAG + EVM 可行；R1 解决 512B 块墙

---

## 10. 结论与建议

### 10.1 现状总结（2026-07-01）

**`dev-evm` 分支（Besu 路线）已完成：**
- Besu Shanghai EVM 嵌入（`XdagEvmExecutor` deploy/call）
- 持久化 WorldUpdater（`RocksDbWorldUpdater` + `EvmStateSchema`）
- EVM 地址推导（`EvmAddress`）
- USDT + OZ ERC-20 JUnit 套件（待 `.bin` verification gate）

**所有分支均未完成（节点级）：**
- `BlockchainImpl` EVM 执行 hook
- P2P EVM tx blob 传播
- `eth_*` JSON-RPC
- Kernel 启动接线

**`feature/evm` 自研栈：** 历史资产，Constantinople 级，**不合并** — 见 ADR-001 与 salvage guide（`2026-06-06-xdag-evm-decision-record.md`）。

### 10.2 距生产的关键差距

```
优先级 P0（必须完成）:
├── [A] verification gate（.bin + mvn test）
├── [B2] R1 链上集成（EVM_TX store、P2P、applyBlock hook、reorg）
├── [C] EVM RPC（eth_sendRawTransaction + 核心 eth_*）
├── [B2] Kernel 接线（EVM_STATE + executor）
└── Devnet E2E（部署 USDT → transfer）

已解决 / 取消:
├── ~~DAG→全序独立算法~~ → applyBlock DFS（ADR-003）
└── ~~feature/evm rebase~~ → Besu embed（ADR-001）

优先级 P1（重要）:
├── MetaMask 完整 Web3 兼容（subscriptions）
├── XRC 代币标准
├── 事件日志索引 + Bloom 过滤器
├── Block Gas Limit / activation height 配置
└── Native↔EVM 余额桥接

优先级 P2（增强）:
├── trace/debug RPC
├── 合约验证工具
├── Conflux 式延迟执行（可选）
└── XDAG 特有预编译合约
```

完整 checklist：`docs/superpowers/specs/2026-06-06-xdag-evm-decision-record.md` § P0 gap checklist。

### 10.3 技术建议

1. **完成 A gate → 推进 B2** — 按 `2026-06-06-xdag-evm-subproject-b2-r1-protocol.md` 实现 R1
2. **排序** — 挂接 `setMain→applyBlock` DFS，无需先建 GHAST；重组多时再考虑延迟执行（P2）
3. **Besu 26.5.0** — 已锁定；升级前验证 Java class file 版本
4. **不要 merge feature/evm** — 仅 salvage RPC 方法列表与 tx 字段设计
5. **Devnet 硬分叉** — 先在 devnet 验证 R1 + USDT E2E，再 testnet chainId 治理

---

## 11. 参考资料

### XDAG 官方
- [XDAG Whitepaper](https://github.com/XDagger/xdag/blob/master/WhitePaper.md)
- [XDAG Mars Project](https://medium.com/@XDAG_Community/xdag-mars-project-df5f6a7d068b)
- [XDAGJ GitHub](https://github.com/XDagger/xdagj)

### XDAG EVM 实施文档（dev-evm，2026-06/07）
- `docs/superpowers/specs/2026-06-06-xdag-evm-subproject-a-design.md` — Sub-project A
- `docs/superpowers/specs/2026-06-06-xdag-evm-subproject-b-design.md` — B1/B2 分解
- `docs/superpowers/specs/2026-06-06-xdag-evm-subproject-b2-r1-protocol.md` — B2 R1 协议
- `docs/superpowers/specs/2026-06-06-xdag-evm-decision-record.md` — ADR + P0 checklist
- `docs/superpowers/plans/2026-06-06-xdag-evm-subproject-a.md` — A 实施计划

### Conflux
- [Tree-Graph Documentation](https://doc.confluxnetwork.org/docs/general/conflux-basics/consensus-mechanisms/proof-of-work/tree-graph/)
- [Consensus Design and Implementation](https://doc.confluxnetwork.org/docs/general/build/node-development/consensus-design/)
- [Scaling Nakamoto Consensus (arXiv 1805.03870)](https://arxiv.org/abs/1805.03870)
- [eSpace EVM Compatibility](https://doc.confluxnetwork.org/docs/espace/build/evm-compatibility)
- [Spaces Documentation](https://doc.confluxnetwork.org/docs/general/conflux-basics/spaces)

### Kaspa
- [Kasplex L2 Based Rollup](https://medium.com/@KaspaKEF/kasplex-l2-a-light-weight-based-rollup-solution-on-kaspa-33a5939bdf61)
- [Kaspa Programmability Mosaic](https://kaspa.org/kaspas-programmability-mosaic/)
- [vProgs](https://vprogs.xyz/)
- [PHANTOM GHOSTDAG Paper](https://eprint.iacr.org/2018/104.pdf)

### IOTA
- [IOTA EVM Documentation](https://docs.iota.org/iota-evm)
- [ISC State Documentation](https://docs.iota.org/developer/iota-evm/explanations/states)
- [IOTA Rebased Technical View](https://blog.iota.org/iota-rebased-technical-view/)

### Vite
- [Snapshot Chain](https://medium.com/vitelabs/snapshot-chain-an-improvement-on-block-lattice-5c1897e0cc89)
- [Smart Contracts in DAG](https://medium.com/vitelabs/smart-contracts-in-dag-5059250b916b)
- [Sync/Async Functions](https://docs.vite.org/soliditypp/fundamentals/sync-async-functions/)

### Fantom/Sonic
- [Sonic Consensus](https://docs.soniclabs.com/technology/consensus)
- [Lachesis Paper (arXiv 2108.01900)](https://ar5iv.labs.arxiv.org/html/2108.01900)
- [FVM & Storage Roadmap](https://blog.fantom.foundation/fantom-virtual-machine-storage-the-roadmap/)
- [SonicDB S6 Verkle Trie](https://arxiv.org/html/2604.06579)

### 学术研究
- [Thunderbolt: Concurrent Smart Contract Execution (arXiv 2407.09409)](https://arxiv.org/abs/2407.09409)
- [Batch-Schedule-Execute (arXiv 2402.05535)](https://arxiv.org/abs/2402.05535)
- [Block-STM (Aptos)](https://medium.com/aptoslabs/block-stm-how-we-execute-over-160k-transactions-per-second-on-the-aptos-blockchain-3b003657e4ba)
- [Execution and Parallelism for DAG-Based BFT (Chainlink)](https://blog.chain.link/execution-and-parallelism-for-dag-based-bft-consensus/)
- [MEV Resistance on a DAG (Chainlink)](https://blog.chain.link/mev-resistance-on-a-dag/)
- [Cross-Shard Atomic Execution (arXiv 2502.12820)](https://arxiv.org/abs/2502.12820)
- [SoK: DAG-based Blockchain Systems (ACM)](https://dl.acm.org/doi/10.1145/3576899)
