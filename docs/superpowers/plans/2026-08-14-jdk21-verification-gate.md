# JDK 21 验证门 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 JDK 21 上证明 dev-evm HEAD（含 C4-C6 + E2E 共 25 个未构建 commit）健康：全量测试绿、executable jar 起 devnet、RPC/WS 探活通过。

**Architecture:** 纯验证性任务，不改产品代码。环境装到用户目录（可整目录删除回滚），构建走仓库既定的 Maven Toolchains 机制，冒烟用临时工作目录 + 新建钱包（不碰仓库根 `devnet/` 旧数据），探活用 curl + python3 标准库。

**Tech Stack:** Temurin 21（Adoptium API tarball）、Apache Maven 3.9.9、maven-toolchains-plugin、`/usr/bin/expect`（钱包交互自动化）、python3 stdlib（WS 探活）。

**Spec:** `docs/superpowers/specs/2026-08-14-jdk21-verification-gate-design.md`

---

## 全局事实（执行前须知，2026-08-14 已核实）

- 本机仅有 JDK 17/11/8；Maven 为 `/usr/local/apache-maven-3.6.3`（**不满足** 3.9.x 要求，且不在 PATH）。
- pom.xml `maven-toolchains-plugin` 硬性要求注册 version=21 的 toolchain，缺 `~/.m2/toolchains.xml` 会 fail fast；该文件当前**不存在**。
- 仓库根 `devnet/` 有旧钱包+链数据，密码未知——**不得使用、不得删除**；冒烟一律在 `/tmp/xdagj-smoke` 下进行（数据目录相对 cwd）。
- 钱包首建是交互式：`EnterNewPassword:` / `ReEnterNewPassword:` / 打印助记词后要求 `HdWallet Mnemonic Repeat:` 原样回显——用 expect 走 PTY。
- `--password` 选项存在（`XdagOption.PASSWORD`），已有钱包时可非交互解锁。
- executable jar 故意不打包网络 conf：启动必须 `-cp target/classes:<jar>`，不能裸 `java -jar`。
- Bash 工具单次超时上限 10 分钟：全量构建（预计 10-30 分钟）必须 `run_in_background` + tail 日志。
- shell 状态不跨命令持久：每个代码块自带环境变量前奏，勿省略。

## 失败处置（spec §3，任一任务失败时适用）

- **测试工件问题**（时序断言、端口冲突、JDK 行为差异）：直接修，每个修复独立 `test(evm): ...` commit（英文）。
- **产品代码缺陷**：**停止执行计划**，报告缺陷性质与影响面，等用户确认后再动——尤其不得擅改 `BlockchainImpl` 三挂载点、`EvmBlockProcessor`、回滚/根摘要路径。
- 环境步骤失败（下载 404、解压异常）：换 Adoptium/Apache archive 的相邻小版本重试一次，仍失败则报告。

## 回滚（如需完全撤销环境改动）

```bash
rm -rf ~/Library/Java/JavaVirtualMachines/jdk-21*
rm -rf ~/tools/apache-maven-3.9.9
rm -f ~/.m2/toolchains.xml        # 该文件为本计划新建，删除无副作用
rm -rf /tmp/xdagj-smoke
```

---

### Task 1: 安装 Temurin 21

**Files:** 无仓库文件改动；产物 `~/Library/Java/JavaVirtualMachines/jdk-21.0.x+y/`

- [ ] **Step 1: 下载并解压到用户 JVM 目录**

```bash
curl -fsSL -o /tmp/temurin21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/temurin21.tar.gz -C ~/Library/Java/JavaVirtualMachines/
rm /tmp/temurin21.tar.gz
```

- [ ] **Step 2: 验证 java_home 能发现 JDK 21**

Run: `/usr/libexec/java_home -v 21 && "$(/usr/libexec/java_home -v 21)/bin/java" -version`
Expected: 打印 `.../jdk-21.0.*/Contents/Home`；版本行含 `openjdk version "21.0.`（Temurin build）。

### Task 2: 安装 Maven 3.9.9（用户目录，不动 /usr/local）

**Files:** 产物 `~/tools/apache-maven-3.9.9/`

- [ ] **Step 1: 下载并解压**

```bash
mkdir -p ~/tools
curl -fsSL -o /tmp/maven399.tar.gz \
  "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
tar -xzf /tmp/maven399.tar.gz -C ~/tools/
rm /tmp/maven399.tar.gz
```

- [ ] **Step 2: 验证版本与宿主 JDK**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
~/tools/apache-maven-3.9.9/bin/mvn -version
```

Expected: `Apache Maven 3.9.9`，`Java version: 21.0.`。

### Task 3: 注册 Maven Toolchains

**Files:** Create: `~/.m2/toolchains.xml`（由 `misc/toolchains.xml` 改 jdkHome 生成）

- [ ] **Step 1: 守卫检查——文件必须不存在（2026-08-14 核实不存在；若存在说明环境已变，停下手动合并）**

Run: `test ! -f ~/.m2/toolchains.xml && echo ABSENT-OK || echo "EXISTS - STOP AND MERGE MANUALLY"`
Expected: `ABSENT-OK`

- [ ] **Step 2: 以真实 jdkHome 生成**

```bash
mkdir -p ~/.m2
JDK21_HOME="$(/usr/libexec/java_home -v 21)"
sed "s|/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home|$JDK21_HOME|" \
  /Users/tron/IDEAProject/xdagj/misc/toolchains.xml > ~/.m2/toolchains.xml
grep jdkHome ~/.m2/toolchains.xml
```

Expected: `<jdkHome>/Users/tron/Library/Java/JavaVirtualMachines/jdk-21.0.*/Contents/Home</jdkHome>`（vendor 保持 `openjdk`，pom 只匹配 version=21，无 vendor 约束）。

### Task 4: JDK 21 全量构建与测试（成功标准 1）

**Files:** 无改动（除非失败处置）；日志 `/tmp/xdagj-jdk21-build.log`

- [ ] **Step 1: 后台启动全量构建（必须 run_in_background，预计 10-30 分钟）**

```bash
cd /Users/tron/IDEAProject/xdagj
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
mvn clean package 2>&1 | tee /tmp/xdagj-jdk21-build.log
```

（`RandomXSyncTest/SyncTest/SnapshotJTest` 由 surefire 默认排除，勿加任何额外 `-DskipTests`/`-Dtest`。）

- [ ] **Step 2: 确认全绿并记录数字**

Run: `grep -E "Tests run:.*Failures.*Errors|BUILD SUCCESS|BUILD FAILURE" /tmp/xdagj-jdk21-build.log | tail -5`
Expected: 汇总行 `Tests run: N, Failures: 0, Errors: 0, Skipped: k`（N ≥ 340，基线 340 之上叠加 C4-C6/E2E 新测试）且 `BUILD SUCCESS`。**把 N 记入最终报告。**
失败 → 按「失败处置」分类；工件修复后重跑本 Task。

- [ ] **Step 3: 确认产物存在**

Run: `ls -lh /Users/tron/IDEAProject/xdagj/target/xdagj-*-executable.jar`
Expected: 一个 executable jar，几十 MB 量级。

### Task 5: 冒烟准备（端口检查 + 临时目录 + 钱包初始化）

**Files:** 产物 `/tmp/xdagj-smoke/`（devnet 数据 + expect 脚本）

- [ ] **Step 1: 端口预检（节点要占 10001/10002/8001/6001/7001）**

Run: `lsof -nP -i :10001 -i :10002 -i :8001 -i :6001 -i :7001 || echo PORTS-FREE`
Expected: `PORTS-FREE`。若有占用：报告占用进程，**不杀别人的进程**，等用户处置。

- [ ] **Step 2: expect 自动化钱包初始化（临时 cwd，新钱包，密码 `xdag-smoke`）**

```bash
mkdir -p /tmp/xdagj-smoke && cd /tmp/xdagj-smoke
REPO=/Users/tron/IDEAProject/xdagj
JAR="$(ls "$REPO"/target/xdagj-*-executable.jar | head -n 1)"
cat > init-wallet.exp <<EOF
set timeout 90
spawn java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -cp $REPO/target/classes:$JAR io.xdag.Bootstrap -d --account init
expect "EnterNewPassword:" { send "xdag-smoke\r" }
expect "ReEnterNewPassword:" { send "xdag-smoke\r" }
expect -re {HdWallet Mnemonic:([^\r\n]+)} { set phrase \$expect_out(1,string) }
expect "HdWallet Mnemonic Repeat:" { send "\$phrase\r" }
expect "HdWallet Initialized Successfully!"
expect eof
EOF
expect init-wallet.exp
```

Expected: 依次出现两次密码提示、助记词打印与回显、`HdWallet Initialized Successfully!`，进程正常退出。

- [ ] **Step 3: 确认钱包落盘在临时目录（仓库根 devnet/ 未被触碰）**

Run: `ls /tmp/xdagj-smoke/devnet/wallet/ && ls -l /Users/tron/IDEAProject/xdagj/devnet/wallet/`
Expected: 临时目录出现新 `wallet.data`；仓库根旧钱包文件 mtime 无变化。

### Task 6: 启动节点 + HTTP RPC 探活（成功标准 2 与 3 前半）

**Files:** 日志 `/tmp/xdagj-smoke/node.log`，PID `/tmp/xdagj-smoke/node.pid`

- [ ] **Step 1: 后台启动 devnet 节点（`--password` 非交互解锁）**

```bash
cd /tmp/xdagj-smoke
REPO=/Users/tron/IDEAProject/xdagj
JAR="$(ls "$REPO"/target/xdagj-*-executable.jar | head -n 1)"
nohup java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -Xms2g -Xmx2g -cp "$REPO/target/classes:$JAR" io.xdag.Bootstrap -d --password 'xdag-smoke' \
  > node.log 2>&1 & echo $! > node.pid
sleep 1 && cat node.pid && ps -p "$(cat node.pid)" -o pid,comm
```

Expected: PID 存活。异常时看 `node.log`（常见：缺 conf → 用了裸 `-jar`；NIO 异常 → 缺 `--add-opens`）。

- [ ] **Step 2: 轮询 eth_chainId 直到应答（≤120 秒）**

```bash
for i in $(seq 1 60); do
  R=$(curl -s http://127.0.0.1:10001/ -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}')
  if [[ "$R" == *'0xcafe'* ]]; then echo "OK: $R"; break; fi
  sleep 2
done
```

Expected: `OK: {"jsonrpc":"2.0","id":1,"result":"0xcafe"}`。超时 → tail node.log 报告后按「失败处置」。

- [ ] **Step 3: eth_blockNumber 与 web3_clientVersion**

```bash
curl -s http://127.0.0.1:10001/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":2}'
curl -s http://127.0.0.1:10001/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":3}'
```

Expected: 各返回 `"result":"0x..."`（块高十六进制；devnet 自出块，稍等可见增长）与非空客户端版本串。**记入最终报告。**

### Task 7: WebSocket 探活（成功标准 3 后半）

**Files:** Create: `/tmp/xdagj-smoke/ws-probe.py`

- [ ] **Step 1: 写入 stdlib WS 客户端（握手 + 单帧 eth_subscribe newHeads）**

```bash
cat > /tmp/xdagj-smoke/ws-probe.py <<'EOF'
import socket, base64, os, json
key = base64.b64encode(os.urandom(16)).decode()
s = socket.create_connection(("127.0.0.1", 10002), timeout=15)
s.sendall((
    "GET / HTTP/1.1\r\nHost: 127.0.0.1:10002\r\n"
    "Upgrade: websocket\r\nConnection: Upgrade\r\n"
    f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
buf = b""
while b"\r\n\r\n" not in buf:
    buf += s.recv(4096)
assert b" 101 " in buf.split(b"\r\n", 1)[0] + b" ", buf[:200]
payload = json.dumps({"jsonrpc": "2.0", "id": 1,
                      "method": "eth_subscribe", "params": ["newHeads"]}).encode()
mask = os.urandom(4)
frame = bytearray([0x81, 0x80 | len(payload)])  # payload < 126 字节，够用
frame += mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
s.sendall(bytes(frame))
hdr = s.recv(2)
ln = hdr[1] & 0x7F
if ln == 126:
    ln = int.from_bytes(s.recv(2), "big")
data = b""
while len(data) < ln:
    data += s.recv(ln - len(data))
resp = json.loads(data)
print(resp)
assert resp.get("id") == 1 and isinstance(resp.get("result"), str) and resp["result"], resp
print("WS-PROBE-OK")
EOF
```

- [ ] **Step 2: 运行探活**

Run: `python3 /tmp/xdagj-smoke/ws-probe.py`
Expected: 打印含订阅 id 的响应（`{'jsonrpc': '2.0', 'id': 1, 'result': '0x...'}`）后输出 `WS-PROBE-OK`。**订阅 id 记入最终报告。**

### Task 8: 收尾（停节点、清理、报告、记忆更新）

**Files:** Modify: `~/.claude/projects/-Users-tron-IDEAProject-xdagj/memory/xdag-evm-program.md`

- [ ] **Step 1: 停节点并清理临时目录**

```bash
kill "$(cat /tmp/xdagj-smoke/node.pid)" && sleep 3
ps -p "$(cat /tmp/xdagj-smoke/node.pid)" || echo NODE-STOPPED
rm -rf /tmp/xdagj-smoke
lsof -nP -i :10001 || echo PORT-RELEASED
```

Expected: `NODE-STOPPED` 与 `PORT-RELEASED`。

- [ ] **Step 2: 复核仓库工作区干净（验证门不应产生未预期改动）**

Run: `cd /Users/tron/IDEAProject/xdagj && git status --porcelain`
Expected: 空输出（若有工件修复 commit，应已提交且此处仍为空）。

- [ ] **Step 3: 更新持久记忆并产出最终报告**

更新 `xdag-evm-program.md`：删除「SINGLE remaining action = run mvn test on JDK21」，改记验证结论（日期、Tests run 数字、冒烟结果、修复 commit 清单如有）。最终报告必须含：测试总数/失败数、三条成功标准逐条勾选、修复 commit 列表（如有）、后续第一子项目提示（缺陷 2 批次打包，见 spec §0 路线图）。

---

## Spec 覆盖对照（自检）

| Spec 要求 | 计划落点 |
|-----------|----------|
| §1-1 mvn clean package 全绿 | Task 4 |
| §1-2 executable jar 起 devnet | Task 6 Step 1 |
| §1-3 HTTP 探活 chainId/blockNumber/clientVersion | Task 6 Step 2-3 |
| §1-3 WS eth_subscribe newHeads 返订阅 id | Task 7 |
| §2 Temurin 到 ~/Library、会话级 JAVA_HOME、不动系统 | Task 1-3（Maven 3.9.9 属达成 §1 的必要环境补充，同样装用户目录可回滚） |
| §3 失败处置两分类 + 不破坏现有数据 | 「失败处置」节 + Task 5 临时目录方案 + Step 3 验证 |
| §4 交付物（数字、commit 清单、记忆、报告） | Task 8 |
| §5 范围外 | 全计划未含 Hardhat/MetaMask/产品代码/合并 |
