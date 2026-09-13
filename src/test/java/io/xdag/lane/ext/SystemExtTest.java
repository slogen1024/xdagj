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

package io.xdag.lane.ext;

import static io.xdag.lane.ext.ChunkExtTest.hashLow;
import static io.xdag.lane.ext.ChunkExtTest.link;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class SystemExtTest {

    private static final Bytes LANE = Bytes.random(20);

    @Test
    public void anchorRoundTripAndLinkCount() {
        Bytes32 prev = hashLow(1);
        Bytes32 main = hashLow(2);
        Bytes32 commit = hashLow(3);
        AnchorExt a = new AnchorExt(LANE, 120L, Bytes32.random(), Bytes32.random(), 100L, 42L, 2L, prev, main, commit);
        ExtResult<AnchorExt> r = AnchorExt.decode(a.encodeHeader(), a.encodePayload(), List.of(link(prev), link(main), link(commit)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(a, r.value());
        assertEquals(3, a.encodePayload().size());
        assertEquals(ExtError.MISSING_LINK, AnchorExt.decode(a.encodeHeader(), a.encodePayload(), List.of(link(prev), link(main))).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, AnchorExt.decode(a.encodeHeader(), a.encodePayload().subList(0, 2), List.of(link(prev), link(main), link(commit))).error());
        byte[] h = a.encodeHeader().toArray();
        h[1] = 1; // v1 forbids any anchor flag
        assertEquals(ExtError.RESERVED_NONZERO, AnchorExt.decode(Bytes32.wrap(h), a.encodePayload(), List.of(link(prev), link(main), link(commit))).error());
    }

    @Test
    public void bondRoundTrip() {
        BondExt bond = new BondExt(false, LANE, 0L);
        assertEquals(bond, BondExt.decode(bond.encodeHeader(), List.of(), List.of()).value());
        BondExt unbond = new BondExt(true, Bytes.wrap(new byte[20]), 5_000_000_000L);
        assertEquals(unbond, BondExt.decode(unbond.encodeHeader(), List.of(), List.of()).value());
        assertEquals(ExtError.EXTRA_LINK, BondExt.decode(bond.encodeHeader(), List.of(), List.of(link(hashLow(1)))).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, BondExt.decode(bond.encodeHeader(), List.of(Bytes32.ZERO), List.of()).error());
    }

    @Test
    public void challengeRoundTrip() {
        Bytes32 anchor = hashLow(4);
        Bytes32 witness = hashLow(5);
        ChallengeExt c = new ChallengeExt(17L, 123_456_789L, anchor, witness);
        ExtResult<ChallengeExt> r = ChallengeExt.decode(c.encodeHeader(), List.of(), List.of(link(anchor), link(witness)));
        assertTrue(r.isOk());
        assertEquals(c, r.value());
        assertEquals(ExtError.MISSING_LINK, ChallengeExt.decode(c.encodeHeader(), List.of(), List.of(link(anchor))).error());
    }

    @Test
    public void claimRoundTrip() {
        Bytes32 anchor = hashLow(6);
        Bytes32 proof = hashLow(7);
        Bytes recipient = Bytes.random(20);
        ClaimExt c = new ClaimExt(true, LANE, 99L, 3L, 1_000_000_000L, recipient, anchor, proof);
        ExtResult<ClaimExt> r = ClaimExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(anchor), link(proof)));
        assertTrue(String.valueOf(r.error()), r.isOk());
        assertEquals(c, r.value());
        assertEquals(2, c.encodePayload().size());
        assertEquals(ExtError.EXTRA_LINK, ClaimExt.decode(c.encodeHeader(), c.encodePayload(), List.of(link(anchor), link(proof), link(hashLow(8)))).error());
    }

    @Test
    public void layoutsArePinned() {
        AnchorExt a = new AnchorExt(Bytes.repeat((byte) 0x11, 20), 0x0102030405060708L, Bytes32.ZERO, Bytes32.ZERO,
                0x0A0B0C0D0E0F1011L, 0x0102L, 3L, hashLow(1), hashLow(2), hashLow(3));
        assertEquals("0x0400" + "11".repeat(20) + "0807060504030201" + "0000", a.encodeHeader().toHexString());
        assertEquals("0x11100f0e0d0c0b0a" + "02010000" + "03000000" + "00".repeat(16),
                a.encodePayload().get(2).toHexString());

        BondExt bond = new BondExt(true, Bytes.repeat((byte) 0x22, 20), 0x0102030405060708L);
        assertEquals("0x0501" + "22".repeat(20) + "0807060504030201" + "0000", bond.encodeHeader().toHexString());

        ChallengeExt c = new ChallengeExt(0x01020304L, 0x0A0B0C0D0E0F1011L, hashLow(4), hashLow(5));
        assertEquals("0x0600" + "04030201" + "11100f0e0d0c0b0a" + "00".repeat(18), c.encodeHeader().toHexString());

        ClaimExt claim = new ClaimExt(true, Bytes.repeat((byte) 0x33, 20), 0x0102030405060708L, 0x0A0B0C0D0E0F1011L,
                0x1112131415161718L, Bytes.repeat((byte) 0x44, 20), hashLow(6), hashLow(7));
        assertEquals("0x0701" + "33".repeat(20) + "0807060504030201" + "0000", claim.encodeHeader().toHexString());
        assertEquals("0x11100f0e0d0c0b0a" + "1817161514131211" + "00".repeat(16),
                claim.encodePayload().get(0).toHexString());
        assertEquals("0x" + "44".repeat(20) + "00".repeat(12), claim.encodePayload().get(1).toHexString());
    }

    @Test
    public void constructorsRejectInconsistentRecords() {
        Bytes32 prev = hashLow(1);
        Bytes32 main = hashLow(2);
        Bytes32 commit = hashLow(3);

        // laneId / srcLane must be exactly 20 bytes.
        assertThrows(IllegalArgumentException.class, () -> new AnchorExt(Bytes.random(19), 1L, Bytes32.ZERO,
                Bytes32.ZERO, 1L, 0L, 0L, prev, main, commit));
        assertThrows(IllegalArgumentException.class, () -> new BondExt(false, Bytes.random(19), 1L));
        assertThrows(IllegalArgumentException.class, () -> new ClaimExt(false, Bytes.random(19), 1L, 1L, 1L,
                Bytes.random(20), prev, main));

        // recipient must be exactly 20 bytes.
        assertThrows(IllegalArgumentException.class,
                () -> new ClaimExt(false, LANE, 1L, 1L, 1L, Bytes.random(19), prev, main));

        // inputCount (Anchor) and inputIndex (Challenge) must fit a u32.
        assertThrows(IllegalArgumentException.class, () -> new AnchorExt(LANE, 1L, Bytes32.ZERO, Bytes32.ZERO, 1L,
                0x1_0000_0000L, 0L, prev, main, commit));
        assertThrows(IllegalArgumentException.class, () -> new ChallengeExt(0x1_0000_0000L, 1L, prev, main));

        // Non-null components.
        assertThrows(NullPointerException.class,
                () -> new AnchorExt(LANE, 1L, null, Bytes32.ZERO, 1L, 0L, 0L, prev, main, commit));
        assertThrows(NullPointerException.class, () -> new ChallengeExt(1L, 1L, null, main));
        assertThrows(NullPointerException.class,
                () -> new ClaimExt(false, LANE, 1L, 1L, 1L, Bytes.random(20), null, main));
    }

    @Test
    public void decodeNeverThrowsOnNullShapes() {
        Bytes32 prev = hashLow(1);
        Bytes32 main = hashLow(2);
        Bytes32 commit = hashLow(3);

        // ANCHOR
        AnchorExt a = new AnchorExt(LANE, 1L, Bytes32.ZERO, Bytes32.ZERO, 1L, 0L, 0L, prev, main, commit);
        List<Address> aLinks = List.of(link(prev), link(main), link(commit));
        assertEquals(ExtError.NO_EXT, AnchorExt.decode(null, a.encodePayload(), aLinks).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, AnchorExt.decode(a.encodeHeader(), null, aLinks).error());
        assertEquals(ExtError.MISSING_LINK, AnchorExt.decode(a.encodeHeader(), a.encodePayload(), null).error());
        assertEquals(ExtError.MISSING_LINK, AnchorExt.decode(a.encodeHeader(), a.encodePayload(),
                Arrays.asList(link(prev), link(main), null)).error());
        List<Bytes32> dirtyAnchorPayload = new ArrayList<>(a.encodePayload());
        dirtyAnchorPayload.set(0, null);
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                AnchorExt.decode(a.encodeHeader(), dirtyAnchorPayload, aLinks).error());

        // BOND
        BondExt bond = new BondExt(false, LANE, 1L);
        assertEquals(ExtError.NO_EXT, BondExt.decode(null, List.of(), List.of()).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, BondExt.decode(bond.encodeHeader(), null, List.of()).error());
        assertTrue(BondExt.decode(bond.encodeHeader(), List.of(), null).isOk());
        assertEquals(ExtError.MISSING_LINK,
                BondExt.decode(bond.encodeHeader(), List.of(), Arrays.asList((Address) null)).error());

        // CHALLENGE
        ChallengeExt c = new ChallengeExt(1L, 1L, prev, main);
        List<Address> cLinks = List.of(link(prev), link(main));
        assertEquals(ExtError.NO_EXT, ChallengeExt.decode(null, List.of(), cLinks).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, ChallengeExt.decode(c.encodeHeader(), null, cLinks).error());
        assertEquals(ExtError.MISSING_LINK, ChallengeExt.decode(c.encodeHeader(), List.of(), null).error());
        assertEquals(ExtError.MISSING_LINK,
                ChallengeExt.decode(c.encodeHeader(), List.of(), Arrays.asList(link(prev), null)).error());

        // CLAIM
        Bytes recipient = Bytes.random(20);
        ClaimExt claim = new ClaimExt(false, LANE, 1L, 1L, 1L, recipient, prev, main);
        List<Address> claimLinks = List.of(link(prev), link(main));
        assertEquals(ExtError.NO_EXT, ClaimExt.decode(null, claim.encodePayload(), claimLinks).error());
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH, ClaimExt.decode(claim.encodeHeader(), null, claimLinks).error());
        assertEquals(ExtError.MISSING_LINK, ClaimExt.decode(claim.encodeHeader(), claim.encodePayload(), null).error());
        assertEquals(ExtError.MISSING_LINK, ClaimExt.decode(claim.encodeHeader(), claim.encodePayload(),
                Arrays.asList(link(prev), null)).error());
        List<Bytes32> dirtyClaimPayload = new ArrayList<>(claim.encodePayload());
        dirtyClaimPayload.set(1, null);
        assertEquals(ExtError.PAYLOAD_COUNT_MISMATCH,
                ClaimExt.decode(claim.encodeHeader(), dirtyClaimPayload, claimLinks).error());
    }

    @Test
    public void u64FieldsKeepBitPattern() {
        Bytes32 prev = hashLow(1);
        Bytes32 main = hashLow(2);
        Bytes32 commit = hashLow(3);

        AnchorExt a = new AnchorExt(LANE, -1L, Bytes32.ZERO, Bytes32.ZERO, Long.MIN_VALUE, 0L, 0L, prev, main, commit);
        ExtResult<AnchorExt> ar = AnchorExt.decode(a.encodeHeader(), a.encodePayload(),
                List.of(link(prev), link(main), link(commit)));
        assertTrue(String.valueOf(ar.error()), ar.isOk());
        assertEquals(a, ar.value());

        BondExt bond = new BondExt(false, LANE, -1L);
        ExtResult<BondExt> br = BondExt.decode(bond.encodeHeader(), List.of(), List.of());
        assertTrue(br.isOk());
        assertEquals(bond, br.value());

        Bytes recipient = Bytes.random(20);
        ClaimExt claim = new ClaimExt(false, LANE, 1L, -1L, Long.MIN_VALUE, recipient, prev, main);
        ExtResult<ClaimExt> cr = ClaimExt.decode(claim.encodeHeader(), claim.encodePayload(),
                List.of(link(prev), link(main)));
        assertTrue(String.valueOf(cr.error()), cr.isOk());
        assertEquals(claim, cr.value());
    }
}
