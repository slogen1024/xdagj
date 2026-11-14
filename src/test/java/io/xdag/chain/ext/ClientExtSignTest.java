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
package io.xdag.chain.ext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

import io.xdag.config.DevnetConfig;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;

/** DEPLOY, ANCHOR, CLAIM and CHALLENGE from sign-ext, byte for byte with ChainBlockBuilder. */
public class ClientExtSignTest {

    private static final long TS = 0x16a00000000L;
    private static final String FEE = "100000000";
    private static final XAmount HEADER_FEE = XAmount.of(100, XUnit.MILLI_XDAG);

    @Test
    public void deployNewInlineArgsMatch() throws Exception {
        byte[] wasm = fill(40, 3);
        byte[] init = fill(20, 4);
        compare("deploy-new", deployNew(wasm, init), priv(), ts(), "1", FEE, "1000", "7", "8", "9", "8", hex(wasm),
                hex(init));
    }

    @Test
    public void deployNewChainsArgsAndDedupesIdenticalChunks() throws Exception {
        byte[] wasm = fill(161, 5);
        compare("deploy-new", deployNew(wasm, wasm), priv(), ts(), "1", FEE, "1000", "7", "8", "9", "8", hex(wasm),
                hex(wasm));
    }

    @Test
    public void deployIntoWithCodeAndWithHashMatch() throws Exception {
        byte[] chain = address(1);
        byte[] wasm = fill(30, 6);
        byte[] init = fill(8, 7);
        compare("deploy-into",
                ChainBlockBuilder.deployIntoChain(new DevnetConfig(), TS, key(), UInt64.valueOf(1), Bytes.wrap(chain),
                        XAmount.of(250), HEADER_FEE, Bytes.wrap(wasm), null, Bytes.wrap(init), 1000L).value(),
                priv(), ts(), "1", FEE, "250", "1000", hex(chain), "-", "8", hex(wasm), hex(init));
        byte[] hash = new byte[32];
        hash[0] = 9;
        byte[] longArgs = fill(225, 2);
        compare("deploy-into",
                ChainBlockBuilder.deployIntoChain(new DevnetConfig(), TS, key(), UInt64.valueOf(1), Bytes.wrap(chain),
                        XAmount.of(250), HEADER_FEE, null, Bytes32.wrap(hash), Bytes.wrap(longArgs), 1000L).value(),
                priv(), ts(), "1", FEE, "250", "1000", hex(chain), hex(hash), "8", "-", hex(longArgs));
    }

    @Test
    public void anchorEmptyAndChainedMatch() throws Exception {
        byte[] chain = address(1);
        Bytes32 state = word(1);
        Bytes32 outbox = word(2);
        Bytes32 prev = word(3);
        Bytes32 main = word(4);
        compare("anchor", anchor(chain, state, outbox, prev, main, Bytes.EMPTY), priv(), ts(), "1", FEE, hex(chain),
                "11", hex(state), hex(outbox), "4", "5", "6", hex(prev), hex(main), "8", "-");
        byte[] commitment = fill(10, 8);
        compare("anchor", anchor(chain, state, outbox, prev, main, Bytes.wrap(commitment)), priv(), ts(), "1", FEE,
                hex(chain), "11", hex(state), hex(outbox), "4", "5", "6", hex(prev), hex(main), "8",
                hex(commitment));
    }

    @Test
    public void claimAndChallengeMatch() throws Exception {
        byte[] src = address(1);
        byte[] recipient = address(20);
        byte[] proof = fill(353, 9);
        Bytes32 anchor = word(7);
        compare("claim",
                ChainBlockBuilder.claim(new DevnetConfig(), TS, key(), UInt64.valueOf(1), HEADER_FEE, true,
                        Bytes.wrap(src), 11L, 3L, 9L, Bytes.wrap(recipient), anchor, Bytes.wrap(proof)).value(),
                priv(), ts(), "1", FEE, "1", hex(src), "11", "3", "9", hex(recipient), hex(anchor), "8", hex(proof));
        byte[] witness = fill(10, 1);
        compare("challenge",
                ChainBlockBuilder.challenge(new DevnetConfig(), TS, key(), UInt64.valueOf(1), HEADER_FEE, 6L, 50L,
                        anchor, Bytes.wrap(witness)).value(),
                priv(), ts(), "1", FEE, "6", "50", hex(anchor), "8", hex(witness));
    }

    private static ChainBlockBuilder.Built deployNew(byte[] wasm, byte[] init) {
        return ChainBlockBuilder.deployNewChain(new DevnetConfig(), TS, key(), UInt64.valueOf(1), HEADER_FEE,
                Bytes.wrap(wasm), new ChainConfigExt(7L, 8L, 9L), Bytes.wrap(init), 1000L).value();
    }

    private static ChainBlockBuilder.Built anchor(byte[] chain, Bytes32 state, Bytes32 outbox, Bytes32 prev,
            Bytes32 main, Bytes commitment) {
        return ChainBlockBuilder.anchor(new DevnetConfig(), TS, key(), UInt64.valueOf(1), HEADER_FEE, Bytes.wrap(chain),
                11L, state, outbox, 4L, 5L, 6L, prev, main, commitment).value();
    }

    private static void compare(String kind, ChainBlockBuilder.Built built, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = kind;
        System.arraycopy(args, 0, cmd, 1, args.length);
        String[] lines = cargo(cmd).split("\n");
        assertSame(lines[0], Bytes.wrap(built.block().toBytes()).toHexString().substring(2));
        if (lines.length - 1 != built.chunks().size()) {
            throw new AssertionError(kind + " chunks rust " + (lines.length - 1) + " java " + built.chunks().size());
        }
        for (int i = 0; i < built.chunks().size(); i++) {
            assertSame(lines[i + 1], built.chunks().get(i).getXdagBlock().getData().toHexString().substring(2));
        }
    }

    private static void assertSame(String rust, String java) {
        int mismatch = -1;
        int n = Math.min(rust.length(), java.length());
        for (int i = 0; i < n; i++) {
            if (rust.charAt(i) != java.charAt(i)) {
                mismatch = i;
                break;
            }
        }
        if (mismatch >= 0 || rust.length() != java.length()) {
            int at = Math.max(mismatch, 0);
            throw new AssertionError("mismatch at hex " + at + " field " + (at / 64) + " rust "
                    + rust.substring(at, Math.min(rust.length(), at + 64)) + " java "
                    + java.substring(at, Math.min(java.length(), at + 64)));
        }
    }

    private static String cargo(String... args) throws Exception {
        String[] full = new String[9 + args.length];
        full[0] = "cargo";
        full[1] = "run";
        full[2] = "--quiet";
        full[3] = "--offline";
        full[4] = "--manifest-path";
        full[5] = "tools/xdag-contract/Cargo.toml";
        full[6] = "--bin";
        full[7] = "sign-ext";
        full[8] = "--";
        System.arraycopy(args, 0, full, 9, args.length);
        Process process = new ProcessBuilder(full).directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(false).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        process.getErrorStream().transferTo(err);
        int code = process.waitFor();
        String hex = out.toString(StandardCharsets.US_ASCII).trim();
        if (code != 0 || hex.isEmpty() || hex.split("\n")[0].length() != 1024) {
            throw new AssertionError("sign-ext " + code + " " + err.toString(StandardCharsets.UTF_8) + hex);
        }
        return hex;
    }

    private static ECKeyPair key() {
        return ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY);
    }

    private static String priv() {
        return SampleKeys.PRIVATE_KEY_STRING;
    }

    private static String ts() {
        return Long.toString(TS);
    }

    private static byte[] address(int first) {
        byte[] raw = new byte[20];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (first > 1 ? 20 - i : i + 1);
        }
        return raw;
    }

    private static Bytes32 word(int b) {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (b + i);
        }
        return Bytes32.wrap(raw);
    }

    private static byte[] fill(int len, int seed) {
        byte[] raw = new byte[len];
        for (int i = 0; i < len; i++) {
            raw[i] = (byte) (seed + i);
        }
        return raw;
    }

    private static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String hex(Bytes32 raw) {
        return hex(raw.toArray());
    }
}
