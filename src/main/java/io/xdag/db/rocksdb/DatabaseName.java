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

package io.xdag.db.rocksdb;

public enum DatabaseName {

    /**
     * Block index.
     */
    INDEX,

    /**
     * Block raw data.
     */
    BLOCK,

    /**
     * Time related block.
     */
    TIME,

    /**
     * Orphan block index
     */
    ORPHANIND,

    SNAPSHOT,

    ADDRESS,

    TXHISTORY,

    /**
     * EVM world state (accounts, contract code, contract storage) for the embedded EVM.
     * Keys are prefixed within this single store (see io.xdag.evm.state.EvmStateSchema):
     * 0x00|address -> account record, 0x01|codeHash -> code, 0x02|address|slot -> storage value.
     */
    EVM_STATE,

    /**
     * Signed EIP-155 RLP transaction blobs keyed by tx hash (see io.xdag.evm.tx.EvmTxStore):
     * 0x00|txHash -> raw signed RLP. Referenced from blocks via XDAG_FIELD_EVM_TX_REF.
     */
    EVM_TX,

    /**
     * Per-main-block EVM execution metadata (see io.xdag.evm.state.EvmMetaStore):
     * 0x00|mainHeight -> stateRoot|blockHash|txCount, 0x01|txHash -> receipt RLP.
     */
    EVM_META,

    /**
     * Reverse-delta undo journal for bounded historical state (sub-project C4):
     * height(8 BE) -> length-framed list of (stateKey, priorValue|tombstone) captured at that height's
     * root commit. Node-local; not consensus data. Only the most recent evm.stateHistoryWindow heights
     * are retained (see io.xdag.evm.state.EvmStateJournal).
     */
    EVM_STATE_JOURNAL
}
