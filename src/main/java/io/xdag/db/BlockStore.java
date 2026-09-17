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
package io.xdag.db;

import io.xdag.core.XdagLifecycle;
import io.xdag.core.*;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

import java.util.List;
import java.util.function.Function;

public interface BlockStore extends XdagLifecycle {

    byte SETTING_STATS = (byte) 0x10;
    byte TIME_HASH_INFO = (byte) 0x20;
    byte HASH_BLOCK_INFO = (byte) 0x30;
    byte SUMS_BLOCK_INFO = (byte) 0x40;
    byte OURS_BLOCK_INFO = (byte) 0x50;
    byte SETTING_TOP_STATUS = (byte) 0x60;
    byte SNAPSHOT_BOOT = (byte) 0x70;
    byte BLOCK_HEIGHT = (byte) 0x80;
    byte SNAPSHOT_PRESEED = (byte) 0x90;
    byte TX_HISTORY = (byte) 0xa0;
    /**
     * Node-local: height of the last {@code setMain} that ran to completion (see
     * {@link io.xdag.core.BlockchainImpl#setMain}). See {@link #getLastCompletedMain()} for exactly
     * what the value proves.
     */
    byte LAST_COMPLETED_MAIN = (byte) 0xb0;
    /**
     * Node-local: height of a {@code setMain}/{@code unSetMain} that started but has not been
     * observed to finish. See {@link #getMainInFlight()} for exactly what the value proves.
     */
    byte MAIN_IN_FLIGHT = (byte) 0xc0;
    String SUM_FILE_NAME = "sums.dat";

    void reset();

    XdagStats getXdagStatus();

    void saveXdagTopStatus(XdagTopStatus status);

    XdagTopStatus getXdagTopStatus();

    void saveBlock(Block block);

    void saveBlockInfo(BlockInfo blockInfo);

    void saveOurBlock(int index, byte[] hashlow);

    void saveTxHistoryToRocksdb(TxHistory txHistory,int id);

    List<TxHistory> getAllTxHistoryFromRocksdb();

    void deleteAllTxHistoryFromRocksdb();

    boolean hasBlock(Bytes32 hashlow);

    boolean hasBlockInfo(Bytes32 hashlow);

    List<Block> getBlocksUsedTime(long startTime, long endTime);

    List<Block> getBlocksByTime(long startTime);

    Block getBlockByHeight(long height);

    Block getBlockByHash(Bytes32 hashlow, boolean isRaw);

    Block getBlockInfoByHash(Bytes32 hashlow);

    BlockInfo getBlockInfo(Bytes32 hashlow);

    Block getRawBlockByHash(Bytes32 hashlow);

    Bytes getOurBlock(int index);

    int getKeyIndexByHash(Bytes32 hashlow);

    void removeOurBlock(byte[] hashlow);

    void fetchOurBlocks(Function<Pair<Integer, Block>, Boolean> function);

    // Snapshot Boot
    boolean isSnapshotBoot();

    void setSnapshotBoot();

    /**
     * Height of the last main block whose {@code setMain} ran to completion, or {@code -1} if the
     * marker was never written (a store created before SP0b-1). Node-local bookkeeping: never part
     * of any hash, never exported with a snapshot.
     *
     * <p><b>What it proves:</b> every INDEX write that {@code setMain} performs for heights up to
     * and including the returned value was issued before the marker itself, so on a store whose
     * INDEX column family is internally ordered the marker is never ahead of that block's own index
     * state. <b>What it does not prove:</b> it says nothing about the other stores — ADDRESS and
     * CHAIN_L1 are separate RocksDB instances with their own write-ahead logs, and a crash can land
     * between two of them. This is a progress marker, not a cross-store commit record; a boot must
     * still reconcile the stores rather than trust the marker as a transaction boundary.
     */
    long getLastCompletedMain();

    void saveLastCompletedMain(long height);

    /**
     * Height of a {@code setMain}/{@code unSetMain} that was entered but whose completion was never
     * recorded, or {@code -1} when no such call is outstanding.
     *
     * <p><b>What it proves:</b> the key is written as the first INDEX write of {@code setMain} and
     * of {@code unSetMain}, and cleared immediately after the matching {@link #saveLastCompletedMain}
     * on every normal exit. A boot that finds it present therefore knows the process died — or a
     * handler threw — somewhere inside that height's transition, and that the on-disk state for that
     * height is partial. <b>What it does not prove:</b> nothing about which of the writes actually
     * landed, and nothing about ADDRESS or CHAIN_L1, which have their own write-ahead logs. It is a
     * "something was in flight here" flag, not a rollback journal.
     */
    long getMainInFlight();

    void saveMainInFlight(long height);

    void clearMainInFlight();

    // RandomX seed
    void savePreSeed(byte[] preseed);

    byte[] getPreSeed();

    // sums.dat and sum.dat
    void saveBlockSums(Block block);

    MutableBytes getSums(String key);

    void putSums(String key, Bytes sums);

    void updateSum(String key, long sum, long size, long index);

    int loadSum(long starttime, long endtime, MutableBytes sums);

    void saveXdagStatus(XdagStats status);

}
