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
package io.xdag.chain.exec.wasm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.chain.InMemoryKVSource;
import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.BondAccount;
import io.xdag.chain.anchor.BondVault;
import io.xdag.chain.anchor.ChallengeRecord;
import io.xdag.chain.anchor.HeightTriggers;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.asset.Settlement;
import io.xdag.chain.exec.ChainExecStore;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ChainInput;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.ext.AnchorExt;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.fraud.VerdictCache;
import io.xdag.chain.fraud.Witness;
import io.xdag.chain.l1.ApplyContext;
import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ChainRecord;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.l1.InputRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.chain.l1.handlers.AnchorHandler;
import io.xdag.chain.l1.handlers.BondHandler;
import io.xdag.chain.l1.handlers.ChallengeHandler;
import io.xdag.chain.l1.handlers.ClaimHandler;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.chain.ext.ExtKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

/** Interpreter replay of one call. A matching commitment pays the attester; a substituted one voids. */
public class E2FlowTest {

    private static final long TS = 0x16a00000000L;
    private static final long MIN_BOND = 1_000L;
    private static final long GAS = 100_000L;

    @Test
    public void honestReplayPaysTheAttesterAndABadRowDoesNotSlash() {
        Fixture f = new Fixture();
        Bytes32 real = f.execute();
        f.anchor(real);
        f.file(f.witness(List.of()), 100L);
        f.file(f.witness(List.of(new Witness.Row(Bytes32.ZERO, new byte[] {1, 2, 3}))), 100L);
        f.fund(200L);
        f.triggers.fire(4L);
        assertEquals(AnchorRecord.Status.CANONICAL, f.body().status());
        assertEquals(MIN_BOND, f.account().amount());
        assertEquals(200L, f.balance(f.attester));
        assertEquals(0L, f.balance(f.challenger));
        assertEquals(0L, f.balance(BondVault.BURN.toArray()));
    }

    @Test
    public void substitutedCommitmentVoids() {
        Fixture f = new Fixture();
        Bytes32 real = f.execute();
        Bytes32 lie = Bytes32.repeat((byte) 9);
        assertNotEquals(real, lie);
        f.anchor(lie);
        f.file(f.witness(List.of()), 100L);
        f.fund(MIN_BOND + 100L);
        f.triggers.fire(4L);
        assertEquals(ChallengeRecord.SUCCESS, f.challenge().status());
        assertEquals(AnchorRecord.Status.VOIDED, f.body().status());
        assertEquals(0L, f.account().amount());
        assertEquals(MIN_BOND / 2 + 100L, f.balance(f.challenger));
        assertEquals(MIN_BOND - MIN_BOND / 2, f.balance(BondVault.BURN.toArray()));
    }

    private static byte[] module() {
        WasmAsm asm = new WasmAsm();
        int empty = asm.addType(new int[0], new int[0]);
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.addFunc(empty, new byte[] {0x0b});
        asm.exportFunc("init", 0);
        asm.exportFunc("call", 1);
        asm.exportFunc("recv", 2);
        asm.exportFunc("query", 3);
        asm.exportMemory();
        return asm.bytes();
    }

    private static final class Fixture {
        final DevnetConfig config = new DevnetConfig();
        final ECKeyPair attesterKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
        final ECKeyPair challengerKey = ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY2);
        final byte[] attester = attesterKey.toAddress().toArray();
        final byte[] challenger = challengerKey.toAddress().toArray();
        final Bytes chain = address(1);
        final Bytes contract = address(2);
        final byte[] code = module();
        final long value = Long.parseLong(ChainBlockBuilder.requiredValue(XAmount.ZERO, 0).toString());
        final ChainL1Store store = new ChainL1Store(new InMemoryKVSource());
        final AddressStoreImpl addresses = new AddressStoreImpl(new InMemoryKVSource());
        final Map<Bytes32, Block> blocks = new HashMap<>();
        final Map<Long, Bytes32> mains = new HashMap<>();
        final ClaimHandler.Settlement settlement = new ClaimHandler.Settlement() {
            @Override
            public boolean transfer(byte[] from, byte[] to, long amount, Bytes32 journalKey) {
                return Settlement.move(addresses, from, to, amount);
            }

            @Override
            public void reverse(byte[] from, byte[] to, long amount, Bytes32 journalKey) {
                if (!Settlement.move(addresses, to, from, amount)) {
                    throw new IllegalStateException("short");
                }
            }
        };
        final AnchorHandler anchors;
        final ChallengeHandler challenges;
        final HeightTriggers triggers;
        Bytes32 callHash;
        Bytes32 anchorHash;
        Bytes32 challengeHash;
        int nonce = 3;

        Fixture() {
            store.start();
            addresses.start();
            anchors = new AnchorHandler(store, blocks::get, mains::get, MIN_BOND, 4L, 64);
            challenges = new ChallengeHandler(store, blocks::get, settlement, MIN_BOND, 1 << 20, 64);
            triggers = new HeightTriggers(store, blocks::get, settlement, 4L, 4, 1 << 20, 64, new VerdictCache());
            byte[] deploy = new byte[32];
            deploy[0] = 7;
            System.arraycopy(chain.toArray(), 0, deploy, 12, 20);
            ChainL1Batch batch = new ChainL1Batch();
            batch.putChain(chain, new ChainRecord(1L, Bytes32.wrap(deploy), 0L, 0L, 1_000_000L, 0L));
            Bytes32 codeHash = HashUtils.sha256(Bytes.wrap(code));
            batch.putContract(contract, new ContractRecord(chain, codeHash, 1L, Bytes32.ZERO));
            batch.putCode(codeHash, 1L, Bytes.wrap(code));
            store.commit(batch);
            ChainL1Batch bond = new ChainL1Batch();
            assertTrue(new BondHandler(store, 4L).apply(new io.xdag.chain.ext.BondExt(false, chain, 0L), attester,
                    MIN_BOND, 1L, bond));
            store.commit(bond);
        }

        Bytes32 execute() {
            var built = ChainBlockBuilder.call(config, TS, attesterKey, UInt64.valueOf(1), chain, contract, 1, GAS,
                    XAmount.of(value), XAmount.ZERO, Bytes.EMPTY);
            assertTrue(built.isOk());
            keep(built.value());
            callHash = Bytes32.wrap(built.value().block().getHashLow().toArray());
            ChainL1Batch batch = new ChainL1Batch();
            batch.putCallCount(chain, 2L, 1L);
            batch.putInput(chain, 2L, 0L, new InputRecord(callHash, ExtKind.CALL, InputStatus.OK, contract));
            store.commit(batch);
            ChainExecStore execStore = new ChainExecStore(new InMemoryKVSource());
            execStore.start();
            ChainExecutor exec = new ChainExecutor(ChicoryEngine.interpreter(), store, execStore, blocks::get, 64);
            ChainExecutor.Outcome out = exec.apply(chain, 2L, 0L, 1_000_000L, TS, List.of(new ChainExecutor.Tx(callHash,
                    ChainInput.Kind.CALL, null, Bytes.wrap(attester), contract, value, GAS, 1, new byte[0], code))).get(0);
            assertEquals(ExecStatus.OK, out.status());
            return out.commitment();
        }

        void anchor(Bytes32 commitment) {
            byte[] payload = new byte[64];
            System.arraycopy(commitment.toArray(), 0, payload, 0, 32);
            Bytes32 main = AnchorBody.hashLow(Bytes32.repeat((byte) 3));
            mains.put(3L, main);
            byte[] deploy = new byte[32];
            deploy[0] = 7;
            System.arraycopy(chain.toArray(), 0, deploy, 12, 20);
            var built = ChainBlockBuilder.anchor(config, TS, attesterKey, UInt64.valueOf(2), XAmount.ZERO, chain, 3L,
                    Bytes32.ZERO, Bytes32.ZERO, 1L, 2L, 0L, AnchorBody.hashLow(Bytes32.wrap(deploy)), main,
                    Bytes.wrap(payload));
            assertTrue(built.isOk());
            keep(built.value());
            AnchorExt ext = ChainBlockClassifier.classify(built.value().block()).as(AnchorExt.class);
            anchorHash = AnchorBody.hashLow(built.value().block().getHash());
            ChainL1Batch batch = new ChainL1Batch();
            assertTrue(anchors.apply(ext, anchorHash, attester, 3L, payload, batch));
            store.commit(batch);
        }

        byte[] witness(List<Witness.Row> rows) {
            return Witness.encodeE2(new Witness.E2Body(rows, code, attester, contract.toArray(), value, GAS, 0L, 1,
                    new byte[0], TS, callHash, new byte[0]));
        }

        void file(byte[] witness, long deposit) {
            var built = ChainBlockBuilder.challenge(config, TS, challengerKey, UInt64.valueOf(nonce++), XAmount.ZERO,
                    0L, deposit, anchorHash, Bytes.wrap(witness));
            assertTrue(built.isOk());
            keep(built.value());
            challengeHash = AnchorBody.hashLow(built.value().block().getHash());
            ChainL1Batch batch = new ChainL1Batch();
            challenges.onApplied(built.value().block(), ChainBlockClassifier.classify(built.value().block()),
                    new ApplyContext(4L, Bytes32.ZERO), batch);
            assertTrue(!batch.isEmpty());
            store.commit(batch);
        }

        void fund(long nano) {
            addresses.updateBalance(BondVault.ADDRESS.toArray(), XAmount.of(nano));
        }

        void keep(ChainBlockBuilder.Built built) {
            blocks.put(Bytes32.wrap(built.block().getHashLow().toArray()), built.block());
            for (Block chunk : built.chunks()) {
                blocks.put(Bytes32.wrap(chunk.getHashLow().toArray()), chunk);
            }
        }

        long balance(byte[] who) {
            return Long.parseLong(addresses.getBalanceByAddress(who).toString());
        }

        BondAccount account() {
            return BondAccount.decode(store.getBond(Bytes.wrap(attester), chain));
        }

        AnchorBody body() {
            return AnchorBody.decode(store.getAnchorRaw(chain, 3L));
        }

        ChallengeRecord challenge() {
            return ChallengeRecord.decode(store.getChallenge(challengeHash));
        }

        static Bytes address(int n) {
            byte[] raw = new byte[20];
            raw[19] = (byte) n;
            return Bytes.wrap(raw);
        }
    }
}
