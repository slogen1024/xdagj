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
package io.xdag.chain.rpc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.anchor.AnchorBody;
import io.xdag.chain.anchor.AnchorHead;
import io.xdag.chain.asset.AnchorRecord;
import io.xdag.chain.exec.ChainExecutor;
import io.xdag.chain.exec.ExecStatus;
import io.xdag.chain.exec.state.SparseMerkleTree;
import io.xdag.chain.ext.ExtCodec;
import io.xdag.chain.l1.ChainL1Keys;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.core.Block;
import io.xdag.core.XdagBlock;

/**
 * {@code chain_*} over the executed head and the 0x06 index. A view whose anchor seq is behind the
 * head is refused: the executor has no historical KV that is not a rewind of the live runtime.
 * Receipts carry status, gas, commitment and return data. Events and the receipt seq are not in
 * that blob. {@code chain_subscribe} has no websocket on this server.
 */
public final class ChainApi {

    private final LongSupplier height;
    private final long activationHeight;
    private final ChainL1Store l1;
    private final Supplier<ChainExecutor> executor;
    private final Consumer<Block> send;

    public ChainApi(LongSupplier height, long activationHeight, ChainL1Store l1, Supplier<ChainExecutor> executor,
            Consumer<Block> send) {
        this.height = Objects.requireNonNull(height, "height");
        this.activationHeight = activationHeight;
        this.l1 = Objects.requireNonNull(l1, "l1");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.send = send;
    }

    public Balance getBalance(Bytes chain, byte[] address, ChainView view) {
        long seq = viewSeq(chain, view);
        return new Balance(view.wire(), seq, Long.toString(ex().balance(chain, address)));
    }

    public State getState(Bytes chain, Bytes contract, byte[] key, ChainView view) {
        long seq = viewSeq(chain, view);
        byte[] value = ex().storage(chain, contract, key);
        return new State(view.wire(), seq, value == null ? null : Bytes.wrap(value).toHexString());
    }

    public Query query(Bytes chain, Bytes contract, int selector, byte[] args, ChainView view) {
        long seq = viewSeq(chain, view);
        byte[] data = ex().query(chain, contract, selector, args == null ? new byte[0] : args);
        return new Query(view.wire(), seq, data == null ? null : Bytes.wrap(data).toHexString());
    }

    public long estimateGas(Bytes chain, Bytes contract, int selector, byte[] args) {
        gate();
        requireHead(chain);
        return ex().estimate(chain, contract, selector, args);
    }

    public Proof getProof(Bytes chain, Bytes contract, byte[] key, ChainView view) {
        long seq = viewSeq(chain, view);
        SparseMerkleTree.Proof proof = ex().proof(chain, contract, key);
        List<String> siblings = new ArrayList<>(proof.siblings().length);
        for (Bytes32 sibling : proof.siblings()) {
            siblings.add(sibling.toHexString());
        }
        String value = proof.value() == null ? null : Bytes.wrap(proof.value()).toHexString();
        return new Proof(view.wire(), seq, proof.present(), value, siblings);
    }

    public Anchor getAnchor(Bytes chain, long seq) {
        gate();
        AnchorBody body = AnchorBody.decode(l1.getAnchorRaw(chain, seq));
        if (body == null) {
            return null;
        }
        return new Anchor(seq, body.status().name().toLowerCase(), body.stateRoot().toHexString(),
                body.outboxMapRoot().toHexString(), body.confirmHeight(), body.inputCount());
    }

    public Head getHead(Bytes chain) {
        gate();
        ChainExecutor exec = ex();
        long applied = exec.appliedSeq(chain);
        String root = (applied < 0 ? SparseMerkleTree.emptyRoot() : exec.stateRoot(chain)).toHexString();
        AnchorHead head = AnchorHead.decode(l1.getAnchorHead(chain));
        if (head == null) {
            return new Head(-1L, null, applied, root);
        }
        return new Head(head.seq(), head.anchorHash().toHexString(), applied, root);
    }

    /** Null when this node has no receipt for the hash. {@code confirm} is {@code confirmed}. */
    public Receipt getReceipt(Bytes32 blockHash) {
        gate();
        byte[] raw = ex().receiptByHash(blockHash);
        if (raw == null || raw.length < 1 + Long.BYTES + Bytes32.SIZE + Integer.BYTES) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int status = buf.get() & 0xff;
        long gas = buf.getLong();
        byte[] commitment = new byte[Bytes32.SIZE];
        buf.get(commitment);
        int len = buf.getInt();
        if (len < 0 || buf.remaining() < len) {
            throw new IllegalArgumentException("receipt");
        }
        byte[] ret = new byte[len];
        buf.get(ret);
        String name;
        try {
            name = ExecStatus.fromCode(status).name().toLowerCase();
        } catch (IllegalArgumentException e) {
            name = Integer.toString(status);
        }
        return new Receipt(name, gas, Bytes32.wrap(commitment).toHexString(), Bytes.wrap(ret).toHexString(),
                "confirmed");
    }

    /** The stored commitment opening. A missing opening is an argument error. */
    public String buildClaim(Bytes chain, long seq, long index) {
        gate();
        byte[] body = ex().opening(chain, seq, index);
        if (body == null) {
            throw new IllegalArgumentException("opening");
        }
        return Bytes.wrap(body).toHexString();
    }

    public List<String> sendCall(List<String> rawBlocks) {
        gate();
        if (send == null) {
            throw new IllegalStateException("ingest");
        }
        List<String> hashes = new ArrayList<>(rawBlocks.size());
        for (String raw : rawBlocks) {
            Block block = new Block(new XdagBlock(unhex(raw)));
            send.accept(block);
            hashes.add(block.getHash().toHexString());
        }
        return hashes;
    }

    public void subscribe(String kind) {
        gate();
        throw new IllegalStateException("websocket");
    }

    private long viewSeq(Bytes chain, ChainView view) {
        gate();
        long applied = requireHead(chain);
        if (view == ChainView.PENDING || view == ChainView.CONFIRMED) {
            return applied;
        }
        Hit hit = latest(chain, view == ChainView.FINAL, applied);
        if (hit == null) {
            throw new IllegalArgumentException("anchor");
        }
        if (hit.seq != applied) {
            throw new IllegalStateException("view");
        }
        return applied;
    }

    private long requireHead(Bytes chain) {
        long applied = ex().appliedSeq(chain);
        if (applied < 0) {
            throw new IllegalStateException("head");
        }
        return applied;
    }

    private Hit latest(Bytes chain, boolean onlyFinal, long applied) {
        byte[] raw = chain.toArray();
        Hit best = null;
        for (byte[] key : l1.sortedKeys()) {
            if (key.length != 29 || key[0] != ChainL1Keys.ANCHOR) {
                continue;
            }
            if (!Arrays.equals(raw, 0, 20, key, 1, 21)) {
                continue;
            }
            long seq = ExtCodec.u64(key, 21);
            if (Long.compareUnsigned(seq, applied) > 0) {
                continue;
            }
            AnchorBody body = AnchorBody.decode(l1.getAnchorRaw(chain, seq));
            if (body == null || body.status() == AnchorRecord.Status.VOIDED) {
                continue;
            }
            if (onlyFinal && body.status() != AnchorRecord.Status.FINAL) {
                continue;
            }
            if (best == null || Long.compareUnsigned(seq, best.seq) > 0) {
                best = new Hit(seq, body);
            }
        }
        return best;
    }

    private ChainExecutor ex() {
        ChainExecutor exec = executor.get();
        if (exec == null) {
            throw new IllegalStateException("executor");
        }
        return exec;
    }

    private void gate() {
        if (height.getAsLong() < activationHeight) {
            throw new ChainInactive();
        }
    }

    private static byte[] unhex(String raw) {
        String s = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        try {
            return Hex.decodeHex(s);
        } catch (DecoderException e) {
            throw new IllegalArgumentException("hex");
        }
    }

    private record Hit(long seq, AnchorBody body) {
    }

    public record Balance(String view, long seq, String balance) {
    }

    public record State(String view, long seq, String value) {
    }

    public record Query(String view, long seq, String data) {
    }

    public record Proof(String view, long seq, boolean present, String value, List<String> siblings) {
    }

    public record Anchor(long seq, String status, String stateRoot, String outboxMapRoot, long confirmHeight,
            long inputCount) {
    }

    public record Head(long anchorSeq, String anchorHash, long appliedSeq, String stateRoot) {
    }

    public record Receipt(String status, long gasUsed, String commitment, String returnData, String confirm) {
    }
}
