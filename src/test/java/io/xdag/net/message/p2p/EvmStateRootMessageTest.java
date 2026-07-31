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

import static org.junit.Assert.assertEquals;

import io.xdag.net.message.MessageCode;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/** The EVM state-root gossip message round-trips its (height, root) through the wire body. */
public class EvmStateRootMessageTest {

    @Test
    public void round_trips_height_and_root_through_the_body() {
        Bytes32 root = Bytes32.fromHexString("0x" + "ab".repeat(32));
        EvmStateRootMessage sent = new EvmStateRootMessage(4242L, root);

        EvmStateRootMessage received = new EvmStateRootMessage(sent.getBody());

        assertEquals(MessageCode.EVM_STATE_ROOT, received.getCode());
        assertEquals(4242L, received.getHeight());
        assertEquals(root, received.getRoot());
    }
}
