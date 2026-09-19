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

package io.xdag.net;

import static io.xdag.BlockBuilder.generateAddressBlock;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PrivateKey;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.message.consensus.BlockRequestMessage;
import io.xdag.net.message.consensus.NewBlockMessage;
import io.xdag.net.message.consensus.SyncBlockMessage;
import io.xdag.net.message.consensus.SyncBlockRequestMessage;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

/**
 * D2: the node used to serve peers the very {@link Block} instance the chain keeps in
 * {@code memOrphanPool} and goes on mutating. {@code NewBlockMessage}/{@code SyncBlockMessage}
 * re-encode from that instance's mutable fields, and {@code info.fee} is part of the hashed header
 * — which {@code tryToConnect} zeroes immediately before putting the block in the pool.
 */
public class BlockServeTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private final Config config = new DevnetConfig();
    private Wallet wallet;
    private Kernel kernel;
    private BlockchainImpl blockchain;
    private XdagP2pHandler handler;
    private MessageQueue msgQueue;

    private final PrivateKey poolSecret = PrivateKey.fromBigInteger(
            new BigInteger("10a55f0c18c46873ddbf9f15eddfc06f10953c601fd144474131199e04148046", 16));
    private final PrivateKey addrSecret = PrivateKey.fromBigInteger(
            new BigInteger("c85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4", 16));

    @Before
    public void setUp() throws Exception {
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        wallet = new Wallet(config);
        wallet.unlock("password");
        ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        wallet.setAccounts(Collections.singletonList(key));
        wallet.flush();

        kernel = new Kernel(config, key);
        DatabaseFactory dbFactory = new RocksdbFactory(config);
        BlockStore blockStore = BlockStoreImpl.forNode(dbFactory);
        blockStore.reset();
        OrphanBlockStore orphanBlockStore =
                new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND), kernel);
        orphanBlockStore.reset();

        kernel.setBlockStore(blockStore);
        kernel.setOrphanBlockStore(orphanBlockStore);
        kernel.setWallet(wallet);

        blockchain = new QuietBlockchain(kernel);
        kernel.setBlockchain(blockchain);

        msgQueue = mock(MessageQueue.class);
        Channel channel = mock(Channel.class);
        when(channel.getMessageQueue()).thenReturn(msgQueue);
        handler = new XdagP2pHandler(channel, kernel);
    }

    @After
    public void tearDown() throws Exception {
        wallet.delete();
    }

    /**
     * A block request for a block the chain is still holding in {@code memOrphanPool} must be
     * answered with the bytes that arrived — the bytes that hash to the hash the peer asked for —
     * and never with the live instance itself.
     */
    @Test
    public void aBlockRequestIsServedFromTheArrivedBytes() {
        Pooled pooled = importPooledBlock();

        handler.processBlockRequest(new BlockRequestMessage(
                pooled.block.getHashLow().mutableCopy(), blockchain.getXdagStats()));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(msgQueue).sendMessage(captor.capture());
        NewBlockMessage sent = (NewBlockMessage) captor.getValue();

        byte[] served = new SimpleDecoder(sent.getBody()).readBytes();
        assertArrayEquals("a peer was served bytes that do not hash to the hash it asked for",
                pooled.arrivedBytes, served);
        assertEquals(pooled.block.getHashLow().toHexString(),
                new Block(new XdagBlock(served)).getHashLow().toHexString());
        assertNotSame("the node handed the wire the live instance the chain is mutating",
                pooled.live, sent.getBlock());
    }

    /**
     * The sync-block request path serves the same block through a different message; it must copy
     * too. The execution-state hint still comes from the live instance's flags, as before.
     */
    @Test
    public void aSyncBlockRequestIsServedFromTheArrivedBytes() {
        Pooled pooled = importPooledBlock();

        handler.processSyncBlockRequest(new SyncBlockRequestMessage(
                pooled.block.getHashLow().mutableCopy(), blockchain.getXdagStats()));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(msgQueue).sendMessage(captor.capture());
        SyncBlockMessage sent = (SyncBlockMessage) captor.getValue();

        byte[] served = new SimpleDecoder(sent.getBody()).readBytes();
        assertArrayEquals("a peer was served bytes that do not hash to the hash it asked for",
                pooled.arrivedBytes, served);
        assertNotSame("the node handed the wire the live instance the chain is mutating",
                pooled.live, sent.getBlock());
    }

    /**
     * {@code getBlockByHash} is public and is read off the blockchain monitor by netty threads, RPC
     * and the award thread, so {@code memOrphanPool} has to be safe for concurrent readers. Holding
     * the pool's own lock must stop a reader, which is what a plain {@code LinkedHashMap} — written
     * under the monitor and read under nothing — never did.
     */
    @Test(timeout = 30_000)
    public void readingAPooledBlockGoesThroughThePoolLock() throws Exception {
        Pooled pooled = importPooledBlock();

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            started.countDown();
            blockchain.getBlockByHash(pooled.block.getHashLow(), true);
            finished.countDown();
        }, "pool-reader");

        synchronized (blockchain.getMemOrphanPool()) {
            reader.start();
            assertTrue("the reader thread never started", started.await(10, TimeUnit.SECONDS));
            assertFalse("memOrphanPool is read with no lock at all",
                    finished.await(500, TimeUnit.MILLISECONDS));
        }

        assertTrue("the read never completed once the pool lock was released",
                finished.await(10, TimeUnit.SECONDS));
        reader.join();
    }

    /** What {@code processExtraBlock} relies on: the oldest extra block is the first entry. */
    @Test
    public void theOrphanPoolStillIteratesInInsertionOrder() {
        Block address = generateAddressBlock(config, ECKeyPair.fromPrivateKey(addrSecret), 1600616700000L);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(address));

        ECKeyPair poolKey = ECKeyPair.fromPrivateKey(poolSecret);
        long generateTime = 1600616700000L;
        List<Bytes> inserted = Lists.newArrayList();
        for (int i = 0; i < 5; i++) {
            generateTime += 64000L;
            // All five reference the address block, never each other: an extra block that links
            // another extra block evicts it from the pool on the way in.
            Block extra = feeCarryingExtraBlock(poolKey, generateTime, address.getHashLow(), "ext" + i);
            Block arrived = new Block(new XdagBlock(extra.toBytes()));
            ImportResult result = blockchain.tryToConnect(arrived);
            assertTrue("import failed: " + result, result == IMPORTED_BEST || result == IMPORTED_NOT_BEST);
            inserted.add(arrived.getHashLow().copy());
        }

        List<Bytes> order;
        synchronized (blockchain.getMemOrphanPool()) {
            order = Lists.newArrayList(blockchain.getMemOrphanPool().keySet());
        }
        assertEquals(inserted.size(), order.size());
        for (int i = 0; i < inserted.size(); i++) {
            assertEquals(inserted.get(i).toHexString(), order.get(i).toHexString());
        }
    }

    private Pooled importPooledBlock() {
        Block address = generateAddressBlock(config, ECKeyPair.fromPrivateKey(addrSecret), 1600616700000L);
        assertSame(IMPORTED_BEST, blockchain.tryToConnect(address));

        Block built = feeCarryingExtraBlock(ECKeyPair.fromPrivateKey(poolSecret),
                1600616700000L + 64000L, address.getHashLow(), "serve");
        byte[] arrivedBytes = built.toBytes();
        Block arrived = new Block(new XdagBlock(arrivedBytes.clone()));

        // The point of the fixture: the header really does carry a fee, so zeroing info.fee is
        // visible in the re-encoded bytes.
        assertNotEquals(XAmount.ZERO, arrived.getInfo().getFee());

        assertSame(IMPORTED_BEST, blockchain.tryToConnect(arrived));

        Block live;
        synchronized (blockchain.getMemOrphanPool()) {
            live = blockchain.getMemOrphanPool().get(arrived.getHashLow());
        }
        assertTrue("the block under test is not in memOrphanPool", live != null);
        assertEquals("tryToConnect did not zero the pooled block's fee",
                XAmount.ZERO, live.getInfo().getFee());

        return new Pooled(arrived, live, arrivedBytes);
    }

    /**
     * An extra block (end-of-epoch timestamp plus a nonce) whose header carries a non-zero fee.
     * {@code isExtraBlock} looks at the timestamp and the nonce only, so such a block reaches
     * {@code memOrphanPool} and has its {@code info.fee} zeroed on the way in.
     */
    private Block feeCarryingExtraBlock(ECKeyPair key, long generateTimeMs, Bytes32 ref, String salt) {
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTimeMs));
        List<Address> pendings = Lists.newArrayList(new Address(ref, XDAG_FIELD_OUT, false));
        Block b = new Block(config, xdagTime, null, pendings, true, null, null, -1,
                XAmount.of(100, XUnit.MILLI_XDAG), null);
        b.signOut(key);
        b.setNonce(HashUtils.sha256(Bytes.wrap(Hex.encode(salt.getBytes()))));
        return b;
    }

    private record Pooled(Block block, Block live, byte[] arrivedBytes) {
    }

    /** The check-main loop would confirm blocks underneath these tests; nothing here needs it. */
    private static class QuietBlockchain extends BlockchainImpl {

        QuietBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public void startCheckMain(long period) {
        }
    }
}
