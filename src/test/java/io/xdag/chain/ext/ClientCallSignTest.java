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
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Test;

import io.xdag.config.DevnetConfig;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;

public class ClientCallSignTest {

    private static final long TS = 0x16a00000000L;

    @Test
    public void rustInlineCallMatchesTheBuilder() throws Exception {
        byte[] chain = address(1);
        byte[] contract = address(20);
        Bytes args = Bytes.wrap(new byte[] {1, 2});
        ChainBlockBuilder.Built built = ChainBlockBuilder.call(new DevnetConfig(), TS,
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), UInt64.valueOf(1), Bytes.wrap(chain),
                Bytes.wrap(contract), 7, 0L, XAmount.of(100), XAmount.of(100, XUnit.MILLI_XDAG), args).value();
        String rust = cargo(SampleKeys.PRIVATE_KEY_STRING, Long.toString(TS), "1", hex(chain), hex(contract), "7",
                "0", "100", "100000000", "8", "0102");
        assertSame(rust, Bytes.wrap(built.block().toBytes()).toHexString().substring(2));
    }

    @Test
    public void rustChunkedCallMatchesTheBuilder() throws Exception {
        byte[] chain = address(1);
        byte[] contract = address(20);
        byte[] raw = new byte[353];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        Bytes args = Bytes.wrap(raw);
        ChainBlockBuilder.Built built = ChainBlockBuilder.call(new DevnetConfig(), TS,
                ECKeyPair.fromPrivateKey(SampleKeys.SRIVATE_KEY), UInt64.valueOf(1), Bytes.wrap(chain),
                Bytes.wrap(contract), 7, 0L, XAmount.of(100), XAmount.of(100, XUnit.MILLI_XDAG), args).value();
        String rust = cargo(SampleKeys.PRIVATE_KEY_STRING, Long.toString(TS), "1", hex(chain), hex(contract), "7",
                "0", "100", "100000000", "8", hex(raw));
        String[] lines = rust.split("\n");
        assertSame(lines[0], Bytes.wrap(built.block().toBytes()).toHexString().substring(2));
        if (lines.length - 1 != built.chunks().size()) {
            throw new AssertionError("chunks rust " + (lines.length - 1) + " java " + built.chunks().size());
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
        full[7] = "sign-call";
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
            throw new AssertionError("sign-call " + code + " " + err.toString(StandardCharsets.UTF_8) + hex);
        }
        return hex;
    }

    private static byte[] address(int first) {
        byte[] raw = new byte[20];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (first > 1 ? 20 - i : i + 1);
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
}
