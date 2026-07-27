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
package io.xdag.rpc.eth;

import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.tx.EvmTransaction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;

/** Builds Ethereum JSON-RPC tx/receipt/block/log objects as ordered maps (Jackson-serialisable). */
public final class EthObjects {

    /** 256-bit zero logs bloom (0x + 512 hex zeros). */
    public static final String ZERO_BLOOM = "0x" + "0".repeat(512);

    private EthObjects() {
    }

    public static Map<String, Object> transaction(EvmTransaction tx, long blockNumber,
                                                   String blockHash, int txIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hash", EthHex.data(tx.getHash().getBytes()));
        m.put("nonce", EthHex.quantity(tx.getNonce()));
        m.put("blockHash", blockHash);
        m.put("blockNumber", EthHex.quantity(blockNumber));
        m.put("transactionIndex", EthHex.quantity(txIndex));
        m.put("from", EthHex.data(tx.getSender().getBytes()));
        m.put("to", tx.getTo().map(a -> (Object) EthHex.data(a.getBytes())).orElse(null));
        m.put("value", EthHex.quantity(tx.getValue().getAsBigInteger()));
        m.put("gasPrice", EthHex.quantity(tx.getGasPrice().getAsBigInteger()));
        m.put("gas", EthHex.quantity(tx.getGasLimit()));
        m.put("input", EthHex.data(tx.getPayload()));
        m.put("chainId", EthHex.quantity(tx.getChainId()));
        m.put("v", EthHex.quantity(BigInteger.valueOf(tx.getSignature().getRecId())));
        m.put("r", EthHex.quantity(tx.getSignature().getR()));
        m.put("s", EthHex.quantity(tx.getSignature().getS()));
        m.put("type", "0x0");
        return m;
    }

    public static Map<String, Object> receipt(EvmTransaction tx, EvmReceipt receipt, long blockNumber,
                                              String blockHash, int txIndex, List<Object> logs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionHash", EthHex.data(tx.getHash().getBytes()));
        m.put("transactionIndex", EthHex.quantity(txIndex));
        m.put("blockHash", blockHash);
        m.put("blockNumber", EthHex.quantity(blockNumber));
        m.put("from", EthHex.data(tx.getSender().getBytes()));
        m.put("to", tx.getTo().map(a -> (Object) EthHex.data(a.getBytes())).orElse(null));
        m.put("cumulativeGasUsed", EthHex.quantity(receipt.gasUsed()));
        m.put("gasUsed", EthHex.quantity(receipt.gasUsed()));
        m.put("contractAddress",
                receipt.contractAddress().map(a -> (Object) EthHex.data(a.getBytes())).orElse(null));
        m.put("logs", logs);
        m.put("logsBloom", ZERO_BLOOM);
        m.put("status", receipt.status() == 1 ? "0x1" : "0x0");
        m.put("effectiveGasPrice", EthHex.quantity(tx.getGasPrice().getAsBigInteger()));
        m.put("type", "0x0");
        return m;
    }

    public static Map<String, Object> log(Log log, long blockNumber, String blockHash, Hash txHash,
                                          int txIndex, int logIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("address", EthHex.data(log.getLogger().getBytes()));
        List<String> topics = new ArrayList<>();
        for (LogTopic t : log.getTopics()) {
            topics.add(EthHex.data(t.getBytes()));
        }
        m.put("topics", topics);
        m.put("data", EthHex.data(log.getData()));
        m.put("blockNumber", EthHex.quantity(blockNumber));
        m.put("blockHash", blockHash);
        m.put("transactionHash", EthHex.data(txHash.getBytes()));
        m.put("transactionIndex", EthHex.quantity(txIndex));
        m.put("logIndex", EthHex.quantity(logIndex));
        m.put("removed", Boolean.FALSE);
        return m;
    }
}
