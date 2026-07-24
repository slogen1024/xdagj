# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

XDAGJ is a Java implementation of the XDAG cryptocurrency protocol — a DAG-based (directed acyclic graph) blockchain with built-in mining pool support. It implements PoW consensus using the RandomX algorithm.

## Build & Run Commands

**Requirements:** JDK 21, Maven 3.9.x (no Maven wrapper included)

```bash
# Build (compile + test + package)
mvn clean package

# Build without tests
mvn clean package -DskipTests

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName

# Verify license headers on all Java files
mvn license:check

# Build executable JAR only
mvn clean package -DskipTests
# Output: target/xdagj-<version>-executable.jar
```

**Excluded tests** (long-running, skipped by default in surefire): `RandomXSyncTest`, `SyncTest`, `SnapshotJTest`.

**Running the node:**
```bash
# Devnet (default)
java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar target/xdagj-*-executable.jar -d

# Testnet
java --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar target/xdagj-*-executable.jar -t
```

The `--add-opens` flags are required. Production scripts in `script/xdag.sh` add ZGC, 4GB heap, and GC logging.

## Architecture

### Entry Point and Lifecycle

`Bootstrap.main()` → `XdagCli` (parses CLI args, creates wallet/config) → `Kernel` (central orchestrator).

`Kernel` is the hub that owns and manages every subsystem's lifecycle. Startup order: channels → databases → blockchain → P2P → consensus → RPC → mining pool → telnet admin.

### Major Subsystems (all under `io.xdag`)

| Package | Role |
|---------|------|
| `core` | Blockchain model: `Block`, `BlockchainImpl`, `Address`, `XdagStats`, import/validation logic |
| `db` | Persistence via RocksDB (6 column families: INDEX, BLOCK, TIME, TXHISTORY, ADDRESS, ORPHANIND, SNAPSHOT). Optional MySQL for extended tx history |
| `net` | Netty-based P2P: `PeerServer` (inbound), `PeerClient` (outbound), `ChannelManager`, `NodeManager`, 24 message types under `net/message/` |
| `consensus` | `XdagPow` (mining), `RandomX` (PoW algorithm via JNI), `XdagSync`/`SyncManager` (chain sync) |
| `rpc` | JSON-RPC 2.0 server (Netty HTTP, default port 10001). `XdagApi` interface, `XdagApiImpl` implementation |
| `pool` | Mining pool: `WebSocketServer` (port 7001), `PoolAwardManager` (reward distribution) |
| `cli` | `XdagCli` (arg parsing), `Shell` (interactive REPL), `TelnetServer` (remote admin, port 6001) |
| `config` | HOCON-based config via Typesafe Config. `MainnetConfig`/`TestnetConfig`/`DevnetConfig` extend `AbstractConfig` |
| `listener` | Event notification interfaces for blockchain events |
| `utils` | Encoding, time, byte manipulation utilities |

### Network State Machine

The node transitions through states: `INIT → KEYS → LOAD → WAIT/WTST/WDST → CONN/CTST/CDST → SYNC/STST/SDST`. The state suffix indicates the network (none=mainnet, TST=testnet, DST=devnet).

### Configuration

Network configs are HOCON files in `src/main/resources/`: `xdag-mainnet.conf`, `xdag-testnet.conf`, `xdag-devnet.conf`. Key sections: `admin.telnet`, `pool`, `node`, `rpc`, `randomx`.

### Key Dependencies

- **Netty 4.2.x** — all networking (P2P, RPC, WebSocket pool, telnet)
- **RocksDB** — primary persistent storage
- **xdagj-native-randomx** — JNI binding to RandomX mining algorithm
- **xdagj-crypto** — custom cryptography (EC keys, addresses)
- **Lombok** — used throughout for `@Getter`, `@Setter`, `@Slf4j`
- **JUnit 4 + Mockito** — test framework (not JUnit 5)

## Code Style

- **Indentation:** 4 spaces (no tabs)
- **Line length:** 120 characters
- **No wildcard imports** (IntelliJ demand threshold set to 99)
- **MIT license header required** on all Java source files (enforced by `license-maven-plugin` during `mvn verify`)
- Formatter configs in `misc/code-style/` for both Eclipse and IntelliJ

## Git Workflow

Gitflow model:
- `master` — production (no direct commits)
- `develop` — main development branch
- `feature/*` — feature branches off develop
- `release/*`, `hotfix/*` — standard gitflow branches
- PRs target `master` or `develop`
