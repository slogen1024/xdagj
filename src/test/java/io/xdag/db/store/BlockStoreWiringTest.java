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

package io.xdag.db.store;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static io.xdag.BlockBuilder.generateAddressBlock;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Pins the on-disk layout that {@link BlockStoreImpl#forNode(DatabaseFactory)} stands for: a node
 * keeps its raw block bytes in the database NAMED {@code TIME} and its time index in the one NAMED
 * {@code BLOCK}. The names read backwards, but they are what every existing node has on disk, so
 * "correcting" the argument order would silently orphan every one of those stores — and every
 * offline tool that opened them by the signature order instead (--repairchain did, before this
 * factory existed) finds no raw bytes at all.
 */
public class BlockStoreWiringTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    @Test
    public void forNodeStoresRawBlocksInTheDatabaseNamedTime() throws Exception {
        Config config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        DatabaseFactory factory = new RocksdbFactory(config);
        try {
            BlockStoreImpl store = BlockStoreImpl.forNode(factory);
            store.start();
            Block block = generateAddressBlock(config, ECKeyPair.generate(), System.currentTimeMillis());
            store.saveBlock(block);

            byte[] hashLow = block.getHashLow().toArray();
            byte[] raw = factory.getDB(DatabaseName.TIME).get(hashLow);
            assertNotNull("raw block bytes must land in the database NAMED TIME", raw);
            assertArrayEquals(block.getXdagBlock().getData().toArray(), raw);
            assertNull("the database NAMED BLOCK holds the time index, keyed by time+hash, not by hash",
                    factory.getDB(DatabaseName.BLOCK).get(hashLow));

            // And the store reads its own writes back: the roles are swapped consistently, not lost.
            assertArrayEquals(block.toBytes(), store.getBlockByHash(block.getHashLow(), true).toBytes());
        } finally {
            factory.close();
        }
    }
}
