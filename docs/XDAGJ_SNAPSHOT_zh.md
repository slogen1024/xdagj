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

从 lane 合约（SP0a）起，一份快照由**三个** RocksDB 目录组成，它们并列存放在节点 store 目录下：

```
<store_dir>/SNAPSHOT/BLOCKS     区块快照（BlockInfo / 公钥 / preseed）
<store_dir>/SNAPSHOT/ADDRESS    新模型地址余额与 nonce
<store_dir>/SNAPSHOT/LANE_L1    lane 全局状态（registry / contract / code / input 索引）+ 状态哈希
```

- **发布方**：`./xdag.sh --makesnapshot` 一次生成三个目录，发布时**三个一起打包**。`SNAPSHOT/LANE_L1` 总是会写出：即使本网络尚未安排 lane 激活高度、LANE_L1 还是空的，导出结果也是「一个 META 键 + 一个状态哈希」这样一份很小的快照，低于激活高度的节点会直接忽略它。
- **使用方**：把三个目录**一并**放到本节点的 `<store_dir>/SNAPSHOT/` 下，再执行 `--enablesnapshot`。
- **强制性**：当快照高度 ≥ `lane.activation.height` 时，缺少 `SNAPSHOT/LANE_L1` 的节点会**拒绝启动**（快速失败，发生在区块/地址导入之前）；低于激活高度时该目录被忽略，有没有都行。该校验对所有快照启动生效，与 `--enablesnapshot` 的第一个参数（`isSnapshotJ`）是 true 还是 false 无关。

### 导入只做一次；重试幂等，但会重新校验

导入成功时，快照记录的状态哈希会被写进本地 LANE_L1（键 `0xFF`），与被导入的键在**同一个原子批次**里落盘，作为「本节点的 LANE_L1 来自哪一份快照」的持久标记：

- **这道校验只在「区块库要被快照重新灌一遍」时才会执行**：`BlockchainImpl` 的快照分支要求 `blockStore.isSnapshotBoot()` 仍为 false，而 `Kernel` 在构造之后立刻把它置位，所以之后的普通重启根本不会走到这里，标记也就与普通重启无关。
- 因此在这条路径上看到标记，只有一种良性解释：**同一次重新灌库的上一轮尝试**已经导入过 lane 状态、节点却在收尾前挂了。这种重试必须是空操作，于是网关在两个条件都成立时直接返回、不动任何键：`SNAPSHOT/LANE_L1` 存在且其记录的哈希**正好等于**标记（即本次灌的是同一份快照），且本地 `stateHash()` **仍然等于**标记（导入之后没有再变过）。
- 任何一条不成立都会**拒绝启动**：换用更新的快照重新灌库（标记停在旧快照，lane 状态会缺掉两份快照之间的全部内容），或本地 LANE_L1 已经往前跑过（区块库重灌会把这些块再应用一遍，造成重复计数）。处置一律是**删除报错信息中给出的本地 `LANE_L1` 目录后重启**——所以**不要**在节点成功启动前删掉 `SNAPSHOT/LANE_L1`。
- 校验失败（哈希对不上、schema 版本不符、快照里没有记录哈希）时**不会写入任何键**，本地 LANE_L1 保持为空，换一份正确的快照即可重试。

### 启动报错与处置

| 报错开头 | 含义 | 处置 |
|----------|------|------|
| `LANE_L1 snapshot required at height … but …/SNAPSHOT/LANE_L1 is missing` | 已过激活高度，但没有随快照拿到 lane 目录 | 向**提供 `SNAPSHOT/BLOCKS` 的同一发布方**索取 `SNAPSHOT/LANE_L1`，放到 `SNAPSHOT/BLOCKS` 旁边，重启 |
| `LANE_L1 already holds state that was not imported from a snapshot (cannot be verified)` | 本地 LANE_L1 已有内容却没有导入标记（例如上一条链留下的目录），无法与快照核对 | 删除报错信息中给出的本地 `LANE_L1` 目录后重启；或者改用与该 LANE_L1 相匹配的快照启动 |
| `LANE_L1 was imported from a snapshot with hash … but SNAPSHOT/LANE_L1 is missing for this boot` | 本地 LANE_L1 带着导入标记，但本次重新灌库时 lane 快照目录不在了，无从判断要灌的是不是同一份 | 把同一份 `SNAPSHOT/LANE_L1` 放回 `SNAPSHOT/BLOCKS` 旁边；拿不到就删除报错信息中给出的本地 `LANE_L1` 目录后重启 |
| `LANE_L1 was imported from a snapshot with hash … but this boot seeds from a snapshot with hash …` | 保留了旧快照的 LANE_L1，却用**另一份（通常更新的）**快照重新灌库 | 删除报错信息中给出的本地 `LANE_L1` 目录后重启，让它随新快照一起导入 |
| `LANE_L1 has moved past the snapshot it was imported from` | 导入之后节点又应用过区块，现在却要把区块库重新灌一遍 | 删除报错信息中给出的本地 `LANE_L1` 目录后重启 |

此外，`--makesnapshot` 若打印 `lane state snapshot NOT written: …`，说明区块/地址快照已经写好、但 lane 快照没写成（最常见原因是 `SNAPSHOT/LANE_L1` 已存在且非空——重复导出会与旧内容混在一起，因此被拒绝）。此时高度与 next start frame 仍会照常打印，但**这份快照不能用于启动激活高度及以后的节点**：删除已存在的 `SNAPSHOT/LANE_L1` 后重新导出。
