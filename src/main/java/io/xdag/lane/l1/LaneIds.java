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
package io.xdag.lane.l1;

import io.xdag.core.Address;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.utils.BasicUtils;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Deterministic identifiers: laneId / contractId are derived from the creating block hash; laneId is
 * also the vault address.
 *
 * <p>Both derivations are domain-separated SHA-256 digests truncated to 20 bytes, i.e.
 * {@code sha256(tag || blockHash)[0..20]} with {@code tag} being the ASCII {@code "xdag-lane"} or
 * {@code "xdag-contract"}. They are protocol constants: every node must derive the same lane and
 * contract address for the same creating block, so neither the tags nor the truncation may change
 * without a hard fork (see {@code LaneL1ProcessorTest#laneAndContractIdsArePinned}, which pins both
 * against a hand-computed digest).
 */
public final class LaneIds {

    private static final Bytes LANE_TAG = Bytes.wrap("xdag-lane".getBytes(StandardCharsets.US_ASCII));
    private static final Bytes CONTRACT_TAG = Bytes.wrap("xdag-contract".getBytes(StandardCharsets.US_ASCII));
    /** The all-zero 20-byte address, used as the {@code contract} field of a non-contract input record. */
    public static final Bytes ZERO_ADDRESS = Bytes.wrap(new byte[20]);

    private LaneIds() {
    }

    /**
     * The lane (and vault) address created by the block with this hash.
     *
     * @throws NullPointerException if {@code blockHash} is {@code null}
     */
    public static Bytes laneIdOf(Bytes32 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        return Bytes.wrap(HashUtils.sha256(Bytes.concatenate(LANE_TAG, blockHash)).slice(0, 20).toArray());
    }

    /**
     * The contract address deployed by the block with this hash.
     *
     * @throws NullPointerException if {@code blockHash} is {@code null}
     */
    public static Bytes contractIdOf(Bytes32 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        return Bytes.wrap(HashUtils.sha256(Bytes.concatenate(CONTRACT_TAG, blockHash)).slice(0, 20).toArray());
    }

    /**
     * The 20-byte address carried by an address-type link (INPUT/OUTPUT/COINBASE); the address sits
     * at offset 8..28 of the 32-byte link structure.
     *
     * @throws NullPointerException     if {@code address} is {@code null}
     * @throws IllegalArgumentException if {@code address} is a block reference rather than an
     *                                  address (its lower 24 bytes are a hashlow, not an address)
     */
    public static Bytes address20(Address address) {
        Objects.requireNonNull(address, "address");
        if (!address.getIsAddress()) {
            throw new IllegalArgumentException("not an address link: " + address.getType());
        }
        return Bytes.wrap(BasicUtils.hash2byte(address.getAddress()).toArray());
    }
}
