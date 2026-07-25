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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.state.InMemoryKVSource;
import java.math.BigInteger;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Before;
import org.junit.Test;

/** Round-trips signed transaction blobs through the EVM_TX key layout (0x00|txHash -> rlp). */
public class EvmTxStoreTest {

    private EvmTxStore store;
    private EvmTransaction tx;

    @Before
    public void setUp() {
        store = new EvmTxStore(new InMemoryKVSource());
        SECP256K1 algo = new SECP256K1();
        KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
        tx = EvmTransaction.unsigned(0L, Wei.of(1_000_000_000L), 21_000L, Optional.empty(),
                Wei.ZERO, Bytes.fromHexString("0x00"), BigInteger.valueOf(0xCAFE)).sign(key, algo);
    }

    @Test
    public void put_get_contains_remove_round_trip() {
        Hash hash = tx.getHash();
        assertFalse(store.contains(hash));
        assertTrue(store.get(hash).isEmpty());

        store.put(tx);
        assertTrue(store.contains(hash));
        assertEquals(tx.getRawRlp(), store.get(hash).orElseThrow());

        EvmTransaction decoded = store.getDecoded(hash).orElseThrow();
        assertEquals(tx.getHash(), decoded.getHash());
        assertEquals(tx.getSender(), decoded.getSender());

        store.remove(hash);
        assertFalse(store.contains(hash));
    }

    @Test
    public void putRaw_is_idempotent() {
        store.putRaw(tx.getHash(), tx.getRawRlp());
        store.putRaw(tx.getHash(), tx.getRawRlp());
        assertEquals(tx.getRawRlp(), store.get(tx.getHash()).orElseThrow());
    }
}
