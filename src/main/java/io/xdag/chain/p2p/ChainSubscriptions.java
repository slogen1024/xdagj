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
package io.xdag.chain.p2p;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Which chains this node executes. {@code subscribeAll} is the v1 transition (roadmap D6): the
 * node runs every chain, and the announcement carries no list because the set is not enumerable.
 * An explicit list is a routing hint, not a consensus input.
 *
 * <p>Wire ({@code 0x1B}): {@code flag u8 ‖ count u32 ‖ chainId<sup>count</sup>}, each chain id 32
 * bytes, strictly increasing. {@code flag} is 1 only together with {@code count} 0.
 */
public final class ChainSubscriptions {

    private static final ChainSubscriptions NONE = new ChainSubscriptions(false, List.of());

    private final boolean subscribeAll;
    private final List<Bytes32> chainIds;

    private ChainSubscriptions(boolean subscribeAll, List<Bytes32> chainIds) {
        this.subscribeAll = subscribeAll;
        this.chainIds = chainIds;
    }

    public static ChainSubscriptions none() {
        return NONE;
    }

    public static ChainSubscriptions all() {
        return new ChainSubscriptions(true, List.of());
    }

    public static ChainSubscriptions of(List<Bytes32> chainIds) {
        return new ChainSubscriptions(false, canonical(chainIds));
    }

    /**
     * @param subscribeAll the resolved flag (network default already applied)
     * @param hexIds {@code chain.subscribe}; empty when the key is absent. 64 hex chars, optional {@code 0x}
     */
    public static ChainSubscriptions parse(boolean subscribeAll, List<String> hexIds) {
        List<String> ids = hexIds == null ? List.of() : hexIds;
        if (subscribeAll && !ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "chain.subscribe must be empty when chain.subscribeAll is true");
        }
        if (ids.size() > ChainCodec.MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException(
                    "chain.subscribe has " + ids.size() + " entries (max " + ChainCodec.MAX_SUBSCRIPTIONS + ")");
        }
        List<Bytes32> parsed = new ArrayList<>(ids.size());
        for (String hex : ids) {
            parsed.add(parseId(hex));
        }
        return subscribeAll ? all() : of(parsed);
    }

    public boolean subscribeAll() {
        return subscribeAll;
    }

    public List<Bytes32> chainIds() {
        return chainIds;
    }

    public boolean covers(Bytes32 chainId) {
        return subscribeAll || Collections.binarySearch(chainIds, chainId) >= 0;
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(1 + Integer.BYTES + chainIds.size() * ChainCodec.CHAIN_ID_BYTES);
        buf.put(subscribeAll ? (byte) 1 : (byte) 0);
        buf.putInt(chainIds.size());
        for (Bytes32 id : chainIds) {
            ChainCodec.chainId(buf, id);
        }
        return buf.array();
    }

    public static ChainSubscriptions decode(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        if (buf.remaining() < 1 + Integer.BYTES) {
            throw ChainCodec.malformed("subscriptions truncated");
        }
        int flag = buf.get() & 0xff;
        if (flag > 1) {
            throw ChainCodec.malformed("subscriptions flag");
        }
        int count = buf.getInt();
        if (count < 0 || count > ChainCodec.MAX_SUBSCRIPTIONS) {
            throw ChainCodec.malformed("subscriptions count");
        }
        if (flag == 1 && count != 0) {
            throw ChainCodec.malformed("subscribeAll carries a list");
        }
        if (buf.remaining() != count * ChainCodec.CHAIN_ID_BYTES) {
            throw ChainCodec.malformed("subscriptions length");
        }
        List<Bytes32> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Bytes32 id = ChainCodec.chainId(buf);
            if (!ids.isEmpty() && ids.get(ids.size() - 1).compareTo(id) >= 0) {
                throw ChainCodec.malformed("subscriptions not strictly increasing");
            }
            ids.add(id);
        }
        return new ChainSubscriptions(flag == 1, List.copyOf(ids));
    }

    private static List<Bytes32> canonical(List<Bytes32> chainIds) {
        List<Bytes32> sorted = new ArrayList<>(chainIds);
        sorted.sort(Bytes32::compareTo);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw new IllegalArgumentException("chain.subscribe contains a duplicate");
            }
        }
        if (sorted.size() > ChainCodec.MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException(
                    "chain.subscribe has " + sorted.size() + " entries (max " + ChainCodec.MAX_SUBSCRIPTIONS + ")");
        }
        return List.copyOf(sorted);
    }

    private static Bytes32 parseId(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("chain.subscribe entry is empty");
        }
        String raw = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (raw.length() != 64) {
            throw new IllegalArgumentException("chain.subscribe entry is not 32 bytes: " + hex);
        }
        try {
            return Bytes32.fromHexString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("chain.subscribe entry is not hex: " + hex, e);
        }
    }
}
