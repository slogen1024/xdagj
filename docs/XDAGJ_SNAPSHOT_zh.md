### XDAGJ snapshot loading method


1. import the wallet of the c pool
    ```shell script
    ./xdag.sh --convertoldwallet <your_wallet_path>/wallet-testnet.dat -t
    ```
2. prepare to load snapshot
    ```shell script
   ./xdag.sh --loadsnapshot <your_snapshot_file_path> -t
    ```
3. load snapshot (snapshot time in hex) 
    ```shell script
    ./xdag.sh -t --enablesnapshot <snapshot_height> <snapshot_time>
    ```
   
   
![C version snapshot](img/C_version_snapshot.png)

---

### 快照目录：三个，缺一不可

从 chain 合约（SP0a）起，一份快照由**三个** RocksDB 目录组成，它们并列存放在节点 store 目录下：

```
<store_dir>/SNAPSHOT/BLOCKS     区块快照（BlockInfo / 公钥 / preseed）
<store_dir>/SNAPSHOT/ADDRESS    新模型地址余额与 nonce
<store_dir>/SNAPSHOT/CHAIN_L1    chain 全局状态（registry / contract / code / input 索引）+ 状态哈希
```

- **发布方**：`./xdag.sh --makesnapshot` 一次生成三个目录，发布时**三个一起打包**。`SNAPSHOT/CHAIN_L1` 总是会写出：即使本网络尚未安排 chain 激活高度、CHAIN_L1 还是空的，导出结果也是「一个 META 键 + 一个状态哈希」这样一份很小的快照，低于激活高度的节点会直接忽略它。
- **使用方**：把三个目录**一并**放到本节点的 `<store_dir>/SNAPSHOT/` 下，再执行 `--enablesnapshot`。
- **强制性**：当快照高度 ≥ `chain.activation.height` 时，缺少 `SNAPSHOT/CHAIN_L1` 的节点会**拒绝启动**（快速失败，发生在区块/地址导入之前）；低于激活高度时该目录被忽略，有没有都行。该校验对所有快照启动生效，与 `--enablesnapshot` 的第一个参数（`isSnapshotJ`）是 true 还是 false 无关。

### 导入只做一次；重试幂等，但会重新校验

导入成功时，快照记录的状态哈希会被写进本地 CHAIN_L1（键 `0xFF`），与被导入的键在**同一个原子批次**里落盘，作为「本节点的 CHAIN_L1 来自哪一份快照」的持久标记：

- **这道校验只在「区块库要被快照重新灌一遍」时才会执行**：`BlockchainImpl` 的快照分支要求 `blockStore.isSnapshotBoot()` 仍为 false，而 `Kernel` 在构造之后立刻把它置位，所以之后的普通重启根本不会走到这里，标记也就与普通重启无关。
- 因此在这条路径上看到标记，只有一种良性解释：**同一次重新灌库的上一轮尝试**已经导入过 chain 状态、节点却在收尾前挂了。这种重试必须是空操作，于是网关在两个条件都成立时直接返回、不动任何键：`SNAPSHOT/CHAIN_L1` 存在且其记录的哈希**正好等于**标记（即本次灌的是同一份快照），且本地 `stateHash()` **仍然等于**标记（导入之后没有再变过）。
- 任何一条不成立都会**拒绝启动**：换用更新的快照重新灌库（标记停在旧快照，chain 状态会缺掉两份快照之间的全部内容），或本地 CHAIN_L1 已经往前跑过（区块库重灌会把这些块再应用一遍，造成重复计数）。处置一律是**删除报错信息中给出的本地 `CHAIN_L1` 目录后重启**——所以**不要**在节点成功启动前删掉 `SNAPSHOT/CHAIN_L1`。
- 校验失败（哈希对不上、schema 版本不符、快照里没有记录哈希）时**不会写入任何键**，本地 CHAIN_L1 保持为空，换一份正确的快照即可重试。

### 启动报错与处置

| 报错开头 | 含义 | 处置 |
|----------|------|------|
| `CHAIN_L1 snapshot required at height … but …/SNAPSHOT/CHAIN_L1 is missing` | 已过激活高度，但没有随快照拿到 chain 目录 | 向**提供 `SNAPSHOT/BLOCKS` 的同一发布方**索取 `SNAPSHOT/CHAIN_L1`，放到 `SNAPSHOT/BLOCKS` 旁边，重启 |
| `CHAIN_L1 already holds state that was not imported from a snapshot (cannot be verified)` | 本地 CHAIN_L1 已有内容却没有导入标记（例如上一条链留下的目录），无法与快照核对 | 删除报错信息中给出的本地 `CHAIN_L1` 目录后重启；或者改用与该 CHAIN_L1 相匹配的快照启动 |
| `CHAIN_L1 was imported from a snapshot with hash … but SNAPSHOT/CHAIN_L1 is missing for this boot` | 本地 CHAIN_L1 带着导入标记，但本次重新灌库时 chain 快照目录不在了，无从判断要灌的是不是同一份 | 把同一份 `SNAPSHOT/CHAIN_L1` 放回 `SNAPSHOT/BLOCKS` 旁边；拿不到就删除报错信息中给出的本地 `CHAIN_L1` 目录后重启 |
| `CHAIN_L1 was imported from a snapshot with hash … but this boot seeds from a snapshot with hash …` | 保留了旧快照的 CHAIN_L1，却用**另一份（通常更新的）**快照重新灌库 | 删除报错信息中给出的本地 `CHAIN_L1` 目录后重启，让它随新快照一起导入 |
| `CHAIN_L1 has moved past the snapshot it was imported from` | 导入之后节点又应用过区块，现在却要把区块库重新灌一遍 | 删除报错信息中给出的本地 `CHAIN_L1` 目录后重启 |

### `--makesnapshot` 的失败行与退出码

`--makesnapshot` 只有三个目录都写成功才退出 0；**任一目录不可用于启动即退出 1**（脚本据此判断），并在输出里给出对应的一行：

| 输出行 | 含义 | 处置 |
|--------|------|------|
| `address snapshot NOT written: …` | `SNAPSHOT/ADDRESS` 的目录拷贝在某个文件上失败（行尾带出错文件），留下的是半个目录；`CHAIN_L1` 仍会继续导出 | 删除**整个** `<store_dir>/SNAPSHOT` 目录后重新 `--makesnapshot` |
| `block snapshot NOT written: no main block reached SNAPSHOT/BLOCKS (see the log)` | 区块扫描一个主块都没写出（扫描内部的异常只进日志），得到的快照高度为 0，节点无法从它启动 | 看日志找原因；删除整个 `SNAPSHOT` 目录后重跑 |
| `chain state snapshot NOT written: … -- this snapshot cannot boot a node at or past the chain activation height; fix the cause and export SNAPSHOT/CHAIN_L1 again` | 区块/地址快照已写好，但 chain 快照没写成（最常见原因是 `SNAPSHOT/CHAIN_L1` 已存在且非空——重复导出会与旧内容混在一起，因此被拒绝） | 这份快照不能用于启动激活高度及以后的节点；处理原因后重新导出——最稳妥的做法同样是删除整个 `SNAPSHOT` 目录后重跑 |

前两种情况的末行会写明 `this snapshot cannot boot a node; delete <store_dir>/SNAPSHOT and run --makesnapshot again`。**重跑必须从空的 `SNAPSHOT` 目录开始**：`CHAIN_L1` 导出拒绝非空目标，而区块扫描与地址拷贝都不会先清掉旧内容——只删其中一个子目录再跑，会在下一个子目录上再失败一次。无论哪种失败，`snapshot height` 与 `next start frame` 仍会照常打印，不要把它们当作成功的信号，看退出码。

### 启动一致性检查与 `--repairchain`

从 SP0b-1 起，节点每次启动都会先核对主链的完整性（`ChainConsistencyCheck`，只读），核对不过就**拒绝启动**、不自动改任何状态；修复只由运维用 `--repairchain` 显式触发。核对依赖两个节点本地的标记（不进任何哈希、不随快照导出）：`setMain`/`unSetMain` 正常结束时写的"最后一个完整高度"，以及两者一进入就写、正常结束才清掉的"进行中"记录。

**报错长什么样**（这段会以 `Uncaught exception during kernel startup:` 为前缀打到 stderr、后面还跟一份 Java 栈迹，日志里同样有一份；高度与哈希是示例）：

```
main chain consistency: nmain=1207, lastCompletedMain=1206, 1 stuck main block(s):
  height 1207 0x8f3c…e1: setMain incomplete (completion marker 1206 is behind persisted nmain 1207); the node refuses to start. Run `xdag.sh --repairchain dry-run` to see the repair plan and `xdag.sh --repairchain` to unwind to the last complete height
```

每个出问题的高度一行，原因是下面四种之一：

| 原因（原文） | 含义 |
|--------------|------|
| `setMain in flight when the node stopped` / `unSetMain in flight when the node stopped` | 节点死在某个高度的确认/回滚过程中间（"进行中"记录还在） |
| `setMain incomplete (completion marker M is behind persisted nmain N)` | 上一次确认没有走完：M+1..N 这些高度的 chain 记录可能只写了一半 |
| `main block above persisted stats (setMain crashed before stats were saved)` | 库里存着比统计更高的主块（确认在保存统计之前崩了） |
| `main block without ref (unwindable only after repair)` | 升级前的老版本崩溃留下的主块：没有自引用，直接回滚会跳过它 |

首次用 SP0b-1 启动一个旧库时会看到一条 `LAST_COMPLETED_MAIN marker absent: initializing to nmain=…` 的 warn 并正常启动：标记从这一刻起才有，之前的历史无法核对。

**先停节点。** `--repairchain` 要独占打开 RocksDB；节点还在跑时命令会以退出码 4 结束，打印根因（RocksDB 的 `LOCK`）和 `if the node is running on this store, stop it first: RocksDB allows only one process to open <store_dir> at a time` 的提示。命令会要求解锁钱包——**钱包只用来构造内核，不签任何东西**（无控制台时用 `--password`，否则退出码 3）。

**模式与退出码**

| 命令 | 做什么 |
|------|--------|
| `./xdag.sh --repairchain dry-run` | 打印检查结果与修复计划，不写任何东西（唯一例外：旧库首次启动时初始化那个标记，输出会说明）。如果修复需要 `force`，dry-run 就会告诉你 |
| `./xdag.sh --repairchain` | 真修复：需要时先把统计抬到库里更高的主块，给没有自引用的主块补上引用，把主链回滚到最后一个完整高度，写标记，再核对一遍 |
| `./xdag.sh --repairchain force` | 同上，但允许回滚到比 `chain.consistency.window`（默认 128）更深的目标 |
| `./xdag.sh --repairchain reinit-marker` | **只用于降级陷阱**（见下）；不核验、不回滚，把标记改成当前统计的高度 |

| 退出码 | 含义 | 下一步 |
|--------|------|--------|
| 0 | 库可以启动了：本来就干净 / 已修复 / dry-run 的计划可以执行 / `reinit-marker` 之后核对干净 | 正常启动节点 |
| 1 | 拒绝：目标比窗口更深 | 看清计划后用 `force` 重跑 |
| 2 | 本地状态修不了：回滚在中途被打断且已经反向了一部分、原始区块字节缺失、修复后仍不一致、`reinit-marker` 被拒或之后仍不干净 | **从快照恢复**（空的 store 目录 + 三个 `SNAPSHOT/*` 目录） |
| 3 | 命令没启动：模式拼错、钱包缺失/锁定/打不开 | 改正后重跑 |
| 4 | 开库之后失败（典型：节点还在跑） | 停节点后重跑；看日志里的完整栈迹 |

**修复之后。** 被回滚的区块**仍在库里**，但节点不会靠本地状态自己往上走：只有同行发来**真正新的**、建立在旧链头之上的区块，节点才会重新确认那些被回滚的高度（同行重发已存在的区块不会推进链顶）。孤立运行、没有同行的节点会停在修复到的高度——这是预期行为，不是修复失败。

**中途被打断的回滚。** "进行中"记录如果是一次 `unSetMain`：那个高度的主块还标着主块 → 什么都没反向，`--repairchain` 会把这一次回滚跑完；主块标志已经清掉 → 反向已经落了未知的一部分，无法幂等续做，命令退出 2（`a main block unwind was interrupted after it started reversing state; restore the block store from a snapshot`）。`reinit-marker` **拒绝**清掉这种记录（退出 2）：它是这次中断的唯一证据。

**降级陷阱。** 有了标记之后**不要降级到 SP0b-1 之前的版本**：老版本继续确认主块而标记冻结，再升级时检查会把标记之上的几百个其实完好的高度都报成"未完成"。如果已经这样做了，先 `--repairchain reinit-marker`（它等于由你断言这些高度全部完整），再启动。快照重灌请用**空的** store 目录。

**一条与回滚有关的升级提醒。** SP0b-1 修正了回滚 chain 区块（带代码链的 DEPLOY、带参数链的 CALL）时的一处费用扣减错误（`unApplyBlock` 现按持久化费用 `amount − fee / k` 精确扣回）。修复不设激活高度、不影响普通转账，但**不会修正既有存储**：如果某个 devnet/testnet 节点曾在旧代码上回滚过 chain 区块，它的某些余额已经静默偏低（或金库偏高），以后可能拒绝一笔别的节点接受的交易而分歧。这样的存储请重新同步，或用快照重灌。

### 写后落盘与导入流水线（SP0b-2）

从 SP0b-2 起，节点**默认**开启两样东西：导入流水线（网络来的块在线程池里并行做哈希与验签，再由一条提交线程**按到达顺序**进链，判定一字不改），以及**写后落盘**——区块、`BlockInfo`、统计与孤块索引的写先进一条有序队列，由一条名为 `xdag-persist` 的线程合批写进 RocksDB。主块确认与回滚（`setMain` / `unSetMain` / `unWindMain`、`--repairchain` 的回滚、快照导入）**不**走队列：它们先把队列排空，再直写，落盘形态与 SP0b-2 之前逐字节相同。

**运维需要知道的四件事**

1. **崩溃会丢掉最后一小段写。** 磁盘上的四个库永远是写流的一个**前缀**（组粒度），最多丢队列里还没写出去的那些——上限是 `chain.persist.maxPending`（默认 4096 条）加一组，或 `chain.persist.flushMs`（默认 20 ms）的时间窗。丢掉的块只是"不在"，同步会重新拿；库里的链顶一定指向库里存在的块。**注意这里说的是"交给 RocksDB"**（写进 WAL），不是 `fsync`——掉电的耐久性与 SP0b-2 之前完全一样，没有变好也没有变差。
   另有一项与块无关的小损失：块的 **sums**（传统同步协议用来挑请求区间的统计）现在也是批量写回的，触发点是每 256 个保存的块、每次换到新的最深时间桶、以及**正常关机**。一次崩溃会丢掉最后不足 256 个块对当前桶的贡献，而且**丢了就补不回来**（没有任何代码会重新给盘上的块计 sums）。后果只是那个区间以后会被同步协议反复钻进去重新请求，**是带宽，不是正确性**——sums 从不进共识。
2. **落盘失败现在会让节点停机。** 以前一次 RocksDB 写失败只是日志里一行错误，节点继续跑；现在队列一旦写失败就**永久中毒**（不重试、不静默丢写），日志里会出现
   ```
   write-behind persistence failed after <N> written entries; the node must stop
   write-behind persistence failed: this node can no longer store anything durably and is stopping
   ```
   紧接着节点自己走一遍正常关机（另起一条 `xdag-persist-failure` 线程跑关机流程）并以**退出码 1** 结束。这是有意的：一个存不下东西的节点继续挖矿、继续答 RPC，比停下来糟糕得多。看到这个先查磁盘（满了？只读？坏道？），处理完直接重启——库里剩下的仍然是一个合法前缀，启动一致性检查会照常核对。
3. **两个关掉的开关**（都是节点本地设置，**不要**写进任何 `.conf`，用 `-D` 传给 JVM 即可；给了非法值节点会拒绝启动）：
   - `-Dchain.persist.maxPending=0` —— 关掉写后落盘，回到**完全同步**的写（每次 put 直接进 RocksDB），行为与 SP0b-2 之前逐字节相同。
   - `-Dchain.ingest.threads=0` —— 关掉导入流水线，回到**同步导入**路径。
   其余四个只是尺寸：`chain.ingest.queue`（默认 4096，在飞块数上限）、`chain.persist.flushMs`（20）、`chain.persist.flushEntries`（256，**必须 ≤ `chain.persist.maxPending`**，否则拒绝启动）、`chain.persist.readCache`（65536 条，INDEX 读缓存，0 = 关）。正常运行时日志里会各有一行
   `Write-behind persistence on: maxPending=…, flushEntries=…, flushMs=…, readCache=…` 与 `Ingest pipeline on: threads=…, queue=…`；关掉的那个就没有对应的行。
4. **关机顺序变了，别在中途 `kill -9`。** 现在是：停 API → 停同步 → **停导入流水线（先停止接收、把已经收下的块全部提交完、再 join）** → 停挖矿 → 停网络 → 停主链检查线程 → **停孤块清理器** → 停 WebSocket / 奖励分发 → 最后一次写 sums 与统计 → 关 `CHAIN_L1` → **通过工厂关掉全部数据库**（这一步会先把写后队列排空、再停 `xdag-persist` 这条**非 daemon** 线程）。正常 `stop` 能走完全程，这一小段写就不会丢；中途强杀就退化成第 1 条说的那个前缀。如果日志里出现 `write-behind writer did not stop within 30 s` 或 `ingest pipeline drain timed out`，说明底下的 RocksDB 卡住了，先看磁盘再重启。

**不受影响的东西**：SP0b-1 的启动一致性检查、`LAST_COMPLETED_MAIN` / `MAIN_IN_FLIGHT` 两个标记、`--repairchain` 的全部模式与退出码、`--makesnapshot` 与快照导入——它们要么跑在直写模式下，要么用离线工具自己开库（离线工具根本不装写后层）。上面那几节一个字都不用改。
