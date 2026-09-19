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
package io.xdag.chain.ingest;

import static io.xdag.config.Constants.BI_EXTRA;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_COINBASE;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.Kernel;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.net.ChannelManager;
import io.xdag.net.PeerClient;
import io.xdag.net.node.Node;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * C1 (SP0b-2): the chain must never import the very {@link Block} instance the arriving
 * {@link BlockWrapper} holds. The relay re-serializes that instance with {@code toBytes()} after
 * the import returns, while the chain goes on mutating what it imported — a BI_EXTRA block stays
 * alive in {@code memOrphanPool} and collects {@code setFee}/{@code setHeight}/flag writes from
 * {@code setMain}, {@code unSetMain} and {@code unApplyBlock}. Sharing one instance would gossip
 * bytes that are not the bytes received (the fee lives inside the hashed, signed header) and would
 * race on non-final fields.
 *
 * <p>{@code SyncManager.importBlock}'s re-parse used to provide that isolation; since SP0b-2 it is
 * {@code PreValidator.compute} that parses the private copy, on the pool thread. The equivalence
 * test structurally cannot see any of this: its wrappers carry ttl 0, so {@code distributeBlock}
 * never runs and nothing ever re-serializes a block.
 */
public class IngestBlockIsolationTest extends ChainL1TestBase {

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.setTxHistoryStore(null);
    }

    private SyncManager syncManager() {
        ChannelManager channels = mock(ChannelManager.class);
        when(channels.getActiveChannels()).thenReturn(List.of());
        kernel.setChannelMgr(channels);
        PeerClient client = mock(PeerClient.class);
        when(client.getNode()).thenReturn(new Node("127.0.0.1", 8001));
        kernel.setClient(client);
        kernel.setBlockchain(blockchain);
        return new SyncManager(kernel);
    }

    /**
     * The header fee the arriving block is signed with. Non-zero on purpose: the chain sets the fee
     * of a BI_EXTRA block it imports to zero, and the fee lives in the hashed header, so this is
     * what makes the byte comparison below able to fail at all. With the {@code XAmount.ZERO} this
     * block used to carry, the import had nothing left to change in its 512 bytes — flags and height
     * live outside them — and the assertion was vacuous.
     */
    private static final XAmount ARRIVING_FEE = XAmount.of(1024, XUnit.NANO_XDAG);

    /**
     * A block of the shape {@code BlockchainImpl.isExtraBlock} accepts — end-of-epoch timestamp and
     * a nonce — round-tripped through its 512 bytes, as one arriving from a peer is. No difficulty
     * search: this block only has to import, not to win the chain top.
     */
    private Block arrivingExtraBlock(long nonceSeed) {
        List<Address> pending = new ArrayList<>();
        pending.add(new Address(topRef, XDAG_FIELD_OUT, false));
        pending.add(new Address(BasicUtils.keyPair2Hash(poolKey), XDAG_FIELD_COINBASE, true));
        long xdagTime = XdagTime.getEndOfEpoch(XdagTime.msToXdagtimestamp(generateTime + 64000L));
        Block template = new Block(config, xdagTime, null, pending, true, null, null, -1, ARRIVING_FEE, null);
        template.signOut(poolKey);
        byte[] nonceBytes = new byte[32];
        ExtCodec.putU64(nonceBytes, 0, nonceSeed);
        template.setNonce(Bytes32.wrap(nonceBytes));
        return new Block(new XdagBlock(template.toBytes()));
    }

    @Test
    public void theChainNeverImportsTheWrappersOwnBlockInstance() throws Exception {
        for (int i = 0; i < 2; i++) {
            mineMain(List.of());
        }
        Block received = arrivingExtraBlock(4242L);
        assertEquals("the arriving block must carry a fee the import can zero", ARRIVING_FEE, received.getFee());
        // Everything that reads the block is done here, before the hand-off: getHashLow() and
        // toBytes() are lazy mutators themselves, and after submit() the block belongs to the pool.
        Bytes32 hashLow = hashLow(received);
        byte[] bytesAsReceived = received.toBytes();

        SyncManager sync = syncManager();
        AtomicReference<ImportResult> committed = new AtomicReference<>();
        AtomicReference<Block> importedInstance = new AtomicReference<>();
        IngestPipeline pipeline = new IngestPipeline(2, 8, pv -> {
            importedInstance.set(pv.block());
            ImportResult r = sync.importPreValidated(pv);
            committed.set(r);
            return r;
        });
        pipeline.start();
        pipeline.submit(new BlockWrapper(received, 0));
        assertTrue("the pipeline did not drain", pipeline.awaitIdle(30, TimeUnit.SECONDS));
        pipeline.stop();

        ImportResult result = committed.get();
        assertTrue("import failed: " + result,
                result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST);

        Block inChain = blockchain.getBlockByHash(hashLow, true);
        assertTrue("the block should have been kept as an extra block", (inChain.getInfo().getFlags() & BI_EXTRA) != 0);
        assertNotSame("the chain imported the instance the relay re-serializes", received, inChain);
        assertNotSame("pre-validation handed the chain the wrapper's own block", received, importedInstance.get());
        assertEquals("the received block picked up the chain's flags", 0, received.getInfo().getFlags());
        // The mutation the byte comparison is looking for: the chain zeroes the fee of the block it
        // imports, and the fee is part of the hashed header the relay re-serializes.
        assertEquals("the import must have zeroed the fee of the block it kept",
                XAmount.ZERO, importedInstance.get().getFee());
        assertArrayEquals("the bytes left to relay are no longer the bytes received",
                bytesAsReceived, received.toBytes());
    }
}
