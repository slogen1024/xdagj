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

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.orphan.ChunkOrphanTestBase;
import io.xdag.config.AbstractConfig;
import io.xdag.config.Config;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.KVSource;
import io.xdag.net.Channel;
import io.xdag.net.ChannelManager;
import io.xdag.net.PeerClient;
import io.xdag.net.XdagP2pHandler;
import io.xdag.net.node.Node;
import io.xdag.utils.BytesUtils;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * The node-local chunk fee gate (design §5) as the two ingest paths see it.
 *
 * <p>The load-bearing test is {@link #aRequestedBlockBypassesThePolicy}. The safety argument for
 * refusing a block at all is "the main block that needs it will arrive and this node will pull it
 * back" — and the pull arrives through the very same ingest path, as an ordinary
 * {@code NewBlockMessage} indistinguishable from unsolicited gossip. A policy that applied to it
 * too would refuse the answer to this node's own question, and the node would sit at that height
 * for good. Everything else here is scaffolding for that one property.
 *
 * <h2>Why the CALL blocks reference nothing that exists</h2>
 *
 * <p>The gate reads the block's own extension field and walks the chunk chain it names; it never
 * looks up a chain record or a contract. A CALL naming a made-up chain and contract is therefore
 * the same input to the gate as a real one, and it keeps the fixture to the blocks the gate
 * actually reads. {@code ChainL1HooksIntegrationTest} imports exactly this shape through the chain
 * itself, so it is a block the DAG accepts, not a malformed one the gate could refuse by accident.
 */
public class ChunkFeePolicyTest extends ChunkOrphanTestBase {

    /** Arbitrary but fixed, so both halves of the on/off comparison build byte-identical blocks. */
    private static final Bytes CHAIN_ID = Bytes.fromHexString("0x1122334455667788990011223344556677889900");
    private static final Bytes CONTRACT = Bytes.fromHexString("0xaabbccddeeff00112233445566778899aabbccdd");
    /** One XDAG epoch in timestamp units. */
    private static final long EPOCH = 0x10000L;
    /** Args wide enough for a three-chunk chain (MAX_DATA_LEN is 352 bytes). */
    private static final int ARGS_BYTES = 1000;
    private static final int ARGS_CHUNKS = 3;
    /** Covers one of the three chunks: enough to be a real fee, not enough to cover the chain. */
    private static final XAmount UNDERPAID = XAmount.of(10, XUnit.MILLI_XDAG);
    /** Exactly the consensus minimum for a three-chunk chain. */
    private static final XAmount PAID = XAmount.of(30, XUnit.MILLI_XDAG);

    /** Read by {@link #newConfig()}; flipped by {@link #runScenario} between the two halves. */
    private boolean feePolicyEnabled = true;

    private SyncManager sync;
    /** Every hash this node asked a peer for, in order, as the fake channel saw it. */
    private final List<Bytes32> requested = Collections.synchronizedList(new ArrayList<>());
    /** Whether the fake channel manager reports a peer; flipped to reproduce a reconnect window. */
    private boolean channelsUp = true;

    @Override
    protected Config newConfig() {
        AbstractConfig cfg = (AbstractConfig) super.newConfig();
        cfg.setChainIngestFeePolicy(feePolicyEnabled);
        return cfg;
    }

    @Before
    public void wireTheNetworkEntryPoint() {
        sync = syncManager();
    }

    // ---- the four properties -------------------------------------------------------------

    /**
     * The one that decides whether the whole scheme stands up: a block this node asked for must
     * never be refused by this node's own policy. Otherwise the main block that references it can
     * never be satisfied and the node stops at that height for good.
     */
    @Test
    public void aRequestedBlockBypassesThePolicy() {
        ChainBlockBuilder.Built underpaid = underpaidCall(1);
        deliverChunks(underpaid);

        assertSame("an unsolicited underpaid block is refused",
                ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid.block()));

        ImportResult viaRequest = submitAsRequested(underpaid.block());
        assertTrue("a block this node asked for must never be refused by its own policy: " + viaRequest,
                viaRequest == ImportResult.IMPORTED_BEST || viaRequest == ImportResult.IMPORTED_NOT_BEST);
    }

    /**
     * End to end, with the policy on: a main block references a block this node refused, and the
     * node has to come out the other side one main block higher. The re-request is the node's own,
     * fired by the {@code NO_PARENT} arm; the fake channel records it and the test answers it the
     * way a peer would, with a plain new-block delivery carrying no mark of having been asked for.
     */
    @Test
    public void aMainBlockReferencingARefusedBlockStillSyncs() {
        ChainBlockBuilder.Built underpaid = underpaidCall(2);
        deliverChunks(underpaid);
        assertSame(ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid.block()));
        long nmainBefore = blockchain.getXdagStats().nmain;

        Block main = buildMain(List.of(hashLow(underpaid.block())));
        assertSame("the refused block is not in the DAG, so its referrer cannot connect yet",
                ImportResult.NO_PARENT, submitAsGossip(main));
        assertTrue("the node must ask its peers for the block it refused",
                requested.contains(hashLow(underpaid.block())));

        // The peer answers. A BlockRequestMessage is answered with a NewBlockMessage, so this is
        // byte for byte the delivery that was refused a moment ago.
        assertTrue("a block this node asked for must be admitted; refused again, the main block that "
                        + "references it can never connect and this node stays at height " + nmainBefore
                        + " for good",
                isImported(submitAsGossip(underpaid.block())));

        assertNotNull("the main block parked behind the refused block must have been released and imported",
                blockchain.getBlockByHash(main.getHashLow(), true));
        topRef = hashLow(main);
        confirm(main);
        assertTrue("the node must have moved past the height it refused at",
                blockchain.getXdagStats().nmain > nmainBefore);
    }

    /**
     * The error direction is benign. A paying block that arrives before its own chunks is the
     * normal order on a network that gossips both; the lenient count is then short, the required
     * fee is smaller than it will be, and the block is let through. Under-counting admits, it never
     * falsely refuses.
     */
    @Test
    public void aPayingBlockArrivingBeforeItsChunksIsAdmitted() {
        // Pays for one chunk of the three it names, and not one of them has been delivered.
        ChainBlockBuilder.Built underpaid = underpaidCall(3);

        assertNotSame("a block whose chunks this node has never seen must not be refused",
                ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid.block()));
    }

    /** The policy changes when a block is stored, never whether it ends up stored. */
    @Test
    public void theDagIsTheSameWithThePolicyOnAndOff() throws Exception {
        Snapshot on = runScenario(true);
        Snapshot off = runScenario(false);

        // Before comparing, check there is something to compare. Equality between two empty
        // snapshots is a pass this test must never be able to give, and a dump() that started
        // reading the wrong database or the wrong fixture would hand it exactly that.
        assertHasRows(on);
        assertHasRows(off);

        assertEquals(on, off);
    }

    private static void assertHasRows(Snapshot s) {
        assertTrue("INDEX is empty; the comparison would be vacuous", !s.index().isEmpty());
        assertTrue("BLOCK is empty; the comparison would be vacuous", !s.block().isEmpty());
        assertTrue("TIME is empty; the comparison would be vacuous", !s.time().isEmpty());
        assertTrue("ORPHANIND is empty; the comparison would be vacuous", !s.orphan().isEmpty());
        assertTrue("no main block was confirmed; the comparison would be vacuous", s.nmain() > 0);
        assertTrue("no block was accepted; the comparison would be vacuous", s.nblocks() > 0);
    }

    // ---- placement -----------------------------------------------------------------------

    /**
     * §5.3: the gate stands equally in front of both ingest paths. The tests above drive the
     * synchronous one; this drives the pipeline, which is what a node with
     * {@code chain.ingest.threads > 0} — every default node — actually runs.
     */
    @Test
    public void thePipelinePathRefusesWhatTheSynchronousPathRefuses() {
        ChainBlockBuilder.Built underpaid = underpaidCall(4);
        deliverChunks(underpaid);
        ChainBlockBuilder.Built paid = lightCall(9, PAID);
        deliverChunks(paid);

        sync.start();
        try {
            sync.submitBlock(gossip(underpaid.block()));
            sync.submitBlock(gossip(paid.block()));
        } finally {
            // Drains the pipeline and joins its commit thread, so both verdicts are final below
            // rather than merely not-yet-arrived.
            sync.stop();
        }

        assertNull("a refused block must not reach the DAG through the pipeline either",
                blockchain.getBlockByHash(underpaid.block().getHashLow(), true));
        assertNotNull("the control: a block that pays goes through the pipeline as usual",
                blockchain.getBlockByHash(paid.block().getHashLow(), true));
    }

    /** With the policy switched off the same unsolicited block is simply imported. */
    @Test
    public void theSwitchTurnsTheGateOff() throws Exception {
        feePolicyEnabled = false;
        freshFixture();

        ChainBlockBuilder.Built underpaid = underpaidCall(5);
        deliverChunks(underpaid);

        assertTrue("with chain.ingest.feePolicy=false nothing is refused",
                isImported(submitAsGossip(underpaid.block())));
    }

    /**
     * The age bound, which is a decision and not an inherited default. The gate counts with the
     * consensus bound — {@code epoch(payingBlock) - 1}, read off the paying block's own timestamp —
     * so a chain too old for the consensus fee check is too old for the gate as well, and a block
     * that names one is admitted however little it pays. Counted without the bound instead, the gate
     * would charge for chunks the consensus check will not, and would refuse blocks consensus
     * accepts: a false refusal, and the one direction §5.4's benign-error argument does not cover.
     */
    @Test
    public void aChainTooOldForTheConsensusFeeCheckIsTooOldForTheGate() {
        ChainBlockBuilder.Built old = underpaidCall(10);
        deliverChunks(old);
        Bytes32 argsHead = hashLow(old.chunks().get(0));

        // Three epochs above the chain: older than minEpoch = epoch(payingBlock) - 1, so the count
        // is zero even though all three chunk blocks are sitting right there in this node's pool.
        Block payer = callNaming(argsHead, txTime() + 3 * EPOCH, UNDERPAID, UInt64.valueOf(11));

        assertNotSame("a chain the consensus fee check will not charge for must not be charged for here",
                ImportResult.CHAIN_FEE_POLICY, submitAsGossip(payer));
    }

    /**
     * The traffic the refusal opens, damped. A refused block is deliberately not in the DAG, so
     * every block that references it parks and asks for it — and the ask goes to every active
     * channel. The record of what this node asked for is what stops the second waiter repeating the
     * first waiter's broadcast; without it the cost of one refused block is (waiters x channels)
     * messages for a request that is already outstanding.
     */
    @Test
    public void aSecondBlockWaitingOnTheSameParentDoesNotAskForItAgain() {
        ChainBlockBuilder.Built underpaid = underpaidCall(12);
        deliverChunks(underpaid);
        Bytes32 refused = hashLow(underpaid.block());
        assertSame(ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid.block()));

        assertSame(ImportResult.NO_PARENT, submitAsGossip(linkTo(refused, 12)));
        assertEquals("the first block to wait on it must ask for it", 1, requestsFor(refused));

        assertSame(ImportResult.NO_PARENT, submitAsGossip(linkTo(refused, 13)));
        assertEquals("a second block waiting on the same parent must not repeat the broadcast",
                1, requestsFor(refused));
    }

    /** A block that pays for every chunk this node can see is not the policy's business. */
    @Test
    public void aFullyPaidBlockIsNeverRefused() {
        ChainBlockBuilder.Built paid = lightCall(6, PAID);
        deliverChunks(paid);

        assertTrue("a block that covers its whole chain must be imported",
                isImported(submitAsGossip(paid.block())));
    }

    /**
     * The dampener must not suppress a request on the strength of one that never went out. The
     * record stamps its clock whether or not anything is listening, so a waiter that turns up while
     * this node has no peers — boot, a reconnect window, a partition — would otherwise record a
     * broadcast it could not send and mute the next 64 seconds of real ones, which is exactly the
     * window in which the first channel comes back.
     */
    @Test
    public void aRequestWithNobodyToSendItToDoesNotMuteTheNextOne() {
        ChainBlockBuilder.Built underpaid = underpaidCall(14);
        deliverChunks(underpaid);
        Bytes32 refused = hashLow(underpaid.block());
        assertSame(ImportResult.CHAIN_FEE_POLICY, submitAsGossip(underpaid.block()));

        channelsUp = false;
        assertSame(ImportResult.NO_PARENT, submitAsGossip(linkTo(refused, 14)));
        assertEquals("there was nobody to ask", 0, requestsFor(refused));

        channelsUp = true;
        assertSame(ImportResult.NO_PARENT, submitAsGossip(linkTo(refused, 15)));
        assertEquals("a request that was never sent must not suppress the first real one",
                1, requestsFor(refused));
    }

    /**
     * The record of what this node asked for is keyed by a hash a peer chose — the missing parent
     * named by a block it sent — so an unbounded one is a memory target. Past the bound the oldest
     * request is forgotten, which costs nothing permanent: the refusal it would have prevented
     * releases the waiters, which ask again and put it back.
     */
    @Test
    public void theRequestRecordIsBounded() {
        ChunkFeePolicy policy = sync.getFeePolicy();
        Bytes32 first = hashOf(0);
        policy.markRequested(first);
        assertTrue("the record must hold what was just put in it", policy.wasRequested(first));

        for (int i = 1; i <= ChunkFeePolicy.MAX_REQUESTED; i++) {
            policy.markRequested(hashOf(i));
        }

        assertFalse("the oldest request must be dropped once the bound is reached",
                policy.wasRequested(first));
        assertTrue("and the newest must still be there",
                policy.wasRequested(hashOf(ChunkFeePolicy.MAX_REQUESTED)));
    }

    private static Bytes32 hashOf(int i) {
        MutableBytes32 h = MutableBytes32.create();
        h.setInt(28, i);
        return Bytes32.wrap(h.toArray());
    }

    /** An ordinary transfer references no chunk chain, so the gate cannot have an opinion on it. */
    @Test
    public void aBlockWithNoChunkChainIsNeverRefused() {
        Block chunk = lightChunk(7);
        assertTrue(isImported(submitAsGossip(chunk)));
        assertTrue(isImported(submitAsGossip(linkTo(hashLow(chunk), 7))));
    }

    // ---- scenario ------------------------------------------------------------------------

    /**
     * What both halves of {@link #theDagIsTheSameWithThePolicyOnAndOff} run. With the policy on the
     * paying block is refused once and pulled back after its referrer asks for it; with the policy
     * off it is imported the first time. The premise assertions inside are what stop this from
     * becoming a comparison of two identical runs.
     */
    private Snapshot runScenario(boolean policyOn) throws Exception {
        feePolicyEnabled = policyOn;
        freshFixture();

        ChainBlockBuilder.Built underpaid = underpaidCall(8);
        deliverChunks(underpaid);

        ImportResult first = submitAsGossip(underpaid.block());
        if (policyOn) {
            assertSame("the scenario must actually exercise a refusal", ImportResult.CHAIN_FEE_POLICY, first);
        } else {
            assertTrue("with the policy off the same block is simply imported", isImported(first));
        }

        Block main = buildMain(List.of(hashLow(underpaid.block())));
        ImportResult mainResult = submitAsGossip(main);
        if (policyOn) {
            assertSame("the referrer must go looking for the refused block", ImportResult.NO_PARENT, mainResult);
            assertTrue("the scenario must actually exercise a pull",
                    requested.contains(hashLow(underpaid.block())));
            assertTrue(isImported(submitAsGossip(underpaid.block())));
        } else {
            assertTrue(isImported(mainResult));
        }
        blockchain.checkMain();
        return snapshot();
    }

    /** Everything the DAG is made of, as it reached RocksDB. */
    private record Snapshot(TreeMap<Bytes, Bytes> index, TreeMap<Bytes, Bytes> block, TreeMap<Bytes, Bytes> time,
                            TreeMap<Bytes, Bytes> orphan, long nmain, long nblocks, Bytes top) {
    }

    private Snapshot snapshot() {
        return new Snapshot(dump(DatabaseName.INDEX), dump(DatabaseName.BLOCK), dump(DatabaseName.TIME),
                dump(DatabaseName.ORPHANIND), blockchain.getXdagStats().nmain,
                blockchain.getXdagStats().nblocks, Bytes.wrap(blockchain.getXdagTopStatus().getTop()));
    }

    private TreeMap<Bytes, Bytes> dump(DatabaseName name) {
        DatabaseFactory factory = dbFactory;
        KVSource<byte[], byte[]> db = factory.getDB(name);
        TreeMap<Bytes, Bytes> m = new TreeMap<>();
        for (byte[] k : db.keys()) {
            m.put(Bytes.wrap(k), Bytes.wrap(db.get(k)));
        }
        return m;
    }

    // ---- fixture -------------------------------------------------------------------------

    /**
     * A second fixture on the same mining timeline, so the two halves of the comparison build
     * byte-identical blocks. {@code setUpChain} calls {@code root.newFolder("node")}, which throws
     * if the directory is still there.
     */
    private void freshFixture() throws Exception {
        long fixtureStart = 1600616700000L;
        tearDownChain();
        FileUtils.deleteDirectory(new File(root.getRoot(), "node"));
        generateTime = fixtureStart;
        setUpChain();
        armTheOrphanPool();
        requested.clear();
        channelsUp = true;
        sync = syncManager();
    }

    /**
     * The network entry point needs a wired kernel, and one channel that records what this node
     * asks for: the {@code NO_PARENT} arm broadcasts its request to every active channel, and the
     * exemption under test is armed by that request.
     */
    private SyncManager syncManager() {
        XdagP2pHandler handler = mock(XdagP2pHandler.class);
        when(handler.sendGetBlock(any(MutableBytes32.class), anyBoolean())).thenAnswer(call -> {
            requested.add(Bytes32.wrap(((MutableBytes32) call.getArgument(0)).toArray()));
            return 0L;
        });
        Channel channel = mock(Channel.class);
        when(channel.getP2pHandler()).thenReturn(handler);
        ChannelManager channels = mock(ChannelManager.class);
        when(channels.getActiveChannels()).thenAnswer(call -> channelsUp ? List.of(channel) : List.of());
        kernel.setChannelMgr(channels);
        PeerClient client = mock(PeerClient.class);
        when(client.getNode()).thenReturn(new Node("127.0.0.1", 8001));
        kernel.setClient(client);
        kernel.setBlockchain(blockchain);
        return new SyncManager(kernel);
    }

    /** As a {@code NewBlockMessage} arrives: nobody asked for it, and it is not a sync block. */
    private static BlockWrapper gossip(Block block) {
        return new BlockWrapper(block, 0, null, false);
    }

    private ImportResult submitAsGossip(Block block) {
        return sync.validateAndAddNewBlock(gossip(block));
    }

    /**
     * As the answer to a request this node made. The wrapper is identical to
     * {@link #gossip}'s — the answer to a {@code BlockRequestMessage} is a {@code NewBlockMessage}
     * and carries no mark at all — so what makes the difference is the node's own record of having
     * asked, which is what the {@code NO_PARENT} arm writes and what this reproduces.
     */
    private ImportResult submitAsRequested(Block block) {
        sync.getFeePolicy().markRequested(block.getHashLow());
        return sync.validateAndAddNewBlock(gossip(block));
    }

    private long requestsFor(Bytes32 hashLow) {
        synchronized (requested) {
            return requested.stream().filter(hashLow::equals).count();
        }
    }

    private static boolean isImported(ImportResult r) {
        return r == ImportResult.IMPORTED_BEST || r == ImportResult.IMPORTED_NOT_BEST;
    }

    /** Chunks tail first, straight into the DAG: what the gate counts, not what it judges. */
    private void deliverChunks(ChainBlockBuilder.Built built) {
        for (int i = built.chunks().size() - 1; i >= 0; i--) {
            assertImported(deliver(built.chunks().get(i)));
        }
    }

    /**
     * A CALL naming an existing chunk chain at a timestamp of the test's choosing — which
     * {@link ChainBlockBuilder#call} cannot do, since it always roots a fresh chain one tick below
     * the paying block. Same field layout as that builder's output (INPUT, OUTPUT, one OUT link to
     * the chain head, then the CALL extension), so the only thing that differs is the timestamp.
     */
    private Block callNaming(Bytes32 argsHead, long timestamp, XAmount headerFee, UInt64 nonce) {
        CallExt ext = new CallExt(CallExt.FLAG_ARGS_CHAIN, CONTRACT, 1, 100L, 0, Bytes.EMPTY, argsHead);
        List<Bytes32> fields = new ArrayList<>();
        fields.add(ext.encodeHeader());
        fields.addAll(ext.encodePayload());
        List<Address> refs = List.of(
                new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()), XDAG_FIELD_INPUT, ONE_XDAG, true),
                new Address(BytesUtils.arrayToByte32(CHAIN_ID.toArray()), XDAG_FIELD_OUTPUT, ONE_XDAG, true),
                new Address(argsHead, XDAG_FIELD_OUT, false));
        Block raw = new Block(config, timestamp, refs, null, false, List.of(poolKey), null, 0, headerFee, nonce,
                fields);
        raw.signOut(poolKey);
        return new Block(new XdagBlock(raw.toBytes()));
    }

    private ChainBlockBuilder.Built underpaidCall(int seed) {
        return lightCall(seed, UNDERPAID);
    }

    /**
     * A CALL whose args ride a {@link #ARGS_CHUNKS}-chunk chain, redrawn over the args payload until
     * the block and its chunks together weigh less than one mined main block — see
     * {@link ChunkOrphanTestBase}'s header for why every delivered block is drawn light.
     */
    private ChainBlockBuilder.Built lightCall(int seed, XAmount headerFee) {
        UInt64 nonce = UInt64.valueOf(seed);
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            ChainBlockBuilder.Built built = ChainBlockBuilder.call(config, txTime(), poolKey, nonce, CHAIN_ID,
                    CONTRACT, 1, 100L, ONE_XDAG, headerFee, payload(ARGS_BYTES, s)).value();
            assertEquals("the fixture's fees assume a three-chunk chain", ARGS_CHUNKS, built.totalChunks());
            BigInteger total = blockchain.calculateCurrentBlockDiff(built.block());
            for (Block chunk : built.chunks()) {
                total = total.add(blockchain.calculateCurrentBlockDiff(chunk));
            }
            if (total.compareTo(MAX_DELIVERED_DIFFICULTY) < 0) {
                return built;
            }
        }
    }
}
