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
package io.xdag.lane.l1;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_COINBASE;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.lane.ext.ChunkChainTest.payload;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.BlockBuilder;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.spec.LaneSpec;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.lane.InMemoryKVSource;
import io.xdag.lane.ext.BondExt;
import io.xdag.lane.ext.CallExt;
import io.xdag.lane.ext.ChunkChainBuilder;
import io.xdag.lane.ext.Classified;
import io.xdag.lane.ext.DeployExt;
import io.xdag.lane.ext.ExtKind;
import io.xdag.lane.ext.LaneBlockBuilder;
import io.xdag.lane.ext.LaneBlockClassifierTest;
import io.xdag.lane.ext.LaneConfigExt;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

public class LaneL1ProcessorTest {

    /** A LaneSpec whose limits tests can shrink. */
    static final class TestSpec implements LaneSpec {
        long activation = 0;
        int maxChunks = 4096;
        int maxWasm = 1024 * 1024;

        @Override public long getLaneActivationHeight() { return activation; }
        @Override public void setLaneActivationHeight(long height) { activation = height; }
        @Override public int getLaneMaxChunksPerChain() { return maxChunks; }
        @Override public int getLaneMaxWasmBytes() { return maxWasm; }
        @Override public int getLaneMaxInlineArgs() { return 256; }
        @Override public XAmount getLaneChunkFee() { return XAmount.of(10, XUnit.MILLI_XDAG); }
    }

    /** A handler that records nothing; used where only registration behaviour is under test. */
    private static final LaneKindHandler INERT = new LaneKindHandler() {
        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, LaneL1Batch batch) {
        }

        @Override
        public void onUnapplied(Block block, Classified classified, LaneL1Batch batch) {
        }
    };

    private static final long TS = 0x16a00000000L;
    /** One tick below {@link #TS}, skipping the end-of-epoch tick, as LaneBlockBuilder roots its chains. */
    private static final long CHAIN_TS = TS - 2;
    private static final long EPOCH = 0x10000L;
    private static final XAmount ONE = XAmount.of(1, XUnit.XDAG);
    private static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);
    private static final LaneConfigExt CFG = new LaneConfigExt(1L, 32L, 10_000_000L);
    private static final Bytes ZERO_LANE = Bytes.wrap(new byte[20]);

    private final Config config = new DevnetConfig();
    private final ECKeyPair sender = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    private final Map<Bytes32, Block> dag = new HashMap<>();
    private InMemoryKVSource src;
    private LaneL1Store store;
    private TestSpec spec;
    private LaneL1Processor proc;
    private Block mainBlock;
    private long nonce = 1;
    private final List<Block> appliedOrder = new ArrayList<>();

    @Before
    public void setUp() {
        src = new InMemoryKVSource();
        store = new LaneL1Store(src);
        store.start();
        spec = new TestSpec();
        proc = new LaneL1Processor(store, spec, h -> dag.get(Bytes32.wrap(h.toArray())));
        mainBlock = LaneBlockClassifierTest.extBlock(config, List.of(), List.of());
    }

    private UInt64 nextNonce() {
        return UInt64.valueOf(nonce++);
    }

    /** Registers chunks and the block in the fake DAG and feeds them to the processor in import order (tail first). */
    private void apply(LaneBlockBuilder.Built built) {
        for (int i = built.chunks().size() - 1; i >= 0; i--) {
            apply(built.chunks().get(i));
        }
        apply(built.block());
    }

    private void apply(Block b) {
        dag.put(Bytes32.wrap(b.getHashLow().toArray()), b);
        proc.onBlockApplied(b);
        appliedOrder.add(b);
    }

    private void unapplyAll() {
        for (int i = appliedOrder.size() - 1; i >= 0; i--) {
            proc.onBlockUnapplied(appliedOrder.get(i));
        }
        appliedOrder.clear();
    }

    private LaneBlockBuilder.Built deployNewLane(Bytes wasm) {
        return deployNewLane(wasm, CFG);
    }

    private LaneBlockBuilder.Built deployNewLane(Bytes wasm, LaneConfigExt laneConfig) {
        int chunks = LaneBlockBuilder.chunksFor(wasm.size());
        XAmount fee = LaneBlockBuilder.minHeaderFee(spec.getLaneChunkFee(), chunks);
        return LaneBlockBuilder.deployNewLane(config, TS, sender, nextNonce(), fee, wasm, laneConfig, payload(20, 9),
                1000L).value();
    }

    private LaneBlockBuilder.Built call(Bytes laneId, Bytes contract, Bytes args, XAmount headerFee) {
        return LaneBlockBuilder.call(config, TS, sender, nextNonce(), laneId, contract, 1, 100L, ONE, headerFee, args)
                .value();
    }

    /**
     * A raw extension block with an explicit timestamp and header fee (unlike
     * {@code LaneBlockClassifierTest.extBlock}, which stamps wall-clock time and a zero fee) so the
     * chunk-chain age rule can be exercised deterministically.
     */
    private Block extBlockAt(long timestamp, XAmount fee, List<Bytes32> ext, List<Address> links) {
        Block b = new Block(config, timestamp, null, links.isEmpty() ? null : links, false, null, null, -1, fee, null,
                ext);
        return new Block(new XdagBlock(b.toBytes()));
    }

    /**
     * A hand-built new-lane DEPLOY paying at {@code payingTs} and referencing a code chain rooted at
     * {@code chainHeadTs}; the chain's blocks are registered in the fake DAG.
     */
    private Block deployWithCodeChain(Bytes wasm, long chainHeadTs, long payingTs) {
        List<Block> chain = ChunkChainBuilder.split(config, wasm, chainHeadTs);
        for (Block c : chain) {
            dag.put(Bytes32.wrap(c.getHashLow().toArray()), c);
        }
        Bytes32 head = Bytes32.wrap(chain.get(0).getHashLow().toArray());
        DeployExt d = new DeployExt(DeployExt.FLAG_NEW_LANE | DeployExt.FLAG_CODE_CHAIN, ZERO_LANE, 1000L, 0,
                HashUtils.sha256(wasm), CFG, Bytes.EMPTY, head, null);
        List<Bytes32> ext = new ArrayList<>();
        ext.add(d.encodeHeader());
        ext.addAll(d.encodePayload());
        return extBlockAt(payingTs, FEE, ext, List.of(new Address(head, XDAG_FIELD_OUT, false)));
    }

    @Test
    public void deployCreatesLaneContractCodeAndInputRecord() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(1000, 1);
        LaneBlockBuilder.Built deploy = deployNewLane(wasm);
        apply(deploy);

        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        Bytes contract = LaneIds.contractIdOf(deploy.block().getHash());
        assertTrue(store.hasLane(laneId));
        LaneRecord lane = store.getLane(laneId);
        assertEquals(10L, lane.createdHeight());
        assertEquals(deploy.block().getHash(), lane.createBlockHash());
        assertEquals(1L, lane.contractCount());
        assertEquals(CFG.gasPriceNano(), lane.gasPriceNano());
        ContractRecord cr = store.getContract(contract);
        assertEquals(laneId, cr.laneId());
        assertEquals(HashUtils.sha256(wasm), cr.codeHash());
        assertEquals(wasm, store.getCode(cr.codeHash()));
        assertEquals(1L, store.getCodeRefCount(cr.codeHash()));
        assertEquals(1L, store.getCallCount(laneId, 10));
        InputRecord in = store.getInput(laneId, 10, 0);
        assertEquals(ExtKind.DEPLOY, in.kind());
        assertEquals(InputStatus.OK, in.status());
        assertEquals(contract, in.contract());
        assertEquals(List.of(new InputRef(laneId, 10, 0)), store.getReverse(deploy.block().getHash()));
        // chunk blocks produce no records
        assertTrue(store.getReverse(deploy.chunks().get(0).getHash()).isEmpty());
    }

    @Test
    public void callsAreRecordedWithStatusesInOrder() {
        proc.onSetMainBegin(10, mainBlock);
        LaneBlockBuilder.Built deploy = deployNewLane(payload(600, 2));
        apply(deploy);
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        Bytes contract = LaneIds.contractIdOf(deploy.block().getHash());

        LaneBlockBuilder.Built ok = call(laneId, contract, payload(20, 3), FEE);
        LaneBlockBuilder.Built unknownContract = call(laneId, Bytes.random(20), payload(20, 4), FEE);
        LaneBlockBuilder.Built lowFee = call(laneId, contract, payload(1000, 5), XAmount.of(20, XUnit.MILLI_XDAG));
        LaneBlockBuilder.Built exactFee = call(laneId, contract, payload(1000, 6), XAmount.of(30, XUnit.MILLI_XDAG));
        apply(ok);
        apply(unknownContract);
        apply(lowFee);
        apply(exactFee);

        // plain transfer into the vault: no EXT at all
        Address from = new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_INPUT, true);
        Address to = new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, true);
        Block plain = BlockBuilder.generateNewTransactionBlock(config, sender, TS, from, to, ONE, nextNonce());
        apply(new Block(new XdagBlock(plain.toBytes())));

        assertEquals(6L, store.getCallCount(laneId, 10));
        assertEquals(InputStatus.OK, store.getInput(laneId, 10, 1).status());
        assertEquals(ExtKind.CALL, store.getInput(laneId, 10, 1).kind());
        assertEquals(contract, store.getInput(laneId, 10, 1).contract());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(laneId, 10, 2).status());
        assertNull(store.getInput(laneId, 10, 2).kind());
        assertEquals(InputStatus.INVALID_FEE, store.getInput(laneId, 10, 3).status());
        assertEquals(InputStatus.OK, store.getInput(laneId, 10, 4).status());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(laneId, 10, 5).status());
        assertEquals(ok.block().getHash(), store.getInput(laneId, 10, 1).blockHash());
    }

    @Test
    public void deployIntoLaneAndCodeReferenceCounting() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(600, 7);
        LaneBlockBuilder.Built first = deployNewLane(wasm);
        apply(first);
        Bytes laneId = LaneIds.laneIdOf(first.block().getHash());
        Bytes32 codeHash = HashUtils.sha256(wasm);

        LaneBlockBuilder.Built reuse = LaneBlockBuilder.deployIntoLane(config, TS, sender, nextNonce(), laneId, ONE,
                FEE, null, codeHash, payload(10, 8), 1L).value();
        apply(reuse);
        assertEquals(2L, store.getLane(laneId).contractCount());
        assertEquals(2L, store.getCodeRefCount(codeHash));
        assertEquals(laneId, store.getContract(LaneIds.contractIdOf(reuse.block().getHash())).laneId());

        LaneBlockBuilder.Built unknown = LaneBlockBuilder.deployIntoLane(config, TS, sender, nextNonce(), laneId, ONE,
                FEE, null, Bytes32.random(), payload(10, 8), 1L).value();
        apply(unknown);
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(laneId, 10, 2).status());
        assertEquals(ExtKind.DEPLOY, store.getInput(laneId, 10, 2).kind());
        assertEquals(2L, store.getLane(laneId).contractCount());
        assertNull(store.getContract(LaneIds.contractIdOf(unknown.block().getHash())));
    }

    @Test
    public void oversizedCodeIsRecordedButNotStored() {
        spec.maxWasm = 500;
        proc.onSetMainBegin(10, mainBlock);
        LaneBlockBuilder.Built deploy = deployNewLane(payload(600, 10));
        apply(deploy);
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        assertEquals(InputStatus.CODE_TOO_LARGE, store.getInput(laneId, 10, 0).status());
        assertFalse(store.hasLane(laneId));
        assertFalse(store.hasCode(HashUtils.sha256(payload(600, 10))));
        assertEquals(1L, store.getCallCount(laneId, 10));

        // A structural verdict is never masked by the fee check: this one underpays as well.
        LaneBlockBuilder.Built underpaid = LaneBlockBuilder.deployNewLane(config, TS, sender, nextNonce(),
                XAmount.ZERO, payload(600, 10), CFG, payload(20, 9), 1000L).value();
        apply(underpaid);
        Bytes underpaidLane = LaneIds.laneIdOf(underpaid.block().getHash());
        assertEquals(InputStatus.CODE_TOO_LARGE, store.getInput(underpaidLane, 10, 0).status());
    }

    @Test
    public void nothingHappensBelowActivationOrOutsideSetMain() {
        spec.activation = 100;
        proc.onSetMainBegin(99, mainBlock);
        apply(deployNewLane(payload(600, 11)));
        assertEquals(1, src.keys().size());
        proc.onSetMainEnd(99, mainBlock);

        spec.activation = 0;
        // no onSetMainBegin: context is null, hook must be inert
        apply(deployNewLane(payload(600, 12)));
        assertEquals(1, src.keys().size());
    }

    @Test
    public void handlersAreDispatchedForOtherKinds() {
        List<Classified> seen = new ArrayList<>();
        Bytes marker = Bytes.random(20);
        proc.registerHandler(ExtKind.BOND, new LaneKindHandler() {
            @Override
            public void onApplied(Block block, Classified classified, ApplyContext ctx, LaneL1Batch batch) {
                assertEquals(10L, ctx.height());
                seen.add(classified);
                // Any LaneL1Batch put: proves the processor commits the handler's own writes too.
                batch.putCallCount(marker, 1, 7);
            }

            @Override
            public void onUnapplied(Block block, Classified classified, LaneL1Batch batch) {
                seen.remove(classified);
                batch.deleteCallCount(marker, 1);
            }
        });
        proc.onSetMainBegin(10, mainBlock);
        BondExt bond = new BondExt(false, Bytes.random(20), 0L);
        Block bondBlock = LaneBlockClassifierTest.extBlock(config, List.of(bond.encodeHeader()), List.of());
        apply(bondBlock);
        assertEquals(1, seen.size());
        assertEquals(bond, seen.get(0).as(BondExt.class));
        assertEquals(7L, store.getCallCount(marker, 1));
        assertEquals(2, src.keys().size()); // META + the handler's own key; the stub itself records nothing else
        proc.onBlockUnapplied(bondBlock);
        assertTrue(seen.isEmpty());
        assertEquals(0L, store.getCallCount(marker, 1));
        assertEquals(1, src.keys().size());
    }

    @Test
    public void registerHandlerAfterFirstHookRunThrows() {
        proc.onSetMainBegin(10, mainBlock);
        assertThrows(IllegalStateException.class, () -> proc.registerHandler(ExtKind.BOND, INERT));
    }

    @Test
    public void unapplyRestoresAnEmptyStore() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(900, 13);
        LaneBlockBuilder.Built deploy = deployNewLane(wasm);
        apply(deploy);
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        Bytes contract = LaneIds.contractIdOf(deploy.block().getHash());
        apply(call(laneId, contract, payload(20, 14), FEE));
        apply(LaneBlockBuilder.deployIntoLane(config, TS, sender, nextNonce(), laneId, ONE, FEE, null,
                HashUtils.sha256(wasm), Bytes.EMPTY, 1L).value());
        proc.onSetMainEnd(10, mainBlock);
        assertTrue(src.keys().size() > 1);

        proc.onSetMainBegin(11, mainBlock);
        apply(call(laneId, contract, payload(20, 15), FEE));
        proc.onSetMainEnd(11, mainBlock);
        assertEquals(1L, store.getCallCount(laneId, 11));

        unapplyAll();
        assertEquals(1, src.keys().size());
        assertNotNull(src.get(LaneL1Keys.META_KEY));
        assertSame(null, store.getLane(laneId));
    }

    @Test
    public void newLaneRejectsZeroOrOversizedMaxCallGas() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(300, 31);
        Bytes32 codeHash = HashUtils.sha256(wasm);

        LaneBlockBuilder.Built zero = deployNewLane(wasm, new LaneConfigExt(1L, 32L, 0L));
        apply(zero);
        Bytes zeroLane = LaneIds.laneIdOf(zero.block().getHash());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(zeroLane, 10, 0).status());
        assertEquals(ExtKind.DEPLOY, store.getInput(zeroLane, 10, 0).kind());
        assertFalse(store.hasLane(zeroLane));
        assertEquals(1L, store.getCallCount(zeroLane, 10));

        LaneBlockBuilder.Built over = deployNewLane(wasm,
                new LaneConfigExt(1L, 32L, LaneL1Processor.MAX_CALL_GAS_CAP + 1));
        apply(over);
        Bytes overLane = LaneIds.laneIdOf(over.block().getHash());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(overLane, 10, 0).status());
        assertEquals(ExtKind.DEPLOY, store.getInput(overLane, 10, 0).kind());
        assertFalse(store.hasLane(overLane));
        assertEquals(1L, store.getCallCount(overLane, 10));

        // Neither rejected deploy installed any code.
        assertFalse(store.hasCode(codeHash));

        LaneBlockBuilder.Built cap = deployNewLane(wasm, new LaneConfigExt(1L, 32L, LaneL1Processor.MAX_CALL_GAS_CAP));
        apply(cap);
        Bytes capLane = LaneIds.laneIdOf(cap.block().getHash());
        assertEquals(InputStatus.OK, store.getInput(capLane, 10, 0).status());
        assertTrue(store.hasLane(capLane));
        assertEquals(LaneL1Processor.MAX_CALL_GAS_CAP, store.getLane(capLane).maxCallGas());
        assertTrue(store.hasCode(codeHash));
    }

    @Test
    public void oldCodeChainIsInvalidFormat() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(600, 21);
        Bytes32 codeHash = HashUtils.sha256(wasm);

        // Three epochs below the paying block: older than minEpoch = epoch(payingBlock) - 1.
        Block old = deployWithCodeChain(wasm, TS - 3 * EPOCH, TS);
        apply(old);
        Bytes oldLane = LaneIds.laneIdOf(old.getHash());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(oldLane, 10, 0).status());
        assertEquals(ExtKind.DEPLOY, store.getInput(oldLane, 10, 0).kind());
        assertFalse(store.hasLane(oldLane));
        assertFalse(store.hasCode(codeHash));

        // Control: the same construction with the chain in the epoch just below the paying block.
        Block fresh = deployWithCodeChain(wasm, CHAIN_TS, TS);
        apply(fresh);
        Bytes freshLane = LaneIds.laneIdOf(fresh.getHash());
        assertEquals(InputStatus.OK, store.getInput(freshLane, 10, 0).status());
        assertTrue(store.hasLane(freshLane));
        assertEquals(wasm, store.getCode(codeHash));
    }

    @Test
    public void registerHandlerRejectsBuiltInKinds() {
        assertThrows(IllegalArgumentException.class, () -> proc.registerHandler(ExtKind.CALL, INERT));
        assertThrows(IllegalArgumentException.class, () -> proc.registerHandler(ExtKind.DEPLOY, INERT));
        assertThrows(IllegalArgumentException.class, () -> proc.registerHandler(ExtKind.CHUNK, INERT));
        assertThrows(NullPointerException.class, () -> proc.registerHandler(null, INERT));
        assertThrows(NullPointerException.class, () -> proc.registerHandler(ExtKind.BOND, null));
        assertThrows(NullPointerException.class, () -> new LaneL1Processor(null, spec, dag::get));
        assertThrows(NullPointerException.class, () -> new LaneL1Processor(store, null, dag::get));
        assertThrows(NullPointerException.class, () -> new LaneL1Processor(store, spec, null));
    }

    @Test
    public void laneAndContractIdsArePinned() {
        // sha256("xdag-lane" || 32 zero bytes)[0..20] and sha256("xdag-contract" || 32 zero bytes)[0..20].
        assertEquals("0x5ffedd494c2f24a09a910fe00c15c2e152c78e4b", LaneIds.laneIdOf(Bytes32.ZERO).toHexString());
        assertEquals("0x40b50e4b989e5b3bcc7febaaa591368788b1c0d2", LaneIds.contractIdOf(Bytes32.ZERO).toHexString());
        assertEquals(20, LaneIds.laneIdOf(Bytes32.ZERO).size());
        assertEquals(20, LaneIds.contractIdOf(Bytes32.ZERO).size());
        assertThrows(NullPointerException.class, () -> LaneIds.laneIdOf(null));
        assertThrows(NullPointerException.class, () -> LaneIds.contractIdOf(null));

        Bytes address = Bytes.random(20);
        Address output = new Address(BytesUtils.arrayToByte32(address.toArray()), XDAG_FIELD_OUTPUT, ONE, true);
        assertEquals(address, LaneIds.address20(output));
        // A hash-low-shaped link (first 8 bytes zero): Address.parse reads them as a u64 amount and a
        // fully random value would overflow toLong() before address20's own check is reached.
        Address blockLink = new Address(Bytes32.leftPad(Bytes.random(24)), XDAG_FIELD_OUT, false);
        assertThrows(IllegalArgumentException.class, () -> LaneIds.address20(blockLink));
        assertThrows(NullPointerException.class, () -> LaneIds.address20(null));
    }

    @Test
    public void refCountBumpDoesNotRewriteBlob() {
        proc.onSetMainBegin(10, mainBlock);
        Bytes wasm = payload(600, 41);
        Bytes32 codeHash = HashUtils.sha256(wasm);
        LaneBlockBuilder.Built first = deployNewLane(wasm);
        apply(first);
        Bytes laneId = LaneIds.laneIdOf(first.block().getHash());
        assertEquals(wasm, store.getCode(codeHash));

        // Mark the stored blob so a rewrite would be visible; the second deploy carries the very
        // same code by chain, yet must only bump the refcount.
        Bytes marker = payload(600, 42);
        src.put(LaneL1Keys.code(codeHash), marker.toArray());

        LaneBlockBuilder.Built second = LaneBlockBuilder.deployIntoLane(config, TS, sender, nextNonce(), laneId, ONE,
                FEE, wasm, null, Bytes.EMPTY, 1L).value();
        apply(second);
        assertEquals(InputStatus.OK, store.getInput(laneId, 10, 1).status());
        assertEquals(2L, store.getCodeRefCount(codeHash));
        assertEquals(marker, store.getCode(codeHash));

        // The undo halves the invariant the same way: blob survives the first undo, dies with the last.
        proc.onBlockUnapplied(second.block());
        assertEquals(1L, store.getLane(laneId).contractCount());
        assertEquals(1L, store.getCodeRefCount(codeHash));
        assertEquals(marker, store.getCode(codeHash));
        proc.onBlockUnapplied(first.block());
        assertEquals(0L, store.getCodeRefCount(codeHash));
        assertFalse(store.hasCode(codeHash));
        assertNull(store.getCode(codeHash));
    }

    @Test
    public void coinbaseFieldIsNotTreatedAsVaultPayment() {
        proc.onSetMainBegin(10, mainBlock);
        LaneBlockBuilder.Built deploy = deployNewLane(payload(600, 70));
        apply(deploy);
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        long before = store.getCallCount(laneId, 10);

        List<Address> links = List.of(
                new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_COINBASE, true));
        Block coinbaseOnly = extBlockAt(TS, FEE, List.of(), links);
        apply(coinbaseOnly);

        // A COINBASE-only link is never settled by applyBlock, so it must record nothing at all.
        assertEquals(before, store.getCallCount(laneId, 10));
        assertTrue(store.getReverse(coinbaseOnly.getHash()).isEmpty());
    }

    @Test
    public void callArgsChainAgeBoundExemptsFeeCheckWhenChainIsTooOld() {
        proc.onSetMainBegin(10, mainBlock);
        LaneBlockBuilder.Built deploy = deployNewLane(payload(600, 71));
        apply(deploy);
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        Bytes contract = LaneIds.contractIdOf(deploy.block().getHash());

        // Three epochs below the paying block: older than minEpoch = epoch(payingBlock) - 1, so the
        // age-bounded lenient count is 0 even though the chain (one real chunk) exists.
        List<Block> chain = ChunkChainBuilder.split(config, payload(20, 72), TS - 3 * EPOCH);
        for (Block c : chain) {
            dag.put(Bytes32.wrap(c.getHashLow().toArray()), c);
        }
        Bytes32 argsHead = Bytes32.wrap(chain.get(0).getHashLow().toArray());

        CallExt call = new CallExt(CallExt.FLAG_ARGS_CHAIN, contract, 1, 100L, 0, Bytes.EMPTY, argsHead);
        List<Bytes32> ext = new ArrayList<>();
        ext.add(call.encodeHeader());
        ext.addAll(call.encodePayload());
        List<Address> links = List.of(
                new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, ONE, true),
                new Address(argsHead, XDAG_FIELD_OUT, false));
        // A fee that would be INVALID_FEE for even a single real chunk (spec's chunk fee is 10
        // mXDAG); OK here only because the age bound excludes the chunk from the count entirely.
        Block callBlock = extBlockAt(TS, XAmount.of(1, XUnit.MILLI_XDAG), ext, links);
        apply(callBlock);

        InputRecord in = store.getInput(laneId, 10, 1);
        assertEquals(ExtKind.CALL, in.kind());
        assertEquals(InputStatus.OK, in.status());
    }

    @Test
    public void duplicateVaultOutputsAreBothRecordedAsInvalidFormat() {
        proc.onSetMainBegin(10, mainBlock);
        LaneBlockBuilder.Built deploy = deployNewLane(payload(600, 73));
        apply(deploy);
        Bytes laneId = LaneIds.laneIdOf(deploy.block().getHash());
        long n = store.getCallCount(laneId, 10);

        List<Address> links = List.of(
                new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, ONE, true),
                new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, ONE, true));
        Block dup = extBlockAt(TS, FEE, List.of(), links);
        apply(dup);

        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(laneId, 10, n).status());
        assertNull(store.getInput(laneId, 10, n).kind());
        assertEquals(InputStatus.INVALID_FORMAT, store.getInput(laneId, 10, n + 1).status());
        assertNull(store.getInput(laneId, 10, n + 1).kind());
        assertEquals(n + 2, store.getCallCount(laneId, 10));

        proc.onBlockUnapplied(dup);
        assertEquals(n, store.getCallCount(laneId, 10));
        assertNull(store.getInput(laneId, 10, n));
        assertNull(store.getInput(laneId, 10, n + 1));
    }
}
