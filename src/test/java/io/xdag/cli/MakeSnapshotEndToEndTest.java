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

package io.xdag.cli;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1SnapshotGate;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.RocksdbKVSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class MakeSnapshotEndToEndTest extends ChainL1TestBase {

    @Test
    public void makeSnapshotWritesThreeDirectoriesThatBootAnotherNode() throws Exception {
        for (int i = 0; i < 5; i++) {
            mineMain(List.of());
        }
        ChainBlockBuilder.Built deploy = deployNewChain(payload(700, 61), payload(10, 62));
        importBuilt(deploy);
        mineMain(List.of(hashLow(deploy.block())));
        confirm(deploy.block());
        Bytes chainId = ChainIds.chainIdOf(deploy.block().getHash());
        assertTrue(chainStore.hasChain(chainId));
        Bytes32 expectedHash = chainStore.stateHash();
        long height = blockchain.getXdagStats().nmain;

        // release the stores so the CLI can open them (same directories)
        chainStore.stop();
        dbFactory.close();
        dbFactory = null;
        chainStore = null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            XdagCli cli = spy(new XdagCli());
            cli.setConfig(config);
            cli.makeSnapshot(false);
        } finally {
            System.setOut(old);
        }
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed, printed.contains("snapshot height: " + height));
        assertTrue(printed, printed.contains("next start frame:"));
        assertTrue(printed, printed.contains("chain state snapshot written to"));

        Path snap = Paths.get(config.getNodeSpec().getStoreDir(), "SNAPSHOT");
        assertTrue(Files.isDirectory(snap.resolve("BLOCKS")));
        assertTrue(Files.isDirectory(snap.resolve("ADDRESS")));
        assertTrue(Files.isDirectory(snap.resolve("CHAIN_L1")));

        RocksdbKVSource snapChain = new RocksdbKVSource(ChainL1SnapshotGate.SNAPSHOT_DB_NAME);
        snapChain.setConfig(config);
        snapChain.init();
        try {
            assertEquals(expectedHash, Bytes32.wrap(snapChain.get(ChainL1Keys.SNAPSHOT_HASH_KEY)));
        } finally {
            snapChain.close();
        }

        // a second node bootstraps from the shipped directory
        String otherRoot = root.newFolder("other").getAbsolutePath();
        // DevnetConfig, not Config: setRootDir is a Lombok setter on AbstractConfig, not part of the interface.
        DevnetConfig other = new DevnetConfig();
        other.setRootDir(otherRoot);
        other.getNodeSpec().setStoreDir(otherRoot + "/rocksdb/xdagdb");
        other.getChainSpec().setChainActivationHeight(0);
        Files.createDirectories(Paths.get(other.getNodeSpec().getStoreDir(), "SNAPSHOT"));
        XdagCli.copyDir(snap.resolve("CHAIN_L1").toString(),
                Paths.get(other.getNodeSpec().getStoreDir(), "SNAPSHOT", "CHAIN_L1").toString());
        RocksdbKVSource otherSrc = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        otherSrc.setConfig(other);
        ChainL1Store otherStore = new ChainL1Store(otherSrc);
        otherStore.start();
        try {
            ChainL1SnapshotGate.checkAndImport(other, height, otherStore);
            assertEquals(expectedHash, otherStore.stateHash());
            assertTrue(otherStore.hasChain(chainId));
        } finally {
            otherStore.stop();
        }
    }
}
