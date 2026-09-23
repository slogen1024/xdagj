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
package io.xdag.chain.orphan;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChunkExt;
import io.xdag.chain.ingest.IngestPipeline;
import io.xdag.config.AbstractConfig;
import io.xdag.config.Config;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A flood arriving the way a flood actually arrives — down a socket, through the real
 * {@link IngestPipeline} — and what it costs everybody who is not the flooder.
 *
 * <p>The three orphan tiers are each pinned on their own elsewhere: {@code OrphanQuotaTest} drives
 * the pool directly and {@code OrphanPeerAttributionTest} shows the source and the chain key
 * surviving the trip into {@code dealOrphan}. What neither of them asks is the question the tiers
 * exist to answer — <b>when the flooder is cut off, is anybody else worse off?</b> A cap that
 * turned into "the pool is full" for everyone would pass every attribution test there is and still
 * be the starvation this subproject was written to stop.
 *
 * <p>So each test here floods one tier to its limit and then delivers, through the same pipeline,
 * the traffic that must be unaffected: another source's chunk, another chain's chunk, and an
 * ordinary account transaction.
 *
 * <h2>On the shared base</h2>
 *
 * <p>This class extends {@link ChunkOrphanTestBase} for the fixture — the armed pool, the
 * difficulty-bounded block builders, {@code pool()} — but delivers through {@code submit} rather
 * than the base's {@code deliver}. The base's {@code deliver} calls {@code PreValidator.reimport}
 * itself, which is the same classification the pipeline computes, so it is the right shortcut for
 * a test about what the pool does with a classified block; it is the wrong one here, where the
 * subject is a flood crossing the whole ingest path with other traffic interleaved in it.
 */
public class ChunkFloodQuotaPipelineTest extends ChunkOrphanTestBase {

    /** The flooder. */
    private static final String FLOODER_IP = "198.51.100.44";

    /** Somebody else, who must not notice. */
    private static final String BYSTANDER_IP = "203.0.113.61";

    /**
     * Both chunk tiers, cut down from the shipping 5000 and 20000. The tier is what is being
     * tested, not how long the test takes to reach it, and at production size reaching it means
     * five thousand block imports.
     */
    private static final int BUDGET = 3;

    /** Chunks sent per flood — comfortably past {@link #BUDGET}, so the cut-off is unambiguous. */
    private static final int FLOOD = 10;

    private IngestPipeline pipeline;

    /** Every committer verdict, so a delivery that was rejected says so instead of just vanishing. */
    private final List<String> verdicts = new CopyOnWriteArrayList<>();

    /**
     * The two chunk sub-tiers, small. The store reads its caps once, in its constructor, so this
     * has to be on the config before {@code setUpChain} builds it — assigning the inherited
     * {@code config} field would be too late and silently lost.
     */
    @Override
    protected Config newConfig() {
        AbstractConfig cfg = (AbstractConfig) super.newConfig();
        cfg.setChainOrphanChunkPerPeer(BUDGET);
        cfg.setChainOrphanChunkPerChain(BUDGET);
        return cfg;
    }

    @Before
    public void startPipeline() {
        pipeline = new IngestPipeline(2, 64, pv -> {
            ImportResult r = blockchain.tryToConnect(pv);
            verdicts.add(r + " " + r.getErrorInfo());
            return r;
        });
        pipeline.start();
    }

    @After
    public void stopPipeline() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    /**
     * One source floods; it spends its own budget and stops. Everybody else's intake is exactly
     * where it was.
     *
     * <p>The three things that must still work afterwards are deliberately different in kind:
     * another peer's chunk (the same category the flood filled), an account transaction (the
     * category the old global gate used to close first), and the flooder's own refused chunks being
     * held nowhere at all — no pool entry, no memory body, and no block on disk, which is what makes
     * the refusal a refusal rather than a quota that only counts.
     */
    @Test
    public void aFloodFromOneSourceSpendsItsOwnBudgetAndNobodyElsesIntakeMoves() throws Exception {
        List<Block> flood = new ArrayList<>();
        for (int i = 0; i < FLOOD; i++) {
            flood.add(lightChunk(80 + i));
        }
        for (Block chunk : flood) {
            submit(chunk, FLOODER_IP);
        }
        drain();

        assertEquals("the flooder gets its budget and not one chunk more",
                BUDGET, pool().chunksHeldBy(FLOODER_IP));
        assertEquals("and the category holds only what was admitted",
                BUDGET, pool().size(OrphanCategory.CHUNK));
        int refused = 0;
        for (Block chunk : flood) {
            if (pool().get(hashLow(chunk)) != null) {
                continue;
            }
            refused++;
            assertNull("a refused chunk must not be held in memory either",
                    blockchain.getOrphanBlockStore().getChunkBody(hashLow(chunk)));
            assertNull("nor on disk -- a chunk skips saveBlock, so the pool was its only home",
                    kernel.getBlockStore().getRawBlockByHash(chunk.getHashLow()));
        }
        assertEquals(FLOOD - BUDGET, refused);

        Block fromBystander = lightChunk(95);
        submit(fromBystander, BYSTANDER_IP);
        drain();
        assertNotNull("another source's chunk must still be admitted: the budget is the flooder's,"
                + " not the pool's", pool().get(hashLow(fromBystander)));
        assertEquals("and charged to that source alone", 1, pool().chunksHeldBy(BYSTANDER_IP));
        assertEquals(BUDGET, pool().chunksHeldBy(FLOODER_IP));

        Block tx = accountTx();
        submit(tx, BYSTANDER_IP);
        drain();
        assertNotNull("an account transaction must be untouched by a chunk flood -- this is the"
                + " starvation the whole subproject exists to stop", pool().get(hashLow(tx)));
        assertEquals(1, pool().size(OrphanCategory.ACCOUNT_TX));
    }

    /**
     * The other tier, and the reason it exists at all: a chain's budget is shared across every
     * source, so spreading a flood over many peers does not buy any more of it.
     *
     * <p>Every chunk below comes from a <em>different</em> IP and each one is that IP's first, so
     * the per-peer tier cannot be what stops them — if it were, this test would pass with the chain
     * tier deleted. And the peer whose chunk was refused here must still be able to send on another
     * chain: a chain running out is not a reason to shut a source down.
     */
    @Test
    public void aFloodOnOneChainSpendsThatChainsBudgetHoweverManySourcesSendIt() throws Exception {
        Block target = lightChunk(60);
        submit(target, FLOODER_IP);
        drain();
        assertEquals("a tail names no successor, so it roots its own chain and is the first on it",
                1, pool().chunksOnChain(hashLow(target)));

        List<Block> flood = new ArrayList<>();
        for (int i = 0; i < FLOOD; i++) {
            Block naming = chunkNaming(hashLow(target), 61 + i);
            flood.add(naming);
            // One IP each, and each its first chunk: the per-peer tier has nothing to say here.
            submit(naming, "192.0.2." + (10 + i));
            drain();
        }

        assertEquals("one chain, one budget, however many sources pay into it",
                BUDGET, pool().chunksOnChain(hashLow(target)));
        assertEquals("and the chunk category holds that and nothing else",
                BUDGET, pool().size(OrphanCategory.CHUNK));

        Block refused = null;
        String refusedIp = null;
        for (int i = 0; i < FLOOD; i++) {
            if (pool().get(hashLow(flood.get(i))) == null) {
                refused = flood.get(i);
                refusedIp = "192.0.2." + (10 + i);
                break;
            }
        }
        assertNotNull("the chain tier never fired", refused);
        assertEquals("a chunk the chain tier turned away must spend no peer budget either",
                0, pool().chunksHeldBy(refusedIp));
        assertNull("and must be held nowhere",
                blockchain.getOrphanBlockStore().getChunkBody(hashLow(refused)));

        // That peer now spends a whole budget's worth on the chain that is already full. Every one
        // is refused by the chain tier, and if a chain refusal charged the sender anyway the peer
        // would be at its own limit by the end of this loop -- which is what the last delivery
        // below is here to catch.
        for (int i = 0; i < BUDGET; i++) {
            submit(chunkNaming(hashLow(target), 75 + i), refusedIp);
            drain();
        }
        assertEquals("a full chain must cost the sender nothing at all",
                0, pool().chunksHeldBy(refusedIp));

        Block elsewhere = lightChunk(96);
        submit(elsewhere, refusedIp);
        drain();
        assertNotNull("a source turned away by one chain's budget must still be able to send on"
                + " another chain", pool().get(hashLow(elsewhere)));
        assertEquals(1, pool().chunksOnChain(hashLow(elsewhere)));
    }

    // ---- delivery -------------------------------------------------------------------------

    /**
     * Hands the block to the real pipeline as a peer's wrapper, exactly as {@code SyncManager} does.
     *
     * <p>The instance itself, not a re-serialisation of it: {@code Block.toBytes()} on an
     * already-parsed block does not reproduce the bytes it was parsed from, so re-wrapping would
     * deliver a different block than the test then looks for. {@code getHashLow()} is a lazy
     * mutator, so it is forced here, on this thread, before the pipeline can race the test for it.
     */
    private void submit(Block block, String peerIp) {
        block.getHashLow();
        pipeline.submit(new BlockWrapper(block, 0, peer(peerIp), false));
    }

    /**
     * Waits for everything submitted so far to be committed — the pipeline's own idle wait, not a
     * poll — then insists every one of them really was imported. A chunk the pool refuses still imports — the refusal is the pool's, not the
     * chain's — so anything here that did not import is a fixture problem, and without this check it
     * would look exactly like a quota doing its job.
     */
    private void drain() throws InterruptedException {
        assertTrue("the ingest pipeline did not drain",
                pipeline.awaitIdle(60, TimeUnit.SECONDS));
        for (String verdict : verdicts) {
            assertTrue("a delivered block was not imported: " + verdict,
                    verdict.startsWith("IMPORTED_"));
        }
    }

    /**
     * A well-formed CHUNK block whose {@code next} is a block of the caller's choosing, which
     * {@link io.xdag.chain.ext.ChunkChainBuilder} cannot express — it only builds whole chains.
     * Timestamped one tick after {@link #txTime()} so it is strictly later than the block it names.
     */
    private Block chunkNaming(Bytes32 next, int seed) {
        for (long s = (long) seed * SEEDS_PER_BLOCK; ; s++) {
            ChunkExt ext = new ChunkExt(1, 64, 32, next, payload(32, s));
            List<Bytes32> fields = new ArrayList<>();
            fields.add(ext.encodeHeader());
            fields.addAll(ext.encodePayload());
            Block raw = new Block(config, txTime() + 1, null,
                    List.of(new Address(next, XDAG_FIELD_OUT, false)), false, null, null, -1,
                    XAmount.ZERO, null, fields);
            Block parsed = new Block(new XdagBlock(raw.toBytes()));
            if (blockchain.calculateCurrentBlockDiff(parsed).compareTo(MAX_DELIVERED_DIFFICULTY) < 0) {
                return parsed;
            }
        }
    }

    /** An ordinary paying transaction from the fixture's funded key. */
    private Block accountTx() {
        Address from = new Address(BytesUtils.arrayToByte32(poolKey.toAddress().toArray()),
                XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(Bytes.random(20).toArray()),
                XDAG_FIELD_OUTPUT, true);
        return new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                config, poolKey, txTime(), from, to, ONE_XDAG, nextNonce()).toBytes()));
    }
}
