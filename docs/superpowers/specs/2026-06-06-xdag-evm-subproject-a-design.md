# XDAG EVM — Program Design & Sub-project A Spec

- **Date:** 2026-06-06
- **Branch:** `dev-evm` (off `develop` @ `5a0f1078`, JDK 21, v0.8.3)
- **Status:** Draft for review
- **Author:** slogen (+ Claude)
- **Goal of the program (user request D):** *Referencing TRON / ETH / BNB, complete XDAG EVM development and test cases so that USDT can be issued on the XDAG chain — end to end.*

---

## 1. Why this exists (current reality)

A substantial hand-rolled EVM (≈67 classes, Constantinople-era, Besu-crypto + Tuweni based, with an `Erc20Test`) already exists — **but only on the stale `feature/new-evm` branch** (last commit 2022-05-05, **943 commits behind `develop`**, JDK 17, local `cmake` native build, dead `jcenter.bintray` repo). It was **never merged to `develop`/`master`**, and everything above the interpreter (state persistence, block-import integration, `eth_*` JSON-RPC) is **test-harness-only** (mocks; `TransactionExecutor` is never called from real block import; `Web3EthModule` is not wired into `Web3`).

Critically for USDT: the old interpreter lacks **PUSH0** and never executes `CHAINID`/`SELFBALANCE`/`BASEFEE`, so modern Solidity (≥0.8.20, default Shanghai) bytecode would hit "invalid opcode."

**Decision (locked with user):** Start fresh on a new `dev-evm` branch off current `develop` and **embed the modern Hyperledger Besu EVM library** rather than maintain the 2019 interpreter. Besu natively supports London/Shanghai/Cancun, PUSH0, modern gas, and all precompiles, and is actively maintained.

---

## 2. Decomposition (D → A → B → C)

Each sub-project gets its own spec → plan → implement cycle. Dependency order:

| # | Sub-project | Outcome (Definition of Done) |
|---|-------------|------------------------------|
| **A** | **EVM execution layer + USDT proof** *(this spec)* | A faithful USDT (real Tether) **and** a clean OZ ERC-20 deploy and exercise every token operation correctly on XDAG's embedded Besu EVM, proven by a comprehensive JUnit suite. State is in-memory (hermetic). |
| **B** | **On-chain integration** | RocksDB-backed `WorldUpdater` in a new column family; an EVM transaction type in the block model; 20-byte address derivation + EIP-155 secp256k1 signing/recovery; execute EVM txs during block import with atomic, deterministic state commit + per-EVM-block state root; chainId config. Contracts become live on-chain. |
| **C** | **`eth_*` JSON-RPC + tooling** | `eth_chainId`, `net_version`, `eth_blockNumber`, `eth_getBalance`, `eth_getTransactionCount`, `eth_getCode`, `eth_call`, `eth_estimateGas`, `eth_gasPrice`, `eth_sendRawTransaction`, `eth_getTransactionByHash`, `eth_getTransactionReceipt`, `eth_getBlockByNumber/Hash`, `eth_getLogs`; legacy RLP tx parsing; receipts + logs storage → **MetaMask / Hardhat deploy and use USDT end-to-end.** |

This document specifies **Sub-project A** in full, plus the cross-cutting decisions that B and C will inherit.

---

## 3. Cross-cutting decisions (apply to A/B/C)

These are fixed now so A's seams are forward-compatible with B and C. (All Besu API facts below were verified by downloading the jars and running `javap`.)

| Topic | Decision | Notes |
|-------|----------|-------|
| **EVM library** | `org.hyperledger.besu:besu-evm:26.5.0` + `org.hyperledger.besu:besu-datatypes:26.5.0` | **Latest JDK-21-safe pair.** 26.6.0 is Java-25 bytecode → `UnsupportedClassVersionError` on JDK 21. Re-verify class-file major version (65 = Java 21) on any upgrade. |
| **Maven repo** | Add `https://hyperledger.jfrog.io/artifactory/besu-maven/` | Artifacts are **not** on Maven Central. (`develop` already declares this repo for other deps.) |
| **License** | Apache-2.0 (compatible with XDAG MIT) | Preserve Apache NOTICE for any Besu source copied (e.g. `SimpleWorld` template in B). |
| **Target fork** | `EvmSpecVersion.SHANGHAI` | PUSH0 + EIP-3860 initcode metering; legacy gas; no blob/KZG complexity. Solidity must compile with `evmVersion=shanghai`. |
| **Fee model** | Legacy (type-0) only | Do **not** emit `baseFeePerGas` in C's block results → MetaMask/Hardhat use type-0. `eth_gasPrice` returns a fixed/low value. |
| **chainId** | Configurable; final values reserved later via `ethereum-lists/chains` | A only needs it for the `CHAINID` opcode; default placeholder (provisional, devnet) — **not** load-bearing in A. Final mainnet/testnet/devnet triple is a B/C decision. |
| **Address derivation** | `keccak256(uncompressedPubKey[1:])[12:]` (true Keccak-256, not SHA3) | XDAG secp256k1 wallet keys reuse directly; one keypair backs DAG identity + EVM account; matches MetaMask. (Implemented in B.) |
| **State commitment** | MPT-style state root per EVM block | Keeps `eth_getProof` / light-client compatibility. (Implemented in B.) |
| **Tuweni** | `io.consensys.tuweni` (pulled by besu-evm) | Reconcile/exclude any stale `org.apache.tuweni` / `io.tmio` coordinates to avoid split-package issues. |
| **Native precompiles** | gnark / secp256k1 / boringssl / jc-kzg-4844 JNA libs | Platform-specific; ensure the deploy OS/arch classifier jars are present (ecrecover/BN/BLS/KZG need them at runtime). |

### Reference takeaways (TRON / ETH / BNB)
- All three use the **same** secp256k1 + keccak 20-byte address derivation XDAG already has keys for; USDT is "just" an ERC-20/TRC-20/BEP-20 contract.
- **Real ETH Tether** (`0xdac17f…ec7`): Solidity 0.4.18, **decimals = 6**, **non-standard** (`transfer`/`approve`/`transferFrom` return *nothing*), `issue()`/`redeem()` (mint/burn), `pause()`, blacklist (`addBlackList`/`destroyBlackFunds`), fee (`basisPointsRate`/`maximumFee`/`setParams`).
- **TRON USDT**: 6 decimals, identical ERC-20 ABI (same bytecode applies). **BSC USDT**: 18 decimals, standard (returns bool).

---

## 4. Sub-project A — detailed design

### 4.1 Scope

**In scope:**
- New thin `io.xdag.evm` execution layer wrapping the Besu EVM library (Shanghai).
- A clean state seam backed in-memory for A (Besu `SimpleWorld`), designed so B can drop in a RocksDB-backed `WorldUpdater` with no executor change.
- Test-support ABI helpers (selector + encode/decode).
- Two USDT fixtures (real Tether + OZ ERC-20, both 6 decimals) checked in as precompiled bytecode (hermetic build, no `solc` at build time).
- A comprehensive JUnit suite proving USDT issuance + the full token lifecycle, plus a few EVM sanity tests.
- `pom.xml` dependency + repo additions.

**Out of scope (→ B/C):** RocksDB persistence, block-import integration, transaction types, EIP-155 signing/recovery, address derivation from XDAG keys, `eth_*` JSON-RPC, MetaMask/Hardhat, state root.

### 4.2 New main packages & files

```
src/main/java/io/xdag/evm/
  EvmConfig.java            // fork (SHANGHAI) + chainId (BigInteger) + EvmConfiguration.DEFAULT
  XdagEvmExecutor.java      // wraps Besu fluent EVMExecutor / processors; deploy() + call()
  XdagExecutionResult.java  // { boolean success, Bytes returnData, long gasUsed, List<Log> logs,
                            //   Optional<Bytes> revertReason, Optional<Address> createdContract }
  XdagEvmException.java     // wraps Besu halt/exceptional states
```

`XdagEvmExecutor` API (intent — exact types may adjust to the pinned API):

```java
// deploy: run init/creation bytecode, persist runtime code at derived address
XdagExecutionResult deploy(WorldUpdater world, Address sender, Bytes initCode,
                           Wei value, long gasLimit);

// call: invoke a function on an existing contract
XdagExecutionResult call(WorldUpdater world, Address sender, Address to,
                         Bytes callData, Wei value, long gasLimit);
```

Implementation drives the canonical Besu pattern:
1. Build `EvmSpec.evmSpec(SHANGHAI, chainId, EvmConfiguration.DEFAULT)` (cache it).
2. Use the fluent `new EVMExecutor(spec)` with `.worldUpdater(world).messageFrameType(...).sender(...).receiver(...).code(...).callData(...).gas(...).ethValue(...).tracer(NO_TRACING).commitWorldState()` then `.execute()`. (Drop to `MainnetEVMs.shanghai(chainId, EvmConfiguration.DEFAULT)` + `MessageCallProcessor`/`ContractCreationProcessor` + manual `MessageFrame` loop only if we need custom logs/gasUsed extraction the fluent facade doesn't expose.)
3. Extract `success`/`returnData`/`gasUsed`/`logs`/`revertReason` from the initial `MessageFrame`.
4. For `deploy`, set the returned runtime code on the contract account and return its address.

> Verified API corrections to honor in code: import `org.hyperledger.besu.evm.internal.EvmConfiguration`; tracer no-op is `OperationTracer.NO_TRACING`; `new Code(Bytes)` compiles in 26.5.0.

### 4.3 State seam

- **A:** use Besu's `SimpleWorld` (implements `WorldUpdater`; `SimpleAccount` implements `MutableAccount`) directly in tests — fast, in-memory, hermetic.
- The executor accepts any `org.hyperledger.besu.evm.worldstate.WorldUpdater`, so **B** later supplies `RocksDbWorldUpdater implements WorldUpdater` (template from Besu `SimpleWorld`, Apache-2.0) with **no executor change**. No XDAG-specific state interface is introduced in A (avoid premature abstraction); the seam is the Besu `WorldUpdater` interface itself.

### 4.4 Test-support helpers

```
src/test/java/io/xdag/evm/
  EvmTestBase.java     // build executor + SimpleWorld; fund account; deploy()/call() convenience;
                       //   premine helper; address constants
  Abi.java             // selector(String sig)=keccak256(sig)[0:4]; encodeAddress; encodeUint256;
                       //   decodeUint256; decodeAddress; concat
```

### 4.5 USDT fixtures (hermetic — precompiled bytecode checked in)

```
src/test/resources/solidity/
  usdt.sol      usdt.bin       // real Tether (solc 0.4.18, decimals 6) — primary, faithful proof
  usdt_oz.sol   usdt_oz.bin    // OpenZeppelin ERC20 named "USDT", decimals 6 — clean baseline
```

- **`.bin` = single contiguous hex line, no `0x`, no trailing whitespace** (matches the prior `.con` convention so a one-line reader works).
- **Fixture generation (offline, once — chosen at implementation time):**
  - Real Tether: (a) `docker run --rm -v $PWD:/src ethereum/solc:0.4.18 --bin --optimize -o /src/out /src/usdt.sol`, **or** (b) fetch the public **creation bytecode** of the deployed contract from Etherscan (no `solc` needed) and check it in. Constructor args `(initialSupply, "Tether USD", "USDT", 6)` are ABI-encoded and appended at deploy time by the test (or to the init code).
  - OZ ERC-20: compile with modern `solc` targeting `evmVersion=shanghai`.
- **No `solc` is added to the Maven build** (build stays hermetic, exactly as the legacy fixtures were produced).
- **License:** keep the upstream Tether attribution/header (Apache-2.0 in `tethercoin/USDT`); ensure `license-maven-plugin` excludes `src/test/resources/solidity/*` rather than forcing the MIT header onto vendored third-party source.

### 4.6 Test matrix

**`UsdtIssuanceTest` (real Tether — the headline "USDT can be issued on XDAG" proof):**
1. **deploy** `usdt.bin` with `(0, "Tether USD", "USDT", 6)` → assert `name()`/`symbol()`/`decimals()==6`.
2. **issue(amount)** (owner mint) → assert `totalSupply()` and `balanceOf(owner)` grow.
3. **transfer(to, amount)** → assert success **via receipt + post-state `balanceOf` reads**, and assert `returnData.size()==0` (locks in the non-standard no-return-bool quirk; prevents a "helpful" fix to a standard ERC-20).
4. **approve / allowance / transferFrom(from,to,amount)** by a third-party spender → assert allowance decrements and balances move.
5. **fee mechanism**: `setParams(basisPointsRate, maxFee)` then `transfer` → assert fee deducted from sender and credited to owner, capped at `maximumFee`.
6. **blacklist**: `addBlackList(evil)` → transfer from evil blocked; `destroyBlackFunds(evil)` → balance zeroed + `totalSupply` reduced.
7. **pause/unpause**: `pause()` → transfer reverts; `unpause()` → succeeds.
8. **redeem(amount)** (owner burn) → assert supply shrinks.
9. **events**: assert `Transfer(from,to,value)` and `Approval(owner,spender,value)` logs (topics + decoded data) are emitted.
10. **6-decimal scaling**: 1 USDT == `1_000_000` raw asserted throughout.

**`UsdtOzBaselineTest` (clean OZ ERC-20):** deploy → mint → `transfer`/`approve`/`transferFrom` asserting standard **bool** returns + events; `decimals()==6`. Demonstrates the standard-return path (BEP-20-style) too.

**`EvmSanityTest`:** a PUSH0 program runs (proves Shanghai is active); a tiny `CREATE` + `CALL` round-trip; a `REVERT` surfaces `revertReason`.

### 4.7 `pom.xml` changes
- Add `besu-maven` repository (if not already inherited).
- Add `org.hyperledger.besu:besu-evm:26.5.0` and `org.hyperledger.besu:besu-datatypes:26.5.0`.
- Reconcile Tuweni coordinates to `io.consensys.tuweni` (exclude stale `org.apache.tuweni`/`io.tmio` if any conflict surfaces in dependency convergence).
- Ensure the shade/assembly and `license-maven-plugin` configs tolerate the new deps + fixture files.

---

## 5. Verification plan (DEFERRED — not run this session)

Per the user's choice, this session **writes code only**; nothing is compiled or executed. Verification is a hard gate before sub-project A is declared done:

1. **Toolchain:** install JDK 21 + Maven 3.9 (deferred; user opted not to install now).
2. **Build:** `JAVA_HOME=<jdk21> mvn -q -DskipTests package` compiles cleanly.
3. **Tests:** `mvn -q -Dtest='UsdtIssuanceTest,UsdtOzBaselineTest,EvmSanityTest' test` → **all green**.
4. **Besu bytecode sanity:** confirm `besu-evm-26.5.0.jar` loads on JDK 21 (no `UnsupportedClassVersionError`); confirm native precompile libs resolve for this OS/arch.
5. **Definition of done met** only when the suite passes; until then the work is explicitly "unverified — written, not run."

---

## 6. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| **Besu 26.6.0+ breaks JDK 21** | Pin 26.5.0; re-check class-file major version on any bump. |
| **Besu fluent API drift** | Pin exact version; if `EVMExecutor` doesn't expose logs/gasUsed cleanly, fall back to low-level `MainnetEVMs` + processors + `MessageFrame` loop (verified to work). |
| **Native precompile libs missing on target OS** | Ensure correct classifier jars; smoke-test ecrecover at verification. |
| **Tuweni group-id clash** with existing deps | Exclude stale coordinates; converge on `io.consensys.tuweni`. |
| **solc 0.4.18 unavailable** for real Tether | Use Etherscan creation-bytecode fetch path instead. |
| **No-return-bool quirk** breaks naive assertions | Assert via receipt + state reads; explicitly assert empty return data. |
| **License plugin** rejects vendored Tether `.sol` | Exclude `src/test/resources/solidity/*` from `license:check`. |
| **Can't verify this session** (no build env) | Spec marks verification as a deferred gate; code written to compile against the verified API, not guessed. |

## 7. Open questions (non-blocking for A)
- Final chainId triple (mainnet/testnet/devnet) + `ethereum-lists/chains` reservation → **decide in B/C**.
- Whether to also add an 18-decimal standard variant to mirror BSC exactly → optional; OZ baseline already exercises the standard-return path.
