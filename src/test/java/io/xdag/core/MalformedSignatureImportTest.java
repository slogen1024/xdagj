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

package io.xdag.core;

import static io.xdag.config.Constants.BI_REF;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_SIGN_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.xdag.Kernel;
import io.xdag.chain.l1.ChainL1TestBase;
import io.xdag.config.AbstractConfig;
import io.xdag.consensus.XdagPow;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * A block may not touch a single byte of state before its shape has been accepted.
 *
 * <p>The shape at issue is a block carrying a PUBLIC_KEY field but no SIGN_OUT field. {@code
 * Block.verifiedKeys()} is not total on it — it reaches {@code Signer.verify(hash, null, key)},
 * which dereferences the signature — and before SP0b-2 {@code canUseInput} computed the keys
 * unconditionally, so the NPE landed there and {@code tryToConnect}'s {@code catch (Throwable)}
 * turned it into ERROR before any state was touched. SP0b-2 moved the empty-inputs fast path ahead
 * of the key computation, which is right for cost but moved the throw all the way down to {@code
 * checkMineAndAdd} — past the link-removal loop, where {@code removeOrphan} deletes ORPHANIND rows,
 * decrements {@code nnoref} and writes BI_REF into a victim's persisted BlockInfo. A peer could
 * craft the shape and de-orphan blocks of its choosing on every node.
 */
public class MalformedSignatureImportTest extends ChainL1TestBase {

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        // dealOrphan records an ORPHANIND row only on a node that generates blocks and has a PoW
        // wired, and the pool is exactly what this test watches.
        ((AbstractConfig) config).setEnableGenerateBlock(true);
        kernel.setPow(Mockito.mock(XdagPow.class));
    }

    /**
     * The block the constructor cannot build: it always emits a SIGN_OUT pair next to the public
     * keys, so the field types are patched in the raw 512 bytes instead — which is all a peer has
     * to do. XDAG_FIELD_NONCE (0) is the type to patch to: {@code Block.parse} ignores it.
     */
    private static Block stripOutSignature(Block signed) {
        byte[] raw = signed.toBytes();
        // The 16 field types are the nibbles of the header's second long, little-endian: nibble f
        // lives in byte 8 + f/2, low half for an even f.
        for (int f = 0; f < XdagBlock.XDAG_BLOCK_FIELDS; f++) {
            int idx = 8 + f / 2;
            int shift = 4 * (f % 2);
            if (((raw[idx] >> shift) & 0xf) == XDAG_FIELD_SIGN_OUT.asByte()) {
                raw[idx] &= (byte) ~(0xf << shift);
            }
        }
        return new Block(new XdagBlock(raw));
    }

    @Test
    public void aBlockWithKeysButNoOutSignatureDeOrphansNothing() {
        long orphansBefore = kernel.getOrphanBlockStore().getOrphanSize();
        assertTrue("the victim is in the orphan pool to begin with", orphansBefore > 0);
        long nnorefBefore = blockchain.getXdagStats().nnoref;
        Block victimBefore = blockchain.getBlockByHash(topRef, false);
        assertNotNull(victimBefore);
        assertEquals("the victim starts unreferenced", 0, victimBefore.getInfo().getFlags() & BI_REF);

        // A public-key field, no out-signature, no inputs, one OUT link to the victim.
        Block signed = new Block(config, txTime(), null,
                List.of(new Address(topRef, XDAG_FIELD_OUT, false)), false, List.of(poolKey), null, 0,
                XAmount.ZERO, null);
        signed.signOut(poolKey);
        Block malformed = stripOutSignature(signed);
        assertFalse("the crafted block still carries its public key", malformed.getPubKeys().isEmpty());
        assertNull("but no out-signature at all", malformed.getOutsig());
        assertTrue("and no inputs, so canUseInput takes its fast path", malformed.getInputs().isEmpty());

        ImportResult result = blockchain.tryToConnect(malformed);

        assertEquals("the victim is still in the orphan pool", orphansBefore,
                kernel.getOrphanBlockStore().getOrphanSize());
        assertEquals("nnoref untouched", nnorefBefore, blockchain.getXdagStats().nnoref);
        Block victimAfter = blockchain.getBlockByHash(topRef, false);
        assertNotNull(victimAfter);
        assertEquals("BI_REF was never written into the victim's persisted BlockInfo", 0,
                victimAfter.getInfo().getFlags() & BI_REF);
        assertFalse("and the rejected block itself was not stored", blockchain.isExist(malformed.getHashLow()));
        assertSame("the malformed shape is rejected outright: " + result.getErrorInfo(),
                ImportResult.INVALID_BLOCK, result);
    }

    /**
     * The fast path survives the fix: an ordinary link block has no public keys at all (the
     * signature is verified against the miner's own wallet keys, not against a field), so the guard
     * is a null check on the out-signature and nothing in this import pays for an ECDSA
     * verification it does not need.
     */
    @Test
    public void anOrdinaryLinkBlockWithNoPublicKeysStillImports() {
        Block signed = new Block(config, txTime(), null,
                List.of(new Address(topRef, XDAG_FIELD_OUT, false)), false, null, null, -1, XAmount.ZERO, null);
        signed.signOut(poolKey);
        Block link = new Block(new XdagBlock(signed.toBytes()));
        assertTrue("a link block carries no public-key field", link.getPubKeys().isEmpty());
        assertNotNull("it does carry an out-signature", link.getOutsig());

        ImportResult result = blockchain.tryToConnect(link);
        assertTrue("import failed: " + result + " " + result.getErrorInfo(),
                result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST);
    }
}
