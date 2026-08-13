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
package io.xdag.rpc.eth;

import io.xdag.rpc.error.JsonRpcException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogsBloomFilter;

/**
 * A parsed {@code eth_getLogs} filter (C5). Owns BOTH the exact {@link #matches(Log)} semantics and the
 * conservative {@link #couldMatch(LogsBloomFilter)} bloom skip test so the two can never diverge — a
 * height is skipped only when its bloom proves no log there can satisfy the same filter that
 * {@code matches} enforces.
 *
 * <p>{@code topics} is a positional list of up to four sets: an <b>empty</b> set at position {@code i}
 * is a wildcard; a non-empty set means {@code log.topics[i]} must be one of its members (JSON array =
 * alternatives). {@code addresses} empty = any address.
 */
public final class LogFilter {

    private static final int MAX_TOPIC_POSITIONS = 4;

    private final Set<Address> addresses;
    private final List<Set<Bytes32>> topics;

    private LogFilter(Set<Address> addresses, List<Set<Bytes32>> topics) {
        this.addresses = addresses;
        this.topics = topics;
    }

    /**
     * Parses the JSON filter object's {@code address} and {@code topics}. Throws
     * {@link JsonRpcException#invalidParams} when more than four topic positions are supplied; other
     * malformed values (bad hex width, a non-string where a hex string is expected) surface as
     * {@link IllegalArgumentException}/{@link ClassCastException}, which the RPC handler maps to
     * invalidParams at the dispatch boundary.
     */
    public static LogFilter parse(Map<?, ?> filter) {
        return new LogFilter(parseAddresses(filter.get("address")), parseTopics(filter.get("topics")));
    }

    private static Set<Address> parseAddresses(Object address) {
        Set<Address> set = new HashSet<>();
        if (address == null || "".equals(address)) {
            return set;
        }
        if (address instanceof List<?> list) {
            for (Object a : list) {
                set.add(EthHex.decodeAddress((String) a));
            }
        } else {
            set.add(EthHex.decodeAddress((String) address));
        }
        return set;
    }

    private static List<Set<Bytes32>> parseTopics(Object topics) {
        List<Set<Bytes32>> out = new ArrayList<>();
        if (!(topics instanceof List<?> list) || list.isEmpty()) {
            return out;
        }
        if (list.size() > MAX_TOPIC_POSITIONS) {
            throw JsonRpcException.invalidParams("eth_getLogs accepts at most " + MAX_TOPIC_POSITIONS
                    + " topic positions");
        }
        for (Object position : list) {
            out.add(parseTopicPosition(position));
        }
        return out;
    }

    /** One positional entry -> allowed set. null or a null-containing array -> empty set (wildcard). */
    private static Set<Bytes32> parseTopicPosition(Object position) {
        Set<Bytes32> allowed = new LinkedHashSet<>();
        if (position == null) {
            return allowed;
        }
        if (position instanceof List<?> alternatives) {
            for (Object alt : alternatives) {
                if (alt == null) {
                    return new LinkedHashSet<>(); // a null alternative widens the whole position to a wildcard
                }
                allowed.add(topic((String) alt));
            }
            return allowed;
        }
        allowed.add(topic((String) position));
        return allowed;
    }

    private static Bytes32 topic(String hex) {
        return Bytes32.wrap(EthHex.decodeData(hex));
    }

    /** Exact Ethereum match: address in the set (or any) AND every constrained topic position satisfied. */
    public boolean matches(Log log) {
        if (!addresses.isEmpty() && !addresses.contains(log.getLogger())) {
            return false;
        }
        for (int i = 0; i < topics.size(); i++) {
            Set<Bytes32> want = topics.get(i);
            if (want.isEmpty()) {
                continue;
            }
            if (i >= log.getTopics().size()) {
                return false;
            }
            if (!want.contains(Bytes32.wrap(log.getTopics().get(i).getBytes()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Conservative skip test: returns true (do NOT skip) unless the height bloom proves no matching log
     * can exist there. Skippable iff some constraint (the address set, or a constrained topic position)
     * has NONE of its terms present in the bloom. Never a false negative — a genuinely matching log puts
     * its address and every constrained topic into the bloom, so every check passes.
     */
    public boolean couldMatch(LogsBloomFilter heightBloom) {
        if (!addresses.isEmpty() && addresses.stream().noneMatch(a -> contains(heightBloom, a.getBytes()))) {
            return false;
        }
        for (Set<Bytes32> want : topics) {
            if (!want.isEmpty() && want.stream().noneMatch(t -> contains(heightBloom, t))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if {@code item}'s single-item bloom is a subset of {@code haystack}. Reuses Besu's own
     * {@code insertBytes} (exactly what {@link LogsBloomFilter.Builder#insertLog} calls per address and
     * topic when the aggregate height bloom is built) and its own {@link LogsBloomFilter#couldContain}
     * byte-subset test, so the two blooms are derived and compared with identical bit math — no
     * hand-rolled keccak/index derivation that could drift out of parity and silently drop logs.
     */
    private static boolean contains(LogsBloomFilter haystack, Bytes item) {
        LogsBloomFilter needle = LogsBloomFilter.builder().insertBytes(item).build();
        return haystack.couldContain(needle);
    }
}
