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

package io.xdag.lane.ext;

import io.xdag.core.Address;
import io.xdag.core.Block;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Classifies a {@link Block} into its lane extension kind and decoded record: a pure function
 * {@code Block -> Classified} that never touches storage and never throws.
 *
 * <p>{@link #classify(Block)} reads {@link Block#getExtFields()}; field 0 is the header and the
 * remaining fields are the {@code payload} handed to the matching {@code *Ext.decode}. {@code links}
 * is {@link Block#getBlockLinks()} — only {@code XDAG_FIELD_OUT} block references, in field order;
 * address outputs (amount-carrying payments) and the amounts on link fields are not part of the
 * codec input. Dispatch is by the header's kind byte via {@link ExtKind#fromCode(int)}; an
 * unassigned code yields {@link ExtError#UNKNOWN_KIND} rather than throwing.
 *
 * <p>{@code block} must be non-null; passing {@code null} is a programming error, not a data
 * condition this class reports via {@link Classified}.
 */
public final class LaneBlockClassifier {

    private LaneBlockClassifier() {
    }

    /**
     * Classifies {@code block}'s extension field, if any. Returns {@link Classified#NONE} when the
     * block carries no extension field, a {@code kind == null} result with {@link ExtError#UNKNOWN_KIND}
     * when the header's kind byte is not assigned, and otherwise the matching {@code *Ext.decode}
     * result wrapped as a {@link Classified}.
     *
     * @param block the block to classify; must not be {@code null}
     */
    public static Classified classify(Block block) {
        List<Bytes32> ext = block.getExtFields();
        if (ext == null || ext.isEmpty()) {
            return Classified.NONE;
        }
        Bytes32 header = ext.get(0);
        ExtKind kind = ExtKind.fromCode(header.get(0) & 0xff);
        if (kind == null) {
            return new Classified(null, null, ExtError.UNKNOWN_KIND);
        }
        List<Bytes32> payload = new ArrayList<>(ext.subList(1, ext.size()));
        List<Address> links = block.getBlockLinks();
        return switch (kind) {
            case CALL -> wrap(kind, CallExt.decode(header, payload, links));
            case DEPLOY -> wrap(kind, DeployExt.decode(header, payload, links));
            case CHUNK -> wrap(kind, ChunkExt.decode(header, payload, links));
            case ANCHOR -> wrap(kind, AnchorExt.decode(header, payload, links));
            case BOND -> wrap(kind, BondExt.decode(header, payload, links));
            case CHALLENGE -> wrap(kind, ChallengeExt.decode(header, payload, links));
            case CLAIM -> wrap(kind, ClaimExt.decode(header, payload, links));
        };
    }

    private static Classified wrap(ExtKind kind, ExtResult<?> result) {
        return result.isOk() ? new Classified(kind, result.value(), null) : new Classified(kind, null, result.error());
    }
}
