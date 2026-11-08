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
package io.xdag.chain.exec;

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.config.Constants.BI_MAIN;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.ext.CallExt;
import io.xdag.chain.ext.ChainBlockClassifier;
import io.xdag.chain.ext.ChunkChain;
import io.xdag.chain.ext.Classified;
import io.xdag.chain.ext.ExtKind;
import io.xdag.chain.l1.ChainIds;
import io.xdag.chain.l1.ChainL1Processor;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.l1.ContractRecord;
import io.xdag.chain.l1.InputStatus;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.utils.XdagTime;

/**
 * Unconfirmed calls in the order {@code applyBlock} would visit them: each block's links, then the
 * block. The walk starts at the current top and stops at a block that is already main or applied.
 * It does not write flags. A call is kept only when it pays exactly one vault and that vault owns
 * the contract, the same gate {@code ChainL1Processor} uses before it writes {@code 0x0C}.
 */
public final class PendingCalls {

    private static final int MAX_BLOCKS = 4096;

    private PendingCalls() {
    }

    public static List<Block> dfs(Block top, Function<Bytes32, Block> load) {
        List<Block> out = new ArrayList<>();
        if (top == null || mainOrApplied(top)) {
            return out;
        }
        walk(top, load, out, new HashSet<>());
        return out;
    }

    public static List<ChainInput> calls(Bytes chain, List<Block> order, ChainL1Store l1,
            ChunkChain.RawBlockLookup lookup, int maxChunks, XAmount chunkFee) {
        List<ChainInput> out = new ArrayList<>();
        for (Block block : order) {
            Classified classified = ChainBlockClassifier.classify(block);
            if (classified.kind() != ExtKind.CALL || !classified.isOk()) {
                continue;
            }
            CallExt call = classified.as(CallExt.class);
            List<Bytes> vaults = vaults(block, l1);
            if (vaults.size() != 1 || !vaults.get(0).equals(chain)) {
                continue;
            }
            ContractRecord target = l1.getContract(call.contract());
            if (target == null || !target.chainId().equals(chain)) {
                continue;
            }
            int chunks = call.argsByChain()
                    ? ChunkChain.countLenient(call.argsChainHead(), lookup, maxChunks,
                            XdagTime.getEpoch(block.getTimestamp()) - 1)
                    : 0;
            InputStatus status = ChainL1Processor.feeCovers(block, chunks, chunkFee) ? InputStatus.OK
                    : InputStatus.INVALID_FEE;
            out.add(new ChainInput(out.size(), ChainInput.Kind.CALL, block.getHash(), status, call.contract()));
        }
        return out;
    }

    private static void walk(Block block, Function<Bytes32, Block> load, List<Block> out, Set<Bytes32> seen) {
        if (block == null || out.size() >= MAX_BLOCKS || mainOrApplied(block) || !seen.add(block.getHash())) {
            return;
        }
        List<Address> links = block.getLinks();
        if (links != null) {
            for (Address link : links) {
                if (!link.getIsAddress()) {
                    walk(load.apply(link.getAddress()), load, out, seen);
                }
            }
        }
        out.add(block);
    }

    private static boolean mainOrApplied(Block block) {
        int flags = block.getInfo().flags;
        return (flags & BI_MAIN) != 0 || (flags & BI_APPLIED) != 0;
    }

    private static List<Bytes> vaults(Block block, ChainL1Store l1) {
        List<Bytes> out = new ArrayList<>();
        for (Address output : block.getOutputs()) {
            if (output.getIsAddress() && output.getType() == XDAG_FIELD_OUTPUT) {
                Bytes address = ChainIds.address20(output);
                if (l1.hasChain(address)) {
                    out.add(address);
                }
            }
        }
        return out;
    }
}
