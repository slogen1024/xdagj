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
package io.xdag.evm.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Before;
import org.junit.Test;

/** EIP-155 mempool: validation gates, replace-by-fee, gas-price ordering, TTL expiry (spec §4.4). */
public class EvmTxPoolTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(0xCAFE);
    private static final long BLOCK_GAS_LIMIT = 30_000_000L;
    private static final Wei MIN_GAS_PRICE = Wei.of(1_000_000_000L); // 1 gwei

    private final SECP256K1 algo = new SECP256K1();
    private final KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
    private final KeyPair otherKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.TWO));
    private final Address sender = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");

    private InMemoryKVSource stateSource;
    private EvmTxStore txStore;
    private long[] clock;
    private EvmTxPool pool;

    @Before
    public void setUp() {
        stateSource = new InMemoryKVSource();
        txStore = new EvmTxStore(new InMemoryKVSource());
        clock = new long[]{1_000L};
        pool = new EvmTxPool(txStore, stateSource, CHAIN_ID, BLOCK_GAS_LIMIT, MIN_GAS_PRICE,
                3600L, () -> clock[0]);
        fund(sender, Wei.fromEth(1), 0L);
        fund(Address.extract(otherKey.getPublicKey()), Wei.fromEth(1), 0L);
    }

    private void fund(Address address, Wei balance, long nonce) {
        RocksDbWorldUpdater world = new RocksDbWorldUpdater(stateSource);
        var account = world.getAccount(address);
        if (account == null) {
            world.createAccount(address, nonce, balance);
        } else {
            account.setBalance(balance);
            account.setNonce(nonce);
        }
        world.commit();
    }

    private EvmTransaction tx(KeyPair signer, long nonce, Wei gasPrice) {
        return EvmTransaction.unsigned(nonce, gasPrice, 21_000L,
                Optional.of(Address.fromHexString("0x2222222222222222222222222222222222222222")),
                Wei.of(1), Bytes.EMPTY, CHAIN_ID).sign(signer, algo);
    }

    @Test
    public void valid_tx_is_added_and_blob_persisted() {
        EvmTransaction t = tx(key, 0, Wei.of(2_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(t.getRawRlp()));
        assertEquals(1, pool.size());
        assertTrue(txStore.contains(t.getHash()));
        assertEquals(t.getHash(), pool.get(t.getHash()).orElseThrow().getHash());
    }

    @Test
    public void wrong_chain_id_rejected() {
        EvmTransaction wrongChain = EvmTransaction.unsigned(0, Wei.of(2_000_000_000L), 21_000L,
                Optional.of(Address.fromHexString("0x2222222222222222222222222222222222222222")),
                Wei.of(1), Bytes.EMPTY, BigInteger.ONE).sign(key, algo);
        assertEquals(EvmTxPool.AddResult.WRONG_CHAIN_ID, pool.add(wrongChain.getRawRlp()));
    }

    @Test
    public void garbage_rlp_rejected() {
        assertEquals(EvmTxPool.AddResult.INVALID_ENCODING, pool.add(Bytes.fromHexString("0xdeadbeef")));
    }

    @Test
    public void nonce_mismatch_rejected() {
        // Window is [accountNonce, accountNonce + MAX_PER_SENDER - 1] = [0, 15]; nonce 16 is outside.
        assertEquals(EvmTxPool.AddResult.NONCE_MISMATCH,
                pool.add(tx(key, EvmTxPool.MAX_PER_SENDER, Wei.of(2_000_000_000L)).getRawRlp()));
    }

    @Test
    public void insufficient_balance_rejected_on_value_only() {
        // A zero balance cannot even cover the transferred value (let alone the gas fee).
        fund(sender, Wei.ZERO, 0L);
        EvmTransaction broke = EvmTransaction.unsigned(0, Wei.of(2_000_000_000L), 21_000L,
                Optional.of(Address.fromHexString("0x2222222222222222222222222222222222222222")),
                Wei.of(5), Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        assertEquals(EvmTxPool.AddResult.INSUFFICIENT_BALANCE, pool.add(broke.getRawRlp()));
    }

    @Test
    public void insufficient_balance_for_the_gas_fee_is_rejected() {
        // Gas now settles in EVM wei (缺口2): the sender must cover value + gasLimit * gasPrice. A
        // balance covering only the 1-wei value but not the gas fee is rejected (ADR-007 allowed it).
        fund(sender, Wei.of(1), 0L);
        EvmTransaction expensiveGas = tx(key, 0, Wei.of(new BigInteger("1000000000000000")));
        assertEquals(EvmTxPool.AddResult.INSUFFICIENT_BALANCE, pool.add(expensiveGas.getRawRlp()));
    }

    @Test
    public void below_min_gas_price_rejected() {
        assertEquals(EvmTxPool.AddResult.UNDERPRICED, pool.add(tx(key, 0, Wei.of(1L)).getRawRlp()));
    }

    @Test
    public void gas_limit_above_block_limit_rejected() {
        EvmTransaction bigGas = EvmTransaction.unsigned(0, Wei.of(2_000_000_000L), BLOCK_GAS_LIMIT + 1,
                Optional.of(Address.fromHexString("0x2222222222222222222222222222222222222222")),
                Wei.ZERO, Bytes.EMPTY, CHAIN_ID).sign(key, algo);
        assertEquals(EvmTxPool.AddResult.GAS_LIMIT_TOO_HIGH, pool.add(bigGas.getRawRlp()));
    }

    @Test
    public void intrinsic_gas_above_tx_gas_limit_rejected() {
        // 21000-gas limit cannot even cover the calldata cost of 100 non-zero bytes.
        EvmTransaction thin = EvmTransaction.unsigned(0, Wei.of(2_000_000_000L), 21_000L,
                Optional.of(Address.fromHexString("0x2222222222222222222222222222222222222222")),
                Wei.ZERO, Bytes.repeat((byte) 1, 100), CHAIN_ID).sign(key, algo);
        assertEquals(EvmTxPool.AddResult.INTRINSIC_GAS_TOO_LOW, pool.add(thin.getRawRlp()));
    }

    @Test
    public void duplicate_rejected_and_replace_by_fee() {
        EvmTransaction low = tx(key, 0, Wei.of(2_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(low.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.DUPLICATE, pool.add(low.getRawRlp()));

        // Same (sender, nonce), lower price: underpriced. Strictly higher price: replaces.
        assertEquals(EvmTxPool.AddResult.UNDERPRICED, pool.add(tx(key, 0, Wei.of(1_500_000_000L)).getRawRlp()));
        EvmTransaction high = tx(key, 0, Wei.of(3_000_000_000L));
        assertEquals(EvmTxPool.AddResult.REPLACED, pool.add(high.getRawRlp()));
        assertEquals(1, pool.size());
        assertEquals(high.getHash(), pool.selectTransactions(10).getFirst().getHash());
    }

    @Test
    public void selection_orders_by_gas_price_desc_and_caps_count() {
        EvmTransaction cheap = tx(key, 0, Wei.of(2_000_000_000L));
        EvmTransaction rich = tx(otherKey, 0, Wei.of(9_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(cheap.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(rich.getRawRlp()));

        List<EvmTransaction> selected = pool.selectTransactions(10);
        assertEquals(2, selected.size());
        assertEquals(rich.getHash(), selected.get(0).getHash());
        assertEquals(cheap.getHash(), selected.get(1).getHash());
        assertEquals(1, pool.selectTransactions(1).size());
    }

    @Test
    public void expired_txs_are_evicted_and_not_selected() {
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 0, Wei.of(2_000_000_000L)).getRawRlp()));
        clock[0] += 3601L;
        assertTrue(pool.selectTransactions(10).isEmpty());
        pool.evictExpired();
        assertEquals(0, pool.size());
    }

    @Test
    public void new_nonce_replaces_stale_sender_entry_regardless_of_price() {
        // After a tx confirms, the account nonce advances; the stale nonce=0 entry is pruned and the
        // new nonce=1 tx occupies a fresh slot — result is ADDED, not REPLACED (nonce-chain pool).
        EvmTransaction first = tx(key, 0, Wei.of(5_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(first.getRawRlp()));

        fund(sender, Wei.fromEth(1), 1L); // simulate `first` having been executed: account nonce -> 1
        EvmTransaction next = tx(key, 1, Wei.of(1_000_000_000L)); // lower price, but a NEW nonce
        // Old assertion was REPLACED (v1 stale-evict semantics); now stale entries are pruned at
        // admission time and the new nonce fills an empty slot, so the result is ADDED.
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(next.getRawRlp()));
        assertEquals(1, pool.size());
        assertEquals(next.getHash(), pool.selectTransactions(10).getFirst().getHash());
    }

    @Test
    public void pool_rejects_when_full_but_accepts_after_expiry_frees_a_slot() {
        // Fill the pool to capacity with distinct senders, then a fresh sender is rejected...
        for (int i = 0; i < EvmTxPool.MAX_POOL_SIZE; i++) {
            KeyPair k = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(1000 + i)));
            fund(Address.extract(k.getPublicKey()), Wei.fromEth(1), 0L);
            assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(k, 0, Wei.of(2_000_000_000L)).getRawRlp()));
        }
        KeyPair overflowKey = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(overflowKey.getPublicKey()), Wei.fromEth(1), 0L);
        assertEquals(EvmTxPool.AddResult.POOL_FULL, pool.add(tx(overflowKey, 0, Wei.of(2_000_000_000L)).getRawRlp()));

        // ...but once the existing entries expire, add() opportunistically evicts and admits it.
        clock[0] += 3601L;
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(overflowKey, 0, Wei.of(2_000_000_000L)).getRawRlp()));
    }

    @Test
    public void remove_drops_pool_entry() {
        EvmTransaction t = tx(key, 0, Wei.of(2_000_000_000L));
        pool.add(t.getRawRlp());
        pool.remove(t.getHash());
        assertEquals(0, pool.size());
        // The persisted blob stays: consensus may still need it for blocks already referencing it.
        assertTrue(txStore.contains(t.getHash()));
    }

    // ---- nonce-chain (Task D2) tests ----

    @Test
    public void nonce_window_accepts_future_nonces_within_16_and_rejects_beyond() {
        // account nonce = 0; window is [0, MAX_PER_SENDER-1] = [0, 15]
        Wei gasPrice = Wei.of(2_000_000_000L);
        // Fund enough for 16 txs: each costs value(1) + gasLimit(21000) * gasPrice(2e9) = 42_000_000_000_001 wei
        BigInteger singleCost = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(2_000_000_000L)));
        fund(sender, Wei.of(singleCost.multiply(BigInteger.valueOf(EvmTxPool.MAX_PER_SENDER))), 0L);

        for (int n = 0; n < EvmTxPool.MAX_PER_SENDER; n++) {
            assertEquals("nonce " + n + " should be ADDED",
                    EvmTxPool.AddResult.ADDED, pool.add(tx(key, n, gasPrice).getRawRlp()));
        }
        // nonce == MAX_PER_SENDER (16) is outside the window
        assertEquals(EvmTxPool.AddResult.NONCE_MISMATCH,
                pool.add(tx(key, EvmTxPool.MAX_PER_SENDER, gasPrice).getRawRlp()));
        // a nonce below account nonce (negative nonce is unsigned, but nonce=-1 on EVM is Long.MAX_VALUE
        // so use a new sender at nonce=1 while account nonce is 0)
        KeyPair k2 = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(77)));
        Address addr2 = Address.extract(k2.getPublicKey());
        fund(addr2, Wei.fromEth(1), 2L); // account nonce = 2
        assertEquals(EvmTxPool.AddResult.NONCE_MISMATCH,
                pool.add(tx(k2, 1, gasPrice).getRawRlp())); // nonce 1 < accountNonce 2
        assertEquals(EvmTxPool.MAX_PER_SENDER, pool.size());
    }

    @Test
    public void replace_by_fee_is_per_sender_nonce_slot() {
        Wei gasPrice = Wei.of(2_000_000_000L);
        // Fund enough to cover both nonce=0 (2e9) and nonce=1 after replacement (3e9).
        // Worst-case cumulative = (1 + 21000*2e9) + (1 + 21000*3e9) = 42_000_000_001 + 63_000_000_001.
        BigInteger costAt2g = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(2_000_000_000L)));
        BigInteger costAt3g = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(3_000_000_000L)));
        fund(sender, Wei.of(costAt2g.add(costAt3g)), 0L);

        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 0, gasPrice).getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 1, gasPrice).getRawRlp()));

        // Same (sender, nonce=1) with a lower price (distinct tx bytes, not a duplicate): UNDERPRICED.
        // Note: equal-price produces identical tx bytes → DUPLICATE fires first; the UNDERPRICED
        // gate is exercised by a strictly lower price that still produces a different hash.
        assertEquals(EvmTxPool.AddResult.UNDERPRICED,
                pool.add(tx(key, 1, Wei.of(1_500_000_000L)).getRawRlp()));
        // Same (sender, nonce=1): strictly higher price → REPLACED; pool stays at 2
        EvmTransaction higherNonce1 = tx(key, 1, Wei.of(3_000_000_000L));
        assertEquals(EvmTxPool.AddResult.REPLACED, pool.add(higherNonce1.getRawRlp()));
        assertEquals(2, pool.size());
    }

    @Test
    public void cumulative_balance_rejects_a_queue_the_sender_cannot_afford() {
        // Each tx costs value(1) + 21000 * 2e9 = 42_000_000_000_001 wei.
        BigInteger singleCost = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(2_000_000_000L)));
        // Fund exactly 2x cost so the 3rd tx pushes cumulative over balance.
        fund(sender, Wei.of(singleCost.multiply(BigInteger.TWO)), 0L);

        Wei gasPrice = Wei.of(2_000_000_000L);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 0, gasPrice).getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 1, gasPrice).getRawRlp()));
        assertEquals(EvmTxPool.AddResult.INSUFFICIENT_BALANCE, pool.add(tx(key, 2, gasPrice).getRawRlp()));
        assertEquals(2, pool.size());
    }

    // ---- selectBatch (Task D3) tests ----

    @Test
    public void select_batch_orders_senders_by_price_and_nonces_ascending_within_sender() {
        // senderA: gasPrice 20 gwei, nonces 0 and 1
        // senderB: gasPrice 30 gwei, nonce 0
        // Expected order: [B nonce=0, A nonce=0, A nonce=1]
        KeyPair keyA = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(10)));
        KeyPair keyB = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(11)));
        Address addrA = Address.extract(keyA.getPublicKey());
        Address addrB = Address.extract(keyB.getPublicKey());
        fund(addrA, Wei.fromEth(1), 0L);
        fund(addrB, Wei.fromEth(1), 0L);

        Wei priceA = Wei.of(20_000_000_000L);
        Wei priceB = Wei.of(30_000_000_000L);
        EvmTransaction txA0 = tx(keyA, 0, priceA);
        EvmTransaction txA1 = tx(keyA, 1, priceA);
        EvmTransaction txB0 = tx(keyB, 0, priceB);

        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txA0.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txA1.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txB0.getRawRlp()));

        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals(3, batch.size());
        assertEquals(addrB, batch.get(0).getSender());
        assertEquals(0L, batch.get(0).getNonce());
        assertEquals(addrA, batch.get(1).getSender());
        assertEquals(0L, batch.get(1).getNonce());
        assertEquals(addrA, batch.get(2).getSender());
        assertEquals(1L, batch.get(2).getNonce());
    }

    @Test
    public void select_batch_stops_a_sender_at_the_budget_without_leaving_nonce_holes() {
        // senderA has two txs each gasLimit=21000; budget=21000 → only nonce=0 selected
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 0, Wei.of(2_000_000_000L)).getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 1, Wei.of(2_000_000_000L)).getRawRlp()));

        List<EvmTransaction> batch = pool.selectBatch(21_000L);
        assertEquals(1, batch.size());
        assertEquals(0L, batch.get(0).getNonce());
    }

    @Test
    public void select_batch_skips_a_sender_with_a_gap_at_the_head() {
        // senderA queues only nonce=1 (account nonce=0) → head gap, disqualified
        // senderB queues nonce=0 → selected
        KeyPair keyA = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(20)));
        KeyPair keyB = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(21)));
        Address addrA = Address.extract(keyA.getPublicKey());
        Address addrB = Address.extract(keyB.getPublicKey());
        fund(addrA, Wei.fromEth(1), 0L);
        fund(addrB, Wei.fromEth(1), 0L);

        // Submit nonce=0 for A first so we can then replace/add nonce=1; but account nonce is 0,
        // so we need to skip nonce=0 entirely. We can achieve a gap by: add nonce=0 and nonce=1,
        // then remove nonce=0 from the pool (simulating it being consumed or manually removed).
        // Simpler: advance account nonce to 1 AFTER adding nonce=1 only — but nonce=1 requires
        // window [0,15] so nonce=1 IS accepted when account nonce=0. Then we advance A's nonce to 0
        // still (no on-chain change). The gap is: accountNonce=0, first queued nonce=1.
        // So just add nonce=1 (within window) and DON'T add nonce=0 for sender A.
        EvmTransaction txA1 = tx(keyA, 1, Wei.of(2_000_000_000L));
        EvmTransaction txB0 = tx(keyB, 0, Wei.of(2_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txA1.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txB0.getRawRlp()));

        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals(1, batch.size());
        assertEquals(addrB, batch.get(0).getSender());
        assertEquals(0L, batch.get(0).getNonce());
    }

    @Test
    public void select_batch_respects_the_max_batch_size_cap() {
        // 65 distinct senders one tx each → selectBatch(Long.MAX_VALUE, 64) returns 64
        for (int i = 0; i < 65; i++) {
            KeyPair k = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(1000 + i)));
            Address addr = Address.extract(k.getPublicKey());
            fund(addr, Wei.fromEth(1), 0L);
            assertEquals(EvmTxPool.AddResult.ADDED,
                    pool.add(tx(k, 0, Wei.of(2_000_000_000L)).getRawRlp()));
        }
        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE, 64);
        assertEquals(64, batch.size());
    }

    @Test
    public void stale_entries_are_pruned_when_the_account_nonce_advances() {
        Wei gasPrice = Wei.of(2_000_000_000L);
        BigInteger singleCost = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(2_000_000_000L)));
        fund(sender, Wei.of(singleCost.multiply(BigInteger.TWO)), 0L);

        EvmTransaction t0 = tx(key, 0, gasPrice);
        EvmTransaction t1 = tx(key, 1, gasPrice);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(t0.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(t1.getRawRlp()));
        assertEquals(2, pool.size());

        // Advance account nonce to 1 (as if t0 executed on-chain).
        fund(sender, Wei.fromEth(1), 1L);

        // Adding any tx for this sender triggers pruning of nonce < accountNonce.
        EvmTransaction t2 = tx(key, 1, Wei.of(3_000_000_000L)); // replaces t1 at same nonce
        assertEquals(EvmTxPool.AddResult.REPLACED, pool.add(t2.getRawRlp()));

        // t0 (nonce=0) was stale and must have been removed from byHash.
        assertTrue("stale nonce=0 entry must be pruned from the pool", pool.get(t0.getHash()).isEmpty());
    }
}
