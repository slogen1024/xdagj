# A4-Full: Transfer-From-Lock Fee Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the transfer-from-lock supply-conservation model (audit finding A4, design `docs/superpowers/specs/2026-08-31-a4-fee-routing-supply-conservation-design.md` §2/§4-deferred): the net EVM fee becomes a **transfer from the bridge deposit lock** to the miner (not a mint), the genesis alloc becomes a lock-backed **"genesis deposit"**, and `getSupply()` counts the alloc premine — total native XDAG is conserved as an equality under fee routing.

**Architecture:** `creditEvmFee` (BlockchainImpl) debits the lock address by the credited nano and journals the debit in a new EVM_META `0x0B` record; the unwind loop reverses the journaled debit (mirroring the `0x08` release-journal discipline). A new `GenesisLockSeeder` credits the lock once per chain lifetime with the alloc's native equivalent, marker-guarded in the ADDRESS column family (so it does NOT re-run when EVM_STATE is wiped, unlike the EVM-side `seedGenesisIfAbsent`). `getSupply` adds the config-derived alloc total when the bridge is scheduled. Config load fail-fasts guarantee alloc wei converts to nano exactly.

**Tech Stack:** Java 21, JUnit 4 + Mockito, RocksDB stores, Maven (JDK 21 at `~/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home`, Maven at `~/tools/apache-maven-3.9.9/bin/mvn`).

**Test command prefix (every task):**
```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home"
MVN="$HOME/tools/apache-maven-3.9.9/bin/mvn"
```

**Consensus-safety context (why no new fork gate):** fee routing is live only on devnet (throwaway; `feeRewardActivationHeight=0`); testnet/mainnet have it at `Long.MAX_VALUE`, so changing `creditEvmFee` semantics is byte-identical on shared nets. The A4 fail-safe already guarantees fee-routing ⇒ bridge scheduled, so the lock exists whenever a credit can fire.

**Key invariants to preserve:**
- Deterministic all-or-nothing: a lock shortfall skips the WHOLE credit (no debit, no credit, fee burned) with a CRITICAL log — every node behaves identically because the lock balance is consensus state (mirrors `releaseMaturedWithdrawals` skip-all).
- Reverse-what-you-did: the unwind re-credits the lock from the `0x0B` journal keyed by the credited block's height M, consumed BEFORE the trailing `rollbackTo` sweeps EVM_META. The credit side (block amount + fee) is already reversed by `unSetMain` — together the transfer fully unwinds.
- The native lock seed must survive EVM_STATE wipes: its marker lives in the ADDRESS CF, NOT in EVM_STATE (where `seedGenesisIfAbsent`'s marker intentionally dies on `rollbackTo`).

---

### Task 1: EVM_META `0x0B` fee-debit journal

**Files:**
- Modify: `src/main/java/io/xdag/evm/state/EvmMetaStore.java`
- Test: `src/test/java/io/xdag/evm/state/EvmMetaStoreTest.java`

- [ ] **Step 1: Write the failing test**

Add to `EvmMetaStoreTest` (same package; the fixture already builds `store = new EvmMetaStore(new InMemoryKVSource())` in `@Before`):

```java
@Test
public void fee_debit_journal_roundtrip_and_sweep() {
    // Absent -> 0 (no journal, nothing to reverse).
    assertEquals(0L, store.getFeeDebit(7L));

    store.putFeeDebit(7L, 123_456L);
    assertEquals(123_456L, store.getFeeDebit(7L));

    // Consumed by the unwind reversal: delete -> absent again.
    store.deleteFeeDebit(7L);
    assertEquals(0L, store.getFeeDebit(7L));

    // removeAbove sweeps records past the fork point but keeps those at or below it.
    store.putFeeDebit(5L, 11L);
    store.putFeeDebit(9L, 22L);
    store.removeAbove(5L);
    assertEquals("at the fork point survives", 11L, store.getFeeDebit(5L));
    assertEquals("past the fork point swept", 0L, store.getFeeDebit(9L));
}

@Test
public void fee_debit_journal_rejects_non_positive_amounts() {
    assertThrows(IllegalArgumentException.class, () -> store.putFeeDebit(1L, 0L));
    assertThrows(IllegalArgumentException.class, () -> store.putFeeDebit(1L, -5L));
}
```

If `assertThrows` is not already statically imported in the file, add `import static org.junit.Assert.assertThrows;`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
$MVN -q test -Dtest=EvmMetaStoreTest#fee_debit_journal_roundtrip_and_sweep
```
Expected: COMPILE ERROR — `getFeeDebit`/`putFeeDebit`/`deleteFeeDebit` do not exist.

- [ ] **Step 3: Implement the journal in `EvmMetaStore`**

(a) Add the constant after `PREFIX_SKIP` (line ~90):

```java
    /**
     * Fee-debit journal (A4 transfer-from-lock): 0x0B | height(8 BE) -> feeNano(8 BE). The native
     * nano ACTUALLY debited from the bridge lock when height M's net EVM fee was credited to block M.
     * Written by BlockchainImpl.creditEvmFee; consumed (read + deleted) by the unwind reversal
     * (reverse-what-you-did bookkeeping, 0x08 precedent); removeAbove-swept for hygiene.
     */
    private static final byte PREFIX_FEE_DEBIT = 0x0B;
```

(b) Add to the class-javadoc record table (after the `0x0A` line):

```
 *   0x0B | mainHeight(8 BE) -> fee-debit journal (A4): nano debited from the bridge lock (8 BE)
```

(c) Add the key helper next to `skipKey`:

```java
    private static byte[] feeDebitKey(long height) {
        byte[] key = heightKey(height);
        key[0] = PREFIX_FEE_DEBIT;
        return key;
    }
```

(d) Add the accessors after `deleteReleases` (end of class):

```java
    /**
     * Journals the nano ACTUALLY debited from the bridge lock for {@code height}'s EVM fee credit
     * (A4 transfer-from-lock). Written by creditEvmFee when it debits; consumed by the unwind
     * reversal. At most one credit per height (K1 convergence invariant), so put-once.
     */
    public void putFeeDebit(long height, long feeNano) {
        if (feeNano <= 0) {
            throw new IllegalArgumentException(
                    "non-positive fee debit " + feeNano + " at height " + height);
        }
        byte[] value = new byte[8];
        for (int i = 0; i < 8; i++) {
            value[i] = (byte) (feeNano >>> (56 - 8 * i));
        }
        store.put(feeDebitKey(height), value);
    }

    /** The journaled lock debit at {@code height}; 0 when none was recorded (or already reversed). */
    public long getFeeDebit(long height) {
        byte[] raw = store.get(feeDebitKey(height));
        if (raw == null) {
            return 0L;
        }
        if (raw.length != 8) {
            throw new IllegalStateException("corrupt EVM_META fee-debit record at height " + height
                    + ": " + raw.length + " bytes");
        }
        long nano = Bytes.wrap(raw).getLong(0);
        if (nano <= 0) {
            throw new IllegalStateException("corrupt EVM_META fee-debit record at height " + height
                    + ": non-positive amount " + nano);
        }
        return nano;
    }

    /** Deletes the fee-debit journal for {@code height} (consumed by the unwind reversal). */
    public void deleteFeeDebit(long height) {
        store.delete(feeDebitKey(height));
    }
```

(e) Add the sweep loop at the end of `removeAbove`, after the `PREFIX_SKIP` loop:

```java
        for (byte[] key : store.prefixKeyLookup(new byte[]{PREFIX_FEE_DEBIT})) {
            if (heightFromKey(key) > height) {
                store.delete(key);
            }
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
$MVN -q test -Dtest=EvmMetaStoreTest
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/evm/state/EvmMetaStore.java src/test/java/io/xdag/evm/state/EvmMetaStoreTest.java
git commit -m "feat(evm): EVM_META 0x0B fee-debit journal for transfer-from-lock (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: ADDRESS-CF seed marker + `GenesisLockSeeder`

**Files:**
- Modify: `src/main/java/io/xdag/db/AddressStore.java`
- Modify: `src/main/java/io/xdag/db/rocksdb/AddressStoreImpl.java`
- Create: `src/main/java/io/xdag/evm/bridge/GenesisLockSeeder.java`
- Test: `src/test/java/io/xdag/evm/bridge/GenesisLockSeederTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/xdag/evm/bridge/GenesisLockSeederTest.java` (copy the standard MIT license header from any existing test file, e.g. `EvmMetaStoreTest.java`):

```java
package io.xdag.evm.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.config.spec.EvmSpec;
import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.evm.state.InMemoryKVSource;
import java.math.BigInteger;
import java.util.List;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class GenesisLockSeederTest {

    private static final Address FUNDED =
            Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    // 1,000,000 * 1e18 wei == 1e24 wei == 1e15 nano (whole-nano by construction).
    private static final BigInteger ALLOC_WEI = new BigInteger("1000000000000000000000000");
    private static final long ALLOC_NANO = 1_000_000_000_000_000L;

    private AddressStore addressStore;

    @Before
    public void setUp() {
        addressStore = new AddressStoreImpl(new InMemoryKVSource());
        addressStore.reset();
    }

    private static EvmSpec spec(long bridgeActivation, List<GenesisAllocEntry> alloc) {
        EvmSpec spec = Mockito.mock(EvmSpec.class);
        Mockito.when(spec.getEvmBridgeActivationHeight()).thenReturn(bridgeActivation);
        Mockito.when(spec.getEvmGenesisAlloc()).thenReturn(alloc);
        return spec;
    }

    @Test
    public void seeds_lock_once_with_the_alloc_native_equivalent() {
        EvmSpec spec = spec(0L, List.of(new GenesisAllocEntry(FUNDED, Wei.of(ALLOC_WEI))));
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();

        assertFalse(addressStore.isEvmGenesisLockSeeded());
        assertEquals(ALLOC_NANO, GenesisLockSeeder.seedIfAbsent(addressStore, spec));
        assertEquals("lock holds the alloc's native equivalent",
                XAmount.of(ALLOC_NANO), addressStore.getBalanceByAddress(lockKey));
        assertTrue(addressStore.isEvmGenesisLockSeeded());

        // Idempotent: a second call (normal restart) must not double-credit.
        assertEquals(0L, GenesisLockSeeder.seedIfAbsent(addressStore, spec));
        assertEquals(XAmount.of(ALLOC_NANO), addressStore.getBalanceByAddress(lockKey));
    }

    @Test
    public void no_seed_when_bridge_unscheduled_or_alloc_empty() {
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();

        // Bridge unscheduled: alloc wei is unbacked-by-design (unwithdrawable, unroutable) — no seed.
        assertEquals(0L, GenesisLockSeeder.seedIfAbsent(addressStore,
                spec(Long.MAX_VALUE, List.of(new GenesisAllocEntry(FUNDED, Wei.of(ALLOC_WEI))))));
        assertEquals(XAmount.ZERO, addressStore.getBalanceByAddress(lockKey));
        assertFalse(addressStore.isEvmGenesisLockSeeded());

        // Bridge scheduled, empty alloc: nothing to back — no seed, no marker.
        assertEquals(0L, GenesisLockSeeder.seedIfAbsent(addressStore, spec(0L, List.of())));
        assertFalse(addressStore.isEvmGenesisLockSeeded());
    }

    @Test
    public void alloc_total_nano_sums_entries_exactly() {
        assertEquals(3L, GenesisLockSeeder.allocTotalNano(List.of(
                new GenesisAllocEntry(FUNDED, Wei.of(BigInteger.valueOf(1_000_000_000L))),
                new GenesisAllocEntry(Address.fromHexString(
                        "0x00000000000000000000000000000000000000aa"),
                        Wei.of(BigInteger.valueOf(2_000_000_000L))))));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
$MVN -q test -Dtest=GenesisLockSeederTest
```
Expected: COMPILE ERROR — `GenesisLockSeeder` and `isEvmGenesisLockSeeded` do not exist.

- [ ] **Step 3: Implement the marker + seeder**

(a) `AddressStore` interface — add after `EXECUTED_NONCE_NUM`:

```java
    /** A4 (transfer-from-lock): 1-byte marker set once the genesis alloc's native equivalent has
     * been seeded into the bridge lock. Lives in the ADDRESS CF so it travels with (and dies with)
     * the balances it guards — an EVM_STATE wipe must NOT re-trigger the native seed. */
    byte EVM_GENESIS_LOCK_SEED = (byte) 0x60;
```

and two methods at the end of the interface:

```java
    boolean isEvmGenesisLockSeeded();

    void markEvmGenesisLockSeeded();
```

(b) `AddressStoreImpl` — add implementations (anywhere after `updateBalance`):

```java
    @Override
    public boolean isEvmGenesisLockSeeded() {
        return addressSource.get(new byte[]{EVM_GENESIS_LOCK_SEED}) != null;
    }

    @Override
    public void markEvmGenesisLockSeeded() {
        addressSource.put(new byte[]{EVM_GENESIS_LOCK_SEED}, new byte[]{1});
    }
```

Check whether any other class implements `AddressStore` (e.g. a snapshot variant): `grep -rn "implements AddressStore" src/main/java`. If one exists, add the same two methods there.

(c) Create `src/main/java/io/xdag/evm/bridge/GenesisLockSeeder.java` (MIT license header from a neighboring file, e.g. `BridgeConstants.java`):

```java
package io.xdag.evm.bridge;

import io.xdag.config.spec.EvmSpec;
import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.evm.GenesisAllocEntry;
import java.math.BigInteger;
import java.util.List;

/**
 * A4 transfer-from-lock (design 2026-08-31 §2): the genesis alloc is a "genesis deposit" — its
 * native equivalent is credited to the bridge lock exactly once per chain lifetime, so every EVM
 * wei (deposit- or alloc-originated) is a claim on locked native and the fee credit always has
 * backing to transfer. Marker-guarded in the ADDRESS column family: an EVM_STATE wipe (which
 * re-runs the EVM-side seedGenesisIfAbsent) must NOT re-run this native credit.
 */
public final class GenesisLockSeeder {

    private GenesisLockSeeder() {
    }

    /**
     * Sum of the alloc entries' wei converted to nano. Exact by the config-load whole-nano
     * fail-fast (AbstractConfig.validateBridgeConfig); longValueExact is the backstop for a
     * non-validated caller.
     */
    public static long allocTotalNano(List<GenesisAllocEntry> alloc) {
        BigInteger totalWei = BigInteger.ZERO;
        for (GenesisAllocEntry entry : alloc) {
            totalWei = totalWei.add(entry.balance().getAsBigInteger());
        }
        return totalWei.divide(BridgeConstants.WEI_PER_NANO).longValueExact();
    }

    /**
     * Credits the lock with the alloc's native equivalent once (idempotent via the ADDRESS-CF
     * marker). No-op when the bridge is unscheduled (no lock semantics) or the alloc is empty.
     * Returns the seeded nano, 0 when nothing was seeded.
     */
    public static long seedIfAbsent(AddressStore addressStore, EvmSpec spec) {
        if (spec.getEvmBridgeActivationHeight() == Long.MAX_VALUE
                || spec.getEvmGenesisAlloc().isEmpty()
                || addressStore.isEvmGenesisLockSeeded()) {
            return 0L;
        }
        long nano = allocTotalNano(spec.getEvmGenesisAlloc());
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();
        addressStore.updateBalance(lockKey,
                addressStore.getBalanceByAddress(lockKey).add(XAmount.of(nano)));
        addressStore.markEvmGenesisLockSeeded();
        return nano;
    }
}
```

Note: verify `GenesisAllocEntry.balance()` returns `Wei` and `Wei.getAsBigInteger()` exists (both already used in `AbstractConfig.parseEvmAlloc`). Verify `InMemoryKVSource` is public in `io.xdag.evm.state` (it is).

- [ ] **Step 4: Run the tests to verify they pass**

```bash
$MVN -q test -Dtest=GenesisLockSeederTest
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/db/AddressStore.java src/main/java/io/xdag/db/rocksdb/AddressStoreImpl.java src/main/java/io/xdag/evm/bridge/GenesisLockSeeder.java src/test/java/io/xdag/evm/bridge/GenesisLockSeederTest.java
git commit -m "feat(evm): genesis alloc seeds the bridge lock once (A4 genesis deposit)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: config fail-fasts (whole-nano alloc, native ceiling) + warn downgrade

**Files:**
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java` (validateBridgeConfig, ~line 341-410)
- Test: `src/test/java/io/xdag/config/EvmConfigSectionTest.java`

- [ ] **Step 1: Write the failing test**

Add to `EvmConfigSectionTest` (follow the `ConfigFactory.parseString` style of `fee_routing_requires_a_scheduled_bridge`, line ~265):

```java
@Test
public void evm_alloc_on_a_bridged_net_must_be_whole_nano_and_fit_the_native_ceiling() {
    String base = "evm.enabled = true\nevm.activationHeight = 0\n"
            + "evm.bridgeActivationHeight = 5\n"
            + "evm.bridgeRecoveryAddress = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\"\n";

    // Not a whole number of nano (1 nano = 1e9 wei): the lock seed could not equal the
    // redeemable wei -> fail fast.
    Config dust = ConfigFactory.parseString(base
            + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
            + "balance = \"1000000001\"}]");
    IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AbstractConfig.validateBridgeConfig(dust));
    assertTrue("message must name the whole-nano requirement",
            ex.getMessage().contains("whole number of nano"));

    // Total above the native XAmount ceiling (2^62 nano) -> fail fast.
    Config huge = ConfigFactory.parseString(base
            + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
            + "balance = \"9000000000000000000000000000\"}]"); // 9e27 wei = 9e18 nano > 2^62
    IllegalStateException ex2 = assertThrows(IllegalStateException.class,
            () -> AbstractConfig.validateBridgeConfig(huge));
    assertTrue("message must name the supply ceiling",
            ex2.getMessage().contains("native supply ceiling"));

    // Whole-nano and under the ceiling -> passes.
    Config ok = ConfigFactory.parseString(base
            + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
            + "balance = \"1000000000000000000000000\"}]"); // devnet's 1e24 wei
    AbstractConfig.validateBridgeConfig(ok); // no exception

    // Bridge UNSCHEDULED: dust alloc is allowed (never seeded, never redeemable).
    Config unbridged = ConfigFactory.parseString("evm.enabled = true\nevm.activationHeight = 0\n"
            + "evm.alloc = [{address = \"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf\", "
            + "balance = \"1000000001\"}]");
    AbstractConfig.validateBridgeConfig(unbridged); // no exception
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
$MVN -q test -Dtest=EvmConfigSectionTest#evm_alloc_on_a_bridged_net_must_be_whole_nano_and_fit_the_native_ceiling
```
Expected: FAIL — no exception thrown for the dust/huge configs.

- [ ] **Step 3: Implement the checks + downgrade the A4 warn**

(a) In `validateBridgeConfig`, replace the fee-routing + alloc WARN block (lines ~356-360, the one saying "alloc wei is currently UNBACKED... inflation") with:

```java
        if (feeRouting && config.hasPath("evm.alloc") && !config.getConfigList("evm.alloc").isEmpty()) {
            log.info("evm.feeRewardActivationHeight is scheduled with a non-empty evm.alloc: alloc wei "
                    + "is lock-backed (genesis deposit, A4 transfer-from-lock), so fee credits transfer "
                    + "from the lock without minting.");
        }
```

(b) Append at the END of `validateBridgeConfig` (after the `bridgeActivation < evmActivation` check — i.e., only reached when the bridge is scheduled):

```java
        // A4-full (transfer-from-lock): on a bridged net every alloc entry becomes a lock-backed
        // "genesis deposit". Each balance must convert to nano exactly (1 nano = 1e9 wei) or the
        // lock seed cannot equal the redeemable wei, and the total must fit the native XAmount
        // long (the A1 ceiling), or the seed itself would overflow.
        if (config.hasPath("evm.alloc")) {
            java.math.BigInteger weiPerNano = java.math.BigInteger.valueOf(1_000_000_000L);
            java.math.BigInteger totalWei = java.math.BigInteger.ZERO;
            for (com.typesafe.config.Config entry : config.getConfigList("evm.alloc")) {
                java.math.BigInteger balance = new java.math.BigInteger(entry.getString("balance"));
                if (balance.mod(weiPerNano).signum() != 0) {
                    throw new IllegalStateException("evm.alloc balance " + entry.getString("balance")
                            + " is not a whole number of nano (1 nano = 1e9 wei): on a "
                            + "bridge-scheduled network alloc wei is seeded into the deposit lock "
                            + "1:1 and must convert exactly.");
                }
                totalWei = totalWei.add(balance);
            }
            if (totalWei.divide(weiPerNano).bitLength() > 62) {
                throw new IllegalStateException("evm.alloc total " + totalWei + " wei exceeds the "
                        + "native supply ceiling once seeded into the deposit lock as nano.");
            }
        }
```

(Uses `java.math.BigInteger.valueOf(1_000_000_000L)` inline rather than `BridgeConstants.WEI_PER_NANO` to keep `validateBridgeConfig` free of an `evm.bridge` import in the static-validation path; the constant's value is protocol-fixed. If `BigInteger` is already imported unqualified in the file — it is, `parseEvmAlloc` uses it — drop the `java.math.` qualifiers.)

- [ ] **Step 4: Run the config tests**

```bash
$MVN -q test -Dtest=EvmConfigSectionTest
```
Expected: all green (devnet's 1e24-wei alloc is whole-nano; existing tests unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/config/AbstractConfig.java src/test/java/io/xdag/config/EvmConfigSectionTest.java
git commit -m "feat(evm): fail fast on non-whole-nano or over-ceiling evm.alloc when bridged (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `creditEvmFee` becomes transfer-from-lock

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java` (`creditEvmFee`, ~line 2629)
- Modify: `src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java` (fixture + 2 new tests)

- [ ] **Step 1: Seed the lock in the test fixture**

In `EvmConsensusIntegrationTest.buildFixture`, immediately after `kernel.setEvmBlockProcessor(...)` (line ~168-169), add:

```java
        // A4: mirror Kernel.startComponents — the genesis alloc backs the lock (genesis deposit),
        // so fee-credit tests exercise the real transfer-from-lock path against a funded lock.
        io.xdag.evm.bridge.GenesisLockSeeder.seedIfAbsent(addressStore, cfg.getEvmSpec());
```

DevnetConfig has `bridgeActivationHeight = 0` and a 1e24-wei alloc, so this funds the lock with 1e15 nano — orders of magnitude above any test fee (~2e8 nano), so every existing fee test still gets its credit.

- [ ] **Step 2: Write the failing tests**

Add to `EvmConsensusIntegrationTest` (model on `confirming_block_is_credited_the_floored_evm_fee`, line 471 — same imports, same 12-block driver loop):

```java
    // -----------------------------------------------------------------------------------------
    // A4 Test A: the fee credit is a TRANSFER from the lock, not a mint
    // -----------------------------------------------------------------------------------------

    @Test
    public void fee_credit_debits_the_lock_by_exactly_the_credited_nano() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        XAmount lockBefore = kernel.getAddressStore().getBalanceByAddress(lockKey);
        assertTrue("fixture must have seeded the lock (genesis deposit)",
                lockBefore.greaterThan(XAmount.ZERO));

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L); // 1000 nano per gas unit
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L, Wei.fromEth(1));
        funding.commit();

        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        List<io.xdag.core.Address> pending = new ArrayList<>();
        Bytes32 ref = addressBlock.getHashLow();
        Block carrier = null;
        for (int i = 1; i <= 12; i++) {
            generateTime += 64000L;
            pending.clear();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock;
            if (i == 3) {
                extraBlock = new Block(config, xdagTime, null, pending, true, null, null, -1,
                        XAmount.ZERO, null, evmRef);
                extraBlock.signOut(poolKey);
                extraBlock.setNonce(HashUtils.sha256(Bytes.wrap(new byte[]{0x12, 0x34})));
                carrier = extraBlock;
            } else {
                extraBlock = generateExtraBlock(config, poolKey, xdagTime, pending);
            }
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
        }

        Block storedCarrier = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        long gasUsed = evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().gasUsed();
        long expectedNano = BigInteger.valueOf(gasUsed).multiply(gasPrice.getAsBigInteger())
                .divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        assertTrue("test needs a non-zero fee", expectedNano > 0);
        assertEquals("fee credited to the carrier",
                XAmount.of(expectedNano), storedCarrier.getInfo().getFee());

        // Transfer, not mint: the lock lost exactly what the carrier gained.
        XAmount lockAfter = kernel.getAddressStore().getBalanceByAddress(lockKey);
        assertEquals("lock debited by exactly the credited nano",
                lockBefore.subtract(XAmount.of(expectedNano)), lockAfter);
        // And the debit is journaled for the unwind reversal.
        assertEquals("0x0B journal records the debit at the credited height", expectedNano,
                evmMetaStore.getFeeDebit(storedCarrier.getInfo().getHeight()));
    }

    // -----------------------------------------------------------------------------------------
    // A4 Test B: a short lock deterministically skips the WHOLE credit (no debit, no credit)
    // -----------------------------------------------------------------------------------------

    @Test
    public void a_short_lock_skips_the_fee_credit_entirely() throws Exception {
        // Rebuild with an EMPTY alloc: the bridge stays scheduled but nothing seeds the lock,
        // so the lock cannot cover any fee. (This is the broken-invariant shape the skip guards.)
        tearDown();
        buildFixture(new DevnetConfig() {
            @Override
            public java.util.List<io.xdag.evm.GenesisAllocEntry> getEvmGenesisAlloc() {
                return java.util.List.of();
            }
        });

        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        assertEquals("empty alloc -> unfunded lock",
                XAmount.ZERO, kernel.getAddressStore().getBalanceByAddress(lockKey));

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L);
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L, Wei.fromEth(1));
        funding.commit();

        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        List<io.xdag.core.Address> pending = new ArrayList<>();
        Bytes32 ref = addressBlock.getHashLow();
        Block carrier = null;
        for (int i = 1; i <= 12; i++) {
            generateTime += 64000L;
            pending.clear();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock;
            if (i == 3) {
                extraBlock = new Block(config, xdagTime, null, pending, true, null, null, -1,
                        XAmount.ZERO, null, evmRef);
                extraBlock.signOut(poolKey);
                extraBlock.setNonce(HashUtils.sha256(Bytes.wrap(new byte[]{0x12, 0x34})));
                carrier = extraBlock;
            } else {
                extraBlock = generateExtraBlock(config, poolKey, xdagTime, pending);
            }
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
        }

        Block storedCarrier = blockchain.getBlockByHash(carrier.getHashLow(), false);
        assertTrue("carrier must have confirmed as main", storedCarrier.getInfo().getHeight() > 0);
        // Execution itself is unaffected — only the credit is skipped.
        assertEquals("deploy must still execute", 1,
                evmMetaStore.getReceipt(deployTx.getHash()).orElseThrow().status());
        assertEquals("credit skipped: no fee on the carrier",
                XAmount.ZERO, storedCarrier.getInfo().getFee());
        assertEquals("no debit: lock untouched",
                XAmount.ZERO, kernel.getAddressStore().getBalanceByAddress(lockKey));
        assertEquals("no journal entry", 0L,
                evmMetaStore.getFeeDebit(storedCarrier.getInfo().getHeight()));
    }
```

Note: `kernel.getAddressStore()` — verify the Kernel getter name (`getAddressStore()` via Lombok `@Getter`); if it differs, capture the `addressStore` local from `buildFixture` into a test field instead.

- [ ] **Step 3: Run to verify the new tests fail**

```bash
$MVN -q test -Dtest=EvmConsensusIntegrationTest#fee_credit_debits_the_lock_by_exactly_the_credited_nano
```
Expected: FAIL — lock balance unchanged (credit currently mints; no journal method call).

- [ ] **Step 4: Rewrite `creditEvmFee` (BlockchainImpl ~2629)**

Replace the method body with:

```java
    /**
     * K1/G3-T1/A4: credit a matured EVM height's net fee (wei) to that height's OWN block
     * ({@code block}, at {@code height}) as a TRANSFER FROM THE BRIDGE LOCK (A4 transfer-from-lock,
     * design 2026-08-31): the payer's wei is a claim on locked native, so the fee is native they
     * already own moving to the miner — the lock is debited by the credited nano (journaled in
     * EVM_META 0x0B for the unwind reversal) and the block's amount + fee gain it. Total native
     * unchanged; getSupply stays exact; the bridge invariant lock == redeemable-EVM drops equally
     * on both sides. wei->nano floors; sub-nano dust is burned. Gated on {@code height} so
     * pre-activation is byte-identical. The A1 overflow guard and the lock-shortfall guard both
     * deterministically skip the WHOLE credit (fee burned) rather than half-applying. Used by BOTH
     * the synchronous setMain path and the async onEvmBlobsAvailable drain.
     */
    private void creditEvmFee(Block block, long height, java.math.BigInteger feeWei) {
        if (block == null || feeWei.signum() <= 0
                || height < kernel.getConfig().getEvmSpec().getEvmFeeRewardActivationHeight()) {
            return;
        }
        java.math.BigInteger nano = feeWei.divide(io.xdag.evm.bridge.BridgeConstants.WEI_PER_NANO);
        if (nano.bitLength() > 62) {
            log.error("CRITICAL: EVM fee credit {} nano at height {} exceeds the native-supply ceiling; "
                    + "skipping the credit (fee burned)", nano, height);
            return;
        }
        if (nano.signum() <= 0) {
            return;
        }
        EvmMetaStore metaStore = kernel.getEvmMetaStore();
        if (metaStore == null) {
            return; // EVM wiring absent (test-only shape): no journal store, no transfer bookkeeping
        }
        long feeNano = nano.longValueExact();
        XAmount evmFee = XAmount.of(feeNano);
        // A4: a short lock means the every-wei-is-lock-backed invariant is broken. Deterministic
        // skip-ALL (no debit, no credit) keeps every node identical — the lock balance is consensus
        // state — and never mints unbacked native (releaseMaturedWithdrawals skip-all precedent).
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        XAmount lockBalance = addressStore.getBalanceByAddress(lockKey);
        if (lockBalance.lessThan(evmFee)) {
            log.error("CRITICAL: bridge lock balance {} cannot cover the {} EVM fee credit at height "
                    + "{}; skipping the credit entirely (fee burned)", lockBalance, evmFee, height);
            return;
        }
        addressStore.updateBalance(lockKey, lockBalance.subtract(evmFee));
        metaStore.putFeeDebit(height, feeNano);
        acceptAmount(block, evmFee);
        block.getInfo().setFee(block.getInfo().getFee().add(evmFee));
        blockStore.saveBlockInfo(block.getInfo());
    }
```

(`EvmMetaStore` is already imported/used in this file at line ~1478; `addressStore` and `XAmount.lessThan` are already used at line ~1509-1510.)

- [ ] **Step 5: Run the full EVM consensus test class**

```bash
$MVN -q test -Dtest=EvmConsensusIntegrationTest
```
Expected: ALL green — the two new tests pass AND every pre-existing fee test still passes (fixture-seeded lock covers their fees). If a pre-existing test fails on a lock-balance interaction, inspect whether it rebuilds the fixture with a config that changes the alloc/bridge and adjust its expectations the same way Test B does.

- [ ] **Step 6: Run the bridge + blockchain suites (adjacent consensus surface)**

```bash
$MVN -q test -Dtest='BridgeDepositIntegrationTest,BridgeWithdrawalIntegrationTest,BlockchainTest'
```
Expected: all green (no behavior change for deposits/withdrawals; `BlockchainTest` has no EVM processor wired, so `creditEvmFee` never fires there).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java
git commit -m "feat(evm): fee credit transfers from the bridge lock instead of minting (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: unwind reversal of the lock debit

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java` (`unWindMain` loop ~line 1032; new method next to `reverseReleasedWithdrawals`)
- Test: `src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Add to `EvmConsensusIntegrationTest` (same driver-loop pattern):

```java
    // -----------------------------------------------------------------------------------------
    // A4 Test C: unwinding the credited height re-credits the lock (reverse-what-you-did)
    // -----------------------------------------------------------------------------------------

    @Test
    public void unwinding_the_credited_height_recredits_the_lock() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        XAmount lockBefore = kernel.getAddressStore().getBalanceByAddress(lockKey);

        SECP256K1 algo = new SECP256K1();
        KeyPair evmKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        Wei gasPrice = Wei.of(1_000_000_000_000L);
        EvmTransaction deployTx = EvmTransaction.unsigned(0L, gasPrice, 200_000L, Optional.empty(),
                Wei.ZERO, INIT_CODE, BigInteger.valueOf(0xCAFE)).sign(evmKey, algo);
        evmTxStore.put(deployTx);
        Bytes32 evmRef = Bytes32.wrap(deployTx.getHash().getBytes());

        RocksDbWorldUpdater funding = new RocksDbWorldUpdater(evmStateSource);
        funding.createAccount(deployTx.getSender(), 0L, Wei.fromEth(1));
        funding.commit();

        long generateTime = 1600616700000L;
        Block addressBlock = generateAddressBlock(config, poolKey, generateTime);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(addressBlock));
        List<io.xdag.core.Address> pending = new ArrayList<>();
        Bytes32 ref = addressBlock.getHashLow();
        Block carrier = null;
        for (int i = 1; i <= 12; i++) {
            generateTime += 64000L;
            pending.clear();
            pending.add(new io.xdag.core.Address(ref, XDAG_FIELD_OUT, false));
            long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime));
            Block extraBlock;
            if (i == 3) {
                extraBlock = new Block(config, xdagTime, null, pending, true, null, null, -1,
                        XAmount.ZERO, null, evmRef);
                extraBlock.signOut(poolKey);
                extraBlock.setNonce(HashUtils.sha256(Bytes.wrap(new byte[]{0x12, 0x34})));
                carrier = extraBlock;
            } else {
                extraBlock = generateExtraBlock(config, poolKey, xdagTime, pending);
            }
            assertSame(IMPORTED_BEST, blockchain.tryToConnect(extraBlock));
            ref = extraBlock.getHashLow();
        }

        Block storedCarrier = blockchain.getBlockByHash(carrier.getHashLow(), false);
        long creditedHeight = storedCarrier.getInfo().getHeight();
        assertTrue("carrier must have confirmed as main", creditedHeight > 0);
        long feeNano = evmMetaStore.getFeeDebit(creditedHeight);
        assertTrue("a debit must have been journaled", feeNano > 0);
        assertEquals("lock is debited before the unwind",
                lockBefore.subtract(XAmount.of(feeNano)),
                kernel.getAddressStore().getBalanceByAddress(lockKey));

        // Drive the exact unWindMain per-height sequence for the credited height: the debit
        // reversal runs BEFORE unSetMain (as in the unwind loop), then unSetMain reverses the
        // credit side (amount + fee). Package-private access mirrors reverseReleasedWithdrawals.
        blockchain.reverseEvmFeeDebit(creditedHeight);
        blockchain.unSetMain(storedCarrier);

        assertEquals("lock restored to its pre-credit balance",
                lockBefore, kernel.getAddressStore().getBalanceByAddress(lockKey));
        assertEquals("journal consumed", 0L, evmMetaStore.getFeeDebit(creditedHeight));
        assertEquals("credit side reversed too (fee)",
                XAmount.ZERO, storedCarrier.getInfo().getFee());
        assertEquals("credit side reversed too (amount)",
                XAmount.ZERO, storedCarrier.getInfo().getAmount());
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
$MVN -q test -Dtest=EvmConsensusIntegrationTest#unwinding_the_credited_height_recredits_the_lock
```
Expected: COMPILE ERROR — `reverseEvmFeeDebit` does not exist.

- [ ] **Step 3: Implement `reverseEvmFeeDebit` + hook it into the unwind loop**

(a) Add the method right after `reverseReleasedWithdrawals` (line ~1574):

```java
    /**
     * Reverses the bridge-lock debit a now-unwound height's EVM fee credit performed (A4
     * transfer-from-lock): reads + deletes its 0x0B journal and re-credits the lock. The credit
     * side (the block's amount and fee) is reversed by unSetMain; together the transfer fully
     * unwinds. Runs inside the unWindMain loop BEFORE the trailing rollbackTo sweeps EVM_META past
     * the fork point. Package-private for testability (reverseReleasedWithdrawals precedent).
     */
    void reverseEvmFeeDebit(long unwoundHeight) {
        if (kernel == null) {
            return;
        }
        EvmMetaStore metaStore = kernel.getEvmMetaStore();
        if (metaStore == null) {
            return;
        }
        long feeNano = metaStore.getFeeDebit(unwoundHeight);
        if (feeNano == 0) {
            return;
        }
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        addressStore.updateBalance(lockKey,
                addressStore.getBalanceByAddress(lockKey).add(XAmount.of(feeNano)));
        metaStore.deleteFeeDebit(unwoundHeight);
        log.info("Reversed the {} nano EVM fee lock-debit of unwound height {}",
                feeNano, unwoundHeight);
    }
```

(b) In `unWindMain` (line ~1032), add the call directly after `reverseReleasedWithdrawals(unwoundHeight);` and before `unSetMain(tmp);`:

```java
                    reverseReleasedWithdrawals(unwoundHeight);
                    // A4: re-credit the lock for this height's journaled fee debit while its 0x0B
                    // journal is still readable (rollbackTo sweeps EVM_META past the fork point).
                    reverseEvmFeeDebit(unwoundHeight);
                    unSetMain(tmp);
```

- [ ] **Step 4: Run the test class**

```bash
$MVN -q test -Dtest=EvmConsensusIntegrationTest
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java
git commit -m "feat(evm): unwind re-credits the lock from the 0x0B fee-debit journal (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Kernel wiring — seed at startup, retire the unbacked-alloc warn

**Files:**
- Modify: `src/main/java/io/xdag/Kernel.java` (~lines 226-245)

- [ ] **Step 1: Add the seed call**

After `evmBlockProcessor.seedBridgeContractIfAbsent();` (line ~231), add:

```java
            // A4 transfer-from-lock: the genesis alloc is a "genesis deposit" — credit the lock
            // with its native equivalent exactly once per chain lifetime (ADDRESS-CF marker), so
            // every EVM wei is lock-backed and fee credits transfer from the lock, never minting.
            long seededNano = io.xdag.evm.bridge.GenesisLockSeeder.seedIfAbsent(
                    addressStore, config.getEvmSpec());
            if (seededNano > 0) {
                log.info("Seeded the bridge lock with {} nano — genesis-deposit backing for {} "
                        + "evm.alloc entr(ies).", seededNano,
                        config.getEvmSpec().getEvmGenesisAlloc().size());
            }
```

(`addressStore` is created at line ~176, well before this block.)

- [ ] **Step 2: Replace the stale unbacked-alloc warn**

Replace the `log.warn("evm.alloc has {} entr(ies) on a bridge-scheduled network: ...NO native backing...")` block (lines ~239-244) with:

```java
                if (!config.getEvmSpec().getEvmGenesisAlloc().isEmpty()) {
                    log.info("evm.alloc has {} entr(ies): lock-backed as a genesis deposit "
                            + "(A4 transfer-from-lock) and counted in getSupply.",
                            config.getEvmSpec().getEvmGenesisAlloc().size());
                }
```

- [ ] **Step 3: Compile + spot-run the node-shaped tests**

```bash
$MVN -q test -Dtest='EvmConsensusIntegrationTest,EvmConfigSectionTest' 2>&1 | tail -5
```
Expected: green (Kernel changes are startup-path only; compilation proves wiring).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/xdag/Kernel.java
git commit -m "feat(evm): seed the genesis-deposit lock at startup; retire the unbacked-alloc warn (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `getSupply` counts the genesis-alloc premine

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java` (`getSupply`, ~line 2427)
- Modify: `src/test/java/io/xdag/core/BlockchainTest.java` (`testGetSupply`, ~line 1845)
- Test: `src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java`

- [ ] **Step 1: Write the failing tests**

(a) Update `BlockchainTest.testGetSupply` (line ~1845): devnet now premines 1e24 wei = 1e15 nano = 1,000,000 XDAG into the lock, and supply must count it:

```java
    @Test
    public void testGetSupply() {
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        // Devnet schedules the bridge with a 1e24-wei genesis alloc: under A4 transfer-from-lock
        // that alloc is premined native seeded into the deposit lock (1,000,000 XDAG), and an
        // accurate closed-form supply includes it.
        assertEquals("1001024.0", blockchain.getSupply(1).toDecimal(1, XUnit.XDAG).toString());
        assertEquals("1002048.0", blockchain.getSupply(2).toDecimal(1, XUnit.XDAG).toString());
        assertEquals("1003072.0", blockchain.getSupply(3).toDecimal(1, XUnit.XDAG).toString());
        XAmount apolloSypply = blockchain.getSupply(config.getApolloForkHeight());
        assertEquals(String.valueOf(config.getApolloForkHeight() * 1024 - (1024 - 128) + 1_000_000L),
                apolloSypply.toDecimal(0, XUnit.XDAG).toString());
    }
```

(b) Add to `EvmConsensusIntegrationTest` a bridge-gating test:

```java
    // -----------------------------------------------------------------------------------------
    // A4 Test D: getSupply counts the alloc premine ONLY on a bridge-scheduled net
    // -----------------------------------------------------------------------------------------

    @Test
    public void get_supply_counts_the_alloc_premine_only_when_the_bridge_is_scheduled() throws Exception {
        // Devnet fixture (bridge scheduled, 1e24-wei alloc): premine counted.
        BlockchainImpl blockchain = new BlockchainImpl(kernel);
        assertEquals("1001024.0", blockchain.getSupply(1).toDecimal(1, XUnit.XDAG).toString());

        // Same config shape with the bridge unscheduled: the alloc never seeds the lock and the
        // supply formula is byte-identical to pre-A4 (shared-net safety).
        tearDown();
        buildFixture(new DevnetConfig() {
            @Override
            public long getEvmBridgeActivationHeight() {
                return Long.MAX_VALUE;
            }

            @Override
            public long getEvmFeeRewardActivationHeight() {
                return Long.MAX_VALUE; // keep the pair coherent (fee-routing needs the bridge)
            }
        });
        BlockchainImpl unbridged = new BlockchainImpl(kernel);
        assertEquals("1024.0", unbridged.getSupply(1).toDecimal(1, XUnit.XDAG).toString());
    }
```

(If `XUnit` is not imported in `EvmConsensusIntegrationTest`, add `import io.xdag.utils.XUnit;` — check the actual package with grep, `BlockchainTest` already imports it.)

- [ ] **Step 2: Run to verify they fail**

```bash
$MVN -q test -Dtest=BlockchainTest#testGetSupply
```
Expected: FAIL — supply is still 1024.0 (premine not counted).

- [ ] **Step 3: Implement in `getSupply`**

In `BlockchainImpl.getSupply` (line ~2427), before the final `return XAmount.ofXAmount(res.longValue());`, add:

```java
        // A4 transfer-from-lock: the genesis alloc is premined native seeded into the deposit lock
        // at chain start (a "genesis deposit"), so an accurate closed-form supply includes it.
        // Config-derived and bridge-gated: zero change for any net without a scheduled bridge or
        // with an empty alloc (testnet/mainnet today).
        if (kernel.getConfig().getEvmSpec().getEvmBridgeActivationHeight() != Long.MAX_VALUE
                && !kernel.getConfig().getEvmSpec().getEvmGenesisAlloc().isEmpty()) {
            res = res.plus(long2UnsignedLong(io.xdag.evm.bridge.GenesisLockSeeder.allocTotalNano(
                    kernel.getConfig().getEvmSpec().getEvmGenesisAlloc())));
        }
```

- [ ] **Step 4: Run the supply tests**

```bash
$MVN -q test -Dtest='BlockchainTest#testGetSupply,EvmConsensusIntegrationTest#get_supply_counts_the_alloc_premine_only_when_the_bridge_is_scheduled'
```
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/core/BlockchainImpl.java src/test/java/io/xdag/core/BlockchainTest.java src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java
git commit -m "feat(evm): getSupply counts the bridge-gated genesis-alloc premine (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: strengthen the conservation capstones

**Files:**
- Modify: `src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java` (tests at ~line 835 and ~line 919)

- [ ] **Step 1: Strengthen `evm_wei_destroyed_equals_nano_credited_scaled_plus_dust` (line ~835)**

Immediately after `BlockchainImpl blockchain = new BlockchainImpl(kernel);` at the top of the test, capture the lock baseline:

```java
        byte[] lockKey = io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20.toArray();
        XAmount lockBefore = kernel.getAddressStore().getBalanceByAddress(lockKey);
```

At the END of the test (after its existing wei/nano equality assertions, keeping whatever local holds the credited nano — reuse the test's existing `expectedNano`-style local; read the test body first), add:

```java
        // A4: the credit is a transfer — the lock lost exactly the credited nano, so
        // (wei destroyed) == (native moved out of the lock) * 1e9 + dust, and NOTHING was minted.
        assertEquals("lock debited by exactly the credited nano (transfer, not mint)",
                lockBefore.subtract(XAmount.of(expectedNano)),
                kernel.getAddressStore().getBalanceByAddress(lockKey));
```

(Adjust the local variable name to whatever that test actually calls its credited-nano value.)

- [ ] **Step 2: Strengthen `async_drain_credits_the_payload_block_after_a_deferred_blob_arrives` (line ~919)**

Same pattern: capture `lockBefore` right after the `BlockchainImpl` construction; at the end assert the async-drain credit debited the lock:

```java
        assertEquals("async drain debits the lock exactly like the sync path",
                lockBefore.subtract(XAmount.of(expectedNano)),
                kernel.getAddressStore().getBalanceByAddress(lockKey));
```

(Again match the test's actual credited-nano local name.)

- [ ] **Step 3: Run the class**

```bash
$MVN -q test -Dtest=EvmConsensusIntegrationTest
```
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/xdag/core/EvmConsensusIntegrationTest.java
git commit -m "test(evm): conservation capstones assert lock-debit equality on sync + async paths (A4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`
- Modify: `docs/superpowers/specs/2026-08-31-a4-fee-routing-supply-conservation-design.md`

- [ ] **Step 1: Update the pre-activation checklist**

In `2026-08-30-evm-audit-findings-and-pre-activation-checklist.md`:
1. Tick the stale A1 checkbox: `- [ ] **A1** overflow guard shipped` → `- [x] **A1** overflow guard shipped — DONE (merged with A3, 2026-08-30).`
2. Change the A4-full item from `- [ ] **A4 (full model) — DEFERRED to activation-prep**: ...` to:

```markdown
- [x] **A4 (full model) — DONE (2026-09-01)**: transfer-from-lock implemented — `creditEvmFee`
  debits the lock (EVM_META `0x0B` journal, unwind-reversed), the genesis alloc seeds the lock as a
  "genesis deposit" (`GenesisLockSeeder`, ADDRESS-CF marker so EVM_STATE wipes never re-seed),
  `getSupply()` adds the bridge-gated alloc premine, config fail-fasts enforce whole-nano alloc +
  native ceiling, and the conservation capstones assert lock-debit equality on the sync AND async
  credit paths. **Caveat for the recovery/ops doc:** a snapshot-bootstrapped node must carry the
  ADDRESS-CF seed marker with the snapshotted balances (or shared nets must keep `evm.alloc` empty,
  the current plan) — otherwise a fresh boot over snapshot state would re-seed the lock.
```

3. In the "Findings & disposition" table row A4, append to the disposition: `**A4-full transfer-from-lock SHIPPED 2026-09-01** (plan docs/superpowers/plans/2026-09-01-a4-full-transfer-from-lock.md).`

- [ ] **Step 2: Update the design doc status**

In `2026-08-31-a4-fee-routing-supply-conservation-design.md`, change the `**Status:**` line to:

```markdown
**Status:** approved 2026-08-31 · consensus-relevant (supply) · fail-safe shipped 2026-08-31; **full transfer-from-lock model IMPLEMENTED 2026-09-01** (plan `docs/superpowers/plans/2026-09-01-a4-full-transfer-from-lock.md`)
```

and annotate §4's "Deferred" list items 1-5 with `— DONE 2026-09-01` (item 5, the config-guard re-scope, is the warn downgrade + the new whole-nano/ceiling fail-fasts).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-08-30-evm-audit-findings-and-pre-activation-checklist.md docs/superpowers/specs/2026-08-31-a4-fee-routing-supply-conservation-design.md
git commit -m "docs(evm): A4-full transfer-from-lock shipped; tick stale A1 checkbox (checklist + design)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: full verification

- [ ] **Step 1: Full test suite**

```bash
$MVN -q test 2>&1 | tail -20
```
Expected: exit 0. Then confirm totals:

```bash
python3 - <<'EOF'
import glob, re
t=f=e=s=0
for p in glob.glob('target/surefire-reports/*.xml'):
    m=re.search(r'tests="(\d+)".*?errors="(\d+)".*?skipped="(\d+)".*?failures="(\d+)"', open(p).read(600))
    if m: t+=int(m.group(1)); e+=int(m.group(2)); s+=int(m.group(3)); f+=int(m.group(4))
print(f'tests={t} failures={f} errors={e} skipped={s}')
EOF
```
Expected: failures=0, errors=0, tests ≥ 628 (621 baseline + ~7 new).

- [ ] **Step 2: License-header check (new file `GenesisLockSeeder.java` + `GenesisLockSeederTest.java` must carry the MIT header)**

```bash
$MVN -q license:check 2>&1 | tail -5
```
Expected: no missing-header failures.

- [ ] **Step 3: Merge back per the finishing-a-development-branch skill** (feature branch → `dev-evm`, then offer next steps to the user; do NOT push without the user's say-so).

---

## Self-review notes

- **Spec coverage:** design §4-deferred items: (1) creditEvmFee debits the lock + paired reversal → Tasks 4-5; (2) seedGenesisIfAbsent-equivalent native seed, reorg-safe (ADDRESS-CF marker survives EVM_STATE wipes; `rollbackTo` never touches it) → Task 2 + 6; (3) getSupply adds alloc total → Task 7; (4) conservation tests → Tasks 4, 5, 8; (5) config-guard re-scope → Task 3 + 6.
- **Shared-net byte-identity:** every new behavior is gated on `bridgeActivationHeight != MAX` (seed, supply, config checks) or `feeRewardActivationHeight` (credit path) — both `MAX` on testnet/mainnet.
- **Shallow-reorg consistency:** the `0x0B` journal is keyed by the credited block M's height; a reorg that keeps M canonical (fork point ≥ M) reverses neither the credit nor the debit — matching `rollbackTo(lowestUnwound-1)`, which also keeps M's execution. A reorg that unwinds M reverses both (journal consumed in the unwind loop before the EVM_META sweep).
- **Known judgment calls an executor must NOT "fix" silently:** the lock-shortfall skip is all-or-nothing by design; the `metaStore == null` early-return is a test-only shape; `BlockchainTest.testGetSupply`'s new 1,001,024 expectation is intentional (devnet genuinely premines 1M XDAG under A4).
