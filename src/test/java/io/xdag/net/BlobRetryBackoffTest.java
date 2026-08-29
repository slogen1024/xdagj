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
package io.xdag.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class BlobRetryBackoffTest {

    private static Bytes32 ref(String h) {
        return Bytes32.fromHexString("0x" + h.repeat(32));
    }

    private static Set<Bytes32> set(Bytes32... refs) {
        return new LinkedHashSet<>(java.util.Arrays.asList(refs));
    }

    @Test
    public void a_fresh_ref_is_due_immediately_then_backs_off_exponentially() {
        BlobRetryBackoff b = new BlobRetryBackoff();
        Bytes32 r = ref("aa");
        // Requested at ticks 0, 1, 3, 7, 15 (intervals 1,2,4,8), then capped at 16: 31, 47, ...
        assertTrue("due on first sight", b.selectDue(set(r), 0).contains(r));
        assertFalse("not due next tick (interval 1 -> eligible at 1)", b.selectDue(set(r), 0).contains(r));
        assertTrue(b.selectDue(set(r), 1).contains(r));   // interval 2 -> next 3
        assertFalse(b.selectDue(set(r), 2).contains(r));
        assertTrue(b.selectDue(set(r), 3).contains(r));   // interval 4 -> next 7
        assertTrue(b.selectDue(set(r), 7).contains(r));   // interval 8 -> next 15
        assertTrue(b.selectDue(set(r), 15).contains(r));  // interval 16 (cap) -> next 31
        assertFalse(b.selectDue(set(r), 30).contains(r));
        assertTrue("capped interval, never gives up", b.selectDue(set(r), 31).contains(r)); // next 47
        assertTrue("still retrying at the max interval", b.selectDue(set(r), 47).contains(r));
    }

    @Test
    public void a_ref_that_stops_being_missing_is_pruned_and_fresh_again_on_return() {
        BlobRetryBackoff b = new BlobRetryBackoff();
        Bytes32 r = ref("bb");
        assertTrue(b.selectDue(set(r), 0).contains(r)); // attempt 0 -> next eligible 1
        // r arrives -> no longer missing -> pruned.
        assertTrue(b.selectDue(set(), 1).isEmpty());
        // r referenced again by a new height -> fresh -> due immediately (state was pruned).
        assertTrue("pruned ref is fresh on return", b.selectDue(set(r), 2).contains(r));
    }

    @Test
    public void independent_refs_back_off_independently() {
        BlobRetryBackoff b = new BlobRetryBackoff();
        Bytes32 a = ref("a1");
        Bytes32 c = ref("c1");
        assertEquals(set(a), b.selectDue(set(a), 0));           // only a seen so far
        assertEquals("c is fresh, a is backing off", set(c), b.selectDue(set(a, c), 0)); // a not due at 0
    }
}
