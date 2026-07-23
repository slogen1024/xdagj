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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.db.rocksdb.RocksdbFactory;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Q-22: exercises {@code clearStorage()} / {@code deleteAccount()} of {@link RocksDbWorldUpdater}
 * against a REAL {@link io.xdag.db.rocksdb.RocksdbKVSource} ({@link DatabaseName#EVM_STATE}) — not the
 * in-memory test double — proving that prior storage slots are physically removed from the store and do
 * not resurrect when an account is recreated in the same commit (S-28).
 *
 * <p>This test loads the native RocksDB library, so it MUST be run on the project's JDK 21 / Maven
 * build (it cannot run under the JDK 17 sandbox used for the patch). It also validates the production
 * prefix-scan path ({@code prefixKeyLookup}) used by the updater's commit to wipe storage prefixes.
 */
public class RocksDbStorageWipeTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Address addr = Address.fromHexString("0x1111111111111111111111111111111111111111");

    private Config config;
    private DatabaseFactory dbFactory;
    private KVSource<byte[], byte[]> store;

    @Before
    public void setUp() throws Exception {
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());
        dbFactory = new RocksdbFactory(config);
        store = dbFactory.getDB(DatabaseName.EVM_STATE);
        store.init();
    }

    @After
    public void tearDown() {
        dbFactory.close();
    }

    /** Seed an account at {@link #addr} with the given storage slots and flush it to the store. */
    private void seedAccountWithSlots(long nonce, Wei balance, UInt256... slotsThenValues) {
        RocksDbWorldUpdater rootUpdater = new RocksDbWorldUpdater(store);
        WorldUpdater tx = rootUpdater.updater();
        MutableAccount a = tx.createAccount(addr, nonce, balance);
        for (int i = 0; i < slotsThenValues.length; i += 2) {
            a.setStorageValue(slotsThenValues[i], slotsThenValues[i + 1]);
        }
        tx.commit();
        rootUpdater.commit();
    }

    @Test
    public void deleteAccount_physically_wipes_persisted_storage_slots() {
        seedAccountWithSlots(1L, Wei.fromEth(2),
                UInt256.ONE, UInt256.valueOf(111),
                UInt256.valueOf(2), UInt256.valueOf(222));
        assertFalse("precondition: slots must be persisted",
                store.prefixKeyLookup(EvmStateSchema.storagePrefix(addr)).isEmpty());

        // SELFDESTRUCT-style: delete the account and commit the deletion through to the store.
        RocksDbWorldUpdater del = new RocksDbWorldUpdater(store);
        WorldUpdater tx = del.updater();
        tx.deleteAccount(addr);
        tx.commit();
        del.commit();

        assertNull("account header must be gone", new RocksDbWorldUpdater(store).getAccount(addr));
        assertTrue("every storage slot must be physically removed after deleteAccount",
                store.prefixKeyLookup(EvmStateSchema.storagePrefix(addr)).isEmpty());
    }

    @Test
    public void recreate_after_delete_does_not_resurrect_old_storage() {
        seedAccountWithSlots(1L, Wei.fromEth(2),
                UInt256.ONE, UInt256.valueOf(111),
                UInt256.valueOf(2), UInt256.valueOf(222),
                UInt256.valueOf(3), UInt256.valueOf(333));

        // In a SINGLE updater: delete the account, then immediately recreate it and set ONLY slot 1.
        // Without the S-28 fix the recreated account keeps storageCleared == false, so slots 2 and 3
        // survive the commit and "resurrect" under the new account.
        RocksDbWorldUpdater rootUpdater = new RocksDbWorldUpdater(store);
        WorldUpdater tx = rootUpdater.updater();
        tx.deleteAccount(addr);
        MutableAccount fresh = tx.createAccount(addr, 0L, Wei.ZERO);
        fresh.setStorageValue(UInt256.ONE, UInt256.valueOf(999));
        tx.commit();
        rootUpdater.commit();

        RocksDbWorldUpdater reopened = new RocksDbWorldUpdater(store);
        MutableAccount reloaded = reopened.getAccount(addr);
        assertNotNull("recreated account must exist", reloaded);
        assertEquals("the freshly written slot must persist",
                UInt256.valueOf(999), reloaded.getStorageValue(UInt256.ONE));
        assertEquals("slot 2 must NOT resurrect", UInt256.ZERO, reloaded.getStorageValue(UInt256.valueOf(2)));
        assertEquals("slot 3 must NOT resurrect", UInt256.ZERO, reloaded.getStorageValue(UInt256.valueOf(3)));

        // And at the physical layer: only slot 1's key should remain under the storage prefix.
        assertEquals("exactly one storage slot should remain in the store",
                1, store.prefixKeyLookup(EvmStateSchema.storagePrefix(addr)).size());
    }
}
