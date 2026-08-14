# 验证门：dev-evm 在 JDK 21 上全绿 + devnet 冒烟（设计 spec）

日期：2026-08-14
分支：dev-evm（HEAD `73c6f9be`）
状态：已批准（brainstorming 定案）

## 0. 路线图前言（已批准的方案 A：串行子项目流水线）

按设计文档 `.claude/docs/smart-contract-design-and-implementation.md` §13.1 的五大主网阻塞缺陷，
整体推进顺序定案如下，每个子项目独立 spec → plan → TDD 实施 → 评审：

| 序 | 子项目 | 对应缺陷 | 状态 |
|----|--------|----------|------|
| 0 | **验证门（本 spec）**：JDK 21 全绿 + 打包 + devnet 冒烟 | — | 本轮 |
| 1 | 批次打包：0x0F 单 tx hash → 批次承诺，矿工装批至 blockGasLimit | 缺陷 2（吞吐 ≈1 tx/64s） | 待启动 |
| 2 | EIP-2718 typed envelope + type-2 解析（maxFeePerGas 视作 gasPrice，baseFeePerGas 返 0） | 缺陷 1（仅 legacy type-0） | 待启动 |
| 3 | XDAG↔EVM 共识层双向锚定 bridge（入金 mint / 出金 burn + 延迟释放；共识参数需先定案） | 缺陷 3（无资金入口） | 待启动 |
| 4 | chainId 注册 + activationHeight 定案（纯配置/流程） | 缺陷 4 | 待启动 |
| 5 | 合入 develop、CI 门禁、第三方审计闭环（纯流程） | 缺陷 5 | 待启动 |

背景：上一次 JDK 21 全量终验（340 tests, 0 failures）停在 `a3cacd36`；其后交付的
C4（历史状态）、C5（getLogs bloom + 全 topic）、C6（WebSocket 订阅）与 E2E 共 25 个 commit
均通过逐 commit 评审，但从未在 JDK 21 上构建。本机仅有 JDK 17/11/8。
在未验证的栈上叠加新特性风险不可控，故验证门先行。

## 1. 目标与成功标准

在 JDK 21 上证明 dev-evm 当前 HEAD 健康。成功标准三条，全部满足才算通过：

1. `mvn clean package` 全量通过——含全部 EVM 单测、集成测试、in-process 真 socket E2E；
   `RandomXSyncTest` / `SyncTest` / `SnapshotJTest` 维持 surefire 默认排除，不额外跳过任何测试；
2. `target/xdagj-*-executable.jar` 以 devnet 模式（`-d`，带 `--add-opens` 双参数）启动成功；
3. RPC 探活通过：HTTP 10001 上 `eth_chainId` 返回 `0xcafe`、`eth_blockNumber` 与
   `web3_clientVersion` 正常应答；若配置启用 WS 端口则一并探活（`eth_subscribe` newHeads
   返回订阅 id 即通过）。

## 2. 环境准备

- 下载 Temurin 21（macOS aarch64 tar.gz，Adoptium API）解压到
  `~/Library/Java/JavaVirtualMachines/`——与现有 ms-17 安装模式一致；不用 brew、不用 sudo，
  整目录删除即可回滚；
- 构建仅以会话级 `JAVA_HOME` 指向 JDK 21，不改系统默认 Java；
- Maven：本机 `/usr/local` 实为 3.6.3（计划阶段核实，不满足 3.9.x 要求），
  故另装 Apache Maven 3.9.9 到 `~/tools/`（同样用户目录、可整目录删除回滚）；
- 按仓库既定机制注册 `~/.m2/toolchains.xml`（源自 `misc/toolchains.xml`，
  jdkHome 指向新装 Temurin 21；pom 的 maven-toolchains-plugin 强制要求该注册）。

## 3. 失败处置策略

沿用 `a3cacd36`（上次 JDK 21 首建修复 3 个测试工件）的先例，失败分两类：

- **测试工件问题**（断言依赖时序、端口冲突、JDK 行为差异等）：直接修复，
  每个修复独立 `test(evm): ...` commit（英文 commit message）；
- **产品代码缺陷**：停下，先报告缺陷性质与影响面，经确认后再修——不擅自改共识路径代码
  （`BlockchainImpl` 三挂载点、`EvmBlockProcessor`、回滚/根摘要等）。

节点冒烟前先查看本地既有 devnet 数据与配置（参考 `634da36e` 的本地 setup），不破坏现有数据；
探活完成后停止节点。

## 4. 交付物

- 全绿测试记录（tests run / failures 数字）+ 冒烟探活结果；
- 修复 commit 清单（如有）；
- 更新持久记忆：清除「唯一遗留动作 = JDK21 验证」，记录验证结论；
- 本 spec 落盘并提交。

## 5. 范围外（YAGNI）

不跑 Hardhat 外部 E2E（`tools/hardhat-e2e/VALIDATE.md` 属后续手动验证）、不做 MetaMask
手动步骤、不动任何产品代码（除非 §3 失败处置需要且经确认）、不合并分支、不改 CI。
