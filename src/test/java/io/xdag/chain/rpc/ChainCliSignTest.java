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
package io.xdag.chain.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ExtKind;
import io.xdag.config.DevnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.core.Block;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;

public class ChainCliSignTest {

    private static final long TS = 0x16a00000000L;

    @Test
    public void callAndBondAreSignedAndSubmitted() {
        List<Block> sent = new ArrayList<>();
        ChainCli.Signer signer = new ChainCli.Signer(new DevnetConfig(),
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), 1L, TS, sent::addAll);
        Bytes chain = Bytes.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
        Bytes contract = Bytes.wrap(new byte[] {20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1});
        String hash = ChainCli.run(null, signer, "call", chain.toHexString(), contract.toHexString(), "7", "100", "0",
                "0102");
        assertEquals(1, sent.size());
        assertEquals(sent.get(0).getHash().toHexString(), hash);
        assertEquals(ExtKind.CALL, ChainBlockClassifier.classify(sent.get(0)).kind());
        assertEquals("key", ChainCli.run(null, "bond", chain.toHexString(), "1000"));

        sent.clear();
        String bond = ChainCli.run(null, signer, "bond", chain.toHexString(), "1000");
        assertEquals(sent.get(0).getHash().toHexString(), bond);
        assertEquals(ExtKind.BOND, ChainBlockClassifier.classify(sent.get(0)).kind());
        assertTrue(ChainCli.run(null, signer, "create", Bytes.wrap(new byte[32]).toHexString(), "1", "0", "1000000",
                "1000").startsWith("0x"));
    }

    @Test
    public void activationSaysWhetherAHeightHasBeenScheduled() {
        ChainCli.Signer devnet = new ChainCli.Signer(new DevnetConfig(),
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), 1L, TS, blocks -> { });
        assertEquals("scheduled 0", ChainCli.run(null, devnet, "activation"));
        ChainCli.Signer testnet = new ChainCli.Signer(new TestnetConfig(),
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), 1L, TS, blocks -> { });
        assertEquals("unscheduled " + Long.MAX_VALUE, ChainCli.run(null, testnet, "activation"));
        assertEquals("chain", ChainCli.run(null, "activation"));
    }
}
