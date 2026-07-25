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
        assertEquals(EvmTxPool.AddResult.NONCE_MISMATCH, pool.add(tx(key, 5, Wei.of(2_000_000_000L)).getRawRlp()));
    }

    @Test
    public void insufficient_balance_rejected() {
        // gasLimit * gasPrice alone exceeds the 1-ETH balance: 21000 * 10^15 = 2.1 * 10^19 > 10^18
        EvmTransaction expensive = tx(key, 0, Wei.of(new BigInteger("1000000000000000")));
        assertEquals(EvmTxPool.AddResult.INSUFFICIENT_BALANCE, pool.add(expensive.getRawRlp()));
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
    public void remove_drops_pool_entry() {
        EvmTransaction t = tx(key, 0, Wei.of(2_000_000_000L));
        pool.add(t.getRawRlp());
        pool.remove(t.getHash());
        assertEquals(0, pool.size());
        // The persisted blob stays: consensus may still need it for blocks already referencing it.
        assertTrue(txStore.contains(t.getHash()));
    }
}
