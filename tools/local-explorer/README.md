# XDAG Local Explorer

极简静态区块浏览器，直连本机 XDAGJ JSON-RPC（原生 `xdag_*`，无 `eth_*`）。

## 前置

1. 本地 devnet 节点已启动，RPC 在 `http://127.0.0.1:10001`
2. conf 含 `rpc.http.corsOrigins = "*"`（见 `xdag-devnet.conf`）

## 启动

```bash
cd tools/local-explorer
python3 -m http.server 8080
# 若 8080 占用: python3 -m http.server 18080
```

浏览器打开：http://127.0.0.1:8080 （或对应端口）

页面顶部可改 RPC URL。

## 功能

| UI | RPC |
|----|-----|
| Network 状态 | `xdag_getStatus`, `xdag_blockNumber`, `xdag_netType` |
| Latest Main Blocks | `xdag_getBlocksByNumber(["20"])` |
| 点击行 / Lookup 高度 | `xdag_getBlockByNumber` |
| Lookup hash | `xdag_getBlockByHash` / `xdag_getTransactionByHash` |
| Lookup Base58 | `xdag_getBalance` |

## 相关文档

- [本地 Devnet 搭建](../../docs/local-dev-setup.md)
- 官方完整 explorer（可选）：https://github.com/XDagger/explorer
