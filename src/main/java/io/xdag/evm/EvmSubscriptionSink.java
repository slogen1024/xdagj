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
package io.xdag.evm;

import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;

/**
 * Observation hooks the WebSocket subscription layer (C6) registers on the consensus + EVM paths.
 * Node-local and nullable: when no WS server is running the sink is absent and consensus/EVM behave
 * exactly as before. Implemented by {@code io.xdag.rpc.ws.SubscriptionManager}; kept in
 * {@code io.xdag.evm} so consensus and EVM fire it without depending on the RPC layer.
 */
public interface EvmSubscriptionSink {

    /** A single emitted log with the coordinates an eth log object needs (mirrors eth_getLogs output). */
    record LogRecord(Log log, Hash txHash, int txIndex, int logIndex) {
    }

    /** A new main block became canonical (drives {@code newHeads}). Fires once per confirmed main block. */
    void onNewMainHead(long height, Bytes32 blockHash, long timestampSeconds);

    /**
     * A main height's EVM logs were produced ({@code removed=false}) or reverted by a reorg
     * ({@code removed=true}). Drives {@code logs}; the manager filters per-subscription.
     */
    void onLogs(long height, Bytes32 blockHash, List<LogRecord> logs, boolean removed);
}
