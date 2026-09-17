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

package io.xdag.chain.l1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import io.xdag.Kernel;
import io.xdag.chain.ext.BondExt;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * The SP2/SP3 registration seam: a {@link ChainKindHandler} put into
 * {@code kernel.getChainKindHandlers()} before {@code new BlockchainImpl(kernel)} is dispatched for
 * a confirmed block of its kind. This is the only registration point there is — the constructor
 * drains the map before starting the check-main loop, and the processor refuses a handler offered
 * after its first hook has run.
 */
public class ChainKindHandlerRegistrationTest extends ChainL1TestBase {

    /** The 20-byte chain id the BOND below stakes to; fixed, so the block's hash is reproducible. */
    private static final Bytes BOND_CHAIN = Bytes.repeat((byte) 0x11, 20);

    private final RecordingHandler handler = new RecordingHandler();

    /** One dispatched onApplied: the block it was handed, and the height its context carried. */
    private record Applied(Bytes32 block, long height) {
    }

    private static final class RecordingHandler implements ChainKindHandler {

        private final List<Applied> applied = new ArrayList<>();
        private final List<Bytes32> unapplied = new ArrayList<>();

        @Override
        public void onApplied(Block block, Classified classified, ApplyContext ctx, ChainL1Batch batch) {
            assertSame("handler dispatched for the wrong kind", ExtKind.BOND, classified.kind());
            assertNotNull("onApplied always runs inside an activated setMain", ctx);
            applied.add(new Applied(block.getHash(), ctx.height()));
        }

        @Override
        public void onUnapplied(Block block, Classified classified, ChainL1Batch batch) {
            unapplied.add(block.getHash());
        }
    }

    @Override
    protected void beforeBlockchain(Kernel kernel) {
        kernel.getChainKindHandlers().put(ExtKind.BOND, handler);
    }

    @Test
    public void aHandlerRegisteredOnTheKernelIsDispatchedForItsKind() {
        // Warm up the chain weight first, so a single EXT block cannot take the top (plan §0.5).
        for (int i = 0; i < 10; i++) {
            mineMain(List.of());
        }

        // A BOND block carries no links and no payload fields, so it imports and applies exactly
        // the way a chunk-chain tail does: applyBlock's link-less branch sets BI_APPLIED and calls
        // the hook.
        Block bond = new Block(new XdagBlock(new Block(config, txTime(), null, null, false, null, null, -1,
                XAmount.ZERO, null, List.of(new BondExt(false, BOND_CHAIN, 0L).encodeHeader())).toBytes()));
        assertImported(bond);

        Block main = mineMain(List.of(hashLow(bond)));
        confirm(bond);

        assertEquals("the kernel-registered handler must have been dispatched exactly once",
                1, handler.applied.size());
        assertEquals(bond.getHash(), handler.applied.get(0).block());
        assertEquals(heightOf(main), handler.applied.get(0).height());
        assertEquals("nothing was unwound", List.of(), handler.unapplied);
    }
}
