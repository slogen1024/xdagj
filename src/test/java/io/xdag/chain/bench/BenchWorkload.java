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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.xdag.BlockBuilder;
import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.config.Config;
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
import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p>The sender keys are derived from the seed as well (sha256, like
 * {@code ChainL1ReorgPropertyTest.senderKey}), so with the same seed, sender count, block count
 * and mix two runs produce byte-identical blocks (RFC 6979 signatures).
 *
 * <p>Every block handed out — chunks and paying blocks alike — has been round-tripped through its
 * own 512 bytes ({@code new Block(new XdagBlock(bytes))}), the form a block has when it arrives
 * from the network: parsed, with its raw {@code XdagBlock} attached. A builder {@code Block} would
 * instead carry {@code xdagBlock == null} and encode itself lazily inside the timed import.
 */
public final class BenchWorkload {

    public enum Kind { PLAIN, CALL_INLINE, CALL_CHAIN, DEPLOY }

    public record Item(Kind kind, List<Block> chunks, Block block) {
    }

    private final List<Item> items = new ArrayList<>();
    private final List<ECKeyPair> senders = new ArrayList<>();
    private final int[] kindCounts = new int[Kind.values().length];

    public BenchWorkload(Config config, long seed, int senderCount, int blocks, int[] mixPercent,
                         long txTime, Bytes chainId, Bytes contract, Bytes32 sharedCodeHash) {
        Random r = new Random(seed);
        UInt64[] nonces = new UInt64[senderCount];
        for (int i = 0; i < senderCount; i++) {
            senders.add(senderKey(seed, i));
            nonces[i] = UInt64.ONE;
        }
        XAmount one = XAmount.of(1, XUnit.XDAG);
        XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);
        for (int n = 0; n < blocks; n++) {
            int s = r.nextInt(senderCount);
            UInt64 nonce = nonces[s];
            nonces[s] = nonce.add(UInt64.ONE);
            ECKeyPair key = senders.get(s);
            long t = txTime + n;                          // strictly increasing ticks inside one epoch
            int pick = r.nextInt(100);
            Kind kind = pick < mixPercent[0] ? Kind.PLAIN
                    : pick < mixPercent[0] + mixPercent[1] ? Kind.CALL_INLINE
                    : pick < mixPercent[0] + mixPercent[1] + mixPercent[2] ? Kind.CALL_CHAIN : Kind.DEPLOY;
            final int itemNo = n;
            Item item = switch (kind) {
                case PLAIN -> {
                    Address from = new Address(BytesUtils.arrayToByte32(key.toAddress().toArray()), XDAG_FIELD_INPUT, true);
                    Address to = new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, true);
                    yield item(kind, List.of(), BlockBuilder.generateNewTransactionBlock(config, key, t, from, to, one, nonce));
                }
                case CALL_INLINE -> {
                    ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, t, key, nonce, chainId, contract, 1, 100L, one, fee, payload(20, itemNo)).value();
                    yield item(kind, b.chunks(), b.block());
                }
                case CALL_CHAIN -> {
                    ChainBlockBuilder.Built b = ChainBlockBuilder.call(config, t, key, nonce, chainId, contract, 1, 100L, one, fee, payload(1000, itemNo)).value();
                    yield item(kind, b.chunks(), b.block());
                }
                case DEPLOY -> {
                    // Joins the existing chain by code hash: no 285-chunk code chain per deploy. With
                    // wasm == null and 10-byte args (inline), this carries 0 chunks and is shape-identical
                    // to CALL_INLINE at import time.
                    ChainBlockBuilder.Built b = ChainBlockBuilder.deployIntoChain(config, t, key, nonce, chainId, one, fee, null, sharedCodeHash, payload(10, itemNo), 1L).value();
                    yield item(kind, b.chunks(), b.block());
                }
            };
            items.add(item);
            kindCounts[kind.ordinal()]++;
        }
    }

    /** Import groups in import order; read-only. */
    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    /** Seed-derived sender keys, index order; read-only. */
    public List<ECKeyPair> senders() {
        return Collections.unmodifiableList(senders);
    }

    public int chunkCount() {
        return items.stream().mapToInt(i -> i.chunks().size()).sum();
    }

    public int kindCount(Kind kind) {
        return kindCounts[kind.ordinal()];
    }

    /** Seeded pseudo-random bytes (the same generator as {@code ChunkChainTest.payload}, kept local). */
    public static Bytes payload(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return Bytes.wrap(b);
    }

    /**
     * Deterministic sender key: sha256("bench-sender" | seed | i). A scalar at or above the curve
     * order (probability ~2^-128) makes fromBytes throw; it is re-hashed, still deterministically.
     */
    static ECKeyPair senderKey(long seed, int i) {
        Bytes32 scalar = HashUtils.sha256(Bytes.concatenate(
                Bytes.wrap("bench-sender".getBytes(UTF_8)), Bytes.ofUnsignedLong(seed), Bytes.ofUnsignedInt(i)));
        for (;;) {
            try {
                return ECKeyPair.fromPrivateKey(PrivateKey.fromBytes(scalar));
            } catch (CryptoException e) {
                scalar = HashUtils.sha256(scalar);
            }
        }
    }

    /** Every block of the item round-tripped through its raw bytes, as a block arriving from a peer is. */
    private static Item item(Kind kind, List<Block> chunks, Block block) {
        List<Block> arrived = new ArrayList<>(chunks.size());
        for (Block c : chunks) {
            arrived.add(arrived(c));
        }
        return new Item(kind, List.copyOf(arrived), arrived(block));
    }

    /**
     * A fresh {@code Block} parsed from {@code b}'s raw 512 bytes. A builder block encodes itself
     * once here ({@code getXdagBlock()}), a block that already carries its bytes is copied verbatim.
     * Never {@code b.toBytes()}: re-encoding an already-parsed block writes the fields in
     * {@code getEncodedBody}'s canonical order, which need not be the original layout, and the hash
     * — which the referencing block has already embedded — would change.
     */
    private static Block arrived(Block b) {
        return new Block(new XdagBlock(b.getXdagBlock().getData().toArray()));
    }
}
