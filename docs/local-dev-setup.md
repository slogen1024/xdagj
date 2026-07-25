# 本地 Devnet 测试环境

在本机跑一条私有链（`-d`），用 JSON-RPC / telnet 联调，并用极简区块浏览器查看数据。

## 1. 工具链

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
java -version   # 需 21.x
mvn -version    # 需 3.9.x
```

RandomX 建议可用内存 **> 5.5G**。

## 2. 构建

```bash
cd /Users/slogen/IdeaProjects/xdagj
mvn clean package -DskipTests
# 产物: target/xdagj-0.8.3-executable.jar
```

## 3. 钱包（首次）

```bash
java --add-opens java.base/java.nio=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -cp target/classes:target/xdagj-*-executable.jar \
     io.xdag.Bootstrap -d --account init
```

数据目录默认 `./devnet/`（相对当前工作目录）：

| 路径 | 用途 |
|------|------|
| `devnet/wallet/wallet.data` | HD 钱包 |
| `devnet/rocksdb/xdagdb` | 链数据 |

## 4. 启动节点

推荐用脚本（仓库根目录执行）：

```bash
export XDAGJ_WALLET_PASSWORD='YOUR_PWD'
./script/devnet-start.sh
```

脚本会把 `target/classes`（含 `xdag-devnet.conf`）放在 classpath 前面。  
**注意：** `*-executable.jar` 故意不打包网络 conf（见 `src/assembly/executable-jar.xml`），不要只用 `java -jar ...` 除非自己把 conf 放进 classpath。

或手动：

```bash
java --add-opens java.base/java.nio=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -cp target/classes:target/xdagj-*-executable.jar \
     io.xdag.Bootstrap -d --password 'YOUR_PWD'
```

配置源：[`src/main/resources/xdag-devnet.conf`](../src/main/resources/xdag-devnet.conf)

| 服务 | 地址 |
|------|------|
| JSON-RPC HTTP | `http://127.0.0.1:10001` |
| Telnet admin | `127.0.0.1:6001`（密码 `root`） |
| Pool WebSocket | `7001` |
| P2P | `8001` |

`node.generate.block.enable = true`：本机可自出块。

## 5. RPC smoke

```bash
curl -s http://127.0.0.1:10001/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"xdag_blockNumber","params":[],"id":1}'

curl -s http://127.0.0.1:10001/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"xdag_getStatus","params":[],"id":1}'
```

Explorer / 联调常用方法：

- `xdag_blockNumber`
- `xdag_getStatus`
- `xdag_netType`
- `xdag_getBlockByNumber`
- `xdag_getBlockByHash`
- `xdag_getBlocksByNumber`
- `xdag_getBalance`
- `xdag_getTransactionByHash`

## 6. Telnet

```bash
telnet 127.0.0.1 6001
# password: root
# 常用: stats / mainblocks / account / balance / block <hash>
```

## 7. 极简区块浏览器

```bash
cd tools/local-explorer
python3 -m http.server 8080
# 端口占用时换: python3 -m http.server 18080
# 浏览器打开 http://127.0.0.1:8080
# 默认 RPC: http://127.0.0.1:10001
```

说明见 [`tools/local-explorer/README.md`](../tools/local-explorer/README.md)。

Devnet 已配置 `rpc.http.corsOrigins = "*"`，静态页可直连 RPC。

## 8. EVM 单元测试（不依赖长跑节点）

```bash
mvn -q -Dtest='EvmSanityTest,UsdtOzBaselineTest,UsdtIssuanceTest,EvmAddressTest,RocksDbWorldStateTest' test
```

注意：`eth_*` RPC 尚未落地；浏览器当前只展示 **原生 XDAG** 数据。

## 9. 排错

| 现象 | 处理 |
|------|------|
| `Unable to locate a Java Runtime` | 检查 `JAVA_HOME` 是否指向 openjdk@21 |
| 端口占用（10001/6001/8001） | `lsof -i :10001` 后结束旧进程，或改 conf |
| 钱包密码错误 / 未 init | 先 `--account init`，再用 `--password` 或 `XDAGJ_WALLET_PASSWORD` |
| 缺少 `--add-opens` | JVM 可能 NIO 异常，必须加上两个 add-opens |
| 浏览器 CORS 失败 | 确认 conf 含 `rpc.http.corsOrigins = "*"`，用 `-cp target/classes:...` 起节点并重新 package |
| `Missing required configuration 'fund.address'` | 未加载到 conf：不要裸跑 `java -jar`，用 `./script/devnet-start.sh` |
| `NoClassDefFoundError: LoggingEventBuilder` | 需 SLF4J 2.x（`pom.xml` 已切 `log4j-slf4j2-impl`）；重新 `mvn package` |
| RandomX / OOM | 加大可用内存；脚本默认 `-Xms2g -Xmx2g`，可自行调大 |

## 10. 可选：官方 Laravel Explorer

功能更全，但依赖 PHP 8 + Composer + MySQL + cron，**不作为本机默认栈**。

- 源码：https://github.com/XDagger/explorer
- 线上：https://explorer.xdag.io/
- API：https://explorer.xdag.io/api-docs

对接要点：`.env` 中把 XdagJ RPC URL 设为 `http://127.0.0.1:10001`，并按上游 README 完成 `composer install` / `migrate` / cron。
