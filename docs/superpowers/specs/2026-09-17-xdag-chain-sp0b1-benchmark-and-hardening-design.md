# SP0b-1：L1 导入基准与主链完整性加固 设计规格

> 状态：设计稿 v1（2026-09-17，用户已逐节确认设计 §1–§5）。
> 上级文档：总体设计与路线图 `2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md` §5.1；总规格 `2026-09-13-xdag-chain-contracts-design.md` §15.2/§20.2 E13；SP0a 竣工规格 `2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md` §12.2（G1/G2/G7/G8/G9/G10）。
> 术语：2026-09-17 起 lane/通道 统一改称 chain/链（应用链）；主链、分片链的用法不变。
> 与总体设计 §5.1 的一处偏差：G2 的"完成标记"放在 `BlockStore`（INDEX 列，节点本地）而不是 `CHAIN_L1` 的 `0xFE` 键——原因见 §3.2；总体设计随本文同步。

---

## 0. 一句话

在不改任何共识判定的前提下，测出 L1 导入的基线吞吐并分阶段归因；把"`setMain` 中途失败留下永远无法回滚的主块"（G1）从根上消除、给"上一次 `setMain` 是否完整结束"一个可核对的标记（G2）、启动时检测即停机并提供离线修复命令；补上快照制作的端到端测试（G9）与带种子的随机 reorg 属性测试（G10）；顺手关闭 G7/G8。

## 1. 范围

### 1.1 包含

1. `ChainL1ImportBenchmarkTest`：JUnit 黑盒基准 + 测试侧分阶段重演；基线文档。
2. `setMain` 的 G1 根治（`updateBlockRef(self)` 提前）。
3. `BlockStore` 节点本地标记 `LAST_COMPLETED_MAIN` 与 `ChainConsistencyCheck`（启动时检测即停机）。
4. `XdagCli --repairchain [--dry-run] [--force]` 离线修复命令。
5. `MakeSnapshotEndToEndTest`（G9）；`XdagCli.copyFile` 抛异常（G7）；`RocksdbKVSource.init()` 失败路径释放 `ReadOptions`（G8）。
6. `ChainL1ReorgPropertyTest`（G10，带种子）。
7. 规格与本地文档同步、记忆更新。

### 1.2 不包含

- 锁外预验证流水线、批量落盘（SP0b-2）；孤块池分队列、配额、TTL、导入期费率策略（SP0b-3）。
- 任何影响 `CHAIN_L1` 状态哈希或全网判定的改动。
- 对 `tryToConnect` 的插桩或改动（基准是黑盒）。
- G3/G4/G5/G6（不阻塞；SP0b-2 触碰构造器时顺手处理 G3/G5）。

## 2. 原则

- **P1/P2/P5/P6 沿用**（SP0a 规格 §2）。本 SP 新增的写操作（`LAST_COMPLETED_MAIN`）是节点本地的，不进 `CHAIN_L1`，不进任何哈希。
- **N1 确定性**不受影响：基准与检查只读；修复命令只调用共识路径已有的逆操作（`unWindMain`）。
- **检测即停机**：一致性检查命中时节点拒绝启动，不自动改状态；修复只由运维显式触发。

---

## 3. 设计

### 3.1 `ChainL1ImportBenchmarkTest`（`src/test/java/io/xdag/chain/bench/`）

- 继承 `ChainL1TestBase`；`@Before` 里 `Assume.assumeTrue("set -Dxdag.bench=true to run", Boolean.getBoolean("xdag.bench"))`；默认跳过，不进 CI 默认路径；无新依赖。
- `BenchWorkload`（同包测试类）：
  - 参数：`senders`（默认 64）、`blocks`（默认 20,000）、`seed`（默认 20260917）、`mix`（默认 `plain=60,callInline=25,callChain=10,deploy=5`）；均可用 `-Dxdag.bench.<name>` 覆盖。
  - 发送方 `ECKeyPair.generate()`，`addressStore.updateBalance(addr, 10_000 XDAG)` 注资；每个发送方维护自己的 nonce；块时间 = `txTime()` 所在纪元内递增的 tick（同一纪元）。
  - 四类块：普通转账（`BlockBuilder.generateNewTransactionBlock`）、内联参数 CALL（`ChainBlockBuilder.call`，20 B 参数）、3 片参数链 CALL（1000 B 参数）、长代码链 DEPLOY（`payload(100_000)` ≈ 285 片；全部 DEPLOY 复用同一份 wasm，第二次起按 codeHash 引用以避免每次 285 片）。CALL 的目标链由基准开始时先确认的一条链提供（`setUp` 里 `deployNewChain` + `confirm`，不计时）。
  - 先全部构建到内存（含分片块），再计时。
- 三个测量（各三轮，每轮新基座；报 p50/p95 单块耗时、块/s、总秒数）：
  1. `direct`：对每块 `blockchain.tryToConnect(b)`，分片块先于付费块；断言全部 `IMPORTED_NOT_BEST`。
  2. `syncPath`：对每块 `syncMgr.validateAndAddNewBlock(new BlockWrapper(b, ttl, null))`（含其进锁前的重解析；`SyncManager` 用 `kernel` 现有实例，`distributeBlock` 因无 channel 为空操作）。
  3. `confirmed`：`direct` 的基础上每 2000 个块 `mineMain(refs)` 一次并在结尾 `confirm`，得到含 `setMain/applyBlock/钩子` 的整体块/s（`refs` 按 nonce 顺序）。
- 阶段重演（同一批块，测试侧单独计时，每阶段 ≥ 3 轮取中位数）：`parse`（`new Block(new XdagBlock(bytes))`）、`signature`（`blockchain.canUseInput(block)`，块内公钥路径）、`refLookup`（对每个 link `blockStore.getBlockInfoByHash`）、`persist`（`blockStore.saveBlock` 到一个独立临时 BlockStore）。输出各阶段中位耗时与"占 `direct` 单块耗时的百分比"。
- 输出：控制台 Markdown 表；`target/bench/l1-import-<yyyyMMdd-HHmm>.json`（含 JVM、CPU 型号、核数、参数、三轮原始数据）。基线文档 `docs/benchmarks/2026-09-xx-l1-import-baseline.md`（`git add -f`）由实施者填入首次结果与机器信息。
- 不改 `BlockchainImpl`。

### 3.2 G1 根治与 G2 标记（`BlockchainImpl` / `BlockStore`）

**G1**：`setMain` 中在 `onSetMainBegin` 之后、`applyBlock(true, block)` 之前加 `updateBlockRef(block, new Address(block))`；结尾原有的同名调用保留（幂等）。效果：DFS 中途抛异常留下的主块 `ref == self`，`unApplyBlock` 不再因 `ref == null` 跳过它，`unSetMain(M)` 能正常撤销其奖励与已应用子块。`ref` 只存在于本地 `BlockInfo`，不进任何哈希。`mainBlockFee < 0` 的早返回路径经分析不可达（主块在成为主块前不可能已被其它主块 `BI_MAIN_REF`），在代码注释中记录并保持原样。

**G2**：`BlockStore` 新增

```java
long getLastCompletedMain();            // 缺失返回 -1
void saveLastCompletedMain(long h);
```

INDEX 列，键 `LAST_COMPLETED_MAIN = {0x7E}`（与现有 `SNAPSHOT_BOOT`、`OURS` 等前缀不冲突，实施时核对 `BlockStoreImpl` 的键表）。`setMain` **正常走到末尾**（`updateBlockRef` 之后、`randomXSetForkTime` 之后）写 `mainNumber`；异常路径不写（写操作不在 `finally`）。`unSetMain` 末尾写 `height − 1`。快照启动分支在 `ChainL1SnapshotGate` 之后写 `snapshotHeight`。

### 3.3 `ChainConsistencyCheck`（`io.xdag.chain.repair`）

```java
record Report(long nmain, long marker, List<Stuck> stuck, boolean markerInitialized) { boolean clean(); String describe(); }
record Stuck(long height, Bytes32 hash, String reason) {}

static Report run(BlockStore blockStore, XdagStats stats, ChainSpec spec, int window);
```

- 调用点：`BlockchainImpl` 构造器，在快照分支与 `xdagStats/xdagTopStatus` 加载之后、装配 `ChainL1Processor` 之前；`kernel.isRepairMode()`（`Kernel` 新字段，默认 false）为真时只把 `Report` 存到 `kernel.setConsistencyReport(...)`，否则 `!clean()` → 抛 `IllegalStateException`，消息 = `report.describe()` + 修复命令提示。
- 规则：
  1. `marker == -1`（首次升级）→ `log.warn`，写 `marker = nmain`，`markerInitialized = true`，视为 clean（历史无法核对）。
  2. `marker < nmain` → `stuck += (h = marker+1 .. nmain, reason = "setMain incomplete")`（只列出高度，hash 取 `getBlockByHeight`）。
  3. 扫描 `h ∈ [max(activation, nmain − window), nmain + 8]`：`getBlockByHeight(h)` 非空且 `BI_MAIN` 已置且 `ref == null` → `stuck += (h, hash, "main block without ref")`。
  4. `marker > nmain + 8` → 视为统计滞后，`log.warn`，不算故障。
- `window` 来自 `chain.consistency.window`（节点本地，默认 128，`AbstractConfig` 读取，不写进仓库 conf）。

### 3.4 `XdagCli --repairchain [--dry-run] [--force]`（`ChainRepairTool`）

- 组装：与 `makeSnapshot` 同样直接打开各存储；`new Kernel(config, wallet)` 只读钱包；`kernel.setRepairMode(true)`；`new BlockchainImpl(kernel)`（构造器不抛，报告落在 kernel）；不调用 `startCheckMain`（构造器里的调用改为受 `repairMode` 抑制）。
- 输出诊断：`report.describe()`；`target = min(marker, 最早 stuck 高度 − 1)`；`--dry-run` 打印计划后退出 0。
- 修复：① 对每个 `stuck` 的主块 `updateBlockRef(M, self)`；② `target < nmain − window` 且无 `--force` → 退出 2 并提示；③ `blockchain.unWindMain(blockchain.getBlockByHeight(target))`；④ `saveLastCompletedMain(target)`；⑤ 重跑 `ChainConsistencyCheck`，clean → 退出 0，否则退出 1 并打印报告。
- 之后节点正常启动，`checkNewMain` 从 `target + 1` 重新确认（块都在本地）。

### 3.5 G7 / G8 / G9

- G7：`XdagCli.copyFile` 捕获 `IOException` 后改为抛 `IllegalStateException("snapshot copy failed: <src> -> <dst>")`；`copyDir` 同样传播。
- G8：`RocksdbKVSource.init()` 打开失败时释放已创建的 `ReadOptions`/`Options`（try/finally），保持 `alive == false`。
- G9 `MakeSnapshotEndToEndTest`：基座生成含 DEPLOY/CALL 的链并确认，关闭基座（保留目录）；`spy(new XdagCli())` + `setConfig(config)` 指向该存储；`makeSnapshot(true)`；断言 `SNAPSHOT/BLOCKS`、`SNAPSHOT/ADDRESS`、`SNAPSHOT/CHAIN_L1` 存在，`CHAIN_L1` 快照的 `0xFF` 哈希 == 源库 `stateHash()`，输出含高度与下一起始帧；再把三目录 `ship` 到第二个空存储，`ChainL1SnapshotGate.checkAndImport` 通过且 `stateHash` 相等。

### 3.6 `ChainL1ReorgPropertyTest`（G10）

- 种子：`DEFAULT_SEEDS = {1..8}`，`-Dxdag.reorg.seeds=a,b,c` 追加；`-Dxdag.reorg.full=true` 跑全部，默认跑前 2 个。
- 生成器（`ReorgScenario.generate(seed)`）：高度数 `3..8`；每高度操作数 `0..6`，操作类型与权重：`DEPLOY_NEW 2, DEPLOY_JOIN 2, CALL_HIT 4, CALL_MISS 2, PLAIN 3, CALL_LOWFEE 1, DEPLOY_BADGAS 1`；发送方从 4 个注资密钥中随机；分叉点在前半段随机；竞争分支长度 = 2 × 分叉后主块数 + 2。生成结果是**纯数据**（操作列表），两次执行同一场景得到相同块字节（基座签名确定性 + 固定时间）。
- 执行：
  1. 基座 A：按场景 apply（含确认）→ 记录 `snapshotA_pre`（`sortedKeys`+values）与相关地址余额/nonce → 分叉回滚（`rewindTo` + `mineMain(…, false)`）→ 断言 `CHAIN_L1` 仅剩 `META`（`0xFE/0xFF` 除外）、余额/nonce 回到分叉点前的记录值 → 在新分支上重新链接全部块并确认 → 记录 `snapshotA_post`。
  2. 基座 B：同一场景直接 apply（无分叉）→ `snapshotB`。
  3. 断言 `snapshotA_post == snapshotB`（键集与每个 value 逐字节；`0xFE/0xFF` 除外）；相关地址余额/nonce 相等。
- 失败输出：种子、操作序列、首个不等的键（hex）与两侧 value。

### 3.7 文档与同步

- `docs/benchmarks/<date>-l1-import-baseline.md`；SP0a 规格 §12.2 G1/G2/G7/G8/G9/G10 标记"已关闭（SP0b-1）"并指向实现；总体设计 §5.1 同步"标记在 `BlockStore`"；`docs/XDAGJ_SNAPSHOT_zh.md` 增加"启动一致性检查与 `--repairchain`"一节；`.claude/docs/chain-l1-foundation.md` 增补 §9 一致性检查/修复；记忆更新。

---

## 4. 测试策略

| 测试 | 内容 |
|------|------|
| `ChainL1ImportBenchmarkTest` | 三测量 + 阶段重演；`-Dxdag.bench=true` 才跑 |
| `MainCompletionMarkerTest`（基座） | 正常出块后 `marker == nmain`；`unSetMain` 后 `marker == nmain`；handler 抛异常的 `setMain` 后 `marker == nmain − 1` 且主块 `ref == self` |
| `ChainConsistencyCheckTest` | 缺标记初始化并 warn；`marker < nmain` 命中；`ref == null` 命中；窗口边界；`marker > nmain + 8` 只 warn |
| `ChainRepairToolTest`（基座） | 场景 (a) handler 在第 k 块抛异常；(b) 人为把已确认主块 `ref` 置 null。断言：检查命中；`--dry-run` 不改库；修复后检查 clean；继续出块确认后 `CHAIN_L1` 与干净重放逐字节相等，余额/nonce 相等；`--force` 边界 |
| `MakeSnapshotEndToEndTest` | §3.5 |
| `CopyDirFailureTest` / `RocksdbInitFailureTest` | G7/G8 |
| `ChainL1ReorgPropertyTest` | §3.6 |
| 回归 | 全量 `mvn test` 绿；`license:check` |

## 5. 文件清单

**改动**：`core/BlockchainImpl.java`（`setMain` 提前 `updateBlockRef`、末尾写标记、`unSetMain` 写标记、构造器调用一致性检查、`repairMode` 抑制 `startCheckMain`）、`db/BlockStore.java` + `db/rocksdb/BlockStoreImpl.java`（标记读写）、`db/rocksdb/RocksdbKVSource.java`（G8）、`Kernel.java`（`repairMode`、`consistencyReport`）、`cli/XdagCli.java` + `cli/XdagOption.java`（`--repairchain`、G7）、`config/AbstractConfig.java` + `config/spec/ChainSpec.java`（`chain.consistency.window`，节点本地）、`chain/l1/ChainL1SnapshotGate.java`（导入后写标记）。
**新增**：`chain/repair/{ChainConsistencyCheck, ChainRepairTool}.java`；测试 `chain/bench/{ChainL1ImportBenchmarkTest, BenchWorkload}.java`、`chain/repair/{ChainConsistencyCheckTest, ChainRepairToolTest, MainCompletionMarkerTest}.java`、`chain/l1/{ChainL1ReorgPropertyTest, ReorgScenario}.java`、`cli/MakeSnapshotEndToEndTest.java`、`cli/CopyDirFailureTest.java`、`db/rocksdb/RocksdbInitFailureTest.java`。

## 6. 开放问题

| # | 问题 | 处理 |
|---|------|------|
| Q1 | `xdagStats.nmain` 的持久化时机（若晚于主块落盘，检查规则 3 的上界 `+8` 是否足够） | 计划首个任务核对 `XdagStats` 持久化路径；不够则改为"扫描到 `getBlockByHeight` 返回空为止" |
| Q2 | `SyncManager` 在基座里能否无网络构造（`syncPath` 测量） | 计划首个任务核对；不能则该测量降级为"重解析 + `tryToConnect`"的等价模拟并注明 |
| Q3 | 修复命令是否需要处理 `ADDRESS` 与 `BLOCK` 之间既有的回滚不对称（Q4 of SP0a） | 不处理；修复只保证 `CHAIN_L1` 与主链一致，`allBalance` 的既有 try/catch 不在本 SP 范围 |
