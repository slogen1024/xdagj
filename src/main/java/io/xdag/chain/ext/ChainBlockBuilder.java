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

package io.xdag.chain.ext;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.config.spec.ChainSpec;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Builds signed CALL / DEPLOY account-transaction blocks plus the chunk chains their oversized
 * payloads (call args, contract code, init args) need.
 *
 * <p><b>L1 amount rule.</b> {@code BlockchainImpl.outPutNum}/{@code getTxFee} count every entry in
 * {@link Block#getOutputs()} without distinguishing a real payment ({@code XDAG_FIELD_OUTPUT}) from
 * a chunk-chain-head reference ({@code XDAG_FIELD_OUT}): both are "outputs" for the L1 fee rule
 * {@code input >= headerFee + MIN_GAS * outputs}. Every builder here therefore emits exactly one
 * {@code OUTPUT} entry (the real payment or self-transfer) plus one {@code OUT} link per chunk chain
 * it references, and {@code value} (for {@link #call} / {@link #deployIntoChain}) or the self-transfer
 * (for {@link #deployNewChain}) must be at least {@link #requiredValue(XAmount, int)} for the number
 * of chunk-chain links ({@code chainLinks}) the built block actually carries — chain links count as
 * outputs here just as they do in {@code outPutNum}. {@link #deployNewChain} computes its own
 * self-transfer this way, after deciding whether an args chain exists (the code chain always
 * exists); {@link #call} and {@link #deployIntoChain} leave {@code value} to the caller, who must size
 * it with {@link #requiredValue(XAmount, int)} using the block's expected {@code chainLinks} count
 * (the chunk-count helpers below let a caller work that out before building). Separately, {@code
 * headerFee} itself must be at least {@link #minHeaderFee(XAmount, int)} for the total number of
 * chunk blocks summed across every chain the block links, or the SP1 chunk-fee consensus rule
 * records the input as {@code INVALID_FEE} once chain semantics activate.
 *
 * <p><b>Chunk chain age rule and head timestamp.</b> A chunk chain referenced by a DEPLOY/CALL block
 * must satisfy {@code epoch(chunk) >= epoch(payingBlock) - 1} for every chunk in the chain
 * ({@link ChunkChain}'s age rule; see its class documentation for why). Every chunk chain built here
 * is therefore rooted one tick below {@code timestamp} — nudged one further tick down when that would
 * otherwise land exactly on the end-of-epoch tick (i.e. {@code timestamp - 1} has low 16 bits {@code
 * 0xffff}), since {@code XdagTime.isEndOfEpoch} would route that chunk block (built with no INPUT)
 * down the RandomX difficulty path in {@code BlockchainImpl.calculateCurrentBlockDiff} — the same
 * reason {@link ChunkChainBuilder#split} itself avoids that tick when it snaps a chain. XDAG requires
 * a block's timestamp to be strictly no later than any block it references, so rooting one tick below
 * {@code timestamp} keeps the head as close as possible to the paying block while satisfying that
 * ordering constraint. {@link ChunkChainBuilder#split} may additionally snap the whole chain further
 * down onto the epoch immediately before the head's own epoch, whenever the chain would otherwise
 * straddle an epoch boundary (see its class documentation). For any chain bounded by
 * {@link #MAX_CHUNKS_PER_CHAIN} chunks the resulting epoch is always exactly {@code
 * epoch(payingBlock)} or {@code epoch(payingBlock) - 1} — never lower — so the age rule is satisfied
 * by every chain this class builds, for any {@code timestamp}. A DEPLOY's code chain and args chain
 * are each rooted independently (neither is offset by the other chain's length): chaining the head
 * timestamps instead (e.g. subtracting the code chain's chunk count before rooting the args chain)
 * could push the second chain's head an extra epoch back, risking a violation of the age rule. The
 * two chains may legitimately share a head timestamp: when their content also happens to coincide,
 * the resulting chunk blocks are byte-identical, and {@link #finish} de-duplicates them by hashlow
 * (keeping the first occurrence, preserving order) before returning them in {@link Built#chunks()} —
 * otherwise an importer would see the second copy as already {@code EXIST}.
 *
 * <p><b>Import order.</b> Because the head is the newest block in a chain and every other chunk is
 * older, and a block's timestamp may never be later than any block it references, the chunks
 * returned in {@link Built#chunks()} (head-first, per {@link ChunkChainBuilder#split}) must be
 * imported tail-first (oldest first) into the node, and the paying block ({@link Built#block()})
 * imported last, after every chunk it (transitively) references.
 *
 * <p><b>Error contract.</b> Payload-shape problems are reported as an {@link ExtResult} failure,
 * never thrown: an empty {@code wasm}, a chain that would need more chunks than {@code config}'s
 * configured {@link ChainSpec#getChainMaxChunksPerChain()} allows (defaulting to
 * {@link #MAX_CHUNKS_PER_CHAIN}) ({@link ExtError#CHUNK_TOO_MANY}), or (for
 * {@link #deployIntoChain}) neither a {@code wasm} nor a {@code codeHash} given
 * ({@link ExtError#BAD_LENGTH} in both other cases). Programmer errors throw instead: {@link
 * Objects#requireNonNull} on {@code config}/{@code sender}/{@code nonce} and the method's payload
 * parameters ({@code args}, or {@code wasm}/{@code chainConfig}/{@code initArgs} as applicable), and
 * {@link IllegalArgumentException} when {@code chainId} (or, for {@link #call}, {@code contract} —
 * enforced by {@link CallExt}'s own constructor) is not exactly 20 bytes.
 *
 * <p><b>Link field ordering.</b> {@link Block}'s constructor assigns each field's wire-format type
 * nibble in the order its {@code links} argument lists them, while {@link Block#getEncodedBody()}
 * always writes every {@code INPUT}/{@code IN} link before every {@code OUTPUT}/{@code OUT}/{@code
 * COINBASE} link, each group in its own relative order. For the nibble sequence and the actual
 * field bytes to agree, every {@code refs} list built here therefore lists the {@code INPUT} entry
 * first, then {@code OUTPUT} entries, then {@code OUT} (chunk-chain-head) links — never any other
 * order.
 */
public final class ChainBlockBuilder {

    /** header, nonce, INPUT, OUTPUT, pubkey, SIGN_OUT x2, ext header */
    private static final int TX_FIXED_FIELDS = 8;
    /** CALL inline-args capacity: fixed fields only (no chain link needed for an inline call). */
    private static final int CALL_INLINE = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS) * ExtCodec.FIELD; // 256
    /** deployNewChain inline-args capacity: fixed fields + codeHash + config + code link used. */
    private static final int DEPLOY_NEW_INLINE = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 3) * ExtCodec.FIELD; // 160
    /** deployIntoChain inline-args capacity with no code chain: fixed fields + codeHash used. */
    private static final int DEPLOY_INTO_INLINE = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 1) * ExtCodec.FIELD; // 224
    /** deployIntoChain inline-args capacity with a code chain: fixed fields + codeHash + code link used. */
    private static final int DEPLOY_INTO_CODE_INLINE = (XdagBlock.XDAG_BLOCK_FIELDS - TX_FIXED_FIELDS - 2) * ExtCodec.FIELD; // 192
    // All four capacities above are already <= CallExt.MAX_INLINE_ARGS (256), so callers of these
    // constants never need to additionally clamp against it.

    /**
     * Protocol default for {@code chain.chunk.maxPerChain}. Callers of this class are actually
     * capped against {@code config}'s configured {@link ChainSpec#getChainMaxChunksPerChain()} (which
     * falls back to this default when not overridden): a payload that would need more chunks than
     * that is reported as {@link ExtError#CHUNK_TOO_MANY} rather than built.
     */
    public static final int MAX_CHUNKS_PER_CHAIN = ChainSpec.DEFAULT_MAX_CHUNKS_PER_CHAIN;

    public record Built(Block block, List<Block> chunks, int chainLinks, int totalChunks) {
        public Built {
            chunks = List.copyOf(chunks);
        }
    }

    private ChainBlockBuilder() {
    }

    public static ExtResult<Built> call(Config config, long timestamp, ECKeyPair sender, UInt64 nonce, Bytes chainId,
                                        Bytes contract, int selector, long gasLimit, XAmount value, XAmount headerFee,
                                        Bytes args) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(args, "args");
        requireAddress(chainId, "chainId");

        int argsChunks = callChunks(args.size());
        List<Block> chunks = List.of();
        Bytes32 argsHead = null;
        int flags = 0;
        Bytes inline = args;
        if (argsChunks > 0) {
            ExtResult<List<Block>> split = splitChain(config, args, timestamp);
            if (!split.isOk()) {
                return ExtResult.fail(split.error());
            }
            chunks = split.value();
            argsHead = headOf(chunks);
            flags = CallExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        CallExt ext = new CallExt(flags, contract, selector, gasLimit, inline.size(), inline, argsHead);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, value));
        refs.add(new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, value, true));
        int chainLinks = 0;
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
            chainLinks = 1;
        }
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(),
                chunks, chainLinks, argsChunks);
    }

    public static ExtResult<Built> deployNewChain(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                                 XAmount headerFee, Bytes wasm, ChainConfigExt chainConfig,
                                                 Bytes initArgs, long gasLimit) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(wasm, "wasm");
        Objects.requireNonNull(chainConfig, "chainConfig");
        Objects.requireNonNull(initArgs, "initArgs");

        ExtResult<List<Block>> codeSplit = splitChain(config, wasm, timestamp);
        if (!codeSplit.isOk()) {
            return ExtResult.fail(codeSplit.error());
        }
        List<Block> code = codeSplit.value();
        List<Block> chunks = new ArrayList<>(code);
        Bytes32 codeHead = headOf(code);
        int flags = DeployExt.FLAG_NEW_CHAIN | DeployExt.FLAG_CODE_CHAIN;

        Bytes32 argsHead = null;
        Bytes inline = initArgs;
        if (initArgs.size() > DEPLOY_NEW_INLINE) {
            ExtResult<List<Block>> argsSplit = splitChain(config, initArgs, timestamp);
            if (!argsSplit.isOk()) {
                return ExtResult.fail(argsSplit.error());
            }
            List<Block> args = argsSplit.value();
            chunks.addAll(args);
            argsHead = headOf(args);
            flags |= DeployExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        DeployExt ext = new DeployExt(flags, Bytes.wrap(new byte[20]), gasLimit, inline.size(), HashUtils.sha256(wasm),
                chainConfig, inline, codeHead, argsHead);

        // Computed after deciding whether an args chain exists: the code chain always exists, so
        // chainLinks is at least 1.
        int chainLinks = argsHead != null ? 2 : 1;
        XAmount self = requiredValue(headerFee, chainLinks);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, self));
        refs.add(new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_OUTPUT, self, true));
        refs.add(new Address(codeHead, XDAG_FIELD_OUT, false));
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
        }
        int totalChunks = deployNewChainChunks(wasm.size(), initArgs.size());
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(),
                chunks, chainLinks, totalChunks);
    }

    /**
     * wasm may be null when the code hash is already known to the network; then codeHash is used as
     * given. When both wasm and codeHash are given, wasm wins: the code hash is recomputed from wasm
     * and the caller-supplied codeHash is ignored. Exactly one of wasm/codeHash must be non-null.
     */
    public static ExtResult<Built> deployIntoChain(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                                  Bytes chainId, XAmount value, XAmount headerFee, Bytes wasm,
                                                  Bytes32 codeHash, Bytes initArgs, long gasLimit) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(initArgs, "initArgs");
        requireAddress(chainId, "chainId");
        if (wasm == null && codeHash == null) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }

        List<Block> chunks = new ArrayList<>();
        int flags = 0;
        Bytes32 codeHead = null;
        Bytes32 hash = codeHash;
        if (wasm != null) {
            ExtResult<List<Block>> codeSplit = splitChain(config, wasm, timestamp);
            if (!codeSplit.isOk()) {
                return ExtResult.fail(codeSplit.error());
            }
            List<Block> code = codeSplit.value();
            chunks.addAll(code);
            codeHead = headOf(code);
            flags |= DeployExt.FLAG_CODE_CHAIN;
            hash = HashUtils.sha256(wasm);
        }
        int inlineCapacity = codeHead != null ? DEPLOY_INTO_CODE_INLINE : DEPLOY_INTO_INLINE;
        Bytes32 argsHead = null;
        Bytes inline = initArgs;
        if (initArgs.size() > inlineCapacity) {
            ExtResult<List<Block>> argsSplit = splitChain(config, initArgs, timestamp);
            if (!argsSplit.isOk()) {
                return ExtResult.fail(argsSplit.error());
            }
            List<Block> args = argsSplit.value();
            chunks.addAll(args);
            argsHead = headOf(args);
            flags |= DeployExt.FLAG_ARGS_CHAIN;
            inline = Bytes.EMPTY;
        }
        DeployExt ext = new DeployExt(flags, chainId, gasLimit, inline.size(), hash, null, inline, codeHead, argsHead);
        List<Address> refs = new ArrayList<>();
        refs.add(input(sender, value));
        refs.add(new Address(BytesUtils.arrayToByte32(chainId.toArray()), XDAG_FIELD_OUTPUT, value, true));
        int chainLinks = 0;
        if (codeHead != null) {
            refs.add(new Address(codeHead, XDAG_FIELD_OUT, false));
            chainLinks++;
        }
        if (argsHead != null) {
            refs.add(new Address(argsHead, XDAG_FIELD_OUT, false));
            chainLinks++;
        }
        int totalChunks = deployIntoChainChunks(wasm != null ? wasm.size() : 0, initArgs.size());
        return finish(config, timestamp, sender, nonce, headerFee, refs, ext.encodeHeader(), ext.encodePayload(),
                chunks, chainLinks, totalChunks);
    }

    private static Address input(ECKeyPair sender, XAmount amount) {
        return new Address(BytesUtils.arrayToByte32(sender.toAddress().toArray()), XDAG_FIELD_INPUT, amount, true);
    }

    private static Bytes32 headOf(List<Block> chunks) {
        return Bytes32.wrap(chunks.get(0).getHashLow().toArray());
    }

    private static void requireAddress(Bytes value, String name) {
        if (value.size() != 20) {
            throw new IllegalArgumentException(name + " must be 20 bytes: " + value.size());
        }
    }

    /**
     * {@code timestamp - 1}, nudged one further tick down when that would land exactly on the
     * end-of-epoch tick (low 16 bits {@code 0xffff}) — see the class documentation.
     */
    private static long chunkHeadTimestamp(long timestamp) {
        long t = timestamp - 1;
        if ((t & 0xffffL) == 0xffffL) {
            t -= 1;
        }
        return t;
    }

    /**
     * Splits {@code payload} into a chunk chain rooted at {@link #chunkHeadTimestamp(long)}.
     * Payload-shape problems are reported as an {@link ExtResult} failure rather than thrown: an
     * empty payload ({@link ExtError#BAD_LENGTH}) or one that would need more chunks than
     * {@code config}'s configured {@link ChainSpec#getChainMaxChunksPerChain()} allows
     * ({@link ExtError#CHUNK_TOO_MANY}; {@link #MAX_CHUNKS_PER_CHAIN} is only the protocol
     * default {@code config} falls back to when not overridden).
     */
    private static ExtResult<List<Block>> splitChain(Config config, Bytes payload, long timestamp) {
        if (payload.isEmpty()) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        if (chunksFor(payload.size()) > config.getChainSpec().getChainMaxChunksPerChain()) {
            return ExtResult.fail(ExtError.CHUNK_TOO_MANY);
        }
        return ExtResult.ok(ChunkChainBuilder.split(config, payload, chunkHeadTimestamp(timestamp)));
    }

    /** Number of CHUNK blocks a payload of {@code len} bytes needs (0 for an empty payload). */
    public static int chunksFor(int len) {
        if (len < 0) {
            throw new IllegalArgumentException("negative length: " + len);
        }
        return Math.ceilDiv(len, ChunkExt.MAX_DATA_LEN);
    }

    /** Number of chunk blocks {@link #call} would produce for {@code argsLen} bytes of call args. */
    public static int callChunks(int argsLen) {
        return argsLen > CALL_INLINE ? chunksFor(argsLen) : 0;
    }

    /**
     * Number of chunk blocks {@link #deployNewChain} would produce for {@code wasmLen} bytes of code
     * (always chained) and {@code argsLen} bytes of init args (chained only above the inline
     * capacity).
     */
    public static int deployNewChainChunks(int wasmLen, int argsLen) {
        int code = chunksFor(wasmLen); // deployNewChain always chains the code
        int args = argsLen > DEPLOY_NEW_INLINE ? chunksFor(argsLen) : 0;
        return code + args;
    }

    /**
     * Number of chunk blocks {@link #deployIntoChain} would produce for {@code wasmLen} bytes of code
     * ({@code 0} meaning no code chain — the code hash is already known) and {@code argsLen} bytes
     * of init args (chained only above the inline capacity, which itself depends on whether a code
     * chain is present).
     */
    public static int deployIntoChainChunks(int wasmLen, int argsLen) {
        int code = wasmLen > 0 ? chunksFor(wasmLen) : 0;
        int inlineCapacity = code > 0 ? DEPLOY_INTO_CODE_INLINE : DEPLOY_INTO_INLINE;
        int args = argsLen > inlineCapacity ? chunksFor(argsLen) : 0;
        return code + args;
    }

    /**
     * Minimum INPUT/OUTPUT amount the L1 fee rule {@code input >= headerFee + MIN_GAS * outputs}
     * needs, where each chunk-chain-head OUT link counts as an output alongside the block's own
     * single OUTPUT transfer (see the class documentation).
     *
     * @param headerFee  the block's own header fee
     * @param chainLinks the number of chunk-chain-head OUT links the block carries (0-2 for the
     *                   builders in this class); the block's single OUTPUT entry is already
     *                   accounted for
     */
    public static XAmount requiredValue(XAmount headerFee, int chainLinks) {
        return headerFee.add(Constants.MIN_GAS.multiply(1L + chainLinks));
    }

    /**
     * Smallest header fee that satisfies the consensus chunk-fee rule (chunkFee =
     * {@code ChainSpec.getChainChunkFee()}) for {@code chunks} chunk blocks, summed across every chain
     * the block links (e.g. {@link #callChunks}/{@link #deployNewChainChunks}/
     * {@link #deployIntoChainChunks}).
     */
    public static XAmount minHeaderFee(XAmount chunkFee, int chunks) {
        return chunkFee.multiply(chunks);
    }

    private static ExtResult<Built> finish(Config config, long timestamp, ECKeyPair sender, UInt64 nonce,
                                           XAmount headerFee, List<Address> refs, Bytes32 extHeader,
                                           List<Bytes32> payload, List<Block> chunks, int chainLinks,
                                           int totalChunks) {
        List<Bytes32> fields = new ArrayList<>();
        fields.add(extHeader);
        fields.addAll(payload);
        int total = 1 + 1 + refs.size() + 3 + fields.size();
        if (total > XdagBlock.XDAG_BLOCK_FIELDS) {
            return ExtResult.fail(ExtError.BAD_LENGTH);
        }
        Block raw = new Block(config, timestamp, refs, null, false, List.of(sender), null, 0, headerFee, nonce, fields);
        raw.signOut(sender);
        Block block = new Block(new XdagBlock(raw.toBytes()));
        return ExtResult.ok(new Built(block, dedupeByHashLow(chunks), chainLinks, totalChunks));
    }

    /**
     * Removes duplicate chunk blocks by hashlow, keeping the first occurrence and preserving order:
     * identical wasm/init-args content chained at the same head timestamp produces byte-identical
     * raw chunk blocks, and importing the same block twice would otherwise be reported as
     * {@code EXIST}.
     */
    private static List<Block> dedupeByHashLow(List<Block> chunks) {
        List<Block> result = new ArrayList<>(chunks.size());
        Set<Bytes32> seen = new HashSet<>();
        for (Block b : chunks) {
            if (seen.add(Bytes32.wrap(b.getHashLow().toArray()))) {
                result.add(b);
            }
        }
        return result;
    }
}
