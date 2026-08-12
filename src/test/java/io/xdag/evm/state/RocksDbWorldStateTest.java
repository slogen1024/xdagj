/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.evm.state;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.EvmConfig;
import io.xdag.evm.XdagEvmExecutor;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.Before;
import org.junit.Test;

/** Proves the RocksDB-backed world state serialises, persists, and reloads account/code/storage. */
public class RocksDbWorldStateTest {

    private InMemoryKVSource store;
    private final Address addr = Address.fromHexString("0x1111111111111111111111111111111111111111");
    private final Address sender = Address.fromHexString("0x23a6049381fd2cfb0661d9de206613b83d53d7df");

    @Before
    public void setUp() {
        store = new InMemoryKVSource();
    }

    @Test
    public void account_code_storage_round_trip_through_store() {
        // Write via a child updater, commit child -> root, then root -> store.
        RocksDbWorldUpdater root = new RocksDbWorldUpdater(store);
        WorldUpdater tx = root.updater();
        MutableAccount a = tx.createAccount(addr, 7L, Wei.fromEth(5));
        a.setCode(Bytes.fromHexString("0x602a600055")); // PUSH1 0x2a PUSH1 0x00 SSTORE
        a.setStorageValue(UInt256.ZERO, UInt256.valueOf(42));
        a.setStorageValue(UInt256.ONE, UInt256.valueOf(1000));
        tx.commit();
        root.commit();

        // A brand-new updater over the SAME store must see the persisted state.
        RocksDbWorldUpdater reopened = new RocksDbWorldUpdater(store);
        Account reloaded = reopened.getAccount(addr);
        assertNotNull("account must survive the store round-trip", reloaded);
        assertEquals(7L, reloaded.getNonce());
        assertEquals(Wei.fromEth(5), reloaded.getBalance());
        assertEquals(Bytes.fromHexString("0x602a600055"), reloaded.getCode());
        assertEquals(UInt256.valueOf(42), reloaded.getStorageValue(UInt256.ZERO));
        assertEquals(UInt256.valueOf(1000), reloaded.getStorageValue(UInt256.ONE));
    }

    @Test
    public void commit_digest_reflects_persisted_state_and_child_returns_zero() {
        // Phase-1 world-state commitment: commitAndDigest() returns a deterministic keccak over exactly
        // the (puts, deletes) it persists, so folding it into the chained state root makes a balance /
        // storage divergence change the root — the pre-fix root over (txHash, status, gasUsed) could not.
        Bytes32 rich = digestOfNewAccount(Wei.fromEth(2));
        Bytes32 richAgain = digestOfNewAccount(Wei.fromEth(2));
        Bytes32 poor = digestOfNewAccount(Wei.fromEth(1));

        assertEquals("identical persisted state must yield an identical digest", rich, richAgain);
        assertNotEquals("a balance-only difference must change the digest", rich, poor);
        assertNotEquals("a non-empty delta must not digest to zero", Bytes32.ZERO, rich);

        // Only the root touches the store; a child updater persists nothing, so it has no delta.
        RocksDbWorldUpdater root = new RocksDbWorldUpdater(new InMemoryKVSource());
        assertEquals(Bytes32.ZERO, ((RocksDbWorldUpdater) root.updater()).commitAndDigest());
    }

    /** Persists one fresh account with the given balance to an empty store; returns the commit digest. */
    private Bytes32 digestOfNewAccount(Wei balance) {
        RocksDbWorldUpdater root = new RocksDbWorldUpdater(new InMemoryKVSource());
        root.createAccount(addr, 1L, balance);
        return root.commitAndDigest();
    }

    @Test
    public void commit_digest_counts_a_deleted_slot_once_regardless_of_path() {
        // Regression for the reference-equality `deletes` set: a storage slot that is both zeroed AND
        // cleared in the same commit must be digested ONCE — identically to a commit that only clears
        // it — since both persist the byte-identical world state. Before the fix the double-add path
        // digested the key twice, making the state root depend on an implementation accident.
        UInt256 slot = UInt256.valueOf(5);
        Bytes32 clearOnly = digestOfResurrection(slot, false);
        Bytes32 zeroThenClear = digestOfResurrection(slot, true);

        assertEquals("a deleted slot must be digested once regardless of how it was reached",
                clearOnly, zeroThenClear);
    }

    /**
     * Persists {@code addr} with a non-zero {@code slot}, then in a fresh updater deletes and re-creates
     * the account (which sets isStorageCleared), optionally also writing the slot to zero — the exact
     * combination that made the buggy reference-set add the slot key to `deletes` twice. Returns the
     * commit digest.
     */
    private Bytes32 digestOfResurrection(UInt256 slot, boolean alsoZeroTheSlot) {
        InMemoryKVSource s = new InMemoryKVSource();
        RocksDbWorldUpdater seed = new RocksDbWorldUpdater(s);
        seed.createAccount(addr, 1L, Wei.ZERO).setStorageValue(slot, UInt256.valueOf(100));
        seed.commit();

        RocksDbWorldUpdater root = new RocksDbWorldUpdater(s);
        root.deleteAccount(addr);
        MutableAccount recreated = root.createAccount(addr, 2L, Wei.ZERO); // isStorageCleared() set
        if (alsoZeroTheSlot) {
            recreated.setStorageValue(slot, UInt256.ZERO); // zero-write of a slot the store still holds
        }
        return root.commitAndDigest();
    }

    @Test
    public void evm_execution_persists_storage_via_executor() {
        XdagEvmExecutor evm = new XdagEvmExecutor(EvmConfig.devnet());

        // Seed: fund sender, and place SSTORE bytecode (slot0 = 42) at the contract address.
        RocksDbWorldUpdater seed = new RocksDbWorldUpdater(store);
        WorldUpdater u = seed.updater();
        u.createAccount(sender, 0L, Wei.fromEth(1));
        MutableAccount c = u.createAccount(addr, 1L, Wei.ZERO);
        c.setCode(Bytes.fromHexString("0x602a600055"));
        u.commit();
        seed.commit();

        // Execute the contract through the executor against a fresh root over the same store.
        var result = evm.call(new RocksDbWorldUpdater(store), sender, addr, Bytes.EMPTY, Wei.ZERO, 1_000_000L);
        assertTrue("SSTORE execution should succeed", result.success());

        // Reload and confirm the slot was persisted by the executor's commit.
        RocksDbWorldUpdater reopened = new RocksDbWorldUpdater(store);
        assertEquals(UInt256.valueOf(42), reopened.getAccount(addr).getStorageValue(UInt256.ZERO));
    }

    @Test
    public void simulated_call_executes_but_never_persists_storage() {
        // Regression for the eth_call/eth_estimateGas state-mutation bug: a simulated call must run
        // to completion (so it can return data / a gas figure) yet leave the store byte-for-byte
        // unchanged. Before the fix the executor committed the root updater and this SSTORE landed on
        // disk, letting an unauthenticated eth_call rewrite contract storage out-of-band from consensus.
        XdagEvmExecutor evm = new XdagEvmExecutor(EvmConfig.devnet());

        // Seed: fund sender, place SSTORE bytecode (slot0 = 42) at the contract; slot0 starts absent.
        RocksDbWorldUpdater seed = new RocksDbWorldUpdater(store);
        WorldUpdater u = seed.updater();
        u.createAccount(sender, 0L, Wei.fromEth(1));
        MutableAccount c = u.createAccount(addr, 1L, Wei.ZERO);
        c.setCode(Bytes.fromHexString("0x602a600055")); // PUSH1 0x2a PUSH1 0x00 SSTORE
        u.commit();
        seed.commit();

        var result = evm.simulateCall(new RocksDbWorldUpdater(store), sender, addr, Bytes.EMPTY, Wei.ZERO,
                1_000_000L);
        assertTrue("the simulated SSTORE still executes in memory", result.success());

        // Nothing was written: slot0 is still absent (zero), not 42.
        RocksDbWorldUpdater reopened = new RocksDbWorldUpdater(store);
        assertEquals("eth_call must not mutate persistent EVM state",
                UInt256.ZERO, reopened.getAccount(addr).getStorageValue(UInt256.ZERO));
    }

    @Test
    public void simulated_deploy_executes_but_never_persists_nonce_or_code() {
        // Regression: eth_estimateGas of a contract creation must not bump the sender nonce or deposit
        // code on disk. Before the fix deploy() committed unconditionally, so even a simulated deploy
        // persisted the nonce bump and the deployed code.
        XdagEvmExecutor evm = new XdagEvmExecutor(EvmConfig.devnet());

        RocksDbWorldUpdater seed = new RocksDbWorldUpdater(store);
        WorldUpdater u = seed.updater();
        u.createAccount(sender, 0L, Wei.fromEth(1));
        u.commit();
        seed.commit();

        // Minimal init code returning empty runtime: PUSH1 0x00 PUSH1 0x00 RETURN.
        Bytes initCode = Bytes.fromHexString("0x60006000f3");
        var result = evm.simulateDeploy(new RocksDbWorldUpdater(store), sender, initCode, Wei.ZERO, 1_000_000L);
        assertTrue(result.success());

        RocksDbWorldUpdater reopened = new RocksDbWorldUpdater(store);
        assertEquals("eth_estimateGas of a deploy must not bump the on-disk sender nonce",
                0L, reopened.getAccount(sender).getNonce());
        assertNull("a simulated deploy must not persist any contract account",
                reopened.getAccount(Address.contractAddress(sender, 0L)));
    }

    @Test
    public void commitAndDigestCapturesPriorValues() {
        InMemoryKVSource store = new InMemoryKVSource();
        Address addr = Address.fromHexString("0x00000000000000000000000000000000000000aa");

        // Height 1: create the account (prior = absent). Capture its journal.
        RocksDbWorldUpdater h1 = new RocksDbWorldUpdater(store);
        h1.createAccount(addr, 0L, Wei.of(100));
        List<EvmStateJournal.Entry> j1 = new ArrayList<>();
        h1.commitAndDigest(j1);
        // The account key must be journaled with a null (absent) prior value.
        assertEquals(1, j1.size());
        assertNull(j1.get(0).priorValue());

        // Height 2: change the balance. The prior value must be the height-1 account record.
        byte[] priorRecord = store.get(EvmStateSchema.accountKey(addr));
        RocksDbWorldUpdater h2 = new RocksDbWorldUpdater(store);
        h2.getAccount(addr).setBalance(Wei.of(250));
        List<EvmStateJournal.Entry> j2 = new ArrayList<>();
        h2.commitAndDigest(j2);
        assertEquals(1, j2.size());
        assertArrayEquals(EvmStateSchema.accountKey(addr), j2.get(0).key());
        assertArrayEquals(priorRecord, j2.get(0).priorValue());
    }

    @Test
    public void noArgCommitAndDigestStillReturnsSameDelta() {
        InMemoryKVSource a = new InMemoryKVSource();
        InMemoryKVSource b = new InMemoryKVSource();
        Address addr = Address.fromHexString("0x00000000000000000000000000000000000000bb");

        RocksDbWorldUpdater ua = new RocksDbWorldUpdater(a);
        ua.createAccount(addr, 1L, Wei.of(5));
        RocksDbWorldUpdater ub = new RocksDbWorldUpdater(b);
        ub.createAccount(addr, 1L, Wei.of(5));

        // The journaling overload must not change the digest the consensus root depends on.
        assertEquals(ua.commitAndDigest(), ub.commitAndDigest(new ArrayList<>()));
    }

    @Test
    public void commitAndDigestJournalsDeletedSlotPriorValue() {
        // Exercises the `deletes` capture loop (distinct from the puts loop above): zeroing a stored
        // slot removes its key, and the reverse-delta must record the slot's prior value so history
        // can restore it. Touching the account also re-puts the account key, so the journal holds more
        // than one entry — assert by locating the storage key, not by size.
        InMemoryKVSource s = new InMemoryKVSource();
        Address acct = Address.fromHexString("0x00000000000000000000000000000000000000cc");
        UInt256 slot = UInt256.valueOf(3);

        RocksDbWorldUpdater h1 = new RocksDbWorldUpdater(s);
        h1.createAccount(acct, 0L, Wei.of(1)).setStorageValue(slot, UInt256.valueOf(11));
        h1.commitAndDigest();

        byte[] storageKey = EvmStateSchema.storageKey(acct, slot);
        byte[] priorSlot = s.get(storageKey); // the height-1 encoded slot value
        assertNotNull(priorSlot);

        RocksDbWorldUpdater h2 = new RocksDbWorldUpdater(s);
        h2.getAccount(acct).setStorageValue(slot, UInt256.ZERO); // zero -> routed into `deletes`
        List<EvmStateJournal.Entry> j2 = new ArrayList<>();
        h2.commitAndDigest(j2);

        EvmStateJournal.Entry deleted = j2.stream()
                .filter(e -> java.util.Arrays.equals(e.key(), storageKey))
                .findFirst().orElseThrow();
        assertArrayEquals(priorSlot, deleted.priorValue());
    }
}
