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
package io.xdag.evm.bridge;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;

/**
 * Protocol constants of the XDAG<->EVM bridge (spec 2026-08-19 §1). The lock address is a
 * nothing-up-my-sleeve constant: the last 20 bytes of keccak256("XDAG-EVM-BRIDGE-LOCK-v1").
 * No private key exists for it (finding one breaks keccak/EC), so native funds sent there can
 * only ever leave via the Phase-3b protocol release rule. Hardcoded, NOT config: a config
 * mismatch between nodes would be a chain split.
 */
public final class BridgeConstants {

    /** 20-byte native lock address: 0x3109ff8cf0be958a428c12d86c0abf64f529f7db. */
    public static final Bytes LOCK_ADDRESS_20 =
            Hash.keccak256(Bytes.wrap("XDAG-EVM-BRIDGE-LOCK-v1".getBytes(StandardCharsets.US_ASCII)))
                    .slice(12, 20);

    /** Wei per nano-XDAG: 1 XDAG = 10^18 wei and 1 XDAG = 10^9 nano, so 1 nano = 10^9 wei. */
    public static final BigInteger WEI_PER_NANO = BigInteger.valueOf(1_000_000_000L);

    private BridgeConstants() {
    }
}
