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

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Builds signed CALL / DEPLOY account-transaction blocks plus the chunk chains their oversized
 * payloads (call args, contract code, init args) need.
 *
 * <p><b>Chunk chain age rule and {@code timestamp - 1}.</b> A chunk chain referenced by a
 * DEPLOY/CALL block must satisfy {@code epoch(chunk) >= epoch(payingBlock) - 1} for every chunk in
 * the chain ({@link ChunkChain}'s age rule; see its class documentation for why). Every chunk
 * chain built here is therefore rooted at {@code headTimestamp = timestamp - 1}, not
 * {@code timestamp} itself: XDAG requires a block's timestamp to be strictly no later than any
 * block it references, so the chain's head (the newest chunk, referenced directly by a link) must
 * carry a timestamp strictly less than the paying block's. Starting one tick below {@code
 * timestamp} keeps the head as close as possible to the paying block while still satisfying that
 * ordering constraint. {@link ChunkChainBuilder#split} may additionally snap the whole chain
 * further down, onto the second-to-last tick of the epoch immediately before {@code
 * headTimestamp}'s own epoch, whenever the chain would otherwise straddle an epoch boundary (see
 * its class documentation). That snap can only move a chain that starts in {@code
 * epoch(timestamp - 1)} into {@code epoch(timestamp - 1) - 1}; since {@code epoch(timestamp - 1)}
 * is always either {@code epoch(timestamp)} or {@code epoch(timestamp) - 1}, and the snap only
 * ever fires in the former case (a chain rooted at {@code epoch(timestamp) - 1} already has 65535
 * ticks of headroom below it, far more than the protocol's {@code maxPerChain} chunks could ever
 * need), the chain's epoch after any snapping is always exactly {@code epoch(payingBlock)} or
 * {@code epoch(payingBlock) - 1} — never lower. The age rule is therefore satisfied unconditionally
 * by every chain this class builds, for any {@code timestamp}. A DEPLOY's code chain and args
 * chain are each rooted at {@code timestamp - 1} independently (neither is offset by the other
 * chain's length): chaining the head timestamps instead (e.g. subtracting the code chain's chunk
 * count before rooting the args chain) could push the second chain's head an extra epoch back,
 * risking a violation of the age rule. The two chains may legitimately share a head timestamp,
 * since their content (and therefore their block hashes) differ.
 *
 * <p><b>Import order.</b> Because the head is the newest block in a chain and every other chunk is
 * older, and a block's timestamp may never be later than any block it references, the chunks
 * returned in {@link Built#chunks()} (head-first, per {@link ChunkChainBuilder#split}) must be
 * imported tail-first (oldest first) into the node, and the paying block ({@link Built#block()})
 * imported last, after every chunk it (transitively) references.
 *
 * <p><b>Value vs. fee.</b> For {@link #call} and {@link #deployIntoLane}, the caller is responsible
 * for choosing {@code value >= headerFee + Constants.MIN_GAS}: this is the ordinary L1 input rule
 * enforced by {@code tryToConnect} (an input must cover every output it funds, including the header
 * fee output and gas), unrelated to and independent of extension-specific validation.
 * {@link #deployNewLane} instead sizes its own self-transfer automatically ({@code headerFee +
 * MIN_GAS}), since a new-lane DEPLOY has no external payee. Separately, {@code headerFee} itself
 * must be at least {@link #minHeaderFee(XAmount, int)} for the number of chunk blocks the built
 * chains actually contain, or the SP1 chunk-fee consensus rule records the input as {@code
 * INVALID_FEE} once lane semantics activate.
 *
 * <p><b>Link field ordering.</b> {@link Block}'s constructor assigns each field's wire-format type
 * nibble in the order its {@code links} argument lists them, while {@link Block#getEncodedBody()}
 * always writes every {@code INPUT}/{@code IN} link before every {@code OUTPUT}/{@code OUT}/{@code
 * COINBASE} link, each group in its own relative order. For the nibble sequence and the actual
 * field bytes to agree, every {@code refs} list built here therefore lists the {@code INPUT} entry
 * first, then {@code OUTPUT} entries, then {@code OUT} (chunk-chain-head) links — never any other
 * order.
 */
public final class LaneBlockBuilder {

    /** header, nonce, INPUT, OUTPUT, pubkey, SIGN_OUT x2, ext header */
    private static final int TX_FIXED_FIELDS = 8;

    public record Built(Block block, List<Block> chunks) {
    }

    private LaneBlockBuilder() {
    }

    public static ExtResult<Built> call(Config config, long timestamp, ECKeyPair sender, UInt64 nonce, Bytes laneId,
                                        Bytes contract, int selector, long gasLimit, XAmount value, XAmount headerFee,
                                        Bytes args) {
        int inlineCapacity = Math.min((XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS) * ExtCodec.FIELD, CallExt.MAX_INLINE_ARGS);
        List<Block> chunks = List.of();
        Bytes32 argsHead = null;
        int flags = 0;
        Bytes inline = args;
        if (args.size() > inlineCapacity) {
            chunks = ChunkChainBuilder.split(config, args, timestamp - 1);
            argsHead = Bytes32.wrap(chunks.get(0).getHashLow().toArray());
            flags = CallExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        CallExt ext = new CallExt(flags, contract, selector, gasLimit, inline.size(), inline, argsHead);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, value));
        refs.add(new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, value, true));
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(), chunks);
    }

    public static ExtResult<Built> deployNewLane(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                                 XAmount headerFee, Bytes wasm, LaneConfigExt laneConfig,
                                                 Bytes initArgs, long gasLimit) {
        List<Block> chunks = new ArrayList<>();
        long chunkHeadTs = timestamp - 1;
        List<Block> code = ChunkChainBuilder.split(config, wasm, chunkHeadTs);
        chunks.addAll(code);
        Bytes32 codeHead = Bytes32.wrap(code.get(0).getHashLow().toArray());
        int flags = DeployExt.FLAG_NEW_LANE | DeployExt.FLAG_CODE_CHAIN;
        // fixed 8 + codeHash + config + code link = 11 fields used
        int inlineCapacity = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 3) * ExtCodec.FIELD;
        Bytes32 argsHead = null;
        Bytes inline = initArgs;
        if (initArgs.size() > Math.min(inlineCapacity, CallExt.MAX_INLINE_ARGS)) {
            List<Block> args = ChunkChainBuilder.split(config, initArgs, chunkHeadTs);
            chunks.addAll(args);
            argsHead = Bytes32.wrap(args.get(0).getHashLow().toArray());
            flags |= DeployExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        DeployExt ext = new DeployExt(flags, Bytes.wrap(new byte[20]), gasLimit, inline.size(), HashUtils.sha256(wasm),
                laneConfig, inline, codeHead, argsHead);
        List<Address> refs = new ArrayList<>();
        // self transfer; tryToConnect requires input amount >= header fee + MIN_GAS x outputs
        XAmount self = headerFee.add(Constants.MIN_GAS);
        refs.add(input(sender, self));
        refs.add(new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_OUTPUT, self, true));
        refs.add(new Address(codeHead, XDAG_FIELD_OUT, false));
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(), chunks);
    }

    /** wasm may be null when the code hash is already known to the network; then codeHash is used as given. */
    public static ExtResult<Built> deployIntoLane(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                                  Bytes laneId, XAmount value, XAmount headerFee, Bytes wasm,
                                                  Bytes32 codeHash, Bytes initArgs, long gasLimit) {
        List<Block> chunks = new ArrayList<>();
        long chunkHeadTs = timestamp - 1;
        int flags = 0;
        Bytes32 codeHead = null;
        Bytes32 hash = codeHash;
        if (wasm != null) {
            List<Block> code = ChunkChainBuilder.split(config, wasm, chunkHeadTs);
            chunks.addAll(code);
            codeHead = Bytes32.wrap(code.get(0).getHashLow().toArray());
            flags |= DeployExt.FLAG_CODE_CHAIN;
            hash = HashUtils.sha256(wasm);
        }
        // fixed 8 + codeHash + optional code link
        int inlineCapacity = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 1 - (codeHead != null ? 1 : 0)) * ExtCodec.FIELD;
        Bytes32 argsHead = null;
        Bytes inline = initArgs;
        if (initArgs.size() > Math.min(inlineCapacity, CallExt.MAX_INLINE_ARGS)) {
            List<Block> args = ChunkChainBuilder.split(config, initArgs, chunkHeadTs);
            chunks.addAll(args);
            argsHead = Bytes32.wrap(args.get(0).getHashLow().toArray());
            flags |= DeployExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        DeployExt ext = new DeployExt(flags, laneId, gasLimit, inline.size(), hash, null, inline, codeHead, argsHead);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, value));
        refs.add(new Address(BytesUtils.arrayToByte32(laneId.toArray()), XDAG_FIELD_OUTPUT, value, true));
        if (codeHead != null) {
            refs.add(new Address(codeHead, XDAG_FIELD_OUT, false));
        }
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(), chunks);
    }

    private static Address input(ECKeyPair sender, XAmount amount) {
        return new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_INPUT, amount, true);
    }

    /** Number of CHUNK blocks a payload of {@code len} bytes needs (0 for an empty payload). */
    public static int chunksFor(int len) {
        return (len + ChunkExt.MAX_DATA_LEN - 1) / ChunkExt.MAX_DATA_LEN;
    }

    /** Smallest header fee that satisfies the consensus chunk-fee rule for {@code chunks} chunk blocks (chunkFee = LaneSpec.getLaneChunkFee()). */
    public static XAmount minHeaderFee(XAmount chunkFee, int chunks) {
        return chunkFee.multiply(chunks);
    }

    private static ExtResult<Built> finish(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                           XAmount headerFee, List<Address> refs, Bytes32 extHeader,
                                           List<Bytes32> payload, List<Block> chunks) {
        List<Bytes32> fields = new ArrayList<>();
        fields.add(extHeader);
        fields.addAll(payload);
        int total = 1 + 1 + refs.size() + 3 + fields.size();
        if (total > XdagBlock.XDAG_BLOCK_FIELDS) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        Block raw = new Block(config, timestamp, refs, null, false, List.of(sender), null, 0, headerFee, nonce, fields);
        raw.signOut(sender);
        return ExtResult.ok(new Built(new Block(new XdagBlock(raw.toBytes())), chunks));
    }
}
