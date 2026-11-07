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
package io.xdag.net.message.chain;

import io.xdag.chain.p2p.ChainSubscriptions;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;

/** {@code 0x1B CHAIN_SUBSCRIPTIONS}. Routing hint, sent both ways once the handshake completes. */
public class ChainSubscriptionsMessage extends Message {

    private final ChainSubscriptions subscriptions;

    public ChainSubscriptionsMessage(ChainSubscriptions subscriptions) {
        super(MessageCode.CHAIN_SUBSCRIPTIONS, null);
        this.subscriptions = subscriptions;
        this.body = subscriptions.encode();
    }

    public ChainSubscriptionsMessage(byte[] body) {
        super(MessageCode.CHAIN_SUBSCRIPTIONS, null);
        this.subscriptions = ChainSubscriptions.decode(body);
        this.body = body;
    }

    public ChainSubscriptions subscriptions() {
        return subscriptions;
    }
}
