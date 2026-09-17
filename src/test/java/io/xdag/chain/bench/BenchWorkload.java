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

package io.xdag.chain.bench;

import static io.xdag.chain.ext.ChunkChainTest.payload;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.config.Config;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Pre-built synthetic import workload: a list of (chunks…, paying block) groups in import order.
 *
 * <p>Items are generated in one pass with a seeded {@link Random}; every sender's nonces are handed
 * out in item order, so importing (or linking from main blocks) in item order keeps each sender's
 * nonce sequence strictly ascending, which {@code applyBlock} enforces. All timestamps are
 * {@code txTime + n}, strictly increasing ticks inside the one epoch the next main block closes.
 */
public final class BenchWorkload {

    public enum Kind { PLAIN, CALL_INLINE, CALL_CHAIN, DEPLOY }

    public record Item(Kind kind, List<Block> chunks, Block block) {
    }

    public final List<Item> items = new ArrayList<>();
    public final ECKeyPair[] senders;

    public BenchWorkload(Config config, long seed, int senderCount, int blocks, int[] mixPercent,
                         long txTime, Bytes chainId, Bytes contract, Bytes32 sharedCodeHash) {
        Random r = new Random(seed);
        senders = new ECKeyPair[senderCount];
        UInt64[] nonces = new UInt64[senderCount];
        for (int i = 0; i < senderCount; i++) {
            senders[i] = ECKeyPair.generate();
            nonces[i] = UInt64.ONE;
        }
        XAmount one = XAmount.of(1, XUnit.XDAG);
        XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);
        for (int n = 0; n < blocks; n++) {
            int s = r.nextInt(senderCount);
            UInt64 nonce = nonces[s];
            nonces[s] = nonce.add(UInt64.ONE);
            ECKeyPair key = senders[s];
            long t = txTime + n;                          // strictly increasing ticks inside one epoch
            int pick = r.nextInt(100);
            Kind kind = pick < mixPercent[0] ? Kind.PLAIN
                    : pick < mixPercent[0] + mixPercent[1] ? Kind.CALL_INLINE
                    : pick < mixPercent[0] + mixPercent[1] + mixPercent[2] ? Kind.CALL_CHAIN : Kind.DEPLOY;
            switch (kind) {
                case PLAIN -> {
                    Address from = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()), XDAG_FIELD_INPUT, true);
                    Address to = new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, true);
                    Block b = new Block(new XdagBlock(BlockBuilder.generateNewTransactionBlock(config, key, t, from, to, one, nonce).toBytes()));
                    items.add(new Item(kind, List.of(), b));
                }
                case CALL_INLINE -> {
                    ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, t, key, nonce, chainId, contract, 1, 100L, one, fee, payload(20, n)).value();
                    items.add(new Item(kind, b.chunks(), b.block()));
                }
                case CALL_CHAIN -> {
                    ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, t, key, nonce, chainId, contract, 1, 100L, one, fee, payload(1000, n)).value();
                    items.add(new Item(kind, b.chunks(), b.block()));
                }
                case DEPLOY -> {
                    // joins the existing chain by code hash: no 285-chunk code chain per deploy
                    ChainBlockBuilder.Built b = ChainBlockBuilder.deployIntoChain(config, t, key, nonce, chainId, one, fee, null, sharedCodeHash, payload(10, n), 1L).value();
                    items.add(new Item(kind, b.chunks(), b.block()));
                }
                default -> throw new IllegalStateException();
            }
        }
    }

    public int chunkCount() {
        return items.stream().mapToInt(i -> i.chunks().size()).sum();
    }
}
