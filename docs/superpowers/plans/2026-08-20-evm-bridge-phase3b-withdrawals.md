# Bridge Phase 3b: EVM→Native Withdrawals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An EVM account calls the system bridge contract's `withdraw(bytes20 nativeTarget)` with wei; after `evm.bridgeWithdrawalDelay` (N) main blocks the protocol releases the equivalent nano-XDAG from the lock address to the native target — completing the two-way bridge of spec `docs/superpowers/specs/2026-08-19-evm-bridge-design.md` §3.

**Architecture:** A fixed-bytecode system contract (seeded marker-guarded like genesis, OUTSIDE the chained root) emits `Withdrawal` events; `executeList` scans the height's receipts after the tx loop (same live+replay path, like the C5 bloom) into EVM_META 0x07; `setMain(H')` releases matured burns (H'−N) by direct `AddressStore` accounting (lock −, target +) and journals what it ACTUALLY did into EVM_META 0x08; `unSetMain`-time reversal reads-and-deletes 0x08 — reverse-what-you-did, immune to skip asymmetries. Release is skipped with a CRITICAL log when the EVM is still stalled below the burn height (node-local DA lag — deterministic only when all nodes have the blobs; shared-net use stays gated on the §13.3-2 DA hard-gate, matching the existing I4 philosophy).

**Tech Stack:** Java 21, Besu datatypes (Address/Wei/Log/LogTopic/keccak), solc 0.8.26 artifacts pre-compiled offline (embedded below — zero network at execution), RocksDB via EvmMetaStore/EvmStateSchema, JUnit 4.

---

## Environment (every Bash test command needs this)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
cd /Users/tron/IDEAProject/xdagj
```

PATH `java` is JDK 17 — export mandatory. Disk tight: `mvn test`/`-Dtest` only, NEVER `mvn package`. Branch dev-evm, commit directly. `docs/`/`.claude/` need `git add -f`. Commits English-only ending `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. MIT header on new Java files; no wildcard imports; 4-space indent; 120 cols; JUnit 4.

## Pre-computed protocol artifacts (solc 0.8.26+commit.8a97fa7a, optimizer OFF, metadata.bytecodeHash "none" — ground truth, never edit)

- **Contract address** = last 20 bytes of `keccak256("XDAG-EVM-BRIDGE-CONTRACT-v1")` = `0x97d38b2e167709f0ddb4880d197ce2920241e3ea` (full keccak `0x04022c2feaf7ac263ccc018497d38b2e167709f0ddb4880d197ce2920241e3ea`).
- **Runtime bytecode** (634 bytes; the ONLY code ever seeded):
  `0x60806040526004361061001d575f3560e01c8063dce0f64e14610021575b5f80fd5b61003b6004803603810190610036919061013c565b61003d565b005b5f3411801561005a57505f633b9aca0034610058919061019d565b145b610099576040517f08c379a000000000000000000000000000000000000000000000000000000000815260040161009090610227565b60405180910390fd5b806bffffffffffffffffffffffff19167fcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42346040516100d89190610254565b60405180910390a250565b5f80fd5b5f7fffffffffffffffffffffffffffffffffffffffff00000000000000000000000082169050919050565b61011b816100e7565b8114610125575f80fd5b50565b5f8135905061013681610112565b92915050565b5f60208284031215610151576101506100e3565b5b5f61015e84828501610128565b91505092915050565b5f819050919050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52601260045260245ffd5b5f6101a782610167565b91506101b283610167565b9250826101c2576101c1610170565b5b828206905092915050565b5f82825260208201905092915050565b7f62616420616d6f756e74000000000000000000000000000000000000000000005f82015250565b5f610211600a836101cd565b915061021c826101dd565b602082019050919050565b5f6020820190508181035f83015261023e81610205565b9050919050565b61024e81610167565b82525050565b5f6020820190506102675f830184610245565b9291505056fea164736f6c634300081a000a`
- **codeHash** = `0x80d42d27751c0527c5efcaddfa9fb802ffda21137cf695c78cf5c114c73d2c78` (keccak of the runtime bytecode).
- **Withdrawal topic0** = `keccak256("Withdrawal(bytes20,uint256)")` = `0xcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42` (self-check: this hex appears inside the runtime bytecode; so does the selector).
- **withdraw selector** = `0xdce0f64e`; calldata for `withdraw(target)` = `0xdce0f64e ‖ target20 ‖ 12 zero bytes` (bytes20 is left-aligned in its ABI slot).
- **Event shape**: `nativeTarget` is indexed bytes20 → topic1 = target20 right-padded to 32 bytes (decode = topic1 bytes [0,20)); `amount` is non-indexed → data = 32-byte big-endian uint256 wei.
- **Solidity source** (goes into `src/test/resources/solidity/xdag_bridge.sol`, Task 1):

```solidity
// SPDX-License-Identifier: MIT
pragma solidity 0.8.26;

/// XDAG bridge withdrawal entry (spec 2026-08-19 section 3.1). No admin, no upgrade, no selfdestruct.
/// msg.value stays in the contract forever: its balance is the cumulative burned wei (audit figure).
contract XdagBridge {
    event Withdrawal(bytes20 indexed nativeTarget, uint256 amount);

    function withdraw(bytes20 nativeTarget) external payable {
        require(msg.value > 0 && msg.value % 1e9 == 0, "bad amount");
        emit Withdrawal(nativeTarget, msg.value);
    }
}
```

## Key existing-code facts (verified 2026-08-20)

- `EvmBlockProcessor.seedGenesisIfAbsent` (`EvmBlockProcessor.java:146-156`) is the seeding template: marker check → `RocksDbWorldUpdater world = new RocksDbWorldUpdater(stateStore)` → mutate → `world.commit()` → raw marker put. Genesis seeding is deliberately OUTSIDE the chained root (documented design); the bridge contract mirrors this. Call sites to mirror: Kernel startup (`Kernel.java:~225`), `executeAndCheckpoint:337`, `rollbackTo:441`.
- `EvmStateSchema` prefixes: 0x00 account, 0x01 code, 0x02 storage, 0x03 genesis marker → **0x04 is free** for the bridge-contract marker. `RocksDbAccount.setCode(Bytes)` exists (`:161`).
- `executeList` (`EvmBlockProcessor.java:~522-590`): tx loop writes receipts (`putReceipt` :565) and inserts logs into the bloom (`:566`); the C5 bloom persist at `:585-586` runs "on normal execution AND reorg replay … so replayed heights regenerate it" — the withdrawal scan follows the SAME lifecycle, placed right there. Log API: `receipt.logs()`, `log.getLogger()` (besu Address), `log.getTopics()` (List<LogTopic>), `log.getData()` (Bytes).
- `unWindMain` (`BlockchainImpl.java:1007-1049`): per-block loop captures `unwoundHeight` at `:1024` BEFORE `unSetMain(tmp)` at `:1028`; ONE `rollbackTo(lowestUnwoundMainHeight − 1)` at the END (`:1042-1047`) — so per-height reversal hooks into the loop and the EVM_META records are still readable there.
- `setMain` calls `processMainBlock(...)` at `:~1382`; the release hook goes right after it (spec §3.2 order: native accounting → EVM execution → matured release), inside the same synchronized block.
- Native balance mutation: `addAmount/subtractAmount(Bytes addressHash20, XAmount, Block)` exist but require a Block context and touch the BI_OURS wallet-stat; protocol releases instead use `addressStore.getBalanceByAddress(byte[])` + `addressStore.updateBalance(byte[], XAmount)` directly (same primitives those methods wrap). `xdag_getBalance` RPC reads the AddressStore directly, so RPC balances are correct; the cached `xdagStats.balance` display aggregate is NOT updated by releases (documented, display-only).
- `XAmount.of(long nano)` constructs from nano; `XAmount.add/subtract/lessThan` exist. `BridgeConstants.LOCK_ADDRESS_20` (tuweni Bytes 20) and `WEI_PER_NANO` (BigInteger 1e9) from Phase 3a.
- EvmMetaStore prefixes 0x00-0x06 used → **0x07 (withdrawals by burn height) and 0x08 (release journal by release height) are free**; `DEPOSIT_ENTRY_LENGTH = 20 + 8` and the `putDeposits/getDeposits` idiom (BE longs, height keys, removeAbove sweeps, sign guards) are the template.
- The Phase-3a integration harness `BridgeDepositIntegrationTest` drives REAL `setMain` (BlockBuilder scaffold from `BlockchainTest.testNew2NewTransactionBlock`, MockBlockchain inner class, EVM stores + processor wired); `EvmConsensusIntegrationTest` shows a crafted 0x0F-carrier block flowing a real EVM tx through the real chain. Task 6 combines both — flagged as the highest-uncertainty seam.
- 3a review carry-forwards folded into this plan: `evm.enabled` cross-check in `validateBridgeConfig` (Task 2), same-height multi-deposit + fork-branch deposit tests (Task 6), alloc-nonempty WARN (Task 2). The "HTTP eth_getBalance" item stays deferred (RPC transport harness has no native chain; documented).

## The determinism caveat (state it in code comments AND docs)

Release at `setMain(H')` requires the EVM to have EXECUTED burn height `H'−N`. A node stalled on missing blobs (I4 defer) below that height CANNOT release deterministically — it skips with a CRITICAL log. On devnet (blobs always local) this is unreachable; on a shared network it is exactly the §13.3-2 DA hard-gate that must close before `evm.enabled=true` anyway. This is the honest v1 semantics; the 0x08 journal makes reversal reverse-what-was-done, so even a skip asymmetry cannot corrupt reversal bookkeeping.

---

### Task 1: BridgeContract constants + BridgeWithdrawal record

**Files:**
- Create: `src/main/java/io/xdag/evm/bridge/BridgeContract.java`
- Create: `src/main/java/io/xdag/evm/bridge/BridgeWithdrawal.java`
- Create: `src/test/resources/solidity/xdag_bridge.sol` (the source above, verbatim; append a note to `src/test/resources/solidity/README.md` if that README lists fixtures — read it first)
- Create: `src/test/java/io/xdag/evm/bridge/BridgeContractTest.java`

- [ ] **Step 1: Failing tests** (MIT header):

```java
package io.xdag.evm.bridge;

import static org.junit.Assert.assertEquals;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.Test;

/** Pins the Phase-3b protocol artifacts (solc 0.8.26, optimizer off, metadata none — spec §3.1). */
public class BridgeContractTest {

    @Test
    public void contract_address_matches_the_derivation() {
        assertEquals("0x97d38b2e167709f0ddb4880d197ce2920241e3ea",
                BridgeContract.ADDRESS.toHexString());
    }

    @Test
    public void runtime_bytecode_hash_is_pinned() {
        assertEquals(634, BridgeContract.RUNTIME_BYTECODE.size());
        assertEquals("0x80d42d27751c0527c5efcaddfa9fb802ffda21137cf695c78cf5c114c73d2c78",
                Hash.keccak256(BridgeContract.RUNTIME_BYTECODE).toHexString());
    }

    @Test
    public void withdrawal_event_topic_is_pinned_and_embedded_in_the_bytecode() {
        assertEquals("0xcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42",
                BridgeContract.WITHDRAWAL_TOPIC0.toHexString());
        // The compiler embeds the event topic in the runtime code — cross-checks source and constant.
        assertEquals(Hash.keccak256(Bytes.wrap("Withdrawal(bytes20,uint256)".getBytes())).toHexString(),
                BridgeContract.WITHDRAWAL_TOPIC0.toHexString());
    }

    @Test
    public void withdraw_selector_is_pinned() {
        assertEquals("0xdce0f64e", BridgeContract.WITHDRAW_SELECTOR.toHexString());
    }
}
```

- [ ] **Step 2: Run** `mvn test -Dtest=BridgeContractTest` → COMPILE ERROR. Red.

- [ ] **Step 3: Implement.**

`BridgeContract.java` (MIT header):

```java
package io.xdag.evm.bridge;

import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

/**
 * The bridge withdrawal system contract (spec §3.1): a fixed runtime bytecode seeded at a
 * nothing-up-my-sleeve address (last 20 bytes of keccak256("XDAG-EVM-BRIDGE-CONTRACT-v1")).
 * Compiled from src/test/resources/solidity/xdag_bridge.sol with solc 0.8.26, optimizer OFF,
 * metadata hash NONE — the bytecode below is consensus data; changing it is a hard fork.
 * The contract has no admin, no upgrade path, and never releases its balance: contract balance
 * == cumulative burned wei (audit figure).
 */
public final class BridgeContract {

    /** 0x97d38b2e167709f0ddb4880d197ce2920241e3ea. */
    public static final Address ADDRESS = Address.wrap(
            Hash.keccak256(Bytes.wrap("XDAG-EVM-BRIDGE-CONTRACT-v1".getBytes(StandardCharsets.US_ASCII)))
                    .slice(12, 20));

    /** The seeded runtime bytecode (634 bytes; solc 0.8.26, optimizer off, metadata none). */
    public static final Bytes RUNTIME_BYTECODE = Bytes.fromHexString(
            "0x60806040526004361061001d575f3560e01c8063dce0f64e14610021575b5f80fd5b61003b6004803603810190"
            + "610036919061013c565b61003d565b005b5f3411801561005a57505f633b9aca0034610058919061019d565b14"
            + "5b610099576040517f08c379a0000000000000000000000000000000000000000000000000000000008152600401"
            + "61009090610227565b60405180910390fd5b806bffffffffffffffffffffffff19167fcb0a8ccf10deec2c41d172"
            + "3a1ab013f59377a9853e5f541e6894b47c2e413a42346040516100d89190610254565b60405180910390a25056"
            + "5b5f80fd5b5f7fffffffffffffffffffffffffffffffffffffffff0000000000000000000000008216905091905056"
            + "5b61011b816100e7565b8114610125575f80fd5b50565b5f8135905061013681610112565b92915050565b5f6020"
            + "8284031215610151576101506100e3565b5b5f61015e84828501610128565b91505092915050565b5f81905091"
            + "9050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f5260126004526024"
            + "5ffd5b5f6101a782610167565b91506101b283610167565b9250826101c2576101c1610170565b5b8282069050"
            + "92915050565b5f82825260208201905092915050565b7f62616420616d6f756e74000000000000000000000000"
            + "000000000000000000005f82015250565b5f610211600a836101cd565b915061021c826101dd565b6020820190"
            + "50919050565b5f6020820190508181035f83015261023e81610205565b9050919050565b61024e81610167565b"
            + "82525050565b5f6020820190506102675f830184610245565b9291505056fea164736f6c634300081a000a");

    /** keccak256("Withdrawal(bytes20,uint256)"). */
    public static final Bytes32 WITHDRAWAL_TOPIC0 = Bytes32.fromHexString(
            "0xcb0a8ccf10deec2c41d1723a1ab013f59377a9853e5f541e6894b47c2e413a42");

    /** withdraw(bytes20) function selector; calldata = selector ‖ target20 ‖ 12 zero bytes. */
    public static final Bytes WITHDRAW_SELECTOR = Bytes.fromHexString("0xdce0f64e");

    private BridgeContract() {
    }
}
```

CRITICAL: the bytecode hex in the plan's artifact section is the single-line ground truth. When splitting it across Java string concatenation lines, VERIFY the reassembled constant by the two pinned tests (length 634 + keccak) — they fail on any transcription slip (this is exactly the Vector-B lesson from defect 1). If they fail, re-split mechanically from the single-line artifact hex; do NOT touch the expected hashes.

`BridgeWithdrawal.java` (MIT header):

```java
package io.xdag.evm.bridge;

import org.apache.tuweni.bytes.Bytes;

/**
 * One burn recorded by the bridge contract's Withdrawal event (spec §3.2): the 20-byte NATIVE
 * target address and the amount in nano-XDAG (event wei / 10^9; the contract enforces exact
 * divisibility). Also reused as the release-journal entry (EVM_META 0x08) — reversal reverses
 * exactly what was released.
 */
public record BridgeWithdrawal(Bytes nativeTarget20, long amountNano) {

    public BridgeWithdrawal {
        if (nativeTarget20.size() != 20) {
            throw new IllegalArgumentException("native target must be 20 bytes, got " + nativeTarget20.size());
        }
        if (amountNano < 0) {
            throw new IllegalArgumentException("negative withdrawal amount " + amountNano);
        }
    }
}
```

Copy `xdag_bridge.sol` from the artifact section verbatim.

- [ ] **Step 4: Run** `mvn test -Dtest=BridgeContractTest` → 4/4 PASS.

- [ ] **Step 5: Commit** — `git add src/main/java/io/xdag/evm/bridge src/test/java/io/xdag/evm/bridge src/test/resources/solidity && git commit -m "feat(evm): bridge withdrawal contract constants and pinned bytecode (phase 3b)\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 2: Withdrawal-delay config, evm.enabled cross-check, alloc warning

**Files:**
- Modify: `src/main/java/io/xdag/config/spec/EvmSpec.java`, `src/main/java/io/xdag/config/AbstractConfig.java`
- Modify: `src/main/java/io/xdag/evm/EvmConfig.java` (6th field), `src/main/java/io/xdag/Kernel.java` (+RpcHandlers EvmConfig call — read both `new EvmConfig(`/`new io.xdag.evm.EvmConfig(` sites)
- Modify: `src/main/resources/xdag-devnet.conf` AND `src/test/resources/xdag-devnet.conf`
- Test: `src/test/java/io/xdag/config/EvmConfigSectionTest.java`

- [ ] **Step 1: Failing tests** (EvmConfigSectionTest, per-network style + validator style):

```java
    // devnet: assertEquals(2L, spec.getEvmBridgeWithdrawalDelay());
    // testnet/mainnet: assertEquals(16L, spec.getEvmBridgeWithdrawalDelay()); // default, unscheduled

    @Test
    public void bridge_on_an_evm_disabled_network_fails_fast() {
        Config c = ConfigFactory.parseString("evm.enabled = false\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }

    @Test
    public void bridge_withdrawal_delay_must_be_positive_when_scheduled() {
        Config c = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
                + "evm.bridgeActivationHeight = 5\n"
                + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"\n"
                + "evm.bridgeWithdrawalDelay = 0");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }
```

CHECK the existing pass-case validator test (`well_formed_or_unscheduled_bridge_config_passes_validation`) — its scheduled config must now include `evm.enabled = true` (adjust it; that IS an allowed edit, note in the report).

- [ ] **Step 2: Run** → red.

- [ ] **Step 3: Implement.**

`EvmSpec`: `long getEvmBridgeWithdrawalDelay();` — javadoc: "Main blocks between an EVM burn and its native release (spec §3.2 N); devnet 2, recommended shared-net value 16."

`AbstractConfig`: field `protected long evmBridgeWithdrawalDelay = 16;` + getter + parse (`evm.bridgeWithdrawalDelay`, next to the bridge siblings). Extend `validateBridgeConfig` (inside the `scheduled` branch, after the recovery-address checks):

```java
        if (config.hasPath("evm.enabled") && !config.getBoolean("evm.enabled")) {
            throw new IllegalStateException("evm.bridgeActivationHeight is scheduled but evm.enabled is false: "
                    + "an EVM-disabled node would collect and silently drop deposits.");
        }
        if (config.hasPath("evm.bridgeWithdrawalDelay") && config.getLong("evm.bridgeWithdrawalDelay") < 1) {
            throw new IllegalStateException("evm.bridgeWithdrawalDelay must be >= 1, got "
                    + config.getLong("evm.bridgeWithdrawalDelay"));
        }
```

`EvmConfig`: 6th field `bridgeActivationHeight`, default `Long.MAX_VALUE` — NOTE the deliberate asymmetry with type2's always-active default and document it:

```java
    /**
     * Default bridge activation: NOT scheduled. Unlike type2ActivationHeight (default active for
     * tests), bridge activation gates contract-code SEEDING into EVM_STATE — an always-active
     * default would silently plant the contract in every unrelated test's world state.
     */
    public static final long DEFAULT_BRIDGE_ACTIVATION_HEIGHT = Long.MAX_VALUE;
```

5-arg ctor delegates with the default; new 6-arg ctor; getter `bridgeActivationHeight()`. Kernel's `new io.xdag.evm.EvmConfig(...)` and RpcHandlers' `new EvmConfig(...)` both append `config.getEvmSpec().getEvmBridgeActivationHeight()` / `evmSpec.getEvmBridgeActivationHeight()`.

`Kernel` EVM-init block: after the existing bridge log, the alloc warning (spec §4):

```java
            if (config.getEvmSpec().getEvmBridgeActivationHeight() != Long.MAX_VALUE
                    && !config.getEvmSpec().getEvmGenesisAlloc().isEmpty()) {
                log.warn("evm.alloc is non-empty on a bridge-scheduled network: genesis-allocated wei has NO "
                        + "native backing and withdrawing it would drain depositors' locked funds (spec §4). "
                        + "Acceptable only on a throwaway devnet.");
            }
```

Both devnet confs (in sync): `evm.bridgeWithdrawalDelay = 2` next to the other bridge keys, comment `# Withdrawal maturation delay N in main blocks (spec §3.2): devnet 2 (~2 min); recommend 16 for shared nets.`

- [ ] **Step 4: Run** `mvn test -Dtest='EvmConfigSectionTest,EvmConsensusIntegrationTest'` → ALL PASS (report exact; EvmConfigSectionTest = 13 existing + 2 new + delay assertions folded into existing per-network tests = 15).

- [ ] **Step 5: Commit** — `git commit -m "feat(evm): bridge withdrawal delay config, evm.enabled cross-check, alloc backing warning\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 3: EVM_META 0x07 withdrawals + 0x08 release journal

**Files:**
- Modify: `src/main/java/io/xdag/evm/state/EvmMetaStore.java`
- Test: `src/test/java/io/xdag/evm/state/EvmMetaStoreTest.java`

- [ ] **Step 1: Failing tests** (mirror the 0x06 deposit tests exactly, adapting to `BridgeWithdrawal`):

```java
    @Test
    public void withdrawal_records_round_trip_in_order_and_clear_by_removeAbove() {
        List<BridgeWithdrawal> ws = List.of(
                new BridgeWithdrawal(Bytes.fromHexString("0x3109ff8cf0be958a428c12d86c0abf64f529f7db"), 5L),
                new BridgeWithdrawal(Bytes.fromHexString("0x1111111111111111111111111111111111111111"), 7_000_000_000L));
        store.putWithdrawals(3L, ws);
        assertEquals(ws, store.getWithdrawals(3L));
        assertEquals(List.of(), store.getWithdrawals(4L));
        store.removeAbove(2L);
        assertEquals(List.of(), store.getWithdrawals(3L));
    }

    @Test
    public void release_journal_round_trips_and_deletes_explicitly() {
        List<BridgeWithdrawal> released = List.of(
                new BridgeWithdrawal(Bytes.fromHexString("0x2222222222222222222222222222222222222222"), 9L));
        store.putReleases(7L, released);
        assertEquals(released, store.getReleases(7L));
        store.deleteReleases(7L);
        assertEquals(List.of(), store.getReleases(7L));
    }

    @Test
    public void release_journal_is_swept_by_removeAbove() {
        store.putReleases(5L, List.of(new BridgeWithdrawal(Bytes.fromHexString(
                "0x2222222222222222222222222222222222222222"), 1L)));
        store.removeAbove(4L);
        assertEquals(List.of(), store.getReleases(5L));
    }
```

- [ ] **Step 2: Run** → red.

- [ ] **Step 3: Implement** — mirror the 0x06 idiom EXACTLY (same `DEPOSIT_ENTRY_LENGTH`-style constant reuse — the entry shape is identical 20+8; same height key builder, same BE long codec, same corrupt-length + negative-amount fail-fasts, same removeAbove sweep loops):

```java
    /** Burn events by burn height (spec §3.2): 0x07 | height(8 BE) -> (nativeTarget20 | amountNano 8 BE)*. */
    private static final byte PREFIX_WITHDRAWALS = 0x07;
    /** Native releases actually performed at a release height: 0x08 | height(8 BE) -> same entry shape.
     *  Written by setMain when it releases, consumed (read+deleted) by the unwind reversal —
     *  reverse-what-you-did bookkeeping, immune to release-skip asymmetries. */
    private static final byte PREFIX_RELEASES = 0x08;

    public void putWithdrawals(long height, List<BridgeWithdrawal> withdrawals) { /* mirror putDeposits */ }
    public List<BridgeWithdrawal> getWithdrawals(long height) { /* mirror getDeposits */ }
    public void putReleases(long height, List<BridgeWithdrawal> released) { /* mirror putDeposits */ }
    public List<BridgeWithdrawal> getReleases(long height) { /* mirror getDeposits */ }
    public void deleteReleases(long height) { store.delete(releasesKey(height)); }
```

(Write the bodies out fully by copying the deposit methods and adapting the record accessors — `nativeTarget20()` is already `Bytes`, so the write side is `w.nativeTarget20().toArray()` with no `.getBytes()` hop. Rename the shared 28-byte constant to `BRIDGE_ENTRY_LENGTH` if that reads better across the three record families — or keep `DEPOSIT_ENTRY_LENGTH` and add a comment; implementer's judgment, state the choice.) Update the class javadoc prefix table with 0x07/0x08. Extend `removeAbove` with BOTH sweeps.

- [ ] **Step 4: Run** `mvn test -Dtest=EvmMetaStoreTest` → ALL PASS (14 + 3 = 17).

- [ ] **Step 5: Commit** — `git commit -m "feat(evm): EVM_META withdrawal records (0x07) and release journal (0x08)\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 4: Contract seeding + withdrawal scan in the processor

**Files:**
- Modify: `src/main/java/io/xdag/evm/state/EvmStateSchema.java` (bridge marker 0x04)
- Modify: `src/main/java/io/xdag/evm/EvmBlockProcessor.java`
- Modify: `src/main/java/io/xdag/Kernel.java` (seed call at startup)
- Test: `src/test/java/io/xdag/evm/EvmBlockProcessorTest.java`

- [ ] **Step 1: READ FIRST** — `seedGenesisIfAbsent` (:146-156) and its three call sites (Kernel ~:225, executeAndCheckpoint :337, rollbackTo :441); `executeList`'s bloom section (:585-590); how EvmBlockProcessorTest builds processors with a custom EvmConfig (the gated-config tests from defect 1/3a).

- [ ] **Step 2: Failing tests** (write full bodies against the harness):

```java
    @Test
    public void bridge_contract_is_seeded_once_when_scheduled_and_never_when_not() {
        // Processor over EvmConfig with bridgeActivationHeight = 0 (6-arg ctor): after
        // seedBridgeContractIfAbsent(), reading the account code at BridgeContract.ADDRESS via a
        // fresh RocksDbWorldUpdater equals BridgeContract.RUNTIME_BYTECODE; calling seed again is
        // a no-op (marker). A processor over the DEFAULT config (bridge unscheduled) seeds NOTHING
        // (code empty, marker absent).
    }

    @Test
    public void a_withdraw_call_records_the_burn_in_evm_meta() {
        // Seeded processor (bridge active). Fund sender S (genesis alloc or createAccount).
        // Execute height 1 with ONE legacy tx from S: to = BridgeContract.ADDRESS,
        // value = 5_000_000_000 wei (= 5 nano, divisible by 1e9), gasLimit 100_000,
        // payload = BridgeContract.WITHDRAW_SELECTOR ‖ target20 ‖ 12 zero bytes,
        // where target20 = 0x1111111111111111111111111111111111111111.
        // Assert: receipt status 1; metaStore.getWithdrawals(1) == [(target20, 5L)];
        // contract balance == 5_000_000_000 wei (burned-in-place audit figure).
    }

    @Test
    public void dust_and_zero_value_withdrawals_revert_and_record_nothing() {
        // Same setup; tx A value 1_500_000_001 wei (not divisible), tx B value 0 — both to the
        // contract with valid calldata. Assert both receipts status 0 and getWithdrawals(h) empty.
    }

    @Test
    public void withdrawal_records_regenerate_on_replay() {
        // Execute the withdraw height, note getWithdrawals(1). rollbackTo(0) — records swept.
        // Re-execute the same height (processMainBlock again with the same refs/blob present).
        // Assert getWithdrawals(1) is regenerated identically (the scan lives on the shared path).
    }
```

Run → red (no seedBridgeContractIfAbsent / putWithdrawals never written).

- [ ] **Step 3: Implement.**

`EvmStateSchema`: `public static final byte PREFIX_BRIDGE_CONTRACT = 0x04;` + `public static byte[] bridgeContractMarkerKey() { return new byte[]{PREFIX_BRIDGE_CONTRACT}; }` (javadoc: seeded-once marker, genesis-marker sibling).

`EvmBlockProcessor`:

```java
    /**
     * Seeds the bridge withdrawal contract's fixed runtime bytecode at its protocol address once
     * per chain lifetime (spec §3.1), marker-guarded exactly like the genesis allocation and, like
     * it, OUTSIDE the chained root (protocol state agreed out-of-band). No-op unless the bridge is
     * scheduled — an always-on seed would plant contract code into every unrelated test state.
     * rollbackTo's reset wipes marker + code; the restore call re-seeds before replay.
     */
    public synchronized void seedBridgeContractIfAbsent() {
        if (bridgeActivationHeight == Long.MAX_VALUE) {
            return;
        }
        if (stateStore.get(EvmStateSchema.bridgeContractMarkerKey()) != null) {
            return;
        }
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(stateStore);
        world.getOrCreate(BridgeContract.ADDRESS).setCode(BridgeContract.RUNTIME_BYTECODE);
        world.commit();
        stateStore.put(EvmStateSchema.bridgeContractMarkerKey(), new byte[]{1});
    }
```

Field `private final long bridgeActivationHeight;` = `config.bridgeActivationHeight()` in the longest ctor. Call `seedBridgeContractIfAbsent()` immediately after EACH existing `seedGenesisIfAbsent()` call (executeAndCheckpoint :337, rollbackTo :441) and in Kernel after its startup `seedGenesisIfAbsent()`.

Withdrawal scan — in `executeList`, in the same block as the C5 bloom persist (after the tx loop; runs live AND replay):

```java
        // Phase 3b: record this height's bridge burns (spec §3.2). Same lifecycle as the bloom —
        // regenerated on replay, so release/reversal always see what THIS execution produced.
        List<BridgeWithdrawal> burns = new ArrayList<>();
        for (Bytes32 txHash : outcome.executed()) {
            EvmReceipt receipt = metaStore.getReceipt(Hash.wrap(txHash)).orElse(null);
            if (receipt == null) {
                continue;
            }
            for (Log evmLog : receipt.logs()) {
                if (!BridgeContract.ADDRESS.equals(evmLog.getLogger()) || evmLog.getTopics().isEmpty()
                        || !BridgeContract.WITHDRAWAL_TOPIC0.equals(
                                Bytes32.wrap(evmLog.getTopics().get(0).getBytes()))) {
                    continue;
                }
                Bytes target20 = evmLog.getTopics().get(1).getBytes().slice(0, 20);
                java.math.BigInteger wei = evmLog.getData().slice(0, 32).toUnsignedBigInteger();
                java.math.BigInteger[] div = wei.divideAndRemainder(BridgeConstants.WEI_PER_NANO);
                if (div[1].signum() != 0 || div[0].bitLength() > 62) {
                    // Unreachable via the contract (enforces divisibility; supply bounds the size) —
                    // deterministic skip keeps a crafted-state surprise from aborting consensus.
                    log.error("Skipping malformed bridge burn at height {}: {} wei", height, wei);
                    continue;
                }
                burns.add(new BridgeWithdrawal(target20, div[0].longValueExact()));
            }
        }
        if (!burns.isEmpty()) {
            metaStore.putWithdrawals(height, burns);
        }
```

(ADAPT names to the method's locals; check the topics list size ≥ 2 before reading topic1 — fold into the guard. The Log/LogTopic accessor shapes: mirror the existing bloom/`collectLogRecords` code in the same file. Imports in order.)

- [ ] **Step 4: Run** `mvn test -Dtest=EvmBlockProcessorTest` → ALL PASS (42 + 4 = 46). Also `mvn test -Dtest='EvmConsensusIntegrationTest,BridgeDepositIntegrationTest'` → pass (seeding is inert there: bridge default unscheduled in EvmConfig unless the harness passes it — CHECK BridgeDepositIntegrationTest's processor construction; if it uses a devnet-spec-driven EvmConfig via Kernel path it now seeds — that is CORRECT devnet behavior; confirm tests stay green and note it).

- [ ] **Step 5: Commit** — `git commit -m "feat(evm): seed the bridge contract when scheduled; scan executed receipts for burns\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 5: Release and reversal hooks in BlockchainImpl

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java`
- Modify: `src/main/java/io/xdag/evm/EvmBlockProcessor.java` (one small accessor)
- Test: `src/test/java/io/xdag/core/BridgeWithdrawalIntegrationTest.java` (create — harness reuse from BridgeDepositIntegrationTest; RELEASE mechanics only, full E2E is Task 6)

- [ ] **Step 1: READ FIRST** — `setMain` (:1349-1390 area), `unWindMain` loop (:1007-1049), `BridgeDepositIntegrationTest`'s harness (how it wires kernel/processor/addressStore and drives main blocks).

- [ ] **Step 2: Failing tests** — `BridgeWithdrawalIntegrationTest` scenarios (write full bodies on the 3a harness; the burn record is INJECTED via `metaStore.putWithdrawals(burnHeight, …)` in these tests — Task 6 produces it end-to-end via real contract execution; keep amounts small and lock pre-funded via a real deposit or direct `addressStore.updateBalance` on the lock):

1. `matured_withdrawal_releases_native_funds_after_n_blocks` — lock pre-funded 100 nano; inject withdrawal [(target20 T, 40 nano)] at burn height H; drive main blocks so the chain confirms height H+2 (devnet N=2). Assert: T's native balance == 40 nano; lock == 60 nano; `metaStore.getReleases(H+2)` == the released list (journal written).
2. `immature_withdrawal_does_not_release` — same but only H+1 confirmed → T untouched, no 0x08 record.
3. `insufficient_lock_skips_all_releases_deterministically` — lock 10 nano, withdrawals [(T1, 8), (T2, 8)] → NEITHER released (skip-all on first shortfall), no 0x08, lock unchanged. (CRITICAL log expected — assert state only.)
4. `unwound_release_is_reversed_exactly` — after test-1-style release at H', force a reorg that unwinds H' (the 3a harness's fork pattern — if BridgeDepositIntegrationTest has no fork helper, build the minimal fork the way BlockchainTest's fork/reorg test does; if NO reorg precedent is drivable in this harness, test the reversal hook DIRECTLY: call the package-visible reversal method with the journal present, assert balances restored and 0x08 deleted — state which route you took).

Run → red.

- [ ] **Step 3: Implement.**

`EvmBlockProcessor` accessor:

```java
    /** True if some deferred (blob-stalled) height at or below {@code height} is still unexecuted. */
    public synchronized boolean hasUnexecutedHeightAtOrBelow(long height) {
        for (long pending : metaStore.pendingHeights()) {
            if (pending <= height) {
                return true;
            }
        }
        return false;
    }
```

`BlockchainImpl` — release hook, called in `setMain` immediately AFTER the `processMainBlock(...)` call (same synchronized block; spec §3.2 order):

```java
    /**
     * Releases bridge withdrawals that matured at this confirmed height (spec §3.2): burns recorded
     * at height {@code mainNumber - N} move nano from the lock address to their native targets.
     * What is ACTUALLY released is journaled (EVM_META 0x08) so the unwind reversal reverses
     * exactly that — release-skip asymmetries can never corrupt reversal bookkeeping.
     * Releases mutate the AddressStore directly (no carrier block exists for a protocol transfer);
     * the cached xdagStats.balance display aggregate is deliberately not touched.
     */
    private void releaseMaturedWithdrawals(long mainNumber) {
        if (kernel == null) {
            return;
        }
        EvmBlockProcessor evmProcessor = kernel.getEvmBlockProcessor();
        long activation = kernel.getConfig().getEvmSpec().getEvmBridgeActivationHeight();
        if (evmProcessor == null || activation == Long.MAX_VALUE) {
            return;
        }
        long burnHeight = mainNumber - kernel.getConfig().getEvmSpec().getEvmBridgeWithdrawalDelay();
        if (burnHeight < activation) {
            return; // no burns can exist before the bridge itself
        }
        if (evmProcessor.hasUnexecutedHeightAtOrBelow(burnHeight)) {
            // DA lag (I4 defer): this node cannot know the burn set yet. Deterministic only when
            // every node has the blobs — shared-net use is gated on the §13.3-2 DA hard-gate.
            log.error("CRITICAL: bridge release at height {} skipped - EVM still stalled at or below "
                    + "burn height {}; withdrawals there will NOT release on this node", mainNumber, burnHeight);
            return;
        }
        List<BridgeWithdrawal> burns = kernel.getEvmMetaStore().getWithdrawals(burnHeight);
        if (burns.isEmpty()) {
            return;
        }
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();
        List<BridgeWithdrawal> released = new ArrayList<>();
        for (BridgeWithdrawal burn : burns) {
            XAmount amount = XAmount.of(burn.amountNano());
            XAmount lockBalance = addressStore.getBalanceByAddress(lockKey);
            if (lockBalance.lessThan(amount)) {
                // Unreachable while the conservation invariant holds (spec §4); deterministic
                // skip-all keeps every node identical even if it is ever violated.
                log.error("CRITICAL: bridge lock balance {} cannot cover release of {} at height {}; "
                        + "skipping ALL remaining releases of this height", lockBalance, amount, mainNumber);
                break;
            }
            addressStore.updateBalance(lockKey, lockBalance.subtract(amount));
            byte[] targetKey = burn.nativeTarget20().toArray();
            addressStore.updateBalance(targetKey,
                    addressStore.getBalanceByAddress(targetKey).add(amount));
            released.add(burn);
        }
        if (!released.isEmpty()) {
            kernel.getEvmMetaStore().putReleases(mainNumber, released);
        }
    }

    /**
     * Reverses the releases a now-unwound height actually performed (reads and deletes its 0x08
     * journal). Runs inside the unWindMain loop BEFORE the trailing rollbackTo wipes EVM_META.
     */
    private void reverseReleasedWithdrawals(long unwoundHeight) {
        if (kernel == null || kernel.getEvmMetaStore() == null) {
            return;
        }
        List<BridgeWithdrawal> released = kernel.getEvmMetaStore().getReleases(unwoundHeight);
        if (released.isEmpty()) {
            return;
        }
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();
        for (int i = released.size() - 1; i >= 0; i--) {
            BridgeWithdrawal r = released.get(i);
            XAmount amount = XAmount.of(r.amountNano());
            byte[] targetKey = r.nativeTarget20().toArray();
            addressStore.updateBalance(targetKey,
                    addressStore.getBalanceByAddress(targetKey).subtract(amount));
            addressStore.updateBalance(lockKey,
                    addressStore.getBalanceByAddress(lockKey).add(amount));
        }
        kernel.getEvmMetaStore().deleteReleases(unwoundHeight);
    }
```

Call sites: `releaseMaturedWithdrawals(mainNumber);` right after the `processMainBlock` call in setMain (NOTE: must run even when that call was skipped for having no refs/deposits — place it OUTSIDE the `if (evmProcessor != null && …)` block, it has its own guards). `reverseReleasedWithdrawals(unwoundHeight);` inside the unWindMain BI_MAIN branch, immediately before `unSetMain(tmp);` (:1028) — `unwoundHeight` is already captured at :1024. VERIFY `kernel.getEvmMetaStore()` exists as a Kernel getter (Phase 3a-era wiring); if the metaStore is only reachable via the processor, add a small `EvmBlockProcessor` delegate (`getWithdrawals/putReleases/getReleases/deleteReleases` pass-throughs) instead of a new Kernel getter — state which route the code took.

- [ ] **Step 4: Run** `mvn test -Dtest='BridgeWithdrawalIntegrationTest,BridgeDepositIntegrationTest,BlockchainTest'` → ALL PASS (report exact).

- [ ] **Step 5: Commit** — `git commit -m "feat(core): matured bridge releases with journaled unwind reversal\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 6: Full-cycle E2E + reorg + 3a carry-forward tests

**Files:**
- Modify: `src/test/java/io/xdag/core/BridgeWithdrawalIntegrationTest.java` (full cycle)
- Modify: `src/test/java/io/xdag/core/BridgeDepositIntegrationTest.java` (carry-forwards)

- [ ] **Step 1: READ FIRST** — `EvmConsensusIntegrationTest` (crafted 0x0F carrier flowing a REAL EVM tx through real setMain) + the 3a harness. The goal: ONE test that runs the whole §3 pipeline with no injected records.

- [ ] **Step 2: The capstone test** — `full_cycle_deposit_burn_and_release` in `BridgeWithdrawalIntegrationTest`:
1. Native wallet deposits 50 XDAG-worth of nano to the lock with a valid remark → EVM account E gets minted (Phase 3a path, real).
2. E signs a legacy EVM tx calling `withdraw(T)` on `BridgeContract.ADDRESS` (calldata `WITHDRAW_SELECTOR ‖ T20 ‖ 12 zeros`), value = an exact-nano wei amount, gasPrice 1; the tx blob is stored and its hash rides a main block as the 0x0F ref (the EvmConsensusIntegrationTest carrier pattern) so REAL setMain executes it.
3. Drive N=2 more confirmed main blocks.
4. Assert: T's native balance == burn nano; lock == deposit − burn; contract wei balance == burn wei; `getWithdrawals(burnHeight)` and `getReleases(burnHeight+2)` both populated.
This is an integration test over finished code — no red phase; a failure is a REAL cross-task bug: STOP and report BLOCKED.

- [ ] **Step 3: Reorg tests** (same file; reuse whatever fork route Task 5's test 4 established):
- `reorg_before_maturation_cancels_the_release` — burn at H confirmed, fork unwinds H before H+N confirms → rollbackTo swept 0x07 → after re-advancing past H'+N on the new branch (which has no burn), T never receives funds.
- `mixed_height_deposit_and_burn_order` — one height carrying BOTH a deposit (funds E) and E's burn tx (spends the mint, spec §3.2 order pin: mint → execute/burn → scan). Assert both 0x06 and 0x07 records for the height and the final balances.

- [ ] **Step 4: 3a carry-forwards** in `BridgeDepositIntegrationTest`:
- `two_deposits_in_one_confirmation_window_mint_in_order` — two lock-paying transfers confirmed by the same main block → both mint (assert both balances; the 0x06 record has 2 ordered entries).
- `fork_branch_deposit_is_wiped_and_new_branch_remints` — deposit on branch A, reorg to branch B carrying a DIFFERENT deposit → A's mint gone, B's mint present (route through the same fork helper; if the harness genuinely cannot drive a fork, report the limitation honestly instead of faking it — Task 5 step 2.4 established what is drivable).

- [ ] **Step 5: Run** `mvn test -Dtest='BridgeWithdrawalIntegrationTest,BridgeDepositIntegrationTest'` → ALL PASS (report exact counts).

- [ ] **Step 6: Commit** — `git commit -m "test(evm): full-cycle bridge E2E, reorg coverage, and 3a carry-forward pins\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 7: Docs + spec appendix + full-suite regression

**Files:**
- Modify: `docs/superpowers/specs/2026-08-19-evm-bridge-design.md` (appendix + status)
- Modify: `.claude/docs/smart-contract-design-and-implementation.md` (§13.1 缺陷 3 bullet)

- [ ] **Step 1: Spec appendix** — append `## 附录 A：系统合约工件（Phase 3b 固化）` containing: the solidity source verbatim, solc version/settings (0.8.26+commit.8a97fa7a, optimizer OFF, metadata.bytecodeHash none), contract address + derivation, runtime bytecode hex, codeHash, topic0, selector, the determinism caveat paragraph (DA lag → release skip + CRITICAL; §13.3-2 hard-gate reference), AND a one-paragraph 交付期修订 note: §3.2.3 的反转实现为 **0x08 释放日志**（setMain 记账实际释放、unwind 读日志反转后删除）——比 spec 原文"读 0x07 镜像反转"更强：即使某高度因停摆/锁不足跳过了部分释放，反转也永远精确等于实际所为。 Update the 状态 header: `；Phase 3b（出金）已于 2026-08-20 交付`.

- [ ] **Step 2: Design-doc bullet** — §13.1 缺陷 3 gains `- **已实现（Phase 3b 出金，2026-08-20，dev-evm）**：…` covering: 系统合约固定字节码（solc 0.8.26 无优化去 metadata，634 字节，codeHash 0x80d4…2c78）播种于 0x97d38b2e…41e3ea（marker 0x04，genesis 同款、链式根外）；executeList 收据日志扫描（bloom 同生命周期，重放再生）→ EVM_META 0x07；setMain(H') 释放 H'−N 成熟 burn（AddressStore 直接记账、锁减目标加），**实际释放写 0x08 日志，unWindMain 逐高度读日志反转后删除——反转的是实际做过的事**；锁不足/EVM 停摆滞后 → 确定性跳过 + CRITICAL（共享网前提 = §13.3-2 DA 硬门槛）；evm.bridgeWithdrawalDelay devnet=2 建议 16；evm.enabled 交叉校验 + alloc 无背书启动告警。**缺陷 3 双向 bridge 至此完整**。Also update the 缺陷 3 已实现 (3a) bullet's trailing "出金未实现" sentence.

- [ ] **Step 3: FULL suite** — `mvn clean test 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD" | tail -3` → copy verbatim (expect ~505+, 0 failures). Regression = STOP.

- [ ] **Step 4: Commit** — `git add -f docs/superpowers/specs/2026-08-19-evm-bridge-design.md .claude/docs/smart-contract-design-and-implementation.md && git commit -m "docs(evm): bridge phase 3b delivery - contract artifacts appendix and design-doc closure\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

## Deviation ledger

Record every adaptation (harness routes for forks, metaStore access route, entry-constant naming, log/topic accessor shapes) in the final report. The known-risky seams: Task 6's carrier-pattern reuse and any fork/reorg driving in the 3a harness — NEEDS_CONTEXT/honest-limitation over invention. Product-code defects: STOP and ask.
