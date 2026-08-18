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
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

/**
 * One EIP-2930 access-list entry: an address plus the storage keys the tx declares it will touch.
 * RLP shape: {@code [address, [storageKey, …]]}. XDAG charges the EIP-2930 intrinsic gas for the
 * list but does NOT pre-warm the slots (spec §5: deterministic overcharge, never undercharge).
 */
public record AccessListEntry(Address address, List<Bytes32> storageKeys) {

    public AccessListEntry {
        storageKeys = List.copyOf(storageKeys);
    }

    /** Reads one entry; the input must be positioned at the entry's own list. */
    public static AccessListEntry readFrom(RLPInput in) {
        in.enterList();
        Address address = Address.wrap(in.readBytes());
        List<Bytes32> keys = in.readList(RLPInput::readBytes32);
        in.leaveList();
        return new AccessListEntry(address, keys);
    }

    public void writeTo(RLPOutput out) {
        out.startList();
        out.writeBytes(address.getBytes());
        out.startList();
        for (Bytes32 key : storageKeys) {
            out.writeBytes(key);
        }
        out.endList();
        out.endList();
    }
}
