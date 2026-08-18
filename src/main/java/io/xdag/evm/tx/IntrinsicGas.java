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
package io.xdag.evm.tx;

import java.util.List;
import org.apache.tuweni.bytes.Bytes;

/**
 * Shanghai-fork intrinsic gas: the gas charged before a single opcode runs.
 *
 * <ul>
 *   <li>21000 base (G_transaction)</li>
 *   <li>calldata: 4 per zero byte, 16 per non-zero byte (EIP-2028)</li>
 *   <li>creation: +32000 (G_txcreate) and +2 per 32-byte initcode word, initcode capped at
 *       49152 bytes (EIP-3860)</li>
 *   <li>access list: 2400 per address, 1900 per storage key (EIP-2930) — charged, not warmed</li>
 * </ul>
 */
public final class IntrinsicGas {

    public static final int MAX_INITCODE_SIZE = 49_152;

    /** EIP-2930: intrinsic cost per access-list address. */
    private static final long ACCESS_LIST_ADDRESS_COST = 2_400L;
    /** EIP-2930: intrinsic cost per access-list storage key. */
    private static final long ACCESS_LIST_STORAGE_KEY_COST = 1_900L;

    private static final long TX_BASE_COST = 21_000L;
    private static final long ZERO_BYTE_COST = 4L;
    private static final long NON_ZERO_BYTE_COST = 16L;
    private static final long CREATE_BASE_COST = 32_000L;
    private static final long INITCODE_WORD_COST = 2L;

    private IntrinsicGas() {
    }

    /** Equivalent to {@code compute(payload, isContractCreation, List.of())}. */
    public static long compute(Bytes payload, boolean isContractCreation) {
        return compute(payload, isContractCreation, List.of());
    }

    /**
     * @throws IllegalArgumentException if creation initcode exceeds the EIP-3860 cap
     */
    public static long compute(Bytes payload, boolean isContractCreation,
                               List<AccessListEntry> accessList) {
        if (isContractCreation && payload.size() > MAX_INITCODE_SIZE) {
            throw new IllegalArgumentException(
                    "initcode size " + payload.size() + " exceeds EIP-3860 cap " + MAX_INITCODE_SIZE);
        }
        long gas = TX_BASE_COST;
        for (int i = 0; i < payload.size(); i++) {
            gas += payload.get(i) == 0 ? ZERO_BYTE_COST : NON_ZERO_BYTE_COST;
        }
        if (isContractCreation) {
            gas += CREATE_BASE_COST;
            gas += INITCODE_WORD_COST * ((payload.size() + 31) / 32);
        }
        for (AccessListEntry entry : accessList) {
            gas += ACCESS_LIST_ADDRESS_COST + ACCESS_LIST_STORAGE_KEY_COST * entry.storageKeys().size();
        }
        return gas;
    }
}
