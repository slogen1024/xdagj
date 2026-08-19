# Bridge Phase 3a: Native→EVM Deposits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native XDAG transfer to the protocol lock address with an encoded remark mints the equivalent wei to the target EVM account — devnet gains a real funding on-ramp (replacing `evm.alloc`'s role), per spec `docs/superpowers/specs/2026-08-19-evm-bridge-design.md` (Phase 3a sections).

**Architecture:** Deposits are detected inside `BlockchainImpl.applyBlock`'s existing OUTPUT-credit branch (same DFS order as evmRefs), gated by `evm.bridgeActivationHeight` at the `setMain` hand-off, persisted per height in EVM_META prefix 0x06 (part of the replay script), and minted by `executeList` BEFORE that height's txs inside the same root commit — the chained root and `rollbackTo` replay cover them with zero new rollback machinery. Invalid remarks mint to the mandatory `evm.bridgeRecoveryAddress`, so every deposit mints and the conservation invariant (lock nano × 10⁹ ≥ bridge wei) stays clean.

**Tech Stack:** Java 21, Besu datatypes (Address/Wei/keccak), `io.xdag.crypto.encoding.Base58` (plain encode/decode), RocksDB via existing EvmMetaStore, JUnit 4.

---

## Environment (every Bash test command needs this)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
cd /Users/tron/IDEAProject/xdagj
```

PATH `java` is JDK 17 — the export is mandatory. Disk is tight: `mvn test` / `-Dtest=...` only, NEVER `mvn package`. Branch dev-evm, commit directly. `docs/`/`.claude/` files need `git add -f`. Commit messages English-only, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. House style: MIT header on new files, no wildcard imports, 4-space indent, 120 cols, JUnit 4.

## Fixed protocol constants (pre-computed, pin with tests)

- **Lock address (20 bytes)** = last 20 bytes of `keccak256("XDAG-EVM-BRIDGE-LOCK-v1")` =
  `0x3109ff8cf0be958a428c12d86c0abf64f529f7db`
  (full keccak: `0xfb208b4e6906e9a93d30ae7a3109ff8cf0be958a428c12d86c0abf64f529f7db`)
- **Remark codec** = `base58( 0x45 ‖ addr20 ‖ keccak256(0x45‖addr20)[0:2] )` — 23-byte payload, always exactly 31-32 ASCII chars (0x45 lead byte → no leading zeros; all four vectors below are 32).
- **External vectors** (computed with ethers v6's independent base58 — ground truth, never edit):
  - `0x7e5f4552091a69125d5dfcb7b8c2659029395bdf` ↔ `2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1`
  - `0x3535353535353535353535353535353535353535` ↔ `2RueRbXvwjnXoWxfFFesBWpWV3P8h6BK`
  - `0xffffffffffffffffffffffffffffffffffffffff` ↔ `2SrgkEeRYqh57C6az4ZAFLpF8cSnrugm`
  - `0x0000000000000000000000000000000000000000` ↔ `2RfCqRDrtGgVuWAeeBxjFxzLjJYDtyhd`
- **Unit conversion**: mint wei = amountNano × 10⁹. Read nano from `XAmount` via `amount.toDecimal(0, XUnit.NANO_XDAG).longValueExact()` (XAmount has no public nano getter; `XUnit.NANO_XDAG.exp == 0` so this is the exact long).

## Key existing-code facts (verified 2026-08-19)

- `BlockchainImpl.applyBlock(boolean flag, Block block, List<Bytes32> evmRefs)` at `BlockchainImpl.java:1061`; recursive call `:1087`; the OUTPUT credit branch `:1180-1183`:
  `addAmount(BasicUtils.hash2byte(linkAddress), link.getAmount().subtract(outPutLimit(block)), block)` — the CREDITED amount is fee-adjusted; deposits must mirror this exact expression.
- `setMain` builds `evmRefs` at `:1327`, calls `applyBlock(true, block, evmRefs)` `:1328`, EVM hook `:1340-1344` guarded by `evmProcessor != null && !evmRefs.isEmpty()`.
- `EvmBlockProcessor.processMainBlock(List<Bytes32> txRefs, long height, long timestampSeconds, Bytes32 blockHash)` at `EvmBlockProcessor.java:166` — early-returns on empty refs `:168` and pre-EVM-activation `:171`; defers to the pending queue on missing blobs `:178-183`; otherwise `executeAndCheckpoint(exp.flat(), …)`.
- `EvmMetaStore` prefixes 0x00..0x05 (`EvmMetaStore.java:51-59`); `putTxList(long, List<Hash>)` `:149` / `getTxList` `:204` show the height-key + fixed-entry encoding idiom; `removeAbove` `:281` sweeps every prefix.
- `Base58` (`io.xdag.crypto.encoding.Base58`): `String encode(Bytes)`, `Bytes decode(String)` throws `io.xdag.crypto.exception.AddressFormatException`; `encodeCheck`/`decodeCheck` also exist (used for NATIVE addresses — 24-byte payload → 33 chars; that is why the bridge remark uses the custom 23-byte format).
- `BlockInfo.remark` is a Lombok-generated `byte[] getRemark()` (32 raw bytes, may be null, ASCII zero-padded).
- keccak: `org.hyperledger.besu.crypto.Hash.keccak256(Bytes)`.
- Config fail-fast precedent: `fund.address` at `AbstractConfig.java:387-391`; gate-parse precedent: `evm.type2ActivationHeight` at `:400-401`.
- Genesis-style mint precedent: `EvmBlockProcessor.seedGenesisIfAbsent` (getOrCreate + setBalance on the root updater).

---

### Task 1: Bridge constants + remark codec (`io.xdag.evm.bridge`)

**Files:**
- Create: `src/main/java/io/xdag/evm/bridge/BridgeConstants.java`
- Create: `src/main/java/io/xdag/evm/bridge/BridgeRemark.java`
- Create: `src/main/java/io/xdag/evm/bridge/BridgeDeposit.java`
- Create: `src/test/java/io/xdag/evm/bridge/BridgeRemarkTest.java`

- [ ] **Step 1: Write the failing tests** (MIT header; the vectors are ground truth):

```java
package io.xdag.evm.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.junit.Test;

/**
 * Bridge remark codec tests. The encode vectors were produced by ethers v6's independent
 * base58 implementation — they pin cross-implementation compatibility.
 */
public class BridgeRemarkTest {

    private static final Address FUNDED = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    @Test
    public void lock_address_constant_matches_the_derivation() {
        assertEquals("0x3109ff8cf0be958a428c12d86c0abf64f529f7db",
                BridgeConstants.LOCK_ADDRESS_20.toHexString());
        assertEquals(20, BridgeConstants.LOCK_ADDRESS_20.size());
    }

    @Test
    public void encodes_to_the_external_vectors() {
        assertEquals("2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1", BridgeRemark.encode(FUNDED));
        assertEquals("2RueRbXvwjnXoWxfFFesBWpWV3P8h6BK", BridgeRemark.encode(
                Address.fromHexString("0x3535353535353535353535353535353535353535")));
        assertEquals("2SrgkEeRYqh57C6az4ZAFLpF8cSnrugm", BridgeRemark.encode(
                Address.fromHexString("0xffffffffffffffffffffffffffffffffffffffff")));
        assertEquals("2RfCqRDrtGgVuWAeeBxjFxzLjJYDtyhd", BridgeRemark.encode(
                Address.fromHexString("0x0000000000000000000000000000000000000000")));
    }

    @Test
    public void encoded_remark_always_fits_the_32_byte_field() {
        assertTrue(BridgeRemark.encode(FUNDED).length() <= 32);
        assertTrue(BridgeRemark.encode(
                Address.fromHexString("0xffffffffffffffffffffffffffffffffffffffff")).length() <= 32);
    }

    @Test
    public void decodes_a_zero_padded_remark_field() {
        byte[] remark = new byte[32];
        byte[] ascii = "2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, remark, 0, ascii.length); // 32 chars fill the field exactly
        assertEquals(Optional.of(FUNDED), BridgeRemark.decode(remark));
    }

    @Test
    public void round_trips_any_address() {
        Address a = Address.fromHexString("0x00000000000000000000000000000000cafebabe");
        byte[] remark = new byte[32];
        byte[] ascii = BridgeRemark.encode(a).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, remark, 0, ascii.length); // shorter strings stay zero-padded
        assertEquals(Optional.of(a), BridgeRemark.decode(remark));
    }

    @Test
    public void rejects_null_empty_garbage_and_tampering() {
        assertTrue(BridgeRemark.decode(null).isEmpty());
        assertTrue(BridgeRemark.decode(new byte[32]).isEmpty());                       // all zeros
        assertTrue(BridgeRemark.decode(pad("hello world")).isEmpty());                 // not base58 payload
        assertTrue(BridgeRemark.decode(pad("2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA2")).isEmpty()); // bad checksum
        assertTrue(BridgeRemark.decode(pad("0OIl+/not-base58-chars!!")).isEmpty());    // invalid alphabet
        // A NATIVE address string (Base58Check, 24-byte payload, 33 chars) gets truncated to 32
        // remark bytes and must fail decode (length/checksum), never alias into an EVM target.
        assertTrue(BridgeRemark.decode(pad("PKcBtHWDSnAWfZntqWPBLedqBShuKSTzS")).isEmpty());
    }

    @Test
    public void rejects_wrong_version_byte() {
        // Re-encode the funded address with version 0x46 and a VALID checksum over it: structure
        // ok, version wrong -> must be rejected (the version byte is load-bearing, not decorative).
        org.apache.tuweni.bytes.Bytes payload = org.apache.tuweni.bytes.Bytes.concatenate(
                org.apache.tuweni.bytes.Bytes.of(0x46), FUNDED);
        org.apache.tuweni.bytes.Bytes ck =
                org.hyperledger.besu.crypto.Hash.keccak256(payload).slice(0, 2);
        String s = io.xdag.crypto.encoding.Base58.encode(
                org.apache.tuweni.bytes.Bytes.concatenate(payload, ck));
        assertTrue(BridgeRemark.decode(pad(s)).isEmpty());
    }

    private static byte[] pad(String s) {
        byte[] remark = new byte[32];
        byte[] ascii = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, remark, 0, Math.min(ascii.length, 32));
        return remark;
    }
}
```

- [ ] **Step 2: Run to verify failure** — `mvn test -Dtest=BridgeRemarkTest` → COMPILE ERROR (classes missing). Red.

- [ ] **Step 3: Implement the three production files** (MIT headers on each):

`BridgeConstants.java`:

```java
package io.xdag.evm.bridge;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;

/**
 * Protocol constants of the XDAG<->EVM bridge (spec 2026-08-19 §1). The lock address is a
 * nothing-up-my-sleeve constant: the last 20 bytes of keccak256("XDAG-EVM-BRIDGE-LOCK-v1").
 * No private key exists for it (finding one breaks keccak/EC), so native funds sent there can
 * only ever leave via the Phase-3b protocol release rule. Hardcoded, NOT config: a config
 * mismatch between nodes would be a chain split.
 */
public final class BridgeConstants {

    /** 20-byte native lock address: 0x3109ff8cf0be958a428c12d86c0abf64f529f7db. */
    public static final Bytes LOCK_ADDRESS_20 =
            Hash.keccak256(Bytes.wrap("XDAG-EVM-BRIDGE-LOCK-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    .slice(12, 20);

    /** Wei per nano-XDAG: 1 XDAG = 10^18 wei and 1 XDAG = 10^9 nano, so 1 nano = 10^9 wei. */
    public static final java.math.BigInteger WEI_PER_NANO = java.math.BigInteger.valueOf(1_000_000_000L);

    private BridgeConstants() {
    }
}
```

`BridgeRemark.java`:

```java
package io.xdag.evm.bridge;

import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

/**
 * Encodes a 20-byte EVM deposit target into the 32-byte ASCII native remark field and back
 * (spec §2.1): {@code base58( 0x45 ‖ addr20 ‖ keccak256(0x45‖addr20)[0:2] )}. The 23-byte
 * payload encodes to at most 32 chars (standard Base58Check's 24-byte payload is 33 chars and
 * does NOT fit — that is why this custom format exists). The 0x45 ('E') version byte makes a
 * pasted NATIVE address string structurally undecodable here instead of silently aliasing.
 */
public final class BridgeRemark {

    private static final byte VERSION = 0x45;
    private static final int PAYLOAD_LENGTH = 23;

    private BridgeRemark() {
    }

    /** The <=32-char ASCII remark string for a deposit to {@code target}. */
    public static String encode(Address target) {
        Bytes versioned = Bytes.concatenate(Bytes.of(VERSION), target);
        Bytes checksum = Hash.keccak256(versioned).slice(0, 2);
        return Base58.encode(Bytes.concatenate(versioned, checksum));
    }

    /**
     * Decodes a raw 32-byte remark field; empty on ANY failure (null, padding-only, bad base58,
     * wrong length, wrong version, bad checksum). Pure and deterministic — consensus code calls
     * this, so there must be no environment-dependent behavior.
     */
    public static Optional<Address> decode(byte[] remark) {
        if (remark == null) {
            return Optional.empty();
        }
        int end = remark.length;
        while (end > 0 && remark[end - 1] == 0) {
            end--;
        }
        if (end == 0) {
            return Optional.empty();
        }
        String ascii = new String(remark, 0, end, StandardCharsets.US_ASCII);
        Bytes payload;
        try {
            payload = Base58.decode(ascii);
        } catch (AddressFormatException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if (payload.size() != PAYLOAD_LENGTH || payload.get(0) != VERSION) {
            return Optional.empty();
        }
        Bytes versioned = payload.slice(0, 21);
        if (!Hash.keccak256(versioned).slice(0, 2).equals(payload.slice(21, 2))) {
            return Optional.empty();
        }
        return Optional.of(Address.wrap(payload.slice(1, 20)));
    }
}
```

`BridgeDeposit.java`:

```java
package io.xdag.evm.bridge;

import org.hyperledger.besu.datatypes.Address;

/**
 * One confirmed native deposit to the bridge lock address, already resolved to its EVM mint
 * target (the decoded remark target, or the network's recovery address for an undecodable
 * remark — every deposit mints, spec §2.2). Amount is in nano-XDAG; mint wei = nano * 10^9.
 */
public record BridgeDeposit(Address target, long amountNano) {
}
```

- [ ] **Step 4: Run** — `mvn test -Dtest=BridgeRemarkTest` → 7/7 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/xdag/evm/bridge src/test/java/io/xdag/evm/bridge
git commit -m "feat(evm): bridge lock-address constant and remark codec (phase 3a)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Bridge config (activation height + recovery address with fail-fast)

**Files:**
- Modify: `src/main/java/io/xdag/config/spec/EvmSpec.java` (below `getEvmType2ActivationHeight()`)
- Modify: `src/main/java/io/xdag/config/AbstractConfig.java` (field ~:167 area, getter ~:216 area, parse ~:400 area)
- Modify: `src/main/resources/xdag-devnet.conf` AND `src/test/resources/xdag-devnet.conf` (BOTH — shadow copy wins per-key)
- Test: `src/test/java/io/xdag/config/EvmConfigSectionTest.java`

- [ ] **Step 1: Failing tests** in `EvmConfigSectionTest` (follow its per-network style):

```java
    // devnet assertions:
    assertEquals(0L, spec.getEvmBridgeActivationHeight());
    assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", spec.getEvmBridgeRecoveryAddress());
    // testnet AND mainnet assertions:
    assertEquals(Long.MAX_VALUE, spec.getEvmBridgeActivationHeight());
    assertNull(spec.getEvmBridgeRecoveryAddress());
```

Plus a fail-fast test (mirror the file's `parseEvmAlloc`-style `ConfigFactory.parseString` tests if present, else assertThrows over a config load):

```java
    @Test
    public void bridge_activation_without_recovery_address_fails_fast() {
        Config c = ConfigFactory.parseString("evm.bridgeActivationHeight = 5");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }

    @Test
    public void bridge_recovery_address_must_be_20_byte_hex() {
        Config c = ConfigFactory.parseString(
                "evm.bridgeActivationHeight = 5\nevm.bridgeRecoveryAddress = \"0x1234\"");
        assertThrows(IllegalStateException.class, () -> AbstractConfig.validateBridgeConfig(c));
    }
```

- [ ] **Step 2: Run to verify failure** — `mvn test -Dtest=EvmConfigSectionTest` → compile error / red.

- [ ] **Step 3: Implement.**

`EvmSpec.java`:

```java
    /** Height at which the XDAG<->EVM bridge activates; Long.MAX_VALUE = not scheduled. */
    long getEvmBridgeActivationHeight();

    /**
     * EVM address minted to when a deposit's remark cannot be decoded (spec §2.2). Hex string
     * (0x + 40); null only when the bridge is not scheduled — networks that activate the bridge
     * MUST set it (fail-fast at config load).
     */
    String getEvmBridgeRecoveryAddress();
```

`AbstractConfig.java` — field/getter/parse next to the type-2 siblings, plus an extracted testable validator (parseEvmAlloc precedent):

```java
    protected long evmBridgeActivationHeight = Long.MAX_VALUE;
    protected String evmBridgeRecoveryAddress;

    @Override
    public long getEvmBridgeActivationHeight() {
        return evmBridgeActivationHeight;
    }

    @Override
    public String getEvmBridgeRecoveryAddress() {
        return evmBridgeRecoveryAddress;
    }

        // in the evm.* parse section:
        validateBridgeConfig(config);
        evmBridgeActivationHeight = config.hasPath("evm.bridgeActivationHeight")
                ? config.getLong("evm.bridgeActivationHeight") : evmBridgeActivationHeight;
        evmBridgeRecoveryAddress = config.hasPath("evm.bridgeRecoveryAddress")
                ? config.getString("evm.bridgeRecoveryAddress") : evmBridgeRecoveryAddress;

    /**
     * A scheduled bridge without a recovery address (or with a malformed one) is a
     * misconfiguration that would strand mis-remarked deposits — refuse to start (S-36
     * fund.address precedent). Static so the rule is unit-testable without a full config load.
     */
    static void validateBridgeConfig(com.typesafe.config.Config config) {
        boolean scheduled = config.hasPath("evm.bridgeActivationHeight")
                && config.getLong("evm.bridgeActivationHeight") != Long.MAX_VALUE;
        if (!scheduled) {
            return;
        }
        if (!config.hasPath("evm.bridgeRecoveryAddress")) {
            throw new IllegalStateException("Missing required configuration 'evm.bridgeRecoveryAddress'. "
                    + "A network that schedules evm.bridgeActivationHeight must set the recovery address.");
        }
        String addr = config.getString("evm.bridgeRecoveryAddress");
        if (!addr.matches("0x[0-9a-fA-F]{40}")) {
            throw new IllegalStateException(
                    "evm.bridgeRecoveryAddress must be a 0x-prefixed 20-byte hex address, got: " + addr);
        }
    }
```

Both devnet confs (next to `evm.type2ActivationHeight = 0`, keep the two files in sync):

```hocon
# XDAG<->EVM bridge (defect-3 phase 3a): deposits activate at genesis on devnet; testnet/mainnet
# omit it (= not scheduled). The recovery address receives mints whose remark cannot be decoded
# (consensus value - all nodes must agree). Devnet uses the funded test address.
evm.bridgeActivationHeight = 0
evm.bridgeRecoveryAddress = "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
```

- [ ] **Step 4: Run** — `mvn test -Dtest=EvmConfigSectionTest` → ALL PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(evm): bridge activation + recovery-address config with fail-fast validation\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 3: EVM_META deposit records (prefix 0x06)

**Files:**
- Modify: `src/main/java/io/xdag/evm/state/EvmMetaStore.java`
- Test: `src/test/java/io/xdag/evm/state/EvmMetaStoreTest.java`

- [ ] **Step 1: Failing tests** (follow the file's InMemoryKVSource + assertion style):

```java
    @Test
    public void deposit_records_round_trip_in_order() {
        List<BridgeDeposit> deposits = List.of(
                new BridgeDeposit(Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"), 5L),
                new BridgeDeposit(Address.fromHexString("0x3535353535353535353535353535353535353535"), 7_000_000_000L));
        store.putDeposits(3L, deposits);
        assertEquals(deposits, store.getDeposits(3L));
        assertEquals(List.of(), store.getDeposits(4L)); // absent height -> empty list
    }

    @Test
    public void deposit_records_are_cleared_by_removeAbove() {
        store.putDeposits(2L, List.of(new BridgeDeposit(Address.ZERO, 1L)));
        store.putDeposits(5L, List.of(new BridgeDeposit(Address.ZERO, 2L)));
        store.removeAbove(2L);
        assertEquals(1, store.getDeposits(2L).size()); // kept: at the rollback point
        assertEquals(List.of(), store.getDeposits(5L)); // wiped: above it
    }
```

- [ ] **Step 2: Run to verify failure** → compile error. Red.

- [ ] **Step 3: Implement in `EvmMetaStore`.** New prefix + fixed 28-byte entries (20-byte address ‖ 8-byte big-endian nano), mirroring the `putTxList` idiom:

```java
    /** Bridge deposits (spec §2.2): 0x06 | height(8 BE) -> concatenated (address20 | amountNano 8 BE). */
    private static final byte PREFIX_DEPOSITS = 0x06;

    private static byte[] depositsKey(long height) {
        byte[] key = new byte[9];
        key[0] = PREFIX_DEPOSITS;
        System.arraycopy(Longs.toByteArray(height), 0, key, 1, 8);   // use the file's existing height-key helper style
        return key;
    }

    /** Persists the height's ordered deposit list (part of the replay script; write-once per height). */
    public void putDeposits(long height, List<BridgeDeposit> deposits) {
        byte[] value = new byte[deposits.size() * 28];
        int pos = 0;
        for (BridgeDeposit d : deposits) {
            System.arraycopy(d.target().toArray(), 0, value, pos, 20);
            System.arraycopy(Longs.toByteArray(d.amountNano()), 0, value, pos + 20, 8);
            pos += 28;
        }
        store.put(depositsKey(height), value);
    }

    /** The height's ordered deposits; empty list when none were recorded. */
    public List<BridgeDeposit> getDeposits(long height) {
        byte[] raw = store.get(depositsKey(height));
        if (raw == null) {
            return List.of();
        }
        if (raw.length % 28 != 0) {
            throw new IllegalStateException("corrupt deposit record at height " + height);
        }
        List<BridgeDeposit> out = new ArrayList<>(raw.length / 28);
        for (int pos = 0; pos < raw.length; pos += 28) {
            Address target = Address.wrap(Bytes.wrap(raw, pos, 20));
            long nano = Longs.fromByteArray(Arrays.copyOfRange(raw, pos + 20, pos + 28));
            out.add(new BridgeDeposit(target, nano));
        }
        return out;
    }
```

IMPORTANT: read the file first — reuse its ACTUAL height-key/long-encoding helpers (it may use its own `heightKey`/BE conversion instead of Guava `Longs`; match whatever `putTxList` uses so the idiom stays uniform). Extend `removeAbove` with a `PREFIX_DEPOSITS` sweep exactly like the `PREFIX_TX_LIST` one at `:196`, and the corrupt-record validation style should match the file's existing checks. Imports: `io.xdag.evm.bridge.BridgeDeposit`, Besu `Address`.

- [ ] **Step 4: Run** — `mvn test -Dtest=EvmMetaStoreTest` → ALL PASS (existing + 2 new).

- [ ] **Step 5: Commit** — `git commit -m "feat(evm): EVM_META deposit records under prefix 0x06 with removeAbove symmetry\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 4: Processor mints deposits (persist → mint before txs → replay)

**Files:**
- Modify: `src/main/java/io/xdag/evm/EvmBlockProcessor.java`
- Test: `src/test/java/io/xdag/evm/EvmBlockProcessorTest.java`

- [ ] **Step 1: read first** — `EvmBlockProcessor.java` in full (processMainBlock :166, onBlobsAvailable :192, executeAndCheckpoint, executeList, rollbackTo) and the test file's harness (stores, funding, `processMainBlock` call style, root/receipt assertions).

- [ ] **Step 2: Failing tests** (adapt helper names; scenarios + assertions exact):

```java
    @Test
    public void deposit_mints_before_the_heights_txs_so_a_funded_sender_can_spend_same_height() {
        // Sender S has ZERO prior EVM balance. Height 1 carries BOTH:
        //   deposits = [ (S, 1_000_000_000 nano) ]  -> mints 1e18 wei
        //   txRefs   = [ legacy transfer from S, value 1234, gasPrice 1, gasLimit 21000 ]
        // If mint ordering were wrong (after txs), the tx would fail "balance below value + gas fee".
        // Assert: tx receipt status 1; S balance == 1e18 - 1234 - 21000; recipient got 1234.
    }

    @Test
    public void deposit_only_height_checkpoints_and_advances_the_chained_root() {
        // processMainBlock(List.of(), height 1, ts, hash, deposits=[(A, 5 nano)]) — NO tx refs.
        // Assert: A's balance == 5e9 wei; a height-1 checkpoint/root exists and differs from
        // the pre-existing root (read root the same way existing root tests do).
    }

    @Test
    public void deposit_replay_is_byte_identical_after_rollback() {
        // Execute heights 1 (deposit + tx) and 2 (tx). Record root(2). rollbackTo(0) — wait:
        // follow the file's existing replay-determinism test EXACTLY (it rolls back and asserts
        // the replayed chained roots match byte-for-byte); the only new ingredient is that
        // height 1 carries a deposit, which replay must re-mint from the 0x06 record.
    }

    @Test
    public void rollback_wipes_a_deposited_balance() {
        // Deposit to fresh address B at height 1 (no txs). rollbackTo(0). Assert B's balance is
        // ZERO again (EVM_STATE wiped; no height-1 replay), and getDeposits(1) is empty
        // (removeAbove swept 0x06).
    }
```

Write all four bodies fully against the file's real helpers. Run → red (5-arg processMainBlock missing).

- [ ] **Step 3: Implement in `EvmBlockProcessor`.**

3a. Signature: the 4-arg `processMainBlock` becomes a delegator; the new 5-arg is the real entry:

```java
    public synchronized void processMainBlock(List<Bytes32> txRefs, long height, long timestampSeconds,
                                              Bytes32 blockHash) {
        processMainBlock(txRefs, height, timestampSeconds, blockHash, List.of());
    }

    public synchronized void processMainBlock(List<Bytes32> txRefs, long height, long timestampSeconds,
                                              Bytes32 blockHash, List<BridgeDeposit> deposits) {
        boolean hasRefs = txRefs != null && !txRefs.isEmpty();
        boolean hasDeposits = deposits != null && !deposits.isEmpty();
        if (!hasRefs && !hasDeposits) {
            return;
        }
        if (height < activationHeight) {
            // Before the EVM hard fork nothing here has consensus meaning (spec §3.1). The caller
            // gates deposits by bridgeActivationHeight >= (its own schedule); this guard only
            // protects against a bridge scheduled before the EVM itself, which is a nonsensical
            // config — ignoring is deterministic either way.
            log.warn("Ignoring EVM payload in pre-activation main block at height {} (activates at {})",
                    height, activationHeight);
            return;
        }
        if (hasDeposits) {
            // Write-once per height, BEFORE any defer: a stalled height must still mint its
            // deposits when it later drains (executeList reads the 0x06 record).
            metaStore.putDeposits(height, deposits);
        }
        Expansion exp = hasRefs ? expandRefs(txRefs) : new Expansion(List.of(), List.of(), List.of());
        if (!metaStore.pendingHeights().isEmpty() || !exp.complete()) {
            metaStore.putPending(height, blockHash, timestampSeconds, txRefs == null ? List.of() : txRefs);
            log.warn("Deferring EVM execution of main block at height {} until blobs arrive", height);
            return;
        }
        executeAndCheckpoint(exp.flat(), height, timestampSeconds, blockHash);
    }
```

3b. Mint inside `executeList` (the shared live+replay path), at the TOP, before the tx loop, applying to the same root updater the height's txs use (so mints and executions share ONE commit and the chained root's state delta covers both):

```java
        // Bridge deposits mint FIRST (spec §2.2): a same-height tx may spend deposited funds.
        // Reading from EVM_META (not a parameter) makes replay identical to live execution.
        for (BridgeDeposit deposit : metaStore.getDeposits(height)) {
            MutableAccount account = root.getOrCreate(deposit.target());
            Wei minted = Wei.of(java.math.BigInteger.valueOf(deposit.amountNano())
                    .multiply(BridgeConstants.WEI_PER_NANO));
            account.setBalance(account.getBalance().add(minted));
        }
```

Place it inside `executeList` immediately after the root updater for the height exists and before the first tx executes; match the local names in the actual method. Note `Wei.add` exists on Besu Wei (UInt256 add) — if not, use `Wei.of(account.getBalance().getAsBigInteger().add(mintedWei))`.

3c. `rollbackTo`: no change needed — verify by reading that its replay drives `executeList` (which now re-mints) and that `removeAbove` (Task 3) wipes 0x06 above the rollback point. If the replay path constructs `executeList` calls per height, confirm the deposit read keys off the SAME height variable. State this verification result in your report.

- [ ] **Step 4: Run** — `mvn test -Dtest=EvmBlockProcessorTest` → ALL PASS (36 + 4 new = 40).

- [ ] **Step 5: Commit** — `git commit -m "feat(evm): deposits mint before txs in execution order, replay-covered via EVM_META 0x06\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 5: Native-side detection and setMain hand-off

**Files:**
- Modify: `src/main/java/io/xdag/core/BlockchainImpl.java` (applyBlock `:1061`/`:1087`/`:1180-1183`, setMain `:1327-1344`)
- Test: `src/test/java/io/xdag/core/BridgeDepositIntegrationTest.java` (create)

- [ ] **Step 1: read first.** (a) `BlockchainImpl` applyBlock + setMain regions; (b) how existing integration tests craft a NATIVE transfer block that credits a wallet address — grep `src/test/java/io/xdag/core` for the transaction-block generators (e.g. `generateTransactionBlock`, `createNewBlock` test usage, and how `EvmConsensusIntegrationTest`/`BlockchainTest` build wallets, fund senders, and drive `setMain`/`checkNewMain`); the transfer helper must accept a remark (the `createNewBlock(..., remark)` path exists — `:1453`). This is the highest-uncertainty seam of the plan: report NEEDS_CONTEXT if no native-transfer-with-remark test precedent exists rather than inventing block bytes.

- [ ] **Step 2: Failing integration test** — `BridgeDepositIntegrationTest` (follow the harness of the closest existing full-chain test found in Step 1; MIT header; scenarios):

```java
    @Test
    public void deposit_with_valid_remark_mints_to_the_remark_target() {
        // Fund native wallet W. W transfers 5 XDAG to the lock address (base58 display form of
        // BridgeConstants.LOCK_ADDRESS_20) with remark "2SFWAZL75Ejuc1MQZsyDT7kB7bjtsgA1"
        // (= funded EVM addr 0x7e5f...bdf). Drive main blocks until the transfer confirms.
        // Assert: EVM balance of 0x7e5f...bdf increased by exactly
        //   creditedNano * 1e9 wei, where creditedNano mirrors applyBlock's fee-adjusted credit
        //   (read the lock address's native balance delta and use it as the expected base -
        //   that pins the "mint == what the lock actually received" property without
        //   re-deriving the fee rule in the test).
    }

    @Test
    public void deposit_with_undecodable_remark_mints_to_the_recovery_address() {
        // Same flow, remark "hello" (undecodable). Assert the RECOVERY address
        // (0x7e5f...bdf on devnet - use a DIFFERENT valid-remark target in the first test if
        // the harness recovery == funded collide; pick target 0x3535...35 with its vector
        // "2RueRbXvwjnXoWxfFFesBWpWV3P8h6BK" there so the two tests discriminate).
    }

    @Test
    public void pre_activation_deposit_does_not_mint() {
        // Config override (anonymous DevnetConfig, the established pattern) with
        // getEvmBridgeActivationHeight() = Long.MAX_VALUE. Same transfer flow.
        // Assert: no EVM mint anywhere (target + recovery unchanged); native lock balance
        // still credited (it is just a normal transfer pre-activation).
    }
```

(Adjust the first test's target per the inline note so recovery-vs-target is discriminating.) Run → red (compile: collection plumbing absent).

- [ ] **Step 3: Implement in `BlockchainImpl`.**

3a. Thread a deposit collector through `applyBlock` (exact mirror of the `evmRefs` threading):
- signature `:1061` → `private XAmount applyBlock(boolean flag, Block block, List<Bytes32> evmRefs, List<BridgeDeposit> deposits)`
- recursive call `:1087` → pass `deposits` through
- OUTPUT branch `:1180-1183` — collect exactly where the credit happens:

```java
                } else if (link.getType() == XDAG_FIELD_OUTPUT) {
                    XAmount credited = link.getAmount().subtract(outPutLimit(block));
                    addAmount(BasicUtils.hash2byte(linkAddress), credited, block);
                    blockGas = blockGas.add(outPutLimit(block));
                    if (BasicUtils.hash2byte(linkAddress).equals(BridgeConstants.LOCK_ADDRESS_20)
                            && credited.isPositive()) {
                        // Bridge deposit (spec §2.2): resolve the mint target NOW (remark decode is
                        // pure; recovery address is a consensus config), keep DFS order.
                        Address target = BridgeRemark.decode(block.getInfo().getRemark())
                                .orElseGet(() -> Address.fromHexString(
                                        kernel.getConfig().getEvmSpec().getEvmBridgeRecoveryAddress()));
                        deposits.add(new BridgeDeposit(target,
                                credited.toDecimal(0, XUnit.NANO_XDAG).longValueExact()));
                    }
                }
```

(Import `io.xdag.evm.bridge.*`, Besu `Address` — CAREFUL: `Address` already means `io.xdag.core.Address` in this file; use the fully qualified `org.hyperledger.besu.datatypes.Address` for the EVM target locals, do NOT add a clashing import.)

3b. `setMain` `:1327-1344`:

```java
            List<Bytes32> evmRefs = new ArrayList<>();
            List<BridgeDeposit> deposits = new ArrayList<>();
            XAmount mainBlockFee = applyBlock(true, block, evmRefs, deposits);
            ...
            // Deposits are consensus-gated HERE by the bridge activation height (exact, not
            // predicted: mainNumber is the confirmed height). Pre-activation deposits are plain
            // transfers - retained at the lock address, never minted retroactively (spec §1).
            if (mainNumber < kernel.getConfig().getEvmSpec().getEvmBridgeActivationHeight()) {
                deposits = List.of();
            }
            if (evmProcessor != null && (!evmRefs.isEmpty() || !deposits.isEmpty())) {
                evmProcessor.processMainBlock(evmRefs, mainNumber, timestampSeconds,
                        Bytes32.wrap(block.getInfo().getHash()), deposits);
            }
```

(Merge with the existing structure — keep the `kernel == null` guard; other `applyBlock` callers, if any exist beyond setMain, get an empty ArrayList: grep `applyBlock(` and update every call site.)

- [ ] **Step 4: Run** — `mvn test -Dtest='BridgeDepositIntegrationTest,EvmConsensusIntegrationTest,MainBlockEvmPackingTest,MinerPackingSeamTest'` → ALL PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(core): detect lock-address deposits in applyBlock and hand them to the EVM processor\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"`

---

### Task 6: Operator surface, docs, full-suite regression

**Files:**
- Modify: `src/main/java/io/xdag/Kernel.java` (EVM init log, ~:226)
- Modify: `.claude/docs/smart-contract-design-and-implementation.md` (§13.1 缺陷 3 bullet)
- Modify: `docs/superpowers/specs/2026-08-19-evm-bridge-design.md` (status header only)

- [ ] **Step 1: Kernel startup log.** In the EVM-init block (after `seedGenesisIfAbsent()` ~:225), when `getEvmBridgeActivationHeight() != Long.MAX_VALUE`, log the deposit coordinates so operators/users can find them without reading code:

```java
            if (config.getEvmSpec().getEvmBridgeActivationHeight() != Long.MAX_VALUE) {
                log.info("XDAG<->EVM bridge deposits active from height {}: lock address {} (native base58 {})",
                        config.getEvmSpec().getEvmBridgeActivationHeight(),
                        io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20,
                        io.xdag.crypto.encoding.Base58.encodeCheck(
                                io.xdag.evm.bridge.BridgeConstants.LOCK_ADDRESS_20));
            }
```

(The base58 display form is what users paste into a wallet's "to" field — it is the standard native address encoding of the 20 lock bytes; verify `encodeCheck` is what `BasicUtils:111` uses for native address display and reuse exactly that call.)

- [ ] **Step 2: Design-doc bullet.** In `.claude/docs/smart-contract-design-and-implementation.md` §13.1 缺陷 3, append a `- **已实现（Phase 3a 入金，2026-08-19，dev-evm）**：…` bullet (缺陷 1/2 style) covering: 锁定地址协议常量 `0x3109ff8cf0be958a428c12d86c0abf64f529f7db`（keccak("XDAG-EVM-BRIDGE-LOCK-v1") 末 20 字节，无私钥）；remark 编码 `base58(0x45‖addr20‖keccak2)` 恒 32 字符（标准 Base58Check 33 字符放不下的勘误）；applyBlock OUTPUT 分支按 DFS 序收集（金额=实际入账的扣费后值）、setMain 按确认高度门控（`evm.bridgeActivationHeight` devnet=0）；EVM_META 0x06 进重放脚本、executeList 先 mint 后执行（同高度可花）、rollbackTo 对称免费；非法 remark → `evm.bridgeRecoveryAddress`（必填 fail-fast）；1 nano = 10⁹ wei 无损。出金（Phase 3b：系统合约 + N 深度释放）未实现。Update the spec header 状态 line to note Phase 3a delivered (date + commit range).

- [ ] **Step 3: FULL suite:**

```bash
mvn clean test 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD" | tail -3
```

Expected: `Tests run: 47x, Failures: 0, Errors: 0` (baseline 460 + ~15 new) and `BUILD SUCCESS`. Any existing-test failure = regression — STOP, report, don't paper over.

- [ ] **Step 4: Commit** (docs need -f):

```bash
git add src/main/java/io/xdag/Kernel.java
git add -f .claude/docs/smart-contract-design-and-implementation.md docs/superpowers/specs/2026-08-19-evm-bridge-design.md
git commit -m "feat(evm): bridge deposit operator logging; document phase 3a delivery

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Deviation ledger

Record every helper-name adaptation, line-number drift, or API mismatch in the final report. The known-risky seam is Task 5's native transfer-with-remark test crafting — NEEDS_CONTEXT over invention. Product-code defects found on the way: STOP and ask the user (standing rule).
