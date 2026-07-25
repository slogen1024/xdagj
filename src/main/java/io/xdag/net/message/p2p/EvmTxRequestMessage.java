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
import org.apache.tuweni.bytes.Bytes32;

/** Request for one EVM tx blob by hash (spec §6.1, 0x1C); answered by {@link EvmTxReplyMessage}. */
public class EvmTxRequestMessage extends Message {

    private final Bytes32 txHash;

    public EvmTxRequestMessage(Bytes32 txHash) {
        super(MessageCode.EVM_TX_REQUEST, EvmTxReplyMessage.class);
        this.txHash = txHash;

        SimpleEncoder enc = new SimpleEncoder();
        enc.writeBytes(txHash.toArray());
        this.body = enc.toBytes();
    }

    public EvmTxRequestMessage(byte[] body) {
        super(MessageCode.EVM_TX_REQUEST, EvmTxReplyMessage.class);
        SimpleDecoder dec = new SimpleDecoder(body);
        this.txHash = Bytes32.wrap(dec.readBytes());
        this.body = body;
    }

    public Bytes32 getTxHash() {
        return txHash;
    }

    @Override
    public String toString() {
        return "EvmTxRequestMessage [" + txHash.toHexString() + "]";
    }
}
