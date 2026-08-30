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

    private EvmTransaction type2Tx(KeyPair signer, long nonce, Wei maxPriorityFeePerGas, Wei maxFeePerGas) {
        return EvmTransaction.unsignedType2(nonce, maxPriorityFeePerGas, maxFeePerGas, 21_000L,
                Optional.of(Address.fromHexString("0x2222222222222222222222222222222222222222")),
                Wei.of(1), Bytes.EMPTY, List.of(), CHAIN_ID).sign(signer, algo);
    }

    /** Fills the pool with {@code count} distinct 1-entry senders at {@code price}; returns their keys. */
    private java.util.List<KeyPair> fillPoolDistinctSenders(int count, Wei price) {
        java.util.List<KeyPair> keys = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            KeyPair k = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(10_000 + i)));
            fund(Address.extract(k.getPublicKey()), Wei.fromEth(1), 0L);
            assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(k, 0, price).getRawRlp()));
            keys.add(k);
        }
        return keys;
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
    public void expiry_frees_a_slot_for_an_underpriced_sender_that_cannot_evict() {
        Wei price = Wei.of(2_000_000_000L);
        fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE, price);

        // A cheaper newcomer (>= minGasPrice but below the pool) cannot outbid and cannot displace by
        // fairness (fairness only breaks EQUAL-price ties), so it is rejected while the pool is full...
        KeyPair cheap = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(cheap.getPublicKey()), Wei.fromEth(1), 0L);
        assertEquals(EvmTxPool.AddResult.POOL_FULL,
                pool.add(tx(cheap, 0, MIN_GAS_PRICE).getRawRlp()));

        // ...but once the existing entries expire, opportunistic eviction frees the whole pool and it is admitted.
        clock[0] += 3601L;
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(cheap, 0, MIN_GAS_PRICE).getRawRlp()));
    }

    @Test
    public void full_pool_admits_a_fresh_same_price_sender_by_evicting_a_peer() {
        Wei price = Wei.of(2_000_000_000L);
        java.util.List<KeyPair> filled = fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE, price);
        assertEquals(EvmTxPool.MAX_POOL_SIZE, pool.size());

        // A brand-new sender (load 0) at the SAME price is strictly less-loaded than any 1-entry peer,
        // so it is admitted by evicting a peer tail — it can never be starved out of a full pool.
        KeyPair fresh = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(fresh.getPublicKey()), Wei.fromEth(1), 0L);
        EvmTransaction freshTx = tx(fresh, 0, price);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(freshTx.getRawRlp()));

        assertTrue("the fresh sender's tx is now pooled", pool.get(freshTx.getHash()).isPresent());
        assertEquals("one-in-one-out keeps the cap exact", EvmTxPool.MAX_POOL_SIZE, pool.size());
        // The oldest peer (first inserted; all share the same clock, so insertion order breaks the age tie)
        // is the victim.
        assertTrue("the evicted victim is the first-inserted peer",
                pool.get(tx(filled.get(0), 0, price).getHash()).isEmpty());
    }

    @Test
    public void a_higher_fee_newcomer_evicts_a_lower_fee_tail() {
        Wei low = Wei.of(2_000_000_000L);
        java.util.List<KeyPair> filled = fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE, low);

        // A higher fee outranks the cheapest tail regardless of load -> admitted, a low-fee tail evicted.
        KeyPair rich = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(rich.getPublicKey()), Wei.fromEth(1), 0L);
        EvmTransaction richTx = tx(rich, 0, Wei.of(3_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(richTx.getRawRlp()));
        assertTrue(pool.get(richTx.getHash()).isPresent());
        assertEquals(EvmTxPool.MAX_POOL_SIZE, pool.size());
        assertTrue("a low-fee tail was evicted",
                pool.get(tx(filled.get(0), 0, low).getHash()).isEmpty());
    }

    @Test
    public void a_cheaper_newcomer_is_rejected_when_full() {
        Wei price = Wei.of(2_000_000_000L);
        fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE, price);

        // Below the pool's going rate and not less-loaded than any victim's equal-price peer (there are
        // none at its price) -> it cannot displace anyone -> POOL_FULL, nothing evicted.
        KeyPair cheap = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(cheap.getPublicKey()), Wei.fromEth(1), 0L);
        assertEquals(EvmTxPool.AddResult.POOL_FULL, pool.add(tx(cheap, 0, MIN_GAS_PRICE).getRawRlp()));
        assertEquals("nothing was evicted", EvmTxPool.MAX_POOL_SIZE, pool.size());
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
    public void select_batch_breaks_price_ties_by_insertion_order() {
        // Two senders with the SAME gasPrice; X added before Y → batch order [X's tx, Y's tx].
        KeyPair keyX = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(30)));
        KeyPair keyY = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(31)));
        Address addrX = Address.extract(keyX.getPublicKey());
        Address addrY = Address.extract(keyY.getPublicKey());
        fund(addrX, Wei.fromEth(1), 0L);
        fund(addrY, Wei.fromEth(1), 0L);

        Wei samePrice = Wei.of(5_000_000_000L);
        EvmTransaction txX = tx(keyX, 0, samePrice);
        EvmTransaction txY = tx(keyY, 0, samePrice);

        // X inserted first, Y second — insertion order must break the tie.
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txX.getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txY.getRawRlp()));

        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals(2, batch.size());
        assertEquals("X (inserted first) should come before Y on a price tie",
                addrX, batch.get(0).getSender());
        assertEquals(addrY, batch.get(1).getSender());
    }

    @Test
    public void select_batch_truncates_a_run_at_an_expired_entry() {
        // Sender queues nonce=1 first (while it is fresh), then the clock advances past TTL,
        // then nonce=0 is added (still fresh). The nonce=0 entry starts the run but nonce=1 is
        // expired mid-run, so the run is truncated: only nonce=0 is selected.
        Wei gasPrice = Wei.of(2_000_000_000L);
        // Fund enough to cover both nonces.
        BigInteger singleCost = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(2_000_000_000L)));
        fund(sender, Wei.of(singleCost.multiply(BigInteger.TWO)), 0L);

        // Add nonce=1 early (clock=1000).
        EvmTransaction txNonce1 = tx(key, 1, gasPrice);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txNonce1.getRawRlp()));

        // Advance clock past TTL so nonce=1 is now expired.
        clock[0] += 3601L;

        // Add nonce=0 at the new time (still fresh relative to its addedAtSeconds).
        EvmTransaction txNonce0 = tx(key, 0, gasPrice);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(txNonce0.getRawRlp()));

        // selectBatch: nonce=0 starts the run; nonce=1 is expired → run truncates after nonce=0.
        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals("only nonce=0 should be selected; expired nonce=1 truncates the run",
                1, batch.size());
        assertEquals(0L, batch.get(0).getNonce());
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

    // ---- type-2 / EIP-1559 (defect-1 Task 3) tests ----

    @Test
    public void type2_is_admitted_and_priced_by_effective_gas_price() {
        // setUp funds sender with 1 ETH — far above value(1) + maxFee(10 gwei) * gasLimit(21000).
        // Effective price = min(priority 2 gwei, maxFee 10 gwei) = 2 gwei ≥ minGasPrice (1 gwei).
        EvmTransaction t = type2Tx(key, 0, Wei.of(2_000_000_000L), Wei.of(10_000_000_000L));
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(t.getRawRlp()));
        assertEquals(1, pool.size());
        assertEquals(t.getHash(), pool.selectTransactions(10).getFirst().getHash());
    }

    @Test
    public void cross_type_rbf_compares_effective_price() {
        // Legacy at 5 gwei occupies (sender, nonce=0).
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(key, 0, Wei.of(5_000_000_000L)).getRawRlp()));
        // Type-2 with effective 5 gwei (priority 5, maxFee 20): not STRICTLY higher → UNDERPRICED,
        // even though its fee cap (20 gwei) is far above the incumbent's price.
        assertEquals(EvmTxPool.AddResult.UNDERPRICED,
                pool.add(type2Tx(key, 0, Wei.of(5_000_000_000L), Wei.of(20_000_000_000L)).getRawRlp()));
        // Type-2 with effective 6 gwei (priority 6, maxFee 20): strictly higher → REPLACED.
        EvmTransaction winner = type2Tx(key, 0, Wei.of(6_000_000_000L), Wei.of(20_000_000_000L));
        assertEquals(EvmTxPool.AddResult.REPLACED, pool.add(winner.getRawRlp()));
        assertEquals(1, pool.size());
        assertEquals(winner.getHash(), pool.selectTransactions(10).getFirst().getHash());
    }

    @Test
    public void cross_type_rbf_legacy_replaces_type2_incumbent() {
        // Type-2 incumbent with effective 5 gwei (priority 5, maxFee 100) occupies (sender, nonce=0).
        assertEquals(EvmTxPool.AddResult.ADDED,
                pool.add(type2Tx(key, 0, Wei.of(5_000_000_000L), Wei.of(100_000_000_000L)).getRawRlp()));
        // Legacy challenger at 6 gwei: strictly higher than the incumbent's EFFECTIVE 5 gwei →
        // REPLACED, even though 6 gwei is far below the incumbent's 100 gwei fee cap.
        EvmTransaction winner = tx(key, 0, Wei.of(6_000_000_000L));
        assertEquals(EvmTxPool.AddResult.REPLACED, pool.add(winner.getRawRlp()));
        assertEquals(1, pool.size());
        assertEquals(winner.getHash(), pool.selectTransactions(10).getFirst().getHash());
    }

    @Test
    public void admission_checks_fee_cap_not_effective_price() {
        // Balance covers value + EFFECTIVE(1 gwei) * gasLimit exactly, but admission charges the
        // worst case value + FEE CAP (maxFee 1000 gwei) * gasLimit (Ethereum admission rule).
        BigInteger effectiveCost = BigInteger.ONE
                .add(BigInteger.valueOf(21_000L).multiply(BigInteger.valueOf(1_000_000_000L)));
        fund(sender, Wei.of(effectiveCost), 0L);
        EvmTransaction t = type2Tx(key, 0, Wei.of(1_000_000_000L), Wei.of(1_000_000_000_000L));
        assertEquals(EvmTxPool.AddResult.INSUFFICIENT_BALANCE, pool.add(t.getRawRlp()));
    }

    @Test
    public void select_batch_orders_mixed_types_by_effective_price() {
        // Effective and feeCap DISAGREE about the winner: senderA is type-2 with effective 2 gwei
        // (priority 2, maxFee 50) — feeCap ordering would rank it first; senderB is legacy 5 gwei.
        KeyPair keyA = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(40)));
        KeyPair keyB = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(41)));
        Address addrA = Address.extract(keyA.getPublicKey());
        Address addrB = Address.extract(keyB.getPublicKey());
        fund(addrA, Wei.fromEth(1), 0L);
        fund(addrB, Wei.fromEth(1), 0L);

        assertEquals(EvmTxPool.AddResult.ADDED,
                pool.add(type2Tx(keyA, 0, Wei.of(2_000_000_000L), Wei.of(50_000_000_000L)).getRawRlp()));
        assertEquals(EvmTxPool.AddResult.ADDED,
                pool.add(tx(keyB, 0, Wei.of(5_000_000_000L)).getRawRlp()));

        List<EvmTransaction> batch = pool.selectBatch(Long.MAX_VALUE);
        assertEquals(2, batch.size());
        assertEquals("legacy with the higher EFFECTIVE price (5 > 2 gwei) must come first, "
                        + "even though the type-2 fee cap (50 gwei) is far larger",
                addrB, batch.get(0).getSender());
        assertEquals(addrA, batch.get(1).getSender());
    }

    // ---- fairness / contiguity / no-self-eviction proofs (G3-T3 step 2) ----

    /**
     * Funds {@code k} and queues nonces [0, n) at {@code price}. The account must afford all n txs; the
     * fixture funds Wei.fromEth(1) which covers many 21000-gas txs at these prices.
     */
    private void queueChain(KeyPair k, int n, Wei price) {
        fund(Address.extract(k.getPublicKey()), Wei.fromEth(1), 0L);
        for (long nonce = 0; nonce < n; nonce++) {
            assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(k, nonce, price).getRawRlp()));
        }
    }

    @Test
    public void equal_price_eviction_targets_the_most_loaded_sender_tail() {
        Wei price = Wei.of(2_000_000_000L);
        // (MAX_POOL_SIZE - 3) one-entry senders + one 3-entry sender, all at the same price. The peers
        // are inserted FIRST so the 3-entry sender is LAST in bySender iteration order: only the
        // load-DESC tiebreak (not first-inserted-wins) can then select it as the victim — drop that
        // tiebreak and a first-inserted peer would be evicted instead, failing the assertion below.
        fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE - 3, price);
        KeyPair loaded = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(777_000)));
        queueChain(loaded, 3, price);
        assertEquals(EvmTxPool.MAX_POOL_SIZE, pool.size());

        // A fresh same-price sender: at equal price the victim is the MOST-loaded sender's tail (nonce 2),
        // not any 1-entry peer.
        KeyPair fresh = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(fresh.getPublicKey()), Wei.fromEth(1), 0L);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(fresh, 0, price).getRawRlp()));

        assertTrue("the loaded sender's tail (nonce 2) was evicted",
                pool.get(tx(loaded, 2, price).getHash()).isEmpty());
        // ...and its lower nonces survive, contiguous.
        assertTrue("nonce 0 survives", pool.get(tx(loaded, 0, price).getHash()).isPresent());
        assertTrue("nonce 1 survives", pool.get(tx(loaded, 1, price).getHash()).isPresent());
        assertEquals(EvmTxPool.MAX_POOL_SIZE, pool.size());
    }

    @Test
    public void evicting_a_tail_keeps_the_chain_contiguous_and_selectable() {
        Wei price = Wei.of(2_000_000_000L);
        // The loaded sender is inserted FIRST so selectBatch (gas-budget + MAX_BATCH_TXS limited, so it
        // returns only a prefix of a 4096-entry pool) picks its run early enough that its surviving txs
        // are actually selected. Which sender is the victim is pinned by the sibling fairness test; here
        // we only need loaded to be the victim, which the load-DESC tiebreak guarantees regardless of order.
        KeyPair loaded = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(777_000)));
        queueChain(loaded, 3, price);
        fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE - 3, price);

        KeyPair fresh = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(999_999)));
        fund(Address.extract(fresh.getPublicKey()), Wei.fromEth(1), 0L);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(tx(fresh, 0, price).getRawRlp()));

        // The loaded sender now holds exactly nonces [0, 1]; selectBatch (which stops a sender at the first
        // nonce gap) must still return both — proving no middle nonce was evicted.
        long loadedSelected = pool.selectBatch(BLOCK_GAS_LIMIT).stream()
                .filter(t -> t.getSender().equals(Address.extract(loaded.getPublicKey())))
                .count();
        assertEquals("both surviving nonces of the loaded sender are selectable (contiguous)",
                2L, loadedSelected);
    }

    @Test
    public void a_sender_extending_its_chain_evicts_a_peer_not_itself() {
        // self holds the CHEAPEST tails in the pool (at minGasPrice); peers sit strictly above it. So on
        // price alone self's own tail is the globally most-evictable entry — ONLY the self-exclusion
        // guard stops it from being chosen. Drop that guard and self's nonce-1 tail is evicted here
        // (orphaning its chain), failing the assertions below.
        Wei selfLow = MIN_GAS_PRICE;                  // cheapest tails in the pool
        Wei peerMid = Wei.of(2_000_000_000L);
        Wei selfHigh = Wei.of(5_000_000_000L);
        KeyPair self = algo.createKeyPair(algo.createPrivateKey(BigInteger.valueOf(777_000)));
        queueChain(self, 2, selfLow);
        java.util.List<KeyPair> peers = fillPoolDistinctSenders(EvmTxPool.MAX_POOL_SIZE - 2, peerMid);
        assertEquals(EvmTxPool.MAX_POOL_SIZE, pool.size());

        // self adds nonce 2 (within its 16-window) at a high price that outbids a peer. WITH self-exclusion
        // a PEER tail is evicted and self's chain stays contiguous; WITHOUT it self's own cheapest tail
        // (nonce 1) would be the victim.
        EvmTransaction selfNext = tx(self, 2, selfHigh);
        assertEquals(EvmTxPool.AddResult.ADDED, pool.add(selfNext.getRawRlp()));

        assertTrue("self nonce 0 untouched", pool.get(tx(self, 0, selfLow).getHash()).isPresent());
        assertTrue("self nonce 1 NOT self-evicted", pool.get(tx(self, 1, selfLow).getHash()).isPresent());
        assertTrue("self nonce 2 added", pool.get(selfNext.getHash()).isPresent());
        assertTrue("a peer tail was evicted instead",
                pool.get(tx(peers.get(0), 0, peerMid).getHash()).isEmpty());
        assertEquals(EvmTxPool.MAX_POOL_SIZE, pool.size());
    }

    @Test
    public void type2_below_min_gas_price_is_underpriced() {
        // A pool floored at 100 wei judges the EFFECTIVE price (min(1, 1000) = 1 wei < 100), not the
        // fee cap (maxFee 1000 wei ≥ 100) → UNDERPRICED.
        EvmTxPool strictPool = new EvmTxPool(txStore, stateSource, CHAIN_ID, BLOCK_GAS_LIMIT,
                Wei.of(100), 3600L, () -> clock[0]);
        assertEquals(EvmTxPool.AddResult.UNDERPRICED,
                strictPool.add(type2Tx(key, 0, Wei.of(1), Wei.of(1000)).getRawRlp()));
    }
}
