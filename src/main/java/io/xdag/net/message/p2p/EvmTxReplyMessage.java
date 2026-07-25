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
package io.xdag.net.message.p2p;

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import org.apache.tuweni.bytes.Bytes;

/** Response carrying a requested EVM tx blob (spec §6.1, 0x1D); body identical to the broadcast. */
public class EvmTxReplyMessage extends Message {

    private final Bytes rawRlp;

    public EvmTxReplyMessage(Bytes rawRlp) {
        super(MessageCode.EVM_TX_REPLY, null);
        this.rawRlp = rawRlp;

        SimpleEncoder enc = new SimpleEncoder();
        enc.writeBytes(rawRlp.toArray());
        this.body = enc.toBytes();
    }

    public EvmTxReplyMessage(byte[] body) {
        super(MessageCode.EVM_TX_REPLY, null);
        SimpleDecoder dec = new SimpleDecoder(body);
        this.rawRlp = Bytes.wrap(dec.readBytes());
        this.body = body;
    }

    public Bytes getRawRlp() {
        return rawRlp;
    }

    @Override
    public String toString() {
        return "EvmTxReplyMessage [" + rawRlp.size() + " bytes]";
    }
}
