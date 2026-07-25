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
package io.xdag.evm.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

/**
 * Execution receipt for one EVM transaction (spec §4.3): status (1 = success, 0 = reverted/failed),
 * gas used, the created contract address for deployments, and the emitted logs.
 *
 * <p>RLP layout: {@code [status, gasUsed, contractAddressOrEmpty, [log...]]} with each log encoded
 * by Besu's own {@link Log#writeTo} / {@link Log#readFrom}.
 */
public record EvmReceipt(int status, long gasUsed, Optional<Address> contractAddress, List<Log> logs) {

    public Bytes toRlp() {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        out.writeLongScalar(status);
        out.writeLongScalar(gasUsed);
        out.writeBytes(contractAddress.map(a -> (Bytes) a.getBytes()).orElse(Bytes.EMPTY));
        out.startList();
        for (Log log : logs) {
            log.writeTo(out);
        }
        out.endList();
        out.endList();
        return out.encoded();
    }

    public static EvmReceipt fromRlp(Bytes rlp) {
        RLPInput in = RLP.input(rlp);
        in.enterList();
        int status = (int) in.readLongScalar();
        long gasUsed = in.readLongScalar();
        Bytes addressBytes = in.readBytes();
        Optional<Address> contractAddress =
                addressBytes.isEmpty() ? Optional.empty() : Optional.of(Address.wrap(addressBytes));
        List<Log> logs = new ArrayList<>();
        in.enterList();
        while (!in.isEndOfCurrentList()) {
            logs.add(Log.readFrom(in));
        }
        in.leaveList();
        in.leaveList();
        return new EvmReceipt(status, gasUsed, contractAddress, List.copyOf(logs));
    }
}
