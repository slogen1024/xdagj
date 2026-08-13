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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.rpc.error.JsonRpcException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.datatypes.LogsBloomFilter;
import org.junit.Test;

public class LogFilterTest {

    private static final Address ADDR = Address.fromHexString("0x00000000000000000000000000000000000000aa");
    private static final Address OTHER = Address.fromHexString("0x00000000000000000000000000000000000000bb");
    private static final Bytes32 T0 = Bytes32.fromHexString("0x" + "11".repeat(32));
    private static final Bytes32 T1 = Bytes32.fromHexString("0x" + "22".repeat(32));
    private static final Bytes32 TX = Bytes32.fromHexString("0x" + "99".repeat(32));

    private static Log log(Address logger, Bytes32... topics) {
        LogTopic[] lt = Arrays.stream(topics).map(LogTopic::of).toArray(LogTopic[]::new);
        return new Log(logger, Bytes.EMPTY, List.of(lt));
    }

    private static LogsBloomFilter bloomOf(Log... logs) {
        LogsBloomFilter.Builder b = LogsBloomFilter.builder();
        for (Log l : logs) {
            b.insertLog(l);
        }
        return b.build();
    }

    @Test
    public void matches_topic0_only() {
        LogFilter f = LogFilter.parse(Map.of("topics", List.of(T0.toHexString())));
        assertTrue(f.matches(log(ADDR, T0)));
        assertFalse(f.matches(log(ADDR, T1)));
    }

    @Test
    public void matches_indexed_topic1_with_wildcard_topic0() {
        LogFilter f = LogFilter.parse(Map.of("topics", Arrays.asList(null, T1.toHexString())));
        assertTrue(f.matches(log(ADDR, T0, T1)));
        assertFalse(f.matches(log(ADDR, T0, T0)));
        assertFalse("log with no topic1 cannot match a constrained position 1", f.matches(log(ADDR, T0)));
    }

    @Test
    public void matches_per_position_alternatives() {
        LogFilter f = LogFilter.parse(Map.of("topics",
                List.of(List.of(T0.toHexString(), T1.toHexString()))));
        assertTrue(f.matches(log(ADDR, T0)));
        assertTrue(f.matches(log(ADDR, T1)));
        assertFalse(f.matches(log(ADDR, TX)));
    }

    @Test
    public void matches_address_set_and_empty_filter() {
        assertTrue("empty filter matches all", LogFilter.parse(Map.of()).matches(log(ADDR, T0)));
        LogFilter f = LogFilter.parse(Map.of("address", ADDR.toHexString()));
        assertTrue(f.matches(log(ADDR, T0)));
        assertFalse(f.matches(log(OTHER, T0)));
    }

    @Test
    public void rejects_more_than_four_topic_positions() {
        assertThrows(JsonRpcException.class, () -> LogFilter.parse(Map.of("topics",
                List.of(T0.toHexString(), T0.toHexString(), T0.toHexString(),
                        T0.toHexString(), T0.toHexString()))));
    }

    @Test
    public void could_match_is_conservative() {
        LogFilter f = LogFilter.parse(Map.of("address", ADDR.toHexString(),
                "topics", List.of(T0.toHexString())));
        assertTrue(f.couldMatch(bloomOf(log(ADDR, T0))));
        assertFalse("neither ADDR nor T0 present -> skip", f.couldMatch(bloomOf(log(OTHER, TX))));
        assertFalse(f.couldMatch(bloomOf(log(ADDR, TX))));
        assertTrue(LogFilter.parse(Map.of()).couldMatch(bloomOf(log(OTHER, TX))));
    }

    @Test
    public void could_match_alternatives_need_only_one_present() {
        LogFilter f = LogFilter.parse(Map.of("topics",
                List.of(List.of(T0.toHexString(), T1.toHexString()))));
        assertTrue(f.couldMatch(bloomOf(log(ADDR, T1))));
        assertFalse(f.couldMatch(bloomOf(log(ADDR, TX))));
    }

    @Test
    public void null_inside_alternatives_widens_position_to_wildcard() {
        // topics = [[T0, null]] -> a null alternative makes position 0 a wildcard: any topic0 matches,
        // and the bloom can never rule the height out on that position.
        LogFilter f = LogFilter.parse(Map.of("topics",
                List.of(Arrays.asList(T0.toHexString(), null))));
        assertTrue(f.matches(log(ADDR, T0)));
        assertTrue(f.matches(log(ADDR, TX)));
        assertTrue(f.couldMatch(bloomOf(log(ADDR, TX))));
    }
}
