# XDAG EVM Sub-project A — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove a faithful USDT (real Tether) and a clean OpenZeppelin ERC-20 deploy and run every token operation correctly on XDAG's embedded Hyperledger Besu EVM (Shanghai), via a comprehensive JUnit suite.

**Architecture:** A thin `io.xdag.evm` layer wraps the Besu EVM **library** (`besu-evm:26.5.0`). `XdagEvmExecutor` builds a Shanghai `EVM` via `MainnetEVMs`, drives a `MessageFrame` through `MessageCallProcessor`/`ContractCreationProcessor`, and returns a result (success/returnData/gasUsed/logs/revertReason/createdContract). State is Besu's in-memory `SimpleWorld` (`WorldUpdater`) — the exact seam a RocksDB-backed `WorldUpdater` slots into in Sub-project B with no executor change.

**Tech Stack:** Java 21, Maven, JUnit 4 + Mockito (project default), Hyperledger Besu EVM `26.5.0` (Apache-2.0), Apache/Consensys Tuweni `Bytes`, Bouncy Castle Keccak-256.

---

## ⚠️ Execution mode for THIS plan (no-build session)

- All **`Run: mvn …`** steps are marked **[DEFERRED → verification gate]**. Do them later, after `JAVA_HOME=<jdk21>` + Maven 3.9 are installed. The TDD ordering (test first) is preserved so the gate is a clean red→green.
- **Bytecode fixtures** (`usdt.bin`, `usdt_oz.bin`) require `solc`/`docker` (or an Etherscan fetch) — produced at the gate. The `.sol` sources and the exact generation commands are written now (Tasks 7–8). Until the gate, the USDT tests reference `.bin` files that do not yet exist and therefore cannot run — this is expected.
- **API caveat:** code is written against the **verified** besu-evm 26.5.0 API (class names confirmed via `javap`). A handful of low-level wiring details (`BlockValues`/`BlockHashLookup` exact signatures, `Address.contractAddress`, `MessageFrame.State`/`getLogs` names, child-updater commit semantics) are marked `// CONFIRM 26.5.0` — verify against the javadoc when the build runs; adjust if needed.
- Commits land on `dev-evm` only (no push, no PR) unless you ask otherwise.

---

## File Structure

**Main (`src/main/java/io/xdag/evm/`):**
- `EvmConfig.java` — immutable config: `EvmSpecVersion fork` (SHANGHAI), `BigInteger chainId`; factory `EvmConfig.devnet()`.
- `XdagExecutionResult.java` — result record: `success`, `returnData`, `gasUsed`, `logs`, `revertReason`, `createdContract`.
- `XdagEvmExecutor.java` — builds the Shanghai EVM + processors once; `deploy(...)` and `call(...)`; private frame loop.

**Test (`src/test/java/io/xdag/evm/`):**
- `EvmTestBase.java` — `SimpleWorld` + `XdagEvmExecutor`; fund/premine; `deploy`/`call` convenience; address constants.
- `Abi.java` — `selector`, `encodeAddress`, `encodeUint256`, `decodeUint256`, `decodeAddress`, `concat`.
- `EvmSanityTest.java` — PUSH0 runs; CREATE+CALL; REVERT reason.
- `UsdtOzBaselineTest.java` — standard OZ ERC-20 (bool returns + events).
- `UsdtIssuanceTest.java` — real Tether full lifecycle.

**Resources (`src/test/resources/solidity/`):**
- `README.md` — how each `.bin` was produced.
- `usdt.sol` + `usdt.bin` — real Tether (solc 0.4.18, 6 decimals).
- `usdt_oz.sol` + `usdt_oz.bin` — OZ ERC-20 named "USDT", 6 decimals.

**Build:** `pom.xml` — besu repo + `besu-evm`/`besu-datatypes` deps; `license-maven-plugin` excludes `src/test/resources/solidity/**`.

---

## Task 1: Add Besu EVM dependency + repo to pom.xml

**Files:**
- Modify: `pom.xml` (`<repositories>` and `<dependencies>` sections)

- [ ] **Step 1: Add the Besu Artifactory repository** (inside the existing `<repositories>` element; `develop` already declares this URL for other deps, so add only if absent):

```xml
<repository>
    <id>besu-maven</id>
    <name>Hyperledger Besu Artifactory</name>
    <url>https://hyperledger.jfrog.io/artifactory/besu-maven/</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
</repository>
```

- [ ] **Step 2: Add the dependencies** (inside `<dependencies>`):

```xml
<!-- Hyperledger Besu EVM (embeddable library). 26.5.0 is the latest JDK-21-safe
     release; 26.6.0+ is Java-25 bytecode and throws UnsupportedClassVersionError. -->
<dependency>
    <groupId>org.hyperledger.besu</groupId>
    <artifactId>besu-evm</artifactId>
    <version>26.5.0</version>
</dependency>
<dependency>
    <groupId>org.hyperledger.besu</groupId>
    <artifactId>besu-datatypes</artifactId>
    <version>26.5.0</version>
</dependency>
```

- [ ] **Step 3: Resolve dependencies** — **[DEFERRED → verification gate]**

Run: `mvn -q dependency:resolve -Dincludes=org.hyperledger.besu`
Expected: `besu-evm-26.5.0.jar` and `besu-datatypes-26.5.0.jar` download from the besu-maven repo with no errors. If a `org.apache.tuweni`/`io.tmio` convergence error appears, add `<exclusions>` for the stale tuweni coordinate (keep `io.consensys.tuweni`).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build(evm): add Hyperledger Besu EVM 26.5.0 dependency"
```

---

## Task 2: EvmConfig

**Files:**
- Create: `src/main/java/io/xdag/evm/EvmConfig.java`

- [ ] **Step 1: Write `EvmConfig`** (the chainId is provisional/devnet — only the `CHAINID` opcode reads it in Sub-project A; the final mainnet/testnet/devnet triple is decided in B/C):

```java
/*
 * The MIT License (MIT)
 * <KEEP the standard XDAG MIT header used across the repo>
 */
package io.xdag.evm;

import java.math.BigInteger;
import org.hyperledger.besu.evm.EvmSpecVersion;

/** Immutable EVM execution configuration: target fork + chainId. */
public final class EvmConfig {

    /** Provisional devnet chainId; reserve final values via ethereum-lists/chains in Sub-project B/C. */
    public static final BigInteger DEVNET_CHAIN_ID = BigInteger.valueOf(0xCAFE); // 51966 (provisional)

    private final EvmSpecVersion fork;
    private final BigInteger chainId;

    public EvmConfig(EvmSpecVersion fork, BigInteger chainId) {
        this.fork = fork;
        this.chainId = chainId;
    }

    public static EvmConfig devnet() {
        return new EvmConfig(EvmSpecVersion.SHANGHAI, DEVNET_CHAIN_ID);
    }

    public EvmSpecVersion fork() { return fork; }
    public BigInteger chainId() { return chainId; }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/xdag/evm/EvmConfig.java
git commit -m "feat(evm): add EvmConfig (Shanghai + chainId)"
```

---

## Task 3: XdagExecutionResult

**Files:**
- Create: `src/main/java/io/xdag/evm/XdagExecutionResult.java`

- [ ] **Step 1: Write the result type** (a record carrying everything tests/RPC need):

```java
/* MIT header */
package io.xdag.evm;

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.log.Log; // CONFIRM 26.5.0 package of Log

/** Outcome of one EVM message (deploy or call). */
public record XdagExecutionResult(
        boolean success,
        Bytes returnData,
        long gasUsed,
        List<Log> logs,
        Optional<Bytes> revertReason,
        Optional<Address> createdContract) {

    public static XdagExecutionResult failure(long gasUsed, Optional<Bytes> revertReason) {
        return new XdagExecutionResult(false, Bytes.EMPTY, gasUsed, List.of(), revertReason, Optional.empty());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/xdag/evm/XdagExecutionResult.java
git commit -m "feat(evm): add XdagExecutionResult"
```

---

## Task 4: EvmSanityTest (failing) — prove Shanghai/PUSH0, CREATE+CALL, REVERT

**Files:**
- Create (test-support, needed first): `src/test/java/io/xdag/evm/EvmTestBase.java`
- Create (test-support): `src/test/java/io/xdag/evm/Abi.java`
- Create (test): `src/test/java/io/xdag/evm/EvmSanityTest.java`

- [ ] **Step 1: Write `Abi` test helper**

```java
/* MIT header */
package io.xdag.evm;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.bouncycastle.jcajce.provider.digest.Keccak;

/** Minimal ABI encode/decode for tests (no web3j dependency needed). */
public final class Abi {
    private Abi() {}

    /** 4-byte function selector = keccak256(signature)[0:4]. True Keccak-256, not SHA3. */
    public static Bytes selector(String signature) {
        byte[] h = new Keccak.Digest256().digest(signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return Bytes.wrap(h, 0, 4);
    }

    /** Left-pad an address to a 32-byte ABI word. */
    public static Bytes encodeAddress(Address a) {
        return Bytes.concatenate(Bytes.wrap(new byte[12]), a); // Address is 20 bytes
    }

    /** Encode a uint256 as a 32-byte big-endian ABI word. */
    public static Bytes encodeUint256(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        int src = Math.max(0, b.length - 32);
        int len = Math.min(b.length, 32);
        System.arraycopy(b, src, out, 32 - len, len);
        return Bytes.wrap(out);
    }

    public static BigInteger decodeUint256(Bytes returnData) {
        return new BigInteger(1, returnData.slice(0, 32).toArray());
    }

    public static Address decodeAddress(Bytes returnData) {
        return Address.wrap(returnData.slice(12, 20));
    }

    public static Bytes concat(Bytes... parts) { return Bytes.concatenate(parts); }
}
```

- [ ] **Step 2: Write `EvmTestBase`** (in-memory world + executor; deploy/call convenience):

```java
/* MIT header */
package io.xdag.evm;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;          // CONFIRM 26.5.0 (verified present)
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/** Base for EVM tests: a SimpleWorld + executor, with funding + deploy/call helpers. */
public abstract class EvmTestBase {

    protected SimpleWorld world;
    protected XdagEvmExecutor evm;

    // Deterministic actors
    protected final Address owner   = Address.fromHexString("0x23a6049381fd2cfb0661d9de206613b83d53d7df");
    protected final Address alice   = Address.fromHexString("0x1111111111111111111111111111111111111111");
    protected final Address bob     = Address.fromHexString("0x2222222222222222222222222222222222222222");
    protected final long gas = 8_000_000L;

    protected void setUp() {
        world = new SimpleWorld();
        evm = new XdagEvmExecutor(EvmConfig.devnet());
        fund(owner, Wei.fromEth(1000));
        fund(alice, Wei.fromEth(1000));
        fund(bob, Wei.fromEth(1000));
    }

    protected void fund(Address a, Wei amount) {
        WorldUpdater u = world.updater();
        MutableAccount acct = u.getOrCreate(a);   // CONFIRM 26.5.0 default method
        acct.setBalance(amount);
        u.commit();
    }

    protected XdagExecutionResult deploy(Address sender, Bytes initCodeWithArgs) {
        return evm.deploy(world.updater(), sender, initCodeWithArgs, Wei.ZERO, gas);
    }

    protected XdagExecutionResult call(Address sender, Address to, Bytes callData) {
        return evm.call(world.updater(), sender, to, callData, Wei.ZERO, gas);
    }

    protected BigInteger callUint(Address sender, Address to, Bytes callData) {
        return Abi.decodeUint256(call(sender, to, callData).returnData());
    }
}
```

- [ ] **Step 3: Write the failing sanity test**

```java
/* MIT header */
package io.xdag.evm;

import static org.junit.Assert.*;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.Before;
import org.junit.Test;

public class EvmSanityTest extends EvmTestBase {

    @Before public void before() { setUp(); }

    // Runtime code: PUSH0; PUSH1 0x00; MSTORE; PUSH1 0x20; PUSH1 0x00; RETURN
    // PUSH0 (0x5f) is only valid on Shanghai+; proves the fork is active.
    @Test public void push0_runs_on_shanghai() {
        Bytes runtime = Bytes.fromHexString("0x5f5f5260205ff3"); // CONFIRM opcode bytes
        // place code at bob and call it
        evm.setCodeForTest(world.updater(), bob, runtime); // helper added in Task 5
        XdagExecutionResult r = call(owner, bob, Bytes.EMPTY);
        assertTrue("PUSH0 program should succeed on Shanghai", r.success());
        assertEquals(BigInteger.ZERO, Abi.decodeUint256(r.returnData()));
    }

    @Test public void revert_surfaces_reason() {
        // PUSH1 0x00 PUSH1 0x00 REVERT  -> empty revert
        Bytes runtime = Bytes.fromHexString("0x60006000fd");
        evm.setCodeForTest(world.updater(), bob, runtime);
        XdagExecutionResult r = call(owner, bob, Bytes.EMPTY);
        assertFalse(r.success());
    }
}
```

- [ ] **Step 4: Run — [DEFERRED → verification gate]**

Run: `mvn -q -Dtest=EvmSanityTest test`
Expected: FAIL to compile (`XdagEvmExecutor` not yet implemented).

---

## Task 5: Implement XdagEvmExecutor (make Task 4 pass)

**Files:**
- Create: `src/main/java/io/xdag/evm/XdagEvmExecutor.java`

- [ ] **Step 1: Implement the executor** against the verified low-level Besu API (gives access to logs/gasUsed/state/revertReason that the fluent facade hides):

```java
/* MIT header */
package io.xdag.evm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;   // NOTE: .internal., verified
import org.hyperledger.besu.evm.precompile.MainnetPrecompiledContracts;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/** Embeds the Besu EVM (Shanghai) and runs deploy/call messages over a WorldUpdater. */
public final class XdagEvmExecutor {

    private final EVM evm;
    private final MessageCallProcessor callProcessor;
    private final ContractCreationProcessor creationProcessor;

    public XdagEvmExecutor(EvmConfig config) {
        // Shanghai adds no precompiles beyond Istanbul; use the Istanbul registry.
        this.evm = MainnetEVMs.shanghai(config.chainId(), EvmConfiguration.DEFAULT); // CONFIRM overload
        PrecompileContractRegistry precompiles =
                MainnetPrecompiledContracts.istanbul(evm.getGasCalculator());
        this.callProcessor = new MessageCallProcessor(evm, precompiles);
        this.creationProcessor = new ContractCreationProcessor(evm, true, List.of(), 1L); // CONFIRM args
    }

    /** Deploy: initCode (creation bytecode + ABI-encoded ctor args) -> contract with runtime code. */
    public XdagExecutionResult deploy(WorldUpdater parent, Address sender, Bytes initCode, Wei value, long gasLimit) {
        WorldUpdater tx = parent.updater();
        MutableAccount senderAcct = tx.getOrCreate(sender);
        long nonce = senderAcct.getNonce();
        Address contract = Address.contractAddress(sender, nonce); // CONFIRM helper name
        senderAcct.setNonce(nonce + 1);

        MessageFrame frame = baseFrame(MessageFrame.Type.CONTRACT_CREATION, tx, sender, contract, contract,
                initCode, Bytes.EMPTY, value, gasLimit);
        runToHalt(frame);

        XdagExecutionResult r = collect(frame, gasLimit, Optional.of(contract));
        if (r.success()) tx.commit(); else tx.revert();
        return r;
    }

    /** Call a function on an existing contract. */
    public XdagExecutionResult call(WorldUpdater parent, Address sender, Address to, Bytes callData, Wei value, long gasLimit) {
        WorldUpdater tx = parent.updater();
        MutableAccount toAcct = tx.getAccount(to);
        Bytes code = (toAcct == null) ? Bytes.EMPTY : toAcct.getCode();

        MessageFrame frame = baseFrame(MessageFrame.Type.MESSAGE_CALL, tx, sender, to, to,
                code, callData, value, gasLimit);
        runToHalt(frame);

        XdagExecutionResult r = collect(frame, gasLimit, Optional.empty());
        if (r.success()) tx.commit(); else tx.revert();
        return r;
    }

    /** Test-only: place runtime code directly at an address. */
    public void setCodeForTest(WorldUpdater parent, Address addr, Bytes runtimeCode) {
        WorldUpdater u = parent.updater();
        MutableAccount a = u.getOrCreate(addr);
        a.setCode(runtimeCode);
        u.commit();
        parent.commit();
    }

    private MessageFrame baseFrame(MessageFrame.Type type, WorldUpdater updater, Address sender,
                                   Address address, Address contract, Bytes code, Bytes input,
                                   Wei value, long gas) {
        return MessageFrame.builder()
                .type(type)
                .worldUpdater(updater)
                .initialGas(gas)
                .address(address)
                .contract(contract)
                .sender(sender)
                .originator(sender)
                .gasPrice(Wei.ZERO)
                .blobGasPrice(Wei.ZERO)
                .value(value)
                .apparentValue(value)
                .inputData(input)
                .code(new Code(code))                              // CONFIRM: concrete Code(Bytes) in 26.5.0
                .blockValues(SHANGHAI_BLOCK)                        // see field below
                .miningBeneficiary(Address.ZERO)
                .blockHashLookup((frame, n) -> org.hyperledger.besu.datatypes.Hash.ZERO) // CONFIRM signature
                .completer(f -> {})
                .build();
    }

    private void runToHalt(MessageFrame initial) {
        Deque<MessageFrame> stack = initial.getMessageFrameStack();
        while (!stack.isEmpty()) {
            MessageFrame f = stack.peekFirst();
            switch (f.getType()) {
                case CONTRACT_CREATION -> creationProcessor.process(f, OperationTracer.NO_TRACING);
                case MESSAGE_CALL -> callProcessor.process(f, OperationTracer.NO_TRACING);
            }
        }
    }

    private XdagExecutionResult collect(MessageFrame frame, long gasLimit, Optional<Address> created) {
        boolean success = frame.getState() == MessageFrame.State.COMPLETED_SUCCESS; // CONFIRM enum
        long gasUsed = gasLimit - frame.getRemainingGas();
        return new XdagExecutionResult(
                success,
                frame.getReturnData(),
                gasUsed,
                frame.getLogs(),                  // CONFIRM getLogs()
                frame.getRevertReason(),          // Optional<Bytes>
                success ? created : Optional.empty());
    }

    // Minimal block context for token execution (number/timestamp/gaslimit; no baseFee -> legacy gas).
    private static final BlockValues SHANGHAI_BLOCK = new BlockValues() {
        // CONFIRM the exact BlockValues interface methods in 26.5.0 and implement each.
        @Override public long getNumber() { return 1L; }
        @Override public long getTimestamp() { return 1_700_000_000L; }
        @Override public long getGasLimit() { return 30_000_000L; }
    };
}
```

- [ ] **Step 2: Run — [DEFERRED → verification gate]**

Run: `mvn -q -Dtest=EvmSanityTest test`
Expected: PASS (PUSH0 succeeds → Shanghai active; REVERT returns `success=false`). If `BlockValues`/`blockHashLookup`/`State`/`getLogs` names differ, adjust per the 26.5.0 javadoc (this is the main compile-time fixup point).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/xdag/evm/XdagEvmExecutor.java \
        src/test/java/io/xdag/evm/Abi.java \
        src/test/java/io/xdag/evm/EvmTestBase.java \
        src/test/java/io/xdag/evm/EvmSanityTest.java
git commit -m "feat(evm): XdagEvmExecutor over Besu + sanity tests"
```

---

## Task 6: OZ ERC-20 fixture source + README

**Files:**
- Create: `src/test/resources/solidity/usdt_oz.sol`
- Create: `src/test/resources/solidity/README.md`

- [ ] **Step 1: Write `usdt_oz.sol`** — a self-contained OZ-style ERC-20 named "USDT", **6 decimals**, with `mint` (owner-only), standard bool returns + events:

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract UsdtOz {
    string public name = "Tether USD";
    string public symbol = "USDT";
    uint8 public constant decimals = 6;
    uint256 public totalSupply;
    address public owner;
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

    constructor() { owner = msg.sender; }

    function mint(address to, uint256 amount) external returns (bool) {
        require(msg.sender == owner, "only owner");
        totalSupply += amount;
        balanceOf[to] += amount;
        emit Transfer(address(0), to, amount);
        return true;
    }

    function transfer(address to, uint256 amount) external returns (bool) {
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
        emit Transfer(msg.sender, to, amount);
        return true;
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        allowance[from][msg.sender] -= amount;
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        emit Transfer(from, to, amount);
        return true;
    }
}
```

- [ ] **Step 2: Write the fixtures `README.md`** (records exact reproduction commands):

````markdown
# Solidity test fixtures

Each `*.bin` is the **single-line hex creation bytecode** (no `0x`, no trailing
newline) loaded by tests. Regenerate offline; the Maven build does NOT run solc.

## usdt_oz.bin  (clean OZ ERC-20, 6 decimals — standard bool returns)
```bash
docker run --rm -v "$PWD":/src ethereum/solc:0.8.26 \
  --bin --optimize --evm-version shanghai -o /src/out --overwrite /src/usdt_oz.sol
tr -d '\n' < out/UsdtOz.bin > usdt_oz.bin
```

## usdt.bin  (real Tether — solc 0.4.18, 6 decimals, non-standard)
```bash
docker run --rm -v "$PWD":/src ethereum/solc:0.4.18 \
  --bin --optimize -o /src/out --overwrite /src/usdt.sol
tr -d '\n' < out/TetherToken.bin > usdt.bin
```
Alternative (no solc): fetch the deployed contract-creation input of
0xdac17f958d2ee523a2206206994597c13d831ec7 from Etherscan and strip the
trailing constructor args, then append your own args at deploy time.
````

- [ ] **Step 3: Generate `usdt_oz.bin` — [DEFERRED → verification gate]**

Run the `usdt_oz.bin` command above. Expected: a hex file ~1–3 KB.

- [ ] **Step 4: Commit** (the `.sol` + README now; `.bin` added at the gate)

```bash
git add src/test/resources/solidity/usdt_oz.sol src/test/resources/solidity/README.md
git commit -m "test(evm): add OZ USDT fixture source + fixtures README"
```

---

## Task 7: UsdtOzBaselineTest (standard ERC-20 path)

**Files:**
- Create: `src/test/java/io/xdag/evm/UsdtOzBaselineTest.java`

- [ ] **Step 1: Write the test** (deploy → mint → transfer/approve/transferFrom; assert **bool** returns, balances, decimals, events):

```java
/* MIT header */
package io.xdag.evm;

import static org.junit.Assert.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Before;
import org.junit.Test;

public class UsdtOzBaselineTest extends EvmTestBase {

    private Address usdt;
    private static final BigInteger ONE_USDT = BigInteger.valueOf(1_000_000L); // 6 decimals

    @Before public void before() throws IOException { setUp(); deployToken(); }

    private Bytes bin(String f) throws IOException {
        String hex = new String(Files.readAllBytes(
            Paths.get("src/test/resources/solidity/" + f)), StandardCharsets.US_ASCII).trim();
        return Bytes.fromHexString(hex.startsWith("0x") ? hex : "0x" + hex);
    }

    private void deployToken() throws IOException {
        XdagExecutionResult r = deploy(owner, bin("usdt_oz.bin")); // no ctor args
        assertTrue("deploy OZ USDT", r.success());
        usdt = r.createdContract().orElseThrow();
    }

    @Test public void decimals_is_six() {
        assertEquals(BigInteger.valueOf(6), callUint(owner, usdt, Abi.selector("decimals()")));
    }

    @Test public void mint_then_transfer_updates_balances_and_returns_bool() {
        // mint(owner, 1000 USDT)
        XdagExecutionResult m = call(owner, usdt, Abi.concat(
            Abi.selector("mint(address,uint256)"),
            Abi.encodeAddress(owner),
            Abi.encodeUint256(ONE_USDT.multiply(BigInteger.valueOf(1000)))));
        assertTrue(m.success());
        assertEquals(BigInteger.ONE, Abi.decodeUint256(m.returnData())); // standard bool == 1

        // transfer(alice, 250 USDT)
        XdagExecutionResult t = call(owner, usdt, Abi.concat(
            Abi.selector("transfer(address,uint256)"),
            Abi.encodeAddress(alice),
            Abi.encodeUint256(ONE_USDT.multiply(BigInteger.valueOf(250)))));
        assertTrue(t.success());
        assertEquals(BigInteger.ONE, Abi.decodeUint256(t.returnData()));
        assertFalse("standard ERC-20 emits a Transfer log", t.logs().isEmpty());

        // balanceOf(alice) == 250 USDT
        BigInteger bal = callUint(alice, usdt, Abi.concat(
            Abi.selector("balanceOf(address)"), Abi.encodeAddress(alice)));
        assertEquals(ONE_USDT.multiply(BigInteger.valueOf(250)), bal);
    }

    @Test public void approve_and_transferFrom() {
        call(owner, usdt, Abi.concat(Abi.selector("mint(address,uint256)"),
            Abi.encodeAddress(owner), Abi.encodeUint256(ONE_USDT.multiply(BigInteger.valueOf(100)))));
        // owner approves bob for 40 USDT
        XdagExecutionResult a = call(owner, usdt, Abi.concat(Abi.selector("approve(address,uint256)"),
            Abi.encodeAddress(bob), Abi.encodeUint256(ONE_USDT.multiply(BigInteger.valueOf(40)))));
        assertTrue(a.success());
        // bob moves 30 USDT owner->alice
        XdagExecutionResult tf = call(bob, usdt, Abi.concat(Abi.selector("transferFrom(address,address,uint256)"),
            Abi.encodeAddress(owner), Abi.encodeAddress(alice), Abi.encodeUint256(ONE_USDT.multiply(BigInteger.valueOf(30)))));
        assertTrue(tf.success());
        // allowance now 10 USDT
        BigInteger allow = callUint(owner, usdt, Abi.concat(Abi.selector("allowance(address,address)"),
            Abi.encodeAddress(owner), Abi.encodeAddress(bob)));
        assertEquals(ONE_USDT.multiply(BigInteger.valueOf(10)), allow);
    }
}
```

- [ ] **Step 2: Run — [DEFERRED → verification gate]**

Run: `mvn -q -Dtest=UsdtOzBaselineTest test`
Expected: PASS once `usdt_oz.bin` exists. (Until the gate, this test cannot run — by design.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/xdag/evm/UsdtOzBaselineTest.java
git commit -m "test(evm): OZ USDT baseline (standard ERC-20 path)"
```

---

## Task 8: Real Tether fixture source

**Files:**
- Create: `src/test/resources/solidity/usdt.sol`

- [ ] **Step 1: Vendor the real `TetherToken.sol`** from `tethercoin/USDT` (pragma `^0.4.17`; keep the upstream Apache-2.0 attribution header intact — do NOT force the MIT header). The full source (Ownable, ERC20Basic/ERC20 with **void** transfer/approve, BasicToken, StandardToken, Pausable, BlackList, SafeMath, TetherToken with `issue`/`redeem`/`setParams`/`deprecate`) is ~400 lines; copy it verbatim from the upstream repo. Confirm it declares `constructor TetherToken(uint _initialSupply, string _name, string _symbol, uint _decimals)` and `decimals = 6` in tests via the constructor arg.

- [ ] **Step 2: Generate `usdt.bin` — [DEFERRED → verification gate]** using the solc 0.4.18 command in the fixtures README.

- [ ] **Step 3: Commit** (`.sol` now; `.bin` at the gate)

```bash
git add src/test/resources/solidity/usdt.sol
git commit -m "test(evm): vendor real TetherToken.sol fixture (Apache-2.0)"
```

---

## Task 9: UsdtIssuanceTest — the headline proof (real Tether lifecycle)

**Files:**
- Create: `src/test/java/io/xdag/evm/UsdtIssuanceTest.java`

- [ ] **Step 1: Write the full-lifecycle test.** Constructor args are appended to the creation bytecode (CREATE semantics). The real Tether `transfer/approve/transferFrom` return **no data** → assert via receipt success + state reads, and assert empty return data to lock in the quirk.

```java
/* MIT header */
package io.xdag.evm;

import static org.junit.Assert.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Before;
import org.junit.Test;

public class UsdtIssuanceTest extends EvmTestBase {

    private Address usdt;
    private static final BigInteger ONE = BigInteger.valueOf(1_000_000L); // 6 decimals

    @Before public void before() throws IOException { setUp(); deployUsdt(); }

    private Bytes bin(String f) throws IOException {
        String hex = new String(Files.readAllBytes(
            Paths.get("src/test/resources/solidity/" + f)), StandardCharsets.US_ASCII).trim();
        return Bytes.fromHexString(hex.startsWith("0x") ? hex : "0x" + hex);
    }

    private void deployUsdt() throws IOException {
        // TetherToken(_initialSupply=0, _name="Tether USD", _symbol="USDT", _decimals=6)
        Bytes args = Abi.concat(
            Abi.encodeUint256(BigInteger.ZERO),
            Abi.encodeUint256(BigInteger.valueOf(0x80)),   // offset of _name (dynamic)
            Abi.encodeUint256(BigInteger.valueOf(0xC0)),   // offset of _symbol (dynamic)  // CONFIRM offsets
            Abi.encodeUint256(BigInteger.valueOf(6)),
            encodeString("Tether USD"),
            encodeString("USDT"));
        XdagExecutionResult r = deploy(owner, Abi.concat(bin("usdt.bin"), args));
        assertTrue("deploy real Tether", r.success());
        usdt = r.createdContract().orElseThrow();
        assertEquals(BigInteger.valueOf(6), callUint(owner, usdt, Abi.selector("decimals()")));
    }

    // ABI dynamic string = 32-byte length + right-padded data
    private static Bytes encodeString(String s) {
        byte[] d = s.getBytes(StandardCharsets.US_ASCII);
        int padded = ((d.length + 31) / 32) * 32;
        byte[] body = new byte[padded];
        System.arraycopy(d, 0, body, 0, d.length);
        return Abi.concat(Abi.encodeUint256(BigInteger.valueOf(d.length)), Bytes.wrap(body));
    }

    private BigInteger balanceOf(Address a) {
        return callUint(owner, usdt, Abi.concat(Abi.selector("balanceOf(address)"), Abi.encodeAddress(a)));
    }
    private BigInteger totalSupply() {
        return callUint(owner, usdt, Abi.selector("totalSupply()"));
    }

    @Test public void issue_mints_to_owner() {
        XdagExecutionResult r = call(owner, usdt, Abi.concat(
            Abi.selector("issue(uint256)"), Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(1_000_000))))); // 1M USDT
        assertTrue("issue() == USDT can be issued on XDAG", r.success());
        assertEquals(ONE.multiply(BigInteger.valueOf(1_000_000)), totalSupply());
        assertEquals(ONE.multiply(BigInteger.valueOf(1_000_000)), balanceOf(owner));
    }

    @Test public void transfer_has_no_return_value_but_moves_balance() {
        call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"),
            Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(1000)))));
        XdagExecutionResult t = call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
            Abi.encodeAddress(alice), Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(250)))));
        assertTrue(t.success());
        assertEquals("real USDT transfer returns NO data", 0, t.returnData().size()); // the quirk
        assertEquals(ONE.multiply(BigInteger.valueOf(250)), balanceOf(alice));
        assertFalse("Transfer event emitted", t.logs().isEmpty());
    }

    @Test public void approve_allowance_transferFrom() {
        call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"),
            Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(100)))));
        call(owner, usdt, Abi.concat(Abi.selector("approve(address,uint256)"),
            Abi.encodeAddress(bob), Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(40)))));
        XdagExecutionResult tf = call(bob, usdt, Abi.concat(Abi.selector("transferFrom(address,address,uint256)"),
            Abi.encodeAddress(owner), Abi.encodeAddress(alice), Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(30)))));
        assertTrue(tf.success());
        BigInteger allow = callUint(owner, usdt, Abi.concat(Abi.selector("allowance(address,address)"),
            Abi.encodeAddress(owner), Abi.encodeAddress(bob)));
        assertEquals(ONE.multiply(BigInteger.valueOf(10)), allow);
        assertEquals(ONE.multiply(BigInteger.valueOf(30)), balanceOf(alice));
    }

    @Test public void fee_mechanism_credits_owner() {
        call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"),
            Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(1000)))));
        // setParams(basisPointsRate=10 (=0.1%), maxFee=5)
        XdagExecutionResult sp = call(owner, usdt, Abi.concat(Abi.selector("setParams(uint256,uint256)"),
            Abi.encodeUint256(BigInteger.valueOf(10)), Abi.encodeUint256(BigInteger.valueOf(5))));
        assertTrue(sp.success());
        BigInteger ownerBefore = balanceOf(owner);
        call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
            Abi.encodeAddress(alice), Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(100)))));
        // fee = min(100 USDT * 10/10000, maxFee). Assert alice got (amount-fee) and owner kept the fee.
        BigInteger aliceBal = balanceOf(alice);
        assertTrue("fee deducted from recipient amount", aliceBal.compareTo(ONE.multiply(BigInteger.valueOf(100))) < 0);
        assertTrue("owner credited the fee", balanceOf(owner).compareTo(ownerBefore.subtract(ONE.multiply(BigInteger.valueOf(100)))) > 0);
    }

    @Test public void blacklist_blocks_and_destroys() {
        call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"),
            Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(10)))));
        call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
            Abi.encodeAddress(alice), Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(10)))));
        // blacklist alice, then transfer FROM alice must fail
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("addBlackList(address)"), Abi.encodeAddress(alice))).success());
        XdagExecutionResult blocked = call(alice, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
            Abi.encodeAddress(bob), Abi.encodeUint256(ONE)));
        assertFalse("blacklisted sender cannot transfer", blocked.success());
        // destroyBlackFunds(alice) zeroes balance + reduces supply
        BigInteger supplyBefore = totalSupply();
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("destroyBlackFunds(address)"), Abi.encodeAddress(alice))).success());
        assertEquals(BigInteger.ZERO, balanceOf(alice));
        assertEquals(supplyBefore.subtract(ONE.multiply(BigInteger.valueOf(10))), totalSupply());
    }

    @Test public void pause_blocks_transfers() {
        call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"), Abi.encodeUint256(ONE)));
        assertTrue(call(owner, usdt, Abi.selector("pause()")).success());
        XdagExecutionResult t = call(owner, usdt, Abi.concat(Abi.selector("transfer(address,uint256)"),
            Abi.encodeAddress(alice), Abi.encodeUint256(ONE)));
        assertFalse("paused contract blocks transfer", t.success());
        assertTrue(call(owner, usdt, Abi.selector("unpause()")).success());
    }

    @Test public void redeem_burns_supply() {
        call(owner, usdt, Abi.concat(Abi.selector("issue(uint256)"),
            Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(500)))));
        BigInteger before = totalSupply();
        assertTrue(call(owner, usdt, Abi.concat(Abi.selector("redeem(uint256)"),
            Abi.encodeUint256(ONE.multiply(BigInteger.valueOf(200))))).success());
        assertEquals(before.subtract(ONE.multiply(BigInteger.valueOf(200))), totalSupply());
    }
}
```

- [ ] **Step 2: Run — [DEFERRED → verification gate]**

Run: `mvn -q -Dtest=UsdtIssuanceTest test`
Expected: PASS once `usdt.bin` exists. Likely fixup: the constructor ABI offsets (`0x80`/`0xC0`) for the two dynamic strings — confirm against the actual 4-arg layout (4 head words → dynamic data starts at 0x80).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/xdag/evm/UsdtIssuanceTest.java
git commit -m "test(evm): real Tether USDT full lifecycle (issue/transfer/fee/blacklist/pause/redeem)"
```

---

## Task 10: Exclude solidity fixtures from license check

**Files:**
- Modify: `pom.xml` (the `license-maven-plugin` `<configuration>/<excludes>`)

- [ ] **Step 1: Add excludes** so the Apache-2.0 Tether `.sol` and the `.bin`/README aren't forced to carry the MIT header:

```xml
<exclude>src/test/resources/solidity/**</exclude>
```

- [ ] **Step 2: Verify license check — [DEFERRED → verification gate]**

Run: `mvn -q license:check`
Expected: BUILD SUCCESS (no missing-header failures on the vendored fixtures).

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build(evm): exclude vendored solidity fixtures from license check"
```

---

## Task 11: Verification gate (run everything once the toolchain exists)

**Prerequisite:** `JAVA_HOME` → JDK 21, Maven 3.9 on PATH, and the `.bin` fixtures generated (Tasks 6/8).

- [ ] **Step 1: Full compile** — Run: `mvn -q -DskipTests package` → BUILD SUCCESS.
- [ ] **Step 2: EVM suite** — Run: `mvn -q -Dtest='EvmSanityTest,UsdtOzBaselineTest,UsdtIssuanceTest' test` → all green.
- [ ] **Step 3: Besu load check** — confirm no `UnsupportedClassVersionError` (validates the 26.5.0 pin) and that native precompiles resolve on this OS/arch.
- [ ] **Step 4: Fix any API-name mismatches** flagged `// CONFIRM 26.5.0` and re-run until green.
- [ ] **Step 5: Final commit** — `git commit -am "test(evm): sub-project A verified green on JDK21"`.

---

## Self-Review

**Spec coverage:**
- Besu 26.5.0 pin + repo → Task 1 ✔ · Shanghai executor → Tasks 2,3,5 ✔ · `WorldUpdater` seam (SimpleWorld) → Tasks 4,5 ✔ · ABI helpers → Task 4 ✔ · real Tether fixture + full lifecycle → Tasks 8,9 ✔ · OZ baseline → Tasks 6,7 ✔ · EVM sanity (PUSH0/REVERT) → Tasks 4,5 ✔ · license exclusion → Task 10 ✔ · deferred verification gate → Task 11 ✔ · hermetic `.bin` (no solc in build) → Task 6 README ✔.
- Out of scope (RocksDB persistence, block import, EIP-155 signing, address derivation, `eth_*` RPC) correctly **not** present → B/C.

**Placeholder scan:** No "TBD/TODO" in implementable steps. The `// CONFIRM 26.5.0` markers are explicit, enumerated API-verification points (not vague hand-waving), each tied to the Task-5/9 fixup notes — acceptable given the no-build constraint.

**Type consistency:** `XdagExecutionResult` fields (`success`/`returnData`/`gasUsed`/`logs`/`revertReason`/`createdContract`) are used identically across executor + all tests. `Abi.selector/encodeAddress/encodeUint256/decodeUint256/concat` signatures match every call site. `EvmTestBase` `deploy/call/callUint` and `XdagEvmExecutor.deploy/call/setCodeForTest` signatures are consistent throughout.

**Known residual risk (called out, not hidden):** the constructor ABI offsets in Task 9 and the low-level frame wiring in Task 5 are the two spots most likely to need a one-line fix at first compile; both are flagged at their step.
