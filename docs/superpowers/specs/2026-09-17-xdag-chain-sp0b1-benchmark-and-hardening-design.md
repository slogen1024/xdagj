# SP0b-1：L1 导入基准与主链完整性加固 设计规格

> 状态：设计稿 v1（2026-09-17，用户已逐节确认设计 §1–§5）→ **竣工同步 v2（2026-09-18）**：§1、§3、§4、§5、§6 已按 `dev-dag-contract` 上的实现（提交 5a82825a…d12181f0 及基准代理随后对基准 harness 的修订，清单见实施计划头部的"执行记录"）改写；与 v1 的偏差在各节开头以"**偏差**"标出，本文与代码不一致时以代码为准。基准数字只在 `docs/benchmarks/2026-09-18-l1-import-baseline.md`，本文不引用。
> 上级文档：总体设计与路线图 `2026-09-17-xdag-chain-contracts-program-design-and-roadmap.md` §5.1；总规格 `2026-09-13-xdag-chain-contracts-design.md` §15.2/§20.2 E13；SP0a 竣工规格 `2026-09-13-xdag-chain-sp0a-block-format-and-l1-hooks-design.md` §12.2（G1/G2/G7/G8/G9/G10，以及本 SP 新发现并关闭的 G11）。
> 术语：2026-09-17 起 lane/通道 统一改称 chain/链（应用链）；主链、分片链的用法不变。
> 与总体设计 §5.1 的一处偏差：G2 的"完成标记"放在 `BlockStore`（INDEX 列，节点本地）而不是 `CHAIN_L1` 的 `0xFE` 键——原因见 §3.2；总体设计随本文同步。

---

## 0. 一句话

在不改任何共识判定的前提下，测出 L1 导入的基线吞吐并分阶段归因；把"`setMain` 中途失败留下永远无法回滚的主块"（G1）从根上消除、给"上一次 `setMain`/`unSetMain` 是否完整结束"两个可核对的标记（G2）、启动时检测即停机并提供离线修复命令；补上快照制作的端到端测试（G9）与带种子的随机 reorg 属性测试（G10）；顺手关闭 G5/G7/G8，并修掉 G10 找出的 unwind 费用不对称（G11）。

## 1. 范围

### 1.1 包含（as-built）

1. `ChainL1ImportBenchmarkTest` + `BenchWorkload`：JUnit 黑盒基准 + 测试侧分阶段重演（§3.1）；基线文档 `docs/benchmarks/2026-09-18-l1-import-baseline.md`。
2. `setMain` 的 G1 根治（`updateBlockRef(self)` 提前到 DFS 之前；子块 ref 在递归之前写、被拒绝时还原）。
3. `BlockStore` 节点本地标记 `LAST_COMPLETED_MAIN`（`0xb0`）与 `MAIN_IN_FLIGHT`（`0xc0`）；`ChainConsistencyCheck`（启动时检测即停机，`Kernel.enterRepairMode()` 下只记录）。
4. `XdagCli --repairchain [dry-run|force|reinit-marker]` 离线修复命令（`ChainRepairTool`；退出码 0/1/2/3/4，§3.4）。
5. `MakeSnapshotEndToEndTest`（G9）与 `--makesnapshot` 不可启动即退出 1；`XdagCli.copyFile` 抛异常（G7）；`RocksdbKVSource.init()` 失败路径释放 `ReadOptions`（G8）。
6. `ChainL1ReorgPropertyTest` + `ReorgScenario`（G10，带种子），以及它找出并修掉的 `unApplyBlock` 费用除数错误（`ChainL1UnwindFeeTest`；SP0a 规格 §12.2 G11）。
7. `BlockStoreImpl.forNode(DatabaseFactory)`：节点开库布局的唯一入口（`Kernel`、`--repairchain`、基准与测试基座共用；`BlockStoreWiringTest`），关闭了 SP0a 规格 G4 里"离线工具按签名顺序开库"的风险。
8. 规格与本地文档同步、记忆更新。

### 1.2 不包含

- 锁外预验证流水线、批量落盘（SP0b-2）；孤块池分队列、配额、TTL、导入期费率策略（SP0b-3）。
- 任何影响 `CHAIN_L1` 状态哈希或全网判定的改动（G11 的费用修复改的是 `AddressStore` 里的余额，不进任何哈希，见 §3.6）。
- 对 `tryToConnect` 的插桩或改动（基准是黑盒）。
- G3/G6（不阻塞）；G4 的磁盘布局本身未改（只收拢了开库入口）；G5 已顺手关闭（`stopCheckMain()` 现经 `stopCleaner()` 停掉 cleaner，`--repairchain` 依赖它退出）。
- `unApplyBlock` 里 `allBalance` 回滚的 try/catch（SP0a Q4）。

## 2. 原则

- **P1/P2/P5/P6 沿用**（SP0a 规格 §2）。本 SP 新增的写操作（`LAST_COMPLETED_MAIN`、`MAIN_IN_FLIGHT`、主块/子块的 `ref`）都是节点本地的，不进 `CHAIN_L1`，不进任何哈希。
- **N1 确定性**不受影响：基准与检查只读；修复命令只调用共识路径已有的逆操作（`unWindMain` / `unSetMain`），外加两种只碰节点本地状态的动作（`reconcileTipTo` 改统计与 top、`updateBlockRef` 改本地 `BlockInfo`）。
- **检测即停机**：一致性检查命中时节点拒绝启动，不自动改状态；修复只由运维显式触发。

---

## 3. 设计

### 3.1 `ChainL1ImportBenchmarkTest`（`src/test/java/io/xdag/chain/bench/`）

> **偏差**：`confirmed` 每 `LINKS_PER_MAIN = 10` 个付费块出一个主块（不是 2000），并新增 `calib.emptyMain` 校准行；`direct` 与 `syncPath` 同轮**交错**并报**配对差值**；报表多一列 `mean`，阶段占比按**均值 / 均值**算而不是"中位数占比"；`syncPath` 用 `new BlockWrapper(b, 0)`；`confirmed.*` 与 `calib.emptyMain` 行的 mean/p50/p95 是**每个主块**的耗时，不是每个付费块。本节描述基线重跑所用的版本（d12181f0 是首版 harness，基准代理随后修订）。数字一律见基线文档。

- 继承 `ChainL1TestBase`；`@Before` 里 `assumeTrue("set -Dxdag.bench=true …", Boolean.getBoolean("xdag.bench"))`，默认 Skipped，不进 CI 默认路径；无新依赖。参数 `-Dxdag.bench.senders`（默认 64）、`.blocks`（20,000；上限 `MAX_BLOCKS = 60,000`——条目 n 的时间戳是 `txTime() + n`，必须留在下一个主块关闭的同一纪元内）、`.seed`（20260917）、`.mix`（`"60,25,10,5"` = `PLAIN` / `CALL_INLINE` / `CALL_CHAIN` / `DEPLOY` 的百分比）。
- **生产形状**：`beforeBlockchain` 里 `kernel.setTxHistoryStore(null)`——没有开 `node.transaction.history.enable` 的节点就是这样，`onNewTxHistory` 直接返回；基座默认的 Mockito mock 会让 `saveTxHistory` 返回 false，把每个金额 link 送进 MySQL 失败回退（WARN + INFO + 一次 RocksDB put），那不是节点的路径。报表头与 JSON 都标 `txHistoryStore=null`。
- `BenchWorkload`（同包）：一趟带种子的 `Random` 生成 `(chunks…, paying block)` 条目列表（`Item(kind, chunks, block)`）；每个发送方的 nonce 按条目顺序发放，按条目顺序导入（或从主块 link）即满足 `applyBlock` 的严格 per-sender nonce 递增。CALL/DEPLOY 的目标链由 `prepareChain()` 在计时前部署并确认的一条链提供，DEPLOY 复用同一份代码（`sharedCodeHash`）。先全部构建到内存再计时。
- 三个测量路径，各 `ROUNDS = 3` 轮、每轮 `freshFixture()` 新基座 + `prepareChain()`：
  1. `direct`：对网络形态的块（已解析、带原始字节）逐个 `tryToConnect`（分片先于付费块），断言导入成功。
  2. `syncPath`：`SyncManager.validateAndAddNewBlock(new BlockWrapper(b, 0))`——网络入口：这种块上 `parse()` 是空操作，`importBlock` 复制 512 字节并重新解析成新 `Block` 再 `tryToConnect`；`SyncManager` 用 `kernel` 现有实例加 mock 的 `ChannelManager`/`PeerClient` 构造（v1 的 Q2 已核实：可以）。
  - **两者交错**：第 r 轮把两条腿背靠背各跑一次（各自新基座），偶数轮 `direct` 先、奇数轮 `syncPath` 先，并额外报一张**配对差值表**（每轮 `syncPath − direct` 的 mean / p50 / total 及三轮的中位数），运行期间的单调漂移（JIT、日志增长、页缓存）不会算到某一条腿头上。
  3. `confirmed`：导入的同时每 `LINKS_PER_MAIN` 个付费块 `mineMain(refs)` 一次，结尾最多 `CONFIRM_MAINS = 6` 个空主块等最后一个付费块被应用；包含伪 PoW、`setMain` 与 `applyBlock`；blocks/s 只数付费块与分片，不数主块；mean/p50/p95 按主块计。
- `calib.emptyMain`：`CALIB_MAINS = 100` 个空主块的基座成本（nonce 搜索 + `tryToConnect` + 无事可 apply 的 `checkMain`），用于把 `confirmed` 拆成"伪 PoW"与"setMain/apply"。
- 阶段重演（最后一份工作负载；先不计时导入一遍，让 `refLookup` 命中真实存在的引用——未命中会被 bloom filter 直接答掉、读起来偏便宜）：`parse`（`new Block(new XdagBlock(bytes))`）、`signature`（`blockchain.canUseInput`）、`refLookup`（`n` = 查找次数）、`persist`（`saveBlock` 到独立目录下 `BlockStoreImpl.forNode` 开的临时库——RocksDB 每目录单写者，基座目录不能重开；另报 `persist.first` = 第一遍冷写）。每阶段 best-of-3 的墙钟 / n——本身就是均值，所以**占比 = 阶段均值 / direct 均值**，绝不能除以 direct 的 p50（分布右偏会高估）。只作成本归因，不是共识路径。
- **统计口径**：`mean` 是 n 个样本的算术平均，`p50`/`p95` 是分位数；`n` 是样本数（付费块、主块或阶段单位）；对该行没有意义的列打 `-`。
- 输出：控制台 Markdown 表 `| measure | n | blocks/s | mean µs | p50 µs | p95 µs | total ms |` + 配对差值表；`target/bench/l1-import-<timestamp>.json`。基线（含机器信息与给 SP0b-2 的结论）：`docs/benchmarks/2026-09-18-l1-import-baseline.md`（`git add -f`）。
- 不改 `BlockchainImpl`。

### 3.2 G1 根治与 G2 标记（`BlockchainImpl` / `BlockStore`）

> **偏差**：键字节是 `0xb0`/`0xc0`（v1 写的 `0x7E` 不成立，按 `BlockStore` 键表顺延）；多了一个 `MAIN_IN_FLIGHT` 记录；每次写标记之前先持久化 `XdagStats`；快照重灌分支写的是 `xdagStats.nmain` 而不是配置的 `snapshotHeight`；`mainBlockFee < 0` 的早返回路径**可达**，不是 v1 说的不可达。

**G1**：`setMain` 在 `onSetMainBegin` 之后、`applyBlock(true, block)` 之前 `updateBlockRef(block, new Address(block))`。DFS 中途抛异常留下的主块 `ref == self`，`unApplyBlock` 不再因 `ref == null` 跳过它，`unSetMain(M)` 能正常撤销其奖励与已应用子块。同样的形状向下一层：`applyBlock` 对每个子块在递归**之前**就 `updateBlockRef(ref, new Address(block))`，子块被拒绝（返回 −1）时还原为 null——`unApplyBlock` 的尾循环只对 `ref == null` 的交易块清 `BI_MAIN_REF`，递归分支也不会误入一棵从未 apply 的子树。`ref` 只存在于本地 `BlockInfo`，不进任何哈希。`mainBlockFee < 0` 的早返回（提升为主块的交易块自身 input 过不了 nonce/同步状态检查）保持原样，但它是**正常结束**：统计与标记照常写。**接受的表面缺口（M3）**：子块 ref 写入与其 `BI_MAIN_REF` 标志之间崩溃会留下一个非 null 的陈旧 ref，直到被下一次覆盖；`unApplyBlock` 以 `BI_MAIN_REF` 为准，不受影响。

**G2**：`BlockStore` 新增两个键（INDEX 列，节点本地，不进哈希、不随快照导出）：

| 键 | 值 | 写 | 清 |
|----|----|----|----|
| `LAST_COMPLETED_MAIN = 0xb0` | 大端 long 高度；缺失读作 −1 | `setMain` 的**两个**正常出口（早返回与末尾）写 `mainNumber`；`unSetMain` 末尾写 `max(0, height − 1)`（0 是地板，负数会被读成"从未写过"）；快照重灌分支写 `xdagStats.nmain`；`--repairchain` 回滚后写 `target`、`reinit-marker` 写 `nmain`；首次启动的旧库由构造器初始化为 `nmain` | 从不清 |
| `MAIN_IN_FLIGHT = 0xc0` | op 字节（`IN_FLIGHT_SET_MAIN = 1` / `IN_FLIGHT_UNSET_MAIN = 2`）+ 大端 long 高度；缺失读作 −1，没有 op 字节的旧记录读作 setMain | `setMain` / `unSetMain` 入口的**第一条** INDEX 写 | 两者正常结束时紧跟 `saveLastCompletedMain` 之后清；快照重灌分支清；修复工具的 `recheck` 只清目标之上的记录、`reinit-marker` 只清 setMain 记录 |

- 异常路径不写标记（写操作不在 `finally`）。标记写本身若失败，会作为"一个本已完整的 `setMain` 抛出"浮出：下一次启动看到标记落后 nmain 而回滚该高度——撤销的是已经落地的工作，安全。
- **统计先于标记（I3）**：`setMain` / `unSetMain` 在每次写 `LAST_COMPLETED_MAIN` 之前先 `blockStore.saveXdagStatus(xdagStats)`；`Kernel.testStop()` 在停掉 check-main 循环之后、关库之前再存一次。于是标记永远不会跑到已持久化的 nmain 前面（`setMain` 返回与 `checkMain` 自己那次保存之间的良性崩溃不再被读成"统计之上有主块"），而标记与统计之间的崩溃由 in-flight 记录夹住。
- `unSetMain` 的 `randomXUnsetForkTime` 以标记为门：`setMain` 只在 apply DFS **之后**才 `randomXSetForkTime`，抛过异常的高度从未设过 fork time，反设会破坏 seed 纪元记账；`forkTimeWasSet = lastCompleted < 0 || lastCompleted >= height`（−1 = 旧库，保留旧的无条件行为）。
- `unSetMain` 对非 `BI_MAIN` 的块直接返回（否则会 `nmain--` 并把标记压到真实顶端之下）。
- 标记**证明**什么、**不证明**什么见 `BlockStore#getLastCompletedMain` / `#getMainInFlight` 的 Javadoc：它是 INDEX 内的进度标记，对 ADDRESS / CHAIN_L1 两个独立 RocksDB 什么都不说，不是跨库提交记录；启动仍要靠核对而不是把它当事务边界。

### 3.3 `ChainConsistencyCheck`（`io.xdag.chain.repair`）

> **偏差**：四条规则（v1 是三条：多了 in-flight 与"统计之上有主块"）；规则 2 也受窗口约束；扫描**不**按激活高度钳制、上界是 `nmain + 64`；`Report` 五个分量；`describe()` 与 `describeForBoot()` 分开。

```java
record Report(long nmain, long marker, List<Stuck> stuck, boolean markerInitialized, boolean inFlightUnwind) {
    boolean clean(); String describe(); String describeForBoot(); long repairTarget();
}
record Stuck(long height, Bytes32 hash, String reason) {}
static Report run(BlockStore blockStore, XdagStats stats, ChainSpec spec, int window);
```

- **调用点**：`BlockchainImpl` 构造器——快照重灌分支或"加载既有状态"分支之后、RandomX 初始化之后、装配 `ChainL1Processor` 之前；两条启动路径都已装好 `xdagStats`，都还没有任何东西能确认一个块。`markerInitialized` → 构造器把 `report.marker()` 写回库（只此一次，写的就是检查采用的值）；每次都 `kernel.recordConsistencyReport(report)`；`!clean()` 时，`kernel.isRepairMode()`（`Kernel.enterRepairMode()` 单向进入，没有生成的 setter）只 `log.warn`，否则 `log.error` 并抛 `IllegalStateException(report.describeForBoot())`——`XdagCli.start()` 捕获后打印 `getMessage()` 退出 −1，运维在终端看到的正是带修复提示的那一段。clean 时 `log.info` 报告（每次启动都把 nmain 与标记留在日志里）。
- **规则**（每高度只报一条，先到先得；结果按高度排序）：
  1. **in-flight**：`MAIN_IN_FLIGHT` 存在 → `stuck += (h, "setMain in flight when the node stopped")` 或 `"unSetMain in flight …"`，`inFlightUnwind = (op == 2)`。先查：它点名节点死在哪个高度，而且是半途 `unSetMain` 留下的唯一证据（那种形状单看标记只是 `marker > nmain` 的 warn）。
  2. **标记落后**：`marker < nmain` → `h ∈ [max(marker + 1, nmain − window), nmain]`，reason `setMain incomplete (completion marker M is behind persisted nmain N)`。受窗口约束，远远落后的标记不会枚举整条链。`marker > nmain + 64` 只 `log.warn`（统计滞后）。
  3. **统计之上有主块**：扫描 `h ∈ [max(1, nmain − window), nmain + 64]`（`ABOVE_TIP_SCAN = 64`），`nmain` 之上第一个空高度即停；`BI_MAIN` 已置、`info.height == h` 且 `h > nmain` → `main block above persisted stats (setMain crashed before stats were saved)`。
  4. **无 ref**：同一扫描内 `h ≤ nmain`、`BI_MAIN` 已置且 `ref == null` → `main block without ref (unwindable only after repair)`——SP0b-1 之前的崩溃留下的形状，新代码不再产生。
  - 缺标记（−1）→ `log.warn`、`marker = nmain`、`markerInitialized = true`，其余规则照跑（历史无法核对）。
  - **不按激活高度钳制**：规则 3/4 说的是主链而不是 chain 协议——共享网的激活高度仍是 `Long.MAX_VALUE`，v1 的 `max(activation, …)` 会把恰恰需要扫描的库扫成空集。只有 `window` 限制回溯深度；`spec` 保留在签名里但扫描不读它。
  - **跳过陈旧高度索引**：`saveBlockInfo` 从不删旧键，被回滚后又在更低处重新确认的块仍可从 `key(h)` 读到且带 `BI_MAIN`；`info.height != h` 的条目一律跳过（真正卡住的块与索引高度一致：`setMain` 在可能出事的 DFS 之前就 `setHeight`）。
- `repairTarget() = max(0, min(marker, 最早 stuck 高度 − 1))`：最早的 stuck 高度本身可疑（含 in-flight 的那个）。半途 `unSetMain` 是它唯一表达不了的形状，修复工具单独处理。
- 文本：`describe()` = `main chain consistency: nmain=N, lastCompletedMain=M[ (initialized on this boot)]` + `, clean` 或 `, K stuck main block(s):` + 每块一行 `  height H <hashlow 或 ?>: <reason>`；`describeForBoot()` = `describe()` + `REPAIR_HINT`（`; the node refuses to start. Run \`xdag.sh --repairchain dry-run\` to see the repair plan and \`xdag.sh --repairchain\` to unwind to the last complete height`），clean 时不加提示；修复工具只用 `describe()`（工具正在跑时"refuses to start"读起来是废话）。
- `window` 来自 `chain.consistency.window`（节点本地，默认 128；`AbstractConfig.getSetting()` 读取并校验 > 0；不写进仓库 conf；`ChainSpecTest.consistencyWindowDefaultsTo128` / `consistencyWindowIsNodeLocalAndValidated`）。

### 3.4 `XdagCli --repairchain [dry-run|force|reinit-marker]`（`ChainRepairTool`）

> **偏差**：模式是**位置参数**（不是 `--dry-run`/`--force` 开关），多了 `reinit-marker`；退出码 0/1/2/3/4（Task 6 质量评审后从 0/1/2/3 拆开）；回滚前先 `reconcileTipTo`；回滚后**不**重新打 `BI_MAIN_CHAIN`，节点需要同行的新块才能重新确认；半途 `unSetMain` 单独处理；结果用 `Status` 枚举表达。

- **组装**（`XdagCli.repairChain(mode)`）：模式校验 → 读钱包（**只为构造 `Kernel`，不签任何东西**）→ `new Kernel(config, wallet)`；`kernel.enterRepairMode()` → `RocksdbFactory` 开库：`BlockStoreImpl.forNode(dbFactory)`（**绝不用构造器**：按签名顺序开库会把 BLOCK/TIME 两库互换，`getBlockByHash(…, true)` 找不到原始字节，每次修复都报 `INCOMPLETE_BLOCK_DATA_REASON`）、`AddressStoreImpl`、`OrphanBlockStoreImpl`、`ChainL1Store` → `new BlockchainImpl(kernel)`（构造器里的检查在修复模式下只记录，工具自己再扫一遍）→ `ChainRepairTool.repair(kernel, blockchain, Options(dryRun, force), System.out::println)` 或 `reinitMarker(...)`；`finally` 里 `blockchain.stopCheckMain()`（经 `stopCleaner()` 停掉构造器起的 cleaner 线程）、`chainStore.stop()`、`dbFactory.close()`。每一行运维输出同时进日志。
- **决策表**（`ChainRepairTool.repair`；每次都重新扫描库，不信任调用方递进来的报告——启动报告是工具随后要写的库的快照）：

| 顺序 | 条件 | `Status` / 动作 | 退出码 |
|------|------|-----------------|--------|
| 1 | 报告 clean | `CLEAN`（"nothing to repair"） | 0 |
| 2 | `inFlightUnwind`（in-flight op = unSetMain） | 见下"半途 unwind"：可续 → 续完后 re-check（`REPAIRED` / `UNREPAIRABLE`）；不可续 → `UNREPAIRABLE`（`INTERRUPTED_UNWIND_REASON`），`force` 也不能越过 | 0 / 2 |
| 3 | `target < tip − window` 且非 `force` | `REFUSED`（"target … is more than … height(s) below the tip …; pass --force to unwind that far"）；**在 dry-run 返回之前判**，所以 dry-run 也会告诉你需要 force | 1 |
| 4 | `dry-run` | `PLANNED`：打印计划，什么都不写——唯一例外是这次启动为无标记的旧库初始化了标记，`dryRunNote` 从 kernel 上的**启动**报告读 `markerInitialized` 并如实说明 | 0 |
| 5 | 否则 | `reconcileTipTo`（若统计之上有主块）→ `patchStuckRefs` → `repairUnwindTo(target)` → `saveLastCompletedMain(target)` → `recheck`：clean → `REPAIRED`；否则 `UNREPAIRABLE`（`STILL_INCONSISTENT_REASON`） | 0 / 2 |
| — | 回滚没走到目标（`repairUnwindTo` 抛 `IllegalStateException`：原始字节缺失，`unWindMain` 在第一个缺字节的块静默停下） | `UNREPAIRABLE`（`INCOMPLETE_BLOCK_DATA_REASON`）；标记与 in-flight 记录**故意不动**，留作下一次尝试/运维的证据 | 2 |
| — | 未知模式；钱包缺失/锁定/打不开（无控制台时 `readPassword` 落到 stdin，stdin 关闭抛 `NoSuchElementException`——不再是栈迹加退出 1） | 未启动 | 3 |
| — | 开库之后的任何异常（典型：节点还在跑，RocksDB 单进程 `LOCK`）——打印根因（`ExceptionUtils.getRootCauseMessage`）加 "if the node is running on this store, stop it first: RocksDB allows only one process to open <storeDir> at a time"，完整栈迹进日志 | 运行中失败 | 4 |

- **`tip` 与 `reconcileTipTo`（C1）**：`scanAboveTip` 只读地找出 `nmain` 之上最高的、`info.height == h` 且 `BI_MAIN` 的连续主块（形态 3）。构造器把 top 钉在 `getBlockByHeight(nmain)`，`unWindMain` 只从 top 往下走，这种块对回滚不可见、会被"成功"跳过。`BlockchainImpl.reconcileTipTo(height, hashLow)` 把 `nmain`、难度对、top 哈希/难度改到那个块并持久化（pre-top 不动——构造器也不派生它）。残留：`xdagStats.balance`（节点本地统计，非共识）可能因此对 `nmain + 1 .. tip` 里我们的每个主块少一份奖励，重新确认后自平——工具会把这句原样打给运维。
- **`patchStuckRefs`**：走库里 `[max(target + 1, tip − window), tip + 64]` 的存储高度（不是 `report.stuck()`：标记与窗口底之间的无 ref 主块从未被报告却也要回滚），跳过陈旧索引条目，给 `BI_MAIN` 且 `ref == null` 的块写 `updateBlockRef(self)`；每 1000 个高度打一行进度。窗口之下的无 ref 主块保持原样（M4：强制回滚到很低的目标不能变成几百万次库读；回滚越过它只反标志不反状态，与检查的口径一致）。
- **`repairUnwindTo(height)`**（`BlockchainImpl`）：校验目标高度存有 `info.height == height` 且 `BI_MAIN` 的主块（陈旧索引条目会让回滚一路走空整条链）→ `unWindMain(target)` → **核验 `xdagStats.nmain == height`**（"回滚返回了"不等于"回滚到了"；C3）→ `saveXdagStatus`。标记与 in-flight 记录由调用方负责。**不重新打 `BI_MAIN_CHAIN`、不动 top**：下次启动把 top 重新钉在 `nmain` 那个主块，`checkNewMain` 只走 top 之下的非主块候选，根本进不了循环、看不到上面被回滚的块；若在这里把分支打回 `BI_MAIN_CHAIN`，它会**悬在启动 top 之上**，普通分叉路径对新块做 `unWindMain(findAncestor(block))` 时向下永远遇不到那个祖先，会把整条主链回滚到创世。留着不打，同一个新块的祖先解析到目标本身，回滚为空操作，`updateNewChain` 在正常分叉处理里重新打标。**代价**：节点不能凭本地状态自己往上走——只有同行发来**真正新的**、建立在旧头之上的块才会推进（重发已存块返回 `EXIST`，top 不动）；孤立修复的节点停在 `target`。
- **`recheck`**：只清**目标之上**的 in-flight 记录（它属于刚被回滚的区间；`unSetMain` 自己会清自己的，这只在那个高度根本没有主块时才触发），re-check 不 clean 就原样放回并在输出里点名"the in-flight record is left in place as evidence"；结果 `kernel.recordConsistencyReport`。
- **半途 unwind**（`finishInterruptedUnwind`，in-flight op = 2）：库里该高度的块仍带 `BI_MAIN` 且 `info.height == h` → 崩溃发生在 `unSetMain` 清标志之前、什么都没反向，把它跑完就是被打断的那个操作：取 raw 块（缺 → `UNREPAIRABLE` INCOMPLETE_BLOCK_DATA）、**先把 `info.fee` 从库里恢复**（`Block.parse()` 用头部费用覆盖了它，而 `unSetMain` 与 `unApplyBlock` 的 OUTPUT 反向都读 `block.getFee()`；与 `unWindMain` 的做法一致，提交 5a698579）、`ref == null` 则补自引用（C2：否则 `unApplyBlock` 直接返回，清了 `BI_MAIN`、`nmain--` 却什么都没反向）、`blockchain.unSetMain(raw)`（它自己存统计、下移标记、清 in-flight）→ re-check。`BI_MAIN` 已清 → 反向已经落了未知的一部分，不可幂等续做 → `UNREPAIRABLE`（`INTERRUPTED_UNWIND_REASON`："… restore the block store from a snapshot"）。只处理这一个高度，其余形状留给 re-check 与下一次普通运行。
- **`reinit-marker`**（`ChainRepairTool.reinitMarker`）：**什么都不核验、什么都不回滚**，先打 WARNING，把 `LAST_COMPLETED_MAIN` 改成持久化的 `nmain`、丢弃 in-flight **setMain** 记录，然后 re-check（不 clean → 退出 2，并说明剩下的不是标记能表达的、要 `--repairchain` 或快照重灌）。只为一种情形存在——**降级陷阱**：回退到 SP0b-1 之前的二进制后节点继续确认主块而标记冻结，再升级时门会要求回滚标记之上的几百个其实完好的高度。**拒绝**（退出 2、不写任何东西、回传入口报告）in-flight **unSetMain**：那条记录是半途 unwind 的唯一证据，普通 `--repairchain` 靠它续做或拒绝；清掉它会让 re-check 变 clean、CLI 对一个不该启动的库退出 0（Task 6 评审 C1）。
- 修复模式（`Kernel.enterRepairMode()`）之外调用 `repair`/`reinitMarker` 抛 `IllegalStateException`（编程错误，不是运维错误）。

### 3.5 G7 / G8 / G9

- **G7**：`XdagCli.copyFile` 捕获 `IOException` 后抛 `IllegalStateException("snapshot copy failed: <src> -> <dst>", e)`；`copyDir` 传播。`makeSnapshot` 把 `SNAPSHOT/ADDRESS` 的拷贝失败（任何 `RuntimeException`）报成 `address snapshot NOT written: …`，继续导出 `CHAIN_L1`、照常打印高度与 next start frame，末行给出处置，并返回 `false`。
- **G8**：`RocksdbKVSource.init()` 的 `ReadOptions` 在 `try` 内创建，`finally` 里 `!alive` 即释放；`alive` 保持 false，对象可再次 `init()`。**残留**：`init()` 里的 `LRUCache(32 MiB)` 与 `BloomFilter(10, false)` 从未释放（成功/失败路径皆然），不在本 SP。
- **G9**：`MakeSnapshotEndToEndTest`（`io.xdag.cli`）在基座上生成一条已确认的链，`spy(new XdagCli())` + `setConfig` 指向该存储，跑 `makeSnapshot(true)`：
  - 返回 `true`；输出含 `snapshot height: <h>`、`next start frame: …`、`chain state snapshot written to …`；三目录存在。
  - **内容按启动路径实际读取的方式核验**（不只看目录存在）：`SNAPSHOT/BLOCKS` 用启动同款 `SnapshotStoreImpl.saveSnapshotToIndex` 灌进第二个节点的块库后，`getBlockByHeight(h)` 读到的正是源库的顶主块（启动就是不加保护地解引用这一读）；`SNAPSHOT/ADDRESS` 经 `AddressStoreImpl` 打开，矿工余额与源库相等；`SNAPSHOT/CHAIN_L1` 记录的哈希等于源库 `stateHash()`，拷到第二个节点后 `ChainL1SnapshotGate.checkAndImport` 通过、`stateHash` 相等、链存在。
  - 第二个用例在 `SNAPSHOT/ADDRESS/CURRENT` 处放一个目录制造拷贝失败：返回 `false`、打印 `address snapshot NOT written` 与出错文件、`CHAIN_L1` 仍导出、高度仍打印、末行指明删除**整个** `SNAPSHOT` 目录后重跑。
  - `makeSnapshot` 现返回 boolean：`XdagCli.start()` 对 `false` 调 `exit(1)`（`XdagCliTest.testMakeSnapshotExitsOneWhenTheSnapshotCannotBoot`）。`SnapshotStoreImpl.makeSnapshot` 在扫描内部吞异常、写了零个块也正常返回，CLI 以 `snapshotHeight == 0` 判定 `block snapshot NOT written: no main block reached SNAPSHOT/BLOCKS (see the log)`（**残留**：那个吞异常本身没改）。`finally` 关闭 INDEX / TIME / SNAPSHOT/BLOCKS 三个句柄（此前进程一直锁着它们，同 JVM 里无法再开）。重跑必须从空的 `SNAPSHOT` 目录开始：`CHAIN_L1` 导出拒绝非空目标，块扫描与 `copyDir` 都不清旧行。
  - 既有、只记录：`-f <dir>` 只移动 store，`setDir()` 不派生 `walletFilePath`；`makeSnapshot` 以 0 长度前缀器直接开名为 `TIME` 的库（`RocksdbFactory` 挂 9 字节），只有目录选择与 `forNode` 一致，点查不受影响。

### 3.6 `ChainL1ReorgPropertyTest`（G10）

> **偏差（形态）**：v1 的"分叉回滚后断言 `CHAIN_L1` 只剩 META、再在新分支上重新链接"不可行——`confirm()` 在非最佳分支上断言 BEST/APPLIED 会失败，且新分支的高度会整体偏移。改为**同高度分支重放**：竞争分支在**相同高度**重新 link **同一批**付费块、每高度主块数也相同，性质 = reorg 之后的状态 == 分叉前 == 新基座直接 apply。v1 的"逐字节比对全部 KV"改为比对 `stateHash()`（它覆盖全部 KV）。

- 种子：默认跑 2 个（约 20 s）；`-Dxdag.reorg.full=true` 跑 1..8（约 1 min）；`-Dxdag.reorg.seeds=a,b,c` 追加（格式错误会点名这条性质）。每条断言消息都带场景（`ReorgScenario.toString()`），凭报告即可复现。
- 生成器 `ReorgScenario.generate(seed)`：**纯数据**——按主块高度分组的随机操作序列（`DEPLOY_NEW` / `DEPLOY_JOIN` / `CALL_HIT` / `CALL_MISS` / `PLAIN` / 低费 / 坏 gas 等）+ 竞争分支额外主块数的上界；同高度内步骤顺序**就是 link 顺序**，按发送方稳定排序以满足 `applyBlock` 的严格 per-sender nonce；`chainRef` 只索引同一顺序里**之前**部署的链。可行性由构造保证（`SENDERS` 里 4 个注资 500 XDAG 的密钥、每操作 ≤ ~1.3 XDAG、≤ 8 × 6 = 48 操作、每高度 ≤ `MAX_OPS` 个付费块）。
- 一个种子的执行：
  1. 基座 A：4 个空主块、记分叉点，然后逐高度 `mineMain(refs)` + `confirm` 所需的确认，记录每高度的 refs 与主块数；取 `appliedHash = chainStore.stateHash()` 与余额快照（发送方余额/nonce、金库、挖矿密钥余额）。
  2. 分叉：`rewindTo(forkPoint)`，在竞争分支上重建**同样的高度**——`mineMain(refs_h, false)` + `count_h − 1` 个空主块——再继续挖空分支主块直到 top 翻转（有界）。节点回滚旧分支的每个主块（unapply 全部付费块）并应用新分支（在相同高度重新 apply 它们）：这就是被测的对称性。
  3. 断言 (a) 旧主块不再 `BI_MAIN`、场景高度上的分支主块是；(b) 每个付费块 `BI_APPLIED`；(c) `stateHash() == appliedHash`；(d) 余额/nonce/金库等于分叉前快照。
  4. 基座 B：新基座、同种子发送方、5 个空主块、同样 apply；断言 (e) `stateHash() == appliedHash`——A 的分叉前状态就是直接 apply 的状态（RFC 6979 签名 + 挖矿时间线从基座常量重启 ⇒ 每个付费块跨运行逐字节相同；`forkSalt` 跨新基座保留已在测试里注明）。
- **它找到了什么（G11）**：性质的余额半边在种子上失败，暴露出**既有**的 `unApplyBlock` 费用除数错误——`applyBlock` 给每个 `XDAG_FIELD_OUTPUT` 地址 link 记 `amount − L`（`L = outPutLimit = max(MIN_GAS, getTxFee/outputs)`，`outputs` 把 `XDAG_FIELD_OUT` 块 link 也算进去）并持久化 `info.fee = k·L`（k = OUTPUT 地址 link 数），而 `unApplyBlock` 按 `fee / outPutNum = k·L/(k+m)` 扣回（m = OUT 块 link 数）。带代码链的 DEPLOY 与带参数链的 CALL 都是 k = m = 1，每次 reorg 每个 OUTPUT 多扣 `L/2`，金库被扣到负数时 `subtractAmount` 静默跳过。**修复（提交 79705de6）**：按持久化费用扣回 `amount − info.fee / k`（nano 精确；m = 0 即所有传统钱包/矿池转账逐字节不变）；`outPutLimit` 在 unwind 时也可用，但持久化费用是"实际收了多少"的真相，将来费用规则改了也不会把新规则套到旧块上；前提是调用方递进来的 raw 块 `info.fee` 已从库里恢复（`unWindMain`、递归调用、`ChainRepairTool` 都这么做）。回归 `ChainL1UnwindFeeTest`（chain 块回到精确的交易前余额与空金库；普通转账逐字节不变），属性测试种子 1–8 全绿。**升级须知**：(i) 余额存在 `AddressStore`、不进任何块哈希——修复前全网"错得一致"，不分叉 DAG，故随分支无条件生效、不设激活高度；(ii) **不修既有存储**——在旧代码上 unwind 过 chain 块的节点留着静默偏低的发送方余额（或偏高的金库），余额是 `applyBlock` 余额不足判定的输入，这样的节点可能拒绝同行接受的交易 → `BI_APPLIED` 与 `CHAIN_L1` stateHash / 快照哈希分歧；见过这种 reorg 的 devnet/testnet 存储要重新同步或从快照重灌；(iii) **不只影响 chain 合约**——xdagj 的 `createNewBlock` 不发 OUT link，但 `tryToConnect` 不禁止外部客户端把 `XDAG_FIELD_OUT` 与 `XDAG_FIELD_OUTPUT` 合在一块，这类外来块的 unwind 同样改变。**残留**：主块自身是传统交易块时 `setMain` 把 `info.fee` 存成 `gasCollected`（子块费用和），新旧除数都不精确；当前没有代码路径造出这种块（`createMainBlock` 不发 `XDAG_FIELD_IN` / `XDAG_FIELD_OUTPUT`）。
- **基座事实（写故障注入测试时别踩）**：`checkNewMain` 从 top 往下走非主块，要求至少看到两个 `BI_MAIN_CHAIN` 候选（`i > 1`）才 `setMain` 最低的那个——所以**第 n 次 `mineMain` 确认的是第 n − 1 次 `mineMain` 产出的主块**。v1 测试场景里"一个 `mineMain` 既链接又触发失败"的说法不对：handler 抛异常发生在链接之后的**下一次** `mineMain` 里。

### 3.7 文档与同步

- `docs/benchmarks/2026-09-18-l1-import-baseline.md`；SP0a 规格 §12.2 G1/G2/G5/G7/G8/G9/G10 标记"已关闭（SP0b-1）"并指向实现，新增 G11；总体设计 §5.1 同步；`docs/XDAGJ_SNAPSHOT_zh.md` 增加"启动一致性检查与 `--repairchain`"一节并更新 `--makesnapshot`；`docs/XDAGJ_Cli_Wallet_en.md` 补 `--repairchain`；`.claude/docs/chain-l1-foundation.md` 增补 §9.1 主链完整性；记忆更新。

---

## 4. 测试策略（as-built；用例数按测试源里的 `@Test` 计）

| 测试 | 用例 | 内容 |
|------|------|------|
| `ChainL1ImportBenchmarkTest` | 1 | `baseline`：`direct`/`syncPath` 同轮交错 + 配对差值、`confirmed`、`calib.emptyMain`、阶段重演（mean/p50/p95，占比按均值）；`-Dxdag.bench=true` 才跑，否则 Skipped |
| `MainCompletionMarkerTest`（基座） | 5 | 正常确认与回滚后标记跟随；handler 抛异常的 `setMain` 后主块 `ref == self` 且标记不推进；子块 ref 在 apply 之前写、回滚后还原；快照启动标记为实际携带的顶端；`unSetMain` 非主块不动标记 |
| `ChainConsistencyCheckTest` | 16 | clean；缺标记初始化不致命；标记落后 = 未完成 setMain（且受窗口约束）；无 ref；统计之上有主块；窗口边界；in-flight 命中 / 清除后 clean；不按激活高度钳制；跳过陈旧索引；`repairTarget` 钳到标记；unSetMain in-flight 报为 unwind；in-flight 规则独占其高度；`describe` 报初始化；in-flight 高度无块报 `?` |
| `ConsistencyGateTest`（基座） | 6 | clean 库构造并记录报告；不一致拒绝构造；修复模式构造且不起 check-main；单独 in-flight 记录拒绝；in-flight unwind 报为 unwind；首次启动初始化缺失标记 |
| `ChainRepairToolTest`（基座） | 12 | 未完成 setMain 回滚后重新确认逐字节相同；旧版无 ref 块补 ref 后回滚；统计之上的主块先 reconcile 再回滚；深目标需要 force；dry-run 也报 force 拒绝；半途 unwind 未开始 → 续完；dry-run 保留 in-flight 记录；无 ref 块的半途 unwind 仍 unapply 子块；已开始反向的半途 unwind 拒绝；到不了目标 → UNREPAIRABLE；第二次运行无事可做；非修复模式拒绝运行 |
| `RepairChainCommandTest`（基座）+ `XdagCliTest` | 7 + 2 | CLI 层：dry-run 计划 + 真修复回滚到最后完整高度；`reinit-marker` 不回滚地采用顶端 / 拒绝抹掉 in-flight unwind / 丢弃 in-flight setMain；钱包打不开 → 3；未知模式 → 3 且不碰库；库打不开 → 4 + 停节点提示；`XdagCliTest.testRepairChain` / `testRepairChainExitsWithTheToolsCode`（非零码到达 `exit()`） |
| `MakeSnapshotEndToEndTest`（基座）+ `XdagCliTest` | 2 + 2 | §3.5；`XdagCliTest.testMakeSnapshot` / `testMakeSnapshotExitsOneWhenTheSnapshotCannotBoot` |
| `CopyDirFailureTest` / `RocksdbInitFailureTest` | 2 / 1 | G7（不可写目标失败；嵌套目录拷贝）/ G8（失败后 `alive == false` 且可复用） |
| `ChainL1ReorgPropertyTest`（基座） | 1（× 种子） | §3.6 |
| `ChainL1UnwindFeeTest`（基座） | 2 | chain 块（m = 1）回到精确的交易前余额与空金库；普通转账逐字节不变 |
| `ChainSpecTest` | +2 | `consistencyWindowDefaultsTo128` / `consistencyWindowIsNodeLocalAndValidated` |
| `BlockStoreWiringTest` / `BlockStoreImplTest` | 1 / 10 | `forNode` 把原始块存进名为 TIME 的库；`BlockStoreImplTest` 改经 `forNode` 开库 |
| 回归 | — | 全量 `mvn test` 绿；`license:check`（计划 Task 11 Step 1） |

## 5. 文件清单（as-built）

**改动**：`core/BlockchainImpl.java`（`setMain` 提前 `updateBlockRef` + 两处正常出口写统计与标记 + in-flight、`unSetMain` 标记/门/非主块守卫、`applyBlock` 子块 ref、`unApplyBlock` 费用反向、构造器一致性门与快照分支标记、`reconcileTipTo`、`repairUnwindTo`、`stopCleaner`）、`db/BlockStore.java` + `db/rocksdb/BlockStoreImpl.java`（两个标记的读写、`forNode`）、`db/rocksdb/RocksdbKVSource.java`（G8）、`Kernel.java`（`enterRepairMode` / `recordConsistencyReport`、`testStop` 存统计、经 `forNode` 开库）、`cli/XdagCli.java` + `cli/XdagOption.java`（`--repairchain`、`makeSnapshot` 返回值/句柄/失败行/重跑提示、G7）、`config/AbstractConfig.java` + `config/spec/ChainSpec.java`（`chain.consistency.window`，节点本地）。
**新增**：`chain/repair/{ChainConsistencyCheck, ChainRepairTool}.java`；测试 `chain/bench/{ChainL1ImportBenchmarkTest, BenchWorkload}.java`、`chain/repair/{ChainConsistencyCheckTest, ConsistencyGateTest, ChainRepairToolTest, MainCompletionMarkerTest}.java`、`chain/l1/{ChainL1ReorgPropertyTest, ReorgScenario, ChainL1UnwindFeeTest}.java`、`cli/{MakeSnapshotEndToEndTest, CopyDirFailureTest, RepairChainCommandTest}.java`、`db/rocksdb/RocksdbInitFailureTest.java`、`db/store/BlockStoreWiringTest.java`；`docs/benchmarks/2026-09-18-l1-import-baseline.md`。
**v1 列了但未改**：`chain/l1/ChainL1SnapshotGate.java`（标记由 `BlockchainImpl` 的快照分支写，快照门不碰它）。

## 6. 开放问题（已结）

| # | 问题 | 处理 |
|---|------|------|
| Q1 | `xdagStats.nmain` 的持久化时机（若晚于主块落盘，检查规则 3 的上界 `+8` 是否足够） | **已结**：`checkMain` 在 `setMain` 返回后才存统计，且 SP0b-1 起 `setMain` 自己在写标记前先存一次；扫描上界改为 `nmain + 64` 并在 `nmain` 之上第一个空高度即停，"统计之上有主块"作为独立规则报告并由 `reconcileTipTo` 修复 |
| Q2 | `SyncManager` 在基座里能否无网络构造（`syncPath` 测量） | **已结**：可以——mock `ChannelManager`（无活动 channel）与 `PeerClient`，`syncPath` 走的是真实网络入口 |
| Q3 | 修复命令是否需要处理 `ADDRESS` 与 `BLOCK` 之间既有的回滚不对称（Q4 of SP0a） | `allBalance` 的 try/catch 未动；但 G10 的余额比对不修 `unApplyBlock` 的 OUTPUT 费用除数就过不了，故该项（G11）在本 SP 修了（§3.6） |
