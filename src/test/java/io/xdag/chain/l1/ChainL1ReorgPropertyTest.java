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

package io.xdag.chain.l1;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.config.Constants.BI_MAIN;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.exception.CryptoException;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PrivateKey;
import io.xdag.utils.BytesUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/**
 * P3 as a property (G10): for a seeded random operation sequence, the CHAIN_L1 state reached by
 * applying it, having a competing branch unwind every main block above the fork point and re-apply
 * the same paying blocks at the same heights, is byte-identical to the state before the fork — and
 * that pre-fork state is exactly what a fresh node reaches by applying the sequence directly.
 *
 * <p>Shape of one seed:
 * <ol>
 * <li>Run A: 4 empty mains, a fork point, then {@link #apply} — per height, {@code mineMain(refs)}
 *     plus the confirmations {@code confirm} needs, recording the refs and the number of mains each
 *     height took. Capture {@code appliedHash = chainStore.stateHash()} and the balance snapshot.</li>
 * <li>Fork: {@code rewindTo(forkPoint)} and rebuild the SAME heights on a competing branch —
 *     {@code mineMain(refs_h, false)} followed by {@code count_h - 1} empty mains — then keep mining
 *     empty branch mains until the top flips (bounded). The node unwinds every old-branch main
 *     (unapplying all paying blocks) and applies the new branch (re-applying them at the same
 *     heights): the symmetry under test.</li>
 * <li>Assert (a) old mains are no longer BI_MAIN and the branch mains at scenario heights are, at
 *     the recorded heights; (b) every paying block is BI_APPLIED; (c) {@code stateHash()} equals
 *     {@code appliedHash}; (d) sender balances/nonces and chain vaults equal the pre-fork snapshot.</li>
 * <li>Run B: a fresh fixture, the same seed-derived senders, 5 empty mains, {@link #apply}; assert
 *     (e) {@code stateHash()} equals {@code appliedHash} — run A's pre-fork state IS the plain
 *     direct-apply state (signing is RFC 6979 and the mining timeline restarts from the fixture
 *     constant, so every paying block is byte-identical across runs).</li>
 * </ol>
 *
 * <p>Runs two seeds by default; {@code -Dxdag.reorg.full=true} runs seeds 1..8,
 * {@code -Dxdag.reorg.seeds=a,b,c} appends seeds. Every assertion message carries the scenario.
 */
public class ChainL1ReorgPropertyTest extends ChainL1TestBase {

    private static final long[] DEFAULT_SEEDS = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final int DEFAULT_SEED_COUNT = 2;
    private static final XAmount FUNDING = XAmount.of(500, XUnit.XDAG);
    /** PLAIN transfers go here until the scenario has deployed its first chain. */
    private static final Bytes UNKNOWN_VAULT = Bytes.repeat((byte) 0x6b, 20);
    private static final Bytes UNKNOWN_CONTRACT = Bytes.repeat((byte) 0x5a, 20);
    private static final ChainConfigExt BAD_GAS_CFG = new ChainConfigExt(1L, 32L, 0L);

    private static long[] seeds() {
        List<Long> out = new ArrayList<>();
        int n = Boolean.getBoolean("xdag.reorg.full") ? DEFAULT_SEEDS.length : DEFAULT_SEED_COUNT;
        for (int i = 0; i < n; i++) {
            out.add(DEFAULT_SEEDS[i]);
        }
        String extra = System.getProperty("xdag.reorg.seeds");
        if (extra != null && !extra.isBlank()) {
            for (String s : extra.split(",")) {
                out.add(Long.parseLong(s.trim()));
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    // ---- per-run scenario state (reset by beginScenario) ----
    private final ECKeyPair[] senders = new ECKeyPair[ReorgScenario.SENDERS];
    private final UInt64[] nonces = new UInt64[ReorgScenario.SENDERS];
    private final List<Bytes> chainIds = new ArrayList<>();
    private final List<Bytes> contracts = new ArrayList<>();
    private final List<Bytes32> codeHashes = new ArrayList<>();
    /** Every paying block, in link order. */
    private final List<Block> paying = new ArrayList<>();
    /** Per scenario height: the refs its main linked, and how many mains the height took in total. */
    private final List<List<Bytes32>> refsPerHeight = new ArrayList<>();
    private final List<Integer> mainsPerHeight = new ArrayList<>();
    /** Per scenario height: the main that linked its refs, and the height it was set main at. */
    private final List<Block> scenarioMains = new ArrayList<>();
    private final List<Long> scenarioHeights = new ArrayList<>();
    private int minedMains;

    /** Counts every mined main (confirm() and the one-arg overload both route through here). */
    @Override
    protected Block mineMain(List<Bytes32> extraRefs, boolean expectBest) {
        minedMains++;
        return super.mineMain(extraRefs, expectBest);
    }

    @Test
    public void reorgThenReapplyEqualsDirectApply() throws Exception {
        long fixtureStart = generateTime; // setUpChain() leaves it at the fixture constant
        boolean firstRun = true;
        for (long seed : seeds()) {
            ReorgScenario sc = ReorgScenario.generate(seed);
            String tag = sc.toString();
            long t0 = System.nanoTime();

            // ---- run A: apply above a fork point
            if (!firstRun) {
                freshFixture(fixtureStart);
            }
            firstRun = false;
            beginScenario(seed);
            for (int i = 0; i < 4; i++) {
                mineMain(List.of());
            }
            Block forkPoint = mineMain(List.of());
            long forkTime = generateTime;
            apply(sc, tag);
            for (Block p : paying) {
                assertTrue(tag + ": paying block " + p.getHashLow() + " was not applied by run A (infeasible sequence?)",
                        hasFlag(p, BI_APPLIED));
            }
            Bytes32 appliedHash = chainStore.stateHash();
            List<byte[]> appliedKeys = chainStore.sortedKeys();
            Map<String, String> appliedBalances = balances();
            List<Block> oldMains = List.copyOf(scenarioMains);
            List<Long> heights = List.copyOf(scenarioHeights);
            List<List<Bytes32>> refs = List.copyOf(refsPerHeight);
            List<Integer> counts = List.copyOf(mainsPerHeight);
            int oldBranchMains = counts.stream().mapToInt(Integer::intValue).sum();

            // ---- fork: the same paying blocks at the same heights on a competing branch
            rewindTo(forkPoint, forkTime);
            List<Block> branchMains = new ArrayList<>();
            Block branchTip = null;
            for (int h = 0; h < refs.size(); h++) {
                Block m = mineMain(refs.get(h), false);
                branchMains.add(m);
                branchTip = m;
                for (int i = 1; i < counts.get(h); i++) {
                    branchTip = mineMain(List.of(), false);
                }
            }
            // 2N+2 mains of weight >= 2^46 outweigh N mains of weight < 2^47; the generator's bound
            // assumes one confirmation per height, so lower-bound it by what branch A really mined.
            int bound = Math.max(sc.branchLength, oldBranchMains + 2);
            int extra = 0;
            Block lastScenarioMain = branchMains.get(branchMains.size() - 1);
            while (!isTop(branchTip) || !hasFlag(lastScenarioMain, BI_MAIN)) {
                assertTrue(tag + ": fork branch did not overtake within " + bound + " extra mains", extra < bound);
                branchTip = mineMain(List.of(), false);
                extra++;
            }

            // (a) main-ness moved from the old branch to the new one, height for height
            for (int h = 0; h < heights.size(); h++) {
                assertFalse(tag + ": old-branch main of scenario height " + h + " (height " + heights.get(h) + ") is still BI_MAIN",
                        hasFlag(oldMains.get(h), BI_MAIN));
                assertTrue(tag + ": branch main of scenario height " + h + " is not BI_MAIN", hasFlag(branchMains.get(h), BI_MAIN));
                assertEquals(tag + ": branch main height mismatch at scenario height " + h,
                        (long) heights.get(h), heightOf(branchMains.get(h)));
            }
            // (b) every paying block was re-applied on the branch
            for (Block p : paying) {
                assertTrue(tag + ": paying block " + p.getHashLow() + " was not re-applied on the branch", hasFlag(p, BI_APPLIED));
            }
            // (c) unwind + re-apply is the identity on CHAIN_L1
            assertStateHash(tag + ": CHAIN_L1 after unwind + re-apply differs from the pre-fork state", appliedHash, appliedKeys);
            // (d) and on the L1 balances/nonces it touched
            assertEquals(tag + ": balances/nonces after the reorg differ from the pre-fork snapshot", appliedBalances, balances());
            long t1 = System.nanoTime();

            // ---- run B: direct apply on a fresh fixture
            freshFixture(fixtureStart);
            beginScenario(seed);
            for (int i = 0; i < 5; i++) {
                mineMain(List.of());
            }
            apply(sc, tag);
            // (e) run A's pre-fork state is the plain direct-apply state
            assertStateHash(tag + ": direct apply on a fresh node differs from run A's pre-fork state", appliedHash, appliedKeys);
            assertEquals(tag + ": balances/nonces of the direct apply differ from run A's pre-fork snapshot",
                    appliedBalances, balances());
            long t2 = System.nanoTime();
            System.out.printf("reorg property: seed=%d heights=%d paying=%d oldBranchMains=%d extraMains=%d runA+reorg=%.1fs runB=%.1fs%n",
                    seed, sc.heights.size(), paying.size(), oldBranchMains, extra, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
        }
    }

    // ---- scenario execution ----

    /** Applies the scenario's heights in order, recording per height the refs linked and the mains mined. */
    private void apply(ReorgScenario sc, String tag) {
        for (List<ReorgScenario.Step> steps : sc.heights) {
            // The generator already emits a height's steps stably sorted by sender (applyBlock walks
            // the links in list order and enforces strict per-sender nonce sequencing, and nonces are
            // handed out in execution order); this stable sort is a no-op guard restating that.
            List<ReorgScenario.Step> ordered = new ArrayList<>(steps);
            ordered.sort(Comparator.comparingInt(ReorgScenario.Step::sender));
            List<Bytes32> refs = new ArrayList<>();
            Block last = null;
            for (ReorgScenario.Step s : ordered) {
                last = execute(s);
                paying.add(last);
                refs.add(hashLow(last));
            }
            int before = minedMains;
            Block main = mineMain(refs);
            if (last != null) {
                confirm(last);
            } else {
                mineMain(List.of());
            }
            refsPerHeight.add(List.copyOf(refs));
            mainsPerHeight.add(minedMains - before);
            scenarioMains.add(main);
            assertTrue(tag + ": scenario main was not set main after its confirmations", hasFlag(main, BI_MAIN));
            scenarioHeights.add(heightOf(main));
        }
    }

    /** Builds and imports the block(s) for one step; returns the paying block to link. */
    private Block execute(ReorgScenario.Step step) {
        ECKeyPair key = senders[step.sender()];
        Bytes args = payload(20, step.payloadSeed());
        switch (step.op()) {
            case DEPLOY_NEW, DEPLOY_BADGAS -> {
                Bytes wasm = payload(600, step.payloadSeed());
                ChainConfigExt cfg = step.op() == ReorgScenario.Op.DEPLOY_BADGAS ? BAD_GAS_CFG : CFG;
                int chunks = ChainBlockBuilder.deployNewChainChunks(wasm.size(), args.size());
                XAmount fee = ChainBlockBuilder.minHeaderFee(config.getChainSpec().getChainChunkFee(), chunks);
                ChainBlockBuilder.Built b = ChainBlockBuilder.deployNewChain(config, txTime(), key, nonce(step.sender()),
                        fee, wasm, cfg, args, 1000L).value();
                importBuilt(b);
                if (step.op() == ReorgScenario.Op.DEPLOY_NEW) {
                    chainIds.add(ChainIds.chainIdOf(b.block().getHash()));
                    contracts.add(ChainIds.contractIdOf(b.block().getHash()));
                    codeHashes.add(HashUtils.sha256(wasm));
                }
                return b.block();
            }
            case DEPLOY_JOIN -> {
                ChainBlockBuilder.Built b = ChainBlockBuilder.deployIntoChain(config, txTime(), key, nonce(step.sender()),
                        chainIds.get(step.chainRef()), ONE_XDAG, FEE, null, codeHashes.get(step.chainRef()), args, 1L).value();
                importBuilt(b);
                return b.block();
            }
            case CALL_HIT, CALL_LOWFEE -> {
                boolean lowFee = step.op() == ReorgScenario.Op.CALL_LOWFEE;
                Bytes callArgs = lowFee ? payload(1000, step.payloadSeed()) : args;
                XAmount fee = lowFee ? XAmount.of(20, XUnit.MILLI_XDAG) : FEE;
                ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, txTime(), key, nonce(step.sender()),
                        chainIds.get(step.chainRef()), contracts.get(step.chainRef()), 1, 100L, ONE_XDAG, fee, callArgs).value();
                importBuilt(b);
                return b.block();
            }
            case CALL_MISS -> {
                ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, txTime(), key, nonce(step.sender()),
                        chainIds.get(step.chainRef()), UNKNOWN_CONTRACT, 1, 100L, ONE_XDAG, FEE, args).value();
                importBuilt(b);
                return b.block();
            }
            case PLAIN -> {
                Address from = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()), XDAG_FIELD_INPUT, true);
                Bytes to = chainIds.isEmpty() ? UNKNOWN_VAULT : chainIds.get(0);
                Address dst = new Address(BytesUtils.arrayToByte32(to.toArray()), XDAG_FIELD_OUTPUT, true);
                Block plain = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(
                        config, key, txTime(), from, dst, ONE_XDAG, nonce(step.sender())).toBytes()));
                assertImported(plain);
                return plain;
            }
            default -> throw new IllegalStateException(step.op().name());
        }
    }

    private UInt64 nonce(int sender) {
        UInt64 n = nonces[sender];
        nonces[sender] = n.add(UInt64.ONE);
        return n;
    }

    // ---- fixture / state helpers ----

    /** Clears the scenario bookkeeping and funds the seed-derived senders identically on every run. */
    private void beginScenario(long seed) {
        chainIds.clear();
        contracts.clear();
        codeHashes.clear();
        paying.clear();
        refsPerHeight.clear();
        mainsPerHeight.clear();
        scenarioMains.clear();
        scenarioHeights.clear();
        for (int i = 0; i < senders.length; i++) {
            senders[i] = senderKey(seed, i);
            nonces[i] = UInt64.ONE;
            addressStore.updateBalance(senders[i].toAddress().toArray(), FUNDING);
        }
    }

    /**
     * Deterministic sender key: sha256("reorg-sender" | seed | i). A scalar at or above the curve
     * order (probability ~2^-128) makes fromBytes throw; it is re-hashed, still deterministically.
     */
    static ECKeyPair senderKey(long seed, int i) {
        Bytes32 scalar = HashUtils.sha256(Bytes.concatenate(
                Bytes.wrap("reorg-sender".getBytes(UTF_8)), Bytes.ofUnsignedLong(seed), Bytes.of((byte) i)));
        for (;;) {
            try {
                return ECKeyPair.fromPrivateKey(PrivateKey.fromBytes(scalar));
            } catch (CryptoException e) {
                scalar = HashUtils.sha256(scalar);
            }
        }
    }

    /**
     * Tears the fixture down and builds a fresh one on the same mining timeline, so a run reproduces
     * another run's paying blocks byte for byte (RFC 6979 signatures, same keys, nonces, timestamps).
     */
    private void freshFixture(long fixtureStart) throws Exception {
        tearDownChain();
        // setUpChain() calls root.newFolder("node"), which throws if the folder still exists.
        FileUtils.deleteDirectory(new File(root.getRoot(), "node"));
        generateTime = fixtureStart;
        setUpChain();
    }

    /** Balances and executed nonces of the senders, plus the balances of every vault the scenario paid into. */
    private Map<String, String> balances() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ECKeyPair s : senders) {
            byte[] a = s.toAddress().toArray();
            out.put(Bytes.wrap(a).toHexString(),
                    addressStore.getBalanceByAddress(a) + "/" + addressStore.getExecutedNonceNum(a));
        }
        for (Bytes c : chainIds) {
            out.put(c.toHexString(), balanceOf(c).toString());
        }
        out.put(UNKNOWN_VAULT.toHexString(), balanceOf(UNKNOWN_VAULT).toString());
        return out;
    }

    private boolean hasFlag(Block b, int flag) {
        Block stored = blockchain.getBlockByHash(b.getHashLow(), false);
        assertNotNull("block was never stored: " + b.getHashLow(), stored);
        return (stored.getInfo().getFlags() & flag) != 0;
    }

    private boolean isTop(Block b) {
        return Arrays.equals(hashLow(b).toArray(), blockchain.getXdagTopStatus().getTop());
    }

    /** stateHash equality; on a mismatch the failure names the symmetric difference of the key sets. */
    private void assertStateHash(String message, Bytes32 expected, List<byte[]> expectedKeys) {
        Bytes32 actual = chainStore.stateHash();
        if (actual.equals(expected)) {
            return;
        }
        Set<String> exp = hexSet(expectedKeys);
        Set<String> act = hexSet(chainStore.sortedKeys());
        Set<String> onlyExpected = new LinkedHashSet<>(exp);
        onlyExpected.removeAll(act);
        Set<String> onlyActual = new LinkedHashSet<>(act);
        onlyActual.removeAll(exp);
        fail(message + " (state hash " + actual + " != " + expected + "); keys only in the reference state: "
                + onlyExpected + "; keys only in the actual state: " + onlyActual
                + (onlyExpected.isEmpty() && onlyActual.isEmpty() ? "; the key sets are identical, so a value differs" : ""));
    }

    private static Set<String> hexSet(List<byte[]> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (byte[] k : keys) {
            out.add(Bytes.wrap(k).toHexString());
        }
        return out;
    }
}
